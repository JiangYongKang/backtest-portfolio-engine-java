package com.github.highcumontoa.backtestportfolioenginejava.performance;

import com.github.highcumontoa.backtestportfolioenginejava.domain.MetricResult;
import com.github.highcumontoa.backtestportfolioenginejava.domain.MetricStatus;
import com.github.highcumontoa.backtestportfolioenginejava.domain.PerformanceReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 绩效与风险指标计算（确定性、无 NaN/Inf）。
 *
 * 输入：按事件时间采样的净值序列（time, equity）。同时间点取最后一次快照，按时间升序。
 * 指标：
 * - 累计收益：last/first - 1，至少 2 点（0 起步资金时结论为 UNDEFINED）；
 * - 年化波动率：逐期简单收益率的样本标准差 × sqrt(年化周期/平均间隔)，
 *   &lt;2 个收益率 INSUFFICIENT_SAMPLES；零波动返回 0（OK）；
 * - 最大回撤：min(equity/peak - 1)，至少 2 点；单调上涨时为 0（OK）；
 * - 历史 VaR / ES（尾部风险，95%）：至少 20 个收益率样本，否则 INSUFFICIENT_SAMPLES；
 *   尾部恰好无亏损时结论为 0（OK），不返回 NaN/Inf。
 */
@Component
public class PerformanceCalculator {

    private static final Logger log = LoggerFactory.getLogger(PerformanceCalculator.class);

    public static final int VAR_MIN_SAMPLES = 20;
    public static final BigDecimal VAR_LEVEL = new BigDecimal("0.05");
    private static final int OUT_SCALE = 8;

    private record Sample(Instant time, BigDecimal equity) {
    }

    public PerformanceReport compute(List<Instant> times, List<BigDecimal> equities,
                                     Instant from, Instant to) {
        if (times.size() != equities.size()) {
            throw new IllegalArgumentException("times and equities size mismatch");
        }
        TreeMap<Instant, BigDecimal> byTime = new TreeMap<>();
        for (int i = 0; i < times.size(); i++) {
            byTime.put(times.get(i), equities.get(i)); // 同刻取最后快照
        }
        List<Sample> samples = new ArrayList<>();
        for (Map.Entry<Instant, BigDecimal> e : byTime.entrySet()) {
            samples.add(new Sample(e.getKey(), e.getValue()));
        }
        samples.sort(Comparator.comparing(Sample::time));

        List<MetricResult> metrics = new ArrayList<>();
        metrics.add(cumulativeReturn(samples));
        metrics.add(annualizedVolatility(samples));
        metrics.add(maxDrawdown(samples));
        metrics.add(var95(samples));
        metrics.add(es95(samples));

        Instant f = from != null ? from
                : samples.isEmpty() ? null : samples.get(0).time();
        Instant t = to != null ? to
                : samples.isEmpty() ? null : samples.get(samples.size() - 1).time();
        log.info("performance report samples={} metrics={}", samples.size(),
                metrics.stream().map(m -> m.name() + "=" + m.value() + "(" + m.status() + ")").toList());
        return new PerformanceReport(f, t, samples.size(), metrics);
    }

    private MetricResult cumulativeReturn(List<Sample> s) {
        if (s.size() < 2) {
            return MetricResult.nonFinite("cumulativeReturn", MetricStatus.INSUFFICIENT_SAMPLES);
        }
        BigDecimal first = s.get(0).equity();
        BigDecimal last = s.get(s.size() - 1).equity();
        if (first.signum() <= 0) {
            return MetricResult.nonFinite("cumulativeReturn", MetricStatus.UNDEFINED);
        }
        BigDecimal r = last.divide(first, 12, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE).setScale(OUT_SCALE, RoundingMode.HALF_UP);
        return MetricResult.ok("cumulativeReturn", r);
    }

    private List<Double> periodReturns(List<Sample> s) {
        List<Double> rets = new ArrayList<>();
        for (int i = 1; i < s.size(); i++) {
            BigDecimal prev = s.get(i - 1).equity();
            if (prev.signum() == 0) {
                continue;
            }
            double r = s.get(i).equity().doubleValue() / prev.doubleValue() - 1.0;
            if (Double.isFinite(r)) {
                rets.add(r);
            }
        }
        return rets;
    }

    private MetricResult annualizedVolatility(List<Sample> s) {
        if (s.size() < 2) {
            return MetricResult.nonFinite("annualizedVolatility", MetricStatus.INSUFFICIENT_SAMPLES);
        }
        List<Double> rets = periodReturns(s);
        if (rets.isEmpty()) {
            return MetricResult.nonFinite("annualizedVolatility", MetricStatus.INSUFFICIENT_SAMPLES);
        }
        double mean = rets.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = rets.stream()
                .mapToDouble(r -> (r - mean) * (r - mean))
                .sum() / (rets.size() - 1);
        double sd = Math.sqrt(variance);
        if (sd == 0.0) {
            return MetricResult.ok("annualizedVolatility",
                    BigDecimal.ZERO.setScale(OUT_SCALE, RoundingMode.HALF_UP));
        }
        long avgSeconds = averagePeriodSeconds(s);
        double periodsPerYear = avgSeconds <= 0 ? 1.0
                : Duration.ofDays(365).toSeconds() / (double) avgSeconds;
        double ann = sd * Math.sqrt(periodsPerYear);
        if (!Double.isFinite(ann)) {
            return MetricResult.nonFinite("annualizedVolatility", MetricStatus.UNDEFINED);
        }
        return MetricResult.ok("annualizedVolatility", bd(ann));
    }

    private long averagePeriodSeconds(List<Sample> s) {
        if (s.size() < 2) {
            return 0;
        }
        long total = Duration.between(s.get(0).time(), s.get(s.size() - 1).time()).toSeconds();
        long n = s.size() - 1;
        return n == 0 ? 0 : total / n;
    }

    private MetricResult maxDrawdown(List<Sample> s) {
        if (s.size() < 2) {
            return MetricResult.nonFinite("maxDrawdown", MetricStatus.INSUFFICIENT_SAMPLES);
        }
        BigDecimal peak = s.get(0).equity();
        BigDecimal mdd = BigDecimal.ZERO;
        for (Sample x : s) {
            if (x.equity().compareTo(peak) > 0) {
                peak = x.equity();
            }
            if (peak.signum() > 0) {
                BigDecimal dd = x.equity().divide(peak, 12, RoundingMode.HALF_UP)
                        .subtract(BigDecimal.ONE);
                if (dd.compareTo(mdd) < 0) {
                    mdd = dd;
                }
            }
        }
        return MetricResult.ok("maxDrawdown", mdd.setScale(OUT_SCALE, RoundingMode.HALF_UP));
    }

    private MetricResult var95(List<Sample> s) {
        List<Double> rets = periodReturns(s);
        if (rets.size() < VAR_MIN_SAMPLES) {
            return MetricResult.nonFinite("var95", MetricStatus.INSUFFICIENT_SAMPLES);
        }
        List<Double> sorted = rets.stream().sorted().toList();
        int idx = (int) Math.ceil(VAR_LEVEL.doubleValue() * sorted.size()) - 1;
        idx = Math.max(0, Math.min(sorted.size() - 1, idx));
        double quantile = sorted.get(idx);
        // VaR 以损失为正：最坏分位数为负时取 -quantile；无亏损（尾部全正）时为 0。
        double var = Math.max(0.0, -quantile);
        return MetricResult.ok("var95", bd(var));
    }

    private MetricResult es95(List<Sample> s) {
        List<Double> rets = periodReturns(s);
        if (rets.size() < VAR_MIN_SAMPLES) {
            return MetricResult.nonFinite("es95", MetricStatus.INSUFFICIENT_SAMPLES);
        }
        List<Double> sorted = rets.stream().sorted().toList();
        int tailCount = Math.max(1,
                (int) Math.floor(VAR_LEVEL.doubleValue() * sorted.size()));
        double sum = 0;
        for (int i = 0; i < tailCount; i++) {
            sum += Math.max(0.0, -sorted.get(i));
        }
        return MetricResult.ok("es95", bd(sum / tailCount));
    }

    private static BigDecimal bd(double v) {
        if (!Double.isFinite(v)) {
            throw new IllegalStateException("non-finite metric escaped guard");
        }
        return BigDecimal.valueOf(v).setScale(OUT_SCALE, RoundingMode.HALF_UP);
    }
}
