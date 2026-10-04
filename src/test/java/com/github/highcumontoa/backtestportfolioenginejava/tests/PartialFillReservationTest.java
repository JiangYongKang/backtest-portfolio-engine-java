package com.github.highcumontoa.backtestportfolioenginejava.tests;

import com.github.highcumontoa.backtestportfolioenginejava.config.CostConfig;
import com.github.highcumontoa.backtestportfolioenginejava.model.FxRate;
import com.github.highcumontoa.backtestportfolioenginejava.model.Order;
import com.github.highcumontoa.backtestportfolioenginejava.model.OrderRequest;
import com.github.highcumontoa.backtestportfolioenginejava.model.OrderStatus;
import com.github.highcumontoa.backtestportfolioenginejava.model.OrderType;
import com.github.highcumontoa.backtestportfolioenginejava.model.Quote;
import com.github.highcumontoa.backtestportfolioenginejava.model.Side;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 大额限价买单部分成交后的资金占用（reservation）正确性。
 *
 * <p>场景：一笔占掉账户大部分可用资金的限价买单走完整链路——
 * 多次部分成交 → 卖出一部分 → 撤掉剩余。要求：
 * <ul>
 *   <li>部分成交后，预占只对应“尚未成交的剩余量”，已成交部分当场让出占用，
 *       释放出的资金立刻可用于下一笔买单；</li>
 *   <li>新单确实超过“扣掉真实占用后的可用资金”时照常拒单（边界一致、不随累计成交次数抖动）；</li>
 *   <li>同账户多笔并发买单各算各的占用，撤单/全部成交时本单占用一次清干净，不重放不漏放。</li>
 * </ul>
 *
 * <p>为使占用金额随数量严格线性（消除单笔最低佣金造成的每笔固定项），本类使用
 * 零最低佣金配置；含最低佣金的口径由 {@link PartialFillSettlementTest} 覆盖。
 */
class PartialFillReservationTest {

    private static final Logger log = LoggerFactory.getLogger(PartialFillReservationTest.class);

    private static final BigDecimal LIMIT = new BigDecimal("10.00");

    /**
     * 零滑点/零佣金/零税：部分成交后剩余量最坏成本 = 限价 × 剩余量，占用金额随数量严格线性，
     * 便于逐笔精确对账。含滑点、最低佣金、税的口径由
     * {@link PartialFillSettlementTest}/{@link LargeOrderLotFlowTest} 等覆盖。
     */
    private static EngineTestKit kit() {
        CostConfig linear = new CostConfig(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                CostConfig.DEFAULT.priceScale(), CostConfig.DEFAULT.moneyScale(),
                CostConfig.DEFAULT.fxMaxStaleMillis());
        return new EngineTestKit(linear);
    }

    private static Quote quote(String symbol, long t, String px) {
        BigDecimal v = new BigDecimal(px);
        return new Quote(symbol, t, v, v, v, false);
    }

    private static void eq(BigDecimal expected, BigDecimal actual, String msg) {
        assertEquals(0, expected.compareTo(actual), msg + " actual=" + actual);
    }

    /** 按引擎同口径计算买入 quantity 股的最坏成本（限价 + 滑点 + 线性佣金，无税）。 */
    private static BigDecimal worstCost(EngineTestKit kit, String symbol,
                                        BigDecimal quantity, BigDecimal limitPx) {
        Order probe = new Order("PROBE", "PROBE", Side.BUY, OrderType.LIMIT,
                symbol, quantity, limitPx, "USD", 0L);
        return kit.fillService.computeCosts(probe, quantity, limitPx).netCashFlow().abs();
    }

    /**
     * 业务1：大额买单多次部分成交后，占用随剩余量同步下降；
     * 释放出的资金马上能让一笔本来买得起的单成交；真正超额的单仍被拒。
     */
    @Test
    void reservationShrinksWithRemainingQtyAndFreesCashForNextOrder() {
        EngineTestKit kit = kit();
        kit.portfolio.deposit("USD", new BigDecimal("110000")); // 整单最坏成本恰为 100000
        kit.engine.addFxRates(List.of(new FxRate("USD", "USD", 0L, BigDecimal.ONE)));
        kit.engine.addQuotes(List.of(
                quote("AAA", 10L, "10"), quote("AAA", 20L, "10"),
                quote("AAA", 30L, "10"), quote("AAA", 40L, "10")));
        // 每次只给小额可见量，强制连续多次部分成交
        kit.engine.addLiquidity("AAA", 10L, new BigDecimal("1000"));
        kit.engine.addLiquidity("AAA", 20L, new BigDecimal("1000"));

        BigDecimal qty = new BigDecimal("10000");
        BigDecimal fullCost = worstCost(kit, "AAA", qty, LIMIT);
        log.info("[业务1-占用随剩余量下降] 整单 10000 股最坏成本={} 入金 110000，大额单占去绝大部分资金",
                fullCost);

        kit.engine.placeOrder(new OrderRequest("BIG", Side.BUY, OrderType.LIMIT,
                "AAA", qty, LIMIT, "USD"), 10L);
        kit.engine.runTo(10L, "USD");

        var big = kit.orderService.getByClientId("BIG").orElseThrow();
        assertEquals(OrderStatus.PARTIALLY_FILLED, big.getStatus());
        BigDecimal filled1 = new BigDecimal("1000");
        BigDecimal remain1 = new BigDecimal("9000");
        eq(filled1, big.getFilledQty(), "first partial fill 1000");
        eq(remain1, big.remainingQty(), "9000 remaining");
        BigDecimal expectReserved1 = worstCost(kit, "AAA", remain1, LIMIT);
        eq(expectReserved1, kit.portfolio.reservedCash("USD"),
                "after fill#1 reservation must cover only the 9000 remaining");
        log.info("[业务1] 成交#1 后 filled=1000 remaining=9000 预占={} (应只对应剩余9000股) 可用={}",
                kit.portfolio.reservedCash("USD"), kit.portfolio.availableCash("USD"));

        kit.engine.runTo(20L, "USD");
        big = kit.orderService.getByClientId("BIG").orElseThrow();
        BigDecimal remain2 = new BigDecimal("8000");
        eq(remain2, big.remainingQty(), "8000 remaining after fill#2");
        BigDecimal expectReserved2 = worstCost(kit, "AAA", remain2, LIMIT);
        eq(expectReserved2, kit.portfolio.reservedCash("USD"),
                "after fill#2 reservation must cover only the 8000 remaining");
        log.info("[业务1] 成交#2 后 filled=2000 remaining=8000 预占={} (应只对应剩余8000股) 可用={}",
                kit.portfolio.reservedCash("USD"), kit.portfolio.availableCash("USD"));

        // 紧接着下一笔正常买单：规模在“扣掉真实占用后的可用资金”以内，必须成交
        BigDecimal affordableQty = new BigDecimal("400");
        BigDecimal affordableCost = worstCost(kit, "AAA", affordableQty, LIMIT);
        assertTrue(affordableCost.compareTo(kit.portfolio.availableCash("USD")) <= 0,
                "400 shares must be affordable against true available cash");
        kit.engine.placeOrder(new OrderRequest("NEXT-OK", Side.BUY, OrderType.LIMIT,
                "AAA", affordableQty, LIMIT, "USD"), 30L);
        kit.engine.runTo(30L, "USD");
        var nextOk = kit.orderService.getByClientId("NEXT-OK").orElseThrow();
        assertEquals(OrderStatus.FILLED, nextOk.getStatus(),
                "a genuinely affordable order must not be rejected while BIG rests partially filled");
        log.info("[业务1-释放立即可用] 大额单部分成交后下一笔 400 股买单成交（占用已让位于成交部分），status={}",
                nextOk.getStatus());

        // 边界：再来一笔真正超额的买单，必须按真实可用资金拒单，结论不受大额单/累计成交影响
        BigDecimal overQty = new BigDecimal("1000");
        BigDecimal overCost = worstCost(kit, "AAA", overQty, LIMIT);
        assertTrue(overCost.compareTo(kit.portfolio.availableCash("USD")) > 0,
                "1000 shares must exceed true available cash");
        kit.engine.placeOrder(new OrderRequest("NEXT-OVER", Side.BUY, OrderType.LIMIT,
                "AAA", overQty, LIMIT, "USD"), 40L);
        kit.engine.runTo(40L, "USD");
        var over = kit.orderService.getByClientId("NEXT-OVER").orElseThrow();
        assertEquals(OrderStatus.REJECTED, over.getStatus());
        assertEquals(com.github.highcumontoa.backtestportfolioenginejava.model.RejectReason.INSUFFICIENT_FUNDS,
                over.getRejectReason(), "over-budget order still rejected");
        log.info("[业务1-超额照拒] 1000 股买单需 {} 超过可用 {}，拒单原因={}",
                overCost, kit.portfolio.availableCash("USD"), over.getRejectReason());
    }

    /**
     * 业务2：多次部分成交累积、金额卡在可用资金附近时，可用/拒单边界必须稳定一致。
     * 在两次部分成交后的同一可用水位上，贴着边界的两笔单：可负担的成交、超一分钱的拒单。
     */
    @Test
    void rejectionBoundaryStaysConsistentAcrossCumulativePartialFills() {
        EngineTestKit kit = kit();
        kit.portfolio.deposit("USD", new BigDecimal("105000")); // 整单最坏成本恰为 100000
        kit.engine.addFxRates(List.of(new FxRate("USD", "USD", 0L, BigDecimal.ONE)));
        kit.engine.addQuotes(List.of(
                quote("AAA", 10L, "10"), quote("AAA", 20L, "10"),
                quote("AAA", 30L, "10"), quote("AAA", 40L, "10"),
                quote("AAA", 50L, "10")));
        kit.engine.addLiquidity("AAA", 10L, new BigDecimal("500"));
        kit.engine.addLiquidity("AAA", 20L, new BigDecimal("500"));

        kit.engine.placeOrder(new OrderRequest("BIG", Side.BUY, OrderType.LIMIT,
                "AAA", new BigDecimal("10000"), LIMIT, "USD"), 10L);
        kit.engine.runTo(10L, "USD");
        kit.engine.runTo(20L, "USD");
        var big = kit.orderService.getByClientId("BIG").orElseThrow();
        eq(new BigDecimal("1000"), big.getFilledQty(), "500+500 filled cumulatively");
        eq(new BigDecimal("9000"), big.remainingQty(), "9000 remaining across two ticks");
        // 核心断言：累计两次部分成交后，占用只对应剩余 9000 股
        BigDecimal reservedAtEdge = new BigDecimal("90000.00");
        eq(reservedAtEdge, kit.portfolio.reservedCash("USD"),
                "reservation tracks only the 9000 remaining after cumulative partial fills");

        BigDecimal available = kit.portfolio.availableCash("USD");
        // 用最坏成本反推贴边数量：floor(可用 / 每股最坏成本)
        BigDecimal perShare = worstCost(kit, "AAA", BigDecimal.ONE, LIMIT);
        BigDecimal affordableQty = available.divide(perShare, 0, java.math.RoundingMode.FLOOR);
        assertTrue(affordableQty.signum() > 0, "real available cash must allow some order");
        BigDecimal affordableCost = worstCost(kit, "AAA", affordableQty, LIMIT);
        assertTrue(affordableCost.compareTo(available) <= 0, "affordable order within boundary");

        kit.engine.placeOrder(new OrderRequest("EDGE-IN", Side.BUY, OrderType.LIMIT,
                "AAA", affordableQty, LIMIT, "USD"), 30L);
        kit.engine.runTo(30L, "USD");
        assertEquals(OrderStatus.FILLED,
                kit.orderService.getByClientId("EDGE-IN").orElseThrow().getStatus(),
                "edge order within available cash fills");
        log.info("[业务2-边界一致] 两次部分成交后贴边 {} 股（需{} <= 可用{}）成交",
                affordableQty, affordableCost, available);

        BigDecimal availableAfter = kit.portfolio.availableCash("USD");
        BigDecimal overQty = availableAfter.divide(perShare, 0, java.math.RoundingMode.FLOOR)
                .add(BigDecimal.ONE);
        BigDecimal overCost = worstCost(kit, "AAA", overQty, LIMIT);
        assertTrue(overCost.compareTo(availableAfter) > 0, "edge+1 share exceeds available");
        kit.engine.placeOrder(new OrderRequest("EDGE-OUT", Side.BUY, OrderType.LIMIT,
                "AAA", overQty, LIMIT, "USD"), 40L);
        kit.engine.runTo(40L, "USD");
        var over = kit.orderService.getByClientId("EDGE-OUT").orElseThrow();
        assertEquals(OrderStatus.REJECTED, over.getStatus());
        assertEquals(com.github.highcumontoa.backtestportfolioenginejava.model.RejectReason.INSUFFICIENT_FUNDS,
                over.getRejectReason());
        log.info("[业务2-边界一致] 贴边+1 股（需{} > 可用{}）稳定拒单，不随累计成交次数抖动",
                overCost, availableAfter);

        // t=50 无流动性约束 -> 大额单剩余量一次性全部成交，终态占用必须清零
        kit.engine.runTo(50L, "USD");
        big = kit.orderService.getByClientId("BIG").orElseThrow();
        assertEquals(OrderStatus.FILLED, big.getStatus());
        eq(BigDecimal.ZERO, kit.portfolio.reservedCash("USD"),
                "fully filled order leaves no reservation behind");
        log.info("[业务2] 大额单全部成交，占用一次清干净 reserved=0");
    }

    /**
     * 业务3：同一账户上多笔并发买单各算各的占用；某单部分成交不让出占用时不得压在总占用里，
     * 也不能把别的单算成自己的不可用；撤单时本单剩余占用一次清零，不重放不漏放。
     */
    @Test
    void concurrentRestingBuysReserveIndependentlyAndCancelReleasesExactlyOwnShare() {
        EngineTestKit kit = kit();
        kit.portfolio.deposit("USD", new BigDecimal("110000"));
        kit.engine.addFxRates(List.of(new FxRate("USD", "USD", 0L, BigDecimal.ONE)));
        // 两笔买单在不同标的上，各自限价在下行情景中才穿越，便于先挂单占用再分别部分成交
        kit.engine.addQuotes(List.of(
                quote("BBB", 10L, "11"), quote("CCC", 10L, "11"),
                quote("BBB", 20L, "10"), quote("CCC", 20L, "10"),
                quote("BBB", 30L, "10"), quote("CCC", 30L, "10"),
                quote("BBB", 40L, "10"), quote("CCC", 40L, "10")));
        kit.engine.addLiquidity("BBB", 20L, new BigDecimal("1000"));
        kit.engine.addLiquidity("CCC", 20L, new BigDecimal("1000"));
        // 撤单时刻无新增可见量：两单保持部分成交挂起，撤 B1 不影响 B2 的占用
        kit.engine.addLiquidity("BBB", 30L, BigDecimal.ZERO);
        kit.engine.addLiquidity("CCC", 30L, BigDecimal.ZERO);
        kit.engine.addLiquidity("BBB", 40L, BigDecimal.ZERO);
        kit.engine.addLiquidity("CCC", 40L, BigDecimal.ZERO);
        // t=50 只有 BBB 有卖出可见量；CCC 显式零可见，B2 保持挂起不续撮合
        kit.engine.addLiquidity("CCC", 50L, BigDecimal.ZERO);
        kit.engine.addLiquidity("CCC", 60L, BigDecimal.ZERO);

        BigDecimal qty = new BigDecimal("5000");
        BigDecimal oneCost = worstCost(kit, "BBB", qty, LIMIT);
        BigDecimal bothCost = oneCost.multiply(new BigDecimal("2"));
        assertTrue(bothCost.compareTo(new BigDecimal("100000")) <= 0,
                "both orders must fit at submit time");

        kit.engine.placeOrder(new OrderRequest("B1", Side.BUY, OrderType.LIMIT,
                "BBB", qty, LIMIT, "USD"), 10L);
        kit.engine.placeOrder(new OrderRequest("B2", Side.BUY, OrderType.LIMIT,
                "CCC", qty, LIMIT, "USD"), 10L);
        kit.engine.runTo(10L, "USD");
        var b1 = kit.orderService.getByClientId("B1").orElseThrow();
        var b2 = kit.orderService.getByClientId("B2").orElseThrow();
        assertEquals(OrderStatus.NEW, b1.getStatus());
        assertEquals(OrderStatus.NEW, b2.getStatus());
        eq(bothCost, kit.portfolio.reservedCash("USD"),
                "two resting buys reserve independently, sum of both worst costs");
        log.info("[业务3-各算各的] 两笔并发买单各占 {}，总占用={}（互不算作对方不可用）",
                oneCost, kit.portfolio.reservedCash("USD"));

        // t=20 两单各成交 1000 股：各自只应继续占用自己的 4000 股剩余量
        kit.engine.runTo(20L, "USD");
        b1 = kit.orderService.getByClientId("B1").orElseThrow();
        b2 = kit.orderService.getByClientId("B2").orElseThrow();
        eq(new BigDecimal("4000"), b1.remainingQty(), "B1 4000 remaining");
        eq(new BigDecimal("4000"), b2.remainingQty(), "B2 4000 remaining");
        BigDecimal eachRemain = worstCost(kit, "BBB", new BigDecimal("4000"), LIMIT);
        eq(eachRemain.multiply(new BigDecimal("2")), kit.portfolio.reservedCash("USD"),
                "each filled leg releases its own share; total covers only the two remainders");
        log.info("[业务3-各算各的] 两单各成交1000后总占用={}（=2 × 剩余4000股 {}），不把已成交部分继续压着",
                kit.portfolio.reservedCash("USD"), eachRemain);

        // 撤掉 B1：只释放 B1 自己剩余的占用，B2 的占用原样保留
        BigDecimal before = kit.portfolio.reservedCash("USD");
        kit.engine.requestCancel(b1.getOrderId(), 30L);
        kit.engine.runTo(30L, "USD");
        assertEquals(OrderStatus.CANCELLED,
                kit.orderService.getByClientId("B1").orElseThrow().getStatus());
        assertEquals(OrderStatus.PARTIALLY_FILLED,
                kit.orderService.getByClientId("B2").orElseThrow().getStatus(),
                "B2 untouched by B1 cancel");
        eq(eachRemain, kit.portfolio.reservedCash("USD"),
                "cancelling B1 releases exactly B1 remainder; B2 reservation intact");

        // 幂等保护：再次撤 B1 被状态机拒绝，占用不得被二次释放
        kit.engine.requestCancel(b1.getOrderId(), 40L);
        kit.engine.runTo(40L, "USD");
        eq(eachRemain, kit.portfolio.reservedCash("USD"),
                "double cancel must not release reservation twice");
        log.info("[业务3-撤单清零不重放] 撤B1后总占用={}（仅余B2的{}），重复撤单不二次释放",
                kit.portfolio.reservedCash("USD"), before.subtract(eachRemain));

        // 用户完整链路收尾：对已部分成交并撤掉尾量的 B1（持有 BBB 1000 股）卖出 500 股；
        // 卖出冻结的是持仓，绝不能影响另一标的在途买单 B2 的现金占用。
        kit.engine.addQuotes(List.of(quote("BBB", 50L, "10"), quote("CCC", 60L, "10")));
        kit.engine.addLiquidity("BBB", 50L, new BigDecimal("500"));
        kit.engine.placeOrder(new OrderRequest("SELL-PART", Side.SELL, OrderType.LIMIT,
                "BBB", new BigDecimal("500"), LIMIT, "USD"), 50L);
        kit.engine.runTo(50L, "USD");
        var sell = kit.orderService.getByClientId("SELL-PART").orElseThrow();
        assertEquals(OrderStatus.FILLED, sell.getStatus(), "partial sell settles against B1 holdings");
        eq(eachRemain, kit.portfolio.reservedCash("USD"),
                "sell on one symbol does not touch the resting buy reservation on another");
        log.info("[业务3-卖出不干扰买单占用] 卖出部分持仓500股后在途买单剩余占用仍为{}",
                kit.portfolio.reservedCash("USD"));

        // 再撤掉 B2 剩余 4000 股买单——本单剩余现金占用必须一次清干净。
        kit.engine.requestCancel(b2.getOrderId(), 60L);
        kit.engine.runTo(60L, "USD");
        assertEquals(OrderStatus.CANCELLED,
                kit.orderService.getByClientId("B2").orElseThrow().getStatus());
        eq(BigDecimal.ZERO, kit.portfolio.reservedCash("USD"),
                "cancel of the last resting buy releases the final reservation once");
        log.info("[业务3-撤剩余买单清零] 部分成交+部分卖出后撤掉最后一笔在途买单，总占用=0");
    }
}
