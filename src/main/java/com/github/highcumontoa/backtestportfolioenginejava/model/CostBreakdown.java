package com.github.highcumontoa.backtestportfolioenginejava.model;

import java.math.BigDecimal;

/**
 * 单笔成交的成本拆解（逐笔可追溯）。
 * grossAmount 为不含费用成交额；slippageCost 为相对中间价/参考价的滑点金额；
 * commission、tax 为费用；netCashFlow 为对现金的实际影响（买为负、卖为正的调用方约定）。
 */
public record CostBreakdown(
        BigDecimal grossAmount,
        BigDecimal slippageCost,
        BigDecimal commission,
        BigDecimal tax,
        BigDecimal netCashFlow
) {
}
