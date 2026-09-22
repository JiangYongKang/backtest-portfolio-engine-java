package com.github.highcumontoa.backtestportfolioenginejava.error;

public class InternalException extends ApiException {
    public InternalException(String message) {
        super(ErrorCategory.INTERNAL, "INTERNAL_ERROR", message);
    }
}
