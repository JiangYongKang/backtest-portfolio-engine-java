package com.github.highcumontoa.backtestportfolioenginejava.tests;

import com.github.highcumontoa.backtestportfolioenginejava.config.CostConfig;
import com.github.highcumontoa.backtestportfolioenginejava.model.FxRate;
import com.github.highcumontoa.backtestportfolioenginejava.model.Quote;
import com.github.highcumontoa.backtestportfolioenginejava.model.ValuationFlag;
import com.github.highcumontoa.backtestportfolioenginejava.model.ValuationResult;
import com.github.highcumontoa.backtestportfolioenginejava.service.FxRateService;
import com.github.highcumontoa.backtestportfolioenginejava.service.MarketDataService;
import com.github.highcumontoa.backtestportfolioenginejava.service.PortfolioService;
import com.github.highcumontoa.backtestportfolioenginejava.service.ValuationService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 多币种折算：FX 缺失/过期、缺价/停牌必须显式标记，不得按 1 或旧价静默估值。 */
class ValuationFxTest {

    private static final Logger log = LoggerFactory.getLogger(ValuationFxTest.class);

    private final CostConfig cfg = CostConfig.DEFAULT; // fxMaxStale 24h

    private void hold(PortfolioService pf, String symbol, String ccy, String qty, String px) {
        pf.deposit(ccy, new BigDecimal("1000000"));
        pf.applyFill(new com.github.highcumontoa.backtestportfolioenginejava.model.Fill(
                "F-" + symbol, "O-" + symbol, symbol,
                com.github.highcumontoa.backtestportfolioenginejava.model.Side.BUY,
                new BigDecimal(qty), new BigDecimal(px), 1L,
                BigDecimal.ZERO, BigDecimal.ZERO), ccy);
    }

    private Quote quote(String symbol, long t, String last, boolean suspended) {
        BigDecimal v = last == null ? null : new BigDecimal(last);
        return new Quote(symbol, t, v, v, v, suspended);
    }

    @Test
    void missingFxIsFlaggedAndNavIncomplete() {
        MarketDataService md = new MarketDataService();
        FxRateService fx = new FxRateService();
        PortfolioService pf = new PortfolioService(cfg);
        hold(pf, "AAA", "EUR", "10", "100");
        md.ingest(quote("AAA", 10L, "100", false));
        // 不提供 EUR->USD 汇率
        ValuationService vs = new ValuationService(md, fx, pf, cfg);
        ValuationResult v = vs.value(10L, "USD");
        assertFalse(v.complete());
        assertEquals(1, v.stalePositions().size());
        assertEquals(ValuationFlag.FX_MISSING, v.positions().get(0).flag());
        assertNull(v.positions().get(0).marketValueBase(), "no silent conversion at 1");
        log.info("missing FX flagged: detail={}", v.positions().get(0).detail());
    }

    @Test
    void staleFxIsFlaggedWhenBeyondMaxAge() {
        MarketDataService md = new MarketDataService();
        FxRateService fx = new FxRateService();
        PortfolioService pf = new PortfolioService(cfg);
        hold(pf, "AAA", "EUR", "10", "100");
        md.ingest(quote("AAA", 100L, "100", false));
        // 汇率时间戳为 0，估值时点 100：年龄 100ms < 24h，仍新鲜
        fx.ingest(new FxRate("EUR", "USD", 0L, new BigDecimal("1.10")));
        ValuationService vs = new ValuationService(md, fx, pf, cfg);
        ValuationResult fresh = vs.value(100L, "USD");
        assertTrue(fresh.complete(), "100ms old fx is fresh");

        // 推进到 24h+1ms 之后 -> 过期
        md.ingest(quote("AAA", cfg.fxMaxStaleMillis() + 2L, "100", false));
        ValuationResult stale = vs.value(cfg.fxMaxStaleMillis() + 2L, "USD");
        assertFalse(stale.complete());
        assertEquals(ValuationFlag.FX_STALE, stale.positions().get(0).flag());
        assertNull(stale.positions().get(0).marketValueBase(), "must not reuse stale fx");
        log.info("stale FX flagged after max age: detail={}", stale.positions().get(0).detail());
    }

    @Test
    void suspendedPositionFlaggedNotValuedAtOldPrice() {
        MarketDataService md = new MarketDataService();
        FxRateService fx = new FxRateService();
        PortfolioService pf = new PortfolioService(cfg);
        hold(pf, "AAA", "USD", "10", "100");
        // t=10 有价格，t=20 停牌且无 last
        md.ingest(quote("AAA", 10L, "100", false));
        md.ingest(quote("AAA", 20L, null, true));
        ValuationService vs = new ValuationService(md, fx, pf, cfg);
        ValuationResult v = vs.value(20L, "USD");
        assertFalse(v.complete());
        assertEquals(ValuationFlag.PRICE_STALE_SUSPENDED, v.positions().get(0).flag());
        assertNull(v.positions().get(0).marketValueBase(), "must not value at old 100");
        log.info("suspended not valued at stale price: {}", v.positions().get(0).detail());
    }

    @Test
    void neverSeenSymbolIsPriceMissing() {
        MarketDataService md = new MarketDataService();
        FxRateService fx = new FxRateService();
        PortfolioService pf = new PortfolioService(cfg);
        hold(pf, "NEW", "USD", "1", "50");
        ValuationService vs = new ValuationService(md, fx, pf, cfg);
        ValuationResult v = vs.value(10L, "USD");
        assertFalse(v.complete());
        assertEquals(ValuationFlag.PRICE_MISSING, v.positions().get(0).flag());
        log.info("never-quoted symbol flagged PRICE_MISSING");
    }

    @Test
    void validMultiCurrencyConversionUsesFloorFx() {
        MarketDataService md = new MarketDataService();
        FxRateService fx = new FxRateService();
        PortfolioService pf = new PortfolioService(cfg);
        hold(pf, "AAA", "EUR", "10", "100");
        md.ingest(quote("AAA", 10L, "100", false));
        fx.ingest(new FxRate("EUR", "USD", 9L, new BigDecimal("1.10")));
        ValuationService vs = new ValuationService(md, fx, pf, cfg);
        ValuationResult v = vs.value(10L, "USD");
        assertTrue(v.complete());
        // 10 * 100 * 1.10 = 1100
        assertEquals(0, new BigDecimal("1100.00").compareTo(v.totalMarketValueBase()));
        log.info("multi-currency mv converted = {}", v.totalMarketValueBase());
    }
}
