package com.github.highcumontoa.backtestportfolioenginejava.tests;

import com.github.highcumontoa.backtestportfolioenginejava.domain.Order;
import com.github.highcumontoa.backtestportfolioenginejava.domain.OrderType;
import com.github.highcumontoa.backtestportfolioenginejava.domain.Side;
import com.github.highcumontoa.backtestportfolioenginejava.error.ErrorCategory;
import com.github.highcumontoa.backtestportfolioenginejava.error.InvalidParameterException;
import com.github.highcumontoa.backtestportfolioenginejava.error.StateConflictException;
import com.github.highcumontoa.backtestportfolioenginejava.error.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 异常分类可区分，且处理器不泄露内部信息。 */
class ExceptionCategoryTest {

    @Test
    void categoriesAndCodesAreDistinguishable() {
        InvalidParameterException bad = new InvalidParameterException("bad qty");
        assertEquals(ErrorCategory.INVALID_PARAMETER, bad.getCategory());
        assertEquals(HttpStatus.BAD_REQUEST,
                new GlobalExceptionHandler().handleApi(bad).getStatusCode());

        StateConflictException conflict = new StateConflictException("already filled");
        assertEquals(ErrorCategory.STATE_CONFLICT, conflict.getCategory());
        assertEquals(HttpStatus.CONFLICT,
                new GlobalExceptionHandler().handleApi(conflict).getStatusCode());
    }

    @Test
    void invalidOrderArgumentsAreInvalidParameter() {
        try {
            new Order("o1", null, "acc", "AAA", Side.BUY, OrderType.LIMIT,
                    new BigDecimal("0"), new BigDecimal("100"), Instant.now());
        } catch (InvalidParameterException e) {
            assertEquals(Order.REJECT_NON_POSITIVE_QTY, e.getCode());
            assertNotNull(e.getMessage());
            return;
        }
        throw new AssertionError("expected InvalidParameterException");
    }

    @Test
    void unexpectedExceptionBodyIsSanitized() {
        var resp = new GlobalExceptionHandler()
                .handleUnexpected(new RuntimeException("db password=secret"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        assertEquals("internal server error", resp.getBody().get("message"),
                "internal details must not leak");
    }

    @Test
    void unknownRouteIs404Not500() {
        var resp = new GlobalExceptionHandler().handleNoResource(
                new org.springframework.web.servlet.resource.NoResourceFoundException(null, null));
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertEquals("NOT_FOUND", resp.getBody().get("code"));
    }
}
