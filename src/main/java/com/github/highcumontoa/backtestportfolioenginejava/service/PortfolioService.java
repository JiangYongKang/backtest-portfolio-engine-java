package com.github.highcumontoa.backtestportfolioenginejava.service;

import com.github.highcumontoa.backtestportfolioenginejava.config.CostConfig;
import com.github.highcumontoa.backtestportfolioenginejava.exception.InvalidArgumentApiException;
import com.github.highcumontoa.backtestportfolioenginejava.model.CostLot;
import com.github.highcumontoa.backtestportfolioenginejava.model.Fill;
import com.github.highcumontoa.backtestportfolioenginejava.model.LotRealization;
import com.github.highcumontoa.backtestportfolioenginejava.model.Position;
import com.github.highcumontoa.backtestportfolioenginejava.model.Side;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 组合账户：多币种现金、持仓与冻结/预占。
 *
 * <p>并发与守恒：
 * <ul>
 *   <li>买入：下单时按“最坏情形”足额 {@link #reserveCash}（金额+预估费用）；成交时从预占中
 *       划转实际支出（含佣金/税），撤单/尾量释放未用预占。可用资金 = cash - reserved。</li>
 *   <li>卖出：下单时 {@link #freezePosition} 冻结可卖数量，可用不足即拒（不超卖）；
 *       成交减持仓、增加现金（扣费税后净额），撤单释放冻结。</li>
 *   <li>每币种独立锁、持仓独立锁；现金变动与持仓变动成对发生，避免重复扣减或漏扣。</li>
 * </ul>
 */
@Service
public class PortfolioService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioService.class);

    private final CostConfig config;
    private final LotService lotService;
    private final ConcurrentMap<String, BigDecimal> cash = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, BigDecimal> reserved = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> cashLocks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Position> positions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> positionLocks = new ConcurrentHashMap<>();

    @Autowired
    public PortfolioService() {
        this(CostConfig.DEFAULT, new LotService());
    }

    public PortfolioService(CostConfig config) {
        this(config, new LotService());
    }

    public PortfolioService(CostConfig config, LotService lotService) {
        this.config = config;
        this.lotService = lotService;
    }

    /** FIFO 批次与已实现盈亏台账（供公司行为/估值层与查询使用）。 */
    public LotService lotService() {
        return lotService;
    }

    private Object cashLock(String ccy) {
        return cashLocks.computeIfAbsent(ccy, k -> new Object());
    }

    private Object positionLock(String symbol) {
        return positionLocks.computeIfAbsent(symbol, k -> new Object());
    }

    public CostConfig getConfig() {
        return config;
    }

    public void deposit(String currency, BigDecimal amount) {
        requireCcy(currency);
        if (amount == null || amount.signum() <= 0) {
            throw new InvalidArgumentApiException("INVALID_DEPOSIT", "deposit amount must be positive");
        }
        synchronized (cashLock(currency)) {
            cash.merge(currency, amount, BigDecimal::add);
            log.info("deposit ccy={} amount={} balance={}", currency, amount, cash(currency));
        }
    }

    public BigDecimal cash(String currency) {
        return cash.getOrDefault(currency, BigDecimal.ZERO);
    }

    /** 可用资金 = 现金 - 预占。 */
    public BigDecimal availableCash(String currency) {
        synchronized (cashLock(currency)) {
            return cash(currency).subtract(reservedCash(currency));
        }
    }

    public Map<String, BigDecimal> allCash() {
        Map<String, BigDecimal> snapshot = new LinkedHashMap<>();
        cash.keySet().stream().sorted(Comparator.naturalOrder())
                .forEach(c -> snapshot.put(c, cash(c)));
        return snapshot;
    }

    /** 买入下单预占资金；可用不足抛 INSUFFICIENT_FUNDS。 */
    public void reserveCash(String currency, BigDecimal amount) {
        requireCcy(currency);
        if (amount == null || amount.signum() < 0) {
            throw new InvalidArgumentApiException("INVALID_RESERVE", "reserve amount invalid");
        }
        synchronized (cashLock(currency)) {
            BigDecimal avail = cash(currency).subtract(reservedCash(currency));
            if (amount.compareTo(avail) > 0) {
                log.warn("reserve rejected insufficient funds ccy={} need={} available={}",
                        currency, amount, avail);
                throw new InvalidArgumentApiException("INSUFFICIENT_FUNDS",
                        "need " + amount + " but available " + avail + " " + currency);
            }
            reserved.merge(currency, amount, BigDecimal::add);
            log.debug("reserveCash ccy={} amount={} reservedNow={}", currency, amount, reservedCash(currency));
        }
    }

    public void releaseCash(String currency, BigDecimal amount) {
        requireCcy(currency);
        if (amount == null || amount.signum() < 0) {
            throw new InvalidArgumentApiException("INVALID_RELEASE", "release amount invalid");
        }
        synchronized (cashLock(currency)) {
            BigDecimal cur = reservedCash(currency);
            if (amount.compareTo(cur) > 0) {
                throw new InvalidArgumentApiException("RELEASE_EXCEEDS_RESERVED",
                        "release " + amount + " exceeds reserved " + cur);
            }
            reserved.put(currency, cur.subtract(amount));
            log.debug("releaseCash ccy={} amount={} reservedNow={}", currency, amount, reservedCash(currency));
        }
    }

    public BigDecimal reservedCash(String currency) {
        return reserved.getOrDefault(currency, BigDecimal.ZERO);
    }

    public void freezePosition(String symbol, BigDecimal quantity) {
        if (quantity == null || quantity.signum() < 0) {
            throw new InvalidArgumentApiException("INVALID_FREEZE", "freeze quantity invalid");
        }
        if (quantity.signum() == 0) {
            return;
        }
        synchronized (positionLock(symbol)) {
            Position p = positions.get(symbol);
            if (p == null || quantity.compareTo(p.getAvailableQuantity()) > 0) {
                BigDecimal avail = p == null ? BigDecimal.ZERO : p.getAvailableQuantity();
                log.warn("freeze rejected insufficient position symbol={} need={} available={}",
                        symbol, quantity, avail);
                throw new InvalidArgumentApiException("INSUFFICIENT_POSITION",
                        "need " + quantity + " but available " + avail + " of " + symbol);
            }
            p.freeze(quantity);
        }
    }

    public void releasePosition(String symbol, BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            return;
        }
        synchronized (positionLock(symbol)) {
            Position p = positions.get(symbol);
            if (p != null) {
                p.releaseFreeze(quantity);
            }
        }
    }

    /**
     * 成交入账（在回测引擎统一编排下调用）。
     * 买入：先按成交额释放预占，再扣减含费现金、增加持仓；
     * 卖出：减持仓（数量已冻结）、按扣费税后净额增加现金。
     * 现金与持仓成对更新，幂等由 FillService/引擎按 execId 保证，本方法不重复入账。
     */
    public void applyFill(Fill fill, String currency) {
        if (fill == null) {
            throw new InvalidArgumentApiException("INVALID_FILL", "fill required");
        }
        requireCcy(currency);
        BigDecimal gross = fill.price().multiply(fill.quantity())
                .setScale(config.moneyScale(), java.math.RoundingMode.HALF_UP);
        if (fill.side() == Side.BUY) {
            // 预占在下单时已冻结可用资金；结算时只按实际支出（含费）扣减现金，
            // 预占的差额释放由调用方（引擎）在成交后统一处理，避免重复/漏扣。
            BigDecimal out = gross.add(nz(fill.commission())).add(nz(fill.tax()));
            synchronized (cashLock(currency)) {
                BigDecimal available = cash(currency).subtract(reservedCash(currency));
                if (out.compareTo(available) > 0) {
                    throw new InvalidArgumentApiException("INSUFFICIENT_FUNDS",
                            "settlement needs " + out + " but available " + available);
                }
                cash.put(currency, cash(currency).subtract(out));
            }
            synchronized (positionLock(fill.symbol())) {
                Position p = positions.computeIfAbsent(fill.symbol(),
                        s -> new Position(s, currency));
                p.applyTrade(Side.BUY, fill.quantity(), fill.price(), fill.commission(), fill.tax());
                // 每笔买入成交（含部分成交）开一个独立成本批次，买入佣金归入批次。
                lotService.openBuy(fill.symbol(), currency, fill.execId(), fill.eventTime(),
                        fill.quantity(), fill.price(), nz(fill.commission()));
            }
            log.info("settle BUY symbol={} qty={} px={} out={} ccy={} cashNow={}",
                    fill.symbol(), fill.quantity(), fill.price(), out, currency, cash(currency));
        } else {
            synchronized (positionLock(fill.symbol())) {
                Position p = positions.get(fill.symbol());
                if (p == null) {
                    throw new InvalidArgumentApiException("INSUFFICIENT_POSITION",
                            "sell with no position of " + fill.symbol());
                }
                p.applyTrade(Side.SELL, fill.quantity(), fill.price(), fill.commission(), fill.tax());
                // 按 FIFO 消耗批次，产出逐笔已实现盈亏（佣金/税分摊到批次）。
                lotService.closeSell(fill.symbol(), currency, fill.execId(), fill.eventTime(),
                        fill.quantity(), fill.price(), nz(fill.commission()), nz(fill.tax()));
            }
            BigDecimal in = gross.subtract(nz(fill.commission())).subtract(nz(fill.tax()));
            synchronized (cashLock(currency)) {
                cash.merge(currency, in, BigDecimal::add);
            }
            log.info("settle SELL symbol={} qty={} px={} in={} ccy={} cashNow={}",
                    fill.symbol(), fill.quantity(), fill.price(), in, currency, cash(currency));
        }
    }

    public Position position(String symbol) {
        return positions.get(symbol);
    }

    /**
     * 拆股/合股：在持仓锁内同步调整加权口径持仓与 FIFO 批次，
     * 数量等比变化、单位成本等比缩小，持仓总成本与批次剩余总成本均保持不变。
     */
    public void applySplitRatio(String symbol, java.math.BigDecimal ratio) {
        synchronized (positionLock(symbol)) {
            Position p = positions.get(symbol);
            if (p != null) {
                p.applyRatio(ratio);
            }
            lotService.applyRatio(symbol, ratio);
        }
    }

    /**
     * 现金分红入账：增加现金，并按币种计入分红收益（当期收益）；
     * 不触碰持仓与批次的数量、剩余成本。
     */
    public void payDividend(String currency, java.math.BigDecimal amount) {
        lotService.addDividend(currency, amount);
        deposit(currency, amount);
    }

    public Map<String, Position> positions() {
        return Map.copyOf(positions);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static void requireCcy(String currency) {
        if (currency == null || currency.isBlank()) {
            throw new InvalidArgumentApiException("CCY_REQUIRED", "currency required");
        }
    }
}
