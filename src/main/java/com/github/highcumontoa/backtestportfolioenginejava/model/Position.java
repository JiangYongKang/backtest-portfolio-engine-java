package com.github.highcumontoa.backtestportfolioenginejava.model;

import com.github.highcumontoa.backtestportfolioenginejava.exception.InvalidArgumentApiException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 持仓：数量、可用（可卖）数量、加权平均成本与成本币种。
 *
 * <p>数量守恒约定：
 * <ul>
 *   <li>买入：quantity 与 availableQuantity 同时增加；avgCost 按金额加权（买入费用计入成本）。</li>
 *   <li>卖出：先 {@link #freeze} 冻结可用数量，成交时减少 quantity，单位 avgCost 不变。</li>
 *   <li>拆股/合股：quantity 与 availableQuantity 等比乘 ratio，avgCost 除以 ratio，持仓总成本不变。</li>
 * </ul>
 * 内部允许零/正持仓；负持仓（超卖）由服务层在 freeze 时拦截。
 */
public final class Position {
    private final String symbol;
    private final String currency;
    private BigDecimal quantity;
    private BigDecimal availableQuantity;
    private BigDecimal avgCost;

    public Position(String symbol, String currency) {
        this.symbol = symbol;
        this.currency = currency;
        this.quantity = BigDecimal.ZERO;
        this.availableQuantity = BigDecimal.ZERO;
        this.avgCost = BigDecimal.ZERO;
    }

    public String getSymbol() { return symbol; }
    public String getCurrency() { return currency; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getAvailableQuantity() { return availableQuantity; }
    public BigDecimal getAvgCost() { return avgCost; }

    /** 持仓总成本（数量*单位成本），用于公司行为前后守恒校验。 */
    public BigDecimal totalCost() {
        return quantity.multiply(avgCost);
    }

    /**
     * 成交入账。
     * 买入：加权平均成本 = (旧总成本 + 买入金额 + 买入佣金) / 新数量（税费买入为 0）。
     * 卖出：数量减少，avgCost 不变；卖出佣金/税不影响持仓成本（在现金净额中体现）。
     * 冻结在下单时由 {@link #freeze} 处理，因此此处只调整 quantity 与（买入的）available。
     */
    public void applyTrade(Side side, BigDecimal qty, BigDecimal price,
                           BigDecimal commission, BigDecimal tax) {
        if (qty == null || qty.signum() <= 0) {
            throw new InvalidArgumentApiException("INVALID_TRADE_QTY", "trade quantity must be positive");
        }
        if (price == null || price.signum() <= 0) {
            throw new InvalidArgumentApiException("INVALID_TRADE_PRICE", "trade price must be positive");
        }
        BigDecimal fee = commission == null ? BigDecimal.ZERO : commission;
        if (side == Side.BUY) {
            BigDecimal oldCost = totalCost();
            BigDecimal added = qty.multiply(price).add(fee);
            BigDecimal newQty = quantity.add(qty);
            this.quantity = newQty;
            this.availableQuantity = availableQuantity.add(qty);
            this.avgCost = oldCost.add(added).divide(newQty, 8, RoundingMode.HALF_UP);
        } else {
            if (qty.compareTo(quantity) > 0) {
                throw new InvalidArgumentApiException("OVERSELL",
                        "sell " + qty + " exceeds position " + quantity + " of " + symbol);
            }
            BigDecimal oldQty = this.quantity;
            this.quantity = oldQty.subtract(qty);
            // 卖出时对应数量已在 freeze 阶段从 available 扣除，这里不再二次扣减。
            if (this.quantity.signum() == 0) {
                this.avgCost = BigDecimal.ZERO;
            }
        }
    }

    /** 冻结可卖数量（卖出下单时调用）；可用不足直接拒绝以防超卖。 */
    public void freeze(BigDecimal qty) {
        if (qty == null || qty.signum() <= 0) {
            throw new InvalidArgumentApiException("INVALID_FREEZE_QTY", "freeze quantity must be positive");
        }
        if (qty.compareTo(availableQuantity) > 0) {
            throw new InvalidArgumentApiException("INSUFFICIENT_POSITION",
                    "freeze " + qty + " exceeds available " + availableQuantity + " of " + symbol);
        }
        this.availableQuantity = availableQuantity.subtract(qty);
    }

    /** 撤销卖出时释放已冻结数量（不得超过总冻结量）。 */
    public void releaseFreeze(BigDecimal qty) {
        if (qty == null || qty.signum() < 0) {
            throw new InvalidArgumentApiException("INVALID_RELEASE_QTY", "release quantity invalid");
        }
        BigDecimal frozen = quantity.subtract(availableQuantity);
        if (qty.compareTo(frozen) > 0) {
            throw new InvalidArgumentApiException("RELEASE_EXCEEDS_FROZEN",
                    "release " + qty + " exceeds frozen " + frozen);
        }
        this.availableQuantity = availableQuantity.add(qty);
    }

    /**
     * 拆股/合股：每 1 股变为 ratio 股。
     * 数量等比放大、单位成本等比缩小（乘 1/ratio），保证 totalCost 不变。
     * 幂等由 CorporateActionService 按事件 id 保证，此处不做去重。
     */
    public void applyRatio(BigDecimal ratio) {
        if (ratio == null || ratio.signum() <= 0) {
            throw new InvalidArgumentApiException("INVALID_RATIO", "split ratio must be positive");
        }
        BigDecimal oldTotalCost = totalCost();
        BigDecimal newQty = quantity.multiply(ratio).setScale(8, RoundingMode.HALF_UP);
        this.quantity = newQty.stripTrailingZeros();
        this.availableQuantity = availableQuantity.multiply(ratio)
                .setScale(8, RoundingMode.HALF_UP).stripTrailingZeros();
        if (this.quantity.signum() > 0) {
            this.avgCost = oldTotalCost.divide(this.quantity, 8, RoundingMode.HALF_UP);
        }
    }
}
