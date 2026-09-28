package com.github.highcumontoa.backtestportfolioenginejava.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 组合估值结果。baseCcy 为估值基础币种；cashByCcy 为各币种现金；
 * totalMarketValueBase 仅汇总 flag=OK 的持仓；stalePositions 给出无法估值的持仓。
 * complete=false 表示存在价格/汇率缺失或过期，totalValueBase 不可作为完整 NAV 使用。
 *
 * <p>盈亏与对账（均折算到基础币种，仅汇总可靠项）：
 * <ul>
 *   <li>{@code totalLotCostBase}：批次台账口径剩余成本合计；</li>
 *   <li>{@code totalUnrealizedPnlBase} = 市值 - 批次剩余成本；</li>
 *   <li>{@code totalRealizedPnlBase}：累计已实现盈亏净额（卖出扣费税后）；</li>
 *   <li>{@code totalDividendBase}：累计现金分红（税前）；</li>
 *   <li>{@code totalPnlBase} = 已实现 + 未实现 + 分红；</li>
 *   <li>{@code navBase} = 现金 + 持仓市值（完整时即组合净资产）。</li>
 * </ul>
 */
public record ValuationResult(
        long eventTime,
        String baseCcy,
        Map<String, BigDecimal> cashByCcy,
        List<PositionValuation> positions,
        BigDecimal totalMarketValueBase,
        BigDecimal totalCostBase,
        BigDecimal totalLotCostBase,
        BigDecimal totalUnrealizedPnlBase,
        BigDecimal totalRealizedPnlBase,
        BigDecimal totalDividendBase,
        BigDecimal totalPnlBase,
        BigDecimal cashTotalBase,
        BigDecimal navBase,
        boolean complete,
        List<PositionValuation> stalePositions
) {
}
