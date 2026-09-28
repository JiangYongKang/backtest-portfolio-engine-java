package com.github.highcumontoa.backtestportfolioenginejava.tests;

import com.github.highcumontoa.backtestportfolioenginejava.model.CorporateAction;
import com.github.highcumontoa.backtestportfolioenginejava.model.CorporateActionType;
import com.github.highcumontoa.backtestportfolioenginejava.model.FxRate;
import com.github.highcumontoa.backtestportfolioenginejava.model.Fill;
import com.github.highcumontoa.backtestportfolioenginejava.model.Quote;
import com.github.highcumontoa.backtestportfolioenginejava.model.Side;
import com.github.highcumontoa.backtestportfolioenginejava.model.ValuationResult;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 估值/绩效口径下的已实现与未实现盈亏，以及现金、持仓成本、已实现盈亏之间的对账：
 * <pre>
 *   现金 = 入金 - 批次剩余成本 + 已实现盈亏 + 分红
 *   NAV  = 现金 + 持仓市值
 *   总盈亏 = 已实现 + 未实现 + 分红 = NAV - 入金
 *   未实现 = 持仓市值 - 批次剩余成本
 * </pre>
 */
class LotValuationPnlTest {

    private static final Logger log = LoggerFactory.getLogger(LotValuationPnlTest.class);

    private static void assertEq(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "expected " + expected + " but got " + actual);
    }

    @Test
    void valuationReportsRealizedAndUnrealizedPnlAndReconciles() {
        var pf = new com.github.highcumontoa.backtestportfolioenginejava.service.PortfolioService();
        pf.deposit("USD", new BigDecimal("100000"));
        // 两批：300@10 + 5 佣金 = 3005；200@10 + 5 = 2005 -> 500 股，成本 5010
        pf.applyFill(new Fill("B1", "OB1", "AAA", Side.BUY,
                new BigDecimal("300"), new BigDecimal("10.00"), 10L,
                new BigDecimal("5"), BigDecimal.ZERO), "USD");
        pf.applyFill(new Fill("B2", "OB2", "AAA", Side.BUY,
                new BigDecimal("200"), new BigDecimal("10.00"), 20L,
                new BigDecimal("5"), BigDecimal.ZERO), "USD");
        // 卖出 250 股 @12，无费税：FIFO 吃掉批次1（成本 3005）+ 批次2 0 股？250<300
        // 实际：批次1 出 250 股，单位成本 3005/300=10.01666667，成本 2504.166666750->2504.17
        pf.applyFill(new Fill("S1", "OS1", "AAA", Side.SELL,
                new BigDecimal("250"), new BigDecimal("12.00"), 30L,
                BigDecimal.ZERO, BigDecimal.ZERO), "USD");
        // 现金 = 100000 - 5010 + 3000 = 97990
        assertEq("97990.00", pf.cash("USD"));

        var market = new com.github.highcumontoa.backtestportfolioenginejava.service.MarketDataService();
        var fx = new com.github.highcumontoa.backtestportfolioenginejava.service.FxRateService();
        market.ingestAll(List.of(new Quote("AAA", 40L, bd("11"), bd("11"), bd("11"), false)));
        fx.ingestAll(List.of(new FxRate("USD", "USD", 0L, BigDecimal.ONE)));
        var valuation = new com.github.highcumontoa.backtestportfolioenginejava.service
                .ValuationService(market, fx, pf);

        ValuationResult v = valuation.value(40L, "USD");
        assertTrue(v.complete());
        // 剩余 250 股：批次1 余 50（成本 500.83）+ 批次2 200（2005）= 2505.83
        assertEq("2505.83", v.totalLotCostBase());
        assertEq("2750.00", v.totalMarketValueBase()); // 250 * 11
        assertEq("244.17", v.totalUnrealizedPnlBase());
        assertEq("495.83", v.totalRealizedPnlBase()); // 3000 - 2504.17
        assertEq("0.00", v.totalDividendBase());
        assertEq("740.00", v.totalPnlBase());       // 495.83 + 244.17
        assertEq("97990.00", v.cashTotalBase());
        assertEq("100740.00", v.navBase());          // 97990 + 2750

        // 核心对账：NAV - 入金 = 总盈亏
        assertEq("740.00", v.navBase().subtract(new BigDecimal("100000")));
        // 现金 = 入金 - 批次剩余成本 + 已实现盈亏（+ 分红0）
        BigDecimal identityCash = new BigDecimal("100000")
                .subtract(v.totalLotCostBase()).add(v.totalRealizedPnlBase());
        assertEq(identityCash.toPlainString(), v.cashTotalBase());
        log.info("valuation pnl: realized=495.83 unrealized=244.17 nav=100740 identity holds");
    }

    @Test
    void dividendIsPartOfPnlButNotOfLotCost() {
        var pf = new com.github.highcumontoa.backtestportfolioenginejava.service.PortfolioService();
        pf.deposit("USD", new BigDecimal("100000"));
        pf.applyFill(new Fill("B1", "OB1", "AAA", Side.BUY,
                new BigDecimal("100"), new BigDecimal("10.00"), 10L,
                BigDecimal.ZERO, BigDecimal.ZERO), "USD"); // 成本 1000
        var ca = new com.github.highcumontoa.backtestportfolioenginejava.service.CorporateActionService(pf);
        ca.apply(new CorporateAction("D1", CorporateActionType.CASH_DIVIDEND,
                "AAA", 20L, null, new BigDecimal("1.00"), "USD")); // 100

        var market = new com.github.highcumontoa.backtestportfolioenginejava.service.MarketDataService();
        var fx = new com.github.highcumontoa.backtestportfolioenginejava.service.FxRateService();
        market.ingestAll(List.of(new Quote("AAA", 30L, bd("10"), bd("10"), bd("10"), false)));
        fx.ingestAll(List.of(new FxRate("USD", "USD", 0L, BigDecimal.ONE)));
        var valuation = new com.github.highcumontoa.backtestportfolioenginejava.service
                .ValuationService(market, fx, pf);

        ValuationResult v = valuation.value(30L, "USD");
        assertEq("1000.00", v.totalLotCostBase());
        assertEq("100.00", v.totalDividendBase());
        assertEq("0.00", v.totalRealizedPnlBase());
        assertEq("0.00", v.totalUnrealizedPnlBase());
        assertEq("100.00", v.totalPnlBase());
        // 现金 = 100000 - 1000 + 0 + 100 = 99100；NAV = 99100 + 1000 = 100100
        assertEq("99100.00", v.cashTotalBase());
        assertEq("100100.00", v.navBase());
        assertEq("100.00", v.navBase().subtract(new BigDecimal("100000")));
        log.info("dividend: lotCost=1000 unchanged, pnl=100, nav=100100");
    }

    @Test
    void sameInputReplaysToIdenticalResult() {
        // 同一份输入构建两次，批次/盈亏快照必须逐项一致（可复现）。
        record Snap(String lots, String pnl, String cash) { }
        Snap run = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            var pf = new com.github.highcumontoa.backtestportfolioenginejava.service.PortfolioService();
            pf.deposit("USD", new BigDecimal("50000"));
            pf.applyFill(new Fill("B1", "OB1", "AAA", Side.BUY,
                    new BigDecimal("100"), new BigDecimal("10.00"), 10L,
                    new BigDecimal("5"), BigDecimal.ZERO), "USD");
            pf.applyFill(new Fill("B2", "OB2", "AAA", Side.BUY,
                    new BigDecimal("100"), new BigDecimal("11.00"), 20L,
                    new BigDecimal("5"), BigDecimal.ZERO), "USD");
            pf.applyFill(new Fill("S1", "OS1", "AAA", Side.SELL,
                    new BigDecimal("150"), new BigDecimal("12.00"), 30L,
                    new BigDecimal("5"), new BigDecimal("1")), "USD");
            String lots = pf.allOpenLots().toString();
            String pnl = pf.realizedPnls().stream()
                    .map(p -> p.netPnl().toPlainString() + "@" + p.matches()).toList().toString();
            Snap snap = new Snap(lots, pnl, pf.cash("USD").toPlainString());
            if (run == null) {
                run = snap;
            } else {
                assertEquals(run.lots(), snap.lots(), "lots must replay identically");
                assertEquals(run.pnl(), snap.pnl(), "realized pnl must replay identically");
                assertEquals(run.cash(), snap.cash(), "cash must replay identically");
            }
        }
        log.info("replay determinism: identical lots/pnl/cash across runs: {}", run.cash());
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
