package com.github.highcumontoa.backtestportfolioenginejava.tests;

import com.github.highcumontoa.backtestportfolioenginejava.model.CorporateAction;
import com.github.highcumontoa.backtestportfolioenginejava.model.CorporateActionType;
import com.github.highcumontoa.backtestportfolioenginejava.model.Fill;
import com.github.highcumontoa.backtestportfolioenginejava.model.Side;
import com.github.highcumontoa.backtestportfolioenginejava.model.lot.LotView;
import com.github.highcumontoa.backtestportfolioenginejava.service.CorporateActionService;
import com.github.highcumontoa.backtestportfolioenginejava.service.PortfolioService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 公司行为对批次的影响：
 * - 拆股/合股：批次数量等比调整，批次剩余成本总额不变，单位成本随 ratio 缩放；
 * - 现金分红：按生效时点持有数量派现计入收益，批次数量与剩余成本一律不动；
 * - 同一条公司行为重复应用不产生第二次效果（幂等）。
 */
class LotCorporateActionTest {

    private static final Logger log = LoggerFactory.getLogger(LotCorporateActionTest.class);

    private static void assertEq(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "expected " + expected + " but got " + actual);
    }

    private PortfolioService seeded() {
        // 两个批次：30 股成本 305.15、30 股成本 305.15 -> 60 股，总成本 610.30
        PortfolioService pf = new PortfolioService();
        pf.deposit("USD", new BigDecimal("100000"));
        pf.applyFill(new Fill("F1", "O1", "AAA", Side.BUY,
                new BigDecimal("30"), new BigDecimal("10.0050"), 10L,
                new BigDecimal("5.00"), BigDecimal.ZERO), "USD");
        pf.applyFill(new Fill("F2", "O1", "AAA", Side.BUY,
                new BigDecimal("30"), new BigDecimal("10.0050"), 20L,
                new BigDecimal("5.00"), BigDecimal.ZERO), "USD");
        return pf;
    }

    @Test
    void splitScalesLotQuantitiesButKeepsLotTotalCost() {
        PortfolioService pf = seeded();
        CorporateActionService ca = new CorporateActionService(pf);
        ca.apply(new CorporateAction("CA1", CorporateActionType.STOCK_SPLIT,
                "AAA", 30L, new BigDecimal("2"), null, null));

        List<LotView> lots = pf.openLots("AAA");
        assertEquals(2, lots.size(), "split does not merge or create lots");
        for (LotView lot : lots) {
            assertEq("60", lot.remainingQty());
            assertEq("305.15", lot.remainingCost());
            assertEq("5.08583333", lot.unitCost());
        }
        assertEq("610.30", pf.lotRemainingCost("AAA"));
        assertEq("120", pf.position("AAA").getQuantity());
        log.info("split 1->2: two lots x60 shares, total lot cost still 610.30");
    }

    @Test
    void reverseSplitScalesUpUnitCostAndConservesCost() {
        PortfolioService pf = seeded();
        CorporateActionService ca = new CorporateActionService(pf);
        ca.apply(new CorporateAction("CA2", CorporateActionType.REVERSE_SPLIT,
                "AAA", 30L, new BigDecimal("0.5"), null, null)); // 2 合 1

        List<LotView> lots = pf.openLots("AAA");
        assertEquals(2, lots.size());
        for (LotView lot : lots) {
            assertEq("15", lot.remainingQty());
            assertEq("305.15", lot.remainingCost());
            assertEq("20.34333333", lot.unitCost());
        }
        assertEq("610.30", pf.lotRemainingCost("AAA"));
        assertEq("30", pf.position("AAA").getQuantity());
        log.info("reverse split 2->1: two lots x15 shares, total lot cost still 610.30");
    }

    @Test
    void splitBeforePartialSellStillConservesRealizedEconomics() {
        PortfolioService pf = seeded();
        CorporateActionService ca = new CorporateActionService(pf);
        ca.apply(new CorporateAction("CA1", CorporateActionType.STOCK_SPLIT,
                "AAA", 30L, new BigDecimal("2"), null, null));
        // 拆股后 120 股；卖出 90 股（= 拆股前批次1全部60 + 批次2的30），@6.00
        // 成本：批次1 全部 305.15 + 批次2 单位成本 5.08583333*30=152.57499990 -> 152.57
        pf.applyFill(new Fill("S1", "O2", "AAA", Side.SELL,
                new BigDecimal("90"), new BigDecimal("6.00"), 40L,
                BigDecimal.ZERO, BigDecimal.ZERO), "USD");

        var pnl = pf.realizedPnls("AAA").get(0);
        assertEq("540.00", pnl.proceeds());
        assertEq("457.72", pnl.costBasis());
        assertEq("82.28", pnl.netPnl());
        // 剩余批次2：30 股，剩余成本 305.15 - 152.57 = 152.58
        List<LotView> open = pf.openLots("AAA");
        assertEquals(1, open.size());
        assertEq("30", open.get(0).remainingQty());
        assertEq("152.58", open.get(0).remainingCost());
        log.info("post-split FIFO sell 90: realized=82.28, remaining lot 30 shares cost 152.58");
    }

    @Test
    void cashDividendCreditsIncomeButLeavesLotsUntouched() {
        PortfolioService pf = seeded();
        CorporateActionService ca = new CorporateActionService(pf);
        BigDecimal cashBefore = pf.cash("USD");
        BigDecimal lotCostBefore = pf.lotRemainingCost("AAA");
        BigDecimal qtyBefore = pf.position("AAA").getQuantity();

        CorporateAction div = new CorporateAction("D1", CorporateActionType.CASH_DIVIDEND,
                "AAA", 40L, null, new BigDecimal("0.50"), "USD");
        assertTrue(ca.apply(div));
        assertEq("30.00", pf.dividends("USD")); // 60 * 0.5
        assertEquals(0, cashBefore.add(new BigDecimal("30.00")).compareTo(pf.cash("USD")));
        assertEq("60", pf.position("AAA").getQuantity());
        assertEq(lotCostBefore.toPlainString(), pf.lotRemainingCost("AAA"));
        assertEquals(2, pf.openLots("AAA").size());
        for (var lot : pf.openLots("AAA")) {
            assertEq("30", lot.remainingQty());
            assertEq("305.15", lot.remainingCost());
        }
        // 重复应用：不再派现，批次依旧不动
        assertFalse(ca.apply(div), "duplicate dividend is a no-op");
        assertEquals(0, cashBefore.add(new BigDecimal("30.00")).compareTo(pf.cash("USD")));
        assertEq("30.00", pf.dividends("USD"));
        assertEquals(2, pf.openLots("AAA").size());
        log.info("dividend paid 30.00 once; lots untouched (qty=60, cost=610.30)");
    }

    @Test
    void duplicateSplitAppliedOnce() {
        PortfolioService pf = seeded();
        CorporateActionService ca = new CorporateActionService(pf);
        CorporateAction split = new CorporateAction("CA1", CorporateActionType.STOCK_SPLIT,
                "AAA", 30L, new BigDecimal("2"), null, null);
        assertTrue(ca.apply(split));
        assertFalse(ca.apply(split));
        assertEq("120", pf.position("AAA").getQuantity());
        assertEq("610.30", pf.lotRemainingCost("AAA"));
        assertEquals(2, pf.openLots("AAA").size());
        log.info("duplicate split ignored: qty=120, lot cost=610.30 still");
    }
}
