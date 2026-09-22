package com.github.highcumontoa.backtestportfolioenginejava.tests;

import com.github.highcumontoa.backtestportfolioenginejava.config.CostConfig;
import com.github.highcumontoa.backtestportfolioenginejava.domain.Fill;
import com.github.highcumontoa.backtestportfolioenginejava.domain.Order;
import com.github.highcumontoa.backtestportfolioenginejava.domain.OrderStatus;
import com.github.highcumontoa.backtestportfolioenginejava.domain.OrderType;
import com.github.highcumontoa.backtestportfolioenginejava.domain.Position;
import com.github.highcumontoa.backtestportfolioenginejava.domain.Side;
import com.github.highcumontoa.backtestportfolioenginejava.error.StateConflictException;
import com.github.highcumontoa.backtestportfolioenginejava.matching.CostCalculator;
import com.github.highcumontoa.backtestportfolioenginejava.matching.MatchingEngine;
import com.github.highcumontoa.backtestportfolioenginejava.market.MarketDataService;
import com.github.highcumontoa.backtestportfolioenginejava.order.OrderRepository;
import com.github.highcumontoa.backtestportfolioenginejava.order.OrderService;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订单生命周期与资金/持仓守恒：
 * 部分成交、撤单释放、成交后拒撤、超量成交拒绝、资金不漏扣/不重复扣。
 */
class OrderLifecycleConservationTest {

    private static final Logger log = LoggerFactory.getLogger(OrderLifecycleConservationTest.class);

    private static final CostConfig COST = new CostConfig(
            new BigDecimal("0.0003"), new BigDecimal("5.00"),
            new BigDecimal("0.001"), new BigDecimal("0.0005"), true, new BigDecimal("20.00"));
    private static final String ACC = "acc-1";
    private static final BigDecimal INITIAL_CASH = new BigDecimal("100000.00");

    private MarketDataService marketData;
    private CostCalculator costs;
    private PortfolioRegistry registry;
    private OrderService orders;

    @BeforeEach
    void setUp() {
        marketData = new MarketDataService(Duration.ofMinutes(5));
        costs = new CostCalculator(COST);
        registry = new PortfolioRegistry();
        registry.createAccount(ACC, "USD", INITIAL_CASH);
        MatchingEngine engine = new MatchingEngine(marketData, costs);
        orders = new OrderService(new OrderRepository(), registry, engine);
    }

    @Test
    void partialBuyFillsConserveCashAndCostBasis() {
        Instant t0 = Instant.parse("2026-09-01T09:00:00Z");
        BigDecimal qty = new BigDecimal("100");
        BigDecimal limit = new BigDecimal("100.00");
        BigDecimal reserve = costs.buyReserveUpperBound(limit, qty); // price, qty
        OrderService.SubmitResult sr = orders.submit(ACC, "AAA", Side.BUY, OrderType.LIMIT,
                qty, limit, reserve, t0, "key-1");
        assertTrue(sr.created());
        Order order = sr.order();

        Portfolio pf = registry.require(ACC);
        assertEquals(0, pf.availableCash("USD").compareTo(INITIAL_CASH.subtract(reserve)),
                "available cash reduced by full reserve");
        log.info("after submit: available={} reserved={}", pf.availableCash("USD"),
                pf.reservedCash("USD"));

        // 第一笔部分成交 40 股，价格 100
        Fill f1 = fill(order, "f1", new BigDecimal("40"), new BigDecimal("100.00"), t0);
        orders.consumeFill(f1);
        assertEquals(OrderStatus.PARTIALLY_FILLED, order.getStatus());
        BigDecimal spent1 = f1.grossAmount().add(f1.commission()).add(f1.tax());

        // 第二笔部分成交 60 股
        Fill f2 = fill(order, "f2", new BigDecimal("60"), new BigDecimal("100.00"), t0.plusSeconds(1));
        orders.consumeFill(f2);
        assertEquals(OrderStatus.FILLED, order.getStatus());
        BigDecimal spent2 = f2.grossAmount().add(fill2tax(f2)).add(f2.tax()).add(f2.commission());

        Position pos = pf.position("AAA");
        assertEquals(0, pos.getQuantity().compareTo(new BigDecimal("100.00000000")));
        // 成本基准 = 成交额 + 两次佣金（买入无税）
        BigDecimal expectedCost = new BigDecimal("10000.00")
                .add(f1.commission()).add(f2.commission());
        assertEquals(0, pos.getCostBasis().compareTo(expectedCost),
                "cost basis equals notional + commissions");
        // 现金 = 初始 - 全部实际支出
        BigDecimal totalSpent = spent1.add(f2.grossAmount()).add(f2.commission()).add(f2.tax());
        assertEquals(0, pf.cash("USD").compareTo(INITIAL_CASH.subtract(totalSpent)),
                "cash debited exactly once per fill");
        assertEquals(0, pf.reservedCash("USD").compareTo(BigDecimal.ZERO),
                "reservation fully released after fill complete");
        log.info("ASSERT cash={} reserved=0 costBasis={}", pf.cash("USD"), pos.getCostBasis());
    }

    @Test
    void duplicateFillIsIdempotent() {
        Instant t0 = Instant.parse("2026-09-01T09:00:00Z");
        BigDecimal reserve = costs.buyReserveUpperBound(new BigDecimal("100"), new BigDecimal("10"));
        Order order = orders.submit(ACC, "AAA", Side.BUY, OrderType.LIMIT,
                new BigDecimal("10"), new BigDecimal("100"), reserve, t0, "k").order();
        Fill f = fill(order, "dup", new BigDecimal("10"), new BigDecimal("100"), t0);
        orders.consumeFill(f);
        BigDecimal cashAfter = registry.require(ACC).cash("USD");
        orders.consumeFill(f); // 重复回报
        assertEquals(0, registry.require(ACC).cash("USD").compareTo(cashAfter),
                "duplicate fill must not double-charge");
        assertEquals(0, order.getFilledQuantity().compareTo(new BigDecimal("10")));
        assertTrue(orders.isFillConsumed("dup"));
        log.info("ASSERT duplicate fill no double charge, cash={}", cashAfter);
    }

    @Test
    void cancelReleasesResidualReserveAndSellFreeze() {
        Instant t0 = Instant.parse("2026-09-01T09:00:00Z");
        BigDecimal reserve = costs.buyReserveUpperBound(new BigDecimal("100"), new BigDecimal("100"));
        Order buy = orders.submit(ACC, "AAA", Side.BUY, OrderType.LIMIT,
                new BigDecimal("100"), new BigDecimal("100"), reserve, t0, "b").order();
        orders.cancel(buy.getOrderId());
        assertEquals(0, registry.require(ACC).availableCash("USD").compareTo(INITIAL_CASH),
                "cancel restores full reserved cash");
        assertEquals(OrderStatus.CANCELLED, buy.getStatus());

        // 撤单再成交 -> 状态冲突
        Fill f = fill(buy, "x", new BigDecimal("1"), new BigDecimal("100"), t0);
        assertThrows(StateConflictException.class, () -> orders.consumeFill(f));
        log.info("ASSERT fill after cancel rejected with STATE_CONFLICT");

        // 再次撤单幂等
        orders.cancel(buy.getOrderId());
    }

    @Test
    void oversellIsRejectedAndSellFreezeConserves() {
        Instant t0 = Instant.parse("2026-09-01T09:00:00Z");
        Portfolio pf = registry.require(ACC);
        BigDecimal reserve = costs.buyReserveUpperBound(new BigDecimal("100"), new BigDecimal("50"));
        Order buy = orders.submit(ACC, "AAA", Side.BUY, OrderType.LIMIT,
                new BigDecimal("50"), new BigDecimal("100"), reserve, t0, "seed").order();
        orders.consumeFill(fill(buy, "seed-f", new BigDecimal("50"), new BigDecimal("100"), t0));
        BigDecimal availableAfterBuy = pf.position("AAA").getAvailableQuantity();

        // 卖 60 股（超持仓）必须被拒
        assertThrows(com.github.highcumontoa.backtestportfolioenginejava.error
                .ResourceLimitException.class, () -> orders.submit(ACC, "AAA", Side.SELL,
                OrderType.LIMIT, new BigDecimal("60"), new BigDecimal("101"), null, t0, "sell-too-many"));
        assertEquals(0, pf.position("AAA").getAvailableQuantity().compareTo(availableAfterBuy));

        // 卖 30：冻结 30，available 变 20；部分成交 30 后总量 20，available 20
        Order sell = orders.submit(ACC, "AAA", Side.SELL, OrderType.LIMIT,
                new BigDecimal("30"), new BigDecimal("101"), null, t0, "sell-ok").order();
        assertEquals(0, pf.position("AAA").getAvailableQuantity().compareTo(new BigDecimal("20.00000000")));
        orders.consumeFill(fill(sell, "sell-f", new BigDecimal("30"), new BigDecimal("101"), t0.plusSeconds(1)));
        assertEquals(0, pf.position("AAA").getQuantity().compareTo(new BigDecimal("20.00000000")));
        assertEquals(0, pf.position("AAA").getAvailableQuantity().compareTo(new BigDecimal("20.00000000")));
        assertFalse(pf.position("AAA").getCostBasis().signum() < 0, "cost basis never negative");
        log.info("ASSERT after partial sell qty={} available={} costBasis={}",
                pf.position("AAA").getQuantity(),
                pf.position("AAA").getAvailableQuantity(), pf.position("AAA").getCostBasis());
    }

    private static BigDecimal fill2tax(Fill f) {
        return BigDecimal.ZERO;
    }

    private Fill fill(Order order, String fillId, BigDecimal qty, BigDecimal price, Instant at) {
        BigDecimal commission = costs.commission(price, qty);
        BigDecimal tax = costs.tax(order.getSide(), price, qty);
        return new Fill(fillId, order.getOrderId(), "AAA", order.getSide(),
                qty, price, commission, tax, at);
    }
}
