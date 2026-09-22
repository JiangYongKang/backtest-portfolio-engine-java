package com.github.highcumontoa.backtestportfolioenginejava.market;

import com.github.highcumontoa.backtestportfolioenginejava.config.EngineProperties;
import com.github.highcumontoa.backtestportfolioenginejava.domain.DataStatus;
import com.github.highcumontoa.backtestportfolioenginejava.domain.FxRate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 汇率服务。
 * 同一货币对只接受时间戳更新的报价，重复/回退报价忽略；
 * 折算时返回显式状态：缺汇率 FX_MISSING、超窗口 FX_STALE，不允许静默按 1 或沿用过期值。
 */
@Service
public class FxRateService {

    private static final Logger log = LoggerFactory.getLogger(FxRateService.class);

    public record FxView(BigDecimal rate, DataStatus status) {
    }

    private final Map<String, FxRate> rates = new ConcurrentHashMap<>();
    private final Duration staleTolerance;

    @org.springframework.beans.factory.annotation.Autowired
    public FxRateService(EngineProperties properties) {
        this.staleTolerance = properties.getFxStaleTolerance();
    }

    public FxRateService(Duration staleTolerance) {
        this.staleTolerance = toleranceSafe(staleTolerance);
    }

    private static Duration toleranceSafe(Duration d) {
        return d == null ? Duration.ofDays(1) : d;
    }

    private static String key(String from, String to) {
        return from + "->" + to;
    }

    public void ingest(FxRate rate) {
        rates.compute(key(rate.fromCcy(), rate.toCcy()), (k, cur) -> {
            if (cur == null || rate.eventTime().isAfter(cur.eventTime())) {
                log.info("ACCEPT fx {} rate={} time={}", k, rate.rate(), rate.eventTime());
                return rate;
            }
            log.warn("IGNORE fx {} time={} not newer than {} reason=stale_or_duplicate",
                    k, rate.eventTime(), cur.eventTime());
            return cur;
        });
    }

    /** 取折算到基准币种的汇率结论。同币种恒为 1 且状态 OK。 */
    public FxView rateToBase(String ccy, String baseCcy, Instant at) {
        if (ccy.equals(baseCcy)) {
            return new FxView(BigDecimal.ONE, DataStatus.OK);
        }
        FxRate direct = rates.get(key(ccy, baseCcy));
        if (direct != null && !direct.eventTime().isAfter(at)) {
            if (direct.eventTime().isBefore(at.minus(staleTolerance))) {
                log.warn("fx {}->{} at {} status=FX_STALE last={}", ccy, baseCcy, at, direct.eventTime());
                return new FxView(null, DataStatus.FX_STALE);
            }
            return new FxView(direct.rate(), DataStatus.OK);
        }
        // 反向报价兜底：baseCcy->ccy
        FxRate inverse = rates.get(key(baseCcy, ccy));
        if (inverse != null && !inverse.eventTime().isAfter(at)
                && !inverse.eventTime().isBefore(at.minus(staleTolerance))) {
            BigDecimal inv = BigDecimal.ONE.divide(inverse.rate(), 12, RoundingMode.HALF_UP);
            return new FxView(inv, DataStatus.OK);
        }
        DataStatus s = (direct != null || inverse != null)
                ? DataStatus.FX_STALE : DataStatus.FX_MISSING;
        log.warn("fx {}->{} at {} status={}", ccy, baseCcy, at, s);
        return new FxView(null, s);
    }

    public Optional<FxRate> latest(String from, String to) {
        return Optional.ofNullable(rates.get(key(from, to)));
    }
}
