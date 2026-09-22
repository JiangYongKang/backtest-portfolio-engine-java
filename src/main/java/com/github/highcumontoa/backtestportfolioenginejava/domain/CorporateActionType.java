package com.github.highcumontoa.backtestportfolioenginejava.domain;

/** 公司行为类型：拆股（正向比例）、合股（反向比例）、现金分红（每股派现）。 */
public enum CorporateActionType {
    SPLIT,
    REVERSE_SPLIT,
    CASH_DIVIDEND
}
