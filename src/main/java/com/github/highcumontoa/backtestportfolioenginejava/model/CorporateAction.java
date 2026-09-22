package com.github.highcumontoa.backtestportfolioenginejava.model;

import java.math.BigDecimal;

/**
 * 公司行为。
 * 拆股/合股：ratio 为每股变更为多少股（拆股 &gt;1，合股 &lt;1）；
 * 现金分红：cashPerShare 为每股税前现金，currency 为分红币种。
 * id 用于幂等，同一 id 重复应用结果不变。
 */
public record CorporateAction(
        String id,
        CorporateActionType type,
        String symbol,
        long effectiveTime,
        BigDecimal ratio,
        BigDecimal cashPerShare,
        String currency
) {
}
