package com.github.highcumontoa.backtestportfolioenginejava.model;

import java.math.BigDecimal;

/**
 * 持仓成本批次（FIFO 核算单元）。
 *
 * <p>每一笔买入成交（含同一订单的每次部分成交）形成一个独立批次：
 * <ul>
 *   <li>{@code unitCost} 为单位成本 =（成交金额 + 买入佣金）/ 成交数量，买入费用归入批次；</li>
 *   <li>{@code remainingQuantity} 为该批次尚未被卖出消耗的数量；</li>
 *   <li>拆股/合股同时调整数量与单位成本，保持剩余总成本不变。</li>
 * </ul>
 */
public record CostLot(
        String lotId,
        String symbol,
        String currency,
        String execId,
        long openTime,
        BigDecimal originalQuantity,
        BigDecimal remainingQuantity,
        BigDecimal unitCost
) {
}
