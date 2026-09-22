package com.github.highcumontoa.backtestportfolioenginejava.error;

public class ResourceLimitException extends ApiException {
    public ResourceLimitException(String message) {
        super(ErrorCategory.RESOURCE_LIMITED, "RESOURCE_LIMITED", message);
    }
}
