package com.github.highcumontoa.backtestportfolioenginejava.tests;

import com.github.highcumontoa.backtestportfolioenginejava.config.CostConfig;
import com.github.highcumontoa.backtestportfolioenginejava.service.BacktestEngine;
import com.github.highcumontoa.backtestportfolioenginejava.service.CorporateActionService;
import com.github.highcumontoa.backtestportfolioenginejava.service.FillService;
import com.github.highcumontoa.backtestportfolioenginejava.service.FxRateService;
import com.github.highcumontoa.backtestportfolioenginejava.service.MarketDataService;
import com.github.highcumontoa.backtestportfolioenginejava.service.MatchingService;
import com.github.highcumontoa.backtestportfolioenginejava.service.OrderService;
import com.github.highcumontoa.backtestportfolioenginejava.service.PortfolioService;
import com.github.highcumontoa.backtestportfolioenginejava.service.ValuationService;

/** 纯手工装配（不依赖 Spring），保证测试确定、可复现。 */
final class EngineTestKit {

    final MarketDataService marketData = new MarketDataService();
    final FxRateService fxRates = new FxRateService();
    final OrderService orderService = new OrderService();
    final CostConfig config = CostConfig.DEFAULT;
    final MatchingService matching = new MatchingService(config);
    final FillService fillService = new FillService(config);
    final PortfolioService portfolio = new PortfolioService(config);
    final CorporateActionService corporateActionService;
    final ValuationService valuationService;
    final BacktestEngine engine;

    EngineTestKit() {
        corporateActionService = new CorporateActionService(portfolio);
        valuationService = new ValuationService(marketData, fxRates, portfolio, config);
        engine = new BacktestEngine(marketData, fxRates, orderService, matching,
                fillService, portfolio, corporateActionService, valuationService);
    }
}
