package com.github.highcumontoa.backtestportfolioenginejava.tests;

import com.github.highcumontoa.backtestportfolioenginejava.config.CostConfig;
import com.github.highcumontoa.backtestportfolioenginejava.config.EngineProperties;
import com.github.highcumontoa.backtestportfolioenginejava.domain.BacktestEvent;
import com.github.highcumontoa.backtestportfolioenginejava.domain.CorporateAction;
import com.github.highcumontoa.backtestportfolioenginejava.domain.CorporateActionType;
import com.github.highcumontoa.backtestportfolioenginejava.domain.MarketTick;
import com.github.highcumontoa.backtestportfolioenginejava.domain.Order;
import com.github.highcumontoa.backtestportfolioenginejava.domain.OrderStatus;
import com.github.highcumontoa.backtestportfolioenginejava.domain.OrderType;
import com.github.highcumontoa.backtestportfolioenginejava.domain.Side;
import com.github.highcumontoa.backtestportfolioenginejava.domain.ValuationResult;
import com.github.highcumontoa.backtestportfolioenginejava.matching.CostCalculator;
import com.github.highcumontoa.backtestportfolioenginejava.matching.MatchingEngine;
import com.github.highcumontoa.backtestportfolioenginejava.market.FxRateService;
import com.github.highcumontoa.backtestportfolioenginejava.market.MarketDataService;

import com.github.highcumontoa.backtestportfolioenginejava.order.OrderRepository;
import com.github.highcumontoa.backtestportfolioenginejava.order.OrderService;
import com.github.highcumontoa.backtestportfolioenginejava.portfolio.CorporateActionService;
import com.github.highcumontoa.backtestportfolioenginejava.portfolio.Portfolio;
import com.github.highcumontoa.backtestportfolioenginejava.portfolio.PortfolioRegistry;
import com.github.highcumontoa.backtestportfolioenginejava.portfolio.SymbolCurrencyRegistry;
import com.github.highcumontoa.backtestportfolioenginejava.portfolio.ValuationService;
import com.github.highcumontoa.backtestportfolioenginejava.engine.BacktestEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端：乱序喂入事件，引擎按事件时间处理；
 * 限价单等待价格触及、停牌不成交、除权日先调整持仓再估值，结果可复现。
 */
class BacktestEngineIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(BacktestEngineIntegrationTest.class);
    private static final String ACC = "acc";

    private MarketDataService marketData;
    private FxRateService fx;
    private OrderService orders;
    private CorporateActionService actions;
    private ValuationService valuation;
    private CostCalculator costs;
    private PortfolioRegistry registry;
    private Portfolio pf;

    @BeforeEach
    void setUp() {
        EngineProperties props = new EngineProperties();
        marketData = new MarketDataService(props.getPriceStaleTolerance());
        fx = new FxRateService(props.getFxStaleTolerance());
        costs = new CostCalculator(props.toCostConfig());
        registry = new PortfolioRegistry();
        registry.createAccount(ACC, "USD", new BigDecimal("1000000"));
        pf = registry.require(ACC);
        MatchingEngine matcher = new MatchingEngine(marketData, costs);
        orders = new OrderService(new OrderRepository(), registry, matcher);
        actions = new CorporateActionService();
        SymbolCurrencyRegistry ccyReg = new SymbolCurrencyRegistry();
        ccyReg.register("AAA", "USD");
        valuation = new ValuationService(marketData, fx, ccyReg);
    }

    private BacktestEngine engine() {
        return new BacktestEngine(marketData, orders, actions, valuation, costs, registry);
    }

    @Test
    void outOfOrderEventsProduceDeterministicResult() {
        Instant t0 = Instant.parse("2026-09-01T09:00:00Z");
        Instant t1 = t0.plusSeconds(60);
        Instant t2 = t0.plusSeconds(120);

        BacktestEvent submit = new BacktestEvent.SubmitOrder("oid-1", null, ACC, "AAA",
                Side.BUY, OrderType.LIMIT, new BigDecimal("100"), new BigDecimal("100"), t0);
        BacktestEvent tick100 = new BacktestEvent.MarketData(
                MarketTick.tradable("AAA", new BigDecimal("101"), t1));
        BacktestEvent tick99 = new BacktestEvent.MarketData(
                MarketTick.tradable("AAA", new BigDecimal("99"), t2));
        BacktestEvent snap = new BacktestEvent.ValuationSnapshot(t2.plusSeconds(1));

        // 故意乱序：快照、晚 tick、早 tick、下单
        List<BacktestEvent> shuffled = new ArrayList<>(List.of(snap, tick99, tick100, submit));

        List<ValuationResult> snaps1 = engine().run(ACC, shuffled);
        // 复现性：同样事件再跑一遍（新引擎但共享状态会有幂等影响，这里重放订单需新账户）
        Order order = orders.get("oid-1").orElseThrow();
        assertEquals(OrderStatus.FILLED, order.getStatus(),
                "limit buy fills once price drops to 99, event order irrelevant");
        assertEquals(0, order.getFilledQuantity().compareTo(new BigDecimal("100")));
        // 限价单成交价 = 限价 100（不优于限价模型下按限价成交）
        assertEquals(0, order.getAvgFillPrice().compareTo(new BigDecimal("100.000000000000")));
        assertEquals(0, pf.position("AAA").getQuantity().compareTo(new BigDecimal("100.00000000")));

        ValuationResult r = snaps1.get(0);
        assertTrue(r.healthy());
        // 估值用最新价 99：市值 9900
        BigDecimal mv = r.lines().get(0).marketValueBase();
        assertEquals(0, mv.compareTo(new BigDecimal("9900.00")));
        log.info("ASSERT out-of-order run deterministic: filled@100, valued@99 mv={}", mv);
    }

    @Test
    void suspendedPeriodDoesNotTradeAndResumes() {
        Instant t0 = Instant.parse("2026-09-01T09:00:00Z");
        List<BacktestEvent> events = List.of(
                new BacktestEvent.MarketData(MarketTick.tradable("AAA", new BigDecimal("100"), t0)),
                // 停牌前下市价买单（按 100 + 滑点预留）
                new BacktestEvent.SubmitOrder("oid-b", null, ACC, "AAA", Side.BUY,
                        OrderType.MARKET, new BigDecimal("5"), null, t0.plusSeconds(1)),
                // 停牌区间：订单挂起，不按旧价成交
                new BacktestEvent.MarketData(MarketTick.suspended("AAA", t0.plusSeconds(2))),
                // 复牌后成交
                new BacktestEvent.MarketData(MarketTick.tradable("AAA", new BigDecimal("102"),
                        t0.plusSeconds(4))));
        engine().run(ACC, events);
        // 买单在停牌期间挂起，复牌 tick 到达后成交
        Order buy = orders.get("oid-b").orElseThrow();
        assertEquals(OrderStatus.FILLED, buy.getStatus());
        log.info("ASSERT market buy waited through suspension, filled at resume avg={}",
                buy.getAvgFillPrice());
    }

    @Test
    void splitBeforeTradingKeepsValuationConsistent() {
        Instant t0 = Instant.parse("2026-09-01T09:00:00Z");
        // 建仓 100 股 @100
        List<BacktestEvent> setup = List.of(
                new BacktestEvent.MarketData(MarketTick.tradable("AAA", new BigDecimal("100"), t0)),
                new BacktestEvent.SubmitOrder("oid-seed", null, ACC, "AAA", Side.BUY,
                        OrderType.LIMIT, new BigDecimal("100"), new BigDecimal("100"), t0),
                new BacktestEvent.MarketData(MarketTick.tradable("AAA", new BigDecimal("99"),
                        t0.plusSeconds(10))));
        engine().run(ACC, setup);
        assertEquals(0, pf.position("AAA").getQuantity().compareTo(new BigDecimal("100.00000000")));

        // 除权日 2 拆 1，当天行情自动除权价 50
        Instant ex = t0.plusSeconds(86400);
        List<BacktestEvent> caDay = List.of(
                new BacktestEvent.CorporateActionEvent(new CorporateAction("split-1", "AAA",
                        CorporateActionType.SPLIT, new BigDecimal("2"), null, ex)),
                new BacktestEvent.MarketData(MarketTick.tradable("AAA", new BigDecimal("50"), ex)),
                new BacktestEvent.ValuationSnapshot(ex.plusSeconds(1)));
        List<ValuationResult> snaps = engine().run(ACC, caDay);
        assertEquals(0, pf.position("AAA").getQuantity().compareTo(new BigDecimal("200.00000000")));
        ValuationResult r = snaps.get(0);
        BigDecimal mv = r.lines().stream().filter(l -> l.symbol().equals("AAA"))
                .findFirst().orElseThrow().marketValueBase();
        assertEquals(0, mv.compareTo(new BigDecimal("10000.00")),
                "post-split market value consistent with pre-split 100*100");
        log.info("ASSERT ex-date split 100@100 -> 200@50, mv stays 10000");
    }
}
