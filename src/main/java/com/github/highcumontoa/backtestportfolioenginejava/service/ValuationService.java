package com.github.highcumontoa.backtestportfolioenginejava.service;

import com.github.highcumontoa.backtestportfolioenginejava.config.CostConfig;
import com.github.highcumontoa.backtestportfolioenginejava.model.FxRate;
import com.github.highcumontoa.backtestportfolioenginejava.model.Position;
import com.github.highcumontoa.backtestportfolioenginejava.model.PositionValuation;
import com.github.highcumontoa.backtestportfolioenginejava.model.Quote;
import com.github.highcumontoa.backtestportfolioenginejava.model.ValuationFlag;
import com.github.highcumontoa.backtestportfolioenginejava.model.ValuationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 组合估值与多币种折算。
 *
 * <p>精度/舍入：价格沿用行情精度，金额一律 {@code moneyScale} 位、{@link RoundingMode#HALF_UP}。
 * 结论约定（禁止按 0 或旧价静默估值）：
 * <ul>
 *   <li>无任何时点可见行情 -> PRICE_MISSING；停牌或快照无价 -> PRICE_STALE_SUSPENDED。</li>
 *   <li>外币无汇率 -> FX_MISSING；汇率年龄超过 {@code fxMaxStaleMillis} -> FX_STALE。</li>
 *   <li>任一持仓或任一币种现金无法可靠折算，{@code complete=false}，
 *       完整 NAV 只汇总 flag=OK 的持仓与可折算现金。</li>
 * </ul>
 */
@Service
public class ValuationService {

    private static final Logger log = LoggerFactory.getLogger(ValuationService.class);

    private final MarketDataService marketData;
    private final FxRateService fxRates;
    private final PortfolioService portfolio;
    private final CostConfig config;

    @Autowired
    public ValuationService(MarketDataService marketData, FxRateService fxRates,
                            PortfolioService portfolio) {
        this(marketData, fxRates, portfolio, CostConfig.DEFAULT);
    }

    public ValuationService(MarketDataService marketData, FxRateService fxRates,
                            PortfolioService portfolio, CostConfig config) {
        this.marketData = marketData;
        this.fxRates = fxRates;
        this.portfolio = portfolio;
        this.config = config;
    }

    public ValuationResult value(long asOfTime, String baseCcy) {
        if (baseCcy == null || baseCcy.isBlank()) {
            throw new com.github.highcumontoa.backtestportfolioenginejava.exception
                    .InvalidArgumentApiException("BASE_CCY_REQUIRED", "base currency required");
        }
        List<PositionValuation> rows = new ArrayList<>();
        List<PositionValuation> stale = new ArrayList<>();
        BigDecimal totalMv = BigDecimal.ZERO.setScale(config.moneyScale(), RoundingMode.HALF_UP);
        BigDecimal totalCost = BigDecimal.ZERO.setScale(config.moneyScale(), RoundingMode.HALF_UP);
        BigDecimal totalLotCost = BigDecimal.ZERO.setScale(config.moneyScale(), RoundingMode.HALF_UP);
        boolean complete = true;

        for (Position p : portfolio.positions().values()) {
            if (p.getQuantity().signum() == 0) {
                continue;
            }
            PositionValuation row = valuePosition(p, asOfTime, baseCcy);
            rows.add(row);
            if (row.flag() == ValuationFlag.OK) {
                totalMv = totalMv.add(row.marketValueBase());
                totalCost = totalCost.add(row.costValueBase());
                totalLotCost = totalLotCost.add(row.lotCostValueBase());
            } else {
                stale.add(row);
                complete = false;
            }
        }

        // 现金折算
        Map<String, BigDecimal> cashBase = new LinkedHashMap<>();
        BigDecimal cashTotalBase = BigDecimal.ZERO.setScale(config.moneyScale(), RoundingMode.HALF_UP);
        for (Map.Entry<String, BigDecimal> e : portfolio.allCash().entrySet()) {
            String ccy = e.getKey();
            BigDecimal amt = e.getValue();
            cashBase.put(ccy, amt);
            Optional<FxRate> fx = fxRates.latestAt(ccy, baseCcy, asOfTime);
            if (fx.isEmpty()) {
                complete = false;
                log.warn("valuation cash fx missing ccy={} base={} t={}", ccy, baseCcy, asOfTime);
                continue;
            }
            if (isStaleFx(fx.get(), asOfTime)) {
                complete = false;
                log.warn("valuation cash fx stale ccy={} base={} fxT={} asOf={}",
                        ccy, baseCcy, fx.get().eventTime(), asOfTime);
                continue;
            }
            cashTotalBase = cashTotalBase.add(
                    amt.multiply(fx.get().rate()).setScale(config.moneyScale(), RoundingMode.HALF_UP));
        }

        // 已实现盈亏 / 分红按币种折算；缺汇率或过期同样使 complete=false。
        BigDecimal realizedBase = BigDecimal.ZERO.setScale(config.moneyScale(), RoundingMode.HALF_UP);
        for (Map.Entry<String, BigDecimal> e : portfolio.realizedPnlByCurrency().entrySet()) {
            Optional<FxRate> fx = fxRates.latestAt(e.getKey(), baseCcy, asOfTime);
            if (fx.isEmpty() || isStaleFx(fx.get(), asOfTime)) {
                complete = false;
                log.warn("valuation realized-pnl fx unavailable ccy={} base={} t={}",
                        e.getKey(), baseCcy, asOfTime);
                continue;
            }
            realizedBase = realizedBase.add(
                    e.getValue().multiply(fx.get().rate()).setScale(config.moneyScale(), RoundingMode.HALF_UP));
        }
        BigDecimal dividendBase = BigDecimal.ZERO.setScale(config.moneyScale(), RoundingMode.HALF_UP);
        for (Map.Entry<String, BigDecimal> e : portfolio.dividendsByCurrency().entrySet()) {
            Optional<FxRate> fx = fxRates.latestAt(e.getKey(), baseCcy, asOfTime);
            if (fx.isEmpty() || isStaleFx(fx.get(), asOfTime)) {
                complete = false;
                log.warn("valuation dividend fx unavailable ccy={} base={} t={}",
                        e.getKey(), baseCcy, asOfTime);
                continue;
            }
            dividendBase = dividendBase.add(
                    e.getValue().multiply(fx.get().rate()).setScale(config.moneyScale(), RoundingMode.HALF_UP));
        }

        BigDecimal unrealizedBase = totalMv.subtract(totalLotCost)
                .setScale(config.moneyScale(), RoundingMode.HALF_UP);
        BigDecimal totalPnlBase = realizedBase.add(unrealizedBase).add(dividendBase)
                .setScale(config.moneyScale(), RoundingMode.HALF_UP);
        BigDecimal navBase = totalMv.add(cashTotalBase)
                .setScale(config.moneyScale(), RoundingMode.HALF_UP);
        log.info("valuation asOf={} base={} complete={} holdingsMv={} lotCost={} unreal={} realized={} div={} cashBase={} nav={} staleCount={}",
                asOfTime, baseCcy, complete, totalMv, totalLotCost, unrealizedBase,
                realizedBase, dividendBase, cashTotalBase, navBase, stale.size());
        return new ValuationResult(asOfTime, baseCcy, cashBase, rows,
                totalMv, totalCost, totalLotCost, unrealizedBase, realizedBase,
                dividendBase, totalPnlBase, cashTotalBase, navBase, complete, List.copyOf(stale));
    }

    private PositionValuation valuePosition(Position p, long asOfTime, String baseCcy) {
        String symbol = p.getSymbol();
        String ccy = p.getCurrency();
        Optional<Quote> q = marketData.latestAt(symbol, asOfTime);
        if (q.isEmpty()) {
            return row(p, null, null, null, null, ValuationFlag.PRICE_MISSING,
                    "no quote at or before t=" + asOfTime);
        }
        Quote quote = q.get();
        if (quote.suspended() || quote.last() == null || quote.last().signum() <= 0) {
            return row(p, quote.last(), quote.eventTime(), null, null,
                    ValuationFlag.PRICE_STALE_SUSPENDED,
                    quote.suspended() ? "suspended since t=" + quote.eventTime()
                            : "missing last price at t=" + quote.eventTime());
        }
        Optional<FxRate> fx = fxRates.latestAt(ccy, baseCcy, asOfTime);
        if (fx.isEmpty()) {
            return row(p, quote.last(), quote.eventTime(), null, null, ValuationFlag.FX_MISSING,
                    "no fx " + ccy + "->" + baseCcy + " at t=" + asOfTime);
        }
        if (isStaleFx(fx.get(), asOfTime)) {
            return row(p, quote.last(), quote.eventTime(), fx.get().rate(), fx.get().eventTime(),
                    ValuationFlag.FX_STALE,
                    "fx stale: fxT=" + fx.get().eventTime() + " asOf=" + asOfTime);
        }
        BigDecimal fxRate = fx.get().rate();
        BigDecimal mvLocal = quote.last().multiply(p.getQuantity())
                .setScale(config.moneyScale(), RoundingMode.HALF_UP);
        BigDecimal mvBase = mvLocal.multiply(fxRate).setScale(config.moneyScale(), RoundingMode.HALF_UP);
        BigDecimal costBase = p.totalCost().multiply(fxRate)
                .setScale(config.moneyScale(), RoundingMode.HALF_UP);
        // 批次台账口径：剩余成本与持仓数量必须一致（同一标的锁内成对更新）。
        BigDecimal lotCostLocal = portfolio.lotRemainingCost(symbol);
        BigDecimal lotCostBase = lotCostLocal.multiply(fxRate)
                .setScale(config.moneyScale(), RoundingMode.HALF_UP);
        BigDecimal unrealizedBase = mvBase.subtract(lotCostBase)
                .setScale(config.moneyScale(), RoundingMode.HALF_UP);
        return new PositionValuation(symbol, p.getQuantity(), quote.last(), quote.eventTime(),
                ccy, fxRate, fx.get().eventTime(), mvBase, costBase, lotCostBase,
                unrealizedBase, ValuationFlag.OK, "ok");
    }

    private boolean isStaleFx(FxRate fx, long asOfTime) {
        // 同币种合成汇率时间戳等于 asOf，不会过期。
        return asOfTime - fx.eventTime() > config.fxMaxStaleMillis();
    }

    private PositionValuation row(Position p, BigDecimal price, Long priceTime,
                                  BigDecimal fx, Long fxTime, ValuationFlag flag, String detail) {
        return new PositionValuation(p.getSymbol(), p.getQuantity(), price,
                priceTime == null ? 0L : priceTime, p.getCurrency(),
                fx, fxTime, null, null, null, null, flag, detail);
    }
}
