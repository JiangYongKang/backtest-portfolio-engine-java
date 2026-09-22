package com.github.highcumontoa.backtestportfolioenginejava.domain;

import com.github.highcumontoa.backtestportfolioenginejava.error.InvalidParameterException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * 成交回报（不可变）。fillId 全局唯一，同一 fillId 重复消费必须幂等。
 * commission/tax 为本笔成交的手续费与税费，逐笔可追溯。
 */
public record Fill(
        String fillId,
        String orderId,
        String symbol,
        Side side,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal commission,
        BigDecimal tax,
        Instant eventTime) {

    public Fill {
        if (fillId == null || fillId.isBlank()) {
            throw new InvalidParameterException("fillId is required");
        }
        if (orderId == null || orderId.isBlank()) {
            throw new InvalidParameterException("orderId is required");
        }
        Objects.requireNonNull(side, "side is required");
        if (quantity == null || quantity.signum() <= 0) {
            throw new InvalidParameterException("fill quantity must be positive");
        }
        if (price == null || price.signum() <= 0) {
            throw new InvalidParameterException("fill price must be positive");
        }
        Objects.requireNonNull(eventTime, "eventTime is required");
        commission = commission == null ? BigDecimal.ZERO : commission;
        tax = tax == null ? BigDecimal.ZERO : tax;
    }

    /** 成交名义金额（价 × 量）。 */
    public BigDecimal grossAmount() {
        return price.multiply(quantity);
    }
}
