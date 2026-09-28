package com.github.highcumontoa.backtestportfolioenginejava.model;

import java.math.BigDecimal;

/**
 * 单持仓估值行。marketValue 为折算到基础币种后的市值；
 * flag 非 OK 时 marketValue/unrealizedPnlBase 记为 null，禁止按零或旧价静默估值。
 *
 * @param costValueBase        加权平均成本口径的持仓成本（折算到基础币种，兼容旧口径）
 * @param lotCostValueBase     批次台账口径的剩余成本（折算到基础币种）
 * @param unrealizedPnlBase    未实现盈亏 = 市值 - 批次剩余成本（基础币种）
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
        BigDecimal lotCostValueBase,
        BigDecimal unrealizedPnlBase,
        ValuationFlag flag,
        String detail
) {
}
