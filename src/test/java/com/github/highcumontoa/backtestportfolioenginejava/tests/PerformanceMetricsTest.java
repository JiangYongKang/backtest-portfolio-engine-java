package com.github.highcumontoa.backtestportfolioenginejava.tests;

import com.github.highcumontoa.backtestportfolioenginejava.model.MetricStatus;
import com.github.highcumontoa.backtestportfolioenginejava.model.PerformanceMetrics;
import com.github.highcumontoa.backtestportfolioenginejava.service.PerformanceService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 指标边界：样本不足、零波动、正常、极端行情下均给出明确结论而非 NaN/Inf/静默 0。 */
class PerformanceMetricsTest {

    private static final Logger log = LoggerFactory.getLogger(PerformanceMetricsTest.class);

    private final PerformanceService svc = new PerformanceService();

    private static List<BigDecimal> navs(double... xs) {
        return java.util.Arrays.stream(xs).mapToObj(BigDecimal::valueOf).toList();
    }

    @Test
    void insufficientSamplesExplicit() {
        PerformanceMetrics m0 = svc.compute(List.of());
        assertEquals(MetricStatus.INSUFFICIENT_SAMPLES, m0.status());
        assertNull(m0.cumulativeReturn());
        PerformanceMetrics m1 = svc.compute(List.of(new BigDecimal("100")));
        assertEquals(MetricStatus.INSUFFICIENT_SAMPLES, m1.status());
        log.info("insufficient samples status={} detail={}", m0.status(), m0.detail());
    }

    @Test
    void zeroVolatilityExplicitNotNaN() {
        PerformanceMetrics m = svc.compute(navs(100, 100, 100, 100));
        assertEquals(MetricStatus.ZERO_VOLATILITY, m.status());
        assertEquals(0, BigDecimal.ZERO.compareTo(m.cumulativeReturn()));
        assertEquals(0, BigDecimal.ZERO.compareTo(m.maxDrawdown()));
        assertNull(m.annualizedVolatility(), "volatility undefined, not silently zero");
        assertNull(m.historicalVaR95());
        assertNull(m.expectedShortfall95());
        for (Object o : new Object[]{m.cumulativeReturn(), m.annualizedVolatility(), m.maxDrawdown()}) {
            assertFalse(o != null && o.toString().contains("NaN"));
        }
        log.info("zero volatility status={} cumRet=0 maxDD=0 vol/VaR/ES=null", m.status());
    }

    @Test
    void normalSeriesProducesFiniteMetrics() {
        PerformanceMetrics m = svc.compute(navs(100, 101, 102, 101, 103, 105, 104, 106));
        assertEquals(MetricStatus.OK, m.status());
        assertTrue(m.annualizedVolatility().signum() > 0);
        assertTrue(m.maxDrawdown().signum() >= 0);
        assertNotNull(m.historicalVaR95());
        assertNotNull(m.expectedShortfall95());
        // 累计收益 = 106/100 - 1 = 0.06
        assertEquals(0, new BigDecimal("0.06000000").compareTo(m.cumulativeReturn()));
        // VaR/ES 以负收益表示损失，ES <= VaR（更差或相等）
        assertTrue(m.expectedShortfall95().compareTo(m.historicalVaR95()) <= 0);
        log.info("normal: cumRet={} annVol={} maxDD={} VaR95={} ES95={}",
                m.cumulativeReturn(), m.annualizedVolatility(), m.maxDrawdown(),
                m.historicalVaR95(), m.expectedShortfall95());
    }

    @Test
    void extremeCrashGivesPositiveDrawdownAndTailLoss() {
        // 两点（单一收益率样本）：方差按 n-1 自由度为 0 -> ZERO_VOLATILITY，绝不产生 Inf
        PerformanceMetrics two = svc.compute(navs(100, 20));
        assertEquals(MetricStatus.ZERO_VOLATILITY, two.status());
        assertEquals(0, new BigDecimal("0.80000000").compareTo(two.maxDrawdown()));
        assertNull(two.annualizedVolatility());

        // 多样本极端下跌：有非零波动，VaR/ES 为显著负收益
        PerformanceMetrics many = svc.compute(navs(100, 20, 21, 19, 22, 18));
        assertEquals(MetricStatus.OK, many.status());
        assertTrue(many.maxDrawdown().signum() > 0);
        assertTrue(many.historicalVaR95().signum() < 0);
        assertTrue(many.expectedShortfall95().signum() < 0);
        log.info("crash many: maxDD={} VaR95={} ES95={}",
                many.maxDrawdown(), many.historicalVaR95(), many.expectedShortfall95());
    }

    @Test
    void noNaNOrInfinityAnywhere() {
        List<List<BigDecimal>> cases = List.of(
                navs(1, 1, 1), navs(100, 50, 75), navs(10, 10.0001, 10.0002),
                navs(1, 2, 4, 8, 16));
        for (List<BigDecimal> c : cases) {
            PerformanceMetrics m = svc.compute(c);
            for (BigDecimal v : new BigDecimal[]{m.cumulativeReturn(), m.annualizedVolatility(),
                    m.maxDrawdown(), m.historicalVaR95(), m.expectedShortfall95()}) {
                if (v != null) {
                    assertFalse(v.toString().contains("NaN") || v.toString().contains("Infinity"));
                }
            }
        }
    }
}
