package com.github.highcumontoa.backtestportfolioenginejava.service;

import com.github.highcumontoa.backtestportfolioenginejava.exception.InvalidArgumentApiException;
import com.github.highcumontoa.backtestportfolioenginejava.exception.StateConflictApiException;
import com.github.highcumontoa.backtestportfolioenginejava.model.Order;
import com.github.highcumontoa.backtestportfolioenginejava.model.OrderRequest;
import com.github.highcumontoa.backtestportfolioenginejava.model.OrderStatus;
import com.github.highcumontoa.backtestportfolioenginejava.model.RejectReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 订单生命周期管理。
 *
 * <p>幂等：{@code clientOrderId} 为幂等键，并发/重复提交同一键只创建一次订单，
 * 其余线程直接取回既有订单（返回的 {@link SubmitResult#created()} 标识是否为本次新建）。
 * orderId 为内部唯一单调键。撤单/拒单委托 {@link Order#terminate} 做终态校验，
 * 非法迁移抛 {@link StateConflictApiException}（ILLEGAL_TRANSITION）。
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final ConcurrentHashMap<String, Order> byOrderId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> clientIdToOrderId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> orderLocks = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(0);

    /** 下单结果：order 为最终订单实体；created=false 表示命中幂等键、复用既有订单。 */
    public record SubmitResult(Order order, boolean created) { }

    /**
     * 幂等下单。
     * @throws InvalidArgumentApiException clientOrderId 缺失
     */
    public SubmitResult submit(OrderRequest request, long eventTime) {
        if (request == null) {
            throw new InvalidArgumentApiException("ORDER_REQUIRED", "order request required");
        }
        if (request.clientOrderId() == null || request.clientOrderId().isBlank()) {
            throw new InvalidArgumentApiException("CLIENT_ORDER_ID_REQUIRED",
                    "clientOrderId (idempotency key) required");
        }
        final String clientId = request.clientOrderId();
        // 并发同键：computeIfAbsent 保证映射动作原子，仅一个线程进入工厂创建
        String orderId = clientIdToOrderId.computeIfAbsent(clientId, k -> {
            String newId = "ORD-" + sequence.incrementAndGet();
            log.info("submit accepted clientOrderId={} orderId={} side={} type={} symbol={} qty={} t={}",
                    clientId, newId, request.side(), request.type(), request.symbol(),
                    request.quantity(), eventTime);
            return newId;
        });
        synchronized (lockFor(orderId)) {
            Order existing = byOrderId.get(orderId);
            if (existing != null) {
                log.debug("submit idempotent hit clientOrderId={} orderId={} status={}",
                        clientId, orderId, existing.getStatus());
                return new SubmitResult(existing, false);
            }
            Order order = new Order(orderId, clientId, request.side(), request.type(),
                    request.symbol(), request.quantity(), request.limitPrice(),
                    request.currency(), eventTime);
            byOrderId.put(orderId, order);
            return new SubmitResult(order, true);
        }
    }

    /** 撤单：仅 NEW/PARTIALLY_FILLED 可撤；终态抛状态冲突。 */
    public Order cancel(String orderId, long eventTime) {
        return terminate(orderId, OrderStatus.CANCELLED, null, eventTime);
    }

    /** 拒单：仅非终态可拒，需给出可区分原因。 */
    public Order reject(String orderId, RejectReason reason, long eventTime) {
        if (reason == null) {
            throw new InvalidArgumentApiException("REJECT_REASON_REQUIRED", "reject reason required");
        }
        return terminate(orderId, OrderStatus.REJECTED, reason, eventTime);
    }

    private Order terminate(String orderId, OrderStatus target,
                            RejectReason reason, long eventTime) {
        Order order = requireOrder(orderId);
        synchronized (lockFor(orderId)) {
            try {
                order.terminate(target, reason, eventTime);
                log.info("order {} -> {} reason={} t={}", orderId, target, reason, eventTime);
            } catch (StateConflictApiException ex) {
                log.warn("terminate rejected orderId={} current={} target={}",
                        orderId, order.getStatus(), target);
                throw ex;
            }
            return order;
        }
    }

    /** 在订单锁内应用一次成交状态迁移（由回测引擎/成交消费路径调用，保证与撤单互斥）。 */
    public Order applyFill(String orderId, java.math.BigDecimal qty,
                           java.math.BigDecimal price, long eventTime) {
        Order order = requireOrder(orderId);
        synchronized (lockFor(orderId)) {
            order.applyFill(qty, price, eventTime);
            return order;
        }
    }

    public Optional<Order> get(String orderId) {
        return Optional.ofNullable(byOrderId.get(orderId));
    }

    public Optional<Order> getByClientId(String clientOrderId) {
        return Optional.ofNullable(clientIdToOrderId.get(clientOrderId))
                .map(byOrderId::get);
    }

    /** 返回所有已创建订单的快照（按 orderId 排序），供回测引擎遍历在途订单。 */
    public java.util.List<Order> allOrders() {
        return byOrderId.values().stream()
                .sorted(java.util.Comparator.comparing(Order::getOrderId))
                .toList();
    }

    private Order requireOrder(String orderId) {
        Order order = byOrderId.get(orderId);
        if (order == null) {
            throw new InvalidArgumentApiException("ORDER_NOT_FOUND", "unknown orderId " + orderId);
        }
        return order;
    }

    private Object lockFor(String orderId) {
        return orderLocks.computeIfAbsent(orderId, k -> new Object());
    }
}
