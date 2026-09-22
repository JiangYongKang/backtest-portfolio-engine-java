package com.github.highcumontoa.backtestportfolioenginejava.model;

import java.math.BigDecimal;

/**
 * 单持仓估值行。marketValue 为折算到基础币种后的市值；
 * flag 非 OK 时 marketValue 记为 null，禁止按零或旧价静默估值。
 */
public record PositionValuation(
        String symbol,
        BigDecimal quantity,
        BigDecimal localPrice,
        long priceTime,
        String currency,
        BigDecimal fxRate,
        Long fxTime,
        BigDecimal marketValueBase,
        BigDecimal costValueBase,
        ValuationFlag flag,
        String detail
) {
}
