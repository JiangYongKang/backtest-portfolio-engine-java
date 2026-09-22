package com.github.highcumontoa.backtestportfolioenginejava.domain;

import java.time.Instant;
import java.util.List;

/**
 * 绩效与风险报告：累计收益、年化波动率、最大回撤、历史 VaR/ES（尾部风险）。
 * 每个指标独立携带状态，样本不足/零波动时为显式结论而非 NaN/Inf/静默零。
 */
public record PerformanceReport(
        Instant from,
        Instant to,
        int sampleCount,
        List<MetricResult> metrics) {

    public PerformanceReport {
        metrics = List.copyOf(metrics);
    }

    /** 按名称取指标。 */
    public MetricResult metric(String name) {
        return metrics.stream()
                .filter(m -> m.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("metric not found: " + name));
    }
}
