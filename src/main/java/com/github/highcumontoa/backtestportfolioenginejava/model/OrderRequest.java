package com.github.highcumontoa.backtestportfolioenginejava.model;

import java.math.BigDecimal;

/**
 * 下单请求。clientOrderId 即幂等键：同键并发/重复提交只产生一次有效结果。
 * 限价单必须提供 limitPrice；市价单忽略 limitPrice。
 */
public record OrderRequest(
        String clientOrderId,
        Side side,
        OrderType type,
        String symbol,
        BigDecimal quantity,
        BigDecimal limitPrice,
        String currency
) {
}
