package com.github.highcumontoa.backtestportfolioenginejava.portfolio;

import com.github.highcumontoa.backtestportfolioenginejava.error.InvalidParameterException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 账户注册表：按 accountId 取得组合；创建时注入初始基准币种资金。 */
@Component
public class PortfolioRegistry {

    private final Map<String, Portfolio> accounts = new ConcurrentHashMap<>();

    /** 创建账户（幂等：已存在则返回既有账户）。 */
    public Portfolio createAccount(String accountId, String baseCcy, BigDecimal initialCash) {
        if (accountId == null || accountId.isBlank()) {
            throw new InvalidParameterException("accountId is required");
        }
        return accounts.computeIfAbsent(accountId,
                id -> new Portfolio(id, baseCcy,
                        initialCash == null ? BigDecimal.ZERO : initialCash));
    }

    public Portfolio require(String accountId) {
        Portfolio p = accounts.get(accountId);
        if (p == null) {
            throw new InvalidParameterException("unknown account: " + accountId);
        }
        return p;
    }

    public boolean exists(String accountId) {
        return accounts.containsKey(accountId);
    }

    public java.util.Set<String> accountIds() {
        return accounts.keySet();
    }
}
