package com.github.highcumontoa.backtestportfolioenginejava.tests;

import com.github.highcumontoa.backtestportfolioenginejava.domain.DataStatus;
import com.github.highcumontoa.backtestportfolioenginejava.domain.MarketTick;
import com.github.highcumontoa.backtestportfolioenginejava.market.MarketDataService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行情边界：乱序到达、时间回退、重复 tick、停牌区间与缺失价格的明确结论。
 */
class MarketDataServiceTest {

    private static final Logger log = LoggerFactory.getLogger(MarketDataServiceTest.class);
    private final MarketDataService svc = new MarketDataService(Duration.ofMinutes(5));

    @Test
    void outOfOrderAndBackwardTicksDoNotRegressPrice() {
        Instant t1 = Instant.parse("2026-09-01T09:00:00Z");
        Instant t2 = Instant.parse("2026-09-01T10:00:00Z");
        // 先到晚的 tick
        assertTrue(svc.ingest(MarketTick.tradable("AAA", bd("110"), t2)));
        // 更早 tick（乱序/回退）必须被拒绝，价格不回退
        assertFalse(svc.ingest(MarketTick.tradable("AAA", bd("100"), t1)));
        MarketDataService.PriceView v = svc.priceAt("AAA", t2);
        assertEquals(DataStatus.OK, v.status());
        assertEquals(0, v.tick().price().compareTo(bd("110")));
        log.info("ASSERT latest price stays 110 after backward tick -> {}", v.tick().price());
    }

    @Test
    void duplicateTickIsIdempotent() {
        Instant t = Instant.parse("2026-09-01T09:00:00Z");
        assertTrue(svc.ingest(MarketTick.tradable("AAA", bd("100"), t)));
        assertFalse(svc.ingest(MarketTick.tradable("AAA", bd("100"), t)));
        assertEquals(0, svc.priceAt("AAA", t).tick().price().compareTo(bd("100")));
    }

    @Test
    void conflictingSameTimestampRejected() {
        Instant t = Instant.parse("2026-09-01T09:00:00Z");
        svc.ingest(MarketTick.tradable("AAA", bd("100"), t));
        assertFalse(svc.ingest(MarketTick.tradable("AAA", bd("101"), t)));
        assertEquals(0, svc.priceAt("AAA", t).tick().price().compareTo(bd("100")));
    }

    @Test
    void missingPriceHasExplicitStatus() {
        MarketDataService.PriceView v = svc.priceAt("NOPE", Instant.parse("2026-09-01T09:00:00Z"));
        assertEquals(DataStatus.MISSING_PRICE, v.status());
        log.info("ASSERT missing price status={}, price kept null", v.status());
    }

    @Test
    void staleAndSuspendedAreExplicit() {
        Instant t = Instant.parse("2026-09-01T09:00:00Z");
        svc.ingest(MarketTick.tradable("AAA", bd("100"), t));
        MarketDataService.PriceView stale = svc.priceAt("AAA", t.plus(Duration.ofMinutes(6)));
        assertEquals(DataStatus.STALE_PRICE, stale.status());
        log.info("ASSERT stale window status={}, old price NOT used silently", stale.status());

        svc.ingest(MarketTick.suspended("AAA", t.plus(Duration.ofMinutes(10))));
        MarketDataService.PriceView susp = svc.priceAt("AAA", t.plus(Duration.ofMinutes(10)));
        assertEquals(DataStatus.SUSPENDED, susp.status());
        assertTrue(svc.tradableAt("AAA", t.plus(Duration.ofMinutes(10))).isEmpty());
        log.info("ASSERT suspended status={}, no tradable price", susp.status());
    }

    private static java.math.BigDecimal bd(String s) {
        return new java.math.BigDecimal(s);
    }
}
