package com.github.highcumontoa.backtestportfolioenginejava.tests;

import com.github.highcumontoa.backtestportfolioenginejava.model.CostLot;
import com.github.highcumontoa.backtestportfolioenginejava.model.LotRealization;
import com.github.highcumontoa.backtestportfolioenginejava.service.LotService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FIFO 批次台账：部分成交拆批、跨批卖出盈亏、费税分摊、拆/合股成本守恒、分红独立、超卖拦截。
 */
class LotServiceTest {

    private static final Logger log = LoggerFactory.getLogger(LotServiceTest.class);

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    private static void eq(BigDecimal expected, BigDecimal actual, String msg) {
        assertEquals(0, expected.compareTo(actual), msg + " actual=" + actual);
    }

    @Test
    void partialFillsOpenSeparateLotsWithCommissionIncluded() {
        LotService lots = new LotService();
        // 同一大单的两次部分成交：30 股 @10.005，佣金 5；再 30 股 @10.005，佣金 5
        lots.openBuy("AAA", "USD", "FILL-1", 10L, bd("30"), bd("10.0050"), bd("5"));
        lots.openBuy("AAA", "USD", "FILL-2", 20L, bd("30"), bd("10.0050"), bd("5"));

        List<CostLot> open = lots.openLots("AAA");
        assertEquals(2, open.size(), "each partial fill is its own lot");
        // 批次成本 = 30*10.005 + 5 = 305.15；单位 = 305.15/30 = 10.17166667
        BigDecimal lot1Cost = open.get(0).unitCost().multiply(open.get(0).remainingQuantity())
                .setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal lot2Cost = open.get(1).unitCost().multiply(open.get(1).remainingQuantity())
                .setScale(2, java.math.RoundingMode.HALF_UP);
        eq(bd("305.15"), lot1Cost, "lot1 cost");
        eq(bd("305.15"), lot2Cost, "lot2 cost");
        eq(bd("610.30"), lots.openCost("AAA"), "total open cost = both fills incl commission");
        log.info("two partial fills -> two lots, openCost={}", lots.openCost("AAA"));
    }

    @Test
    void sellConsumesFifoAcrossLotsWithRealizedPnl() {
        LotService lots = new LotService();
        // 批1: 100 @10 + 佣金5 => 成本 1005
        lots.openBuy("AAA", "USD", "B1", 1L, bd("100"), bd("10.00"), bd("5"));
        // 批2: 100 @12 + 佣金5 => 成本 1205
        lots.openBuy("AAA", "USD", "B2", 2L, bd("100"), bd("12.00"), bd("5"));

        // 卖出 150 @15：先吃完批1（100），再吃批2的 50
        // gross = 2250；佣金 0.0003*2250=0.675->0.68(min 5 不触发? max(0.68,5)=5)，税=2.25
        List<LotRealization> rows = lots.closeSell("AAA", "USD", "S1", 3L,
                bd("150"), bd("15.00"), bd("5"), bd("2.25"));
        assertEquals(2, rows.size(), "one sell spanning two lots yields two realization rows");
        assertEquals("S1", rows.get(0).execId()); // execId 是卖出回报 id
        assertEquals("LOT-1", rows.get(0).lotId(), "first row consumes lot1 (FIFO)");
        assertEquals("LOT-2", rows.get(1).lotId(), "second row consumes lot2");
        // 批1 整批：成本 1005，成交额 1500
        eq(bd("100"), rows.get(0).quantity(), "row1 qty");
        eq(bd("1005.00"), rows.get(0).costBasis(), "row1 cost basis full lot1");
        eq(bd("1500.00"), rows.get(0).grossProceeds(), "row1 gross");
        // 批2 部分：50 股，成本 = 1205*50/100 = 602.50
        eq(bd("50"), rows.get(1).quantity(), "row2 qty");
        eq(bd("602.50"), rows.get(1).costBasis(), "row2 cost basis half of lot2");
        eq(bd("750.00"), rows.get(1).grossProceeds(), "row2 gross");

        // 费税合计必须分毫不差分摊到两行
        BigDecimal feeSum = rows.stream().map(LotRealization::commission)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal taxSum = rows.stream().map(LotRealization::tax)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        eq(bd("5.00"), feeSum, "commission fully allocated");
        eq(bd("2.25"), taxSum, "tax fully allocated");

        // 盈亏合计 = 2250 - 1005 - 602.50 - 5 - 2.25 = 635.25
        BigDecimal pnlSum = rows.stream().map(LotRealization::realizedPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        eq(bd("635.25"), pnlSum, "realized pnl across rows");
        eq(bd("635.25"), lots.realizedPnl("USD"), "cumulative realized pnl");

        // 剩余只有批2的 50 股，成本 1205-602.50=602.50
        List<CostLot> open = lots.openLots("AAA");
        assertEquals(1, open.size());
        eq(bd("50"), open.get(0).remainingQuantity(), "50 left in lot2");
        eq(bd("602.50"), lots.openCost("AAA"), "remaining open cost");
        log.info("FIFO sell: rows={} realized={} openCost={}", rows.size(), pnlSum, lots.openCost("AAA"));
    }

    @Test
    void splitScalesLotsAndKeepsTotalCost() {
        LotService lots = new LotService();
        lots.openBuy("AAA", "USD", "B1", 1L, bd("100"), bd("10.00"), bd("5")); // 1005
        BigDecimal before = lots.openCost("AAA");
        lots.applyRatio("AAA", bd("2")); // 1 拆 2
        List<CostLot> open = lots.openLots("AAA");
        eq(bd("200"), open.get(0).remainingQuantity(), "qty doubled");
        eq(before, lots.openCost("AAA"), "open cost unchanged by split");
        // 单位成本 1005/200 = 5.025
        eq(bd("5.02500000"), open.get(0).unitCost(), "unit cost halved");

        lots.applyRatio("AAA", bd("0.1")); // 10 合 1：200 -> 20
        eq(bd("20"), lots.openLots("AAA").get(0).remainingQuantity(), "qty after reverse split");
        eq(before, lots.openCost("AAA"), "open cost still unchanged after reverse split");
        log.info("split+reverse: openCost invariant={} qty=20", lots.openCost("AAA"));
    }

    @Test
    void dividendDoesNotTouchLots() {
        LotService lots = new LotService();
        lots.openBuy("AAA", "USD", "B1", 1L, bd("100"), bd("10.00"), bd("0"));
        lots.addDividend("USD", bd("50.00"));
        eq(bd("50.00"), lots.dividendIncome("USD"), "dividend recorded");
        eq(bd("100"), lots.openLots("AAA").get(0).remainingQuantity(), "qty untouched");
        eq(bd("1000.00"), lots.openCost("AAA"), "cost untouched");
        eq(bd("0.00"), lots.realizedPnl("USD"), "dividend is separate from realized pnl");
        log.info("dividend=50 independent of lots and realized pnl");
    }

    @Test
    void oversellBeyondLotsRejected() {
        LotService lots = new LotService();
        lots.openBuy("AAA", "USD", "B1", 1L, bd("10"), bd("100.00"), bd("0"));
        assertThrows(com.github.highcumontoa.backtestportfolioenginejava.exception
                .InvalidArgumentApiException.class, () ->
                lots.closeSell("AAA", "USD", "S1", 2L, bd("11"), bd("100"), bd("0"), bd("0")));
        assertEquals(0, lots.realizations("AAA").size(), "no partial booking on oversell rejection");
    }

    @Test
    void replayIsDeterministic() {
        LotService a = replay();
        LotService b = replay();
        assertEquals(a.realizedPnl("USD"), b.realizedPnl("USD"));
        assertEquals(a.openCost("AAA"), b.openCost("AAA"));
        assertEquals(a.dividendIncome("USD"), b.dividendIncome("USD"));
        assertEquals(a.openLots("AAA").get(0).unitCost(), b.openLots("AAA").get(0).unitCost());
    }

    private LotService replay() {
        LotService lots = new LotService();
        lots.openBuy("AAA", "USD", "B1", 1L, bd("100"), bd("10.00"), bd("5"));
        lots.openBuy("AAA", "USD", "B2", 2L, bd("100"), bd("12.00"), bd("5"));
        lots.closeSell("AAA", "USD", "S1", 3L, bd("150"), bd("15.00"), bd("5"), bd("2.25"));
        lots.applyRatio("AAA", bd("2"));
        lots.addDividend("USD", bd("10.00"));
        return lots;
    }
}
