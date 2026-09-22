package com.github.highcumontoa.backtestportfolioenginejava.exception;

/** 内部错误的对外表现：不携带原始细节，统一归类为 INTERNAL。 */
public class InternalApiException extends ApiException {
    public InternalApiException(String message) {
        super("INTERNAL_ERROR", message);
    }

    public InternalApiException(String message, Throwable cause) {
        super("INTERNAL_ERROR", message, cause);
    }

    @Override
    public ErrorCategory getCategory() {
        return ErrorCategory.INTERNAL;
    }
}
