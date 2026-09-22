package com.github.highcumontoa.backtestportfolioenginejava.config;

import java.math.BigDecimal;

/**
 * 成交成本配置（不可变）。
 * commissionRate：佣金比例；commissionMin：单笔最低佣金；
 * taxRate：税率（taxOnSellOnly 时仅卖出收取）；
 * slippageRate：市价单滑点比例，买高卖低；
 * commissionReservePerOrder：下单资金预留时，为"可能的多笔部分成交每笔最低佣金"
 * 预留的每单佣金上界（超出部分成交会触发 RESOURCE_LIMITED，行为显式可判定）。
 */
public record CostConfig(
        BigDecimal commissionRate,
        BigDecimal commissionMin,
        BigDecimal taxRate,
        BigDecimal slippageRate,
        boolean taxOnSellOnly,
        BigDecimal commissionReservePerOrder) {
}
