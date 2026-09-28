package com.github.highcumontoa.backtestportfolioenginejava.service;

import com.github.highcumontoa.backtestportfolioenginejava.exception.InvalidArgumentApiException;
import com.github.highcumontoa.backtestportfolioenginejava.model.CostLot;
import com.github.highcumontoa.backtestportfolioenginejava.model.LotRealization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.concurrent.atomic.AtomicLong;

/**
 * 持仓批次与已实现盈亏台账（先进先出 FIFO）。
 *
 * <p>核算口径（金额一律 {@code moneyScale=2} 位、{@link RoundingMode#HALF_UP}，
 * 批次单位成本为展示值，保留 8 位）：
 * <ul>
 *   <li><b>买入开批</b>：每笔买入成交（含同一订单的每次部分成交）开一个独立批次，
 *       批次成本 = 成交额 + 买入佣金（买入费用归入对应批次）。</li>
 *   <li><b>卖出消耗</b>：按开仓时间 FIFO 消耗批次；一笔卖出跨多个批次时拆成多行
 *       {@link LotRealization}。佣金/税按各批次成交额占比分摊（尾批承接舍入差），
 *       已实现盈亏 = 成交额 − 消耗成本 − 分摊佣金 − 分摊税。</li>
 *   <li><b>拆股/合股</b>：每个未售尽批次数量乘 ratio、剩余总成本不变，单位成本随之等比缩小。</li>
 *   <li><b>现金分红</b>：按币种累计入当期分红收益，不触碰任何批次的数量与成本。</li>
 * </ul>
 * 每个 symbol 一把内部锁；与 {@link PortfolioService} 的持仓锁为不同对象，
 * PortfolioService 在持仓锁内调用本服务，不存在锁环。
 */
@Service
public class LotService {

    private static final Logger log = LoggerFactory.getLogger(LotService.class);

    private static final int MONEY_SCALE = 2;
    private static final int QTY_SCALE = 8;
    private static final int UNIT_SCALE = 8;

    private final ConcurrentMap<String, Ledger> ledgers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, BigDecimal> realizedByCcy = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, BigDecimal> dividendByCcyMap = new ConcurrentHashMap<>();
    private final AtomicLong lotSeq = new AtomicLong(0);

    public LotService() {
    }

    private Ledger ledger(String symbol) {
        return ledgers.computeIfAbsent(symbol, k -> new Ledger(k));
    }

    /** 买入成交开一个新成本批次（买入佣金归入批次成本）。 */
    public CostLot openBuy(String symbol, String currency, String execId, long eventTime,
                           BigDecimal quantity, BigDecimal price, BigDecimal commission) {
        require(symbol, currency, execId, quantity, price);
        BigDecimal fee = commission == null ? BigDecimal.ZERO : commission;
        BigDecimal qty = quantity.setScale(QTY_SCALE, RoundingMode.HALF_UP);
        BigDecimal gross = price.multiply(quantity).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal remainingCost = gross.add(fee).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        String lotId = "LOT-" + lotSeq.incrementAndGet();
        CostLot lot;
        synchronized (ledger(symbol)) {
            lot = ledger(symbol).open(lotId, currency, execId, eventTime, qty, remainingCost);
        }
        log.info("lot OPEN lotId={} symbol={} execId={} qty={} px={} fee={} cost={} {}",
                lotId, symbol, execId, qty, price, fee, remainingCost, currency);
        return lot;
    }

    /** 卖出成交按 FIFO 消耗批次，返回逐批次消耗与已实现盈亏明细（按 FIFO 顺序）。 */
    public List<LotRealization> closeSell(String symbol, String currency, String execId, long eventTime,
                                          BigDecimal quantity, BigDecimal price,
                                          BigDecimal commission, BigDecimal tax) {
        require(symbol, currency, execId, quantity, price);
        BigDecimal fee = commission == null ? BigDecimal.ZERO : commission;
        BigDecimal levy = tax == null ? BigDecimal.ZERO : tax;
        List<LotRealization> rows;
        BigDecimal totalPnl;
        synchronized (ledger(symbol)) {
            Ledger lg = ledger(symbol);
            if (quantity.compareTo(lg.openQuantity()) > 0) {
                throw new InvalidArgumentApiException("OVERSELL",
                        "sell " + quantity + " exceeds open lots " + lg.openQuantity() + " of " + symbol);
            }
            rows = lg.close(execId, currency, eventTime,
                    quantity.setScale(QTY_SCALE, RoundingMode.HALF_UP), price, fee, levy);
            totalPnl = rows.stream().map(LotRealization::realizedPnl)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
        realizedByCcy.merge(currency, totalPnl, BigDecimal::add);
        log.info("lot CLOSE symbol={} execId={} qty={} px={} lots={} realizedPnl={} {}",
                symbol, execId, quantity, price, rows.size(), totalPnl, currency);
        return List.copyOf(rows);
    }

    /** 拆股/合股：同步调整所有未售尽批次数量与单位成本，保持剩余总成本不变。 */
    public void applyRatio(String symbol, BigDecimal ratio) {
        if (symbol == null || ratio == null || ratio.signum() <= 0) {
            throw new InvalidArgumentApiException("INVALID_RATIO", "symbol and positive ratio required");
        }
        Ledger lg = ledgers.get(symbol);
        if (lg == null) {
            return;
        }
        synchronized (lg) {
            BigDecimal costBefore = lg.openCost();
            lg.applyRatio(ratio.setScale(QTY_SCALE, RoundingMode.HALF_UP));
            log.info("lot RATIO symbol={} ratio={} openCost {} -> {} qty -> {}",
                    symbol, ratio, costBefore, lg.openCost(), lg.openQuantity());
        }
    }

    /** 现金分红按生效时点持有数量计入当期收益（不改批次数量与成本）。 */
    public void addDividend(String currency, BigDecimal amount) {
        if (currency == null || currency.isBlank()) {
            throw new InvalidArgumentApiException("CCY_REQUIRED", "currency required");
        }
        if (amount == null || amount.signum() < 0) {
            throw new InvalidArgumentApiException("INVALID_DIVIDEND", "dividend amount invalid");
        }
        if (amount.signum() == 0) {
            return;
        }
        dividendByCcyMap.merge(currency,
                amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP), BigDecimal::add);
        log.info("lot DIVIDEND {} amount={} cumulative={}",
                currency, amount, dividendByCcyMap.get(currency));
    }

    /** 指定标的尚未售尽的批次（FIFO 顺序）。 */
    public List<CostLot> openLots(String symbol) {
        Ledger lg = ledgers.get(symbol);
        if (lg == null) {
            return List.of();
        }
        synchronized (lg) {
            return lg.openLots();
        }
    }

    /** 指定标的剩余批次成本之和。 */
    public BigDecimal openCost(String symbol) {
        Ledger lg = ledgers.get(symbol);
        if (lg == null) {
            return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
        synchronized (lg) {
            return lg.openCost();
        }
    }

    /** 指定标的全部卖出消耗明细（按发生顺序）。 */
    public List<LotRealization> realizations(String symbol) {
        Ledger lg = ledgers.get(symbol);
        if (lg == null) {
            return List.of();
        }
        synchronized (lg) {
            return List.copyOf(lg.history);
        }
    }

    /** 指定币种累计已实现盈亏（手续费/税已计入）。 */
    public BigDecimal realizedPnl(String currency) {
        return realizedByCcy.getOrDefault(currency,
                BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
    }

    /** 指定币种累计现金分红收益。 */
    public BigDecimal dividendIncome(String currency) {
        return dividendByCcyMap.getOrDefault(currency,
                BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
    }

    /** 各币种已实现盈亏快照（币种升序）。 */
    public Map<String, BigDecimal> realizedPnlByCcy() {
        Map<String, BigDecimal> snap = new LinkedHashMap<>();
        realizedByCcy.keySet().stream().sorted(Comparator.naturalOrder())
                .forEach(c -> snap.put(c, realizedByCcy.get(c)));
        return snap;
    }

    /** 各币种分红收益快照（币种升序）。 */
    public Map<String, BigDecimal> dividendByCcy() {
        Map<String, BigDecimal> snap = new LinkedHashMap<>();
        dividendByCcyMap.keySet().stream().sorted(Comparator.naturalOrder())
                .forEach(c -> snap.put(c, dividendByCcyMap.get(c)));
        return snap;
    }

    private static void require(String symbol, String currency, String execId,
                                BigDecimal qty, BigDecimal price) {
        if (symbol == null || symbol.isBlank() || currency == null || currency.isBlank()) {
            throw new InvalidArgumentApiException("LOT_FIELD_REQUIRED", "symbol and currency required");
        }
        if (execId == null || execId.isBlank()) {
            throw new InvalidArgumentApiException("LOT_EXEC_REQUIRED", "execId required");
        }
        if (qty == null || qty.signum() <= 0) {
            throw new InvalidArgumentApiException("LOT_QTY_INVALID", "quantity must be positive");
        }
        if (price == null || price.signum() <= 0) {
            throw new InvalidArgumentApiException("LOT_PRICE_INVALID", "price must be positive");
        }
    }

    /** 单 symbol 的 FIFO 批次台账，所有方法在 LotService 持本对象锁时调用。 */
    private static final class Ledger {
        private final String symbol;
        private final List<Entry> lots = new ArrayList<>();
        private final List<LotRealization> history = new ArrayList<>();

        private Ledger(String symbol) {
            this.symbol = symbol;
        }

        CostLot open(String lotId, String currency, String execId, long eventTime,
                     BigDecimal qty, BigDecimal remainingCost) {
            Entry e = new Entry(symbol, lotId, currency, execId, eventTime, qty, remainingCost);
            lots.add(e);
            return e.snapshot();
        }

        List<LotRealization> close(String execId, String currency, long eventTime,
                                   BigDecimal qty, BigDecimal price,
                                   BigDecimal commission, BigDecimal tax) {
            BigDecimal totalGross = price.multiply(qty).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            BigDecimal remaining = qty;
            BigDecimal grossAllocated = BigDecimal.ZERO;
            BigDecimal feeAllocated = BigDecimal.ZERO;
            BigDecimal levyAllocated = BigDecimal.ZERO;
            List<LotRealization> rows = new ArrayList<>();

            for (int i = 0; i < lots.size() && remaining.signum() > 0; i++) {
                Entry e = lots.get(i);
                if (e.remainingQty.signum() == 0) {
                    continue;
                }
                BigDecimal take = remaining.min(e.remainingQty);
                boolean lastLotSlice = false;
                // 先按单价算归属成交额；若是本次卖出消耗的最后一片，尾片承接全部舍入差。
                BigDecimal gross = price.multiply(take).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                BigDecimal costBasis;
                boolean fullyConsume = take.compareTo(e.remainingQty) == 0;
                if (fullyConsume) {
                    costBasis = e.remainingCost.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                } else {
                    costBasis = e.remainingCost.multiply(take)
                            .divide(e.remainingQty, MONEY_SCALE, RoundingMode.HALF_UP);
                }

                // 先记录，尾片在循环结束后统一校正成交额与分摊费用，保证各合计与整笔卖出分毫不差。
                LotRealization tentative = new LotRealization(execId, symbol, currency,
                        e.lotId, eventTime, take, costBasis, gross,
                        BigDecimal.ZERO.setScale(MONEY_SCALE), BigDecimal.ZERO.setScale(MONEY_SCALE),
                        BigDecimal.ZERO.setScale(MONEY_SCALE));
                rows.add(tentative);
                grossAllocated = grossAllocated.add(gross);
                remaining = remaining.subtract(take);
                if (remaining.signum() == 0) {
                    lastLotSlice = true;
                }

                // 更新批次数量与剩余成本（最后整批消耗时成本清零，杜绝尾差）。
                e.remainingQty = e.remainingQty.subtract(take);
                if (fullyConsume) {
                    e.remainingCost = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                } else {
                    e.remainingCost = e.remainingCost.subtract(costBasis)
                            .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                }
                if (lastLotSlice) {
                    break;
                }
            }
            if (remaining.signum() > 0) {
                throw new InvalidArgumentApiException("OVERSELL",
                        "sell exceeds open lots of " + symbol);
            }

            // 尾片承接成交额舍入差，使各批次成交额合计 == 整笔成交额。
            if (!rows.isEmpty()) {
                int last = rows.size() - 1;
                LotRealization tail = rows.get(last);
                BigDecimal tailGross = totalGross.subtract(
                        rows.subList(0, last).stream()
                                .map(LotRealization::grossProceeds)
                                .reduce(BigDecimal.ZERO, BigDecimal::add))
                        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);

                // 佣金/税按成交额占比分摊，尾片承接舍入差。
                List<LotRealization> allocated = new ArrayList<>(rows.size());
                BigDecimal feeAcc = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                BigDecimal levyAcc = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                for (int i = 0; i < rows.size(); i++) {
                    LotRealization r = rows.get(i);
                    BigDecimal g = i == last ? tailGross : r.grossProceeds();
                    BigDecimal feePart;
                    BigDecimal levyPart;
                    if (i == last) {
                        feePart = commission.subtract(feeAcc).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                        levyPart = tax.subtract(levyAcc).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                    } else {
                        feePart = allocate(g, totalGross, commission);
                        levyPart = allocate(g, totalGross, tax);
                        feeAcc = feeAcc.add(feePart);
                        levyAcc = levyAcc.add(levyPart);
                    }
                    BigDecimal pnl = g.subtract(r.costBasis()).subtract(feePart).subtract(levyPart)
                            .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                    LotRealization done = new LotRealization(r.execId(), r.symbol(), r.currency(),
                            r.lotId(), r.eventTime(), r.quantity(), r.costBasis(), g,
                            feePart, levyPart, pnl);
                    allocated.add(done);
                    history.add(done);
                }
                return allocated;
            }
            return rows;
        }

        private static BigDecimal allocate(BigDecimal partGross, BigDecimal totalGross, BigDecimal totalFee) {
            if (totalFee.signum() == 0 || totalGross.signum() == 0) {
                return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            }
            return totalFee.multiply(partGross)
                    .divide(totalGross, MONEY_SCALE, RoundingMode.HALF_UP);
        }

        void applyRatio(BigDecimal ratio) {
            for (Entry e : lots) {
                if (e.remainingQty.signum() == 0) {
                    continue;
                }
                // 数量等比调整；剩余总成本保持不变，单位成本随之等比缩小。
                e.remainingQty = e.remainingQty.multiply(ratio)
                        .setScale(QTY_SCALE, RoundingMode.HALF_UP);
            }
        }

        BigDecimal openQuantity() {
            return lots.stream().map(e -> e.remainingQty).reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(QTY_SCALE, RoundingMode.HALF_UP);
        }

        BigDecimal openCost() {
            return lots.stream().map(e -> e.remainingCost).reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }

        List<CostLot> openLots() {
            List<CostLot> out = new ArrayList<>();
            for (Entry e : lots) {
                if (e.remainingQty.signum() > 0) {
                    out.add(e.snapshot());
                }
            }
            return List.copyOf(out);
        }

        /** 可变批次条目：剩余数量 + 精确剩余成本（金额口径），单位成本由二者派生。 */
        private static final class Entry {
            private final String symbol;
            private final String lotId;
            private final String currency;
            private final String execId;
            private final long openTime;
            private final BigDecimal originalQty;
            private BigDecimal remainingQty;
            private BigDecimal remainingCost;

            private Entry(String symbol, String lotId, String currency, String execId, long openTime,
                          BigDecimal qty, BigDecimal remainingCost) {
                this.symbol = symbol;
                this.lotId = lotId;
                this.currency = currency;
                this.execId = execId;
                this.openTime = openTime;
                this.originalQty = qty;
                this.remainingQty = qty;
                this.remainingCost = remainingCost;
            }

            private BigDecimal unitCost() {
                if (remainingQty.signum() == 0) {
                    return BigDecimal.ZERO.setScale(UNIT_SCALE, RoundingMode.HALF_UP);
                }
                return remainingCost.divide(remainingQty, UNIT_SCALE, RoundingMode.HALF_UP);
            }

            CostLot snapshot() {
                return new CostLot(lotId, symbol, currency, execId, openTime,
                        originalQty, remainingQty, unitCost());
            }
        }
    }
}
