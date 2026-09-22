package com.github.highcumontoa.backtestportfolioenginejava.model;

import com.github.highcumontoa.backtestportfolioenginejava.exception.InvalidArgumentApiException;
import com.github.highcumontoa.backtestportfolioenginejava.exception.StateConflictApiException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 订单聚合，维护唯一且可查询的最终状态。
 *
 * <p>合法状态迁移（非法迁移抛 {@link StateConflictApiException}，原因 ILLEGAL_TRANSITION）：
 * <pre>
 *   NEW               -> PARTIALLY_FILLED / FILLED / CANCELLED / REJECTED
 *   PARTIALLY_FILLED  -> PARTIALLY_FILLED / FILLED / CANCELLED / REJECTED
 *   FILLED/CANCELLED/REJECTED 为终态，任何迁移都被拒绝
 * </pre>
 * 成交数量必须为正且不得超过剩余数量；均价按成交量加权（价格精度由撮合层保证）。
 */
public final class Order {
    private final String orderId;
    private final String clientOrderId;
    private final Side side;
    private final OrderType type;
    private final String symbol;
    private final BigDecimal quantity;
    private final BigDecimal limitPrice;
    private final String currency;
    private final long createTime;

    private OrderStatus status;
    private BigDecimal filledQty;
    private BigDecimal avgFillPrice;
    private RejectReason rejectReason;
    private long lastUpdateTime;

    public Order(String orderId, String clientOrderId, Side side, OrderType type,
                 String symbol, BigDecimal quantity, BigDecimal limitPrice,
                 String currency, long createTime) {
        if (orderId == null || orderId.isBlank()) {
            throw new InvalidArgumentApiException("ORDER_ID_REQUIRED", "orderId required");
        }
        if (side == null || type == null || symbol == null || symbol.isBlank()) {
            throw new InvalidArgumentApiException("ORDER_FIELD_REQUIRED", "side/type/symbol required");
        }
        if (quantity == null || quantity.signum() <= 0) {
            throw new InvalidArgumentApiException("INVALID_QUANTITY", "quantity must be positive");
        }
        if (type == OrderType.LIMIT && (limitPrice == null || limitPrice.signum() <= 0)) {
            throw new InvalidArgumentApiException("INVALID_LIMIT_PRICE",
                    "limit order requires positive limitPrice");
        }
        this.orderId = orderId;
        this.clientOrderId = clientOrderId;
        this.side = side;
        this.type = type;
        this.symbol = symbol;
        this.quantity = quantity;
        this.limitPrice = limitPrice;
        this.currency = currency;
        this.createTime = createTime;
        this.status = OrderStatus.NEW;
        this.filledQty = BigDecimal.ZERO;
        this.avgFillPrice = BigDecimal.ZERO;
        this.lastUpdateTime = createTime;
    }

    public String getOrderId() { return orderId; }
    public String getClientOrderId() { return clientOrderId; }
    public Side getSide() { return side; }
    public OrderType getType() { return type; }
    public String getSymbol() { return symbol; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getLimitPrice() { return limitPrice; }
    public String getCurrency() { return currency; }
    public long getCreateTime() { return createTime; }
    public OrderStatus getStatus() { return status; }
    public BigDecimal getFilledQty() { return filledQty; }
    public BigDecimal getAvgFillPrice() { return avgFillPrice; }
    public RejectReason getRejectReason() { return rejectReason; }
    public long getLastUpdateTime() { return lastUpdateTime; }

    /**
     * 应用一笔成交：校验非终态、数量合法且不超剩余，更新累计成交量与加权均价，
     * 并据剩余数量迁移到 PARTIALLY_FILLED 或 FILLED。
     * 重复成交回报的幂等在 FillService（按 execId）保证，聚合内只保证数量守恒。
     */
    public void applyFill(BigDecimal qty, BigDecimal price, long eventTime) {
        if (qty == null || qty.signum() <= 0) {
            throw new InvalidArgumentApiException("INVALID_FILL_QTY", "fill quantity must be positive");
        }
        if (price == null || price.signum() <= 0) {
            throw new InvalidArgumentApiException("INVALID_FILL_PRICE", "fill price must be positive");
        }
        if (status == OrderStatus.FILLED || status == OrderStatus.CANCELLED
                || status == OrderStatus.REJECTED) {
            throw new StateConflictApiException("ILLEGAL_TRANSITION",
                    "order " + orderId + " already terminal: " + status);
        }
        if (qty.compareTo(remainingQty()) > 0) {
            throw new InvalidArgumentApiException("FILL_EXCEEDS_REMAINING",
                    "fill " + qty + " exceeds remaining " + remainingQty());
        }
        BigDecimal newFilled = filledQty.add(qty);
        // 加权平均成交价：(旧成交量*旧均价 + 本次量*本次价)/新成交量，保留 8 位中间精度
        BigDecimal weighted = filledQty.multiply(avgFillPrice).add(qty.multiply(price))
                .divide(newFilled, 8, RoundingMode.HALF_UP);
        this.avgFillPrice = weighted;
        this.filledQty = newFilled;
        this.status = (filledQty.compareTo(quantity) == 0)
                ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
        this.lastUpdateTime = eventTime;
    }

    /** 迁移到终态 CANCELLED/REJECTED；FILLED 已终态不可再迁移。 */
    public void terminate(OrderStatus terminal, RejectReason reason, long eventTime) {
        if (terminal != OrderStatus.CANCELLED && terminal != OrderStatus.REJECTED) {
            throw new InvalidArgumentApiException("ILLEGAL_TERMINAL",
                    "terminate() only accepts CANCELLED/REJECTED");
        }
        if (status == OrderStatus.FILLED || status == OrderStatus.CANCELLED
                || status == OrderStatus.REJECTED) {
            throw new StateConflictApiException("ILLEGAL_TRANSITION",
                    "order " + orderId + " cannot move from " + status + " to " + terminal);
        }
        this.status = terminal;
        this.rejectReason = reason;
        this.lastUpdateTime = eventTime;
    }

    public BigDecimal remainingQty() {
        return quantity.subtract(filledQty);
    }
}
