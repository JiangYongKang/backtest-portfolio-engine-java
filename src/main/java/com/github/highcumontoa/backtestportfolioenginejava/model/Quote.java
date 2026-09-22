package com.github.highcumontoa.backtestportfolioenginejava.model;

import java.math.BigDecimal;

/**
 * 行情快照，以 eventTime 为事件时间（与到达顺序无关）。
 * last 为 null 表示该时点无有效价格（缺失或停牌）；suspended 显式标记停牌。
 */
public record Quote(
        String symbol,
        long eventTime,
        BigDecimal bid,
        BigDecimal ask,
        BigDecimal last,
        boolean suspended
) {
}
