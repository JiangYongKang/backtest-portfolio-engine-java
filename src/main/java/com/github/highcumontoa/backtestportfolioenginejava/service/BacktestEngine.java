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
 *   <li>成交生成唯一 execId，经 FillService 幂等后结算到组合；每笔买入成交（含部分成交）
 *       先把本笔实际支出从本单自己的预占中划出，再把预占台账重校准为“仅覆盖剩余量的最坏成本”，
 *       已成交部分当场让出占用、立即可用于后续新单；订单全部成交自动释放尾量预占。</li>
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
            BigDecimal estCost = estimateCost(order, order.getQuantity(), px);
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
        // 买入结算前，先把这笔实际支出从本单自己的预占中划出（释放等额预留），
        // 使结算校验只面对“其他订单的预占”，避免大额单把自身预占误判成不可用。
        if (order.getSide() == Side.BUY) {
            BigDecimal gross = fill.price().multiply(fill.quantity())
                    .setScale(portfolio.getConfig().moneyScale(), java.math.RoundingMode.HALF_UP);
            BigDecimal out = gross.add(nz(fill.commission())).add(nz(fill.tax()));
            chargeBuyReservation(order, out);
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

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /**
     * 买入成交结算前，从本单预占台账划出等额预留（不超过台账余额）。
     * 划出后该笔支出与预留等额抵消，其他订单的可用资金不受影响；
     * 若实际支出超过台账（极端行情下市价单价格跳出估价），超出部分由结算按自由现金校验。
     */
    private void chargeBuyReservation(Order order, BigDecimal out) {
        BigDecimal held = cashReservation.getOrDefault(order.getOrderId(), BigDecimal.ZERO);
        BigDecimal covered = held.min(out);
        if (covered.signum() > 0) {
            releaseLedgerCash(order, covered);
            cashReservation.put(order.getOrderId(), held.subtract(covered));
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

    /**
     * 买入最坏成本（足额预占）。
     * 限价单用限价作为参考价，再按买入滑点上浮并计入佣金/税——成交价含滑点可能高于限价，
     * 预占必须覆盖这部分上浮，否则部分成交续结算时可用资金会被滑点“挤爆”。
     * 市价单用当刻可见参考价，滑点同理计入。
     *
     * <p>{@code qty} 为要覆盖的数量：下单时传整单量；部分成交后重校准必须传
     * <b>剩余量</b>（{@link Order#remainingQty()}），使预占随剩余量同步下降，
     * 已成交部分当场把占用让出来。
     */
    private BigDecimal estimateCost(Order order, BigDecimal qty, BigDecimal px) {
        var cb = fillService.computeCosts(order, qty, px);
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
            BigDecimal need = estimateCost(order, order.remainingQty(), px);
            int cmp = need.compareTo(held);
            if (cmp < 0) {
                // 剩余量最坏成本下降：释放差额。
                releaseLedgerCash(order, held.subtract(need));
                cashReservation.put(order.getOrderId(), need);
            } else if (cmp > 0) {
                // 剩余量最坏成本上升（如部分成交后按剩余重估、价格上浮）：补足差额；
                // 可用资金不足时维持原预占（后续结算再做硬校验），不改变既有订单状态。
                try {
                    portfolio.reserveCash(order.getCurrency(), need.subtract(held));
                    cashReservation.put(order.getOrderId(), need);
                } catch (com.github.highcumontoa.backtestportfolioenginejava.exception.ApiException ex) {
                    log.warn("top-up reservation failed orderId={} need={} held={}: {}",
                            order.getOrderId(), need, held, ex.getCode());
                }
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

    /** 指定标的尚未售尽的成本批次（FIFO 顺序），供按笔对账。 */
    public java.util.List<com.github.highcumontoa.backtestportfolioenginejava.model.CostLot>
            openLots(String symbol) {
        return portfolio.lotService().openLots(symbol);
    }

    /** 指定标的全部卖出消耗与已实现盈亏明细（按发生顺序）。 */
    public java.util.List<com.github.highcumontoa.backtestportfolioenginejava.model.LotRealization>
            realizations(String symbol) {
        return portfolio.lotService().realizations(symbol);
    }

    /** 指定币种累计已实现盈亏（费税已计入）。 */
    public BigDecimal realizedPnl(String currency) {
        return portfolio.lotService().realizedPnl(currency);
    }

    /** 指定币种累计现金分红收益。 */
    public BigDecimal dividendIncome(String currency) {
        return portfolio.lotService().dividendIncome(currency);
    }
}
