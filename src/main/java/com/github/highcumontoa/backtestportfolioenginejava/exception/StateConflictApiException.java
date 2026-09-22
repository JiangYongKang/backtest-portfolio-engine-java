package com.github.highcumontoa.backtestportfolioenginejava.exception;

/**
 * STATE_CONFLICT 类别异常。骨架：仅声明类型，具体工厂方法在填充阶段补充。
 */
public class StateConflictApiException extends ApiException {
    public StateConflictApiException(String code, String message) {
        super(code, message);
    }

    @Override
    public ErrorCategory getCategory() {
        return ErrorCategory.STATE_CONFLICT;
    }
}
