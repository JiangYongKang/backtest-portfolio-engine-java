package com.github.highcumontoa.backtestportfolioenginejava.error;

public class StateConflictException extends ApiException {
    public StateConflictException(String message) {
        super(ErrorCategory.STATE_CONFLICT, "STATE_CONFLICT", message);
    }
}
