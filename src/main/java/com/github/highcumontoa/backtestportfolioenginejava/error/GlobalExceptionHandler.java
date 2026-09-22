package com.github.highcumontoa.backtestportfolioenginejava.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

/**
 * 统一异常链路：
 * 业务异常按类别映射 4xx 与稳定错误码；
 * 未预期的内部异常仅记录完整堆栈，对外返回脱敏信息，不直接透出。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record ErrorBody(String timestamp, String category, String code, String message) {
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorBody> handleApi(ApiException ex) {
        log.warn("business error category={} code={} message={}",
                ex.getCategory(), ex.getCode(), ex.getMessage());
        HttpStatus status = switch (ex.getCategory()) {
            case INVALID_PARAMETER -> HttpStatus.BAD_REQUEST;
            case STATE_CONFLICT -> HttpStatus.CONFLICT;
            case DATA_MISSING -> HttpStatus.UNPROCESSABLE_ENTITY;
            case RESOURCE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case INTERNAL -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status).body(new ErrorBody(
                Instant.now().toString(), ex.getCategory().name(), ex.getCode(),
                ex.getMessage()));
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> handleNoResource(
            org.springframework.web.servlet.resource.NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "timestamp", Instant.now().toString(),
                "category", ErrorCategory.INVALID_PARAMETER.name(),
                "code", "NOT_FOUND",
                "message", "resource not found"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception ex) {
        log.error("unexpected internal exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "timestamp", Instant.now().toString(),
                "category", ErrorCategory.INTERNAL.name(),
                "code", "INTERNAL_ERROR",
                "message", "internal server error"));
    }
}
