package com.github.highcumontoa.backtestportfolioenginejava.matching;

import com.github.highcumontoa.backtestportfolioenginejava.config.CostConfig;
import com.github.highcumontoa.backtestportfolioenginejava.domain.Side;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 成交成本计算器（无状态）。
 *
 * 滑点：市价单成交价 = 基准价 × (1 + side.sign × slippageRate)；限价单不加滑点。
 * 佣金：max(成交额 × commissionRate, commissionMin)，2 位小数 HALF_UP；
 * 税费：成交额 × taxRate，taxOnSellOnly 时仅卖出收取；
 * 全部金额精度 2 位小数 HALF_UP，保证逐笔可追溯、可对账。
 */
@Component
public class CostCalculator {

    public static final int MONEY_SCALE = 2;

    private final CostConfig config;

    public CostCalculator(CostConfig config) {
        this.config = config;
    }

    /** 应用滑点后的预期成交价。限价单返回 limitPrice；市价单按方向偏移。 */
    public BigDecimal expectedFillPrice(Side side, BigDecimal referencePrice,
                                        BigDecimal limitPrice, boolean marketOrder) {
        if (!marketOrder) {
            return limitPrice;
        }
        BigDecimal factor = BigDecimal.ONE.add(
                config.slippageRate().multiply(BigDecimal.valueOf(side.sign())));
        return referencePrice.multiply(factor).setScale(8, RoundingMode.HALF_UP);
    }

    public BigDecimal commission(BigDecimal price, BigDecimal quantity) {
        BigDecimal notional = price.multiply(quantity);
        BigDecimal byRate = notional.multiply(config.commissionRate());
        return byRate.max(config.commissionMin()).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    public BigDecimal tax(Side side, BigDecimal price, BigDecimal quantity) {
        if (config.taxOnSellOnly() && side.isBuy()) {
            return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
        return price.multiply(quantity).multiply(config.taxRate())
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** 买入含费总成本（成交额 + 佣金 + 税）。 */
    public BigDecimal buyTotalOutflow(BigDecimal price, BigDecimal quantity) {
        return price.multiply(quantity)
                .add(commission(price, quantity))
                .add(tax(Side.BUY, price, quantity))
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 买单下单时的保守资金预留上界。
     * 部分成交会令每笔都承担最低佣金，因此预留 = 成交额 + 比例佣金 + 税 + 一笔最低佣金，
     * 保证任意分笔方式下实际支出都不超过预留。
     */
    public BigDecimal buyReserveUpperBound(BigDecimal price, BigDecimal quantity) {
        return price.multiply(quantity)
                .add(price.multiply(quantity).multiply(config.commissionRate()))
                .add(tax(Side.BUY, price, quantity))
                .add(config.commissionReservePerOrder())
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** 卖出净到账（成交额 - 佣金 - 税）。 */
    public BigDecimal sellNetProceeds(BigDecimal price, BigDecimal quantity) {
        return price.multiply(quantity)
                .subtract(commission(price, quantity))
                .subtract(tax(Side.SELL, price, quantity))
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
