package com.github.highcumontoa.backtestportfolioenginejava.service;

import com.github.highcumontoa.backtestportfolioenginejava.config.CostConfig;
import com.github.highcumontoa.backtestportfolioenginejava.exception.InvalidArgumentApiException;
import com.github.highcumontoa.backtestportfolioenginejava.model.Fill;
import com.github.highcumontoa.backtestportfolioenginejava.model.Position;
import com.github.highcumontoa.backtestportfolioenginejava.model.Side;
import com.github.highcumontoa.backtestportfolioenginejava.model.lot.LotView;
import com.github.highcumontoa.backtestportfolioenginejava.model.lot.RealizedPnl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

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
    private final ConcurrentMap<String, BigDecimal> cash = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, BigDecimal> reserved = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> cashLocks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Position> positions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> positionLocks = new ConcurrentHashMap<>();

    /** FIFO 批次台账（只在逐标的持仓锁内变更）。 */
    private final LotLedger lotLedger = new LotLedger();
    /** 逐笔卖出已实现盈亏（含费用、税），按成交事件时间追加。 */
    private final List<RealizedPnl> realizedPnls = new CopyOnWriteArrayList<>();
    /** 分币种累计已实现盈亏（净额，含卖出佣金与税）。 */
    private final ConcurrentMap<String, BigDecimal> realizedByCcy = new ConcurrentHashMap<>();
    /** 分币种累计现金分红（税前）。 */
    private final ConcurrentMap<String, BigDecimal> dividendsByCcy = new ConcurrentHashMap<>();
    /** 批次自增序号（与 execId 组合保证 lotId 唯一可复现）。 */
    private final java.util.concurrent.atomic.AtomicLong lotSeq =
            new java.util.concurrent.atomic.AtomicLong(0);

    @Autowired
    public PortfolioService() {
        this(CostConfig.DEFAULT);
    }

    public PortfolioService(CostConfig config) {
        this.config = config;
    }

    private Object cashLock(String ccy) {
        return cashLocks.computeIfAbsent(ccy, k -> new Object());
    }

    Object positionLock(String symbol) {
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

    /**
     * 买入成交结算时，把对应毛成交额从“预占”划转为“已支出”。
     * 现金的实际扣减已在 {@link #applyFill} 完成，这里只下调预占；
     * 成交费用部分继续保留在预占中，待部分成交校准/撤单/全部成交时统一释放。
     * 释放额取本笔毛额与当前预占的较小值，保证预留账永不出现负数。
     */
    public void settleReservedCash(String currency, BigDecimal grossAmount) {
        requireCcy(currency);
        if (grossAmount == null || grossAmount.signum() <= 0) {
            return;
        }
        synchronized (cashLock(currency)) {
            BigDecimal cur = reservedCash(currency);
            BigDecimal take = grossAmount.min(cur);
            if (take.signum() > 0) {
                reserved.put(currency, cur.subtract(take));
                log.debug("settleReservedCash ccy={} gross={} settled={} reservedNow={}",
                        currency, grossAmount, take, reservedCash(currency));
            }
        }
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
     * 买入：先按成交额释放预占，再扣减含费现金、增加持仓，并开一个独立成本批次；
     * 卖出：减持仓（数量已冻结）、按 FIFO 消耗批次并登记已实现盈亏，按扣费税后净额增加现金。
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
                // 本笔支出在下单时已按最坏情形预占（含费、含滑点），因此结算只要求现金本身
                // 不被打成负数；不能再用“现金 - 全部预占”校验，否则大额单第一笔成交就会
                // 因为剩余数量的预占仍挂着而被误判 INSUFFICIENT_FUNDS（小额现金充裕时会被掩盖）。
                if (out.compareTo(cash(currency)) > 0) {
                    throw new InvalidArgumentApiException("INSUFFICIENT_FUNDS",
                            "settlement needs " + out + " but cash " + cash(currency));
                }
                cash.put(currency, cash(currency).subtract(out));
            }
            synchronized (positionLock(fill.symbol())) {
                Position p = positions.computeIfAbsent(fill.symbol(),
                        s -> new Position(s, currency));
                p.applyTrade(Side.BUY, fill.quantity(), fill.price(), fill.commission(), fill.tax());
                // 每笔买入成交 = 一个独立批次；同一大单多次部分成交 -> 多个批次。
                String lotId = "LOT-" + lotSeq.incrementAndGet() + "-" + fill.execId();
                lotLedger.openLot(lotId, fill.symbol(), fill.execId(), fill.orderId(),
                        fill.eventTime(), fill.quantity(), fill.price(),
                        nz(fill.commission()), nz(fill.tax()), config.moneyScale());
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
                // 先按 FIFO 消耗批次（数量不足会抛 LOT_OVERSELL），再改 Position，
                // 保证批次剩余数量与持仓数量永远一致。
                LotLedger.SellConsumption consumption = lotLedger.consumeFifo(
                        fill.symbol(), fill.quantity(), fill.price(), config.moneyScale());
                p.applyTrade(Side.SELL, fill.quantity(), fill.price(), fill.commission(), fill.tax());
                recordRealizedPnl(fill, currency, gross, consumption);
            }
            BigDecimal in = gross.subtract(nz(fill.commission())).subtract(nz(fill.tax()));
            synchronized (cashLock(currency)) {
                cash.merge(currency, in, BigDecimal::add);
            }
            log.info("settle SELL symbol={} qty={} px={} in={} ccy={} cashNow={}",
                    fill.symbol(), fill.quantity(), fill.price(), in, currency, cash(currency));
        }
    }

    /** 登记一笔卖出的已实现盈亏：毛盈亏 = 毛收入 - FIFO 成本；净盈亏再扣卖出佣金与税。 */
    private void recordRealizedPnl(Fill fill, String currency, BigDecimal gross,
                                   LotLedger.SellConsumption consumption) {
        BigDecimal costBasis = consumption.costBasis();
        BigDecimal commission = nz(fill.commission());
        BigDecimal tax = nz(fill.tax());
        BigDecimal grossPnl = gross.subtract(costBasis)
                .setScale(config.moneyScale(), RoundingMode.HALF_UP);
        BigDecimal netPnl = grossPnl.subtract(commission).subtract(tax)
                .setScale(config.moneyScale(), RoundingMode.HALF_UP);
        RealizedPnl pnl = new RealizedPnl(
                fill.execId(), fill.orderId(), fill.symbol(), currency, fill.eventTime(),
                fill.quantity(), fill.price(), gross, costBasis, commission, tax,
                grossPnl, netPnl, consumption.matches());
        realizedPnls.add(pnl);
        realizedByCcy.merge(currency, netPnl, BigDecimal::add);
        log.info("realized pnl execId={} symbol={} qty={} gross={} cost={} comm={} tax={} net={}",
                fill.execId(), fill.symbol(), fill.quantity(), gross, costBasis,
                commission, tax, netPnl);
    }

    /**
     * 现金分红派现（公司行为专用入口）：按生效时点持仓数量计入当期分红收益，
     * 增加现金但不改动批次数量与剩余成本。幂等由 CorporateActionService 按事件 id 保证。
     */
    public void payDividend(String currency, BigDecimal proceeds) {
        requireCcy(currency);
        if (proceeds == null || proceeds.signum() < 0) {
            throw new InvalidArgumentApiException("INVALID_DIVIDEND", "dividend proceeds invalid");
        }
        if (proceeds.signum() == 0) {
            return;
        }
        synchronized (cashLock(currency)) {
            cash.merge(currency, proceeds, BigDecimal::add);
            dividendsByCcy.merge(currency, proceeds, BigDecimal::add);
        }
        log.info("dividend paid ccy={} proceeds={} cashNow={}", currency, proceeds, cash(currency));
    }

    /**
     * 拆股/合股调整批次（公司行为专用入口）：所有未售完批次数量乘 ratio、
     * 批次剩余成本不变。调用方必须已持有该标的的持仓锁
     * （{@link CorporateActionService} 与 {@link Position#applyRatio} 在同一临界区内成对调用）。
     */
    public void adjustLotsForRatio(String symbol, BigDecimal ratio) {
        lotLedger.applyRatio(symbol, ratio);
    }

    public Position position(String symbol) {
        return positions.get(symbol);
    }

    public Map<String, Position> positions() {
        return Map.copyOf(positions);
    }

    /** 某标的未售完批次（FIFO 顺序）的只读快照。 */
    public List<LotView> openLots(String symbol) {
        return lotLedger.openLots(symbol);
    }

    /** 全部标的未售完批次（按 symbol 字典序、标的内 FIFO 顺序）。 */
    public Map<String, List<LotView>> allOpenLots() {
        return lotLedger.allOpenLots();
    }

    /** 批次台账口径的剩余成本合计（含归属买入费用），按标的。 */
    public BigDecimal lotRemainingCost(String symbol) {
        synchronized (positionLock(symbol)) {
            return lotLedger.remainingCost(symbol);
        }
    }

    /** 逐笔卖出已实现盈亏（按成交事件时间顺序）。 */
    public List<RealizedPnl> realizedPnls() {
        return new ArrayList<>(realizedPnls);
    }

    /** 某标的的逐笔已实现盈亏。 */
    public List<RealizedPnl> realizedPnls(String symbol) {
        return realizedPnls.stream().filter(p -> p.symbol().equals(symbol)).toList();
    }

    /** 分币种累计已实现盈亏（净额，含卖出佣金与税）。 */
    public Map<String, BigDecimal> realizedPnlByCurrency() {
        Map<String, BigDecimal> snapshot = new LinkedHashMap<>();
        realizedByCcy.keySet().stream().sorted(Comparator.naturalOrder())
                .forEach(c -> snapshot.put(c, realizedByCcy.get(c)));
        return snapshot;
    }

    public BigDecimal realizedPnl(String currency) {
        return realizedByCcy.getOrDefault(currency, BigDecimal.ZERO);
    }

    /** 分币种累计现金分红（税前）。 */
    public Map<String, BigDecimal> dividendsByCurrency() {
        Map<String, BigDecimal> snapshot = new LinkedHashMap<>();
        dividendsByCcy.keySet().stream().sorted(Comparator.naturalOrder())
                .forEach(c -> snapshot.put(c, dividendsByCcy.get(c)));
        return snapshot;
    }

    public BigDecimal dividends(String currency) {
        return dividendsByCcy.getOrDefault(currency, BigDecimal.ZERO);
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
