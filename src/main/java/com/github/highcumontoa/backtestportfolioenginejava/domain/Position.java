package com.github.highcumontoa.backtestportfolioenginejava.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 单标的持仓（线程安全）。
 * quantity 为当前总持仓，availableQuantity 为可卖（未冻结）数量；
 * 卖单下单时 {@link #reserve} 冻结数量，成交时 {@link #applySettledSell} 核销总量，
 * 撤单时 {@link #release} 退回冻结。
 * costBasis 为持仓总成本（买入含手续费），平均成本 = costBasis / quantity。
 * 精度：数量 8 位小数 HALF_UP；成本 2 位小数 HALF_UP。
 */
public final class Position {

    public static final int QTY_SCALE = 8;
    public static final int MONEY_SCALE = 2;

    private final String symbol;
    private BigDecimal quantity = BigDecimal.ZERO;
    private BigDecimal availableQuantity = BigDecimal.ZERO;
    private BigDecimal costBasis = BigDecimal.ZERO;

    public Position(String symbol) {
        this.symbol = symbol;
    }

    /** 买单成交：数量与成本增加（成本含买入手续费）。 */
    public synchronized void applyBuy(BigDecimal qty, BigDecimal price, BigDecimal commission) {
        BigDecimal q = scaleQty(qty);
        BigDecimal fee = commission == null ? BigDecimal.ZERO : commission;
        BigDecimal addCost = price.multiply(qty).add(fee)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        costBasis = costBasis.add(addCost);
        quantity = quantity.add(q);
        availableQuantity = availableQuantity.add(q);
    }

    /**
     * 卖单成交结算：数量此前已通过 reserve 冻结，这里只核销总量并按平均成本结转，
     * 不再重复扣减 availableQuantity。
     */
    public synchronized void applySettledSell(BigDecimal qty, BigDecimal price) {
        BigDecimal q = scaleQty(qty);
        if (q.compareTo(quantity.subtract(availableQuantity)) > 0) {
            throw new IllegalStateException(
                    "settled sell exceeds reserved quantity for " + symbol);
        }
        BigDecimal avgCost = avgCost();
        BigDecimal reduceCost = avgCost == null ? BigDecimal.ZERO
                : avgCost.multiply(qty).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        quantity = quantity.subtract(q);
        if (quantity.signum() <= 0) {
            quantity = BigDecimal.ZERO;
            availableQuantity = BigDecimal.ZERO;
            costBasis = BigDecimal.ZERO;
        } else {
            costBasis = costBasis.subtract(reduceCost).max(BigDecimal.ZERO);
        }
    }

    /** 卖出下单时预留（冻结）数量。 */
    public synchronized void reserve(BigDecimal qty) {
        BigDecimal q = scaleQty(qty);
        if (q.compareTo(availableQuantity) > 0) {
            throw new IllegalStateException(
                    "insufficient available quantity for " + symbol);
        }
        availableQuantity = availableQuantity.subtract(q);
    }

    /** 撤单时释放此前冻结的数量。 */
    public synchronized void release(BigDecimal qty) {
        availableQuantity = availableQuantity.add(scaleQty(qty));
        if (availableQuantity.compareTo(quantity) > 0) {
            availableQuantity = quantity;
        }
    }

    /**
     * 应用拆/合股：数量与可用数量按乘数调整，总成本基准不变（平均成本相应摊薄/抬高）。
     * 已冻结数量同样按比例调整，保持守恒。
     */
    public synchronized void applyRatioMultiplier(BigDecimal multiplier) {
        if (quantity.signum() == 0) {
            return;
        }
        quantity = quantity.multiply(multiplier)
                .setScale(QTY_SCALE, RoundingMode.HALF_UP);
        availableQuantity = availableQuantity.multiply(multiplier)
                .setScale(QTY_SCALE, RoundingMode.HALF_UP);
    }

    /** 平均成本，空仓返回 null。 */
    public synchronized BigDecimal avgCost() {
        if (quantity.signum() == 0) {
            return null;
        }
        return costBasis.divide(quantity, 12, RoundingMode.HALF_UP);
    }

    private static BigDecimal scaleQty(BigDecimal qty) {
        return qty.setScale(QTY_SCALE, RoundingMode.HALF_UP);
    }

    public String getSymbol() { return symbol; }
    public synchronized BigDecimal getQuantity() { return quantity; }
    public synchronized BigDecimal getAvailableQuantity() { return availableQuantity; }
    public synchronized BigDecimal getCostBasis() { return costBasis; }
}
