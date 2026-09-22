package com.github.highcumontoa.backtestportfolioenginejava.tests;

import com.github.highcumontoa.backtestportfolioenginejava.config.CostConfig;
import com.github.highcumontoa.backtestportfolioenginejava.model.CostBreakdown;
import com.github.highcumontoa.backtestportfolioenginejava.model.Fill;
import com.github.highcumontoa.backtestportfolioenginejava.model.Order;
import com.github.highcumontoa.backtestportfolioenginejava.model.OrderType;
import com.github.highcumontoa.backtestportfolioenginejava.model.Quote;
import com.github.highcumontoa.backtestportfolioenginejava.model.Side;
import com.github.highcumontoa.backtestportfolioenginejava.service.FillService;
import com.github.highcumontoa.backtestportfolioenginejava.service.MatchingService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** 撮合判定与逐笔成本可追溯：滑点、最低佣金、税、停牌/无价、幂等。 */
class MatchingAndCostTest {

    private static final Logger log = LoggerFactory.getLogger(MatchingAndCostTest.class);

    private final CostConfig cfg = CostConfig.DEFAULT; // slip 0.0005, comm 0.0003 min 5, tax 0.001
    private final MatchingService matching = new MatchingService(cfg);
    private final FillService fills = new FillService(cfg);

    private Order order(Side side, OrderType type, String qty, String limit) {
        return new Order("O", "C", side, type, "AAA", new BigDecimal(qty),
                limit == null ? null : new BigDecimal(limit), "USD", 1L);
    }

    private Quote quote(String bid, String ask, String last, boolean suspended) {
        return new Quote("AAA", 10L,
                bid == null ? null : new BigDecimal(bid),
                ask == null ? null : new BigDecimal(ask),
                last == null ? null : new BigDecimal(last), suspended);
    }

    @Test
    void buySlippageMovesPriceUpAndCostsTrace() {
        Order o = order(Side.BUY, OrderType.MARKET, "100", null);
        Quote q = quote("9.99", "10.00", "10.00", false);
        BigDecimal ref = matching.referencePrice(o, q);
        assertEquals(0, new BigDecimal("10.00").compareTo(ref), "buy uses ask");
        CostBreakdown cb = fills.computeCosts(o, new BigDecimal("100"), ref);
        // fill = 10*1.0005 = 10.0050 (4 dp)
        assertEquals(0, new BigDecimal("10.0050").compareTo(fills.fillPrice(o, ref)));
        // gross = 10.0050*100 = 1000.50
        assertEquals(0, new BigDecimal("1000.50").compareTo(cb.grossAmount()));
        // slippage = (10.005-10)*100 = 0.50
        assertEquals(0, new BigDecimal("0.50").compareTo(cb.slippageCost()));
        // commission = max(1000.50*0.0003=0.300, 5) = 5
        assertEquals(0, new BigDecimal("5.00").compareTo(cb.commission()));
        // buy no tax
        assertEquals(0, BigDecimal.ZERO.compareTo(cb.tax()));
        // net = -(1000.50+5) = -1005.50
        assertEquals(0, new BigDecimal("-1005.50").compareTo(cb.netCashFlow()));
        log.info("BUY cost gross={} slip={} comm={} tax={} net={}",
                cb.grossAmount(), cb.slippageCost(), cb.commission(), cb.tax(), cb.netCashFlow());
    }

    @Test
    void sellSlippageMovesPriceDownAndTaxApplied() {
        Order o = order(Side.SELL, OrderType.MARKET, "100", null);
        Quote q = quote("10.00", "10.01", "10.00", false);
        BigDecimal ref = matching.referencePrice(o, q);
        assertEquals(0, new BigDecimal("10.00").compareTo(ref), "sell uses bid");
        // fill = 10*0.9995 = 9.9950
        assertEquals(0, new BigDecimal("9.9950").compareTo(fills.fillPrice(o, ref)));
        CostBreakdown cb = fills.computeCosts(o, new BigDecimal("100"), ref);
        // gross = 999.50
        assertEquals(0, new BigDecimal("999.50").compareTo(cb.grossAmount()));
        // tax = 999.50*0.001 = 0.9995 -> 1.00 (2dp)
        assertEquals(0, new BigDecimal("1.00").compareTo(cb.tax()));
        // commission max(0.29985->0.30, 5) = 5
        assertEquals(0, new BigDecimal("5.00").compareTo(cb.commission()));
        // net = 999.50 - 5 - 1 = 993.50
        assertEquals(0, new BigDecimal("993.50").compareTo(cb.netCashFlow()));
        log.info("SELL cost gross={} comm={} tax={} net={}",
                cb.grossAmount(), cb.commission(), cb.tax(), cb.netCashFlow());
    }

    @Test
    void largeOrderUsesRateCommissionAboveMinimum() {
        Order o = order(Side.BUY, OrderType.MARKET, "10000", null);
        CostBreakdown cb = fills.computeCosts(o, new BigDecimal("10000"), new BigDecimal("100"));
        // fill=100.05, gross=100.05*10000=1,005,000.00 ; commission = 300.15 > min 5
        assertEquals(0, new BigDecimal("300.15").compareTo(cb.commission()),
                "commission should be rate based when above floor");
        log.info("rate commission={}", cb.commission());
    }

    @Test
    void limitCrossRules() {
        Quote q = quote("10", "10", "10", false);
        assertTrue(matching.canMatch(order(Side.BUY, OrderType.LIMIT, "1", "10.00"), q),
                "buy: ref<=limit matches");
        assertFalse(matching.canMatch(order(Side.BUY, OrderType.LIMIT, "1", "9.99"), q));
        assertTrue(matching.canMatch(order(Side.SELL, OrderType.LIMIT, "1", "10.00"), q),
                "sell: ref>=limit matches");
        assertFalse(matching.canMatch(order(Side.SELL, OrderType.LIMIT, "1", "10.01"), q));
    }

    @Test
    void suspendedAndPriceMissingDoNotMatch() {
        Order o = order(Side.BUY, OrderType.MARKET, "1", null);
        assertFalse(matching.canMatch(o, quote(null, null, null, true)), "suspended");
        assertFalse(matching.canMatch(o, quote(null, null, null, false)), "no price");
    }

    @Test
    void partialQuantityRespectsAvailableLiquidity() {
        Order o = order(Side.BUY, OrderType.MARKET, "100", null);
        Quote q = quote("10", "10", "10", false);
        assertEquals(0, new BigDecimal("30").compareTo(
                matching.matchedQuantity(o, q, new BigDecimal("30"))));
        assertEquals(0, BigDecimal.ZERO.compareTo(
                matching.matchedQuantity(o, q, BigDecimal.ZERO)));
    }

    @Test
    void fillConsumptionIsIdempotentByExecId() {
        Order o = order(Side.BUY, OrderType.MARKET, "10", null);
        Fill f = fills.buildFill("E1", o, new BigDecimal("10"), new BigDecimal("10"), 10L);
        assertTrue(fills.consume(f));
        Fill duplicate = fills.buildFill("E1", o, new BigDecimal("10"), new BigDecimal("10"), 10L);
        assertFalse(fills.consume(duplicate), "same execId must not be applied twice");
        assertTrue(fills.isConsumed("E1"));
        log.info("duplicate execId E1 ignored, single settlement guaranteed");
    }
}
