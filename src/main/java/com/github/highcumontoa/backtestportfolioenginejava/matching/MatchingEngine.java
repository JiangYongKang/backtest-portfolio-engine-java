package com.github.highcumontoa.backtestportfolioenginejava.matching;

import com.github.highcumontoa.backtestportfolioenginejava.domain.Fill;
import com.github.highcumontoa.backtestportfolioenginejava.domain.MarketTick;
import com.github.highcumontoa.backtestportfolioenginejava.domain.Order;
import com.github.highcumontoa.backtestportfolioenginejava.domain.OrderType;
import com.github.highcumontoa.backtestportfolioenginejava.market.MarketDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 本地撮合引擎。
 *
 * 撮合模型（确定性、可复现）：
 * - 市价单：以截至成交时点的最新可交易价格叠加滑点作为成交价（买高卖低），
 *   停牌或无行情则无法成交（订单挂起等待后续 tick）；
 * - 限价单：最新价不劣于限价方可成交，成交价等于限价（限价单不加滑点）；
 * - 单次撮合可处理部分数量（由调用方给出 matchQty）；
 * - 每笔成交即时计算佣金与税费，随 Fill 逐笔留存。
 */
@Component
public class MatchingEngine {

    private static final Logger log = LoggerFactory.getLogger(MatchingEngine.class);

    private final MarketDataService marketData;
    private final CostCalculator costCalculator;

    public MatchingEngine(MarketDataService marketData, CostCalculator costCalculator) {
        this.marketData = marketData;
        this.costCalculator = costCalculator;
    }

    public record MatchResult(Fill fill, String reason) {
        public boolean matched() { return fill != null; }
        public static MatchResult no(String reason) { return new MatchResult(null, reason); }
    }

    /**
     * 尝试撮合一笔（可能是部分成交）。
     *
     * @param order    订单
     * @param matchQty 本次拟成交数量（必须为剩余量的一部分）
     * @param at       成交事件时间
     */
    public MatchResult tryMatch(Order order, BigDecimal matchQty, Instant at) {
        Optional<MarketTick> tickOpt = marketData.tradableAt(order.getSymbol(), at);
        if (tickOpt.isEmpty()) {
            log.warn("no match order={} at={} reason=missing_or_suspended", order.getOrderId(), at);
            return MatchResult.no("NO_TRADABLE_PRICE");
        }
        MarketTick tick = tickOpt.get();
        BigDecimal refPrice = tick.price();

        BigDecimal fillPrice;
        if (order.getType() == OrderType.MARKET) {
            fillPrice = costCalculator.expectedFillPrice(order.getSide(), refPrice, null, true);
        } else {
            if (!limitSatisfied(order, refPrice)) {
                log.info("no match order={} refPrice={} limit={} reason=limit_not_met",
                        order.getOrderId(), refPrice, order.getLimitPrice());
                return MatchResult.no("LIMIT_NOT_MET");
            }
            fillPrice = order.getLimitPrice();
        }

        BigDecimal commission = costCalculator.commission(fillPrice, matchQty);
        BigDecimal tax = costCalculator.tax(order.getSide(), fillPrice, matchQty);
        String fillId = "fill-" + order.getOrderId() + "-"
                + UUID.nameUUIDFromBytes((order.getOrderId() + "|"
                        + order.getFilledQuantity() + "|" + matchQty + "|" + at).getBytes());
        Fill fill = new Fill(fillId, order.getOrderId(), order.getSymbol(), order.getSide(),
                matchQty, fillPrice, commission, tax, at);
        log.info("match order={} fillId={} qty={} price={} commission={} tax={} tickTime={}",
                order.getOrderId(), fillId, matchQty, fillPrice, commission, tax, tick.eventTime());
        return new MatchResult(fill, "MATCHED");
    }

    /** 限价条件：买价不高于限价、卖价不低于限价。 */
    private boolean limitSatisfied(Order order, BigDecimal marketPrice) {
        return order.getSide().isBuy()
                ? marketPrice.compareTo(order.getLimitPrice()) <= 0
                : marketPrice.compareTo(order.getLimitPrice()) >= 0;
    }
}
