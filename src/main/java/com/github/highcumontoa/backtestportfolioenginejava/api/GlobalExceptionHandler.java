package com.github.highcumontoa.backtestportfolioenginejava.api;

import com.github.highcumontoa.backtestportfolioenginejava.exception.ApiException;
import com.github.highcumontoa.backtestportfolioenginejava.exception.ErrorCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常链路：对外只暴露 类别/错误码/可读消息，内部异常不泄漏堆栈与细节。
 * HTTP 映射：参数非法 400、状态冲突 409、数据缺失 422、资源超限 429、其余 500。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex) {
        HttpStatus status = switch (ex.getCategory()) {
            case INVALID_ARGUMENT -> HttpStatus.BAD_REQUEST;
            case STATE_CONFLICT -> HttpStatus.CONFLICT;
            case DATA_MISSING -> HttpStatus.UNPROCESSABLE_ENTITY;
            case RESOURCE_EXCEEDED -> HttpStatus.TOO_MANY_REQUESTS;
            case INTERNAL -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        log.warn("api error category={} code={} msg={}", ex.getCategory(), ex.getCode(), ex.getMessage());
        return ResponseEntity.status(status)
                .body(new ErrorResponse(ex.getCategory().name(), ex.getCode(), ex.getMessage()));
    }

    /** 兜底：任何未受控异常统一转 INTERNAL，不把内部信息透出。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleOther(Exception ex) {
        log.error("unhandled internal exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(ErrorCategory.INTERNAL.name(), "INTERNAL_ERROR", "internal error"));
    }
}
