package com.github.highcumontoa.backtestportfolioenginejava.tests;

import com.github.highcumontoa.backtestportfolioenginejava.model.CostLot;
import com.github.highcumontoa.backtestportfolioenginejava.model.CorporateAction;
import com.github.highcumontoa.backtestportfolioenginejava.model.CorporateActionType;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 引擎层：FIFO 批次 + 已实现盈亏与现金/成本的对账关系，以及公司行为后的批次口径。
 */
class LotRealizedPnlIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(LotRealizedPnlIntegrationTest.class);

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

    /**
     * 对账恒等式（单币种）：
     * 期末现金 + 剩余批次成本 = 外部入金 + 累计已实现盈亏 + 累计分红。
     */
    @Test
    void cashCostRealizedIdentityAfterBuySellRoundTrip() {
        EngineTestKit kit = new EngineTestKit();
        kit.portfolio.deposit("USD", bd("100000"));
        kit.engine.addFxRates(List.of(new FxRate("USD", "USD", 0L, BigDecimal.ONE)));
        kit.engine.addQuotes(List.of(quote(10L, "100"), quote(20L, "105")));

        // BUY 100 MARKET: fill=100.05, gross=10005, comm=5 -> 现金 -10010；批次成本 10010
        kit.engine.placeOrder(new OrderRequest("B1", Side.BUY, OrderType.MARKET,
                "AAA", bd("100"), null, "USD"), 10L);
        kit.engine.runTo(10L, "USD");

        // SELL 100 MARKET @105: fill=104.9475(卖价下浮 0.0005), gross=10494.75, comm=5,
        // tax=10494.75*0.001=10.49（HALF_UP）
        kit.engine.placeOrder(new OrderRequest("S1", Side.SELL, OrderType.MARKET,
                "AAA", bd("100"), null, "USD"), 20L);
        ValuationResult v = kit.engine.runTo(20L, "USD");

        var sell = kit.orderService.getByClientId("S1").orElseThrow();
        assertEquals(OrderStatus.FILLED, sell.getStatus());

        // realized = 10494.75 - 10010 - 5 - 10.49 = 469.26
        eq(bd("469.26"), kit.portfolio.lotService().realizedPnl("USD"), "realized pnl");
        // 现金 = 100000 - 10010 + (10494.75-5-10.49)= 100000 -10010 +10479.26 = 100469.26
        eq(bd("100469.26"), kit.portfolio.cash("USD"), "cash after round trip");
        eq(bd("0.00"), kit.portfolio.lotService().openCost("AAA"), "no open cost after full close");
        assertTrue(kit.portfolio.lotService().openLots("AAA").isEmpty());

        // 对账恒等式
        BigDecimal lhs = kit.portfolio.cash("USD").add(kit.portfolio.lotService().openCost("AAA"));
        BigDecimal rhs = bd("100000")
                .add(kit.portfolio.lotService().realizedPnl("USD"))
                .add(kit.portfolio.lotService().dividendIncome("USD"));
        eq(lhs, rhs, "cash + openCost = deposits + realized + dividend");

        // 估值结果字段一致
        eq(bd("469.26"), v.realizedPnlBase(), "valuation realized");
        eq(bd("0.00"), v.unrealizedPnlBase(), "flat after close");
        eq(bd("469.26"), v.totalPnlBase(), "total pnl = realized when flat");
        log.info("round-trip identity holds: cash={} realized={} totalPnl={}",
                kit.portfolio.cash("USD"), v.realizedPnlBase(), v.totalPnlBase());
    }

    /** 部分卖出后：剩余批次、已实现盈亏、未实现盈亏三者自洽。 */
    @Test
    void partialSellLeavesFifoLotAndSplitsRealizedFromUnrealized() {
        EngineTestKit kit = new EngineTestKit();
        kit.portfolio.deposit("USD", bd("100000"));
        kit.engine.addFxRates(List.of(new FxRate("USD", "USD", 0L, BigDecimal.ONE)));
        // 两次不同价买入，再卖出 150，剩余 50 来自第二批
        kit.engine.addQuotes(List.of(
                quote(10L, "100"), quote(20L, "100"), quote(30L, "105"), quote(40L, "110")));
        kit.engine.placeOrder(new OrderRequest("B1", Side.BUY, OrderType.LIMIT,
                "AAA", bd("100"), bd("101"), "USD"), 10L);
        kit.engine.runTo(10L, "USD");
        kit.engine.placeOrder(new OrderRequest("B2", Side.BUY, OrderType.LIMIT,
                "AAA", bd("100"), bd("101"), "USD"), 20L);
        kit.engine.runTo(20L, "USD");
        kit.engine.placeOrder(new OrderRequest("S1", Side.SELL, OrderType.LIMIT,
                "AAA", bd("150"), bd("104"), "USD"), 30L);
        kit.engine.runTo(30L, "USD");
        ValuationResult v = kit.engine.runTo(40L, "USD");

        List<CostLot> open = kit.portfolio.lotService().openLots("AAA");
        assertEquals(1, open.size());
        eq(bd("50"), open.get(0).remainingQuantity(), "50 shares remain from second lot");
        // 剩余成本 = 第二批成本的一半
        BigDecimal secondLotCost = bd("100").multiply(bd("100.0500")).add(bd("5")); // 10010.00
        eq(secondLotCost.divide(bd("2")), open.get(0).unitCost()
                .multiply(open.get(0).remainingQuantity()).setScale(2, java.math.RoundingMode.HALF_UP),
                "remaining 50 shares carry half of lot2 cost");

        List<LotRealization> rows = kit.portfolio.lotService().realizations("AAA");
        assertEquals(2, rows.size(), "sell of 150 hit two lots");

        // 浮盈：现价 110 * 50 = 5500；剩余成本 5005 -> 495
        eq(bd("495.00"), v.unrealizedPnlBase(), "unrealized on remaining 50 @110");
        // 已实现：(100 批成本 10010 + 50 批成本 5005) = 15015 成本；成交额 150*104.9475
        BigDecimal gross = rows.stream().map(LotRealization::grossProceeds)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal fees = rows.stream().map(r -> r.commission().add(r.tax()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal expectedRealized = gross.subtract(bd("15015.00")).subtract(fees);
        eq(expectedRealized, v.realizedPnlBase(), "realized = gross - cost - fees");

        BigDecimal lhs = kit.portfolio.cash("USD").add(kit.portfolio.lotService().openCost("AAA"));
        BigDecimal rhs = bd("100000").add(v.realizedPnlBase()).add(v.dividendIncomeBase());
        eq(lhs, rhs, "identity after partial sell");
        log.info("partial sell: realized={} unrealized={} openCost={}",
                v.realizedPnlBase(), v.unrealizedPnlBase(), kit.portfolio.lotService().openCost("AAA"));
    }

    /** 拆股后批次数量/单位成本调整、总成本不变；随后卖出的盈亏按调整后批次计算。 */
    @Test
    void splitAdjustsLotsThenSellRealizesCorrectly() {
        EngineTestKit kit = new EngineTestKit();
        kit.portfolio.deposit("USD", bd("100000"));
        kit.engine.addFxRates(List.of(new FxRate("USD", "USD", 0L, BigDecimal.ONE)));
        kit.engine.addQuotes(List.of(
                quote(10L, "100"), quote(30L, "50"), quote(40L, "50")));
        kit.engine.addCorporateActions(List.of(
                new CorporateAction("CA1", CorporateActionType.STOCK_SPLIT,
                        "AAA", 20L, bd("2"), null, null)));

        kit.engine.placeOrder(new OrderRequest("B1", Side.BUY, OrderType.MARKET,
                "AAA", bd("100"), null, "USD"), 10L);
        kit.engine.runTo(10L, "USD");
        BigDecimal costBefore = kit.portfolio.lotService().openCost("AAA");

        kit.engine.runTo(30L, "USD"); // 触发拆股
        List<CostLot> lots = kit.portfolio.lotService().openLots("AAA");
        eq(bd("200"), lots.get(0).remainingQuantity(), "lot qty doubled");
        eq(costBefore, kit.portfolio.lotService().openCost("AAA"), "lot total cost preserved");

        // 拆股后卖出全部 200 @49.975(卖价下浮)：盈亏相对调整后成本（=原成本）计算
        kit.engine.placeOrder(new OrderRequest("S1", Side.SELL, OrderType.MARKET,
                "AAA", bd("200"), null, "USD"), 40L);
        ValuationResult v = kit.engine.runTo(40L, "USD");
        // gross=9995；成本 10010；comm=5；tax=9.995->10.00 => realized = 9995-10010-5-10 = -30
        eq(bd("-30.00"), v.realizedPnlBase(), "realized after split reflects scaled basis");
        eq(bd("0.00"), kit.portfolio.lotService().openCost("AAA"), "flat");
        log.info("post-split sell realized={} (cost basis preserved through split)", v.realizedPnlBase());
    }

    /** 现金分红计入当期收益、不改批次；重复公司行为不重复派现。 */
    @Test
    void dividendBooksIncomeOnceAndLeavesLotsIntact() {
        EngineTestKit kit = new EngineTestKit();
        kit.portfolio.deposit("USD", bd("100000"));
        kit.engine.addFxRates(List.of(new FxRate("USD", "USD", 0L, BigDecimal.ONE)));
        kit.engine.addQuotes(List.of(quote(10L, "100"), quote(30L, "100")));
        kit.engine.placeOrder(new OrderRequest("B1", Side.BUY, OrderType.MARKET,
                "AAA", bd("100"), null, "USD"), 10L);
        kit.engine.runTo(10L, "USD");

        CorporateAction div = new CorporateAction("D1", CorporateActionType.CASH_DIVIDEND,
                "AAA", 20L, null, bd("0.50"), "USD");
        kit.engine.addCorporateActions(List.of(div));
        ValuationResult v20 = kit.engine.runTo(20L, "USD");
        eq(bd("50.00"), kit.portfolio.lotService().dividendIncome("USD"), "dividend 100*0.5");
        eq(bd("50.00"), v20.dividendIncomeBase(), "valuation dividend");
        eq(bd("100"), kit.portfolio.lotService().openLots("AAA").get(0).remainingQuantity(),
                "lot qty unchanged by dividend");

        BigDecimal cashBefore = kit.portfolio.cash("USD");
        // 重复应用同一事件：幂等无副作用
        assertFalse(kit.corporateActionService.apply(div));
        eq(cashBefore, kit.portfolio.cash("USD"), "no double cash");
        eq(bd("50.00"), kit.portfolio.lotService().dividendIncome("USD"), "no double dividend");
        log.info("dividend once=50, duplicate corporate action idempotent");
    }
}
