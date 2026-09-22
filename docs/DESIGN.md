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
| `CorporateActionService` | 拆股、合股、现金分红；按事件 id 幂等 |
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
- **买入**：下单时按最坏情形（限价用限价、市价用当刻价，含费估算）**足额预占现金**；
  成交结算只扣减实际支出；部分成交后把预占重新校准为“剩余量最坏成本”，释放差额；
  撤单/拒单/全部成交释放全部剩余预占。逐订单台账保证**只释放一次**。
- **卖出**：下单时**冻结可卖数量**，可用不足直接拒单（不超卖）；成交减持仓、增加税后现金；
  撤单释放未成交部分冻结。
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
# 运行全部测试（53 个用例，覆盖部分成交、乱序/缺失行情、重复回报、并发等）
mvn test

# 只运行某一类边界测试
mvn -Dtest=PartialFillSettlementTest test
mvn -Dtest=MarketDataEventTimeTest test
mvn -Dtest=ConcurrencyTest test
mvn -Dtest=ValuationFxTest test

# 启动服务（可选的 HTTP 通路）
mvn spring-boot:run
```

测试以**纯手工装配**（`EngineTestKit`，不依赖 Spring）为主，确定性强；
另含一个 `@SpringBootTest` 验证容器能正常装配。所有关键判定均通过 SLF4J 打印
**输入（行情/订单）与判定依据（接受/拒绝原因、成本拆解、资金/持仓余额、估值标志）**。

### 覆盖的代表性边界
- 部分成交跨多个 tick 后撤单：现金恰为两笔成交成本之和、预占清零、持仓守恒；
- 乱序/重复/回退行情：floor 视角一致、当前价不回退；
- 停牌窗口：市价单拒单且资金完好，限价单恢复后续撮合；
- 重复成交回报（同 `execId`）只入账一次；重复公司行为无副作用；
- 汇率缺失/过期、价格缺失/停牌：显式标志，NAV 标 `complete=false`；
- 32 线程同幂等键只下一单；并发卖出冻结不超卖；并发买入预占不超额；
- 指标样本不足、零波动、极端下跌：明确结论，无 NaN/Inf。
