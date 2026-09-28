package com.github.highcumontoa.backtestportfolioenginejava.service;

import com.github.highcumontoa.backtestportfolioenginejava.exception.InvalidArgumentApiException;
import com.github.highcumontoa.backtestportfolioenginejava.model.CorporateAction;
import com.github.highcumontoa.backtestportfolioenginejava.model.CorporateActionType;
import com.github.highcumontoa.backtestportfolioenginejava.model.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 公司行为处理：拆股、合股、现金分红。
 *
 * <p>规则：
 * <ul>
 *   <li>幂等：以事件 id 去重，同一事件重复应用结果保持不变（第二次返回 false，不产生任何副作用）。</li>
 *   <li>拆股/合股：持仓数量与可用数量乘 ratio，单位成本除以 ratio，<b>持仓总成本不变</b>；
 *       因此除权日前后按成本口径的估值连续，不含跳变。</li>
 *   <li>现金分红：按 effectiveTime 时点持仓数量 * cashPerShare 增加现金（税前），不改变持仓数量。</li>
 * </ul>
 */
@Service
public class CorporateActionService {

    private static final Logger log = LoggerFactory.getLogger(CorporateActionService.class);

    private final PortfolioService portfolio;
    private final ConcurrentHashMap<String, CorporateAction> applied = new ConcurrentHashMap<>();

    public CorporateActionService(PortfolioService portfolio) {
        this.portfolio = portfolio;
    }

    /** 应用公司行为；重复事件返回 false 且无副作用。 */
    public boolean apply(CorporateAction action) {
        validate(action);
        if (applied.putIfAbsent(action.id(), action) != null) {
            log.warn("corporate action duplicate ignored id={} type={} symbol={}",
                    action.id(), action.type(), action.symbol());
            return false;
        }
        Position p = portfolio.position(action.symbol());
        if (action.type() == CorporateActionType.CASH_DIVIDEND) {
            BigDecimal qty = p == null ? BigDecimal.ZERO : p.getQuantity();
            BigDecimal proceeds = qty.multiply(action.cashPerShare())
                    .setScale(portfolio.getConfig().moneyScale(), java.math.RoundingMode.HALF_UP);
            // 直接派现：计入当期分红收益（现金增加），不改动批次数量与剩余成本。
            portfolio.payDividend(action.currency(), proceeds);
            log.info("corporate action CASH_DIVIDEND id={} symbol={} qty={} perShare={} proceeds={} {}",
                    action.id(), action.symbol(), qty, action.cashPerShare(), proceeds, action.currency());
            return true;
        }
        // 拆股 / 合股：Position（数量/均价）与批次（数量、成本池不动）在同一标的锁内成对调整，
        // 保证两边看到的数量始终一致；批次口径总剩余成本调整前后保持不变。
        if (p == null) {
            log.info("corporate action {} id={} symbol={} no position, nothing to adjust",
                    action.type(), action.id(), action.symbol());
            return true;
        }
        BigDecimal beforeQty;
        BigDecimal beforePosCost;
        BigDecimal beforeLotCost;
        synchronized (portfolio.positionLock(action.symbol())) {
            beforeQty = p.getQuantity();
            beforePosCost = p.totalCost();
            beforeLotCost = portfolio.lotRemainingCost(action.symbol());
            p.applyRatio(action.ratio());
            portfolio.adjustLotsForRatio(action.symbol(), action.ratio());
        }
        log.info("corporate action {} id={} symbol={} ratio={} qty {} -> {} posCost {} lotCost {} -> {}",
                action.type(), action.id(), action.symbol(), action.ratio(),
                beforeQty, p.getQuantity(), beforePosCost, beforeLotCost);
        return true;
    }

    public boolean isApplied(String actionId) {
        return applied.containsKey(actionId);
    }

    private void validate(CorporateAction a) {
        if (a == null || a.id() == null || a.id().isBlank()) {
            throw new InvalidArgumentApiException("CA_ID_REQUIRED", "corporate action id required");
        }
        if (a.symbol() == null || a.symbol().isBlank() || a.type() == null) {
            throw new InvalidArgumentApiException("CA_FIELD_REQUIRED", "symbol and type required");
        }
        if (a.type() == CorporateActionType.CASH_DIVIDEND) {
            if (a.cashPerShare() == null || a.cashPerShare().signum() < 0
                    || a.currency() == null || a.currency().isBlank()) {
                throw new InvalidArgumentApiException("INVALID_DIVIDEND",
                        "cash dividend requires non-negative cashPerShare and currency");
            }
        } else {
            if (a.ratio() == null || a.ratio().signum() <= 0) {
                throw new InvalidArgumentApiException("INVALID_RATIO",
                        "split/reverse-split requires positive ratio");
            }
        }
    }
}
