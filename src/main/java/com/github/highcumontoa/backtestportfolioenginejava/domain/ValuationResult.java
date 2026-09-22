package com.github.highcumontoa.backtestportfolioenginejava.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 组合估值快照。
 * cashBase：各币种现金折算到基准币种后的合计；折算失败的币种计入 issues 且该部分现金不并入。
 * totalEquity：cashBase + 各健康持仓市值；healthy=false 时 totalEquity 为 null。
 * issues 汇总所有非 OK 的数据状态，估值使用方必须显式处理，不允许静默使用。
 */
public record ValuationResult(
        Instant valuationTime,
        String baseCcy,
        BigDecimal cashBase,
        List<ValuationLine> lines,
        BigDecimal totalEquity,
        boolean healthy,
        List<DataStatus> issues) {

    public ValuationResult {
        lines = List.copyOf(lines);
        issues = List.copyOf(issues);
    }
}
