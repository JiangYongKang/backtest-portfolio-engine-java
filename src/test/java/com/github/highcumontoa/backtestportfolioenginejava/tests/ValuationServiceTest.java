package com.github.highcumontoa.backtestportfolioenginejava.tests;

import com.github.highcumontoa.backtestportfolioenginejava.config.CostConfig;
import com.github.highcumontoa.backtestportfolioenginejava.domain.DataStatus;
import com.github.highcumontoa.backtestportfolioenginejava.domain.Fill;
import com.github.highcumontoa.backtestportfolioenginejava.domain.FxRate;
import com.github.highcumontoa.backtestportfolioenginejava.domain.MarketTick;
import com.github.highcumontoa.backtestportfolioenginejava.domain.Order;
import com.github.highcumontoa.backtestportfolioenginejava.domain.OrderType;
import com.github.highcumontoa.backtestportfolioenginejava.domain.Side;
import com.github.highcumontoa.backtestportfolioenginejava.domain.ValuationResult;
import com.github.highcumontoa.backtestportfolioenginejava.matching.CostCalculator;
import com.github.highcumontoa.backtestportfolioenginejava.matching.MatchingEngine;
import com.github.highcumontoa.backtestportfolioenginejava.market.FxRateService;
import com.github.highcumontoa.backtestportfolioenginejava.market.MarketDataService;
import com.github.highcumontoa.backtestportfolioenginejava.order.OrderRepository;
import com.github.highcumontoa.backtestportfolioenginejava.order.OrderService;
import com.github.highcumontoa.backtestportfolioenginejava.portfolio.Portfolio;
import com.github.highcumontoa.backtestportfolioenginejava.portfolio.PortfolioRegistry;
import com.github.highcumontoa.backtestportfolioenginejava.portfolio.SymbolCurrencyRegistry;
import com.github.highcumontoa.backtestportfolioenginejava.portfolio.ValuationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 估值边界：缺失/停牌/过期价格、缺失/过期汇率绝不静默按零或 1 计价。 */
class ValuationServiceTest {

    private static final Logger log = LoggerFactory.getLogger(ValuationServiceTest.class);
    private static final String ACC = "acc";
    private static final Instant T0 = Instant.parse("2026-09-01T09:00:00Z");

    private MarketDataService marketData;
    private FxRateService fx;
    private PortfolioRegistry registry;
    private OrderService orders;
    private CostCalculator costs;
    private ValuationService valuation;
    private Portfolio pf;

    @BeforeEach
    void setUp() {
        marketData = new MarketDataService(Duration.ofMinutes(5));
        fx = new FxRateService(Duration.ofDays(1));
        registry = new PortfolioRegistry();
        registry.createAccount(ACC, "USD", new BigDecimal("1000000"));
        CostConfig cfg = new CostConfig(new BigDecimal("0.0003"), new BigDecimal("5"),
                new BigDecimal("0.001"), new BigDecimal("0.0005"), true, new BigDecimal("20"));
        costs = new CostCalculator(cfg);
        orders = new OrderService(new OrderRepository(), registry,
                new MatchingEngine(marketData, costs));
        SymbolCurrencyRegistry ccyReg = new SymbolCurrencyRegistry();
        ccyReg.register("AAA", "USD");
        ccyReg.register("HHH", "HKD");
        valuation = new ValuationService(marketData, fx, ccyReg);
        pf = registry.require(ACC);
    }

    private void seedBuy(String symbol, String ccy, BigDecimal qty, BigDecimal price) {
        BigDecimal reserve = costs.buyReserveUpperBound(price, qty);
        Order buy = orders.submit(ACC, symbol, Side.BUY, OrderType.LIMIT,
                qty, price, reserve, T0, "seed-" + symbol).order();
        orders.consumeFill(new Fill("f-" + symbol, buy.getOrderId(), symbol, Side.BUY,
                qty, price, costs.commission(price, qty), BigDecimal.ZERO, T0));
    }

    @Test
    void healthyValuationConvertsMultiCurrency() {
        seedBuy("AAA", "USD", new BigDecimal("100"), new BigDecimal("50"));
        seedBuy("HHH", "HKD", new BigDecimal("100"), new BigDecimal("10"));
        // 港币持仓现金不足问题：买入用 USD 预留但标的以 HKD 计价——本简化模型以基准币结算
        marketData.ingest(MarketTick.tradable("AAA", new BigDecimal("60"), T0));
        marketData.ingest(MarketTick.tradable("HHH", new BigDecimal("12"), T0));
        fx.ingest(new FxRate("HKD", "USD", new BigDecimal("0.128"), T0));

        ValuationResult r = valuation.value(pf, T0.plusSeconds(10));
        assertTrue(r.healthy(), "all prices and fx present -> healthy");
        // AAA 市值 6000；HHH 市值 100*12*0.128 = 153.60
        BigDecimal aaa = r.lines().stream().filter(l -> l.symbol().equals("AAA"))
                .findFirst().orElseThrow().marketValueBase();
        BigDecimal hhh = r.lines().stream().filter(l -> l.symbol().equals("HHH"))
                .findFirst().orElseThrow().marketValueBase();
        assertEquals(0, aaa.compareTo(new BigDecimal("6000.00")));
        assertEquals(0, hhh.compareTo(new BigDecimal("153.60")));
        log.info("ASSERT multi-currency equity={} cashBase={}", r.totalEquity(), r.cashBase());
    }

    @Test
    void missingPriceExcludesLineAndMarksUnhealthy() {
        seedBuy("AAA", "USD", new BigDecimal("10"), new BigDecimal("50"));
        // 不接入任何行情
        ValuationResult r = valuation.value(pf, T0.plusSeconds(10));
        assertFalse(r.healthy());
        assertNull(r.totalEquity(), "no silent total when price missing");
        assertTrue(r.issues().contains(DataStatus.MISSING_PRICE));
        assertNull(r.lines().get(0).marketValueBase());
        log.info("ASSERT missing price -> issues={}, totalEquity=null", r.issues());
    }

    @Test
    void suspendedPositionExcluded() {
        seedBuy("AAA", "USD", new BigDecimal("10"), new BigDecimal("50"));
        marketData.ingest(MarketTick.tradable("AAA", new BigDecimal("60"), T0));
        marketData.ingest(MarketTick.suspended("AAA", T0.plusSeconds(60)));
        ValuationResult r = valuation.value(pf, T0.plusSeconds(60));
        assertFalse(r.healthy());
        assertTrue(r.issues().contains(DataStatus.SUSPENDED));
        log.info("ASSERT suspended -> not valued at stale price, issues={}", r.issues());
    }

    @Test
    void missingAndStaleFxAreExplicit() {
        seedBuy("HHH", "HKD", new BigDecimal("100"), new BigDecimal("10"));
        marketData.ingest(MarketTick.tradable("HHH", new BigDecimal("12"), T0));
        // 无汇率
        ValuationResult noFx = valuation.value(pf, T0.plusSeconds(10));
        assertFalse(noFx.healthy());
        assertTrue(noFx.issues().contains(DataStatus.FX_MISSING));
        log.info("ASSERT fx missing -> no implicit rate 1, issues={}", noFx.issues());

        // 汇率过期
        fx.ingest(new FxRate("HKD", "USD", new BigDecimal("0.128"), T0.minus(Duration.ofDays(2))));
        ValuationResult staleFx = valuation.value(pf, T0.plusSeconds(10));
        assertTrue(staleFx.issues().contains(DataStatus.FX_STALE));
        log.info("ASSERT stale fx not reused, issues={}", staleFx.issues());
    }
}
