package com.github.highcumontoa.backtestportfolioenginejava.tests;

import com.github.highcumontoa.backtestportfolioenginejava.model.Quote;
import com.github.highcumontoa.backtestportfolioenginejava.service.MarketDataService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** 行情：以事件时间为准，乱序/重复/回退不改变结论；缺失与停牌明确。 */
class MarketDataEventTimeTest {

    private static final Logger log = LoggerFactory.getLogger(MarketDataEventTimeTest.class);

    private Quote q(long t, String last, boolean suspended) {
        BigDecimal v = last == null ? null : new BigDecimal(last);
        return new Quote("AAA", t, v, v, v, suspended);
    }

    @Test
    void outOfOrderArrivalYieldsSameEventTimeView() {
        MarketDataService a = new MarketDataService();
        MarketDataService b = new MarketDataService();
        List<Quote> ordered = List.of(q(1, "10", false), q(2, "11", false), q(3, "12", false));
        List<Quote> shuffled = List.of(q(3, "12", false), q(1, "10", false), q(2, "11", false));

        a.ingestAll(ordered);
        b.ingestAll(shuffled);
        for (long t = 1; t <= 3; t++) {
            BigDecimal pa = a.latestAt("AAA", t).orElseThrow().last();
            BigDecimal pb = b.latestAt("AAA", t).orElseThrow().last();
            assertEquals(0, pa.compareTo(pb), "same view regardless of arrival order at t=" + t);
        }
        log.info("ordered vs shuffled arrival produce identical floor views");
    }

    @Test
    void duplicateQuoteIsIdempotent() {
        MarketDataService md = new MarketDataService();
        assertTrue(md.ingest(q(1, "10", false)));
        // 完全相同的重复快照被幂等接受但不重复计数（accept=false），当前价格不变
        assertFalse(md.ingest(q(1, "10", false)), "identical duplicate not re-applied");
        assertEquals(10, md.latestAt("AAA", 1).orElseThrow().last().intValue());
        assertEquals(1, md.watermark());
    }

    @Test
    void lateOlderQuoteIsBackfilledButNeverRollsBackPrice() {
        MarketDataService md = new MarketDataService();
        md.ingest(q(2, "20", false));
        assertTrue(md.ingest(q(1, "10", false)), "older event backfilled into history");
        // 当前（t>=2）视角仍为 20，价格不回退；但 t=1 的历史视角可正确查到 10
        assertEquals(20, md.latestAt("AAA", 5).orElseThrow().last().intValue());
        assertEquals(20, md.latestAt("AAA", 2).orElseThrow().last().intValue());
        assertEquals(10, md.latestAt("AAA", 1).orElseThrow().last().intValue());
        log.info("older quote backfilled; current view not rolled back, floor preserved");
    }

    @Test
    void conflictingSameTimestampRejected() {
        MarketDataService md = new MarketDataService();
        assertTrue(md.ingest(q(1, "10", false)));
        assertFalse(md.ingest(q(1, "99", false)), "conflict at same eventTime rejected");
        assertEquals(10, md.latestAt("AAA", 1).orElseThrow().last().intValue());
    }

    @Test
    void floorQueryHasNoLookahead() {
        MarketDataService md = new MarketDataService();
        md.ingestAll(List.of(q(10, "10", false), q(20, "20", false)));
        assertEquals(10, md.latestAt("AAA", 15).orElseThrow().last().intValue(), "must not see t=20");
        assertTrue(md.latestAt("AAA", 5).isEmpty(), "no quote before first event => missing");
        assertTrue(md.latestAt("ZZZ", 100).isEmpty(), "unknown symbol => missing");
    }

    @Test
    void suspensionAndMissingPriceAreDistinguishable() {
        MarketDataService md = new MarketDataService();
        md.ingest(new Quote("AAA", 1, null, null, null, true));
        Optional<Quote> sq = md.latestAt("AAA", 1);
        assertTrue(sq.isPresent());
        assertTrue(sq.get().suspended());
        assertNull(sq.get().last());
        assertTrue(md.isSuspended("AAA", 5));
        log.info("suspended quote flagged explicitly, last=null, no silent zero valuation");
    }

    @Test
    void watermarkAdvances() {
        MarketDataService md = new MarketDataService();
        md.ingestAll(List.of(q(5, "1", false), q(9, "2", false), q(2, "0", false)));
        assertEquals(9, md.watermark());
    }
}
