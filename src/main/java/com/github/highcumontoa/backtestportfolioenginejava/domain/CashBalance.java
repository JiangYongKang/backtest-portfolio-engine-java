package com.github.highcumontoa.backtestportfolioenginejava.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 单币种现金账户（线程安全）。
 *
 * 记账恒等式：cash = 总现金；reserved = 其中被买单冻结的部分；available = cash - reserved。
 * - 下单冻结：reserved += A（cash 不变，available 减少）；
 * - 买单成交金额 A：资金真实流出，cash -= A，同时核销等额预留 reserved -= A
 *   （available 不变：冻结资金转为真实支出）；
 * - 撤单/拒单：reserved -= A（cash 不变，available 恢复）；
 * - 卖单成交：cash += 净到账。
 * 货币精度固定 2 位小数 HALF_UP。
 */
public final class CashBalance {

    public static final int SCALE = 2;

    private final String currency;
    private BigDecimal cash;
    private BigDecimal reserved = BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);

    public CashBalance(String currency, BigDecimal initialCash) {
        this.currency = currency;
        this.cash = initialCash.setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** 买单下单时冻结资金。 */
    public synchronized void reserve(BigDecimal amount) {
        BigDecimal a = money(amount);
        if (a.compareTo(available()) > 0) {
            throw new IllegalStateException("insufficient available cash " + currency);
        }
        reserved = reserved.add(a);
    }

    /** 撤单/拒单释放预留（资金未流出，仅解冻）。 */
    public synchronized void release(BigDecimal amount) {
        BigDecimal a = money(amount);
        reserved = reserved.subtract(a);
        if (reserved.signum() < 0) {
            reserved = BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        }
    }

    /**
     * 买单成交：真实支出 finalAmount，cash 与 reserved 各扣减一份。
     * 由 Portfolio 保证 finalAmount 不超过该单剩余预留。
     */
    public synchronized void settleBuy(BigDecimal finalAmount) {
        BigDecimal f = money(finalAmount);
        if (f.compareTo(reserved) > 0) {
            throw new IllegalStateException("buy settlement exceeds remaining reserved amount");
        }
        cash = cash.subtract(f);
        reserved = reserved.subtract(f);
    }

    /** 卖单成交：净到账入现金。 */
    public synchronized void settleSell(BigDecimal proceeds) {
        cash = cash.add(money(proceeds));
    }

    /** 现金分红入账。 */
    public synchronized void addDividend(BigDecimal amount) {
        cash = cash.add(money(amount));
    }

    public synchronized BigDecimal available() {
        return cash.subtract(reserved);
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(SCALE, RoundingMode.HALF_UP);
    }

    public String getCurrency() { return currency; }
    public synchronized BigDecimal getCash() { return cash; }
    public synchronized BigDecimal getReserved() { return reserved; }
}
