package com.github.highcumontoa.backtestportfolioenginejava.tests;

import com.github.highcumontoa.backtestportfolioenginejava.config.CostConfig;
import com.github.highcumontoa.backtestportfolioenginejava.domain.Fill;
import com.github.highcumontoa.backtestportfolioenginejava.domain.Order;
import com.github.highcumontoa.backtestportfolioenginejava.domain.OrderType;
import com.github.highcumontoa.backtestportfolioenginejava.domain.Side;
import com.github.highcumontoa.backtestportfolioenginejava.error.ResourceLimitException;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 并发边界：幂等键唯一、并发下单不超卖/不超额、并发成交与撤单安全。 */
class ConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyTest.class);
    private static final CostConfig CFG = new CostConfig(new BigDecimal("0.0003"),
            new BigDecimal("5"), new BigDecimal("0.001"), new BigDecimal("0.0005"),
            true, new BigDecimal("20"));
    private static final String ACC = "acc";
    private static final Instant T0 = Instant.parse("2026-09-01T09:00:00Z");

    private PortfolioRegistry registry;
    private OrderService orders;
    private CostCalculator costs;
    private Portfolio pf;

    @BeforeEach
    void setUp() {
        registry = new PortfolioRegistry();
        registry.createAccount(ACC, "USD", new BigDecimal("100000"));
        costs = new CostCalculator(CFG);
        MatchingEngine engine = new MatchingEngine(
                new MarketDataService(Duration.ofMinutes(5)), costs);
        orders = new OrderService(new OrderRepository(), registry, engine);
        pf = registry.require(ACC);
    }

    @Test
    void sameIdempotencyKeyConcurrentSubmitProducesOneOrder() throws Exception {
        int n = 32;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> orderIds = Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
        AtomicInteger created = new AtomicInteger();
        BigDecimal reserve = costs.buyReserveUpperBound(new BigDecimal("100"), new BigDecimal("10"));
        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                start.await();
                OrderService.SubmitResult r = orders.submit(ACC, "AAA", Side.BUY, OrderType.LIMIT,
                        new BigDecimal("10"), new BigDecimal("100"), reserve, T0, "SAME-KEY");
                orderIds.add(r.order().getOrderId());
                if (r.created()) {
                    created.incrementAndGet();
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(1, orderIds.size(), "concurrent same-key submits collapse to one order");
        assertEquals(1, created.get(), "exactly one effective creation");
        // 只有一笔预留，可用资金只被冻结一次
        BigDecimal available = pf.availableCash("USD");
        assertTrue(available.compareTo(new BigDecimal("100000").subtract(reserve)) == 0,
                "reservation applied exactly once, available=" + available);
        log.info("ASSERT 32 concurrent same-key submits -> 1 order, reserved once={}", reserve);
    }

    @Test
    void concurrentSellsNeverOversell() throws Exception {
        // 建仓 100 股
        BigDecimal reserve = costs.buyReserveUpperBound(new BigDecimal("100"), new BigDecimal("100"));
        Order seed = orders.submit(ACC, "AAA", Side.BUY, OrderType.LIMIT,
                new BigDecimal("100"), new BigDecimal("100"), reserve, T0, "seed").order();
        orders.consumeFill(new Fill("sf", seed.getOrderId(), "AAA", Side.BUY,
                new BigDecimal("100"), new BigDecimal("100"),
                costs.commission(new BigDecimal("100"), new BigDecimal("100")),
                BigDecimal.ZERO, T0));

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            int idx = i;
            pool.submit(() -> {
                start.await();
                try {
                    // 每单卖 10 股，总量 200 > 持仓 100，只应成功 10 单
                    orders.submit(ACC, "AAA", Side.SELL, OrderType.LIMIT,
                            new BigDecimal("10"), new BigDecimal("101"), null, T0,
                            "sell-" + idx);
                    accepted.incrementAndGet();
                } catch (ResourceLimitException e) {
                    rejected.incrementAndGet();
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(10, accepted.get(), "exactly holdings can be reserved for sell");
        assertEquals(10, rejected.get(), "oversell attempts rejected");
        assertEquals(0, pf.position("AAA").getAvailableQuantity().compareTo(BigDecimal.ZERO),
                "all available quantity frozen, no oversell");
        assertEquals(0, pf.position("AAA").getQuantity().compareTo(new BigDecimal("100.00000000")),
                "total quantity unchanged by reservations");
        log.info("ASSERT 20 concurrent sells of 10 against 100 held -> 10 ok / 10 rejected");
    }

    @Test
    void concurrentDuplicateFillsChargedOnce() throws Exception {
        BigDecimal reserve = costs.buyReserveUpperBound(new BigDecimal("100"), new BigDecimal("10"));
        Order order = orders.submit(ACC, "AAA", Side.BUY, OrderType.LIMIT,
                new BigDecimal("10"), new BigDecimal("100"), reserve, T0, "k").order();
        Fill fill = new Fill("same-fill", order.getOrderId(), "AAA", Side.BUY,
                new BigDecimal("10"), new BigDecimal("100"),
                costs.commission(new BigDecimal("100"), new BigDecimal("10")),
                BigDecimal.ZERO, T0);
        int n = 16;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> statuses = new HashSet<>();
        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                start.await();
                Order o = orders.consumeFill(fill);
                synchronized (statuses) {
                    statuses.add(o.getStatus().name() + ":" + o.getFilledQuantity());
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(Set.of("FILLED:10"), statuses, "all observers see identical final state");
        BigDecimal cash = pf.cash("USD");
        BigDecimal expected = new BigDecimal("100000").subtract(new BigDecimal("1005.00"));
        assertEquals(0, cash.compareTo(expected), "charged exactly once: " + cash);
        log.info("ASSERT 16 concurrent duplicate fills -> one charge, cash={}", cash);
    }
}
