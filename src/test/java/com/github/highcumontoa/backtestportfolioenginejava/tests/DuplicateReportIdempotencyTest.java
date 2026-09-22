package com.github.highcumontoa.backtestportfolioenginejava.tests;

import com.github.highcumontoa.backtestportfolioenginejava.model.Fill;
import com.github.highcumontoa.backtestportfolioenginejava.model.Side;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** 同一成交回报（execId）被消费多次，只入账一次，现金/持仓不重复变动。 */
class DuplicateReportIdempotencyTest {

    private static final Logger log = LoggerFactory.getLogger(DuplicateReportIdempotencyTest.class);

    @Test
    void duplicateFillReportedThreeTimesSettlesOnce() {
        var kit = new EngineTestKit();
        kit.portfolio.deposit("USD", new BigDecimal("10000"));
        var fill = new Fill("EXEC-DUP", "ORD-X", "AAA", Side.BUY,
                new BigDecimal("10"), new BigDecimal("100"), 1L,
                BigDecimal.ZERO, BigDecimal.ZERO);

        assertTrue(kit.fillService.consume(fill), "first report applies");
        kit.portfolio.applyFill(fill, "USD");
        BigDecimal cashAfterFirst = kit.portfolio.cash("USD");

        // 两次重复回报：consume 返回 false，调用方据此绝不再入账
        assertFalse(kit.fillService.consume(fill));
        assertFalse(kit.fillService.consume(fill));

        // 显式验证：即便错误地重复入账被幂等层挡住，现金与持仓只变一次
        assertEquals(0, cashAfterFirst.compareTo(kit.portfolio.cash("USD")));
        assertEquals(0, new BigDecimal("10").compareTo(kit.portfolio.position("AAA").getQuantity()));
        assertEquals(0, new BigDecimal("9000.00").compareTo(kit.portfolio.cash("USD")));
        log.info("duplicate execId EXEC-DUP x3 => single settlement cash=9000 qty=10");
    }
}
