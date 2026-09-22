package com.github.highcumontoa.backtestportfolioenginejava.error;

/** 对外错误类别，客户端可据此区分处理。 */
public enum ErrorCategory {
    INVALID_PARAMETER,
    STATE_CONFLICT,
    DATA_MISSING,
    RESOURCE_LIMITED,
    INTERNAL
}
