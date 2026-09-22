package com.github.highcumontoa.backtestportfolioenginejava.config;

import java.math.BigDecimal;

/**
 * 交易成本与精度配置（逐笔可追溯）。
 * 骨架仅给出字段与默认值，撮合阶段读取这些参数。
 *
 * @param slippageRate   滑点比例（相对成交价，买卖均按不利方向施加）
 * @param commissionRate 佣金比例（按成交金额）
 * @param minCommission  单笔最低佣金
 * @param taxRate        印花/交易税比例（仅卖出）
 * @param priceScale     价格保留小数位
 * @param moneyScale     金额/资金保留小数位
 * @param fxMaxStaleMillis 汇率可接受最大年龄，超过视为过期
 */
public record CostConfig(
        BigDecimal slippageRate,
        BigDecimal commissionRate,
        BigDecimal minCommission,
        BigDecimal taxRate,
        int priceScale,
        int moneyScale,
        long fxMaxStaleMillis
) {
    public static final CostConfig DEFAULT = new CostConfig(
            new BigDecimal("0.0005"),
            new BigDecimal("0.0003"),
            new BigDecimal("5.00"),
            new BigDecimal("0.001"),
            4,
            2,
            24L * 60 * 60 * 1000
    );
}
