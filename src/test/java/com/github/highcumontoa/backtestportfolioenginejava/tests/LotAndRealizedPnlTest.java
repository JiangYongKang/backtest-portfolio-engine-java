package com.github.highcumontoa.backtestportfolioenginejava.tests;

import com.github.highcumontoa.backtestportfolioenginejava.model.Fill;
import com.github.highcumontoa.backtestportfolioenginejava.model.FxRate;
import com.github.highcumontoa.backtestportfolioenginejava.model.OrderRequest;
import com.github.highcumontoa.backtestportfolioenginejava.model.OrderType;
import com.github.highcumontoa.backtestportfolioenginejava.model.Quote;
import com.github.highcumontoa.backtestportfolioenginejava.model.Side;
import com.github.highcumontoa.backtestportfolioenginejava.model.lot.LotMatch;
import com.github.highcumontoa.backtestportfolioenginejava.model.lot.LotView;
import com.github.highcumontoa.backtestportfolioenginejava.model.lot.RealizedPnl;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 批次与已实现盈亏：
 * 1) 一笔大单多次部分成交必须拆成多个批次，买入费用归到对应批次；
 * 2) 卖出按先进先出消耗批次，能查到每笔卖出的成本、费用与已实现盈亏；
 * 3) 批次剩余成本合计与持仓加权成本口径在 2 位精度下自洽。
 */
class LotAndRealizedPnlTest {

    private static final Logger log = LoggerFactory.getLogger(LotAndRealizedPnlTest.class);

    private static Quote quote(long t, String last) {
        BigDecimal v = new BigDecimal(last);
        return new Quote("AAA", t, v, v, v, false);
    }

    private static void assertEq(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "expected " + expected + " but got " + actual);
    }

    @Test
    void partialFillsCreateSeparateLotsWithBuyFeesAllocated() {
        // 直接对组合服务入账两笔买入回报（同一订单的两次部分成交），验证拆批与费用归属。
        var pf = new com.github.highcumontoa.backtestportfolioenginejava.service.PortfolioService();
        pf.deposit("USD", new BigDecimal("100000"));
        // 买 30@10.005，含滑点：gross=300.15，佣金最低 5 -> 批次成本 305.15
        pf.applyFill(new Fill("F1", "O1", "AAA", Side.BUY,
                new BigDecimal("30"), new BigDecimal("10.0050"), 10L,
                new BigDecimal("5.00"), BigDecimal.ZERO), "USD");
        // 买 30@10.005，佣金 5 -> 批次成本 305.15
        pf.applyFill(new Fill("F2", "O1", "AAA", Side.BUY,
                new BigDecimal("30"), new BigDecimal("10.0050"), 20L,
                new BigDecimal("5.00"), BigDecimal.ZERO), "USD");

        List<LotView> lots = pf.openLots("AAA");
        assertEquals(2, lots.size(), "each partial fill is an independent lot");
        assertEq("30", lots.get(0).remainingQty());
        assertEq("305.15", lots.get(0).remainingCost());
        assertEq("30", lots.get(1).remainingQty());
        assertEq("305.15", lots.get(1).remainingCost());
        assertEquals("F1", lots.get(0).execId());
        assertEquals("F2", lots.get(1).execId());
        assertEq("610.30", pf.lotRemainingCost("AAA"));
        assertEq("60", pf.position("AAA").getQuantity());
        log.info("two partial fills -> two lots, each cost 305.15; total remainingCost=610.30");
    }

    @Test
    void sellConsumesLotsFifoAndReportsRealizedPnlWithFeesAndTax() {
        EngineTestKit kit = new EngineTestKit();
        kit.portfolio.deposit("USD", new BigDecimal("100000"));
        kit.engine.addFxRates(List.of(new FxRate("USD", "USD", 0L, BigDecimal.ONE)));
        kit.engine.addQuotes(List.of(quote(10L, "10"), quote(20L, "10"), quote(30L, "12")));
        // 限制每 tick 30 股可见量，迫使 100 股买单先成交两个 30 股批次（剩余撤掉）。
        kit.engine.addLiquidity("AAA", 10L, new BigDecimal("30"));
        kit.engine.addLiquidity("AAA", 20L, new BigDecimal("30"));

        kit.engine.placeOrder(new OrderRequest("B1", Side.BUY, OrderType.LIMIT,
                "AAA", new BigDecimal("100"), new BigDecimal("12.00"), "USD"), 10L);
        kit.engine.runTo(20L, "USD");
        var buy = kit.orderService.getByClientId("B1").orElseThrow();
        assertEq("60", buy.getFilledQty());

        // 撤掉剩余 40 股：只保留两个批次。
        kit.engine.requestCancel(buy.getOrderId(), 25L);
        kit.engine.runTo(30L, "USD");

        List<LotView> before = kit.portfolio.openLots("AAA");
        assertEquals(2, before.size());
        // 批次1：30 股，成本 30*10.005 + 5 = 305.15；批次2 同。
        assertEq("305.15", before.get(0).remainingCost());
        assertEq("305.15", before.get(1).remainingCost());

        // 卖出 40 股 @12：卖出含滑点价 = 12*0.9995 = 11.9940
        // gross = 40*11.994 = 479.76；佣金 max(479.76*0.0003=0.1439, 5)=5；税=0.47976->0.48
        var sellFill = new Fill("S1", "O2", "AAA", Side.SELL,
                new BigDecimal("40"), new BigDecimal("11.9940"), 30L,
                new BigDecimal("5.00"), new BigDecimal("0.48"));
        kit.portfolio.applyFill(sellFill, "USD");

        List<RealizedPnl> pnls = kit.portfolio.realizedPnls("AAA");
        assertEquals(1, pnls.size());
        RealizedPnl pnl = pnls.get(0);
        assertEq("479.76", pnl.proceeds());
        // FIFO：批次1 全部 30 股（成本 305.15）+ 批次2 取 10 股
        // 批次2 单位成本 = 305.15/30 = 10.17166667（8位），10 股成本 = 101.716666670 -> 101.72
        assertEq("406.87", pnl.costBasis());
        assertEq("72.89", pnl.grossPnl());
        assertEq("67.41", pnl.netPnl()); // 72.89 - 5 - 0.48
        assertEq("5.00", pnl.commission());
        assertEq("0.48", pnl.tax());

        List<LotMatch> matches = pnl.matches();
        assertEquals(2, matches.size());
        assertEquals(before.get(0).lotId(), matches.get(0).lotId());
        assertEq("30", matches.get(0).quantity());
        assertEq("305.15", matches.get(0).costBasis());
        assertEq("10", matches.get(1).quantity());
        assertEq("101.72", matches.get(1).costBasis());
        // 两条明细毛收入合计恰为成交额（尾差归最后一条）。
        BigDecimal matchProceeds = matches.stream().map(LotMatch::proceeds)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEq("479.76", matchProceeds);

        // 批次1 已消失，批次2 剩 20 股；剩余成本 = 305.15 - 101.72 = 203.43
        List<LotView> after = kit.portfolio.openLots("AAA");
        assertEquals(1, after.size());
        assertEq("20", after.get(0).remainingQty());
        assertEq("203.43", after.get(0).remainingCost());
        assertEq("20", kit.portfolio.position("AAA").getQuantity());
        log.info("FIFO sell 40: realized gross=72.89 net=67.41; remaining lot qty=20 cost=203.43");
    }

    @Test
    void sellingWholeLotConsumesFullCostWithoutRoundingLeak() {
        // 单位成本除不尽时，整批卖空必须带走全部剩余成本，不得留下分币尾差。
        var pf = new com.github.highcumontoa.backtestportfolioenginejava.service.PortfolioService();
        pf.deposit("USD", new BigDecimal("100000"));
        // 100 股，成本 1000.03 -> 单位成本 10.0003，能除尽；改用 100 股 1000.01 制造除不尽
        pf.applyFill(new Fill("F1", "O1", "AAA", Side.BUY,
                new BigDecimal("30"), new BigDecimal("10.00"), 10L,
                new BigDecimal("0.01"), BigDecimal.ZERO), "USD"); // 成本 300.01
        List<LotView> lots = pf.openLots("AAA");
        assertEq("10.00033333", lots.get(0).unitCost());

        // 先卖 10 股：折算成本 = 10.00033333*10 = 100.0033333 -> 100.00
        pf.applyFill(new Fill("S1", "O2", "AAA", Side.SELL,
                new BigDecimal("10"), new BigDecimal("11.00"), 20L,
                BigDecimal.ZERO, BigDecimal.ZERO), "USD");
        assertEq("100.00", pf.realizedPnls("AAA").get(0).costBasis());
        // 再卖 20 股整批清空：剩余成本 200.01 必须全部带走
        pf.applyFill(new Fill("S2", "O3", "AAA", Side.SELL,
                new BigDecimal("20"), new BigDecimal("11.00"), 30L,
                BigDecimal.ZERO, BigDecimal.ZERO), "USD");
        assertEq("200.01", pf.realizedPnls("AAA").get(1).costBasis());
        assertTrue(pf.openLots("AAA").isEmpty(), "lot fully consumed and removed");
        assertEq("300.01", pf.realizedPnls("AAA").stream()
                .map(RealizedPnl::costBasis).reduce(BigDecimal.ZERO, BigDecimal::add));
        log.info("whole-lot close carries full residual cost 200.01, no rounding leak");
    }
}
