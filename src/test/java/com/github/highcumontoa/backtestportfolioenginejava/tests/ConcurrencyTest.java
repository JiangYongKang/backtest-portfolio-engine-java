package com.github.highcumontoa.backtestportfolioenginejava.tests;

import com.github.highcumontoa.backtestportfolioenginejava.model.OrderRequest;
import com.github.highcumontoa.backtestportfolioenginejava.model.OrderType;
import com.github.highcumontoa.backtestportfolioenginejava.model.Side;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 并发：同一幂等键只产生一次有效结果；并发卖出不超卖；资金不重复占用。 */
class ConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyTest.class);

    @Test
    void concurrentSameIdempotencyKeyCreatesExactlyOneOrder() throws Exception {
        // 直接压测订单服务：同一 clientOrderId 的并发布尔创建必须恰好成功一次。
        var orderService = new com.github.highcumontoa.backtestportfolioenginejava.service.OrderService();
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        Set<String> orderIds = ConcurrentHashMap.newKeySet();
        AtomicInteger created = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    OrderRequest req = new OrderRequest("SAME-KEY", Side.BUY, OrderType.LIMIT,
                            "AAA", new BigDecimal("100"), new BigDecimal("100"), "USD");
                    var result = orderService.submit(req, 10L);
                    orderIds.add(result.order().getOrderId());
                    if (result.created()) {
                        created.incrementAndGet();
                    }
                } catch (Exception e) {
                    log.error("thread error", e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(1, orderIds.size(), "all threads resolve the same order id");
        assertEquals(1, created.get(), "exactly one create wins, rest are idempotent hits");
        assertEquals(1, orderService.allOrders().size(), "one order for one idempotency key");
        log.info("concurrent idempotent submit: {} threads -> created=1, single order {}",
                threads, orderIds.iterator().next());
    }

    @Test
    void concurrentSellsNeverOversell() throws Exception {
        // 直接压测组合服务临界区：先建 50 股多头，多线程并发“冻结 10 股”。
        // 恰好 5 个成功，其余抛 INSUFFICIENT_POSITION；绝不可能超卖。
        EngineTestKit kit = new EngineTestKit();
        kit.portfolio.deposit("USD", new BigDecimal("100000000"));
        kit.portfolio.applyFill(new com.github.highcumontoa.backtestportfolioenginejava.model.Fill(
                "BASE", "OBASE", "AAA", Side.BUY, new BigDecimal("50"),
                new BigDecimal("100"), 1L, BigDecimal.ZERO, BigDecimal.ZERO), "USD");

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger frozen = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    kit.portfolio.freezePosition("AAA", new BigDecimal("10"));
                    frozen.incrementAndGet();
                } catch (com.github.highcumontoa.backtestportfolioenginejava.exception
                        .InvalidArgumentApiException e) {
                    assertEquals("INSUFFICIENT_POSITION", e.getCode());
                    rejected.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(5, frozen.get(), "exactly 50 shares can be frozen in 5 x 10");
        assertEquals(15, rejected.get(), "remaining requests rejected, no oversell");
        var pos = kit.portfolio.position("AAA");
        assertEquals(0, new BigDecimal("50").compareTo(pos.getQuantity()));
        assertEquals(0, BigDecimal.ZERO.compareTo(pos.getAvailableQuantity()),
                "all 50 frozen, available never goes negative");
        log.info("concurrent freeze: frozen={} rejected={} available=0 (no oversell)",
                frozen.get(), rejected.get());
    }

    @Test
    void concurrentBuyReservationsNeverOverCommitCash() throws Exception {
        // 每笔买入预占 10000，可用资金仅 25000：恰好 2 笔预占成功，其余资金不足。
        var pf = new com.github.highcumontoa.backtestportfolioenginejava.service.PortfolioService();
        pf.deposit("USD", new BigDecimal("25000"));
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger denied = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    pf.reserveCash("USD", new BigDecimal("10000"));
                    ok.incrementAndGet();
                } catch (com.github.highcumontoa.backtestportfolioenginejava.exception
                        .InvalidArgumentApiException e) {
                    assertEquals("INSUFFICIENT_FUNDS", e.getCode());
                    denied.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(2, ok.get());
        assertEquals(6, denied.get());
        assertEquals(0, new BigDecimal("20000").compareTo(pf.reservedCash("USD")));
        assertEquals(0, new BigDecimal("5000").compareTo(pf.availableCash("USD")));
        log.info("concurrent reserve: committed=20000 available=5000 no over-commitment");
    }
}
