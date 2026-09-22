package com.github.highcumontoa.backtestportfolioenginejava.model;

/** 估值数据质量标识，缺失/过期信息必须显式标记，禁止静默估值。 */
public enum ValuationFlag {
    OK,
    PRICE_MISSING,
    PRICE_STALE_SUSPENDED,
    FX_MISSING,
    FX_STALE
}
