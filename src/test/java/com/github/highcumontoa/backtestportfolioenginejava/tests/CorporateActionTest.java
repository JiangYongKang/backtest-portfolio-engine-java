package com.github.highcumontoa.backtestportfolioenginejava.tests;

import com.github.highcumontoa.backtestportfolioenginejava.model.CorporateAction;
import com.github.highcumontoa.backtestportfolioenginejava.model.CorporateActionType;
import com.github.highcumontoa.backtestportfolioenginejava.model.FxRate;
import com.github.highcumontoa.backtestportfolioenginejava.model.Quote;
import com.github.highcumontoa.backtestportfolioenginejava.model.ValuationResult;
import com.github.highcumontoa.backtestportfolioenginejava.service.CorporateActionService;
import com.github.highcumontoa.backtestportfolioenginejava.service.PortfolioService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 公司行为：拆/合股数量与成本守恒、分红派现、重复应用幂等、除权前后成本口径连续。 */
class CorporateActionTest {

    private static final Logger log = LoggerFactory.getLogger(CorporateActionTest.class);

    private void seedPosition(PortfolioService pf) {
        // 直接通过内部成交建立 100 股、成本 10 的持仓
        pf.deposit("USD", new BigDecimal("100000"));
        var fill = new com.github.highcumontoa.backtestportfolioenginejava.model.Fill(
                "F0", "O0", "AAA",
                com.github.highcumontoa.backtestportfolioenginejava.model.Side.BUY,
                new BigDecimal("100"), new BigDecimal("10.00"), 1L,
                BigDecimal.ZERO, BigDecimal.ZERO);
        pf.applyFill(fill, "USD");
    }

    @Test
    void splitIsIdempotentAndKeepsTotalCost() {
        PortfolioService pf = new PortfolioService();
        seedPosition(pf);
        CorporateActionService ca = new CorporateActionService(pf);
        CorporateAction split = new CorporateAction("CA1", CorporateActionType.STOCK_SPLIT,
                "AAA", 10L, new BigDecimal("2"), null, null);
        assertTrue(ca.apply(split));
        var p = pf.position("AAA");
        assertEquals(0, new BigDecimal("200").compareTo(p.getQuantity()));
        BigDecimal costAfterFirst = p.totalCost();
        assertEquals(0, new BigDecimal("1000.0000000000").compareTo(costAfterFirst));

        // 重复应用：无副作用
        assertFalse(ca.apply(split), "duplicate corporate action is a no-op");
        assertTrue(ca.isApplied("CA1"));
        assertEquals(0, new BigDecimal("200").compareTo(pf.position("AAA").getQuantity()));
        assertEquals(0, costAfterFirst.compareTo(pf.position("AAA").totalCost()));
        log.info("split idempotent: qty=200 totalCost unchanged={}", costAfterFirst);
    }

    @Test
    void reverseSplitScalesUpUnitCost() {
        PortfolioService pf = new PortfolioService();
        seedPosition(pf);
        CorporateActionService ca = new CorporateActionService(pf);
        ca.apply(new CorporateAction("CA2", CorporateActionType.REVERSE_SPLIT,
                "AAA", 10L, new BigDecimal("0.2"), null, null)); // 5 合 1
        var p = pf.position("AAA");
        assertEquals(0, new BigDecimal("20.00000000").compareTo(p.getQuantity()));
        assertEquals(0, new BigDecimal("1000.0000000000").compareTo(p.totalCost()));
        assertEquals(0, new BigDecimal("50.00000000").compareTo(p.getAvgCost()));
        log.info("reverse split qty=20 avgCost=50 totalCost=1000");
    }

    @Test
    void cashDividendAddsCashOnceOnRepeatedApply() {
        PortfolioService pf = new PortfolioService();
        seedPosition(pf);
        CorporateActionService ca = new CorporateActionService(pf);
        BigDecimal cashBefore = pf.cash("USD");
        CorporateAction div = new CorporateAction("D1", CorporateActionType.CASH_DIVIDEND,
                "AAA", 20L, null, new BigDecimal("0.50"), "USD");
        assertTrue(ca.apply(div));
        // 100 * 0.5 = 50
        assertEquals(0, cashBefore.add(new BigDecimal("50.00")).compareTo(pf.cash("USD")));
        assertEquals(0, new BigDecimal("100").compareTo(pf.position("AAA").getQuantity()),
                "dividend does not change share quantity");
        assertFalse(ca.apply(div), "repeated dividend pays only once");
        assertEquals(0, cashBefore.add(new BigDecimal("50.00")).compareTo(pf.cash("USD")),
                "no double dividend");
        log.info("dividend paid once=50.00, repeated apply ignored");
    }

    @Test
    void valuationConsistentAroundEffectiveDate() {
        // 通过完整引擎：拆股前(t=5)后(t=15)用各自行情，成本口径估值保持连续
        EngineTestKit kit = new EngineTestKit();
        seedPosition(kit.portfolio);
        kit.engine.addFxRates(List.of(new FxRate("USD", "USD", 0L, BigDecimal.ONE)));
        // 价格也按 1 拆 2 调整：t=5 last=10, t=15 last=5
        BigDecimal a = new BigDecimal("10"), b = new BigDecimal("5");
        kit.engine.addQuotes(List.of(
                new Quote("AAA", 5L, a, a, a, false),
                new Quote("AAA", 15L, b, b, b, false)));
        kit.engine.addCorporateActions(List.of(
                new CorporateAction("CAX", CorporateActionType.STOCK_SPLIT,
                        "AAA", 10L, new BigDecimal("2"), null, null)));

        ValuationResult before = kit.engine.runTo(5L, "USD");
        assertEquals(0, new BigDecimal("1000.00").compareTo(before.totalMarketValueBase()),
                "pre-split mv 100*10");
        ValuationResult after = kit.engine.runTo(15L, "USD");
        // 200 * 5 = 1000，市值不因公司行为跳变
        assertEquals(0, new BigDecimal("1000.00").compareTo(after.totalMarketValueBase()),
                "post-split mv 200*5 must equal pre-split economics");
        assertEquals(0, new BigDecimal("200").compareTo(kit.portfolio.position("AAA").getQuantity()));
        log.info("valuation continuous around split: mv before=1000 after=1000");
    }
}
