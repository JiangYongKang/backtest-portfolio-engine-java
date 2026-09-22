package com.github.highcumontoa.backtestportfolioenginejava.tests;

import com.github.highcumontoa.backtestportfolioenginejava.api.ErrorResponse;
import com.github.highcumontoa.backtestportfolioenginejava.api.GlobalExceptionHandler;
import com.github.highcumontoa.backtestportfolioenginejava.exception.DataMissingApiException;
import com.github.highcumontoa.backtestportfolioenginejava.exception.ErrorCategory;
import com.github.highcumontoa.backtestportfolioenginejava.exception.InternalApiException;
import com.github.highcumontoa.backtestportfolioenginejava.exception.InvalidArgumentApiException;
import com.github.highcumontoa.backtestportfolioenginejava.exception.ResourceExceededApiException;
import com.github.highcumontoa.backtestportfolioenginejava.exception.StateConflictApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.*;

/** 异常分类对外可区分，内部异常不透出细节。 */
class ExceptionCategoryTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void categoriesMapToDistinctHttpStatusAndStableCodes() {
        assertEquals(HttpStatus.BAD_REQUEST, handler.handleApi(
                new InvalidArgumentApiException("BAD", "x")).getStatusCode());
        assertEquals(HttpStatus.CONFLICT, handler.handleApi(
                new StateConflictApiException("CONF", "x")).getStatusCode());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, handler.handleApi(
                new DataMissingApiException("MISS", "x")).getStatusCode());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, handler.handleApi(
                new ResourceExceededApiException("LIMIT", "x")).getStatusCode());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, handler.handleApi(
                new InternalApiException("x")).getStatusCode());
    }

    @Test
    void errorBodyCarriesCategoryAndCodeOnly() {
        var resp = handler.handleApi(new InvalidArgumentApiException("INVALID_QUANTITY", "bad qty"));
        ErrorResponse body = resp.getBody();
        assertNotNull(body);
        assertEquals(ErrorCategory.INVALID_ARGUMENT.name(), body.category());
        assertEquals("INVALID_QUANTITY", body.code());
        assertEquals("bad qty", body.message());
    }

    @Test
    void unexpectedExceptionDoesNotLeakInternals() {
        var resp = handler.handleOther(new RuntimeException("secret db password=..."));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        ErrorResponse body = resp.getBody();
        assertNotNull(body);
        assertEquals("INTERNAL", body.category());
        assertFalse(body.message().contains("secret"), "internal details must not leak");
        assertFalse(body.message().contains("password"));
    }
}
