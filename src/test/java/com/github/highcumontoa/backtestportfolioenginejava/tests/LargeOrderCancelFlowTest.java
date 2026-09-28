package com.github.highcumontoa.backtestportfolioenginejava.tests;

import com.github.highcumontoa.backtestportfolioenginejava.model.FxRate;
import com.github.highcumontoa.backtestportfolioenginejava.model.OrderRequest;
import com.github.highcumontoa.backtestportfolioenginejava.model.OrderStatus;
import com.github.highcumontoa.backtestportfolioenginejava.model.OrderType;
import com.github.highcumontoa.backtestportfolioenginejava.model.Quote;
import com.github.highcumontoa.backtestportfolioenginejava.model.Side;
import com.github.highcumontoa.backtestportfolioenginejava.model.lot.LotView;
import com.github.highcumontoa.backtestportfolioenginejava.model.lot.RealizedPnl;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 需求指定链路：一笔占掉账户大部分可用资金的大额买单
 * “多次部分成交（3000 + 3000）→ 卖出一部分（2000）→ 再撤掉剩余买单（4000 股）”。
 * 每个节点校验资金占用/现金余额/批次/已实现盈亏，撤单后预占必须清零且现金不多扣。
 */
class LargeOrderCancelFlowTest {

    private static final Logger log = LoggerFactory.getLogger(LargeOrderCancelFlowTest.class);

    private static void assertEq(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "expected " + expected + " but got " + actual);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Test
    void largeBuyPartialFillsThenSellThenCancelRemainingBuyReconciles() {
        EngineTestKit kit = new EngineTestKit();
        kit.portfolio.deposit("USD", new BigDecimal("100000.00"));
        kit.engine.addFxRates(List.of(new FxRate("USD", "USD", 0L, BigDecimal.ONE)));
        kit.engine.addQuotes(List.of(
                new Quote("AAA", 10L, bd("10"), bd("10"), bd("10"), false),
                new Quote("AAA", 20L, bd("10"), bd("10"), bd("10"), false),
                new Quote("AAA", 30L, bd("11"), bd("11"), bd("11"), false),
                new Quote("AAA", 40L, bd("11"), bd("11"), bd("11"), false)));
        // 只在 t=10/20 各给 3000 股流动性：9000 股大单仅成交 6000，剩余 4000 一直挂着。
        kit.engine.addLiquidity("AAA", 10L, bd("3000"));
        kit.engine.addLiquidity("AAA", 20L, bd("3000"));
        // t=30 报价已到 11：剩余限价买单（限 10）不会向上穿越而继续挂单；给足流动性只成全卖单。
        kit.engine.addLiquidity("AAA", 30L, bd("100000"));
        kit.engine.addLiquidity("AAA", 40L, bd("100000"));

        kit.engine.placeOrder(new OrderRequest("BIG", Side.BUY, OrderType.LIMIT,
                "AAA", bd("9000"), bd("10.00"), "USD"), 10L);

        kit.engine.runTo(10L, "USD");
        assertEq("30024.00", new BigDecimal("100000.00").subtract(kit.portfolio.cash("USD")));
        // 剩余 6000 的最坏成本（含滑点、佣金）继续占用
        assertEq("60048.01", kit.portfolio.reservedCash("USD"));

        kit.engine.runTo(20L, "USD");
        var big = kit.orderService.getByClientId("BIG").orElseThrow();
        assertEquals(OrderStatus.PARTIALLY_FILLED, big.getStatus());
        assertEq("6000", big.getFilledQty());
        assertEq("3000", big.remainingQty());
        // 剩余 3000 股仍占用：3000*10.005 + 9.01 = 30024.01
        assertEq("30024.01", kit.portfolio.reservedCash("USD"));
        assertEquals(2, kit.portfolio.openLots("AAA").size());

        // t=30：卖出已成交的 6000 股中的 2000 股（买单的剩余买入预占不影响卖出）
        kit.engine.placeOrder(new OrderRequest("SELL-PART", Side.SELL, OrderType.LIMIT,
                "AAA", bd("2000"), bd("11.00"), "USD"), 30L);
        kit.engine.runTo(30L, "USD");
        var sell = kit.orderService.getByClientId("SELL-PART").orElseThrow();
        assertEquals(OrderStatus.FILLED, sell.getStatus());
        List<RealizedPnl> pnls = kit.portfolio.realizedPnls("AAA");
        assertEquals(1, pnls.size());
        // 卖出价 10.9945：gross=21989，佣金 6.60，税 21.99
        assertEq("21989.00", pnls.get(0).proceeds());
        assertEq("1944.41", pnls.get(0).netPnl());
        // 买入预占仍然原样挂着，未被卖出流程误释放
        assertEq("30024.01", kit.portfolio.reservedCash("USD"));

        // t=40：撤掉剩余 4000 ... 实际剩余 3000 股买单
        kit.engine.requestCancel(big.getOrderId(), 40L);
        kit.engine.runTo(40L, "USD");
        var cancelled = kit.orderService.getByClientId("BIG").orElseThrow();
        assertEquals(OrderStatus.CANCELLED, cancelled.getStatus());
        assertEq("3000", cancelled.remainingQty());
        assertEq("0.00", kit.portfolio.reservedCash("USD"));

        // 最终批次：批次1 卖剩 1000（成本 10008），批次2 完整 3000（30024）-> 4000 股，成本 40032
        List<LotView> lots = kit.portfolio.openLots("AAA");
        assertEquals(2, lots.size());
        assertEq("1000", lots.get(0).remainingQty());
        assertEq("10008.00", lots.get(0).remainingCost());
        assertEq("3000", lots.get(1).remainingQty());
        assertEq("30024.00", lots.get(1).remainingCost());
        assertEq("40032.00", kit.portfolio.lotRemainingCost("AAA"));
        assertEq("4000", kit.portfolio.position("AAA").getQuantity());

        // 现金 = 100000 - 2*30024(买入) + (21989 - 6.60 - 21.99)(卖出净额) = 61912.41
        assertEq("61912.41", kit.portfolio.cash("USD"));
        // 对账恒等式：现金 = 入金 - 剩余批次成本 + 已实现盈亏
        BigDecimal identity = new BigDecimal("100000.00")
                .subtract(kit.portfolio.lotRemainingCost("AAA"))
                .add(kit.portfolio.realizedPnl("USD"));
        assertEq(identity.toPlainString(), kit.portfolio.cash("USD"));
        log.info("large buy->partial sells->cancel: cash=61912.41 reserved=0 lots=1000+3000 cost=40032 pnl=1944.41");
    }
}
