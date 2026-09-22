package com.github.highcumontoa.backtestportfolioenginejava.model;

/**
 * 订单生命周期状态。
 * 合法迁移：NEW -> PARTIALLY_FILLED -> (FILLED | CANCELLED)；
 * NEW/PARTIALLY_FILLED -> REJECTED；终态 FILLED/CANCELLED/REJECTED 不可再迁移。
 */
public enum OrderStatus {
    NEW, PARTIALLY_FILLED, FILLED, CANCELLED, REJECTED
}
