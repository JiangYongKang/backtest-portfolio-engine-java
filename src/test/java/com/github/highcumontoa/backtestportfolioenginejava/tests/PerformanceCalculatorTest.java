package com.github.highcumontoa.backtestportfolioenginejava.tests;

import com.github.highcumontoa.backtestportfolioenginejava.domain.MetricResult;
import com.github.highcumontoa.backtestportfolioenginejava.domain.MetricStatus;
import com.github.highcumontoa.backtestportfolioenginejava.domain.PerformanceReport;
import com.github.highcumontoa.backtestportfolioenginejava.performance.PerformanceCalculator;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 绩效指标边界：样本不足、零波动、极端行情均返回显式状态，绝无 NaN/Inf。 */
class PerformanceCalculatorTest {

    private static final Logger log = LoggerFactory.getLogger(PerformanceCalculatorTest.class);
    private final PerformanceCalculator calc = new PerformanceCalculator();
    private final Instant t0 = Instant.parse("2026-01-01T00:00:00Z");

    private PerformanceReport report(double... equities) {
        List<Instant> times = new ArrayList<>();
        List<BigDecimal> vals = new ArrayList<>();
        for (int i = 0; i < equities.length; i++) {
            times.add(t0.plusSeconds(86400L * i));
            vals.add(BigDecimal.valueOf(equities[i]));
        }
        return calc.compute(times, vals, times.get(0), times.get(times.size() - 1));
    }

    @Test
    void insufficientSamplesAreExplicit() {
        PerformanceReport r = report(100);
        for (MetricResult m : r.metrics()) {
            if (m.name().equals("cumulativeReturn") || m.name().equals("maxDrawdown")
                    || m.name().equals("annualizedVolatility")) {
                assertNull(m.value(), m.name() + " must be null not NaN");
                assertNotNull(m.status());
            }
        }
        log.info("ASSERT 1-sample report has explicit statuses, no NaN: {}",
                r.metrics().stream().map(m -> m.name() + ":" + m.status()).toList());
    }

    @Test
    void zeroVolatilityIsExplicitZeroNotNaN() {
        PerformanceReport r = report(100, 100, 100, 100, 100);
        MetricResult vol = r.metric("annualizedVolatility");
        assertEquals(MetricStatus.OK, vol.status());
        assertEquals(0, vol.value().compareTo(BigDecimal.ZERO));
        MetricResult mdd = r.metric("maxDrawdown");
        assertEquals(0, mdd.value().compareTo(BigDecimal.ZERO));
        log.info("ASSERT flat series vol=0 mdd=0 return=0");
    }

    @Test
    void varRequiresMinimumSamples() {
        PerformanceReport r = report(100, 101, 99, 102, 98);
        assertEquals(MetricStatus.INSUFFICIENT_SAMPLES, r.metric("var95").status());
        assertEquals(MetricStatus.INSUFFICIENT_SAMPLES, r.metric("es95").status());
        log.info("ASSERT <20 samples -> VaR/ES INSUFFICIENT_SAMPLES");
    }

    @Test
    void normalSeriesProducesFiniteMetrics() {
        double[] eq = new double[60];
        for (int i = 0; i < eq.length; i++) {
            // 含一次极端下跌（尾部风险）
            eq[i] = i == 40 ? 80 : 100 + Math.sin(i / 3.0) * 2;
        }
        PerformanceReport r = report(eq);
        for (MetricResult m : r.metrics()) {
            if (m.status() == MetricStatus.OK) {
                assertNotNull(m.value(), "OK metric must carry a value: " + m.name());
                assertTrue2(Double.isFinite(m.value().doubleValue()),
                        m.name() + " must be finite");
            }
        }
        // 极端下跌应被 VaR/ES 捕获（VaR>0）
        assertTrue2(r.metric("var95").value().compareTo(BigDecimal.ZERO) > 0, "VaR captures crash");
        assertTrue2(r.metric("maxDrawdown").value().compareTo(BigDecimal.ZERO) < 0, "drawdown negative");
        log.info("ASSERT crash series var95={} es95={} mdd={} return={}",
                r.metric("var95").value(), r.metric("es95").value(),
                r.metric("maxDrawdown").value(), r.metric("cumulativeReturn").value());
    }

    private static void assertTrue2(boolean cond, String msg) {
        org.junit.jupiter.api.Assertions.assertTrue(cond, msg);
    }
}
