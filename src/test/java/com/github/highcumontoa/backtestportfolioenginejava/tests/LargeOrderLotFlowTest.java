package com.github.highcumontoa.backtestportfolioenginejava.tests;

import com.github.highcumontoa.backtestportfolioenginejava.model.CostLot;
import com.github.highcumontoa.backtestportfolioenginejava.model.FxRate;
import com.github.highcumontoa.backtestportfolioenginejava.model.LotRealization;
import com.github.highcumontoa.backtestportfolioenginejava.model.OrderRequest;
import com.github.highcumontoa.backtestportfolioenginejava.model.OrderStatus;
import com.github.highcumontoa.backtestportfolioenginejava.model.OrderType;
import com.github.highcumontoa.backtestportfolioenginejava.model.Quote;
import com.github.highcumontoa.backtestportfolioenginejava.model.Side;
import com.github.highcumontoa.backtestportfolioenginejava.model.ValuationResult;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 大额单链路：一笔占账户大部分可用资金的买单，经历
 * “多次部分成交 → 卖出一部分 → 再撤掉剩余买单”。
 * 校验资金占用/现金余额不重扣不漏扣、批次与已实现盈亏按笔可对账、恒等式成立。
 */
class LargeOrderLotFlowTest {

    private static final Logger log = LoggerFactory.getLogger(LargeOrderLotFlowTest.class);

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    private static void eq(BigDecimal expected, BigDecimal actual, String msg) {
        assertEquals(0, expected.compareTo(actual), msg + " actual=" + actual);
    }

    private static Quote quote(long t, String last) {
        BigDecimal v = new BigDecimal(last);
        return new Quote("AAA", t, v, v, v, false);
    }

    @Test
    void largeBuyPartiallyFillsThenPartialSellThenCancelRemainder() {
        EngineTestKit kit = new EngineTestKit();
        // 账户 120000；1000 股限价 100 的买单最坏成本约 100080，占用绝大部分资金。
        kit.portfolio.deposit("USD", bd("120000"));
        kit.engine.addFxRates(List.of(new FxRate("USD", "USD", 0L, BigDecimal.ONE)));
        kit.engine.addQuotes(List.of(
                quote(10L, "100"), quote(20L, "100"), quote(30L, "100"),
                quote(35L, "102"), quote(40L, "102"), quote(50L, "102")));
        // 买单每个时刻仅 300 股可见量；t=35 价格涨到 102 高于买单限价 100，买单未穿越自然挂起，
        // 该时刻流动性充足只让限价 101 的卖出成交 400。
        kit.engine.addLiquidity("AAA", 10L, bd("300"));
        kit.engine.addLiquidity("AAA", 20L, bd("300"));
        kit.engine.addLiquidity("AAA", 30L, bd("300"));
        kit.engine.addLiquidity("AAA", 35L, bd("1000"));

        // 限价 100：含滑点买价 100.05 不超过限价可成交；最坏预占按限价 100 估算
        kit.engine.placeOrder(new OrderRequest("BIG-BUY", Side.BUY, OrderType.LIMIT,
                "AAA", bd("1000"), bd("100.00"), "USD"), 10L);

        ValuationResult v10 = kit.engine.runTo(10L, "USD");
        var order = kit.orderService.getByClientId("BIG-BUY").orElseThrow();
        assertEquals(OrderStatus.PARTIALLY_FILLED, order.getStatus());
        eq(bd("300"), order.getFilledQty(), "first partial fill 300");

        // t=20、t=30 再各成交 300 -> 累计 900
        kit.engine.runTo(20L, "USD");
        ValuationResult v30 = kit.engine.runTo(30L, "USD");
        order = kit.orderService.getByClientId("BIG-BUY").orElseThrow();
        eq(bd("900"), order.getFilledQty(), "three partial fills = 900");
        eq(bd("100"), order.remainingQty(), "100 still resting");
        assertTrue(kit.portfolio.reservedCash("USD").signum() > 0, "remainder still reserved");

        // 三次部分成交各开一个批次，每批 300 股
        List<CostLot> lotsAt30 = kit.portfolio.lotService().openLots("AAA");
        assertEquals(3, lotsAt30.size(), "three buy lots, one per partial fill");
        for (CostLot l : lotsAt30) {
            eq(bd("300"), l.remainingQuantity(), "each lot holds 300");
        }

        // t=35 卖出 400 股（价格 102），消耗前两个批次（300+100），与在途买单互不干扰
        kit.engine.placeOrder(new OrderRequest("PART-SELL", Side.SELL, OrderType.LIMIT,
                "AAA", bd("400"), bd("101.00"), "USD"), 35L);
        ValuationResult v40 = kit.engine.runTo(35L, "USD");
        var sell = kit.orderService.getByClientId("PART-SELL").orElseThrow();
        assertEquals(OrderStatus.FILLED, sell.getStatus(), "sell settles against held 900");

        // 卖出 FIFO 明细：批1 全 300 + 批2 的 100
        List<LotRealization> rows = kit.portfolio.lotService().realizations("AAA");
        assertEquals(2, rows.size(), "400 sell spans lot1(300) + lot2(100)");
        eq(bd("300"), rows.get(0).quantity(), "lot1 fully consumed");
        eq(bd("100"), rows.get(1).quantity(), "100 taken from lot2");
        BigDecimal soldRealized = rows.stream().map(LotRealization::realizedPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        eq(soldRealized.setScale(2, RoundingMode.HALF_UP), v40.realizedPnlBase(),
                "valuation realized matches lot ledger");

        // 剩余批次：批2 的 200 + 批3 的 300 = 500（批2 每 100 股成本 10008）
        List<CostLot> openAfterSell = kit.portfolio.lotService().openLots("AAA");
        BigDecimal openQty = openAfterSell.stream().map(CostLot::remainingQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        eq(bd("500"), openQty, "200 (lot2) + 300 (lot3) remain");
        var pos = kit.portfolio.position("AAA");
        eq(bd("500"), pos.getQuantity(), "position qty 900-400");
        eq(bd("500"), pos.getAvailableQuantity(), "no frozen shares after sell settled");

        // t=50 撤掉买单剩余 100：预占清零，现金/持仓/批次均不因撤单变动
        BigDecimal cashBeforeCancel = kit.portfolio.cash("USD");
        BigDecimal reservedBeforeCancel = kit.portfolio.reservedCash("USD");
        assertTrue(reservedBeforeCancel.signum() > 0);
        order = kit.orderService.getByClientId("BIG-BUY").orElseThrow();
        kit.engine.requestCancel(order.getOrderId(), 50L);
        kit.engine.runTo(50L, "USD");
        var cancelled = kit.orderService.getByClientId("BIG-BUY").orElseThrow();
        assertEquals(OrderStatus.CANCELLED, cancelled.getStatus());
        eq(bd("900"), cancelled.getFilledQty(), "cancelled with 900 filled");
        eq(bd("0.00"), kit.portfolio.reservedCash("USD"), "reservation fully released");
        eq(cashBeforeCancel, kit.portfolio.cash("USD"), "cancel does not move cash");

        // 每笔买入现金流出 = 300*100.05 + 9(佣金=30015*0.0003) = 30024；三笔 = 90072
        // 卖出 400：fill=101.949, gross=40779.60, comm=12.23, tax=40.78 -> 现金流入 40726.59
        // 期末现金 = 120000 - 90072 + 40726.59 = 70654.59
        eq(bd("70654.59"), kit.portfolio.cash("USD"), "cash exactly = 3 buys - 1 sell net");

        // 剩余 500 股批次成本 = 批2 剩余 200/300 + 批3 全 300
        // 每批成本 30024；批2 卖出 100 股按比例消耗成本 30024*100/300=10008.00，
        // 剩余 = 30024 - 10008.00 = 20016.00；批3 = 30024；合计 50040.00
        BigDecimal openCost = kit.portfolio.lotService().openCost("AAA");
        eq(bd("50040.00"), openCost, "remaining lot cost across lot2/lot3");

        // 对账恒等式：现金 + 剩余成本 = 入金 + 已实现 + 分红
        BigDecimal lhs = kit.portfolio.cash("USD").add(openCost);
        BigDecimal rhs = bd("120000")
                .add(kit.portfolio.lotService().realizedPnl("USD"))
                .add(kit.portfolio.lotService().dividendIncome("USD"));
        eq(lhs, rhs, "cash + openCost = deposit + realized + dividend");

        // 已实现 = 40779.60 - (30024+10008.00=40032) - 12.23 - 40.78 = 694.59
        eq(bd("694.59"), kit.portfolio.lotService().realizedPnl("USD"), "realized from 400 sold");
        log.info("LARGE FLOW OK: cash={} openCost={} realized={} identity {} = {}",
                kit.portfolio.cash("USD"), openCost,
                kit.portfolio.lotService().realizedPnl("USD"), lhs, rhs);
    }
}
