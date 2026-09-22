package com.github.highcumontoa.backtestportfolioenginejava.domain;

import java.math.BigDecimal;

/**
 * 单个指标结论。status != OK 时 value 为 null（绝不为 NaN/Inf），
 * 并通过 status 给出明确原因（样本不足/零波动/无定义）。
 */
public record MetricResult(String name, BigDecimal value, MetricStatus status) {

    public static MetricResult ok(String name, BigDecimal value) {
        return new MetricResult(name, value, MetricStatus.OK);
    }

    public static MetricResult nonFinite(String name, MetricStatus status) {
        if (status == MetricStatus.OK) {
            throw new IllegalArgumentException("non-finite result needs non-OK status");
        }
        return new MetricResult(name, null, status);
    }
}
