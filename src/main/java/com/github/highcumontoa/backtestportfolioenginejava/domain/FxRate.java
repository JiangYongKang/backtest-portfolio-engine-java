package com.github.highcumontoa.backtestportfolioenginejava.domain;

import com.github.highcumontoa.backtestportfolioenginejava.error.InvalidParameterException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * 汇率（不可变）：1 单位 fromCcy 兑换 rate 单位 toCcy。
 * 估值时按 rate 的时间戳与估值时点比较判定是否过期，绝不静默按 1 计价。
 */
public record FxRate(
        String fromCcy,
        String toCcy,
        BigDecimal rate,
        Instant eventTime) {

    public FxRate {
        if (fromCcy == null || fromCcy.isBlank() || toCcy == null || toCcy.isBlank()) {
            throw new InvalidParameterException("currency codes are required");
        }
        if (rate == null || rate.signum() <= 0) {
            throw new InvalidParameterException("fx rate must be positive");
        }
        Objects.requireNonNull(eventTime, "eventTime is required");
    }
}
