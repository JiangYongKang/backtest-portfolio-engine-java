package com.github.highcumontoa.backtestportfolioenginejava.model;

/** 拒单/非法迁移的可区分原因。 */
public enum RejectReason {
    UNKNOWN_SYMBOL,
    MARKET_CLOSED,
    INSUFFICIENT_FUNDS,
    INSUFFICIENT_POSITION,
    INVALID_LIMIT_PRICE,
    INVALID_QUANTITY,
    DUPLICATE_IDEMPOTENCY_KEY,
    ILLEGAL_TRANSITION,
    RISK_LIMIT_EXCEEDED
}
