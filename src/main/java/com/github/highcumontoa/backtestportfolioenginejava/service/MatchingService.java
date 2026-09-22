package com.github.highcumontoa.backtestportfolioenginejava.service;

import com.github.highcumontoa.backtestportfolioenginejava.config.CostConfig;
import com.github.highcumontoa.backtestportfolioenginejava.model.Order;
import com.github.highcumontoa.backtestportfolioenginejava.model.OrderType;
import com.github.highcumontoa.backtestportfolioenginejava.model.Quote;
import com.github.highcumontoa.backtestportfolioenginejava.model.Side;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 成交撮合（本地模拟，单标的即时撮合，允许部分成交）。
 *
 * <p>参考价（未含滑点）：
 * <ul>
 *   <li>买入取 ask，卖出取 bid；对手价缺失时回退 last；两者皆无则不可成交。</li>
 *   <li>停牌（suspended=true）一律不可成交。</li>
 *   <li>限价单需价格穿越限价：买入参考价 &lt;= limitPrice，卖出参考价 &gt;= limitPrice。</li>
 * </ul>
 * 默认模型为“流动性足够即全部成交”；部分成交通过
 * {@link #matchedQuantity(Order, Quote, BigDecimal)} 的 availableQty 参数由回测层按批量约束注入。
 */
@Service
public class MatchingService {

    private static final Logger log = LoggerFactory.getLogger(MatchingService.class);

    private final CostConfig config;

    @Autowired
    public MatchingService() {
        this(CostConfig.DEFAULT);
    }

    public MatchingService(CostConfig config) {
        this.config = config;
    }

    public CostConfig getConfig() {
        return config;
    }

    /** 是否可成交（含停牌、价格缺失、限价穿越判定）。 */
    public boolean canMatch(Order order, Quote quote) {
        return referencePrice(order, quote) != null;
    }

    /**
     * 撮合参考价；返回 null 表示不可成交，调用方必须据此给出“未成交/停牌/无价”结论，
     * 不得回退到 0 或旧价。
     */
    public BigDecimal referencePrice(Order order, Quote quote) {
        if (order == null || quote == null) {
            return null;
        }
        if (quote.suspended()) {
            log.debug("no match: suspended orderId={} symbol={} t={}",
                    order.getOrderId(), order.getSymbol(), quote.eventTime());
            return null;
        }
        BigDecimal ref = order.getSide() == Side.BUY ? quote.ask() : quote.bid();
        if (ref == null || ref.signum() <= 0) {
            ref = quote.last();
        }
        if (ref == null || ref.signum() <= 0) {
            log.debug("no match: no usable price orderId={} symbol={} t={}",
                    order.getOrderId(), order.getSymbol(), quote.eventTime());
            return null;
        }
        if (order.getType() == OrderType.LIMIT) {
            BigDecimal limit = order.getLimitPrice();
            boolean crosses = order.getSide() == Side.BUY
                    ? ref.compareTo(limit) <= 0
                    : ref.compareTo(limit) >= 0;
            if (!crosses) {
                log.debug("no match: limit not crossed orderId={} ref={} limit={} side={}",
                        order.getOrderId(), ref, limit, order.getSide());
                return null;
            }
        }
        return ref;
    }

    /** 流动性足够时按订单剩余数量成交。 */
    public BigDecimal matchedQuantity(Order order, Quote quote) {
        return matchedQuantity(order, quote, order.remainingQty());
    }

    /**
     * 在给定可见量 availableQty 下计算本次撮合量（支持部分成交）。
     * 可成交量取 min(剩余量, 可见量)，为 0 或不可成交返回 ZERO。
     */
    public BigDecimal matchedQuantity(Order order, Quote quote, BigDecimal availableQty) {
        if (!canMatch(order, quote)) {
            return BigDecimal.ZERO;
        }
        if (availableQty == null || availableQty.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal qty = order.remainingQty().min(availableQty);
        return qty.signum() <= 0 ? BigDecimal.ZERO : qty;
    }
}
