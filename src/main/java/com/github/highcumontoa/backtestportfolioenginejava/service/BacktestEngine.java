package com.github.highcumontoa.backtestportfolioenginejava.service;

import com.github.highcumontoa.backtestportfolioenginejava.model.CorporateAction;
import com.github.highcumontoa.backtestportfolioenginejava.model.Order;
import com.github.highcumontoa.backtestportfolioenginejava.model.OrderRequest;
import com.github.highcumontoa.backtestportfolioenginejava.model.Quote;
import com.github.highcumontoa.backtestportfolioenginejava.model.RejectReason;
import com.github.highcumontoa.backtestportfolioenginejava.model.Side;
import com.github.highcumontoa.backtestportfolioenginejava.model.ValuationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地事件驱动回测编排门面。
 *
 * <p>所有意图（下单/撤单）、行情与公司行为都带事件时间；{@link #runTo} 统一按事件时间排序推进，
 * 因此结论与到达顺序无关、可复现。撮合规则：
 * <ol>
 *   <li>下单时点按最坏情形预占买入资金（金额按限价/当刻价 + 预估费用）或冻结卖出持仓。</li>
 *   <li>每个行情时点尝试撮合所有在途订单；市价/限价由 {@link MatchingService} 判定，
 *       停牌/无价不成交，后续行情恢复后可续撮合（支持部分成交）。</li>
 *   <li>成交生成唯一 execId，经 FillService 幂等后结算到组合；订单全部成交自动释放尾量预占。</li>
 *   <li>撤单释放未用预占/冻结；runTo 结束仍未成交的市价单被拒（MARKET_CLOSED 语义），
 *       限价单保持在途等待下一次 runTo（GTC）。</li>
 * </ol>
 */
@Service
public class BacktestEngine {

    private static final Logger log = LoggerFactory.getLogger(BacktestEngine.class);

    private final MarketDataService marketData;
    private final FxRateService fxRates;
    private final OrderService orderService;
    private final MatchingService matchingService;
    private final FillService fillService;
    private final PortfolioService portfolio;
    private final CorporateActionService corporateActionService;
    private final ValuationService valuationService;

    /** 待处理意图：订单请求或撤单。 */
    private record Intent(long time, OrderRequest order, String cancelOrderId) { }

    private final List<Intent> intents = new ArrayList<>();
    private final List<CorporateAction> actions = new ArrayList<>();
    private final AtomicLong execSeq = new AtomicLong(0);
    private long processedThrough = Long.MIN_VALUE;

    /** 逐订单买入现金预占台账：orderId -> 当前仍预占的金额。 */
    private final java.util.concurrent.ConcurrentHashMap<String, BigDecimal> cashReservation =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 逐订单卖出冻结台账：orderId -> 当前仍冻结数量。 */
    private final java.util.concurrent.ConcurrentHashMap<String, BigDecimal> frozenReservation =
            new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * 可见成交量（流动性）约束：key = symbol|eventTime -> 该时刻可撮合量。
     * 未设置视为流动性充足（按订单剩余量成交）；设置后单笔撮合取 min(剩余量, 可见量)，
     * 从而可复现“部分成交、后续行情继续撮合”的场景。
     */
    private final java.util.concurrent.ConcurrentHashMap<String, BigDecimal> liquidity =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static String liquidityKey(String symbol, long t) {
        return symbol + "|" + t;
    }

    /** 设置某 symbol 在某事件时间的可见成交量（用于部分成交回放）。 */
    public void addLiquidity(String symbol, long eventTime, BigDecimal availableQuantity) {
        liquidity.put(liquidityKey(symbol, eventTime), availableQuantity);
    }

    /** 订单最后一次撮合所在的事件时间，防止同一 tick 内被下单路径与推进路径重复撮合。 */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> lastMatchTime =
            new java.util.concurrent.ConcurrentHashMap<>();

    public BacktestEngine(MarketDataService marketData, FxRateService fxRates,
                          OrderService orderService, MatchingService matchingService,
                          FillService fillService, PortfolioService portfolio,
                          CorporateActionService corporateActionService,
                          ValuationService valuationService) {
        this.marketData = marketData;
        this.fxRates = fxRates;
        this.orderService = orderService;
        this.matchingService = matchingService;
        this.fillService = fillService;
        this.portfolio = portfolio;
        this.corporateActionService = corporateActionService;
        this.valuationService = valuationService;
    }

    public void addQuotes(List<Quote> quotes) {
        marketData.ingestAll(quotes);
    }

    public void addFxRates(List<com.github.highcumontoa.backtestportfolioenginejava.model.FxRate> rates) {
        fxRates.ingestAll(rates);
    }

    public void addCorporateActions(List<CorporateAction> corporateActions) {
        if (corporateActions != null) {
            actions.addAll(corporateActions);
        }
    }

    /** 提交订单意图（在 eventTime 下单并立即尝试撮合）。 */
    public void placeOrder(OrderRequest request, long eventTime) {
        intents.add(new Intent(eventTime, request, null));
    }

    /** 撤单意图。 */
    public void requestCancel(String orderId, long eventTime) {
        intents.add(new Intent(eventTime, null, orderId));
    }

    /** 推进至 targetTime：依次处理公司行为、意图、行情推进，并对在途订单续撮合。 */
    public ValuationResult runTo(long targetTime, String baseCcy) {
        // 1) 公司行为（幂等）
        actions.stream()
                .filter(a -> a.effectiveTime() <= targetTime)
                .sorted(java.util.Comparator.comparingLong(CorporateAction::effectiveTime))
                .forEach(corporateActionService::apply);
        // 已处理的行为从待处理列表移除，避免重复遍历（apply 本身幂等，双保险）
        actions.removeIf(a -> a.effectiveTime() <= targetTime);

        // 2) 意图按时间处理
        List<Intent> due = intents.stream()
                .filter(i -> i.time() <= targetTime)
                .sorted(java.util.Comparator.comparingLong(Intent::time))
                .toList();
        for (Intent in : due) {
            if (in.order() != null) {
                handleSubmit(in.order(), in.time());
            } else {
                handleCancel(in.cancelOrderId(), in.time());
            }
        }
        intents.removeIf(i -> i.time() <= targetTime);

        // 3) 以 [processedThrough, targetTime] 内的行情推进并续撮合
        matchAt(targetTime);
        processedThrough = targetTime;

        // 4) 到点仍无任何成交可能的市价单做拒单结论，释放占用；限价单保留等待。
        expireMarketOrders(targetTime);

        return valuationService.value(targetTime, baseCcy);
    }

    private void handleSubmit(OrderRequest req, long t) {
        OrderService.SubmitResult sr = orderService.submit(req, t);
        if (!sr.created()) {
            return; // 幂等命中，不重复预占/冻结
        }
        Order order = sr.order();
        String ccy = req.currency();
        if (order.getSide() == Side.BUY) {
            BigDecimal px = reservePrice(order, t);
            if (px == null) {
                // 下单时点无价：无法估算所需资金，直接拒单，避免按 0 预占
                orderService.reject(order.getOrderId(), RejectReason.MARKET_CLOSED, t);
                return;
            }
            BigDecimal estCost = estimateCost(order, px);
            try {
                portfolio.reserveCash(ccy, estCost);
                cashReservation.put(order.getOrderId(), estCost);
            } catch (com.github.highcumontoa.backtestportfolioenginejava.exception.ApiException ex) {
                orderService.reject(order.getOrderId(),
                        "INSUFFICIENT_FUNDS".equals(ex.getCode())
                                ? RejectReason.INSUFFICIENT_FUNDS
                                : RejectReason.RISK_LIMIT_EXCEEDED, t);
                return;
            }
        } else {
            try {
                portfolio.freezePosition(order.getSymbol(), order.getQuantity());
                frozenReservation.put(order.getOrderId(), order.getQuantity());
            } catch (com.github.highcumontoa.backtestportfolioenginejava.exception.ApiException ex) {
                orderService.reject(order.getOrderId(), RejectReason.INSUFFICIENT_POSITION, t);
                return;
            }
        }
        matchOrder(order, t);
    }

    private void handleCancel(String orderId, long t) {
        Optional<Order> opt = orderService.get(orderId);
        if (opt.isEmpty()) {
            log.warn("cancel ignored unknown orderId={}", orderId);
            return;
        }
        Order order = opt.get();
        try {
            orderService.cancel(orderId, t);
        } catch (com.github.highcumontoa.backtestportfolioenginejava.exception.StateConflictApiException ex) {
            log.warn("cancel rejected orderId={} status={}", orderId, order.getStatus());
            return;
        }
        releaseReservation(order);
    }

    /** 用截至 t 的最新可见行情撮合一次所有在途订单。 */
    private void matchAt(long t) {
        for (Order order : activeOrders()) {
            matchOrder(order, t);
        }
    }

    private void matchOrder(Order order, long t) {
        if (isTerminal(order)) {
            return;
        }
        Long lastT = lastMatchTime.get(order.getOrderId());
        if (lastT != null && lastT >= t) {
            return; // 同一（或更早）事件时间已撮合过，防止同 tick 重复成交
        }
        Optional<Quote> q = marketData.latestAt(order.getSymbol(), t);
        if (q.isEmpty()) {
            return; // 从未有行情：缺失，暂不成交
        }
        BigDecimal ref = matchingService.referencePrice(order, q.get());
        if (ref == null) {
            return; // 停牌/无价/限价未穿越：明确不成交，等待后续
        }
        BigDecimal avail = liquidity.get(liquidityKey(order.getSymbol(), t));
        BigDecimal qty = (avail == null)
                ? matchingService.matchedQuantity(order, q.get())
                : matchingService.matchedQuantity(order, q.get(), avail);
        if (qty.signum() == 0) {
            return;
        }
        String execId = "FILL-" + execSeq.incrementAndGet();
        var fill = fillService.buildFill(execId, order, qty, ref, t);
        if (!fillService.consume(fill)) {
            return; // 重复回报保护
        }
        portfolio.applyFill(fill, order.getCurrency());
        orderService.applyFill(order.getOrderId(), qty, fill.price(), t);
        lastMatchTime.put(order.getOrderId(), t);
        Order updated = orderService.get(order.getOrderId()).orElseThrow();
        if (updated.getStatus() == com.github.highcumontoa.backtestportfolioenginejava.model.OrderStatus.FILLED) {
            releaseReservation(updated);
        } else {
            recalibrateReservation(updated, t);
            log.debug("partial fill orderId={} filled={} remaining={}",
                    order.getOrderId(), updated.getFilledQty(), updated.remainingQty());
        }
    }

    private void expireMarketOrders(long t) {
        for (Order order : activeOrders()) {
            if (order.getType() == com.github.highcumontoa.backtestportfolioenginejava.model.OrderType.MARKET) {
                Optional<Quote> q = marketData.latestAt(order.getSymbol(), t);
                boolean tradable = q.filter(quote -> matchingService.canMatch(order, quote)).isPresent();
                if (!tradable) {
                    orderService.reject(order.getOrderId(), RejectReason.MARKET_CLOSED, t);
                    releaseReservation(order);
                }
            }
        }
    }

    /** 买入预占用价：限价单用限价（最坏成本），市价单用当刻可见价；无价返回 null。 */
    private BigDecimal reservePrice(Order order, long t) {
        if (order.getType() == com.github.highcumontoa.backtestportfolioenginejava.model.OrderType.LIMIT) {
            return order.getLimitPrice();
        }
        return marketData.latestAt(order.getSymbol(), t)
                .map(qq -> matchingService.referencePrice(order, qq))
                .orElse(null);
    }

    private BigDecimal estimateCost(Order order, BigDecimal px) {
        var cb = fillService.computeCosts(order, order.getQuantity(), px);
        return cb.netCashFlow().abs();
    }

    /**
     * 部分成交后重新校准台账：买入按剩余量最坏成本下调现金预占；
     * 卖出把冻结台账更新为剩余量（实际可卖冻结已在成交时随持仓减少）。
     */
    private void recalibrateReservation(Order order, long t) {
        if (order.getSide() == Side.BUY) {
            BigDecimal held = cashReservation.getOrDefault(order.getOrderId(), BigDecimal.ZERO);
            if (order.remainingQty().signum() == 0) {
                releaseLedgerCash(order, held);
                cashReservation.remove(order.getOrderId());
                return;
            }
            BigDecimal px = reservePrice(order, t);
            if (px == null) {
                return; // 价格暂不可得，维持预占等待后续
            }
            BigDecimal need = estimateCost(order, px);
            if (need.compareTo(held) < 0) {
                releaseLedgerCash(order, held.subtract(need));
                cashReservation.put(order.getOrderId(), need);
            }
        } else {
            frozenReservation.put(order.getOrderId(), order.remainingQty());
        }
    }

    /** 终态（成交完/撤单/拒单）释放该订单全部剩余预占或冻结，台账清零，保证幂等。 */
    private void releaseReservation(Order order) {
        if (order.getSide() == Side.BUY) {
            BigDecimal held = cashReservation.remove(order.getOrderId());
            if (held != null && held.signum() > 0) {
                releaseLedgerCash(order, held);
            }
        } else {
            BigDecimal unfilled = order.remainingQty();
            frozenReservation.remove(order.getOrderId());
            if (unfilled.signum() > 0) {
                portfolio.releasePosition(order.getSymbol(), unfilled);
            }
        }
    }

    private void releaseLedgerCash(Order order, BigDecimal amount) {
        if (amount.signum() <= 0) {
            return;
        }
        BigDecimal held = portfolio.reservedCash(order.getCurrency());
        portfolio.releaseCash(order.getCurrency(), amount.min(held));
    }

    private List<Order> activeOrders() {
        return orderService.allOrders().stream()
                .filter(o -> !isTerminal(o))
                .toList();
    }

    private boolean isTerminal(Order o) {
        return switch (o.getStatus()) {
            case FILLED, CANCELLED, REJECTED -> true;
            default -> false;
        };
    }

    /** 按内部 orderId 取订单只读视图（供 REST 层）。 */
    public com.github.highcumontoa.backtestportfolioenginejava.model.OrderView
            getOrderView(String orderId) {
        return orderService.get(orderId)
                .map(com.github.highcumontoa.backtestportfolioenginejava.model.OrderView::from)
                .orElseThrow(() -> new com.github.highcumontoa.backtestportfolioenginejava.exception
                        .InvalidArgumentApiException("ORDER_NOT_FOUND", "unknown orderId " + orderId));
    }

    /** 按幂等键 clientOrderId 取订单只读视图。 */
    public com.github.highcumontoa.backtestportfolioenginejava.model.OrderView
            findOrderView(String clientOrderId) {
        return orderService.getByClientId(clientOrderId)
                .map(com.github.highcumontoa.backtestportfolioenginejava.model.OrderView::from)
                .orElseThrow(() -> new com.github.highcumontoa.backtestportfolioenginejava.exception
                        .InvalidArgumentApiException("ORDER_NOT_FOUND",
                        "unknown clientOrderId " + clientOrderId));
    }
}
