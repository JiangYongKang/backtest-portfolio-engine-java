package com.github.highcumontoa.backtestportfolioenginejava.market;

import com.github.highcumontoa.backtestportfolioenginejava.config.EngineProperties;
import com.github.highcumontoa.backtestportfolioenginejava.domain.DataStatus;
import com.github.highcumontoa.backtestportfolioenginejava.domain.MarketTick;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地行情接入服务。
 *
 * 关键规则：
 * 1) 只接受 eventTime &gt;= 已接受最新时间戳的 tick；更早（回退）的 tick 被丢弃并记录，
 *    保证价格不会因乱序到达而回退；
 * 2) 同一时间戳的重复 tick：完全相同则忽略（幂等），不同则以先到者为准，拒绝歧义；
 * 3) 停牌 tick 也推进时间线，停牌区间内估值结论为 SUSPENDED，不沿用旧价；
 * 4) 从未收到价格返回 MISSING_PRICE；最新价格早于估值时点超过容忍窗口返回 STALE_PRICE。
 */
@Service
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

    public record PriceView(MarketTick tick, DataStatus status) {
    }

    private final Map<String, MarketTick> latest = new ConcurrentHashMap<>();
    private final Duration staleTolerance;

    @org.springframework.beans.factory.annotation.Autowired
    public MarketDataService(EngineProperties properties) {
        this.staleTolerance = properties.getPriceStaleTolerance();
    }

    /** 测试/直配构造。 */
    public MarketDataService(Duration staleTolerance) {
        this.staleTolerance = staleTolerance;
    }

    /**
     * 接入一条 tick。返回是否被接受。重复/回退 tick 不产生任何状态变化。
     */
    public boolean ingest(MarketTick tick) {
        return latest.compute(tick.symbol(), (sym, cur) -> {
            if (cur == null) {
                log.info("ACCEPT tick {} price={} time={} suspended={} reason=first",
                        sym, tick.price(), tick.eventTime(), tick.suspended());
                return tick;
            }
            int cmp = tick.eventTime().compareTo(cur.eventTime());
            if (cmp < 0) {
                log.warn("REJECT tick {} time={} earlier than latest={} reason=backward_time",
                        sym, tick.eventTime(), cur.eventTime());
                return cur;
            }
            if (cmp == 0) {
                boolean same = java.util.Objects.equals(cur.price(), tick.price())
                        && cur.suspended() == tick.suspended();
                if (same) {
                    log.info("IGNORE tick {} time={} reason=duplicate", sym, tick.eventTime());
                } else {
                    log.warn("REJECT tick {} time={} conflicting tick kept reason=same_timestamp_conflict",
                            sym, tick.eventTime());
                }
                return cur;
            }
            log.info("ACCEPT tick {} price={} time={} suspended={} reason=advance",
                    sym, tick.price(), tick.eventTime(), tick.suspended());
            return tick;
        }) == tick;
    }

    /** 取某标的截至 at 时点的最新有效 tick（仅按已接入数据，不做新鲜度判定）。 */
    public Optional<MarketTick> latestAt(String symbol, Instant at) {
        MarketTick t = latest.get(symbol);
        if (t == null || t.eventTime().isAfter(at)) {
            return Optional.empty();
        }
        return Optional.of(t);
    }

    /**
     * 估值时点的价格结论：始终返回非 null 的 PriceView，status 显式说明是否可用。
     */
    public PriceView priceAt(String symbol, Instant at) {
        MarketTick t = latest.get(symbol);
        if (t == null || t.eventTime().isAfter(at)) {
            log.warn("valuation {} at {} status=MISSING_PRICE reason=no_tick", symbol, at);
            return new PriceView(null, DataStatus.MISSING_PRICE);
        }
        if (t.suspended()) {
            log.warn("valuation {} at {} status=SUSPENDED lastTickTime={}", symbol, at, t.eventTime());
            return new PriceView(t, DataStatus.SUSPENDED);
        }
        if (t.eventTime().isBefore(at.minus(staleTolerance))) {
            log.warn("valuation {} at {} status=STALE_PRICE lastTickTime={} tolerance={}",
                    symbol, at, t.eventTime(), staleTolerance);
            return new PriceView(t, DataStatus.STALE_PRICE);
        }
        return new PriceView(t, DataStatus.OK);
    }

    /** 用于撮合：截至 at 的最新可交易价格，停牌或无数据时返回 empty（撮合方按拒绝/挂起处理）。 */
    public Optional<MarketTick> tradableAt(String symbol, Instant at) {
        return latestAt(symbol, at).filter(t -> !t.suspended());
    }
}
