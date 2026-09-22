package com.github.highcumontoa.backtestportfolioenginejava.tests;

import com.github.highcumontoa.backtestportfolioenginejava.config.CostConfig;
import com.github.highcumontoa.backtestportfolioenginejava.domain.CorporateAction;
import com.github.highcumontoa.backtestportfolioenginejava.domain.CorporateActionType;
import com.github.highcumontoa.backtestportfolioenginejava.domain.Fill;
import com.github.highcumontoa.backtestportfolioenginejava.domain.Order;
import com.github.highcumontoa.backtestportfolioenginejava.domain.OrderType;
import com.github.highcumontoa.backtestportfolioenginejava.domain.Position;
import com.github.highcumontoa.backtestportfolioenginejava.domain.Side;
import com.github.highcumontoa.backtestportfolioenginejava.matching.CostCalculator;
import com.github.highcumontoa.backtestportfolioenginejava.matching.MatchingEngine;
import com.github.highcumontoa.backtestportfolioenginejava.market.MarketDataService;
import com.github.highcumontoa.backtestportfolioenginejava.order.OrderRepository;
import com.github.highcumontoa.backtestportfolioenginejava.order.OrderService;
import com.github.highcumontoa.backtestportfolioenginejava.portfolio.CorporateActionService;
import com.github.highcumontoa.backtestportfolioenginejava.portfolio.Portfolio;
import com.github.highcumontoa.backtestportfolioenginejava.portfolio.PortfolioRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 公司行为：拆股/合股的数量与成本基准、现金分红、重复应用幂等。 */
class CorporateActionServiceTest {

    private static final Logger log = LoggerFactory.getLogger(CorporateActionServiceTest.class);
    private static final CostConfig COST = new CostConfig(
            new BigDecimal("0.0003"), new BigDecimal("5.00"),
            new BigDecimal("0.001"), new BigDecimal("0.0005"), true,
            new BigDecimal("20.00"));

    private PortfolioRegistry registry;
    private OrderService orders;
    private CostCalculator costs;
    private CorporateActionService actions;
    private Portfolio pf;
    private static final String ACC = "acc";
    private static final Instant T0 = Instant.parse("2026-09-01T09:00:00Z");

    @BeforeEach
    void setUp() {
        registry = new PortfolioRegistry();
        registry.createAccount(ACC, "USD", new BigDecimal("1000000"));
        costs = new CostCalculator(COST);
        MatchingEngine engine = new MatchingEngine(
                new MarketDataService(Duration.ofMinutes(5)), costs);
        orders = new OrderService(new OrderRepository(), registry, engine);
        actions = new CorporateActionService();
        pf = registry.require(ACC);
    }

    private void seedHolding(String symbol, BigDecimal qty, BigDecimal price) {
        BigDecimal reserve = costs.buyReserveUpperBound(price, qty);
        Order buy = orders.submit(ACC, symbol, Side.BUY, OrderType.LIMIT,
                qty, price, reserve, T0, "seed-" + symbol).order();
        BigDecimal commission = costs.commission(price, qty);
        orders.consumeFill(new Fill("seed-fill-" + symbol, buy.getOrderId(), symbol, Side.BUY,
                qty, price, commission, BigDecimal.ZERO, T0));
    }

    @Test
    void splitDoublesQuantityAndKeepsCostBasis() {
        seedHolding("AAA", new BigDecimal("100"), new BigDecimal("50"));
        Position pos = pf.position("AAA");
        BigDecimal costBefore = pos.getCostBasis();
        BigDecimal avgBefore = pos.avgCost();

        CorporateAction split = new CorporateAction("ca-1", "AAA",
                CorporateActionType.SPLIT, new BigDecimal("2"), null,
                T0.plusSeconds(10));
        assertTrue(actions.apply(split, pf));
        assertEquals(0, pos.getQuantity().compareTo(new BigDecimal("200.00000000")));
        assertEquals(0, pos.getCostBasis().compareTo(costBefore), "cost basis unchanged by split");
        assertEquals(0, pos.avgCost().compareTo(avgBefore.divide(new BigDecimal("2"),
                12, java.math.RoundingMode.HALF_UP)), 0, "avg cost halved");
        log.info("ASSERT split qty 100->200 costBasis stays {} avg {}->{}",
                costBefore, avgBefore, pos.avgCost());

        // 幂等：重复应用结果不变
        assertFalse(actions.apply(split, pf));
        assertEquals(0, pos.getQuantity().compareTo(new BigDecimal("200.00000000")));
        assertEquals(0, pos.getCostBasis().compareTo(costBefore));
    }

    @Test
    void reverseSplitConservesQuantityRatio() {
        seedHolding("BBB", new BigDecimal("100"), new BigDecimal("20"));
        Position pos = pf.position("BBB");
        BigDecimal costBefore = pos.getCostBasis();
        CorporateAction rev = new CorporateAction("ca-2", "BBB",
                CorporateActionType.REVERSE_SPLIT, new BigDecimal("10"), null,
                T0.plusSeconds(10));
        actions.apply(rev, pf);
        assertEquals(0, pos.getQuantity().compareTo(new BigDecimal("10.00000000")));
        assertEquals(0, pos.getCostBasis().compareTo(costBefore), "cost basis unchanged");
        // 平均成本变为 10 倍：成本基准 2005（含最低佣金 5），10 股 -> 200.50
        BigDecimal expectedAvg = new BigDecimal("200.50");
        assertEquals(0, pos.avgCost().setScale(2, java.math.RoundingMode.HALF_UP)
                .compareTo(expectedAvg));
        log.info("ASSERT reverse split 100->10 avg cost ~200, costBasis={}", costBefore);
        assertFalse(actions.apply(rev, pf));
    }

    @Test
    void cashDividendCreditsCashAndKeepsPosition() {
        seedHolding("CCC", new BigDecimal("1000"), new BigDecimal("10"));
        BigDecimal cashBefore = pf.cash("USD");
        Position pos = pf.position("CCC");
        BigDecimal qtyBefore = pos.getQuantity();
        BigDecimal costBefore = pos.getCostBasis();

        CorporateAction div = new CorporateAction("ca-3", "CCC",
                CorporateActionType.CASH_DIVIDEND, null, new BigDecimal("0.50"),
                T0.plusSeconds(10));
        actions.apply(div, pf);
        // 分红 = 1000 * 0.50 = 500
        assertEquals(0, pf.cash("USD").compareTo(cashBefore.add(new BigDecimal("500.00"))));
        assertEquals(0, pos.getQuantity().compareTo(qtyBefore));
        assertEquals(0, pos.getCostBasis().compareTo(costBefore));
        log.info("ASSERT dividend +500 cash {}->{}, holding unchanged",
                cashBefore, pf.cash("USD"));

        // 重复分红不得重复入账
        assertFalse(actions.apply(div, pf));
        assertEquals(0, pf.cash("USD").compareTo(cashBefore.add(new BigDecimal("500.00"))));
    }

    @Test
    void actionOnNoPositionIsNoOpAndIdempotent() {
        CorporateAction split = new CorporateAction("ca-4", "ZZZ",
                CorporateActionType.SPLIT, new BigDecimal("2"), null, T0);
        assertTrue(actions.apply(split, pf), "first application recorded");
        assertFalse(actions.apply(split, pf), "duplicate still idempotent");
    }
}
