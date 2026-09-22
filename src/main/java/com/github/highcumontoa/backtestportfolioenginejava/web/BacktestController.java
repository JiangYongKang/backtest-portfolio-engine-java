package com.github.highcumontoa.backtestportfolioenginejava.web;

import com.github.highcumontoa.backtestportfolioenginejava.domain.FxRate;
import com.github.highcumontoa.backtestportfolioenginejava.domain.MarketTick;
import com.github.highcumontoa.backtestportfolioenginejava.domain.Order;
import com.github.highcumontoa.backtestportfolioenginejava.domain.OrderType;
import com.github.highcumontoa.backtestportfolioenginejava.domain.Side;
import com.github.highcumontoa.backtestportfolioenginejava.error.InvalidParameterException;
import com.github.highcumontoa.backtestportfolioenginejava.error.StateConflictException;
import com.github.highcumontoa.backtestportfolioenginejava.matching.CostCalculator;
import com.github.highcumontoa.backtestportfolioenginejava.market.FxRateService;
import com.github.highcumontoa.backtestportfolioenginejava.market.MarketDataService;
import com.github.highcumontoa.backtestportfolioenginejava.order.OrderService;
import com.github.highcumontoa.backtestportfolioenginejava.portfolio.Portfolio;
import com.github.highcumontoa.backtestportfolioenginejava.portfolio.PortfolioRegistry;
import com.github.highcumontoa.backtestportfolioenginejava.portfolio.SymbolCurrencyRegistry;
import com.github.highcumontoa.backtestportfolioenginejava.portfolio.ValuationService;
import com.github.highcumontoa.backtestportfolioenginejava.web.dto.OrderView;
import com.github.highcumontoa.backtestportfolioenginejava.web.dto.SubmitOrderRequest;
import com.github.highcumontoa.backtestportfolioenginejava.web.dto.ValuationView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * 本地回测/估值 REST 入口。为保持控制器纤薄，仅做参数转译并委托领域服务；
 * 所有错误由 GlobalExceptionHandler 按类别统一转译。
 */
@RestController
@RequestMapping("/api")
public class BacktestController {

    private final PortfolioRegistry registry;
    private final OrderService orderService;
    private final MarketDataService marketData;
    private final FxRateService fxRateService;
    private final ValuationService valuationService;
    private final CostCalculator costCalculator;
    private final SymbolCurrencyRegistry symbolCcy;
    private final Clock clock;

    public BacktestController(PortfolioRegistry registry, OrderService orderService,
                              MarketDataService marketData, FxRateService fxRateService,
                              ValuationService valuationService,
                              CostCalculator costCalculator,
                              SymbolCurrencyRegistry symbolCcy, Clock clock) {
        this.registry = registry;
        this.orderService = orderService;
        this.marketData = marketData;
        this.fxRateService = fxRateService;
        this.valuationService = valuationService;
        this.costCalculator = costCalculator;
        this.symbolCcy = symbolCcy;
        this.clock = clock;
    }

    @PostMapping("/accounts")
    public Map<String, String> createAccount(@RequestParam String accountId,
                                             @RequestParam(defaultValue = "USD") String baseCcy,
                                             @RequestParam(defaultValue = "1000000") BigDecimal initialCash) {
        registry.createAccount(accountId, baseCcy, initialCash);
        return Map.of("accountId", accountId, "baseCcy", baseCcy);
    }

    @PostMapping("/symbols/{symbol}/currency")
    public Map<String, String> registerCurrency(@PathVariable String symbol,
                                                @RequestParam String ccy) {
        symbolCcy.register(symbol, ccy);
        return Map.of("symbol", symbol, "ccy", ccy);
    }

    @PostMapping("/market/{symbol}")
    public Map<String, Object> ingestTick(@PathVariable String symbol,
                                          @RequestParam BigDecimal price,
                                          @RequestParam String at,
                                          @RequestParam(defaultValue = "false") boolean suspended) {
        MarketTick tick = suspended
                ? MarketTick.suspended(symbol, Instant.parse(at))
                : MarketTick.tradable(symbol, price, Instant.parse(at));
        boolean accepted = marketData.ingest(tick);
        int matched = 0;
        // 与回测引擎一致：可交易 tick 推进后，确定性地撮合所有账户该标的的活动订单。
        if (accepted && !suspended) {
            for (String accountId : registry.accountIds()) {
                for (Order order : orderService.activeOrdersFor(accountId, symbol)) {
                    if (orderService.matchRemaining(order, tick.eventTime()).isPresent()) {
                        matched++;
                    }
                }
            }
        }
        return Map.of("accepted", accepted, "symbol", symbol, "at", at, "ordersMatched", matched);
    }

    @PostMapping("/fx")
    public Map<String, String> ingestFx(@RequestParam String from, @RequestParam String to,
                                        @RequestParam BigDecimal rate, @RequestParam String at) {
        fxRateService.ingest(new FxRate(from, to, rate, Instant.parse(at)));
        return Map.of("pair", from + "->" + to, "at", at);
    }

    @PostMapping("/orders")
    public OrderView submit(@RequestBody SubmitOrderRequest req) {
        Side side = parseSide(req.side());
        OrderType type = parseType(req.type());
        Portfolio portfolio = registry.require(req.accountId());
        Instant now = Instant.now(clock);

        BigDecimal estimate = null;
        if (side == Side.BUY) {
            BigDecimal refPrice;
            if (type == OrderType.MARKET) {
                refPrice = marketData.tradableAt(req.symbol(), now)
                        .map(t -> costCalculator.expectedFillPrice(Side.BUY, t.price(), null, true))
                        .orElseThrow(() -> new InvalidParameterException(
                                "no reference price for market buy " + req.symbol()));
            } else {
                refPrice = req.limitPrice();
            }
            estimate = costCalculator.buyReserveUpperBound(refPrice, req.quantity());
        }
        OrderService.SubmitResult result = orderService.submit(req.accountId(), req.symbol(),
                side, type, req.quantity(), req.limitPrice(), estimate, now,
                req.idempotencyKey());
        return toView(result.order());
    }

    @PostMapping("/orders/{orderId}/cancel")
    public OrderView cancel(@PathVariable String orderId) {
        return toView(orderService.cancel(orderId));
    }

    @GetMapping("/orders/{orderId}")
    public OrderView get(@PathVariable String orderId) {
        return orderService.get(orderId).map(BacktestController::toView)
                .orElseThrow(() -> new InvalidParameterException("unknown order: " + orderId));
    }

    @GetMapping("/accounts/{accountId}/valuation")
    public ValuationView valuation(@PathVariable String accountId,
                                   @RequestParam Optional<String> at) {
        Instant time = at.map(Instant::parse).orElseGet(() -> Instant.now(clock));
        return ValuationView.from(valuationService.value(registry.require(accountId), time));
    }

    private static Side parseSide(String s) {
        try {
            return Side.valueOf(s.toUpperCase());
        } catch (Exception e) {
            throw new InvalidParameterException("side must be BUY or SELL: " + s);
        }
    }

    private static OrderType parseType(String s) {
        try {
            return OrderType.valueOf(s.toUpperCase());
        } catch (Exception e) {
            throw new InvalidParameterException("type must be MARKET or LIMIT: " + s);
        }
    }

    private static OrderView toView(Order o) {
        return new OrderView(o.getOrderId(), o.getIdempotencyKey(), o.getAccountId(),
                o.getSymbol(), o.getSide().name(), o.getType().name(),
                o.getTotalQuantity(), o.getFilledQuantity(), o.remainingQuantity(),
                o.getAvgFillPrice(), o.getLimitPrice(), o.getStatus().name(),
                o.getRejectReason(), o.getSubmitTime(), o.getLastFillTime());
    }
}
