# 本地事件驱动回测与组合估值服务 — 设计与验证说明

本服务用**本地行情**与**本地成交回报**，模拟“下单 → 撮合 → 成交回报 → 资金/持仓出账 → 组合估值 → 绩效指标”的完整链路。
所有结论都以**事件时间（eventTime）**为准，与数据到达顺序无关，因此可复现；重复、乱序、并发等边界均给出确定结论。

---

## 1. 模块与职责

| 组件 | 职责 |
| --- | --- |
| `MarketDataService` | 行情接入；按 `symbol` 维护事件时间有序历史；floor 查询无未来函数；去重、防回退、识别缺失与停牌 |
| `FxRateService` | 汇率接入；按货币对维护事件时间历史；同币种恒为 1；过期判定交给估值层 |
| `OrderService` | 订单生命周期；`clientOrderId` 幂等键；状态机迁移与唯一终态 |
| `MatchingService` | 撮合判定（市价/限价、停牌、无价、价格穿越）与参考价、可成交量 |
| `FillService` | 滑点/佣金/最低佣金/税的逐笔成本核算；`execId` 幂等去重 |
| `LotLedger` / `Lot` | FIFO 持仓批次台账：一笔买入成交一个批次，卖出按先进先出消耗并核销成本 |
| `PortfolioService` | 多币种现金、持仓、买入资金预占、卖出持仓冻结；批次开批/核销、已实现盈亏与分红台账；临界区保证不超卖/不重复占用 |
| `CorporateActionService` | 拆股、合股、现金分红；按事件 id 幂等；拆合股同步调整批次 |
| `ValuationService` | 多币种折算估值；价格/汇率缺失与过期显式标记；固定精度与舍入 |
| `PerformanceService` | 累计收益、年化波动率、最大回撤、95% VaR/ES；样本不足/零波动显式结论 |
| `BacktestEngine` | 事件驱动编排门面，统一推进事件时间并串联上述服务 |

---

## 2. 事件时间与行情规则（结论与到达顺序无关）

- 每条行情/汇率带 `eventTime`，存储为按时间排序的历史；查询使用 **floor 语义**：
  只可见 `eventTime <= asOfTime` 的最新一条，**没有未来函数**。
- **乱序到达**：晚到的更老快照会被“补录”到历史（用于正确的 floor 回放），但永远不会成为当前最新值，
  因此**不会造成价格回退或重复成交**。
- **重复**：相同 `symbol + eventTime` 且字段一致，幂等忽略（不重复计数、不重复驱动）。
- **同刻冲突**：相同 `symbol + eventTime` 但价格不同，拒绝后到者并记录，保留先到定义（结果可复现）。
- **缺失与停牌显式区分**，绝不按 0 或旧价静默估值：
  - 该标的从无快照 → `PRICE_MISSING`；
  - 最新可见快照 `suspended=true` 或 `last=null` → `PRICE_STALE_SUSPENDED`。
- 全局水位线 `watermark` 为已接收快照的最大事件时间。

---

## 3. 订单生命周期

合法迁移（非法迁移抛 `STATE_CONFLICT / ILLEGAL_TRANSITION`）：

```
NEW ──fill──> PARTIALLY_FILLED ──fill──> FILLED   （终态）
  │                │
  ├──cancel───────> CANCELLED                     （终态，可带已成交量）
  └──reject───────> REJECTED                      （终态，带 RejectReason）
```

- `clientOrderId` 是**幂等键**：并发/重复提交只创建一次订单，其余返回同一订单（`created=false`）。
- 成交数量必须为正且不超过剩余数量；均价按成交量加权。
- 拒单原因 `RejectReason` 可区分：`INSUFFICIENT_FUNDS`、`INSUFFICIENT_POSITION`、
  `MARKET_CLOSED`、`INVALID_LIMIT_PRICE`、`INVALID_QUANTITY`、`RISK_LIMIT_EXCEEDED` 等。
- **成交回报幂等**：以 `execId` 去重，同一回报重复消费只入账一次，现金与持仓不重复变动。

---

## 4. 撮合与成本模型

### 4.1 撮合
- 参考价：**买入取 ask、卖出取 bid**；对手价缺失回退 `last`；都没有则不可成交。
- 停牌一律不可成交；无价不可成交。
- 限价穿越：买入 `参考价 <= limit`，卖出 `参考价 >= limit`。
- 可成交量：默认流动性充足即全部成交；可通过
  `BacktestEngine.addLiquidity(symbol, eventTime, qty)` 指定某时刻可见量，
  撮合量取 `min(订单剩余量, 可见量)`，从而复现**部分成交**；后续行情时点继续撮合剩余量。
- 市价单在 `runTo` 结束时仍无法成交（停牌/无价）→ 拒单 `MARKET_CLOSED` 并释放占用；
  限价单未成交则挂起（GTC），等待后续行情，直到成交或撤单。
- 同一订单在同一事件时间只撮合一次（记录最后撮合时间），避免下单路径与推进路径重复成交。

### 4.2 成本（逐笔可追溯，`CostBreakdown`）
默认参数（可通过 `CostConfig` 覆盖）：

| 参数 | 默认值 | 含义 |
| --- | --- | --- |
| `slippageRate` | 0.0005 | 滑点率：买价上浮、卖价下浮 |
| `commissionRate` | 0.0003 | 佣金率（按成交额） |
| `minCommission` | 5.00 | 单笔最低佣金 |
| `taxRate` | 0.001 | 印花/交易税（**仅卖出**） |
| `priceScale` | 4 | 价格小数位 |
| `moneyScale` | 2 | 金额/资金小数位 |

- 含滑点成交价：买 `ref*(1+slip)`，卖 `ref*(1-slip)`，按价格精度 `HALF_UP`。
- 佣金 `max(成交额 * commissionRate, minCommission)`；税费仅卖出收取。
- 净现金流：买 `-(成交额+佣金+税)`，卖 `+(成交额-佣金-税)`。

### 4.3 资金与持仓守恒
- **买入**：下单时按最坏情形（限价用限价、市价用当刻价，含费、含滑点估算）**足额预占现金**；
  **每笔成交结算时把该笔实际支出（成交额+佣金+税）从预占划转**（现金同步扣减），
  随后把该订单预占重算为“剩余量最坏成本”，只向下释放差额（幂等，重复校准不会多放）；
  撤单/拒单/全部成交释放全部剩余预占。逐订单台账保证**只释放一次**，且任意时点 `reserved ≤ cash`，
  大额单多次部分成交也不会把可用资金“锁死”或漏释放。
- **卖出**：下单时**冻结可卖数量**，可用不足直接拒单（不超卖）；成交减持仓、按 FIFO 核销批次、
  登记已实现盈亏，并按税后净额增加现金；撤单释放未成交部分冻结。
- 现金、持仓与批次在各自锁内成对更新；边界测试断言
  “现金 = 入金 − 批次剩余成本 + 已实现盈亏 + 分红”。

---

## 5. 持仓批次（Lot）与已实现盈亏（FIFO）

在原“单一加权平均成本”之外，新增一层**逐笔可对账的批次台账**，两套口径并存、互不影响。

### 5.1 开批
- **每一笔买入成交 = 一个独立批次**；一笔大单被拆成多次部分成交时，每笔回报各自开批
  （`lotId = LOT-<序号>-<execId>`，序号在引擎内单调，保证同一份输入重放结果一致）。
- 批次成本（成本池）= 成交价 × 数量 **+ 买入佣金 + 买入税**，即买入费用归到对应批次。
- 批次按建批先后进入该标的的 **FIFO 队列**；查询接口
  `PortfolioService.openLots(symbol)` / `allOpenLots()` 返回只读快照 `LotView`
  （剩余数量、剩余成本、累计已消耗成本、动态单位成本 = 剩余成本/剩余数量，8 位 HALF_UP）。

### 5.2 卖出消耗（先进先出）与已实现盈亏
- 卖出从队首批次依次消耗，耗尽的批次出队；每笔卖出产生一条 `RealizedPnl`：
  - `costBasis`：本次 FIFO 核销的批次成本合计（`matches` 给出每批消耗的数量/成本/对应毛收入）；
  - `proceeds` = 卖出成交价 × 数量；
  - `grossPnl = proceeds − costBasis`；
  - **`netPnl = grossPnl − 卖出佣金 − 卖出税`**（手续费与税计入当期已实现盈亏）。
- **尾差处理**：部分消耗按单位成本（8 位）折算到 `moneyScale`；整批清空时把批次剩余成本
  全部带走；卖出毛收入拆分明细时尾差归最后一条。因此批次全部卖完后，
  “累计核销成本”恰好等于“建批成本之和”，不会残留分币尾差。
- 批次核销与 `Position` 数量更新、现金增减在**同一把逐标的/逐币种锁内成对完成**，
  批次剩余数量与持仓数量永远一致；超卖在冻结阶段（`INSUFFICIENT_POSITION`）与
  批次核销阶段（`LOT_OVERSELL`）双重拦截。

### 5.3 对账恒等式（单币种）
```
现金        = 入金 − 批次剩余成本 + 累计已实现盈亏(netPnl) + 累计现金分红
NAV         = 现金 + 持仓市值
未实现盈亏   = 持仓市值 − 批次剩余成本
总盈亏       = 已实现 + 未实现 + 分红 = NAV − 入金
```
已实现盈亏与分红按币种分别累计，估值时再按汇率折算到基础币种（见第 7 节）。

### 5.4 大额订单的资金占用（多算/漏算防护）
买入下单按最坏情形足额预占；**每笔成交结算时把该笔实际支出（毛额+佣金+税）从预占划转**，
随后把台账重算为“剩余数量最坏成本”并只向下释放差额（幂等）；撤单/全部成交释放全部剩余预占。
因此任何时点 `reserved ≤ cash`，即使一笔买单占掉账户绝大部分可用资金、跨多次部分成交，
也不会出现“现金对不上、预占漏释放、成交被误判资金不足”。这一路径由
`LargeOrderLotFlowTest` / `LargeOrderCancelFlowTest` 覆盖。

### 5.5 与原加权平均成本口径的兼容范围
- `Position.avgCost / totalCost()` **保持原语义不变**（买入费用计入加权成本，卖出不改变单位成本），
  原有依赖该口径的接口与用例全部不受影响；估值结果中以 `totalCostBase` 继续暴露该口径。
- 批次口径是**更细的并列视图**：估值结果同时给出 `totalLotCostBase`（批次剩余成本）、
  `totalRealizedPnlBase`、`totalUnrealizedPnlBase`、`totalDividendBase`、`totalPnlBase`。
- 两者数量口径一致；金额口径在“整手、费用规整”的常规场景下基本一致，
  在单位成本除不尽、含最低佣金等场景会有不超过舍入位（`moneyScale`）的差异，
  **对账与已实现盈亏以批次口径为准**。

---

## 6. 公司行为规则

以事件 `id` 幂等，重复应用结果不变：

- **拆股 / 合股**（`ratio` = 每股变为多少股，拆股 >1，合股 <1）：
  - 持仓数量、可用数量乘以 `ratio`；
  - 单位成本除以 `ratio`；
  - **持仓总成本保持不变**，因此除权日前后成本口径估值连续；
  - **批次同步调整**：所有未售完批次的剩余数量乘 `ratio`、批次剩余成本（成本池）保持不变，
    单位成本随之除以 `ratio`；`Position` 与批次在同一标的锁内成对调整。
    调整前后“所有批次剩余成本之和”保持不变，之后 FIFO 卖出核销的仍是原始买入成本。
- **现金分红**：按**生效时点实际持有数量** × 每股现金增加现金，计入**当期分红收益**
  （`PortfolioService.dividends(ccy)` / 估值 `totalDividendBase`），
  **不改动任何批次的数量与剩余成本**，也不产生已实现盈亏；重复派现只发一次。

---

## 7. 估值、多币种折算、精度与舍入

- 舍入统一为 **`RoundingMode.HALF_UP`**：金额 `moneyScale=2` 位；中间量（均价/成本）保留 8 位。
- 市值使用**行情价**（`last`），不是成本价：`市值 = last × 数量 × 汇率`。
- 汇率 floor 查询；同币种恒为 1。**绝不默认按 1 折算，也不沿用过期旧值**：
  - 无汇率 → `FX_MISSING`；
  - 汇率年龄 > `fxMaxStaleMillis`（默认 24 小时）→ `FX_STALE`。
- 任一持仓或任一币种现金无法可靠折算，则 `ValuationResult.complete=false`，
  问题项进入 `stalePositions`，其市值记为 `null`；完整 NAV 只汇总可靠项。

### 7.1 估值结果中的盈亏字段（折算到基础币种）
| 字段 | 含义 |
| --- | --- |
| `totalMarketValueBase` | 持仓市值合计（仅 flag=OK 项） |
| `totalCostBase` | 加权平均成本口径（旧口径，兼容） |
| `totalLotCostBase` | **批次台账口径**剩余成本合计 |
| `totalUnrealizedPnlBase` | 未实现盈亏 = 市值 − 批次剩余成本 |
| `totalRealizedPnlBase` | 累计已实现盈亏净额（卖出已扣佣金/税），分币种折算 |
| `totalDividendBase` | 累计现金分红（税前），分币种折算 |
| `totalPnlBase` | 总盈亏 = 已实现 + 未实现 + 分红 |
| `cashTotalBase` / `navBase` | 现金合计 / NAV = 现金 + 市值 |

单持仓行 `PositionValuation` 同步给出 `lotCostValueBase` 与 `unrealizedPnlBase`。
已实现盈亏或分红涉及的币种缺汇率/汇率过期时，同样使 `complete=false`，对应金额不计入合计。
盈亏与现金、成本的对账关系见 5.3。

---

## 8. 绩效与风险指标

输入为按时间升序的净值序列。

- 少于 2 个正净值点 → `INSUFFICIENT_SAMPLES`，所有值为 `null`。
- 期间收益全为 0（或只有 1 个收益率样本，方差按 n−1 自由度为 0）→ `ZERO_VOLATILITY`：
  累计收益/最大回撤给数值，波动率/VaR/ES 为 `null`。
- 正常：累计收益、年化波动率（×√252）、最大回撤、历史 95% VaR（最近秩 5% 分位）、
  ES（最差 5% 的条件均值）。
- 全程使用 `BigDecimal + DECIMAL128`，结果 **绝不出现 NaN/Inf，也不静默为 0**。

---

## 9. 统一异常链路

- 基类 `ApiException` 携带稳定 `code` 与 `ErrorCategory`：
  `INVALID_ARGUMENT(400)`、`STATE_CONFLICT(409)`、`DATA_MISSING(422)`、
  `RESOURCE_EXCEEDED(429)`、`INTERNAL(500)`。
- `GlobalExceptionHandler` 统一把异常转为 `ErrorResponse{category, code, message}`；
  未受控异常一律返回 `INTERNAL / INTERNAL_ERROR / "internal error"`，**不泄漏内部堆栈或敏感信息**。

---

## 10. 本地验证方法

```bash
# 运行全部测试（71 个用例，覆盖批次/FIFO、公司行为、大额单链路、并发、估值对账等）
mvn test

# 只运行批次与已实现盈亏相关用例
mvn -Dtest=LotAndRealizedPnlTest test            # 部分成交拆批、FIFO 卖出、整批核销无尾差
mvn -Dtest=LotCorporateActionTest test           # 拆/合股后批次守恒、分红不改批次、重复行为
mvn -Dtest=LotValuationPnlTest test              # 已实现/未实现/分红与现金成本的对账恒等式、可复现
mvn -Dtest=LargeOrderLotFlowTest test            # 大额单 3 次部分成交→部分卖出（资金占用对账）
mvn -Dtest=LargeOrderCancelFlowTest test         # 大额单多次部分成交→卖出→撤剩余买单
mvn -Dtest=LotConcurrencyTest test               # 同标的并发买入开批、并发卖出 FIFO 核销

# 回归用例（原下单/部分成交/撤单/公司行为/估值/绩效）
mvn -Dtest=PartialFillSettlementTest,CorporateActionTest,ConcurrencyTest test
mvn -Dtest=ValuationFxTest,PerformanceMetricsTest test

# 启动服务（可选的 HTTP 通路）
mvn spring-boot:run
```

REST 便捷通路（`/api`）除原有下单、撤单、估值外，新增按笔对账接口：

| 接口 | 说明 |
| --- | --- |
| `GET /positions/{symbol}/lots` | 该标的未售完批次（数量、剩余成本、单位成本） |
| `GET /lots` | 全部标的未售完批次 |
| `GET /realized-pnl?symbol=` | 逐笔卖出已实现盈亏（FIFO 明细、佣金、税） |

测试以**纯手工装配**（`EngineTestKit`，不依赖 Spring）为主，确定性强；
另含一个 `@SpringBootTest` 验证容器能正常装配。所有关键判定均通过 SLF4J 打印
**输入（行情/订单）与判定依据（接受/拒绝原因、成本拆解、资金/持仓余额、批次核销、估值标志）**。

### 覆盖的代表性边界
- **部分成交拆批**：一笔大单多次部分成交各自成批，买入佣金/税归到对应批次；
- **FIFO 卖出**：跨批次消耗，逐笔可查成本、毛/净已实现盈亏（含卖出佣金与税），整批清空无尾差；
- **拆股/合股后的批次**：数量等比调整、成本池不变，调整后继续 FIFO 卖出且成本守恒；
- **现金分红**：按生效时点持仓量计入当期收益，批次数量/成本不变；
- **重复公司行为**：同一事件 id 第二次应用无任何副作用（不重复派现、不二次调量）；
- **大额订单**：占绝大部分可用资金的买单多次部分成交后部分卖出、再撤剩余，
  预占/现金/批次/盈亏逐节点对账，不多算不漏算；
- **同标的并发买卖**：16 笔并发买入恰好开 16 个批次、现金守恒；
  超过持仓量的并发卖出恰好成交持仓量、其余 `LOT_OVERSELL`，已实现盈亏合计精确；
- 部分成交跨多个 tick 后撤单：现金恰为各笔成交成本之和、预占清零、持仓守恒；
- 乱序/重复/回退行情：floor 视角一致、当前价不回退；
- 停牌窗口：市价单拒单且资金完好，限价单恢复后续撮合；
- 重复成交回报（同 `execId`）只入账一次；
- 汇率缺失/过期、价格缺失/停牌：显式标志，NAV 标 `complete=false`；
- 32 线程同幂等键只下一单；并发卖出冻结不超卖；并发买入预占不超额；
- 指标样本不足、零波动、极端下跌：明确结论，无 NaN/Inf。
