package com.github.highcumontoa.backtestportfolioenginejava.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 回测事件（sealed）。处理顺序严格以 eventTime 为准，而非到达顺序。
 * 同一时刻的优先级：公司行为 &gt; 成交回报 &gt; 行情 &gt; 订单指令 &gt; 估值快照，
 * 保证除权日先调整持仓再按新价成交/估值。
 */
public sealed interface BacktestEvent
        permits BacktestEvent.MarketData, BacktestEvent.FillReport,
        BacktestEvent.SubmitOrder, BacktestEvent.CancelOrder,
        BacktestEvent.CorporateActionEvent, BacktestEvent.ValuationSnapshot {

    Instant eventTime();

    /** 同刻排序优先级，值小者先处理。 */
    int priority();

    record MarketData(MarketTick tick) implements BacktestEvent {
        @Override public Instant eventTime() { return tick.eventTime(); }
        @Override public int priority() { return 20; }
    }

    record FillReport(Fill fill) implements BacktestEvent {
        @Override public Instant eventTime() { return fill.eventTime(); }
        @Override public int priority() { return 10; }
    }

    record SubmitOrder(String orderId, String idempotencyKey, String accountId,
                       String symbol, Side side, OrderType type,
                       BigDecimal quantity, BigDecimal limitPrice,
                       Instant orderTime) implements BacktestEvent {
        @Override public Instant eventTime() { return orderTime; }
        @Override public int priority() { return 30; }
    }

    record CancelOrder(String orderId, Instant cancelTime) implements BacktestEvent {
        @Override public Instant eventTime() { return cancelTime; }
        @Override public int priority() { return 30; }
    }

    record CorporateActionEvent(CorporateAction action) implements BacktestEvent {
        @Override public Instant eventTime() { return action.exDate(); }
        @Override public int priority() { return 0; }
    }

    record ValuationSnapshot(Instant at) implements BacktestEvent {
        @Override public Instant eventTime() { return at; }
        @Override public int priority() { return 40; }
    }
}
