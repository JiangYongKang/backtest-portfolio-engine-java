package com.github.highcumontoa.backtestportfolioenginejava.api;

import com.github.highcumontoa.backtestportfolioenginejava.model.OrderView;
import com.github.highcumontoa.backtestportfolioenginejava.model.OrderRequest;
import com.github.highcumontoa.backtestportfolioenginejava.model.OrderType;
import com.github.highcumontoa.backtestportfolioenginejava.model.Side;
import com.github.highcumontoa.backtestportfolioenginejava.model.ValuationResult;
import com.github.highcumontoa.backtestportfolioenginejava.service.BacktestEngine;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * REST 入口（演示/本地验证用）。事件以毫秒时间戳显式传入，回测引擎单例会话。
 * 主要价值由可直接实例化的服务与边界测试覆盖，HTTP 仅提供便捷通路。
 */
@RestController
@RequestMapping("/api")
public class BacktestController {

    /** 下单请求体。 */
    public record PlaceOrderBody(
            String clientOrderId,
            String side,
            String type,
            String symbol,
            BigDecimal quantity,
            BigDecimal limitPrice,
            String currency,
            Long eventTime
    ) { }

    private final BacktestEngine engine;

    public BacktestController(BacktestEngine engine) {
        this.engine = engine;
    }

    @PostMapping("/orders")
    public OrderView placeOrder(@RequestBody PlaceOrderBody body) {
        long t = body.eventTime() == null ? 0L : body.eventTime();
        OrderRequest req = new OrderRequest(
                body.clientOrderId(),
                Side.valueOf(body.side()),
                OrderType.valueOf(body.type()),
                body.symbol(),
                body.quantity(),
                body.limitPrice(),
                body.currency());
        engine.placeOrder(req, t);
        engine.runTo(t, body.currency());
        return engine.findOrderView(body.clientOrderId());
    }

    @PostMapping("/orders/{orderId}/cancel")
    public String cancel(@PathVariable String orderId, @RequestParam long eventTime) {
        engine.requestCancel(orderId, eventTime);
        return "accepted";
    }

    @GetMapping("/orders/{orderId}")
    public OrderView getOrder(@PathVariable String orderId) {
        return engine.getOrderView(orderId);
    }

    @GetMapping("/valuation")
    public ValuationResult valuation(@RequestParam long asOf, @RequestParam String baseCcy) {
        return engine.runTo(asOf, baseCcy);
    }

    /** 某标的未售完的 FIFO 批次（数量、剩余成本、单位成本）。 */
    @GetMapping("/positions/{symbol}/lots")
    public java.util.List<com.github.highcumontoa.backtestportfolioenginejava.model.lot.LotView>
            lots(@PathVariable String symbol) {
        return engine.openLots(symbol);
    }

    /** 全部标的未售完批次。 */
    @GetMapping("/lots")
    public java.util.Map<String,
            java.util.List<com.github.highcumontoa.backtestportfolioenginejava.model.lot.LotView>> allLots() {
        return engine.allOpenLots();
    }

    /** 逐笔卖出的已实现盈亏（FIFO 明细、佣金、税）。 */
    @GetMapping("/realized-pnl")
    public java.util.List<com.github.highcumontoa.backtestportfolioenginejava.model.lot.RealizedPnl>
            realizedPnl(@RequestParam(required = false) String symbol) {
        return symbol == null ? engine.realizedPnls() : engine.realizedPnls(symbol);
    }
}
