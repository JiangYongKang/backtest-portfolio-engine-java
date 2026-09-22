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
 * 部分成交 + 撤单下的资金/持仓守恒。
 * 买入 100 股限价单：t=10 仅可见 30 股（部分成交）、t=20 再成交 30 股，随后撤掉剩余 40 股。
 * 断言现金扣减恰为两笔成交（每笔最低佣金）、预占最终清零、持仓 60 股，全过程不重扣不漏扣。
 */
class PartialFillSettlementTest {

    private static final Logger log = LoggerFactory.getLogger(PartialFillSettlementTest.class);

    private static Quote quote(long t, String last) {
        BigDecimal v = new BigDecimal(last);
        return new Quote("AAA", t, v, v, v, false);
    }

    @Test
    void buyPartiallyFillsAcrossTicksThenCancelConservesCash() {
        EngineTestKit kit = new EngineTestKit();
        kit.portfolio.deposit("USD", new BigDecimal("100000"));
        kit.engine.addFxRates(List.of(new FxRate("USD", "USD", 0L, BigDecimal.ONE)));
        kit.engine.addQuotes(List.of(quote(10L, "10.00"), quote(20L, "10.00")));
        // 每个时刻仅 30 股可见量 -> 强制部分成交
        kit.engine.addLiquidity("AAA", 10L, new BigDecimal("30"));
        kit.engine.addLiquidity("AAA", 20L, new BigDecimal("30"));

        kit.engine.placeOrder(new OrderRequest("P1", Side.BUY, OrderType.LIMIT,
                "AAA", new BigDecimal("100"), new BigDecimal("10.00"), "USD"), 10L);

        ValuationResult v10 = kit.engine.runTo(10L, "USD");
        var at10 = kit.orderService.getByClientId("P1").orElseThrow();
        assertEquals(OrderStatus.PARTIALLY_FILLED, at10.getStatus());
        assertEquals(0, new BigDecimal("30").compareTo(at10.getFilledQty()));
        assertEquals(0, new BigDecimal("70").compareTo(at10.remainingQty()));
        BigDecimal reservedAfterFirst = kit.portfolio.reservedCash("USD");
        assertTrue(reservedAfterFirst.signum() > 0, "remaining qty still reserved");
        log.info("after fill#1: filled=30 remaining=70 reserved={} cash={}",
                reservedAfterFirst, kit.portfolio.cash("USD"));

        ValuationResult v20 = kit.engine.runTo(20L, "USD");
        var at20 = kit.orderService.getByClientId("P1").orElseThrow();
        assertEquals(OrderStatus.PARTIALLY_FILLED, at20.getStatus());
        assertEquals(0, new BigDecimal("60").compareTo(at20.getFilledQty()));

        // 撤销剩余 40 股
        kit.engine.requestCancel(at20.getOrderId(), 30L);
        kit.engine.runTo(30L, "USD");
        var final_ = kit.orderService.getByClientId("P1").orElseThrow();
        assertEquals(OrderStatus.CANCELLED, final_.getStatus());
        assertEquals(0, new BigDecimal("60").compareTo(final_.getFilledQty()));
        assertEquals(0, new BigDecimal("40").compareTo(final_.remainingQty()));
        assertEquals(0, BigDecimal.ZERO.compareTo(kit.portfolio.reservedCash("USD")),
                "reservation fully released after cancel");

        var pos = kit.portfolio.position("AAA");
        assertEquals(0, new BigDecimal("60").compareTo(pos.getQuantity()));
        assertEquals(0, new BigDecimal("60").compareTo(pos.getAvailableQuantity()));

        // 两笔成交：每笔 fill=10.005, gross=300.15, 最低佣金 5，无税
        // 单笔现金流出 = 300.15 + 5 = 305.15；两笔 = 610.30
        BigDecimal expectedCash = new BigDecimal("100000").subtract(new BigDecimal("610.30"));
        assertEquals(0, expectedCash.compareTo(kit.portfolio.cash("USD")),
                "cash must reflect exactly two partial fills: " + kit.portfolio.cash("USD"));
        log.info("PARTIAL CONSERVED: two fills x30, cash={} (expect 99389.70), position=60, reserved=0",
                kit.portfolio.cash("USD"));
    }

    @Test
    void oversellRejectedAndDoesNotChangePosition() {
        EngineTestKit kit = new EngineTestKit();
        kit.portfolio.deposit("USD", new BigDecimal("100000"));
        kit.engine.addFxRates(List.of(new FxRate("USD", "USD", 0L, BigDecimal.ONE)));
        kit.engine.addQuotes(List.of(quote(10L, "10")));
        kit.engine.placeOrder(new OrderRequest("S1", Side.SELL, OrderType.MARKET,
                "AAA", new BigDecimal("5"), null, "USD"), 10L);
        kit.engine.runTo(10L, "USD");
        var s1 = kit.orderService.getByClientId("S1").orElseThrow();
        assertEquals(OrderStatus.REJECTED, s1.getStatus());
        assertNotNull(s1.getRejectReason());
        assertNull(kit.portfolio.position("AAA"), "no short position created");
        assertEquals(0, new BigDecimal("100000").compareTo(kit.portfolio.cash("USD")),
                "cash untouched on rejected sell");
        log.info("oversell rejected reason={} cash unchanged", s1.getRejectReason());
    }

    @Test
    void restingAwayLimitCancelledReleasesReservation() {
        EngineTestKit kit = new EngineTestKit();
        kit.portfolio.deposit("USD", new BigDecimal("100000"));
        kit.engine.addFxRates(List.of(new FxRate("USD", "USD", 0L, BigDecimal.ONE)));
        kit.engine.addQuotes(List.of(quote(10L, "10"), quote(20L, "10")));
        kit.engine.placeOrder(new OrderRequest("P2", Side.BUY, OrderType.LIMIT,
                "AAA", new BigDecimal("40"), new BigDecimal("8.00"), "USD"), 10L);
        kit.engine.runTo(10L, "USD");
        var resting = kit.orderService.getByClientId("P2").orElseThrow();
        assertEquals(OrderStatus.NEW, resting.getStatus());
        assertTrue(kit.portfolio.reservedCash("USD").signum() > 0);
        kit.engine.requestCancel(resting.getOrderId(), 20L);
        kit.engine.runTo(20L, "USD");
        assertEquals(0, BigDecimal.ZERO.compareTo(kit.portfolio.reservedCash("USD")));
        assertEquals(0, new BigDecimal("100000").compareTo(kit.portfolio.cash("USD")),
                "never filled => cash fully intact");
        assertNull(kit.portfolio.position("AAA"));
        log.info("resting order cancelled, reservation released, cash intact");
    }
}
