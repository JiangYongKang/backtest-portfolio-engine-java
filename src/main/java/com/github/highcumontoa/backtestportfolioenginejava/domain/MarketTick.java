package com.github.highcumontoa.backtestportfolioenginejava.domain;

import com.github.highcumontoa.backtestportfolioenginejava.error.InvalidParameterException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * 本地行情 tick（不可变）。
 * 停牌时 price 允许为 null（suspended=true）；正常 tick 必须携带正价格。
 */
public record MarketTick(
        String symbol,
        BigDecimal price,
        Instant eventTime,
        boolean suspended) {

    public MarketTick {
        if (symbol == null || symbol.isBlank()) {
            throw new InvalidParameterException("symbol is required");
        }
        Objects.requireNonNull(eventTime, "eventTime is required");
        if (!suspended && (price == null || price.signum() <= 0)) {
            throw new InvalidParameterException("non-suspended tick requires positive price");
        }
        if (suspended) {
            price = null;
        }
    }

    /** 便捷构造：正常交易 tick。 */
    public static MarketTick tradable(String symbol, BigDecimal price, Instant eventTime) {
        return new MarketTick(symbol, price, eventTime, false);
    }

    /** 便捷构造：停牌 tick。 */
    public static MarketTick suspended(String symbol, Instant eventTime) {
        return new MarketTick(symbol, null, eventTime, true);
    }
}
