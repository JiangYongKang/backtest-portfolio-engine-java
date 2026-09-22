package com.github.highcumontoa.backtestportfolioenginejava.domain;

import com.github.highcumontoa.backtestportfolioenginejava.error.InvalidParameterException;
import com.github.highcumontoa.backtestportfolioenginejava.error.StateConflictException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;

/**
 * 订单聚合根。封装生命周期状态机，所有迁移方法加锁以支持并发下单/撤单。
 * 数量精度 8 位小数，价格保留输入精度；均价内部以 12 位小数 HALF_UP 计算。
 */
public final class Order {

    public static final String REJECT_NON_POSITIVE_QTY = "QUANTITY_MUST_BE_POSITIVE";
    public static final String REJECT_LIMIT_PRICE_REQUIRED = "LIMIT_PRICE_REQUIRED";
    public static final String REJECT_INVALID_SYMBOL = "SYMBOL_REQUIRED";

    private final String orderId;
    private final String idempotencyKey;
    private final String accountId;
    private final String symbol;
    private final Side side;
    private final OrderType type;
    private final BigDecimal totalQuantity;
    private final BigDecimal limitPrice;
    private final Instant submitTime;

    private OrderStatus status = OrderStatus.NEW;
    private BigDecimal filledQuantity = BigDecimal.ZERO;
    private BigDecimal avgFillPrice = null;
    private Instant lastFillTime = null;
    private String rejectReason = null;

    public Order(String orderId, String idempotencyKey, String accountId, String symbol,
                 Side side, OrderType type, BigDecimal totalQuantity,
                 BigDecimal limitPrice, Instant submitTime) {
        if (orderId == null || orderId.isBlank()) {
            throw new InvalidParameterException("orderId is required");
        }
        if (accountId == null || accountId.isBlank()) {
            throw new InvalidParameterException("accountId is required");
        }
        if (symbol == null || symbol.isBlank()) {
            throw new InvalidParameterException(REJECT_INVALID_SYMBOL, "symbol is required");
        }
        this.side = Objects.requireNonNull(side, "side is required");
        this.type = Objects.requireNonNull(type, "type is required");
        if (totalQuantity == null || totalQuantity.signum() <= 0) {
            throw new InvalidParameterException(REJECT_NON_POSITIVE_QTY, "quantity must be positive");
        }
        if (type == OrderType.LIMIT && (limitPrice == null || limitPrice.signum() <= 0)) {
            throw new InvalidParameterException(REJECT_LIMIT_PRICE_REQUIRED, "limit order requires positive limit price");
        }
        if (submitTime == null) {
            throw new InvalidParameterException("submitTime is required");
        }
        this.orderId = orderId;
        this.idempotencyKey = idempotencyKey;
        this.accountId = accountId;
        this.symbol = symbol;
        this.totalQuantity = totalQuantity.stripTrailingZeros();
        this.limitPrice = limitPrice;
        this.submitTime = submitTime;
    }

    /**
     * 应用一笔成交（支持部分成交）。超额成交或在终态成交均抛 STATE_CONFLICT。
     */
    public synchronized void applyFill(BigDecimal qty, BigDecimal price, Instant fillTime) {
        if (qty == null || qty.signum() <= 0) {
            throw new InvalidParameterException("fill quantity must be positive");
        }
        if (price == null || price.signum() <= 0) {
            throw new InvalidParameterException("fill price must be positive");
        }
        if (!status.acceptsFill()) {
            throw new StateConflictException(
                    "order " + orderId + " cannot accept fill in status " + status);
        }
        BigDecimal newFilled = filledQuantity.add(qty);
        if (newFilled.compareTo(totalQuantity) > 0) {
            throw new StateConflictException(
                    "fill quantity exceeds order quantity for order " + orderId);
        }
        BigDecimal oldNotional = avgFillPrice == null
                ? BigDecimal.ZERO
                : avgFillPrice.multiply(filledQuantity);
        avgFillPrice = oldNotional.add(price.multiply(qty))
                .divide(newFilled, 12, RoundingMode.HALF_UP);
        filledQuantity = newFilled;
        lastFillTime = fillTime;
        status = newFilled.compareTo(totalQuantity) == 0
                ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
    }

    /**
     * 结算阶段失败时回滚刚应用的成交，恢复到迁移前状态（仅 OrderService 在同一锁内调用）。
     */
    public synchronized void rollbackFill(BigDecimal qty, BigDecimal previousFilled,
                                          OrderStatus previousStatus) {
        BigDecimal restoredNotional = avgFillPrice == null || previousFilled.signum() == 0
                ? BigDecimal.ZERO
                : avgFillPrice.multiply(filledQuantity.subtract(qty));
        filledQuantity = previousFilled;
        avgFillPrice = previousFilled.signum() == 0 ? null
                : restoredNotional.divide(previousFilled, 12, RoundingMode.HALF_UP);
        status = previousStatus;
    }

    public synchronized void cancel() {
        if (status == OrderStatus.FILLED) {
            throw new StateConflictException("cannot cancel a fully filled order " + orderId);
        }
        if (status.isTerminal()) {
            throw new StateConflictException(
                    "cannot cancel order " + orderId + " in terminal status " + status);
        }
        status = OrderStatus.CANCELLED;
    }

    public synchronized void reject(String reason) {
        if (status.isTerminal()) {
            throw new StateConflictException(
                    "cannot reject order " + orderId + " in terminal status " + status);
        }
        this.status = OrderStatus.REJECTED;
        this.rejectReason = reason;
    }

    public synchronized BigDecimal remainingQuantity() {
        return totalQuantity.subtract(filledQuantity);
    }

    public String getOrderId() { return orderId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getAccountId() { return accountId; }
    public String getSymbol() { return symbol; }
    public Side getSide() { return side; }
    public OrderType getType() { return type; }
    public BigDecimal getTotalQuantity() { return totalQuantity; }
    public BigDecimal getLimitPrice() { return limitPrice; }
    public Instant getSubmitTime() { return submitTime; }
    public synchronized OrderStatus getStatus() { return status; }
    public synchronized BigDecimal getFilledQuantity() { return filledQuantity; }
    public synchronized BigDecimal getAvgFillPrice() { return avgFillPrice; }
    public synchronized Instant getLastFillTime() { return lastFillTime; }
    public synchronized String getRejectReason() { return rejectReason; }
}
