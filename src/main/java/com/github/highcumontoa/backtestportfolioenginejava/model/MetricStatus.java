package com.github.highcumontoa.backtestportfolioenginejava.model;

/** 指标可计算性结论，样本不足/零波动时返回明确结论而非 NaN。 */
public enum MetricStatus {
    OK,
    INSUFFICIENT_SAMPLES,
    ZERO_VOLATILITY
}
