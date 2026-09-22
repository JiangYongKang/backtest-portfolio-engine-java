package com.github.highcumontoa.backtestportfolioenginejava.domain;

/**
 * 行情/汇率数据质量状态。非 OK 状态下估值结果必须带显式标记，
 * 不允许按零、旧价或汇率 1 静默计价。
 */
public enum DataStatus {
    /** 价格有效且在新鲜窗口内。 */
    OK,
    /** 从未收到该标的的价格。 */
    MISSING_PRICE,
    /** 最新价格时间戳早于估值时点允许的新鲜窗口（含停牌区间）。 */
    STALE_PRICE,
    /** 最新 tick 显式标记停牌。 */
    SUSPENDED,
    /** 缺少币种汇率。 */
    FX_MISSING,
    /** 汇率超过允许的过期窗口。 */
    FX_STALE
}
