package com.github.highcumontoa.backtestportfolioenginejava.error;

public class DataMissingException extends ApiException {
    public DataMissingException(String message) {
        super(ErrorCategory.DATA_MISSING, "DATA_MISSING", message);
    }
}
