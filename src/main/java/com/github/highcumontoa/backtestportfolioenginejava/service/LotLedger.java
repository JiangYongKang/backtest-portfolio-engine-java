package com.github.highcumontoa.backtestportfolioenginejava.service;

import com.github.highcumontoa.backtestportfolioenginejava.exception.InvalidArgumentApiException;
import com.github.highcumontoa.backtestportfolioenginejava.model.lot.Lot;
import com.github.highcumontoa.backtestportfolioenginejava.model.lot.LotMatch;
import com.github.highcumontoa.backtestportfolioenginejava.model.lot.LotView;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 批次台账：按标的维护 FIFO 批次队列与剩余成本汇总。
 *
 * <p>FIFO 口径：队列按建批（买入成交）先后排列，卖出从队首依次消耗；
 * 耗尽的批次出队，其成本进入卖出成交的已实现盈亏。
 *
 * <p>金额守恒：
 * <ul>
 *   <li>开批成本 = 成交额 + 买入佣金 + 买入税；</li>
 *   <li>批次内整批清空时剩余成本全部带走，部分消耗按单位成本（8 位）折算到 moneyScale，
 *       因此所有批次被卖完时累计消耗成本恰等于建批成本之和，无尾差；</li>
 *   <li>卖出毛收入按各批次消耗量拆分，尾差归最后一条明细，明细合计恰为成交额。</li>
 * </ul>
 *
 * <p>非线程安全：调用方（{@link PortfolioService}）必须在逐标的持仓锁内调用，
 * 从而保证“现金 + Position + 批次”三者原子更新。
 */
public final class LotLedger {

    private final Map<String, Deque<Lot>> lots = new ConcurrentHashMap<>();

    private Deque<Lot> queue(String symbol) {
        return lots.computeIfAbsent(symbol, s -> new ArrayDeque<>());
    }

    /** 开批：一笔买入成交新增一个批次，成本 = 成交额 + 买入佣金 + 买入税。 */
    public Lot openLot(String lotId, String symbol, String execId, String orderId, long eventTime,
                       BigDecimal quantity, BigDecimal fillPrice, BigDecimal commission,
                       BigDecimal tax, int moneyScale) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new InvalidArgumentApiException("INVALID_LOT_QTY", "lot quantity must be positive");
        }
        BigDecimal fee = (commission == null ? BigDecimal.ZERO : commission)
                .add(tax == null ? BigDecimal.ZERO : tax);
        BigDecimal cost = fillPrice.multiply(quantity)
                .add(fee)
                .setScale(moneyScale, RoundingMode.HALF_UP);
        Lot lot = new Lot(lotId, symbol, execId, orderId, eventTime,
                quantity, fillPrice, cost, commission, tax);
        queue(symbol).addLast(lot);
        return lot;
    }

    /**
     * FIFO 卖出消耗：按建批先后依次扣减批次。
     * 数量超过总剩余时抛 {@code LOT_OVERSELL}，防止超卖产生负批次。
     * 卖出毛收入按本次成交价在各批次消耗量间拆分（尾差归最后一条明细）。
     */
    public SellConsumption consumeFifo(String symbol, BigDecimal quantity, BigDecimal sellPrice,
                                       int moneyScale) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new InvalidArgumentApiException("INVALID_SELL_QTY", "sell quantity must be positive");
        }
        if (sellPrice == null || sellPrice.signum() <= 0) {
            throw new InvalidArgumentApiException("INVALID_SELL_PRICE", "sell price must be positive");
        }
        if (quantity.compareTo(remainingQuantity(symbol)) > 0) {
            throw new InvalidArgumentApiException("LOT_OVERSELL",
                    "sell " + quantity + " exceeds open-lot quantity "
                            + remainingQuantity(symbol) + " of " + symbol);
        }
        BigDecimal grossTotal = sellPrice.multiply(quantity)
                .setScale(moneyScale, RoundingMode.HALF_UP);
        BigDecimal need = quantity;
        BigDecimal costBasis = BigDecimal.ZERO.setScale(moneyScale, RoundingMode.HALF_UP);
        BigDecimal proceedsAllocated = BigDecimal.ZERO.setScale(moneyScale, RoundingMode.HALF_UP);
        List<LotMatch> matches = new ArrayList<>();
        Deque<Lot> q = queue(symbol);

        while (need.signum() > 0) {
            Lot lot = q.peekFirst();
            if (lot == null || !lot.isOpen()) {
                throw new InvalidArgumentApiException("LOT_QUEUE_EXHAUSTED",
                        "fifo queue exhausted while selling " + symbol);
            }
            BigDecimal take = need.min(lot.getRemainingQty());
            BigDecimal takenCost = lot.consume(take, moneyScale);
            costBasis = costBasis.add(takenCost);

            boolean lastMatch = take.compareTo(need) == 0;
            BigDecimal proceeds;
            if (lastMatch) {
                // 尾差归最后一条，保证明细毛收入合计恰为 grossTotal。
                proceeds = grossTotal.subtract(proceedsAllocated);
            } else {
                proceeds = sellPrice.multiply(take).setScale(moneyScale, RoundingMode.HALF_UP);
                proceedsAllocated = proceedsAllocated.add(proceeds);
            }
            matches.add(new LotMatch(lot.getLotId(), take, takenCost, proceeds));

            need = need.subtract(take);
            if (!lot.isOpen()) {
                q.pollFirst();
            }
        }
        return new SellConsumption(costBasis, List.copyOf(matches));
    }

    /** 拆股/合股：调整该标的所有未售完批次的数量，批次剩余成本不变。 */
    public void applyRatio(String symbol, BigDecimal ratio) {
        Deque<Lot> q = lots.get(symbol);
        if (q == null) {
            return;
        }
        for (Lot lot : q) {
            lot.applyRatio(ratio);
        }
    }

    public BigDecimal remainingQuantity(String symbol) {
        Deque<Lot> q = lots.get(symbol);
        if (q == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (Lot lot : q) {
            sum = sum.add(lot.getRemainingQty());
        }
        return sum;
    }

    /** 该标的所有未售完批次的剩余成本合计。 */
    public BigDecimal remainingCost(String symbol) {
        Deque<Lot> q = lots.get(symbol);
        if (q == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (Lot lot : q) {
            sum = sum.add(lot.getRemainingCost());
        }
        return sum;
    }

    /** 未售完批次（FIFO 顺序）的只读快照。 */
    public List<LotView> openLots(String symbol) {
        Deque<Lot> q = lots.get(symbol);
        if (q == null) {
            return List.of();
        }
        List<LotView> views = new ArrayList<>(q.size());
        for (Lot lot : q) {
            views.add(LotView.of(lot));
        }
        return List.copyOf(views);
    }

    /** 全部标的的未售完批次快照（按 symbol 字典序、标的内 FIFO 顺序）。 */
    public Map<String, List<LotView>> allOpenLots() {
        Map<String, List<LotView>> out = new LinkedHashMap<>();
        lots.keySet().stream().sorted().forEach(symbol -> {
            List<LotView> open = openLots(symbol);
            if (!open.isEmpty()) {
                out.put(symbol, open);
            }
        });
        return Map.copyOf(out);
    }

    /** 一次 FIFO 卖出消耗的结果。 */
    public record SellConsumption(BigDecimal costBasis, List<LotMatch> matches) {
    }
}
