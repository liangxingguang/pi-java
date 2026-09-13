# 31 — agent loop 宿主层对齐 pi `agent.ts`

> **状态：待评审。** 编制日期 2026-09-13。**基线 pi `v0.85.1`**（见 `docs/27`）。
> **2026-09-13 修订**：§2.2、§3.1、§4.1、§4.2、§8 按**读 pi 实测**更正 ——
> 初稿的「真源翻转」与「压缩原地改写」两条**被证伪**，见 §8。
>
> **前置**：`docs/27`（对齐规则 = pi 稳定版）· `docs/28`（驱动已换成 `PiLoop`）·
> `docs/30`（record-log 折叠链已退休）。
>
> **已裁决**（用户，2026-09-13）：②消息为 agent 层真源 —— **对齐 pi**；③`pendingWrites` —— **删除**。

---

## 1. 范围与对齐目标

### 1.1 对齐什么

pi 的「agent loop 部分」是两个文件：

| pi | 行数 | 内容 |
|---|---|---|
| `packages/agent/src/agent-loop.ts` | 803 | 双循环：`runAgentLoop` / `runLoop` / `streamAssistantResponse` / `executeToolCalls` |
| `packages/agent/src/agent.ts` | 592 | 循环的**宿主** `Agent`：状态、事件归约、run 生命周期 |

**`agent-loop.ts` 基本搬完**（`PiLoop` 433 + `PiLoopTools` 171 = 604 行，对 803 是 0.75 倍），
经 L5 差分验证（`docs/29`：8/8 剧本通过，7 个逐字节相同）。

> ⚠️ **但不是完全 1:1**：`AgentLoopTurnUpdate` 少一个 `context` 字段（`docs/31 §8.3-6`），
> 而这正是压缩的落地通道。**实施的第 1 步就是补它** —— 见 §8.3-6。

**除那一处外，本文只处理 `agent.ts` 这一层。** pi-java 把已对齐的循环插进了一套 pi 没有的
状态机里 —— 那是本文要拆的东西。

### 1.2 明确排除

| 排除项 | 理由 |
|---|---|
| pi 的 `harness/` 层（`agent-harness.ts`、`runtime/lane.ts`、`runtime/drive/*`） | `docs/27 §4` 已排除；这一层 pi 自己正在用 pico 替换 |
| pi 的 `pico` | 同上。`docs/27` 的对齐规则指向**产品层**，不是内层的两套实验框架 |

**这条排除有一个直接推论：`lane` 概念不在对齐目标内。**

```ts
// pi v0.85.1  packages/agent/src/harness/agent-harness.ts:587
lane(name: string, context: Context): Promise<AgentLane>;
```

车道的真实来源是 harness 层。实测三件事：

```
pi 侧 lane 名恒为 "main"            : harness.lane("main", context)
pi-java 里 createLane 的生产调用    : 0 个（只有 AgentHarnessTest / MultiLaneTest）
pi-java 有没有 subagent             : 0 个文件
```

生产路径只有一条：`SessionRunner` 取 `owner.laneName()` = `AgentHarness.DEFAULT_LANE`。
TUI / RPC / web 各自开一个 session，每个 session 一条车道。

⇒ **一个 session = 一个 pi 形状的 agent 状态。** 不需要「多车道怎么映射到 pi 的单 `Agent`」——
本来就是一比一。

---

## 2. 现状差异

### 2.1 映射表

| pi `agent.ts` | pi-java 现状 | 处置 |
|---|---|---|
| `AgentState.messages`（**真源**） | `LaneState.transcript`（entries 是真源，每轮重建 messages） | **翻转**（§3.1、§4.2） |
| `activeRun?: {promise, resolve, abortController}`（存在即运行） | `RunPhase.Idle/Assistant/Checkpoint` + `lane.runId` | 换成 `activeRun`（§3.2） |
| `processEvents(event)` 归约器（~40 行） | `ActionExecutor.executeAction` 七路 switch + `PiLaneSink` | 换成 `processEvents`（§3.3） |
| `prompt()` / `continue()` / `abort()` / `reset()` | `ActionExecutor.run`/`runContinue` + `AgentHarness.abort/reset` | 1:1 重写 |
| `handleRunFailure()`：合成错误助手消息，走**同一条** `processEvents` | `HarnessUtils.determineOutcome` + `TryFinishRun` 分支 | 1:1 |
| `isStreaming` / `streamingMessage` | `RunPhase` / `lane.partial` | 1:1 |
| `pendingToolCalls: Set<string>` | `List<Action.ExecuteTool>` | 改 `Set<String>` |
| `systemPrompt` / `model` / `thinkingLevel` / `tools`（**字段**） | `Entry.ModelChange` / `ThinkingLevelChange` / `ActiveToolsChange`（**transcript 里的 entry**） | 翻成字段 + 会话层写 entry（§4.1） |
| `PendingMessageQueue` ×2（steering / followUp） | `QueueManager` ×3（多 `nextRun`） | 保留（§4.4） |
| `subscribe(listener)` → 事件 | `PiLaneSink` 直接写 entry | 会话层改为订阅者（§3.4） |
| — | `Action` / `peekAction` / `executeAction` / `LoopInvariants` / `DriveMode` / `RunPhase` | **删**（§6） |
| — | `pendingWrites` | **删**（裁决 ③，§5） |
| — | `LaneRecord` 记录日志 | **保留**为旁路审计（pi 无，但有真实消费者） |
| — | 多车道容器 / `LaneHandle` / `LaneConfig` 车道级覆盖 / `parentLeafId` | **删或搬**（§6、§4.3） |

### 2.2 三处结构性偏离

**(1) 消息的真源模型。** pi 是**两层**，实测（`docs/31 §4.2` 有完整证据）：

| 层 | 角色 |
|---|---|
| `sessionManager`（entry 日志） | **持久真源**。`buildSessionContext()` 把 entry 转成 messages |
| `agent.state.messages` | **工作副本**。循环直接 `push`（`agent-loop.ts:319,350,365,401…`），会话层订阅事件落 entry |

**两者之间只在「日志变了」的时候同步** —— 实测 pi 全部 5 处赋值点：

| 位置 | 时机 |
|---|---|
| `sdk.ts:376` | 启动时恢复既有会话 |
| `agent-session-runtime.ts:256` | resume / load |
| `agent-session.ts:2034` | **手动**压缩后 |
| `agent-session.ts:2359` | **自动**压缩后 |
| `agent-session.ts:3286` | 分支导航（`sessionManager.branch`）后 |

pi-java 的结构其实**同型**，差在重建时机：它把「entry → messages」放在
`ContextAssembler.buildMessagesForLane`，**每次请求都走一遍**
（`ContextEntries.pathToLeaf(transcript, leafId)`）；pi 只在上面 5 个点重建。

⇒ **② 的对齐内容不是「翻转真源」，而是「把重建点从『每请求』收敛到『日志变更时』」。**
循环改为直接改写 `messages`；entry 日志仍是持久真源，压缩/分支/加载三处重建。

> ⚠️ **本条推翻了本文初稿的写法。** 初稿写的是「pi 的 `messages` 是真源、entries 降级」，
> 读 pi 后被证伪 —— 见 §8「验证结果」。

**(2) 运行态。** pi 用**对象存在与否**表示「正在跑」（`activeRun?`），pi-java 用三态枚举
`RunPhase.Idle/Assistant/Checkpoint` 加一个 `lane.runId`。

**(3) 推进方式。** pi 用一个 40 行的 `processEvents` 归约循环事件；pi-java 用
`peekAction`（挑下一个 `Action`）+ `executeAction`（七路 switch 执行）的步进链。

### 2.3 命名错位的由来

`docs/20` 自己写下了根因：

> pi-java 对齐的是 pi 的 `harness/agent-harness.ts` 状态机骨架，而该骨架几乎全部未实现
> （`peekAction`/`executeAction`/`runToCompletion` 等均抛 `HarnessNotImplemented`）。
> pi 真正在用的是 `agent-loop.ts` 的双层 while，**行为细节只存在于后者**。

`AgentHarness` / `LaneState` / `peekAction` / `executeAction` 这套 API 的形状，
是从一份**未实现的骨架**抄来的。`PiLoop` 是第一次把真东西搬过来；本文把剩下的宿主层补齐。

---

## 3. 目标形态

### 3.1 `AgentState` 形状

```java
// 目标：pi types.ts:334-358 的 Java 版
final class LaneState {
    // ── pi AgentState 的九个字段 ──
    List<Message> messages;                  // 工作副本：循环直接改写；日志变更时从 entry 重建
    boolean isStreaming;
    Message streamingMessage;                // 原 lane.partial
    Set<String> pendingToolCalls;            // 原 List<Action.ExecuteTool>
    String errorMessage;                     // 原 lane.newestOwn / determineOutcome
    String systemPrompt;
    ModelId<?> model;
    ModelThinkingLevel thinkingLevel;
    Set<AgentTool<?, ?>> tools;

    // ── pi-java 自身的东西（pi 放在别处，见下）──
    List<LaneRecord> records;                // 旁路审计，不参与状态
    TelemetrySpan runSpan;                   // 遥测（pi 在 telemetry 层）
    long runStartNanos;
    ActiveRun activeRun;                     // §3.2
}
```

**消失的字段与它们的去向**：

| 消失 | 去向 |
|---|---|
| `RunPhase phase` | `activeRun` 是否为空（§3.2） |
| `Entry` 包裹的 transcript | `messages: List<Message>` + 配置字段 |
| `stepIndex` | pi 无此概念（`StepAttempt` 记录仍有，改由循环侧计数） |
| `newestOwn` | pi 用 `errorMessage` + 消息自身的 `stopReason` |
| `pendingWrites` | **删**（§5） |
| `partial` | 更名 `streamingMessage`，语义已一致 |
| `pendingTurnUpdate` | `prepareNextTurn` 的返回值直接回给循环（`PiLoop.NextTurnUpdate` 已具备） |
| `parentLeafId` | 搬到会话层（§4.3） |
| `activeTools` / `systemPrompt`（车道级覆盖） | 并入上面的字段；**车道级覆盖取消**（§6） |

### 3.2 `activeRun` 取代 `RunPhase`

> **✅ 已实施（2026-09-13，commit `03d8669`）** —— 见 §8.8。

```java
record ActiveRun(AbortSignal signal, CompletableFuture<Void> promise) {}
```

| pi | pi-java |
|---|---|
| `if (this.activeRun) throw "Agent is already processing."` | 同 |
| `get signal() { return this.activeRun?.abortController.signal; }` | 同 |
| `abort() { this.activeRun?.abortController.abort(); }` | 同 |
| `waitForIdle() { return this.activeRun?.promise ?? Promise.resolve(); }` | 同 |
| `runWithLifecycle`：置 `activeRun`，`isStreaming=true`，`try/catch/finally` | 同 |
| `finishRun()`：清 `isStreaming` / `streamingMessage` / `pendingToolCalls`，resolve，清 `activeRun` | 同 |

**`RunPhase.Checkpoint` 的三件事各有归属**：outcome 判定 → 消息的 `stopReason`（`handleRunFailure`
已有）；`TryFinishRun` 的续跑分支 → 循环本身（`PiLoop` 已承担）；收口 → `finishRun()`。

### 3.3 `processEvents` 归约器

逐条对齐 pi `agent.ts:553-590`：

| 事件 | 状态变更 |
|---|---|
| `message_start` | `streamingMessage = event.message` |
| `message_update` | `streamingMessage = event.message` |
| `message_end` | `streamingMessage = null`；`messages.add(event.message)` |
| `tool_execution_start` | `pendingToolCalls.add(toolCallId)` |
| `tool_execution_end` | `pendingToolCalls.remove(toolCallId)` |
| `turn_end` | 助手消息带 `errorMessage` ⇒ 记 `state.errorMessage` |
| `agent_end` | `streamingMessage = null` |

归约之后**按订阅顺序依次 await 监听者**（pi 的 `for (const listener of this.listeners) await listener(...)`）。

### 3.4 会话层改为订阅者

对齐 pi `agent-session.ts:643` 的 `_handleAgentEvent`：

```java
// 目标形态（pi-java 的会话层）
void handleAgentEvent(PiLoop.Event event) {
    // 1. 队列记账：user message_start ⇒ 从对应队列移除（UI 要看到队列变化）
    // 2. 转发给扩展
    // 3. 转发给监听者
    // 4. 持久化：message_end ⇒
    //      role=user/assistant/toolResult  ⇒ appendMessage(entry)
    //      role=custom                      ⇒ appendCustomMessageEntry(...)
    // 5. 助手 message_end ⇒ 记 lastAssistantMessage（自动压缩在 agent_end 检查）
    // 6. turn_end ⇒ flush 挂起的 custom message
}
```

**`PiLaneSink` 因此退化为纯事件转发**：不再造 entry、不再碰 `pendingWrites`。

---

## 4. 四个待定细节

### 4.1 配置 entry 谁写、何时写

**问题**：pi 把 `model` / `thinkingLevel` / `tools` 存在 Agent 的**字段**上，entry 由会话层写；
pi-java 把它们当 transcript 里的 entry，靠折叠读出来。

**✅ 已实测**（`v0.85.1`）：pi 的做法是**字段赋值与 entry 追加在同一处**，**不走事件** ——
`AgentEvent` 里根本没有 model/thinking 变更事件：

```ts
// agent-session.ts:1665-1666
this.agent.state.model = model;
this.sessionManager.appendModelChange(model.provider, model.id);

// agent-session.ts:1801-1809
this.agent.state.thinkingLevel = effectiveLevel;
if (/* 非默认 */) {
    this.sessionManager.appendThinkingLevelChange(effectiveLevel);
    this._emit({ type: "thinking_level_changed", level: effectiveLevel });
}

// agent-session.ts:980   —— tools 只有字段，无对应 entry
this.agent.state.tools = tools;
```

**方案**（照抄这个形状）：

| 项 | 目标 |
|---|---|
| `model` / `thinkingLevel` / `tools` | 成为 `LaneState` 的**字段** |
| `ModelChange` / `ThinkingLevelChange` entry | 由**会话层在设置时**写；与字段赋值同处 |
| `ActiveToolsChange` entry | pi 在 **product 层无对应 entry**（`agent.state.tools` 只有字段）。pi-java 已有该 entry 且下游在消费 ⇒ **保留发射**，但不再由它派生状态 |
| 变更来源 | `run()` 的初始配置 · `prepareNextTurn` 钩子 · 扩展的显式 setter ——三者都改成「设字段 + 写 entry」 |

> `thinking_level_changed` 这个**会话事件**在 pi 里另发（`:160`），pi-java 的 A1–A4 事件层要一并承接。

### 4.2 装配与压缩搬到哪

**问题**：`ContextAssembler.buildMessagesForLane` 每次请求做三件事 ——
建系统提示、`ContextEntries.pathToLeaf(transcript)` 重建消息、fire `transform_context` 钩子。

**方案**：

| 现状 | 目标 |
|---|---|
| 每次请求从 entries 重建 messages | **删除** —— 循环的 context 就是 `state.messages`（pi `createContextSnapshot()`） |
| 系统提示每次请求重建 | 移到 **run 起点**（pi 的 `AgentState.systemPrompt` 是字段；skills 由会话层在启动时注入） |
| `transform_context` 钩子 | **保留在循环里** —— pi 的 `AgentLoopConfig.transformContext`，`PiLoop` 已有 |
| `CompactionExecutor.checkAutoCompact`（`PiLaneEngine.assemble` 里，请求路径上） | 改为 pi 的两段式触发，见下 |

#### 压缩：pi 的实测机制

**✅ 已实测。** 压缩**不是**原地改写 messages，而是**「写 entry → 从日志整体重建」**：

```ts
// agent-session.ts:2357-2359（自动压缩）
this.sessionManager.appendCompaction(summary, firstKeptEntryId, tokensBefore, details, fromExtension, usage);
const sessionContext = this.sessionManager.buildSessionContext();
this.agent.state.messages = sessionContext.messages;      // 整体替换
```

压缩的**输入**也是日志，不是当前 messages：
`sessionManager.getBranch()` → `prepareCompaction(pathEntries, settings)`（`_runAutoCompaction:2259-2262`）。

触发点两处，都在会话层：

| 触发 | 位置 | 时机 |
|---|---|---|
| 阈值 | `agent-session.ts:542 _compactBeforeNextAssistantResponse` | 包在 `prepareNextTurnWithContext` 里（`:557-577`），**下一轮助手响应之前** |
| 溢出 | `agent-session.ts:2132 _checkCompaction` | `agent_end` 之后（由 `_handlePostAgentRun:1142` 调） |

⇒ **pi-java 的落法**：`CompactionExecutor` 从 `PiLaneEngine.assemble` 摘掉，改成上述两个会话层触发点；
压缩后**从 entry 日志重建 messages 并整体替换** —— **不需要原地改写**。

> ⚠️ **本节的初稿被实测推翻了。** 初稿写的是「压缩必须原地改写真源」，并把它列为
> 全方案风险最高项。实测正好相反：pi **从日志重建**，`messages` 是可丢弃的工作副本。
> 见 §8「验证结果」。

### 4.3 分支 / fork 归谁

**问题**：pi-java 用 `LaneConfig.parentLeafId` + `createLane` 表达分支。

**方案**：搬到会话层。pi 里它是 `harness/session/fork.ts` + `fork-policy.ts` ——
是 **fork**，不是 lane。pi-java 的 `AgentSession` 已有分支入口（`AgentSession:647`），
把 `parentLeafId` 从 `LaneConfig` 移到那里即可。

### 4.4 `nextRun` 队列的去向

**裁定：保留。**

pi **有** `nextRun`，在 `harness/agent-harness.ts:222,564` 与 `harness/runtime/lane.ts:1430`
（`kind: "steer" | "followUp" | "nextRun" | "write"`）—— 属**被排除的 harness 层**。
pi 的 `Agent`（`agent.ts`）只有 steering + followUp 两条。

`Action.java:92` 那句「pi has no nextRun queue」是只读 `agent.ts` 得出的，**不准确**。
`nextRun` 有 pi 对应物，只是在对齐范围外 ⇒ 保留，不迁移。

---

## 5. `pendingWrites` 删除面（裁决 ③）

`pendingWrites` 是「已进 transcript、尚未落盘的 entry」队列。pi **完全没有这个机制**，
而它正是 `docs/28 §2.3` 记的「欠写不收敛」缺陷的根源。

`src/main` 里的使用点（12 个文件）：

| 位置 | 做什么 |
|---|---|
| `LaneState` | 字段声明 |
| `ActionExecutor` | run/runContinue 清空、写用户 entry、`ApplyPendingWrite` 执行 |
| `AssistantStreamExecutor` | 写助手 entry |
| `ContextAssembler` | 写配置 entry |
| `HarnessUtils` | 写工具 entry + `recordDeferredWrite` |
| `ToolExecutionPipeline` | 写工具 entry |
| `PiLaneSink` | 写 entry |
| `AgentHarness` / `ActionExecutor.finishRun` | 清空 |
| `LoopInvariants` | owed-write 断言（**随步进链一起删**） |
| `LaneSnapshot` / `SnapshotService` | 对外快照字段 |

**删除方式**：不是逐点删，而是**随着 `PiLaneSink` 退化为事件转发（§3.4）整片消失** ——
entry 由会话层在 `message_end` 上直接写入，没有「先记账后落盘」这个中间态。

⚠️ **需要一并裁决的**：`Action.ApplyPendingWrite` 与 `WriteDeferred` **记录**。
记录本身是旁路审计，可以留；`ApplyPendingWrite` 随步进链删。

---

## 6. 删除清单与测试迁移

> **✅ 已实施（2026-09-13，commit `03d8669`）** —— 见 §8.8。
> 两处与本文不同：`pendingWrites` 是**先独立删掉**的（§5 的「随 sink 退化而消失」
> 不是唯一路径）；**多车道容器没删** —— `createLane` 有生产调用者（会话层的 fork /
> 分支、TUI 的车道选择），须先按 §4.3 把分支入口搬走。

### 6.1 删除

| 目标 | 行数 | 依据 |
|---|---|---|
| `Action` / `RunPhase` / `PeekAction` | 129 + 17 + 19 | §3.2、§3.3 |
| `ActionExecutor` 的步进链（`peekAction` / `executeAction` / `computeNextAction` / `executeTryFinishRun` / `executeConsumeQueueItem` / `executeApplyPendingWrite`） | 大部 of 510 | §3.3 |
| `AssistantStreamExecutor` | 219 | 流式执行已由 `PiLoop` 承担 |
| `LoopInvariants` | 72 | 只在 `peekAction` 里被调用 |
| `DriveMode.MANUAL` 步进语义 | — | 无生产消费者；22 个测试在用 |
| 多车道容器：`createLane` / `LaneHandle` / `lanes()` / `LaneConfig` 车道级覆盖 | — | §1.2 |
| `AgentHarness.peekAction` / `executeAction` / `runToCompletion` | — | §3.2 |
| `pendingWrites` 全套 | — | §5 |

**保留**：`ActionExecutor` 的起手/收口职责（`run` / `runContinue` / `finishRun`）——
它们搬进 `agent.ts` 语义的 `Agent` 宿主里，而不是消失。
`QueueManager` / `CompactionExecutor` / `AgentTool` 注册表 / `HookSystem` / `ToolExecutionPipeline`
/ `LaneRecord` **全部保留**。

### 6.2 测试迁移

| 类别 | 文件数 | 处置 |
|---|---|---|
| 用 `peekAction` / `executeAction` 驱动 | 21 | 改调 `prompt()` / `continue()`（公开 API 语义不变） |
| `PiLaneEngineTest` / `RunSummaryAggregatorTest` | — | 断言对象从 `transcript()` 改 `messages()` |
| `MultiLaneTest` / `AgentHarnessTest` 的多车道用例 | — | **删**（面本身消失） |
| `SessionResumeFoldTest` / `QueueSchedulingTest` / `QueueRecordEmissionTest` | — | 保留，改驱动方式 |

**迁移原则**：断言**行为**的用例改驱动即可；断言**结构**（transcript 形状、phase、Action 序列）
的用例随结构一起删 —— 它们钉的是要被拆掉的东西。

---

## 7. 验收

1. ~~**L5 差分保持 8/8**~~ **✅ 9/9**（§8.7 的 S9 之后），`03d8669` 后不变。
2. **全 reactor `mvn -o -am clean verify` 绿**（14 模块），checkstyle 零违规 —— **✅ `03d8669` 达成**。
3. **双驱动不再并存**：`src/main` 里不再有 `peekAction` / `executeAction` / `RunPhase` /
   `Action` 的引用 —— **✅ 达成**（仅剩解释历史的注释）。
4. **`pendingWrites` 零命中** —— **✅ 达成**。
5. **行为等价抽查**：崩溃恢复、abort、steer 注入时机、自动压缩四条路径各有用例 ——
   **✅ 达成**（`SessionResumeFoldTest` / `MidStreamAbortTest` / `QueueSchedulingTest` /
   `CompactionTest`）。本轮另有 9 条**真实差异**由删除暴露并逐条对 pi 判定，见 §8.8。
6. ~~**`PiLoop` 补上 `context` 通道**（§8.3-6），且新增一个 `prepareNextTurn` **非 null** 的
   L5 剧本 —— 现有 8 个剧本里它恒为 `null`，覆盖不到这条通道。~~
   **✅ 已完成（2026-09-13）**：通道见 §8.3-6；剧本为 **S9**，L5 现为 **9/9**。
   实施时发现「只加剧本」不够 —— 帧里没有请求，得先让请求可观察，见 §8.6。

> ⚠️ 第 6 条是**实施的第 1 步**：`PiLoop` 现在的 1:1 是「在 L5 剧本覆盖到的范围内」，
> 不是全量的。压缩的落地通道就在这条缺口上。

---

## 8. 验证结果（读 pi `v0.85.1` 实测，2026-09-13）

### 8.1 已证实

| # | 假设 | 结论 | 证据 |
|---|---|---|---|
| 1 | 配置变更**不走事件**，由会话层直接写字段 + 追加 entry | ✅ **证实** | `agent-session.ts:1665-1666`（model）、`:1801-1809`（thinkingLevel）、`:980`（tools 只有字段）。`AgentEvent` 里确实没有配置变更事件 |
| 2 | 压缩后 messages 被整体替换 | ✅ **证实**，但机制与初稿**相反** | `:2357-2359`：`appendCompaction(...)` → `buildSessionContext()` → `agent.state.messages = sessionContext.messages`。**从日志重建**，非原地改写 |
| 3 | 消息是**工作副本**，entry 日志是持久真源 | ✅ **证实** | 循环直接 `push`（`agent-loop.ts:319,350,365,401,474,554`）；日志变更时整体重建，全部 5 处见 §2.2(1) |

### 8.2 部分证实

**4. `Message` 角色的等价性 —— 部分。**

`CustomAgentMessages` **默认为空**（`agent/types.ts:317-319`，靠 declaration merging 扩展）。
多出来的三个角色（`bashExecution` / `branchSummary` / `compactionSummary`）来自
**pi 的 harness 层**（`harness/messages.ts:20,41,48` 的 `role: "..."` 与 `:56-58` 的合并声明）
—— **属被排除范围**。

`session-manager.ts:101/128` 记明：这些 entry **不参与 LLM 上下文**，或**在 `buildSessionContext()`
里被转成 user 消息** —— 与 pi-java 的 `ContextEntries.toMessages` 同型。

⇒ **仍待做**：逐项核对 pi-java 的 `Entry` 八种类型在 `ContextEntries` 里是否都有对应转换。

### 8.3 未验证（实施前必须清点）

**5. 下游消费者不依赖 `transcript()`。** TUI / web / RPC / SQLite 四个消费者读
`snapshot().transcript()` 的地方**未清点**；`messages` 取代它之后是否等价未验证。

**6. `prepareNextTurn` 的 context 通道 —— ✅ 证实缺失，`PiLoop` 少一个字段。**

| | 字段 |
|---|---|
| pi `AgentLoopTurnUpdate`（`types.ts:138-145`） | `context?` · `model?` · `thinkingLevel?` —— **三个** |
| pi-java `PiLoop.NextTurnUpdate`（`PiLoop.java:127`） | `model` · `thinking` —— **两个** |

压缩正是靠 `context` 这条通道生效的（`agent-session.ts:557-577` 返回
`{context: {...nextContext, systemPrompt, tools}}`）。

⇒ **`PiLoop` 并非完全 1:1，这是一个真实缺口。** 且 **L5 差分覆盖不到它** ——
`docs/29 §6` 自己记着「八个剧本里 `prepareNextTurn` / `shouldStopAfterTurn` 恒为 `null`」。

**这是实施的第 1 步**：补 `AgentLoopTurnUpdate.context` 通道 + 补一个 L5 剧本让它非 null，
否则 §4.2 的阈值压缩无处落地。

**✅ 已完成（2026-09-13）**，且实施时又读出一处**此前未发现的差异**（见 §8.4）。

### 8.4 实施时新发现：`prepareNextTurn` 的调用时机与重拉

`docs/31` 初稿只知道 `context` 字段缺失。实施时逐行对照 `agent-loop.ts:165-279`，
又读出**两处**控制流差异：

| # | pi | pi-java（改前） |
|---|---|---|
| 5 | `shouldStopAfterTurn` 先（`:249-252`，在 `turn_end` 之后），`prepareNextTurn` 后（`:176-183`，在**下一轮的开头**） | **顺序相反**：`prepareNextTurn` 先，`shouldStopAfterTurn` 后 |
| 6 | `prepareNextTurn` 返回后，若此前没有待注入的 steer，则**再轮询一次**（`:184-189`） | 不重拉 |

**第 5 条的后果是实的**：`shouldStopAfterTurn` 为真时 pi 直接返回，**根本不会调用**
`prepareNextTurn`；改前会多跑一次有副作用的钩子。

**第 6 条在 Java 侧更重**：pi 的注释写明理由是「准备可能很慢（例如压缩）」——
压缩要跑一次 LLM 调用（数秒）。改前，用户在这段时间敲的 steer 会被推迟整整一轮。

> **两处都由新增的 `PiLoopTurnHooksTest` 钉住**（5 个用例）。做了反向实验：把旧的
> 「先 prepareNextTurn 后 shouldStop」插回去，5 个用例里 3 个失败 + 1 个报错。

### 8.5 异步 ↔ 同步的对齐口径（**通用原则，适用于后续所有移植**）

pi 用 `async/await`，Java 没有。**这不是障碍**：pi 的 `runLoop` 里没有任何 `Promise.all`，
每个 `await` 都是「等它做完再往下」——这个循环**从来不会让两个循环步骤并发**，
它是一个顺序状态机，`await` 只是它的挂起语法。

⇒ **对齐的是「可观察效果的顺序」，不是 async 机器。**

| pi | pi-java | 等价性 |
|---|---|---|
| `await emit(e)` | `sink.emit(e)`（同步） | ✅ 顺序等价 |
| `await streamFn(...)` | 阻塞式 `StreamIterator`（虚拟线程） | ✅ |
| `await config.getSteeringMessages()` | `Supplier.get()` | ✅ 前提：队列线程安全 |
| `await config.prepareNextTurn(...)` | 同一线程阻塞 | ⚠️ **必须显式处理「阻塞期间世界在变」** —— 即第 6 条重拉 |

**唯一一处机制上真不等价、且已裁决为有意偏离**：pi 的 `subscribe` 接受
`(event, signal) => Promise<void> | void` 并**逐个 await**，`agent_end` 之后才算 idle：

> `agent_end` is the final emitted event for a run, but the agent does not become idle
> until all awaited listeners for that event have settled.

`PiLoop.Sink` 是**同步**的。同步 sink 天然给了「emit 返回即 listener 完成」——
顺序与结算语义都对；但**不能 await 一个异步 listener**。

**裁决（2026-09-13）**：**明确记为有意偏离**，不引入 `CompletionStage` 重载 ——
把 sink 链传染成异步的代价远大于收益。**pi-java 的扩展不许在 sink 里阻塞**；
真出现需要 await 的扩展场景时再单独设计。

### 8.6 系统提示搬出消息列表（2026-09-13，**已实施**，commit `6320d5a`）

`docs/31` 初稿把这条记为「`Context.systemPrompt` 未单独携带」。读 pi 后，实际**比初稿说的更深**：

| 事实 | 证据 |
|---|---|
| pi 的 `Message` **只有三个角色**，没有 system | `packages/ai/src/types.ts:470`：`UserMessage \| AssistantMessage \| ToolResultMessage` |
| 系统提示是 `Context` 上的独立字段 | `types.ts:524-528` |
| provider 适配层读的是 `context.systemPrompt`，**从不扫描消息列表** | `api/anthropic-messages.ts:1074`、`api/openai-completions.ts:1214`、`api/google-generative-ai.ts:380`、`api/mistral-conversations.ts:523`、`api/openai-responses-shared.ts:175`、`api/bedrock-converse-stream.ts:254` |
| pi 的 `AgentLoopConfig` **没有 tools 字段** | `types.ts:145-213` 实测；工具只属于 `Context` |

⇒ 处置：

- 新增顶层 `harness/Context`（三字段对齐 pi），取代 `PiLoop` 的内嵌 Context；
  `PiLoop.Config` 去掉 `toolDefs`、`StreamOptions` 去掉 `tools` —— 工具此前有**两个**真源。
- **`Message.SystemMessage` 删除**：拆出系统提示后它没有任何生产者，留着等于让 6 个 provider
  各守一个不可能发生的分支。删掉后 sealed 联合恰好三个变体，编译器证明了没有漏网点。
- `StreamFn` 改为 pi 的形参与顺序 `(model, context, options)`；`StreamRequest` 增
  `systemPrompt`，6 个 provider 改读它。
- `ContextAssembler` 不再把系统提示折成 `messages[0]`，系统提示在 run 起点装进 Context
  —— 顺带落地了 §4.2 的「移到 run 起点」。`transformContext` 因此像 pi 一样只看得见消息。
- `RequestContext`（`before_request` 钩子）增 `systemPrompt`：钩子原先从 `messages[0]`
  看到它，拆分后必须单独给，否则钩子看不到完整请求。

验证：全 reactor `mvn -o clean verify` 绿（14 模块）；L5 8/8 不变（帧里不含系统提示，预期无变化）。

### 8.7 S9 与 `echoRequest`：帧看不见的东西验证不了（2026-09-13，**已实施**）

§8.3-6 补上 `context` 通道后需要一条差分剧本守住它，于是加了 **S9**。实施时发现一个陷阱：

**归一化后的帧只有 agent 事件**（`agent_start` / `turn_*` / `message_*` / `tool_execution_*` /
`agent_end`），**请求消息本身从不进帧** —— 剧本的流函数是假的，根本不看自己的参数。
所以「上下文被整体替换」这个后果在帧里完全不可观察：只加一条 `prepareNextTurn` 非 `null`
的剧本，它会在钩子**根本没接上**时照样通过 —— 一条不可能为它存在的理由而失败的用例。

**处置**：给共享剧本格式加一个字段 `echoRequest`（两侧 runner 各约 6 行、逐字相同），
把本次请求的形状（消息数 / 模型 / 系统提示）编进首个文本块。S9 第二轮回显
`[n=1 model=openai/switched sys=SYS2]`；替换未生效时是 `n=3`。

**反向实验**：Java 侧 `driver::prepareNextTurn` 换成 `null` ⇒ S9 失败，其余剧本不受影响。

**这条有一个通用结论**：L5 差分只能验证**进得了帧**的东西。碰到只改变请求形状的通道
（压缩、`transformContext`、工具过滤……），必须先把该形状折进帧，否则「加了剧本」只是
给人一种已覆盖的错觉。

**S9 仍不覆盖**：`AgentLoopTurnUpdate.thinkingLevel` —— 不出现在任何帧上，差分**结构上**
验证不了，故未写进剧本。

---

### 8.8 §6 删除清单与 §3.2 —— 已实施（2026-09-13，commit `03d8669`）

**实施依据是「生产路径上零调用者」**：`SessionRunner` 早已只走
`piEngine().run()` / `.continueRun()`，而 `peekAction` / `executeAction` /
`runToCompletion` / `DriveMode` 只被测试使用。于是同一套 `LaneState` 被两条驱动路径
各自解读，**两者行为不一致长期无人发现** —— 因为测试走的是旧路径。

#### 删掉的（约 1100 行）

| 目标 | 依据 |
|---|---|
| `Action` / `PeekAction` / `LoopInvariants` / `RunPhase` / `DriveMode` | §6.1 |
| `ActionExecutor` 的步进链 | §3.3 |
| `AssistantStreamExecutor` | 流式执行已由 `PiLoop` 承担 |
| `ToolExecutionPipeline` | `PiToolRunner` 取代；随之 `ToolExecutor` 也失活（零调用者） |

#### 新增的

| 类型 | 职责 |
|---|---|
| `ActiveRun` | **存在即为正在运行**，取代三态 `RunPhase`（§3.2）。`waitForIdle` 等它的完成信号 |
| `RunLifecycle` | 只留起手/收口/空闲操作（`runId`、`before_run`、`OperationStarted`、`OperationFinished`、`before_run_end`、run span）+ 恢复三件套 |
| `LaneRegistry` | 车道容器与生命周期 —— 拆出来是为了让 `AgentHarness` 回到 500 行以内 |

宿主 API 对齐 pi：`prompt(...)` / `continueRun(...)` 是**阻塞**的整轮运行
（§8.5 的口径），`piEngine()` 不再外露。

> ⚠️ **§5 的删除方式与实施不同。** 设计写的是「随 `PiLaneSink` 退化为纯事件转发而整片
> 消失」，实际是**先独立删掉**：`pendingWrites` 全仓零读取点（§8.3-5 清点确认），
> 与 sink 的改造没有依赖关系，先删能缩小后续改动面。
>
> ⚠️ **§3.1 的字段清单只落地了一半。** `activeRun` 已取代 `RunPhase`，但
> `transcript` **没有**被 `messages` 取代 —— §8.3-5 的清点表明下游（`SessionPersistence`
> 的落盘、RPC/web 的 entry JSON、fork 取 `entry.id()`）**需要 `Entry` 对象**，
> 而 harness 的 transcript 是**新 entry 的唯一生产者**。故 `messages` 工作副本应
> **增量添加**而非替换，留待 §4.2。

#### 删除后才暴露的既有缺陷（生产早已如此，不是本次引入）

删掉旧路径后 9 个测试立刻转红。**它们在本 commit 之前是通过的** —— 因为它们走的是
旧路径。逐条对 pi 源码判定后：

| # | 症状 | 判定 | 处置 |
|---|---|---|---|
| 1 | 流中途 abort 被记成 COMPLETED | pi 把 signal 交给 streamFunction 由 provider 收尾（`agent-loop.ts:307-311`）；pi-java 的同步 `StreamIterator` 不能假定 provider 照做 | **补循环侧保证**：拉取途中信号一响就停止消费，被切断的一轮标 `aborted`（A8）。provider 给出终局判定时**不覆盖** |
| 2 | 出错的运行被记成 COMPLETED | `determineOutcome` 返回 `"error"`，而 `RunLifecycle.outcome()` 只认 `OperationOutcome` 取值 ⇒ 落默认分支。span 属性写着 error，记录写着 COMPLETED | **修**：`"error"` → FAILED |
| 3 | 无 `tool.execute` 跨度、无 tool.executions/tool.errors | `ToolExecutionPipeline` 独占这些，`PiToolRunner` 没有 | **修**：跨度在 `PiLaneSink.noteToolStart` 开、结果消息处关；`batchSize` 在收尾时补（pi 保证全部 start 早于任何 end） |
| 4 | `StepAttempt.durationMs` 恒为 null | `PiLaneSink` 传硬 `null` | **修**：传实测毫秒 |
| 5 | run span 的 `outcome` 是 `tool_use` | 消息级的 `tool_use`/`length` 被当成了**运行**结局；到达终局的运行必然已消费完全部工具调用 | **修**：`determineOutcome` 归一为 `completed` |
| 6 | 截断的调用**有** `ToolFinished` 记录（旧路径没有） | pi 的 `failToolCallsFromTruncatedMessage` **同样**发 start/end（`agent-loop.ts:379-405`） | **改测试**：记录在，但 `executed == 0` |
| 7 | 拒绝理由文本 | 旧路径是通用句 "Tool call denied by hook"；pi 的 `createErrorToolResult` 把理由带给模型 | **改测试**：断言钩子给的理由 |
| 8 | 校验错误无 "Tool error: " 前缀 | 前缀是 `ToolExecutionPipeline` 的构造，pi 侧无对应物 | **改测试**：断言校验器措辞 |
| 9 | 拒绝结果的块类型是 `TextContent` 不是 `ToolResultContent` | pi 的 `createToolResultMessage` 把文本直接放进结果消息 | **改测试**；`QueueMode.All` **不合并**（`PendingMessageQueue.drain` 原样返回，`agent-loop.ts:200-208`）—— 旧的 `"first\n\nsecond"` 合并是 pi-java 自己的构造 |

> **这条是本次最有价值的产出**：删除不是「删掉没用的东西」，而是**让生产路径的缺陷
> 不再有替身**。任何一次「新旧并存」的重构都应该预期这一幕，并把红掉的测试当作
> 待判定的清单，而不是待修平的噪音。

#### 测试迁移

21 个文件改由 `prompt(...)` 驱动；2 个纯 `Action` 序列用例（`AgentLoopL2Test`、
`RunToCompletionTest`）随结构删除 —— 它们钉的正是被拆掉的东西，其行为覆盖已在
`PiLoopTest` 层（截断/abort/follow-up）与 `ConsecutiveRunsTest` 重复。
`RunToCompletionTest` 里唯一的行为回归（连续运行的 operation 配对）已迁入后者。

#### 仍未做的

- **§3.1 的 `messages` 工作副本**（见上）
- **§4.1** 配置 entry 由会话层写、`LaneState` 持字段
- **§4.2** 装配与压缩移出请求路径
- **§4.3 + §1.2 的多车道删除**：`createLane` 有**生产调用者**（`AgentSession` 的
  fork/分支、TUI 的 `TreeSelectorScreen`），不是零调用者 —— 需先按 §4.3 把分支入口
  搬到会话层，**不能直接删**

---

## 9. 与既有文档的关系

| 文档 | 关系 |
|---|---|
| `docs/27` | 对齐规则与基线，本文的前提 |
| `docs/28` | 驱动层换成 `PiLoop` 的决策；本文是它的续篇（宿主层） |
| `docs/29` | L5 差分报告，本文 §7 验收第 1 条的依据 |
| `docs/30` | 折叠链退休；`records` 降级为旁路审计由它确立 |
| `docs/03` | 类级设计；实施后 §2.3 的 `LaneState`/`LaneRecord` 两节需同步 |
