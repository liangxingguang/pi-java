# 23 — Run / Turn / Tool 生命周期对齐设计（方案 B）

> 对齐基准：pi `packages/agent/src/agent-loop.ts` 的 `runLoop` / `runAgentLoop` /
> `runAgentLoopContinue` 与 `executeToolCalls*` / `prepareToolCall` / `executePreparedToolCall`。
> 关联文档：`docs/19`（行为差异）、`docs/20`（优化方案）、`docs/22`（deferred 写入与 entry 溯源）。
> 编制日期：2026-09-12。
>
> **代码行号基准**：`docs/22` D7 拆分**之后**——assistant 流执行已从 `ActionExecutor` 抽出到
> `AssistantStreamExecutor`。文中 `ActionExecutor:*` 均为拆分后的行号（该文件现约 691 行）。

覆盖两组事件，共用同一条跨层通道（§4.2）：

- **turn 生命周期** —— `turn_start` / `turn_end`（§1.1–§1.2、§4.3）
- **工具执行生命周期** —— `tool_execution_start` / `_update` / `_end`（§1.3、§4.5）

## 0. 问题陈述

pi-java **既没有 turn 概念，也没有真正的工具执行事件**。`AgentSessionEvent` 的 13 个变体中
无 `TurnStart` / `TurnEnd`，全仓库 0 处发射 `turn_start`；`tool_execution_*` 虽有发射，
但只带一个 `type` 字段。后果有四：

| # | 现象 | 位置 |
|---|---|---|
| 1 | 前端 `case "turn_start"` 永不触发（死代码） | `frontend/client/main.ts:298-300` |
| 2 | 前端 `case "turn_end"` 的 `event.toolResults` 恒为 `undefined`，整段死代码 | `frontend/client/main.ts:302-314` |
| 3 | `agent_start` **每轮**发一次（pi 是每 run 一次），语义错位 | `AgentEventTranslator.java:67-70` |
| 4 | `tool_execution_*` 无 `toolName` / `args` / `result`，前端工具卡片渲染不出 | `AgentEventTranslator.java:73-78` |

**根因一（第 1–3 项）**：把「每个 `StreamEvent.Start`（每轮一次）」当成了
「`agent_start`（每 run 一次）」发射，于是 `agent_start` 事实上在冒充 `turn_start`。

**根因二（第 4 项）——阶段错位**：pi 的 `tool_execution_*` 在**工具执行阶段**发射
（`agent-loop.ts:445`）；pi-java 却从 **LLM 输出阶段**的 `StreamEvent.ToolCallStart/Delta/End`
翻译，而这些流事件只有 `contentIndex`，本就不含 name/args。
等于把「模型吐出工具调用」当成了「工具开始执行」——不是漏字段，是**发错了阶段**。详见 §4.5。

---

## 1. pi 的语义（精确定义）

### 1.1 发射点

```109:114:packages/agent/src/agent-loop.ts
	await emit({ type: "agent_start" });
	await emit({ type: "turn_start" });
	for (const prompt of prompts) {
		await emit({ type: "message_start", message: prompt });
		await emit({ type: "message_end", message: prompt });
	}
```

```174:179:packages/agent/src/agent-loop.ts
		while (hasMoreToolCalls || pendingMessages.length > 0) {
			if (!firstTurn) {
				await emit({ type: "turn_start" });
			} else {
				firstTurn = false;
```

```196:200:packages/agent/src/agent-loop.ts
			if (message.stopReason === "error" || message.stopReason === "aborted") {
				await emit({ type: "turn_end", message, toolResults: [] });
				await emit({ type: "agent_end", messages: newMessages });
				return;
			}
```

```224:224:packages/agent/src/agent-loop.ts
			await emit({ type: "turn_end", message, toolResults });
```

### 1.2 一轮的完整顺序

```
turn_start
  ├─ inject pendingMessages（steering）        :182-190
  ├─ streamAssistantResponse（LLM）            :193
  ├─ [error/aborted] → turn_end + agent_end, return   :196-200
  ├─ executeToolCalls → toolResults            :207-222
  ├─ push toolResults 进 context               :218-221
  ├─ turn_end(message, toolResults)            :224
  ├─ prepareNextTurn                           :232
  ├─ shouldStopAfterTurn                       :248
  └─ pendingMessages = getSteeringMessages     :259
```

关键约束：

- **C1** `agent_start` 每 run 一次（`:109` / `:138`），`turn_start` 每轮一次
- **C2** 首轮 `turn_start` **早于**用户消息的 `message_start/end`（`:110` 在 `:111` 前）
- **C3** 工具执行**属于本轮**：`turn_end` 在工具执行之后（`:224` 在 `:207-222` 后）
- **C4** `turn_end` 携带本轮的 `message` 与 `toolResults`
- **C5** `turn_end` → `prepareNextTurn` → `shouldStopAfterTurn` 顺序固定

### 1.3 工具执行事件的语义

pi 的 `tool_execution_*` **不是**流事件，而是执行阶段事件：

```445:450:packages/agent/src/agent-loop.ts
		await emit({
			type: "tool_execution_start",
			toolCallId: toolCall.id,
			toolName: toolCall.name,
			args: toolCall.arguments,
		});
```
```687:693:packages/agent/src/agent-loop.ts
					emit({
						type: "tool_execution_update",
						toolCallId: prepared.toolCall.id,
						toolName: prepared.toolCall.name,
						args: prepared.toolCall.arguments,
						partialResult,
					}),
```
```767:774:packages/agent/src/agent-loop.ts
	await emit({
		type: "tool_execution_end",
		toolCallId: finalized.toolCall.id,
		toolName: finalized.toolCall.name,
		result: finalized.result,
		isError: finalized.isError,
	});
```

约束：

- **C6** `tool_execution_start` 在**参数校验（`:618`）与钩子（`:619`）之后、执行之前**发射，
  携带 `toolCallId` / `toolName` / `args`
- **C7** `tool_execution_update` 由 `tool.execute` 的 `onUpdate` 回调驱动（`:683-696`），
  携带 `partialResult`；工具不回调则不发
- **C8** `tool_execution_end` 携带**完整结果** `result`（含 content/details/usage）与 `isError`；
  被 `beforeToolCall` 阻断时走 `:514`、`beforeResult.terminate` 透传到 `:638-639`
- **C9** 并行批次中 `start` 事件**顺序发射**（`:444-450` 循环内），实际执行是 `Promise.all`（`:540`）

> 对照：pi 的**流阶段**也有 `toolcall_start/delta/end`，但那是 `assistantMessageEvent`，
> 被包进 `message_update`（`:336-342`），与 `tool_execution_*` 是两套不同的东西。

---

## 2. pi-java 的结构差异（设计前提）

pi 一轮 = `streamAssistant` + `executeToolCalls` **在同一循环体内**。
pi-java 因 MANUAL 驱动把一轮拆成多个 Action：

```
StreamAssistant → [LLM] → 写 assistant entry → phase=CHECKPOINT
  → TryFinishRun(outcome)
      ├─ tool_use → pendingToolCalls → phase=ASSISTANT
      │     → ExecuteTool(Batch) → ToolResultMessage 入 transcript
      │     → 回到 StreamAssistant（第二轮）
      └─ completed/error → run 收口
```

因此 pi-java 中：

- 「工具执行」发生在 `TryFinishRun` **之后**，而 pi 发生在 `turn_end` **之前**
- 一轮的结束点 = **下一轮 `StreamAssistant` 即将执行时**（或 run 收口时）
- `prepareNextTurn` 在 `ActionExecutor:392`（`executeTryFinishRun` 内）触发，位于工具执行**之前**，与 pi 的 C5 顺序相反（既有错位，见 §6）

---

## 3. 方案 B 设计总览

```
agent-core                            coding-agent                    消费方
─────────────────────────────────────────────────────────────────────────────
ActionExecutor
  executeTryFinishRun      ─┐ TurnEnded（run 收口补发）
AssistantStreamExecutor    ─┤ TurnStarted / TurnEnded（每轮开头）
ToolExecutionPipeline       ─┘ ToolExecutionStarted / Updated / Ended
  （before/after 钩子之间）  │
                             ↓
                    ctx.lifecycleListener()
                             ↓  AgentHarness.onLifecycleEvent(桥接)
AgentSession.emitSessionEvent(TurnStart / TurnEnd / ToolExecution*)
                             ↓  eventHub
        ┌────────────────────┼────────────────────┐
        ↓                    ↓                    ↓
Web AgentEventTranslator   RPC JsonEventMapper   其他订阅者
  {type:"turn_start"}        JSON wire
  {type:"turn_end",
   message, toolResults}
  {type:"tool_execution_start",
   toolCallId, toolName, args}
  {type:"tool_execution_update", …partialResult}
  {type:"tool_execution_end", …result, isError}
        ↓
frontend main.ts（turn 分支 298-314 已存在；工具卡片需组件侧接线）
```

---

## 4. 详细设计

### 4.1 事件模型

`AgentSessionEvent` 新增五个变体：

```java
/** 一轮开始（对齐 pi turn_start）。turnIndex 从 0 起，每个 run 重置。 */
record TurnStart(int turnIndex) implements AgentSessionEvent {}

/**
 * 一轮结束（对齐 pi turn_end）。
 * @param message     本轮最终 assistant 消息（含 stopReason）
 * @param toolResults 本轮执行的工具结果（无工具调用时为空列表）
 */
record TurnEnd(AssistantMessage message, List<Message> toolResults)
        implements AgentSessionEvent {}

/** 工具开始执行（对齐 pi tool_execution_start，C6）。 */
record ToolExecutionStart(String toolCallId, String toolName,
                          Map<String, Object> arguments) implements AgentSessionEvent {}

/** 工具执行中的进度回调（对齐 pi tool_execution_update，C7）。 */
record ToolExecutionUpdate(String toolCallId, String toolName,
                           Object partialResult) implements AgentSessionEvent {}

/**
 * 工具执行结束（对齐 pi tool_execution_end，C8）。
 * @param content 结果内容块（对应 pi result.content）
 * @param isError 是否为错误结果
 */
record ToolExecutionEnd(String toolCallId, String toolName,
                        List<ContentBlock> content, boolean isError)
        implements AgentSessionEvent {}
```

> `turnIndex` 便于消费方区分首轮；也可用它驱动 `agent_start` 的配套修正（§4.8）。
> `ToolExecutionEnd` 只透传 `content` + `isError`：**不序列化 `details`**——
> `ToolResult<TDetails>` 的 details 是各工具私有类型（如 `ReadDetails`），跨 wire 传输需要
> 额外的类型注册表，收益不明；pi 的 `result` 虽完整，但 Web 前端实际只消费 content/isError。

### 4.2 跨层通道（turn / tool 共用）

依赖方向 `agent-core ← coding-agent`，所以 agent-core **不能**引用 `AgentSessionEvent`。
沿用 `streamListener`（`HarnessConfig:67`）的同构模式，且 turn 与 tool **共用一条通道**
（二者都是 harness 生命周期事件，分开注册只会增加桥接样板）：

```java
// com.pijava.agent.harness.HarnessLifecycleEvent（agent-core，public sealed interface）
public sealed interface HarnessLifecycleEvent {

    record TurnStarted(String lane, String runId, int turnIndex)
            implements HarnessLifecycleEvent {}

    record TurnEnded(String lane, String runId, AssistantMessage message,
                     List<Message> toolResults) implements HarnessLifecycleEvent {}

    record ToolStarted(String lane, String runId, String toolCallId, String toolName,
                       Map<String, Object> arguments) implements HarnessLifecycleEvent {}

    record ToolUpdated(String lane, String runId, String toolCallId, String toolName,
                       Object partialResult) implements HarnessLifecycleEvent {}

    record ToolEnded(String lane, String runId, String toolCallId, String toolName,
                     List<ContentBlock> content, boolean isError)
            implements HarnessLifecycleEvent {}
}
```

- `HarnessConfig` 增加字段 `Consumer<HarnessLifecycleEvent> lifecycleListener`
  （默认 `event -> { }`，与 `streamListener` 一致）
- `ExecutionContext` 增加对应组件；`ActionExecutor` 与 `ToolExecutionPipeline`
  通过 `ctx.lifecycleListener()` 发射
- `AgentHarness` 增加运行时注册（与 `onStreamEvent:168` 同构）：

```java
public AutoCloseable onLifecycleEvent(Consumer<HarnessLifecycleEvent> listener)
```

> 之所以用运行时注册而非仅 HarnessConfig 字段：`AgentSession.assemble` 里 HarnessConfig 先于
> `AgentSession` 构造（`AgentSession.java:257`），桥接闭包需要 session 实例，必须在构造后注册。

### 4.3 turn 发射点（AssistantStreamExecutor）

> **注意（docs/22 D7 拆分后）**：assistant 流执行已从 `ActionExecutor` 抽出到
> `AssistantStreamExecutor`（`execute(laneName, lane, sa)`），turn_start / turn_end 的发射点随之移到该文件。

**turn_start** —— `AssistantStreamExecutor.execute` 开头，abort 检查之后、上下文组装之前：

```java
// AssistantStreamExecutor.java:53 execute(...)，abort 检查之后
ctx.lifecycleListener().accept(
    new HarnessLifecycleEvent.TurnStarted(laneName, lane.runId, lane.stepIndex));
```

`lane.stepIndex` 在每次 LLM 请求前自增（`:402`），天然就是轮序号。

**turn_end** —— 同样在 `AssistantStreamExecutor.execute` 开头，**先结束上一轮再开始本轮**：

```java
if (lane.stepIndex > 0) {
    ctx.lifecycleListener().accept(new HarnessLifecycleEvent.TurnEnded(
        laneName, lane.runId, lane.partial, List.copyOf(lane.turnToolResults)));
    lane.turnToolResults.clear();
}
```

理由：pi-java 一轮的结束点就是「下一轮 LLM 即将开始」（§2），此处 `lane.turnToolResults`
已收集完上一轮全部工具结果。

**run 收口路径** —— `executeTryFinishRun` 的终态分支（`phase = IDLE` 之前）也要补发 turn_end，
否则最后一轮（无后续 LLM）不会发：

```java
// 终态：completed / error / shouldStopAfterTurn == true
ctx.lifecycleListener().accept(new HarnessLifecycleEvent.TurnEnded(
    laneName, lane.runId, lane.partial, List.copyOf(lane.turnToolResults)));
lane.turnToolResults.clear();
```

### 4.4 本轮工具结果的收集

`LaneState` 新增字段：

```java
/** 本轮已执行的工具结果（turn_end 时读取后清空）。 */
final List<Message> turnToolResults = new ArrayList<>();
```

在 `ActionExecutor.executeTool`（`:439`）与 `executeToolBatch`（`:454`）中，
`toolPipeline.appendEntry(...)` 之后累加：

```java
toolPipeline.appendEntry(lane, et, outcome);
lane.turnToolResults.add(new Message.ToolResultMessage(
    et.toolCallId(), et.toolName(), outcome.blocks(), outcome.isError()));
```

并在 `ActionExecutor.run:67` / `runContinue:154` 中 `lane.turnToolResults.clear()`（新 run 重置）。

> 不用「从 transcript 回溯」的理由：并行工具批次、多个 assistant 内容块会让回溯边界不稳定；
> 显式累加与 pi 的 `toolResults`（`:205, :215`）语义一一对应。

### 4.5 工具执行生命周期（ToolExecutionPipeline）

**正确的发射层是 `ToolExecutionPipeline`，不是 `StreamEvent` 翻译层。** 三处发射点：

```java
// ToolExecutionPipeline.executeStages:56 内

// ① C6：before_tool 钩子之后、真正执行之前（顺序发射，符合 C9）
if (decision.allowed()) {
    ctx.lifecycleListener().accept(new HarnessLifecycleEvent.ToolStarted(
        laneName, lane.runId, et.toolCallId(), et.toolName(), decision.args()));
}

// ② C7：执行中——在 runRawSafely 内传真实回调
//    （现有签名 runRawSafely(LaneState, BeforeToolDecision) 需补 laneName 参数）
private RawToolResult runRawSafely(String laneName, LaneState lane,
                                   BeforeToolDecision decision) {
    ...
    var result = ctx.toolExecutor().executeRaw(
        decision.call().toolName(), decision.call().toolCallId(),
        decision.args(), lane.abortSignal,
        partial -> ctx.lifecycleListener().accept(new HarnessLifecycleEvent.ToolUpdated(
            laneName, lane.runId, decision.call().toolCallId(),
            decision.call().toolName(), partial)));

// ③ C8：after_tool 钩子之后（outcome 已是最终值）
ctx.lifecycleListener().accept(new HarnessLifecycleEvent.ToolEnded(
    laneName, lane.runId, et.toolCallId(), et.toolName(),
    outcome.blocks(), outcome.isError()));
```

配套改动与边界：

| 项 | 说明 |
|---|---|
| **必须的签名改动** | `ToolExecutor.executeRaw:106` 目前给 `onUpdate` 传 `null`，工具拿不到回调，C7 无从发射。需新增带 `ToolUpdateCallback<?>` 的重载并透传 |
| **并行批次** | `start` 在 `beforeToolDecision` 循环后**顺序**发射（C9，对齐 pi `:444-450`）；`update`/`end` 由各虚拟线程并发发射，消费方按 `toolCallId` 关联 |
| **被拒绝的调用** | 不发 `start`，直接发 `end`（content = `Tool call denied by hook`，`isError=true`），对齐 pi `:514` 与 `:638-639` 的 immediate outcome |
| **旧翻译要删除** | `AgentEventTranslator:73-78` 的 `StreamEvent.ToolCall* → tool_execution_*` 必须移除；流阶段的 `toolcall_*` 改发 `message_update`（对齐 pi `:336-342`，见 §4.7） |

### 4.6 桥接（AgentSession）

```java
// AgentSession.assemble(...) 内，AgentSession 构造之后
harness.onLifecycleEvent(ev -> agentSession.emitSessionEvent(switch (ev) {
    case HarnessLifecycleEvent.TurnStarted t ->
        new AgentSessionEvent.TurnStart(t.turnIndex());
    case HarnessLifecycleEvent.TurnEnded t ->
        new AgentSessionEvent.TurnEnd(t.message(), t.toolResults());
    case HarnessLifecycleEvent.ToolStarted t ->
        new AgentSessionEvent.ToolExecutionStart(
            t.toolCallId(), t.toolName(), t.arguments());
    case HarnessLifecycleEvent.ToolUpdated t ->
        new AgentSessionEvent.ToolExecutionUpdate(
            t.toolCallId(), t.toolName(), t.partialResult());
    case HarnessLifecycleEvent.ToolEnded t ->
        new AgentSessionEvent.ToolExecutionEnd(
            t.toolCallId(), t.toolName(), t.content(), t.isError());
}));
```

### 4.7 消费方

**Web**（`AgentEventTranslator.translate`）新增五个 case，payload 必须完整：

```java
case AgentSessionEvent.TurnStart t ->
    out.add(new WebServerMessage.AgentEvent(typeNode("turn_start")));
case AgentSessionEvent.TurnEnd t ->
    out.add(turnEnd(t.message(), t.toolResults()));       // + message, toolResults
case AgentSessionEvent.ToolExecutionStart t ->
    out.add(toolStart(t));      // {type, toolCallId, toolName, args}
case AgentSessionEvent.ToolExecutionUpdate t ->
    out.add(toolUpdate(t));     // {type, toolCallId, toolName, partialResult}
case AgentSessionEvent.ToolExecutionEnd t ->
    out.add(toolEnd(t));        // {type, toolCallId, toolName, result:{content}, isError}
```

`turnEnd(...)` 用 `WebWireJson.messageNode(...)` 序列化，**必须带 payload**——否则前端
`main.ts:303` 的 `if (event.toolResults)` 仍恒假。

同时**删除**阶段错位的旧翻译（§0 根因二）：

```java
// 删除：StreamEvent.ToolCallStart/Delta/End → tool_execution_start/update/end
// 改为：与 pi `agent-loop.ts:336-342` 一致，作为 message_update 推送
case StreamEvent.ToolCallDelta d -> out.add(messageUpdate(d.partial()));
```

**RPC**：`JsonEventMapper` 增加 `turn_start` / `turn_end` / `tool_execution_*` 的 wire 映射。

**前端**：

- turn 分支 `main.ts:298-314` **已存在**，后端补齐 payload 后自动生效，无需改动
- 工具卡片属于**前端侧未接线**：`main.ts:764-777` 的 `.tools=${[]}` 是硬编码空数组，
  需换成 `stateSync` 里的 `toolNames`（已由 `SerializedAgentState` 下发），
  并把 `buildToolResultsMap():471-479` 的 `toolResultsById` 接到
  `<streaming-message-container>`。这两处依赖 `@mariozechner/pi-web-ui` 的 props 支持；
  **若外部组件不支持，后端补齐 payload 后仍只能在 `agent_end` 后以 toolResult 消息展示**
  ——因此前端接线是本项收益的前置条件，需先确认组件能力

### 4.8 配套修正：`agent_start` 每 run 一次

现状 `AgentEventTranslator:67-70` 把每个 `StreamEvent.Start` 翻成 `agent_start`。
对齐 pi C1 后应改为：

- 新增 `AgentSessionEvent.AgentStart()`，由 `SessionRunner` 在 `harness.run` 前 emit
- `AgentEventTranslator` 的 `translateStream` **移除** `Start → agent_start` 分支，
  仅保留 `streaming = true` 的内部状态维护

> 此项与 turn 生命周期同属「run/turn 事件对齐」，但可独立拆分落地；
> 若不改，多轮对话仍会收到 N 个 `agent_start`。

---

## 5. 对齐检查表

| # | pi 约束 | pi-java 落点 | 状态 |
|---|---|---|---|
| C1 | `agent_start` 每 run 一次 | §4.8 配套修正 | ⬜ |
| C2 | 首轮 `turn_start` 早于 user `message_end` | ⚠️ **不可对齐**（见 §6） | ➖ |
| C3 | 工具执行属于本轮，`turn_end` 在其后 | §4.3（下一轮 StreamAssistant 开头 + 收口分支） | ⬜ |
| C4 | `turn_end` 带 `message` + `toolResults` | §4.3 + §4.4 + §4.7 | ⬜ |
| C5 | `turn_end` → `prepareNextTurn` → `shouldStopAfterTurn` | ⚠️ **不可对齐**（见 §6） | ➖ |
| C6 | `tool_execution_start` 带 `toolCallId/toolName/args`，执行前发射 | §4.5 ① | ⬜ |
| C7 | `tool_execution_update` 带 `partialResult`，由 `onUpdate` 驱动 | §4.5 ② + `executeRaw` 重载 | ⬜ |
| C8 | `tool_execution_end` 带 `result` + `isError` | §4.5 ③ | ⬜ |
| C9 | 并行批次中 `start` 顺序发射 | §4.5（beforeToolDecision 循环后） | ⬜ |

---

## 6. 已知不可对齐项（明确记录，不强行拉平）

1. **C2 首轮时序**：pi 的 `turn_start`(`:110`) 在用户消息 `message_start/end`(`:111-114`) 之前；
   pi-java 的 `UserMessageReceived` 在 `harness.run` 之后发射（`SessionRunner:131`），
   而 turn_start 在 `AssistantStreamExecutor.execute`（晚于 user entry 写入 `ActionExecutor:93-97`）。
   调整顺序需要改动 `ActionExecutor.run` 的 entry 写入时机，风险大于收益。
   **前端只做 `isStreaming`/`renderApp`，顺序差异无可观察影响。**

2. **C5 prepareNextTurn 位置**：pi 在 `turn_end`(`:224`) 之后、工具执行之后触发；
   pi-java 的 `firePrepareNextTurn`（`ActionExecutor:392`）在 `TryFinishRun` 中触发，
   即工具执行**之前**。这是 MANUAL 驱动拆分 Action 的固有结果（§2）。
   `docs/16` 记录该对齐时已注明「fires after turn_end」，实际位置有偏差——
   **不在本方案范围内，建议单独立项。**

3. **C8 的 `result` 只透传 `content` + `isError`**（有意偏差）：pi 的 `result` 是完整
   `AgentToolResult{content, details, usage, terminate, addedToolNames}`。`details` 是各工具
   私有类型（如 `ReadDetails`），跨 wire 传输需要额外类型注册表，且前端 `@mariozechner/pi-web-ui`
   是否消费未知——故本方案不透传（§4.1）。若后续前端需要 `details` 渲染（如 diff 视图），
   再单独立项设计序列化方案。

---

## 7. 影响面与风险

| 项 | 说明 |
|---|---|
| 新增公开 API | `HarnessLifecycleEvent`（agent-core，sealed 5 变体）、`AgentHarness.onLifecycleEvent`、`AgentSessionEvent` 的 5 个新变体 |
| 兼容性 | 纯新增，无破坏；`0.1.0-SNAPSHOT` 期内安全。`ToolExecutor.executeRaw` 保留原 4 参签名，**新增**带 `ToolUpdateCallback<?>` 的重载 |
| 驱动模式覆盖 | 发射点放在 `ActionExecutor` / `AssistantStreamExecutor` / `ToolExecutionPipeline`（而非 `SessionRunner`），**MANUAL 与 AUTOMATIC 同时生效**；若放 `SessionRunner` 则在 `runToCompletion`（`AgentHarness:523`）下失效 |
| 事件量 | 每轮 +2（turn）；**每个工具调用 +2（start/end）**，有进度回调的工具另加 N 条 update。并行批次下 update 可能高频，建议 Web 侧对 `tool_execution_update` 做节流或直接不推送（pi 亦非必需） |
| 序列化边界 | `tool_execution_end` **只传 `content` + `isError`**，不透传 `ToolResult.details`（各工具私有类型，无跨 wire 类型注册表） |
| 重试路径 | `continueRun`（`ActionExecutor:154`）会重置 `stepIndex`，turnIndex 从 0 重新计数；与 pi `agentLoopContinue`（重新 `agent_start` + `turn_start`）一致 |
| 前端前置依赖 | 工具卡片的最终收益依赖 `@mariozechner/pi-web-ui` 的 `.tools` / `toolResultsById` props；若组件不支持，后端补齐 payload 后仍只能在 `agent_end` 后展示（§4.7） |
| 测试 | 需覆盖：单轮无工具、多轮带工具（并行/串行）、before_tool 拒绝、error 路径、abort 路径、AUTOMATIC 驱动 |

---

## 8. 实施步骤

1. agent-core：新增 `HarnessLifecycleEvent`（sealed 5 变体）；`HarnessConfig.lifecycleListener`
   + `ExecutionContext` 组件 + `AgentHarness.onLifecycleEvent`
2. agent-core：`LaneState.turnToolResults`；`ActionExecutor` 三处 turn 发射
   （turn_start / 上一轮 turn_end / 收口 turn_end）+ 工具结果累加
3. agent-core：`ToolExecutor.executeRaw` 新增带回调重载；`ToolExecutionPipeline` 三处发射（C6/C7/C8）；
   并行批次 start 顺序发射（C9）
4. coding-agent：`AgentSessionEvent` 新增 5 个变体；`AgentSession.assemble` 桥接（sealed switch）
5. pi-java-web：`AgentEventTranslator` 5 个 case（含 payload 构造）；**删除** `StreamEvent.ToolCall*`
   → `tool_execution_*` 的旧翻译，改发 `message_update`
6. pi-java-coding-agent RPC：`JsonEventMapper` wire 映射
7. 前端（可选，见 §7 前置依赖）：`main.ts:764-777` 的 `.tools` 接线真实 `toolNames`
8. 配套（可拆）：`AgentSessionEvent.AgentStart` + 移除 `Start → agent_start`
9. 测试：单轮 / 多轮工具 / 并行批次 / 钩子拒绝 / error / abort / AUTOMATIC

> 步骤 1–4 是后端主干，可独立交付；步骤 5–6 是消费方；步骤 7 依赖外部组件能力确认。

## 9. 验收

- `mvn clean verify` 零错误零警告
- `AgentEventTranslatorTest` 新增：
  - `turnStartEmittedPerTurn`（多轮时 turn_start 数量 == 轮数）
  - `turnEndCarriesMessageAndToolResults`（payload 非空）
  - `toolExecutionStartCarriesToolNameAndArgs`（C6，含并行批次顺序）
  - `toolExecutionEndCarriesResultAndIsError`（C8）
  - `deniedToolCallEmitsEndOnly`（before_tool 拒绝时不发 start）
  - `multiTurnEmitsOneAgentStart`（配套项落地后）
- `ToolBatchParityTest` 扩展：串行/并行两种路径都能收到完整的 `tool_execution_start → _end` 对
- 手工验证：Web 端发起「读文件并总结」，DevTools WS 帧序列为
  `agent_start → turn_start → message_end(user) → message_update*`
  `→ tool_execution_start(toolName,args) → tool_execution_end(result,isError)`
  `→ turn_start → message_update* → turn_end(message, toolResults) → agent_end`
- `docs/19` 增补 turn / tool 两组条目到行为条款映射表
