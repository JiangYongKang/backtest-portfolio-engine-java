package com.github.highcumontoa.backtestportfolioenginejava.model;

import java.math.BigDecimal;

/** 汇率：1 单位 baseCcy 兑换 rate 单位 quoteCcy，eventTime 为报价时间。 */
public record FxRate(
        String baseCcy,
        String quoteCcy,
        long eventTime,
        BigDecimal rate
) {
}
