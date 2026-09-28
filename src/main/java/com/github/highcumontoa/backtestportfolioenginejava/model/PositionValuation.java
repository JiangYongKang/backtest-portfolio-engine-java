package com.github.highcumontoa.backtestportfolioenginejava.model;

import java.math.BigDecimal;

/**
 * 单持仓估值行。marketValue 为折算到基础币种后的市值；
 * costValue 为剩余批次成本折算到基础币种后的金额；
 * unrealizedPnlBase = marketValue - costValue（浮动盈亏，已实现盈亏在行外按币种累计）；
 * flag 非 OK 时 marketValue/cost/unrealizedPnl 记为 null，禁止按零或旧价静默估值。
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
        BigDecimal unrealizedPnlBase,
        ValuationFlag flag,
        String detail
) {
}
