package com.github.highcumontoa.backtestportfolioenginejava.model.lot;

import com.github.highcumontoa.backtestportfolioenginejava.exception.InvalidArgumentApiException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 持仓成本批次：一笔买入成交形成一个独立批次（部分成交拆成多个批次）。
 *
 * <p>批次金额按“成本池”记账，避免反复四舍五入产生尾差：
 * <ul>
 *   <li>{@code remainingQty}：批次尚未卖出的数量；</li>
 *   <li>{@code remainingCost}：尚未卖出部分对应的成本（含归属买入费用）；</li>
 *   <li>{@code allocatedCost}：已被卖出部分消耗的成本，用于对账；</li>
 *   <li>单位成本 = 剩余成本 / 剩余数量，动态计算（8 位 HALF_UP）。</li>
 * </ul>
 * 本类非线程安全；所有变更必须在 {@code PortfolioService} 的逐标的锁内进行。
 */
public final class Lot {

    /** 单位成本等中间量的内部精度。 */
    public static final int UNIT_SCALE = 8;

    private final String lotId;
    private final String symbol;
    private final String execId;
    private final String orderId;
    private final long openTime;
    private final BigDecimal fillPrice;
    private final BigDecimal commission;
    private final BigDecimal tax;

    /** 建批时数量（拆合股后更新为调整后的数量），仅作展示。 */
    private BigDecimal initialQty;
    /** 建批时成本（拆合股后重置为调整后的剩余成本），仅作展示。 */
    private BigDecimal initialCost;
    private BigDecimal remainingQty;
    private BigDecimal remainingCost;
    private BigDecimal allocatedCost;

    public Lot(String lotId, String symbol, String execId, String orderId, long openTime,
               BigDecimal quantity, BigDecimal fillPrice, BigDecimal cost,
               BigDecimal commission, BigDecimal tax) {
        if (lotId == null || lotId.isBlank()) {
            throw new InvalidArgumentApiException("LOT_ID_REQUIRED", "lot id required");
        }
        if (quantity == null || quantity.signum() <= 0) {
            throw new InvalidArgumentApiException("INVALID_LOT_QTY", "lot quantity must be positive");
        }
        if (cost == null || cost.signum() < 0) {
            throw new InvalidArgumentApiException("INVALID_LOT_COST", "lot cost must be non-negative");
        }
        this.lotId = lotId;
        this.symbol = symbol;
        this.execId = execId;
        this.orderId = orderId;
        this.openTime = openTime;
        this.fillPrice = fillPrice;
        this.commission = commission == null ? BigDecimal.ZERO : commission;
        this.tax = tax == null ? BigDecimal.ZERO : tax;
        this.initialQty = quantity;
        this.initialCost = cost;
        this.remainingQty = quantity;
        this.remainingCost = cost;
        this.allocatedCost = BigDecimal.ZERO;
    }

    public String getLotId() { return lotId; }
    public String getSymbol() { return symbol; }
    public String getExecId() { return execId; }
    public String getOrderId() { return orderId; }
    public long getOpenTime() { return openTime; }
    public BigDecimal getFillPrice() { return fillPrice; }
    public BigDecimal getCommission() { return commission; }
    public BigDecimal getTax() { return tax; }
    public BigDecimal getInitialQty() { return initialQty; }
    public BigDecimal getInitialCost() { return initialCost; }
    public BigDecimal getRemainingQty() { return remainingQty; }
    public BigDecimal getRemainingCost() { return remainingCost; }
    public BigDecimal getAllocatedCost() { return allocatedCost; }

    /** 单位成本 = 剩余成本 / 剩余数量（8 位 HALF_UP）；批次耗尽返回 0。 */
    public BigDecimal unitCost() {
        if (remainingQty.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return remainingCost.divide(remainingQty, UNIT_SCALE, RoundingMode.HALF_UP);
    }

    /** 是否还有剩余数量。 */
    public boolean isOpen() {
        return remainingQty.signum() > 0;
    }

    /**
     * FIFO 消耗本批次 {@code qty} 股，返回被消耗的成本金额。
     * 非整批消耗按单位成本（8 位 HALF_UP）折算；整批清空时把剩余成本全部带走，
     * 避免逐批折算累积尾差（所有批次的 remainingCost 最终恰好核销为 0）。
     */
    public BigDecimal consume(BigDecimal qty, int moneyScale) {
        if (qty == null || qty.signum() <= 0) {
            throw new InvalidArgumentApiException("INVALID_LOT_CONSUME", "consume quantity must be positive");
        }
        if (qty.compareTo(remainingQty) > 0) {
            throw new InvalidArgumentApiException("LOT_CONSUME_EXCEEDED",
                    "consume " + qty + " exceeds lot remaining " + remainingQty + " of " + lotId);
        }
        BigDecimal takenCost;
        if (qty.compareTo(remainingQty) == 0) {
            // 整批清空：带走全部剩余成本，杜绝尾差。
            takenCost = remainingCost;
        } else {
            takenCost = unitCost().multiply(qty).setScale(moneyScale, RoundingMode.HALF_UP);
        }
        remainingQty = remainingQty.subtract(qty);
        remainingCost = remainingCost.subtract(takenCost);
        allocatedCost = allocatedCost.add(takenCost);
        if (remainingQty.signum() == 0) {
            remainingCost = BigDecimal.ZERO.setScale(moneyScale, RoundingMode.HALF_UP);
        }
        return takenCost;
    }

    /**
     * 拆股/合股：剩余数量乘 ratio（8 位 HALF_UP）；剩余成本保持不变。
     * 调整后重置展示用的初始数量/成本，使后续单位成本仍等于调整后的成本口径，
     * 已消耗（allocated）部分不受影响。
     */
    public void applyRatio(BigDecimal ratio) {
        if (ratio == null || ratio.signum() <= 0) {
            throw new InvalidArgumentApiException("INVALID_RATIO", "split ratio must be positive");
        }
        BigDecimal newQty = remainingQty.multiply(ratio)
                .setScale(UNIT_SCALE, RoundingMode.HALF_UP).stripTrailingZeros();
        this.remainingQty = newQty;
        this.initialQty = newQty;
        this.initialCost = remainingCost;
    }
}
