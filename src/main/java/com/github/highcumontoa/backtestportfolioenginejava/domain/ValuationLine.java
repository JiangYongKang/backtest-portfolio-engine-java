package com.github.highcumontoa.backtestportfolioenginejava.domain;

import java.math.BigDecimal;

/**
 * 单标的估值行。localPrice 为本地币种最新有效价格（可能为 null，由 status 说明原因）；
 * marketValueBase 为折算到基准币种后的市值，当 status 非 OK 时为 null，绝不按零估值。
 */
public record ValuationLine(
        String symbol,
        BigDecimal quantity,
        BigDecimal localPrice,
        String priceCcy,
        BigDecimal marketValueBase,
        DataStatus status) {
}
