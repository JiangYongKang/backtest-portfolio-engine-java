package com.github.highcumontoa.backtestportfolioenginejava.model.lot;

import java.math.BigDecimal;

/**
 * 一笔卖出成交按 FIFO 消耗某个批次的明细行。
 *
 * @param lotId      被消耗的批次
 * @param quantity   从该批次消耗的数量
 * @param costBasis  该数量对应的成本（含归属买入费用）
 * @param proceeds   该数量对应的卖出毛收入（成交价 * 数量，未扣卖出费用）
 */
public record LotMatch(
        String lotId,
        BigDecimal quantity,
        BigDecimal costBasis,
        BigDecimal proceeds
) {
}
