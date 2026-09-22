package com.github.highcumontoa.backtestportfolioenginejava.exception;

/**
 * DATA_MISSING 类别异常。骨架：仅声明类型，具体工厂方法在填充阶段补充。
 */
public class DataMissingApiException extends ApiException {
    public DataMissingApiException(String code, String message) {
        super(code, message);
    }

    @Override
    public ErrorCategory getCategory() {
        return ErrorCategory.DATA_MISSING;
    }
}
