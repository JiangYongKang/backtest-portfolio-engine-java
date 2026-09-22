package com.github.highcumontoa.backtestportfolioenginejava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * 引擎配置（application.properties: backtest.*）。
 * priceStaleTolerance：行情超过估值时点多久视为过期（停牌区间同样按此判定）；
 * fxStaleTolerance：汇率过期窗口，过期绝不沿用旧值。
 */
@ConfigurationProperties(prefix = "backtest")
public class EngineProperties {

    private String baseCurrency = "USD";
    private Duration priceStaleTolerance = Duration.ofMinutes(5);
    private Duration fxStaleTolerance = Duration.ofDays(1);
    private Cost cost = new Cost();

    public static class Cost {
        /** 佣金费率（按成交额比例）。 */
        private BigDecimal commissionRate = new BigDecimal("0.0003");
        /** 单笔最低佣金（报价币种）。 */
        private BigDecimal commissionMin = new BigDecimal("5.00");
        /** 印花/交易税率。 */
        private BigDecimal taxRate = new BigDecimal("0.001");
        /** 税费是否仅卖出收取（A股式印花税）。 */
        private boolean taxOnSellOnly = true;
        /** 滑点比例（市价单按成交价朝不利方向偏移的比例）。 */
        private BigDecimal slippageRate = new BigDecimal("0.0005");
        /** 每单佣金预留上界：覆盖多笔部分成交各自的最低佣金。 */
        private BigDecimal commissionReservePerOrder = new BigDecimal("20.00");

        public BigDecimal getCommissionRate() { return commissionRate; }
        public void setCommissionRate(BigDecimal v) { this.commissionRate = v; }
        public BigDecimal getCommissionMin() { return commissionMin; }
        public void setCommissionMin(BigDecimal v) { this.commissionMin = v; }
        public BigDecimal getTaxRate() { return taxRate; }
        public void setTaxRate(BigDecimal v) { this.taxRate = v; }
        public boolean isTaxOnSellOnly() { return taxOnSellOnly; }
        public void setTaxOnSellOnly(boolean v) { this.taxOnSellOnly = v; }
        public BigDecimal getSlippageRate() { return slippageRate; }
        public void setSlippageRate(BigDecimal v) { this.slippageRate = v; }
        public BigDecimal getCommissionReservePerOrder() { return commissionReservePerOrder; }
        public void setCommissionReservePerOrder(BigDecimal v) { this.commissionReservePerOrder = v; }
    }

    public CostConfig toCostConfig() {
        return new CostConfig(cost.commissionRate, cost.commissionMin,
                cost.taxRate, cost.slippageRate, cost.taxOnSellOnly,
                cost.commissionReservePerOrder);
    }

    public String getBaseCurrency() { return baseCurrency; }
    public void setBaseCurrency(String v) { this.baseCurrency = v; }
    public Duration getPriceStaleTolerance() { return priceStaleTolerance; }
    public void setPriceStaleTolerance(Duration v) { this.priceStaleTolerance = v; }
    public Duration getFxStaleTolerance() { return fxStaleTolerance; }
    public void setFxStaleTolerance(Duration v) { this.fxStaleTolerance = v; }
    public Cost getCost() { return cost; }
    public void setCost(Cost cost) { this.cost = cost; }
}
