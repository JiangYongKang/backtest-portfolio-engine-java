package com.github.highcumontoa.backtestportfolioenginejava.tests;

import com.github.highcumontoa.backtestportfolioenginejava.exception.InvalidArgumentApiException;
import com.github.highcumontoa.backtestportfolioenginejava.exception.StateConflictApiException;
import com.github.highcumontoa.backtestportfolioenginejava.model.Order;
import com.github.highcumontoa.backtestportfolioenginejava.model.OrderStatus;
import com.github.highcumontoa.backtestportfolioenginejava.model.OrderType;
import com.github.highcumontoa.backtestportfolioenginejava.model.RejectReason;
import com.github.highcumontoa.backtestportfolioenginejava.model.Side;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** 订单生命周期：部分成交均价、数量守恒、终态唯一且非法迁移可区分。 */
class OrderLifecycleTest {

    private static final Logger log = LoggerFactory.getLogger(OrderLifecycleTest.class);

    private Order newLimitBuy() {
        return new Order("O1", "C1", Side.BUY, OrderType.LIMIT, "AAA",
                new BigDecimal("100"), new BigDecimal("10.00"), "USD", 1L);
    }

    @Test
    void partialFillThenFullFillUpdatesAvgPriceAndState() {
        Order o = newLimitBuy();
        o.applyFill(new BigDecimal("40"), new BigDecimal("10.00"), 10L);
        assertEquals(OrderStatus.PARTIALLY_FILLED, o.getStatus());
        assertEquals(0, new BigDecimal("40").compareTo(o.getFilledQty()));

        o.applyFill(new BigDecimal("60"), new BigDecimal("12.00"), 20L);
        assertEquals(OrderStatus.FILLED, o.getStatus());
        assertEquals(0, BigDecimal.ZERO.compareTo(o.remainingQty()));
        // 加权均价 = (40*10 + 60*12)/100 = 11.20
        assertEquals(0, new BigDecimal("11.20000000").compareTo(o.getAvgFillPrice()),
                "weighted avg price");
        log.info("partial->full: filledQty={} avg={} status={}", o.getFilledQty(), o.getAvgFillPrice(), o.getStatus());
    }

    @Test
    void overfillIsRejected() {
        Order o = newLimitBuy();
        InvalidArgumentApiException ex = assertThrows(InvalidArgumentApiException.class,
                () -> o.applyFill(new BigDecimal("101"), new BigDecimal("10"), 1L));
        assertEquals("FILL_EXCEEDS_REMAINING", ex.getCode());
        log.info("overfill rejected code={}", ex.getCode());
    }

    @Test
    void filledOrderCannotBeCancelledOrReFilled() {
        Order o = newLimitBuy();
        o.applyFill(new BigDecimal("100"), new BigDecimal("10"), 1L);
        StateConflictApiException e1 = assertThrows(StateConflictApiException.class,
                () -> o.terminate(OrderStatus.CANCELLED, null, 2L));
        assertEquals("ILLEGAL_TRANSITION", e1.getCode());
        StateConflictApiException e2 = assertThrows(StateConflictApiException.class,
                () -> o.applyFill(new BigDecimal("1"), new BigDecimal("10"), 3L));
        assertEquals("ILLEGAL_TRANSITION", e2.getCode());
        log.info("terminal state protected status={}", o.getStatus());
    }

    @Test
    void cancelThenRejectIsRejected() {
        Order o = newLimitBuy();
        o.terminate(OrderStatus.CANCELLED, null, 1L);
        assertEquals(OrderStatus.CANCELLED, o.getStatus());
        assertThrows(StateConflictApiException.class,
                () -> o.terminate(OrderStatus.REJECTED, RejectReason.MARKET_CLOSED, 2L));
    }

    @Test
    void limitOrderWithoutPriceRejectedAtConstruction() {
        InvalidArgumentApiException ex = assertThrows(InvalidArgumentApiException.class,
                () -> new Order("O2", "C2", Side.BUY, OrderType.LIMIT, "AAA",
                        new BigDecimal("1"), null, "USD", 1L));
        assertEquals("INVALID_LIMIT_PRICE", ex.getCode());
    }

    @Test
    void nonPositiveQuantityRejected() {
        InvalidArgumentApiException ex = assertThrows(InvalidArgumentApiException.class,
                () -> new Order("O3", "C3", Side.BUY, OrderType.MARKET, "AAA",
                        BigDecimal.ZERO, null, "USD", 1L));
        assertEquals("INVALID_QUANTITY", ex.getCode());
    }
}
