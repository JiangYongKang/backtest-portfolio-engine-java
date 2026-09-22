package com.github.highcumontoa.backtestportfolioenginejava.web.dto;

import java.math.BigDecimal;

/** 下单请求。idempotencyKey 用于重复/并发提交去重；limitPrice 仅限价单需要。 */
public record SubmitOrderRequest(
        String accountId,
        String symbol,
        String side,
        String type,
        BigDecimal quantity,
        BigDecimal limitPrice,
        String idempotencyKey) {
}
