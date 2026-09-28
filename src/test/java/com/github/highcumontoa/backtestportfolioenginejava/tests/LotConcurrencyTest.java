package com.github.highcumontoa.backtestportfolioenginejava.tests;

import com.github.highcumontoa.backtestportfolioenginejava.model.Fill;
import com.github.highcumontoa.backtestportfolioenginejava.model.Side;
import com.github.highcumontoa.backtestportfolioenginejava.model.lot.LotView;
import com.github.highcumontoa.backtestportfolioenginejava.model.lot.RealizedPnl;
import com.github.highcumontoa.backtestportfolioenginejava.service.PortfolioService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 同一标的上的并发买卖：批次开批、FIFO 核销、现金与已实现盈亏在逐标的/逐币种临界区内
 * 成对更新，多线程重放后数量、成本、现金、盈亏都必须与串行口径一致（不多算/不漏算）。
 */
class LotConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(LotConcurrencyTest.class);

    private static void assertEq(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "expected " + expected + " but got " + actual);
    }

    @Test
    void concurrentBuyFillsCreateExactlyOneLotEachAndConserveCash() throws Exception {
        PortfolioService pf = new PortfolioService();
        pf.deposit("USD", new BigDecimal("1000000"));
        int n = 16;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        AtomicInteger ok = new AtomicInteger();
        for (int i = 1; i <= n; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    // 每笔 100 股 @10，佣金 5 -> 支出 1005.00，一个批次
                    pf.applyFill(new Fill("FB" + idx, "OB" + idx, "AAA", Side.BUY,
                            new BigDecimal("100"), new BigDecimal("10.00"), idx,
                            new BigDecimal("5.00"), BigDecimal.ZERO), "USD");
                    ok.incrementAndGet();
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

        assertEquals(n, ok.get());
        List<LotView> lots = pf.openLots("AAA");
        assertEquals(n, lots.size());
        for (LotView lot : lots) {
            assertEq("100", lot.remainingQty());
            assertEq("1005.00", lot.remainingCost());
        }
        assertEq("1600", pf.position("AAA").getQuantity());
        assertEq("16080.00", pf.lotRemainingCost("AAA"));
        assertEq("983920.00", pf.cash("USD")); // 1,000,000 - 16*1005
        assertTrue(pf.realizedPnls().isEmpty());
        log.info("concurrent {} buys: {} lots, qty=1600 lotCost=16080 cash=983920",
                n, lots.size());
    }

    @Test
    void concurrentSellsFifoNeverOverConsumeAndPnlSumsExactly() throws Exception {
        PortfolioService pf = new PortfolioService();
        pf.deposit("USD", new BigDecimal("1000000"));
        // 建仓 10 个批次，每批 100 股 @10 含佣 5（成本 1005）-> 共 1000 股、成本 10050
        for (int i = 1; i <= 10; i++) {
            pf.applyFill(new Fill("FB" + i, "OB", "AAA", Side.BUY,
                    new BigDecimal("100"), new BigDecimal("10.00"), i,
                    new BigDecimal("5.00"), BigDecimal.ZERO), "USD");
        }

        // 12 个线程各尝试卖出 100 股 @11（无费税，简化对账）：恰好 10 笔成功，2 笔超卖被拒。
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger sold = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (int i = 1; i <= threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    pf.applyFill(new Fill("FS" + idx, "OS" + idx, "AAA", Side.SELL,
                            new BigDecimal("100"), new BigDecimal("11.00"), 100L + idx,
                            BigDecimal.ZERO, BigDecimal.ZERO), "USD");
                    sold.incrementAndGet();
                } catch (com.github.highcumontoa.backtestportfolioenginejava.exception
                        .InvalidArgumentApiException e) {
                    assertEquals("LOT_OVERSELL", e.getCode());
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

        assertEquals(10, sold.get());
        assertEquals(2, rejected.get());
        assertTrue(pf.openLots("AAA").isEmpty(), "all lots fully consumed via FIFO");
        assertEq("0", pf.position("AAA").getQuantity());
        // 每笔毛收入 1100，10 笔 = 11000；成本恰为 10050；已实现 = 950
        BigDecimal costBasis = pf.realizedPnls().stream().map(RealizedPnl::costBasis)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal netPnl = pf.realizedPnls().stream().map(RealizedPnl::netPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEq("10050.00", costBasis);
        assertEq("950.00", netPnl);
        assertEquals(10, pf.realizedPnls().size());
        // 现金对账：入金 - 买入支出 10050 + 卖出收入 11000 = 1000950
        assertEq("1000950.00", pf.cash("USD"));
        log.info("concurrent sells: sold=10 rejected=2 costBasis=10050 realized=950 cash reconciles");
    }
}
