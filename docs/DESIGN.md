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
| `PortfolioService` | 多币种现金、持仓、买入资金预占、卖出持仓冻结；临界区保证不超卖/不重复占用 |
| `LotService` | FIFO 成本批次与逐笔已实现盈亏台账；拆/合股同步调整批次；分红独立计当期收益 |
| `CorporateActionService` | 拆股、合股、现金分红；按事件 id 幂等（同时作用于持仓与批次） |
| `ValuationService` | 多币种折算估值；价格/汇率缺失与过期显式标记；固定精度与舍入；已实现/未实现盈亏 |
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
- **买入**：下单时按最坏情形（限价用限价、市价用当刻价，含费估算）**按整单量足额预占现金**；
  每笔成交（含部分成交）结算时：
  1. 先把**本笔实际支出**（成交额+佣金+税）从**本单自己的预占台账**划出等额预留，
     使结算的可用资金校验只面对“其他订单的占用 + 本单剩余预留之外的自由现金”；
  2. 随即把本单预占台账**重校准为仅覆盖尚未成交的剩余量**的最坏成本
     `estimateCost(order, remainingQty, px)`——已成交部分当场让出占用，
     释放出的资金**立刻**计入可用资金，可用于下一笔新单；
  3. 撤单/拒单/全部成交时把本单台账剩余预占**一次性**释放；逐订单台账 + 终态 remove
     保证**只释放一次、不漏放**（重复撤单被订单状态机拒绝，不会二次释放）。
- **可用资金判定边界**：`可用资金 = 现金余额 − 所有在途买单台账预占之和`。
  新买单的最坏成本 `<= 可用资金` 才接受，否则拒单 `INSUFFICIENT_FUNDS`；
  部分成交次数再多，判定也只取决于“现金 − 真实剩余占用”这一瞬时口径，结论稳定不抖动。
  逐订单台账相互独立：A 单已成交部分不会继续压在总占用里，B 单也不会把 A 单的占用
  算成自己的不可用（各自只在自己的台账额度内划出/释放）。
- **卖出**：下单时**冻结可卖数量**，可用不足直接拒单（不超卖）；成交减持仓、增加税后现金；
  撤单释放未成交部分冻结。卖出冻结的是持仓，不动用任何买入现金预占。
- 现金与持仓在各自锁内成对更新；边界测试断言“现金减少额 == 各笔成交成本之和”。

---

## 5. 公司行为规则

以事件 `id` 幂等，重复应用结果不变：

- **拆股 / 合股**（`ratio` = 每股变为多少股，拆股 >1，合股 <1）：
  - 持仓数量、可用数量乘以 `ratio`；
  - 单位成本除以 `ratio`；
  - **持仓总成本保持不变**，因此除权日前后成本口径估值连续。
- **现金分红**：按生效时点持仓数量 × 每股现金增加现金（税前），不改变持仓数量；重复派现只发一次。

---

## 5A. 持仓批次与已实现盈亏（FIFO，按笔对账）

在原有“单一加权平均成本”之外，新增一层**逐笔成本批次台账**（`LotService`），两套口径并行保留。

### 5A.1 批次口径
- **每一笔买入成交即开一个独立批次**（`CostLot`）：同一大单被拆成多次部分成交时，
  每次部分成交各自成批；批次成本 = 成交额 + **该笔买入佣金**（买入费用精确归入对应批次）。
- **卖出按先进先出（FIFO）消耗批次**：一笔卖出跨多个批次时拆成多行 `LotRealization`，
  每行记录消耗的批次、数量、成本基础（cost basis）、归属成交额、分摊佣金/税与**已实现盈亏**。
- 已实现盈亏（金额 2 位、`HALF_UP`）：
  `realizedPnl = 归属成交额 − 消耗批次成本 − 分摊佣金 − 分摊税`；
  一笔卖出的佣金/税按各批次成交额占比分摊，**尾批承接全部舍入差**，
  因此各批次行的成交额/佣金/税/盈亏合计与整笔成交**分毫不差**。
- 部分消耗批次时，成本按数量比例扣减，剩余成本继续保留在该批次；整批清零时尾差一并出清。
- 查询入口：`PortfolioService.lotService()`、`BacktestEngine.openLots/realizations/
  realizedPnl/dividendIncome`，REST 暴露在 `GET /api/positions/{symbol}/lots`
  与 `GET /api/positions/{symbol}/realizations`。

### 5A.2 公司行为对批次的影响
- **拆股/合股**：在同一把持仓锁内同时调整加权口径持仓与每个未售尽批次——
  数量乘 `ratio`、**剩余总成本保持不变**、单位成本等比缩小/放大（8 位展示精度）。
  随后卖出的已实现盈亏以调整后的批次成本计算，除权前后经济连续。
- **现金分红**：按**生效时点实际持有数量**计入当期分红收益（按币种累计），
  **不改动任何批次的数量与剩余成本**，也不计入已实现盈亏；重复事件只计一次。

### 5A.3 估值与对账关系
- `ValuationResult` 新增（均折算到基础币种，只汇总可可靠折算部分）：
  `unrealizedPnlBase`（总市值 − 剩余批次成本）、`realizedPnlBase`、`dividendIncomeBase`、
  `totalPnlBase = realized + unrealized + dividend`；
  `PositionValuation` 新增每行 `unrealizedPnlBase`。
- **对账恒等式**（单/多币种分别成立，折算后亦成立）：

  ```
  期末现金 + 剩余批次成本 = 外部入金累计 + 累计已实现盈亏 + 累计现金分红
  总收益 = 已实现盈亏 + 未实现盈亏 + 现金分红
  ```

  买入佣金/滑点体现在剩余成本里（未平仓时表现为浮亏），卖出费用/税直接冲减已实现盈亏，
  因此费用不漏出恒等式。

### 5A.4 与原“加权平均成本”口径的兼容范围
- 两套口径**并行**：`Position.avgCost/totalCost`（加权平均，既有 API 与测试不变）
  与 `LotService` 批次台账（精确对账）。估值的成本与浮盈亏改用**批次口径**，
  因为它在跨批次部分卖出后仍能与已实现盈亏严格配平；`Position.totalCost` 仅作展示与
  拆股守恒校验。
- 两者在“买入费用全部资本化、卖出不改变单位成本”上口径一致，全仓卖出后两者剩余成本都为 0；
  差异只出现在**跨批次部分卖出后**的单位成本展示（加权平均 vs FIFO 批次）。
- 买入费用归属从“并入加权均价”细化为“归入具体批次”，使每笔卖出的盈亏可逐笔追溯。

### 5A.5 资金预占与大额单（顺带修复的边界缺陷）
- 买入结算前，先把该笔实际支出从**本单自己的预占台账**中划出等额预留，
  使结算的可用资金校验只面对“其他订单的占用”。此前大额单在部分成交续结算时，
  会把自身仍预占的资金误判为不可用（小金额场景被掩盖，大额必触发 `INSUFFICIENT_FUNDS`）。
- 部分成交后重校准预占支持**双向**：按**剩余量**重算最坏成本，下降则释放差额，上升则补足
  （补不足仅告警，不擅改订单状态，留待结算硬校验）。
- **本版修复（部分成交后占用不随剩余量下降）**：重校准曾错误地按订单**原始整单量**重算
  最坏成本（`estimateCost` 内部固定取 `order.getQuantity()`）。后果：大额限价买单部分成交后，
  已成交部分的占用不让出——
  - 当释放/补占发生在资金紧张水位时，引擎按整单口径尝试补占，要么把仅剩的自由现金补进去、
    要么补占失败后台账仍维持接近整单的口径；
  - 账户明明有钱，紧接着的正常买单却因 `可用 = 现金 − 虚高占用` 被判 `INSUFFICIENT_FUNDS`
    误拒；多笔并发在途买单叠加时偏差更大。
  修复后 `estimateCost(order, qty, px)` 显式接收数量：下单传整单量、重校准传 `remainingQty()`，
  台账恒等于“剩余量最坏成本”，部分成交让出的资金当场可用于新单；撤单/全部成交一次清干净。
  新单超额仍按同一口径照常拒单，边界不受影响（见 `PartialFillReservationTest`）。

---

## 6. 估值、多币种折算、精度与舍入

- 舍入统一为 **`RoundingMode.HALF_UP`**：金额 `moneyScale=2` 位；中间量（均价/成本）保留 8 位。
- 市值使用**行情价**（`last`），不是成本价：`市值 = last × 数量 × 汇率`。
- 汇率 floor 查询；同币种恒为 1。**绝不默认按 1 折算，也不沿用过期旧值**：
  - 无汇率 → `FX_MISSING`；
  - 汇率年龄 > `fxMaxStaleMillis`（默认 24 小时）→ `FX_STALE`。
- 任一持仓或任一币种现金无法可靠折算，则 `ValuationResult.complete=false`，
  问题项进入 `stalePositions`，其市值记为 `null`；完整 NAV 只汇总可靠项。

---

## 7. 绩效与风险指标

输入为按时间升序的净值序列。

- 少于 2 个正净值点 → `INSUFFICIENT_SAMPLES`，所有值为 `null`。
- 期间收益全为 0（或只有 1 个收益率样本，方差按 n−1 自由度为 0）→ `ZERO_VOLATILITY`：
  累计收益/最大回撤给数值，波动率/VaR/ES 为 `null`。
- 正常：累计收益、年化波动率（×√252）、最大回撤、历史 95% VaR（最近秩 5% 分位）、
  ES（最差 5% 的条件均值）。
- 全程使用 `BigDecimal + DECIMAL128`，结果 **绝不出现 NaN/Inf，也不静默为 0**。

---

## 8. 统一异常链路

- 基类 `ApiException` 携带稳定 `code` 与 `ErrorCategory`：
  `INVALID_ARGUMENT(400)`、`STATE_CONFLICT(409)`、`DATA_MISSING(422)`、
  `RESOURCE_EXCEEDED(429)`、`INTERNAL(500)`。
- `GlobalExceptionHandler` 统一把异常转为 `ErrorResponse{category, code, message}`；
  未受控异常一律返回 `INTERNAL / INTERNAL_ERROR / "internal error"`，**不泄漏内部堆栈或敏感信息**。

---

## 9. 本地验证方法

```bash
# 运行全部测试（76 个用例，覆盖部分成交、乱序/缺失行情、重复回报、并发、FIFO 批次与盈亏等）
mvn test

# 只运行某一类边界测试
mvn -Dtest=PartialFillSettlementTest test
mvn -Dtest=PartialFillReservationTest test   # 大额单部分成交后占用随剩余量下降/边界拒单/并发各算各的/撤单清零
mvn -Dtest=MarketDataEventTimeTest test
mvn -Dtest=ConcurrencyTest test
mvn -Dtest=ValuationFxTest test

# 批次与已实现盈亏相关
mvn -Dtest=LotServiceTest test                  # FIFO 拆批/跨批卖出/费税分摊/拆合股/分红
mvn -Dtest=LotRealizedPnlIntegrationTest test  # 引擎级对账恒等式、部分卖出、拆股后卖出
mvn -Dtest=LargeOrderLotFlowTest test          # 大额单：多次部分成交→部分卖出→撤剩余
mvn -Dtest=ConcurrentLotTradingTest test       # 同标的并发买卖，批次/现金/盈亏一致
mvn -Dtest=LotCorporateActionTest test         # 拆/合股与分红对批次的影响、重复幂等
mvn -Dtest=LotMultiCcyAndReplayTest test       # 多币种盈亏折算、同输入重放一致

# 启动服务（可选的 HTTP 通路）
mvn spring-boot:run
```

批次/盈亏的只读查询（REST）：
`GET /api/positions/{symbol}/lots`（剩余批次：数量、单位成本）、
`GET /api/positions/{symbol}/realizations`（逐笔卖出的 FIFO 消耗与已实现盈亏）；
估值结果 `GET /api/valuation` 内含 `unrealizedPnlBase/realizedPnlBase/dividendIncomeBase/totalPnlBase`。

测试以**纯手工装配**（`EngineTestKit`，不依赖 Spring）为主，确定性强；
另含一个 `@SpringBootTest` 验证容器能正常装配。所有关键判定均通过 SLF4J 打印
**输入（行情/订单）与判定依据（接受/拒绝原因、成本拆解、资金/持仓余额、估值标志）**。

### 覆盖的代表性边界
- 部分成交跨多个 tick 后撤单：现金恰为各笔成交成本之和、预占清零、持仓守恒；
- **大额买单多次部分成交：每笔成交后预占恰为“剩余量 × 最坏每股成本”，同步下降；
  让出的资金马上能让贴边可负担的新单成交，真正超额（贴边+1）的新单稳定拒单；
  多笔并发在途买单各算各的占用，撤一单只释放自己那一份，重复撤单不二次释放，
  全部成交/撤单后总占用为 0**（`PartialFillReservationTest`，日志按“业务1/2/3 + 用例结论”输出）；
- **每次部分成交各开一个成本批次；卖出 FIFO 跨批，逐行已实现盈亏（费税分摊、尾批兜舍入差）**；
- **大额买单多次部分成交 → 部分卖出 → 撤剩余：预占不把自身占用误判为不可用，
  现金/批次/盈亏严格满足对账恒等式**；
- **同一标的并发买卖：批次数量之和 == 持仓数量，已实现盈亏 == 各行之和，恒等式在争用下成立**；
- 乱序/重复/回退行情：floor 视角一致、当前价不回退；
- 停牌窗口：市价单拒单且资金完好，限价单恢复后续撮合；
- 重复成交回报（同 `execId`）只入账一次；重复公司行为无副作用（批次不二次调整、分红不重复发）；
- 拆股/合股后批次数量与单位成本同步调整、剩余总成本不变；分红按生效时点数量计一次、批次不动；
- 汇率缺失/过期、价格缺失/停牌：显式标志，NAV 标 `complete=false`；
- 32 线程同幂等键只下一单；并发卖出冻结不超卖；并发买入预占不超额；
- 指标样本不足、零波动、极端下跌：明确结论，无 NaN/Inf。
