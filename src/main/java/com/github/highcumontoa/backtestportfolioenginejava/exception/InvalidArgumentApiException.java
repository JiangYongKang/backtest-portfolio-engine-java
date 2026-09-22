package com.github.highcumontoa.backtestportfolioenginejava.exception;

/**
 * INVALID_ARGUMENT 类别异常。骨架：仅声明类型，具体工厂方法在填充阶段补充。
 */
public class InvalidArgumentApiException extends ApiException {
    public InvalidArgumentApiException(String code, String message) {
        super(code, message);
    }

    @Override
    public ErrorCategory getCategory() {
        return ErrorCategory.INVALID_ARGUMENT;
    }
}
