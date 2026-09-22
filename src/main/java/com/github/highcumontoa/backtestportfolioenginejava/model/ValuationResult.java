package com.github.highcumontoa.backtestportfolioenginejava.model;

import java.util.List;
import java.math.BigDecimal;
import java.util.Map;

/**
 * 组合估值结果。baseCcy 为估值基础币种；cashByCcy 为各币种现金；
 * totalMarketValueBase 仅汇总 flag=OK 的持仓；stalePositions 给出无法估值的持仓。
 * complete=false 表示存在价格/汇率缺失或过期，totalValueBase 不可作为完整 NAV 使用。
 */
public record ValuationResult(
        long eventTime,
        String baseCcy,
        Map<String, BigDecimal> cashByCcy,
        List<PositionValuation> positions,
        BigDecimal totalMarketValueBase,
        BigDecimal totalCostBase,
        boolean complete,
        List<PositionValuation> stalePositions
) {
}
