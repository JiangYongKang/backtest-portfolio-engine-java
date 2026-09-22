package com.github.highcumontoa.backtestportfolioenginejava.service;

import com.github.highcumontoa.backtestportfolioenginejava.exception.InvalidArgumentApiException;
import com.github.highcumontoa.backtestportfolioenginejava.model.MetricStatus;
import com.github.highcumontoa.backtestportfolioenginejava.model.PerformanceMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 绩效与风险指标（输入为按时间升序的净值/组合总价值序列）。
 *
 * <p>明确结论约定（绝不返回 NaN/Inf，也不静默为 0）：
 * <ul>
 *   <li>少于 2 个净值点：{@link MetricStatus#INSUFFICIENT_SAMPLES}，所有 value 为 null。</li>
 *   <li>期间收益全为 0（零波动）：{@link MetricStatus#ZERO_VOLATILITY}，累计收益与最大回撤给数值，
 *       波动率/VaR/ES 为 null。</li>
 *   <li>正常：{@link MetricStatus#OK}，波动率按 252 个交易日年化。</li>
 * </ul>
 * 全部计算用 {@link MathContext#DECIMAL128}，最终结果保留 8 位 HALF_UP。
 */
@Service
public class PerformanceService {

    private static final Logger log = LoggerFactory.getLogger(PerformanceService.class);
    private static final int SCALE = 8;
    private static final BigDecimal TRADING_DAYS = new BigDecimal("252");
    private static final BigDecimal VAR_CONF = new BigDecimal("0.05");

    public PerformanceMetrics compute(List<BigDecimal> navSeries) {
        if (navSeries == null) {
            throw new InvalidArgumentApiException("NAV_REQUIRED", "nav series required");
        }
        List<BigDecimal> nav = navSeries.stream()
                .filter(v -> v != null && v.signum() > 0)
                .toList();
        if (nav.size() < 2) {
            log.warn("metrics insufficient samples size={}", navSeries.size());
            return new PerformanceMetrics(MetricStatus.INSUFFICIENT_SAMPLES,
                    null, null, null, null, null,
                    "need at least 2 positive nav points, got " + navSeries.size());
        }

        List<BigDecimal> returns = new ArrayList<>(nav.size() - 1);
        for (int i = 1; i < nav.size(); i++) {
            BigDecimal r = nav.get(i).subtract(nav.get(i - 1))
                    .divide(nav.get(i - 1), MathContext.DECIMAL128);
            returns.add(r);
        }

        BigDecimal cumulativeReturn = nav.get(nav.size() - 1)
                .divide(nav.get(0), MathContext.DECIMAL128)
                .subtract(BigDecimal.ONE);

        BigDecimal maxDrawdown = maxDrawdown(nav);

        BigDecimal variance = variance(returns);
        if (variance.signum() == 0) {
            log.info("metrics zero volatility over {} points; cumulativeReturn={} maxDD={}",
                    nav.size(), cumulativeReturn, maxDrawdown);
            return new PerformanceMetrics(MetricStatus.ZERO_VOLATILITY,
                    scaled(cumulativeReturn), null, scaled(maxDrawdown), null, null,
                    "all period returns are zero; volatility/VaR/ES undefined");
        }

        BigDecimal annVol = variance.sqrt(MathContext.DECIMAL128)
                .multiply(TRADING_DAYS.sqrt(MathContext.DECIMAL128));

        List<BigDecimal> sorted = returns.stream().sorted().toList();
        BigDecimal var95 = percentileLoss(sorted, VAR_CONF);
        BigDecimal es95 = expectedShortfall(sorted, VAR_CONF);

        log.info("metrics OK points={} cumRet={} annVol={} maxDD={} var95={} es95={}",
                nav.size(), cumulativeReturn, annVol, maxDrawdown, var95, es95);
        return new PerformanceMetrics(MetricStatus.OK,
                scaled(cumulativeReturn), scaled(annVol), scaled(maxDrawdown),
                scaled(var95), scaled(es95), "ok");
    }

    private BigDecimal variance(List<BigDecimal> xs) {
        if (xs.size() < 2) {
            return BigDecimal.ZERO;
        }
        BigDecimal mean = xs.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(xs.size()), MathContext.DECIMAL128);
        BigDecimal sumSq = BigDecimal.ZERO;
        for (BigDecimal x : xs) {
            BigDecimal d = x.subtract(mean);
            sumSq = sumSq.add(d.multiply(d));
        }
        return sumSq.divide(BigDecimal.valueOf(xs.size() - 1L), MathContext.DECIMAL128);
    }

    private BigDecimal maxDrawdown(List<BigDecimal> nav) {
        BigDecimal peak = nav.get(0);
        BigDecimal maxDd = BigDecimal.ZERO;
        for (BigDecimal v : nav) {
            if (v.compareTo(peak) > 0) {
                peak = v;
            }
            BigDecimal dd = peak.subtract(v).divide(peak, MathContext.DECIMAL128);
            if (dd.compareTo(maxDd) > 0) {
                maxDd = dd;
            }
        }
        return maxDd;
    }

    /**
     * 历史 5% 分位损失（取升序收益的经验分位，返回负值表示亏损）。
     * 采用最近秩（ceil(alpha*n)）定位，保证在极端小样本下也有确定结论。
     */
    private BigDecimal percentileLoss(List<BigDecimal> sortedAsc, BigDecimal alpha) {
        int n = sortedAsc.size();
        BigDecimal rankDec = alpha.multiply(BigDecimal.valueOf(n));
        int rank = rankDec.setScale(0, RoundingMode.CEILING).intValueExact();
        rank = Math.max(1, Math.min(n, rank));
        return sortedAsc.get(rank - 1);
    }

    /** 尾部条件期望：最差 5% 收益的均值（含分位定位点），返回负值。 */
    private BigDecimal expectedShortfall(List<BigDecimal> sortedAsc, BigDecimal alpha) {
        int n = sortedAsc.size();
        BigDecimal rankDec = alpha.multiply(BigDecimal.valueOf(n));
        int k = rankDec.setScale(0, RoundingMode.CEILING).intValueExact();
        k = Math.max(1, Math.min(n, k));
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < k; i++) {
            sum = sum.add(sortedAsc.get(i));
        }
        return sum.divide(BigDecimal.valueOf(k), MathContext.DECIMAL128);
    }

    private static BigDecimal scaled(BigDecimal v) {
        return v.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
