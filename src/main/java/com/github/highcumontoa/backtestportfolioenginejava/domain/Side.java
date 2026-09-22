package com.github.highcumontoa.backtestportfolioenginejava.domain;

/** 交易方向。 */
public enum Side {
    BUY,
    SELL;

    /** 买方向返回 +1，卖方向返回 -1，用于持仓与资金的带符号计算。 */
    public int sign() {
        return this == BUY ? 1 : -1;
    }

    public boolean isBuy() {
        return this == BUY;
    }
}
