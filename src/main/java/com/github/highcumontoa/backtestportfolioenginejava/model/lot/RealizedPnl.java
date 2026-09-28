package com.github.highcumontoa.backtestportfolioenginejava.model.lot;

import java.math.BigDecimal;
import java.util.List;

/**
 * 一笔卖出成交的已实现盈亏（按 FIFO 与批次逐笔对账）。
 *
 * <ul>
 *   <li>{@code grossPnl} = 毛收入 - 批次成本（不含卖出费用）；</li>
 *   <li>{@code netPnl} = grossPnl - commission - tax（卖出佣金与税计入当期已实现盈亏）；</li>
 *   <li>{@code matches} 给出该笔卖出依次消耗了哪些批次、各多少数量与成本。</li>
 * </ul>
 */
public record RealizedPnl(
        String execId,
        String orderId,
        String symbol,
        String currency,
        long eventTime,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal proceeds,
        BigDecimal costBasis,
        BigDecimal commission,
        BigDecimal tax,
        BigDecimal grossPnl,
        BigDecimal netPnl,
        List<LotMatch> matches
) {
}
