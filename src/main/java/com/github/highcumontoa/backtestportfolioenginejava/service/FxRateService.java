package com.github.highcumontoa.backtestportfolioenginejava.service;

import com.github.highcumontoa.backtestportfolioenginejava.exception.InvalidArgumentApiException;
import com.github.highcumontoa.backtestportfolioenginejava.model.FxRate;
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

/**
 * 汇率接入与查询。
 *
 * <p>与行情一致：按 "BASE/QUOTE" 货币对维护事件时间有序历史，floor 查询避免未来函数；
 * 同刻重复幂等忽略、同刻冲突拒绝。过期判定不在本类静默处理——查询返回报价时间戳，
 * 由估值层依据 {@code CostConfig.fxMaxStaleMillis} 判定 FX_STALE/FX_MISSING，
 * 绝不默认按 1 或沿用上次值计价（同币种除外，恒为 1）。
 */
@Service
public class FxRateService {

    private static final Logger log = LoggerFactory.getLogger(FxRateService.class);

    /** pairKey -> (eventTime -> FxRate)。 */
    private final ConcurrentMap<String, ConcurrentSkipListMap<Long, FxRate>> history =
            new ConcurrentHashMap<>();

    private static String pairKey(String base, String quote) {
        return base.toUpperCase() + "/" + quote.toUpperCase();
    }

    public boolean ingest(FxRate rate) {
        if (rate == null || rate.baseCcy() == null || rate.quoteCcy() == null) {
            throw new InvalidArgumentApiException("INVALID_FX", "fx rate and currencies required");
        }
        if (rate.rate() == null || rate.rate().signum() <= 0) {
            throw new InvalidArgumentApiException("INVALID_FX_RATE", "fx rate must be positive");
        }
        String key = pairKey(rate.baseCcy(), rate.quoteCcy());
        ConcurrentSkipListMap<Long, FxRate> series =
                history.computeIfAbsent(key, k -> new ConcurrentSkipListMap<>());
        FxRate existing = series.get(rate.eventTime());
        if (existing != null) {
            if (existing.rate().compareTo(rate.rate()) == 0) {
                log.debug("ingest duplicate fx ignored pair={} t={}", key, rate.eventTime());
                return false;
            }
            log.warn("ingest conflicting fx at same eventTime pair={} t={} existing={} incoming={}",
                    key, rate.eventTime(), existing.rate(), rate.rate());
            return false;
        }
        FxRate prev = series.putIfAbsent(rate.eventTime(), rate);
        if (prev != null) {
            return prev.rate().compareTo(rate.rate()) == 0;
        }
        log.debug("ingest accepted fx pair={} t={} rate={}", key, rate.eventTime(), rate.rate());
        return true;
    }

    public int ingestAll(List<FxRate> rates) {
        if (rates == null) {
            return 0;
        }
        List<FxRate> sorted = rates.stream()
                .sorted(Comparator.comparingLong(FxRate::eventTime))
                .toList();
        int accepted = 0;
        for (FxRate r : sorted) {
            if (ingest(r)) {
                accepted++;
            }
        }
        return accepted;
    }

    /** 截至 asOfTime 可见的最新汇率（含报价时间戳）；同币种返回合成的 1:1 汇率。 */
    public Optional<FxRate> latestAt(String baseCcy, String quoteCcy, long asOfTime) {
        if (baseCcy.equalsIgnoreCase(quoteCcy)) {
            return Optional.of(new FxRate(baseCcy.toUpperCase(), quoteCcy.toUpperCase(),
                    asOfTime, BigDecimal.ONE));
        }
        ConcurrentSkipListMap<Long, FxRate> series = history.get(pairKey(baseCcy, quoteCcy));
        if (series == null) {
            return Optional.empty();
        }
        Map.Entry<Long, FxRate> e = series.floorEntry(asOfTime);
        return e == null ? Optional.empty() : Optional.of(e.getValue());
    }

    /** 仅取汇率数值；同币种为 1，否则取最新值（过期与否由估值层判定）。 */
    public BigDecimal rateValue(String baseCcy, String quoteCcy, long asOfTime) {
        return latestAt(baseCcy, quoteCcy, asOfTime).map(FxRate::rate).orElse(null);
    }
}
