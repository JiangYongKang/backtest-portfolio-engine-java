package com.github.highcumontoa.backtestportfolioenginejava.domain;

/**
 * 订单状态机：
 * NEW -> PARTIALLY_FILLED -> FILLED
 * NEW/PARTIALLY_FILLED -> CANCELLED
 * NEW -> REJECTED（终态，不可再迁移）
 * FILLED/CANCELLED/REJECTED 均为终态。
 */
public enum OrderStatus {
    NEW,
    PARTIALLY_FILLED,
    FILLED,
    CANCELLED,
    REJECTED;

    public boolean isTerminal() {
        return this == FILLED || this == CANCELLED || this == REJECTED;
    }

    /** 是否允许再接收成交回报。 */
    public boolean acceptsFill() {
        return this == NEW || this == PARTIALLY_FILLED;
    }
}
