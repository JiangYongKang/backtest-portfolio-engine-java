package com.github.highcumontoa.backtestportfolioenginejava.exception;

/** 所有业务异常的统一基类，携带稳定错误码与分类，供对外转换。 */
public abstract class ApiException extends RuntimeException {
    private final String code;

    protected ApiException(String code, String message) {
        super(message);
        this.code = code;
    }

    protected ApiException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public abstract ErrorCategory getCategory();
}
