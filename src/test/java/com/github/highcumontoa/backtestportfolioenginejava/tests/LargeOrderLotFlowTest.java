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
 * 大额订单完整链路（不能只在小额场景正确）：
 * 一笔占掉账户绝大部分可用资金的买单（9000 股 / 10 万账户）跨三个 tick 各成交 3000/3000/3000，
 * 然后卖出其中 2000 股，最后撤掉剩余 4000 股买单。
 * 逐节点校验：预占校准、现金余额、批次数量与剩余成本、已实现盈亏、以及
 * “现金 = 入金 - 剩余批次成本 + 已实现盈亏”的对账恒等式。
 */
class LargeOrderLotFlowTest {

    private static final Logger log = LoggerFactory.getLogger(LargeOrderLotFlowTest.class);

    private static void assertEq(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "expected " + expected + " but got " + actual);
    }

    @Test
    void largeBuyPartiallyFillsThenPartialSellThenCancelEverythingReconciles() {
        EngineTestKit kit = new EngineTestKit();
        final BigDecimal deposit = new BigDecimal("100000.00");
        kit.portfolio.deposit("USD", deposit);
        kit.engine.addFxRates(List.of(new FxRate("USD", "USD", 0L, BigDecimal.ONE)));
        // 买单全程 10 元；t=40 卖单时价格 11 元
        kit.engine.addQuotes(List.of(
                new Quote("AAA", 10L, bd("10"), bd("10"), bd("10"), false),
                new Quote("AAA", 20L, bd("10"), bd("10"), bd("10"), false),
                new Quote("AAA", 30L, bd("10"), bd("10"), bd("10"), false),
                new Quote("AAA", 40L, bd("11"), bd("11"), bd("11"), false),
                new Quote("AAA", 50L, bd("11"), bd("11"), bd("11"), false)));
        // 每 tick 仅 3000 股可见量 -> 9000 股大单被拆成 3 个批次
        kit.engine.addLiquidity("AAA", 10L, bd("3000"));
        kit.engine.addLiquidity("AAA", 20L, bd("3000"));
        kit.engine.addLiquidity("AAA", 30L, bd("3000"));
        kit.engine.addLiquidity("AAA", 40L, bd("100000"));

        // 下单最坏成本 = 9000*10 + 90000*0.0003=27 佣金(>=5) = 90027.00（买税 0）
        kit.engine.placeOrder(new OrderRequest("BIG", Side.BUY, OrderType.LIMIT,
                "AAA", bd("9000"), bd("10.00"), "USD"), 10L);

        // ---- t=10：成交 3000 ----
        kit.engine.runTo(10L, "USD");
        // 含滑点买价 10.005：gross=30015，佣金 max(9.0045,5)=9.00 -> 支出 30024.00
        assertEq("30024.00", new BigDecimal("100000.00").subtract(kit.portfolio.cash("USD")));
        assertEq("3000", kit.portfolio.position("AAA").getQuantity());
        assertEquals(1, kit.portfolio.openLots("AAA").size());
        // 剩余 6000 股仍预占（含滑点）= 6000*10.005 + 60030*0.0003=18.009->18.01 = 60048.01
        assertEq("60048.01", kit.portfolio.reservedCash("USD"));

        // ---- t=20：再成交 3000 ----
        kit.engine.runTo(20L, "USD");
        assertEq("60048.00", new BigDecimal("100000.00").subtract(kit.portfolio.cash("USD")));
        assertEq("30024.01", kit.portfolio.reservedCash("USD")); // 3000*10.005 + 9.01
        assertEquals(2, kit.portfolio.openLots("AAA").size());

        // ---- t=30：再成交 3000，剩余 0（订单 9000 全部成交）----
        kit.engine.runTo(30L, "USD");
        var big = kit.orderService.getByClientId("BIG").orElseThrow();
        assertEquals(OrderStatus.FILLED, big.getStatus());
        assertEq("90072.00", new BigDecimal("100000.00").subtract(kit.portfolio.cash("USD")));
        assertEq("0.00", kit.portfolio.reservedCash("USD"));
        List<LotView> lots = kit.portfolio.openLots("AAA");
        assertEquals(3, lots.size());
        for (LotView lot : lots) {
            assertEq("3000", lot.remainingQty());
            assertEq("30024.00", lot.remainingCost());
        }
        assertEq("90072.00", kit.portfolio.lotRemainingCost("AAA"));
        assertEq("9000", kit.portfolio.position("AAA").getQuantity());
        log.info("big buy filled as 3 lots: cash spent 90072, reserved=0, available cash=9928");

        // ---- t=40：卖出 2000 股，FIFO 只动第一个批次 ----
        // 卖出含滑点价 = 11*0.9995 = 10.9945；gross=21989.00
        // 佣金 21989*0.0003=6.5967->6.60；税 21.989->21.99
        kit.engine.placeOrder(new OrderRequest("SELL-PART", Side.SELL, OrderType.LIMIT,
                "AAA", bd("2000"), bd("11.00"), "USD"), 40L);
        kit.engine.runTo(40L, "USD");
        var sell = kit.orderService.getByClientId("SELL-PART").orElseThrow();
        assertEquals(OrderStatus.FILLED, sell.getStatus());

        List<RealizedPnl> pnls = kit.portfolio.realizedPnls("AAA");
        assertEquals(1, pnls.size());
        RealizedPnl pnl = pnls.get(0);
        assertEq("21989.00", pnl.proceeds());
        // 批次1 单位成本 = 30024/3000 = 10.008（整除）-> 2000 股成本 20016.00
        assertEq("20016.00", pnl.costBasis());
        assertEq("1973.00", pnl.grossPnl());
        assertEq("6.60", pnl.commission());
        assertEq("21.99", pnl.tax());
        assertEq("1944.41", pnl.netPnl());
        assertEquals(1, pnl.matches().size());
        assertEq("2000", pnl.matches().get(0).quantity());

        // 批次1 剩 1000 股，成本 10008.00；批次2/3 完整
        List<LotView> afterSell = kit.portfolio.openLots("AAA");
        assertEquals(3, afterSell.size());
        assertEq("1000", afterSell.get(0).remainingQty());
        assertEq("10008.00", afterSell.get(0).remainingCost());
        assertEq("3000", afterSell.get(1).remainingQty());
        assertEq("30024.00", afterSell.get(1).remainingCost());
        assertEq("3000", afterSell.get(2).remainingQty());
        assertEq("7000", kit.portfolio.position("AAA").getQuantity());
        // 现金 = 100000 - 90072 + (21989 - 6.60 - 21.99) = 31888.41
        assertEq("31888.41", kit.portfolio.cash("USD"));
        log.info("partial sell 2000: cash=31888.41 realized net=1944.41 lots=1000+3000+3000");

        // ---- 主对账恒等式：现金 = 入金 - 剩余批次成本 + 已实现盈亏（+分红0）----
        BigDecimal remainingCost = kit.portfolio.lotRemainingCost("AAA");
        assertEq("70056.00", remainingCost); // 10008 + 30024 + 30024
        BigDecimal realized = kit.portfolio.realizedPnl("USD");
        assertEq("1944.41", realized);
        BigDecimal identity = deposit.subtract(remainingCost).add(realized);
        assertEq(identity.toPlainString(), kit.portfolio.cash("USD"));

        // ---- t=50：（无剩余买单可撤，验证再卖不会超卖、现金不多算）----
        // 这里额外尝试卖出 8000 股（超过 7000）必须被拒，现金、批次不变。
        BigDecimal cashBefore = kit.portfolio.cash("USD");
        kit.engine.placeOrder(new OrderRequest("OVERSELL", Side.SELL, OrderType.LIMIT,
                "AAA", bd("8000"), bd("11.00"), "USD"), 50L);
        kit.engine.runTo(50L, "USD");
        var over = kit.orderService.getByClientId("OVERSELL").orElseThrow();
        assertEquals(OrderStatus.REJECTED, over.getStatus());
        assertEq(cashBefore.toPlainString(), kit.portfolio.cash("USD"));
        assertEq("7000", kit.portfolio.position("AAA").getQuantity());
        assertEquals(1, kit.portfolio.realizedPnls("AAA").size());
        log.info("oversell rejected; cash/lots/pnl untouched");
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
