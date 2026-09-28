package com.github.highcumontoa.backtestportfolioenginejava.tests;

import com.github.highcumontoa.backtestportfolioenginejava.model.FxRate;
import com.github.highcumontoa.backtestportfolioenginejava.model.OrderRequest;
import com.github.highcumontoa.backtestportfolioenginejava.model.OrderType;
import com.github.highcumontoa.backtestportfolioenginejava.model.Quote;
import com.github.highcumontoa.backtestportfolioenginejava.model.Side;
import com.github.highcumontoa.backtestportfolioenginejava.model.ValuationResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 多币种已实现盈亏/分红折算到基础币种，以及同一输入重放结果完全一致。
 */
class LotMultiCcyAndReplayTest {

    private record Snapshot(BigDecimal cash, BigDecimal realized, BigDecimal dividend,
                            BigDecimal openCost, int lots, int realizations) { }

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    private static void eq(BigDecimal e, BigDecimal a, String m) {
        assertEquals(0, e.compareTo(a), m + " actual=" + a);
    }

    private static Quote q(String sym, long t, String last) {
        BigDecimal v = new BigDecimal(last);
        return new Quote(sym, t, v, v, v, false);
    }

    /** EUR 标的卖出产生已实现盈亏与分红，估值按 EUR->USD 折算。 */
    @Test
    void realizedAndDividendConvertViaFx() {
        EngineTestKit kit = new EngineTestKit();
        kit.portfolio.deposit("EUR", bd("100000"));
        kit.engine.addFxRates(List.of(new FxRate("EUR", "USD", 0L, bd("1.10"))));
        kit.engine.addQuotes(List.of(q("EZZ", 10L, "100"), q("EZZ", 20L, "110")));
        kit.engine.placeOrder(new OrderRequest("B1", Side.BUY, OrderType.MARKET,
                "EZZ", bd("100"), null, "EUR"), 10L);
        kit.engine.runTo(10L, "USD");
        kit.engine.placeOrder(new OrderRequest("S1", Side.SELL, OrderType.MARKET,
                "EZZ", bd("100"), null, "EUR"), 20L);
        // 分红 100*1 = 100 EUR（持仓在 t=20 卖出前仍为 100；此处分红生效放在 t=15）
        kit.engine.addCorporateActions(List.of(new com.github.highcumontoa.backtestportfolioenginejava
                .model.CorporateAction("D1",
                com.github.highcumontoa.backtestportfolioenginejava.model.CorporateActionType.CASH_DIVIDEND,
                "EZZ", 15L, null, bd("1.00"), "EUR")));
        ValuationResult v = kit.engine.runTo(20L, "USD");

        BigDecimal realizedEur = kit.portfolio.lotService().realizedPnl("EUR");
        BigDecimal divEur = kit.portfolio.lotService().dividendIncome("EUR");
        assertTrue(realizedEur.signum() > 0, "positive realized in EUR");
        eq(bd("100.00"), divEur, "100 EUR dividend");
        // 折算：*1.10
        eq(divEur.multiply(bd("1.10")).setScale(2, java.math.RoundingMode.HALF_UP),
                v.dividendIncomeBase(), "dividend converted at 1.10");
        eq(realizedEur.multiply(bd("1.10")).setScale(2, java.math.RoundingMode.HALF_UP),
                v.realizedPnlBase(), "realized converted at 1.10");
        eq(v.realizedPnlBase().add(v.dividendIncomeBase()).add(v.unrealizedPnlBase()),
                v.totalPnlBase(), "total = realized + unrealized + dividend");
        assertTrue(v.complete());
    }

    /** 同一批输入跑两遍，批次、已实现盈亏、分红与现金必须逐位一致。 */
    @Test
    void sameInputReplaysIdentically() {
        Snapshot a = runOnce();
        Snapshot b = runOnce();
        assertEquals(a, b, "replaying identical input yields identical books");
    }

    private Snapshot runOnce() {
        EngineTestKit kit = new EngineTestKit();
        kit.portfolio.deposit("USD", bd("100000"));
        kit.engine.addFxRates(List.of(new FxRate("USD", "USD", 0L, BigDecimal.ONE)));
        kit.engine.addQuotes(List.of(q("AAA", 10L, "100"), q("AAA", 20L, "100"),
                q("AAA", 30L, "105"), q("AAA", 40L, "100")));
        kit.engine.addLiquidity("AAA", 10L, bd("30"));
        kit.engine.addLiquidity("AAA", 20L, bd("30"));
        kit.engine.placeOrder(new OrderRequest("B1", Side.BUY, OrderType.LIMIT,
                "AAA", bd("100"), bd("100"), "USD"), 10L);
        kit.engine.runTo(10L, "USD");
        kit.engine.runTo(20L, "USD");
        kit.engine.placeOrder(new OrderRequest("S1", Side.SELL, OrderType.MARKET,
                "AAA", bd("40"), null, "USD"), 30L);
        kit.engine.runTo(30L, "USD");
        kit.engine.addCorporateActions(List.of(new com.github.highcumontoa.backtestportfolioenginejava
                .model.CorporateAction("D1",
                com.github.highcumontoa.backtestportfolioenginejava.model.CorporateActionType.CASH_DIVIDEND,
                "AAA", 35L, null, bd("0.25"), "USD")));
        var cancel = kit.orderService.getByClientId("B1").orElseThrow();
        kit.engine.requestCancel(cancel.getOrderId(), 40L);
        kit.engine.runTo(40L, "USD");
        var lots = kit.portfolio.lotService();
        return new Snapshot(kit.portfolio.cash("USD"), lots.realizedPnl("USD"),
                lots.dividendIncome("USD"), lots.openCost("AAA"),
                lots.openLots("AAA").size(), lots.realizations("AAA").size());
    }
}
