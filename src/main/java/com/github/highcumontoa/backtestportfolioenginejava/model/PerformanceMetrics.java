package com.github.highcumontoa.backtestportfolioenginejava.model;

import java.math.BigDecimal;

/**
 * 绩效与风险指标。任何无法得出数值的指标以 status 明确标注，value 返回 null，
 * 不得返回 NaN/Inf 或静默为 0。
 */
public record PerformanceMetrics(
        MetricStatus status,
        BigDecimal cumulativeReturn,
        BigDecimal annualizedVolatility,
        BigDecimal maxDrawdown,
        BigDecimal historicalVaR95,
        BigDecimal expectedShortfall95,
        String detail
) {
}
