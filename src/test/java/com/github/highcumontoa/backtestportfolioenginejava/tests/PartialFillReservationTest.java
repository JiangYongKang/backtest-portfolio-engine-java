package com.github.highcumontoa.backtestportfolioenginejava.tests;

import com.github.highcumontoa.backtestportfolioenginejava.model.FxRate;
import com.github.highcumontoa.backtestportfolioenginejava.model.Order;
import com.github.highcumontoa.backtestportfolioenginejava.model.OrderRequest;
import com.github.highcumontoa.backtestportfolioenginejava.model.OrderStatus;
import com.github.highcumontoa.backtestportfolioenginejava.model.OrderType;
import com.github.highcumontoa.backtestportfolioenginejava.model.Quote;
import com.github.highcumontoa.backtestportfolioenginejava.model.RejectReason;
import com.github.highcumontoa.backtestportfolioenginejava.model.Side;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 大额限价买单部分成交后的现金占用校准。
 *
 * <p>业务背景：一笔占掉账户大部分可用资金的限价买单（限价 100、市价 97，每股留 3 元价差缓冲），
 * 每档行情仅给 300 股可见量 -> 多次部分成交。修复前 {@code recalibrateReservation}
 * 按订单“原始总量”重估最坏成本，已成交部分的价差缓冲退不出来，总占用被压在接近整单金额，
 * 导致账户明明有钱、随后一笔买得起的单却被判 INSUFFICIENT_FUNDS。
 *
 * <p>关键数字（默认费率：滑点 0.05%、佣金 0.03%、最低 5、2 位金额）：
 * <ul>
 *   <li>1000 股限价 100 最坏成本 = 100050.00 + 30.02 = 100080.02（下单全额预占）；</li>
 *   <li>每 300 股按市价 97 成交：fill=97.0485，out=29114.55+8.73=29123.28；</li>
 *   <li>正确的剩余占用：700 股=70056.01、400 股=40032.01、100 股=10010.00（最低佣金）。</li>
 * </ul>
 */
class PartialFillReservationTest {

    private static final Logger log = LoggerFactory.getLogger(PartialFillReservationTest.class);

    private static final BigDecimal LIMIT = new BigDecimal("100.00");
    private static final BigDecimal MARKET = new BigDecimal("97.00");
    private static final BigDecimal BIG_QTY = new BigDecimal("1000");
    private static final BigDecimal EACH_TICK = new BigDecimal("300");
    private static final BigDecimal DEPOSIT = new BigDecimal("120000");

    private static Quote quote(String symbol, long t, String last) {
        BigDecimal v = new BigDecimal(last);
        return new Quote(symbol, t, v, v, v, false);
    }

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    private static void eq(BigDecimal expected, BigDecimal actual, String msg) {
        assertEquals(0, expected.compareTo(actual), msg + " actual=" + actual);
    }

    /**
     * 搭一个标准场景：入金 120000，AAA 市价 97，t=10/20/30 各只给 300 股，
     * 1000 股限价 100 的买单在 t=10 入场。调用方按需 runTo 推进。
     */
    private static EngineTestKit scenario() {
        EngineTestKit kit = new EngineTestKit();
        kit.portfolio.deposit("USD", DEPOSIT);
        kit.engine.addFxRates(List.of(new FxRate("USD", "USD", 0L, BigDecimal.ONE)));
        kit.engine.addQuotes(List.of(
                quote("AAA", 10L, "97"), quote("AAA", 20L, "97"), quote("AAA", 30L, "97"),
                quote("BBB", 40L, "100"), quote("BBB", 50L, "100"),
                quote("CCC", 10L, "97"), quote("CCC", 20L, "97"), quote("CCC", 30L, "97")));
        kit.engine.addLiquidity("AAA", 10L, EACH_TICK);
        kit.engine.addLiquidity("AAA", 20L, EACH_TICK);
        kit.engine.addLiquidity("AAA", 30L, EACH_TICK);
        // 后续时刻 AAA 可见量为 0：保证尾量只是挂着，不会在探测单/撤单的 runTo 里被顺手成交
        kit.engine.addLiquidity("AAA", 35L, BigDecimal.ZERO);
        kit.engine.addLiquidity("AAA", 40L, BigDecimal.ZERO);
        kit.engine.addLiquidity("AAA", 45L, BigDecimal.ZERO);
        kit.engine.addLiquidity("AAA", 50L, BigDecimal.ZERO);
        kit.engine.addLiquidity("AAA", 60L, BigDecimal.ZERO);
        kit.engine.placeOrder(new OrderRequest("BIG-BUY", Side.BUY, OrderType.LIMIT,
                "AAA", BIG_QTY, LIMIT, "USD"), 10L);
        return kit;
    }

    /** 把场景推进到第 stages 次部分成交之后（stages ∈ {1,2,3}）。 */
    private static EngineTestKit scenarioAfter(int stages) {
        EngineTestKit kit = scenario();
        long[] times = {10L, 20L, 30L};
        for (int i = 0; i < stages; i++) {
            kit.engine.runTo(times[i], "USD");
        }
        return kit;
    }

    private static Order bigOrder(EngineTestKit kit) {
        return kit.orderService.getByClientId("BIG-BUY").orElseThrow();
    }

    /** 用真实费率核算一笔 BBB 限价 100 买单的最坏现金支出，避免断言里硬编码浮点。 */
    private static BigDecimal probeCost(EngineTestKit kit, String clientId, BigDecimal qty) {
        Order probe = new Order("EST-" + clientId, clientId, Side.BUY, OrderType.LIMIT,
                "BBB", qty, LIMIT, "USD", 0L);
        return kit.fillService.computeCosts(probe, qty, LIMIT).netCashFlow().abs();
    }

    /** 提交一笔 BBB 限价 100 的探测买单并推进到 t，返回订单终态。 */
    private static Order submitProbe(EngineTestKit kit, String clientId, BigDecimal qty, long t) {
        kit.engine.addQuotes(List.of(quote("BBB", t, "100")));
        kit.engine.placeOrder(new OrderRequest(clientId, Side.BUY, OrderType.LIMIT,
                "BBB", qty, LIMIT, "USD"), t);
        kit.engine.runTo(t, "USD");
        return kit.orderService.getByClientId(clientId).orElseThrow();
    }

    @Test
    @DisplayName("大额买单多次部分成交：占用随剩余量下降，退出的钱马上能用于下一笔买得起的单")
    void reservationShrinksWithRemainingAndFreedCashIsReusable() {
        EngineTestKit kit = scenarioAfter(1);
        // 第 1 次成交 300：剩余 700 股的最坏占用 70056.01
        eq(bd("300"), bigOrder(kit).getFilledQty(), "filled 300 after tick1");
        eq(bd("70056.01"), kit.portfolio.reservedCash("USD"),
                "reservation must track 700 remaining, not ~full order");
        eq(bd("20820.71"), kit.portfolio.availableCash("USD"), "freed cash available immediately");
        log.info("[部分成交-1] filled=300 remaining=700 reserved={} available={} (旧逻辑压着 70956.74)",
                kit.portfolio.reservedCash("USD"), kit.portfolio.availableCash("USD"));

        kit = scenarioAfter(2);
        eq(bd("40032.01"), kit.portfolio.reservedCash("USD"), "reservation for 400 remaining");
        eq(bd("21721.43"), kit.portfolio.availableCash("USD"), "more cash freed after tick2");
        log.info("[部分成交-2] filled=600 remaining=400 reserved={} available={}",
                kit.portfolio.reservedCash("USD"), kit.portfolio.availableCash("USD"));

        kit = scenarioAfter(3);
        eq(bd("900"), bigOrder(kit).getFilledQty(), "filled 900 after tick3");
        eq(bd("100"), bigOrder(kit).remainingQty(), "100 still resting");
        eq(bd("10010.00"), kit.portfolio.reservedCash("USD"),
                "reservation for last 100 (min commission 5)");
        eq(bd("22620.16"), kit.portfolio.availableCash("USD"),
                "account actually has 22620.16 free, not 19919.98 pinned by stale reservation");
        log.info("[部分成交-3] filled=900 remaining=100 reserved={} available={}",
                kit.portfolio.reservedCash("USD"), kit.portfolio.availableCash("USD"));

        // 随后一笔本来买得起的单（200 股 BBB，需 20016.00）必须能成交——这正是旧逻辑误拒的单
        BigDecimal affordable = bd("200");
        eq(bd("20016.00"), probeCost(kit, "AFFORDABLE", affordable), "probe cost sanity");
        Order probe = submitProbe(kit, "AFFORDABLE", affordable, 40L);
        assertEquals(OrderStatus.FILLED, probe.getStatus(),
                "affordable buy must fill; stale reservation must not reject it");
        // 探测单已全部成交，自身占用清零，只剩大额单剩余 100 股的占用
        eq(bd("10010.00"), kit.portfolio.reservedCash("USD"),
                "filled probe leaves no reservation; big order remainder still held");
        log.info("[复用资金] 200 股 BBB 买入成交，reserved={} 只剩大额单尾量",
                kit.portfolio.reservedCash("USD"));
    }

    @Test
    @DisplayName("边界守住：部分成交后确实超额的新买单仍被拒（INSUFFICIENT_FUNDS），且不留占用")
    void oversizedNewBuyStillRejectedAfterPartialFill() {
        EngineTestKit kit = scenarioAfter(3);
        BigDecimal available = kit.portfolio.availableCash("USD");
        BigDecimal reservedBefore = kit.portfolio.reservedCash("USD");
        BigDecimal cashBefore = kit.portfolio.cash("USD");
        // 230 股需 23018.40 > 正确可用 22620.16：必须拒
        BigDecimal oversized = bd("230");
        assertTrue(probeCost(kit, "TOO-BIG", oversized).compareTo(available) > 0,
                "test setup: probe really exceeds available");
        Order rejected = submitProbe(kit, "TOO-BIG", oversized, 40L);
        assertEquals(OrderStatus.REJECTED, rejected.getStatus());
        assertEquals(RejectReason.INSUFFICIENT_FUNDS, rejected.getRejectReason(),
                "reject reason must distinguish insufficient funds");
        // 被拒单不得留下任何预占：总占用与现金都与提交前一致
        eq(reservedBefore, kit.portfolio.reservedCash("USD"), "rejected order reserves nothing");
        eq(cashBefore, kit.portfolio.cash("USD"), "rejected order never touches cash");
        log.info("[超额拒单] 230 股需 {} > 可用 {}，已拒单 reason={} reserved 不变={}",
                probeCost(kit, "TOO-BIG", oversized), available,
                rejected.getRejectReason(), kit.portfolio.reservedCash("USD"));
    }

    @Test
    @DisplayName("边界一致性：三次部分成交逐档累积，卡着可用资金边界的单结论始终一致（买得起即过、多一股即拒）")
    void boundaryDecisionConsistentAcrossAccumulatedPartialFills() {
        // 每一档都用全新引擎，避免探测单自身改变现金；各档正确可用：20820.71 / 21721.43 / 22620.16
        BigDecimal[] expectedAvailable = {bd("20820.71"), bd("21721.43"), bd("22620.16")};
        for (int stage = 1; stage <= 3; stage++) {
            // 1) 找到“恰好买得起”的最大股数：线性搜索（区间很小），按真实费率逐档核算
            EngineTestKit probeKit = scenarioAfter(stage);
            BigDecimal available = probeKit.portfolio.availableCash("USD");
            eq(expectedAvailable[stage - 1], available, "stage " + stage + " available");
            BigDecimal maxAffordable = BigDecimal.ZERO;
            for (int q = 1; q <= 500; q++) {
                BigDecimal qty = BigDecimal.valueOf(q);
                if (probeCost(probeKit, "Q-" + q, qty).compareTo(available) <= 0) {
                    maxAffordable = qty;
                } else {
                    break;
                }
            }
            assertTrue(maxAffordable.signum() > 0, "at least one share affordable at stage " + stage);

            // 2) 恰好买得起的那一档：独立引擎上必须成交
            EngineTestKit acceptKit = scenarioAfter(stage);
            Order accepted = submitProbe(acceptKit, "EDGE-OK-" + stage, maxAffordable, 40L);
            assertEquals(OrderStatus.FILLED, accepted.getStatus(),
                    "stage " + stage + ": qty " + maxAffordable + " costing <= available must fill");

            // 3) 多一股（确实超额）：独立引擎上必须拒
            EngineTestKit rejectKit = scenarioAfter(stage);
            BigDecimal oneMore = maxAffordable.add(BigDecimal.ONE);
            Order rejected = submitProbe(rejectKit, "EDGE-NO-" + stage, oneMore, 40L);
            assertEquals(OrderStatus.REJECTED, rejected.getStatus(),
                    "stage " + stage + ": qty " + oneMore + " exceeding available must reject");
            assertEquals(RejectReason.INSUFFICIENT_FUNDS, rejected.getRejectReason());
            log.info("[边界一致-第{}档] available={} 恰好买得起 {} 股成交，{} 股拒单",
                    stage, available, maxAffordable, oneMore);
        }
    }

    @Test
    @DisplayName("部分成交后撤掉剩余：尾量占用一次清零，重复撤单不重复释放")
    void cancelAfterPartialFillReleasesEntireReservationOnce() {
        EngineTestKit kit = scenarioAfter(3);
        BigDecimal cashBeforeCancel = kit.portfolio.cash("USD");
        assertTrue(kit.portfolio.reservedCash("USD").compareTo(BigDecimal.ZERO) > 0);

        String orderId = bigOrder(kit).getOrderId();
        kit.engine.requestCancel(orderId, 50L);
        kit.engine.runTo(50L, "USD");
        Order cancelled = bigOrder(kit);
        assertEquals(OrderStatus.CANCELLED, cancelled.getStatus());
        eq(bd("900"), cancelled.getFilledQty(), "900 filled before cancel");
        eq(bd("100"), cancelled.remainingQty(), "100 cancelled unfilled");
        eq(bd("0.00"), kit.portfolio.reservedCash("USD"),
                "cancel releases the entire remaining reservation exactly once");
        eq(cashBeforeCancel, kit.portfolio.cash("USD"), "cancel never moves cash");
        log.info("[撤单清零] 撤掉剩余 100 股后 reserved=0 cash={} 不变", cashBeforeCancel);

        // 重复撤单：订单已终态，引擎忽略，占用不会出现“二次释放”（负值/异常）
        kit.engine.requestCancel(orderId, 60L);
        kit.engine.runTo(60L, "USD");
        eq(bd("0.00"), kit.portfolio.reservedCash("USD"), "double cancel does not double-release");
        log.info("[重复撤单] 终态再撤被忽略，reserved 仍=0");
    }

    @Test
    @DisplayName("同账户并发挂着的多笔买单各算各的：一单部分成交不压另一单，撤单只清自己的占用")
    void concurrentRestingBuysKeepIndependentReservations() {
        EngineTestKit kit = scenarioAfter(3);
        // 再挂一笔 CCC：限价 90、市价 97 不穿越，纯挂单；100 股最坏成本 9009.50（最低佣金）
        kit.engine.placeOrder(new OrderRequest("REST-CCC", Side.BUY, OrderType.LIMIT,
                "CCC", bd("100"), bd("90.00"), "USD"), 35L);
        kit.engine.runTo(35L, "USD");
        var ccc = kit.orderService.getByClientId("REST-CCC").orElseThrow();
        assertEquals(OrderStatus.NEW, ccc.getStatus(), "CCC rests away from market");

        // 总占用 = AAA 尾量 10010.00 + CCC 全额 9009.50，各单独立记账
        eq(bd("19019.50"), kit.portfolio.reservedCash("USD"),
                "two resting buys reserve independently");
        log.info("[并发挂单] AAA 尾量 + CCC 挂单 合计占用={}", kit.portfolio.reservedCash("USD"));

        // AAA 再无成交，直接撤 CCC：只能释放 CCC 自己的 9009.50
        kit.engine.requestCancel(ccc.getOrderId(), 45L);
        kit.engine.runTo(45L, "USD");
        eq(bd("10010.00"), kit.portfolio.reservedCash("USD"),
                "cancelling CCC releases only CCC reservation");
        log.info("[并发撤单] 撤 CCC 后只剩 AAA 尾量占用={}", kit.portfolio.reservedCash("USD"));

        // 再撤 AAA：全部清干净，不重不漏
        kit.engine.requestCancel(bigOrder(kit).getOrderId(), 50L);
        kit.engine.runTo(50L, "USD");
        eq(bd("0.00"), kit.portfolio.reservedCash("USD"), "all reservations cleared");
        log.info("[并发清零] 两单都结束，reserved=0");
    }

    @Test
    @DisplayName("全部成交时占用自动清零（不留尾量台账）")
    void fullyFilledOrderClearsReservationCompletely() {
        EngineTestKit kit = new EngineTestKit();
        kit.portfolio.deposit("USD", DEPOSIT);
        kit.engine.addFxRates(List.of(new FxRate("USD", "USD", 0L, BigDecimal.ONE)));
        kit.engine.addQuotes(List.of(quote("AAA", 10L, "97")));
        // 不给流动性约束 -> 1000 股一次性全部成交
        kit.engine.placeOrder(new OrderRequest("FULL-BUY", Side.BUY, OrderType.LIMIT,
                "AAA", BIG_QTY, LIMIT, "USD"), 10L);
        kit.engine.runTo(10L, "USD");
        var order = kit.orderService.getByClientId("FULL-BUY").orElseThrow();
        assertEquals(OrderStatus.FILLED, order.getStatus());
        eq(bd("1000"), order.getFilledQty(), "full quantity filled");
        eq(bd("0.00"), kit.portfolio.reservedCash("USD"), "fully filled order leaves zero reservation");
        // 1000 股按 97.0485 成交：gross=97048.50，佣金 29.11 -> 现金 22922.39
        eq(bd("22922.39"), kit.portfolio.cash("USD"), "cash settled exactly once");
        log.info("[全部成交] FILLED 后 reserved=0 cash={}", kit.portfolio.cash("USD"));
    }
}
