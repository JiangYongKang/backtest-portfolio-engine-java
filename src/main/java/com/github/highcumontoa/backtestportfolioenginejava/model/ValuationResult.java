package com.github.highcumontoa.backtestportfolioenginejava.model;

import java.util.List;
import java.math.BigDecimal;
import java.util.Map;

/**
 * 组合估值结果。baseCcy 为估值基础币种；cashByCcy 为各币种现金；
 * totalMarketValueBase 仅汇总 flag=OK 的持仓；stalePositions 给出无法估值的持仓。
 * complete=false 表示存在价格/汇率缺失或过期，totalValueBase 不可作为完整 NAV 使用。
 *
 * <p>盈亏口径（折算到基础币种，仅汇总可可靠折算部分）：
 * <ul>
 *   <li>{@code totalCostBase}：剩余持仓批次成本之和（FIFO 批次口径）；</li>
 *   <li>{@code unrealizedPnlBase}：总市值 − 剩余成本（浮动盈亏）；</li>
 *   <li>{@code realizedPnlBase}：累计卖出已实现盈亏（费税已计入）；</li>
 *   <li>{@code dividendIncomeBase}：累计现金分红；</li>
 *   <li>{@code totalPnlBase} = realized + unrealized + dividend（当期总收益口径）。</li>
 * </ul>
 * 对账关系：外部入金 + realizedPnl + dividend = 现金 + 剩余成本（费用/滑点体现为浮亏或已实现亏损）。
 */
public record ValuationResult(
        long eventTime,
        String baseCcy,
        Map<String, BigDecimal> cashByCcy,
        List<PositionValuation> positions,
        BigDecimal totalMarketValueBase,
        BigDecimal totalCostBase,
        BigDecimal unrealizedPnlBase,
        BigDecimal realizedPnlBase,
        BigDecimal dividendIncomeBase,
        BigDecimal totalPnlBase,
        boolean complete,
        List<PositionValuation> stalePositions
) {
}
