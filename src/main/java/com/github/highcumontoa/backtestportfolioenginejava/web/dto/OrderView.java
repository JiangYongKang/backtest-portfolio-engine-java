package com.github.highcumontoa.backtestportfolioenginejava.web.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** 订单只读视图。 */
public record OrderView(
        String orderId,
        String idempotencyKey,
        String accountId,
        String symbol,
        String side,
        String type,
        BigDecimal totalQuantity,
        BigDecimal filledQuantity,
        BigDecimal remainingQuantity,
        BigDecimal avgFillPrice,
        BigDecimal limitPrice,
        String status,
        String rejectReason,
        Instant submitTime,
        Instant lastFillTime) {
}
