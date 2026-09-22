package com.github.highcumontoa.backtestportfolioenginejava.portfolio;

import com.github.highcumontoa.backtestportfolioenginejava.domain.CashBalance;
import com.github.highcumontoa.backtestportfolioenginejava.domain.DataStatus;
import com.github.highcumontoa.backtestportfolioenginejava.domain.FxRate;
import com.github.highcumontoa.backtestportfolioenginejava.domain.Position;
import com.github.highcumontoa.backtestportfolioenginejava.domain.ValuationLine;
import com.github.highcumontoa.backtestportfolioenginejava.domain.ValuationResult;
import com.github.highcumontoa.backtestportfolioenginejava.market.FxRateService;
import com.github.highcumontoa.backtestportfolioenginejava.market.MarketDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 组合估值服务。
 *
 * 估值约定：
 * - 价格：OK 才参与估值；MISSING_PRICE / STALE_PRICE / SUSPENDED 的持仓市值记为不可用，
 *   绝不用零或旧价静默估值；
 * - 汇率：FX_MISSING / FX_STALE 时对应持仓与现金均不并入基准币种总额；
 * - 金额一律 2 位小数 HALF_UP；
 * - 只要存在任何非 OK 的行，healthy=false，totalEquity=null，强制使用方显式处理。
 *
 * 标的币种通过 symbolCcy 映射提供（本地回测简化模型：每标的一个报价币种）。
 */
@Service
public class ValuationService {

    public static final int MONEY_SCALE = 2;

    private static final Logger log = LoggerFactory.getLogger(ValuationService.class);

    private final MarketDataService marketData;
    private final FxRateService fxRateService;
    private final Map<String, String> symbolCcy;

    public ValuationService(MarketDataService marketData, FxRateService fxRateService,
                            SymbolCurrencyRegistry symbolCcy) {
        this.marketData = marketData;
        this.fxRateService = fxRateService;
        this.symbolCcy = symbolCcy.mapping();
    }

    public ValuationResult value(Portfolio portfolio, Instant at) {
        String baseCcy = portfolio.getBaseCurrency();
        List<ValuationLine> lines = new ArrayList<>();
        List<DataStatus> issues = new ArrayList<>();
        BigDecimal equity = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        boolean healthy = true;

        // 持仓估值
        for (Position pos : portfolio.positions()) {
            if (pos.getQuantity().signum() == 0) {
                continue;
            }
            String ccy = symbolCcy.getOrDefault(pos.getSymbol(), baseCcy);
            MarketDataService.PriceView pv = marketData.priceAt(pos.getSymbol(), at);
            BigDecimal mvBase = null;
            DataStatus status = pv.status();
            BigDecimal localPrice = pv.tick() == null ? null : pv.tick().price();

            if (status == DataStatus.OK) {
                FxRateService.FxView fx = fxRateService.rateToBase(ccy, baseCcy, at);
                if (fx.status() == DataStatus.OK) {
                    mvBase = localPrice.multiply(pos.getQuantity())
                            .multiply(fx.rate())
                            .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                } else {
                    status = fx.status();
                }
            }
            if (status != DataStatus.OK) {
                healthy = false;
                issues.add(status);
                log.warn("position {} excluded from equity status={} qty={}",
                        pos.getSymbol(), status, pos.getQuantity());
            } else {
                equity = equity.add(mvBase);
            }
            lines.add(new ValuationLine(pos.getSymbol(), pos.getQuantity(),
                    localPrice, ccy, mvBase, status));
        }

        // 现金折算
        BigDecimal cashBase = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        for (CashBalance cb : portfolio.cashBalances()) {
            FxRateService.FxView fx = fxRateService.rateToBase(cb.getCurrency(), baseCcy, at);
            if (fx.status() != DataStatus.OK) {
                healthy = false;
                issues.add(fx.status());
                log.warn("cash {} excluded from equity status={} amount={}",
                        cb.getCurrency(), fx.status(), cb.getCash());
                continue;
            }
            cashBase = cashBase.add(cb.getCash().multiply(fx.rate())
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        }

        if (!healthy) {
            log.warn("valuation at {} UNHEALTHY issues={} cashBase={}", at, issues, cashBase);
            return new ValuationResult(at, baseCcy, cashBase, lines, null, false, issues);
        }
        BigDecimal total = cashBase.add(equity).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        log.info("valuation at {} totalEquity={} {} cashBase={}", at, total, baseCcy, cashBase);
        return new ValuationResult(at, baseCcy, cashBase, lines, total, true, issues);
    }
}
