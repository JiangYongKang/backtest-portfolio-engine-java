package com.github.highcumontoa.backtestportfolioenginejava.order;

import com.github.highcumontoa.backtestportfolioenginejava.domain.Fill;
import com.github.highcumontoa.backtestportfolioenginejava.domain.Order;
import com.github.highcumontoa.backtestportfolioenginejava.domain.OrderStatus;
import com.github.highcumontoa.backtestportfolioenginejava.domain.OrderType;
import com.github.highcumontoa.backtestportfolioenginejava.domain.Side;
import com.github.highcumontoa.backtestportfolioenginejava.error.InvalidParameterException;
import com.github.highcumontoa.backtestportfolioenginejava.error.StateConflictException;
import com.github.highcumontoa.backtestportfolioenginejava.matching.MatchingEngine;
import com.github.highcumontoa.backtestportfolioenginejava.portfolio.Portfolio;
import com.github.highcumontoa.backtestportfolioenginejava.portfolio.PortfolioRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 订单生命周期服务（线程安全）。
 *
 * 资源模型（买单）：下单时全额预留 estimatedOutflow（保留至订单终态）；
 *   每笔成交现金只扣减"实际成交额+费用"，预留额不动；
 *   订单进入终态（FILLED/CANCELLED/REJECTED）时统一释放剩余预留。
 *   若实际成交累计将超过预留（现金不足），结算抛 RESOURCE_LIMITED。
 * 资源模型（卖单）：下单冻结全部数量；成交核销总量（available 已冻结）；
 *   撤单释放剩余冻结数量。
 *
 * 幂等：同一 idempotencyKey 并发/重复提交只产生一个有效订单；
 *   同一 fillId 成交回报重复消费只生效一次。
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository repository;
    private final PortfolioRegistry registry;
    private final MatchingEngine matchingEngine;

    /** 买单：本单全额冻结资金；卖单：本单冻结数量。终态时移除。 */
    private final Map<String, BigDecimal> reservedAmounts = new ConcurrentHashMap<>();
    /** 买单已实际结算（扣现金）的累计金额，用于完结时精确释放剩余预留。 */
    private final Map<String, BigDecimal> settledBuyAmounts = new ConcurrentHashMap<>();
    private final Set<String> consumedFills = ConcurrentHashMap.newKeySet();

    public OrderService(OrderRepository repository, PortfolioRegistry registry,
                        MatchingEngine matchingEngine) {
        this.repository = repository;
        this.registry = registry;
        this.matchingEngine = matchingEngine;
    }

    public record SubmitResult(Order order, boolean created) {
    }

    /**
     * 提交订单（幂等）。estimatedOutflow 为买单按限价或市价参考价估算的含费预留资金，
     * 由调用方用 CostCalculator 计算后传入，使订单模块不直接依赖行情模块。
     */
    public SubmitResult submit(String accountId, String symbol, Side side, OrderType type,
                               BigDecimal quantity, BigDecimal limitPrice,
                               BigDecimal estimatedOutflow, Instant at,
                               String idempotencyKey) {
        return submit("ord-" + UUID.randomUUID(), accountId, symbol, side, type,
                quantity, limitPrice, estimatedOutflow, at, idempotencyKey);
    }

    /**
     * 提交订单（显式 orderId，供事件回测使用，保证重放可复现）。
     */
    public SubmitResult submit(String orderId, String accountId, String symbol, Side side,
                               OrderType type, BigDecimal quantity, BigDecimal limitPrice,
                               BigDecimal estimatedOutflow, Instant at,
                               String idempotencyKey) {
        Portfolio portfolio = registry.require(accountId);

        if (idempotencyKey != null) {
            Optional<Order> existing = repository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                log.info("idempotent submit key={} -> existing order={} status={}",
                        idempotencyKey, existing.get().getOrderId(), existing.get().getStatus());
                return new SubmitResult(existing.get(), false);
            }
        }

        Order order = new Order(orderId, idempotencyKey, accountId, symbol, side, type,
                quantity, limitPrice, at);

        if (side.isBuy()) {
            if (estimatedOutflow == null || estimatedOutflow.signum() <= 0) {
                throw new InvalidParameterException("estimatedOutflow required for buy order");
            }
            portfolio.reserveBuyCash(portfolio.getBaseCurrency(), estimatedOutflow);
            reservedAmounts.put(orderId, estimatedOutflow);
            settledBuyAmounts.put(orderId, BigDecimal.ZERO);
        } else {
            portfolio.reserveSellQuantity(symbol, quantity);
            reservedAmounts.put(orderId, quantity);
        }

        Order stored = repository.insert(order);
        if (stored != order) {
            rollbackReservation(portfolio, side, symbol, orderId,
                    side.isBuy() ? estimatedOutflow : quantity);
            log.info("idempotent submit key={} lost race -> existing order={}",
                    idempotencyKey, stored.getOrderId());
            return new SubmitResult(stored, false);
        }
        log.info("submit created order={} account={} {} {} qty={} limit={} at={}",
                orderId, accountId, side, type, quantity, limitPrice, at);
        return new SubmitResult(order, true);
    }

    private void rollbackReservation(Portfolio portfolio, Side side, String symbol,
                                     String orderId, BigDecimal amount) {
        if (side.isBuy()) {
            portfolio.releaseBuyCash(portfolio.getBaseCurrency(), amount);
            settledBuyAmounts.remove(orderId);
        } else {
            portfolio.releaseSellQuantity(symbol, amount);
        }
        reservedAmounts.remove(orderId);
    }

    /**
     * 消费外部成交回报（fillId 幂等）。重复回报不重复结算。
     * 订单在终态（已撤/已拒）收到回报抛 STATE_CONFLICT，非法迁移被拒绝。
     */
    public Order consumeFill(Fill fill) {
        Order order = repository.findById(fill.orderId())
                .orElseThrow(() -> new InvalidParameterException(
                        "unknown order for fill: " + fill.orderId()));

        synchronized (order) {
            if (!consumedFills.add(fill.fillId())) {
                log.info("duplicate fill ignored fillId={} order={} status={}",
                        fill.fillId(), order.getOrderId(), order.getStatus());
                return order;
            }
            if (order.getStatus().isTerminal()) {
                consumedFills.remove(fill.fillId());
                throw new StateConflictException("fill on terminal order "
                        + order.getOrderId() + " status=" + order.getStatus());
            }
            BigDecimal before = order.getFilledQuantity();
            order.applyFill(fill.quantity(), fill.price(), fill.eventTime());
            Portfolio portfolio = registry.require(order.getAccountId());
            BigDecimal reserved = reservedAmounts.get(order.getOrderId());
            // 在状态迁移前先取本单此前已结算累计额，结算成功后再落账，避免读到本笔自身。
            BigDecimal settledBefore = order.getSide().isBuy()
                    ? settledBuyAmounts.getOrDefault(order.getOrderId(), BigDecimal.ZERO)
                    : BigDecimal.ZERO;
            try {
                portfolio.settle(fill, portfolio.getBaseCurrency(),
                        reserved == null ? BigDecimal.ZERO : reserved, settledBefore);
            } catch (RuntimeException ex) {
                // 结算失败（如剩余预留不足）：回滚订单成交状态与幂等标记，资金/持仓不变。
                order.rollbackFill(fill.quantity(), before,
                        before.signum() == 0 ? OrderStatus.NEW : OrderStatus.PARTIALLY_FILLED);
                consumedFills.remove(fill.fillId());
                throw ex;
            }

            if (order.getSide().isBuy()) {
                BigDecimal spent = fill.grossAmount().add(fill.commission()).add(fill.tax());
                settledBuyAmounts.merge(order.getOrderId(), spent, BigDecimal::add);
            }
            if (order.getStatus() == OrderStatus.FILLED) {
                releaseResidualReservation(order, portfolio);
            }
            log.info("fill consumed fillId={} order={} filled {} -> {} status={}",
                    fill.fillId(), order.getOrderId(), before,
                    order.getFilledQuantity(), order.getStatus());
            return order;
        }
    }

    /** 用撮合引擎在 at 时点尝试撮合理想数量（市价/限价），成交则消费回报。 */
    public Optional<Fill> matchRemaining(Order order, Instant at) {
        synchronized (order) {
            if (!order.getStatus().acceptsFill()) {
                return Optional.empty();
            }
            MatchingEngine.MatchResult result =
                    matchingEngine.tryMatch(order, order.remainingQuantity(), at);
            if (!result.matched()) {
                return Optional.empty();
            }
            consumeFill(result.fill());
            return Optional.of(result.fill());
        }
    }

    /** 撤单。已撤幂等返回；已成交/已拒抛状态冲突。 */
    public Order cancel(String orderId) {
        Order order = repository.findById(orderId)
                .orElseThrow(() -> new InvalidParameterException("unknown order: " + orderId));
        synchronized (order) {
            if (order.getStatus() == OrderStatus.CANCELLED) {
                log.info("cancel idempotent order={}", orderId);
                return order;
            }
            if (order.getStatus().isTerminal()) {
                throw new StateConflictException(
                        "cannot cancel terminal order " + orderId + " status=" + order.getStatus());
            }
            order.cancel();
            Portfolio portfolio = registry.require(order.getAccountId());
            releaseResidualReservation(order, portfolio);
            log.info("cancel order={} status=CANCELLED filled={}", orderId,
                    order.getFilledQuantity());
            return order;
        }
    }

    /** 拒单（资金/持仓校验等场景），释放预留。 */
    public Order reject(String orderId, String reason) {
        Order order = repository.findById(orderId)
                .orElseThrow(() -> new InvalidParameterException("unknown order: " + orderId));
        synchronized (order) {
            if (order.getStatus().isTerminal()) {
                throw new StateConflictException(
                        "cannot reject terminal order " + orderId);
            }
            order.reject(reason);
            Portfolio portfolio = registry.require(order.getAccountId());
            releaseResidualReservation(order, portfolio);
            log.info("reject order={} reason={}", orderId, reason);
            return order;
        }
    }

    /**
     * 订单终态时释放剩余预留：
     * 买单释放"初始预留 - 已实际结算"的差额（以账户当前 reserved 为上限）；
     * 卖单释放未成交的剩余冻结数量。
     */
    private void releaseResidualReservation(Order order, Portfolio portfolio) {
        String oid = order.getOrderId();
        BigDecimal reserved = reservedAmounts.remove(oid);
        if (order.getSide().isBuy()) {
            BigDecimal settled = settledBuyAmounts.getOrDefault(oid, BigDecimal.ZERO);
            settledBuyAmounts.remove(oid);
            if (reserved != null) {
                String ccy = portfolio.getBaseCurrency();
                // 成交已等额核销预留，终态只释放"初始预留 - 实际支出"的尾差。
                BigDecimal residual = reserved.subtract(settled).max(BigDecimal.ZERO)
                        .setScale(2, java.math.RoundingMode.HALF_UP);
                if (residual.signum() > 0) {
                    portfolio.releaseBuyCash(ccy, residual);
                }
            }
        } else {
            BigDecimal remaining = order.remainingQuantity();
            if (remaining.signum() > 0) {
                portfolio.releaseSellQuantity(order.getSymbol(), remaining);
            }
        }
    }

    public Optional<Order> get(String orderId) {
        return repository.findById(orderId);
    }

    /** 查询某账户、某标的仍可成交（NEW/PARTIALLY_FILLED）的订单。 */
    public java.util.List<Order> activeOrdersFor(String accountId, String symbol) {
        return repository.findAll().stream()
                .filter(o -> o.getAccountId().equals(accountId))
                .filter(o -> o.getSymbol().equals(symbol))
                .filter(o -> o.getStatus().acceptsFill())
                .toList();
    }

    public boolean isFillConsumed(String fillId) {
        return consumedFills.contains(fillId);
    }
}
