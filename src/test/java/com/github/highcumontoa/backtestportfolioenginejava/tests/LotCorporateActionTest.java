package com.github.highcumontoa.backtestportfolioenginejava.tests;

import com.github.highcumontoa.backtestportfolioenginejava.model.CorporateAction;
import com.github.highcumontoa.backtestportfolioenginejava.model.CorporateActionType;
import com.github.highcumontoa.backtestportfolioenginejava.model.CostLot;
import com.github.highcumontoa.backtestportfolioenginejava.model.FxRate;
import com.github.highcumontoa.backtestportfolioenginejava.model.Quote;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 公司行为对 FIFO 批次的影响：拆/合股调整批次数量与单位成本且总成本不变、
 * 重复事件批次也不二次调整；分红按生效时点数量计一次、批次不变。
 */
class LotCorporateActionTest {

    private static final Logger log = LoggerFactory.getLogger(LotCorporateActionTest.class);

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
    void duplicateSplitAppliedToLotsOnce() {
        EngineTestKit kit = new EngineTestKit();
        kit.portfolio.deposit("USD", bd("100000"));
        kit.portfolio.applyFill(new com.github.highcumontoa.backtestportfolioenginejava.model.Fill(
                "B1", "O1", "AAA", com.github.highcumontoa.backtestportfolioenginejava.model.Side.BUY,
                bd("100"), bd("10.00"), 1L, bd("5"), bd("0")), "USD"); // 成本 1005

        var ca = kit.corporateActionService;
        CorporateAction split = new CorporateAction("CA1", CorporateActionType.STOCK_SPLIT,
                "AAA", 10L, bd("2"), null, null);
        assertTrue(ca.apply(split));
        CostLot afterFirst = kit.portfolio.lotService().openLots("AAA").get(0);
        eq(bd("200"), afterFirst.remainingQuantity(), "qty doubled once");
        eq(bd("1005.00"), kit.portfolio.lotService().openCost("AAA"), "cost preserved");

        // 重复应用：批次不二次调整
        assertFalse(ca.apply(split), "duplicate split ignored");
        CostLot afterSecond = kit.portfolio.lotService().openLots("AAA").get(0);
        eq(bd("200"), afterSecond.remainingQuantity(), "qty not doubled twice");
        eq(bd("1005.00"), kit.portfolio.lotService().openCost("AAA"), "cost still preserved");
        // 加权口径持仓与批次口径数量一致
        eq(bd("200"), kit.portfolio.position("AAA").getQuantity(), "position agrees with lots");
        log.info("duplicate split: lot qty stays 200, cost 1005");
    }

    @Test
    void reverseSplitScalesEveryOpenLotAndKeepsAggregateCost() {
        EngineTestKit kit = new EngineTestKit();
        kit.portfolio.deposit("USD", bd("100000"));
        kit.portfolio.applyFill(new com.github.highcumontoa.backtestportfolioenginejava.model.Fill(
                "B1", "O1", "AAA", com.github.highcumontoa.backtestportfolioenginejava.model.Side.BUY,
                bd("100"), bd("10.00"), 1L, bd("0"), bd("0")), "USD");
        kit.portfolio.applyFill(new com.github.highcumontoa.backtestportfolioenginejava.model.Fill(
                "B2", "O2", "AAA", com.github.highcumontoa.backtestportfolioenginejava.model.Side.BUY,
                bd("50"), bd("20.00"), 2L, bd("0"), bd("0")), "USD");
        BigDecimal costBefore = kit.portfolio.lotService().openCost("AAA"); // 1000+1000=2000

        kit.corporateActionService.apply(new CorporateAction("CA2",
                CorporateActionType.REVERSE_SPLIT, "AAA", 10L, bd("0.1"), null, null));
        var lots = kit.portfolio.lotService().openLots("AAA");
        eq(bd("10"), lots.get(0).remainingQuantity(), "lot1 100 -> 10");
        eq(bd("5"), lots.get(1).remainingQuantity(), "lot2 50 -> 5");
        eq(costBefore, kit.portfolio.lotService().openCost("AAA"), "aggregate cost invariant");
        // 单位成本等比放大 10 倍：lot1 100/股，lot2 200/股
        eq(bd("100.00000000"), lots.get(0).unitCost(), "lot1 unit cost x10");
        eq(bd("200.00000000"), lots.get(1).unitCost(), "lot2 unit cost x10");
        log.info("reverse split across two lots: qty 10+5, aggregate cost unchanged 2000");
    }

    @Test
    void dividendUsesQuantityAtEffectiveTimeAndDoesNotMoveLots() {
        EngineTestKit kit = new EngineTestKit();
        kit.portfolio.deposit("USD", bd("100000"));
        kit.engine.addFxRates(List.of(new FxRate("USD", "USD", 0L, BigDecimal.ONE)));
        kit.engine.addQuotes(List.of(quote(10L, "100"), quote(40L, "100")));
        // t=10 买入 100
        kit.engine.placeOrder(new com.github.highcumontoa.backtestportfolioenginejava.model.OrderRequest(
                "B1", com.github.highcumontoa.backtestportfolioenginejava.model.Side.BUY,
                com.github.highcumontoa.backtestportfolioenginejava.model.OrderType.MARKET,
                "AAA", bd("100"), null, "USD"), 10L);
        kit.engine.runTo(10L, "USD");

        // 分红生效 t=20：持有 100，派 0.5 -> 50
        CorporateAction div = new CorporateAction("D1", CorporateActionType.CASH_DIVIDEND,
                "AAA", 20L, null, bd("0.50"), "USD");
        kit.engine.addCorporateActions(List.of(div));
        var v20 = kit.engine.runTo(20L, "USD");
        eq(bd("50.00"), kit.portfolio.lotService().dividendIncome("USD"), "dividend on 100 shares");
        eq(bd("50.00"), v20.dividendIncomeBase(), "valuation exposes dividend");
        eq(bd("100"), kit.portfolio.lotService().openLots("AAA").get(0).remainingQuantity(),
                "dividend does not alter lot qty");
        // 拆股在分红之后不影响已计分红
        kit.engine.addCorporateActions(List.of(new CorporateAction("CA1",
                CorporateActionType.STOCK_SPLIT, "AAA", 30L, bd("2"), null, null)));
        kit.engine.runTo(40L, "USD");
        eq(bd("50.00"), kit.portfolio.lotService().dividendIncome("USD"),
                "later split does not change booked dividend");
        eq(bd("200"), kit.portfolio.lotService().openLots("AAA").get(0).remainingQuantity(),
                "split still scales lot after dividend");
        log.info("dividend booked at effective-time qty; later split independent");
    }
}
