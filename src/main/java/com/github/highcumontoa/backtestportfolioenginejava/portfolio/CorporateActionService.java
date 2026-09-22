package com.github.highcumontoa.backtestportfolioenginejava.portfolio;

import com.github.highcumontoa.backtestportfolioenginejava.domain.CorporateAction;
import com.github.highcumontoa.backtestportfolioenginejava.domain.CorporateActionType;
import com.github.highcumontoa.backtestportfolioenginejava.domain.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 公司行为处理（幂等）。
 *
 * 规则：
 * - SPLIT / REVERSE_SPLIT：数量与可用（含冻结）数量同乘乘数，成本基准总额不变，
 *   平均成本相应摊薄/抬高；空仓不受影响；
 * - CASH_DIVIDEND：按除权日（exDate）时点持仓数量 × 每股派现，现金直接入账，
 *   持仓与成本基准不变；
 * - 同一 actionId 重复应用结果不变（已应用直接返回 false 并记录）；
 * - 除权日事件在引擎排序中优先于行情与撮合，保证除权前后估值口径一致。
 */
@Service
public class CorporateActionService {

    public static final int MONEY_SCALE = 2;

    private static final Logger log = LoggerFactory.getLogger(CorporateActionService.class);

    private final Set<String> applied = ConcurrentHashMap.newKeySet();

    /**
     * 应用公司行为到指定账户组合。
     *
     * @return true 表示本次实际应用；false 表示重复事件被忽略（幂等）
     */
    public boolean apply(CorporateAction action, Portfolio portfolio) {
        String dedupeKey = portfolio.getAccountId() + ":" + action.actionId();
        if (!applied.add(dedupeKey)) {
            log.info("corporate action skipped actionId={} account={} reason=duplicate",
                    action.actionId(), portfolio.getAccountId());
            return false;
        }
        portfolio.exclusively(() -> {
            Position pos = portfolio.position(action.symbol());
            if (pos == null || pos.getQuantity().signum() == 0) {
                log.info("corporate action actionId={} {} no position, no-op",
                        action.actionId(), action.symbol());
                return;
            }
            if (action.type() == CorporateActionType.CASH_DIVIDEND) {
                BigDecimal proceeds = pos.getQuantity().multiply(action.amount())
                        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                portfolio.addDividend(portfolio.getBaseCurrency(), proceeds);
                log.info("dividend applied actionId={} {} qty={} dps={} proceeds={}",
                        action.actionId(), action.symbol(),
                        pos.getQuantity(), action.amount(), proceeds);
            } else {
                BigDecimal multiplier = action.quantityMultiplier();
                pos.applyRatioMultiplier(multiplier);
                log.info("ratio applied actionId={} type={} {} multiplier={} newQty={} newCostBasis={}",
                        action.actionId(), action.type(), action.symbol(), multiplier,
                        pos.getQuantity(), pos.getCostBasis());
            }
        });
        return true;
    }

    public boolean isApplied(String actionId, String accountId) {
        return applied.contains(accountId + ":" + actionId);
    }
}
