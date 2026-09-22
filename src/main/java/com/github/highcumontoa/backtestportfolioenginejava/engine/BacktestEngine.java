package com.github.highcumontoa.backtestportfolioenginejava.engine;

import com.github.highcumontoa.backtestportfolioenginejava.domain.BacktestEvent;
import com.github.highcumontoa.backtestportfolioenginejava.domain.Fill;
import com.github.highcumontoa.backtestportfolioenginejava.domain.MarketTick;
import com.github.highcumontoa.backtestportfolioenginejava.domain.Order;
import com.github.highcumontoa.backtestportfolioenginejava.domain.Side;
import com.github.highcumontoa.backtestportfolioenginejava.domain.ValuationResult;
import com.github.highcumontoa.backtestportfolioenginejava.matching.CostCalculator;
import com.github.highcumontoa.backtestportfolioenginejava.order.OrderService;
import com.github.highcumontoa.backtestportfolioenginejava.portfolio.CorporateActionService;
import com.github.highcumontoa.backtestportfolioenginejava.portfolio.Portfolio;
import com.github.highcumontoa.backtestportfolioenginejava.portfolio.PortfolioRegistry;
import com.github.highcumontoa.backtestportfolioenginejava.portfolio.ValuationService;
import com.github.highcumontoa.backtestportfolioenginejava.market.MarketDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 本地事件驱动回测引擎。
 *
 * 处理顺序严格按 (eventTime, priority) 排序，而非事件到达顺序；
 * 同一时刻公司行为先于成交、行情、订单指令与估值，保证除权口径一致。
 * 行情驱动：每个 MarketData 事件接入后，尝试撮合该标的所有活动订单（市价/限价），
 * 缺失或停牌行情下订单保持挂起，不会以零价/旧价成交。
 */
public class BacktestEngine {

    private static final Logger log = LoggerFactory.getLogger(BacktestEngine.class);

    private final MarketDataService marketData;
    private final OrderService orderService;
    private final CorporateActionService corporateActionService;
    private final ValuationService valuationService;
    private final CostCalculator costCalculator;
    private final PortfolioRegistry registry;

    private final List<ValuationResult> snapshots = new ArrayList<>();

    public BacktestEngine(MarketDataService marketData, OrderService orderService,
                          CorporateActionService corporateActionService,
                          ValuationService valuationService,
                          CostCalculator costCalculator,
                          PortfolioRegistry registry) {
        this.marketData = marketData;
        this.orderService = orderService;
        this.corporateActionService = corporateActionService;
        this.valuationService = valuationService;
        this.costCalculator = costCalculator;
        this.registry = registry;
    }

    public static final Comparator<BacktestEvent> EVENT_ORDER =
            Comparator.comparing(BacktestEvent::eventTime)
                    .thenComparingInt(BacktestEvent::priority);

    /**
     * 运行一批事件。事件列表可乱序传入，引擎内部按事件时间排序，结果确定可复现。
     */
    public List<ValuationResult> run(String accountId, List<BacktestEvent> events) {
        Portfolio portfolio = registry.require(accountId);
        List<BacktestEvent> sorted = new ArrayList<>(events);
        sorted.sort(EVENT_ORDER);
        log.info("backtest run account={} events={}", accountId, sorted.size());

        for (BacktestEvent e : sorted) {
            switch (e) {
                case BacktestEvent.CorporateActionEvent ca ->
                        corporateActionService.apply(ca.action(), portfolio);
                case BacktestEvent.MarketData md -> onMarketData(md.tick(), portfolio);
                case BacktestEvent.FillReport fr -> orderService.consumeFill(fr.fill());
                case BacktestEvent.SubmitOrder so -> onSubmit(so);
                case BacktestEvent.CancelOrder co -> orderService.cancel(co.orderId());
                case BacktestEvent.ValuationSnapshot vs -> {
                    ValuationResult vr = valuationService.value(portfolio, vs.at());
                    snapshots.add(vr);
                }
            }
        }
        return List.copyOf(snapshots);
    }

    private void onMarketData(MarketTick tick, Portfolio portfolio) {
        marketData.ingest(tick);
        if (tick.suspended()) {
            return; // 停牌区间不撮合
        }
        // 行情推进后撮合该标的所有活动订单（确定性：按 orderId 排序）。
        List<Order> active = orderService.activeOrdersFor(portfolio.getAccountId(),
                tick.symbol()).stream()
                .sorted(Comparator.comparing(Order::getSubmitTime)
                        .thenComparing(Order::getOrderId))
                .toList();
        for (Order order : active) {
            orderService.matchRemaining(order, tick.eventTime());
        }
    }

    private void onSubmit(BacktestEvent.SubmitOrder so) {
        BigDecimal estimate = null;
        if (so.side() == Side.BUY) {
            BigDecimal refPrice;
            if (so.type() == com.github.highcumontoa.backtestportfolioenginejava.domain.OrderType.MARKET) {
                Optional<MarketTick> t = marketData.tradableAt(so.symbol(), so.orderTime());
                if (t.isEmpty()) {
                    log.warn("market buy rejected at submit order for {} reason=no_reference_price",
                            so.symbol());
                    // 无参考价无法预留资金：以无效参数拒绝，避免按零预留。
                    throw new com.github.highcumontoa.backtestportfolioenginejava
                            .error.DataMissingException(
                            "no reference price to reserve cash for " + so.symbol());
                }
                refPrice = costCalculator.expectedFillPrice(Side.BUY, t.get().price(),
                        null, true);
            } else {
                refPrice = so.limitPrice();
            }
            estimate = costCalculator.buyReserveUpperBound(refPrice, so.quantity());
        }
        orderService.submit(so.orderId(), so.accountId(), so.symbol(), so.side(), so.type(),
                so.quantity(), so.limitPrice(), estimate, so.orderTime(),
                so.idempotencyKey());
    }

    public List<ValuationResult> snapshots() {
        return List.copyOf(snapshots);
    }

    /** 手动估值（不产生事件）。 */
    public ValuationResult valueNow(String accountId, java.time.Instant at) {
        return valuationService.value(registry.require(accountId), at);
    }

    /** 供测试/外部注入一笔成交。 */
    public Order acceptExternalFill(Fill fill) {
        return orderService.consumeFill(fill);
    }
}
