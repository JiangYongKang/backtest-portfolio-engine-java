package com.github.highcumontoa.backtestportfolioenginejava.portfolio;

import com.github.highcumontoa.backtestportfolioenginejava.domain.CashBalance;
import com.github.highcumontoa.backtestportfolioenginejava.domain.Fill;
import com.github.highcumontoa.backtestportfolioenginejava.domain.Position;
import com.github.highcumontoa.backtestportfolioenginejava.domain.Side;
import com.github.highcumontoa.backtestportfolioenginejava.error.InvalidParameterException;
import com.github.highcumontoa.backtestportfolioenginejava.error.ResourceLimitException;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 单账户组合：现金（多币种）、持仓与一致性控制的统一入口。
 *
 * 并发模型：账户级 ReentrantLock。任何会改变资金/持仓的操作（下单预留、撤单释放、
 * 成交结算、公司行为）都必须在 {@link #exclusively} 内完成，校验与变更在同一临界区内，
 * 从根上杜绝超卖与重复占用。锁内不调用外部服务（价格/汇率在锁外预先取好）。
 */
public class Portfolio {

    private final String accountId;
    private final String baseCurrency;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, CashBalance> cash = new ConcurrentHashMap<>();
    private final Map<String, Position> positions = new ConcurrentHashMap<>();

    public Portfolio(String accountId, String baseCurrency, BigDecimal initialBaseCash) {
        this.accountId = accountId;
        this.baseCurrency = baseCurrency;
        this.cash.put(baseCurrency, new CashBalance(baseCurrency, initialBaseCash));
    }

    public String getAccountId() { return accountId; }
    public String getBaseCurrency() { return baseCurrency; }

    /** 在账户排他锁内执行。 */
    public <T> T exclusively(Supplier<T> action) {
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    public void exclusively(Runnable action) {
        exclusively(() -> { action.run(); return null; });
    }

    private CashBalance cashOf(String ccy) {
        return cash.computeIfAbsent(ccy, c -> new CashBalance(c, BigDecimal.ZERO));
    }

    private Position positionOf(String symbol) {
        return positions.computeIfAbsent(symbol, Position::new);
    }

    // —— 下单阶段：预留资源 ——

    /** 买单预留资金（含预估费用），可用资金不足抛 RESOURCE_LIMITED。 */
    public void reserveBuyCash(String ccy, BigDecimal amount) {
        exclusively(() -> {
            try {
                cashOf(ccy).reserve(amount);
            } catch (IllegalStateException e) {
                throw new ResourceLimitException(e.getMessage());
            }
        });
    }

    /** 卖单预留（冻结）可卖数量，不足抛 RESOURCE_LIMITED。 */
    public void reserveSellQuantity(String symbol, BigDecimal quantity) {
        exclusively(() -> {
            try {
                positionOf(symbol).reserve(quantity);
            } catch (IllegalStateException e) {
                throw new ResourceLimitException(e.getMessage());
            }
        });
    }

    /** 撤单/拒单释放买单预留资金。 */
    public void releaseBuyCash(String ccy, BigDecimal amount) {
        exclusively(() -> cashOf(ccy).release(amount));
    }

    /** 撤单释放卖单冻结数量（成交部分已核销，只释放剩余撤销量由调用方计算）。 */
    public void releaseSellQuantity(String symbol, BigDecimal quantity) {
        exclusively(() -> positionOf(symbol).release(quantity));
    }

    // —— 成交阶段：结算 ——

    /**
     * 结算一笔成交：
     * 买单在账户临界区内按"本笔金额 &lt;= 本单剩余预留（初始预留 - 已成交累计支出）"校验后
     * 扣减现金；预留额在订单终态由 OrderService 统一释放，多笔部分成交不会重复或漏扣；
     * 卖单核销冻结数量并把净到账入现金。
     *
     * @param reservedForOrder 本单下单时全额冻结的资金（仅买单使用）
     * @param settledBefore    本单此前各笔已实际支出的累计金额
     */
    public void settle(Fill fill, String settlementCcy,
                       BigDecimal reservedForOrder, BigDecimal settledBefore) {
        if (settlementCcy == null || settlementCcy.isBlank()) {
            throw new InvalidParameterException("settlement currency is required");
        }
        exclusively(() -> {
            Position pos = positionOf(fill.symbol());
            CashBalance cb = cashOf(settlementCcy);
            if (fill.side().isBuy()) {
                BigDecimal finalAmount = fill.grossAmount().add(fill.commission()).add(fill.tax());
                BigDecimal already = settledBefore == null ? BigDecimal.ZERO : settledBefore;
                BigDecimal remainingReservation = reservedForOrder.subtract(already);
                if (finalAmount.compareTo(remainingReservation) > 0) {
                    throw new ResourceLimitException(
                            "buy settlement exceeds remaining reserved cash for order "
                                    + fill.orderId());
                }
                cb.settleBuy(finalAmount);
                pos.applyBuy(fill.quantity(), fill.price(), fill.commission());
            } else {
                BigDecimal net = fill.grossAmount().subtract(fill.commission())
                        .subtract(fill.tax());
                cb.settleSell(net);
                pos.applySettledSell(fill.quantity(), fill.price());
            }
        });
    }

    // —— 查询（快照值）——

    /** 现金分红入账（指定币种，基准币种以外的分红币种需事先配置汇率）。 */
    public void addDividend(String ccy, BigDecimal amount) {
        exclusively(() -> cashOf(ccy).addDividend(amount));
    }

    public BigDecimal cash(String ccy) {
        CashBalance cb = cash.get(ccy);
        return cb == null ? BigDecimal.ZERO : cb.getCash();
    }

    public BigDecimal reservedCash(String ccy) {
        CashBalance cb = cash.get(ccy);
        return cb == null ? BigDecimal.ZERO : cb.getReserved();
    }

    public BigDecimal availableCash(String ccy) {
        CashBalance cb = cash.get(ccy);
        return cb == null ? BigDecimal.ZERO : cb.available();
    }

    public Position position(String symbol) {
        return positions.get(symbol);
    }

    public Collection<Position> positions() {
        return positions.values();
    }

    public Collection<CashBalance> cashBalances() {
        return cash.values();
    }
}
