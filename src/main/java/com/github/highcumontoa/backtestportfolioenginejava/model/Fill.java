package com.github.highcumontoa.backtestportfolioenginejava.model;

import java.math.BigDecimal;

/**
 * 成交回报（本地模拟或外部回报统一形态）。
 * execId 为成交回报唯一键，重复消费同一 execId 必须幂等。
 * price 为含滑点后的实际成交价；commission/tax 为逐笔费用。
 */
public record Fill(
        String execId,
        String orderId,
        String symbol,
        Side side,
        BigDecimal quantity,
        BigDecimal price,
        long eventTime,
        BigDecimal commission,
        BigDecimal tax
) {
}
