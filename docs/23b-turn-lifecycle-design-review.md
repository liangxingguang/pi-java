# 设计文档审查报告 — `docs/23-turn-lifecycle-alignment-design.md`

> 审查对象：`docs/23-turn-lifecycle-alignment-design.md`（538 行，commit `ef7d2e9`）
> 审查方式：三个并行代理分头核对 **pi 参考实现**、**pi-java agent-core**、**pi-java coding-agent/web/前端**，
> 逐条验证文档的每一处引用（行号 + 内容 + 语义），再由人工交叉复核设计判断。
> 审查日期：2026-09-12。**审查基线**：`main` @ `f33a94e`（工作树干净）。
>
> **修订状态**：本报告的全部 P0（4 项）、P1（5 项）、P2（5 项）已按结论修订进 `docs/23`
> （变更清单见其 §10）。**本报告保留为评审当时的事实记录，不复改**——下文所有「错误/过期」判断
> 描述的是**修订前**的 `docs/23`。

---

## 一、总评

**结论：问题全部真实存在，诊断方向正确，但方案有 4 处可执行缺陷 + 3 处引用失真，须修订后再实施。**

| 维度 | 权重 | 得分 | 一句话依据 |
|---|---|---|---|
| 问题陈述的真实性（§0） | 20 | **20** | 四项现象逐条核实**全部属实**，根因二（阶段错位）判断准确且有源码证据 |
| pi 语义引用准确性（§1） | 20 | **13** | 20/20 行号命中 pi 源码；但 C6 方向性错误、C9 引错函数、事件族漏一项 |
| 方案正确性 / 完备性（§3–§5） | 25 | **14** | 分层与发射点选择正确；但收口路径漏 2 处、turn 事件顺序与 pi 相反、截断路径未覆盖、与 record 日志的关系未论证 |
| 可实施性（§7–§9） | 20 | **12** | 3 处行号错误、1 处 API 不可调用（`appendEntry` 私有）、前端前置判断过期且建议方案类型不兼容 |
| 完备性与诚实性（§6/§7/§9） | 15 | **10** | §6「不可对齐项」诚实、§7 风险表扎实；但会让 2 个既有测试变红未提、无替代方案对比、无并发契约 |
| **合计** | 100 | **69** | 修订后方可进入实施 |

**评级：🟡 需修订（Revise before implementation）** — 不是「推倒重来」，主干（在 `ToolExecutionPipeline` 发执行阶段事件、删除 `StreamEvent.ToolCall*` 的错位翻译）是对的；要改的是**覆盖完备性、顺序、以及若干过期/错误的引用**。

---

## 二、§0 问题陈述：逐条核实（全部成立）

| # | 文档主张 | 核实结果 | 证据 |
|---|---|---|---|
| 1 | 前端 `case "turn_start"` 是死代码 | ✅ **成立** | `frontend/client/main.ts:298-300` 存在；全仓库（含 TS/JS/bundle）**0 处发射** `turn_start` |
| 2 | `case "turn_end"` 的 `event.toolResults` 恒为 `undefined` | ✅ **成立** | 唯一发射点 `AgentEventTranslator.java:53` 只写 `type`；`main.ts:303-312` 整段不可达 |
| 3 | `agent_start` 每轮发一次，语义错位 | ✅ **成立** | `AgentEventTranslator.java:67-70`：`case StreamEvent.Start → typeNode("agent_start")`（`StreamEvent.Start` 每轮一次） |
| 4 | `tool_execution_*` 无 `toolName`/`args`/`result` | ✅ **成立** | 同文件 `:73-78`，三个分支都只发 `typeNode(...)` |
| — | `AgentSessionEvent` 13 变体、无 `TurnStart`/`TurnEnd` | ✅ **成立** | `AgentSessionEvent.java`（61 行）恰 13 个 record + 1 个 `CompactionReason` enum |
| — | 根因二：从 **LLM 输出阶段** 翻译执行事件 | ✅ **成立且重要** | `StreamEvent.ToolCallStart/Delta/End` 只带 `contentIndex`，本就不含 name/args；而执行阶段（`ToolExecutionPipeline`）信息齐全 |

**这一段是本文档最有价值的部分。** 根因二（「把模型吐出工具调用当成工具开始执行」——不是漏字段而是发错阶段）是准确的诊断，且指向了正确的修法：发射点下沉到 `ToolExecutionPipeline`。§0 的四项现象与两处根因**没有一处夸大或臆造**。

> 补充（文档未提，属既有缺口）：`AgentSessionEvent` 里 `MessageUpdate`/`AgentSettled` 已走同一条 `eventHub`，而 `SessionEventHub` 在**零订阅者时静默丢弃**事件——与刚修掉的 `SubmissionPublisher` 是同类语义。turn 事件新增后，迟到订阅者同样收不到早先的 `turn_start`。这一点应在 §7 写明（**非新增缺陷**，但会被新事件继承）。

---

## 三、§1 pi 语义引用：行号全中，但有两处方向性错误

**行号命中率 20/20** —— 文档引用的 `agent-loop.ts` 每一处区间都落在真实且描述相符的代码上（`109-114`/`138`/`174-179`/`182-190`/`193`/`196-200`/`207-222`/`224`/`232`/`248`/`259`/`336-342`/`514`/`540`/`618-619`/`638-639`/`687-693`/`767-774`），`AgentToolResult` 的五字段（`types.ts:360-375`）也确认无误。**引用纪律很好。** 但语义与覆盖有三处问题：

### 3.1 🔴 **C6 说反了**（连带 §4.5 的「被拒绝不发 start」也错）

文档 C6 称 `tool_execution_start` 在「参数校验（`:618`）与钩子（`:619`）**之后**、执行之前」发射。**pi 的实际顺序相反**：

```
:445-450  await emit({ type: "tool_execution_start", ... })     ← 先发 start
:452      const preparation = await prepareToolCall(...)        ← 校验(:618) 与 hook(:619-620) 在这里面
```

并行路径同理：`start` 在 `:499-505`，`prepareToolCall` 在 `:507`。**推论**：pi 对**被 `beforeToolCall` 拒绝的调用也发 `start`**（`:514` 立即发 `end`，`isError: true`）。文档 §4.5「被拒绝的调用｜不发 `start`，直接发 `end`」并声称「对齐 pi `:514` 与 `:638-639`」——这是**不实的对齐声明**（本仓把这类声明当缺陷，Phase 22 共修 5 处）。

**修订选项**：（a）把 ① 的发射点上移到 Stage 1 循环内、`beforeToolDecision` **之前**，与 pi 严格一致（推荐，代价是「被拒调用」也有 start/end 对，前端更易处理）；或（b）保留现设计，但把这条**移入 §6「有意偏差」**并写明理由，删掉「对齐 pi」的措辞。

### 3.2 🟡 **C9 引用了串行函数**

C9/§4.5 称「并行批次中 `start` 顺序发射（`:444-450` 循环内）」。`:444-450` 属于 `executeToolCallsSequential`；并行版的顺序发射循环在 **`:499-505`**。**结论对、引文错**（断言正确性不受影响）。

### 3.3 🟡 **§1.2「一轮的完整顺序」漏了 5 项，其中 2 项影响结论**

漏列（按重要性）：

1. **`:207-213` 的截断分支**：`message.stopReason === "length"` 时走 `failToolCallsFromTruncatedMessage`，**不执行工具**，但**仍然发 `tool_execution_start` + `tool_execution_end` + `tool_result_message`**（`:386-406`）→ 见 §4.3 的覆盖缺口；
2. **`:216 hasMoreToolCalls = !executedToolBatch.terminate`** —— 决定循环是否继续的批次终止位（这是 L0-② 刚对齐过的条款，此处反而没列）；
3. `:226-245` prepareNextTurn 的快照会**替换 `currentContext` 并换 model/reasoning**；
4. `:247-257` `shouldStopAfterTurn == true` → 发 `agent_end` 并 `return`（不是普通的「继续循环」）；
5. `:263-268` 外层 `while(true)` 的 follow-up 重入与 `:274` 终态 `agent_end`。

另：§1 只覆盖了 turn 与 tool 两个事件族，**pi 还有 `tool_result_message` 事件族**（`:402` `emitToolResultMessage`），文档未列入清单。若只做 turn/tool 两组，应在 §1 明确「本次不对齐 `tool_result_message`」。

### 3.4 ✅ `turnIndex` 不是发明的（但文档白白放过了对齐证据）

文档 §4.1 把 `turnIndex` 说成「便于消费方区分首轮」的自有设计。**pi 确实有**：`packages/coding-agent/src/core/agent-session.ts:344 _turnIndex = 0`，`:729` 在 `agent_start` 重置，`:736`/`:743` 挂到 turn_start/turn_end，`:748` 在 turn_end **之后**自增；类型在 `extensions/types.ts:730/737`。

> 注意分寸：raw `AgentEvent`（`agent/src/types.ts:433-434`）**不带** turnIndex，只有 session 层带。所以正确表述是「对齐 pi 的 **session 层** turnIndex」，而不是「pi 的 agent-loop 有 turnIndex」。文档现在的措辞既不准确也不够有力——应直接引 `agent-session.ts` 坐实。

---

## 四、方案评审（§3–§5）：主干对，四处需修

### 4.1 🔴 **`turn_end` 收口路径漏了 2 处（turn 事件会丢）**

§4.3 把「run 收口 turn_end」放在 `executeTryFinishRun` 的终态分支。但 `ActionExecutor` 有**两条完全绕过 `executeTryFinishRun` 的收口路径**：

```java
// ActionExecutor:440-446  executeTool
if (outcome.terminate() && lane.pendingToolCalls.isEmpty()) {
    return new Action.FinishOperation("completed", true);   // ← 不经过 TryFinishRun
}
// ActionExecutor:465-471  executeToolBatch
if (allTerminate) {
    return new Action.FinishOperation("completed", true);   // ← 同上
}
```

这两条路径下：末轮的 `turn_end` 既不会由「下一轮 `StreamAssistant` 开头」发出（run 已终止），也不会走 TryFinishRun 分支，**永久丢失**，且本轮已执行的工具结果一并丢失。**这是 Phase 22「漏第 6 个 StepAttempt 发射点」的同类错误**，在方案评审阶段就能挡下。

**建议**：把 run 收口的 `turn_end` 放到 **`executeFinishOperation`**（`ActionExecutor:345`）——它是 `phase = IDLE` 的**唯一落点**（`:357`），一处覆盖全部 4 条收口路径（正常 / stop-hook / tool-terminate ×2），天然不需要在每个分支重复。至少也要在 §4.3 显式列出这 2 个补发点。

> 顺带：§4.3 写「终态分支（`phase = IDLE` 之前）」——`executeTryFinishRun` **不设** `phase = IDLE`（它只返回 `FinishOperation`），设 IDLE 的是 `executeFinishOperation:357`。定位描述与代码不符。

### 4.2 🔴 **turn_end 与 turn_start 的顺序：§4.3 代码块与 §9 验收序列互相打架，且都比 pi 反**

- 散文说对了：「**先结束上一轮再开始本轮**」。
- 但 §4.3 的**代码块顺序是 `turn_start` 在前、`turn_end` 在后**——照抄代码块的实现者会得到 `turn_start(N+1) → turn_end(N)` 的颠倒顺序。
- §9 的手工验收序列把颠倒**固化成了验收标准**：`… → turn_start → message_update* → turn_end(message, toolResults) → agent_end`（两轮只有 1 个 `turn_end`，且排在第二个 `turn_start` 之后）。

pi 的顺序是 `turn_end(:224)` 先、下一次 `turn_start(:176)` 后；pi 的 `_turnIndex` 也是在 `turn_end` **之后**自增（`agent-session.ts:748`）。当前写法会让消费者看到「新一轮已开始，上一轮才结束」，`main.ts:298-314` 的既有分支状态机会被带偏。

**建议**：§4.3 代码块调换顺序（turn_end 块在前），§9 验收序列改为
`agent_start → turn_start(0) → message_end(user) → message_update* → tool_execution_start/end → turn_end(0) → turn_start(1) → message_update* → turn_end(1) → agent_end`，
并补一条「`turn_end` 数量 == `turn_start` 数量 == 轮数」的断言（现 §9 只断言了 turn_start 的数量）。

### 4.3 🟡 **`LaneState.turnToolResults` 的生命周期：漏 `reset()`，且 §4.4 给的落点不可实现**

**（a）漏一个重置点。** 文档 §4.4/§8 说在 `run:67` / `runContinue:154` 清除。实际 `stepIndex` 有**三处**重置：`ActionExecutor:73`（`run`）、**`:140`（`reset`）**、`:169`（`runContinue`）。漏掉 `reset()` ⇒ reset 之后残留上一 run 的工具结果，会串进下一 run 的 `turn_end`。（行号也各差 6/15 行；方法名文档 §4.4 叫 `runContinue`、§7/§9 叫 `continueRun`，实际只有 `runContinue`。）

**（b）落点不可实现。** §4.4 写「在 `ActionExecutor.executeTool`（`:439`）与 `executeToolBatch`（`:454`）中，`toolPipeline.appendEntry(...)` 之后累加」。但 **`appendEntry` 是 `ToolExecutionPipeline` 的私有方法**（声明 `:116`，唯一调用点 `:240`，在 `closeToolSpan` 内部），`ActionExecutor` 根本无法调用它——它只在 `:442`/`:458` 调 `executeStages(...)`。照文档字面实现会编译不过。

**建议**：改为「在 `ActionExecutor.executeTool`/`executeToolBatch` 中，**用 `executeStages` 的返回值与入参 `calls` 按位置 zip**，构造 `ToolResultMessage` 累加进 `lane.turnToolResults`」（`executeStages` 的返回顺序与 `calls` 一致，含被拒调用），并补上 `reset()` 的重置。

**（c）截断路径漏采集 → C4 不成立。** `HarnessUtils.failTruncatedToolCalls`（`:169-189`）**直接往 transcript 里塞 `ToolResultMessage`**，完全不经过 pipeline。pi 在该路径下 `turn_end.toolResults` 是**有内容的**（`agent-loop.ts:386-406`，且 pi-java 那条错误文案是逐字抄 pi 的）。按现方案，pi-java 这一轮 `turn_end.toolResults` 会是空列表。⇒ 要么在 `failTruncatedToolCalls` 里一并采集，要么 §5 的 C4 标注为「截断路径不成立」。

### 4.4 🟡 **`HarnessConfig.lifecycleListener`：把「死配置」当模板复制了一遍**

§4.2 说「沿用 `streamListener`（`HarnessConfig:67`）的同构模式」，并新增 `HarnessConfig.lifecycleListener` 字段。但 **`HarnessConfig.streamListener` 是死字段**：全仓库**没有任何地方读 `config.streamListener()`**（唯一 setter 也无人调用、无测试使用）。真正生效的链路是 `AgentHarness.onStreamEvent`（`:168`，返回 `AutoCloseable`）→ `broadcastStreamEvent`（`:174`）→ `ExecutionContext.streamListener`（`ExecutionContext:52`，`Supplier<Consumer<StreamEvent>>`，在 `AgentHarness:135` 绑成 `() -> this::broadcastStreamEvent`）。

§4.2 的**结论是对的**（运行时注册、构造后桥接），但**引错了模板**，并且顺手再造一个同类死字段。

**建议**：删掉 `HarnessConfig.lifecycleListener`，只保留 `AgentHarness.onLifecycleEvent(...)` + `ExecutionContext` 组件（用 `Supplier` 或直接引用），并在 §4.2 改引 `onStreamEvent` 这条**活链路**。若确实想要「配置期静态监听」，那应该先解释为何 `streamListener` 是死的却仍要再造一个。

### 4.5 🟡 **`turnIndex = lane.stepIndex` 语义串台**

`lane.stepIndex` 的真实身份是 **LLM 调用/尝试计数器**，不是轮序号：

- 自增点在 `AssistantStreamExecutor:83`：`int attemptIdx = lane.stepIndex++;`（**文档 §4.3 引的 `ActionExecutor:402` 是错的**——`:402` 是 `lane.phase = RunPhase.ASSISTANT;`）；
- `LaneState:39` 注释为「Monotonic step counter within the current run」；
- LLM span 的 `"attempt"` 属性（`:91`）与 `RunSpanFactory:51` 的 `span.addAttribute("attemptCount", lane.stepIndex)` 都把它当**尝试数**用。

当前恰好「一次 StreamAssistant == 一轮」所以数值相等（截断重试在 pi 与 pi-java 两边**都是新的一轮**，已核对 pi `:207-213`，不会错位），但把公开 API 的语义建在一个「尝试计数器」上，等于把两个概念绑死：日后任何「同一轮内重试」的改动都会静默改变对外 turn 序号。

**建议**：`turnIndex` 用独立字段（在 `run`/`reset`/`runContinue` 三处随 `stepIndex` 一起重置），并直接引 pi `agent-session.ts:344/736/748` 说明语义（0 起、每 run 重置、turn_end 后自增）。

### 4.6 🟡 **与 Phase 21/22 record 日志的关系：全文未提，这是最大的架构疑问**

Phase 21/22 刚刚确立「**record 日志是唯一真相源，状态是它的折叠**」。而本次新增的第三个观测通道（`lifecycleListener`）与既有 record 层**高度重叠**：

| 新事件 | 已有 record（字段更全） |
|---|---|
| `HarnessLifecycleEvent.ToolStarted(toolCallId, toolName, arguments)` | `LaneRecord.ToolStarted(runId, assistantEntryId, toolIndex, toolCallId, toolName, **effectiveArgs**, **resultEntryId**, replay)` |
| `HarnessLifecycleEvent.ToolEnded(toolCallId, toolName, content, isError)` | `LaneRecord.ToolFinished(toolCallId, toolName, isError, **terminate**, resultEntryId, durationMs)` |
| `TurnStarted/TurnEnded(runId, turnIndex, message, toolResults)` | `LaneRecord.StepAttempt(runId, step, attempt, resultEntryId, ...)` |

且 `LaneSnapshot`（含 `transcript` + `records`）已经通过 `watch()` 暴露给订阅者。

文档对这条关系**一字未提**，于是无法回答两个必然会被问到的问题：（1）为什么不能从 record 日志派生这些事件？（2）`effectiveArgs` vs `arguments`、`resultEntryId` 这些既有词汇为何不沿用？

**判断**：新增**实时**通道本身有正当理由（`tool_execution_update` 是亚记录粒度的进度流，record 是事后审计；且 record 走 deferred 写入，不适合做实时推送），但**理由必须写进文档**，否则下次审计会把它当成「绕过真相源的平行通道」。建议 §3 或 §4.2 增一节「与 record 日志的分工」：**实时事件 = 只读旁路，不参与状态派生；record = 审计与恢复的唯一真相源**，并统一词汇（`effectiveArgs` 而非裸 `arguments`）。

### 4.7 🟡 其他

| 项 | 说明 |
|---|---|
| **§7 中 `Object partialResult` / `Map<String,Object> arguments`** | 跨模块公开 API 用 `Object` 承载 payload，与项目 ADT 风格不符；且 wire 序列化无类型信息。至少 `partialResult` 应收窄为 `ContentBlock` 或既有 `partial` 类型 |
| **并发契约缺失** | `ToolUpdated` 由工具虚拟线程回调发射，`ToolEnded` 由主线程发射（`ToolExecutionPipeline:74/154`）。§7 只提了「update 可能高频」，没写「监听器可能被并发调用」。`broadcastStreamEvent` 用 `CopyOnWriteArrayList` 是安全的先例，新通道应对齐并**在 javadoc 写明线程契约** |
| **`turn_start` 发射点与 auto-compaction 的关系** | §4.3 把 `turn_start` 放在 `AssistantStreamExecutor:58` 之后、`:60-62`（`applyPendingTurnUpdate` + `checkAutoCompact`）之前。即**自动压缩发生在 turn 之内**。pi 的压缩是独立操作，不是 turn。是否要把压缩排除在 turn 外，文档应给一句明确表态（现状可接受，但需写明） |
| **§8 step 2 与 §4.3 自相矛盾** | §4.3 说 turn 发射点在 `AssistantStreamExecutor`，§8 step 2 说「`ActionExecutor` 三处 turn 发射」 |

---

## 五、消费方与前端（§4.7）：两处判断过期/不成立

### 5.1 🔴 **「`toolResultsById` 待接线」是过期信息；「换成 `toolNames`」类型不兼容**

文档 §4.7/§7 把前端工具卡片列为「需先确认 `@mariozechner/pi-web-ui` 是否支持 props」的前置风险。实测：

- **`toolResultsById` 早已接线**：`main.ts:611 const toolResultsById = buildToolResultsMap();` → `:776 .toolResultsById=${toolResultsById}`。引入自 commit `53e93c15`（2026-08-28）。文档把它当成待办，**事实错误**。
- **props 确实存在**：`node_modules/@mariozechner/pi-web-ui/dist/components/StreamingMessageContainer.d.ts:5 tools: AgentTool[]; :8 toolResultsById?: Map<...>;`、`MessageList.d.ts:5 tools: AgentTool[];`、`Messages.d.ts:37`。⇒ §7 「若外部组件不支持」的**风险已消解**，应改写为「已确认支持」。
- **真正没接线的只有 `.tools`（`main.ts:766`、`:773` 硬编码 `[]`）**，但文档建议的「换成 `stateSync` 里的 `toolNames`」**做不到**：`.tools` 需要 `AgentTool[]`（含 `name`/`description`/`parameters`/`label`），而 `SerializedAgentState.tools` 是 `string[]`（`frontend/shared/protocol.ts:114`，`main.ts:241` 只拿它显示「N tools」）。**要接线得先新增协议字段把工具 schema 下发**——这是一项独立的后端改动，文档没有识别到。

### 5.2 🟡 **验收清单漏了「会变红的既有测试」**

§9 把 §4.5/§4.8 的改动写成纯新增，但两个既有测试断言的正是**要被删掉的行为**：

- `AgentEventTranslatorTest.startEmitsAgentStart`（`:33`，`@Test` 在 `:32`；**本报告初稿误引 `:40`，
  已于 2026-09-12 更正**）—— 断言 `StreamEvent.Start → agent_start`；
- `AgentEventTranslatorTest.toolCallEventsEmitToolExecution`（`:52`，`:56`/`:59` 断言 `tool_execution_start`/`_end`）。

⇒ 这两条必须在 §8/§9 显式写成「改写/删除」，否则实施者会以为只需新增。

> 另：RPC 侧 `JsonEventMapper` 里 `agent_start` / `tool_result` **都不存在**（前者只在 Web 层，后者只是 `HtmlExporter` 的块类型）。§4.7「RPC：增加 turn_*/tool_execution_* 映射」本身成立且是机械改动（`RpcDispatcher.emitEvent:312-321` 直通 `toWire`，无需改 dispatcher），但文档不应暗示这两个名字已在 wire 上。顺带发现（非本文档责任）：`UserMessageReceived` 在 RPC 侧目前落成 `{"type":"unsupported_event"}`。

---

## 六、引用准确性汇总（行号类）

| 文档引用 | 实际 | 判定 |
|---|---|---|
| `ActionExecutor` ≈ 691 行 | **497 行** | 🔴 错（且 691 本身会破 500 行规约） |
| `lane.stepIndex` 自增在 `ActionExecutor:402` | `AssistantStreamExecutor:83`；`:402` 是 `lane.phase = RunPhase.ASSISTANT;` | 🔴 错 |
| `AssistantStreamExecutor.java:53 execute(...)` | 签名在 **:52**，`:53` 是 abort 检查 | 🟡 差 1 行（描述内容无误） |
| `ActionExecutor:392` prepareNextTurn | ✅ 完全一致 | ✅ |
| `ActionExecutor:439` / `:454` | ✅ | ✅ |
| `ActionExecutor:93-97` 写 user entry | ✅ | ✅ |
| `ActionExecutor:154` runContinue | ✅（名为 `runContinue`，文档 §7/§9 写 `continueRun`） | 🟡 命名不一致 |
| `ToolExecutionPipeline.executeStages:56` | ✅ | ✅ |
| `runRawSafely(LaneState, BeforeToolDecision)` 需补 laneName | ✅ 无 laneName | ✅ |
| `ToolExecutor.executeRaw:106` 传 `null` onUpdate | ✅ `:106` 正是 `registry.execute(..., null, ...)` | ✅ |
| `AgentSession.java:257` HarnessConfig 先于 AgentSession | ✅（AgentSession 在 `:273`） | ✅ |
| `SessionRunner:131` UserMessageReceived 在 `harness.run`(:111) 之后 | ✅ | ✅ |
| `main.ts:298-300` / `:302-314` | ✅ 逐行一致 | ✅ |
| `main.ts:764-777` `.tools=${[]}` / `:471-479 buildToolResultsMap` | ✅ 行号对；`toolResultsById` 已接线的判断错 | 🟡 |
| `docs/16` 记录「fires after turn_end」 | ❌ 该句**不在 docs/16**，逐字出现在 `ActionExecutor.java:388` 与 `docs/superpowers/plans/2026-08-28-agent-loop-alignment.md:346`；`docs/16:74` 只有等价的中文表述 | 🔴 出处错（但「存在偏差」的结论成立） |
| `docs/19` 行为条款映射表 | ✅ 存在于 `docs/19 §10`（`:349`） | ✅ |

---

## 七、必改清单（按优先级）

**P0（不改就会产生错误行为）**

1. **§4.3 收口 `turn_end`**：移到 `executeFinishOperation`（或补列 `executeTool`/`executeToolBatch` 两条 terminate 路径）。
2. **§4.3 顺序**：代码块改为 `turn_end(上一轮)` 在前、`turn_start(本轮)` 在后；§9 验收序列同步修正，并加「turn_end 数 == turn_start 数 == 轮数」断言。
3. **§4.4 落点**：改为「用 `executeStages` 返回值与 `calls` zip 累加」；重置点补 `reset():140`（共三处）。
4. **C6 与「被拒调用不发 start」**：或改成 pi 的顺序（start 在 hook 之前），或移入 §6 有意偏差并删掉「对齐 pi」措辞。

**P1（影响可实施性/正确性）**

5. **截断路径**：`failTruncatedToolCalls` 的工具结果需计入 `turnToolResults`，否则 C4 该路径不成立。
6. **删 `HarnessConfig.lifecycleListener`**，改引 `onStreamEvent` 活链路；§4.2 说明 `streamListener` 字段已死。
7. **`turnIndex` 独立字段**，并引 pi `agent-session.ts:344/736/748` 坐实「对齐 session 层」。
8. **§4.7 前端**：删掉「`toolResultsById` 待接线」「组件可能不支持」两处过期判断；说明 `.tools` 需要**新增协议字段下发工具 schema**，否则不可接线。
9. **§9**：把 `startEmitsAgentStart`、`toolCallEventsEmitToolExecution` 标为「改写」而非新增。

**P2（严谨性与可读性）**

10. 增一节「与 record 日志的分工」，统一 `effectiveArgs` 等词汇。
11. §1 补齐 §1.2 漏列项（截断分支 / terminate 位 / shouldStop 出口 / follow-up 重入），并声明 `tool_result_message` 不在范围内。
12. C9 引用改为 `:499-505`；`ActionExecutor` 行数改 497；`stepIndex` 引用改 `AssistantStreamExecutor:83`；`docs/16` 出处更正。
13. §7 补并发契约（`ToolUpdated` 由工具线程回调）、`Object partialResult` 收窄、压缩是否算 turn 的明确表态。
14. §8 step 2 与 §4.3 的「发射点在哪个文件」表述统一。

---

## 八、可选强化（超出「对齐」范围但值得考虑）

1. **增加一份替代方案对比**。文档自称「方案 B」却从未给出 A 或对比。建议补一小节：B（发射点下沉到执行层）vs A（继续在翻译层补字段）vs C（由 record 日志派生），各 2–3 行 trade-off——尤其是 C 方案，能在 Phase 21 的架构前提下给出「为何不这样做」的书面理由（见 §4.6）。
2. **`tool_execution_update` 的节流**：§7 已提到「建议 Web 侧节流或直接不推送」。建议把它升级为设计决策（例如 harness 侧做合并/丢弃窗口），否则又是一个「文档说建议、无人实现」的悬空项。
3. **`AgentStart`（§4.8）**：建议**独立立项**。它改动的是 `StreamEvent.Start` 的语义边界（Web 层内部状态 `streaming` 与 `agent_start` 目前共用一个 case），与 turn 生命周期耦合低，且会连带改 1 个既有测试；放在同一批交付会放大回归面。

---

## 九、结论

- **问题真实**：§0 的四项现象与两条根因全部核实成立，这是本仓 Web/前端事件层的一个**真实且已存在一段时间**的缺陷（前端两段死代码 + `agent_start` 语义冒充 `turn_start` + 工具执行事件发错阶段）。**值得做。**
- **方向正确**：把执行阶段事件下沉到 `ToolExecutionPipeline`、删除 `StreamEvent.ToolCall*` 的错位翻译、并把 run/turn 语义补进 `AgentSessionEvent`，是正确且有限的一步。
- **但方案尚不能照做**：4 处 P0（收口覆盖、事件顺序、累加落点不可实现、C6 对齐声明不实）+ 5 处 P1 会让实施者产出错误行为或编译不过；其中「收口漏路径」「累加落点私有方法」两项属于**方案评审阶段就应当挡下**的缺陷（与 Phase 22 漏发射点同类）。
- **评分 69/100 → 🟡 需修订后实施。** 修订量不大（预计 §4.3/§4.4/§4.7/§9 四处正文 + 一张引用更正表），改完即可作为 Phase 23 的实施蓝图。
