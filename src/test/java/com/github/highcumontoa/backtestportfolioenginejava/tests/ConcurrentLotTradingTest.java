package com.github.highcumontoa.backtestportfolioenginejava.tests;

import com.github.highcumontoa.backtestportfolioenginejava.model.Fill;
import com.github.highcumontoa.backtestportfolioenginejava.model.LotRealization;
import com.github.highcumontoa.backtestportfolioenginejava.model.Side;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 同一标的并发买卖：多线程直接压测 {@code PortfolioService.applyFill} 的成对更新临界区，
 * 校验现金、持仓数量、FIFO 批次数量/成本、已实现盈亏在争用下不多算不漏算，恒等式严格成立。
 */
class ConcurrentLotTradingTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentLotTradingTest.class);

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    @Test
    void concurrentBuyAndSellFillsOnSameSymbolKeepBooksConsistent() throws Exception {
        var kit = new EngineTestKit();
        var pf = kit.portfolio;
        pf.deposit("USD", bd("100000000"));

        int buyers = 10;
        int sellers = 10;
        int threads = buyers + sellers;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger okBuy = new AtomicInteger();
        AtomicInteger okSell = new AtomicInteger();
        AtomicLong seq = new AtomicLong();

        // 先建 1000 股底仓（在任何并发任务提交之前），保证任何交错下都有足够持仓，
        // 不会因超卖拒单而改变成交笔数。
        pf.applyFill(new Fill("SEED", "O-SEED", "AAA", Side.BUY,
                bd("1000"), bd("100.00"), 0L, bd("0"), bd("0")), "USD");

        // 每个买单：100 股 @100，佣金 0；共 20 笔买（buyers 个线程，每线程 2 笔）
        for (int i = 0; i < buyers; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int k = 0; k < 2; k++) {
                        String exec = "BUY-" + seq.incrementAndGet();
                        pf.applyFill(new Fill(exec, "O-" + exec, "AAA", Side.BUY,
                                bd("100"), bd("100.00"), 1L, bd("0"), bd("0")), "USD");
                        okBuy.incrementAndGet();
                    }
                } catch (Exception e) {
                    log.error("buyer error", e);
                } finally {
                    done.countDown();
                }
            });
        }
        for (int i = 0; i < sellers; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    String exec = "SELL-" + seq.incrementAndGet();
                    // 与真实引擎一致：卖出先冻结可卖数量（临界区保证不超卖），再结算。
                    pf.freezePosition("AAA", bd("100"));
                    pf.applyFill(new Fill(exec, "O-" + exec, "AAA", Side.SELL,
                            bd("100"), bd("105.00"), 2L, bd("0"), bd("0")), "USD");
                    okSell.incrementAndGet();
                } catch (Exception e) {
                    log.error("seller error", e);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(20, okBuy.get(), "all 20 buy fills booked");
        assertEquals(10, okSell.get(), "all 10 sell fills booked");

        // 数量守恒：底仓 1000 + 买 2000 - 卖 1000 = 2000
        var pos = pf.position("AAA");
        assertEquals(0, new BigDecimal("2000").compareTo(pos.getQuantity()),
                "position qty conserved under concurrent trades");
        assertEquals(0, new BigDecimal("2000").compareTo(pos.getAvailableQuantity()));

        // 批次数量之和必须等于持仓数量（不多批、不漏批）
        var lots = pf.lotService();
        BigDecimal lotQty = lots.openLots("AAA").stream()
                .map(l -> l.remainingQuantity()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal("2000").compareTo(lotQty), "open lot qty == position qty");

        // 卖出明细：10 笔卖出各 100 股
        List<LotRealization> rows = lots.realizations("AAA");
        BigDecimal soldQty = rows.stream().map(LotRealization::quantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal("1000").compareTo(soldQty), "realized qty = sold 1000");
        // 每行成交/成本/盈亏必须自洽：realized = gross - cost（无费税）
        for (LotRealization r : rows) {
            BigDecimal g = bd("105.00").multiply(r.quantity()).setScale(2,
                    java.math.RoundingMode.HALF_UP);
            assertEquals(0, g.compareTo(r.grossProceeds()), "gross = 105 * qty");
            BigDecimal expectPnl = r.grossProceeds().subtract(r.costBasis());
            assertEquals(0, expectPnl.compareTo(r.realizedPnl()),
                    "row pnl = gross - cost basis");
        }
        // 已实现盈亏与剩余成本之和只取决于“最终卖出了哪 1000 股”，
        // 用恒等式统一校验，不依赖具体 FIFO 交错顺序（顺序由线程调度决定）。

        // 现金恒等式：现金 + 剩余成本 = 入金 + 已实现
        BigDecimal cash = pf.cash("USD");
        BigDecimal openCost = lots.openCost("AAA");
        BigDecimal lhs = cash.add(openCost);
        BigDecimal rhs = bd("100000000").add(lots.realizedPnl("USD"))
                .add(lots.dividendIncome("USD"));
        assertEquals(0, lhs.compareTo(rhs),
                "cash + openCost = deposits + realized under concurrency; lhs=" + lhs + " rhs=" + rhs);
        // 底仓与所有买入批次成本均为 100，FIFO 交错不影响总盈亏：卖出 1000 股 * (105-100) = 5000
        BigDecimal sumRowPnl = rows.stream().map(LotRealization::realizedPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, sumRowPnl.compareTo(lots.realizedPnl("USD")),
                "ledger realized must equal sum of realization rows");
        assertEquals(0, new BigDecimal("5000.00").compareTo(lots.realizedPnl("USD")),
                "realized = 1000 shares * 5 spread regardless of interleaving");
        log.info("CONCURRENT LOTS OK: buys=20 sells=10 qty=2000 openCost={} realized={} identity {} = {}",
                openCost, lots.realizedPnl("USD"), lhs, rhs);
    }
}
