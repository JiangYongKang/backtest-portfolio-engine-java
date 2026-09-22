package com.github.highcumontoa.backtestportfolioenginejava.domain;

/**
 * 绩效/风险指标的结论状态。任何指标都不得返回 NaN/Inf：
 * 样本不足、零波动等情形以显式状态给出结论。
 */
public enum MetricStatus {
    OK,
    /** 样本数不足以计算该指标。 */
    INSUFFICIENT_SAMPLES,
    /** 指标在零波动下无定义（如夏普比率）。 */
    ZERO_VOLATILITY,
    /** 指标在当前数据下无定义的其他情形。 */
    UNDEFINED
}
