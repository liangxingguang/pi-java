# 68 - A-20 无生产者代码逐项裁决与清理（设计稿）

> ✅ **状态：已实现并闭环（2026-10-02，commits `78aee75`→`de0ce06`）** —— R1–R7 全按建议。
> §12 为实施记录。新登记 **B142**（见 §5，不在本包实施）。

**来源：** [`48 §5`](48-ai-next-work-items-design.md) A-20 行、[`41 §1.5`](41-gap-inventory.md)、[`32`](32-open-items-register.md) B111/B112
**pi 锚点：** `3390bd93630965a12a0a1a5c36ce890ec22f7e1d`（pi 侧全部结论经 `git grep/show 3390bd9`，未读工作树）
**Java 基准：** `c8d44bd`（工作树 clean）
**范围：** 仅「有形状、主流无生产者/无消费者」代码的**删／留文档化**裁决。唯一真行为修复＝pi-messages 车道重试归零。**不新增任何功能。**

---

## 1. pi 事实清单（锚点已核实）

| # | 事实 | 证据 |
|---|---|---|
| P1 | pi **无 usage 流事件、无 usage 工厂**；usage 挂在共享 `partial.usage` 上被观察，初值各车道内联字面量（`input/output/totalTokens`） | `packages/ai/src/types.ts:637-668`；`api/anthropic-messages.ts:518-529`、`api/openai-completions.ts:325-333` |
| P2 | pi **无任何 RetryPolicy 命名静态预设**；provider 重试只有单一参数化函数 `retryProviderRequest`，`maxRetries = options.maxRetries ?? 0`（默认 **0**），退避 500ms 底数/8s 顶/25% 抖动 | `packages/ai/src/utils/provider-retry.ts:1-124` |
| P3 | pi **mistral 车道锚点上无任何重试接线**（`git grep maxRetries|retry` mistral-conversations.ts 为空） | —— |
| P4 | pi-messages 低层 `stream` 用**单次 `fetch`**，无任何重试包裹 | `packages/ai/src/api/pi-messages.ts:355-424`（`const response = await (options?.fetch ?? globalThis.fetch)(url, …)`，失败直接进 catch 推 error 帧） |
| P5 | `streamSimple` 是 pi 主流默认入口：`sdk.ts:39 setDefaultStreamFn(streamSimple)`；agent-loop/agent 默认消费它 | `packages/coding-agent/src/core/sdk.ts:39`；`packages/agent/src/agent-loop.ts:123,148`、`agent.ts:237` |
| P6 | pi **主流编码代理跑的是 legacy `new Agent(…)`＋`agent-loop.ts`**；`AgentHarness` 仅 experimental（mini/micro/services） | `packages/coding-agent/src/core/sdk.ts:368`；`AgentHarness` 仅命中 `src/experimental/**` |
| P7 | `clampThinkingLevel` 在 pi **8 条车道**发送前逐帧执行；`getSupportedThinkingLevels` 服务级别选择 UI | `models.ts:924-954`；8 调用点（azure/google×2/mistral/codex/completions/responses/completions） |
| P8 | `DeferredHandle` 主流零生产者：唯一产出者是 **faux 测试 provider** 在 `streamOptions.deferred` 条件下构造 | `packages/ai/src/providers/faux.ts:524-532`；真实 provider 无 `fetchDeferred` |
| P9 | ToolResult `details` **主流有生产者**：edit 每次成功必放 `{diff,patch,firstChangedLine}`；bash 截断时放 `{truncation,fullOutputPath}`；read 截断时放 `{truncation}`。错误路 `createErrorToolResult` 放**空对象 `{}`**（legacy agent-loop 主流形状；experimental harness 路为 undefined，依 P6 不对齐） | `packages/agent/src/harness/tools/edit.ts:133-139`、`bash.ts:88-115`、`read.ts:123-137`；`agent-loop.ts:767-772` |
| P10 | ToolResult `usage` 主流零生产者（4 内置工具均无），唯一实例是 `examples/extensions/subagent`（嵌套模型调用工具按 Usage 全形累加）——扩展面 | `packages/coding-agent/examples/extensions/subagent/index.ts:367-375` |

## 2. Java 现状与逐项裁决

| # | 构造 | Java 调用面（已核实） | 裁决 |
|---|---|---|---|
| J1 | `StreamEvent.UsageInfo.from(…)`（`ai/stream/StreamEvent.java:256-259`） | 主源码/测试**零调用**；是规范构造器纯透传别名；pi 无对应物（P1） | **删** |
| J2 | `ModelThinkingLevels.supported/clamp`（`ai/catalog/ModelThinkingLevels.java:40-83`） | **clamp 已有生产者**：A-10（docs/57）接线于 `SimpleOptions.java:200`，经 completions 车道生产可达；supported 经 clamp 间接可达 | **留**，旧「无生产者」登记作废 |
| J3 | RetryPolicy 五命名预设 `anthropic/openai/google/mistral/deepseek`（`ai/http/RetryPolicy.java:47-90`） | 全仓零调用；pi 无对应物（P2，连形状都相反：pi 默认 0 次） | **删** |
| J4 | `RetryPolicy.defaultPolicy()`（`RetryPolicy.java:42-44`，3 次/1s/无抖动） | 仅测试显式引用＋被两处当字段默认；语义与 pi 相反（P2） | **删**（消费点改显式 builder 或新默认，见 J5/J6） |
| J5 | `PiHttpClient.Builder.retryPolicy` 字段默认 `defaultPolicy()`（`ai/http/PiHttpClient.java:213`） | 生产消费方仅 2 个：`PiMessagesApi.java:69`（不显式设）与 Mistral（显式设） | **默认改零重试**（对齐 P4） |
| J6 | `HarnessConfig.retryPolicy` 分量（`agent/harness/HarnessConfig.java:93,114,166`） | 访问器 `retryPolicy()` **全仓零读者**（主源码＋测试）；生产 builder 从不设置；provider 重试已由 A-14 经 ApiOptions 落地 | **删分量**（canonical＋18-参兼容 ctor＋Builder；约 24 处测试构造点机械跟进） |
| J7 | `agent/stream/StreamSimple.java`（84 行）＋`context/ContextEstimator.java` | 两者**互为仅有的生产引用**，生产零外部调用（真正对应物是 ai 层 `AbstractChatApi`）；pi 无对应物 | **两类＋两测试一起删**，B112 以删除结案（不进非投影集——无生产者可投影） |
| J8 | `DeferredHandle`（`ai/message/DeferredHandle.java`） | 主流零构造点；`AssistantMessage.fromPartial` 硬编码 null（`Message.java:197`）；与 pi 同状（P8） | **留形状、不接线**，javadoc 补指本文；B111 保持登记 |
| J9 | ToolResult `details`（`Message.java:281-305`） | **有生产者**：`EditTool:151`、`BashTool:161`、`ReadTool:123,133`、`BashUpdateEmitter:161`；错误路 `PiLoopTools:283 Map.of()` 对齐 P9 legacy 主流 | **留**，旧「无生产者」登记作废；`{}` 不改 |
| J10 | ToolResult `usage`（`Message.java:285`；`ToolResult.UsageInfo` 两分量） | 主流零生产者（与 P10 同状）；转发链（PiToolRunner/HookSystem）保留 | **留形状、不接线** |

## 3. 实施步骤（TDC 口径：删除无 RED，行为修复严格 RED）

> 本包 6 步中仅第 1 步是行为变更（RED→GREEN→变异）；第 2–5 步为删除，「红」＝删除后全仓编译＋模块回归，无新行为可写 RED（与 docs/55 死码删除同口径）。每步独立可编译、独立 commit。

### 第 1 步：pi-messages 重试归零（唯一行为修复）

**RED（新测试，pi-java-ai）**：`PiMessagesNoRetryTest` —— RecordingHttpServer 首次返回 `500`、二次返回 `200`（SSE 终局帧）；断言 pi-messages 流以 **error 终局结束、服务器只收到 1 次请求**。
现状红：`PiHttpClient` 按 `defaultPolicy`（3 次、5xx 可重试）重试 ⇒ 第 2 次 200 成功。

**GREEN**：
```java
// PiHttpClient.java:213
private RetryPolicy retryPolicy = new RetryPolicy.Builder().maxRetries(0).build();
```
`PiMessagesApi.java:69` 不动（吃新默认）；Mistral 显式 policy 不受影响。

**变异 M1**：默认改回 `defaultPolicy()` ⇒ 恰本测试 1 红。验证后立即还原。

### 第 2 步：删 `UsageInfo.from`

- 删 `StreamEvent.java:255-259`（javadoc＋方法）。
- 全仓 grep 无调用（J1），编译即过。docs/42 中对它的历史性描述在第 6 步统一更正。

### 第 3 步：删 RetryPolicy 五命名预设＋defaultPolicy

- 删 `RetryPolicy.java:42-90` 六段。
- 跟进显式引用点：`PiHttpClientSseTest`（8 处 `defaultPolicy()` → `new RetryPolicy.Builder().build()`）；`HarnessConfig` 的两处引用随第 4 步消失；agent-core 测试中的 `RetryPolicy.defaultPolicy()`（如 `ConfigEntryEmissionTest:56`）随第 4 步消失。

### 第 4 步：删 `HarnessConfig.retryPolicy` 分量

- canonical 分量、compact ctor 第 114 行兜底、18-参兼容 ctor 形参（:141,147）、Builder 字段/setter/build() 透传（:166,256+）。
- 跟进约 24 处 `new HarnessConfig(…)` 测试构造点：删该位置实参（机械改、无语义）。
- 生产 `AgentSession` builder 从不设置，零改动。

### 第 5 步：删 StreamSimple／ContextEstimator

- 删 `agent/stream/StreamSimple.java`、`agent/context/ContextEstimator.java` 及对应测试 `StreamSimpleTest`、`ContextEstimatorTest`。
- grep 确认无其他引用（Estimate.java:40、docs/57/55 中的文字属文档引用，第 6 步处理；主源码零引用）。

### 第 6 步：文档回填＋回归

- 回归：ai、agent-core、coding-agent 带 `-am`；checkstyle 全模块；确认无文件超 500 行、无残留 import。
- 文档回填清单见 §6。

## 4. 裁决点（需用户拍板）

| # | 裁决 | 备选 |
|---|---|---|
| R1 | J1/J3/J4 **死码一律删**（含 defaultPolicy；测试改显式 builder） | 保留＋javadoc 标注（误导风险仍在） |
| R2 | J5 **PiHttpClient 默认零重试**（对齐 pi-messages 单次 fetch；Mistral 显式 policy 不受影响） | 仅给 PiMessagesApi 显式塞零重试 policy、保留 builder 老默认（老默认仍与 pi 相反） |
| R3 | J6 **删 HarnessConfig.retryPolicy 死分量**（约 24 测试点机械跟进） | 保留分量仅加 javadoc（零行为、零读者，纯 churn 回避） |
| R4 | J7 **删 StreamSimple/ContextEstimator**，B112 删除结案 | 仅删 overflow 分支保留类（类本身也零调用，无意义） |
| R5 | J8/J10 **DeferredHandle、toolResult.usage 留形状不接线**（B111 等登记保持） | 删形状（与 pi 保留的扩展面相悖） |
| R6 | J9 **details 全部保留**，错误路 `{}` 照 legacy 主流不改 | 改 undefined（那是 experimental harness 形状，非对齐目标） |
| R7 | clamp 缺口**另登记 B142**（见 §5），不进本包 | 本包顺带对齐（混入行为变更，破坏「纯清理」边界） |

## 5. 新登记缺口（本包发现、不在本包实施）

**B142（疑似，实施前先取证）**：pi 在 **8 条车道**发送前都 `clampThinkingLevel`（P7）；Java 目前仅 completions 车道经 `SimpleOptions:200` clamp。其余车道（responses/anthropic/google/azure/mistral）若确实未 clamp，则用户选择 `xhigh/max` 等不被支持级别时会原样发给 provider。做「metadata/车道行为对齐」包时逐车道取证后裁决。

## 6. 风险、未覆盖与文档回填

**风险**
- 第 1 步改变 pi-messages 生产重试行为（3→0）：这正是对齐点；影响面仅 PiMessagesApi（Mistral 显式 policy 不受影响）。
- 第 4 步 churn 集中在测试文件，机械删实参；模块回归兜底。

**未覆盖（如实登记）**
- B142（§5）。
- DeferredHandle 若未来出现真实 provider 生产者，需另立设计包（B111）。

**闭环时文档回填（grep 复核，不允许残留「待做」描述）**
- [`41`](41-gap-inventory.md):118-123（§1.5 五行逐条标注裁决）
- [`48`](48-ai-next-work-items-design.md) banner ＋ §5 A-20 行
- [`32`](32-open-items-register.md):594（DeferredHandle，标注 B111 维持）、687（B111）、688（B112 删除结案）
- [`42`](42-usage-domain-design.md):60,99,249,275（UsageInfo.from 已删）
- [`46`](46-*.md) §7 B15-残留-9（supported/clamp 已接线，结案）
- [`55`](55-c-terminal-event-payload-design.md):27,414（B112 删除结案）
- [`57`](57-a10-request-options-max-tokens-design.md)（ContextEstimator 已删的文字注记）
- javadoc：`DeferredHandle`、`Message`（ToolResult details/usage）补 docs/68 指引

---

## 12. 实施记录

### 12.1 步骤与 commits

| 步 | 内容 | commit | 验证 |
|---|---|---|---|
| 1 | PiHttpClient 默认零重试（唯一行为修复） | `78aee75` | RED：重试产出 StreamDone（error 断言失败，服务器 2 次命中）；GREEN：1285 全绿 |
| 2 | 删 `UsageInfo.from` | `d5a3582` | ai 编译通过（零调用已 grep 证实） |
| 3 | 删 RetryPolicy 六预设 | `350f252` | ai **1285/1285** 绿；PiHttpClientSseTest 改显式 Builder |
| 4 | 删 `HarnessConfig.retryPolicy` 分量 | `7dc12c4` | agent-core（24 文件机械跟进） |
| 5 | 删 StreamSimple／ContextEstimator | `de0ce06` | agent-core **526/526**（删 9 旧测试，535−9=526） |
| 6 | 回归＋文档回填 | —— | coding-agent **290/290**（-am）、checkstyle 全 14 模块 SUCCESS |

### 12.2 变异探针

| 探针 | 预期 | 实际 |
|---|---|---|
| M1：PiHttpClient 默认改 `new Builder().build()`（maxRetries=3） | 恰 1 红 | ✅ 恰 1 红（`serverErrorIsNotRetriedAndEndsInError`），还原后绿 |

删除类步骤（2–5）无新行为可写 RED；以「全仓编译＋模块回归」为证，与 docs/55 死码删除同口径。

### 12.3 实施偏离

- **步骤次序调整（3↔4）**：实际按 1→2→**4→3**→5 实施。删 RetryPolicy 预设会使 agent-core 23 个测试构造点失去 `defaultPolicy()`；先删 `HarnessConfig.retryPolicy` 分量（同时消掉这些引用），再删预设，保证每个 commit 后整个 reactor 都绿。范围与裁决不变。
- **RetryPolicy 预设数量更正**：docs/41 旧台账与设计稿写作时按「五个预设」，实测为 **6 个**（defaultPolicy＋五命名），见取证报告。

### 12.4 台账结案

- **B112**：以删除结案（StreamSimple overflow 停因路随类删除，无生产者，不进非投影集）。
- **B111 / DeferredHandle**：维持登记，留形状不接线（两侧同无真实生产者）。
- **B15-残留-9**（ModelThinkingLevels supported/clamp）：A-10（docs/57）已接线，结案。
- 旧 §1.5（docs/41）中 UsageInfo.from、RetryPolicy 预设两行作废。
- **新登记 B142**：见 §5（8 车道 clamp 对齐，待后续包取证）。

### 12.5 最终状态

工作树于闭环时 clean；ai 1285、agent-core 526、coding-agent 290；无文件超 500 行；无新增 System.out。
