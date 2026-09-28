package com.github.highcumontoa.backtestportfolioenginejava.model.lot;

import java.math.BigDecimal;

/**
 * 批次只读视图（查询/序列化用）。{@code unitCost} 为剩余成本/剩余数量（8 位 HALF_UP）。
 *
 * @param remainingCost 尚未卖出部分的成本（含归属买入费用）
 * @param allocatedCost 已被卖出部分消耗掉的成本（累计）
 */
public record LotView(
        String lotId,
        String symbol,
        String execId,
        String orderId,
        long openTime,
        BigDecimal fillPrice,
        BigDecimal initialQty,
        BigDecimal remainingQty,
        BigDecimal initialCost,
        BigDecimal remainingCost,
        BigDecimal allocatedCost,
        BigDecimal unitCost
) {
    public static LotView of(Lot lot) {
        return new LotView(
                lot.getLotId(),
                lot.getSymbol(),
                lot.getExecId(),
                lot.getOrderId(),
                lot.getOpenTime(),
                lot.getFillPrice(),
                lot.getInitialQty(),
                lot.getRemainingQty(),
                lot.getInitialCost(),
                lot.getRemainingCost(),
                lot.getAllocatedCost(),
                lot.unitCost());
    }
}
