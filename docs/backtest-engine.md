# 本地事件驱动回测与组合估值服务 — 设计说明

## 1. 总览

服务用本地行情与成交回报，模拟"下单 → 撮合 → 成交 → 持仓出账 → 估值 → 绩效"的完整链路。
所有输入以**事件时间（eventTime）**为准排序处理，与到达顺序无关；同一组事件多次运行结论一致、可复现。

核心组件：

| 组件 | 职责 |
| --- | --- |
| `BacktestEngine` | 事件排序（eventTime + 同刻优先级）、编排处理、行情驱动撮合、估值快照 |
| `MarketDataService` | 行情接入：乱序/回退/重复 tick 处理，新鲜度与停牌判定 |
| `FxRateService` | 汇率接入：过期/缺失显式判定，绝不静默按 1 折算 |
| `OrderService` | 订单状态机、幂等下单/成交、资金与数量预留核销、撤单/拒单 |
| `MatchingEngine` | 市价/限价撮合判定、滑点价格、逐笔费用 |
| `CostCalculator` | 滑点、佣金（比例 + 最低）、税费的逐笔计算 |
| `Portfolio` | 账户级排他锁，多币种现金与持仓的唯一变更入口 |
| `CorporateActionService` | 拆股、合股、现金分红（actionId 幂等） |
| `ValuationService` | 组合估值、多币种折算、数据质量显式标识 |
| `PerformanceCalculator` | 累计收益、年化波动率、最大回撤、VaR/ES |

## 2. 事件时间与排序

`BacktestEvent` 为 sealed 类型，排序键为 `(eventTime, priority)`，同刻优先级：

1. 公司行为（0）— 除权日先调整持仓；
2. 成交回报（10）；
3. 行情（20）；
4. 下单/撤单（30）；
5. 估值快照（40）。

因此同一除权日"先拆股、后按新价估值"，不会出现除权口径不一致。

### 行情规则

- 仅接受 **eventTime 严格更新** 的 tick；时间戳回退的 tick 被丢弃（日志 `REJECT ... reason=backward_time`），价格永不回退；
- 同一时间戳：内容相同视为重复（`IGNORE ... duplicate`），内容不同拒绝歧义（保留先到者）；
- 从未收到价格：`MISSING_PRICE`；
- 最新价早于估值时点超过 `backtest.price-stale-tolerance`：`STALE_PRICE`；
- 显式停牌 tick：`SUSPENDED`，停牌区间订单挂起、不撮合，绝不沿用旧价估值。

## 3. 订单生命周期与幂等

状态机：`NEW -> PARTIALLY_FILLED -> FILLED`；`NEW/PARTIALLY_FILLED -> CANCELLED`；
`NEW -> REJECTED`。`FILLED/CANCELLED/REJECTED` 为终态，终态再收成交回报抛 `STATE_CONFLICT`。

- **下单幂等**：同一 `idempotencyKey`（含并发提交）只有一次有效创建，其余返回同一订单；并发竞争失败方自动回滚已预留资源。
- **成交幂等**：`fillId` 全局去重，重复回报不重复扣钱/加持仓。
- **超额成交**被状态机拒绝（`fill quantity exceeds order quantity`）。
- 撤单幂等（重复撤返回当前状态），已成交/已拒单再撤抛 `STATE_CONFLICT`。
- 稳定拒绝码：`QUANTITY_MUST_BE_POSITIVE`、`LIMIT_PRICE_REQUIRED`、`SYMBOL_REQUIRED` 等，可区分原因。

## 4. 撮合与成本模型

- **限价单**：最新价不劣于限价方可成交，成交价 = 限价（买：市场价 ≤ 限价；卖：市场价 ≥ 限价），不加滑点。
- **市价单**：成交价 = 最新可交易价 × (1 + 方向 × slippage-rate)，买高卖低；停牌或无行情时挂起。
- 每个可交易 tick 到达后，引擎按 `(提交时间, orderId)` 确定性顺序撮合该标的所有活动订单。

费用逐笔计算并随 `Fill` 留存，可追溯：

- 佣金 = max(成交额 × commission-rate, commission-min)；
- 税费 = 成交额 × tax-rate，tax-on-sell-only=true 时仅卖出收取；
- 全部金额 2 位小数 HALF_UP。

## 5. 资金与持仓守恒

账户使用可重入排他锁，"校验 + 变更"在同一临界区内，杜绝并发超卖与重复占用。

会计恒等式：cash = 总现金，reserved = 被买单冻结部分，available = cash - reserved。

- 买单下单：reserved += 预留上界（available 减少，cash 不变）；
  预留上界 = 成交额 + 比例佣金 + 税 + commission-reserve-per-order，
  覆盖"多笔部分成交各付最低佣金"的极端拆分（无有限上界时的显式保守值，可配置）；
- 买单成交：cash -= 实际支出、reserved -= 实际支出（冻结资金转为真实流出，available 不变），
  且按"本笔 ≤ 本单剩余预留"校验，累计超预留抛 RESOURCE_LIMITED 并回滚订单状态；
- 订单进入终态（FILLED/CANCELLED/REJECTED）统一释放剩余预留尾差；
- 卖单下单：冻结数量；成交核销总量、按平均成本结转成本基准；撤单释放剩余冻结；
- 买入手续费计入持仓成本基准；卖出税费/佣金不进剩余持仓成本。

## 6. 公司行为规则

- **拆股 SPLIT（ratio=r）**：数量（含冻结）× r，成本基准总额不变，平均成本 ÷ r；
- **合股 REVERSE_SPLIT（ratio=r）**：数量 × (1/r)，成本基准不变，平均成本 × r；
- **现金分红**：除权日持仓量 × 每股派现入现金，持仓与成本基准不变；
- 同一 actionId 对同一账户重复应用结果不变（首次 true，重复 false）；
- 数量按 8 位小数 HALF_UP 取整，尾差留在成本基准中；空仓为 no-op 但仍记录幂等键。

## 7. 估值、多币种与舍入约定

- 数量：8 位小数 HALF_UP；均价：内部 12 位小数 HALF_UP；**所有金额：2 位小数 HALF_UP**。
- 持仓市值 = 数量 × 本地币种有效价 × 折算汇率，再 2 位小数 HALF_UP。
- 价格非 OK（MISSING_PRICE/STALE_PRICE/SUSPENDED）或汇率非 OK（FX_MISSING/FX_STALE）时，
  对应行 marketValueBase = null 且不并入总额；同币种汇率恒为 1（显式规则，非兜底）。
- 只要存在任一问题，healthy=false、totalEquity=null，issues 列出全部数据状态，
  强制使用方显式处理，绝不按零、旧价或汇率 1 静默计价。

## 8. 绩效与风险指标

基于按事件时间采样的净值序列（同刻取最后快照）：

- 累计收益：<2 点 INSUFFICIENT_SAMPLES；起始净值 ≤0 为 UNDEFINED；
- 年化波动率：逐期简单收益率样本标准差 × √(一年秒数/平均间隔)；零波动返回 0（OK）；
- 最大回撤：min(净值/历史峰值 − 1)，单调上涨为 0；
- VaR95 / ES95：收益率样本 <20 为 INSUFFICIENT_SAMPLES；尾部无亏损为 0；
- 任何指标都不返回 NaN/Inf（输出前有有限性断言）。

## 9. 统一异常链路

| 类别 | HTTP | 场景 |
| --- | --- | --- |
| INVALID_PARAMETER | 400 | 参数非法（带稳定错误码） |
| STATE_CONFLICT | 409 | 非法订单状态迁移、终态成交 |
| DATA_MISSING | 422 | 无参考价无法预留等数据缺失 |
| RESOURCE_LIMITED | 429 | 资金/可卖数量不足 |
| INTERNAL | 500 | 未预期异常：仅记录堆栈，对外脱敏为 internal server error |

## 10. 本地验证

mvn test 运行全部 31 个测试（无需外部依赖）；mvn spring-boot:run 启动 REST 服务。

关键用例（边界场景，测试中均打印输入与判定依据日志）：

- MarketDataServiceTest：乱序/回退/重复 tick、停牌、缺失价格；
- OrderLifecycleConservationTest：部分成交资金/成本守恒、重复回报、撤单释放、超卖拒绝；
- CorporateActionServiceTest：拆/合股、分红、重复应用幂等；
- ValuationServiceTest：缺价/停牌/缺汇率/过期汇率显式标识；
- PerformanceCalculatorTest：样本不足、零波动、极端行情无 NaN；
- ConcurrencyTest：32 并发同键只下一单、20 并发卖出不超卖、16 并发重复回报只扣一次；
- BacktestEngineIntegrationTest：乱序事件确定性、停牌挂起复牌成交、除权日前后估值一致。

REST 速览：

```
POST /api/accounts?accountId=a&baseCcy=USD&initialCash=1000000
POST /api/symbols/AAA/currency?ccy=USD
POST /api/market/AAA?price=100&at=2026-09-01T09:00:00Z
POST /api/fx?from=HKD&to=USD&rate=0.128&at=2026-09-01T09:00:00Z
POST /api/orders                 # body: {accountId,symbol,side,type,quantity,limitPrice,idempotencyKey}
POST /api/orders/{id}/cancel
GET  /api/orders/{id}
GET  /api/accounts/{id}/valuation?at=2026-09-01T09:00:00Z
```
