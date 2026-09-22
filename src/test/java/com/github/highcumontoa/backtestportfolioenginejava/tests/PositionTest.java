package com.github.highcumontoa.backtestportfolioenginejava.tests;

import com.github.highcumontoa.backtestportfolioenginejava.exception.InvalidArgumentApiException;
import com.github.highcumontoa.backtestportfolioenginejava.model.Position;
import com.github.highcumontoa.backtestportfolioenginejava.model.Side;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** 持仓数量/成本守恒、冻结防超卖、拆合股总成本不变。 */
class PositionTest {

    private static final Logger log = LoggerFactory.getLogger(PositionTest.class);

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    @Test
    void buyTwiceProducesWeightedCost() {
        Position p = new Position("AAA", "USD");
        p.applyTrade(Side.BUY, bd("100"), bd("10"), bd("5"), bd("0"));
        p.applyTrade(Side.BUY, bd("100"), bd("12"), bd("5"), bd("0"));
        assertEquals(0, bd("200").compareTo(p.getQuantity()));
        // 总成本 = 100*10+5 + 100*12+5 = 2210, 均价 11.05
        assertEquals(0, new BigDecimal("11.05000000").compareTo(p.getAvgCost()));
        assertEquals(0, bd("2210.0000000000").compareTo(p.totalCost())
                , "total cost includes commissions: " + p.totalCost());
        log.info("weighted avgCost={} totalCost={}", p.getAvgCost(), p.totalCost());
    }

    @Test
    void freezePreventsOversell() {
        Position p = new Position("AAA", "USD");
        p.applyTrade(Side.BUY, bd("10"), bd("100"), BigDecimal.ZERO, BigDecimal.ZERO);
        p.freeze(bd("10"));
        assertEquals(0, BigDecimal.ZERO.compareTo(p.getAvailableQuantity()));
        InvalidArgumentApiException ex = assertThrows(InvalidArgumentApiException.class,
                () -> p.freeze(bd("1")));
        assertEquals("INSUFFICIENT_POSITION", ex.getCode());
        // 卖出不改变单位成本
        p.applyTrade(Side.SELL, bd("4"), bd("110"), BigDecimal.ZERO, BigDecimal.ZERO);
        assertEquals(0, bd("6").compareTo(p.getQuantity()));
        assertEquals(0, bd("100.00000000").compareTo(p.getAvgCost()));
        log.info("after partial sell qty={} avail={} avgCost={}",
                p.getQuantity(), p.getAvailableQuantity(), p.getAvgCost());
    }

    @Test
    void sellMoreThanHeldRejected() {
        Position p = new Position("AAA", "USD");
        p.applyTrade(Side.BUY, bd("5"), bd("100"), BigDecimal.ZERO, BigDecimal.ZERO);
        InvalidArgumentApiException ex = assertThrows(InvalidArgumentApiException.class,
                () -> p.applyTrade(Side.SELL, bd("6"), bd("100"), BigDecimal.ZERO, BigDecimal.ZERO));
        assertEquals("OVERSELL", ex.getCode());
    }

    @Test
    void splitKeepsTotalCostAndScalesQuantity() {
        Position p = new Position("AAA", "USD");
        p.applyTrade(Side.BUY, bd("100"), bd("10"), BigDecimal.ZERO, BigDecimal.ZERO);
        BigDecimal costBefore = p.totalCost();
        p.applyRatio(bd("2")); // 1 拆 2
        assertEquals(0, bd("200").compareTo(p.getQuantity()));
        assertEquals(0, bd("200").compareTo(p.getAvailableQuantity()));
        assertEquals(0, costBefore.compareTo(p.totalCost()),
                "total cost invariant after split");
        assertEquals(0, bd("5.00000000").compareTo(p.getAvgCost()));
        log.info("split qty={} avgCost={} totalCost={}", p.getQuantity(), p.getAvgCost(), p.totalCost());
    }

    @Test
    void reverseSplitKeepsTotalCost() {
        Position p = new Position("AAA", "USD");
        p.applyTrade(Side.BUY, bd("100"), bd("10"), BigDecimal.ZERO, BigDecimal.ZERO);
        BigDecimal costBefore = p.totalCost();
        p.applyRatio(bd("0.1")); // 10 合 1
        assertEquals(0, bd("10.00000000").compareTo(p.getQuantity()));
        assertEquals(0, costBefore.compareTo(p.totalCost()));
        assertEquals(0, bd("100.00000000").compareTo(p.getAvgCost()));
        log.info("reverse split qty={} avgCost={}", p.getQuantity(), p.getAvgCost());
    }

    @Test
    void fullCloseResetsCost() {
        Position p = new Position("AAA", "USD");
        p.applyTrade(Side.BUY, bd("10"), bd("100"), BigDecimal.ZERO, BigDecimal.ZERO);
        p.applyTrade(Side.SELL, bd("10"), bd("100"), BigDecimal.ZERO, BigDecimal.ZERO);
        assertEquals(0, BigDecimal.ZERO.compareTo(p.getQuantity()));
        assertEquals(0, BigDecimal.ZERO.compareTo(p.getAvgCost()));
    }
}
