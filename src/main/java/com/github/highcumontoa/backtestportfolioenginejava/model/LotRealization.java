package com.github.highcumontoa.backtestportfolioenginejava.model;

import java.math.BigDecimal;

/**
 * 单笔卖出对单个买入批次的 FIFO 消耗明细（按笔可对账的已实现盈亏行）。
 *
 * <p>{@code realizedPnl} = 归属卖出成交额 - 消耗批次成本 - 归属佣金 - 归属税；
 * 一笔卖出跨越多个批次时会产生多行，按批次（FIFO 顺序）分别记录。
 */
public record LotRealization(
        String execId,
        String symbol,
        String currency,
        String lotId,
        long eventTime,
        BigDecimal quantity,
        BigDecimal costBasis,
        BigDecimal grossProceeds,
        BigDecimal commission,
        BigDecimal tax,
        BigDecimal realizedPnl
) {
}
