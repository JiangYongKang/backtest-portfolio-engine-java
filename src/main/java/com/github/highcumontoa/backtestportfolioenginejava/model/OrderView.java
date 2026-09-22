package com.github.highcumontoa.backtestportfolioenginejava.model;

import java.math.BigDecimal;

/** 订单只读视图（供查询/REST 返回）。 */
public record OrderView(
        String orderId,
        String clientOrderId,
        OrderStatus status,
        String side,
        String type,
        String symbol,
        BigDecimal quantity,
        BigDecimal filledQty,
        BigDecimal remainingQty,
        BigDecimal avgFillPrice,
        String rejectReason
) {
    public static OrderView from(Order o) {
        return new OrderView(o.getOrderId(), o.getClientOrderId(), o.getStatus(),
                o.getSide().name(), o.getType().name(), o.getSymbol(),
                o.getQuantity(), o.getFilledQty(), o.remainingQty(),
                o.getAvgFillPrice(),
                o.getRejectReason() == null ? null : o.getRejectReason().name());
    }
}
