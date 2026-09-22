package com.github.highcumontoa.backtestportfolioenginejava.order;

import com.github.highcumontoa.backtestportfolioenginejava.domain.Order;
import com.github.highcumontoa.backtestportfolioenginejava.error.StateConflictException;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存订单仓储（线程安全）。
 * orderId 与 idempotencyKey 均唯一；同一幂等键的并发插入只有一方成功。
 */
@Repository
public class OrderRepository {

    private final ConcurrentHashMap<String, Order> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> idempotencyIndex = new ConcurrentHashMap<>();

    /**
     * 插入订单。若幂等键已存在，返回该键对应的既有订单（调用方据此实现"只产生一次有效结果"）。
     */
    public Order insert(Order order) {
        String key = order.getIdempotencyKey();
        if (key != null) {
            String existing = idempotencyIndex.putIfAbsent(key, order.getOrderId());
            if (existing != null) {
                Order prior = byId.get(existing);
                if (prior != null) {
                    return prior;
                }
            }
        }
        Order race = byId.putIfAbsent(order.getOrderId(), order);
        if (race != null) {
            if (key != null && order.getOrderId().equals(idempotencyIndex.get(key))) {
                throw new StateConflictException("orderId conflict: " + order.getOrderId());
            }
            return race;
        }
        return order;
    }

    public Optional<Order> findById(String orderId) {
        return Optional.ofNullable(byId.get(orderId));
    }

    public Optional<Order> findByIdempotencyKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(idempotencyIndex.get(key)).map(byId::get);
    }

    public Collection<Order> findAll() {
        return byId.values();
    }
}
