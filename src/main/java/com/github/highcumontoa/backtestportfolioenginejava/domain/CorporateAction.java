package com.github.highcumontoa.backtestportfolioenginejava.domain;

import com.github.highcumontoa.backtestportfolioenginejava.error.InvalidParameterException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * 公司行为（不可变）。
 * SPLIT：ratio 为每股变为多少股（如 2 拆 1 -> ratio=2）；
 * REVERSE_SPLIT：ratio 为多少股合 1 股（如 10 合 1 -> ratio=10）；
 * CASH_DIVIDEND：ratio 字段不用，amount 为每股现金分红（报价币种，税前）。
 * actionId 用于幂等：同一 actionId 重复应用结果不变。
 */
public record CorporateAction(
        String actionId,
        String symbol,
        CorporateActionType type,
        BigDecimal ratio,
        BigDecimal amount,
        Instant exDate) {

    public CorporateAction {
        if (actionId == null || actionId.isBlank()) {
            throw new InvalidParameterException("actionId is required");
        }
        if (symbol == null || symbol.isBlank()) {
            throw new InvalidParameterException("symbol is required");
        }
        Objects.requireNonNull(type, "type is required");
        Objects.requireNonNull(exDate, "exDate is required");
        if ((type == CorporateActionType.SPLIT || type == CorporateActionType.REVERSE_SPLIT)
                && (ratio == null || ratio.signum() <= 0)) {
            throw new InvalidParameterException("split ratio must be positive");
        }
        if (type == CorporateActionType.CASH_DIVIDEND
                && (amount == null || amount.signum() < 0)) {
            throw new InvalidParameterException("dividend amount must be non-negative");
        }
    }

    /** 拆/合股后的数量乘数：拆股为 ratio，合股为 1/ratio。 */
    public BigDecimal quantityMultiplier() {
        return switch (type) {
            case SPLIT -> ratio;
            case REVERSE_SPLIT -> BigDecimal.ONE.divide(ratio, 16,
                    java.math.RoundingMode.HALF_UP);
            case CASH_DIVIDEND -> BigDecimal.ONE;
        };
    }
}
