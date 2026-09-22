package com.github.highcumontoa.backtestportfolioenginejava.service;

import com.github.highcumontoa.backtestportfolioenginejava.exception.InvalidArgumentApiException;
import com.github.highcumontoa.backtestportfolioenginejava.model.Quote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 行情接入与事件时间推进。
 *
 * <p>规则（结论只取决于事件时间，与到达顺序无关）：
 * <ul>
 *   <li>每个 symbol 维护按事件时间排序的历史：晚到的更老快照会被补录（供 floor 查询），
 *       但永不会成为“当前最新”，因此<b>不会造成价格回退或重复成交</b>。</li>
 *   <li>相同 symbol+eventTime 且关键字段一致视为重复，幂等忽略（返回 false，不报错）；
 *       内容不同视为同刻冲突，拒绝并记录（保留先到定义，结果可复现）。</li>
 *   <li>{@link #latestAt} 用 floor 语义返回 eventTime &lt;= asOfTime 的最新快照（无未来函数）；
 *       从未出现=缺失(empty)；最新可见快照 suspended=true 表示停牌区间；last=null 表示该刻无有效价。</li>
 * </ul>
 * 全局水位线 {@link #watermark} 为已接受快照的最大事件时间。
 */
@Service
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

    /** symbol -> (eventTime -> 快照)，有序历史以支持任意时点 floor 查询。 */
    private final ConcurrentMap<String, ConcurrentSkipListMap<Long, Quote>> history =
            new ConcurrentHashMap<>();
    private final AtomicLong watermark = new AtomicLong(Long.MIN_VALUE);

    /**
     * 接收一条行情（乱序到达也可，按 eventTime 落库）。
     * @return true 新快照落库；false 同刻重复/冲突被忽略（重复幂等，冲突保留先到定义）
     */
    public boolean ingest(Quote quote) {
        if (quote == null || quote.symbol() == null || quote.symbol().isBlank()) {
            throw new InvalidArgumentApiException("INVALID_QUOTE", "quote and symbol required");
        }
        final String symbol = quote.symbol();
        ConcurrentSkipListMap<Long, Quote> series =
                history.computeIfAbsent(symbol, k -> new ConcurrentSkipListMap<>());
        Quote existing = series.get(quote.eventTime());
        if (existing != null) {
            if (sameMarketData(existing, quote)) {
                log.debug("ingest duplicate quote ignored symbol={} t={}", symbol, quote.eventTime());
                return false;
            }
            log.warn("ingest conflicting quote at same eventTime symbol={} t={} existing(last={},susp={}) incoming(last={},susp={})",
                    symbol, quote.eventTime(), existing.last(), existing.suspended(),
                    quote.last(), quote.suspended());
            return false;
        }
        Quote prev = series.putIfAbsent(quote.eventTime(), quote);
        if (prev != null) {
            // 并发下同刻竞争：一致则幂等，不一致判冲突
            if (sameMarketData(prev, quote)) {
                return false;
            }
            log.warn("ingest concurrent conflicting quote symbol={} t={}", symbol, quote.eventTime());
            return false;
        }
        advanceWatermark(quote.eventTime());
        log.debug("ingest accepted quote symbol={} t={} last={} suspended={}",
                symbol, quote.eventTime(), quote.last(), quote.suspended());
        return true;
    }

    /** 批量喂入：按事件时间（再按 symbol）稳定排序后回放，使结论与到达顺序无关。 */
    public int ingestAll(List<Quote> quotes) {
        if (quotes == null) {
            return 0;
        }
        List<Quote> sorted = quotes.stream()
                .sorted(Comparator.comparingLong(Quote::eventTime)
                        .thenComparing(Quote::symbol))
                .toList();
        int accepted = 0;
        for (Quote q : sorted) {
            if (ingest(q)) {
                accepted++;
            }
        }
        return accepted;
    }

    /** 截至 asOfTime 可见的最新快照（floor 语义，无未来函数）；该 symbol 从无快照返回 empty（缺失）。 */
    public Optional<Quote> latestAt(String symbol, long asOfTime) {
        ConcurrentSkipListMap<Long, Quote> series = history.get(symbol);
        if (series == null) {
            return Optional.empty();
        }
        Map.Entry<Long, Quote> e = series.floorEntry(asOfTime);
        return e == null ? Optional.empty() : Optional.of(e.getValue());
    }

    public long watermark() {
        long w = watermark.get();
        return w == Long.MIN_VALUE ? 0L : w;
    }

    public boolean isSuspended(String symbol, long asOfTime) {
        return latestAt(symbol, asOfTime).map(Quote::suspended).orElse(false);
    }

    private boolean sameMarketData(Quote a, Quote b) {
        return eq(a.bid(), b.bid()) && eq(a.ask(), b.ask())
                && eq(a.last(), b.last()) && a.suspended() == b.suspended();
    }

    private static boolean eq(BigDecimal x, BigDecimal y) {
        if (x == null || y == null) {
            return x == null && y == null;
        }
        return x.compareTo(y) == 0;
    }

    private void advanceWatermark(long t) {
        watermark.accumulateAndGet(t, Math::max);
    }
}
