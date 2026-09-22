package com.github.highcumontoa.backtestportfolioenginejava.tests;

import com.github.highcumontoa.backtestportfolioenginejava.model.FxRate;
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
 * 端到端：本地行情 -> 下单 -> 成交出账 -> 持仓估值。
 * 断言现金与持仓守恒；日志打印输入行情、订单与判定后的资金/持仓。
 */
class BacktestEndToEndTest {

    private static final Logger log = LoggerFactory.getLogger(BacktestEndToEndTest.class);

    private static Quote quote(long t, String last) {
        BigDecimal v = new BigDecimal(last);
        return new Quote("AAA", t, v, v, v, false);
    }

    @Test
    void buySettlesWithCostsAndValuationConservesCash() {
        EngineTestKit kit = new EngineTestKit();
        kit.portfolio.deposit("USD", new BigDecimal("100000"));
        kit.engine.addFxRates(List.of(new FxRate("USD", "USD", 0L, BigDecimal.ONE)));
        kit.engine.addQuotes(List.of(quote(10L, "100")));

        log.info("INPUT deposit=100000 USD, quote t=10 last=100, BUY 100 MARKET");
        kit.engine.placeOrder(new OrderRequest("C-1", Side.BUY, OrderType.MARKET,
                "AAA", new BigDecimal("100"), null, "USD"), 10L);

        ValuationResult v = kit.engine.runTo(10L, "USD");
        assertTrue(v.complete(), "single currency + live quote must be complete");

        var order = kit.orderService.getByClientId("C-1").orElseThrow();
        assertEquals(OrderStatus.FILLED, order.getStatus());
        assertEquals(0, new BigDecimal("100").compareTo(order.getFilledQty()));
        // fill price incl slippage = 100.05
        assertEquals(0, new BigDecimal("100.0500").compareTo(order.getAvgFillPrice()));

        var pos = kit.portfolio.position("AAA");
        assertEquals(0, new BigDecimal("100").compareTo(pos.getQuantity()));
        // gross 10005.00 + commission 5.00
        BigDecimal expectedCash = new BigDecimal("100000").subtract(new BigDecimal("10010.00"));
        assertEquals(0, expectedCash.compareTo(kit.portfolio.cash("USD")),
                "cash after buy: " + kit.portfolio.cash("USD"));
        assertEquals(0, BigDecimal.ZERO.compareTo(kit.portfolio.reservedCash("USD")),
                "no reservation left after full fill");
        // 市值按行情价（非成本价）估值：last 100 * 100 = 10000
        assertEquals(0, new BigDecimal("10000.00").compareTo(v.totalMarketValueBase()));
        // 总资产 = 现金 + 市值；相对初始资金的减少 = 费用 + 滑点 + 买入价高于市价的浮亏
        // 买入成本价 100.05 vs 现价 100 => 浮亏 5(滑点)，加佣金 5，合计减少 10
        BigDecimal nav = kit.portfolio.cash("USD").add(v.totalMarketValueBase());
        log.info("RESULT cash={} positionQty={} avgCost={} mv={} nav={} feeImpact={}",
                kit.portfolio.cash("USD"), pos.getQuantity(), pos.getAvgCost(),
                v.totalMarketValueBase(), nav,
                new BigDecimal("100000").subtract(nav));
        // 初始 100000 - NAV(89990+10000=99990) = 10，恰为佣金 5 + 滑点 5
        assertEquals(0, new BigDecimal("10.00").compareTo(
                new BigDecimal("100000").subtract(nav)), "total asset reduced only by fees+slippage");
    }

    @Test
    void suspendedWindowProducesNoTradeThenResumes() {
        EngineTestKit kit = new EngineTestKit();
        kit.portfolio.deposit("USD", new BigDecimal("100000"));
        kit.engine.addFxRates(List.of(new FxRate("USD", "USD", 0L, BigDecimal.ONE)));
        // t=10 停牌，t=20 恢复
        kit.engine.addQuotes(List.of(
                new Quote("AAA", 10L, null, null, null, true),
                quote(20L, "50")));

        kit.engine.placeOrder(new OrderRequest("C-S", Side.BUY, OrderType.MARKET,
                "AAA", new BigDecimal("10"), null, "USD"), 10L);
        ValuationResult v10 = kit.engine.runTo(10L, "USD");
        var at10 = kit.orderService.getByClientId("C-S").orElseThrow();
        // 市价单在停牌时点 runTo 结束被拒（MARKET_CLOSED），资金全额释放
        assertEquals(OrderStatus.REJECTED, at10.getStatus());
        assertEquals(0, BigDecimal.ZERO.compareTo(kit.portfolio.reservedCash("USD")));
        assertEquals(0, new BigDecimal("100000").compareTo(kit.portfolio.cash("USD")));
        log.info("suspended t=10: order REJECTED market closed, cash intact");

        // 限价单在停牌时挂起，恢复后续撮合
        kit.engine.placeOrder(new OrderRequest("C-L", Side.BUY, OrderType.LIMIT,
                "AAA", new BigDecimal("10"), new BigDecimal("60"), "USD"), 10L);
        ValuationResult v20 = kit.engine.runTo(20L, "USD");
        var at20 = kit.orderService.getByClientId("C-L").orElseThrow();
        assertEquals(OrderStatus.FILLED, at20.getStatus(), "limit order fills when market resumes");
        assertTrue(v20.complete());
        log.info("resumed t=20: limit BUY filled at avg={}, cash={}",
                at20.getAvgFillPrice(), kit.portfolio.cash("USD"));
    }
}
