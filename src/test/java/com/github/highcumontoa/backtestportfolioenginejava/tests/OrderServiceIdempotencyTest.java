package com.github.highcumontoa.backtestportfolioenginejava.tests;

import com.github.highcumontoa.backtestportfolioenginejava.exception.StateConflictApiException;
import com.github.highcumontoa.backtestportfolioenginejava.model.Order;
import com.github.highcumontoa.backtestportfolioenginejava.model.OrderRequest;
import com.github.highcumontoa.backtestportfolioenginejava.model.OrderStatus;
import com.github.highcumontoa.backtestportfolioenginejava.model.OrderType;
import com.github.highcumontoa.backtestportfolioenginejava.model.RejectReason;
import com.github.highcumontoa.backtestportfolioenginejava.model.Side;
import com.github.highcumontoa.backtestportfolioenginejava.service.OrderService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** 订单幂等键与状态迁移：重复提交唯一、终态迁移被拒且原因可区分。 */
class OrderServiceIdempotencyTest {

    private static final Logger log = LoggerFactory.getLogger(OrderServiceIdempotencyTest.class);

    private OrderRequest req(String clientId) {
        return new OrderRequest(clientId, Side.BUY, OrderType.LIMIT, "AAA",
                new BigDecimal("10"), new BigDecimal("100"), "USD");
    }

    @Test
    void duplicateClientOrderIdCreatesOnce() {
        OrderService svc = new OrderService();
        var first = svc.submit(req("K1"), 1L);
        var second = svc.submit(req("K1"), 1L);
        assertTrue(first.created());
        assertFalse(second.created(), "duplicate idempotency key must not create again");
        assertEquals(first.order().getOrderId(), second.order().getOrderId());
        assertEquals(1, svc.allOrders().size());
        log.info("idempotent submit orderId={} createdOnce=true", first.order().getOrderId());
    }

    @Test
    void cancelTerminalOrderRejected() {
        OrderService svc = new OrderService();
        Order o = svc.submit(req("K2"), 1L).order();
        svc.reject(o.getOrderId(), RejectReason.MARKET_CLOSED, 2L);
        StateConflictApiException ex = assertThrows(StateConflictApiException.class,
                () -> svc.cancel(o.getOrderId(), 3L));
        assertEquals("ILLEGAL_TRANSITION", ex.getCode());
        assertEquals(OrderStatus.REJECTED, svc.get(o.getOrderId()).orElseThrow().getStatus());
        assertEquals(RejectReason.MARKET_CLOSED,
                svc.get(o.getOrderId()).orElseThrow().getRejectReason());
        log.info("terminal cancel rejected, reject reason preserved");
    }

    @Test
    void applyFillThenCancelLeavesCancelledWithPartialState() {
        OrderService svc = new OrderService();
        Order o = svc.submit(req("K3"), 1L).order();
        svc.applyFill(o.getOrderId(), new BigDecimal("4"), new BigDecimal("99"), 2L);
        Order cancelled = svc.cancel(o.getOrderId(), 3L);
        assertEquals(OrderStatus.CANCELLED, cancelled.getStatus());
        assertEquals(0, new BigDecimal("4").compareTo(cancelled.getFilledQty()));
        assertEquals(0, new BigDecimal("6").compareTo(cancelled.remainingQty()));
        log.info("partially filled then cancelled: filled={} remaining={}",
                cancelled.getFilledQty(), cancelled.remainingQty());
    }
}
