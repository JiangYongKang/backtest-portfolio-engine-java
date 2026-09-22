package com.github.highcumontoa.backtestportfolioenginejava.error;

/** 业务异常基类：携带可区分的错误类别与错误码，供统一异常链路转译。 */
public abstract class ApiException extends RuntimeException {

    private final ErrorCategory category;
    private final String code;

    protected ApiException(ErrorCategory category, String code, String message) {
        super(message);
        this.category = category;
        this.code = code;
    }

    public ErrorCategory getCategory() {
        return category;
    }

    public String getCode() {
        return code;
    }
}
