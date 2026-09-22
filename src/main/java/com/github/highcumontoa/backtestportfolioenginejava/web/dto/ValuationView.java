package com.github.highcumontoa.backtestportfolioenginejava.web.dto;

import com.github.highcumontoa.backtestportfolioenginejava.domain.ValuationResult;

import java.math.BigDecimal;
import java.time.Instant;

/** 估值视图：不健康时 totalEquity 为 null，issues 列出所有数据问题。 */
public record ValuationView(
        Instant valuationTime,
        String baseCcy,
        BigDecimal cashBase,
        BigDecimal totalEquity,
        boolean healthy,
        java.util.List<String> issues,
        java.util.List<com.github.highcumontoa.backtestportfolioenginejava.domain.ValuationLine> lines) {

    public static ValuationView from(ValuationResult r) {
        return new ValuationView(r.valuationTime(), r.baseCcy(), r.cashBase(),
                r.totalEquity(), r.healthy(),
                r.issues().stream().map(Enum::name).toList(), r.lines());
    }
}
