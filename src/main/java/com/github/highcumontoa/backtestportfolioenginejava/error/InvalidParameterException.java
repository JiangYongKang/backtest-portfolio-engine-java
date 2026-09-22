package com.github.highcumontoa.backtestportfolioenginejava.error;

public class InvalidParameterException extends ApiException {
    public InvalidParameterException(String message) {
        super(ErrorCategory.INVALID_PARAMETER, "INVALID_PARAMETER", message);
    }

    /** 携带稳定错误码（如订单拒绝原因）。 */
    public InvalidParameterException(String code, String message) {
        super(ErrorCategory.INVALID_PARAMETER, code, message);
    }
}
