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

> ⚠️ **上表第二行（2026-09-13 实测推翻）。** `createLane` 有生产调用者：
> `AgentSession:635,661`（仅内存态 fork）、`InMemorySessionRepository:102`、
> TUI `TreeSelectorScreen:48`。结论方向不变（要删运行时多车道容器），但**删除路径不是
> 零调用者删除**。另外 **pi 本身就有 lane**（`pi.lane.config`/`pi.lane.state`，
> 逐分支投影）—— 「lane 概念不在对齐目标内」只对 `agent.ts` 成立，对 pi 的**会话层**不成立。
> 详见 §8.10。

---

## 2. 现状差异

### 2.1 映射表

| pi `agent.ts` | pi-java 现状 | 处置 |
|---|---|---|
| `AgentState.messages`（**真源**） | `LaneState.transcript`（entries 是真源，每轮重建 messages） | **翻转**（§3.1、§4.2） |
| `activeRun?: {promise, resolve, abortController}`（存在即运行） | `RunPhase.Idle/Assistant/Checkpoint` + `lane.runId` | 换成 `activeRun`（§3.2） |
| `processEvents(event)` 归约器（~40 行） | `ActionExecutor.executeAction` 七路 switch + `PiLaneSink` | 效果已落地；**残差三项在 pi 里是死字段，不补**（§3.3、§8.14） |
| `prompt()` / `continue()` / `abort()` / `reset()` | `ActionExecutor.run`/`runContinue` + `AgentHarness.abort/reset` | 1:1 重写 |
| `handleRunFailure()`：合成错误助手消息，走**同一条** `processEvents` | `HarnessUtils.determineOutcome` + `TryFinishRun` 分支 | 1:1 |
| `isStreaming` / `streamingMessage` | `RunPhase` / `lane.partial` | 1:1 |
| `pendingToolCalls: Set<string>` | `List<Action.ExecuteTool>` | 改 `Set<String>` |
| `systemPrompt` / `model` / `thinkingLevel` / `tools`（**字段**） | `Entry.ModelChange` / `ThinkingLevelChange` / `ActiveToolsChange`（**transcript 里的 entry**） | 翻成字段 + 会话层写 entry（§4.1） |
| `PendingMessageQueue` ×2（steering / followUp） | `QueueManager` ×3（多 `nextRun`） | 保留（§4.4） |
| `subscribe(listener)` → 事件 | `PiLaneSink` 直接写 entry | 落盘时机已对齐（`message_end`）；**entry 的作者不搬**，§3.4、§8.14 |
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

> **⚠️ 本节只落地了一部分，且残差不补。** `messages` 与 `streamingMessage` 已同形；
> `isStreaming` / `pendingToolCalls` / `errorMessage` **不补** —— 读数证明它们在 pi 里也
> 没有任何读者（`isStreaming` 连 pi 的产品层都改用自己的私有字段）。见 §8.14。

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

> **⚠️ 本节未实施，且是有意留下的** —— 见 §8.14。entry 的**作者**仍是 harness
> （`PiLaneSink`），会话层是**订阅者**（`SessionRunner` 的 `persistPerEntry` 只负责落盘）。
> 与本节设想的「会话层订阅并 append、sink 退化为纯转发」是**相反的切分**。

---

## 4. 四个待定细节

### 4.1 配置 entry 谁写、何时写

> **✅ 已实施（2026-09-13，commit `153380f`）** —— 见 §8.9。

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

> **✅ 已实施（2026-09-13，commit `c82c9b2`）** —— 见 §8.11。

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

> ⚠️ **本节的初稿把 pi 看轻了。** 「是 fork，不是 lane」不成立 —— pi 的 fork **就是**
> 建一个新 lane（`pi.lane.config` / `pi.lane.state` 逐分支投影）。要删的是
> **运行时多车道容器**，不是 lane 概念本身。详见 §8.10；顺序上排在 §4.2 之后。

> **✅ 已实施（2026-09-13，commit `ab7d309`）** —— 见 §8.12。落法：每个
> `AgentSession` 持自己的 harness（`AgentHarness.fork()`），`laneName` 恒为
> `DEFAULT_LANE`，运行时多车道容器整体删除；**存储层的 lane 一字未动**。

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

- ~~**§3.1 的 `messages` 工作副本**~~ —— 已随 §4.2 落地（§8.11）
- ~~**§4.2** 装配与压缩移出请求路径~~ —— 见 §8.11
- ~~**§4.3 + 多车道运行时容器**~~ —— 已落地（§8.12）；`createLane` 的生产调用者已改走
  会话层，pi 的存储层 lane 保留

**本文的实施项至此全部完成**；剩下的只有 `docs/03` 的类级描述重写（见 §8.12 末）。

---

### 8.9 §4.1 —— 已实施（2026-09-13，commit `153380f`）

**读 pi 得到的准确形状**（`agent-session.ts`）：

| 项 | pi 的做法 | 守卫 |
|---|---|---|
| `setModel` | 字段赋值 + `appendModelChange`（`:1687`） | **无条件**写 entry；变更判定只作用于 `model_select` **事件**（`_emitModelSelect` 相等时提前返回） |
| `setThinkingLevel` | 字段赋值 + `appendThinkingLevelChange`（`:1813-1829`） | `isChanging = effectiveLevel !== previousLevel`，且**只在非默认等级时**写 |

**pi-java 的缺口**：初始配置会写 entry，但**显式 setter 只改字段**。`/model`、
`/thinking`、TUI 模型选择器、RPC `cycleModel` 都走 setter ⇒ 切换后持久化日志里
没有这条变更，恢复时丢失。

**落法**：新增 `RunLifecycle.recordModelChange` / `recordConfigChanged`，由 setter 在
字段赋值同点调用；`LaneState.recordedThinking` 承担「上次记过什么」，于是

- 首次运行补记一次（与改动前的行为一致，日志形状不变）；
- 后续同等级运行**不再重复**（改动前每次运行都无条件追加一条，会堆出一串）；
- `seedTranscript` 从既有日志初始化它 —— 否则恢复后首次运行会把日志里已有的那条再写一遍；
- `reset` 清空日志时一并清空。

**未纳入**：`ActiveToolsChange`。`setActiveTools` 在生产路径上**零调用者**，
加发射是投机代码；§4.1 表里「保留发射」指的是留住该 entry 类型与其下游消费（已满足）。

**验证**：全反应堆 14 模块绿（agent-core 371）；L5 10/10 不变；checkstyle 0 违规。
新增 `ConfigEntryEmissionTest`（5 例），两次反向实验确认会咬住（撤掉 model 写入、
撤掉 seed 初始化 → 分别红对应那一条）。

---

### 8.10 多车道：pi **有** lane，要删的不是这个概念（2026-09-13）

**§1.2 的前提需要修正。** 它写「pi-java 里 `createLane` 的生产调用：0 个」，
实测**不成立**（`AgentSession:635,661`、`InMemorySessionRepository:102`、
TUI `TreeSelectorScreen:48`）。但更要紧的是下一条。

**pi 自己就有 lane，lane 就是分支。** 实测 `harness/session/values.ts:158-161`：

```ts
branchTip  = value("pi.branch.tip",   branch)
laneConfig = value("pi.lane.config",  lane)
laneState  = value("pi.lane.state",   lane)   // { currentOperationId, lastOperationId, inbox }
```

`fork-policy.ts:53-58` 在 fork 时**逐分支**投影它们。所以：

- **存储层的 lane（`Session.createLane` / `moveLane` + 各存储实现）是对齐的，要留住** ——
  它正是 pi 的分支模型；
- **要删的是 `AgentHarness` 的「运行时多车道容器」**（`LaneRegistry` / `LaneHandle` /
  `LaneConfig` / `createLane` / `lanes()` / `moveLane`）—— 一个 harness 同时装多条车道。
  pi 的对齐目标 `agent.ts` 是**单状态**的（`AgentState`），lane 归**被排除的** harness 层。

**它为什么还在**：只有**内存态 fork** 需要它 —— 那条路径没有独立会话，只能共享父会话的
harness，靠新建 lane 假装隔离。持久化分支**早就对齐了**：`AgentSession:648-659` 走
`persistentRepository.fork(...)` 建**独立会话**，并显式把 `laneName` 设回 `DEFAULT_LANE`。

**顺序**：删容器要**排在 §4.2 之后**。pi 里「切换到分支」是**用该 lane 的日志重建 agent
状态**（`agent-session.ts:3286`，5 个同步点之一），而 §4.2 的 `messages` 工作副本正是
做这件事的机制；先删容器，分支切换就没有落脚点。落法：让每个 `AgentSession` 持有**自己
的** harness（fork 也不例外），`laneName` 恒为 `DEFAULT_LANE`。

---

### 8.11 §4.2 + §3.1 —— 已实施（2026-09-13，commit `c82c9b2`）

**读 pi 得到的准确形状**（`agent.ts` / `agent-session.ts`）：

| 项 | pi 的做法 |
|---|---|
| 工作副本 | `state.messages` 由 `processEvents` 在 `message_end` 上 `push`（`agent.ts:554-557`） |
| 交给循环的 | `createContextSnapshot()` = `{...state, messages: state.messages.slice()}`（`:437-443`）—— **一份拷贝** |
| 重建点 | 只在日志整体替换/首次填充时 `state.messages = buildSessionContext().messages`（`:2357-2359` 等 5 处） |
| 阈值压缩 | `_compactBeforeNextAssistantResponse` 包在 `prepareNextTurnWithContext` 里（`:542` / `:557-577`），压缩后**经 `NextTurnUpdate.context` 整体交回循环** |
| 溢出压缩 | `_handlePostAgentRun:1142` → `_checkCompaction:2132`，在 `agent_end` **之后** |

**落法**（`docs/31 §4.2` + §3.1 的 `messages` 工作副本一并落地）：

- `LaneState.messages` 就是工作副本。`PiLaneSink.onMessageEnd` 往里追加，**先于**
  `alreadyPresent` 的抑制 —— 起手的用户 prompt 因「日志里已有」不重复落盘，但照样进副本，
  pi 的用户消息也是经 `message_end` 进 `state.messages` 的。
- 重建只在四处：`seedTranscript`（resume）· `applyCompaction` · `LaneRegistry.move` · `reset`。
  统一走 `HarnessUtils.rebuildLaneMessages`。**压缩从不原地改写消息，它换的是日志。**
- 循环拿到的是**拷贝**，与 `createContextSnapshot()` 的 `.slice()` 同形。
- 请求路径上只剩 `transform_context` 钩子，外加钩子看不到的两项**执行步开销**
  （`before_request` 钩子 + `llm.request` 跨度）—— 它们原本长在 `AssistantStreamExecutor`
  里，属执行步，`PiLoop` 不带，必须显式搬到一个点上（`PiLaneEngine.beforeRequest`）。
- `prepare_next_turn` 的结果**在钩子返回点就地应用**（`ContextAssembler.applyTurnUpdate`），
  `LaneState.pendingTurnUpdate` 这个「暂存到下次请求」的字段删掉了 —— pi 的
  `prepareNextTurnWithContext` 自己 `appendModelChange`，返回给循环的只是 `{model, reasoning}`。
- 两处压缩触发点就位：阈值在 `prepareNextTurn`（`CompactionExecutor.checkThreshold`，
  压缩后经 `NextTurnUpdate.context` 把重建的消息交回循环）；溢出在 `drive` 里
  `PiLoop` 返回之后（`PiLaneSink.checkOverflowAfterRun`），从轮内的 `endRequest` 搬出来。

**一处顺带的行为修正**：此前阈值压缩发生在 `transformContext` 里 —— 它换掉了
`lane.transcript`，但循环持有的 `context.messages()` **没有跟着换**（钩子返回值只喂 provider）。
于是循环内部状态与 provider 看到的上下文分叉。现在经 `NextTurnUpdate.context` 换了整个
context，与 pi 一致。

**验证**：全反应堆 14 模块绿（agent-core 374 = 371 + 3）；L5 `ConformanceTest` 10/10 不变；
checkstyle 0 违规；8 个改动文件全部 ≤ 500 行。新增 `LaneMessagesTest`（3 例），
**三次反向实验**逐一确认会咬住：撤掉 `checkThreshold`、撤掉 `checkOverflowAfterRun`、
撤掉 `seedTranscript` 里的重建 —— 各自只红对应那一条。

**仍未做的**：~~`docs/03 §2.3` 的 `LaneState`/`LaneRecord` 两节待同步（CLAUDE.md 要求）；
多车道运行时容器删除（§4.3 / §8.10）~~ —— 两项随后均已落地：后者见 §8.12，
前者见 §8.13。

---

### 8.12 §4.3 + 多车道运行时容器删除 —— 已实施（2026-09-13，commit `ab7d309`）

**删掉的是「一个 harness 装多条车道」，不是 lane 概念**（§8.10 的裁决）。存储层的
`Session.createLane` / `moveLane` + 各存储实现**一字未动** —— 那正是 pi
`pi.branch.tip` / `pi.lane.config` / `pi.lane.state` 的分支模型。

**读 pi 得到的准确形状**（`harness/session/fork.ts` + `fork-policy.ts`）：

| 项 | pi 的做法 |
|---|---|
| fork 的内容 | `createForkSnapshot` **复制 entry**：`scope:"tree"` 复制全部；`scope:"branch"` 从分支尖端回溯到请求点 |
| 分支点 | `selectBranchFork`：`position:"before"` 时 `destinationTip = parentId`，**请求的 entry 本身不入选** |
| 找不到 entry | 抛 `Fork entry … is not on source branch`（静默给空会话是错的） |
| lane 归谁 | `pi.lane.*` 是**存储值**，逐分支投影；`agent.ts` 的 `AgentState` 是单状态的 |

**落法**：

- **`AgentHarness` 只持一条车道**（`private final LaneState lane`）。删除
  `LaneRegistry` / `LaneHandle` / `LaneConfig` / `LaneExistsException`，以及
  `createLane` / `lanes()` / `moveLane` / `lane()`；新增 `laneName()` 与
  **`fork()`**（同配置的全新 harness，车道为空，内容由调用方播种）。
- **`LaneState` 吸收 `HarnessState`**：`model` / `thinkingLevel` / `systemPrompt` /
  `activeTools` / `compactionSettings` / 队列模式 / `toolExecution` 并回车道的字段。
  一个 harness 一条车道之后，「harness 的可变配置」与「车道的可变配置」是同一个东西，
  而 pi 的 `AgentState` 本就把它们和消息放在一起（§4.1 表格里仍挂在 harness 那一行的项，
  至此归位）。
- **`HarnessUtils.requireLane(lane, laneName)`** 退化成一次名字核对：名字对不上就抛
  `Lane not found`。保留名字是因为「该调用属于哪条车道」仍是各处 API 的形状，名字对不上
  意味着调用方还拿着旧的分支名。
- 五个协作者（`HookSystem` / `SnapshotService` / `QueueManager` / `ExecutionContext` /
  `HarnessUtils`）的 `ConcurrentMap<String, LaneState>` 参数换成单个 `LaneState`。
  `HookSystem` 其实只用那张表记 hook 错误；`SnapshotService` 的会话快照车道列表恒为单元素。
- **会话层**：`AgentSession.laneName` 字段删除，访问器改为 `harness.laneName()`（恒为
  `DEFAULT_LANE`），调用点因此无需改动。`InMemorySessionRepository.ensureLane` 删除。

**顺带修正的两处既有缺陷**（删除才让它们现形，与 §6 那次同一模式）：

1. **内存态 fork 无历史**。此前 `harness.createLane(branchName)` 建的新 lane 是**空的**，
   `LaneConfig.parentLeafId` 只写不读（全仓唯一读者就是 `LaneRegistry` 自己）—— 分支会话
   拿不到父会话的任何 entry。现在走 `harness.fork()` + `seedTranscript` 播种：
   `forkCopy` 播全量（pi `scope:"tree"`），`forkFromEntry` 播到 entry **之前**
   （pi `position:"before"`），找不到即抛。
2. **持久化 fork 与父会话共用车道**。此前持久化分支复用父 harness 并把 `laneName` 设回
   `DEFAULT_LANE` ⇒ 子会话的 `attach` 播种写进了**父会话的车道**，而那条车道非空，
   `seedTranscript` 的「非空即 no-op」守卫直接跳过 —— 于是分支会话显示的是**父会话的
   transcript**，此后两者的运行还往同一条日志里追加。现在子会话拿 `harness.fork()`，
   各自一条空车道，`attach` 的播种真正生效。
3. TUI `TreeSelectorScreen.apply` 此前是 `createLane(选中的名字)`，而列表本来就来自现存
   分支的名字 —— 对任何真实分支都直接抛 `LaneExistsException`，从未生效过。现在接上
   `session.forkFromEntry(leafId)` + `switcher`，即「选中分支点 = 从它分支」。

**验证**：全反应堆 14 模块绿（agent-core 369）；L5 `ConformanceTest` 10/10 不变；
checkstyle 0 违规。删除 `MultiLaneTest`（5 例）与 `AgentHarnessTest` 的 4 例容器 API 测试，
新增 `SingleLaneTest`（4 例：一条车道 / 名字不符即抛 / 连续运行累加 / `fork()` 互不相干）——
**反向实验**：把 `fork()` 改回返回 `this`，`forkReturnsAnIndependentEmptyHarness` 立刻红。

**仍未做的**：`docs/03 §2.2`（`AgentHarness` 核心类）与 §2.3 的类级描述仍停在旧 API 上
—— 已加停止横幅指向本文。**随后已全量重写，见 §8.13。**

---

### 8.13 `docs/03 §2.1-§2.3` 类级重写 —— 已完成（2026-09-13，commit `7b96114`）

`docs/31` 的实施项全部落地后，`docs/03` 第 2 章的三节成了唯一还在说旧 API 的地方
—— 而且**过期早于本轮**：`DriveMode` / `peekAction` / `executeAction` / `runToCompletion`
在 `03d8669` 就删了，那时没同步。

**重写的三节**：

| 节 | 原稿 | 现在 |
|---|---|---|
| §2.1 | pi **harness 层**的 phase 枚举（`idle`/`turn`/`compaction`/`branch_summary`/`retry`）+ mermaid 状态图 | 「运行态：`activeRun`」—— pi `agent.ts` 的对象存在与否；三个「阶段」各自的真实归属；一次运行的生命周期时序图（含 `shouldStopAfterTurn` 先于 `prepareNextTurn` 的顺序修正） |
| §2.2 | Phase 2c 的**推测 API**（`TreeNavigator` / `promptFromTemplate` / 手动驱动 / 多车道容器） | 按 `ab7d309` 的实际形状：`HarnessConfig` 字段表、完整的公开 API、`LaneState` 字段、快照类型、`setModel`/`setThinkingLevel` 的 entry 写入规则 |
| §2.3 | Entry 8 变体 + LaneRecord 的旧形状（`Entry.Message` 还是 `role` + `blocks`） | 真实形状：两个 sealed interface 的公共访问器、Entry 8 变体的字段表、LaneRecord 11 变体的字段表、两层真源的分工、「记录是旁路审计」的边界 |

**`docs/03` 的过期不是「没写」，而是「写了另一个东西」。** 这一章是 Phase 1-2 的
详细设计，此后 pi 对齐（`docs/27` 起）把宿主层整体换掉了，而 §2 描述的是换掉之前的那套。
现在 §2 以 `docs/31` 的结论为准，`docs/31` 以 pi 源码为准。

---

### 8.14 §3.3 / §3.4 的真实状态（2026-09-13 清点，同日按 pi 源码定案）

§8.8 记录了 §5 与 §3.1 的实施偏差，但**漏了 §3.3 与 §3.4** —— 而 §2.1 的映射表把它们
写成「处置」栏里的目标，读起来像已完成。清点后又逐条回 pi 源码核对，结论如下。

#### §3.3 `processEvents` 归约器 —— **结案：残差三项在 pi 里都是死字段，不补**

pi 的归约器写四个字段，Java 侧对照：

| pi `AgentState` 字段 | pi-java | 在 pi 里被读吗 |
|---|---|---|
| `messages` | `LaneState.messages` | ✅ 同形（`PiLaneSink.onMessageEnd`，§4.2） |
| `streamingMessage` | `LaneState.partial` | ✅ 同义，名字不同 |
| `isStreaming` | 由 `activeRun != null` 承担 | ❌ **pi 自己也不读** —— `AgentSession.isStreaming` 取的是它自己的私有字段 `_isAgentRunActive`（`agent-session.ts:916`），不是 `agent.state.isStreaming` |
| `pendingToolCalls` | 无（`PiLaneSink` 内按 callId 索引的 `toolSpans`/`toolStartNanos`） | ❌ `agent.ts:560-569` 只加只删；全仓唯一同名串在 `ai/transform-messages.ts`，是**另一个局部变量** |
| `errorMessage` | 无（`newestOwn` + `determineOutcome`） | ❌ 只在 `agent.ts:574` 写；读它的都是**消息对象**上的同名字段（`response.errorMessage`），不是 `state.errorMessage` |

⇒ **三项都是「写上去了但没人读」的可观察状态**，`isStreaming` 更是连 pi 的产品层都不用它。
按本项目既有的口径（`§4.1` 拒绝为 `ActiveToolsChange` 加发射：「生产零调用者，加发射是
投机代码」），**不补**。若将来有读者（例如 TUI 想显示「N 个工具在跑」），再加不迟 —— 那时
它是为读者而加，不是为形状而加。

`errorMessage` 另有一层：pi 的 **`AssistantMessage.errorMessage`**（`ai/types.ts:442`）是
真的有人在读的（provider 适配层与压缩报告），而 pi-java 的 `AssistantMessage` 没有这个字段。
那属于 `pi-java-ai` 的消息模型，不在本文范围。

#### §3.4 会话层改为订阅者 —— **结案：不实施**，且理由不是成本

设计想要 pi `agent-session.ts:643` 的分工：Agent 发事件 → **会话层 append entry**，
`PiLaneSink` 退化为纯转发。实际是**反向**：harness 造 entry（`PiLaneSink.append`），
会话层订阅并落盘（`SessionRunner.persistPerEntry`）。

**注意：落盘时机已经对齐了。** `persistPerEntry` 就在 `message_end` 上 flush
（`SessionRunner:270-285`），与 pi 的 `session-manager._persist` 同点 —— 崩溃窗口都是
「一条 entry」。剩下的差别只是**谁构造 `Entry` 对象**。

**为什么不该改**：entry 身份在 pi 里是**只有一个权威**（`sessionManager`），而 pi-java 有
两个 —— harness 造 provisional（`seq = transcript.size()`、`timestamp = null`），存储用
`Entry.committed(...)` 重赋（`MemorySessionStorage:85`、`Session:236`）。

一度打算「把提交后的身份写回车道」来统一它们。**读了重试路径后放弃**：

1. pi 的 `_prepareRetry` 把出错的助手消息**只留在会话历史、不留 agent 状态**
   （`dropTrailingErrorAssistant` 就是它）；所以失败回合之后，**两个视图本来就该不同**。
2. 写回会让车道的 `parentId` 跟随存储的链，而存储的链**包含那条被丢掉的 entry** ——
   车道里却没有它。此后任何 `rebuildLaneMessages`（压缩触发）走
   `ContextEntries.pathToLeaf`，会在缺失的父节点处 `break`，把上下文截断成**最后一条消息**。
   ⇒ 写回会把一个「视图不同」变成一次真实的上下文坍塌。

`timestamp = null` 也不是疏漏：那是全仓 `ProvisionedEntry` 的约定（`LaneView:95/102/110`、
`Session:236` 同样传 `null`），语义是「身份由存储赋，尚未提交」。

⇒ **两份视图各自自洽，且按设计必须不同**。要真正统一，得先把「会话历史 ⊃ agent 状态」
这条 pi 的不变量建出来（entry 上区分「只进历史」与「进历史且进上下文」），那是另一个课题，
不是把 append 搬个家。**不实施，理由记录在此。**

> `PiLaneSink` 因此仍是**新 entry 的唯一生产者** —— 这条同时是 §4.2 能成立的前提：
> `messages` 工作副本与 entry 由同一个类在同一个事件点上维护，不必跨层同步。

---

### 8.15 第 9 步遗留 A 项：端口两相拆分 —— 已实施（2026-09-13，commit `aaba914`）

`PiLoop.ToolRunner` 从单相 `run(ToolCall)` 拆成 `prepare` / `execute`，对齐 pi
`agent-loop.ts` 的 `prepareToolCall`（`:607-675`）与 `executePreparedToolCall` +
`finalizeExecutedToolCall`（`:677-764`）。准备相产物是密封的 `Preparation`
（`ImmediateOutcome` | `Prepared`）；`Prepared` 对循环**半透明** —— 只回吐 `call()`，
工具句柄与钩子改写后的参数留在签发者 `PiToolRunner` 里（镜像 pi 的
`PreparedToolCall.tool` / `.args`）。

**直接收益**：L5 唯一放宽规则 `PARALLEL_TOOL_END_ORDER` 连表带守卫删除，十个剧本
首次**全部严格**逐帧比对。反证实验确认严格性有牙：把 end 摆放改回源序批量 →
S4 恰在第 16/17/18 帧变红。

**顺带修掉的四处分歧**（全部先读 pi 源码定案，非臆断）：

| 分歧 | pi 事实（`agent-loop.ts`） | 旧 Java 行为 |
|---|---|---|
| 钩子与查找的顺序 | 查找→校验→`before_tool`（:613-626） | 钩子先于查找 |
| null registry | `tools?.find` → `Tool X not found`（:613-618） | 靠 catch 吞 NPE，文案是 JVM 诊断消息 |
| 中止与拒绝同时命中 | 中止检查排在 block 分支前（:636-661） | 拒绝先赢、还能带 `terminate` |
| 拒绝兜底文案 | `reason \|\| "Tool execution was blocked"`（:643） | `"Tool call denied"` |

**一条被推翻的旧结论**：docs/29 §4 初版宣称「所有 start 都早于任何 end 是 pi 并行
批次的结构保证」，并据 pi 的 `:655-661` 把中止路径改成「每个已 start 的调用补一个
`Operation aborted` end」。**两条都不成立**：pi 的准备循环是 start/准备交替（immediate
的 end 会插到后续 start 之前），且 immediate 收尾后 `break`（:514-516），中止批次只有
第一个调用有帧。S4 录制里被拒调用恰好源序最后，才让旧说法蒙对了帧序。哨兵已重钉为
pi 形状（`abortedParallelBatchFramesOnlyTheFirstCallLikePi`），docs/29 §4/§5 同步更正。

**遗留**：B 项（延迟任务真并发 = pi 的 `Promise.all`）仍开放 —— 当前串行执行在
确定性工具下帧序与 pi 一致，真并发需要先想清楚差分侧怎么验证非确定完成序。
`QueueMode.All` 的宿主层行为变更仍待用户确认。

### 8.16 before/after_tool 钩子形状对齐 —— 已实施（2026-09-13，commit `e252aaa`）

两相拆分（§8.15）落地后再逐行读 pi 的钩子链 —— `prepareToolCall` 的 catch
（`agent-loop.ts:668-673`）、`executePreparedToolCall`（`:677-718`）、
`finalizeExecutedToolCall`（`:720-764`）—— 查出三处分歧，全部对齐：

| 分歧 | pi 事实 | 旧 Java 行为 |
|---|---|---|
| `after_tool` 返回值 | 逐字段 `??` 合并（`:745-751`），`isError = patch.isError ?? executed.isError`（`:752`） | 整体替换 `ToolResult` —— 改 `content` 会**静默吞掉** `terminate` |
| 钩子抛异常 | `before`→immediate 错误结果（`:668-673`）；`after`→错误结果（`:754-757`）；异常不出端口 | 记账后吞掉，等于「放行 / 无操作」 |
| 失败的执行 | 执行相 catch 先把异常转成错误结果（`:708-714`），**收尾钩子照样跑**在它上面 | catch 短路，`after_tool` 被跳过 |

新增 `AfterToolPatch`（五字段补丁，`null`=保留 —— JS 里 `null` 与 `undefined` 同落
`??` 右操作数，pi 也清不掉字段，故 Java 的 null=保留与之一一对应）与
`AfterToolOutcome`（`result` + `isError`；pi 把 `isError` 放在结果**外面**，`:728-729`）；
`ToolResultContext` 补 `isError` 字段（pi 把 `result` 与 `isError` 并列传给钩子，`:736-741`）。
`HookSystem` 的「钩子非致命」契约第一次有了例外：两个工具钩子记账后**向上重抛**，
转换点留在端口 —— 与 pi 的 catch 位置同构（收尾函数的 catch，不是事件总线的 catch）。

**反证实验**（每组恰好红一个哨兵，还原后全绿）：整体替换 →
`afterToolPatchMergesFieldByFieldAndKeepsTerminate`；吞 after 异常 →
`throwingAfterToolBecomesErrorResult`；失败跳过钩子 →
`afterToolHookRunsOnFailedExecutionAndSeesIsError`；吞 before 异常 →
`throwingBeforeToolBecomesImmediateErrorResult`。

**差分覆盖说明**：L5 的 conformance 端口不经 `HookSystem`（剧本无钩子项），这些形状
只有单元级哨兵钉住 —— `PiToolRunnerTest` 5 新例（类内 10/10）、agent-core 375/375、
全 reactor `clean verify` 绿。**结构修正**：§8.15 插错了位置，把 §8.14 与其结语（「两份
视图」段）切开了 —— 已挪回 §8.14 末尾。

### 8.17 end 载荷 = 完整结果树，流式更新补上生产者 —— 已实施（2026-09-13，commit `0fb6c95`）

钩子对齐（§8.16）之后继续逐行读 `executePreparedToolCall`（`agent-loop.ts:677-718`）与
`emitToolExecutionEnd`（`:774-782`），查出两处分歧。**两处的根因是同一个**：两侧
conformance 归一化恰好都丢弃 `result`/`partialResult`/`args` —— 差分对这两帧只比
`id`/`name`/`isError`，所以 L5 的 10/10 从未看过这些载荷。丢字段的豁免必须两侧同时做，
否则「比对通过」只是「没在看」：

| 分歧 | pi 事实 | 旧 Java 行为 |
|---|---|---|
| `tool_execution_end.result` | `finalized.result` **整棵树**（`:779`）；失败路径是 `createErrorToolResult`：`content=[text]`、`details` 为**空对象**非 null（`:767-772`） | 只带 `details`/`text` 替身，错误结果的 `details` 干脆是 null |
| `tool_execution_update` | 工具经 update 回调流出的每个部分结果直接成帧（`:690-704`），`args` 用**原始**调用参数（`:696`），执行落定后 `acceptingUpdates` 闩丢弃迟到回调（`:688`） | 事件类型存在但**从无生产者**（`PiToolRunner` 给工具传 null 回调） |

**移植**（package 1）：`ToolOutcome` 的 `result` 改为完整 `ToolResult<?>`，
`terminate()` 从结果对象派生（批次门判据 `result.terminate === true`，`:590`）；
`ToolRunner.execute(Prepared, Sink)` 开事件汇（pi 把 `emit` 递进
`executePreparedToolCall`，`:679`），`PiToolRunner` 用 `AtomicBoolean` 闩镜像
`acceptingUpdates`；`createErrorToolResult` 落在 `PiLoopTools`（截断/中止/立即失败
三路共用）。**L5 盲区补齐**：两侧归一化纳入 end 的完整 `result` 树与 update 的
`partialResult`（`terminate` 只留 true、`addedToolNames` 只留非空、null/undefined
同落线上 —— 规则逐字镜像，两侧文件互为对偶）；剧本工具新增 `updates`/`details`
字段；新增 **S11**（流式更新 + details 载荷上 wire）与 **S12**（terminate 批次门：
混合批次继续、全真才停 —— 「Ghost turn 永不被消费」即停止的可观察证据）。
12 份 pi 基准全部重算，**严格 12/12**；顺手补回 pi 侧 runner 清单里漏提交的 S10 id
（其基准此前只能靠手改副本生成）。

**反证实验**（每组恰好红预测集合，还原后全绿）：end 降级为 `details` ⇒
S2/S3/S4/S9/S10/S11/S12 七红（S1/S5/S6/S7/S8 无执行过的 end，绿得其所）；
错误结果 `details` 改 null ⇒ **仅 S5**（截断路径）红；every 门改 any ⇒ **仅 S12** 红
（混合批次提前停）；去闩 + args 换成改写后参数 ⇒ `PiToolRunnerTest` 对应两例分红。
还原后 agent-core **381/381**、全 reactor `clean verify` 绿。

**当时的遗留（package 2，A7，已由 §8.18 闭环）**：pi 的 `createToolResultMessage`
（`:784-797`）把 `details`/`usage`/`addedToolNames` 也放在结果**消息**上，随 entry 落库；
当时 pi-java 的 `Message.ToolResultMessage` 只有 `(toolUseId, toolName, content, isError)`。

### 8.18 结果消息载荷 A7 —— 已实施（2026-09-14，commit `0141250`）

package 1（§8.17）把 end 帧的 `result` 树补齐后，剩下的唯一缺席者是结果**消息**。
逐行读 `createToolResultMessage`（`agent-loop.ts:784-797`）与 pi TS 类型
（`packages/ai/src/types.ts:452-468`）钉死三条事实，全部照搬：

| 事实 | pi 出处 | Java 落法 |
|---|---|---|
| 消息带 `details`/`usage`/`addedToolNames`，从结果对象**转发**（非重算） | `:792-794` | `ToolResultMessage` 增三字段；`PiToolRunner.toOutcome` 单点转发（错误路径 `errorOutcome` 也走它 —— pi 的 denied 结果同样只在 `:784-797` 合成一次，两处 details 恒相等） |
| `addedToolNames` 有 length 门（空 ⇒ 键缺席）；`details`/`usage` undefined ⇒ 键缺席 | `:791` `...(length ? {...} : {})` | 编码器主动省略（Jackson 会写 null，JS stringify 丢 undefined —— 豁免方向相反，必须显式不写） |
| 消息**无** `terminate`（只活在结果树里） | 类型 :452-468 无此字段 | 消息不增 terminate；L5 消息帧也不比较它 |

**类型取舍**：`usage` 在消息上声明为 `Object` —— pi 侧是共享 `Usage`，pi-java 的工具
usage（`ToolResult.UsageInfo`）在 agent 模块，ai 不能反向依赖；原样透传（null ≙
undefined）与 pi 的 JS 对象语义同构，且避免字段名翻译（`inputTokens` vs `input`）在
任何脚本里被设值时炸出假差异。`role()` 保持 `"tool"`（持久化方言，改 `"toolResult"`
会连带改破既有会话文件）；WS 线格式由 `WebWireJson` 翻译成 pi 形状。既有的截断/中止
合成点（`PiLoopTools.failTruncated`/`abortedOutcome`）也改为携带结果载荷 —— pi 的
`failToolCallsFromTruncatedMessage`（`:379-404`）同样经 `createToolResultMessage`，
故其消息 `details={}` 而非 null。provider 投影不读这三字段（Mistral 分支改为命名
模式以吸收 arity 变化）。

**四路往返（docs/23c §5）实测结论**：JSONL 写 = `SessionJson.messageNode`（唯一载荷
构建器，`JsonlCodec` 经 `valueToTree` 走它）；JSONL 读 + SQLite 读 = `MessageJsonCodec.decode`
（SQLite 的 `EntryRows` 与 JSONL 共用同一 mapper，编解码同源）；Web WS = `WebWireJson.messageNode`
（独立构建器，需同步改）；**RPC 转录 = 无生产者** —— `TranscriptItem` 类型已定义但没有任何
代码构建它，server 只转发 snapshot/progress 事件。故第四路记为「未接线」，无载荷可测。
新增哨兵：`JsonlSessionStorageTest.toolResultPayloadRoundTripsThroughJsonl`（含行级
判据：无载荷消息不许写出空壳键）、`SqliteToolResultPayloadTest`（open→append→drain→
关→重开全链路）、`WebWireJsonTest.toolResultCarriesStructuredPayloadOnTheWire`、
`MessageTest.toolResultCarriesStructuredPayloadLikePi`、`PiToolRunnerTest.messageForwardsResultPayloadFields`。
**L5 消息帧盲区补齐**：两侧归一化器的 toolResult 消息渲染纳入
`details`/`usage`/`addedToolNames?`（省略规则逐字镜像），12 份基准重算 —— 其中
S1/S6/S7/S8 无 toolResult 消息帧、字节不变，其余 8 份随消息形状更新，**严格 12/12**。

**反证实验**（每组恰好红预测集合，还原后全绿）：RE-A 驱动桩不转发 details ⇒
L5 {S2,S3,S4,S9,S10,S11,S12} 七红、S5 绿得其所（其消息来自宿主 `failTruncated`，
不经驱动注入点）；RE-B 持久化编码器丢 details ⇒ JSONL+SQLite 两 L3 哨兵红、L5 无感
（消息帧取自活事件而非存储，证明了两条链各自独立被钉住）；RE-C 去掉 web 的 length 门 ⇒
`WebWireJsonTest` 恰好 1 红。**实现期踩坑两件**：`ObjectNode.putPOJO` 存的 `POJONode`
在序列化前对树内查询不可见（`get(0)` 得 null）⇒ 改显式 `putArray`+逐元素 `add`；
`findEntries(EntryQuery.all())` 在本测试形状下不保证旧序 ⇒ 按 toolName 定位而非下标。
agent-core **383/383**、ai/web/sqlite 模块绿、全 reactor `clean verify` 绿。

**遗留不变**：B 项（真并发 = pi 的 `Promise.all`）与 `QueueMode.All` 宿主层仍待用户；
`addedToolNames` 的 provider 层消费者（pi 的 native deferred tools）在 pi-java 今日
无对应物，字段按 pi 形状预留（Phase 2c MCP）。

---

### 8.19 assistant 身份与计量 3a —— 已实施（2026-09-14，commit `04deb48`/`e002048`/`a597768`）

package 3（压缩触发时机）拆成 3a/3b/3c，3a 是地基：pi 的每条 assistant 消息在
provider 层构造时就写死 `api`/`provider`/`model` 并带上 `usage`/`timestamp`
（(+`errorMessage` 出错时)），3b 的 `estimateContextTokens` 与 3c 的
`_checkCompaction`（sameModel/stale 判断）全部从这条消息读起 —— 字段缺失时
触发时机与溢出恢复必然偏离。逐行读三处出处钉死形状：

| 事实 | pi 出处 | Java 落法 |
|---|---|---|
| partial 与终局**同一对象形状**，逐字段携带 | `assistant-message-frame.ts:77-92`（`cloneStartMessage`） | `Message.AssistantMessage.fromPartial` 全 9 字段投影（3a 前只搬 content/stopReason，即本次修的丢点） |
| 身份在 provider 流出口挂载 | `providers/faux.ts:281-291`（`cloneMessage`）等 | `AbstractChatApi` 事件出口单点装饰（`IdentitySubscriber`），7 个 adapter 各自声明 `apiName()` KnownApi 字面量 —— `ModelId` 只有 (provider, modelName)，协议是 adapter 的身份 |
| conformance 恒挂 `openai-responses/openai/mock` + 全零 usage（**不经 faux**） | `run.test.ts` `createAssistantMessage` | `ScriptedStreams` 逐字镜像常量；model 恒为 `"mock"`，脚本换模型只影响请求文本不影响消息字段 |

**排除裁决**：pi 类型上的 `responseModel`/`responseId`/`providerThinkingLevel`/
`diagnostics`/`rawStopReason`/`endTurn` 在对齐面（`packages/agent/src`）零消费者
（grep 命中的只有 prompt-templates/skills 同名局部量）⇒ 不移植，pi 改判时清点重开；
`UserMessage.timestamp` 同理不移植。usage 只在有全量分解时用分解，否则由
input/output 计数合成（cache 0、cost 零），无 UsageInfo ⇒ null ⇒ 键省略
（null ≙ undefined，A7 规则）。

**L5 帧豁免（两侧同步）**：timestamp 不进帧（pi 侧 `Date.now()`，两侧都不可复现，
同 toolCallId 的豁免逻辑）；deferred 不进帧（handle id 随机且脚本从不设置）。
12 份基准全部重算（58 行变更），**严格 12/12**。四路往返：JSONL 写 =
`SessionJson.messageNode` 增六条件键；JSONL/SQLite 读 = `MessageJsonCodec`
（共用编解码，SQLite 零改动）；WS = `WebWireJson` 镜像 pi 形状但**维持
「wire 无消息 timestamp」的既有有意偏离**，由
`assistantCarriesIdentityAndMetricsButNoTimestamp` 的 `has("timestamp")==false`
钉住；RPC 转录仍无生产者（现状不变）。

**新哨兵**：`MessageTest` 五例（9 组件构造、fromPartial 全字段、计数合成、
无 UsageInfo⇒null、withStopReason 保身份）、`AbstractChatApiTest.barePartialExitsWithProviderIdentityAttached`
（裸 partial 出口挂身份）+ 回归 EVENT 自带身份钉住「已挂 ⇒ 透传不覆盖」，
`JsonlSessionStorageTest.assistantPayloadRoundTripsThroughJsonl`（含 message
子对象级原始行判据）、`WebWireJsonTest` 上述哨兵。

**反证实验**（每组恰好红预测集合，还原后 384/384+237/237 全绿、残留扫描零命中）：
RE-A `fromPartial` 摘掉 usage+身份 ⇒ L5 **12/12 红** + `MessageTest` 两例红、
其余 382 例无感；RE-B `SessionJson` 跳过 3a 六键 ⇒ 384 里**恰 1 红**（L3 哨兵），
L5 全绿 —— 证明消息帧与持久化两条链各自独立被钉住。

**实现期踩坑三件**：① Java **数值条件表达式**（JLS 15.25）：
`cond ? Long.valueOf((long) v) : Double.valueOf(v)` 两分支皆可转数值 ⇒ 整体提升
为 double，装箱被编译器当场拆掉再 `l2d`（字节码实测坐实）—— 帧渲染的 `0.0` vs `0`
假红根因，修成两条独立 return，注释已钉「别改回三元」；② 单独 `surefire:test`
不带 `-am` 会链到 ~/.m2 旧 pi-ai jar ⇒ `NoSuchMethodError: withIdentity`
（[[jdk25-mvn-am]] 的又一次显形）；③ entry 层自带 `timestamp` 字段，旧形状行的
整行 substring 判据会误报 —— 行级判据改判到 message 子对象。

**遗留**：3b（`estimateContextTokens` 移植 + `checkThreshold` 操作数改
`model.contextWindow` + `contextWindow>0` 护栏；现 `ContextEstimator` javadoc
声称对齐实为 chars/3.5，属**虚假声明**，随 3b 修正）、3c（`_checkCompaction`
四守卫；`isContextOverflow`/`isRecoverableLength` 定义尚未定位）—— 后已落地（§8.21，判据定位于 `packages/ai/src/utils/overflow.ts`）；B 项
（真并发）与 `QueueMode.All` 待用户。agent-core **384/384**、ai **237/237**、
全 reactor `clean verify` 绿。

### 8.20 压缩计量 3b —— 已实施（2026-09-14，commit `8159d38`/`d5923ff`/`5aa88d6`）

3a 把每条 assistant 消息的身份与用量带进了消息体，3b 消费它：阈值门与
tokensBefore 改读 **pi 那份估算函数**，操作数换成**当前模型**的 contextWindow。
逐行读 pi 两处（注意路径：**compaction.ts 在 `packages/agent/src/harness/compaction/`，
而阈值门在 `packages/coding-agent/src/core/agent-session.ts`** —— 早前笔记把
agent-session.ts 记成 packages/agent 下，是错的，在此更正）：

| 事实 | pi 出处 | Java 落法 |
|---|---|---|
| `calculateContextTokens(usage) = usage.totalTokens \|\| input+output+cacheRead+cacheWrite` | compaction.ts:164-166 | 显式 0 判等（JS falsy ≙ Java `!= 0`），`ContextUsageEstimator.calculateContextTokens` |
| 有效用量 = role assistant ∧ usage 在 ∧ stopReason∉{aborted,error} ∧ 折算 >0；**从尾部回溯**找最后一条 | :167-180, :215-243 | `assistantUsageOf` + 倒扫；命中 ⇒ `tokens = usage + Σ estimateTokens(其后消息)`，未命中 ⇒ 全列表字符估算 |
| `estimateTokens`：每消息 `ceil(chars/4)`；user/toolResult/custom = text + image(4800)；assistant = text + thinking + toolCall(`name.length + JSON.stringify(arguments).length`)；`safeJsonStringify` 失败 ⇒ `"[unserializable]"` | :251-310, :38-43 | `ContextUsageEstimator.estimateTokens`（sealed 三角色 switch）+ `SessionJson.mapper()` 序列化 |
| 阈值门 `!model \|\| model.contextWindow <= 0 \|\| !shouldCompact(estimateContextTokens(context.messages).tokens, window, settings)` ⇒ 跳过 | agent-session.ts:542-559；shouldCompact compaction.ts:246-249 | `CompactionExecutor.checkThreshold` 逐条同形；窗口经 `HarnessConfig.contextWindow:ToIntFunction<ModelId<?>>` 由宿主解析（resolver 目录值 `ModelInfo.maxInputTokens` ≙ pi contextWindow；未编目 ⇒ 0 ⇒ 跳过），fallback 静态 maxInputTokens 保留旧装配形状 |
| prepareCompaction **:638** 只有「空路径 ∨ 末条是 compaction ⇒ 不可压」；**单条可压**（切点落自己身上） | compaction.ts:638, :370-398 | 旧实现两处 `size<=1` 守卫是**发明**，撤下；阈值侧静默跳过、手动侧抛（文案属宿主表面） |
| `tokensBefore = estimateContextTokens(...).tokens` 单一来源，阈值/手动/溢出一条路 | :667 | `CompactionExecutor.contextTokens(lane)`；`CompactionService.compact` 增 4 参 `tokensBefore` 纯透传 |
| findCutPoint 累加复用**同一个** estimateTokens | :387 | `CompactionService.findCutPoint` 改读 `ContextUsageEstimator.estimateTokens` |

**Java 方言裁决**（钉在类 javadoc）：UserMessage 恒为块列表（pi 的裸字符串分支不存在）；
`UrlImageContent` 按图像常数 4800；`ToolUseContent.arguments` 经 `Map.copyOf` **不可能
为 null** ⇒ pi 的 `?? "undefined"` 分支在本构造面不可达，仅作形状保留；压缩摘要经
ContextEntries 投影成 user 消息读全文，前缀字符计入是**有界启发式差异**（pi 自己也是
启发式）。

**L5 口径**：conformance 剧本不设 compactionSettings ⇒ 门短路，估算器不触帧；
ScriptedStreams 恒 ZERO_USAGE ⇒ 用量锚点恒无效、走字符路径 —— 全 reactor 绿内
**严格 12/12 复证**（`ConformanceTest tests="12" failures="0"`）。

**新哨兵**：`ContextUsageEstimatorTest` 14 例（total 优先/分项回退、锚点+trailing、
aborted/error/zero 永不锚定且扫描继续、ceil 边界、双图像方言、toolCall 长度、
`{}`、嵌套 null、循环引用哨兵、shouldCompact 边界）；`CompactionThresholdGateTest`
9 例（守卫逐条 + :638 静默 + 用量/字符双路起爆 + 窗口随当前模型）；
`LaneMessagesTest` 3b 段 3 例（500 用量起爆而字符永不可、零窗口不压、百万窗口不压）。
夹具修正两处：`compactReducesTranscriptToRetentionRatio` keep 8→20（ceil 累加改变
兜底路可达性，注释记录意图）；`compactThrowsWhenTranscriptTooSmall` 重写为
`compactThrowsOnlyOnEmptyTranscript_pi638`。

**反证实验**（每组红集与预测核对手法同 3a）：RE-1 操作数改回
`ctx.maxInputTokens()` ⇒ **恰 5 红**（GateTest 用量/字符/窗随模型 3 例 +
LaneMessages 2 例，守卫类测试无感 —— 它们不依赖操作数来源）；RE-2 摘用量锚点 ⇒
**恰 7 红**（估算器锚点 3 例 + 依赖用量起爆的 4 例，字符路哨兵无感）；RE-3 摘 :638
守卫 ⇒ **恰 1 红**（lastEntryIsCompactionSkipsSilently）；RE-4 塞回 `size<=1` 发明 ⇒
4 红（预期 1，偏差原因：GateTest 夹具转录本就只有 1 条 —— 单条可压正是 pi 行为，
红在守卫该在的地方，全部 4 个仍由该编辑单独引起）。全部还原后残留扫描零命中，
定向 6 类复验绿。

**遗留**：3c 已落地（§8.21：`_checkCompaction` 全守卫 + `packages/ai/src/utils/overflow.ts:134
isContextOverflow` / `:171 isRecoverableLength` 移植，自造的 OverflowDetector 已删除）；
ContextEstimator（chars/3.5 死链）javadoc 虚假声明已改为指向本估算器。

### 8.21 溢出恢复与收尾检查 3c —— **已实施（2026-09-14，设计经用户审核通过；实施记录见 8.21.6）**

> 本节是 3c 的准入设计文档。**未经审核认可前不写任何实施代码。**
> pi 事实全部逐行读自 `packages/coding-agent/src/core/agent-session.ts`、
> `packages/ai/src/utils/overflow.ts`、`packages/ai/src/utils/retry.ts`、
> `packages/agent/src/harness/compaction/compaction.ts`（3b 已读的函数不复述）。

#### 8.21.1 pi 事实（运行后检查全景）

pi 的收尾循环住在宿主 `_runAgentPrompt`（agent-session.ts:1101-1114）：
`agent.prompt()` 完成后 `while (await _handlePostAgentRun()) await agent.continue()`。
`_handlePostAgentRun`（:1116-1144）按序三查，**重试在前、压缩在后**：
①`_isRetryableError(msg) && _prepareRetry(msg)` ⇒ true 即续跑（:1123）；
②`_checkCompaction(msg)` ⇒ true 即续跑（:1137）；③`agent.hasQueuedMessages()`（:1143）。
两机制的交接由判据本身完成：**`_isRetryableError` 第一行就是
`if (isContextOverflow(message, window)) return false`（:2876-2880，注释明文
"Context overflow errors are NOT retryable (handled by compaction instead)"）**
⇒ 溢出错误永远不耗重试预算、必然落到压缩路。

`_checkCompaction(assistantMessage, skipAbortedCheck=true)`（:2154-2258）逐守卫：

| # | 守卫/分支 | pi 行为 | 出处 |
|---|---|---|---|
| G0 | `!settings.enabled` ⇒ false | 全局开关最先 | :2156 |
| G1 | `skipAbortedCheck && stopReason==="aborted"` ⇒ false | **prompt 预检传 false**（ catches aborted responses，:1258-1263），运行后检查传默认 true | :2159 |
| G2 | `contextWindow = this.model?.contextWindow ?? 0` | 操作数=当前模型窗口（3b 已接 resolver） | :2161 |
| G3 | `sameModel = model && msg.provider===model.provider && msg.model===model.id` | **只闸溢出两判据**（:2183-2184）；阈值 case 3 **不闸** | :2167-2168 |
| G4 | `compactionEntry=getLatestCompactionEntry(getBranch())`；`assistantMessage.timestamp <= compactionEntry.timestamp` ⇒ false | **全局闸**（含阈值路），防陈旧消息重触发 | :2173-2178 |
| C1 | `contextOverflow = sameModel && isContextOverflow(msg, window)` | 判据①（下详） | :2183 |
| C2 | `recoverableLength = sameModel && isRecoverableLength(msg, model?.maxTokens ?? 0)` | 判据②；操作数是**输出上限原值**（目录 maxOutputTokens） | :2184 |
| R0 | 溢出命中后 `willRetry = stopReason !== "stop"` | 成功完成的 length-stop 只压不续 | :2186-2192 |
| R1 | `willRetry && _overflowRecoveryAttempted` ⇒ 发 `compaction_end{result:undefined, aborted:false, willRetry:false, errorMessage:固定文案}` + `_emitSessionCompactFailed`，return false | 一次性 compact-retry 闩；**两文案**：overflow⇒`"Context overflow recovery failed after one compact-and-retry attempt. Try reducing context or switching to a larger-context model."`；recoverableLength⇒`"Truncated response recovery failed after one compact-and-retry attempt."` | :2194-2214 |
| R2 | 置闩 ⇒ `agent.state.messages` 尾若为 assistant 则**摘除**（日志不动）⇒ `_runAutoCompaction("overflow", true)` | 摘除在 prepare 前；**重建后还会再摘一次**（:2410-2419，注释 :2413 解释：日志重建会把 kept 的失败 assistant 复活） | :2216-2223 |
| T1 | 阈值复查：`direct = msg.usage ? calculateContextTokens : 0`；`stopReason==="error" \|\| direct===0` ⇒ `estimate=estimateContextTokens(state.messages)`；**若锚点消息在 compaction 界前 ⇒ false**（:2237-2249，防刚压完被压缩前的旧用量再推过线）；`contextTokens=estimate.tokens`；否则 `contextTokens=direct` | 与 prepareNextTurn 门（:542）互补：一个轮间、一个**运行收尾** | :2226-2256 |
| T2 | `shouldCompact(contextTokens, contextWindow, settings)` ⇒ `_runAutoCompaction("threshold", false)` | | :2254 |

`_runAutoCompaction(reason, willRetry)`（:2270-2445）形状：`!model⇒false`；
`prepareCompaction⇒undefined⇒false`（3b 已对齐）；发 `compaction_start{reason}`；
`session_before_compact` 扩展钩子（cancel⇒`compaction_end{aborted:true}`+failed⇒false；
自定义结果⇒直采）；摘要期间可中止（aborted⇒end+failed⇒false）；
`appendCompaction` ⇒ **`getEntries()/buildSessionContext()/state.messages=` 整体重建**（:2380-2382）；
`estimatedTokensAfter = estimateMessagesTokens(newContext.messages)`（:2383，
**纯字符 ΣestimateTokens**，:294-300 —— 不用用量锚点）；发 `session_compact` 扩展事件；
发 `compaction_end{result, willRetry}`；willRetry ⇒ 重建后再摘尾 error/length（R2 注）⇒
return true（续跑）；否则 return `hasQueuedMessages()`；
catch ⇒ `compaction_end{errorMessage: reason==="overflow" ?
"Context overflow recovery failed: "+msg : "Auto-compaction failed: "+msg}`+failed+false。

闩的复位点（都在 message 事件上，非运行边界）：① **user message_start** ⇒ false（:643）；
② **assistant message_end 且 stopReason∉{error,length}** ⇒ false（:694-696）。

`isContextOverflow(message, contextWindow?)`（overflow.ts:134-163）三路：
case1 `stopReason==="error" && errorMessage` 存在 ⇒ 先过 **NON_OVERFLOW 3 条排除**
（`Throttling error|Service unavailable:` / `rate limit` / `too many requests`）
再过 **OVERFLOW_PATTERNS 25 条**（Anthropic/OpenAI/Google/xAI/Groq/OpenRouter/
Together/llama.cpp/LM Studio/Copilot/MiniMax/Kimi/DS4/Cerebras/Mistral/z.ai/Ollama/
DashScope…逐字在 :37-63，含 `model'?s` 的 ASCII（0x27）撇号可选类与 `[\d,]+` 数字逗号类；
全文 U+2019 字节级复核零命中，旧稿「U+2019 撇号」系误记，见 8.21.6 更正）；
case2 **静默溢出**（z.ai 形）：`contextWindow && stopReason==="stop" &&
usage.input+usage.cacheRead > contextWindow`；
case3 **length 零输出**（MiMo 形）：`contextWindow && stopReason==="length" &&
usage.output===0 && input+cacheRead >= contextWindow*0.99`。
`isRecoverableLength(message, desiredMaxOutput)`（:171-173）：
`stopReason==="length" && desiredMaxOutput>0 && usage.output < desiredMaxOutput`。

#### 8.21.2 pi-java 现状差距

| 面 | 现状 | 差距 |
|---|---|---|
| 溢出判据 | `OverflowDetector.isOverflow(null, lastStopReason, UsageInfo旁路累计, ctx.maxInputTokens())` | **javadoc 声称 aligned 实为自造**（§8.19 ContextEstimator 同族问题）：子串 5 个 vs 25 正则、无排除表；case2 无 `stopReason==="stop"` 闸、算 `input+output` 而非 `input+cacheRead`、操作数静态 maxInputTokens；case3 无 `>=0.99*window` 满窗条件；不读 errorMessage（3a 字段没消费） |
| 收尾守卫 | `checkOverflowAfterRun` 只有 `settings!=null && transcript.size()>1` | **G1/G3/G4/R1 全无**；`size>1` 又是发明（3b 同款，撤）；**T1/T2 阈值复查完全没有**（轮间门只覆盖 prepareNextTurn） |
| 溢出恢复 | 压完就完 | **R0/R2 没有**：error/length 尾不摘、不续跑 ⇒ pi 会自动重试一次的行为在 pi-java 是哑的 |
| 事件面 | `AgentSessionEvent.CompactionStart/CompactionEnd`（含 reason/aborted/willRetry/errorMessage 全形状）**生产代码零发射者** | 又一处「无生产者」（与 RPC 转录同族）；两条固定失败文案无落点 |
| 结果载荷 | `CompactionResult.estimatedTokensAfter` 恒 null（CompactionService:54） | pi 在两条自动路都填 ΣestimateTokens(新上下文) |
| prompt 预检 | 无 | pi :1258-1263（aborted 响应在**下一次用户输入前**补查阈值） |

#### 8.21.3 改动方案

**pi-java-ai**（判据同层归位，pi 在 packages/ai）：
- 新建 `com.pijava.ai.utils.ContextOverflow`：`isContextOverflow(AssistantMessage, Integer window)`
  + `isRecoverableLength(AssistantMessage, long desiredMaxOutput)`。25+3 条正则**逐字移植**
  （`Pattern.CASE_INSENSITIVE`；撇号是半角 ASCII 0x27 原样保留，全文无 U+2019（8.21.6 更正）；`x?'` 型字符类不动）。
  读消息对象 = 3a 字段的第一个消费者（errorMessage/usage 分解/stopReason）。
  window 参数 `Integer`：null ≙ pi undefined ⇒ case2/3 短路（pi `if (contextWindow &&` 的
  falsy 闸含 0 ⇒ **0 也短路**，判等用 `!= null && > 0`）。
- **删除** `agent-core/context/OverflowDetector`（`OverflowDetectorTest` 改写为对 ContextOverflow 的判据测试）。

**agent-core**（机制层，§4.2 同族裁决）：
- `ContextUsageEstimator` 补 `estimateMessagesTokens(List<Message>)`（纯字符 Σ，供 estimatedTokensAfter）。
- `HarnessConfig`/`ExecutionContext` 新增两槽：
  ① `ToIntFunction<ModelId<?>> maxOutputTokens`（fallback `ignored -> 0`；pi `model?.maxTokens ?? 0`
  同缺 ⇒ isRecoverableLength 恒 false，保守形状）；
  ② `CompactionObserver observer`（onStart(reason, willRetry) / onEnd(reason, result|null,
  aborted, willRetry, errorMessage)，agent-core 内建 record `CompactionEvents`；默认无操作。
  **裁决取新槽不取 HookSystem**：钩子是用户可Cancel/替换的扩展面，事件发射是内部接线，
  且 HookSystem 按车道注册、这里要的是无条件旁路。）
- `LaneState` 加 `boolean overflowRecoveryAttempted`（pi 会话级字段，非运行级）；
  复位两点对齐 pi 事件位：`PiLaneSink` 的 user message_start、assistant message_end（stop∉{error,length}）。
- 新类 `harness/PostRunCompactionCheck`（package 私有）= `_checkCompaction` 逐条移植：
  G0→G1(skipAborted 参数)→G2/G3(经 3b resolver)→G4(分支尾扫 compaction entry，
  `Instant` 比较，消息 timestamp null ⇒ **不跳** ≙ JS `undefined <= n` 为 false)→
  C1/C2→R0/R1/R2→T1/T2。**阈值路复用 3b 的 contextTokens/checkThreshold 已确立形状，
  不另造第二份估算。**R1 固定文案两串入常量。R2 摘尾 = `lane.messages.removeLast()`
  （日志不动，对齐 :2219-2221）。
- `CompactionExecutor.applyCompaction` 接 observer 两发射点 + 重建后**再摘尾**
  （willRetry 且新上下文尾 assistant stop∈{error,length}，:2410-2419）+
  填 `estimatedTokensAfter`；reason 枚举化 manual/threshold/overflow。
- `PiLaneEngine.drive`：`checkOverflowAfterRun` 换成 `do { run } while (postRun.check(msg, true))`
  —— 续跑走与 continueRun 相同的 `PiLoop.continueRun` 通道（每次重挂 runId、重开 sink 形状）。
- `prompt` 起点（仅带 prompt 的驱动，continueRun 不做）：先 `_findLastAssistantMessage` 同形扫描，
  跑 `check(lastAssistant, /*skipAborted=*/false)`，**忽略返回值**（pi 注释 :1259：
  用户消息紧接着自己会发，不在这里 continue）。

**coding-agent**（宿主接线）：
- `AgentSession.assemble`：`.maxOutputTokens(models::maxOutputTokens)`（DefaultModelResolver 补一个
  目录读法，3b 同法）；`.observer(e -> owner::emitSessionEvent 映射)` 把 CompactionStart/End
  接进既有事件面（**JsonEventMapper 已会映射，零生产变有生产**）。
- `SessionRunner.isRetryableError`：溢出排除改走共享判据（对最后一条 assistant 消息调
  ContextOverflow.isContextOverflow），撤 `CONTEXT_OVERFLOW_MARKERS` 子串表。
  ⚠️ **只对齐「溢出⇒不重试」的交接，白名单问题不在本包**（见 8.21.5-3d）。

**L5 预期**：conformance 无 compactionSettings ⇒ G0 最先短路；ScriptedStreams 无
overflow 停因 ⇒ 预期 **12/12 不动**（跑 strict 复证）。

**测试计划**：① ai 判据表驱动（25 条 OVERFLOW 各 1 命中样本、3 条 NON_OVERFLOW 压制、
case2 stop 闸+input+cacheRead 口径、case3 0.99 边界、window null/0 短路、
isRecoverableLength 三条件）；② PostRunCompactionCheck 逐守卫哨兵（G1 双向、G3 只闸溢出、
G4 全局 + null-timestamp、R0 成功 length 只压不续、R1 双固定文案、R2 双摘尾、
T1 锚点过期分支）；③ drive 续跑端到端（脚本：overflow 错误 turn ⇒ 压 ⇒ 第二请求 ⇒ 成功，
断言请求数=2、日志含 compaction、observer 收到 start/end 各 2、闩不复位则二次红）；
④ prompt 预检（aborted 尾 + 下一次 prompt 前压）；⑤ estimatedTokensAfter 填值；
⑥ 反向实验 5 组（摘 G4/摘 R1 文案路/摘续跑/摘预检/判据退回旧 Detector ⇒ 各自恰红）。

#### 8.21.4 需要审核的裁决点

1. **续跑回路住在 agent-core `drive()` 内环**（pi 在宿主外环）——沿用 §4.2「机制进核、
   数据经 resolver 注入」的既有裁决；交错顺序已被 `_isRetryableError` 的溢出排除守住，
   两条路对同一消息互斥。
2. **判据归位 pi-java-ai**，删 agent-core 的 OverflowDetector（含其 javadoc 不实声明一并消灭）。
3. **事件走新 `CompactionObserver` 槽**，不走 HookSystem（理由如上）。
4. **maxOutputTokens 缺席 ⇒ 0 ⇒ recoverableLength 恒 false**：宁可不恢复，不误恢复。
5. **消息 timestamp 为 null 时 G4/锚点过期检查不跳**（JS `undefined <= n === false` 的忠实形状）。

#### 8.21.5 取证新发现（登记，不入 3c）

- **3d 自动重试判据白/黑名单反转**：pi `isRetryableAssistantError`（retry.ts:235-240）
  = `stopReason==="error"` ∧ errorMessage 命中 **RETRYABLE_PROVIDER_ERROR_PATTERN 白名单**
  （overloaded/rate limit/429/5xx/…retry.ts:26-45）**且**不命中
  NON_RETRYABLE_PROVIDER_LIMIT 配额类（:7-24）才可重试；pi-java
  `SessionRunner.isRetryableError(String)`（:315-326）是**默认全可重试**的黑名单
  （null⇒true！），且无 stopReason 判。差异会在「401/参数错等确定性错误」上烧重试预算。
- **settings 按模型覆盖**：pi `getCompactionSettings(model)`（settings-manager.ts:891-901）
  reserve/keepRecent 支持 per-model override；pi-java 的 settings supplier 无模型参数。
- **compaction `details` 生产者**：pi-java `CompactionResult.details` 恒 null；pi 填
  readFiles/modifiedFiles（摘要生成路产出）——并入既有的「/compact 命令面复查」清单项。
- `_emitSessionCompactFailed`（扩展层事件）：扩展层在 docs/27 §4 排除面 ⇒ observer 只保证
  会话事件 `compaction_end.errorMessage`，扩展事件不发 —— 列**待用户**。

#### 8.21.6 实施记录（2026-09-14）

**提交**：`af213a8` feat(ai)（ContextOverflow 逐字移植 + 目录 maxOutputTokens，判据测试 8 例）
→ `8c4a0dc` feat(agent-core)（G0→T2 全守卫、闩、observer、continue 边界、驱动续跑；13 守卫哨兵 + E2E）
→ `60804f7` feat(coding-agent)（observer 接会话事件面；isRetryableError 撤
CONTEXT_OVERFLOW_MARKERS 改走共享判据）。全 reactor `mvn -o -am clean verify` 绿、
L5 strict **12/12** 不动（命中 8.21.3 预期：conformance 无 compaction 设置 ⇒ G0 最先短路）。
设计测试计划 ①–⑤ 全落（判据表驱动 / PostRunCompactionCheckTest 13 例 / PostRunOverflowDriveTest
端到端续跑与单趟 / abortedSkippedAfterRunButPrePromptCatchesIt 预检 /
freshUsageAnchorFiresThresholdWithPureTokensAfter 钉 estimatedTokensAfter）。

**形状按设计，实施期修正三处：**

1. **continue 边界补齐成 pi `Agent.continue()`（agent.ts:362-388）的原文**——设计稿只写了
   「续跑走 continueRun 通道」。实施时把 pi 的前导判定原样搬进 `PiLaneEngine.continuePrompts`：
   **守卫读工作副本尾（不是日志尾）**；尾是 assistant ⇒ 先 `drainSteer`、空则 `drainFollowUp`、
   都空抛 `"Cannot continue from message role: assistant"`；尾非 assistant ⇒ 空 prompts
   （纯续跑，pi runContinuation 形）。排空出的消息**不预写日志**，由该 pass 的
   message_start/message_end 事件路径落日志（pi `runPromptMessages` 同形，与 startRun 的
   预写路径相对）。`RunLifecycle.startContinue` 改签收 prompts，原「日志尾空 / assistant 尾」
   两道守卫删除（它们站错了源，是 HookTest/E2E 首跑两红的根因）。
   **驱动环里续跑判定先于 `finishRun`**——抛错落在上一 pass 未关的 op 内、由 finally 收口，
   旧顺序（先 finish 再判）有 double-finishRun 风险。QueueConsumed 仍记旧 runId
   （排空先于收尾），按 D10「谁排空谁发射」接受。
2. **`checkThreshold`（轮内门）改道 `runAutoCompaction`**——pi 轮内路（:550）同样发
   compaction_start/end；旧实现自造一条不发事件的静默压缩路。改道后 `runAutoCompaction` 是
   唯一自动入口，`AutoCompactionOutcome(compacted, shouldContinue)` 用两个问题答复 pi 一个
   boolean 的两类调用方（R0/R2 问 shouldContinue，T2 同理，轮内门问 compacted）。
3. **post-run 输入 = `PiLaneSink.lastAssistant`** ≙ pi `_lastAssistantMessage`（读后即清，
   每 pass 一个新 sink；suppressed 消息不计）；prompt 预检扫工作副本（`findLastAssistant`，
   含 aborted），返回值照 pi 忽略。

**测试侧修正（钉 pi 真值，非回归）：**
- `LaneMessagesTest.overflowingTurn` 夹具补 `withUsage`+`withIdentity`：剧本世界没有
  AbstractChatApi 出口盖章，判据读的是**终局消息对象**（C1/sameModel/T1 直读全靠它）——
  旧夹具 usage 只活在事件流里、无身份戳，3c 判据「看不见」这条静默溢出。
  **剧本夹具规范**：要被判据看见的 assistant 必须自带身份与终局 usage。
- `nonPositiveWindow…` 旧钉「窗口≤0 完全不压」重写为「轮内门静默、post-run T 路照压」：
  `shouldCompact`（compaction.ts:235-238）**没有** window>0 守卫，那道守卫只住在轮内门
  （agent-session.ts:543）⇒ 窗口 0 下阈值线 = `-reserve`，任何正读数过线。旧钉是拿轮内门
  守卫脑补 T 路的发明。
- `HookTest` 的 shouldStopAfterTurn 钉改判：运行内不排 followUp（agent-loop.ts:252-255 在
  :261 排空**之前** agent_end+return），但 ③ 看到队列有货 ⇒ continue 排空再跑一个 pass
  ⇒ 总请求 2、followUp 空、`passRunIds` 2。

**反向实验（先预测红名单再动刀，五红一发现）：**
- E1 摘 G3（`sameModel ⇒ true`）⇒ 恰 1 红 `differentModelOverflowTextIgnored` ✓
- E2 给 `check()` 塞回发明的 `window<=0 ⇒ false` ⇒ 红 `nonPositiveWindow…` ✓
  （同预测里的 aborted 例未红——其夹具窗口非零；预测过含，无害）
- E4 摘 ③（`checkAfterRun` 只回 `check`）⇒ 恰 2 红 `queuedMessagesAloneDriveAContinuation`
  + `HookTest.shouldStopAfterTurnEndsRunButPostRunContinueDrainsFollowUp` ✓
- E5 摘 `continuePrompts` 的队列排空 ⇒ 红 HookTest（落进 throw 路）✓
- E6 给 R1 分支加发 onStart ⇒ 恰 1 红 `latchedOverflowAnnouncesFailureWithEndOnly`
  （钉死 pi :2194-2211 的「只 end 无 start」形状）✓
- **E3/E3b/E3c 全绿 —— 登记为发现，不是漏洞**：R2 副本摘尾（pi :2214-2218）与重建后
  `dropTrailingRetryableAssistant`（pi :2410-2419）在 pi-java 当前可达形状上**互为冗余**
  ——rebuild 的切断点永不把 assistant 送回副本尾，单独或双双 no-op 都无可观测差异。
  pi 两道都在，门判是「和 pi 表现一样」，**两道都保留**；可观测兜底是 continuePrompts 的
  throw（E2E 下验过其红）。

**更正**：8.21.1/8.21.3 两处「U+2019 撇号」为误记——pi `overflow.ts` 全文 `e2 80 99`
字节级**零命中**，`model'?s` 型字符类是纯 ASCII 0x27；移植逐字照此（af213a8 提交信息同步记录）。
**存量登记**：`coding-agent/AgentSession.java` 在 HEAD 已 837 行（超 500 限），3c 仅 +45 行、
未夹带结构拆分——拆分并入例行清点项，不进本包。

---

### 8.22 自动重试环 3d —— **已实施（2026-09-15，设计经用户审核通过；实施记录见 8.22.6）**

> 本节是 3d 的准入设计文档。**未经审核认可前不写任何实施代码。**
> pi 事实全部逐行读自 `packages/coding-agent/src/core/agent-session.ts`、
> `packages/ai/src/utils/retry.ts`、`packages/agent/src/agent-loop.ts`、
> `packages/coding-agent/src/core/settings-manager.ts`、
> `packages/coding-agent/src/core/compaction/compaction.ts`（3c 已读的 overflow/压缩路不复述）。

#### 8.22.1 pi 事实（两环全景）

**环 A：post-run ①（会话级续跑重试）**。`_handlePostAgentRun` :1123
`_isRetryableError(msg) && await _prepareRetry(msg)` ⇒ true 即 `agent.continue()`。判据链：

- `_isRetryableError`（:2876-2880）：第一行 `isContextOverflow(msg, this.model?.contextWindow ?? 0)`
  ⇒ **false**（溢出交压缩，交接在分类器这一层，3c 已落判据本身）；否则
  `isRetryableAssistantError`（retry.ts:235-240）：`stopReason==="error"` ∧ `errorMessage` 非空
  ∧ **不**命中配额排除表（`NON_RETRYABLE_PROVIDER_LIMIT_ERROR_PATTERN`，6 条拼接正则 :7-24：
  `GoUsageLimitError|FreeUsageLimitError|Monthly usage limit reached|available balance|
  insufficient_quota|out of budget|quota exceeded|billing`）∧ **命中**白名单
  （`RETRYABLE_PROVIDER_ERROR_PATTERN`，30 条 :26-90：overloaded / `rate.?limit` /
  too many requests / 429/500/502/503/504/524 / service·server·internal.?error /
  provider.?returned.?error / 连接网络族 / timeout·terminated / websocket 族 /
  stream 早断族 / retry delay / gRPC `ResourceExhausted`…）。两表都 `new RegExp(patterns.join("|"), "i")`。
  **null/缺失 ⇒ false —— 默认不重试**，与 pi-java 现状的「null ⇒ true」黑名单正好反转。
- `_prepareRetry`（:2917-2965）：`enabled` 复查 ⇒ false；`_retryAttempt++`；
  **`> maxRetries` ⇒ `--` 还原后 false**（保完成计数给终局失败事件）；
  `delayMs = retryDelayMs(settings, attempt)`（:111-115：`base * 2^max(0,attempt-1)`、
  `Number.isSafeInteger` 护栏、`min(…, maxAgentDelayMs ?? 60_000)`）；发
  `auto_retry_start{attempt, maxAttempts, delayMs, errorMessage||"Unknown error"}`；
  **只摘工作副本**（尾是 assistant ⇒ `state.messages = slice(0,-1)`，:2937-2941，日志保留 ——
  用户历史里看得见那次失败）；可中止 sleep：退避中被 abort ⇒
  `auto_retry_end{success:false, attempt, finalError:"Retry cancelled"}` + 计数清零 + false
  （:2948-2957）；成功睡完 ⇒ true ⇒ continue（摘除后尾非 assistant ⇒ 纯续跑，3c 已落）。
- **计数是会话级**（`_retryAttempt` :339，跨 prompt 存活），三个复位点：
  ① assistant message_end 且 `stopReason!=="error"` 且 `attempt>0` ⇒ 发
  `auto_retry_end{success:true, attempt}` + 清零（:698-706，防同轮多 ring 累加）；
  ② post-run 终局失败：`msg.stopReason==="error"` ∧ `attempt>0`（① 返回 false 之后）⇒
  发 `auto_retry_end{success:false, attempt, finalError:msg.errorMessage}` + 清零
  （:1127-1134）；③ 退避 sleep 中止（上）。**运行边界不复位**。
- `agent_end` 装饰（:666）：每个 pass 的 agent_end 带
  `willRetry = _willRetryAfterAgentEnd(event)`（:721-733）：`!enabled ∨ attempt >= maxRetries`
  ⇒ false；否则**倒扫 `event.messages` 找第一条 assistant** 过 `_isRetryableError`。
  `event.messages` = 本 pass 的 newMessages（agent-loop.ts:217/:253）。装饰先于复位
  （:666 在 :694-706 前 ⇒ 用 ring 前计数）。预算耗尽链：末次错误 pass 的 agent_end
  `willRetry` 已 false ⇒ 紧接终局失败 `auto_retry_end` ⇒ 再走 ②③。
- `abort()`（:1639-1646）与 `dispose()`（:876-885）先 `abortRetry()`；
  `isRetrying` = `_retryAbortController` 非空（:2977-2979）；isIdle 含重试。
- **`retryAssistantCall`（retry.ts:174-224）不在环 A** —— 环 A 的退避环就是
  post-run ① + continue；retryAssistantCall 只被**环 B**（摘要）与 bedrock 使用（grep 实测：
  coding-agent/compaction/compaction.ts:598、harness 层（排除面）、bedrock api）。

**环 B：摘要重试**。compaction/branch-summary 的每次摘要 LLM 调用走
`completeSummarization`（coding-agent/compaction/compaction.ts:578-600）：
`retryAssistantCall(produce, settings.retry, signal, callbacks)` —— **与环 A 同一份
`settings.retry` 预算/退避**；callbacks ⇒ 会话事件 `summarization_retry_scheduled
{attempt,maxAttempts,delayMs,errorMessage}` / `summarization_retry_attempt_start
{source:"compaction",reason}|{source:"branchSummary"}` / `summarization_retry_finished`
（:2888-2911、:1941-1942、:3243-3244）。retryAssistantCall 语义：abort 终局**不重试**
（但曾 schedule 过 ⇒ 报 `onRetryFinished(false,…)`；退避中被 abort ⇒ 归一化成
`{...response去掉errorMessage, stopReason:"aborted"}` 形状）；非白名单错误 ⇒ 直接返回；
预算耗尽 ⇒ 返回末次错误。重试耗尽后 `getSummarizationFailure`（:545-553）
（error ⇒ `"Summarization failed: <errorMessage||Unknown error>"`；length ⇒
`"…generation hit the token cap and the summary is incomplete"`）然后 **throw**
（:712-718；摘要里出现 toolCall 也 throw）⇒ 上层 catch 落
`compaction_end{errorMessage}`。**没有静默截断兜底**。

**设置**：`getRetrySettings()`（settings-manager.ts:927-933）=
`enabled??true / maxRetries??3 / baseDelayMs??2000 / maxAgentDelayMs??60000`；
`setRetryEnabled` 写全局 + markModified + save。`retry.provider.*`（timeoutMs /
maxRetries / maxRetryDelayMs??60000）是 **SDK/provider 层**，与本两环不同层。

**L5 预期**：ScriptedStreams 无 error 停因、无摘要调用 ⇒ 预期 **12/12 不动**。

#### 8.22.2 pi-java 现状差距

| 面 | 现状 | 差距 |
|---|---|---|
| 环位置 | `SessionRunner.drive` 外层 do-while 整场重跑；压缩/队列在**引擎环内**（3c） | **顺序反转**：pi ①重试→②压缩；pi-java 先对错误尾跑 ②（T1 error⇒估算可过线）再由外层重试。非溢出可重试错误 + 过阈值 ⇒ 事件与日志次序都不同。attempt 是 **per-drive 局部**，pi 会话级跨 prompt |
| 判据 | `isRetryableError(String)` 黑名单：`null⇒true`，仅溢出一刀（窗口传 null） | 白名单反转（见上）；stopReason/errorMessage 双闸；溢出交接改读**真窗口**（case1 虽不消耗窗口，行为等价，形状须对齐消息对象 API） |
| 事件 | AutoRetryStart/End 由 SessionRunner 按 attempt 时点发；退避无 cap | message_end 成功复位事件缺（:698-706）；"Retry cancelled" 分支缺；终局失败 finalError 来源不同；`agent_end.willRetry` 现在是本次 shouldRetry 局部量，且 **AgentEnd 每 drive 一条**（pi 每 pass 一条带装饰）；`summarization_retry_*` 三事件无 |
| 摘尾 | `AgentHarness.dropTrailingErrorAssistant` → RunLifecycle:318 **摘日志尾 + 重建副本** | pi 只摘副本、日志保留（唯一调用方在 3d 后消失）⇒ 公开 API 删除 |
| 环 B | `LlmSummaryGenerator` 单次调用，**失败/空输出静默回退截断摘要**（:118） | pi：同预算重试 + 失败/length/toolCall **throw**；兜底是发明，掩盖 `compaction_end{errorMessage}` |
| 设置 | `MAX_RETRIES=3`/`BASE_DELAY_MS=2000` 常量；开关内存 volatile | maxRetries/baseDelayMs/maxAgentDelayMs 不可配；enabled 未持久化（pi save+markModified） |

#### 8.22.3 改动方案

**pi-java-ai**：新 `com.pijava.ai.utils.RetryableError` —— `isRetryableAssistantError(AssistantMessage)`
（两拼接正则逐字移植，`CASE_INSENSITIVE`，与 overflow.ts 同层同文件位）；
`RetryBackoff.delayMs(base, maxAgentCap, attempt)`（纯函数，两环共用，safe 值 + cap）。
**不动** `com.pijava.ai.http.RetryPolicy`（HTTP 层，是 pi SDK/provider 层的 pi-java 方言，另列清点）。

**agent-core**：
- `RetrySettings` record（harness 方言，4 字段 ≙ getRetrySettings 返回）；
  `HarnessConfig`/`ExecutionContext` 新三槽（18 参兼容 ctor 续用 + compact 默认）：
  `Supplier<RetrySettings> retrySettings`（默认 `true/3/2000/60_000` ≙ pi 默认）、
  `BooleanSupplier retryAborted`（默认 `() -> false`）、`RetryObserver retryObserver`（默认 NOOP）。
- 新 `RetryObserver`（接口，与 CompactionObserver 并列）：`onAutoRetryStart(attempt,maxAttempts,delayMs,errorMessage)`、
  `onAutoRetryEnd(success,attempt,finalError)`、`onSummarizationRetryScheduled(attempt,maxAttempts,delayMs,errorMessage)`、
  `onSummarizationRetryAttemptStart(source,reason)`、`onSummarizationRetryFinished()`。
- `LaneState.retryAttempt`（int，会话级；运行边界不复位）。`PiLaneSink.onMessageEnd`
  在 3c 闩复位点旁：`stopReason!=="error" ∧ attempt>0` ⇒ `onAutoRetryEnd(true,attempt,null)` + 清零。
- 新 `PostRunRetry`（package 私有，= `_isRetryableError` + `_prepareRetry` + `_willRetryAfterAgentEnd`）；
  `PostRunCompactionCheck.checkAfterRun` 改名/扩为 pi 全形：
  `①retry → 终局失败复位块 → ②compaction → ③queue`（委托 PostRunRetry + 现有 check）。
  ① 命中时摘尾＝role-only **副本**（日志不动，pi :2937-2941；与 R2 同形状、各自原位不互用）。
  退避 sleep 在引擎环内（阻塞驱动方言），每 50ms 轮询 `retryAborted`。
- `AgentHarness` 公开 `boolean retryWouldFollow(AssistantMessage)` ≙ `_willRetryAfterAgentEnd`
  单判（enabled/预算/倒扫消息）—— coding-agent 装饰 AgentEnd 用；引擎 ① 处再算一遍
  （pi 也是装饰、① 各算各的，不共享缓存）。**删** `dropTrailingErrorAssistant`（公开 + RunLifecycle）。
- `SummaryGenerator.generate` 加 `reason` 参；`LlmSummaryGenerator` 内装 retryAssistantCall
  同形环（produce=单发，读 retrySettings，可被 run abort/`retryAborted` 中断 ⇒ 归一 aborted），
  回调走 `RetryObserver`（source="compaction"，reason 透传）；`getSummarizationFailure`
  + toolCall 检查逐条移植后 **throw IllegalStateException** ⇒ 现有 catch 链落
  `compaction_end{errorMessage: "Auto-compaction failed: …"}`。**撤** 失败/空输出截断兜底
  （空输出 ≙ pi 空文本摘要，合法）。`SummaryGenerator.truncating()` 默认槽保留（harness 方言，非产品路）。

**coding-agent**：
- `AgentSession.assemble`：`.retrySettings(…从设置读…)`、`.retryAborted(owner::retryAborted)`、
  `.retryObserver(匿名 → AgentSessionEvent.AutoRetryStart/End + 新
  SummarizationRetryScheduled/AttemptStart/Finished 三变体)`；`setAutoRetryEnabled` 持久化。
- `SessionRunner.drive`：do-while **撤**；一次 `prompt()`（或 continueRun）即全剧；
  AgentEnd 改**每 pass** 一条（downstream 包装收 `PiLoop.Event.AgentEnd`，
  `willRetry = harness.retryWouldFollow(尾 assistant)`）；RunSummary `attempts` 改读
  `outcome.passRunIds().size()`；异常兜底 catch 保留（引擎内部失败面，实施时核对
  pi agent.ts:520-527 的 failureMessage 路在 pi-java 引擎是否已有对应物，**不臆造**）。
- `JsonEventMapper`：AutoRetry 族既有；summarization 族新增映射。

**测试计划**：① ai 白名单表驱动（每条至少一命中样本、6 排除、null/非 error ⇒ false、
大小写）；② PostRunRetry 逐守卫（预算 `>=` 装饰 vs `>` 还原链、start 四字段、role-only
副本摘尾日志不动、abort⇒"Retry cancelled"+清零、终局失败 finalError+清零、message_end
成功复位事件）；③ **顺序哨兵**：可重试错误尾 + 高用量过阈值 ⇒ 重试先跑、本轮**无**压缩
entry、`auto_retry_start` 早于任何 compaction 事件（钉住与旧行为相反的那一钉）；
④ 环 B 事件链（瞬断重试成功 / 确定性错误快失败 / 预算耗尽 ⇒ `compaction_end{errorMessage}`
含 "Summarization failed" / length / toolCall）；⑤ E2E：error→退避→continue 成功双 pass、
`passRunIds=2`、AutoRetryStart+End(success) 顺序、③ 队列与重试闩/计数互不污染；
⑥ 反向实验 ~6（白名单换回黑名单、①/② 换序、撤成功复位、abort 不可取消、兜底不撤、
装饰改扫全局尾）各自预测红名单后动刀。

#### 8.22.4 需要审核的裁决点

1. **两环都进 agent-core**：① 进引擎 post-run（新 `PostRunRetry`，SessionRunner do-while 撤）——
   否则 ①→② 顺序无法对齐；设置/中止/事件经 ExecutionContext 注入（§4.2 与 3c 续裁决）。
2. **AgentEnd 每 pass 一条 + `retryWouldFollow` 公开判据**（装饰与 ① 各算各的，保 pi 形状）；
   payload 沿用现有 accumulatedMessages（频率对齐、载荷差异登记 8.22.5-3）。
3. **事件走新 `RetryObserver` 槽**（auto_retry_* + summarization_retry_* 共五法），不进
   HookSystem（续 3c 裁决③理由）；coding-agent 映射到会话事件面。
4. **白名单判据归位 ai**（与 overflow.ts 同层）；`_isRetryableError` 溢出交接改传**真窗口**。
5. **摘要路加 reason 参、重试环入 generator、撤截断兜底改 throw**；`truncating()` 默认槽保留。
6. **`dropTrailingErrorAssistant` 删除**：pi 的摘尾只动副本；重试摘尾在 PostRunRetry 原位实现。

#### 8.22.5 取证新发现（登记，不入 3d）

- `retry.provider.*`（SDK 层 timeoutMs/maxRetries/maxRetryDelayMs）↔ pi-java ai HTTP
  `RetryPolicy` 的映射对照 —— 独立清点项。
- **branch summary 在 pi-java 无实现**（grep 零命中）—— pi `branch-summarization.ts:349-353`
  的 `source:"branchSummary"` 重试路形状保留，功能本体列清单项。
- TUI/RPC 对 auto_retry / summarization_retry 的渲染（倒计时、`isRetrying`、isIdle 含重试）
  —— 并入命令/界面面清点。
- pi 的 agent_end 载荷是**本 pass newMessages**，pi-java 是 accumulatedMessages（3d 前既有
  选择）；3d 只改频率，载荷全量对照列清单项。

#### 8.22.6 实施记录（2026-09-15）

**提交**：`101a8ca` feat(ai)（retry.ts 白名单反转判据 `RetryableError` + `RetryBackoff`
指数退避；表驱动 55 例）
→ `2f920a0` refactor(agent-core)（新 `PostRunRetry`（环 ① 本体）+ `RetrySettings`/
`RetryObserver`/ExecutionContext 三槽 + `checkAfterRun` 全序 ①→终局失败→②→③；
`LlmSummaryGenerator` 装 `retryAssistantCall` 同形环、撤截断兜底改 throw；
`dropTrailingErrorAssistant` 删）
→ `f27b1bd` fix(agent-core)（provider 错误投影：`PiLoopRunner.withErrorShape` 补
stopReason/errorMessage，`PiLaneSink` 的 abort 重写同步 errorMessage）
→ `3607641` feat(coding-agent)（SessionRunner do-while 撤、`agent_end` 每 pass 一条 +
`retryWouldFollow` 装饰、用户回声挪首个 `agent_start`、retry 三件套接线、`abortRetry`
controller 生命周期、`setAutoRetryEnabled` 即刻持久化、`JsonEventMapper` summarization 族）。
全 reactor `mvn -o -am clean verify` 绿（telemetry 26 / ai 336 / agent-core 436 /
session-backend-sqlite 35 / coding-agent 218 / tui 188(1 skip) / protocol 14 / server 2 /
web 37 / evals 43(17 为 smoke skip)；BOM/root/client/dist 无测试）；
L5 strict **12/12**（`agent-core` 的 `harness.conformance.ConformanceTest`，同轮复跑确认；
3d 零改动 `conformance/` ⇒ pi-out 基线仍有效）。
设计测试计划 ①–⑤ 全落：① `RetryableErrorTest` 55 例表驱动 + `RetryBackoffTest`（含
safe-integer 与 cap 回退）；② `PostRunRetryTest` 12 例逐守卫；③ 顺序哨兵
`retryWinsBeforeCompactionThisPass`；④ `CompactionServiceTest` 9 例环 B 事件链；
⑤ E2E `AgentSessionRetryEventOrderTest.retryEventsKeepPiOrderWithSingleEcho`
（单回声全序）。

**形状按设计，实施期修正六处：**

1. **provider 错误投影是环 ① 的活命条件**（`f27b1bd`）——pi 把 `stopReason`/
   `errorMessage` 盖在助手消息上，pi-java 只把文本放在 `StreamError` Throwable、投影出的
   partial 可能只是 identityBase 空壳。不补投影则白名单判据在真 provider 路上**恒 false**、
   环 ① 纯装饰（单测喂手工消息看不出来）。修正：`withErrorShape` 只在投影缺失处补
   reason/message（partial 自带终局与 text 优先，先例 `LlmSummaryGenerator.terminal`）；
   `PiLaneSink` 的 abort 重写同步 errorMessage，让文本活到 `determineOutcome`。
2. **顺序哨兵夹具要「有牙」**：原夹具读数 ~2 token、永不过 `window - reserve`，空 transcript
   又撞 `runAutoCompaction` 的 `:638` 静默跳 ⇒ ①②换序**不可观测**。补 1000 字符轮 +
   预置 transcript 后「环 ② 不发射」才是真断言（RE 换序恰此一红）。
3. **用户回声挪到首个 `agent_start`**：`agent_end` 改每 pass 一条后，回声留在原处会排在
   第一次 `agent_end` 之后，顺序破。
4. **`abortRetry` 取 pi 的 controller 生命周期**：只在退避睡眠在飞时有效，窗口由 observer
   的 `beginRetrySleep`/`endRetrySleep` 开/关（替换原 `resetRetryAbort`）；`abort()` 与
   `close()` 先调 `abortRetry()`（pi `:1639`/`:876`）。
5. **`setAutoRetryEnabled` 即刻持久化**（pi `setRetryEnabled` = 全局写 + markModified +
   save），且判定端每轮经设置**实时读**，不缓存。
6. **`Settings.unknown()` 的 `Map.copyOf` → `unmodifiableMap(new HashMap<>(…))`**：`Map.copyOf`
   拒 null 值 ⇒ 任何「未知字段值为 null」的设置无法 round-trip。3d 的 `retry: null`
   （`??` 链默认值语义下的合法形状）撞上 3d 之前的读取端即 **NPE**（`SettingsManager.migrate`
   读 `queueMode`/`websockets` 处是暴露点）。回归测试
   `SettingsManagerTest.futureUnknownFieldWithNullValueLoadsRoundTripAsUnknown`；该修复
   **独立成提交** `aa7d6ac`（`fix(coding-agent)`），因它修的是存量健壮性，不是 3d 的功能面。

**反向实验（先预测红名单再动刀，六红全中）：**
- RE-1 撤白名单（退回黑名单形状）⇒ 恰 1 红 `RetryableErrorTest.deterministicErrorsAreNotRetryable` ✓
- RE-2 退避睡眠改不可中止 ⇒ 恰 1 红 `abortDuringBackoffEmitsCancelledEndAndZeros` ✓
- RE-3 摘终局失败块 ⇒ 恰 2 红 `terminalFailureEmitsFinalErrorOnceBudgetDead`
  + `terminalFailureFinalErrorPassesThroughEmptyAsPi` ✓
- RE-4 装饰门 `>=` 改 `<` ⇒ 恰 1 红 `decorationGatesMirrorPiOrder` ✓
- RE-5 摘 `PiLaneSink` 的 message_end 成功复位 ⇒ 红
  `retryEventsKeepPiOrderWithSingleEcho` **并** `RpcDispatcherTest.autoRetryRerunsAfterError`
  （预测 1、实得 2——RPC 路同样穿过该复位点，复位点的第二处钉，非串扰）✓
- RE-6 撤环 B 的 throw（退回截断兜底）⇒ 恰 3 红 `CompactionServiceTest` 的
  `errorResponseThrowsSummarizationFailure` / `exhaustedBudgetThrowsFinalErrorWithEvents` /
  `lengthStopThrowsIncompleteCapMessage` ✓
- （①②换序的 RE 见 `f27b1bd` 提交信息：撤/换后恰顺序哨兵一红。）

**注意（环境/工具，非代码问题）：**
- **本地仓库陷阱**：Maven 本地库是 `D:/repository`（非 `~/.m2`），其中 pi-java 各模块构件
  可能**陈旧**（实测 09-13 版）。单模块 `-pl <M> test` **不带 `-am`** 会解析到旧构件 ⇒
  假绿/假红（本轮一次孤立运行即用旧 `Settings` 复现了 6. 的 NPE）。改动底层模块
  **必须带 `-am`**；配 `-Dtest=` 用时一并加 `-Dsurefire.failIfNoSpecifiedTests=false`。
- **`web` 的 `PiWebServerAuthTest` 对负载敏感**：本轮三次全 reactor 红、第四次绿，
  红时恰在 15s 轮询预算处（`createWeb` 启动延迟），单独跑与轻载下均绿 —— 记为环境敏感，
  非 3d 回归。
- **`mvn … | tail` 吞退出码**：管道退出码取 `tail`，需 `PIPESTATUS[0]`（本轮首跑即因此
  把 FAILURE 误读为 exit 0）。

**存量登记（越线，拆分列清点项、不进本包）**：`coding-agent/AgentSession.java` 现 **988 行**
（8.21.6 记录时 837，本包 +136）；`agent-core/AgentHarness.java` 现 **502 行**，本包（`2f920a0`
+15）首次越 500 线 —— 两处均未夹带结构拆分。

---

### 8.23 工具批次真并发（B）—— **已实施（2026-09-16，设计经用户审核通过；实施记录见 8.23.7，顺序规则见 8.23.8，update 时机见 8.24）**

> 本节是 B 的准入设计文档。**未经审核认可前不写任何实施代码。**
> pi 事实逐行读自 `packages/agent/src/agent-loop.ts:409-591`（`executeToolCalls` /
> `executeToolCallsSequential` / `executeToolCallsParallel` / `executePreparedToolCall` /
> `finalizeExecutedToolCall`）；pi-java 现状读自 `PiLoopTools`、`PiToolRunner`、
> `PiLaneEngine`、`PiLaneSink`、`JsonlFileTelemetry` 与 `conformance/` 剧本格式。
> 前序：§8.17（end 载荷 + 流式更新）、§8.18（结果消息载荷 A7）、§8.19-§8.22。

#### 8.23.1 pi 事实

**批次门**（`:409-424`）：`config.toolExecution === "sequential"` **或**批内任一工具的
`executionMode === "sequential"` ⇒ 整批走串行路径（已对齐，`PiLoopTools.useSequentialPath`）。

**并行路径三段**（`:487-561`）：

1. **准备循环**（串行、源序）：逐调用发 `tool_execution_start` → `await prepareToolCall`
   （查找 → 校验 → `before_tool` → 中止检查 → 拒绝检查）⇒
   - `kind === "immediate"`（拒绝 / 未找到 / 参数非法 / 已中止）**当场**发
     `tool_execution_end` 并 push 定局结果，`aborted ⇒ break`（`:506-517`）——它的 end
     因此排在**所有真正执行过的调用之前**；
   - `kind === "prepared"` ⇒ push 一个 **thunk**（`async () => {…}`，`:520-541`），
     **此刻还没执行**；入队后再查中止，`aborted ⇒ break`（`:542-544`）。
2. **执行段**（`:547-549`）：`await Promise.all(entries.map(e => e()))` —— 全部 thunk
   **真并发**启动，pi 不设并发上限。每个 thunk **自己**在完成时 `await emitToolExecutionEnd`
   （`:539`）⇒ **end 帧 = 完成序**；thunk 首查 `signal?.aborted` ⇒ 不执行、直接发
   `createErrorToolResult("Operation aborted")` 的 end（`:521-528`）。
   `Promise.all` **保持输入顺序** ⇒ `orderedFinalizedCalls` 是**源序**。
3. **收束**（`:550-560`）：按**源序**逐条 `createToolResultMessage` + `emitToolResultMessage`；
   `terminate = 非空 ∧ every(result.terminate === true)`（`:589-591`）。

**两套顺序不同源（本包核心事实）**：`tool_execution_end`（与 `tool_execution_update`）=
**完成序**（谁先完成谁先发）；`toolResult` **消息**（`message_start`/`message_end`）=
**源序**，且在**全部 end 之后**统一发。

**串行路径**（`:431-485`）：每个调用 start → 准备 → 执行 → 收尾 → end → **立刻**发其
message，逐个成组；`signal?.aborted ⇒ break`（`:476-478`）。它与并行路径的
「ends 全先、messages 全后」**是两种形状**（已对齐）。

**中止是协作式的，不打断在飞**：`executePreparedToolCall`（`:677-718`）把 `signal` 交给
工具本体，**没有**任何「abort ⇒ 取消执行」的竞速；是否提前收手由工具自己观察信号决定。
已启动的 thunk 一定跑到返回、并发出自己的 end。

**流式更新**（`:682-706`）：`onUpdate` 把每个分片**内联**发成
`tool_execution_update`（`emit(...)` 在回调里同步调用，`:692-702`），返回的 Promise 收进
`updateEvents`；工具返回后 `await Promise.all(updateEvents)`（`:706`）—— 被推迟的是
**完成**（该工具的全部 update 处理完才发它的 end），**不是发射点**。
> ⚠️ 本节初版写作「成批落地，**不是内联发射**」，措辞有误，已按 §8.24.1 的逐行重读更正。

**异常**：thunk 内部 `executePreparedToolCall` 自己有 catch（`:708-714` 转错误结果），
收尾段也有（`:754-757`）；**`Promise.all` 本身没有 try/catch** ⇒ thunk 若仍抛出，
整个批次 reject、向上冒到 run 的外层收口。

#### 8.23.2 pi-java 现状与差距

`PiLoopTools.executeParallel`（`:142-186`）**已是两相结构**：准备循环串行（start /
准备 / immediate 当场收尾 / break）、prepared 打包成 `Supplier<ToolOutcome>`（end 在
supplier 内发射）、收束按源序发消息 —— **形状与 pi 一致**。

差距**只有一处**（`:175-178`）：

```java
var outcomes = new ArrayList<PiLoop.ToolOutcome>();
for (var entry : entries) { outcomes.add(entry.get()); }   // ← 串行执行
```

后果两条：① **耗时 = 累加**而非取最大（用户可感知：N 个慢工具慢 N 倍）；
② **end 帧 = 源序**（pi 是完成序）—— 但瞬时工具下两序**同形**，故 L5 钉不出这处差异
（`conformance/scripts/S4.json`、`S10.json` 的工具都即时返回，见 8.23.6）。

**并发面审计**（B 把这些面从单线程变多线程，逐个过了一遍）：

| 面 | 现状 | 结论 |
|---|---|---|
| `AbortSignal` | `volatile boolean` | ✅ 可并发 |
| `ToolRegistry.tools` | `ConcurrentHashMap` | ✅ |
| `ToolContext` | 全 final（cwd/env/shell/fs） | ✅ 共享安全；`shell`/`fs` 实现自身需可重入（pi 同契约） |
| `HookSystem` 注册表 | `ConcurrentHashMap` | ✅；钩子**体**将在工具线程执行（pi 同） |
| `PiLaneEngine.observing.execute` → `sink.noteToolTerminate` | `HashMap.put` | ⚠️ **将变成工具线程写** ⇒ 需并发映射 |
| `PiToolRunner` 的 `onUpdate` → `emit` | 内联同步调用 | ⚠️ **工具线程调用宿主事件链** ⇒ 需串行化点 |
| `PiLaneSink` 其余状态（`lane.messages`/`records`/`transcript`、批次表、`stepIndex`…） | 只在 `message_end`（**收束后**、源序）与准备相被碰 | ✅ 不在并发窗口内 |
| `JsonlFileTelemetry.writeLine` | `synchronized (lock)` | ✅ |
| `JsonlFileTelemetry.currentStack` | 全局 `ArrayDeque`（javadoc 却写 "on this thread"） | ⚠️ 见 8.23.5-1；约束：**工具线程不得触碰遥测绑定** |

#### 8.23.3 改动方案

**`PiLoopTools`（执行段）**：

- 真并发：每批次建一个 `Executors.newVirtualThreadPerTaskExecutor()`，全部 thunk 一次性提交，
  **按源序 `get()` 收结果**（消息与 `terminate` 的源序由此天然保持），批次结束即关闭
  （`try`-with-resources；**不** `shutdownNow` —— pi 不打断在飞任务）。虚拟线程 × 1/任务、
  不设上限 —— pi 的 `Promise.all` 同样不设。
- **thunk 内部一个字不改**：end 的发射位置已经在 thunk 里，并发后顺序自动变成完成序。
- `Future.get()` 的 `ExecutionException` ⇒ **解包原样重抛**（保 pi「批次级失败、异常向上冒」
  的形状）；`InterruptedException` ⇒ 恢复中断位并**按中止处理**（Java 方言，先例
  `PostRunRetry.sleepInterruptible`）。
- 不使用预览 API（结构化并发在 JDK 25 仍属预览）——`ExecutorService` 是正式面。

**`PiLaneSink`（串行化点）**：

- `emit` 全程 `synchronized`（专用 `lock` 对象，先例 `JsonlFileTelemetry.writeLine`）。
  它是**所有事件通往宿主的唯一漏斗**（`PiLaneEngine` 的每一处发射都经它），加锁即把宿主
  消费者重新变回「单线程事件循环」—— 这正是 pi 的运行时语义（JS 单线程 + `await emit`），
  并发只存在于**工具体**。
- `toolTerminate` 换 `ConcurrentHashMap`（唯一会被工具线程写的映射；读点在收束之后）。

**不动**：串行路径、`failTruncated`、准备循环、`acceptingUpdates` 闩、批次门、两相端口。

#### 8.23.4 需要审核的裁决点

1. **并发手段**：每批次一个虚拟线程 executor（推荐）/ 复用共享池 / `Thread.ofVirtual()`
   手工 join。批级作用域 = pi 的 `Promise.all` 作用域，且天然跟随批次生命周期。
2. **串行化点落在 `PiLaneSink.emit`**（推荐）——宿主消费者（会话事件、TUI/RPC/web、记录发射）
   因此**不需要**任何改动，保持既有单线程假设；代价是工具线程若在某消费者上阻塞会被拖住
   （pi 的 `await emit` 同）。
3. **遥测**：本轮**不在工具线程绑定跨度**（推荐：现状本就不绑，保持「遥测绑定只在引擎线程」
   的约束），把 `currentStack` 的线程归属**登记为独立清点项**（8.23.5-1）。
   不在本包内顺手改 ThreadLocal —— 那会静默弄丢「跨线程继承」这一既有行为，须先实测
   流式回调的线程归属。
4. **中止语义照 pi**：不打断在飞；已启动的调用跑完、各发自己的 end。用户若期待
   「abort 立刻停」，这是**有意与 pi 一致**的行为，文档写明。
5. **异常解包原样重抛**，不换类型（照 pi 批次级失败向上冒）。
6. **验证手段用 latch 交替夹具**（推荐，确定性）：「A 等 B」—— 并发下 B 先完成、A 后完成，
   断言 end 顺序 = `[B, A]` 而消息顺序 = `[A, B]`；**串行实现下 A 等不到 B ⇒ 夹具必然失败
   （而非侥幸通过）** ⇒ 天然有牙且不 flaky。**不做时长阈值断言**（flaky，且 latch 已直接
   证明并发）。
7. **是否给 L5 剧本格式加 `delayMs`**（可选加项）：加则能录一条**差分**场景（慢调用后完成），
   把「完成序」变成两侧同录的硬证据（剧本格式现为
   `(name, executionMode, details, updates)`，**无延迟字段**）；代价是扩格式（两侧 runner
   同步）+ **重跑 pi 侧录制**。不加，则 B 的证据只到单元/E2E 级、L5 维持不动。

#### 8.23.5 取证新发现（登记，不入 B）

1. **`JsonlFileTelemetry.currentStack` 是全局 `ArrayDeque`，javadoc 却写 "on this thread"**
   —— 文档与实现不符。当前唯一 `pushCurrent` 调用点（`PiLaneSink.beginRequest`）与
   `recordEvent` 调用点（`PayloadRecordingStreamFn`）**是否同线程未实测**。清点项：线程归属
   实测 + 是否改 ThreadLocal（含 `recordEvent` 的语义）。
2. **`tool_execution_update` 的发射时机差异**：pi 收集成 Promise、在该工具 end 前成批落地
   （`:690-706`），pi-java 内联发射。单工具下同形；**多工具交错**时 L5 覆盖不到
   （剧本工具不产 update 交错）。
   → **已结案为包 C，见 §8.24**（结论：发射点两侧一致；「成批」指的是完成序保证，
   在同步漏斗下平凡成立；交错盲区由新剧本 S14 补上，RE-1 证明该剧本有牙）。
3. **宿主消费者的并发契约**：收敛到 `PiLaneSink.emit` 的锁后仍是「互斥的单线程调用」，
   但 TUI/RPC/web 的消费者此前没有任何显式线程声明 —— 清点项（B 之后调用方来自引擎线程
   **与**工具线程两种，锁保证互斥，但「总是哪个线程」不再唯一）。
4. **内置工具的线程安全审计**（`coding-agent` 侧 read/write/edit/bash/glob…）：pi 的契约是
   「工具必须可并发执行」；本轮**只审计不改**，发现共享状态的工具单独立项。
5. **`approvalHandler` 与钩子体在工具线程执行**（pi 同）：交互类钩子（权限询问）需自己保证
   线程安全。

#### 8.23.6 测试计划

① **并发夹具（latch 交替）**，落在 `ToolBatchParityTest`/`PiLoopTest`：
   - `laterCallFinishesFirstEndsFirstButMessagesStaySourceOrder`：A 等 B ⇒ end 顺序 `[c2, c1]`、
     消息顺序 `[c1, c2]`；
   - `abortedBatchStillEmitsInFlightEnds`：中止后已启动的调用各发 end（协作式、不打断）；
   - `immediateEndStaysAheadOfConcurrentEnds`：准备相 immediate（拒绝）的 end **仍排在**
     并发 end 之前（S4 形状）—— 这批不变量在并发下必须重钉；
   - 既有 `ToolBatchParityTest` 三例（terminate 语义）保持绿。
② **L5 差分**：预期**不动**（S4/S10 工具即时返回 ⇒ 完成序 = 源序，两侧录制同形）——
   必须实跑确认 12/12。若采纳裁决点 7，则新增 **S13**「慢调用后完成」并重跑 pi 侧录制后比对。
③ **反证（先预测红名单再动刀）**：
   - RE-1 thunks 改回串行 ⇒ 恰红 ① 的 latch 夹具（并发夹具**必然**失败，非侥幸）；
   - RE-2 摘 `PiLaneSink.emit` 的锁 ⇒ 并发交错探测器（两线程各发 N 个 update，sink 进入即记
     「重入」）**应**红。**诚实标注：该哨兵是概率性的**（大 N + 交替提高概率），是本包
     **已知的验证弱点**，反证时必须确认它能红；
   - RE-3 immediate 收尾挪到收束之后 ⇒ 恰红 `immediateEndStaysAheadOfConcurrentEnds`。
④ **E2E**：`AgentHarness` 层一个双工具批次（`prompt(lane, text, images, downstream)` 注入
   记录型 sink），钉 end 完成序 + 消息源序 + `terminate` 语义 + 转录落盘顺序（源序）。

#### 8.23.7 实施记录（2026-09-16）

**提交**：`66d4793` feat(agent-core)（`PiLoopTools` 执行段改真并发 +
`awaitOutcome` 解包重抛；`PiLaneSink.emit` 全程 `synchronized`、`toolTerminate` 换
`ConcurrentHashMap`）→ `13c5ee6` test(agent-core)（`ToolBatchConcurrencyTest` 6 例；
L5 剧本格式加 `delayMs`（两侧 runner 同步）；S4/S12 用延迟钉完成序、新 **S13**；
pi 侧重录；`PiLoopTest` 那条断言按名字收窄）。
全 reactor `mvn -o clean verify` 绿（telemetry 26 / ai 336 / **agent-core 443** /
session-backend-sqlite 35 / coding-agent 218 / tui 188(1 skip) / protocol 14 / server 2 /
web 37 / evals 43(17 为 smoke skip)；BOM/root/client/dist 无测试）；
L5 strict **13/13**（S1–S13，含新 S13），**连跑 7 轮全绿** —— 这正是本包要修掉的 flake
（见下第 1 条）。

**形状按设计，实施期修正五处：**

1. **§8.23.6 ②「L5 预期不动」被证伪 —— 这是本包最重要的一条更正。** 真并发落地后
   S12 立刻红：`8 轮里 3 绿 5 红，红的一律且只有 S12`，diff 是 `pi: tc3 的 end 在 tc4 前 /
   java: tc4 在 tc3 前`。根因**不是**实现错，是设计的证据假设错：S12 第二批两个调用是同一个
   `term1`（**等延迟**），而 pi 那边「等延迟 ⇒ 源序」是 **JS 微任务队列的副产品**
   （thunk 体在源序里同步进入队列），**不是 pi 的语义承诺**；Java 的真线程没有那条队列，
   等延迟就是纯竞速。S4 的 ok1/ok2 同形（8 轮全绿，是运气而非不变量）。
   处置 = 采纳裁决点 7：剧本加 `delayMs`，把**完成序变成声明出来的、两侧同录的证据**
   —— S4 的 `ok2`、S12 拆出 `term2`（20ms/40ms），S13 则把「后声明者先完成」直接做成
   场景。pi 侧重录结果：**S4 逐字节不变**（延迟不改帧，只定序），S12 只有 `term1→term2`
   的改名。
2. **`PiLoopTest.parallelBatchEmitsEveryStartBeforeAnyEnd` 的断言比它的名字宽**：名字说的是
   「start 全在前」，断言却多钉了 end 的具体顺序（`bash, read, grep`）—— 那正是「三个瞬时
   桩恰好同序」的意外。改为「start 源序 + end 任意排列 + 每个调用恰一 start 一 end」。
   与 1. 同源：**凡钉到「等延迟下的顺序」的断言都是运行时运气，不是被测对象。**
3. **完成序夹具的第一版没牙（实测失败）**：原写法让 `faster` 在自己的执行体里
   `countDown` 再返回，而 end 是在**执行体返回之后**才发的 ⇒ `end:slower` 可以先于
   `end:faster`，与断言相反。改为**观察端放行**：sink 记下 `end:faster` 时放行闩锁，
   `slower` 在自己的执行体里等它 —— 源序发射 end 的实现于是**必然超时**。
   同时删掉 `batchRunsItsCallsSimultaneously` 里对 end 具体顺序的断言（同上，是运气），
   该用例只钉「两个调用同时在跑」。
4. **`abortedBatchStillEmitsInFlightEnds` 需要「先跑起来再中止」的编排**：第一版让 `faster`
   自由置位中止，于是与 `slower` 的**执行前中止检查**竞速（谁先谁后不定，中止若先到，
   `slower` 走的是「不执行、直接发 aborted 结果」那条路，用例虽仍绿但测的不是在飞语义）。
   改为第二个闩锁：`faster` 先等 `slower` 的执行体真的开跑再置位。
5. **`immediateEndStaysAheadOfConcurrentEnds` 未按计划落地（有意）**：pi 的形状里准备循环
   **跑完才提交**全部执行票，所以 immediate 的 end 结构上就在并发 end 之前，根本没有可交错的
   窗口 —— 用闩锁去钉它只是演戏。该不变量由 **S4** 承接（被拒调用的 end 在 ok1/ok2 之前），
   且现在有 `delayMs` 定序。§8.23.6 ③ 计划的 RE-3 因此改为动「在飞调用的 end」那条路。

**并发面审计的落地结果**（8.23.2 表逐项兑现）：`PiLaneSink.emit` 加锁与 `toolTerminate`
换并发映射是**唯一**两处必要改动；`AbortSignal`/`ToolRegistry`/`ToolContext`/`HookSystem`
注册表/`writeLine` 均原样可用。8.23.5-1 的 `JsonlFileTelemetry.currentStack` 仍是清点项
（约束「工具线程不得触碰遥测绑定」本轮成立：新增的并发路径只经 `PiLaneSink`，不经遥测绑定）。

**反向实验（先预测红名单再动刀，三红全中）：**

- RE-1 执行段改回串行 `entry.get()` ⇒ 恰 3 红（`batchRunsItsCallsSimultaneously` /
  `laterCallFinishingFirstEndsFirstButMessagesStaySourceOrder` /
  `harnessRunsTheRealToolBatchSimultaneously`，均为闩锁超时）+ **L5 恰 S13 一红**，
  其余 12 个剧本绿（串行 = 源序，pi 的录制除 S13 外都是源序）✓
- RE-2 摘 `PiLaneSink.emit` 的 `synchronized` ⇒ 恰 1 红
  `harnessSerializesConcurrentToolEventsForTheHost`，L5 **全绿**（帧序与这把锁无关）✓
  —— 8.23.6 ③ 曾把该哨兵诚实标注为「概率性」；实测（8 调用 × 20 更新，宿主侧计数
  并发进入）能稳定报红，且**带锁侧是确定性的**：锁在则永不重叠。
- RE-3 让在飞调用在中止后**不发自己的 end** ⇒ 恰 1 红 `abortedBatchStillEmitsInFlightEnds`，
  L5 全绿 ✓
- 三处补丁全部还原，还原后 `mvn -o clean verify` 绿（上列数字取自该轮）。

**注意（环境/工具，非代码问题）：**

- **`mvn surefire:test -pl <M>` 不带 `-am` 会解析到 `D:/repository` 的陈旧 pi-java 构件** ——
  本轮用它做「连跑 N 轮」的复现实验时，13 个剧本全部以
  `NoSuchMethodError: AssistantMessage.withIdentity(...)` 报错，一度被误读为「新实现全红」。
  连跑必须每次走 `-am … test`。与 §8.22.6 记的是同一个陷阱的**新面孔**（那边是假绿，这边是
  假红）。
- **`-Dtest=A+B` 静默匹配零个测试**（配 `failIfNoSpecifiedTests=false` 时表现为 exit 0），
  多类必须用逗号：`-Dtest=A,B`。本轮一次 RE 首跑因此把「没跑」读成了「全绿」。

**存量登记（越线，拆分列清点项、不进本包）**：`agent-core` 侧无新增越线文件
（`PiLoopTools` 305 行 / `PiLaneSink` 469 行）；`coding-agent` 的
`core/AgentSession.java` 988 行、`agent-core/AgentHarness.java` 502 行两处存量与 §8.22.6 同，
本包未触碰。8.23.5 的五条清点项（遥测线程归属、update 发射时机、宿主消费者线程契约、
内置工具线程安全、钩子在工具线程执行）**全部仍开放**。

#### 8.23.8 「谁先完成」的仲裁者 —— 等延迟下的顺序是运行时的产物（2026-09-16）

> 本节是 §8.23.7 第 1 条的展开。**它记录的是证据假设，不是实现**：B 的代码一行未改，
> 改的是「什么算 pi 的规则」。所有行号为落稿当时实测，pi 侧 = `pi-v0.85.1`
> `packages/agent/src/agent-loop.ts`。

**① 三类帧，三种顺序规则**

| 帧 | 顺序规则 | pi 出处 | pi-java 出处 | 等价 |
|---|---|---|---|---|
| `tool_execution_start` | **源序** —— 准备循环串行推进，跑完才提交执行票 | `:497-503` | `PiLoopTools.java:154-156` | ✅ |
| `tool_execution_end`（prepared） | **完成序** —— 由各自的延迟任务在**自己完成时**发 | `:520-541`（end 在 `:539`） | `PiLoopTools.java:169-176`（end 在 `:173-174`） | ⚠️ 规则同，**仲裁者不同**（见 ③） |
| `tool_execution_end`（immediate） | **源序** —— 准备循环内当场收尾 | `:506-517` | `PiLoopTools.java:158-167` | ✅ 构造性 |
| `tool_execution_update` | 完成序（同工具内 FIFO） | `:682-706` | `PiToolRunner.onUpdate` → `PiLaneSink.emit:275-292` | ⚠️ 发射**时机**差异另登记（§8.23.5-2） |
| `toolResult` 消息 | **源序** —— 收束后按 entries 顺序统一补发 | `:550-555` | `PiLoopTools.java:199-204` | ✅ |
| `terminate` | 非空 ∧ **every** | `:559`、`:589-591` | `allTerminate:290-300` | ✅（与顺序无关） |

**② 时序图 —— 两序在②与③之间分家**

```
pi executeToolCallsParallel (agent-loop.ts:487-561)
  ┌────────── ① 准备循环（串行·源序，:497-545）──────────┐
  │ start₁ → await prep₁ → immediate? 当场 end₁ → break?  │
  │ start₂ → await prep₂ → thunk₂ 入队 ──────────────────▶│
  └───────────────────────────────────────────────────────┘
  ┌────────── ② 执行段 await Promise.all(:547-549) ───────┐
  │ thunk₁() ─┐                                           │
  │ thunk₂() ─┴─▶ 各任务**完成时自己** await emit end      │──▶ end 帧 = 完成序
  └───────────────────────────────────────────────────────┘     （谁先完成谁先发）
  ┌────────── ③ 收束（:550-555）──────────────────────────┐
  │ for (finalized of orderedFinalizedCalls) 发 message   │──▶ 消息 = 源序
  └───────────────────────────────────────────────────────┘     （batches 全部 end 之后）
              ▲
    orderedFinalizedCalls 由 Promise.all 保序 ⇒ ③ 的源序是**结构性**的；
    ② 的完成序则取决于**运行时怎么仲裁「同时」**。
```

**③ 差距：同一个「完成」，两个仲裁者**

| | 谁在决定 end 的先后 |
|---|---|
| **pi** | JS 单线程事件循环。`:548` 的 `map(entry => entry())` **同步按源序启动**每个任务；延迟相同的任务落在**同一个队列的相邻位置**，队列 FIFO ⇒ 按登记先后（= 源序）排空。代码里**没有一行**在保证它。 |
| **pi-java** | 操作系统调度器。虚拟线程同样按源序**提交**（`PiLoopTools.java:192-193`），但恢复次序由调度决定 ⇒ 等延迟就是纯竞速。 |

⇒ **这是一处真实存在的不等价**，只在「延迟恰好相等」这一档出现：

```
旧 S12 第二批（两个 term1，等延迟）
   pi  : end t₃, end t₄          ← 恒定，队列 FIFO 决定
   java: end t₃, end t₄ / end t₄, end t₃   ← 竞速，调度器决定  → L5 8 轮 3 绿 5 红

新 S12 第二批（term1 20ms, term2 40ms；conformance/scripts/S12.json:9-10）
   pi  : end t₃, end t₄   ┐
   java: end t₃, end t₄   ┘ 两侧同规则、同结果 —— 由**声明出来的耗时**决定
   （录制：conformance/pi-out/S12.pi.jsonl:27-30）
```

**④ 反证：pi 自己就不按源序 —— S13**

`conformance/scripts/S13.json`：`slowish`（`delayMs:160`，带 2 条流式更新）**声明在前**，
`quickish`（无延迟）**声明在后**。pi 的录制（`conformance/pi-out/S13.pi.jsonl:11-20`）：

| 行 | 帧 | 说明 |
|---|---|---|
| 11 | `tool_execution_start` tc1 slowish | start **源序** |
| 12 | `tool_execution_start` tc2 quickish | |
| 13 | `tool_execution_end` **tc2 quickish** | end **反源序** ← 特征行 |
| 14-15 | `tool_execution_update` tc1 ×2 | slowish 的更新在它自己的 end 之前成批落地 |
| 16 | `tool_execution_end` tc1 slowish | |
| 17-20 | `message_start/end` **tc1 → tc2** | 消息**源序**，且全部 end 之后 |

⇒ pi 的真实规则 = **谁先完成谁先发 end**。「等延迟 ⇒ 源序」只是它的**退化情形**：
等延迟让「谁先完成」变成了「谁先启动」，而启动恰好是源序。S13 是这条规则的正例。

**⑤ 四种情形 × 影响**

| 情形 | pi | pi-java | 差异 | 影响 |
|---|---|---|---|---|
| 真实工具（bash/read/edit，耗时必不相等） | 完成序 = **耗时序** | 同 | 无 | **无** —— 有语义的正是这一档 |
| 真·零延迟（拒绝/未找到/参数非法/已中止） | immediate ⇒ 准备循环内**源序** | 同 | 无 | **无** —— 构造性一致（S4 形状） |
| **等延迟且真的执行过** | 源序（队列 FIFO） | **竞速** | **有** | 见下 |
| 中止 | 在飞跑完、各发自己的 end | 同（不打断） | 无 | **无** |

第三条的影响，逐层清点 —— 前三层都是「无」：

1. **宿主消费者**：`tool_execution_end` 是「该调用完成了」的通知；两个**同时**完成的工具谁先报，
   没有任何 pi 消费者依赖（`docs/28 §5.1` 的九处发射点与 `PiLaneSink` 全部只读单条事件）。
2. **转录与记录**：落盘顺序由 ③ 的**源序**决定，与 ② 无关 ⇒ 不受影响。
3. **可观测性**：跨度属性只依赖单次调用的值（`PiLaneSink.closeToolSpan:449-464`），无数值/顺序依赖。
4. **证据层**：**唯一有影响的一层**，且是 §8.23.7 第 1 条那件事 —— 严格逐帧把 pi 的
   调度副产品钉成了断言。修补在证据侧（剧本声明 `delayMs`），**不动比较器、不加放宽规则**。

**⑥ 为什么不反向对齐（把 Java 也做成「等延迟恒源序」）**

一旦把「等延迟」压回源序，**异构延迟下的完成序也会被压成源序** ⇒ S13 立刻红。
两条规则不可兼得，而 §5 第二条（有语义的那条）才是要保的。
即：**规则两侧字面相同（end 由任务自己发），差别只在「同时」落到哪个运行时** ——
复刻微任务队列不是可选项。

**⑦ 方法论结论（本节真正要留下的东西）**

> **同一份剧本下两侧输出相同 ≠ 两侧规则相同。** 旧证据是 `运行时 × 剧本` 的**联合产物**；
> §8.23 把它读成了「pi 的规则」。可观测性工程上这是一类固定陷阱：
> **凡断言「等延迟下的顺序」，测的都是调度器，不是被测对象。**
> 断言应钉**被声明出来的事实**（剧本里的 `delayMs`、准备循环的源序），
> 而不是运行时**恰好**给出的排列。
>
> 本条与 §8.23.7 第 2 条（`PiLoopTest.parallelBatchEmitsEveryStartBeforeAnyEnd:302`
> 的断言比名字宽）同源，两处均已按此收窄。

**落点索引**（复核用）：

| 内容 | 位置 |
|---|---|
| 真并发执行段 + `awaitOutcome` 解包 | `PiLoopTools.java:148-206`、`:218-234` |
| 事件唯一漏斗（串行化点） | `PiLaneSink.java:65`（`emitLock`）、`:275-292`（`emit`） |
| `terminate` 并发映射 | `PiLaneSink.java:74` |
| 并发夹具 6 例 | `ToolBatchConcurrencyTest.java:227/269/300/334/369/472` |
| 收窄后的 start 断言 | `PiLoopTest.java:302` |
| 定序剧本 | `conformance/scripts/S4.json`、`S12.json:9-10`、`S13.json:8-9` |
| pi 侧真相 | `conformance/pi-out/S12.pi.jsonl:27-30`、`S13.pi.jsonl:11-20` |
| pi 原实现 | `agent-loop.ts:497-503` / `:506-517` / `:520-541` / `:547-549` / `:550-555` / `:589-591` |

### 8.24 `tool_execution_update` 的发射时机（C）—— **已实施（2026-09-16，设计经用户审核通过；实施记录见 8.24.7）**

> 本节是 C 的准入设计文档。**未经审核认可前不写任何实施代码。**
> 来源是 §8.23.5-2 的登记项。pi 事实逐行读自 `packages/agent/src/agent-loop.ts:677-718`、
> `packages/agent/src/agent.ts:175/418/544-591`、`packages/coding-agent/src/core/agent-session.ts:643-667`；
> pi-java 现状读自 `PiToolRunner.execute`、`PiLoop.Sink`、`PiLaneSink.emit` 与 `conformance/`。

#### 8.24.1 pi 事实

**`executePreparedToolCall`（`agent-loop.ts:677-718`）全貌：**

```ts
const updateEvents: Promise<void>[] = [];          // :682
let acceptingUpdates = true;                        // :683
try {
    const result = await prepared.tool.execute(     // :686  ← 工具本体
        prepared.toolCall.id, prepared.args as never, signal,
        (partialResult) => {                        // :690  ← 工具回调，**同步**进入
            if (!acceptingUpdates) return;          // :691  闩落下后静默丢弃
            updateEvents.push(                      // :692
                Promise.resolve(
                    emit({ type: "tool_execution_update", ... }),   // :694  ← **内联调用**
                ),
            );
        },
    );
    acceptingUpdates = false;                       // :705
    await Promise.all(updateEvents);                // :706  ← 完成序保证
    return { result, isError: false };
} catch (error) {                                   // :708
    acceptingUpdates = false;                       // :709
    await Promise.all(updateEvents);                // :710  错误路径同样先收干净
    return { result: createErrorToolResult(...), isError: true };
} finally { acceptingUpdates = false; }             // :715-717
```

**两条语义必须分开，这是本节的核心更正：**

| # | 语义 | 依据 |
|---|---|---|
| ① | **发射点内联** —— `emit(update)` 在工具回调里**同步调用** ⇒ 帧进入消费者的顺序 = 工具调用 `onUpdate` 的顺序 | `:692-702`（`emit(...)` 是实参，`Promise.resolve` 只包返回值） |
| ② | **完成序保证** —— 该工具的全部 update 发射**完成之后**才发它的 end | `:706`（`await Promise.all`）先于 `:539`（`emitToolExecutionEnd`） |

> ⚠️ **§8.23.1 的措辞需要更正**：原文写「更新帧在该工具 end 之前成批落地，**不是内联发射**」。
> 按上面逐行读，**发射点是内联的**；被 `Promise.all` 推迟的是**完成**（await），不是发射。
> 「成批」描述的是 ②，不是 ①。更正随本节一并落到 §8.23.1。

**宿主侧的 `emit` 是异步的，②因此有实际内容**（这是「完成」在本产品的所指）：

| 环节 | 位置 | 事实 |
|---|---|---|
| 循环收到的 sink | `agent.ts:418` | `(event) => this.processEvents(event)` —— 箭头本身同步，**但返回 Promise** |
| 实际处理 | `agent.ts:544` | `private async processEvents(...)` |
| 首个 await | `agent.ts:588-590` | `for (const listener of this.listeners) await listener(event, signal)` |
| listener 契约 | `agent.ts:175` | `Promise<void> \| void` —— **允许异步** |
| 产品里的 listener | `agent-session.ts:402` → `:643` | `subscribe(this._handleAgentEvent)`，而它是 `async`，首个 await 在 `:667` `await this._emitExtensionEvent(event)` |

⇒ 产品语义：**工具不被消费者阻塞**（`emit` 的 Promise 只收集、不 await），工具一路往前跑；
阻塞被推迟到工具返回后的 `:706`。②因此是一条真保证：end 帧一定排在**已被消费者处理完**的
update 之后。

**conformance 路径的 sink 不同**：`conformance/pi/run.test.ts` 传的是
`async (event) => { stream.push(event); }` —— 函数体**同步**（`stream.push` 是同步入队），
所以在 L5 里 ① 与 ② 合成一条：帧按内联顺序入队。

#### 8.24.2 pi-java 现状与差距

`PiToolRunner.execute`（`PiToolRunner.java:150-169`）：

```java
executed = registry.execute(call.toolName(), call.toolCallId(), state.args(), signal,
    partial -> {                                     // :155  工具回调，同步进入
        if (acceptingUpdates.get()) {                // :157  pi :691 的闩
            emit.emit(new PiLoop.Event.ToolExecutionUpdate(...));   // :158  ← **内联调用**
        }
    }, toolContext);
```

三个面逐项对照：

| 面 | pi | pi-java | 判定 |
|---|---|---|---|
| ① 发射点 | 内联调用（`:692-702`） | 内联调用（`PiToolRunner.java:158`） | ✅ **一致** |
| ② 完成序保证 | `await Promise.all(updateEvents)`（`:706`） | **平凡成立** —— `PiLoop.Sink.emit` 返回 `void`（`PiLoop.java:81-83`），发射即完成 | ✅ **等价** |
| ③ 消费者阻塞语义 | 工具**不**被消费者阻塞（Promise 只收集）；阻塞推迟到 `:706` | 工具线程**被**消费者阻塞（`PiLaneSink.emit:275-292` 同步过锁） | ⚠️ **有差异**，但只影响墙钟与「消费者慢时谁被卡住」，**不改变帧序**（帧序由发射点决定，两侧相同） |

**②为何是「等价」而不是「缺失」**：`PiLoop.Sink.emit` 的契约是同步 `void`，事件链上
**不存在「发射未完成」这个状态** ⇒ pi 的完成序保证在这里没有可违反的余地。
它不是「没实现」，是「无事可做」。

**③为何不发生**：pi 的 ③ 只有在**宿主消费者是异步的**时候才有可观测内容；
pi-java 的 `Sink` 契约（`void`）在**类型层面**排除了异步消费者 —— TUI / RPC / web /
JSONL 遥测的消费者面全是同步调用。⇒ 结构性不可达。

#### 8.24.3 改动方案

**生产代码：零改动。** 差距判定为「无」（②③两项分别等价 / 不可达）。

**证据侧：新增一条能把「update 交错」照出来的剧本。** 理由 ——

> 现有 **13 个剧本一个也产生不了 update 交错**：两侧的桩都是「睡够 → 背靠背发 N 条 update
> → 返回」，`for` 循环里没有任何可插入点，别的调用的帧**不可能**落在同一工具的两条 update
> 之间（pi 侧 `stream.push` 同步、Java 侧 `emit` 同步）。也就是说 **update 与并发批次的
> 交错关系目前是 L5 的纯盲区**，不是「覆盖到了但没差异」。

剧本格式加一个**可选**字段：

| 字段 | 语义 | 缺省 | 对既有录制的影响 |
|---|---|---|---|
| `updateEveryMs` | 相邻两条 update 之间睡这么久；**首条之前仍先睡 `delayMs`** | `0`（背靠背） | **无** —— 既有 13 个剧本都不写它 ⇒ 帧与今天逐字节相同 |

新增 **S14「流式更新与并发批次的交错」**，用**声明出来的时序**钉住交错点（同 S13 的
`delayMs` 思路：凡交错必须是被声明的事实，不是运行时巧合）：

```
tools: [ streamer(delayMs 0, updates 3, updateEveryMs 150),   ← 声明在前
         blip   (delayMs 375, updates 0) ]                    ← 声明在后

预期帧序（pi 与 pi-java 都应如此）：
  start tc1 streamer
  start tc2 blip
  update tc1 partial 1        ← @150
  update tc1 partial 2        ← @300
  end    tc2 blip             ← @375   ★ B 的 end 落在 A 的两条 update 之间
  update tc1 partial 3        ← @450
  end    tc1 streamer         ← @450
  message tc1 streamer / message tc2 blip      ← 结果消息源序
```

★ 那一行是本节要买的东西：**它同时排除两种误读** ——「update 在该工具 end 前成批落地」
（则会看到 `u1 u2 u3 end tc1` 连成一块、`end tc2` 不在中间）与「end 早于所有 update」。

#### 8.24.4 需要审核的裁决点

1. **本包是否接受「零生产改动」的结论**（推荐接受）—— 即 C 的交付物是**证据 + 一处措辞更正**，
   不是代码。若不接受，请指出认为 ③（消费者阻塞语义）需要对齐的理由。
2. **是否扩剧本格式加 `updateEveryMs` 并新增 S14**（推荐：加）——
   纯可选字段、零影响既有录制（pi 侧重录后 12 个旧剧本应逐字节不变，本身就是一次回归校验）；
   代价是 PI 侧 runner 与 Java 侧 runner 各加约 5 行 + 重跑录制。
3. **③（pi 不被消费者阻塞 / pi-java 被阻塞）的处置**（推荐：登记为结构性差异，不修）——
   要「对齐」它就得把 `PiLoop.Sink.emit` 改成异步契约，那会波及整个宿主层，
   而收益只是墙钟。**若裁决为「要修」，它应是一个独立大包，不在 C 内。**

#### 8.24.5 未覆盖登记（诚实标注）

| 项 | 为何覆盖不到 |
|---|---|
| ② 完成序保证本身 | 需要一个**异步消费者**才能观察「发射未完成」的状态；`PiLoop.Sink` 契约是同步 `void` ⇒ 不可达 |
| ③ 消费者阻塞语义 | 同上；且它是墙钟性质，L5 的帧比对结构上看不见 |
| `acceptingUpdates` 闩在「工具返回后仍有迟到 update」时的行为 | pi 侧桩工具返回后不会再有 update；两侧都是 `finally` 落闩，形状相同但没有剧本能证明 |
| **串行路径**下的 update 时机 | 串行路径无并发，无所谓交错；`updateEveryMs` 在串行路径下同样生效（会拖长该调用） |

#### 8.24.6 测试计划

① **L5 差分**：新增 S14，重跑 pi 侧录制，`ConformanceTest` 的 `SCENARIOS` 增至 `S1…S14`，
   **14/14 严格逐帧**。同时校验 12 个旧剧本的 `.pi.jsonl` 在重录后**逐字节不变**
   （`git diff --stat conformance/pi-out/` 只应出现 S14）。
② **反证（先预测红名单再动刀）**，本包唯一能让结论变假的实验：
   - **RE-1（关键）**：把 Java 侧 `PiToolRunner` 的 update 改成「收集进列表、在该工具 end 前统一发」
     （＝**我原先误读的那个形状**）⇒ **S14 必须红**，其余 13 个剧本应全绿（它们没有交错，
     两种形状同形）。**若 S14 不变红，说明它没有牙、这次扩格式是白花钱** —— 必须在 §8.24.7
     如实记下来，并重新考虑证据形式。
   - RE-2：去掉 `acceptingUpdates` 闩 ⇒ 预期**全绿**（没有剧本在工具返回后还发 update）——
     这条是**预期不红**的对照，用来证明前一条的红不是偶然。
③ **E2E**：不新增。C 无生产改动，`ToolBatchConcurrencyTest.concurrentUpdatesSurviveTheEmitFunnel`
   （4 工具 × 3 update 的闩锁夹具）已经钉住 Java 侧的「每工具 update FIFO + 宿主不重入」。

#### 8.24.7 实施记录（2026-09-16）

**裁决**：两条裁决点均按推荐 —— ① 接受「生产代码零改动」的结案；② 扩剧本格式加
`updateEveryMs` 并新增 S14。

**提交**：`aa2ff00` docs（设计稿）→ 实施提交 `test(agent-core)`（`conformance/pi/run.test.ts`
加字段与间隔；`ConformanceScript.Tool` 加 `updateEveryMs`；`ConformanceRunner.executed`
加参数与「首条紧跟 delayMs、其后每条之间睡」；`ConformanceTest` 的 `SCENARIOS` 增至 S14；
新剧本 `conformance/scripts/S14.json` + 录制 `conformance/pi-out/S14.pi.jsonl`）。
**生产代码确认零改动** —— `git status` 里 `pi-java-agent-core/src/main/` 一个文件都没动。

**pi 侧录制**：14/14（`npx vitest --run --config vitest.conformance.config.ts`，2.44s）。
**旧的 13 个 `.pi.jsonl` 重录后逐字节不变**（`sha256sum -c` 13/13 OK）—— 这正是「缺省 0
＝背靠背」所要的证据：新字段对既有剧本**零影响**，且这一次重录本身构成一次回归校验。

**S14 的形状**（`conformance/pi-out/S14.pi.jsonl:11-17`，`streamer` 3 条 update 每 300ms、
`blip` 750ms 后结束）：

```
start tc1 streamer
start tc2 blip
update tc1 partial 1      ← @300
update tc1 partial 2      ← @600
end    tc2 blip           ← @750   ★ 别个调用的 end 落在本调用两条 update 之间
update tc1 partial 3      ← @900
end    tc1 streamer       ← @900
message tc1 / message tc2 ← 结果消息源序（turn_end.toolResults ["streamer","blip"]）
```

★ 那一行就是本包买到的判别力：它同时排除「该工具的 update 成批落在自己 end 之前」
（则会看到 `end blip` 排到 `partial 1` 之前）与「end 早于所有 update」两种误读。

**反向实验（先预测红名单再动刀）：**

- **RE-1（关键，命中）**：把 `ConformanceRunner.executed` 的 update 改成「收进列表、在
  返回前统一发」（＝ §8.23.1 初版误读的那个形状）⇒ **恰 `[14]` = S14 一红，其余 13 绿**，
  diff 正是 `java: end tc2 blip / partial 1 / partial 2` 对 `pi: partial 1 / partial 2 /
  end tc2 blip`。**S14 有牙**，扩格式不是白花钱。
- **RE-2（对照，预期不红，实测不红）**：去掉 `PiToolRunner` 的 `acceptingUpdates` 闩
  ⇒ **14/14 全绿**。这条是「预期不红」的对照组，用来证明 RE-1 的红不是偶然；
  它同时说明**该闩在 L5 里是盲区**（见下）。
- 两处补丁全部还原，`grep` 复查无残留，`PiToolRunner.java` 回到未改动状态。

**稳定性**：L5 **14/14 连跑 5 轮全绿**（S14 的时序靠 150ms 余量声明，不靠竞速）。
全 reactor `mvn -o clean verify` **BUILD SUCCESS**（14 模块全绿，11:41 min —— 本轮
`pi-java-tui` 的 `NoMode2027JLineBackendTest` 单模块耗时拉到 07:30，非失败）：
telemetry 26 / ai 336 / **agent-core 444**（较 B 的 443 **+1 = S14**）/ session-backend-sqlite 35 /
coding-agent 218 / tui 188(1 skip) / protocol 14 / server 2 / web 37 / evals 43(17 为 smoke skip)；
`checkstyle:check -pl pi-java-agent-core` exit 0。

**未覆盖（诚实标注，与 §8.24.5 一致）**：② 完成序保证与 ③ 消费者阻塞语义二者都要
**异步消费者**才能观察，而 `PiLoop.Sink.emit` 的契约是同步 `void` ⇒ 结构性不可达；
`acceptingUpdates` 闩由 RE-2 证实 L5 覆盖不到（没有剧本在工具返回后还发 update）。
8.23.5 的清单从本包起的余项：遥测线程归属、宿主消费者线程契约、内置工具线程安全、
钩子在工具线程执行。

---

### 8.25 遥测的「当前跨度」与它的线程归属（D）—— **已实施（2026-09-16，设计经用户审核通过；实施记录见 8.25.7）**

> **本包清 §8.23.5 第 1 条**（原文照抄）：
> 「`JsonlFileTelemetry.currentStack` 是全局 `ArrayDeque`，javadoc 却写 "on this thread"
> —— 文档与实现不符。当前唯一 `pushCurrent` 调用点（`PiLaneSink.beginRequest`）与
> `recordEvent` 调用点（`PayloadRecordingStreamFn`）**是否同线程未实测**。清点项：线程归属
> 实测 + 是否改 ThreadLocal（含 `recordEvent` 的语义）。」
>
> 本包回答三问：① 今天到底同不同线程；② 「全局栈 + javadoc 写 on this thread」算不算缺陷；
> ③ 改不改、怎么改。
> **取证后结论比登记时重**：默认路径确实同线程（面①），但**有两条今天就跑得到的路径**
> 让跨线程读写同一个栈成真（面③：运行中 `/compact`、并发 prompt），后果不是「读不到」
> 而是**读到别人的跨度**（静默错配）。另外捞到一条大得多的东西（面④：pi 的跨度词汇与
> adapter 契约），**本包不顺手做它**。
>
> **结案（细节见 §8.25.7）**：① 默认路径**同线程** —— 已实测（也装了绊线，因为它是调用形状
> 的产物而非结构保证）；② **算缺陷** —— 跨线程读会静默错配，读写全在文件锁之外，另有
> 一条 `startSpan` 只推不弹；③ 已按**方案 A** 改为 `ThreadLocal` + 补对称 pop。
> 面④ 那两层按裁决另立包，**方案 C 并入其中作地基**（理由见 §8.25.3）。

#### 8.25.1 pi 事实：pi 的遥测层**没有「当前跨度」这个构造**

pi 有独立的遥测包 `packages/telemetry`（`@earendil-works/pi-telemetry`），契约就是 357 行
里的两个接口：

```ts
export interface TelemetryContext {
	startSpan<T>(options: SpanOptions, callback: (span: TelemetrySpan) => T | Promise<T>): Promise<T>;
}

export interface TelemetrySpan extends TelemetryContext {
	addEvent(name: string, attributes?: SpanAttributes): void;
	setAttributes(attributes: SpanAttributes): void;
	setStatus(status: SpanStatus): void;
}
```

| # | 结构性事实 | 出处 |
|---|---|---|
| ① | **接口只有一个方法**。没有 `currentSpan` / `pushCurrent` / `peek` —— 跨度只能经 `startSpan(options, callback)` 拿到，且**以回调参数**交到手上 | `packages/telemetry/src/index.ts:11-20` |
| ② | **事件挂在跨度对象上**（`span.addEvent(name, attrs)`），不挂在任何环境态上 | `:15` |
| ③ | **父级是显式参数**：请求侧的父上下文随请求选项走 —— `ProviderRequestOptions.telemetryContext`，注释原文 *"Explicit parent context for telemetry produced by this logical request."*；`SimpleStreamOptions extends StreamOptions extends ProviderRequestOptions` | `packages/ai/src/types.ts:126-127` / `:179` / `:313` |

⇒ pi 对「这条 event / 这个 span 属于谁」的答案是**把它写成参数**。**它没有一个可以被错配的
环境态**，所以「线程归属」在 pi 侧不是一个「处理得好不好」的问题 —— **是没有这个构造**。
任何线程拿到 span 对象就能往里写事件，天然并发安全，也天然不需要线程局部存储。

pi 还把这套契约固化成一份**可移植的一致性套件**（`createTelemetryAdapterConformance`，
任何 adapter 实现都得跑）：

| 组 | 用例 | 断言要点 | pi-java 对照 |
|---|---|---|---|
| callback lifecycle | admits once synchronously and preserves the result | 回调**同步**入场一次；返回值原样传出；span `status=ok` 且已 settled | 部分 ✓（同步入场与返回，无 promise 形态） |
| callback lifecycle | preserves synchronous and asynchronous rejection values | 抛出的**同一个值**原样穿透（同步 / 异步 / `undefined` / 不可读对象四路）；状态 error | 部分 ✓（异常同一对象原样穿透；无异步形态） |
| status | uses last explicit status without automatic overwrite | 显式 `ok` 之后即使抛异常**仍为 ok** —— 自动置错不得覆盖显式值 | **✗ 无 `setStatus`** |
| recording | merges attributes and records ordered events | 属性合并（后写胜）；事件**有序**记在 span 上 | 属性 ✓ / **事件 ✗**（走环境态） |
| recording | ignores failed attribute calls atomically | 一次失败的属性写入**不得部分生效** | n/a（逐个 `addAttribute`，无批量写） |
| recording | makes calls after settlement inert | 回调返回后对 span 的任何调用都是 no-op（**含开子 span：不记录**） | **✗ 部分**（`close()` 幂等、迟到 `addAttribute` 不落盘；但迟到 `startSpan` **会**开子 span 并落盘） |
| parentage | records nested and concurrent child relationships | 根 `parentId=null`；并发子各自挂父；结束序 `second-child < first-child < parent` | ✓（`parentSpanId` + 文件行序） |
| passivity | suppresses unreadable telemetry payload failures | 选项不可读时仍**入场一次并返回结果**，只是什么都不记 | n/a（Java 无「不可读对象」惯用法） |
| passivity | ignores failed status calls atomically | 失败的 `setStatus` 被吞掉；同回调的 rejection 仍把状态置 error | n/a（同上） |

**pi 的跨度词汇**则是一份 typed schema（`packages/agent/src/harness/telemetry.ts`，635 行）
里声明出来的 12 个名字，逐个带 `description` / `parents` / `startAttributes` / `endAttributes`
/ `events` / `status.errorWhen`：

| pi | pi-java |
|---|---|
| `pi.ai.request` | `llm.request` |
| `pi.harness.run` | `harness.run` |
| `pi.harness.tool` | `tool.execute` |
| `pi.harness.compaction` | `compaction.apply` |
| `pi.harness.turn` / `pi.harness.step` / `pi.harness.checkpoint` / `pi.harness.navigation` / `pi.harness.hook` / `pi.harness.sleep` / `pi.harness.event_handler` / `pi.session.write` | —— 无 |

**4/4 名称全不相同，且 pi-java 一个 `pi.` 前缀都没有**；属性名同样是两套（pi 用
`pi.lane.name` / `pi.operation.outcome` / `pi.step.attempt` …，pi-java 用 `lane` / `outcome` /
`attempt` …）。这一条超出本包范围，登记为 §8.25.5-1。

#### 8.25.2 pi-java 现状与差距

**面① 线程归属 —— 默认路径同线程，但「同线程」不是保证**

先看默认路径（单次运行、无重叠）。`pushCurrent` / `popCurrent` 在生产代码里各**只有一个**
调用点，`recordEvent` 只有两处，四处全部落在**同一个栈帧**里：

```
本次运行的虚拟线程 ─┐
                  │  PiLoopRunner.stream()  的一次调用（:190 起）
  transformContext│  PiLaneEngine.beforeRequest(:303) → PiLaneSink.beginRequest(:162)
     :193         │                                    ├─ pushCurrent(llmSpan)        ← 触摸①
                  │                                    └─ llmSpan = parent.openSpan("llm.request") :167
  streamFn.stream │  PayloadRecordingStreamFn.stream:94 → recordEvent(llm.payload.request)   ← 触摸②
     :214         │
  while(iter)     │  ResponseRecordingIterator.next:162 → recordEvent(llm.payload.response)  ← 触摸③
     :220-225     │
  emit(MessageEnd)│  PiLaneSink.emit(:275) → onMessageEnd(:308) → endRequest(:184)
                  │                                    └─ popCurrent(llmSpan)        ← 触摸④
                  └─
  ── 工具批次在此之后（endRequest 已 pop，栈为空）⇒ B 的并发只发生在栈已空时 ──
```

三处按行号可查的证据：`PiLoopRunner:193`（transformContext 钩子，就在 `:214` 取流的
**同一个方法体里**）、`PiLoopRunner:214`（`config.streamFn().stream(...)`）、
`PiLoopRunner:220-225`（同线程拉迭代器 —— `recordEvent` 长在 `next()` 里，由消费者线程调用）。
再加上 `AgentHarness` 只持**一个** `LaneState`（`AgentHarness.java:60`；多车道容器
`LaneRegistry` 已删，见 `:52-58`）⇒ 一个 harness 一条车道。

**但「这条线程」是哪条线程，值得先钉清楚**：生产里 **不是**调用方线程。
`AgentSession.processPrompt`（`:545`）是 `Thread.startVirtualThread(() -> SessionRunner.drive(...))`
—— **每个 prompt 一条新虚拟线程**，调用方立刻返回（TUI 主线程继续渲染、RPC stdin 线程继续读）：

| 宿主入口 | 调用 `processPrompt` 的线程 | harness 跑在 |
|---|---|---|
| TUI（`PiTuiApp:353-360` / `InteractiveMode:47-50`） | **主/渲染线程** | 每次一条新虚拟线程（事件经 `TuiEventDispatcher` 回投主线程） |
| RPC（`RpcDispatcher:279-285`） | stdin 读线程（`RpcMode:44-47`） | 同上 |
| Web WS（`WebDispatcher:134-138`） | WebSocket 回调线程 | 同上 |
| print/json（`PrintMode:35-38/:57-60`） | 主线程 | 同上 |

⇒ **默认路径下全局 `ArrayDeque` 的行为与 ThreadLocal 逐位相同**（只有一条线程碰它），但
这条线程**每次运行都换一条**，且宿主侧随时有别的线程活着 —— 于是「同线程」是**当前调用形状
的产物，不是结构保证**。面③的三条路径今天就打破了它。

**面② 文档与实现**：`TelemetryContext.pushCurrent` 的 javadoc 写「as the current span for
`recordEvent` **on this thread**」（`TelemetryContext.java:72-73`），而实现是
`JsonlFileTelemetry.currentStack` —— 一个**恒被共享**的 `ArrayDeque`（`:57`）。
**javadoc 在效果上没说错，错的是它没有被兑现**：没有任何类型或实现让「读的那个线程＝写的那个
线程」成立。它是一句愿望，不是一条保证。

同一处还有第二层文档不符：`docs/18` §5.2 写「**不依赖 ThreadLocal**」，紧跟着说
push/pop 是「供 event 行在**批量 worker 线程**绑定当前栈顶 span」（§7.3 再写一遍「批量 worker
线程在 lambda 首尾 push/pop parent span」）—— 而**共享栈恰恰做不到「worker 线程各自绑定」**：
两个 worker 同时 push，`peek()` 拿到的是别人的 span。那份设计文档自己给的用途，实现没有提供。
（另：§7.3 描述的 `ToolExecutionPipeline.executeStages` 形状已随第 9 步作废 —— 新循环里
`tool.execute` 跨度的开/关都在**引擎线程**上（`noteToolStart` / `emitToolRecords`），
今天没有任何 worker 线程 push。）

**面③ 两条今天就可达的破坏路径（本包真正的发现）**

`currentStack` 的 `push/peek/pop` **完全在 `lock` 之外**——`lock`（`:52`）只护文件 IO。
默认路径无害，但下面两条路把「第二条线程」真的带进来了：

1. **手动压缩与运行重叠**（确定性可达，不需要竞速）：TUI 的 `/compact`
   （`core/slash/builtin/MiscCommands:124-127`，经 `CommandUtil.simple` 在 `execute` 里
   **同步**跑 `body.run(...)` ⇒ 跑在**分派该命令的线程**上，TUI 即主/渲染线程）与 RPC 的
   `compact`（`RpcDispatcher:158`，跑在 **stdin 读线程**）
   都走 `RunLifecycle.compact:237-239` → `new CompactionExecutor(ctx).compact(...)`，
   而**那里没有 `lane.isRunning()` 门**（只查 transcript 是否为空、末条是否已是压缩，
   `CompactionExecutor:84-89`）。摘要生成器拿到的又是**同一个** `recordingStreamFn`
   （`AgentSession.java:366-367`）⇒ 它的 `recordEvent` 读的是**共享栈顶**，而此刻栈顶正是
   那条**正在运行的请求**的 `llm.request` span。**压缩的 payload 行被挂到了别的线程的
   请求跨度上**；同时 `push`/`pop`（`:168`/`:181`）与这次 `peek` 之间无同步 ⇒ 数据竞争
   （`ArrayDeque` 扩容搬运时可以把内部状态写坏，不只是错配）。
2. **并发 prompt**：`AgentSession.processPrompt`（`:529-547`）**没有 in-flight 门**，
   无条件起虚拟线程；上层的 `PiLaneEngine:90-92` / `RunLifecycle:147-150` 那个
   「lane not idle」检查是 check-then-act，而 `LaneState.activeRun`（`:86`）**既非 volatile
   也无锁** ⇒ 两条线程可以都认为车道空闲。两条 run 线程于是共用同一个 `currentStack`。
   **诚实标注：没有找到确实这么调的生产调用方**（TUI/RPC/Web 都是 fire-and-forget，且一次
   只发一条），所以这条是「**无门可挡**」而不是「已观测到」。
3. **`fork` 共享同一实例**：`AgentHarness.fork()`（`:150-152`）返回 `new AgentHarness(config)`
   —— **同一个 `HarnessConfig`**，于是同一份 `telemetry()` 与同一个 `currentStack`；
   `AgentSession.forkCopy:765` / `forkFromEntry:792` / `forkInMemory:828` 都走它。
   父会话与 fork 会话的 run 一旦重叠，又是跨线程共享栈（是否重叠取决于调用方，代码不挡）。
   **一处顺带发现**：`fork()` 的 javadoc（`:147-148`）列了共享的三个不可变依赖
   （streamFn / toolRegistry / toolContext），并断言「隔离度只增不减」—— 它**没提 telemetry**，
   而 telemetry 恰恰是这几个依赖里唯一**带可变状态**（`currentStack` + `writer`）的那个。

三条的性质是同一个：**全局栈让「谁绑的」与「谁读的」可以解耦，而解耦的方向恰好是错的**——
不是「读不到」（那还看得见），而是**读到别人的**（静默错误）。这正是 A1 要买的东西
（见 §8.25.3）。

**面④ 与 pi 的两层差距**（取证时发现，**超出本包**）：见 §8.25.1 的两张表 ——
① **adapter 契约**：pi 的 9 条一致性用例里，pi-java 只有 2 条（parentage、属性合并）大致成立，
`setStatus` 与 span 级事件**结构上不存在**；② **跨度词汇**：pi 声明 12 个 `pi.*` 跨度名，
pi-java 有 4 个且无一同名。

**面⑤ 一个已经处理对了的点（记录，免得被面③误伤）**：`JsonlFileTelemetry.with(...)`
返回的是**新实例**（`:130`）⇒ 新文件、新锁、**新栈**。若 harness 与 payload wrapper 各拿一份，
`pushCurrent` 就对 `recordEvent` 不可见，事件会丢 `traceId/spanId`。实测**没有**这个问题：
全仓只有一个 exporter 构造点（`AgentSession.java:265`），同一实例同时交给 harness 与 wrapper，
`:263-264` 的注释正是为此写的。

**面⑥ 自动压缩的 payload 行本就丢归属（今天就已经如此）**：自动压缩跑在 run 线程
（`PiLaneEngine:328` 阈值 / `:101` 运行前 / `:184` 运行后），触发时 `endRequest` 早已
`popCurrent` ⇒ 摘要那次请求的 `llm.payload.request` / `llm.payload.response` 两行
**没有 `traceId`/`spanId`**。它不属于面③的「错配」，但同属一处设计缺口：**这条请求没有
自己的跨度可绑** —— `CompactionExecutor:238-239` 开的 `compaction.apply` 走的是 `openSpan`
（不进栈），`LlmSummaryGenerator` 也没有任何 push。登记见 §8.25.5-8。

#### 8.25.3 改动方案

**方案 A（推荐）—— 让实现兑现 javadoc，且修两处真错**

| # | 改动 | 理由 | 生产可观察性 |
|---|---|---|---|
| A1 | `currentStack` → `ThreadLocal<ArrayDeque<JsonlSpan>>` | 「on this thread」由**构造**保证；顺带消除面③的数据竞争；单线程下与今天**逐位相同** | 默认路径**无变化**；面③-1（手动压缩重叠）从「挂到**别人**的 span」变为「**不挂** span」—— 把静默错误换成**可检测的缺失** |
| A2 | `startSpan` 补对称的 `pop`（`finally` 里） | 它已经有 `pushCurrent(span)`（`:98`）却从不 `popCurrent` ⇒ ① 栈无界增长；② 回调返回后的 `recordEvent` 会绑到一个**已结束**的 span（错配） | 无（生产路径只用 `openSpan`，`startSpan` 仅测试与公开 API 可达） |
| A3 | 更正 `docs/18` §5.2 / §7.3 的措辞 | 「不依赖 ThreadLocal」与「worker 线程 push/pop」两句在 A1 之后要重写：不依赖 ThreadLocal 说的是 **parent 关系**（对，且不变），push/pop 说的是 **event 绑定**（A1 之后才是真的） | —— |
| A4 | 新增钉住线程归属的测试（§8.25.6） | 把「未实测」变成**实测且被测试守住**的证据 | —— |

A1+A2 合计约 10 行生产改动，**不改任何对外签名**（`pushCurrent`/`popCurrent`/`recordEvent`
的名字、参数、语义都不动）。

**方案 B —— 只改文档**：把 javadoc 改成「当前线程或任意线程绑定的栈顶」，把 `docs/18` 的
`worker 线程` 措辞删掉。零代码风险，但**留下数据竞争**，且等于承认「worker 线程绑定」这条
设计意图作废。

**方案 C（pi 形状，已裁：另立包，不属本包）—— 删掉环境态**：`recordEvent` 改成挂 span 对象
（`span.addEvent`），父级显式传（对齐 pi 的两层：`ProviderRequestOptions.telemetryContext`
`packages/ai/src/types.ts:126-127` 与 `withTelemetryContext`/`TELEMETRY_CONTEXT_KEY`
`packages/agent/src/harness/context.ts:27-36`），`pushCurrent`/`popCurrent` 整个删除。
**这是唯一能让 §8.25.1 的 9 条用例里有更多条成立的形状**，也是 §8.25.2 面④ 两层差距的地基。

**裁决（2026-09-16）：C 并入 §8.25.5-1/-2 那个包，作它的地基；D 只做 A。** 三点理由：

1. **C 单独做，对判据的收益是 0**。判据是「分支所有功能都和 pi 表现一样」，而
   `llm.payload.request/response` 这套 event 行是 **pi-java 独有构造** —— pi 侧没有对应物，
   无从对齐。C 换来的是**内部构造**更好（无环境态可错配、事件天然归属），
   不是可观察表现更像 pi。它的可观察收益全部落在 §8.25.5-1/-2（跨度词汇、adapter 契约）上，
   而那两项已裁为另立包。
2. **C 的接线是真正的工作量，不是删代码**。父级要跨 `StreamFn.stream(model, context, options)`
   从 `PiLaneSink.beginRequest`（agent-core）走到 `PayloadRecordingStreamFn`（coding-agent），
   而后者看不到 sink。pi-java 的对应物是 `StreamOptions`（3 字段 record，**5 个构造点**）
   与 `Context`（**13 个 `new Context(` 构造点**）；`StreamOptions` 的 javadoc 明说它对齐
   pi 的 `SimpleStreamOptions` **+ `StreamOptions`**，而 `telemetryContext` 在 pi 属于
   **更下一层**的 `ProviderRequestOptions` ⇒ 加到 `StreamOptions` 上等于把 pi 的两层并成一层，
   挂 `Context` 更贴但波及 13 个构造点。**这是一个要单独裁决的形状问题**，不适合塞进 D。
3. **A 是 C 的回归基线**（不是技术前提）。C 要删的 `pushCurrent`/`popCurrent`，今天唯一的生产
   使用者是 `PiLaneSink:173/:193`。A 之后「绑定 == 同线程」被 ①-a/①-b 钉成不变量，
   C 的 diff 于是应当**逐字节不变**，而这个「不变」正由 A 的测试守住；不做 A 直接做 C，
   删完之后没有任何既存断言兜底。
   ⚠️ **注意「学神不学形」**：pi 的 span event 是随 span 落地的结构化小属性，
   而 pi-java 的 payload event 是**整份请求/响应负载的独立行**。照搬 `span.addEvent` 会把整个
   消息列表塞进 span 行、`--trace-payloads` 的文件结构与离线消费方式剧变（见 §8.25.5-3）。
   C 的正确形态多半是 `recordEvent(span, name, payload)`（显式父 + 仍是独立行），不是 `addEvent`。

**唯一支持「C 进 D」的论据**（记录在案，不予采纳）：§8.25.4-4 裁了「不加那两道门」，
而 C 是那两道门**结构性替代** —— 不加门也不会错。但它的验收目标是「构造更好」而非
「表现更像 pi」，与判据不同源，故仍随 -1/-2 一起走。

**若 §8.25.5-1/-2 长期不做，C 也不要单独做**：脱离那两项，它是纯结构改动，
没有一个可观察目标来验收，等于无法证伪。

#### 8.25.4 需要审核的裁决点 —— **已裁（2026-09-16）**

1. **本包是否采纳方案 A** ⇒ **采纳方案 A**（A1+A2+A3+A4）。（方案 B 落选：它留下数据竞争
   与面③的静默错配；且 A 的成本只有约 10 行。）
2. **A2 要不要做** ⇒ **做**。`startSpan` 自己发了 `pushCurrent` 却不 `popCurrent`，
   属明确的自相矛盾（栈无界增长 + 回调返回后的 `recordEvent` 绑到已结束的 span）。
3. **§8.25.2 面④ 的两层差距（adapter 契约 9 条 / 跨度词汇 12 个）要不要立包** ⇒
   **D 之后另立包**，且**方案 C 并入该包作地基**（理由见 §8.25.3 方案 C 的裁决块）。
   D 不碰这两层，也不碰 C。
4. **面③-1/③-2 那两道「门」（并发 prompt、运行中手动压缩）要不要在本包一起加** ⇒
   **不加，登记为 §8.25.5-6/-7**。
   **理由是一条重要区分**：A1 改的是**归属**（同一件事，只是记对了地方），加门改的是**行为**
   （今天允许的调用会变成拒绝）。后者必须先与 pi 对照才能定 —— pi 的 `/compact` 在运行中到底
   允不允许、pi 的会话有没有「一条 prompt 在飞」的门，本包**没有取证**，不能顺手加。
   A1 之后这两条路径的最坏后果从「静默错配」降为「事件行缺 `traceId`」，不再需要抢在同一包里修。
   （「C 是不加门的结构性替代」这条论据已记录、但随 -3 一起推迟；见 §8.25.3。）

#### 8.25.5 登记（本包不修，另立包）

| # | 项 | 现状 | 代价素描 |
|---|---|---|---|
| 1 | **跨度词汇与属性词汇不对齐 pi 的 typed schema**（**并含方案 C 的构造改造作地基**，裁决见 §8.25.3） | pi 12 个 `pi.*` 跨度，pi-java 4 个、零同名 | 大：先做 C（删环境态、父级显式传、事件归属 span），再对齐 12 个跨度的 start/end 属性 + 事件 + `errorWhen` 条件；其中 8 个 pi-java 今天没有对应发射点 |
| 2 | **pi 的 adapter 契约（9 条）pi-java 只大致满足 2 条**（**同包，依赖 1 的 C**） | 无 `setStatus`、无 span 级事件、settle 后开子 span 不是 no-op | 中：要么补 API（`setStatus` / `addEvent`），要么显式声明「pi-java 的 JsonlFileTelemetry 不是 pi adapter 的实现」并写明差异 |
| 3 | **`recordEvent` 的环境态语义本身** | 无 span 绑定 ⇒ 事件行无 `traceId`/`spanId`（与 pi「事件必有宿主 span」相反） | 与方案 C 同一件事。⚠️ **不能照搬 `span.addEvent`**：pi 的 event 是随 span 落地的小属性，pi-java 的是整份负载的独立行（见 §8.25.3 裁决块第 3 条） |
| 4 | **`JsonlFileTelemetry.with(...)` 返回新实例**（新文件/新锁/新栈） | 当前唯一构造点用对了；但这是**约定**不是**类型**保证。**A1 之后新实例各自持有独立的 ThreadLocal**（仍是新文件/新锁） | 小：加断言/注释，或让 `with` 共享栈与文件 |
| 5 | ~~**`docs/18` §7.3 描述的 worker 线程 push/pop 已无实现**~~ | **已由 A3 结案**（2026-09-16）：§5.2/§7.3 两处重写，并补上「默认路径同线程是调用形状的产物」这句 | —— |
| 6 | **并发 prompt 没有门**：`AgentSession.processPrompt`（`:529-547`）无条件起虚拟线程；`LaneState.activeRun`（`:86`）非 volatile 无锁；`PiLaneEngine:90-92` / `RunLifecycle:147-150` 是 check-then-act | 「无门可挡」（未见这么调的生产调用方） | 中：要一条**会话级**串行保证；且须先对照 pi（pi 有没有同等的门） |
| 7 | **运行中手动 `/compact` 没有门**：`RunLifecycle.compact:237-239` 直通 `CompactionExecutor`，后者只查 transcript 空与末条是否压缩（`:84-89`） | 可与运行重叠，走同一 `streamFn`/telemetry | 中：同理，先取证 pi 的 `/compact` 在运行中是否允许；**这是行为改动，不是归属改动** |
| 8 | **摘要请求没有自己的跨度**（面⑥） | 自动压缩的 payload 行 `traceId` 为 null | 与方案 C / 本表第 1、3 项同族 |
| 9 | **`JsonlSpan.startSpan` 根本不碰当前栈**（实施 A2 时发现） | 同一个 `startSpan`，两个实现行为不同：`JsonlFileTelemetry.startSpan` push（A2 后也 pop），`JsonlSpan.startSpan`（`:338-349`）只开子 span、**既不 push 也不 pop**。而接口 `startSpan` 的 javadoc（`TelemetryContext:16-25`）**一个字都没提「绑定为当前跨度」** ⇒ 「`startSpan` 会绑定」是一处**未文档化的局部行为**，三个实现里只有一个有它（`Otel`/`Noop` 都没有） | 小：A2 选择**保留 push、补齐 pop**（仓内唯一依赖者是一条既有单测；生产路径只用 `openSpan` + `PiLaneSink` 的显式 push）。反方向（**删掉 push**，让所有实现都不碰栈）同样自洽 —— 该行为既无文档也无生产消费者。选哪个都行，**但它该被写进 javadoc**，否则下一个写遥测装饰器的人会踩空 |
| 10 | **接口的 `openSpan` 默认实现不可用**（同一处发现） | `TelemetryContext:32-39` 的 default `openSpan` 在**自己的回调里**就把 span 关掉，返回一个**已结束**的 span。真实实现（`JsonlFileTelemetry:137`、`OtelTelemetryContext:96`）**各自覆写**才没出问题 | 小：写装饰器时**必须显式转发 `openSpan`**，否则 harness 拿到的全是已结束的 span（本包的测试装饰器就踩过，已在注释里钉住）。要么把 default 改成抛 `UnsupportedOperationException`，要么在 javadoc 里写明「必须覆写」 |

#### 8.25.6 测试计划

① **实测（本包核心证据，两条）**
   - ①-a **单元级**：`JsonlFileTelemetryTest` 加「A 线程 `pushCurrent`、B 线程 `recordEvent`」
     夹具，钉住「B 的事件行**不带** A 的 `spanId`」；并配一条正向对照「A **自己**的事件行
     带 A 的 `spanId`」—— 否则「`spanId` 永远为 null」也能让前一条通过。
   - ①-b **E2E**：用一个记录线程号的遥测装饰器包住真实 `JsonlFileTelemetry`，跑一次真
     `AgentHarness` 运行（含请求/响应两处真 `recordEvent`），断言 `pushCurrent` /
     `recordEvent` / `popCurrent` 三处的 `Thread.currentThread().threadId()` **全部相等**。
     落点 `agent-core` 侧，自带一个与 `PayloadRecordingStreamFn` 同形的最小 wrapper
     （后者是 `coding-agent` 的包内类，`agent-core` 看不到它）。
② **RE-1（关键，单元级）**：把 A1 还原成**共享** `ArrayDeque` ⇒ ①-a 的第一条断言
   **必须红**（B 的行带上了 A 的 `spanId`）。若不变红，说明夹具没有牙、必须重做 ——
   照实记进实施记录。
③ **RE-2（关键，可达性级）**：把面③-1 做成夹具 —— `prompt` 跑在**独立线程**上（照抄生产
   形状 `AgentSession:545`），工具体闩住不放；测试线程随即调 `harness.compact(...)`。预测：
   - A1 生效 ⇒ 压缩那次的 payload 行**没有 `traceId`**（自己的跨度没绑，也不去借别人的）；
   - 还原成共享栈 ⇒ 同一行**带上了那条在飞请求的 `spanId`**（面③-1 的错配，直接可观测）。
   这条的意义是把「单元夹具里的错配」证成「今天就跑得到」，**是本包最重要的一条证据**。
   成本较高（需要闩锁夹具 + 独立线程 + 会真调一次摘要生成器）；若实测发现摘要生成器的桩
   难以在测试里驱动，退化为只用 ①-a + ②，并**如实标注 ③ 未做**。
④ **A2 的针**：`startSpan` 返回之后再 `recordEvent` ⇒ 断言**不带**那个已结束 span 的 id
   （今天会带上 —— 这正是 A2 修的那半个错）。
⑤ **回归面**：`JsonlFileTelemetryTest`、`HarnessTelemetrySpansTest`、`HarnessToolExecutionSpansTest`、
   `PayloadRecordingStreamFnTest` 应**全绿** —— 单线程下 A1 与今天逐位相同，
   A2 只在「回调返回后再 `recordEvent`」时可观察。
⑥ **全 reactor**：`mvn -o clean verify` + `checkstyle:check -pl pi-java-telemetry,pi-java-agent-core`。

#### 8.25.7 实施记录（2026-09-16）

**提交**（分支 `agent-core-pi-loop`）：

| # | 提交 | 内容 |
|---|---|---|
| 1 | `fc09c2c` | 裁决落地（§8.25.3 C 的推迟理由 / §8.25.4 四问 / §8.25.5-5 结案）+ **A3**（`docs/18` §5.2、§7.3 重写） |
| 2 | `cf55aba` | **A1 + A2**（`JsonlFileTelemetry`）+ 单元级 A4（三条用例） |
| 3 | `40504b5` | agent-core 侧 A4（①-b、③） |

**A1/A2 的实际形状**：`currentStack` 由共享 `ArrayDeque` 改为
`ThreadLocal<Deque<JsonlSpan>>`（`ThreadLocal.withInitial(ArrayDeque::new)`，不调
`remove()` —— 空 deque 随线程消亡，生产每 prompt 一条新虚拟线程）；`startSpan` 的
`finally` 里补 `popCurrent(span)`，**在 `close()` 之前**（先解绑再结束）。`popCurrent`
保留 `peek() == span` 守卫，因此内层解绑不会弹掉外层。**对外签名零改动**，
`Noop`/`Otel`/`JsonlSpan` 三个实现无需跟随。

**反向实验（先预测、后动刀）：**

| RE | 动刀 | 预测 | 实测 |
|---|---|---|---|
| RE-1 | `currentStack` 还原成共享 deque | 恰 ①-a 第一条断言红，其余 15 条绿 | ✅ 命中。`recordEventOnlySeesSpansBoundOnItsOwnThread:283`，`Expecting value to be false but was true` —— B 线程的事件行带上了 A 的 `spanId`。**「其余 15 条绿」本身就是证据**：单线程下共享栈与 ThreadLocal 不可区分，与「A1 不改变默认路径输出」相符 |
| RE-A2 | 摘掉 `startSpan` 的 `popCurrent` | 两条解绑用例红 | ✅ 命中（`:300`、`:327`），其余 14 条绿 |
| RE-2 | `currentStack` 还原成共享 deque | ③ 红：摘要请求的 payload 行**带上在飞请求的 `spanId`** | ✅ 命中。`HarnessTelemetryThreadAttributionTest:353`，`Expecting value to be false but was true`。**并用探针把「借到的是哪条」也钉实**：断言「摘要行的 `spanId` == 最后一条 `llm.request` span_start 的 `spanId`」—— 探针**通过**（失败点后移到紧随其后的那条），即借到的**逐字就是**在飞那条请求的跨度，与 §8.25.6 ③ 的预测逐字相符 |

**一处「第一版没牙」的夹具（照实记）**：`innerSpanUnbindingLeavesOuterSpanBound`
第一版从 **span 对象**进（`outer.startSpan(...)`），而 `JsonlSpan.startSpan`
（`:338-349`）**根本不碰当前栈** ⇒ 该用例在任何实现下都通过，测不到
`popCurrent` 的配对守卫。RE-A2 的第一轮（只有 1 红）把它照了出来；改为两层都从
`JsonlFileTelemetry.startSpan` 进之后，RE-A2 才变成 2 红。**这条与 §8.25.5-9 是同一个发现的两面。**

**判据**：

- `pi-java-telemetry` **29/29** 绿（`JsonlFileTelemetryTest` 13 → **16**）、checkstyle 0 违规；
- `pi-java-agent-core` **446/446** 绿（444 + 2）、checkstyle 0 违规；新测试**连跑 5 轮绿**；
- 全 reactor `mvn -o clean verify` **BUILD SUCCESS**（14 模块，全部 checkstyle 0 违规）。

**⚠️ 一次未复现的全 reactor flake（与 D 无关，登记备查）**：第一次 `clean verify` 有一红
`PiLoopTest.parallelBatchEmitsEveryStartBeforeAnyEnd:338`（`agent-core` 446 里的 1），
断言是 `frames.indexOf(ends.getFirst()) > frames.indexOf(starts.getLast())` ——
**「所有 start 早于所有 end」**。它不碰遥测（该用例用 `Recorder`），且：

- 单独跑该测试类 **10/10 绿**；整模块套件 **3/3 绿**；第二次 `clean verify` 绿；
- 但它是**真竞态**：准备循环按调用逐个发 start、并把执行票**在循环内**交出去
  （`docs/29 §4.1`：`:520-541` 的延迟任务），所以 task₁ 的 end 可以落在 start₂/start₃ 之间。
  `docs/29 §4.1` 已明说「所有 start 早于任何 end **不是**该模式的保证」，
  §8.23.7 第 2 条也已把这条用例的断言放宽为「start 源序 + end 任意排列」——
  **唯独第三条断言（全 start 早于全 end）留了下来**，它就是残留的那半个运气断言。
- **不在本包修**（遥测无关，且其属于 §8.23 的收口面）。修法一行：删掉 `:338`，
  或按 §8.23.7 的口径改成「每个调用恰一 start 一 end」（后者已由
  `containsExactly`/`containsExactlyInAnyOrder` 覆盖）。**请裁决是否顺手修**。
  参照：§8.23.7 已记过同类先例（`web` 的 `PiWebServerAuthTest` 对负载敏感，三红一绿）。

**新登记**：§8.25.5-9（`JsonlSpan.startSpan` 不碰当前栈 / 接口 javadoc 未写明绑定语义）、
-10（接口 `openSpan` 的默认实现返回**已结束**的 span，装饰器必须显式转发）。

---

## 9. 与既有文档的关系

| 文档 | 关系 |
|---|---|
| `docs/27` | 对齐规则与基线，本文的前提 |
| `docs/28` | 驱动层换成 `PiLoop` 的决策；本文是它的续篇（宿主层） |
| `docs/29` | L5 差分报告，本文 §7 验收第 1 条的依据 |
| `docs/30` | 折叠链退休；`records` 降级为旁路审计由它确立 |
| `docs/03` | 类级设计；§2.1-§2.3 已按本文的结论重写（§8.13），§2 以本文为准、本文以 pi 源码为准 |
