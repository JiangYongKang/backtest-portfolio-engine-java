package com.github.highcumontoa.backtestportfolioenginejava.service;

import com.github.highcumontoa.backtestportfolioenginejava.config.CostConfig;
import com.github.highcumontoa.backtestportfolioenginejava.exception.InvalidArgumentApiException;
import com.github.highcumontoa.backtestportfolioenginejava.model.CostBreakdown;
import com.github.highcumontoa.backtestportfolioenginejava.model.Fill;
import com.github.highcumontoa.backtestportfolioenginejava.model.Order;
import com.github.highcumontoa.backtestportfolioenginejava.model.Side;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 成交回报与成本核算。
 *
 * <p>固定精度与舍入：价格按 {@code config.priceScale()}、金额按 {@code config.moneyScale()}，
 * 一律 {@link RoundingMode#HALF_UP}。成本逐笔拆解可追溯：
 * <ul>
 *   <li>滑点：买入成交价 = ref*(1+slip)，卖出 = ref*(1-slip)，按价格精度舍入；
 *       slippageCost = |含滑点价-参考价| * 数量。</li>
 *   <li>佣金：max(成交额*commissionRate, minCommission)，买卖都收。</li>
 *   <li>税费：成交额*taxRate，仅卖出收。</li>
 *   <li>净现金流：买 = -(成交额+佣金+税)，卖 = +(成交额-佣金-税)。</li>
 * </ul>
 * execId 幂等：{@link #consume} 对同一成交回报只入账一次。
 */
@Service
public class FillService {

    private static final Logger log = LoggerFactory.getLogger(FillService.class);

    private final CostConfig config;
    private final ConcurrentHashMap<String, Fill> consumed = new ConcurrentHashMap<>();

    @Autowired
    public FillService() {
        this(CostConfig.DEFAULT);
    }

    public FillService(CostConfig config) {
        this.config = config;
    }

    public CostConfig getConfig() {
        return config;
    }

    /** 含滑点成交价：买入上浮、卖出下浮，按价格精度 HALF_UP。 */
    public BigDecimal fillPrice(Order order, BigDecimal referencePrice) {
        if (referencePrice == null || referencePrice.signum() <= 0) {
            throw new InvalidArgumentApiException("INVALID_REF_PRICE", "reference price required");
        }
        BigDecimal factor = BigDecimal.ONE.add(
                order.getSide() == Side.BUY ? config.slippageRate() : config.slippageRate().negate());
        return referencePrice.multiply(factor).setScale(config.priceScale(), RoundingMode.HALF_UP);
    }

    /** 逐笔成本拆解。quantity 为本次成交量，referencePrice 为未含滑点参考价。 */
    public CostBreakdown computeCosts(Order order, BigDecimal quantity, BigDecimal referencePrice) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new InvalidArgumentApiException("INVALID_FILL_QTY", "quantity must be positive");
        }
        BigDecimal fillPx = fillPrice(order, referencePrice);
        BigDecimal gross = fillPx.multiply(quantity).setScale(config.moneyScale(), RoundingMode.HALF_UP);
        BigDecimal refGross = referencePrice.multiply(quantity)
                .setScale(config.moneyScale(), RoundingMode.HALF_UP);
        BigDecimal slippageCost = gross.subtract(refGross).abs();

        BigDecimal commission = gross.multiply(config.commissionRate())
                .setScale(config.moneyScale(), RoundingMode.HALF_UP);
        if (commission.compareTo(config.minCommission()) < 0) {
            commission = config.minCommission();
        }
        BigDecimal tax = order.getSide() == Side.SELL
                ? gross.multiply(config.taxRate()).setScale(config.moneyScale(), RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(config.moneyScale(), RoundingMode.HALF_UP);

        BigDecimal netCashFlow = order.getSide() == Side.BUY
                ? gross.add(commission).add(tax).negate()
                : gross.subtract(commission).subtract(tax);

        log.debug("cost orderId={} side={} qty={} ref={} fill={} gross={} slip={} comm={} tax={} net={}",
                order.getOrderId(), order.getSide(), quantity, referencePrice, fillPx,
                gross, slippageCost, commission, tax, netCashFlow);
        return new CostBreakdown(gross, slippageCost, commission, tax, netCashFlow);
    }

    /** 构造成交回报（含滑点价与逐笔佣金/税）。 */
    public Fill buildFill(String execId, Order order, BigDecimal quantity,
                          BigDecimal referencePrice, long eventTime) {
        if (execId == null || execId.isBlank()) {
            throw new InvalidArgumentApiException("EXEC_ID_REQUIRED", "execId required");
        }
        BigDecimal fillPx = fillPrice(order, referencePrice);
        CostBreakdown cb = computeCosts(order, quantity, referencePrice);
        return new Fill(execId, order.getOrderId(), order.getSymbol(), order.getSide(),
                quantity, fillPx, eventTime, cb.commission(), cb.tax());
    }

    /**
     * 消费一笔成交回报（幂等）。
     * @return true 首次消费并应入账；false 该 execId 已消费过，调用方必须跳过以避免重复扣减
     */
    public boolean consume(Fill fill) {
        if (fill == null || fill.execId() == null) {
            throw new InvalidArgumentApiException("INVALID_FILL", "fill and execId required");
        }
        Fill prev = consumed.putIfAbsent(fill.execId(), fill);
        if (prev != null) {
            log.warn("duplicate fill ignored execId={} orderId={} qty={}",
                    fill.execId(), fill.orderId(), fill.quantity());
            return false;
        }
        log.info("fill consumed execId={} orderId={} side={} qty={} price={} comm={} tax={}",
                fill.execId(), fill.orderId(), fill.side(), fill.quantity(),
                fill.price(), fill.commission(), fill.tax());
        return true;
    }

    public boolean isConsumed(String execId) {
        return consumed.containsKey(execId);
    }
}
