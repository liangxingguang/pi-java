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
| 3 | 无 `tool.execute` 跨度、无 tool.executions/tool.errors | `ToolExecutionPipeline` 独占这些，`PiToolRunner` 没有 | **修**：跨度在 `PiLaneSink.noteToolStart` 开、结果消息处关；`batchSize` 在收尾时补（~~pi 保证全部 start 早于任何 end~~ —— 该依据**已撤回**，`docs/29 §4.1`；真实依据与「顺序路径上这个属性语义有误」见 `docs/31 §8.26.5-11`） |
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

**遗留**（⚠️ **本行已过期：B 项于 2026-09-16 由 §8.23 实施闭环**，`delayMs` 剧本解决了此处「差分侧怎么验证非确定完成序」；下行保留为历史记录）：B 项（延迟任务真并发 = pi 的 `Promise.all`）仍开放 —— 当前串行执行在
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

**遗留不变**（⚠️ B 项**已于 2026-09-16 由 §8.23 实施闭环**）：~~B 项（真并发 = pi 的 `Promise.all`）~~ 与 `QueueMode.All` 宿主层仍待用户；
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
四守卫；`isContextOverflow`/`isRecoverableLength` 定义尚未定位）—— 后已落地（§8.21，判据定位于 `packages/ai/src/utils/overflow.ts`）；B 项（**⚠️ 已于 2026-09-16 由 §8.23 实施闭环，下行该项过期**）
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
| 1 | ~~**跨度词汇与属性词汇不对齐 pi 的 typed schema**（**并含方案 C 的构造改造作地基**，裁决见 §8.25.3）~~ | **已结案（2026-09-17，docs/31 §8.28）：结案为「不做」。** 取证翻转了前提 —— pi v0.85.1 声明的 12 个 `pi.*` 跨度里 **11 个没有任何发射点**，唯一有发射点的 `pi.harness.hook` 覆盖的是 pi-java 里对应概念的另一半；两份 schema **没有 `events:` 声明**、生产**从不安装真 adapter**（默认 `NOOP_TELEMETRY_CONTEXT`）⇒「对齐 12 个跨度的属性/事件/`errorWhen`」**没有对齐对象**。按判据（行为，不是文档），改名换到的是文档对齐。**方案 C（删环境态）随同一并结案**（§8.28.7-1） | —— |
| 2 | ~~**pi 的 adapter 契约（9 条）pi-java 只大致满足 2 条**（**同包，依赖 1 的 C**）~~ | **已结案（2026-09-17，docs/31 §8.28）：只修真实差异，其余显式声明。** 9 条逐条分诊（§8.28.4）：**第 6 条是真差且零 API 变更可修** —— 父已结算后开的子跨度曾照常落盘，已按 pi 语义降级为 noop（`35c4758`）；第 1、2 条的异步半边与第 4 条的「事件」是**结构性 / 语义不可对齐**（`Function` vs `Promise`、`recordEvent` 整份负载 vs span 上小属性），已写进 javadoc（`c3eef82`）；第 3、5、9 条依赖 `setStatus`，**不补**（无观察者 + 半对齐更难解释）。⚠️ 口径：**不是「pi-java 的 adapter 不满足 pi 的契约」，而是「pi-java 的 adapter 比 pi 多，且 pi 那 9 条只对着一个测试用实现」** | —— |
| 3 | **`recordEvent` 的环境态语义本身** | 无 span 绑定 ⇒ 事件行无 `traceId`/`spanId`（与 pi「事件必有宿主 span」相反） | 与方案 C 同一件事。⚠️ **不能照搬 `span.addEvent`**：pi 的 event 是随 span 落地的小属性，pi-java 的是整份负载的独立行（见 §8.25.3 裁决块第 3 条） |
| 4 | **`JsonlFileTelemetry.with(...)` 返回新实例**（新文件/新锁/新栈） | 当前唯一构造点用对了；但这是**约定**不是**类型**保证。**A1 之后新实例各自持有独立的 ThreadLocal**（仍是新文件/新锁） | 小：加断言/注释，或让 `with` 共享栈与文件 |
| 5 | ~~**`docs/18` §7.3 描述的 worker 线程 push/pop 已无实现**~~ | **已由 A3 结案**（2026-09-16）：§5.2/§7.3 两处重写，并补上「默认路径同线程是调用形状的产物」这句 | —— |
| 6 | **并发 prompt 没有门**：`AgentSession.processPrompt`（`:529-547`）无条件起虚拟线程；`LaneState.activeRun`（`:86`）非 volatile 无锁；`PiLaneEngine:90-92` / `RunLifecycle:147-150` 是 check-then-act | 「无门可挡」（未见这么调的生产调用方） | 中：要一条**会话级**串行保证；且须先对照 pi（pi 有没有同等的门） |
| 7 | **运行中手动 `/compact` 没有门**：`RunLifecycle.compact:237-239` 直通 `CompactionExecutor`，后者只查 transcript 空与末条是否压缩（`:84-89`） | 可与运行重叠，走同一 `streamFn`/telemetry | 中：同理，先取证 pi 的 `/compact` 在运行中是否允许；**这是行为改动，不是归属改动** |
| 8 | ~~**摘要请求没有自己的跨度**（面⑥）~~ | **已结案（2026-09-17，docs/31 §8.29）：修。** 新跨度 `compaction.summary`（父 = `compaction.apply`），摘要生成期间绑定为当前跨度 ⇒ 请求行与响应行都归属到它，收尾还带 `summaryChars` 与 token；形状取 B（**不**复用 `llm.request` 名，免得改变既有按名聚合的口径） | —— |
| 9 | **`JsonlSpan.startSpan` 根本不碰当前栈**（实施 A2 时发现） | 同一个 `startSpan`，两个实现行为不同：`JsonlFileTelemetry.startSpan` push（A2 后也 pop），`JsonlSpan.startSpan`（`:338-349`）只开子 span、**既不 push 也不 pop**。而接口 `startSpan` 的 javadoc（`TelemetryContext:16-25`）**一个字都没提「绑定为当前跨度」** ⇒ 「`startSpan` 会绑定」是一处**未文档化的局部行为**，三个实现里只有一个有它（`Otel`/`Noop` 都没有） | 小：A2 选择**保留 push、补齐 pop**（仓内唯一依赖者是一条既有单测；生产路径只用 `openSpan` + `PiLaneSink` 的显式 push）。反方向（**删掉 push**，让所有实现都不碰栈）同样自洽 —— 该行为既无文档也无生产消费者。选哪个都行，**但它该被写进 javadoc**，否则下一个写遥测装饰器的人会踩空 |
| 10 | **接口的 `openSpan` 默认实现不可用**（同一处发现） | `TelemetryContext:32-39` 的 default `openSpan` 在**自己的回调里**就把 span 关掉，返回一个**已结束**的 span。真实实现（`JsonlFileTelemetry:137`、`OtelTelemetryContext:96`）**各自覆写**才没出问题 | 小：写装饰器时**必须显式转发 `openSpan`**，否则 harness 拿到的全是已结束的 span（本包的测试装饰器就踩过，已在注释里钉住）。要么把 default 改成抛 `UnsupportedOperationException`，要么在 javadoc 里写明「必须覆写」 |

> **本表续见 §8.26.5**（编号自 11 起）：那三条是 §8.26（工具批次与结构化并发）调查中登记的，
> 与本节同族 —— 都出在「并发下被工具线程触碰的宿主共享状态」或「遗留的工具跨度族」上。

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
- ~~但它是**真竞态**：准备循环按调用逐个发 start、并把执行票**在循环内**交出去
  （`docs/29 §4.1`：`:520-541` 的延迟任务），所以 task₁ 的 end 可以落在 start₂/start₃ 之间。~~
  ⇒ **该诊断已证伪（2026-09-17）**：Java 侧 `PiLoopTools.executeParallel:190-194` 是
  **准备循环跑完才 `submit`**（循环里只登记 thunk），故本桩形状下「全 start 早于全 end」
  是**结构保证**，不是运气。真正的根因是**测试桩自己不是线程安全的** —— `Recorder.frames`
  用一个普通 `ArrayList` 收帧，而包 B 之后 `end` 帧由 **worker 线程**发出：实测 300 轮
  坏 5 轮。诊断、修复与量化见 **§8.27.7**。
- **已在 §8.27.7 修**：改的是**桩**（三个测试 sink 换 `CopyOnWriteArrayList`），
  **不是删 `:338`** —— 删掉它就丢掉了「本桩形状下全 start 早于全 end」这条真实覆盖。
  `docs/29 §4.1` 那条撤回仍然有效：它说的是「pi 的并行分支**不保证**该性质」（immediate
  调用会在准备循环里发 end，S4 即如此），与本桩形状不矛盾。
  参照：§8.23.7 已记过同类先例（`web` 的 `PiWebServerAuthTest` 对负载敏感，三红一绿）。

**新登记**：§8.25.5-9（`JsonlSpan.startSpan` 不碰当前栈 / 接口 javadoc 未写明绑定语义）、
-10（接口 `openSpan` 的默认实现返回**已结束**的 span，装饰器必须显式转发）。

---

### 8.26 工具批次与结构化并发（裁决）

> **触发**：用户提问「适合使用结构并发来实现工具调用么」。§8.23.3 记过一条非目标
> ——「不使用预览 API（结构化并发在 JDK 25 仍属预览），`ExecutorService` 是正式面」
> （`:1733`）——但只给了「预览」**一条**理由，且无出处；也没人问过「预览转正之后呢」。
> 本节补齐依据，结论是：**预览只是三条理由里最弱的一条**。调查另发现三个真实缺陷，
> 登记在 §8.26.5（编号续 §8.25.5，自 11 起）。

#### 8.26.1 作为**原则**：批次**已经是**结构化的

问题不是"要不要结构化"，而是"要不要把 `ExecutorService` 换成 JDK 的预览 API"。

| 保证 | 现有实现 | 证据 |
|---|---|---|
| 派生任务不逃出作用域 | 作用域 = 批次方法体：`try (var workers = Executors.newVirtualThreadPerTaskExecutor())` | `PiLoopTools.java:190` |
| 退出时**所有** worker 确已终止 | `ExecutorService.close()` 的契约就是"waits until all tasks have completed execution and the executor has terminated"；被中断时先 `shutdownNow()` 再**继续等** | 本机 JDK 源码 `java.base/java/util/concurrent/ExecutorService.java:368-407` |
| 结果确定性收拢 | `entries` 位置即源序 → `futures` 同序 → `outcomes` 按序回填；消息与 `terminate` 均源序 | `PiLoopTools.java:153-205` |
| 退出**不**取消在飞任务（pi 的要求） | 故意不 `shutdownNow` | `PiLoopTools.java:186-188`；`ToolBatchConcurrencyTest.abortedBatchStillEmitsInFlightEnds` |
| 宿主事件仍互斥 | 唯一漏斗 `emitLock` | `PiLaneSink.java:274-292` |

即：`close()` 等全部 worker 结束这条，**恰好就是** `StructuredTaskScope` 卖的那条保证 ——
现有实现已经用它自己的方式拿到了。副作用是它的作用域**比 pi 的更强**（pi 的 `Promise.all`
在缺陷 B 那条路径上会提前拒绝，见 §8.26.5-12）。

#### 8.26.2 作为 **JDK API**：不适合。三条**互相独立**的否决理由

**① 默认策略会中断兄弟工具线程，与 pi 正面冲突**（判据层面的否决；**与预览无关，转正后依然成立**）。
本机 JDK 25.0.4 源码（`lib/src.zip` → `java.base/java/util/concurrent/`，下同）：

- 无参 `open()` 的 `@implSpec` 明写等价于 `Joiner.awaitAllSuccessfulOrThrow()`
  （`StructuredTaskScope.java:905-908`）；后者的 javadoc 原文：
  *"The `Joiner` **cancels** the scope and causes `join` to throw if any subtask fails"*
  （`:613-619`）。
- **取消不是抽象的**：`onComplete` → `cancel()`（`StructuredTaskScopeImpl.java:187-191`），
  `cancel()` → `interruptAll()`（`:147-153`），`interruptAll()` 逐个 **`t.interrupt()`**
  （`:134-140`）⇒ 首个失败会**打断正在跑的工具线程**。
- 这与 pi 的语义**正面相反**：pi 的中止是**协作式**的，在飞的调用跑到返回、自己发
  `tool_execution_end`，兄弟**不**被打断（`agent-loop.ts:521-529`；§8.23.4 裁决④）。
  pi-java 为此**故意不 `shutdownNow`**（`PiLoopTools.java:186-188`），并由
  `ToolBatchConcurrencyTest.abortedBatchStillEmitsInFlightEnds` 钉住。
  ⇒ 采用默认策略等于**主动引入一个 pi 明确拒绝的行为**。
- 要关掉它必须显式换 `Joiner.awaitAll()`（`:632-634`，*"**does not cancel the scope if a
  subtask fails**"*）——花代价买一个**必须立刻停用**的能力。
- 另有一处不可忽略：`join()` 把异常包成 `FailedException`（`StructuredTaskScopeImpl.java:258`
  `throw new FailedException(e)`），而 `awaitOutcome`（`PiLoopTools.java:218-234`）刻意
  **解包原样重抛**（保 pi「批次级失败、异常向上冒」的形状）⇒ 宿主侧 `catch (SpecificException)`
  会失效。

**② 构建代价：`--enable-preview` 是全树、且污染产物**。
`javac --enable-preview` "要求 -source 或 --release 一起使用"，`java --enable-preview`
"允许应用程序使用**此版本**中的预览功能"（本机 JDK `-help` 原文）⇒ 开关**绑定发行版**；
编译出的 class 带 preview 标记，只能在**完全相同的 JDK** 上加载。而 pi-java 的产物是
**库 + CLI + fat jar + native 二进制**，消费者拿不走。现状：根 `pom.xml:33` `java.release=25`、
`:88` `<release>`，全树**无任何** `--enable-preview`。

**③ native 与 API 稳定性**。
`-Pnative` 产 `pi-java` 二进制（`pi-java-dist/pom.xml:88-113`），而
`docs/10-phase5-native-design.md:539` 的核实前提正是"无 `--enable-preview`"；native-image
不支持 preview 特性。时限那一半：五度预览（JEP 505 为第五次），JDK 25 刚把
`ShutdownOnFailure`/`ShutdownOnSuccess` 换成 `Joiner` + 静态 `open()`，转正拟在 JDK 28
⇒ 今天写的明天要重写。

#### 8.26.3 为什么不「学形」

判据是**行为**（「分支所有功能都和 pi 表现一样」），不是技术名词对齐。pi 的工具批次在
**结构上**已经等价于一个作用域（**批次 = 作用域、退出即 join、结果确定收拢**），
差别只在**仲裁者**（pi：JS 微任务队列 / Java：调度器）与**能否取消**（pi：不能）。
换成 `StructuredTaskScope` 能得到的只有：取消（**必须关掉**）、批超时（**pi 没有**）、
`ScopedValue` 继承（**仓内无一个 `ScopedValue` 使用点，没有可继承的东西**）、
JSON 线程转储里的结构化层级（与判据无关）。四条没有一条是行为收益。
同 §8.25.3 的 ⚠️ 口径、也同 §8.23.8 ⑥「**复刻微任务队列不是可选项**」。

#### 8.26.4 唯一有吸引力的东西：批超时 —— 恰恰不能要

`Configuration.withTimeout(Duration)`（超时即取消作用域、`join()` 抛 `TimeoutException`，
`StructuredTaskScope.java:787`/`:819`）是现有实现**确实没有**的能力——但
**pi 的 `Promise.all` 没有批超时**。加了就是行为不一致，按判据不能加。
（现有实现也刻意不设：`PiLoopTools.java:186-188`。若要加，必须作为**新能力**提出，
不能包装成对齐。）

#### 8.26.5 登记（续 §8.25.5，编号自 11 起）

| # | 项 | 症状 | 现状/可达性 | 处置 |
|---|---|---|---|---|
| 11 | **顺序路径的 `batchSize` 语义错** | `batchSize` 在 `closeToolSpan`（`PiLaneSink.java:456`）按**当时**的 `batchCallIds.size()` 写，而 `batchCallIds` 只在 `noteToolStart`（`:217`）追加、在助手消息落定时清空（`:326`），读取由**结果消息**驱动（`:358` → `emitToolRecords:418` → `closeToolSpan:430`）。并行路径相位③在整批后回填 ⇒ 恒为 `N` ✅；**顺序路径逐调用成组**（`PiLoopTools.java:96-113`）⇒ 第 k 个调用收尾时只有 k 个成员登记过，`batchSize` = **1,2,3,…,N** ❌ | **生产可达**，不是角落：`BashTool`/`EditTool`/`WriteTool` 都是 `ExecutionMode.Sequential`，一个这样的调用即让**整批降级**（`useSequentialPath:76-88`，对应 S10）。pi 自己的录制 `conformance/pi-out/S10.pi.jsonl:11-18` 就是 `… → end tc1 → message_start tc1 → **message_end tc1** → start tc2 → …`。**未覆盖**：L5 差分是**帧级**的、不含遥测属性；`HarnessToolExecutionSpansTest.java:146` 只有单调用批（两种读法同值）。同根的另一处：`toolIndex`（`:222`）**两条路径都正确**（列表单调增长） | **进 §8.25.5-1/-2 的 pi 跨度词汇包**（用户裁决）。理由：`batchSize` 挂在**遗留**的 `tool.execute` 跨度族上，而 pi 的工具跨度属性只有 `pi.tool.name`/`call_id`/`replay`/`recovery`/`is_error`（`packages/agent/src/harness/telemetry.ts:422-448`）——**根本没有 batchSize**；那包要重建整个族，届时自然定案（很可能是删）。**本包不改行为、不补测试** |
| 12 | **批内 join 是源序，异常选择与 pi 不同**（且作用域更强） | `PiLoopTools.java:195-197` 按**源序**逐个 `future.get()`；pi 用 `await Promise.all(...)`（`agent-loop.ts:547-549`），**最早抛出者**（时间序）获胜。另有两处差异：**(a) 作用域更强** —— 抛异常后 `finally` 走 `close()`，它**等全部 worker 终止**才让异常向上冒，因此 pi-java 的失败路径耗时是 `max(全部工具)`，pi 是「首个失败」；pi 那边**迟到的 `tool_execution_end` 可以落在 `agent_end` 之后**，pi-java 结构上不可能（`ExecutorService.java:368-407`）。**(b) 触发条件两侧不同构** —— pi 的 `catch` 无类型，工具体抛什么都转成结果，thunk 只可能因**宿主 sink 失败**而 reject；pi-java 的 `catch (Exception)` 使 `Error`（OOM/`StackOverflowError`/`AssertionError`）与 sink 失败才逃得出去 | 可达性窄（"同批 ≥2 条抛 `Error`"），且**不影响帧序**（相位③恒源序）、不影响 `terminate`（序无关）。**但 (b) 有一条更重的下游**：`SessionRunner` 两处都是 `catch (Exception)`，`Error` 两条都不接 ⇒ `statusFuture`/`entriesFuture` **永不完成**、不发 `AgentEnd`/`AgentSettled`、虚拟线程带未捕获 `Error` 死去 —— 宿主**永久挂起**（车道却已 idle）。这是**宿主层缺口**，经同一个门到达 | **登记，不改**（用户裁决）：可达性窄；按完成序重抛会引入 pi-java 从未有过的跨线程顺序语义。**备注**：这个角落若要贴近 pi，**恰恰**是 `StructuredTaskScope` 能帮上的一点（它按完成序选异常）——但为它引入预览不值得，真要改是 3 行、不依赖任何预览 API。宿主那半边（`Error` 不接）与 pi 的 `handleRunFailure`（把异常**压成文本**、合成 assistant 消息、promise **resolve**）是另一处更大差距，另立包 |
| 13 | **`lane.records` 被工具线程写入**（数据竞争，**包 B 引入**） | `LaneState.records` 是**普通 `ArrayList`**（`LaneState.java:79`），却有一个**工具线程**写者：`PiToolRunner.execute:172` 在**worker 线程**上调 `hooks.fireAfterTool(...)`（`PiLoopTools.java:172` 的 worker lambda 里）→ `HookSystem.fireAfterTool:200` 的 `catch` → `recordHookError:334` `lane.records.add(...)`。同时宿主在**别的线程**读它：`SnapshotService.java:75` `lane.records.stream()`、`:81` `List.copyOf(lane.records)`（由 HTTP 请求线程经 `WebDispatcher` 到达） | **两条今天可达的路径**：①同一并行批里**两个**工具的 `after_tool` 钩子都抛异常 ⇒ 两条虚拟线程并发 `add`（丢记录；扩容期还会 `ArrayIndexOutOfBoundsException`）；②批次在飞时来一个 web/RPC 快照请求 ⇒ `ConcurrentModificationException`（或复制期 `AIOOBE`）。**包 B 之前不可能**：那时工具在引擎线程上顺序跑。§8.23.2 的审计只覆盖了「工具线程不得触碰**遥测绑定**」，漏了宿主记录表。`before_tool` 走 `prepare`（引擎线程），安全 | **已裁决并落地（2026-09-17，用户裁「先修复」）**：换成 **`CopyOnWriteArrayList`**（`LaneState.java:103`）。「走漏斗」那条**不采用** —— 它只覆盖工具线程这一条通路，而宿主线程的 `abort:303` 与运行中压缩同样在写这张表；且它要把 pi **没有**的「钩子错误」塞进与 pi 事件 **1:1** 的 `PiLoop.Event` 端口。**RE 已验齿**（回退成 `ArrayList` ⇒ **5 跑 2 红**，`ConcurrentModificationException` 冒在 `SnapshotService:75`）⇒ **修复后 8 跑 8 绿**。实施记录、影响面与**未覆盖的三条相邻问题**（可见性 / 运行中替换 transcript / 压缩 attempt 读-改-写）见 **§8.27** |

#### 8.26.6 未来触发条件

`StructuredTaskScope` **在 JDK 28 转正**（`@PreviewFeature` 摘掉、API 冻结）**且**
`-Pnative` 退役（或 native-image 支持 preview）之后，才值得重新评估。届时：
唯一可用策略是 `Joiner.awaitAll()`（默认策略**必定**冲突，见 §8.26.2 ①），
绕不开 `FailedException` 的包装，且仍须先证明**无行为差异**。在此之前，
`ToolExecution.java:7-9` 的那句"`StructuredTaskScope` … is avoided"**仍然正确**，
其依据即本节。

---

### 8.27 `lane.records` 的跨线程访问（§8.26.5-13）—— **已实施（2026-09-17）**

> 判据仍是**行为**（「分支所有功能都和 pi 表现一样」）。pi 是单线程 + 逐个 `await emit`，
> 工具体里不可能有第二条线程碰宿主状态；Java 的包 B（§8.23）引入真并发之后，
> 「谁在哪个线程碰宿主状态」第一次需要按**共享对象**逐个过一遍。

#### 8.27.1 事实：这张审计表有一个工具线程写者、以及多个宿主读者

| 角色 | 位置 | 线程 |
|---|---|---|
| **写** | `HookSystem.recordHookError:334`（`lane.records.add`）← `fireAfterTool:200` 的 `catch` ← `PiToolRunner.execute:172` | **worker**（`PiLoopTools:169-176` 的并行任务体）。只有 `after_tool` 走这条；`before_tool` 在 `prepare`（引擎线程）⇒ 安全 |
| 写 | `PiLaneSink:397/:404/:429/:432`（步 / 用量 / 工具记录） | 引擎线程（在 `emit` 的 `emitLock` 内） |
| 写 | `RunLifecycle:69/:137/:182`、`QueueManager:77/:109`、`HarnessUtils:119`、`CompactionExecutor:327-337` | 引擎线程；`CompactionExecutor` 也可能在**宿主线程**（运行中的 `/compact`） |
| 写 | `AgentHarness.abort:303` / `close:488`（`AbortRequested`） | **宿主线程** —— 公开 API `abort(String):296` 正是「`lane.isRunning()` 时也照写」，即批次在飞时照样写 |
| 读 | `SnapshotService:75`（`stream()`）、`:81`（`List.copyOf`） | **任意线程**：宿主的 `snapshot()`（`WebDispatcher:236`、`RpcDispatcher:365`、TUI 轮询）与引擎线程的 `publishState` |
| 读 | `CompactionExecutor:348`（遍历数 attempt）、`PiLaneEngine:443`（`copyOf`） | 引擎线程 / 宿主线程 |

它此前是**普通 `ArrayList`**（`LaneState.java:79`；改后字段在 `:103`）。

#### 8.27.2 为什么 §8.23.2 的审计漏了它

§8.23.2 那次审计的对象是**「工具线程不得触碰遥测绑定」**（结论：`toolTerminate` 要并发映射；
`toolStartNanos`/`toolAllowed` 只在引擎线程 ⇒ 保持 `HashMap`）。那条结论**按对象成立**，
但**不能外推**成「车道状态里没有别的工具线程写者」——`lane.records` 根本不在那次审计的对象里。
**教训**：并发审计的单位是**「共享对象 × 全部线程」**，不是「某条已知的破坏路径」。
（同形的问题在 §8.25 的 D 包又出现一次：那次只盯 `currentStack`，漏了压缩与在飞请求的重叠。）

#### 8.27.3 裁决：换容器（`CopyOnWriteArrayList`），**不**走漏斗

§8.26.5-13 登记的两个方向，取前者：

| | 换容器（**采用**） | worker 侧改走 `PiLaneSink.emit` 漏斗（**不采用**） |
|---|---|---|
| 改动面 | `LaneState` 一行 + 字段 javadoc | `HookSystem` 的错误记账要变成事件、`PiToolRunner` 的错误路径要重排 |
| 覆盖范围 | **全部**跨线程写点，含宿主线程的 `abort`（`:303`）与运行中压缩（两者今天就在写这张表） | 只覆盖「工具线程的钩子错误」一条，其余仍要另想办法 |
| 与 pi 端口的关系 | 无影响 | 要把 pi **没有**的「钩子错误」事件塞进 `PiLoop.Event` —— 那是与 pi 事件 **1:1** 的端口，塞进去就破坏了它的对齐意义（同 §8.26.3 的口径） |
| 代价 | 每次 `add` 复制一次数组 | 要让 worker 去抢宿主的闩锁 |

**为什么接受复制代价**：这张表是**只追加的旁路审计**（`docs/28` 选项 C 已把恢复改读 entry、
`records` 降级为审计），且引擎在**每条 entry 落定时已经整体复制它一次**
（`PiLaneSink.onMessageEnd:369` → `publishState` → `SnapshotService:81` 的 `List.copyOf`）
⇒ COW 的复制与既有量级同阶，不改复杂度。

#### 8.27.4 本次**未**覆盖的相邻问题（如实登记，不许外推）

| 项 | 为什么不在本包 | 归处 |
|---|---|---|
| `transcript` / `messages` / `partial` / `runId` 等字段的**可见性** —— 宿主线程 `snapshot()` 与引擎线程追加之间没有 happens-before | 这些字段的写者**唯一**（引擎线程，加 `synchronized (lane)` 的 reset/restore，都在无运行态），**不产生结构破坏**；宿主拿到的是副本，最坏是「少最后一条」 | 只登记，不改 |
| `transcript` 在**运行中被整体替换**（压缩的 `clear()`+`addAll()`、reset、restore）与运行期追加并发 | 根因不是容器类型，而是**运行中 `/compact` 没有 `isRunning` 门**（`RunLifecycle.compact:237-239`） | §8.25.5-6/-7（同根因） |
| `CompactionExecutor.compactionAttempt:348-354` 的**读-改-写**（数一遍再写 attempt）不原子 | COW 只让集合安全，不让「读-改-写」原子；两条压缩并发时仍可能同号。根因同上 | §8.25.5-6/-7 |

**本包只修「会被结构破坏的那个集合」**：丢记录 / 抛 CME 是**崩溃**，读到过期值是**陈旧**，
两者不同级；把后者顺手做掉，等于夹带修改 §8.25 已登记的设计门。

#### 8.27.5 夹具与 RE

`LaneRecordsConcurrencyTest.concurrentHookErrorsAllLandInTheRecordLog`（agent-core）：
八个并行工具的 `after_tool` 钩子先在闩锁上互等（**八个都进场**才由读者线程统一放行），
放行后同时抛出 ⇒ 八条虚拟线程的 `records.add` 挤进同一窗口，而读者线程同时在
`harness.snapshot("default")`。三条断言：钩子都到了（并发真的发生，顺序批次等不到闩锁）、
读者**没抛**、`UsageCause.HOOK` 记录**恰好 8 条**（一条不丢）。

| | 结果 |
|---|---|
| **RE（把 `records` 回退成 `ArrayList`）** | **5 跑 2 红** —— `ConcurrentModificationException` 冒在 `SnapshotService:75` 的 `stream()`（栈顶 `ArrayList$ArrayListSpliterator.tryAdvance:1695`），正是登记里写的「批次在飞时来一个快照请求」那条路径 |
| **修复后** | **8 跑 8 绿** |

#### 8.27.6 影响面

零行为改动（并发正确性本身除外）：`records` 的声明类型仍是 `List<LaneRecord>`，
全部调用点（`add`/`clear`/`addAll`/`stream`/`forEach`/`copyOf`/下标）对 COW 语义相同。
`RunLifecycle.restoreRecords:298-299` 的 `clear()`+`addAll()` 在 COW 上**不是原子**，
但它跑在恢复边界（车道未运行、`synchronized (lane)` 内），无并发读者 —— 与 `ArrayList` 时等价。

#### 8.27.7 同一根因的**测试侧**兄弟：`PiLoopTest.parallelBatchEmitsEveryStartBeforeAnyEnd` 的 flake

§8.25.7 登记过一次**未复现的全 reactor flake**，当时的诊断是「**真竞态**：准备循环按调用逐个发
start、并把执行票**在循环内**交出去 ⇒ task₁ 的 end 可以落在 start₂/start₃ 之间」，处置建议是
「修法一行：**删掉 `:338`**」。**该诊断已证伪，本条更正它并给出真正的根因与修法。**

**① 结构上不存在那条竞态。** `PiLoopTools.executeParallel:148-206` 的形状是：准备循环按源序发
`tool_execution_start`、并把「执行票」登记成 **thunk** 存进 `entries`；`workers.submit(...)`
**只在准备循环跑完之后**（`:190-194`）才发生。所以「全 start 早于任何 end」在本桩形状下是
**结构保证**，不是运气 —— 与 `docs/29 §4.1` 说 pi「不保证」并不矛盾：那条说的是 pi 的
**immediate 分支**（调用在准备相当场失败、在自己的闭包里发 end，S4/S13 剧本即如此），
而本用例的 `StubTools` 恒给执行票、且不中止，走的正是没有 immediate、没有 abort 的那一支。

**② 真根因：测试桩不是线程安全的。** 包 B 之后 `end` 帧由 **worker 线程**发出，而
`Recorder.frames` 是一个普通 `ArrayList` —— 引擎线程与多个 worker 并发 `add`，会**丢帧**。
丢掉的若是某个 `tool_execution_end`，`frames.indexOf(...)` 就可能落到 start 之前（甚至
`ends` 只有 2 条、`ends.getFirst()` 位置反而更早），断言假红。

**量化（临时探针，`ScratchSinkRaceProbe`，跑完即删）**：两个 sink 各 300 轮，每轮 3 个并行
工具在函数体里闩锁会合，收帧的表分别是 `ArrayList` 与 `CopyOnWriteArrayList`：

```
PROBE sink=ArrayList          rounds=300 丢帧/脏帧=5  次序坏=0  抛异常=0
PROBE sink=CopyOnWriteArrayList rounds=300 丢帧/脏帧=0  次序坏=0  抛异常=0
```

5/300 ≈ 1.7% —— 与「全 reactor 跑几轮才红一次的未复现 flake」的量级吻合。
（探针第一版报的是 300/300 全坏：它的 `emit` 把非工具事件也 `add` 了一个 `null`，是探针
自己的 bug，修掉后才是上表。）

**③ 修法：改桩，不删断言。** 删 `:338` 会丢掉一条**真实覆盖**（本桩形状下全 start 早于全 end
—— 它正好是「相位③在整批结束后才回填结果消息」那条实现的哨兵）。四处收帧的表换成
`CopyOnWriteArrayList`：

| 文件 | 字段 |
|---|---|
| `PiLoopTest.java:113` | `Recorder.frames` |
| `PiLoopTest.java:135` | `StubTools.invoked`（`execute` 跑在 worker 线程上） |
| `PiLaneEngineTest.java:133` | `Recorder.frames` |
| `PiLoopTurnHooksTest.java:153` | `Recorder.frames` |

同类夹具里**只有 `ToolBatchConcurrencyTest` 本来就是对的**（它收帧用 `ConcurrentLinkedQueue`，
是当时唯一按「帧可能来自 worker」写的夹具）—— 这三个是漏网的。
`PiLoopTest:319-324` 的用例 javadoc 记下「本断言靠结构 + 桩线程安全**两条**，缺一不可」，
免得后人再把 ② 的症状误诊回 ①。

**④ 验证**：三个类连跑 5 轮，每轮 `Tests run: 7, Failures: 0, Errors: 0`。
**本处只改测试**，零生产代码改动（生产侧的同类问题是 §8.27.1，已在 `0e76e5a` 修）。

**⑤ 口径更正（无损）**：`docs/29 §4.1` 那条撤回依然有效，本次是**收窄它的适用面** ——
它讲的是「pi 的并行分支**不保证**全 start 早于全 end」（对 immediate 分支成立，S4/S13 已证），
不能外推成「所有带并行工具的用例都不许这么断言」。§8.25.7 的 ⚠️ 块已就地更正并保留原诊断。

---

### 8.28 §8.25.5 最后两条的**前提证伪**与重新裁决 —— **已实施（2026-09-17，设计经用户审核通过；实施记录见 8.28.9）**

> **裁决（2026-09-17，用户「继续推进」⇒ 采纳本文档四项推荐）**：
> 1. 第 1 条**结案为「不做」**；方案 C 随之**一并结案**；
> 2. 第 2 条**只修 §8.28.4 第 6 条**（settled 父 ⇒ 子跨度惰性）+ 写差异声明 javadoc，其余显式声明差异；
> 3. §8.28.5 的 ②③ **不修**（② 今天就不可达 ⇒ 登记；③ 删，已随本包落地）；④ 仅登记；
> 4. §8.28.6 的 `batchSize` **修语义、不删**。
> **唯一留白**：是否另立 `TelemetryAdapterConformance` 套件（§8.28.7 末尾）—— 本包未做，随时可加。

> **本包清 §8.25.5 第 1、2 条**（原文照抄）：
>
> 1. 「**跨度词汇与属性词汇不对齐 pi 的 typed schema**（**并含方案 C 的构造改造作地基**，
>    裁决见 §8.25.3）」，处置栏：*「大：先做 C（删环境态、父级显式传、事件归属 span），再对齐
>    12 个跨度的 start/end 属性 + 事件 + `errorWhen` 条件；其中 8 个 pi-java 今天没有对应发射点」*
> 2. 「**pi 的 adapter 契约（9 条）pi-java 只大致满足 2 条**（**同包，依赖 1 的 C**）」，
>    处置栏：*「中：要么补 API（`setStatus` / `addEvent`），要么显式声明「pi-java 的
>    JsonlFileTelemetry 不是 pi adapter 的实现」并写明差异」*
>
> **取证后结论翻转**：第 1 条的处置建立在一个**假前提**上 —— 「pi 发射 12 个跨度」。pi v0.85.1
> 的实况是 **11/12 一个发射点都没有**，唯一有发射点的那个（`pi.harness.hook`）覆盖的是
> pi-java 里对应概念的另一半；而且 **pi 的生产遥测是 noop**（没有任何地方装过真 adapter）。
> 于是「对齐 12 个跨度的属性/事件/`errorWhen`」**没有对齐对象**。按本分支判据（**行为**，
> 不是文档），这两条要么收窄、要么结案。本文档给证据、三个选项与推荐，**您裁决后才写代码**
> —— 已于 2026-09-17 裁决并实施（见本节题头与 §8.28.9）。
>
> 取证底座：pi 钉住版本 `D:\workplaceForai\pi-v0.85.1` @ `d981de12`（Release v0.85.1），
> 另与 `D:\workplaceForai\pi` @ `71dca871b` 做过差分，**遥测源码逐行相同**。

#### 8.28.1 取证：pi v0.85.1 的遥测实况

**表 1 —— 声明的 12 个名字 vs 发射点：只有 1 个被发射**

声明处是 `packages/agent/src/harness/telemetry.ts` 的两份 schema（`AI_TELEMETRY_SCHEMA:42-118`
+ `HARNESS_TELEMETRY_SCHEMA:233-592`），名字共 12 个：

```
pi.ai.request             telemetry.ts:45
pi.harness.run            telemetry.ts:236
pi.harness.compaction     telemetry.ts:258
pi.harness.navigation     telemetry.ts:280
pi.harness.checkpoint     telemetry.ts:302
pi.harness.turn           telemetry.ts:328
pi.harness.step           telemetry.ts:354
pi.harness.tool           telemetry.ts:400
pi.harness.hook           telemetry.ts:453   ← 唯一有发射点
pi.harness.sleep          telemetry.ts:490
pi.harness.event_handler  telemetry.ts:524
pi.session.write          telemetry.ts:545
```

三条**本机复跑**的取证命令（排除 `telemetry.ts` 自身）：

| 取证 | 结果 |
|---|---|
| `grep -rn --include=*.ts -E '"pi\.(harness\|ai\|session)\.[a-z_]+"' packages/*/src` | **只两条真命中**：`hooks.ts:377` 的 `pi.harness.hook`。另两条 `pi.session.name`（`session/values.ts:194`、`session/fork-policy.ts:16`）是**值键不是跨度名**，别误读 |
| `grep -rn --include=*.ts -E 'startHarnessSpan\(\|startAiSpan\(' packages/*/src` | `startHarnessSpan(` **唯一**调用点 `hooks.ts:376`；`startAiSpan(` **零**调用点 |
| `ls packages/telemetry/src` | `index.ts` / `memory.ts` / `noop.ts` / `testing/` —— **没有 JSONL adapter、没有 OTel adapter** |

**表 2 —— 唯一发射者的形状**（`hooks.ts:370-397`，`invokeToolRegistration`）

- 只被 `beforeTool:164` 与 `afterTool:307` 调用 ⇒ 其余钩子名（`before_run` / `before_drive` /
  `before_compaction` / `before_navigation` / `after_response` …）**直接调 handler、无跨度**。
- 开始属性：`pi.lane.name`、`pi.operation.id`、`pi.hook.name`、`pi.hook.registration_id`（条件展开）。
- 结束属性：`pi.hook.outcome` ∈ {`blocked`（`before_tool` 且 handler 返回了 `block`）/ `completed`
  / `failed`}；失败路径**两个 API 都调** —— `setAttributes` 然后 `setStatus`（`:394-397`）。
- **无事件**：`addEvent` 在整个生产源码里**没有调用点**，且两份 schema **都没有 `events:` 声明**
  ⇒ pi 的「跨度事件」词汇是**空集**。

**表 3 —— adapter 实况：生产从不安装**

- `getTelemetryContext`（`packages/agent/src/harness/context.ts:31`）缺省 `NOOP_TELEMETRY_CONTEXT`；
  `withTelemetryContext` 全仓只有**两处**调用者 —— `telemetry.ts:145` 与 `:633`，**都在跨度回调
  包装器内部**（即「装了也只是把自己再装回去」）⇒ 生产路径上**没有任何真 adapter 被安装**，
  `InMemoryTelemetryContext` 只出现在测试里。
- 9 条一致性套件（`packages/telemetry/src/testing/conformance.ts:61-315`）**只注册了一个 adapter**
  （`conformance.test.ts:5-12` 的 `InMemoryTelemetryContext`）。要「一致性」的对象在 pi 里只有一个。
- ⚠️ pi-java 反而**多**两个 adapter（JSONL / OTel）—— 那是 pi-java 的**扩展**，不是缺口。本次
  调查正因为把扩展读成了缺口，才把「无 `setStatus`」登记成待补项（见 §8.28.4 的 ⚠️）。

**表 4 —— schema 是「被测试守住的文档」，不是运行行为**

`packages/agent/test/harness/telemetry.test.ts:22-38` 逐字节比对 `docs/telemetry-schema.md`
（checked in，13KB）与 `renderAgentTelemetrySchemaMarkdown()` 的输出，并断言 harness schema 的
键恰是那 11 个名字。⇒ 这套词汇在 pi 的角色是**契约文档 + 生成物守门**。它**不发任何跨度**。

**表 5 —— L5 录制语料**里**没有**遥测行，「用差分证明词汇对齐」这条路不存在

`conformance/pi-out/S1..S14.pi.jsonl` 与 `java-out/*`：grep `span|telemetry|trace|"name":"pi.`
**零命中**。结构原因：`run.test.ts` 的 `Normalizer.frame()`（`:346-388`）只切 10 种 `AgentEvent`，
`default: return {type:"unknown"}`，且 runner 从不安装 adapter ⇒ 语料**不可能**含跨度行。

#### 8.28.2 前提证伪（逐条对着登记栏的原话）

| 登记栏原话 | 实况 | 判定 |
|---|---|---|
| 「其中 **8 个** pi-java 今天没有对应发射点」 | 不是 8 个 —— **11/12 在 pi 里也没有发射点** | 证伪 |
| 「再对齐 12 个跨度的 start/end 属性 **+ 事件** + `errorWhen` 条件」 | 「事件」在 pi 是**空集**（无 `events:` 声明、生产无 `addEvent`）；`errorWhen` 是 schema 里给 adapter 读的**散文文本**，不是运行时可观察物 | **无对象** |
| （隐含）「pi 的跨度有可观察输出」 | pi 生产 adapter = noop ⇒ 一次真实运行**一个跨度都不落** | **无可观察目标** |

⚠️ 这是 §8.26.3「不为对齐技术名词引入 pi 没有的机制」的**镜像**：那次是「pi 没有而我们要忍住」，
这次是**「pi 有一份文档、没有行为」**。按判据（行为），把 pi-java 的 4 个自有跨度名改成 `pi.*`
换到的是**文档对齐**，不是表现对齐 —— 正是 §8.23.8 ⑥ / §8.26.3 反复拒绝的「学形不学神」。

#### 8.28.3 第 1 条（跨度词汇）的三个选项与推荐

| 选项 | 内容 | 代价 | 收益 |
|---|---|---|---|
| **A（推荐）结案为「不做」** | 保留 4 个自有名；把 pi 的 12 名与「schema 非行为」这组事实登记备查；`docs/18 §5.3` 加一句说明为何不采用 `pi.*` 名 | **零代码** | 判据不被污染；消掉一条会反复误导后人的「待对齐」 |
| B 只改名（4 个自有名 → `pi.*`） | 名对齐，属性名不动 | 中：动 `docs/18 §5.3`、3 个测试类、离线消费约定 | **负**：名对了语义仍不同（§8.28.4），且 pi 根本不发这些跨度 |
| C 全量重建（12 个） | 按 schema 补齐 12 个跨度 + 属性 + `errorWhen` | 大 | **负且危险**：11 个在 pi 是**死声明**，重建等于**发明 pi 没有的行为**，直接违反判据 |

推荐 **A**。理由一句话：**判据只认行为；pi 在这件事上的行为是「声明一份 schema，一个都不发」。**

#### 8.28.4 第 2 条（adapter 契约）的重新裁决

登记栏的处置是二选一（补 API / 声明差异）。取证后逐条分诊 —— 9 条 × {pi 语义 | pi-java 现状 | 判定}：

| # | pi 用例 | pi-java 现状 | 判定 |
|---|---|---|---|
| 1 | `admits once synchronously and preserves the result` | `NoopTelemetryContext.startSpan:21` 同步入场一次、原样返回 ✓。但 pi 的返回是 `Promise<T>`（**异步回调可满足**），pi-java 的签名是 `Function`（同步） | 部分 ✓ / **结构性不可满足**（改签名才可能）→ 声明差异 |
| 2 | `preserves synchronous and asynchronous rejection values` | 同步半边 ✓（`JsonlFileTelemetry.startSpan:127-130` 原样穿透并 `markError`）；异步半边结构性不可满足 | 同上 |
| 3 | `uses last explicit status without automatic overwrite`（后写胜、显式不被自动覆盖） | **✗ 无 `setStatus`**；状态由 `startSpan` 的 catch **自动**置 error | **真差**，但补它要新增公共 API（见下） |
| 4 | `merges attributes and records ordered events` | 属性是**逐键** `addAttribute` —— 无「批量写入后写胜」语义，因而也没有第 5 条的原子性概念；**事件**：pi-java 的 `recordEvent` 是**整份负载的独立行**，pi 的 `addEvent` 是**挂在 span 上的小属性** | **不是同一个东西**（⚠️ §8.25.3 已裁「不能照搬 `addEvent`」）→ 声明差异 |
| 5 | `ignores failed attribute calls atomically` | 无批量 API | n/a |
| 6 | `makes calls after settlement inert` | **✗ 半真**：`close()` 幂等 ✓（`JsonlFileTelemetry:326`）、迟到 `addAttribute` 不落盘 ✓（`:310`）；**但迟到 `startSpan`/`openSpan` 会开子跨度并落盘**（`:343`/`:356` 无 `ended` 检查）。pi 的规则是**「settled 父 ⇒ 子降级为 noop：回调照跑、什么都不记」**（`packages/telemetry/src/memory.ts:126`，本机复核原文 `if (parent?.settled) return NOOP_TELEMETRY_CONTEXT.startSpan(options, callback);`） | **真差，且零 API 变更可修** |
| 7 | `records nested and concurrent child relationships` | ✓（`parentSpanId` + 文件行序） | **唯一已满足** |
| 8 | `suppresses unreadable telemetry payload failures` | Java 无「不可读对象」惯用法 | n/a |
| 9 | `ignores failed status calls atomically` | 同上（无 `setStatus`） | n/a |

**推荐：只修第 6 条（settled 父 ⇒ 子惰性），其余以「显式差异声明」结案。**

**不补 `setStatus` / `addEvent` / `setAttributes` 的三条理由**：

1. 它们服务的是 pi **schema-typed** 的跨度族，而那份 schema 在 pi 里 11/12 无发射点、生产是
   noop（§8.28.1）⇒ 补完也**没有观察者**。
2. `addEvent` 与 pi-java 的 `recordEvent` **不是同一个东西** —— 照搬会把整份消息列表塞进 span 行，
   `--trace-payloads` 的文件结构与离线消费方式剧变（§8.25.3 的 ⚠️ 已裁过）。
3. 补完 `setStatus` 仍要声明两处**结构性**差异 —— **同步 vs `Promise`**、**无 `end()` vs 有 `close()`**
   —— 收益被吃掉大半，属「半个对齐」，反而更难解释。

⚠️ **差异声明必须写进 javadoc，而不只是文档**：pi 的 `TelemetrySpan` **没有 `end()`**
（结算由 `startSpan` 拥有，`packages/telemetry/README.md:103`），pi 的 `SpanStatus` **只有 `ok|error`**
（`packages/telemetry/src/index.ts:12`）；pi-java 的 `TelemetrySpan extends AutoCloseable` 且有
`close()`，另有 `TelemetryContext.openSpan`（无回调开跨度）、`recordEvent` + `pushCurrent/popCurrent`
与**两个 pi 没有的 adapter**（JSONL / OTel）。**这些都是 pi-java 的扩展，不是缺陷** —— 但今天
javadoc 里一个字都没说 ⇒ 下一个拿 pi 契约来对表的人会**再次**把它们误判成缺口（**本次调查正是
这么误判的**，见 §8.25.5-2/-10 的登记口气）。

#### 8.28.5 顺带捞到的四条（三条此前未登记）

| # | 发现 | 出处 | 处置 |
|---|---|---|---|
| ① | 父已结算后开的子跨度**仍落盘**（= §8.28.4 第 6 条） | `JsonlFileTelemetry.java:343` / `:356` | **已修**（本包唯一的行为改动，零 API 变更；RE 见 §8.28.9） |
| ② | `RunLifecycle.reset` 把 `lane.runSpan` 置 null 却**不 `close()`**（只有 `closeRunSpan` 关） | `RunLifecycle.java:228` vs `:54` | **裁决：仅登记，不改** —— **今天就不可达**（证明见下），按 §3.3/§4.1 的口径「零调用者不补代码」，改它就是投机代码 |
| ③ | `JsonlSpan.markAborted()` **全仓无调用者**（另两处 `markAborted` 是 `PiLoopRunner` 上无关的静态方法） | `JsonlFileTelemetry.java:321` | **已删**（`7add447`）：`aborted` 是 pi-java 自造且**死掉**的第三种状态，而 pi 的 `SpanStatus` 只有 `ok\|error`（`packages/telemetry/src/index.ts:12`）；中止由 `harness.run` 的 `outcome` 属性表达，与 pi 的 `pi.operation.outcome` 同口径 |
| ④ | 开/关顺序不对称：`PiLaneSink.endRequest:197-198` 是 `close()` 再 `popCurrent()`，而 `JsonlFileTelemetry.startSpan:131-132` 是 `popCurrent()` 再 `close()` | 两处 | **仅登记**：今天无影响（`popCurrent` 的守卫是 `peek()==span`，与是否已结算无关） |

**② 为什么不可达**（两条互相独立的路，各查一遍）：

1. `runSpan != null` ⟺ 运行在飞 ⟹ `activeRun != null` ⟹ `reset` 在**读到那行之前就抛**
   （`RunLifecycle.reset:216-219` 先查 `lane.isRunning()`，而 `isRunning` 就是
   `activeRun != null`，`LaneState:190`）。`runSpan` 只有两个写入点
   （`startRun:56`、`startContinue:134`），都由 `begin(lane)` 起手，而 `begin` 的第一句
   同样是「车道非空闲即抛」（`:147-150`）；两个清零点成对
   （`closeRunSpan` 关跨度并置 null：`RunSpanFactory:45`，随后 `finishRun:188` 才
   `activeRun = null`）。⇒ **`reset` 走到 `:228` 时 `runSpan` 恒为 null。**
2. 唯一「先清 `activeRun`、不碰 `runSpan`」的地方是 `restoreRecords:307`（崩溃恢复），
   而它唯一的调用者是会话加载（`SessionPersistence.restoreFromRecordLog:140`，
   文档自己写着「The operation is never resumed mid-flight」）—— 那条路上本进程
   **还没有启动过任何 run**，`runSpan` 同样是 null。

**触发条件（若将来失效，届时才需要修）**：出现不带 `isRunning` 门的 reset 变体，或
`restoreRecords` 被运行中调用 —— 那时 `:228` 会静默漏一个只有 `span_start` 的跨度。
修法是一行（把 `lane.runSpan = null` 换成 `runSpans.closeRunSpan(lane, "reset")`）。

#### 8.28.6 缺陷 A（`batchSize`）的新归宿

§8.26.5-11 原裁「进 pi 跨度词汇包」，理由是「那包要重建整个族，届时自然定案（很可能是删）」。
**该前提随本包证伪而消失**（§8.28.3 选项 A 不重建）⇒ `batchSize` 需要自己的处置。

事实不变：并行路径恒为 `N` ✅；**顺序路径是前缀数 1,2,…,N** ❌ ——
`batchSize` 在 `closeToolSpan`（`PiLaneSink.java:466`）读 `batchCallIds.size()`，而这张表在
`noteToolStart`（`:222`）追加、在助手消息落定时清空（`:331`），**由结果消息驱动收尾**；顺序路径
逐调用成组（`PiLoopTools.executeSequential:96-113`）⇒ 第 k 个收尾时只登记了 k 个。
**生产可达**：一个 `ExecutionMode.Sequential` 工具即让整批降级；pi 自己的录制
`conformance/pi-out/S10.pi.jsonl:11-18` 就是 `… → end tc1 → message_start tc1 → message_end tc1 → start tc2 → …`。

**裁决（2026-09-17）：修语义、不删。** `tool.execute` 是 pi-java **自有**的跨度族，`batchSize` 服务
pi-java 自己的离线分析契约（`docs/18 §5.3`）；正确读法是「这批工具调用的**总数**」，而总数在相位①
拿到助手消息时就已知（不必等逐个收尾）—— 故本包把取值改为**助手消息落定时定下的本批总数**
（`batchCallCount`），收尾时只读不算。`toolIndex` 不动：列表在准备相单调增长，
`size() - 1` 两条路径本来就对。实施与 RE 见 §8.28.9。

#### 8.28.7 四项裁决（2026-09-17）

1. **第 1 条（跨度词汇）⇒ A：结案为「不做」。** 保留 4 个自有跨度名，不改成 `pi.*`；
   §8.25.5-1 按 §3.3/§3.4 的写法**结案标注**。
   **方案 C（删环境态）随之一并结案** —— §8.25.3 把它并进本包的唯一理由是「对齐 12 个跨度 /
   adapter 契约需要它」，理由消失后它回到「无可观察目标、不可证伪」；而 §8.25.3 自己写明
   「若 §8.25.5-1/-2 长期不做，C 也不要单独做」⇒ 现在正是那句话所指的情形。
   ⇒ **同族的第 3、8 条一并失去处置载体**（第 3 条的处置栏写的是「与方案 C 同一件事」，
   第 8 条「摘要请求没有自己的跨度」服务的是同一份 schema）—— 但**不在本包顺手结案**：
   第 3 条的可观察面（事件行有没有 `traceId`/`spanId`）已被 package D 的 A1/A2 改过语义
   （现在的规则是「无绑定就留空，不借别人的」），第 8 条要判的是「摘要该不该有自己的跨度」，
   那是 pi-java 自己的产品问题。两条各自另立裁决。
   ⇒ **第 8 条已于 2026-09-17 另行裁决为「修」，落地见 §8.29**（形状选 B：新跨度名
   `compaction.summary`）；**第 3 条仍未结案**，留白在此备查。
2. **第 2 条（adapter 契约）⇒ 只修第 6 条 + 写差异声明 javadoc。** 已落地。
   ⚠️ 注意结案口径：**不是**「pi-java 的 adapter 不满足 pi 的契约」，而是
   **「pi-java 的 adapter 比 pi 多，且 pi 自己那 9 条只对着一个测试用实现」** ——
   可满足的按 pi 语义满足，不可满足的（同步 vs `Promise`、`close()` vs 无 `end()`）
   以**结构性差异**声明，不假装对齐。
3. **§8.28.5 的 ②③ ⇒ ② 仅登记（今天就不可达）、③ 删。** 已落地。
4. **§8.28.6 的 `batchSize` ⇒ 修语义。** 已落地。

**留白（本包未做，随时可加）**：是否另立 `TelemetryAdapterConformance` 套件，照仓内现成范式
（`agent-core` 的 `ConformanceGroup{1,2,3}Test` 抽象基类 × 3 个 backend 子类、`evals` 的
`ChatApiConformanceSuite`）把「可满足的 pi 用例 + 每条差异的断言」一起钉住。
**倾向：暂不做** —— pi 那 9 条套件的注册表里只有一个测试用 adapter，本包把可满足的两条
（生命周期 / 结算惰性）都补了夹具，再套一层抽象基类要先有第二个 adapter 才划算；
若将来加 OTel 之外的真 adapter，从那条用例集起手即可。

#### 8.28.8 实施切分（2026-09-17 实际执行）

| # | 内容 | 模块 | 可独立编译 | 落到 |
|---|---|---|---|---|
| 1 | settled 父 ⇒ 子跨度惰性（`JsonlFileTelemetry` 两处入口）+ 夹具 | telemetry | ✓ | `35c4758` |
| 2 | 差异声明 javadoc（`TelemetryContext` / `TelemetrySpan` / `JsonlFileTelemetry`：扩展清单 + 结构性差异） | telemetry | ✓ | `c3eef82` |
| 3 | `TelemetryAdapterConformance` 套件 | telemetry | ✓ | **未做**（见 §8.28.7 留白） |
| 4 | 删 `JsonlSpan.markAborted`（② 仅登记，未改） | telemetry | ✓ | `7add447` |
| 5 | `batchSize` 语义修复 + 夹具 | agent-core | ✓ | `7f0cfb9` |
| 6 | `docs/18 §5.1/§5.3` 更正 + §8.25.5-1/-2 **结案标注** | docs | — | 本提交 |

#### 8.28.9 实施记录（2026-09-17）

**提交**（分支 `agent-core-pi-loop`）：

| # | 提交 | 内容 |
|---|---|---|
| 1 | `35c4758` | settled 父 ⇒ 子跨度惰性（`startSpan` / `openSpan` 两处入口）+ 2 条夹具 |
| 2 | `c3eef82` | 差异声明 javadoc（`TelemetryContext` / `TelemetrySpan` / `JsonlFileTelemetry` 三处） |
| 3 | `7add447` | 删掉不可达的 `aborted` 状态（`markAborted`） |
| 4 | `7f0cfb9` | `batchSize` 语义修复 + 多调用顺序批夹具 |

> ⚠️ §8.28.5 / §8.28.6 里的行号是**改动前**的位置（取证时逐条核过的就是那些行）；
> 本包落地后 `JsonlFileTelemetry`、`PiLaneSink` 两处均有下移，引用时以符号名为准。

**① settled 父 ⇒ 子跨度惰性**（`JsonlFileTelemetry.JsonlSpan`）：`startSpan` 与 `openSpan`
各加一个 `if (ended)` 早退，把子跨度**降级为 noop 上下文**（`NoopTelemetryContext.INSTANCE`）——
照 pi 的原文语义（`packages/telemetry/src/memory.ts:126`），不是"记在已结算的父下面"。
noop 的那两处天然满足其余要求：回调同步入场一次、返回值/异常原样穿透、
`openSpan` 回来的惰性 span 忽略 `addAttribute`/`close`（调用方无需特判）。**零 API 变更。**

夹具 `childSpanOpenedAfterItsParentSettledIsInert` 同时钉住三件事：返回值 `7` 原样穿透、
回调恰入场一次、异常**同一对象**穿透、且**行数一行不增**（含 `openSpan` 那条腿）。
必须配正向对照 `childSpanOpenedBeforeItsParentSettledIsRecorded` ——
否则「什么都不记」在「本来就什么都不记」的实现上也是绿的。

**② 差异声明 javadoc**：三份 javadoc 各写清「哪些是 pi-java 的扩展、哪两处是**不可闭合的
结构性差异**（同步 `Function` vs `Promise`、`AutoCloseable.close()` vs 无 `end()`），
以及 `addEvent`/`setStatus` **不移植是决定而非疏漏**」。这条不是文档洁癖：本次调查
**正是因为**把扩展读成了缺口，才把「无 `setStatus`」登记成待补项（§8.28.4 的 ⚠️）。

**③ `markAborted` 删除**：删除后 `status` 的取值闭集就是 `ok` / `error`，与 pi 的
`SpanStatus`（`packages/telemetry/src/index.ts:12`）一致；中止由 `harness.run` 的
`outcome` 属性表达（`RunSpanFactory.closeRunSpan` 写 `OperationOutcome`，
取值 `completed` / `aborted` / `failed` / `declined`）—— 与 pi 的
`pi.operation.outcome` ∈ {completed, aborted, failed, suspended} 同口径：
**中止是结果属性，不是状态。**

**④ `batchSize` 语义修复**（`PiLaneSink`）：新增字段 `batchCallCount`，在助手消息落定
（`onMessageEnd` 的助手分支）时由 `countToolCalls(assistant)` 定下（数助手消息里的
`ToolUseContent` 块），`closeToolSpan` 改为读它，不再读 `batchCallIds.size()`。
两个前提都成立：① 总数在那条助手消息落定时就已知；② 帧序保证 `message_end(assistant)`
**先于**该批任何 `tool_execution_start`。`toolIndex` 不动。

夹具 `sequentialBatchReportsTheWholeBatchSizeOnEverySpan`：**双调用**顺序批。既有的
`toolCallEmitsExecuteSpanCountersAndAuditRecords` 只有单调用批（两种读法同值
`batchSize == 1`），测不出这个缺陷 —— 这正是它先前躲过检测的原因。
第一条断言把**路径**钉住（`start:a, end:a, start:b, end:b` 逐调用成组，即缺陷的前提，
也是 pi 自己 `S10.pi.jsonl:11-18` 的形状；`ToolExecution.defaultMode()` 是 Parallel，
夹具的 `activeTools` 又是空的，故显式传 `new ToolExecution.Sequential()`）。

**RE（关键，两条）**：

| RE | 做法 | 结果 |
|---|---|---|
| RE-1 | 把 `closeToolSpan` 的读改回 `batchCallIds.size()` | **恰一条红**：`expected: 2 but was: 1`，落在 `call-a` 上（call-b 尚未 start）—— 与预测逐字相符 |
| RE-2 | 首次运行（未显式给执行模式）时的路径形状 | 实测 `start:a, start:b, end:a, end:b`（**并行**路径）⇒ 夹具当时**没有牙**：并行路径两种读法同值。据此才补上 `new ToolExecution.Sequential()`。**如实记录：第一版夹具是运气夹具。** |

**回归**：`mvn -o -pl pi-java-telemetry -am verify` 与
`mvn -o -pl pi-java-agent-core -am verify` 全绿（telemetry 31 / agent-core 448，
checkstyle + spotbugs 零违规）。

⚠️ **`-am` 不可省**（本包又踩一次）：不带 `-am` 的 `verify` 会拿 `D:/repository` 里的
**旧** `pi-java-ai` 构件，agent-core 的 448 条里 208 条报 `NoSuchMethod` —— 那是构件陈旧，
不是本包改坏了什么（memory `jdk25-mvn-am`）。

**未覆盖 / 留白（如实登记）**：

- `TelemetryAdapterConformance` 套件**未做**（§8.28.7 留白，倾向暂不做）。
- §8.25.5-3（`recordEvent` 的环境态语义）**未结案**，理由见 §8.28.7-1（它的观察面与
  本包的证伪不是同一件事）。
- ~~§8.25.5-8（摘要请求没有自己的跨度）未结案~~ ⇒ **已由 §8.29 结案（2026-09-17）**。
- §8.28.5-②（`reset` 漏关 run span）**未改**，可达性论证见 §8.28.5；
  若将来出现不带 `isRunning` 门的 reset 变体，届时修（一行）。

---

### 8.29 摘要请求的宿主跨度（§8.25.5-8）—— **已实施（2026-09-17，设计经用户审核通过）**

> **裁决（2026-09-17，用户）**：修。形状取**选项 B（新跨度名 `compaction.summary`）**
> —— 三个备选与取舍见 §8.29.2。落地：`CompactionExecutor` 一处 + 两条新用例；另有一条
> 既有特征化断言按新语义改判（§8.29.5）。

#### 8.29.1 缺陷（取证）

摘要是一次**真正的 LLM 调用**，走的是与主循环**同一个** `streamFn`
（`AgentSession:366` 把 `recordingStreamFn` 交给 `LlmSummaryGenerator`，后者在 `:226`
调 `streamFn.stream(...)`），于是 `PayloadRecordingStreamFn` 照常为它发出
`llm.payload.request` / `llm.payload.response` 事件行。但事件行的归属靠**当前线程的绑定**
（`JsonlFileTelemetry:173-179` 读 `currentStack` 栈顶），而压缩路径**从不 `pushCurrent`**：

| 环节 | 主循环 | 压缩摘要 |
|---|---|---|
| 开跨度 | `PiLaneSink.beginRequest:185` 开 `llm.request` | 只有外层的 `compaction.apply`（`CompactionExecutor:240`），且它走 `openSpan`（不碰栈） |
| 绑定 | `PiLaneSink:191` `pushCurrent` | **无** |
| 事件行 | 带 `traceId`/`spanId` | `traceId` 为 null，连 `spanId` 键都没有 |

后果三条：① 摘要那次请求连不回哪一次运行、哪一次压缩（`--trace-payloads` 开着时负载
整份在盘上，却是**孤儿行**）；② 运行中手动 `/compact`（§8.25.5-7 无门）与在飞请求重叠时，
同一份文件里主循环那些行有 id、摘要那些行没有，离线分析只能猜；③ 摘要是全链路上
**唯一一处花掉 token 却不在 trace 里留痕**的 LLM 调用 —— `compaction.apply` 的 start/end
属性只有 `reason/estimatedTokens/entriesBefore/entriesAfter`（`:240-242`/`:282`），
`docs/18 §1` 承诺的「每轮 LLM 调用埋点」在它身上是空的。

#### 8.29.2 形状裁决：三个备选

| # | 做法 | 判 |
|---|---|---|
| A | 摘要调用复用 `llm.request` 名，作 `compaction.apply` 的子跨度 | ✗ **静默改变既有聚合口径**：按跨度名数 `llm.request` 从此不再等于 agent 轮数；而「摘要」与「agent 一轮请求」本来也不是一件事 |
| **B** | 新名 **`compaction.summary`**，父 = `compaction.apply`，摘要生成期间绑定为当前跨度 | ✓ **采用**。既有按名聚合一律不受影响（只多一个名），且它正是挂摘要时长与用量的地方；登记项的原话就是「摘要请求**没有自己的跨度**」 |
| C | 不新开跨度，把事件行绑到 `compaction.apply` 上 | ✗ 让「只记元数据的操作跨度」变成「带整份负载的跨度」，与该跨度既有的用法不一致；且摘要只是压缩的一个子步骤，绑在父上就再也分不出它的耗时 |

**非目标（决策而非疏漏）**：不新增 `llm.*` 计数器。摘要是 LLM 调用，但
`llm.requests`/`llm.tokens.*`（`PiLaneSink:214-221`）服务的是**主循环**的聚合，把摘要
混进去会改变既有指标语义；`compaction.summary` 跨度自身带 `inputTokens`/`outputTokens`，
需要聚合时按跨度名取（`docs/18 §5.3` 同步登记）。

#### 8.29.3 落地

`CompactionExecutor.compactTranscript`（唯一调用点 `:252`，在 `applyCompaction` 的压缩体
内）改为接收父跨度 `TelemetrySpan`：

1. `parent.openSpan(new SpanOptions("compaction.summary", Map.of("reason", reason)))` ——
   父是 `compaction.apply`，因此同一条 trace、`parentSpanId` 指向它；
2. 摘要生成期间 `ctx.telemetry().pushCurrent(summarySpan)`，`finally` 里先 `close()`
   再 `popCurrent`（与 `PiLaneSink.endRequest:210-211` 同序）；
3. 收尾属性：`summaryChars`，以及 `result.usage()` 非空时的 `inputTokens`/`outputTokens`。

三点须留意（已写进 javadoc）：① **不**复用 `llm.request` 名；② 生成器的重试环（3d 环 B）
**在同一条跨度下**发生，每次重试的请求行都绑到它，重试次数从行数看得见；③ 非 LLM 的
截断兜底生成器（`SummaryGenerator.truncating()`）没有 LLM 调用，这条跨度仍会出现
（只有 `summaryChars`、无事件行与 token）—— 跨度描述的是「摘要这一步」，**不是**
「一定发生了一次请求」。

落点在 `compactTranscript` 而非 `LlmSummaryGenerator`，理由有二：**接线**上，harness 侧
`ctx.telemetry()` 就是 `PayloadRecordingStreamFn` 持有的那一个 exporter 实例
（`AgentSession:265-266` 与 `:377` 同一份），无需给 `com.pijava.agent.compaction` 注入遥测；
**安全**上，正因为不注入，就不存在「注入了另一个实例 ⇒ 绑定静默失效」这条路
（`with(...)` 返回新实例，§8.25.5-4）。可达性亦已核对：阈值/溢出压缩从
`PostRunCompactionCheck:163/:180/:204` 进（run 已终局，`llm.request` 的 push 早被
`endRequest` pop 掉），手动 `/compact` 从 `RunLifecycle.compact` 进；A1 之后栈是
`ThreadLocal`，另一线程的重叠压缩不会互相污染。

#### 8.29.4 测试与 RE

新夹具 `HarnessCompactionSummarySpanTest`（2 条），接线照生产形状：同一个
`JsonlFileTelemetry`（`withPayloads(true)`）既进 `HarnessConfig.telemetry`、又包住
`streamFn`（`PayloadRecordingStreamFn` 的最小同形替身，`agent-core` 看不到那个包内类）。
断言：摘要的**请求行与响应行**都绑 `compaction.summary`；该跨度的 `parentSpanId` =
`compaction.apply` 的 spanId、traceId 同源；收尾带 `summaryChars` 与 token。第二条用例是
正向对照 —— 主循环的请求行仍绑 `llm.request`，摘要那个绑定既不抢它、也不在 pop 后残留。

**RE-1（关键）**：停掉 `pushCurrent` ⇒ **恰两条红**（每个类各一条），失败点都落在「归属」
断言上：`expected: "afc24a46a19cc3b5" but was: ""`（另一条 `"991328bc"`）。
断言一律用 `path(...)` 而非 `get(...)` 读 JSON —— 键缺席时得到 `""` 而不是 NPE，
回归时读到的就是上面那句「没绑上」，而不是栈里一个空指针。

#### 8.29.5 顺带改判：一条既有特征化断言

`HarnessTelemetryThreadAttributionTest.compactionFromAnotherThreadDoesNotInheritTheInFlightRequestSpan`
（§8.25.6 ③）原先断言**摘要请求行没有 `traceId`/`spanId`** —— 那是「没有归属」时期对
不变量「**不借用**在飞请求的跨度」的写法。补上归属后按新语义重写：

- 保留的不变量：摘要行的 `spanId` **≠** 在飞那条 `llm.request` 的 spanId（不借用）；
- 新增的正向面：摘要行的 `spanId` **=** `compaction.summary` 跨度的 spanId（有自己的归属）。

⚠️ 同一条用例的 `runRequests` 筛选也得跟着改：它原来靠 `has("traceId")` 把摘要行排除，
现在摘要行也有 traceId ⇒ 改为按系统提示前缀排除（否则「最后一条请求行」会取到摘要那条）。
这是**前提变更**引起的夹具维护，不是断言被削弱。

#### 8.29.6 未覆盖 / 留白

- 重试的**每次**尝试没有各自的跨度（主循环那边每次 attempt 一条 `llm.request`，因为重试环
  在更外层）。内层环共享一条 —— 够用，但不对称，登记备查。
- 截断兜底生成器下那条「无事件、无 token」的跨度形状**无测试**（生产装的是
  `LlmSummaryGenerator`，缺省才是 `truncating()`）。
- §8.25.5-3（`recordEvent` 的环境态语义）**仍未结案**：本条解决「摘要请求该有自己的跨度」，
  第 3 条问的是「没有宿主跨度时事件行怎么办」的兜底规则，面不同。

**回归**：`mvn -o -pl pi-java-agent-core -am test` 绿 —— telemetry 31 / ai 336 /
agent-core **450**（本包 +2）。

---

### 8.30 压缩产物的文件清单 `details`（B2）—— **已实施（2026-09-17，设计经用户审核通过；实施记录见 8.30.8）**

> 类别 **B（功能缺口：pi 有、pi-java 无）**。出处：`docs/32 §3 B2`，原登记在本文 `§8.21.5`。
> 判据仍是**行为**：落库的 `Entry.Compaction` 形状 + 摘要文本的尾部块。
> **本节是设计，尚未写码** —— 审核通过后实施，实施记录续在 §8.30.x，同时把本行抬头改成
> 「**已实施（日期，设计经用户审核通过；实施记录见 8.30.x）**」。
> → **2026-09-17 已完成**：抬头已改，实施记录见 §8.30.8（含三条 RE 与一处**预测不符的更正**）。

#### 8.30.1 缺口的确切形状

pi 每次压缩产出两样东西，pi-java **都没有**：

| # | pi 的行为 | 出处（`v0.85.1`） | pi-java 现状 |
|---|---|---|---|
| ① | `details = {readFiles: string[], modifiedFiles: string[]}`，**两键恒在**、数组可为空、去重、排序 | `coding-agent/.../compaction/compaction.ts:962`（legacy）、`agent/.../harness/compaction/compaction.ts:813`（harness） | `CompactionService.compact:57` 直接传 `null` ⇒ `Entry.Compaction.details` **恒 null**，落盘时整个键缺席（`Entry` 类头 `@JsonInclude(NON_NULL)`） |
| ② | 摘要**文本**尾部追加 `<read-files>` / `<modified-files>` 两块 | 同上 `:951` / `:812`，块本体在 `harness/compaction/utils.ts:62-72` | 无 |
| ③ | 清单由 **assistant 的 toolCall 块**确定性抽取，**从不问模型** | `utils.ts:24-51` | 无 |
| ④ | 清单**跨压缩累积**：上一份 compaction entry 的 `details` 回灌（`readFiles`→read、`modifiedFiles`→edited） | `compaction.ts:46-76`（harness）/ `:42-70`（legacy） | 无（且第一份就恒 null，谈不上累积） |

③④ 要强调：`readFiles`/`modifiedFiles` **不是**模型输出的解析结果 —— 没有任何 prompt 请求它们；
模型侧只看到 ② 拼在摘要尾部的文本。所以这是一次**纯确定性**移植，没有模型行为的不确定性，
也不需要任何夹具上的模型响应形状假设（这一点决定了它的验证方式，见 §8.30.6）。

#### 8.30.2 pi 的逐字形状（取证）

`FileOperations`（`utils.ts:5-12`）：三个 `Set<string>` —— `read` / `written` / `edited`。

`extractFileOpsFromMessage`（`utils.ts:24-51`）：只看 `role === "assistant"` 且 `content` 是数组的消息；
逐块取 `type === "toolCall"`，读 `arguments.path`（**是字符串才算**），再按**工具名硬编码**分派 ——
`read`→read、`write`→written、`edit`→edited；**其它工具名一律不记**（`bash`/`glob`/`grep` 都不记）。
pi-java 三个内置工具的名字与之逐字相同：`ReadTool:35` / `WriteTool:33` / `EditTool:44`。

`computeFileLists`（`utils.ts:54-59`）：`modified = edited ∪ written`；
`readFiles = read ∖ modified` 后排序；`modifiedFiles = modified` 后排序。
**排序口径**：JS 默认 `sort()` 按 UTF-16 码元序，Java `String.compareTo` 同为 UTF-16 码元序
（含代理对时也比码元）⇒ **两侧逐字相同**，不需要自定义比较器。

`formatFileOperations`（`utils.ts:62-72`）：两块各自形如
`\n\n<read-files>\n{p1}\n{p2}\n</read-files>`；两块之间**没有**额外分隔（每块自带 `\n\n` 前缀）；
两块都空 ⇒ 返回 `""`（摘要文本一个字符都不变）。

`extractFileOperations`（harness `compaction.ts:46-76`）：先回灌上一份 compaction 的 `details`
（`readFiles`→read、`modifiedFiles`→edited，**逐元素判字符串**），再逐条消息抽 `toolCall`。

顺带登记一条**同族的**缺口：pi 的 `previousSummary` / split-turn 等 §8.30.7 另列。

#### 8.30.3 pi-java 的接线点（都已就位，只缺生产者）

| 位置 | 现状 |
|---|---|
| `CompactionResult.java:24` 的 `details` 组件 | 在 |
| `Entry.java:144` `Entry.Compaction.details` | 在（`@JsonInclude(NON_NULL)`） |
| `EntryJsonCodec.java:63` `optionalObject(node, "details")` | 在（解码侧本来就吃这个键） |
| `CompactionExecutor.java:411` 把 `result.details()` 写进 entry | 在 |
| **`CompactionService.compact:52-57`** | **`summarize(...)` 之后直接 `new CompactionResult(..., null)` —— 唯一的缺口** |

即：**消费链一条不缺，缺的只有生产者**。这也解释了为什么 B2 一直留着：没有任何一处会因为
`details == null` 而报错，它是**静默**缺的。

#### 8.30.4 设计

新增两个类，改一个方法（都在 `pi-java-agent-core/…/compaction/`）：

1. **`FileOperations`**（包内可见，~20 行）：三个 `LinkedHashSet<String>` 累加器。
   pi 侧也是可变对象，不改形状换不可变 —— 逐个 `Set.add` 与 pi 的 `fileOps.read.add(path)` 同构。
2. **`CompactionFiles`**（~110 行）：
   - `extractFromMessage(Message m, FileOperations ops)` —— 读 `ContentBlock.ToolUseContent`
     （pi-java 里 pi 的 `toolCall` 块叫这个名字，`ContentBlock.java:79`：`(id, name, arguments)`），
     `arguments()` 已经是 `Map<String,Object>`（不是 JSON 字符串）⇒ 判据是
     `args.get("path") instanceof String`；
   - `compute(FileOperations ops)` → `record Lists(List<String> readFiles, List<String> modifiedFiles)`；
   - `format(List<String> readFiles, List<String> modifiedFiles)` → 逐字照 `utils.ts:62-72`；
   - `details(List<String> readFiles, List<String> modifiedFiles)` → 有序字典；
   - `extract(List<Message> messages, List<Entry> transcript)` —— 含 ④ 的累积回灌。
3. **`CompactionService.compact` 收尾**改成：

   ```java
   var fileOps = CompactionFiles.extract(discardedMessages, transcript);
   var lists = CompactionFiles.compute(fileOps);
   String summary = summaryResult.text() + CompactionFiles.format(lists.readFiles(), lists.modifiedFiles());
   return new CompactionResult(summary, firstKept, tokensBefore, null, summaryResult.usage(),
       CompactionFiles.details(lists.readFiles(), lists.modifiedFiles()));
   ```

**为什么累积回灌放在 `CompactionService` 而不是别处**：只有它同时拿得到 `transcript`
（可回扫上一次的 `Entry.Compaction`）和 `discardedMessages`。回扫规则照 pi：
**从尾往前**找最后一份 `Entry.Compaction`（harness `:642-648`）。pi-java 的 marker 由
`CompactionExecutor:423` 的 `kept.add(0, compactionEntry)` 放在**转录下标 0**，所以每次都是一击命中，
但回扫写法要保留 —— 它不依赖「marker 恰在头部」这个实现细节。

**`fromHook` 的取舍（必须登记）**：pi legacy 的回灌带一个额外守卫 `!prevCompaction.fromHook`
（`coding-agent/.../compaction/compaction.ts:52`，注释说该字段只为会话文件兼容而留），
harness 那份只做形状守卫。pi-java 的 `Entry.Compaction` **没有 `fromHook` 字段**
（`Entry.java:135-146`）⇒ 采用 **harness 的形状守卫**（对象、非 null、非数组、逐元素判字符串），
并登记一条差异：**若将来有 hook 写下的 compaction entry，它也会被回灌**。
（`CompactionExecutor:246-250` 的 `before_compaction` 钩子若返回 `keepEntries`，会**整份替换**转录，
因此钩子可以塞进一份自己写的 `Entry.Compaction` —— 这正是 pi legacy 那个 `fromHook` 要挡的情形，
所以这不是空谈。）今天 pi-java 无该生产者，判为可接受。

**一个 Java 侧的具体陷阱**：`Map.copyOf` / `Map.of` 的**迭代序不保证**，
而 pi 写出的是 `{"readFiles":…,"modifiedFiles":…}`。为保持 JSONL 逐字节可比，
详情字典用 `Collections.unmodifiableMap(new LinkedHashMap<>(…))` 构造，**不要用 `Map.of`**。

#### 8.30.5 边界与不做

- **不动** `CompactionResult` / `Entry.Compaction` 的字段（`details` 早就在，形状不用改）。
- **不动** 切点算法、`tokensBefore`、`usage`、`retainedTail`、重试环与钩子。
- **不改** 其它 entry 类型的 `details`（`BranchSummary` / `Message.tool.details` 各有各的生产者，
  B1 那包才会碰前者）。
- **不为空清单发明特殊值**：pi 写 `{readFiles:[], modifiedFiles:[]}`，pi-java 同样写空数组，
  **不是** `null`、**不是**缺键。这是本包可见的、也是最容易被"顺手优化掉"的一处。
- **不**把这件事并入 `docs/32` 素描里建议的「`/compact` 命令面复查」——
  它是摘要生成路的**纯生产者**逻辑，与命令面无关，混在一起审两种行为不合算。

#### 8.30.6 验证

新夹具 `CompactionFileOpsTest`（`pi-java-agent-core` 的 `com.pijava.agent.compaction` 包）：

1. `read`/`write`/`edit` 各一次 ⇒ 三集合各 1，且 `readFiles` 只含那条 read；
2. **被 read 又被 edit 的路径只进 `modifiedFiles`**（`read ∖ modified` 的差分规则）；
3. 乱序 + 重复工具调用 ⇒ 去重且排序（这条同时钉住「JS `sort()` ≡ `String.compareTo`」）；
4. **无任何文件操作** ⇒ `details` 仍是**两键空数组的对象**，且摘要文本尾部**没有任何块**；
5. **累积**：先压一次（内含 `read a.ts` 的 assistant 轮），再压一次（第二批不含 `a.ts`）
   ⇒ 第二份 `details.readFiles` **仍含 `a.ts`**；
6. **逐字节**断言摘要尾部的 `<read-files>` / `<modified-files>` 文本（含 `\n\n` 前缀与块间衔接）；
7. `bash`/`glob` 等工具名**不入账**（防"顺手把其它工具也记上"）。

**RE（反向实验）**：
- 停掉生产者（还原成传 `null`）⇒ 第 4/5/6 条红；
- 只停掉**累积回灌** ⇒ **第 5 条恰一红**，其余全绿（证明累积是独立的一处，不是被别的断言顺带覆盖）；
- 把 `read ∖ modified` 的差分去掉 ⇒ 第 2 条红。

**回归**：`mvn -o -pl pi-java-agent-core -am test`（`-am` 必须带，见 memory `jdk25-mvn-am`），
再全 reactor `mvn -o clean verify`。L5 差分剧本不压缩，**预期 12/12 不动**——
若动了，说明碰到的是别的东西，要停下来查。
预计规模：主源码 ~130 行 + 测试 ~150 行 ⇒ **1 个 commit**（`feat(agent-core)`）。

#### 8.30.7 本包**不含**：压缩摘要路的其余四处（各自另立）

复核 `LlmSummaryGenerator` / `CompactionService` 时发现摘要路还有四处更深的差距。
它们**都不并入本包** —— 免得一次审核裹进四种行为变更：

| # | 差距 | 出处 | 为何不并入 |
|---|---|---|---|
| a | **摘要 prompt 是另写的简版**：pi 的 `SUMMARIZATION_SYSTEM_PROMPT`（harness `compaction.ts:420-422`）、六段式 `SUMMARIZATION_PROMPT`（`:424-455`，含 `### Done/### In Progress/### Blocked`、`## Key Decisions`、`## Next Steps`、`## Critical Context`）、`<conversation>` 信封（`:561-577`）与 `serializeConversation`（`utils.ts:91-132`，含 `[Assistant tool calls]:` / `[Tool result]:` 与 `TOOL_RESULT_MAX_CHARS = 2000` 截断）pi-java **全无**（`LlmSummaryGenerator:297-326` 是自写的四段简版，只有 `[User]:` / `[Assistant]:`） | 对比 `LlmSummaryGenerator:51-55` / `:297-314` vs 上述 | 改的是**模型看到的东西**，意义最大，也最该单独审 |
| b | `previousSummary` 未传（`CompactionService:53` 恒 `null`）⇒ pi 的 `UPDATE_SUMMARIZATION_PROMPT` 那条路在 pi-java 是**死码** | harness `:650-654`、`:457` | 与 (a) 同一处装配，宜同包 |
| c | 摘要请求未设 `maxTokens = min(floor(0.8·reserveTokens), model.maxTokens)`、未设 `thinkingLevel`、未用 `customInstructions`（`reserveTokens` 参数在 pi-java 收了但没用） | `LlmSummaryGenerator:221-222` 传 `OptionalInt.empty()` / `ThinkingConfig.OFF` vs harness `:559-566`、`:585-589` | 同上（同一处请求装配） |
| d | **split turn**：pi 在切点落在 turn 中间时对 `turnPrefixMessages` 单独摘要，两段用 `\n\n---\n\n**Turn Context (split turn):**\n\n` 拼接（`TURN_PREFIX_SUMMARIZATION_PROMPT`） | harness `:672`、`:679-684`、`:691-695`、`:774`、`:709-722` | 是**切点算法本身**的变更，独立且更大 |

⇒ 建议后续顺序：**(a+b+c) 一包 → (d) 一包 → B1 → B5 → B3 → B4**。

⚠️ 同处的记账问题：`docs/13:45` 把这条链记成「目标对齐度 **90%**（结构化摘要流程对齐 pi；
`serializeConversation` 完整细节渐进）」—— 按上述取证，**该数字是低报**（prompt 文本、序列化、
请求参数、`previousSummary` 四条都不一致）。待 (a+b+c) 那包落地时一并订正。

**归档动作**（实施后做，`docs/32 §10.1`）：本包在 §8.30 落地后，于 `docs/32` 的 B2 行按
「改行不改号」补指向，并在 G 类留一行；§0.1 的「已复核」小节列表需加上 `§8.30`。

#### 8.30.8 实施记录（2026-09-17）

**commit**：`4380796`（`feat(agent-core): 压缩产物写文件清单 details 与摘要尾部两块`，
4 文件 +423/-2）。文档侧归档（本节与 `docs/32`）是其后那个 `docs` 提交。

**落地内容**（`docs/31` 之外只碰了 `docs/32` 的登记）：

| 文件 | 变化 |
|---|---|
| `compaction/FileOperations.java`（新，25 行） | 三个 `LinkedHashSet` 累加器 |
| `compaction/CompactionFiles.java`（新，183 行） | `collect` / `extractFromMessage` / `carryOver` / `addPaths` / `compute` / `format` + 内嵌 `record Lists`（`formatted()` / `details()`） |
| `compaction/CompactionService.java`（改 6 行） | `collect` → 摘要尾部拼 `formatted()`、`details` 取 `lists.details()` |
| `compaction/CompactionFileOpsTest.java`（新，209 行，10 条用例） | 下面的表 |

**API 与设计的一处偏离**：设计写的是 `extract`/`compute`/`format`/`details` 四个顶层方法，
实现收成 `collect(messages, transcript) → Lists` 一个入口，`formatted()`/`details()` 挂在
`Lists` 上。理由：两个产物**同源**（同一次抽取），拆成四个方法会让调用方自己记住「这两处必须
用同一份清单」—— 那是把不变量交给调用方。语义逐条不变。

**三条 RE 的实测**（每条都先停掉一处真实现，跑完再复原）：

| RE | 停掉什么 | 预期 | 实测 |
|---|---|---|---|
| RE-1 | `CompactionService` 的生产者（还原成 `null` + 裸文本） | 4/5/6 红 | **10/10 全红**（2 failures + 8 errors，errors 是 `details()` 为 null 的 NPE）✔ 有牙 |
| RE-2 | `CompactionFiles.collect` 的累积回灌 | **恰 1 红** | **2 红** —— `fileOpsCarryOverAcrossSuccessiveCompactions` + `malformedPreviousDetailsIsIgnored` |
| RE-3 | `compute` 的 `read ∖ modified` 差分 | 1 红 | **恰 1 红**：`aPathBothReadAndEditedCountsAsModifiedOnly` ✔ |

⚠️ **RE-2 与预测不符，以实测为准**：设计说「恰一红」，实际是两条 —— 因为
`malformedPreviousDetailsIsIgnored`（8.30.6 里未单列，实现时补的）**也是**一条依赖「读上一份
details」的用例。要守的性质没变，但提法要改准确：**停掉累积回灌会打死恰好那两条读上一份
details 的用例，其余 8 条一条不动** —— 这才是「累积没有被别的断言顺带覆盖」的证据。
预测错的原因是设计时把这条额外夹具（形状守卫）算成了"抽取"侧，实际它走的是回灌侧。

**一条工具链坑（本轮踩到，记下来）**：`mvn -o -pl pi-java-agent-core` **不带 `-am`** 时，
`pi-java-ai` 从本地仓库 `D:/repository` 取，而那份构件比工作树的 `Message.AssistantMessage`
**旧**（3 参构造 vs 9 参）⇒ `SessionJson` / `MessageJsonCodec` 编译失败，报的是**假红**。
memory `jdk25-mvn-am` 记的「假绿/假红」两向都成立，本次撞的是后一向的**编译期**形态
（此前记的是测试期形态）：**该带 `-am` 的场景必须带**。

**回归**：`mvn -o -pl pi-java-agent-core -am test` 绿 —— agent-core **460**（本包 +10），
telemetry 31 / ai 336 不动；`mvn -o clean verify` 全 reactor **BUILD SUCCESS**（14 个模块），
警告只有既有的 shade 重叠资源。**L5 差分 14/14 不动**（与设计预期一致：剧本不压缩）。

**未覆盖 / 留白**：

- **`Entry.Compaction.details` 的落盘往返无单测**：本轮钉的是 `CompactionResult.details`
  的产出，`EntryJsonCodec:63` 的编解码由既有 JSONL 一致性组覆盖形状，但**没有**一条用例
  断言「压缩产物的 JSONL 行里 details 是两键对象」。要补得靠一条端到端压缩夹具。
- 钩子写下的 compaction entry 会被回灌（§8.30.4 登记的差异）**无测试** —— 今天无该生产者。
- 摘要属于「被丢弃前缀」的**边界**：本包只看 `discardedMessages`（pi 的
  `messagesToSummarize`）；pi 在 split turn 下还会再抽 `turnPrefixMessages`
  （`compaction.ts:691-695`）—— 那是 (d) 那包的事，此处**不预埋**。

---

### 8.31 provider 路由跟着模型走 + thinking `signature` 容忍缺失（P1/P2）—— **已实施**

> **来源**：生产事故。2026-09-17 22:14 web UI 报 `` `signature` is not set ``（日志见 8.31.1）。
> **本包两条缺陷一起做**（用户裁决「P1/P2 一起」）：P1 是**触发条件的根因**（模型与协议错配），
> P2 是**致命反应的根因**（错配之外，真实 anthropic 兼容端点也会踩）。单做 P2 ⇒ 模型仍走错协议；
> 单做 P1 ⇒ 兼容端点/relay 的 thinking 仍会把整轮 run 打死。
> **状态**：已实施并提交（`8.31.8`）。裁决点 ①/② 均按推荐执行。

#### 8.31.0 两条缺陷的一句话与判据

| # | 缺陷 | pi 的参照（判据＝行为相同） |
|---|---|---|
| **P1** | **适配器不跟模型走**。StreamFn 把**会话级** provider 名闭包死，`model.provider()` 被完全忽略 ⇒ 切到别的 provider 的模型，请求仍用旧适配器发出去 | `compat.ts:262`/`:287` `resolveApiProvider(model.api)` —— 模型自带 `api`，派发**跟着模型** |
| **P2** | `AnthropicMessagesApi` 对 thinking 的 `signature` 用**严格必填**访问器，而线上不保证该字段存在（它由后续 `signature_delta` 补、relay 还可能不给） | `anthropic-messages.ts:633` `thinkingSignature: event.content_block.signature ?? ""` |

#### 8.31.1 事故证据链（全链可回放）

1. **用户动作**：会话内把模型切到 `teamorouter/deepseek-v4-flash`、thinking 提到 `high`
   （会话树帧 `model_change{provider:"teamorouter",modelId:"deepseek-v4-flash"}`，2026-09-17 22:12:33）。
2. **第 1 个 `llm.request` 成功**：`trace-unknown-20260917-141431.jsonl` 行 4-5 —— 4200ms、
   `inputTokens=1781 outputTokens=153 stopReason=tool_use`。随后 bash 工具执行成功。
3. **第 2 个 `llm.request` 即死**：同行 14-15 —— 1586ms、**`inputTokens=0 outputTokens=0`、
   `stopReason:"error"`**；日志侧（`~/.pi-java/logs/pi-java.log:562`）
   `[ws->client] {"message":"`signature` is not set","type":"error"}`，
   且错误帧**之前一个内容帧都没有**（`persistPending`/`agent_start` 之后 1.57s 空白），
   收尾 `attempts=1 stopReason=error / tokens: in=1781 out=153`、`run end ... outcome=error`。
4. **报文的唯一来源**：Stainless 生成的 `com/anthropic/core/Values.kt:174`
   （`is JsonMissing -> throw AnthropicInvalidDataException("\`$name\` is not set")`）。
   **openai SDK 里连 `"signature"` 字面量都没有** ⇒ 见该报文 ⇔ 走了 anthropic 协议。
5. **pi-java 里唯一能触发它的两处**：`AnthropicMessagesApi.java:122`
   （`block.thinking().map(t -> t.signature())`，content_block_start）与 `:145`
   （`delta.asSignature().signature()`，signature_delta）。抛点落在
   `:168` 的 `catch (Exception e) → emitError`，故**任何日志都没有堆栈**
   （`AbstractChatApi:85` 的 `[ai] LLM stream failed` 从未触发）。
6. **矛盾的解释（= P1 的指纹）**：`llm.request` 跨度的 `"model"` 属性写的是
   `RunSpanFactory.modelLabel(ctx.model().get())`（`PiLaneSink:187`）＝**模型自己的**
   `provider/name`；而适配器由 `DefaultProviders.streamFnFor` 里的**会话级** provider 名决定
   （见 8.31.2）—— **标签是模型、适配器是另一个**，所以「trace 说 teamorouter/deepseek、
   报错却是 anthropic SDK 的」。

#### 8.31.2 P1 —— 适配器不跟模型走

**现状（四个事实合起来才致命）**

| 环节 | 位置 | 行为 |
|---|---|---|
| 会话级 provider 名 | `DefaultProviders.java:60` + `AgentSession.java:254` | `--provider` > `settings.defaultProvider` > `"google"`；本机 `settings.json` 是 `"anthropic"`，`web-ui.sh:24` 只传 `--mode web --port` ⇒ **`"anthropic"`** |
| 闭包 | `DefaultProviders.java:75-84` | `streamFnFor` 把该名字闭包进 lambda，**每个请求** `providers.get(providerName)`，**从不看 `model.provider()`** |
| 不可换 | `AgentSession.java:261`（建一次）→ `:355`（交给 harness）；`AgentHarness.java:43` `private final StreamFn streamFn` | 适配器在会话生命周期内**结构上不可替换** |
| 切模型 | `RpcDispatcher.java:136` → `AgentHarness.java:429 setModel` | 只换模型对象；`AgentSession:366` 的摘要生成器共用同一条 StreamFn ⇒ 压缩请求也不换 |

**pi 的参照**：`compat.ts:262`/`:287` 先 `getBuiltinProviderForModel(model)`，未命中则
`resolveApiProvider(model.api)` —— 派发键是**模型的 `api`**；凭据同样跟着模型
（`:225-231` `withEnvApiKey` → `getEnvApiKey(model.provider, ...)`）。会话的 default provider
在 pi 只决定**起手用哪个模型**，不决定此后每个请求的适配器。

**影响面（不止本事故）**：任何「模型的 provider ≠ 会话 default provider」的请求都打错适配器。
今日可达的两种：
① `models.json` 自定义 provider（团队路由器、opencode 一类）—— 本事故；
② 内建目录里**别的** provider 的模型（`DefaultModelResolver.java:113` 会照 `provider/model`
解析出 `ModelId(provider, name)`，目录由 `ModelsJsonConfig.allModels()` 提供 = 内建 ∪ models.json）。

**修法（最小面，签名零改动）**

```java
public static StreamFn streamFnFor(Args args, String defaultProvider,
                                   ProviderRegistry providers, Settings settings) {
    var fallback = resolveProviderName(args, defaultProvider);       // 仅作回退
    return (model, context, options) -> {
        var provider = providers.get(model.provider()).orElse(null);
        if (provider == null) {                                      // 见 8.31.7 裁决点 ①
            System.err.println("[provider] \"" + model.provider()
                + "\" is not registered; falling back to \"" + fallback + "\"");
            provider = providers.get(fallback).orElseThrow(
                () -> new IllegalStateException("Unknown provider: " + fallback));
        }
        return streamBlocking(provider, model, context, options,
            apiOptions(args, model.provider(), settings, Credentials::resolveApiKey));
    };
}
```

- **凭据跟着模型**：`apiOptions`（`:113`）的 provider 参数改为 `model.provider()`，
  链不变（CLI `--api-key` > `settings.defaultApiKey` > `Credentials.resolveApiKey(provider)`）——
  与 pi 的 `getEnvApiKey(model.provider)` 同形。
- **baseUrl 不动**：`settings.defaultBaseUrl` 仍是「内建 provider 指向 relay」的全局覆盖，
  且 `ModelsJsonProvider.java:39-47` 已把自己的 `baseUrl` **钉死**在 `createApi` 里
  （注释 :42-45 明写「models.json 的 baseUrl 必须赢过它」）⇒ 本事故里 teamorouter 的
  正确端点 `/v1` 已经是对的，**P1 不需要碰 apiOptions 的优先级**。
- **不动**：`ModelId`（record 只有 provider+name）、`ModelInfo`、catalog、`ApiOptions` 形状、
  StreamRequest、任何宿主层（TUI/RPC/web）代码。

#### 8.31.3 P2 —— thinking `signature` 用严格必填访问器

**现状**（`AnthropicMessagesApi.java`）：`:117-127` content_block_start 分支读
`block.thinking().map(t -> t.signature()).orElse("")`（读点在 `:121-122`）；`:145` signature_delta 分支读
`delta.asSignature().signature()`。**字段缺失即抛**，而：
- Anthropic 的 thinking 块 `signature` 由后续 `signature_delta` 补 —— 起点不带它是**合法**的；
- relay/兼容端点为非 Anthropic 模型合成 thinking 时，常常**整个流都不给** `signature`（本事故）；
- `pi-java` 自己的重放规则（`:282-299 appendThinkingBlock`：空签名降级为 text，降级点在 `:289-293`）**已经**承认
  空签名是正常状态 —— 读的时候却把它当必填，前后矛盾。

**pi 的参照（三处，都是容忍）**：`:631` `thinking: event.content_block.thinking ?? ""`；
`:633` `thinkingSignature: event.content_block.signature ?? ""`；
`:700-706` signature_delta **只改块、不 push 事件**；
`:1293-1320` 请求侧重放（`hasThinkingSignature` → thinking / 否则降级 text，与 pi-java `:282-299`
同规则）。

**修法**：改用 SDK 的**非抛异常面** —— `ThinkingBlock._signature(): JsonField<String>`
（`ThinkingBlock.kt:65`）与 `SignatureDelta._signature()`（`SignatureDelta.kt:54`），
两者都是 public、Java 可见（`javap` 已核）；`JsonField.asString(): Optional<String>`
对 `JsonMissing`/`JsonNull` 返回空（`getRequired` 才是抛的那条，且它是 Kotlin `internal`、
Java 侧连符号都被 mangling 成 `getRequired$anthropic_java_core`）。

```java
// :120-127
var initial = block.thinking()
        .map(t -> t._signature().asString().orElse("")).orElse("");
// :145
return builder.emitThinkingSignature(
        delta.asSignature()._signature().asString().orElse(""));
```

- **signature_delta 缺字段 ⇒ 不再追加**。pi 那边 `block.thinkingSignature += event.delta.signature`
  在 JS 里会拼出字面量 `"undefined"` —— 那是 pi 的事故（TS 类型谎报 required），**不复制**；
  本处以空串处理并在 javadoc 写明这处**故意与 pi 的字面行为不同**（真 Anthropic 不可达）。
- **空签名照旧降级**：`:123-125` 的 `if (!initial.isEmpty())` 守卫保留；`emitThinkingSignature("")`
  即使被调用也只是空追加（`StreamPartialBuilder:145-151`），不改块内容。

#### 8.31.4 本包**不做**、但登记（都有 `file:line` 证据）—— ⚠️ 行号为 2026-09-17 快照；R1/R2/R7 已由 §8.33 包① 实施（状态见 §8.33.9），本表保留实施前的证据原样

| # | 登记项 | 证据 | 为什么不在本包 |
|---|---|---|---|
| R1 | content_block_start 的**初始 thinking 文本被丢弃** | pi `:632`（分支 `:629`）收 `thinking ?? ""`；pi-java `:121` 只读 signature，`emitThinkingStart()`（`StreamPartialBuilder:121-128`）也不接受初始文本 | 要动 `StreamPartialBuilder` 的事件形状 ⇒ 另立包 |
| R2 | `redacted_thinking` **未处理** | pi `:638-647` 映射为 thinking（`"[Reasoning redacted]"` + `signature = data`）；pi-java 落到 text 分支（`:128-130`） | 新增块类型支持，与本包的两条缺陷不同面 |
| R3 | signature 会发一条 `ThinkingDelta` 事件 | pi `:700-706` **只改块、不 push**；pi-java `emitThinkingSignature` 返回 `ThinkingDelta(idx,"",snapshot)` | 改的是 `StreamEvent` 通道形状 ⇒ 需 L5 剧本先覆盖（§8.24 同口径：不钉没剧本的顺序/形状） |
| R4 | **per-model `api` 表达不出** | pi `types.ts` 的 `Model.api` 是派发键；pi-java `ModelInfo:27-37` 无该字段、`models.json` 的 `api` 在 **provider 级**（`ModelsJsonConfig:147-160`） | P1 做到「provider 级派发」即覆盖今日全部已注册 provider；单 provider 多 API（pi 的 fireworks/opencode）**今日无表达方式**，加字段是投机代码（同 §8.25.5 C1 口径） |
| R5 | **空签名重放策略不可配** | pi 有 `Model.compat.allowEmptySignature`（`types.ts:714`；`anthropic-messages.ts:1304` 三态；`generate-models.ts:2242-2253` 给 Kimi 系打开）；pi-java `ModelInfo` 无 `compat` ⇒ 恒降级 text（`:289-293`） | 需要 catalog/compat 字段 + models.json schema 扩展 ⇒ 另立包 |
| R6 | `ModelsJsonProvider` 钉死 baseUrl ⇒ **CLI `--base-url` 对它失效** | `ModelsJsonProvider.java:39-47`（`pinned` 无条件覆盖 `options.baseUrl()`），而注释 `:42-45` 声称「CLI --base-url 仍然适用」—— **注释与实现不符** | 本包不动 apiOptions 优先级；登记待裁决（要么改注释、要么让 CLI 赢） |
| R7 | **初始 signature 不在 `ThinkingStart.partial` 里** | `StreamPartialBuilder:121-128 emitThinkingStart()` 先 `blocks.add(ThinkingContent(""))` 再 `snapshot()` 返回，而 `emitThinkingSignature(initial)` 在**之后**才 `blocks.set(idx, …)`（`:145-151`）⇒ 事件自己的 partial 看不到初始 signature。pi `:630-635` 建块（含 `thinking`/`thinkingSignature`）、`:636` 入 `content`、`:637` 才 push `thinking_start` | 与 R1 同根（`emitThinkingStart` 不接受初始内容），但 R7 连**已经读到的** signature 也进不去首个 partial ⇒ 要动事件形状 |
| R8 | `emitThinkingSignature` **先于** `emitThinkingStart` ⇒ `IndexOutOfBoundsException` | `StreamPartialBuilder:145-151` 用 `Math.max(0, thinkingBlockIndex)`，`thinkingBlockIndex` 初始 -1 ⇒ 对**空** `blocks` 做 `set(0, …)`；同族的 `emitThinkingDelta`（`:131-137`）有惰性建块分支，`emitThinkingSignature` 没有 —— **两侧不对称** | 生产不可达（signature 恒跟在 content_block_start 之后）；改它要么加同样的惰性分支、要么钉死前置断言，属投机代码 |


#### 8.31.5 验证计划（RE 先行：先证明夹具会红）

| RE | 夹具 | 现状（**应红**） | 改后（应绿） |
|---|---|---|---|
| **RE-P1** | `DefaultProvidersTest`（同 package，已测 `apiOptions`）：`ProviderRegistry.create()` 注册两个**桩** provider（protocol 不同、各自记下被调用），`apiOptions` 走假 settings；调 `streamFnFor(args, "A", registry, settings)` 拿 StreamFn，**用 provider="B" 的 ModelId 调它** | 打进 A 的适配器 ⇒ 断言 B 被调用时**红** | 打进 B |
| **RE-P2** | 反射调 `AnthropicMessagesApi.mapEvent`（手法照 `AnthropicMessagesApiBuildParamsTest:29-36`），喂 `RawMessageStreamEvent.ofContentBlockStart(...)`，块为 `ContentBlock.ofThinking(ThinkingBlock.builder().thinking("x").build())`（**不给 signature** ⇒ `JsonMissing`） | 抛 `AnthropicInvalidDataException("`signature` is not set")` ⇒ 断言不抛时**红**；报文**逐字等于生产事故** | 返回 `ThinkingStart`、不抛 |
| RE-P2b | 同上，块**带** `signature("sig")` | 绿（现状也绿） | 绿（防回归：签名仍要进块，`partial` 里可见） |

**回归**：`mvn -o -pl pi-java-ai -am test`、`-pl pi-java-agent-core -am test`（带 `-am`，见 memory
`jdk25-mvn-am`）、`mvn -o clean verify` 全 reactor；**L5 差分必须真跑**（剧本用桩 StreamFn，
**预期不动**——但本项目已有**两次**「预期不动」被证伪的前科，故只报实测数字）。
基线（`docs/32:9`，提交时以 `git rev-list --left-right --count main...HEAD` 与实测为准）：
telemetry 31 / ai 336 / agent-core 450。
**不引入任何新依赖**（RE-P2 用反射，沿用仓库既有手法）。

#### 8.31.6 影响与风险

- **行为变更面**：仅「已注册 provider 且 `model.provider()` ≠ 会话 default provider」的请求
  —— 从**错误适配器**变为**正确适配器**。这正是判据要的（pi 就是这么派的）。
- **不新增硬失败**：未注册的 provider 走回退 + stderr 警告（裁决点 ①），与今日行为一致。
- **对用户当前配置的净效果**：`anthropic/claude-*`（经 relay）**不变**；
  `teamorouter/deepseek-v4-flash` 从「打 relay 的 anthropic 端点」变为
  「打 models.json 声明的 `openai-completions` 端点」—— 与 `models.json` 的声明一致，
  且 P2 之后即使仍走 anthropic 端点也不再打死整轮 run。
- **风险**：① 某个 provider 名在目录里有、注册表里无 ⇒ 回退路径被首次触发（今日不可见，
  警告可观测）；② 压缩摘要与主请求共用 StreamFn ⇒ 摘要也随模型换适配器（**与 pi 一致**，
  但属行为变更，需在 L5/实测里确认无回归）。

#### 8.31.7 待你裁决的两点

1. **未注册 provider 的兜底**：**推荐**「回退会话 provider + stderr 警告」（不新增硬失败，
   改动最外科）；备选「直接抛 `Unknown provider`」（更响，但会把今天能跑的路径变成报错）。
2. **R1/R2 是否顺手做**（初始 thinking 文本 + `redacted_thinking`）：**推荐不做**
   —— 它们要动 `StreamPartialBuilder` 的事件形状，与本包两条缺陷不同面，另立一包更干净。

#### 8.31.8 实施记录（已实施）

**提交**：设计 `f52df6b` → P2 `fb4866d`（`pi-java-ai`）→ P1 `922ef4c`（`pi-java-coding-agent`）。
提交后分支位置：`git rev-list --left-right --count main...HEAD` = **0 behind / 105 ahead**。

**RE 实测（先证红，再证绿）**

| RE | 首跑（**红**，修复前） | 改后 |
|---|---|---|
| RE-P1 | 恰 1 红：`streamFnRoutesByModelProviderNotSessionProvider:134` —— actual `["alpha"]`（会话 provider）vs expected `["beta"]`（模型 provider），**即缺陷本身**；同文件其余 8 个用例照旧绿 | 9/9 绿 |
| RE-P2 | 恰 2 红：`thinkingBlockWithoutSignatureStillStreams:122`、`signatureDeltaWithoutSignatureDoesNotKillTheStream:169`，报文**逐字等于生产事故**：``StreamError[reason=error, error=com.anthropic.errors.AnthropicInvalidDataException: `signature` is not set, partial=AssistantMessage[…]]`` | 4/4 绿 |
| RE-P2b | 绿（防回归，修复前后都该绿） | 绿 |

**夹具踩到的三个坑（已写进测试 javadoc，供后来者避开）**

1. **SDK 的 builder 自己就拦**：`ThinkingBlock.builder().thinking("x").build()` 抛的是
   「`` `signature` is required, but was not set ``」——**另一条**报文、来源是 builder 而非适配器
   ⇒ 从 builder 进**测不到**被修的那一行。夹具改为 `ObjectMappers.jsonMapper().readValue(...)`
   （线上的真实入口）。
2. `emitThinkingStart()` 的 partial 在 `emitThinkingSignature(initial)` **之前**快照 ⇒ 断言要落在
   `ThinkingEnd`/`ThinkingDelta` 的 partial 上（这是新登记 **R7**，见 `8.31.4`）。
3. 只喂 `signature_delta`、不给前置 `content_block_start` ⇒ `emitThinkingSignature` 对空 `blocks`
   做 `set(0, …)` 抛 `IndexOutOfBoundsException`（新登记 **R8**）。夹具改成**一条流共享一个
   builder + 一组块态数组**（照 `streamInternal` 的形状），而不是每个事件一个新 builder。

**回归实测**

- `mvn -o clean verify`（全 reactor，含 checkstyle + spotbugs）：**BUILD SUCCESS**，5 分 58 秒。
- 测试计数（实测）：telemetry **31** / ai **340** / agent-core **460** / session-sqlite 35 /
  coding-agent 220 / tui 188（1 skipped）/ protocol 14 / client 2 / server 2 / web 37 /
  evals 43（17 skipped）。
  - ai 相对 `8.31.5` 引的基线 336 **+4**，恰等于本包新增测试文件的 4 个用例 ✅。
  - agent-core 460 比台账基准 450 **多 10**：**与本包无关**（本包未触碰 agent-core 任何文件），
    来自台账写成之后落地的包（§8.30/B2 等）；**未逐项追平**，如实登记。
- **L5 差分（真跑，共 4 轮 14/14 绿）**：`clean verify` 内 1 轮 +
  `mvn -o -pl pi-java-agent-core -am -Dtest=ConformanceTest -Dsurefire.failIfNoSpecifiedTests=false test`
  独立 3 轮。剧本用桩 StreamFn ⇒ 「预期不动」这次**成立**（本项目两次前科，故报实测数字而非预期）。
- ⚠️ **不带 `-am` 的独立跑会假红（本次又撞一次）**：14/14 Error，
  `NoSuchMethodError: 'AssistantMessage AssistantMessage.withIdentity(String,String,String,Instant)'`
  —— agent-core 解析到 `~/.m2` 里的**旧 `pi-java-ai`**。这正是 memory `jdk25-mvn-am` 记的坑；
  **看到这条 `NoSuchMethodError` 先想 -am，不要当成回归**。

**裁决落实**

- 裁决点 ①：取推荐 —— 未注册 model provider ⇒ **回退会话 provider + stderr 一行警告**
  （`[provider] unknown model provider "…"; falling back to "…"`），不新增硬失败。
- 裁决点 ②：取推荐 —— **不做** R1/R2（初始 thinking 文本、`redacted_thinking`），另立包。

**本包**未**覆盖的（如实登记）**

- **未用 22:14 那次会话做端到端复现**：验证止于单元 + 差分两级；「用户再切一次
  `teamorouter/deepseek-v4-flash` 是否不再报错」**没有被实测过**。P1 的正确性证据是
  「适配器选择跟着模型」这一条被夹具钉住，而不是「事故会话重放通过」。
- **事故里「第 1 个请求成功、第 2 个请求死」的差异仍未证**（`8.31.1` 第 2/3 条）：
  当时 `--trace-payloads` 是**关**的，没有 request/response 载荷可比。下次复现需开
  `--trace-payloads` 才能定论（是「第 1 个响应本就带 signature、第 2 个不带」，还是别的形状差异）。


---

### 8.32 B 类功能缺口的补全路线（判定 + 包划分）—— **路线已批准**（用户 2026-09-17「按照顺序」⇒ ①→②→③→④→⑤；包① 已实施，见 §8.33；包② 已实施，见 §8.34；**包③ 已实施，见 §8.36.8**；④–⑥ 待逐包设计）

#### 8.32.0 这一节解决什么

B 类九条（台账 `docs/32 §3`）此前每条只有一行「修法素描」。2026-09-17 对九条各做了一次只读取证
（pi 源码逐字 + pi-java 现状 + 三个消费者的清点），本节给出**判定、包划分、依赖**，
以及**必须由用户拍板的裁决点**。每包在实施前另出详细设计 —— 包①见 §8.33。

本节**不含代码改动**，只是路线与裁决。

#### 8.32.1 判定表

| # | 条目 | 判定 | 归包 | 依据（本轮实测） |
|---|---|---|---|---|
| B1 | branch summary 无实现 | **阻塞于裁决** —— 范围**远超台账**：缺的不止是摘要函数，是**整棵同会话树导航** | 包⑥ | §8.31.4 只登记了「无实现」；实测 pi 侧 `agent-session.ts:3136-3167 navigateTree` / `:3226-3251` / `:3280-3300` 全无对应物 |
| B2 | compaction `details` 生产者 | **已结案** | — | `4380796`（§8.30.8） |
| B3 | 重试的宿主渲染 | **可做，但拆三块**：RPC（帧已对，只差状态字段）/ TUI（**结构性盲区：整模块零订阅**）/ web（需产品裁决） | 包④ / 包⑤ | 见 §8.32.2 第 7 条 |
| B4 | `addedToolNames` 的 provider 层消费者 | **结案为不做**（机制归属原判是错的） | — | 见 §8.32.2 第 6 条 |
| B5 | 宿主 `catch (Exception)` 不接 `Error` ⇒ 永久挂起 | **可做，两步**：① `catch (Throwable)` ＋ `finally` 幂等兜底 ② `handleRunFailure` 落引擎侧。**两步均已裁**（2026-09-19：①取「两处 Throwable＋finally 兜底」；②**做，落引擎侧**）| 包③ —— **已实施**（§8.36.8，`16ca4d7`/`f16436b`/`d136097`） | pi `agent.ts:484-525`；pi-java `SessionRunner`；引擎 `PiLaneEngine.drive` |
| B6 | 初始 thinking 文本被丢弃 | **可做** → **已做**（§8.33） | 包① | pi `anthropic-messages.ts:632` `thinking ?? ""` vs pi-java `AnthropicMessagesApi:128-129` 只读 `_signature()` |
| B7 | `redacted_thinking` 未处理 | **可做（SDK 路由已实证）** → **已做**（§8.33） | 包① | pi `:638-647`；SDK `ContentBlock.kt:549-553` |
| B8 | 空签名重放策略不可配（`compat.allowEmptySignature`） | **可做，但被 P2 前置**；**设计见 §8.34** | 包② | pi `types.ts:713-714`、`:193`(默认归一)/`:1047`(入参)/`:1227`(形参缺省)/`:1304`(**唯一行为点**)；⚠️ **更正：行为上只有两态**（`undefined ≡ false`，实测），不是三态；启用处为 Fireworks 全量 / Kimi Coding / Xiaomi(休眠)。pi-java `ModelInfo` 无 `compat`，且 `ModelsJsonSchema` `ignoreUnknown=true` **静默吞掉**用户写的 `compat` |
| B9 | 初始 signature 进不了 `ThinkingStart.partial` | **可做（与 B6 是同一处改动）** → **已做**（§8.33） | 包① | pi 先建块（`:630-635`）再 push（`:637`）；pi-java `StreamPartialBuilder:127` 先 `snapshot()` 返回、`AnthropicMessagesApi:131` 才改块且**返回值被丢弃** |

#### 8.32.2 本轮新挖到的八条（**均不在** §8.31.4 表内）

| # | 发现 | 证据 | 影响 |
|---|---|---|---|
| **P1** | thinking 的 signature **不落盘也不回读** | pi-java `SessionJson.java:129-132` 只写 `type`+`text`；`MessageJsonCodec.java:142` 只读 `text` 且走 1 参构造器 ⇒ 签名恒 `""` | **B7/B8 的忠实度在 resume 后失效**；redacted 载荷丢失后无法回升 |
| **P2** | pi 的 `transform-messages.ts` **整段缺失** | pi `packages/ai/src/api/transform-messages.ts:95-116`（`isSameModel = provider && api && model.id` 在 `:95-98`；redacted 跨模型丢 `:104-105`、无签名跨模型降级 text `:112-116`）；pi-java 全仓零命中 | **B7/B8 的前置**：不加此闸，B8 会让跨模型重放**比今天更错** |
| **P3** | 空 text 块不丢 | pi `anthropic-messages.ts:1282` `if (block.text.trim().length === 0) continue;`（在**助手内容循环** `:1280` 内）vs pi-java `AnthropicMessagesApi.java:284-286` 无条件 `ofText` | B7 修好后 redacted 若走错分支留下的空块**会被原样发给 Anthropic** |
| **P4** | pi-messages 车道同样丢 signature/redacted | `PiMessagesEvent.ThinkingEnd(int,String,String,boolean)` 已在 `:43` **声明**，而 `PiMessagesApi:93` 调无参 `emitThinkingEnd()`；pi `pi-messages.ts:60-64` + `:236-240` 是真装配的 | 「只修 Anthropic 适配器」覆盖不到 signature 语义 |
| **P5** | §8.31.4 的 pi 行号**整体错位 1 行** | 实测 R1 = `:632`（文档写 `:631`）、R2 = `:638-647`（写 `:637-645`）、R7 = 建块 `:630-635`/入 content `:636`/push `:637` | 文档更正，搭本轮车 |
| **P6** | **落盘 thinking 块的字段名与 pi 不同**：pi-java 写 `{"type":"thinking","text":…}`，pi 写 `{"type":"thinking","thinking":…}` | pi `session-manager.ts:1030-1056` 是 `JSON.stringify(entry)` 原样落盘，块形状即 `types.ts:357-365`（`thinking` / `thinkingSignature?` / `redacted?`）；pi-java `SessionJson.blockNode:129-132` 写 `text`、`MessageJsonCodec:142` 读 `text` | ① `SessionJson` 类注释自称「shape matches pi **byte-for-byte**」，**与实现不符**；② pi-java **解码不了 pi 写的会话文件**（`requireString(node,"text")` 抛 schema 错）；③ 同仓内 `FrameNormalizer.java:224` 用的却是 pi 形状 `{"type":"thinking","thinking":…}` —— **两处口径不一致** |
| **P7** | `auto_retry_end` 成功路多写一个 `"finalError":null` 键 | pi `rpc-mode.ts`/`json-event.ts:48-51` 透传 ⇒ `undefined` 被 `JSON.stringify` **省略**；pi-java `JsonEventMapper.java:94-99` 无条件 `put`。同文件 `:110-113` 对 `SummarizationRetryAttemptStart.reason` **已做**「null ⇒ 省略」 | 线格式 1 键差异（包④） |
| **P8** | **L5 两侧的 scripted stream 对 thinking 块不对称**：pi 侧 `ScriptedStream` 的 `queueMicrotask` 循环**只**处理 `text` / `toolCall` ⇒ thinking 块**不推任何** `thinking_start/delta/end`；Java 侧 `ScriptedStreams.eventsFor` 的 `case "thinking"` **推** `ThinkingStart`+`ThinkingEnd`（经 `PiLoopRunner.isUpdateEvent:335-343` 变成两条 `message_update` 帧） | pi `conformance/pi/run.test.ts` 的 `ScriptedStream` 推事件循环；pi-java `ScriptedStreams.java:73-79` | **裁决点 E 的前提被证伪**（见 §8.33.9）：任何声明 thinking 块的剧本都会**因夹具不对称**而红（Java 每块多两帧），**与生产行为无关**。谁先加 thinking 剧本谁先撞红 —— 必须先修 pi 侧孪生。登记不改（今日 14 个剧本无一声明 thinking） |

> P5 的更正**已在本节就地完成**（下文引用的行号一律为实测值）。§8.31.4 原表的行号更正**已随包① 就地完成**（R1 `:632`、R2 `:638-647`、R7 `:630-635`/`:636`/`:637`）；该表记录的是**实施前**的证据，故不加「已修」字样，只在其标题行注明状态。

#### 8.32.3 包划分与依赖

| 包 | 内容 | 面 | 依赖 | 改动是否动 L5 帧 |
|---|---|---|---|---|
| **①** | thinking 块的**采集 → 落盘 → 回读**：B6 + B7 + B9 + P1 + P4 + P6 | `ai`（采集）+ `agent-core`（落盘） | 无 | **B6/B7 可被新剧本测到**（`FrameNormalizer:223-224` 保留 `type` 与 `text`）；signature/redacted **两侧都被归一化抹掉** ⇒ 不可测 |
| **②** | thinking/text 的**请求侧重放规则**：P2 + P3 + B8（**并新登记 B13**：redacted 落线） | `ai`（+ `coding-agent` 配置链） | **① 已完成**（§8.33；B7 引入的 `redacted` 位正是 P2 的输入） | 无 thinking 剧本 ⇒ 不动 |
| **③** | 宿主失败通路：B5（**设计见 §8.36，待审**；两步均已裁：第 1 步＝活性收口（两处 `Throwable`＋`finally`），第 2 步＝`handleRunFailure` **落引擎侧**） | `agent-core`（+ `coding-agent` 宿主） | 无 | 无（会话层事件不在 L5 帧内） |
| **④** | RPC 重试面：B3-块1 + P7 | `coding-agent/rpc` | 无 | 无 |
| **⑤** | TUI 重试面：B3-块2（含**结构改动**：TUI 长出一条会话事件订阅通道） | `tui` | 无（与 ④ 同源但互不依赖） | 无 |
| **⑥** | branch summary：B1（**待裁决**） | `agent-core` + `coding-agent` | 无 | 无（结构上覆盖不到） |

**冲突点**：包①的 B7 重放分支与包②的 B8 策略分支**落在同一个 `appendThinkingBlock`**（`AnthropicMessagesApi:311-328`，唯一私有调用点 `:288`）。
故包①**不在该处加任何重放分支**（落盘/回读只碰 `SessionJson`/`MessageJsonCodec`），
把「redacted 回升 / 同模型判断 / 三态」整段留给包②一次成形 —— 避免同一方法被改两次。

#### 8.32.4 B4 为什么结案为不做（**修正原登记**）

原登记（`docs/31:1059-1061`、`docs/32 §3 B 表` 的 B4 行，现 `docs/32:94`）写的是「`addedToolNames` 的 provider 层消费者 …
对应 pi 的 **native deferred tools**，pi-java 今日无对应物（**Phase 2c MCP**）」。**这话两头都错**：

1. **机制归属错**：`addedToolNames` 不是 MCP —— **pi 根本没有 MCP**。它是**扩展系统**的产物：
   `packages/coding-agent/src/core/extensions/wrapper.ts:17-37` 在 `execute` 前后快照活跃工具集，
   差值即 added names；由 `packages/ai/src/utils/deferred-tools.ts:8-39 splitDeferredTools` 消费。
2. **「无对应物」的推论错**：`deferred-tools.ts:15` 在 `enabled=false` 时**原样返回全部工具** ——
   与 pi-java 今天的「全量送」**逐字等价**。而 pi 侧 `openai-completions.ts:838-840` 的那层过滤
   有闸门：`compat.deferredToolsMode === "kimi"` 才生效；pi-java 因 `ModelInfo` 无 `compat`
   **结构上进入不了该模式** ⇒ **今天不可达、零行为差异**。

**结案为不做**，触发条件（任一成立再评估）：① 扩展系统落地且真产出 `addedToolNames`；
② `Model.compat` 落地（并入包②）后被配成 `deferredToolsMode:"kimi"`。
届时**并入包②**重评，不新开包。

#### 8.32.5 与 L5 差分的关系（覆盖面边界，必须写进后续每包）

L5 剧本（`conformance/scripts/S*.json`）是**帧级**且**直接驱动 `PiLoop`**（Java 侧
`ConformanceRunner.java:44-76`；pi 侧 `conformance/pi/run.test.ts:1-45` 驱动 `agentLoop`）。
由此，以下四类**结构上不可能**被 L5 覆盖，只能靠定点用例：

1. **全部会话层事件**（`auto_retry_*` / `summarization_retry_*` / `agent_settled`）—— 帧里没有会话事件这一层（包③④⑤）。
2. **剧本模型表达不了的东西**：`ConformanceScript.java` 的字段无 retry 设置、无会话事件（包④）。
3. **一切宿主渲染**：TUI 文本/状态栏、RPC `get_state`、web 帧、print-mode JSON（包⑤）。
4. **被两侧归一化抹掉的字段**：thinking 的 `signature`/`redacted`（`FrameNormalizer.java:117-136`）⇒ **包①的 P1/P4/P6 不可测**。

#### 8.32.6 需要用户裁决的点（本路线层面的）

| # | 问题 | 推荐 |
|---|---|---|
| A | 包①②…的实施**顺序**：是否按 ①→②→③→④→⑤ 推进？ | 按编号；①②是同族且②依赖① |
| B | **B1（包⑥）是否在本轮做**？其范围是整棵同会话树导航，不是「补一个函数」 | 建议**暂缓**，先做完 ①–⑤（B1 需先有一份独立设计） |
| C | **B5 第 2 步**（`handleRunFailure` 落引擎侧）是否做？它会打开一条 pi-java **从未有过**的「从异常重试」入口，且可能让宿主把失败记成 `completed` 而非 `error` | ~~建议**先只做第 1 步**~~ ⇒ **2026-09-19 用户已裁：两步都做，第 2 步落引擎侧**。裁决后核到底的结论（§8.36.5）：**两条「风险」都站不住或必须一并解决** —— 「从异常重试」是 pi 的**既有**行为（合成消息会进 `agent-session.ts:1123` 的重试判定，挡掉它反而是分家）；「记成 completed」是真风险，由「宿主读尾 assistant」在同一包解掉 |
| D | **B3-块3（web）**走哪条：(i) 最小（`AgentEnd` 不再清 `streaming`，改由 `AgentSettled` 清）还是 (ii) 完整（新增前端 `retry` 词汇）？ | 建议 (i)；(ii) 属产品改动，另立 |

---

### 8.33 包①：thinking 块的采集、落盘与回读（B6+B7+B9+P1+P4+P6）—— **已实施**（2026-09-18，实施记录见 §8.33.9）

#### 8.33.0 范围与不变量

**做**：让一个 thinking 块从**进流**到**落盘**到**回读**的全程带上 `signature` 与 `redacted`，
并把 Anthropic 在 `content_block_start` 里预置的文本/签名**如实收下**。

**不变量（本包承诺不动）**：

- `StreamEvent.ThinkingStart/ThinkingDelta/ThinkingEnd` 三条 record 的**形状零改动**
  （`StreamEvent.java:119/127/134`）—— 变的只是它们 `partial()` 里那一块的**内容**。
- **不碰** `AnthropicMessagesApi.appendThinkingBlock`（`:311-328`）—— 重放规则整段留给包②（§8.32.3 冲突点）。
- **不碰** `emitThinkingSignature` 的 `signature_delta` 现役路径（`:157`）—— 台账 C8 的裁决不变。

#### 8.33.1 事实基线（逐字）

**pi 采集侧**（`packages/ai/src/api/anthropic-messages.ts`）：
`:629-637` thinking 分支 —— `thinking: …thinking ?? ""`、`thinkingSignature: …signature ?? ""`，
**先** `output.content.push(block)`（`:636`）**再** push `thinking_start`（`:637`，`partial: output`）；
`:638-647` redacted 分支 —— `thinking: "[Reasoning redacted]"`、`thinkingSignature: …data`、`redacted: true`，同样先入后退。
`:700-706` `signature_delta` **只改块、不 push 事件**；`:720-726` `thinking_end` 带 `content: block.thinking`。

**SDK 路由已实证**（本包的唯一真不确定点，已验穿）：
`anthropic-java-core-2.52.0-sources.jar` 的 `ContentBlock.kt:489-560` 是**手写反序列化器**，
`"redacted_thinking" -> ContentBlock(redactedThinking = it, _json = json)`（`:549-553`）
⇒ `isRedactedThinking()` 会命中。**兜底要当心**：`?: ContentBlock(_json = json)` 意为
`tryDeserialize` 失败时**四个变体全为 null** ⇒ 会落到 `emitTextStart()`。
`RedactedThinkingBlock.data()` 是 `getRequired`（`:39`），`_data()` 是 `JsonField`（`:57`）；
`ThinkingBlock._thinking()`（`:72`）/`_signature()`（`:65`）同为非抛异常面。

**落盘侧**：pi 原样 `JSON.stringify(entry)`（`session-manager.ts:1030-1056`），块形状见 `types.ts:357-365`。
pi-java 现在是 `{"type":"thinking","text":…}`（P6）。

#### 8.33.2 改动 A：采集侧（`pi-java-ai`）

**A1. `ContentBlock.ThinkingContent` 加第四个组件 `redacted`**（`ContentBlock.java:41-51`）：

```java
record ThinkingContent(String text, String signature, boolean redacted) implements ContentBlock {
    public ThinkingContent { signature = signature == null ? "" : signature; }
    /** 2 参便利构造器：16 处既有调用点零改动。 */
    public ThinkingContent(String text, String signature) { this(text, signature, false); }
    /** 1 参便利构造器（保留）。 */
    public ThinkingContent(String text) { this(text, "", false); }
}
```

**为什么必须有这个位**：redacted 块必须**按原样**回升成 `{type:"redacted_thinking", data}`；
没有这个位，回放只能发 `{type:"thinking", thinking:"[Reasoning redacted]", signature:<opaque>}` —— 上游会拒。
（回放本身在包②。）

**A2. `StreamPartialBuilder.emitThinkingStart` 长出带初值的重载**（`:121-128`）：

```java
public StreamEvent.ThinkingStart emitThinkingStart() { return emitThinkingStart("", "", false); }

public StreamEvent.ThinkingStart emitThinkingStart(
        String initialText, String initialSignature, boolean redacted) {
    var text = initialText == null ? "" : initialText;
    var sig  = initialSignature == null ? "" : initialSignature;
    thinkingBuf.setLength(0);
    thinkingBuf.append(text);          // ⚠️ 必须 seed 缓冲，不能只写块
    thinkingSigBuf.setLength(0);
    thinkingSigBuf.append(sig);
    thinkingBlockIndex = blocks.size();
    blocks.add(new ContentBlock.ThinkingContent(text, sig, redacted));
    int idx = nextContentIndex++;
    return new StreamEvent.ThinkingStart(idx, snapshot());
}
```

⚠️ **seed 缓冲是承重的**：`emitThinkingDelta`（`:139-140`）用 `thinkingBuf.toString()` **覆盖**块 ——
只写块不写缓冲，初始文本会被**第一个 delta 冲掉**（夹具 §8.33.6 的 B6-2 正是钉这条）。
四处生产调用点（`AnthropicMessagesApi:120`、`GoogleGenerativeAiApi:115`、`PiMessagesApi:89`、
`ResponsesStreamProcessor:130`）经无参重载**零改动**。

**A3. `AnthropicMessagesApi` 的 `content_block_start`**（`:117-134` 整段替换）：

```java
if (block.isRedactedThinking()) {                    // 必须排在 isThinking() 之前
    isToolBlock[0] = false;
    isThinkingBlock[0] = true;
    var rb = block.redactedThinking().orElseThrow();
    // pi 是 `thinkingSignature: event.content_block.data`（:642）直取 —— TS 类型谎报 required，
    // 缺字段会拼出字面量 "undefined"。此处**故意不复刻**，用非抛异常的 _data()
    // （与 §8.31 对 signature 的口径一致）。
    return builder.emitThinkingStart("[Reasoning redacted]",
            rb._data().asString().orElse(""), true);
}
if (block.isThinking()) {
    isToolBlock[0] = false;
    isThinkingBlock[0] = true;
    var tb = block.thinking().orElseThrow();
    return builder.emitThinkingStart(
            tb._thinking().asString().orElse(""),
            tb._signature().asString().orElse(""),
            false);
}
```

⇒ **`emitThinkingSignature(initial)` 那三行（`:128-132`）删除**（这是 B9）：
初始签名改为**随首个 `ThinkingStart.partial` 一起投影**，与 pi 的「先入块、后 push」同序。
`emitThinkingSignature` 此后只剩 `signature_delta` 一个生产调用点（`:157`）。

**A4. `PiMessagesApi:89/92-93` 补上 `thinking_end` 的载荷（P4）**：

```java
var end = builder.emitThinkingEnd();
var th = thinkingBlockAt(end.partial(), end.contentIndex());
publisher.submit(new PiMessagesEvent.ThinkingEnd(
        end.contentIndex(), end.thinking(), th.signature(), th.redacted()));
```

（`PiMessagesEvent.ThinkingEnd` 的四个组件**早已在 `:43` 声明**，只是从没被填过；
pi 侧形状见 `pi-messages.ts:57`（`thinking_start` **不带**签名 ⇒ 本车道无 B9 问题）
与 `:60-64` / `:236-240`。）

#### 8.33.3 改动 B：落盘与回读（`pi-java-agent-core`）—— **含裁决点 C**

**B1. `SessionJson.blockNode` 的 thinking 分支**（`:129-132`）：

```java
case ContentBlock.ThinkingContent t -> {
    node.put("type", "thinking");
    node.put("thinking", t.text());                                        // ← 裁决点 C
    if (!t.signature().isEmpty()) node.put("thinkingSignature", t.signature());
    if (t.redacted()) node.put("redacted", true);
}
```

**B2. `MessageJsonCodec.decodeBlock` 的 `"thinking"` 分支**（`:142`）改为**容忍两种键**：

```java
case "thinking" -> new ContentBlock.ThinkingContent(
        readStringEither(node, "thinking", "text"),          // 新键优先，旧键兜底
        optionalString(node, "thinkingSignature", ""),
        optionalBoolean(node, "redacted", false));
```

（旧键兜底是**必需的**：用户 `~/.pi-java` 下已有按 `text` 落盘的会话文件。）

**B3. 同步改 SQLite 载荷编解码**：`SessionJson` 自述被「JSONL codec **和** SQLite payload codec」共用
（类注释 `:22-27`）⇒ 改一处即两处；但需**实测确认** SQLite 侧没有第二份手写块编解码。

**B4. 文档**：若裁决点 C 取「对齐 pi」，`docs/03` 的 JSONL v4 格式节需同步（本轮 grep 未见该节写过 thinking 块形状，实施时按现状补写）。

#### 8.33.4 裁决点（包①的）

| # | 问题 | 选项 | 推荐 |
|---|---|---|---|
| **C** | 落盘 thinking 块的**文本字段名**：保持 `text` 还是改 pi 的 `thinking`？ | (a) 保持 `text`，只新增 `thinkingSignature`/`redacted` 两键<br>(b) 改 `thinking` + 旧键兜底读 | **(b)** —— `SessionJson` 类注释自称 byte-for-byte（`:22-27`），`FrameNormalizer:224` 已用 pi 形状，而 (a) 会把既存错误**固化进新字段**；兜底读（B2）让旧文件照常可读，代价只有一处 if |
| **D** | redacted 的 `data` 缺失时怎么办？ | (a) 照 pi 的字面行为拼 `"undefined"`<br>(b) `_data()` 容忍 ⇒ 空串 | **(b)**，并在 javadoc 写明**故意不复刻**（同 §8.31 对 `signature_delta` 的既有口径） |
| **E** | 是否新增 L5 剧本覆盖 B6/B7？（S15：一段 `content_block_start` 带非空 `thinking` + 一段 `redacted_thinking`） | 做 / 不做 | ~~**做**~~ → **不做**。**原推荐（「这是 B6/B7 唯一能被差分钉住的路径」）在实施时被证伪**，两条独立理由见 §8.33.9-E：① L5 用**桩 Stream**驱动 `PiLoop`，`content_block_start` 的解析与落盘**都不在它的路径上**，剧本零牙；② 两侧 scripted stream 对 thinking 块本身不对称（P8）⇒ 剧本会**因夹具**而红 |

#### 8.33.5 RE 夹具（**先红，不红不许改生产代码**）

| 条目 | 夹具 | 今天**应红** | 改后 |
|---|---|---|---|
| B6-1 | 扩 `AnthropicMessagesApiThinkingSignatureTest`：`feedJson` 一条 `{"type":"thinking","thinking":"pre","signature":"sig"}`，断言 `((ThinkingStart) e).partial()` 里 thinking 块的 `text()=="pre"` | 红（`""`） | 绿 |
| B6-2 | 同流继续喂 `thinking_delta("post")` + `stop()`，断言 `ThinkingEnd.partial` 的 `text()=="prepost"` | 红 | 绿 —— **这条才钉住「缓冲必须被 seed」** |
| B7-1 | 先**单独**断言 `ObjectMappers.jsonMapper().readValue("{\"type\":\"redacted_thinking\",\"data\":\"x\"}", ContentBlock.class).isRedactedThinking()` 为真（SDK 路由的活凭据，非 javap 推断） | 绿（预期；若红则 B7 修法作废，见 §8.33.7） | 绿 |
| B7-2 | `feedJson` 一条 redacted 块，断言返回 `ThinkingStart`、`text()=="[Reasoning redacted]"`、`signature()=="opaque"`、`redacted()==true`；再 `stop()` 断言 partial 里**没有**空 `TextContent` | 红（今天返回 `TextStart`） | 绿 |
| B9-1 | 同 B6-1 的流，断言 **`ThinkingStart.partial` 里** `signature()=="sig"` | 红（`""`） | 绿 |
| P1-1 | `JsonlSessionStorageTest` 加往返：落一个带 signature + redacted 的 assistant 消息，读回断言两者都在 | 红 | 绿 |
| P6-1 | 同一往返用例里断言**落盘 JSON 的键名**是 `thinking`/`thinkingSignature`/`redacted`（裁决点 C 取 (b) 时） | 红 | 绿 |
| P6-2 | 反向兼容：喂一段**旧键** `{"type":"thinking","text":"old"}` 的 JSONL，断言能解码 | 绿（今天就是旧键） | 绿（兜底不许破） |
| P4-1 | `PiMessagesApiTest`：喂一条带签名的 thinking 流，断言 `PiMessagesEvent.ThinkingEnd.contentSignature()` 非空 | 红（恒 `""`） | 绿 |

**夹具纪律（照抄既有 javadoc，不得违反）**：`AnthropicMessagesApiThinkingSignatureTest:36-41` ——
**只从 JSON 反序列化进**，因为 `ThinkingBlock.builder()` 自己会抛另一条报文（`` `signature` is required ``），
从而**测不到适配器那一行**。`feedJson` 的 `instanceof` 白名单（`:79-91`）**需补 `RedactedThinkingBlock` 分支**，
否则抛 `IllegalArgumentException("夹具不认识的事件类型")`。

**回归门**：`thinkingBlockWithSignatureKeepsIt`、`signatureDeltaWithSignatureLandsInPartial` 必须**保持绿**。

#### 8.33.6 不做 / 未覆盖（如实登记）

- **不做重放侧**：`appendThinkingBlock` 一行不改（冲突点，§8.32.3）—— 本包落盘的 `signature`/`redacted`
  在**下一次请求**里仍走今天的老规则，直到包②。**这是本包的已知不完全**，必须写进实施记录。
- **不新增 L5 剧本之外的差分手段**：`signature`/`redacted` 被两侧归一化抹掉（`FrameNormalizer:117-136`），
  **L5 永远测不到它们** —— 只能靠上表的定点用例。
- **不动** `emitThinkingSignature` 的 `signature_delta` 路径（C8 裁决不变）。
- **不查** 块索引交错（B9 的证伪点 4）：pi-java 的 `contentIndex` 是自己的顺序计数器
  （`StreamPartialBuilder:126`），与 pi 的 `findIndex(b => b.index === event.index)` 不同源。
  本包**不动索引**，单列登记。

#### 8.33.7 证伪点（实施时按此复核）

1. **`ContentBlock.ThinkingContent` 加 boolean 组件后，Jackson 反序列化会不会因缺字段而失败？**
   `ContentBlock` 带 `@JsonTypeInfo`/`@JsonSubTypes`（`:16-25`）。若某条链路用 Jackson 读块，
   缺 `redacted` 的旧 JSON 可能报错 ⇒ **RE 第 P6-2 条必须覆盖**（喂缺 `redacted` 的旧 JSON）。
2. **B7 的兜底风险**：`tryDeserialize` 失败 ⇒ 四变体全 null ⇒ 静默落到 `emitTextStart`。
   夹具必须**显式**断言 `isRedactedThinking()`（RE B7-1），不能只看端到端结果。
3. **`block.redactedThinking()` / `block.thinking()` 的正确访问器名**：实测 `ContentBlock.kt` 有
   `redactedThinking(): Optional<…>`（`:107`）与 `isRedactedThinking()`（`:138`）。`thinking()` 同理。
   实施时以 javap 为准。
4. **「L5 不动」不许写成预测**：本项目已有两次「预期不动被证伪」的前科 ⇒ 必须**真跑 L5 并报实测数字**。

#### 8.33.8 验收

1. 上表每条夹具**先红后绿**，红灯数在实施记录里逐个报（不许只报「都绿了」）。
2. `mvn -o clean verify` 全 reactor 零错误、checkstyle/spotbugs 零违规。
3. L5 差分**真跑** ≥3 轮并报实测（14/14 或新增 S15 后的 15/15）。
4. `docs/32` 补行（B6/B7/B8/B9 状态、P1–P7 新登记、B4 改为「结案为不做 + 触发条件」）；
   `docs/31:1059-1061` 与 `docs/31 §8.31.4` 的行号按 §8.32.2-P5 就地更正。

#### 8.33.9 实施记录（2026-09-18）

**结论：A/B 两处改动全部落地；验收 4 条逐条对上，另有 2 处务必读的更正与 1 条新增登记。**

**A. 采集侧（`pi-java-ai`）—— 逐条红灯**

先给**夹具清单**（方法名 + 现文件行号），下表与它一一对应；设计文档里用的 B6-1/B7-2 只是**条目号**，
不是测试方法名 —— 一条测试里可能塞了两条断言（下面各自注明）。

| 夹具（方法名） | 位置 | 覆盖 |
|---|---|---|
| `initialThinkingTextLandsInFirstPartialAndSurvivesDeltas` | `AnthropicMessagesApiThinkingSignatureTest:210` | **B6**（两条断言：首个 partial 的文本 + 经 delta 后 `prepost`） |
| `initialThinkingSignatureLandsInFirstPartial` | 同上 `:238` | **B9** |
| `redactedThinkingBlockStartsAsThinkingWithOpaqueSignature` | 同上 `:265` | **B7**（两条断言：SDK 路由凭据 + 修复后形状） |
| `thinkingEndCarriesSignatureAndRedactedIntoPartial` | `PiMessagesApiTest:107` | **P4** |
| `thinkingSignatureAndRedactedSurviveJsonlRoundTrip` | `JsonlSessionStorageTest:58` | **P1 + P6**（`agent-core`，见 B） |
| `legacyThinkingTextKeyStillDecodes` | 同上 `:89` | **P6 反向兼容**（`agent-core`，见 B） |
| `ContentBlockJsonTest`（新文件，2 条） | `pi-java-ai/.../message/` | 证伪点 1，**回归门**（见 D1） |

**实施前的实际红（`AnthropicMessagesApiThinkingSignatureTest`，新增 3 条）**：

| 夹具 | 实施前的实际红 | 现状 |
|---|---|---|
| B6 `initialThinkingTextLandsInFirstPartialAndSurvivesDeltas:210` | ✗ `expected: "pre" but was: ""` —— **修复前 pi-java 整个没读 `event.content_block.thinking`** | ✓ |
| B9 `initialThinkingSignatureLandsInFirstPartial:238` | ✗ `expected: "sig" but was: ""` —— 初始签名被 `emitThinkingSignature(initial)` 的**返回值直接丢弃、从未 submit** | ✓ |
| B7 `redactedThinkingBlockStartsAsThinkingWithOpaqueSignature:265` | ✗ —— 第 1 条断言（SDK 路由）**是绿的**，第 2 条炸：旧行为返回 `TextStart` | ✓ |

> **B7 的两条断言要分开读**：前半段是 **SDK 反序列化路由的活凭据**（`ContentBlock.kt:549-553` 手写
> 反序列化器把 `"redacted_thinking"` 路由到 `redactedThinking` 变体）—— 它**实测为真**
> ⇒ **B7 的修法不作废**（证伪点 2 关闭）。后半段钉形状：`text=="[Reasoning redacted]"`、
> `signature=="opaque"`、`redacted==true`，且 `content()` 里**不许留空 `TextContent`**。
> 这两半在**同一条测试**里，所以「路由断言通过」这件事在红灯报告里看得见、但没有独立成条。

**P4 的红是补做的**（`PiMessagesApiTest:107`，新增 1 条）：

| 夹具 | 实施前的实际红 | 现状 |
|---|---|---|
| P4 `thinkingEndCarriesSignatureAndRedactedIntoPartial:107` | ✗ `expected: "sig-9" but was: ""` | ✓ |

> ⚠️ **顺序颠倒**：这条夹具最初写在生产修复**之后**（违反 RE 先行）⇒ 用
> `git stash push -- pi-java-ai/.../PiMessagesApi.java` 还原生产代码、跑出上面那条红、再
> `git stash pop`。如实记在这里，不粉饰。

> **过程注（不是夹具）**：B6/B9 最初合写在**一条**测试里，B6 先炸 ⇒ B9 的红看不见，后拆成两条
> ——与下面 B 的两处「被挡住」是同一个坑。

**B. 落盘侧（`pi-java-agent-core`）—— 逐条红灯（`JsonlSessionStorageTest`，新增 2 条）**

一次运行 `Tests run: 11, Failures: 1, Errors: 1`（该类 9 → 11）：

| 夹具 | 实施前的实际红 | 现状 |
|---|---|---|
| `thinkingSignatureAndRedactedSurviveJsonlRoundTrip:58` | ✗ **Failure** 在 `:67` —— `assertThat(raw)` 的键名包含断言不成立（落盘仍是 `type`+`text`） | ✓ |
| `legacyThinkingTextKeyStillDecodes:89` | ✗ **Error** 在 `:97` —— 新键那半 `MessageJsonCodec.decodeBlock` 抛错（旧解码器只认 `text`） | ✓ |

> ⚠️ **两条红各自「挡住」了一半**，必须如实报：
> ① 往返夹具**键名断言在前**（`:67`，`P6` 的键名），**签名回读断言在 `:79`**（`P1`）
> —— 所以 **P1 的「回读」红从未被独立观察到**：修完键名之后才走到 `:79` 并通过，**没有单独的红灯记录**。
> ② `legacyThinkingTextKeyStillDecodes` 一条用例里放了**旧键**（`:91`）与**新键**（`:97`）两半，
> **新键那半先抛** ⇒ **旧键那半（P6 反向兼容，设计要求「保持绿」）也没被独立观察到**。
> 这两处与「一条夹具里塞两个断言 ⇒ 后一个的红看不见」是同一个坑，**本包第二次踩**（第一次在 B6/B9）。

**C. 裁决点 C/D/E 与实施时的三个实测更正**

- **裁决点 C 取 (b)**（改 pi 的 `thinking` + 旧键兜底读）：照设计。
- **裁决点 D 取 (b)**（`_data()` 容忍 ⇒ 空串）：照设计，javadoc 已写明**故意不复刻** pi 的字面 `"undefined"`。
- **裁决点 E 由「做」改为「不做」** —— **原推荐被证伪**，两条独立理由：
  1. **前提错**：E 的理由是「B6/B7 唯一能被差分钉住的路径」，但 L5 是用**桩 Stream** 驱动 `PiLoop`
     （pi 侧 `run.test.ts` 的 `ScriptedStream`、java 侧 `ScriptedStreams.eventsFor` 手搓 `StreamEvent`），
     **`content_block_start` 的解析与落盘都不在 L5 的路径上** ⇒ 剧本对 B6/B7/P1/P6 **零牙**。
  2. **夹具不对称**：pi 侧推事件循环**只**处理 `text`/`toolCall`（thinking 块零 `thinking_*` 事件），
     Java 侧 `case "thinking"` 推 `ThinkingStart`+`ThinkingEnd` ⇒ 剧本会**因夹具**而红。**已立为 P8 / E8。**
  ⇒ 本包**不新增剧本**；L5 报的是既有 14 条的实测（见 F）。
- **新增发现 P8**（见 §8.32.2 末行）：即上面第 2 条。
- **`docs/03 §5.2` 的处置与原设计不同**：该节**根本没写过 thinking 块的形状**（grep 已证），
  且整节（header / entry / record / lane / fact 五行）**都与实现脱节** ⇒ 改为就地加一条 ⚠️ 勘误指向
  「以 `JsonlCodec`/`EntryJsonCodec`/`SessionJson` 为准」，不逐项重写草图。
- **`ContentBlock.ThinkingContent` 加第四组件的真实代价**（设计只说「零 call-site churn」）：**漏了模式解构**。
  `pi-java-tui/.../MessageBubble.java:87` 的 `case ContentBlock.ThinkingContent(var text, _)` **是**嵌套模式
  ⇒ 编译失败（`需要 String,String,boolean / 找到 String,String`）。便捷构造器盖不住 `case` 的模式。
  修法 `(var text, _, _)` + 一行注释。**这是本包唯一在编译期就自曝的破坏面**。

**D. 证伪点复核（§8.33.7 四条）**

1. **Jackson 会不会因缺 `redacted` 而读不了旧 JSON？** ⇒ **不会**，实测见新夹具
   `ContentBlockJsonTest`（`pi-java-ai/.../message/`）：`{"type":"thinking","text":"old"}` 正常读出、
   `redacted` 取布尔缺省 `false`；带 `redacted:true` 的往返也相等。
   ⚠️ **这条夹具从一开始就是绿的**（没有红可验）⇒ 它是**回归门**、不是 RE 证据，如实标注。
   附：生产链路**没有**任何一处用 Jackson 读 `ContentBlock`（已全仓 grep）⇒ 本项其实只在公开 API 面上有意义。
2. **B7 兜底风险**（反序列化失败 ⇒ 四变体全 null ⇒ 静默落 text）：夹具**已显式**断言
   `isRedactedThinking()`（即 B7 那条测试的**第 1 条断言**，实测**通过**）⇒ **不作废**。
3. **访问器名**：`isRedactedThinking()` / `redactedThinking()` / `isThinking()` / `thinking()` 全部实测存在，与设计一致。
4. **「L5 不动」不许写成预测**：见 F，**报实测数字**。

**E. B3 的实测（设计里「需实测确认 SQLite 侧没有第二份手写块编解码」）**

✅ **确认无第二份**：`pi-java-session-backend-sqlite` 全模块 `ThinkingContent` **零命中**；
`SqliteCodecs` 只把载荷交给 `SessionJson.mapper()`（`:31`/`:39`），entry 行编码走
`EntryRows.java:34-41` 的 `SessionJson.mapper().valueToTree(entry)` ⇒ **与 JSONL 同一条 `blockNode`
与同一个 `MessageJsonCodec`**，改一处两后端同时生效。**B3 无需额外改动。**

**F. 实测数字（全部真跑，非预测）**

| 项 | 实测 |
|---|---|
| L5 差分 | **3 轮全绿，每轮 14/14**（`Tests run: 14, Failures: 0, Errors: 0`；1.257 s / 1.518 s / 1.532 s）。**不加 S15**（裁决点 E 的更正） |
| `mvn -o clean verify` | **BUILD SUCCESS**，14 个模块全绿；checkstyle **0 violations**（逐模块）；spotbugs **11/11 模块 `BugInstance size is 0`** |
| ai | **346**（原 340 ⇒ `+6`：thinking-signature 3 条 + `PiMessagesApiTest` 1 条 + `ContentBlockJsonTest` 2 条） |
| agent-core | **462**（含 L5 的 14 条动态用例） |
| `JsonlSessionStorageTest` | **11**（原 9 ⇒ `+2`） |

**G. 实施期观察（本包不做，已登记）**

`PiWebServerAuthTest.acceptsConnectionWithValidToken:97` 在**全 reactor 跑**时红过一次
（`Expecting actual not to be null`：连接已建立，但 15 s 内没等到 `ready` 帧），同轮 `clean verify`
复跑绿（2.160 s）。**隔离复跑 6 次：红 1 / 绿 5**（红的那次 15.05 s，绿的都是 1.3 s 上下）。
⇒ **非确定性** ⇒ 不可能是包① 的回归（回归会确定性红）；且 `git diff` 里 `pi-java-web/` 零改动、
`WebDispatcher.start()`（`resubscribe()` 之后才 `send(Ready)`）不在任何被改路径上。
**未定位根因**，登记为 **A12**（见 `docs/32`），本包不动。
⚠️ **不是本次新发现**：`§8.23.7` 早有同一条（`docs/31:1625`「三次全 reactor 红、第四次绿」、
`:2593` 同）—— 那在前、包① 在后，**同一现象**，本次只是又一次复现 + 一次隔离计数（红 1 / 绿 5）。


---

### 8.34 包②：thinking/text 的**请求侧重放规则**（P2 + P3 + B8）—— **已实施**（2026-09-18，实施记录见 §8.34.11）

> **状态：已实施。** 设计经审核通过（决策 5 由用户裁决为「含，但改带整个 ModelInfo」），
> 红夹具先提交（`22ebcc2`）再动生产代码。实施记录与实测数字见 §8.34.11。
> 双取证：pi 检出 `D:\workplaceForai\pi` @ `71dca871b`、pi-java @ `33eb309`；**承重引文逐条手工复核过**。

#### 8.34.0 先更正上一轮（我方）的四处错引

上一轮的取证报告**推翻了我自己的四处引用**，如实记下（`docs/32` 与 §8.32 的相关行已就地更正）：

| 我说过 | 实际 |
|---|---|
| `appendThinkingBlock` 在 `:295-312` | **`:311-328`**（javadoc `:306-310`）；`AnthropicMessagesApi:295-312` 是**别的东西** |
| text 块写在 `:268-270` | **`:284-286`**（`:268-270` 是 thinking 预算配置）；「无空块闸」这半**是对的** |
| pi 的 `isSameModel` 在 `transform-messages.ts:89` | **`:95-98`**（`:89` 是 toolResult 分支的 `return msg`） |
| `types.ts:193` 是 compat 声明 | **`:713-714`** 才是；`:193` 是 `samplingParams` — 与 `anthropic-messages.ts:193` 的 compat 默认读**重号了** |
| P3 在「`transform-messages.ts` 里」 | **不在该文件**。`transform-messages.ts` **从不丢 text 块**；空 text 闸在 `anthropic-messages.ts:1282` |
| B8 是**三态**（§8.32.1 原表） | **行为上只有两态**：`undefined` 与 `false` **完全等价**（`?? false`，`anthropic-messages.ts:193`）。见 §8.34.4-决策 3 |

#### 8.34.1 pi 的规则（逐字，全部手工复核）

**（1）闸的位置**：`transformMessages(messages, model, normalizeId)` 是**共享预通道**，
由 **6 个**请求构造器各调一次（`anthropic-messages.ts:1029`、`openai-completions.ts:1212`、
`openai-responses-shared.ts:172`、`google-shared.ts:138`、`mistral-conversations.ts:139`、
`bedrock-converse-stream.ts:935`）。pi **没有单独的「重放路径」** —— 每次请求都把整段历史重新转换。
Anthropic 侧：`stream:502` → `buildParams:566`（定义 `:1021`）→ `transformMessages:1029` → `convertMessages:1043`。
⚠️ 重试环 `:576-579` 包的是**已算好的 `params`** ⇒ **重试不重跑**本闸。

**（2）同模型判据**（`transform-messages.ts:95-98`）：
```ts
const isSameModel =
    assistantMsg.provider === model.provider &&
    assistantMsg.api === model.api &&
    assistantMsg.model === model.id;
```
⚠️ 字段**不对称**：存的是 `assistantMsg.model`，比的是目标模型的 `model.id`。

**（3）thinking 块的五条分支**（`transform-messages.ts:101-116`，同一 `flatMap`）：

| # | 条件 | 同模型 | 跨模型 | 出处 |
|---|---|---|---|---|
| a | `block.redacted` | **原样保留** | **丢弃**（`[]`）—— 注释明写「opaque encrypted content, only valid for the same model」 | `:104-105` |
| b | `isSameModel && block.thinkingSignature`（真值） | **原样保留**，**含 thinking 文本为空**时（OpenAI 加密推理） | —— 走 c | `:109` |
| c | `!block.thinking \|\| thinking.trim()===""` | **丢弃** | **丢弃**（同） | `:110-111` |
| d | 无签名、文本非空 | **保留**为 thinking 块（签名仍缺） | **降级为 `{type:"text",text:thinking}`** | `:112-116` |
| e | text 块 | 同对象 | **同对象** —— 行为上是 **no-op** | `:119-125` |

**（4）落线（Anthropic）**（`anthropic-messages.ts:1287-1321`）：
- `:1289-1295` **redacted → `{type:"redacted_thinking", data: block.thinkingSignature!}`**
  （注意 `:1292` 是**非空断言** ⇒ redacted 块若无签名会产出 `data: undefined`）。
- `:1297` `hasThinkingSignature = !!sig && sig.trim().length > 0`；
- `:1298` 文本空**且**无签名 ⇒ `continue`（**丢弃，与 allowEmptySignature 无关**）；
- `:1302-1314` 无签名且文本非空 ⇒ `allowEmptySignature ? {type:"thinking",…,signature:""} : {type:"text",…}`；
- `:1315-1321` 有签名 ⇒ `thinking` 块带原签名（**文本可空**）。

**（5）空 text 块（P3）**：`anthropic-messages.ts:1282` `if (block.text.trim().length === 0) continue;`
—— 它是**助手内容循环**（`:1280`）的第一个 `if`，同一循环还处理 thinking(`:1287-1321`) 与
toolCall(`:1322-1329`)。另有**用户消息**的 filter 版（`:1265-1271`，`filteredBlocks.length===0 ⇒ continue`）
与**消息级**兜底（`:1331` `if (blocks.length === 0) continue;`）。

**（6）`allowEmptySignature`（B8）的全部命中**（`packages/` 内非测试只有 3 个文件）：
声明 `types.ts:713-714`；用户配置 `coding-agent/src/core/model-config.ts:135`；
默认归一 `anthropic-messages.ts:193`（`?? false`）；入参 `:1047`；形参默认 `:1227`；
**唯一的行为点** `:1304`。语料生成 `packages/ai/scripts/generate-models.ts`：
**Fireworks 全部** anthropic-messages 模型（`:1427`，无 allowlist，`:1495-1501` 应用）、
**Kimi Coding 全部**（`:2242`/`:2253`，`k3` 与 `kimi-for-coding`，别名 `k2p5/k2p6/k2p7` 归一到后者）、
**Xiaomi** 有规则（`:1075`）但**休眠**（其内置模型全是 `openai-completions`，`:770` 的早退使其打不到）。
⚠️ **生成的语料数据不在检出里**（`packages/ai/src/providers/data/` 被 gitignore）⇒ 上表是**唯一可验的 in-tree 证据**。

**（7）关键推论**：`allowEmptySignature` **只在同模型重放时被咨询** —— 跨模型在闸里就已降级/丢弃，
带签名的 thinking 块**根本到不了** `convertMessages`。这决定了 B8 不可能单独生效（⇒ 必须同包做 P2）。

#### 8.34.2 pi-java 侧现状（实测，`33eb309`）

**（1）现有重放规则**（`AnthropicMessagesApi:311-328`，**私有**，**唯一调用点 `:288`**）：
```java
var signature = th.signature() == null ? "" : th.signature().trim();   // :313
if (text.isBlank() && signature.isEmpty()) { return; }                 // :315-317  丢
if (signature.isEmpty()) { result.add(ofText(text)); return; }         // :318-322  降级 text
result.add(ofThinking(ThinkingBlockParam.builder().thinking(text).signature(signature).build())); // :323-327
```
⇒ 形状上**已经等于** pi 的 (c)+(d-同模型)+(部分 b)，**但缺三样**：`isSameModel` 闸、`allowEmptySignature`、`redacted`。

**（2）`redacted()` 在 `pi-java-ai` 生产代码里零读点** ⇒ 一个 redacted 块
（`text="[Reasoning redacted]"`、`signature=<不透明载荷>`、`redacted=true`）**落进 `:323-327` 的有签名分支**
⇒ 会被当成**带签名的 thinking 块**发出去，签名位放的是**加密载荷**。
**且全仓没有任何代码路径能产出 `redacted_thinking` 线格** —— 这是 P2 的下游，**新登记 B13**。

**（3）空 text 块无闸**：`:284-286` `TextBlockParam.builder().text(tc.text())` 无条件。
`:238 if (blockParams.isEmpty()) continue;` 护的是**消息**不是**块** ⇒ 只有一条空 text 块的消息**照样发出空块**（B11 / P3 成立）。

**（4）无 `isSameModel` 对应物**：全仓 `isSameModel|transform-messages` **零命中**。
最近的**形状**先例是 `OpenAICompletionsApi:235` `if (!reasoning.isEmpty() && "deepseek".equalsIgnoreCase(provider))`
（provider **名**闸，从 `:155` 传入）—— 借它的形状、不是它的语义。
`Message.AssistantMessage` 已带身份三元组（`Message.java:65-75` 的 `api`/`provider`/`model`），**闸所需的输入齐备**。

**（5）另外三条车道的重放口径**（本包评估是否一并纳管）：
`PiMessagesApi:225-226`（thinking **只送 text**，签名/redacted 全丢）、
`OpenAICompletionsApi:210-211`+`:235`（只送 reasoning 文本，deepseek 门）、
`ResponsesMessageConverter:180-182` 与 `GoogleGenerativeAiApi:212-213`（**整块不重放**）。

**（6）配置链断点**：`ModelInfo` **10 个组件、无 `compat`**（`catalog/ModelInfo.java:27-38`）；
`ModelsJsonSchema` 的**四个**记录全带 `@JsonIgnoreProperties(ignoreUnknown = true)`
（`:23`/`:27`/`:37`/`:53`），而 `ModelsJsonConfig.java:46` 是**裸 `new ObjectMapper()`**
⇒ **用户在 models.json 里写 `compat` 会被静默吞掉**（已实测确认，不只是推断）。
今唯一全参构造点是 `ModelsJsonConfig.java:205-208`（10 参）。
⚠️ **陷阱**：`pi-java-web` 里另有一个**同名不同类**的 `ModelInfo`（`WebProtocol.java:29`，3 个组件）
—— 改 schema 时**不许碰它**。

**（7）一处**已撒谎的注释**：`AnthropicMessagesApi:299-301` 仍写着
「ThinkingContent is dropped: replaying thinking blocks requires the original signature…」
—— 而它下面 `:288` 正是在重放。**生产源码里的假陈述**，登记 **D8**（本包顺手修）。

#### 8.34.3 差距表（pi 分支 → pi-java 现状 → 本包动作）

| pi 分支 | pi-java 现状 | 本包动作 |
|---|---|---|
| a redacted 同模型保留 / 跨模型丢 | **无**（落进有签名分支，线格错） | **改**（含 B13 的 `redacted_thinking` 落线） |
| b 同模型 + 真值签名 ⇒ 保留（文本可空） | 形状已有，**无闸** | **加闸** |
| c 空文本且无签名 ⇒ 丢 | 有（`:315-317`，但用 `text.isBlank()`，pi 用 `!thinking`） | **对齐判据**（`isBlank` vs `null`） |
| d 无签名 ⇒ 同模型保留 / 跨模型降级 text | 有，但**恒降级**（无闸） | **加闸** |
| e text 块 | 已是 no-op | **不动**（实测同对象） |
| `allowEmptySignature` 三态 | 无 `compat` | **做**（两态） |
| 空 text 块闸（P3，在**适配器**不在闸里） | 无 | **做** |
| 其余四条变换：图片降级（`:35-57`）、跨模型 toolCall `thoughtSignature` 剥离（`:131-134`）、跨模型 toolCall id 归一（`:136-142`）、**孤儿 toolCall 合成 `toolResult`**（`:158-220`）、跳过 `error`/`aborted` 助手消息（`:194-197`） | 全无 | **不在本包** ⇒ 登记 **B14**（一条聚合行，带逐条出处） |

#### 8.34.4 四个决策与推荐

**决策 1 —— 闸放哪儿？** 推荐 **(A) 忠实位置：`pi-java-ai` 里加共享预通道**
（如 `api/TransformMessages.java`，镜像 `transform-messages.ts` 的**形状与调用位置**），
at least 挂到 Anthropic 与 pi-messages 两条**真重放签名**的车道；Google/Responses 那条接上去是 no-op。
**不推荐 (B) 只改 `AnthropicMessagesApi`** —— 位置上就不对：pi 的闸在**适配器之外**，
放进去意味着以后每接一条车道都要再抄一遍。
**但**：共享通道**只落 thinking 五分支**，其余四条（B14）只留骨架与出处，**不实现**（不投机）。

**决策 2 —— `compat` 的形状？** 推荐 **typed record** `ModelCompat`（`ModelInfo` 第 11 个组件，
附便捷构造器兜住 14 个构造点里绝大部分），以及 `ModelDef` 显式加 `compat` 键
—— **「静默吞掉」的根治办法就是让 `compat` 成为已知键**（比关 `ignoreUnknown` 安全：后者会让
用户 models.json 里任何手滑键都变成硬错）。
不推荐 `Map<String,Object> compat`：pi 的 compat 是**逐 provider 的 typed interface**
（`forceAdaptiveThinking`/`supportsStrictTools`/`deferredToolsMode`…），Map 会把类型错误推后到读点。

**决策 3 —— 要不要三态？** **不要**（实测 `undefined ≡ false`，§8.34.0 末行）。
只需 `boolean`，缺省 `false`。**这条同时更正 §8.32.1 的 B8 行。**

**决策 4 —— 跨模型的 redacted 是「丢」还是「降级 text」？** 照 pi：**丢**（`:105`，无降级分支）。
这条反直觉（文本还在，为何不降级），但它是 pi 的**明文选择**（注释给了理由：不透明密文跨模型无效）。

**决策 5（§8.34.10 侦察后新增，**2026-09-18 已裁**）—— `compat` 怎么从目录走到适配器？**

> 这一条**不在**原设计的 A–E 里：原设计只定了 `ModelCompat` 的**形状**（决策 2），
> 没定**投送路径**。侦察发现「适配器拿不到 `ModelInfo`」是真的断链，故补此决策。

**实测的断链**（§8.34.10-6）：活路径上**只有 `ModelId`**，一路到底都是：
`AgentSession:267`（`resolve` 返回 `ModelId<?>`）→ `HarnessConfig.model:76`（`ModelId<?>`）
→ `ExecutionContext.model:36`（`Supplier<ModelId<?>>`）→ `PiLoop.Config.model:245`
→ `PiLoopRunner:214` → `PayloadRecordingStreamFn:97` → `DefaultProviders:91` lambda
→ `StreamRequest:117`。harness 里**没有任何** `ModelInfo`／`ModelCatalog`。
⚠️ `StreamSimple:44` 确实拿着 `ModelInfo`，但**它在生产上是死的**（3 个调用点全在
`StreamSimpleTest`，主源零调用者）—— 它**不是**可用接缝，别被它骗。

**三个候选**：

| | 做法 | 代价 | 评价 |
|---|---|---|---|
| **(A)** | `StreamRequest` 加第 8 组件 `ModelCompat compat`（**保留 7 参便捷构造器** ⇒ 测试零改签）；取数点在 `DefaultProviders.streamBlocking`（`providers` 在其闭包内 ⇒ `provider.builtinModels().find(model)`） | `pi-java-ai` 2 文件 + `pi-java-coding-agent` 1 文件 | **推荐** |
| (B) | 走 `extra` 通道（`"compat.allowEmptySignature"`），与 `thinking.budgetTokens` 同形 | 零签名改动 | 非类型化；且那个"同形先例"本身是**休眠**的（见下） |
| (C) | 学 `contextWindow` 的先例（`HarnessConfig:81-82` 的 `ToIntFunction<ModelId<?>>`，宿主层 `AgentSession:360` 注入 `models::contextWindow`） | **12 个主源文件 + ~34 个测试文件** | 最"正统"但要动 `StreamFn` 签名；与包② 收益不成比例 |

**推荐 (A)**：类型安全、测试零改签、把 `compat` 放在「**目标模型的属性**」这个语义位置上
（与 pi 的 `model.compat` **同位**，而不是折成一堆散键）。**且一次修好同一接缝上的四个 compat 键**
—— 包③/④ 要的 `supportsStrictTools`／`supportsToolReferences`／`deferredToolsMode`
全落进同一个字段，不必各修一次。

**顺带发现（新登记 B15，**不在本包**）**：同一个断链让**扩展开启在生产上不可达**。
`models.json` 的 `reasoning:true` 只落成 `ModelCapability.THINKING`（`ModelsJsonConfig:191-193`），
而 `thinkingLevelMap` 硬写 `empty()`（`:208`）；`HarnessConfig.thinkingLevelMap` 默认 `empty()`（`:159`）
且 `Builder.thinkingLevelMap` **零主源调用者**；`forLevel` 在空 map 上**恒返回 `ThinkingConfig.OFF`**
（`ThinkingLevelMap:26-34`）⇒ `DefaultProviders:111-113` 门恒假 ⇒ `extra` 永无
`thinking.budgetTokens` ⇒ `AnthropicMessagesApi:269-276` 永不发 `thinking` 配置。
**`ThinkingLevelMap.of(` 也只有测试调用者** ⇒ 非空 map 在生产上**不可构造**。
⇒ 根因不是某处写错，是**「目录元数据 → 请求路径」这个通道整体缺失**；
`compat` 只是它的第二个受害者。B15 需要**自己的设计包**（它改变**发什么请求**，比本包重）。

**裁决（用户，2026-09-18）＝ (A) 的加强版：`StreamRequest` 带整个 `ModelInfo`**，
不是窄 `ModelCompat` 字段。理由：一步到位 —— `compat` 与 `thinkingLevelMap`（B15）**共用同一载体**，
且适配器拿到的是**目标模型的完整元数据**，正是 pi 的形状（pi 的请求构建器本来就收
`model: Model<TApi>`，`compat` 只是它上面一个可选字段）。

**落地形状（让「改语义」不炸读取面）**：`StreamRequest` 第 1 组件由 `ModelId<?>` 改为 `ModelInfo`，**同时给**：

- **7 参便捷构造器**（收 `ModelId<?>` ⇒ 内部合成 `ModelInfo.minimal(id)`）⇒ **17 个测试构造点与
  evals 套件零改签**（构造面 `new StreamRequest(` 实测 17 测试 + 3 主源）；
- **`modelId()` 便捷访问器** ⇒ 22 处 `.model()` 读数（**10 个文件**，全部只要 `.provider()`/`.modelName()`）
  **机械替换**为 `modelId()`，可 sed；
- 取数点 `DefaultProviders.streamBlocking`：`provider.builtinModels().find(model)`
  （`providers` 在该 lambda 闭包内），**查不到则回落 `ModelInfo.minimal(modelId)`**
  ⇒ 保住今天「任意 `ModelId` 都收」的宽容度，**线上线格零变化**（`modelId()` 与原 `model` 逐字段相同）。

⚠️ **给 B14 留的话（图片降级那条）**：合成出来的 `ModelInfo` 的 `capabilities` 是**空集**，
与「这个模型确实不支持图片」**不可区分** ⇒ B14 读 `model.input` 判定时必须把「**未知**」与
「**不支持**」分开，否则目录未命中的模型会被**误降级**掉图片。

#### 8.34.5 夹具计划（**先红后绿**；一条夹具一个断言）

⚠️ **吸取包① 的两次教训**：**不许**把两条断言塞进一条测试（后一个的红会被前一个挡住），
**不许**夹具后写（包① 的 P4 被迫用 `git stash` 补红）。

⚠️ **第三条规则（§8.34.10-4 实测推出来的）**：钉**闸**的夹具**断言 `TransformMessages` 的输出**，
**不许断言线格** —— 在 Anthropic 车道上，闸的四条非 redacted 分支**产出的线格与「跳过闸」完全相同**
（结构性惰性），断言线格会绿、且绿的原因与本次修法无关。
**只有 redacted 那两条（P2-a1/P2-a2）与 B8/P3 可以打线格。**

| # | 夹具（方法名） | 期望出参（线格压成 `类型:载荷`） | 现状（**实测**） | 判定 |
|---|---|---|---|---|
| 1 | `redactedSameModelReplaysAsRedactedThinking` | `["text:hi","redacted:opaque-payload"]` | `["text:hi","thinking:opaque-payload"]` | 🔴 **B13**（P2-a2） |
| 2 | `redactedCrossModelIsDropped` | `["text:hi"]` | `["text:hi","thinking:opaque-payload"]` | 🔴 P2-a1 |
| 3 | `signatureWithTextCrossModelDowngradesToText` | `["text:hi","text:reasoning body"]` | `["text:hi","thinking:sig-abc"]` | 🔴 闸 (e) |
| 4 | `signatureWithBlankTextCrossModelIsDropped` | `["text:hi"]` | `["text:hi","thinking:sig-abc"]` | 🔴 闸 (c) |
| 5 | `blankAssistantTextBlockIsNotSent` | `["text:hi","text:real answer"]` | `["text:hi","text:","text:   ","text:real answer"]` | 🔴 P3-1 |
| 6 | `blankUserTextBlockIsNotSent` | `["text:hi"]` | `["text:   ","text:hi"]` | 🔴 P3-3（**用户**车道） |
| 7 | `signatureWithBlankTextSameModelKeepsThinking` | `["text:hi","thinking:sig-abc"]` | 同 | 🟢 回归门 |
| 8 | `signatureWithTextSameModelKeepsThinking` | `["text:hi","thinking:sig-abc"]` | 同 | 🟢 回归门 |
| 9 | `noSignatureSameModelDowngradesToText` | `["text:hi","text:reasoning body"]` | 同 | 🟢 回归门（B8 的**对照面**） |
| 10 | `blankThinkingWithoutSignatureIsDropped` | `["text:hi"]` | 同 | 🟢 回归门 |
| 11 | `blankThinkingWithoutSignatureCrossModelIsDropped` | `["text:hi"]` | 同 | 🟢 回归门 |
| 12 | `whitespaceOnlySignatureWithBlankTextIsDropped` | `["text:hi"]` | 同 | 🟢 回归门（钉证伪点 2 的结论） |

**实测**：`Tests run: 12, Failures: 6` —— 红灯 **6**、绿 **6**，与上表逐条对上
（文件 `pi-java-ai/src/test/java/com/pijava/ai/protocol/AnthropicThinkingReplayTest.java`）。

**B8 三条挪到 Phase B**（要先有 `ModelCompat` 与决策 5 的投送路径）：B8-1（`true` ⇒ 线格是
`{type:"thinking",signature:""}`）、B8-2（缺席/`false` 行为相同 ⇒ 钉死两态）、
B8-3（models.json 的 `compat` 键 ⇒ 读到 `ModelInfo.compat()`，这条**不**依赖投送，Phase B 内先做）。
**闸**那一层的夹具（断言 `TransformMessages` 输出）同样在 Phase B —— 先建**空过**的
`TransformMessages`（证明这条缝本身零行为改动），写夹具 ⇒ 红 ⇒ 再填分支。

> 「**会绿**」那 6 条老实标注为**回归门**而非 RE 证据 —— 包① 的 `ContentBlockJsonTest`
> 已经吃过一次这个口径。**红灯数字只算 6。**

**§8.34.5-a 三条新教训（都是本次实测撞出来的，续在包① 的「夹具没牙」家族之后）**

- **形态 (4)：夹具的观测面比被测车道宽。** `render` 走的是**出参里全部消息的全部块**，
  而夹具带了 `user("hi")` ⇒ 期望值**必须以 `"text:hi"` 开头**。第一版全漏了这条前缀，
  跑出 **12 条全红**——而那个红**与本次要修的行为毫无关系**。
  **教训：红灯必须逐条读 `actual`**，「Failures: 6」这种数字不区分「红对了」与「红错了」。
  （这条尤其阴：它把 6 条回归门也染红了，**症状**恰好长得像「到处都是差距」。）
- **形态 (5)：设计阶段凭记忆列的差异表本身是错的。** 原表三条判定全错 ——
  P2-b1 说「现状被 `:315-317` 丢掉」（实为 `:315` 要求**文本与签名同空**，有签名就不会丢 ⇒ **绿**）；
  P2-c1 说「可能会绿」（实为 `:323` 原样发 thinking ⇒ **红**）；
  P2-d2 说「同模型+无签名+非空文本 ⇒ 保留为 thinking，现状降级 text 是差距」
  （**pi 侧也是降级 text**，`:1296-1316` 的 `allowEmptySignature` 缺省 false ⇒ **根本不是差距**）。
  **教训：差集必须逐分支对读两侧源码再列**；凭记忆写出的「差异表」会把红灯数、回归门数同时写错。
- **形态 (6)：夹具写在实现之后 ⇒ 没有红灯可看**（§8.34.11 的 B8-1 与决策 5 两条）。
  红灯是 RE 的**唯一**证据来源；夹具后写，就只剩「它今天绿」这一句空话 —— 而空过的实现、
  恰巧正确的路径、以及「断言落在下游早就做对的地方」**都会绿**。
  **补救只能靠变异探针**（改一行生产代码看它是否变红），代价是**变异点由人挑** ——
  挑错变异点会得出「夹具没牙」的**错误结论**（本包实测：改 `allowEmptySignature` 硬写 false，
  B8-1 恰 1 红、B8-2 仍绿 —— 后者**本就该**对该变异失明，因为 B8-2 钉的是**归一**不是分叉）。
  **教训：能先写就先写；已经后写的，**在文档里标明**并给出变异实测，不许含糊成「已测」**。

#### 8.34.6 证伪点（实施前必须打掉的）

1. **`isSameModel` 的输入在 pi-java 齐不齐？** —— ✅ **已答（§8.34.10-2/3）**：三项输入齐备
   （`api` 由适配器的 `apiName()` 提供）；**老会话判异 = 与现状逐字相同** ⇒ 决策 C 取**判异**，风险实测为 0。
2. **`appendThinkingBlock` 的 `trim()`** 会不会与 pi 的「真值判定」（`:109` 用真值，`:1297` 用 `trim`）
   打架？—— ✅ **已答（结论与原推荐相反）**：pi 自己确实**不一致**（纯空白签名在闸 `:109` 算「有」、
   在落线 `:1297` 算「无」），**但这个不对称在出参上不可观察** —— 凡两者会分歧的路径都被落线
   重新归并：`:1298` `if (block.thinking.trim().length === 0 && !hasThinkingSignature) continue;`
   又用 `trim` 判文本 ⇒ 纯空白签名 + 空文本**仍然被丢**；而纯空白签名 + 有文本时
   `:1304` 的 `!hasThinkingSignature` 分支把两个来源**都**收敛成 `text`。
   ⇒ **原推荐的「照抄」是多余的复杂度**：**不照抄**，pi-java 保持 `:313` 的 `trim` 口径
   （行为相同、代码更简单）。结论由夹具 12（`whitespaceOnlySignatureWithBlankTextIsDropped`）钉住。
   **教训：「pi 内部不自洽」不等于「有不一致要复刻」—— 先证明它可观察。**
3. **重试不重跑闸**（`:576-579`）—— pi-java 的重试环是否也持有已构造的 params？**需实测**，
   否则会出现「pi 重试用旧块 / pi-java 重试用新块」的隐藏差异。
4. **`compat` 落 models.json 后，`AiCli` 的写回路径**会不会把它抹掉？**需清点写侧**（本次只查了读侧）。
5. **加第 11 个组件会不会再撞一次嵌套模式解构？** —— ✅ **已答（§8.34.10-1）**：全仓**零**
   `case ModelInfo(...)` / `instanceof ModelInfo` ⇒ **无模式破坏面**。

#### 8.34.7 需要拍板的点

| # | 问题 | 推荐 |
|---|---|---|
| A | 闸的位置：(A) 共享预通道 vs (B) 只改 Anthropic | **(A)**，但只落 thinking 五分支 |
| B | B14（其余四条变换）**跟本包做**还是另立包 | **另立**：图片降级与孤儿 toolResult 合成**各自是一块功能**，塞进来会让本包从 200 行变 800 行 |
| C | 老会话缺身份三元时，`isSameModel` 判异（忠实、但会降级全部老 thinking 块）还是判同（宽松、但与 pi 不同） | **判异**（判据优先），但**先实测一份老会话**再落 |
| D | `compat` 用 typed record 还是 Map | **typed record** |
| E | 跨模型 redacted：丢 vs 降级 | **丢**（照 pi） |

#### 8.34.8 验收标准（沿用 §8.33.8 的四条口径）

1. 上表每条夹具**先红后绿**，红灯逐个报（「会绿」的两条**明确标注为回归门**，不许混进红灯数）；
   **一条夹具一个断言**。
2. `mvn -o clean verify` 全 reactor 零错误、checkstyle/spotbugs 零违规。
3. L5 差分**真跑 ≥3 轮**报实测；**预期是「不动」也必须报实测数字**（§8.33.9-F 已被 §8.23 的
   「预期不动被证伪」教训教训过一次）。
4. `docs/32` 补行：B8/B10/B11 状态、**B13/B14/D8 新登记**、§8.32.1 的「三态」更正。

#### 8.34.9 本包**不含**

- 不碰 `appendThinkingBlock` 以外的四条车道的**线格**（pi-messages/openai 的 thinking 重放口径
  先只接闸，**不改它们自己的落线**——那是各自车道的事）。
- 不做 B14 四条变换（另立）。
- 不关 `ignoreUnknown`（决策 2 的理由）。
- 不动 `pi-java-web` 的同名 `ModelInfo`。
- 不给任何 compat 键做「将来可能用得上」的预留（判据是行为）。

#### 8.34.10 开工前侦察（**实测**，2026-09-18；两条发现改变实施形状）

**（1）`ModelInfo` 无模式破坏面 —— 与包① 相反。**
全仓 grep：**零** `case ModelInfo(...)`、**零** `instanceof ModelInfo`；`ModelInfo` 只出现在
构造函数、字段访问与类型位置（含 `pi-java-agent-core/.../StreamSimple.java:44` 的形参）。
⇒ 加第 11 个组件**不会**重演包① 的 `MessageBubble:87`（那条是**唯一**的三参嵌套模式）。
⚠️ 仍要避开的**同名陷阱**：`pi-java-web` 的 `ModelInfo`（`WebProtocol.java:29`，3 组件）是**另一个类**。

**（2）`isSameModel` 的三项输入在重放点全部可得，但 `api` 的来源要挑一下。**
- 目标侧：`StreamRequest.model()` 是 **`ModelId<?>`**（`model/ModelId.java:10-13`，**只有 `provider` + `modelName`，没有 `api`**）
  —— 因为 pi-java 的 `api` 是**provider 级**（即台账 C9 那条）。
- 消息侧：`Message.AssistantMessage`（`Message.java:65-75`）有 `api`/`provider`/`model`；
  写入点在 `AbstractChatApi.identityBase:45-49`（`apiName()` / `request.model().provider()` / `.modelName()`）。
- ⇒ 判定式取
  `Objects.equals(msg.provider(), target.provider()) && Objects.equals(msg.api(), apiName()) && Objects.equals(msg.model(), target.modelName())`，
  其中 `api` 一项由**适配器自己的 `apiName()`** 提供（`AnthropicMessagesApi:42` → `"anthropic-messages"`）。
  **这不是权宜** —— pi-java 里「即将发出的请求的 api」本就等于正在执行的那个适配器。

**（3）老会话实测（决策 C 的风险 = 0，我原先的担心不成立）。**
`~/.pi-java/agent/sessions/--D--workplaceForai-pi-java--/`：
- `2026-08-30` 的会话：assistant 消息**无** `api`/`provider`/`model`；thinking 块是旧键
  `{"type":"thinking","text":"…"}`（**无签名**，其中一条还是 `"text":""`）。
- `2026-09-10` 的会话：assistant 消息**有**身份（`"api":"anthropic-messages"`、
  `"provider":"teamorouter"` 与 `"provider":"anthropic"`、`"model":"claude-opus-4-8"`）；
  thinking 块**同样无签名**（包① 之前没有任何东西落过签名）。

⇒ **判异对存量数据是零影响**：老消息身份为 null ⇒ 判异 ⇒ 无签名的 thinking 块**降级为 text**，
而**今天的行为也是降级为 text**（`AnthropicMessagesApi:318-322`）—— 逐字相同。
我原先担心的「判异会把全部老 thinking 块降级」描述的**正是现状**，不是新损害。
（反过来说：若判**同**，老消息会翻转成「保留为 thinking 块」= **凭空虚增**一处行为差异。故判异同时更忠实、更保守。）

**（4）⚠️ 对 Anthropic 车道，闸的五条分支里有**四条是惰性**的 —— 夹具必须打闸、不许打线格。**
把 pi 的闸与 pi 的适配器**逐条对照**（`transform-messages.ts:95-116` × `anthropic-messages.ts:1296-1321`）：

| pi 闸的分支 | 若**跳过闸**、只走适配器，线格是什么 | 是否惰性 |
|---|---|---|
| `:109` 同模型 + 真值签名 ⇒ 保留（文本可空） | `:1315-1321` thinking + 原签名 —— **相同** | ✅ 惰性 |
| `:111` 空文本且无真值签名 ⇒ 丢 | `:1298` 同条件 `continue` —— **相同** | ✅ 惰性 |
| `:112` 同模型 + 无签名 ⇒ 保留为 thinking | `:1297` `hasThinkingSignature=false` ⇒ `:1302` 分支 ⇒ **text**（除非 allowEmptySignature）—— 与「保留」的下场**相同** | ✅ 惰性 |
| `:113-116` 跨模型 + 无签名 ⇒ **降级为 text** | 适配器单独也会把它变成 text（同上）—— **相同** | ✅ 惰性 |
| `:104-105` **redacted**：同模型保留 / 跨模型丢 | 适配器**完全不看 `redacted`**（`:1289` 的 `if` 在 pi 里是 redacted 专用分支，pi-java **无对应物**）⇒ 同模型产出 `thinking(签名=密文)`、跨模型**照样送** | ❌ **非惰性** |

⇒ **结论一**：在 Anthropic 车道上，**唯一真正改变线格的分支是 redacted**（即 B13）。
⇒ **结论二**：凡是要钉「闸本身」的夹具（保留/丢弃/降级），**必须断言 `TransformMessages` 的输出**
（闸之后的 `List<Message>`），**不许断言线格** —— 后者会绿，而且绿的原因与本次修法无关。
这正是「夹具没牙」的**第三种形态**（前两种：夹具后写、一条塞两断言）：**断言落在了下游早就做对的地方**。
§8.34.5 里那两条原本标注为「会绿」的行（P2-c1 / P2-d1）由此**升格为解释**：它们不是偶然绿，是**结构性惰性**。

**（5）闸的价值主要在**非 Anthropic 车道**。**
`PiMessagesApi:225-226` 对**任何** thinking 块一律送 `{type:"thinking","thinking":text}`（签名与 `redacted` 全丢）
⇒ 在它那里，闸的「跨模型降级 / 丢弃」是**真的会改变送出去的东西**。故共享预通道不是为 Anthropic 修的。


---

#### 8.34.11 实施记录（2026-09-18，**已实施**）

**落地清单**（每项都能指到 §8.34 的决策号）：

| 文件 | 改动 | 决策 |
|---|---|---|
| `catalog/ModelCompat.java` | **新增**。`record ModelCompat(boolean allowEmptySignature)` + `NONE`/`of`。pi 的 compat 接口有八个字段，此处只携带**被实际消费的一个**；javadoc 记明 | 决策 3 |
| `catalog/ModelInfo.java` | 第 11 个组件 `compat`；compact ctor `null ⇒ NONE`；10 参便捷构造器；`minimal(id)` 工厂 | 决策 5 |
| `api/StreamRequest.java` | 组件 1 `ModelId<?>` → **`ModelInfo model`**；7 参便捷构造器（合成 `ModelInfo.minimal`）⇒ 17 处测试构造点零改动；新增 `modelId()` 访问器 ⇒ 10 个文件 22 处读点机械替换 | 决策 5 |
| `api/TransformMessages.java` | **新增**。pi `transform-messages.ts` 的**闸**：`isSameModel` + thinking 五分支。非 assistant 消息原样放行（`toolResult` 的 id 归一是 B14，本包不做） | 决策 1 |
| `protocol/AnthropicMessagesApi.java` | ① `buildParams` 开头调闸；② `toBlockParams` 补**空文本跳过**（B11，两条车道共用一处）；③ `appendThinkingBlock` 重写为 pi 落线四分支，含 **redacted → `redacted_thinking`**（B13）与 `allowEmptySignature` 分叉；④ 删 D8 的**假注释**；⑤ 读 `request.model().compat()` | 决策 2/5 |
| `provider/ModelsJsonSchema.java` | `ModelDef` 加 `compat` 键；新增 `CompatDef`（块内未知键仍忽略） | B8 |
| `provider/ModelsJsonConfig.java` | `CompatDef → ModelCompat` 映射（缺席/键缺席 ⇒ `NONE`） | 决策 3 |
| `coding/agent/core/DefaultProviders.java` | `streamBlocking` 改投 `provider.builtinModels().find(model)` 的真实 `ModelInfo`，未命中退化为 `minimal` | 决策 5 |

**实测数字**（`mvn -o -pl pi-java-ai test`，本机 JDK 25 / GraalVM）：

| 阶段 | ai 模块 | 说明 |
|---|---|---|
| 开工（`22ebcc2` 红夹具） | `367 / 6` | 线格 6 红 = 本轮要修的缺口 |
| + 投送与 compat 字段 | `358 / 6` | **行为中性**：只有 6 条预定的红（比预期更有力 —— 计划里的「空过版」那一步被这条证据取代） |
| + 闸（`TransformMessages` 五分支） | `367 / 3` | 3 条跨模型 thinking 线格**转绿**；剩 3 条（redacted 同模型 + 两条空文本）仍红 —— **正是闸管不到、必须由落线承担的**（闸/落线的分工由此被数字证实） |
| + 落线四分支 | `367 / 0` | 全绿 |
| + Phase B（B8-1/B8-2/B8-3） | `372 / 0` | 见下 |

**全仓**（`mvn -o clean verify`，11 模块，exit 0 / BUILD SUCCESS，checkstyle 零违规）：
telemetry 31 · **ai 372** · agent-core 462 · session-sqlite 35 · **coding-agent 222** · tui 188（1 skipped）
· protocol 14 · server 2 · web 37 · evals 43（17 skipped）。**零 Failures 零 Errors。**

**实施中被证伪/更正的三处**（都不是笔误，是判断错）：

**（1）决策 1 对 `PiMessagesApi` 的定性是错的 —— 本包**没有**给它挂闸。**
决策 1 原文把 `PiMessagesApi` 描述为「真重放签名」的车道。实测（`:225-226`）**相反**：它对任何 thinking
块一律送 `{type:"thinking","thinking":text}`，**签名与 `redacted` 全丢**。更关键的是它是 pi-java
**自己的** wire 形状（pi 侧无对应物，pi 的 6 个请求构建器里没有它）—— 在一条 pi 没有的车道上按 pi
的闸改行为，等于**发明**行为而非对齐。
⇒ **裁决：不挂闸**；该车道自己的「签名全丢」缺口**单独登记为 B18**，不在本包发明规则。

**（2）pi 落线不 trim 签名，pi-java 落 trim —— 保留偏差并写明。**
pi `:1316` 落线的是**未 trim** 的 `thinkingSignature`（它只在 `:1296` 的**判空**里 trim）。
pi-java 沿用包① 之前 `:318` 的 trim。差别只在签名首尾带空白时可见，而真 Anthropic 的签名是无空白
base64；两处**判空语义一致**（`isEmpty` vs `trim().isEmpty()` 在该处等价，见 §8.34.6-2 的证伪否定）。
⇒ 保留 trim，在 `appendThinkingBlock` 的 javadoc 里**明文记为刻意偏差**，供日后翻案。

**（3）一个既有绿测试因闸而**报错**（不是失败）—— 根因是夹具造了 pi 造不出的状态。**
`AnthropicMessagesApiBuildParamsTest.replaysThinkingBlockWithSignature` ⇒ `NoSuchElement`。
根因（**逐段实测**，非推断）：该夹具用 `AssistantMessage(content)` 兼容构造器造出**身份三元组为 null**
的助手消息，而闸按 pi 的 `===` 语义判它**异模型** ⇒ 丢签名。
而 pi 的 `AssistantMessage` 里 `provider`/`api`/`model` 是 **required** ——「无身份的助手消息」在 pi 里
**不存在**；生产侧 `AbstractChatApi:174-178` 恒挂三元组（`apiName()` / `provider` / `modelName`，正是闸
比较的三个值），会话恢复侧 `MessageJsonCodec:41-49` 原样读回。⇒ **夹具缺陷，不是闸缺陷。**
修法 = 给三条 thinking 夹具**补上生产形状的身份**（新 `assistant(...)` 助手）。这不只是「让它过」：
另两条（`downgradesThinkingWithoutSignatureToText` / `skipsEmptyThinkingWithoutSignature`）在身份为 null 时
**根本走不到**它们各自点名的落线分支（已在闸里被降级/丢弃）⇒ 名字与覆盖面对不上，正是 §8.34.5-a 的
「夹具没牙」。补身份后三条各自回到自己那一层。

**RE 证据**（每条新夹具都实测过「红得动」；不是事后补的断言）：

| 夹具 | 变异探针 | 实测 |
|---|---|---|
| `TransformMessagesTest` 3 条行为红 | 闸返原样（空过版） | 恰 3 红，5 门绿 |
| `AnthropicThinkingReplayTest` 6 条红 | 落线保持旧实现 | 恰 6 红，6 门绿 |
| **B8-1** `allowEmptySignatureKeepsThinkingWithEmptySignature` | `allowEmptySignature` 硬写 `false` | **恰 1 红**（B8-2 与其余门全绿 —— B8-2 钉的是**归一**，本就该对该变异失明） |
| **决策 5** `DefaultProvidersTest.streamFnCarriesCatalogModelMetadata` | `streamBlocking` 改回只投 `ModelId` | **恰 1 红**（`unknownModelStillCarriesItsId` 仍绿 —— 它钉的是回退路径） |

⚠️ **B8-1 与决策 5 两条夹具是事后补的**（先写了实现，再写夹具），因此**不能**声称「先红后绿」；
它们的「有牙」是**用变异探针实测**出来的，上表两行即证据。**这一形态记入 §8.34.5-a 的第六种**：
不是「夹具没牙」，而是「**夹具写在实现之后 ⇒ 没有红灯可看**」—— 补救只能靠变异，代价是**变异点由人挑**
（挑错变异点就会得出「夹具没牙」的错误结论）。

**（4）闸只挂了一条车道 —— 是范围裁剪，须写明。**
§8.34.4 决策 1 说闸「不属于任何一条车道」。**实施落点却是**：`pi-java-ai/src/main` 里只有
`AnthropicMessagesApi` 调它。其余五条车道的请求构建器（`OpenAICompletionsApi`、`GoogleGenerativeAiApi`、
`MistralConversationsApi`、`AzureOpenAIResponsesApi`、`PiMessagesApi`）**都存在、都没挂**。
理由：本包的 18 条夹具全在 Anthropic 车道上，往未取证的车道挂规则＝**在没夹具的地方改行为**。
⇒ **如实定性为「通道已建、只接了一根线」**，后果是那五条车道上跨模型重放**今天仍未生效**
（§8.34.10-（5）已预言「闸的价值主要在非 Anthropic 车道」）⇒ 接线**另立包**。

**本包不含（如实登记，均已进 docs/32）**：
- **B16**：pi 的 `sanitizeSurrogates` 在全仓**无对应物**（pi 侧 54 处调用）。孤对代理字符会让请求体 JSON 非法 ⇒ 400。
- **B17**：`AnthropicMessagesApi.toBlockParams` 对 `ImageContent`/`UrlImageContent`/`DiffContent` **静默丢弃**
  —— pi 在 user 车道把图片映射成 `{type:"image",source:{...}}`（`:1250-1260`），而 pi-java 的
  Google/Responses/PiMessages 三条车道**都**映射了图片 ⇒ 不是「图片进不了 Message」，是 Anthropic 车道独缺。
- **B18**：`PiMessagesApi` 对任何 thinking 块丢签名与 `redacted`（本包刻意不动，见上（1））。

---

### 8.35 响应侧字段覆盖与收尾语义（**B19/B20 均已闭环**）

> **状态**：设计已落地并经用户审核；本包**按 B19 → B20 的顺序逐条实施**。
> **B19（收/发两侧）已闭环**（§8.35.13，`ffc43e2`/`b916d29`/`478fe91`/`7369ae6`）；
> **B22** 顺带修（§8.35.11）；**B20 设计定稿已落地并经用户审核**（§8.35.14 + 实施记录 §8.35.15），
> 实施计划 ⑩ 个提交（③–⑩ **全部落地**；见 §8.35.15 七/八）；D1 的「P0 只读探针」作为首个提交**被用户否决**，
> 改为提交 ④ 的**真实车道门**（已过，见 §8.35.15 六-4）。
> 本包**不占**既有 ③/④ 序号（③ = B5 宿主层 `Error` 通道、④ = B3/B12），文中称 **「响应侧字段覆盖包」**。
> 登记：`docs/32` 的 **B19 / B20 / B21**（B 类 13→16 行，B22/B23 随接线夹具、B24 随 B19、
> **B25/B26 随 §8.35.14 的审计更正**登记）。
>
> ⚠️ **§8.35.14 撤回本节两处结论**（复核 pi 源码所证伪，非笔误）：
> ① §8.35.2 末行「Responses ✅ 已对齐」**作废**（实为 α/β/γ/δ/ε 五处差距）；
> ② §8.35.2 末段「`rawStopReason` 零消费者 ⇒ 不移植」的**结论**作废（读点在**生产者层** Google 车道，2 处）。

#### 8.35.0 立案：一次生产事故，牵出的是一整层

**事故**（用户 2026-09-18 问「为什么中断了」）：会话
`~/.pi-java/agent/sessions/--D--workplaceForai-pi-java--/2026-09-10T15-13-13-817Z_ffffffff-ee64-7816-ee45-2d112ca0ae6d.jsonl`，
provider `openai` / model `glm-5.3-flash` / baseUrl `https://api.teamorouter.cn/v1`
（`~/.pi-java/agent/settings.json`）。最后一个请求 `attempt 6` 于 22:33:06.623 发出，**201 s** 后返回，
relay 报 `usage: input 67416 / output 1014`，而落盘的助手消息是（`seq 275`）：

```json
{"role":"assistant","content":[],"stopReason":"stop","api":"openai-completions",
 "provider":"openai","model":"glm-5.3-flash","usage":{"input":67416.0,"output":1014.0,...}}
```

⇒ **1014 个输出 token 换回一条空消息**，且 `stopReason` 是「正常结束」。前端表现为**静默停住**（不报错）。

**复现**（走**真实 `openai` 车道**，不是手搓 HTTP；`--trace-payloads` +
`-Dpi-java.traces.dir=/tmp/pi-probe`，未污染 `~/.pi-java` 的会话）：

| 探针 | 提问 | pi-java 可见内容 | relay 计费 output |
|---|---|---|---|
| P2 | 1–100 之间质数共几个？ | `{"text":"25"}` —— **2 字符** | **127** |
| P3 | (x+5)×3−12=30，x=? | `{"text":"9"}` —— **1 字符** | **102** |

P2 的答案**是对的**（数出 25 个质数必须真推理）⇒ 推理确实发生过，
**125 / 127 的输出 token 在 pi-java 的消息里不存在**。事故只是同一现象的极端形态：**全部输出都是推理 ⇒ 可见内容为空**。

**这一层有多大**：把「响应侧字段」逐个清点后发现，**不止 reasoning 一处**（§8.35.2），
而且**最重的一处在主车道 Anthropic 上**。故本包名不再叫「completions 车道收尾语义」。

#### 8.35.1 根因一：`openai-completions` 车道的 reasoning 往返（B19）

**（1）收：三个字段名一个都没读。**

| | pi（`openai-completions.ts`） | pi-java（`OpenAICompletionsApi.java`） |
|---|---|---|
| 推理文本 | `:597-620` 依次试 `reasoning_content` / `reasoning` / `reasoning_text`，取**第一个非空** | `:96-105` 只读 `delta.content()` |
| 未知字段的读取方式 | `choice.delta as Record<string, unknown>` | `delta._additionalProperties()` —— **`pi-java-ai/src/main` 全仓只用过一次**（`OpenRouterImagesApi:63`，且在 **message** 上），**delta 上零次** |
| 签名 | `:615-618` **用命中的字段名当 `thinkingSignature`** | 无 |
| 事件 | `thinking_start` / `thinking_delta` / `thinking_end` | 一个都不产 |

**「字段名当签名」是本条设计的关键**：pi 重放时不再猜「该用哪个字段名发回去」，而是**读签名**
（`OPENAI_COMPLETIONS_REASONING_FIELDS.includes(signature)`，`:1316`）⇒ 消息**自描述**。
所以只补「收」而不补「签名」，重放侧仍然发不出去。**两者是一次改动，不可拆。**

⚠️ **两处数组顺序不同，别顺手「修正」成一致**：接收循环的试探序是
`["reasoning_content","reasoning","reasoning_text"]`（`:597`），重放识别用的常量是
`["reasoning","reasoning_content","reasoning_text"]`（`:280-282`）。前者决定「同一条响应里两个字段都有时取哪个」
（chutes.ai 会同时返回两个 ⇒ pi 取 `reasoning_content`），后者只是 `includes` 判定 ⇒ 顺序无关。**照抄。**

⚠️ **`opencode-go` 特例在 pi-java 不可达**：pi 在两处把 `reasoning` 改写成 `reasoning_content`
（`:615-617` 收、`:1312-1314` 发）。pi-java 的内置 provider 里**没有 `opencode-go`**（16 个内置 + models.json），
⇒ **不移植**，但要在代码注释里写明「**有意**省略，理由是 provider 不可达」，否则后来者会当成漏抄。

**SDK 能力（已 `javap` 实证，2026-09-18，`openai-java-core-4.42.0`）**：
`ChatCompletionChunk$Choice$Delta` 有 `_additionalProperties(): Map<String, JsonValue>` ⇒ 未知字段可读；
`Choice.finishReason(): Optional<FinishReason>`，`FinishReason` 同时有 `asString()`（原始线格字符串）与
`known()`（未知值落 `Value._UNKNOWN`，不抛）。⇒ **一律取 `asString()`**，与 pi 的字符串 switch 同形。

**（2）发：pi 有两条独立规则，pi-java 合成了门。**

```java
// :235（现役）
if (!reasoning.isEmpty() && "deepseek".equalsIgnoreCase(provider)) {
    ab.putAdditionalProperty("reasoning_content", com.openai.core.JsonValue.from(reasoning.toString()));
}
```

pi 的两条（**必须拆开照抄，不能合成一个门**）：

| | pi 位置 | 条件 | 行为 |
|---|---|---|---|
| (i) 签名回填 | `:1310-1318` | 第一个非空 thinking 块的签名 ∈ 三个已知字段名 | `assistantMsg[签名] = 全部 thinking 块 join("\n")`，**无 provider 门** |
| (ii) 补空串 | `:1356-1362` | `compat.requiresReasoningContentOnAssistantMessages`（`detectCompat:1643` **＝ `isDeepSeek`**）**且** `model.reasoning` **且** 上面没写过 | `assistantMsg.reasoning_content = ""` |

⇒ 现役那个 `"deepseek"` 门**不是错的**：它是 (ii) 的**近似**（`isDeepSeek` 还看 baseUrl 含 `deepseek.com`），
但它**顶替**了本该独立存在的 (i)，缺了 `model.reasoning` 这一半条件，且把 (i)(ii) 合成了一个动作。
后果：**在非 deepseek 的 relay 上，thinking 块永远发不回去**（pi 会发）⇒ 多轮推理连续性在 pi-java 上不存在。

⚠️ **`content` 的形态别动**：pi `:1300-1307` 有一段注释说明 assistant 的 `content` **必须是纯字符串**
（送 `[{type:"text",...}]` 数组会让某些模型（DeepSeek V3.2 via NVIDIA NIM）**照着回显块结构**，
产出 `[{'type':'text','text':'[{...}]'}]` 这种递归嵌套）。pi-java `:239` 已是纯字符串 ⇒ 保持。

#### 8.35.2 根因二：stop reason 映射跨车道缺失（B20）—— **本包最重的一条**

**审计**（五条「有响应」的车道逐个查，全部实测）：

| 车道 | pi-java 现状 | pi 参照 | 差在哪 |
|---|---|---|---|
| **Anthropic** | `:190-193` 处理 `message_delta` **只取 `usage`**；`event.delta.stop_reason` **全文件零读取**；`:94` 硬写 `toolCallSeen[0] ? "tool_use" : "end_turn"` | `anthropic-messages.ts:743-745` + `mapStopReason(:1464-1493)`：`end_turn`→stop、`max_tokens`→**length**、`tool_use`→toolUse、`refusal`→error+explanation、`pause_turn`/`stop_sequence`→stop、`sensitive`→error、**未知值抛错** | `max_tokens` / `refusal` / 未知值**全丢** |
| **openai-completions** | `:132` 硬写 `toolCall.started() ? "tool_use" : "stop"` | `:571-577` + `mapStopReason(:1550-1571)`：stop/end→stop、length→length、function_call/tool_calls→toolUse、content_filter/network_error→error+text、未知→error+text | 同上 |
| **Google** | `:149-156` 把 `finishReason().toString().toLowerCase()` **原样透传** | `google-shared.ts:379-411`：`STOP`→stop、`MAX_TOKENS`→**length**、其余（SAFETY/RECITATION/…共 15 种）→**error** | `MAX_TOKENS`→`"max_tokens"`（≠length）、`SAFETY`→`"safety"`（≠error） |
| **Mistral** | `:151-154` 只把 `tool_calls` 归一成 `tool_use`，其余原样 | `mistral-conversations.ts:926-941`：`stop`→stop、`length`/**`model_length`**→length、`tool_calls`→toolUse、`error`→error+text、未知→error+text | 无 error 兜底、无 `model_length`、**无 errorMessage** |
| **Responses** | `:190-197` `mapStopReason(...)` | `openai-responses-shared.ts:763-796` | ~~✅ 已对齐（唯一一条）~~ ⚠️ **该判定已在 §8.35.14 第四节作废**：复核出 **α/β/γ/δ/ε 五处**差距（default 不抛、`incomplete` 无文案、`done("error")`、`response.failed` 文案、出错后继续消费） |

**为什么这条比「少一个字段」严重得多 —— 它是 `length` 机制的活命条件。**

`length` 机制**已经实现了**（§8.21 包 3c、L1 ③），消费点五处：`PiLoopRunner:108`、
`ContextOverflow:118`/`:138`、`CompactionExecutor:310`、`PiLaneSink:367`、`LlmSummaryGenerator:160`。
**但在主车道 Anthropic 上 `stopReason` 永远不会是 `"length"`** ⇒ 五处**同时失效**。最危险的是第一处：

```java
// PiLoopRunner:106-108（现役）
// pi: length 截断 ⇒ 全部失败，不执行（:206-208 分派，:379-404 实现）
var batch = PiLoopTools.run(toolCalls, context, config, emit,
    "length".equals(message.stopReason()));
```

pi 的规则是「**length 截断 ⇒ 本回合全部工具调用判失败、不执行**」（`agent-loop.ts:206-208`）
—— 被 `max_tokens` 截断的工具参数**可能是不完整的 JSON**。pi-java 在主车道上拿不到 `length`
⇒ 传 `false` ⇒ **这些截断调用会被真的执行**。（Mistral 车道同样；Google 车道透传成 `"max_tokens"`，也不是 `"length"`。）

⚠️ **为什么一直没被发现**：`length` 机制的夹具**全部直接构造** `AssistantMessage(stopReason="length")`，
**没有一条从线格走到消息**。这是 §8.33 教训形态 (6)「夹具写在实现之后 ⇒ 没有红灯可看」的**跨层**复现
（夹具在宿主层，缺口在协议层）⇒ 本包的夹具计划必须包含**跨层那一条**（§8.35.6 末行）。

**另有一处口径分歧：`"end_turn"`。**

`AnthropicMessagesApi:94` 的 `"end_turn"` 是 pi-java **自有**取值，且**自家文档都不一致**：

- `AssistantMessage.java:32` 把它列为 `stopReason` 的**首选**取值；`StreamEvent.java:199` 同；
- **但 `LaneState.java:261` 的词汇表是 `"stop" | "tool_use" | "error" | "length" | null`** —— **不含 `end_turn`**；
- 全仓夹具（`TransformMessagesTest`、`AnthropicThinkingReplayTest`、`JsonlSessionStorageTest`、`StreamEventTest`…）都按 `end_turn` 写。

而 pi 在这一处的取值是 **`"stop"`**（`anthropic-messages.ts:1466`）⇒ **每一份 Anthropic 会话转录里
`stopReason` 与 pi 不同**；转录是**载荷**，按 §8.18 A7 的口径属于对齐面。
⇒ 列为本包**裁决点 D2**（§8.35.8），**不擅自改**（要动一批夹具，且既有转录已在盘上）。

**顺带复核 `rawStopReason`（`:1080` 的旧裁定）**：旧裁定说它在对齐面「零消费者」⇒ 不移植。
本次**全仓复核推翻了该结论**（详见 §8.35.14 第五节）——「在对齐面 `packages/agent/src` 没有消费者」这句**是对的**，
但**读点在生产者层**：`google-generative-ai.ts:272-273` 与 `google-vertex.ts:289-290` 两处用它拼
`"Provider stopped with: ${raw}"`，而那正是**本包第五节 Google 车道收尾文案的唯一来源**。
原文的「八个适配器写它，零个消费者读它」已作废（写点 10 处、读点 2 处）。
⇒ **移出「本包不引入」清单**，改列**裁决点 D5**（§8.35.14 第五节）。

#### 8.35.3 非本事故因素：请求侧 `compat.thinkingFormat`（B21）

pi 的请求侧有 **10 种** thinking 开关形状（`openai-completions.ts:866`/`:879`/`:887`/`:892`/`:897`/
`:914`/`:924`/`:934`/`:939`/`:948`：zai / qwen / qwen-chat-template / chat-template / baseten /
deepseek / openrouter / ant-ling / together / string-thinking；其中 `detectCompat:1644-1654` 只会**产出 6 种**，
其余 4 种只有用户显式写 `model.compat` 才可能出现）。pi-java 请求侧**没有这个概念**
（`DefaultProviders:112-116` 只放 `thinking.budgetTokens`）。

**但如实标注：这与本次事故无关。** `api.teamorouter.cn` **不匹配 pi 的任何探测模式**
（`detectCompat:1581-1600` 逐条比对：z.ai / together / moonshot / openrouter / cloudflare / nvidia /
ant-ling / deepseek 全不匹配）⇒ **pi 在该 relay 上什么都不发**。
能力缺口为真，但它管「**发什么请求**」，与本包「响应侧字段覆盖」不是同一层 ⇒ **须自己一包**，本包不碰。

#### 8.35.4 前置依赖：B10 的闸只有一根线 —— 本包的**前置**，不是后续

§8.34.11-（4）已如实定性：「通道已建、只接了一根线」——`TransformMessages` 只被
`AnthropicMessagesApi` 调用。**本包让 B19 落地后，completions 车道开始产 thinking 块**，
一旦跨模型重放（换成 Anthropic 模型），那条车道就**带着 thinking 块**发请求，而闸不在这条线上
⇒ 会**引入**一条比今天更错的路径。⇒ 顺序：**B10 接线 → B19 → B20**（B10 的剩余范围见 `docs/32` B10）。

#### 8.35.5 修法落点

| # | 文件 | 改什么 | 参照 |
|---|---|---|---|
| 1 | **B10 剩余**：`OpenAICompletionsApi` / `GoogleGenerativeAiApi` / `MistralConversationsApi` / `AzureOpenAIResponsesApi` 的请求构建处 | 挂 `TransformMessages` 闸（**`PiMessagesApi` 除外** —— 它不是 pi 的车道，§8.34.11-（1）） | `transform-messages.ts:95-116` |
| 2 | `OpenAICompletionsApi:94-105` | content 之前按 pi 顺序探三个 reasoning 字段；命中 ⇒ `emitThinkingStart("", 字段名, false)` + `emitThinkingDelta(text)`；收流前 `if (thinkingStarted) emitThinkingEnd()` | `:597-620`、`:478-489` |
| 3 | `OpenAICompletionsApi:200-240` | 拆成 (i)(ii) 两条（§8.35.1-2）：去掉 `"deepseek"` 门、改读**第一个非空 thinking 块的签名**并校验 ∈ 三字段名；`requiresReasoningContentOnAssistantMessages` 那条另写 | `:1310-1318`、`:1356-1362`、`:1643` |
| 4 | `AnthropicMessagesApi:190-193` + `:94` | `message_delta` 里加读 `event.delta().stopReason()` / `stopDetails()` → `mapStopReason`；`:94` 的硬写换成映射结果 + 无 `message_delta` 时的兜底 | `:743-745`、`:1464-1493` |
| 5 | `OpenAICompletionsApi:132` | 同 4（`choice.finishReason()` → `asString()` → 映射） | `:1550-1571` |
| 6 | `GoogleGenerativeAiApi:149-156` | 换掉 `toLowerCase()` 透传 | `google-shared.ts:379-411` |
| 7 | `MistralConversationsApi:151-154` | 补 `length`/`model_length`/`error`/未知 + errorMessage | `mistral-conversations.ts:926-941` |
| 8 | `ModelCompat` | 加两个 flag：`supportsFinishReason`（**默认 `true`** —— pi 的 `detectCompat:1638` 是常量 true，只有用户显式关才 false）、`requiresReasoningContentOnAssistantMessages`（默认 = provider 名 `deepseek` 或 baseUrl 含 `deepseek.com`，`detectCompat:1643`）。⚠️ 前者默认方向与 `allowEmptySignature`（`?? false`）**相反** ⇒ 必须写进 record 的 javadoc，否则下一个人会照 `NONE` 的语义读错 | `types.ts:713-714`、`:1638`、`:1643`、`:1691-1700` |
| 9 | `ModelsJsonSchema.CompatDef` | 暴露上面两个（否则用户关不掉 `supportsFinishReason`） | `docs/31 §8.34.4` |

**映射函数放哪**：**按车道各写一份**（不抽公共工具类）—— pi 自己就是四份独立的 `mapStopReason`
（`anthropic-messages.ts:1464` / `openai-completions.ts:1550` / `google-shared.ts:379` /
`openai-responses-shared.ts:763`，**case 集各不相同**；`google-shared` 还多一个 `mapStopReasonString`），
抽公共会把四套语义塞进一个 switch。`ResponsesStreamProcessor:190-197` 已是正确范式：**照它的形状写另外四份**
（含「pi 注释标 wonky 的照抄」这种注释级细节）。

**错误文本通道已通，无需新管道**：pi 在 `stopReason === "error"` 时
`throw new Error(errorMessage || "Provider returned an error stop reason")`（`:687-689`）⇒ 文本随异常走；
pi-java 的等价物是 `emitError("error", new RuntimeException(文本))`，由
`PiLoopRunner:318-333 withErrorShape`（`err.error().getMessage()`）落到消息的 `errorMessage` 上。
**这条通道是活的且致命的**：`RetryableError:141` 与 `ContextOverflow:89-90` 都读它
（§8.22：3d 的重试环白名单要求非空 `errorMessage`）⇒ 映射产出的 errorMessage 文本必须**逐字**照抄 pi。

#### 8.35.6 夹具计划（**先红证毕**）

夹具形态**已有范式**：`OpenAIResponsesApiTest` 用 `com.sun.net.httpserver.HttpServer` 起本地 SSE 服务，
喂固定线格、断言事件序列（`textFlowEmitsTextEventsThenDone` / `thinkingFlowEmitsThinkingEvents` 等）。
**注意：Google 与 Mistral 两条车道现在连一个测试类都没有**（`pi-java-ai/src/test/.../protocol/` 下无
`GoogleGenerativeAiApiTest`、无 `MistralConversationsApiTest`）⇒ 本包要为它们**新建**测试类。

| 夹具 | 喂什么线格 | 断言 | 今天应当 |
|---|---|---|---|
| `OpenAICompletionsApiTest`（新增流用例） | `delta:{reasoning_content:"…"}` 后接 `delta:{content:"…"}`，末帧 `finish_reason:"stop"` | `ThinkingStart` 签名 == `"reasoning_content"`；`ThinkingDelta` 文本；`TextStart` 在其后 | **红**（现在不产任何 thinking 事件） |
| 同上 | `finish_reason:"length"` | `StreamDone.reason() == "length"` | **红**（现在是 `"stop"`） |
| 同上 | `finish_reason:"content_filter"` | `StreamError` 且文本 == `"Provider finish_reason: content_filter"` | **红** |
| 同上 | **整条流无** `finish_reason` | `StreamError` 且文本 == `"Stream ended without finish_reason"` | **红**（依 D1） |
| 同上 | 重放：thinking 块签名 == `"reasoning"` | 出参 assistant 消息带 `reasoning` 键、值为 thinking 文本 | **红**（现在只给 `deepseek` 发 `reasoning_content`） |
| `AnthropicMessagesApiTest`（新增流用例） | `message_delta:{stop_reason:"max_tokens"}` | `StreamDone.reason() == "length"` | **红**（现在 `"end_turn"`） |
| 同上 | `message_delta:{stop_reason:"refusal", stop_details:{explanation:"…"}}` | `error` + explanation 文本 | **红** |
| 同上 | `stop_reason:"end_turn"` | 依 D2 | 依 D2 |
| `GoogleGenerativeAiApiTest`（新类） | `finishReason:"MAX_TOKENS"` / `"SAFETY"` | `"length"` / `"error"` | **红** |
| `MistralConversationsApiTest`（新类） | `finish_reason:"model_length"` / 未知值 | `"length"` / `"error"` + 文本 | **红** |
| **跨层回归门**（本包核心） | 线格 ⇒ 消息 ⇒ `PiLoopRunner` | `length` 截断时 `PiLoopTools.run` 收到 `true`（本回合工具**不执行**） | **红** |

⚠️ 每条**先跑一遍存证**，把 actual 抄回本节（§8.34.5 的做法）—— 本仓既有纪律。

#### 8.35.7 本包不含（如实登记）

- **`reasoning_details`**（OpenRouter / llama.cpp 的**结构化**形状）：`openai-completions.ts:661-671` 收、
  `:342` 把它 `JSON.stringify` 进签名位、`:1283` `parseOpenAIReasoningDetails` 发。**另一套机制**，
  本包只做三个**纯文本**字段名。可达性未取证的，不顺手做。
- **`requiresThinkingAsText`**（`:1293`：把 thinking 当**文本**发回 `content` 而不是发回 reasoning 字段）：
  `detected` **恒 false**（`:1642`），只有用户显式写 `model.compat` 才开；pi-java 无此 flag ⇒ **不可表达**。
  ⚠️ 它与 B19 的修法**在同一处**（真要加是 4 行），但**无用户需求证据** ⇒ 本包不做。
- **B21**：请求侧 `thinkingFormat`（§8.35.3）。
- **`rawStopReason`**：~~维持不做（§8.35.2 末）~~ ⚠️ **已改判** —— 该条「零消费者」的结论在
  §8.35.14 第五节被证伪，改列**裁决点 D5**（建议全量移植）。
- **Responses 车道不看 `supportsFinishReason`**：pi 的 responses 映射**没有**该 compat 分支
  （`:763-796`，缺 status 直接 `return {stopReason:"stop"}`）⇒ pi-java 照抄「不看」，**不补**。
- **Bedrock 等 pi 有、pi-java 没有的车道**：无车道 ⇒ 无「响应侧」可覆盖。

#### 8.35.8 裁决点（**请用户拍板**）

| # | 问题 | 选项 | 我的建议 |
|---|---|---|---|
| **D1** | 缺 `finish_reason` 时的**严格程度** | (a) 照 pi：`supportsFinishReason` 默认 true ⇒ **抛**「Stream ended without finish_reason」；(b) 默认容忍（缺失时按「有 toolCall ⇒ tool_use，否则 stop」） | **(a)**，但**先测**：pi-java 的历史日志里**没有原始帧**，**这个 relay 到底发不发 `finish_reason` 从未被观测过**。（a）若猜错会让今天能跑的 relay 直接失败。⇒ **实施第 1 个提交 = P0 只读不判**：把读到的 `finish_reason`（**含缺席**）落进诊断/日志，用**现有 provider** 跑一次，据观测结果决定严格版。P0 零行为改动，但**要写代码** ⇒ 请裁决它能否作为本包第 1 个提交 |
| **D2** | Anthropic 车道的 `"end_turn"` 口径 | (a) 改成 pi 的 `"stop"`；(b) 保留 `end_turn`，只补 `max_tokens`/`refusal` 等**非正常**取值 | **(a)** —— 「正常结束」是**每一条**助手消息的取值，口径分歧最大；且全部消费点只比较 `error`/`length`/`aborted` ⇒ 盘上的旧转录不会坏 |
| **D3** | Google / Mistral 两条车道**是否并入本包** | (a) 并入（一次把「收尾语义」做齐）；(b) 只做 Anthropic + completions | **(a)** —— 四条车道共用同一参照系与同一夹具形状，拆开会把审计表撕成两半；**但提交仍分车道**（每车道一次 commit，200–500 行） |
| **D4** | 顺序 | B10 接线**先于**本包？（§8.35.4） | **是** |

#### 8.35.9 验证与不做

**验证**：① 每条夹具**先红证毕**（actual 抄回 §8.35.6）；② 跨层回归门（线格 → 消息 → `PiLoopRunner`）；
③ `mvn -o clean verify` 全 reactor 绿 + checkstyle 零违规；④ **真实车道回归**：用 `openai` 车道重跑
P2/P3，确认「可见 token ≈ 计费 token」（今天 2/127 ⇒ 目标 >100/127）；⑤ Anthropic 车道重跑一条真实请求，
核对转录 `stopReason`（依 D2）。**探针仍走真实车道、trace 目录重定向**，不碰 `~/.pi-java` 的会话。

**不做**：不动 `content` 的纯字符串形态（§8.35.1-2）；不抽公共 `mapStopReason`（§8.35.5）；
~~不引入 `rawStopReason`~~（**已改判**，见 §8.35.14 第五节 D5）；不做 `reasoning_details` / `requiresThinkingAsText`；不改请求侧 `thinkingFormat`；
不碰 `PiMessagesApi` 的闸与它自己的 wire 形状（§8.34.11-（1）的裁决不变）。

#### 8.35.10 B10 接线夹具「先红证毕」：5 红 1 绿，其中 **2 条红得不是地方**（新登记 B22/B23）

**夹具**：`pi-java-ai/src/test/java/com/pijava/ai/protocol/LaneTransformMessagesWiringTest.java`
（新增）。**为什么用录制请求体的 HTTP 桩而不是反射私有构建器**：本包正要给各车道的构建器
**加形参**（api 名）⇒ 反射夹具会在实现落地的瞬间编译不过，「先红」会退化成编译期红、看不到
行为红；桩录的是**请求字节**，形参怎么改都不影响它。它同时是**唯一**能守住「五条车道都挂了闸」
的夹具 —— `TransformMessagesTest` 只证**闸本身**（把某条车道的接线拆掉，它照样全绿）。

**实测**（`mvn -o -pl pi-java-ai test -Dtest=LaneTransformMessagesWiringTest`）：
`Tests run: 6, Failures: 5, Errors: 0`。逐条 actual（**已抄回**）：

| 车道 | actual 请求体 | 红的原因 |
|---|---|---|
| openai-completions | `{"messages":[{"content":"hi","role":"user"},{"role":"assistant","content":"VISIBLE"}],"model":"gpt-4o",...}` | **对**：thinking 文本被 `:235` 的 `"deepseek"` 门拦下 |
| google | `{"contents":[{"parts":[{"text":"hi"}],"role":"user"},{"parts":[{"text":"VISIBLE"}],"role":"model"}],...}` | **对**：`toGoogleContents:212-213` 返回 `List.of()` |
| mistral | `{"stream":true,"messages":[{"role":"user","content":"hi"},{"role":"assistant","content":"VISIBLE"}],...}` | **对**：`extractText` 只收 `TextContent` |
| openai-responses | `""` | ❌ **不是地方**：请求**根本没发出**（见 B22） |
| azure-openai-responses | `""` | ❌ **同上** |
| （回归门）同模型 deepseek 重放 | — | **绿**（预期） |

**B22：responses 车道回放助手文本消息即抛 —— 缺 `id`。**

```
java.lang.IllegalStateException: `id` is required, but was not set
  at com.openai.core.Check.checkRequired(Check.kt:12)
  at ...ResponseOutputMessage$Builder.build(ResponseOutputMessage.kt:348)
  at ResponsesMessageConverter.addAssistantItems(ResponsesMessageConverter.java:194)
  at ResponsesMessageConverter.convertMessages(:115) → buildParams(:60/:47)
  at OpenAIResponsesApi.streamInternal(OpenAIResponsesApi.java:54)
```

pi 侧**有**回填（`openai-responses-shared.ts:228-242`）：文本块的 id 取
`parseTextSignature(textBlock.textSignature)?.id`，**取不到时**回退
`msg_pi_${msgIndex}`（首个文本块）或 `msg_pi_${msgIndex}_${textBlockIndex}`（后续文本块），
超 64 字符再压成 `msg_${shortHash(msgId)}`（「OpenAI requires id to be max 64 characters」）。
pi-java 的 `:186-194` **一个都不设** ⇒ SDK 必填校验直接抛。

- **可达性（如实标注）**：该车道今天**从 CLI 不可达** —— `DefaultProviders.apiOptions:147`
  恒传 `Map.of()`，而协议覆盖读的是 `ApiOptions.extra["protocol"]`（`ConfigurableProvider:108-113`）
  ⇒ 无人设置；`ModelsJsonProvider:61-69` 只认 `openai-completions`/`anthropic-messages`；
  `AZURE_OPENAI_RESPONSES` 全树**只有枚举本身**（`Protocol.java:28`）⇒ Azure 车道**没有任何
  provider 创建它**。故属**潜在**缺陷，不是线上已在崩的东西。
- **但仍必修**：① 它是本包该车道夹具的**前置** —— 不修则夹具永远红在「没发出请求」上，
  「闸有没有挂上」这件事**测不到**；② 它是 pi 有、pi-java 无的**纯移植缺口**，
  对称号成立（判据是行为，不是有没有人已经踩到）；③ 修法只有 10 行，且**照抄 pi 的取值规则**。

**B23：文本块无 `textSignature`（新登记，本包不做）。** pi 的文本块带签名
（`encodeTextSignatureV1(item.id, item.phase)`，`openai-responses-shared.ts:701`），pi-java 是
`record TextContent(String text)`（`ContentBlock.java:29`）—— 组件都没有。后果：即便修了 B22，
回填的 id 也**只能永远是 `msg_pi_N` 合成值**，pi 那条「取回原 `msg_xxx` / `phase`」的路径不可达。
这属于「文本块载荷」的缺口（与 §8.35.1 的 thinking 载荷同族但**另一条**），须自己一包。

**教训（进 §8.35.6 的夹具纪律）：「先红证毕」必须**同时**复核**红的原因**。**
本包 5 条红里有 **2 条**是因为**请求压根没发出去**——若只看「5 红」就开工，这两条车道会在实现
落地后**要么永远红、要么被顺手改成假绿**（例如把断言放宽成「不抛异常就算过」）。判据是
**actual 里得看得见那一层的东西**：本包要求 actual 是**请求体**，一旦是 `""` 就说明红在更前面。

#### 8.35.11 B10 接线**已闭环**（4 个提交）+ 顺带修 B22

**落地提交**（`b-class-gap-fill` 分支）：

| 提交 | 内容 |
|---|---|
| `9953790` | 夹具先红证毕 + 登记 B22/B23（§8.35.10） |
| `55ade86` | **B22**：responses 车道回放助手消息补 `id` |
| `a7d55d8` | 接线 1/4：`openai-completions`（`buildParams` 加 `apiName` 形参） |
| `d2a9c44` | 接线 2/4：`google` + `mistral`（各一行过闸） |
| `175a383` | 接线 3/4：`openai-responses` + `azure-openai-responses`（共用转换器，`apiName` 由调用方传） |

**最终状态**：六条车道**全部**过共享预通道 —— `anthropic-messages`（包②已挂）+ 上表五条；
**`pi-messages` 按裁决仍不挂**（§8.34.11-（1）：它不是 pi 的车道，给它按 pi 的闸改行为＝发明）。
`LaneTransformMessagesWiringTest` **6/6 绿**；`mvn -o clean verify` 全 reactor 绿（14 模块，
checkstyle/spotbugs 零违规）。

**接线形状（给下一个人的三条）**：

1. **apiName 一律由调用方传，不在转换器里写常量。** 闸的同模型判据是
   `Objects.equals(msg.api(), apiName)`，而 `msg.api()` 是 `AbstractChatApi` 用**它自己的
   `apiName()`** 盖的章 ⇒ 两处必须同源。responses 两条车道共用
   `ResponsesMessageConverter` 但 api 名不同，正是这条的试金石：所以把 `buildParams`
   收成唯一签名 `(request, ropts, modelName, apiName)`、**删掉 2 参重载** ——
   留着重载就是留一个「传错名字也编译得过」的口子。
2. **闸必须先于车道自己的映射。** pi 六处调用点全在各自的消息转换之前
   （`transform-messages.ts` 的输出再进 `convertMessages`）。反过来做（先映射再补闸）
   等于没挂：thinking 块在映射里已经被丢掉了（Google/Mistral/Responses 三条车道今天
   就是这么丢的）。
3. **不抽公共 `mapStopReason`，同理不抽公共「过闸包装层」。** 前者是 pi 自己的形状
   （四份独立函数），后者目前只是 5 行重复；`ResponsesMessageConverter` 那种
   「共用一个转换器、各自传名字」是**例外**而不是可以推广的范式。

**顺带的存量为证**：把闸挂上后，`OpenAICompletionsApiRequestTest` 两条既有用例转红 ——
它们用 `new AssistantMessage(content)`（api/provider/model 全 null）构造助手消息，
**信息量本来就不足以区分「同模型」与「跨模型」**（闸落地前这个区别在该车道上不影响输出，
所以没人发现）。修法是**把前提补明**（给消息真实身份），断言原样不动 —— 不是放宽断言。
这一类「夹具缺前提」与 §8.34.11 的「夹具写在实现之后」是**同族但不同**的形态：
那个是**没有红灯可看**，这个是**有红灯但红灯换了个意思**。

#### 8.35.12 B19 夹具「先红证毕」：13 红 4 绿（其中 3 条是**两侧同绿**的对照）

**夹具**（新增两个类，共 17 个用例）：

| 类 | 覆盖 | 形态 |
|---|---|---|
| `OpenAICompletionsReasoningTest` | **收**（6 例） | 本地 HTTP server 喂线格 SSE，走真实 SDK 路径（同 `OpenAIResponsesApiTest`） |
| `OpenAICompletionsReasoningReplayTest` | **发**（11 例） | 录制请求体的桩（同 `LaneTransformMessagesWiringTest`），断言打在**序列化后的请求体**上 |

**红 13**（actual 逐字抄回）：

| 用例 | actual | 为什么算红得对 |
|---|---|---|
| `reasoningContentDeltaEmitsThinkingBlockSignedWithFieldName` | `["Start","TextStart","TextDelta","TextEnd","StreamDone"]` | 请求**发出去了**、文本**收到了** ⇒ 红在「thinking 事件缺失」这一层，不在更前面 |
| `reasoningFieldIsAcceptedAndBecomesTheSignature` | `NoSuchElement` at `thinkingSignature`（**没有** `ThinkingStart`） | 同上 |
| `probeOrderPrefersReasoningContentWhenBothArePresent` | `NoSuchElement`（同上） | 同上 |
| `reasoningAccumulatesIntoASingleBlock` | `["Start","TextStart","TextDelta","TextEnd","StreamDone"]` | 同上 |
| `endsFollowBlockCreationOrderNotAFixedOrder` | `["Start","TextStart","TextDelta","TextEnd","StreamDone"]` | 同上 |
| `reasoningContentSignatureReplaysThatFieldName` | 请求体 `{"messages":[{"content":"hi","role":"user"},{"role":"assistant","content":"answer"}],"model":"glm-5.3-flash","stream_options":{"include_usage":true},"stream":true}` | **这是事故第二半的实证**：provider `openai`（非 deepseek）⇒ 带签名的 thinking 块**整块发不回去** |
| `reasoningSignatureReplaysThatFieldName` | 同上请求体 | 同上 |
| `multipleThinkingBlocksJoinWithNewline` | 同上请求体 | 同上 |
| `whitespaceOnlyThinkingBlocksAreExcludedFromTheJoin` | 同上请求体 | 同上 |
| `unknownSignatureDropsTheTextAndKeepsOnlyTheEmptyFill` | `…{"role":"assistant","content":"answer","reasoning_content":"REASON-A"}` | 现役代码**不看签名**：未知签名照样按 `reasoning_content` 发 |
| `thinkingOnlyAssistantMessageIsDropped` | `…{"role":"assistant"}`（**既无 content 也无 tool_calls**） | 今天真会发出这种消息（部分 provider 直接 400）；pi `:1365-1372` 是 `continue` |
| `deepseekProviderGetsTheEmptyReasoningContent` | `…{"role":"assistant","content":"answer"}` | 规则 (ii) 今天不存在 |
| `deepseekBaseUrlGetsTheEmptyReasoningContent` | `…{"role":"assistant","content":"answer"}`（model 名 `some-reasoner`） | 同上 |

**绿 4，必须分开定性**：

1. `nonStringReasoningValueIsIgnored`（收侧）：今天**绿**是因为**什么都不收** —— 它两侧同绿，
   **不是证据**。它的牙要靠**变异探针**证明（去掉 `typeof === "string"` 那半 ⇒ 必须转红）。
2. `nonReasoningModelGetsNoEmptyFill` / `ordinaryRelayGetsNoEmptyFill` / `explicitCompatFalseDisablesTheEmptyFill`
   （发侧）：三条**对照**，同样两侧同绿。前两条钉住 (ii) 的**两个合取项**
   （`compat && model.reasoning`）、第三条钉住**显式覆盖**。
3. ⇒ **教训（新形态）**：**「两侧同绿」的夹具必须配一条变异探针**，否则它只是装饰。
   这与 §8.35.10 的「红得不是地方」是一对：那条说**红要红对**，这条说**绿也要问为什么绿**。

**夹具前置**：`ModelCompat` 加了第二个组件
（`Boolean requiresReasoningContentOnAssistantMessages`，**三态**：`null` = 探测、
`true`/`false` = 用户显式覆盖）。它是 `explicitCompatFalseDisablesTheEmptyFill` 的**编译前置**
（不给就写不出「显式 false ≠ 未指定」这条），且**落地时零消费者**（行为改动为零，
所以不影响其它 13 条红的性质）。`ModelCompat.of(boolean)` 与 `NONE` 语义不变
（`NONE = (false, null)`），既有调用点与 `ModelsJsonConfigTest` 全部原样。

**顺带登记（读 pi 该函数时发现，不在 B19 范围）**：

**B24** —— `choice.usage` 回退缺失。pi 在 `openai-completions.ts:565-568` 有一段：
`chunk.usage` 缺席时再读 `choice.usage`，注释点名 **Moonshot** 把 usage 放在 choice 里。
pi-java `OpenAICompletionsApi:124-127` 只看 `chunk.usage()` ⇒ 那条 relay 上**计费永远是 0**
（而 `~/.pi-java` 的用量统计、压缩阈值、上下文估算全都吃 usage）。

#### 8.35.13 B19 **已闭环**（4 个提交，收/发两侧都落地）

| 提交 | 内容 |
|---|---|
| `ffc43e2` | 夹具先红证毕（13 红 4 绿）+ `ModelCompat` 第二组件**编译前置** + §8.35.12 + B24 登记 |
| `b916d29` | **收**：探三个线格字段、命中的名字写成 signature、块结束按建块序发 |
| `478fe91` | **发**：签名自描述回放（规则 i）+ 空串回填（规则 ii）+ 落线跳过规则 + `baseUrl` 请求期探测 |
| `7369ae6` | models.json 的 `compat` 暴露 `requiresReasoningContentOnAssistantMessages` |

**收侧**（`OpenAICompletionsApi.streamInternal`）：`delta._additionalProperties()` 上按
`reasoning_content` → `reasoning` → `reasoning_text` 探**第一个非空字符串**，命中的字段名
**原样**当 `thinkingSignature`（pi `:597-620`）。`typeof === "string"` 那条守卫靠
`JsonField.asString()` 表达（数字/对象取空）。收尾事件改按**建块序**发（pi `:674-676`），
用 `ArrayDeque<Supplier<StreamEvent>>` 记序 —— **不能**写死「先 thinking 再 text」：同一个
delta 里两者都有时 pi 先处理 content（`:584` 在 `:597` 之前）⇒ 文本块先建。

**发侧**（`addAssistantMessage`）两条独立规则：

- **(i) 签名即线格名**（pi `:1310-1318`，**无 provider 门**）：签名命中
  `["reasoning","reasoning_content","reasoning_text"]` 才发，多块以**单个** `"\n"` 连接；
  签名取**第一个非空块**的（`:1313`）。⚠️ 这个数组与收侧的探测数组**顺序不同且必须分开**
  （`:278` vs `:597`）—— 收侧顺序有意义（chutes.ai 两个字段同时给、`reasoning_content` 胜），
  发侧只是 `includes` 测试。
- **(ii) 空串回填**（pi `:1356-1362`）：**两个合取项** —— 家族的 compat 判据
  （provider 名精确等于 `deepseek` **或** baseUrl 小写含 `deepseek.com`，`:1592`）
  **且** `model.reasoning`（= `ModelCapability.THINKING`，`ModelsJsonConfig:193` 的映射）。
  ⚠️ 门是「`reasoning_content` **这个键**还没被写」而非「什么都没写」：签名是 `reasoning`
  时 pi **两个字段都发**（`reasoning` 有内容、`reasoning_content` 空串）
  —— `unknownSignatureDropsTheTextAndKeepsOnlyTheEmptyFill` 与
  `reasoningSignatureReplaysThatFieldName` 从两个方向钉住这一条。

**顺带修掉的一条落线规则**（pi `:1365-1372`）：既无文本又无工具调用的助手消息整条丢掉。
旧守卫是 `if (!text.isEmpty() || !toolCalls.isEmpty() || !reasoning.isEmpty())` ——
多出来的第三个析取项正好把该丢的那种消息发出去（行车事故里「1014 token 换回空消息」的形状）。
⚠️ **reasoning 不算内容**：只带 thinking 的消息**就是要丢的那种**。

**`baseUrl` 走请求期而不是目录期**：pi 从 `model.baseUrl` 判，pi-java 的 `ModelInfo` 没有
baseUrl，有效值在适配器构造时定下 ⇒ 存成字段、由 `buildParams(request, apiName, baseUrl)`
第三参透传。这样 `--base-url` / settings 覆盖才影响判据（目录期烘死会看不见它们）。
5 处既有调用点随之更新（4 处直接调用 + 1 处反射 `getDeclaredMethod`）。

**有意不移植**：① opencode-go 的签名改写（`:615-617`）—— pi-java 无该 provider，
分支不可达，注释里写明了；② `reasoning_details` 那层（结构化形状）—— 仍在 B19 行之外。

**两个存量用例编码的正是被替换掉的近似**，随语义改写（不是删）：

| 用例 | 改法 |
|---|---|
| `deepseekThinkingContentIsRoundTripped` | thinking 块补上签名 `reasoning_content`（钉 (i)，**与 provider 无关**那一点由此可见：`StreamRequest.of` 合成的最小 `ModelInfo` 没有 THINKING 能力，它照样绿） |
| `nonDeepseekThinkingIsNotRoundTripped` → `unsignedThinkingIsNotRoundTripped` | **空签名**的块不发 —— 决定权在签名、不在 provider 名 |

⇒ 后者改名是**结论变了**的标记：旧名字断言的是「非 deepseek 不回放」，而 pi 的真规则是
「没有签名不回放」。旧代码把这两件事混成一件（provider 名开闸），收侧写完签名才分得开
—— **收侧写签名与发侧读签名是同一个决定的两半**，只做一半会让人以为「非 deepseek 不需要」。

**验证**：`pi-java-ai` 396/396（395 + 新的入口夹具）；全 reactor `mvn -o clean verify` 绿、
checkstyle 零违规。发侧 11/11 绿（此前 8 红），其中三条**两侧同绿**的对照各做**变异探针**、
均**恰一条红**：去掉 `model.reasoning` 合取 ⇒ `nonReasoningModelGetsNoEmptyFill`；探测恒真
⇒ `ordinaryRelayGetsNoEmptyFill`；显式 `false` 被忽略 ⇒ `explicitCompatFalseDisablesTheEmptyFill`。
models.json 那侧的入口夹具同样探针过（`compatOf` 退回二态 ⇒ 恰一条红）—— 它是**实现之后**
才写的（§8.35.12 教训形态 (6) 的适用条件），故必须补探针而不能以「绿」作证。

**未做（如实登记）**：B24（`choice.usage` 回退，另一层）仍开放；B20（stop reason 映射）
是**下一个包**，其首个提交是 D1 的 P0 只读探针。

#### 8.35.14 B20 设计定稿：三条裁决落地 + **两处审计更正**（含新裁决点 D5）

本节是 B20 的**实施蓝图**。它同时**撤回 §8.35.2 的一行结论**（Responses「✅ 已对齐」）
与**一段结论**（`rawStopReason` 零消费者）—— 两者都是本次复核 pi 源码时被**证伪**的，故按
「登记发现」的规矩先把更正写在这里，再动代码。

##### 一、裁决回执（用户已拍板）

| # | 裁决 | 落地 |
|---|---|---|
| **D1** | **直接照 pi 严格版**（放弃「P0 只读探针」作为首个提交，§8.35.8 的建议(a)后半段**被否**） | 提交 ④：`supportsFinishReason` 默认 `true`，缺 `finish_reason` ⇒ **抛** |
| **D2** | Anthropic 正常结束 `end_turn` → **`"stop"`** | 提交 ③（独立，因其动 7 个测试文件） |
| **D3** | Google / Mistral **并入**本包，但**提交分车道** | 提交 ⑤ / ⑥ |
| **D4** | B10 接线先于本包 | 已执行（§8.35.11） |
| **D5** | `rawStopReason` 是否移植（第五节被证伪的「零消费者」结论 ⇒ 新裁决点） | **(a) 全量移植** | 提交 ⑨ |
| **B26** | 第六节的「partial 的 `stopReason` 初值应为 `"pending"`」（新发现 C）是否**一并做** | **一并做** | 提交 ⑩（外溢说明见第六节末） |

⚠️ D5/B26 的答复在设计稿 `163d2ea` **之后**到达，故第七节的 ⑨/⑩ 两行在设计稿里只带
条件标签 `（D5=(a)）`；本表是它的定稿。

**D1 的残留风险如实登记**：P0 探针被否 ⇒「这个 relay 到底发不发 `finish_reason`」**仍未观测**。
补偿不是探针而是**门**：提交 ④ 的完成条件包含 §8.35.9 ④ 的**真实车道回归**——用配置里的
`teamorouter` 跑一次真实请求（trace 目录重定向、不碰 `~/.pi-java` 的会话），**确认线格里有
`finish_reason`** 才算过。若届时发现它不发，则停下来回头请裁决（严格版会让今天能跑的 relay 全失败）。

##### 二、逐车道 pi 语义（定稿，逐行出处）

| 车道 | 映射 | 取值表 | 收尾文案（缺 reason / error 兜底） |
|---|---|---|---|
| **Anthropic** | `mapStopReason(reason, stopDetails)` `anthropic-messages.ts:1464-1493` | `end_turn`→stop、`max_tokens`→**length**、`tool_use`→toolUse、`refusal`→error+`stopDetails?.explanation \|\| "The model refused to complete the request"`、`pause_turn`/`stop_sequence`→stop、`sensitive`→error+`"Provider stopped with: sensitive"`、**其余 throw** | `"Anthropic stream ended without a stop reason"` / `"An unknown error occurred"` |
| **completions** | `:1550-1571` | `null`→stop、`stop`/`end`→stop、`length`→length、`function_call`/`tool_calls`→toolUse、`content_filter`→error+`"Provider finish_reason: content_filter"`、`network_error`→error+`"Provider finish_reason: network_error"`、**其余 error**+`` `Provider finish_reason: ${reason}` ``（**不抛**） | `"Stream ended without finish_reason"` / **`"Provider returned an error stop reason"`**（措辞与其他三条不同） |
| **Google** | `google-shared.ts:379-411` | `STOP`→stop、`MAX_TOKENS`→length、**15 个**（BLOCKLIST/PROHIBITED_CONTENT/SPII/SAFETY/IMAGE_SAFETY/IMAGE_PROHIBITED_CONTENT/IMAGE_RECITATION/IMAGE_OTHER/RECITATION/FINISH_REASON_UNSPECIFIED/OTHER/LANGUAGE/MALFORMED_FUNCTION_CALL/UNEXPECTED_TOOL_CALL/NO_IMAGE）→error（**无文案**）、**其余 throw** | `"Google stream ended without a finish reason"` / 由 **`rawStopReason`** 拼 `` `Provider stopped with: ${raw}` ``，缺失才 `"An unknown error occurred"` |
| **Mistral** | `:926-941` | `null`→stop、`stop`→stop、`length`/**`model_length`**→length、`tool_calls`→toolUse、`error`→error+`"Provider stopped with: error"`、**其余 error**+`` `Provider stopped with: ${reason}` ``（**不抛**） | `"Mistral stream ended without a finish reason"` / `"An unknown error occurred"` |
| **Responses** | `openai-responses-shared.ts:763-796` | 见第四节（**本包要修**） | `"OpenAI Responses stream ended without a stop reason"` / `"An unknown error occurred"` |

**completions 独有的中间一步**（`openai-completions.ts:685-687`，夹在 abort 检查与 error 检查**之间**）：
`!hasFinishReason && !compat.supportsFinishReason` ⇒ 就地改写为 `toolCall?toolUse:stop` —— 即
**容忍版**（D1 的选项 (b)）在 pi 里**存在**，只是默认关着（`detected.supportsFinishReason` 恒 `true`，`:1638`）。

##### 三、pi-java 的三个结构性偏差（本包修两个、注明一个）

**① `error` 之后仍发 `done`（γ 的车道侧形态）** —— 五个车道**无一例外**：`mapEvent`/事件循环的
`catch` 已经 `builder.emitError(...)`，但 `streamInternal` 随后**照样** `emitDone(...)`。
pi 在收尾处是 **`throw`**，外层 `catch` 发完 `{type:"error"}` 就 `stream.end()`——**一条流只有一个终局事件**。
今天 pi-java 的物理错误轮次在通道上是「先 `error` 后 `done`」两条。
⇒ 收尾必须**先判后发**：`error`/`aborted` 走 `emitError` 且**不再** `emitDone`。

**② 没有 `pending` 概念** —— pi 的累加器初值 `stopReason: "pending"`（`:526`/`:333`/`:75`/`:222`/`:444`），
收尾拿它当「**一个 stop reason 都没观测到**」的哨兵；pi-java 各车道用**局部变量兜底成 `"stop"`**
（Google `:161`、Mistral `:79`/`:104`），把「什么都没看到」伪装成「正常结束」。
⇒ 严格版（D1）要的就是**区分这两件事**。

**③ aborted 半段在车道层不可达（本包不改，只注明）** —— `StreamRequest` **没有** abort signal
（pi 的 `options.signal`），故 pi 收尾的第一个检查（`:779`/`:682`/`:264`/`:150`）与
`stopReason === "aborted"` 分支在车道层**结构上不可达**；pi-java 把它上提到宿主层
`PiLoopRunner.markAborted`（`:291-303`，已有 javadoc 说明「provider 层看不见信号」）。
⇒ **维持该分工**，各车道补一句注释指向它，**不**把 signal 塞进 `StreamRequest`。

##### 四、审计更正 A：§8.35.2 表格末行「**Responses ✅ 已对齐**」**作废**

那一行是**唯一**被判为已对齐的，本次逐行复核 `openai-responses-shared.ts` + `openai-responses.ts`
后发现**五处**差距。**先说为什么它比前四条更值得写清楚**：pi 的 `errorMessage` 是重试分类器
`RetryableError.isRetryableAssistantError`（`pi-java-ai`/`utils/RetryableError.java:140-150`）的
**唯一输入**（要求 `stopReason=="error"` ∧ `errorMessage` 非空 ∧ 命中瞬断白名单）——文案丢了，
环 A 在那条路径上就恒 false。

| # | 位置 | pi | pi-java 现状 | 后果 |
|---|---|---|---|---|
| **α** | `mapStopReason` 的 `default`（`:789-791`） | **`throw new Error("Unhandled stop reason: …")`** | `:201` `default -> "stop"` | 新状态被当成**正常结束** |
| **β** | `incomplete` 且非 `max_output_tokens`（`:771-781`） | `stopReason:"error"` + `"Response incomplete: X"`（无 reason 时 `"Response incomplete without a provider reason"`），经外层 catch 成 `{type:"error"}` | `:197` 只回 `"error"`，**无文案、且不发 error 事件** | 转录取不到原因；重试分类器拿不到文本 |
| **γ** | 终局事件的**类型** | pi **从不**发 `done("error")`（收尾处 `:184-189` 先 throw） | `:174` `emitDone("error")` | 「done = 成功」这条协议不变量被破 |
| **δ** | `response.failed` 的文案（`:749-757`） | `` `${error.code \|\| "unknown"}: ${error.message \|\| "no message"}` `` → 退化 `incomplete: X` → `"Unknown error (no error details in response)"` | `:205-211` `code + ": " + message`，退化 `"Response failed without error details"` | 三处文案都不同（`code()` 为 null 时 NPE 风险另计） |
| **ε** | 出错后是否继续消费 | `throw` ⇒ **终止整条流** | `:105`/`:109` `emitError` 后**继续迭代**，后面的 `completed`/`outputTextDelta` 照样处理 | 出错后仍可能补出文本/工具调用 |

⇒ 更正为「**Responses 车道同样是缺口，且是本次唯一一处『被判为已对齐、实为有缺口』的车道**」。
`§8.35.2` 那行**保留原文并加作废批注**（历史记录性质，与本仓一贯做法一致）。

##### 五、审计更正 B：`rawStopReason` 的「**零消费者**」结论被证伪 ⇒ 新裁决点 **D5**

`Message.java:55-59` 与 §8.35.2 末段的原始论证是「pi 侧引用只有一处（声明），八个适配器写、**零个消费者读**」。
**复核推翻**：pi 侧**有两个读点**，都在 Google 车道收尾，且它是那句文案的**唯一来源**：

- `packages/ai/src/api/google-generative-ai.ts:272-273`
- `packages/ai/src/api/google-vertex.ts:289-290`

```
const errorMessage = output.rawStopReason
    ? `Provider stopped with: ${output.rawStopReason}`
    : "An unknown error occurred";
```

写点 10 处（`anthropic:744`、`bedrock:292`、`google:217`、`google-vertex:234`、`mistral:614`、
`completions:572`、`responses-shared:588`/`:747`），声明在 `types.ts:443`。
⇒ 原文的**措辞**其实是对的（「在对齐面（`packages/agent/src`）没有消费者」——两个读点都在
**生产者层**），但**结论「故不移植」是错的**：pi-java 也要实现那一层，缺了它就**产不出** pi 的文案。

**D5（请裁决）**：

| 选项 | 内容 | 代价 |
|---|---|---|
| **(a)**（我建议） | **全量移植**：`AssistantMessage` 加第 10 个组件 + 五条车道写点 + `MessageJsonCodec` + `fromPartial`/`withStopReason`/`withErrorShape` 全投影 + 夹具 | 主源码 20 个构造点、codec、测试都要过一遍 |
| **(b)** | 只做 Google 的**文案行为**：车道内局部量，**不加**消息字段 | 文案对齐、转录仍缺键 |
| **(c)** | 维持不做 | 转录缺键 + Google 文案退化成 `"An unknown error occurred"` |

**建议 (a)**，与 D2 同一条理由：D2 之所以要改，是因为「**每一份** Anthropic 转录的取值都不同」；
`rawStopReason` 是**全车道、每一条消息**都写的键，属同一等级，不是边角。⚠️ 而 (b) 会让
「文案对了、键还是没有」这种半对齐状态更难排查。

##### 六、新发现 C：partial 的 `stopReason` **初值**应为 `"pending"`（与 D5 同类，建议并做）

pi 五条车道的累加器都从 `stopReason: "pending"` 起（臂注：`:526`/`:333`/`:75`/`:222`/`:444`），
且**只在终局事件改写** ⇒ 流进行中的**每一个** `message_update`（以及 `message_start`）载荷里
`stopReason` 都是 `"pending"`。pi-java 的 `StreamPartialBuilder.stopReason` 初值是 `null`
（序列化时键主动省略，`Message.java:61`）⇒ 两边**每一条中间帧**都不同。

**为什么 S 系列差分测不到**：那套剧本走 `faux` provider，它的 partial 从一开始就是
`stopReason: options.stopReason ?? "stop"`（`faux.ts:93`）⇒ 两侧都是 `"stop"`，**结构上覆盖不到**
（与 §8.33「桩盖不住」同一形态）。

**落地两选**：① `StreamPartialBuilder` 字段初值改 `"pending"`（收尾检查随之成为**字面量**）；
② 各车道用局部布尔判「有没有观测到」。**建议 ①**（两件事一次做对），**但必须标注外溢**：
`StreamPartialBuilder` 也被 **`PiMessagesApi`** 使用，而它不是 pi 的车道（§8.34.11-（1））；
初值改动会让它的 partial 也带 `"pending"`——**这是可接受的**（pi-java 自有协议的 partial 形状本就
跟随同一 builder），但要在提交信息里写明，**不改**它的任何其他行为。

##### 七、提交计划（每项**独立可编译**，200–500 行内）

| # | 提交 | 内容 |
|---|---|---|
| ① | `feat(ai): ModelCompat.supportsFinishReason` | `ModelCompat` 加第三组件（**默认 true**，与 `allowEmptySignature` **方向相反**）+ `ModelsJsonSchema.CompatDef` + `ModelsJsonConfig.compatOf` + 入口夹具 |
| ② | `fix(ai): Anthropic 车道 stop reason 映射与收尾` | `mapStopReason`（含 `refusal` 取 `stopDetails.explanation`）+ 收尾三分支 + **不再 error 后发 done** |
| ③ | `fix(ai): Anthropic 正常结束取值改为 stop（D2）` | `:94` 硬写点 + `AssistantMessage`/`StreamEvent` 词汇表 + 7 个测试文件（约 13 处） |
| ④ | `fix(ai): openai-completions 车道 stop reason 映射与严格收尾（D1）` | `mapStopReason` + `supportsFinishReason` 门 + 收尾 |
| ⑤ | `fix(ai): Google 车道 stop reason 映射与收尾` | 映射按**原始字符串**（Java SDK 1.15.0 的 `Known` **缺** IMAGE_*/NO_IMAGE 四种，未知值会被 `knownEnum()` 吞成 UNSPECIFIED，只能读 `toString()`）+ **新测试类** |
| ⑥ | `fix(ai): Mistral 车道 stop reason 映射与收尾` | 同上 + **新测试类** |
| ⑦ | `fix(ai): Responses 车道收尾语义更正` | α/β/γ/δ/ε 五条 |
| ⑧ | `test(agent-core): 跨层回归门（线格 → 消息 → PiLoopRunner 的 length 门）`（设计稿原写 `test(ai)`，实施时据实改模块，理由见 §8.35.15 五） | §8.35.6 末行 |
| ⑨ | （D5=(a)）`feat(ai): 助手消息携带 rawStopReason` | 第 10 个组件 + 五条车道写点 + codec |
| ⑩ | （D5=(a)）`fix(ai): partial 的 stopReason 初值改为 pending` | `StreamPartialBuilder` 初值 + 外溢说明 |

**收尾不抽公共方法（考虑后否决）**：五条车道的收尾看似同形，但**措辞逐条不同**
（pending noun 五样、error 兜底 completions 是 `"Provider returned an error stop reason"`、
Google 走 `rawStopReason`），抽出来只会变成一个 7 参外壳，反而**藏掉** pi 的逐车道差异
⇒ 按 pi 的形状**各车道内联**（每处约 10 行）。

##### 八、夹具计划（修订版；**每条先跑一遍把 actual 抄回本节**——§8.34.5 的纪律）

> **本表的「今天」列是预测，不是证据** —— 逐条实测的 actual（含 ③–⑦ 的红灯原文、两处预测更正、
> 逐车道 SDK 读法）已抄回 **§8.35.15 一/二/三**。本节保留预测原文并在错处就地加注，以便对照。

| 夹具 | 喂什么 | 断言 | 今天 |
|---|---|---|---|
| `AnthropicMessagesApiTest` | `message_delta:{stop_reason:"max_tokens"}` | `StreamDone.reason()=="length"` | 红 |
| 同上 | `stop_reason:"refusal"` + `stop_details:{explanation:"…"}` | `StreamError` 且文案 == explanation | 红 |
| 同上 | `stop_reason:"end_turn"` | `"stop"`（D2） | 红 |
| 同上 | `stop_reason:"sensitive"` | error + `"Provider stopped with: sensitive"` | 红 |
| 同上 | 未知 `stop_reason` | error + `"Unhandled stop reason: X"`（**α 同形**） | 红 |
| 同上 | 整条流无 `message_delta.stop_reason` | error + `"Anthropic stream ended without a stop reason"` | 红 |
| `OpenAICompletionsApiTest` | `finish_reason:"length"` | `"length"` | 红 |
| 同上 | `finish_reason:"content_filter"` | error + `"Provider finish_reason: content_filter"` | 红 |
| 同上 | **整条流无** `finish_reason` | error + `"Stream ended without finish_reason"`（D1 严格） | 红 |
| 同上 | 同上 + `compat.supportsFinishReason:false` | **不**报错：`toolCall?toolUse:stop` | ⚠️ **预测有误：实为对照面**（④ 实测两侧同绿 —— 旧实现从不判定、恒发 `"stop"`，与该格同值）；更正见 §8.35.15 二-1 |
| `GoogleGenerativeAiApiTest`（新类） | `finishReason:"MAX_TOKENS"` / `"SAFETY"` | `"length"` / error | 红 |
| `MistralConversationsApiTest`（新类） | `"model_length"` / `"error"` / 未知值 | `"length"` / error+`"Provider stopped with: error"` / error+`"Provider stopped with: X"` | 红 |
| `ResponsesStreamProcessor` 相关 | `response.incomplete` + `incomplete_details.reason!="max_output_tokens"` | **`StreamError`**（不是 done）且文案 `"Response incomplete: X"` | 红 |
| 同上 | 未终局的流 | error + `"…before a terminal response event"` | 绿（对照）—— ⑦ 实测成立，但理由与预测不同（旧实现照发 `done(stop)`，与「有终局事件」同形）；更正见 §8.35.15 二-2 |
| **跨层回归门** | 线格 → 消息 → `PiLoopRunner` | `length` 截断 ⇒ `PiLoopTools.run(..., **true**)`，工具**不执行** | 红 —— ⑧ 实测：变异探针（回退提交 ④）下 `expected: 0 but was: 1`，转录 `[user, assistant(tool_use), tool, assistant(stop)]`；夹具落在 **agent-core** 而非 `ai`（偏差说明见 §8.35.15 五） |

⚠️ 每条**先红证毕**；R1 类「两侧同绿」的对照各做**变异探针**（§8.35.12 的教训形态 (6)：夹具
若写在实现之后就没有红灯可看）。

##### 九、验证与不做

**验证**：① 逐条先红证毕（actual 抄回第八节）；② 跨层回归门；③ `mvn -o clean verify` 全 reactor
绿 + checkstyle 零违规；④ **真实车道回归**（D1 的那道门）：`teamorouter` 跑一次真实请求、trace
目录重定向，**确认线格里出现 `finish_reason`**；⑤ Anthropic 车道真实请求一次，核对转录 `stopReason`（D2）。

**不做**：不把 abort signal 塞进 `StreamRequest`（第三节 ③ 已定）；不抽公共收尾方法（第七节末）；
不碰 `PiMessagesApi` 的闸与协议形状（§8.34.11-（1）不变）；不做 `reasoning_details` /
`requiresThinkingAsText`（§8.35.7）；不改请求侧 `thinkingFormat`（B21）；不引入 `rawStopReason`
以外的 pi 消息字段（`responseModel`/`responseId`/`providerThinkingLevel`/`diagnostics`/`endTurn`
的排除结论**维持**——它们**没有**本次这种「生产者层读点」的反例）。

---

#### 8.35.15 B20 **实施记录**（提交 ③–⑧；red actual 已抄回 §8.35.14 第八节）

> **状态**：③–⑩ **全部落地**（`d2254a2`/`811aa31`/`7f437b4`/`9f4c4bb`/`775f3a6`/`1007246`/`4619b78`/`32784aa`，
> 外加 web wire 的 `a8c0a62`）。⑩（B26 partial 初值 `pending`）的两处待裁**已裁并已落地**（第八节 → 8.7/8.8）：
> (a) 两个中间读点**照报**、零折算；(b) web wire **补上** `rawStopReason`。
> 每步的验证面一律是 `mvn -o clean verify`（全 14 模块 BUILD SUCCESS + checkstyle/spotbugs 零违规）
> 加该模块的定向测试，逐提交列在下表，不再重复。

##### 一、逐提交落地与实测

| # | 提交 | 内容 | 夹具 | 实测（"修复前" = 用 `git checkout --` 回退该车道后跑新夹具得到的 actual） | 计数 |
|---|---|---|---|---|---|
| ③ | `d2254a2` | **D2** 词表清理：`AssistantMessage`/`StreamEvent` 的 `stopReason` javadoc 写明取值域、7 个测试文件 13 处 `"end_turn"`→`"stop"` | —（纯词表） | 零行为改动 ⇒ 全绿；那些夹具此前用 pi 造不出的取值而恒绿（`determineOutcome` 把非 `aborted`/`error` 一律记 `completed`） | ai 410 |
| ④ | `811aa31` | **D1** completions：读 `finish_reason` + `mapStopReason` + 严格/容忍两分支 | `OpenAICompletionsApiTest`（8 条） | **6 红 2 绿**：`length`⇒`"stop"`；`tool_calls`（线格里**无** tool 块）⇒`"stop"`；contentFilter / networkError / unknown / 无 `finish_reason` 四格 ⇒ `Expected size: 1 but was: 0`，实际 `[Start, TextStart, TextDelta, TextEnd, StreamDone]` | ai 410 |
| ⑤ | `7f437b4` | Google：读点从 response 级换成**候选级** + 映射 + 四段收尾 | `GoogleGenerativeAiApiTest`（**新建**，8 条） | **7 红 1 绿**，修复前终局值是**线格原值**：`max_tokens` / `safety` / `finish_reason_unspecified` / `no_image` / `brand_new` / （无 finishReason 时也是）`finish_reason_unspecified` / `stop`；绿的是 `stopFinishReasonMapsToStop`（对照面） | ai 418 |
| ⑥ | `9f4c4bb` | Mistral：读点从 delta 守卫**之后**挪出 + 映射 + 四段收尾 + `[DONE]` 改 `break` | `MistralConversationsApiTest`（**新建**，8 条） | **5 红 3 绿**：`model_length`⇒`StreamDone(model_length)`；只带 finish_reason 的终帧⇒`StreamDone(stop)`；`error`⇒`StreamDone(error)` 且 0 条 error；未知值⇒`StreamDone(brand_new_reason)`；无取值⇒`StreamDone(stop)` | ai 426 |
| ⑦ | `775f3a6` | Responses：α/β/γ/δ/ε 五条 | `OpenAIResponsesApiTest`（新增 8 条 + 加强 1 条 + 删 1 条） | **7 红 1 对照**：incomplete+content_filter ⇒ `[Start, TextStart, TextDelta, StreamDone(error)]` 且 **0 条 error**；incomplete 无 reason ⇒ `[Start, StreamDone(error)]`；未知 status ⇒ `[Start, StreamDone(stop)]`；failed 三条退化文案 ⇒ 全部 `"Response failed without error details"` / `"\`message\` is not set"`（SDK 异常文本）；error 事件后**仍照发**内容（`Hi AFTER-ERROR`）与 `StreamDone(stop)` | ai 426→433 |
| ⑧ | `1007246` | **跨层回归门**（见第五节） | `CrossLayerLengthGateTest`（**新建**，2 条） | **1 红 1 对照**（变异探针见第五节） | agent-core 462→464 |
| ⑨ | `4619b78` | `rawStopReason` 全量移植（D5=(a)） | 见第七节 | 8 条变异探针，逐条红灯见第七节 | ai 439 / agent-core 464 |
| ⑩ | `32784aa` | partial 初值改 `"pending"`（B26）＋ `markAborted` 判据换字面量 ＋ web wire（`a8c0a62`） | 见第八节 8.8 | 3 条变异探针，逐条红灯见 8.8 | ai 440 / agent-core 465 |

##### 二、第八节两处预测的更正（实现后才知道）

1. **completions 的 `explicitlyDisabledFinishReasonSupportEndsNormally` 预测「红」实为 ④ 的对照面** ——
   它测的是**容忍分支**（`supportsFinishReason:false`），而旧实现从不判定、恒发 `"stop"`，与该格同值
   ⇒ 它没有红灯可看，是「两侧同绿」的对照。
2. **Responses 的「未终局的流」预测「绿（对照）」实测成立**，但理由与预测不同：不是因为旧实现也判，
   而是它**照发 `done(stop)`**，与「有终局事件」这一断言恰好同形 ⇒ 加强后（断言文案）才是真对照。

⇒ **教训（§8.34.5 形态 (6) 的又一例）**：「今天应当红/绿」这一列是**预测**，不是证据；
凡预测为「绿」的行都必须当场标成**对照面**并说明它为何在两侧同绿，否则它会被读成「已覆盖」。

##### 三、逐车道 SDK 读法（四次探针的合计结论；全部为**实测**，非推断）

| 车道 | SDK / 形态 | 「有没有观测到」怎么读 | 未知值的命运 | 陷阱 |
|---|---|---|---|---|
| Anthropic | `anthropic-java` | message_delta 的 `stop_reason` | 抛（pi 的 `default` 也是抛） | `asKnown()` **不抛**（与 openai-java 相反） |
| completions | openai-java 4.42.0 | `choice.finishReason()` → `Optional`，**键缺席与 JSON null 同为 `empty()`** | 落 error 事件（不抛） | `known()` 对未知/空值**抛**；故只能 `asString()`（`network_error` 不在 `Known` 里，专钉这条）；pi 的 `if (choice.finish_reason)` 是**真值**判断 ⇒ 空串算「没观测到」 |
| Google | google-genai 1.15.0 | **必须读候选级** `candidate.finishReason()`（`Optional`） | 抛（pi 的 `default` 抛） | response 级 `finishReason()` 在候选没有该字段时**自己造**一个 `FINISH_REASON_UNSPECIFIED`、**永不返回 null** ⇒「没观测到」与「线格真的发了 UNSPECIFIED」不可区分（⑤ 的红证给出了观测证据）；`knownEnum()` 对未知值与 SDK 词表**缺**的 `NO_IMAGE` **都静默吞成 UNSPECIFIED** |
| Mistral | 无官方 SDK（手写 SSE） | `choice.finish_reason` 真值判断 | 落 error 事件 + **自带文案**（不抛） | 读点必须在 delta 处理**之前**才是 pi 的形状，但那样会漏「同帧的 tool_call 终帧」⇒ ⑥ 保留一处**刻意偏差**并在读点注明 |
| Responses | openai-java 4.42.0 | `ResponseStatus`（Enum 模式，未知值照常反序列化） | 抛（pi 的 `default` 抛） | `ResponseError.code()`/`message()` 在字段缺席**或为 null** 时**抛** `OpenAIInvalidDataException`（`'message' is not set`）⇒ δ 必须先问 `_code()`/`_message()` 的存在性，否则 pi 的 `"unknown"`/`"no message"` 兜底会退化成 SDK 异常文本 |

##### 四、**四处 `default` 互不相同**（照 pi 写，别「统一」清单）

| 车道 | 未知取值的落点 | pi 出处 |
|---|---|---|
| Anthropic | **throw** | `anthropic-messages.ts:1464-1493` |
| Google | **throw**（`Unhandled stop reason: X`） | `google-shared.ts:379-411` |
| Responses | **throw**（α 修复前是 `"stop"`） | `openai-responses-shared.ts:763-796` |
| completions | **落 error 事件**（`Provider finish_reason: X`） | `openai-completions.ts:1550-1571` |
| Mistral | **落 error 事件且文案做进映射**（`Provider stopped with: X`） | `mistral-conversations.ts:926-941` |

同一条 pi 的五种落法 ⇒ 第七节末「收尾不抽公共方法」的否决理由在此得到逐条印证。

##### 五、⑧ 跨层回归门（本包的核心那一格）

**为什么要它**：缺口是「夹具在宿主层，缺口在协议层」（§8.35.2 结语，`:4696`）。`AgentLoopL1Test`
的 ③ 用例用 `scriptedStreamFn` **伪造**一条 `stopReason=="length"` 的助手消息，只能证「门**给定**
截断消息会关」，证不了「任何真实车道会从线格上产出这样一条消息」。

**夹具**`pi-java-agent-core/src/test/java/com/pijava/agent/harness/CrossLayerLengthGateTest.java`
（新建）：本地 `HttpServer` 起 **completions 线格**桩（第 1 个响应 = 参数完整的工具调用 +
`finish_reason:"length"`；第 2 个 = 重试后的正常收尾），经**真实** `OpenAICompletionsApi` 落成助手消息，
交给 `AgentHarness`/`PiLoopRunner`，断言三跳：① 消息 ⇒ 门（`executed == 0`、回灌的是**失败**结果且
文案含 `hit the output token limit`）；② 线格 ⇒ 消息（第一条助手消息 `stopReason == "length"`）；
③ 回合继续（重试的正常收尾生效）。

| 夹具 | 性质 | 实测 |
|---|---|---|
| `lengthFromTheWireStopsToolExecutionInThisTurn` | 门（本包核心） | **红**：变异探针 = 把 `OpenAICompletionsApi` 换回提交 ④ 之前的版本（那一版根本不读 `finish_reason`）⇒ `expected: 0 but was: 1`，转录 `[user, assistant(tool_use), tool, assistant(stop)]` —— 截断的调用被当真送进执行器，回灌的还是**成功**结果 |
| `toolCallFinishReasonFromTheWireExecutesTheTool` | **对照面** | 同一变异下仍绿（它不涉及 `length` 门）；但它自己在夹具写错时**会红**：第一版 SSE 写成 `data: data: {...}`（`chunk()` 已带前缀、外层又套一次）⇒ 两条夹具同时红成 `assistant(error: Error reading response)`（SDK 把整行当 JSON 解，`SseMessage.kt:62-64`）⇒ 证明它确实在守「线格真的送到了工具调用」 |

⚠️ **与设计稿的一处偏差**：第七节表格把本提交写作 `test(ai)`，但 `PiLoopRunner` 在 **agent-core**、
且 `ai` 依赖不到它 ⇒ 夹具只能落在 `agent-core`（它依赖 `ai`，能同时看到两侧）。模块标签据实记为
`test(agent-core)`。

**一条可复用的教训**：跨层夹具的**失败信息必须能区分「门没关」与「线格没送到」** —— ⑧ 的第一版
把断言顺序写成「先线格、后门」，变异探针下红的是**线格**那一跳（`expected "length" but was "tool_use"`），
正题（工具被当真执行了）反被藏在后面。改为「先门、后线格」后，红灯第一次就说出本包那句话。

##### 六、未覆盖 / 不可达事项（如实登记，**不用假夹具补**）

1. **abort 检查四条车道在车道层都不可达** —— `StreamRequest` 没有 signal，中止由宿主
   `PiLoopRunner.markAborted` 在流外处理（第三节 ③）。④–⑦ 的四条车道 javadoc 各自写明。
2. **Responses 的 `pending` 哨兵不可达**（⑦）—— `sawTerminal` 只由 `finalizeResponse` / `failed`
   两支写入，前者必经 `mapStopReason` 写下非 pending 取值、后者直接 throw；pi 侧同一对前置条件。
   留着只为与 pi 收尾四段**同形**，**不是**补缺口。
3. **`formatProviderError` 层 pi-java 没有**（⑦）—— pi 车道 catch 会把 `errorMessage` 过一遍该函数，
   本仓文案是未包装的原文。
4. **D1 真实车道闸**（第九节 ④）：**已过** —— teamorouter 真实请求拿到 `done(stop)` ⇒ 该 relay 的线格里
   确有 `finish_reason`（探针只打印非机密事实，用完已删）。
5. **D2 真实车道检查**（第九节 ⑤）：**本环境无法执行** —— 配置里 anthropic 车道也被 `defaultBaseUrl`
   送去同一个 relay，而它没有 `/v1/messages` 路由（404 `route_not_found`）。**未**用改造 baseUrl 的方式
   绕过。⇒ D2 目前只有车道级夹具背书。
6. **Mistral 真实请求**：`MISTRAL_API_KEY` 未配置，取不到凭证；**未**用其他 provider 的 key 顶替。
7. **其余 abort/哨兵之外的「绿」行**：逐条已在上表标为对照面，并注明它在两侧同绿的理由（第二节 1/2）。

##### 七、⑨ `rawStopReason` 实测（`4619b78`，D5=(a)）

**形状**：`AssistantMessage`（流式快照）与 `Message.AssistantMessage`（终局消息）各加**第 10 个组件**
`rawStopReason`，位置在 `errorMessage` 之后（对齐 pi `types.ts:438-448` 的字段序）；`StreamPartialBuilder`
加字段 + `noteRawStopReason`；五条车道 **6 个写点**（原值先落消息、映射结果再落 `stopReason`）；
codec 一写一读（`SessionJson` / `MessageJsonCodec`）。

**设计取舍：不留 9 参兼容构造器**。让编译器把每一个「会静默丢掉新字段」的调用点枚举出来 —— 这正是
3a 那类「丢点」缺陷的护栏。代价是 ~30 处测试的机械配对（纯加 `, null`），其中一处是唯一的**语义**配对：
`WebWireJsonTest:84`（见下「跨模块外溢」）。

| 车道 | 线格原值 | 映射结果 | pi 出处 |
|---|---|---|---|
| Anthropic | `"max_tokens"` | `length` | `anthropic-messages.ts:744` |
| Google | `"MAX_TOKENS"` | `length` | `google-generative-ai.ts:217` |
| Mistral | `"model_length"` | `length` | `mistral-conversations.ts:614` |
| completions | `"tool_calls"` | `tool_use` | `openai-completions.ts:572` |
| Responses（incomplete） | `"incomplete.max_output_tokens"`（**复合量**） | `length` | `openai-responses-shared.ts:588` |
| Responses（failed） | `"failed"`（写的是 **status**，不是复合量） | —— （throw） | `openai-responses-shared.ts:747` |

⚠️ 六条**没有一条**能从 `stopReason` 反推出来 —— 这就是这张表是证据而不是复述的原因。

**8 个变异探针**（夹具写在实现之后 ⇒ 无红灯可看，逐条探针证有牙；§8.34.11 教训形态 (6)）：

| 探针（把什么改坏） | 恰红的夹具 | 红灯原文 |
|---|---|---|
| Google 写点摘掉 | `GoogleGenerativeAiApiTest.rawStopReason…` | `expected: "MAX_TOKENS" but was: null` |
| Anthropic 写点摘掉 | `AnthropicMessagesApiTest.rawStopReason…` | `expected: "max_tokens" but was: null` |
| Mistral 写点摘掉 | `MistralConversationsApiTest.rawStopReason…` | `expected: "model_length" but was: null` |
| completions 写点摘掉 | `OpenAICompletionsApiTest.rawStopReason…` | `expected: "tool_calls" but was: null` |
| 复合量退成裸 status | `OpenAIResponsesApiTest.…IsTheStatusAndIncompleteReasonComposite` | `expected: "incomplete.max_output_tokens" but was: "incomplete"` |
| Responses `:747` 写点关掉 | `OpenAIResponsesApiTest.failedResponseRawStopReasonIsTheStatus` | `expected: "failed" but was: null` |
| `fromPartial` 投影置 null | `MessageTest.fromPartialProjectsEveryField` | `expected: "refusal" but was: null` |
| `SessionJson` 落盘关掉 / decode 键名写错 | `JsonlSessionStorageTest.assistantPayloadRoundTripsThroughJsonl` | `expected: "refusal" but was: null` |

**跨模块外溢（本提交跨 **三** 个模块，非单模块可独立编译）**：记录组件变更 ⇒ `agent-core` 编译不过
（`LlmSummaryGenerator` / `PiLoopRunner` / `SessionJson` / `MessageJsonCodec` 四文件必须同批）；
`pi-java-web` 由编译器查出**唯一**一处 9 参调用点（`WebWireJsonTest:84`）—— **只解配对，不改 wire 行为**；
`pi-java-evals` 未动（走 1 参兼容构造器）。

**计数**：ai **439** → agent-core **464**；`mvn -o clean verify` 全 **14** 模块 BUILD SUCCESS、checkstyle **0** 违规。

##### 八、⑩ 实施记录（B26；**两处已裁、已落地** `32784aa` + `a8c0a62`）

> 原稿只写「初值 + 外溢说明」，实测发现外溢比原稿大（以下 8.1–8.5 是**修订稿**，
> 落笔于实现之前；8.8 是实施记录）。裁决：(a) 两个中间读点**照报 `"pending"`**；
> (b) web wire **补上**。两处裁决均取建议项。

###### 8.1 新发现（pi 侧）：`"pending"` 是**非持久化**取值

pi 的**存盘**消息类型显式排除它：`harness/session/types.ts:13` ＝
`stopReason: Exclude<StopReason, "pending">`；会话层**拒绝** append 一条 pending 助手消息
（conformance `session/testing/conformance/session-repo.ts:264` 就是拿它当反例）。
即：`"pending"` 在 pi 里是**流的中间态**，一旦「落定」必须已被改写。pi 五条车道都从
`stopReason: "pending"` 起（`anthropic-messages.ts:526`/`openai-completions.ts:333`/`google-generative-ai.ts:75`/
`mistral-conversations.ts:222`/`openai-responses.ts:139`；pi 侧 `grep` 实际共 **10** 处，另含
`azure-openai-responses:95`/`bedrock-converse-stream:138`/`google-vertex:93`/`openai-codex-responses:252`/
`pi-messages:186`，pi-java 只实现上述 5 条车道），
**只有终局事件改写它** —— 所以「流跑完还停在 pending」在 pi 里等于「provider 没给终局判定」，
而这个状态**不会**进会话。

⚠️ 原稿（§8.35.14 六）说「① 字段初值改 `"pending"`（收尾检查随之成为字面量）」—— 那句「随之成为字面量」
**不是**装饰：它至少有一处是**必需**的转换，见 8.3。

###### 8.2 事实：中间快照会流进终局消息（宿主层真实路径）

`PiLoopRunner:235-240` **每收到一个 update 事件**都用 `fromPartial(event.partial())` 重建 `finalMessage`。
⇒ ⑩ 之后，只要中止发生在「provider 已吐帧、但还没吐终局」的窗口里，`finalMessage.stopReason()`
就是 `"pending"` 而不再是 `null`。这不是测试桩的形状，是**每一条真实车道**的形状。

###### 8.3 **必需**的转换：`PiLoopRunner.markAborted`（`:299`）

```java
if (!cutShort && message.stopReason() != null) { return message; }   // 今天：null ⇒ 「没观测到终局」
```
该检查的语义是 pi 的「provider 有没有写下终局判定」。⑩ 之后它**恒真** ⇒ 「进场前就已中止、
provider 照样吐完帧」那一支（`:206-213` 的 `abortedAtEntry`，**不是** `cutShort` 支）会被原样留下，
`stopReason == "pending"`；而 `ContextEntries.NON_PROJECTED_STOP_REASONS = {deferred, error, aborted}`
**不含** `"pending"` ⇒ **被打断的响应被投影进后续请求的上下文** —— 正是 `MidStreamAbortTest:34-36`
记的那个 A8 缺陷（该测试用 `AssistantMessage.empty()` 造桩，走不到 builder，故**不会**替我们红）。

⇒ 必须同步改成 `!"pending".equals(message.stopReason())`。**pi 侧没有这条检查**，因为 pi 的 provider
自己把终局 reason 写进累加器（`drive/response.ts:156/162` 的 `normalizeError`/`normalizeAborted` 是
在**流外**对「已落定」消息做事），宿主只需按 `response.stopReason` 分支 —— 这条转换是把 pi 的那条
隐式不变量（「落定的消息不是 pending」）在 pi-java 的宿主侧**显式**写出来。

###### 8.4 取值会变、但**无夹具钉住**的两个中间读点（需裁决，见 8.6-(a)）

| 读点 | 今天 | ⑩ 之后 | 下游 |
|---|---|---|---|
| `HarnessUtils.deriveNewestOwn:93` | `null` | `"pending"` | `NewestOwn.stopReason` → ① `LaneRecord` 的 `stopReason` 字段；② `determineOutcome`（只认 `aborted`/error ⇒ **不变**） |
| `RunSpanFactory.closeRunSpan:46` | 该属性**缺省**（`if (stopReason != null)`） | 写入 `"pending"` | `harness.run` 跨度的 `stopReason` 属性 |

两者都是 **pi-java 自有的旁路审计面**（pi 的跨度词汇表里没有这两个属性，见 §8.28）⇒ 判据（行为对齐）
在这里是**沉默**的，两种改法都不违背 pi。等裁决。

###### 8.5 经复核**不受影响**的收尾检查（逐条给理由，不留「大概没事」）

| 位置 | 为何不受影响 |
|---|---|
| `LlmSummaryGenerator.terminal:268`（`partial.stopReason() != null`） | 它防的是**安全网空快照**，而安全网用的是 `AbstractChatApi:113` 的 `identityBase` → `AssistantMessage.empty()`（`:76-83` 走 4 参兼容构造器）⇒ 仍是 `null`，⑩ **不碰** `empty()` |
| `withErrorShape`（`fixReason`） | `StreamError` 的 partial 由 `emitError` 写成终局值；安全网 `onError` 那支同 `empty()` ⇒ 两条路都不产生 `"pending"` |
| `AbstractChatApi.send:129`（`fromPartial(done.partial())`） | `StreamDone` 的 partial 只可能来自 `emitDone`（先写 reason 再快照）或安全网的 `identityBase` |
| `ContextEntries:166`（`NON_PROJECTED_STOP_REASONS`） | 读的终局消息；⑩ 之后多出一个 `"pending"` 取值**只**会在 8.3 那条转换缺失时出现 —— 那正是要防的 |
| `MidStreamAbortTest` / `ScriptedStreams` / 各 `scriptedStreamFn` 桩 | 桩用 `AssistantMessage.empty()`/`Message.AssistantMessage` 直造，**不走** builder |

**结构性覆盖不到**：L5 差分（`FrameNormalizer` 的 `stopReasonOf` 只把 `tool_use` 归一成 `toolUse`、
其余逐字比）走的是 `FauxProvider`，它的 partial 由 `msg.withStopReason(null)` 直造（`FauxProvider:81-82`），
**不经过** `StreamPartialBuilder` ⇒ 与 §8.33「桩盖不住」同一形态。

###### 8.6 落地清单（裁决后执行）

1. `StreamPartialBuilder.stopReason` 初值 `null` → `"pending"`（+ javadoc 注明：pi 词汇表外的哨兵值、
   与 §8.35.14 六 的出处）。
2. `PiLoopRunner.markAborted:299` 的 `!= null` → `!"pending".equals(...)`（**必需**，8.3）。
   实施时保留 `null` 分支（`observed == null || "pending".equals(observed)`）：非流式构造的消息与
   旧转录仍是 `null`，与 `"pending"` 同属「没观测到终局」，一并折算。
3. 外溢标注：`PiMessagesApi` 同样使用该 builder ⇒ 它的 partial 也会带 `"pending"`（**可接受**；
   它是 pi-java 自有协议、不是 pi 的车道），**不改**它的其他行为。
4. 两个中间读点按 8.6-(a) 的裁决处理（保留 null 语义 ⇒ 加一处 `"pending"` 折算并注明；或照报 `"pending"`
   并接受审计面取值变化）。⇒ **裁决：(a) 照报** ⇒ 零折算、零代码改动（8.1 表即最终行为）。
5. 夹具：`StreamPartialBuilderTest` 新增「初值即 `"pending"`」+ `PiLoopRunner` 的
   `abortedAtEntry` 支（8.3）各一条；逐条探针（§8.34.11 形态 (6)）。

###### 8.7 两处裁决（**均已裁**，取建议项）

- **(a)** 8.4 的两个中间读点：保留今天的 `null` 语义（把 `"pending"` 折算回「没观测到」），
  还是照报 `"pending"`（审计面取值随之变化）？**裁决：照报**（取建议项）—— 审计面如实反映消息、
  且少一层折算；这是 pi 无对应物的自有面，故交用户裁定。
- **(b)** `pi-java-web` 的 wire 是否投影 `rawStopReason`（⑨ 的编译器发现，见第七节）？
  `WebWireJson.messageNode` 现按 3a 的字段清单**逐键写出**（不含该键）、其 partial 投影更**只带 role+content**
   ⇒ 今天的 wire 是**裁剪过的**投影，不是消息镜像。**建议**随 3a 的「纯为形状对齐」口径补上（4 行），
  但**未**擅自改 —— ⑨ 只解了配对。**裁决：补上**（取建议项），随 3a 的「纯为形状对齐」口径，
  实现见 `a8c0a62`（含一条缺席哨兵：旧形状消息不得凭空长出该键）。

###### 8.8 实施记录（`32784aa` + `a8c0a62`）

**形状**：`StreamPartialBuilder.stopReason` 初值 `= "pending"`；`PiLoopRunner.markAborted` 的判据
**换成字面量**（8.3 那条**必需**的转换）；`WebWireJson.messageNode` 补 `rawStopReason` 键（裁决 b；
缺席规则同 `toolResult` 支：null ⇒ 键省略，因为 Jackson 会写出显式 `null`）。

**计数**：ai **439 → 440**（`StreamPartialBuilderTest` +1）、agent-core **464 → 465**
（`PendingStopReasonSettlementTest` **新建** 1 条）、web **37**（`WebWireJsonTest` 8 条，改造既有用例）；
`mvn -o clean verify` 全 **14** 模块 BUILD SUCCESS、checkstyle **0** 违规。

**三条变异探针**（前两条的夹具写在实现之后 ⇒ 无红灯可看，只能靠探针证有牙，§8.34.11 形态 (6)；
第三条的夹具是改造既有用例、本可看红灯，一并记录）：

| 探针（把什么改坏） | 恰红的夹具 | 红灯原文 |
|---|---|---|
| `StreamPartialBuilder` 初值退回 `null` | `StreamPartialBuilderTest.stopReasonStartsAtPendingAndOnlyTerminalEventsRewriteIt` | `expected: "pending" but was: null`（5/5 → 4/5） |
| `markAborted` 判据去掉 `"pending".equals(...)` 项 | `PendingStopReasonSettlementTest.pendingPartialIsNeverSettledAsPending` | `expected: "aborted" but was: "pending"`（1/1 → 0/1） |
| wire 投影改成 `if (false)` | `WebWireJsonTest.assistantCarriesIdentityAndMetricsButNoTimestamp` | `Expecting value to be true but was false`（8/8 → 7/8） |

⚠️ 第三条探针**第一版的红灯是 NPE**（直接读缺键的 `.asText()`），说不清是「没投影」还是「值不对」——
按 §8.35.15 五 的纪律改成**先断存在性**（`assertThat(node.has("rawStopReason")).isTrue()`）再读值，
第二遍才拿到自解释的红灯；夹具里那条注释就是这次改动的痕迹。

**为什么必须新建 `PendingStopReasonSettlementTest`**：⑩ 唯一能伤到的路径是「**进场前**就已中止
（`abortedAtEntry`）＋ provider 照样吐帧、但不发终局事件」，而既有夹具两条都结构性覆盖不到 ——
`MidStreamAbortTest` 走「**拉取途中**中止」（`cutShort` 支，收尾不看 `stopReason`）且 partial 用
`AssistantMessage.empty()` 直造、走不到 builder；L5 差分的 `FauxProvider:81-82` 用
`msg.withStopReason(null)` 造帧。新夹具两者都占：`AbortSignal` 在 `PiLoop.run` **之前** abort ＋
真实 `StreamPartialBuilder` 造三帧（`emitStart`/`emitTextStart`/`emitTextDelta`）、**无** `done`/`error`；
断言分两段 —— 中间帧 `containsOnly("pending")`、落定消息 `isEqualTo("aborted")`。

**中间读点（裁决 a）的最终行为** —— 8.4 那张表即现状，**无**折算代码：`HarnessUtils.deriveNewestOwn:93`
读到的 `NewestOwn.stopReason` 与 `RunSpanFactory.closeRunSpan:46` 写到 `harness.run` 跨度上的属性，
在「流进行中」这一刻都是 `"pending"`。两者都是 pi-java 自有旁路审计面（pi 跨度词汇表无此二属性，§8.28）。

---

### 8.36 包③：宿主的失败通路（B5）—— **设计待审**（2026-09-19）

> 路线 §8.32.3 的第 ③ 包（面 = `agent-core` + `coding-agent` 宿主）。§8.32.6-C 的建议是
> 「先只做第 1 步（一行 `catch (Throwable)`），第 2 步单列设计后再定」。**2026-09-19 用户两处裁决**：
> 第 1 步取「两处 `Throwable` ＋ `finally` 兜底」；第 2 步（pi 的 `handleRunFailure`）**做，落引擎侧**。
> 本节因此把两步都设计到**可实施**，并据实登记两处同族但今天可达性不同的兄弟缺口。
> 依例：**设计（含裁决点）经用户审核通过后才写代码**。

#### 8.36.0 范围与不变量

**做**（两件，彼此独立、各自可验）：

1. 让「引擎抛出的东西不是 `Exception`」不再让宿主永久挂起（§8.36.4）；
2. 让**引擎内**的异常按 pi 的形状收束成**一条失败助手消息 + 四事件**（§8.36.5）。

**不变量（本包承诺不动）**：

- 引擎侧的**正常** run 语义：`PiLaneEngine.drive` 的 `finally` 收口（`:200-205`）、`PostRunRetry` 的判定与
  预算、`stopReason` 的取值口径、`HarnessUtils.determineOutcome` 的四态。
  （第 2 步**新增**一条失败时的合成路径，不碰正常路径 —— 由夹具 1/3 与探针 P4/P5 守住。）
- L5 帧：第 1 步改的是会话层事件（§8.32.5 第 1 条：**不在帧里**）；第 2 步虽然落在帧的观测面内，
  但 L5 剧本**造不出引擎内抛出** ⇒ 同样覆盖不到。细则见 §8.36.6 的「覆盖面边界」。
- ~~不引入 pi-java 从未有过的「从异常重试」入口~~ —— **该不变量已被 §8.36.5 推翻并撤回**：
  pi 的失败消息本来就会进重试判定（`agent-session.ts:1123`），「不引入」等于**与 pi 分家**。
  取而代之的不变量是：**只在「引擎内抛出」这条路上做事**，正常路径与路 C 一字不动。

#### 8.36.1 事实基线（逐字）

**工程形状**（`SessionRunner.java:63-167`，四层嵌套）：

```
try (registration = harness().onStreamEvent(...)) {          // (:63)  X
    List<Entry> transcript = List.of();                     // (:83)
    try { outcome = harness().prompt(...); flush; ... }      // (:84-101)
    catch (Exception e) { …发 StreamError… }                 // (:105)  B  ← 引擎抛出
    entriesFuture.complete(transcript);                      // (:119)
    …条目投递 (:122-135) · emit AgentSettled (:138) · run summary (:139-147)…
    statusFuture.complete(new RunStatus(exitCode, stopReason)); // (:149)
} catch (Exception e) { …LOG.error · 发 StreamError · emit AgentEnd(List.of(), false)
                          · emit AgentSettled · statusFuture=(1,error) · entriesFuture=[]… }  // (:151)  C  ← 宿主自身
finally { if (streamObserver == null) queue.add(Optional.empty()); }  // (:164-167)  只关队列
```

**四条离开路径 × 今天的收口**（这是本包的全部事实基础）：

| # | 抛出点 | 今天谁接 | `entriesFuture` | `statusFuture` | `AgentEnd` | `AgentSettled` |
|---|---|---|---|---|---|---|
| **A** | **B 区内**抛 `Exception`（`harness.prompt`／`flush`／`runIds`／`transcript`） | `:105` `catch (Exception)` | ✅ 落定（= 局部初值 `List.of()`） | ✅ `(1,error)` | ❌ | ✅ `:138` |
| **B** | **B 区内**抛 `Error`（工具 worker、钩子、引擎内部） | **无人接** | ❌ **永不完成** | ❌ **永不完成** | ❌ | ❌ |
| **C** | **B 区外、(X) 区内**抛 `Exception`（条目投递／`emitSessionEvent`／`RunSummaryAggregator`／`printRunSummary`／`flush`） | `:151` `catch (Exception)` | ✅ 落定（= `List.of()`） | ✅ `(1,error)` | ✅ `:160` | ✅ `:161` |
| **D** | **B 区外、(X) 区内**抛 `Error` | **无人接** | ❌ **永不完成** | ❌ **永不完成** | ❌ | ❌ |

**两个事实要点**：

1. 路径 B/D 上，`finally`（`:164-167`）**照跑**，所以 `queue` 一定关闭、流一定终止 ——
   挂起的**不是流，是两个 future**。而 `SessionResult.entries()/status()` 是 `join()`（`:37/:42`）。
2. **车道本身没卡**：引擎侧 `PiLaneEngine.drive:200-205` 自己的 `finally` 已经把 `activeRun` 卸下、
   `run.done()` 完成（该处注释原文「驱动**抛出**时同样要收口…异常路径按 error 结算，原异常照常向上抛」）。
   ⇒ **引擎已经有的那条纪律，宿主没有**。这是本包的判据形状（§8.36.3）。
3. **第 2 步之后这张表会怎么变**（读表前先知道，免得把「基线的今天」当成「交付后的明天」）：
   A 与 B（引擎内抛出）**不再离开引擎** —— 由 §8.36.5 的 catch 接住并转成「一条失败消息 + 四事件 +
   正常返回」；宿主两处 `catch` 的可达面收窄为「`(X)` 区之外」（C/D）与「catch 体自身抛出」。
   C/D 一条不动。⇒ 表里 A/B 两行仍是**今天**的事实，第 1 步（§8.36.4）对它们的价值从「收口」变成
   「catch 体自身抛出时的兜底」（§8.36.5 下游表末行）。

**消费者与各自的挂起形态**（全仓实测，共 4 个）：

| 消费者 | 代码 | 挂起形态 |
|---|---|---|
| 打印模式 | `PrintMode.java:38`／`:61` `result.status().exitCode()` | **`pi-java -p` 永久挂起**，只能 Ctrl-C（`status().join()` 永不返回） |
| 斜杠命令 | `SkillsCommands.java:55` `result.entries()` | 同上（`entries().join()`） |
| 交互模式 | `PiTuiApp.java:357` `statusFuture().thenAccept(...)` | 回调**永不触发** ⇒ 停在 streaming 态 |
| RPC | `RpcDispatcher.java:283` `streaming = true`，`:313-316` 由 `AgentEnd`**或** `AgentSettled` 清 | 两条都不发 ⇒ `streaming` 恒真（`get_state` 也这么报） |
| web | `WebDispatcher.java:137` 发射后不管，`client/main.ts:256-258` `case "agent_end": streamingMessage = null` | 前端转圈不止 |

**`Error` 的到达路径**（生产可达，非角落）：

- **工具 worker**：`PiToolRunner:113/163/175` 是 `catch (Exception e)` ⇒ 工具体抛的 `Error` 不被接、
  原样冒到 `FutureTask`；`PiLoopTools.awaitOutcome:218-234` **刻意**解包原样重抛（javadoc 原文
  「非受检的 `Error` 也照原样冒」）⇒ 直接穿出 `PiLoop.run` → `harness.prompt` → 路径 B。
  这正是 §8.26.5-12 自己登记的那条下游（「宿主那半边见 B5」）。
- **钩子**：`HookSystem.fireVoid:319-327` 是 `catch (Exception e)` ⇒ 钩子抛的 `Error` 冒穿。
- **引擎内部**：`PiLoop`／`PiLoopRunner`／`AgentHarness` **零个** catch 子句（已逐文件确认）。
- **`Error` 的现实现实来源**：`StackOverflowError`（深层 JSON/正则）、`OutOfMemoryError`（大文件）、
  `NoClassDefFoundError`／`ExceptionInInitializerError`（懒初始化）、`LinkageError`；
  **native 产物上还有** native-image 自己的 `Error` 族。
- ⚠️ **不是** provider 层：`AbstractChatApi:104` 的 `Flow.Subscriber.onError(Throwable)` 已把
  provider 侧的 `Throwable` 兜成 `StreamError` 事件 ⇒ **provider 的 `Error` 到不了路径 B**。
  （这条决定了夹具不能拿 provider 当注入点，见 §8.36.4。）

#### 8.36.2 pi 的对照（`packages/agent/src/agent.ts`）

| pi | 逐字 | pi-java 现状 |
|---|---|---|
| 执行体收口 | `runWithLifecycle:484-504`：`try { await executor(signal) } catch (error) { await this.handleRunFailure(error, signal.aborted) } finally { this.finishRun() }` —— **`catch` 无类型** | 两条 `catch` 都写死 `Exception`；`finally` 只关队列 |
| 失败被「压成文本」 | `handleRunFailure:506-521`：合成一条 assistant 消息 —— `content:[{type:"text",text:""}]`、`api/provider/model` 取自 `_state.model`、`usage: EMPTY_USAGE`、`stopReason: aborted ? "aborted" : "error"`、`errorMessage: error instanceof Error ? error.message : String(error)`、`timestamp: Date.now()` | **无对应物 ⇒ §8.36.5 补**（用户 2026-09-19 已裁：做，落引擎侧） |
| 失败**照常走事件链** | `:522-525` 依次 `processEvents(message_start/message_end/turn_end/agent_end)` ⇒ 监听者**一定**看到 `agent_end`，promise **resolve**（不 reject） | 路径 A **不发 `AgentEnd`**；路径 B/D 什么都不发 |
| 「没有 Error/Exception 之分」 | TS 的 `catch` 一律接得住（栈溢出在 JS 是 `RangeError`） | Java 的 `Exception`／`Error` 分裂是**方言**，pi 侧**没有**这个可错配的形状 |

#### 8.36.3 判定：差距的**精确形状**

**主差距**：

> **宿主的收口写在 `catch` 里，而不是写在 `finally` 里 —— 于是「收口是否发生」取决于
> 「抛出来的东西是不是 `Exception`」。**

引擎侧已经把这条纪律写对了（`PiLaneEngine.drive:200-205` 的 `finally`），宿主侧没有。
按判据（分支所有功能都和 pi 表现一样）：pi 的承诺是**「一个 run 一定会落定，且一定发 `agent_end`」**——
pi-java 今天两条都不保证（落定只在 `Exception` 世界保证，`agent_end` 只在路径 C 保证）。
⇒ 两半都要补：**落定**由 §8.36.4 收，**`agent_end`（连同「失败为什么是一条消息」）**由 §8.36.5 收。
两半各自独立可验（探针 P1/P3 钉前半，P4 钉后半）。

**副差距（同因、不同层，本包只登记）**：`RunLifecycle.begin` 之后、`drive` 的 `try` 之前那一段
（`fireBeforeRun`／`publishState`／`openRunSpan`）没有收口 ⇒ `activeRun` 泄漏、车道永久忙（登记 -14）。

#### 8.36.4 设计（第 1 步，可实施）

**①-a 行为：两处 `catch (Exception)` → `catch (Throwable)`**（`:105`、`:151` 各一处，两处字面替换）。

- 效果：`Error` 与 `Exception` 同待遇 —— 路径 B 走 A 的收口、路径 D 走 C 的收口，`StreamError`
  事件随之发出（因此**可见**），日志有 `LOG.warn/error`。
- 依据：pi 的 `catch` 无类型（§8.36.2 第 4 行）；这是**把 Java 方言造成的缺口补回 pi 的形状**，不是发明。
- 编译面核查：`StreamEvent.StreamError(String, Throwable, AssistantMessage)`（`StreamEvent.java:214`）
  的第 2 参数本来就是 `Throwable` ⇒ 无需改任何签名。

**①-b 活性：`finally` 补幂等兜底**（`:164-167`）：

```java
} finally {
    if (streamObserver == null) {
        queue.add(Optional.empty());
    }
    // 兜底收口（照抄引擎侧 PiLaneEngine.drive:200-205 的同一条纪律）：无论从哪条路离开
    // ——含 catch 体自己再抛、含 Error 直接穿出——两个 future 都必须落定，否则
    // SessionResult.status()/entries()（join）永久挂起，打印模式只能 Ctrl-C。
    // complete() 幂等：正常路径上这两次调用都是无操作。
    entriesFuture.complete(List.of());
    statusFuture.complete(new RunStatus(1, "error"));
}
```

**为什么两半都要**（各自单独不成立）：

| 只有 ①-a | 只有 ①-b |
|---|---|
| catch 体自己抛（`emitSessionEvent` 的监听器抛 `Error` —— `SessionEventHub.emit:29-37` **只隔离 `RuntimeException`**、`Error` 穿出）⇒ 路径 C 的收口死在 `AgentEnd` 与 `AgentSettled` 之间 ⇒ **仍然挂起** | `Error` 被静默吞掉（无 `StreamError` 事件、无日志）⇒ 打印模式只会「什么都没发生地退出 1」，`-v` 也看不到原因 |

**如实标注两处边界**：

1. **不承诺 OOM 极端下的活性**：若 `finally` 里的 `complete` 自身因 OOM 再抛，`statusFuture` 仍可能不落定。
   本包把「**结构性挂起**」堵死；「JVM 已在崩溃态」不在承诺范围内。这条写进 javadoc。
2. `entriesFuture.complete(List.of())` 在兜底路上给**空表**（与 `:163` 同口径）。残余转录仍可由
   `harness.snapshot(lane).transcript()` 取到 —— 但**本包不改**这个口径（见 §8.36.7 的 -15：
   路 C 的 `AgentEnd` 载荷是**产品面**的另一个决定，与活性无关）。

#### 8.36.5 第 2 步设计：`handleRunFailure` 落**引擎**侧（**用户 2026-09-19 已裁：做，落引擎侧**）

pi 把它放在 `Agent`（= pi-java 的**引擎**：`PiLaneEngine` + `PiLaneSink`），不是会话层。
落引擎侧的**收益**是形状忠实（失败变成一条**消息**，进转录、进 `message_end`/`agent_end`，
前端的整表替换于是拿到一条真实存在的失败助手消息）；**代价**是 §8.32.6-C 点名的两条，逐条核过：

| 点名的风险 | 裁「落引擎侧」并核到底之后的实际情况 |
|---|---|
| 「打开一条 pi-java 从未有过的**从异常重试**入口」 | **前提被我自己的下一段推翻了**：pi 的失败消息**本来就会进重试判定**（`agent-session.ts:1123` 读 `_lastAssistantMessage`，而它由这条合成消息赋值）。⇒ 这**不是**新入口，而是 pi 的既有行为；Java 要做的只是**别把它挡掉**（catch 放在 `while` 里，见下） |
| 「可能让宿主把失败记成 **completed** 而非 error」 | **是真的，且必须在同一包解决**：宿主的 `stopReason`（`SessionRunner:66-73`）**只**由 `StreamEvent.StreamDone/StreamError` 喂，合成一条 `Message` 不会改它 ⇒ 若引擎把异常吞成消息而宿主不看消息，会报 `(0, completed)`、**崩溃的 run 退出码 0**。⇒ 落点定案（下）之后，这一条由「宿主读尾 assistant」（下下段）一次解掉 |
| **新发现（本包登记）** | 路径 **A**（引擎抛**普通 `Exception`**）今天**也不发 `AgentEnd`** ⇒ web 前端（`client/main.ts:256` 靠 `agent_end` 清 `streamingMessage`）**在非 `Error` 的引擎崩溃上同样永久转圈**。不是 `Error` 问题，是「两条失败路形状不同」。**裁「落引擎侧」后收窄为一半**：**引擎内**抛出的那些随本步关闭（在引擎里就被接住、转成四事件）；**宿主层**抛出的（引擎 catch 体自身抛、`prompt()` 在 `drive` 之外的部分、`:119-149`）仍不发 `AgentEnd` ⇒ 与「路 C 的空数组载荷」一起并入登记 **-15** |

**落点：`PiLaneEngine.drive` 的 `while` 之内、`runPass(...)` 的调用点上**。判据是 pi 的两处逐字：

- pi 的 `handleRunFailure` 在 `Agent`（= pi-java 的引擎 `PiLaneEngine` + `PiLaneSink`），不在会话层；
- **决定性的一条**：pi 的 `_handlePostAgentRun:1116-1123` 读 `_lastAssistantMessage`，而后者由 `message_end`
  监听器在**这条合成消息**上赋值（`agent-session.ts:685-691`：`appendMessage` 落盘 + `_lastAssistantMessage = event.message`）
  ⇒ **pi 的失败消息会进重试判定**（`:1123` `_isRetryableError(msg) && _prepareRetry(msg)`）。
  Java 的重试环住在 `while` **里**（`postRun.checkAfterRun`）⇒ catch 必须放在循环内，否则合成的失败消息
  进不了重试判定，与 pi 分家。**这同时改写了上表第一行的结论**：「从异常重试」不是要**打开**的入口，
  而是 pi **本来就有**的行为 —— §8.32.6-C 把它当风险点名，是把 pi 的既有形状当成了新增面。
- **结构 1:1**：pi 的 `runWithLifecycle` 包的是 `_runAgentPrompt`（**一个 pass**），不是整个 `prompt()` 循环
  （`agent.ts:484-504`）；`_handlePostAgentRun` 在 `prompt()` 的循环里（`agent-session.ts:1109-1113`）。
  Java 的 `runPass` ≙ `_runAgentPrompt`、`postRun.checkAfterRun` ≙ `_handlePostAgentRun`
  ⇒ try/catch 落在 `while` 体内、`runPass` 调用点上，是**逐层同构**，不是方言取舍。
- **顺序**（也照 pi）：`handleRunFailure` 在 `finally { finishRun() }` **之前**（`agent.ts:500-504`）
  ⇒ Java 里合成先于 `drive` 的 `finally` 结算。

**逐字移植（`packages/agent/src/agent.ts:506-525`）**：

| 字段／事件 | pi 原文 | Java 落法 |
|---|---|---|
| `content` | `[{ type: "text", text: "" }]` | `List.of(new ContentBlock.TextContent(""))` —— **空块照发**（pi 如此） |
| `api` / `provider` / `model` | `this._state.model.api/provider/id` | `lane.model` 的三个分量（`ModelId`） |
| `usage` | `EMPTY_USAGE`（`agent.ts:39-46`） | `Usage.of(0, 0)`（现成工厂＝`new Usage(0,0,0,0,null,null,0,Cost.zero())`；pi 的 `EMPTY_USAGE` 没有 `reasoning` 分量，正好对上） |
| `stopReason` | `aborted ? "aborted" : "error"`，`aborted` 来自 `abortController.signal.aborted` | `var sig = lane.abortSignal(); boolean aborted = sig != null && sig.isAborted();`（`LaneState:201` = `activeRun.signal()`，与 `PiLoopTools.aborted(config)` 同一判据、同一对象；catch 在循环内 ⇒ `activeRun` 尚未 `finishRun` 清掉，取得到） |
| `errorMessage` | `error instanceof Error ? error.message : String(error)` | `t.getMessage() != null ? t.getMessage() : t.toString()` —— **pi 的非 `Error` 分支在 Java 不成立**：`Throwable` 恒有 `getMessage()`（`AssertionError("boom")` 也取得到 `"boom"`），`Error`（Java 的）**不是**「没有 message」，两语言的 `Error` 只是同名不同物。`toString()` 兜底＝`类名: message` |
| `timestamp` | `Date.now()` | `Instant.now()` |
| 事件 ×4 | `message_start` → `message_end` → `turn_end(message, [])` → `agent_end([message])` | 依次 `sink.emit(new PiLoop.Event.MessageStart(f))` / `MessageEnd(f)` / `TurnEnd(f, List.of())` / `AgentEnd(List.of(f))`（记录形状见 `PiLoop.java:48-64`） |

**这条合成消息要顺带驱动两条宿主侧结果，缺一不可**（详见下面「下游副作用」表与「失败如何到宿主」）：

- **车道级**：`determineOutcome(lane)` 读的是车道副本的尾条，合成消息一进去就变 `"error"`
  ⇒ `OperationFinished` / run span 记 error；
- **会话级**：`SessionResult.status()` **不读**车道副本，只认宿主自己的 `stopReason` 字段
  ⇒ 必须另补一条读尾 assistant 的判定（下下段）。

**结构改动（两处，都不是新行为）**：

1. **`runPass` 拆成两半**：catch 需要拿到 sink 引用，而今天 sink 是 `runPass` 的局部变量。
   拆成 `startPass(...)`（建 present 集合 → `new PiLaneSink` → `configFor` → 系统提示 → 工作副本）
   与 `drivePass(pass)`（`PiLoop.run`/`continueRun`），中间夹 try/catch。
2. **新类 `RunFailure`**（`com.pijava.agent.harness`，约 70 行含 javadoc）：`PiLaneEngine.java` 现 **445 行**
   （上限 500），合成逻辑＋四事件＋javadoc 塞进去会越线。

**失败如何到宿主（pi 的规则，`modes/print-mode.ts:139-155` 逐字）**：

pi 的 print 模式**不**看任何流信号 —— 它在 `agent.prompt()` **resolve 之后**读 `session.state.messages[last]`；
尾消息是 assistant 且 `stopReason === "error" | "aborted"` ⇒ 打 `errorMessage`（缺则打 `Request <stopReason>`）、
**`exitCode = 1`**；否则逐 text 块打印（`print-mode.ts:139-155`）。

⇒ Java 的对应点**也不是事件处理器，而是 `SessionRunner.drive` 里 `harness().prompt(...)` 返回之后、
`statusFuture` 落定之前**（`:104` 拿到 `outcome.transcript()` 与 `:137` 的 `entriesFuture.complete` 之间）
对**尾 assistant** 的一次读 —— 与 pi 同形「跑完再读」。

**落法**（读的是已经在手上的 `transcript`）：

```java
// pi print-mode.ts:139-155：跑完之后读尾 assistant 定终局，而不是看流信号。
var tail = tailAssistant(transcript);
if (tail != null && ("error".equals(tail.stopReason()) || "aborted".equals(tail.stopReason()))) {
    stopReason.set(tail.stopReason());
}
```

- ⚠️ **与本节初稿（`38f991c` 之前）的两处偏差，实施时按「照抄 pi」改正**（这一节是本步的**唯一**依据，
  故以 pi 原文为准改这里，而不是让代码迁就初稿）：
  1. **初稿那个 `if (!sawTerminal.get())` 闸被删掉** —— pi **无条件**读尾（`print-mode.ts:139-155`
     里没有任何「观测到过终局就跳过」的条件）。加闸会**真出错**：一次驱动可能跑**多个 pass**、
     每个 pass **至少一条** `StreamDone`（多轮工具调用每轮一条）⇒ 夹具 1 里工具批次炸掉时
     `sawTerminal` **早已置位**（前一轮工具调用的 `StreamDone` 就置了它）、`stopReason` 停在
     `"tool_use"` ⇒ **崩溃的 run 被记成 `(0, "tool_use")`、退出码 0** —— 正是本步要修的那个病。
     「见过终局」与「这次运行以失败收场」是**两件事**，初稿把它们当成一件了。
  2. **判定必须读「转录的最后一条消息」而不是「最后一条助手消息」** —— pi 是
     `state.messages[last]?.role === "assistant"`，**不往回找**更早的助手。初稿的 `lastAssistant`
     是倒扫跳过非消息条目后**继续找助手**（会把「尾条是工具结果」这种正常形状误判成尾条是崩溃的
     助手消息）。落地版 `tailAssistant` 照抄 pi：倒扫跳过非消息条目，遇到**第一条**消息条目就定案
     —— 是助手则返回，否则 `null`。
- 判定**复用 `exitCode` 的既有词表**（`error`→1、`aborted`→130），不新造取值。
- 与 B20 ⑩ 同口径：`"completed"` 是「什么都没观测到」的既有缺省，本次不动它的语义。

**这条对产品面的直接效果**：`passEvents` 的 `AgentEnd` 分支发的是
`owner.accumulatedMessages()`（`:208-209`，**整表**），合成消息已落盘 ⇒ web 的整表替换拿到一条
真实的失败助手消息 ⇒ `client/main.ts:256` 的 `agent_end` 一到就清 `streamingMessage`，**转圈结束**。
（RPC 的 `streaming` 同理：`RpcDispatcher:313-316` 由 `AgentEnd` **或** `AgentSettled` 清，两者本步都会来。）

**下游副作用逐条核过**（`PiLaneSink.onMessageEnd:342-397` 对一条 `stopReason="error"` 的助手消息）：

| 副作用 | 结果 |
|---|---|
| 转录落盘 + 工作副本追加 | ✔ 与 pi 的 `appendMessage`（`agent-session.ts:685`）同 |
| `lastAssistant` | ✔ 赋值（pi 的 `_lastAssistantMessage`，`:691`）⇒ 重试环与压缩检查都看得到 |
| 溢出恢复闩锁 | ✔ `stopReason="error"` **不复位**（pi `:694-696` 同） |
| `auto_retry_end{success:true}` | ✔ **不误发**（判据 `!"error".equals(stopReason)`，pi `:698-706` 同） |
| `lane.newestOwn` | ✔ 重算 ⇒ `finally` 里 `HarnessUtils.determineOutcome(lane)` 得 `"error"` ⇒ `OperationFinished` 记 FAILED、run span 标 error（`RunLifecycle.outcome` 的注释正是在讲这件事） |
| `endRequest` 的 `llmSpan` | ✔ 有 `if (llmSpan != null)` 守卫（`:204`）⇒ 崩溃点在「没有在飞请求」时也不会关错跨度 |
| `AgentEnd` 只发一条 | ⚠️ 若下游 sink 在**引擎**发完 `AgentEnd` **之后**才抛，catch 会再合成一条 ⇒ 两条 `AgentEnd`。pi 同形（监听者在 `processEvents` 里抛会冒到 `handleRunFailure` 再发一条）。**如实标注，不改** |
| catch 体**自己**抛 | ⚠️ 合成要过 `sink.emit`，而下游（宿主的 `emitSessionEvent` → 会话监听者）**正是上次抛出者** ⇒ 第二次抛会从 catch 体里冒出去，`drive` 的 `finally` 只收口车道、**合成消息落不了盘**。pi 同形（`handleRunFailure` 里 `await this.processEvents` 再抛就没人接了）。这正是**第 1 步不能省**的原因：宿主的两处 `catch (Throwable)` + `finally` 是这一层的兜底 |

**这一步关闭了什么、没关闭什么（如实划界）**：

| | 第 2 步之前 | 第 2 步之后 |
|---|---|---|
| **引擎内部**抛出（工具 worker 的 `Error`、钩子的 `Error`、`PiLoop` 自身） | 逃到宿主：路 A 无 `AgentEnd`、路 B 全无 | **关闭**：引擎里接住 ⇒ 合成消息 + 四事件 + 正常返回 ⇒ `AgentEnd` 一定发（且带整表）。web 转圈、RPC `streaming` 恒真两条**随之关闭** |
| **宿主层**抛出（引擎 catch 体自身抛、`prompt()` 在 `drive` 之外的部分、`:119-149` 的条目投递/摘要） | 路 A 无 `AgentEnd`；路 C 有但**载荷是空数组** | **不变**：仍在宿主的 `:105`/`:151` 收口，路 A 依旧**不发** `AgentEnd` ⇒ RPC/web 在那条路上依旧挂。**合并登记为 -15**（与「路 C 载荷是 `List.of()`」同一个未决决定） |
| 两处 catch 的形状 | 只接 `Exception` | 接 `Throwable`（第 1 步）；`finally` 幂等兜底 |

⇒ **第 1 步不是「可省的冗余」**：它与第 2 步的触发面**互不重叠** —— 第 2 步吃「引擎内部」，
第 1 步吃「宿主层」与「引擎 catch 体自身抛」（下游表末行）。两条都留着。

#### 8.36.6 夹具与验证（**先红证毕**）

**先划清「谁钉谁」**（不划清就会出现「一条夹具同时钉两步、回退任一步都变红」的假绿灯）：

| 夹具 | 注入点 | 钉的是 | 住哪 |
|---|---|---|---|
| **1** | Sequential 工具抛 `AssertionError`（引擎内） | **第 2 步**（引擎合成）＋ 第 2 步带来的活性 | **agent-core**（C 组；`PiLoop.Event` 只在引擎边界可见）＋ **coding-agent**（A 组） |
| **2a** | 会话监听器在 `AgentSettled` 上抛 `AssertionError` | **第 1 步的外层 catch**（与 ①-b 的兜底） | coding-agent |
| **2b** | 会话监听器在 `AgentEnd` 上抛 `AssertionError` | **第 1 步的引擎外 catch**（`Error` 逃出 `harness.prompt` 之后谁来接） | coding-agent |
| **3** | 同夹具 1，但异常文本命中瞬断白名单 | **第 2 步的 placement**（catch 必须在 `while` 里） | **agent-core** |

⚠️ **实施细化（比本表的括号更窄）**：夹具 1 的断言分**两个模块**写 —— C 组（形状：`PiLoop.Event` 四事件、
合成消息字段、转录与结算）**只能**在 agent-core 断言（`PiLoop.Event` 不越过引擎边界），
A 组（future 落定、会话事件面、无 `StreamError`）**只能**在 coding-agent 断言。
⇒ 落地为 `agent-core:EngineFailureSettlementTest` ＋ `coding-agent:SessionFailurePathTest`
（后者另含夹具 2a/2b），不是「夹具 1 整条住在某一侧」。

**夹具 1（引擎内 `Error`：合成 + 活性）** —— 走 §8.36.1 的**生产可达路径**：

- 剧本：`FauxProvider.sequence` 第一 pass 发一个**自定义工具**的 `ToolCallEnd`（`ExecutionMode.Sequential`，
  走 `executeSequential`——工具体在**引擎线程**上跑，最短路径）；
- 注入点：`harness.setActiveTools(Set.of(boomTool))`（`AgentHarness:458` 公开）；
- `boomTool.execute(...)` 抛 `new AssertionError("boom")` ⇒ 穿 `PiToolRunner` 的 `catch (Exception)`
  ⇒ 穿 `PiLoopTools.executeSequential` ⇒ 穿 `PiLoop.run` ⇒ 穿 `harness.prompt` ⇒ **路径 B**；
- 断言分两组：

| 组 | 断言 | 钉住的实现 |
|---|---|---|
| **A（活性）** | **(A1)** `statusFuture().get(10, SECONDS)` 完成且 `(1,"error")`；**(A2)** 会话事件流里出现 `AgentEnd`；**(A3)** **没有** `StreamError` 事件（pi 的引擎内失败走**消息**不走流错误） | 第 2 步的合成 ＋ `print-mode.ts:139-155` 的宿主读尾（A1）；四事件（A2/A3） |
| **C（形状）** | **(C1)** `PiLoop` 事件流里 `message_start`/`message_end`/`turn_end`/`agent_end` **各一条**，携带的助手消息 `content == [TextContent("")]`、`usage.input == 0`、`api/provider/model == 车道模型`；**(C2)** 该消息 `stopReason == "error"`、`errorMessage == "boom"`；**(C3)** 转录里**恰好多一条**助手消息（`present` 去重、`newestOwn` 重算都走通）；**(C4)** `lane` 的 run 结算为 error（`OperationFinished` outcome） | pi `agent.ts:506-525` 逐字、落盘、`determineOutcome` |

- **今天的红灯**：A1 ⇒ `get(10s)` 抛 `TimeoutException`（这就是缺陷本身）；C1/C2/C3 ⇒ **根本没有**合成消息。
  （`result.stream().toList()` 能正常终止 —— `finally` 关队列 —— 所以红点精确落在 future 与事件上。）
- ⚠️ **夹具不能拿 provider 当注入点**（§8.36.1 末条已实证 `AbstractChatApi:104` 会把 provider 的
  `Throwable` 兜成事件）；**也不用** `before_run` 钩子 —— 它在 `RunLifecycle.begin` **之后**抛，
  还会连带暴露 `activeRun` 泄漏（§8.36.7），会把两件事混在一条红里。
- ⚠️ **A1 在第 2 步之后是被两条独立的纪律同时保住的**（合成后的正常返回 ＋ 第 1 步的
  `catch (Throwable)` 体里的两次 `complete`）⇒ **A1 不是第 2 步的判别器**，C1/C2/C3 才是（见 P4/P5）。
  ⚠️ **实测修正**：那第二条纪律**不是** `finally`（`finally` 只在「外层 catch 体自身再抛」那一条路上
  才是出口，见 P2 行）—— 初稿把两条纪律里的第二条写成了 `finally`，P2 的实测把它纠正了。

**夹具 2a（宿主的外层 catch：catch 体自己抛）** —— 行号按落地后的 `SessionRunner`：

- 注入点：`session.subscribe(...)` 注册一个**在 `AgentSettled` 上抛 `AssertionError`** 的监听器
  （`SessionEventHub.emit:35` 只隔离 `RuntimeException`，`Error` 穿出 —— 已逐行确认）；
- 路径：正常收尾路的 `AgentSettled`（`:156`）先抛 ⇒ **外层 catch**（`:169`）⇒ 其 catch 体在
  `AgentEnd`（`:179`）之后发 `AgentSettled`（`:180`）时**再次**被同一监听器打断 ⇒ catch 尾部
  两次 `complete`（`:181-182`）跑不到 ⇒ **只有 `finally`（`:193-194`）能救这两个 future**；
- 断言：**(B1)** 两个 future 都落定（钉 `finally` 兜底）；**(B2)** 会话事件流里出现 `StreamError`
  （钉 `:169` 的 `catch (Throwable)`）。

**夹具 2b（宿主的内层 catch：`Error` 逃出引擎之后谁来接）**：

- 注入点：同一 `subscribe(...)`，但监听器**只在 `AgentEnd` 上抛**，
  **不**在 `AgentSettled`/`EntryAppended` 上抛。
- ⚠️ **与本节初稿的偏差（实施时改正）**：初稿写的是「监听器在 **pass 内事件（`MessageUpdate`）** 上抛」，
  **这个形状造不出要钉的东西**。原因：`HarnessEventBus.broadcastStream:38` 与
  `SessionEventHub.emit:35` 都**只隔离 `RuntimeException`** ⇒ `MessageUpdate` 监听器抛的 `Error` 会
  穿回 `PiLoop.run`，被**引擎**的 catch 接住 —— 而引擎 catch 体（`RunFailure.settle`）发的四事件里
  **只有 `AgentEnd` 会走到会话监听面**（`MessageStart`/`MessageEnd`/`TurnEnd` 只是 `PiLoop.Event`，
  宿主不经由 `emitSessionEvent` 转发它们）⇒ 监听器**不会被第二次触发**，`Error` 被引擎吞掉，
  一切正常完成。**唯一能把 `Error` 送出 `harness.prompt` 的形状**是让监听器在 `AgentEnd` 上抛：
  那正是「引擎 catch 体自己抛」那条路（§8.36.5 下游表末行）。
- 路径（两条 `AgentEnd` 发射点，都在引擎之内）：pass 正常收尾时 `passEvents` 投递的
  `AgentEnd`（`SessionRunner:235`）先抛 ⇒ 引擎 catch（`PiLaneEngine` 的 `catch (Throwable)`）接住
  ⇒ `RunFailure.settle` 再 `emit(AgentEnd([failure]))` ⇒ 同一监听器**第二次**抛 ⇒ 逃出
  `harness.prompt` ⇒ **宿主内层 catch（`:105`）**；
- 断言：**(B3)** 两个 future 都落定；**(B4)** 会话事件流里出现 `StreamError`；**(B5)** 会话事件流里
  **没有载荷为空的 `AgentEnd`** —— 这条**才是 P1 的判别器**：`:105` 若回退成 `catch (Exception)`，
  这个 `Error` 就会掉到 `:169`（那里是 `Throwable`），于是 `:179` 的
  `AgentEnd(List.of(), false)` 就会冒出来（登记 -15(a)）。
- ⚠️ **B4 不是 P1 的判别器**（两条路都发 `StreamError`，只是一个来自 `:105`、一个来自 `:169`）
  —— 把它当判别器就会得到一个「回退也绿」的假探针。**B5 的断言写法也要跟着改**：初稿写「没有 `AgentEnd`」，
  但落到 `AgentEnd` 上抛的注入点后，事件流里**必然**有若干条 `AgentEnd`（`passEvents` 那条先发出去才抛的），
  真正缺的是**空载荷**的那一条 ⇒ 断言改成 `noneMatch(end -> end.messages().isEmpty())`。

**夹具 3（第 2 步的 placement：catch 必须在 `while` 里）**：

- **同一注入点**，但 `boomTool.execute(...)` 抛 `new AssertionError("connection refused")`
  —— 文本命中瞬断白名单（`RetryableError:87` 的 `connection.?refused`）；
- ⚠️ **与本节初稿的偏差（实施时改正，且必须改，否则夹具根本跑不起来）**：初稿说「同一注入点」，
  即批次里**只有**一个抛错的工具 —— **那个形状不可达**。`_prepareRetry` 摘掉工作副本尾部的失败助手
  （`agent-session.ts:2941-2945`）之后，副本尾停在 `stopReason="tool_use"` 的那条助手消息上，
  而 `Agent.continue()` **要求尾部不是助手**（`agent.ts:372`，否则抛
  `Cannot continue from message role: assistant`）⇒ 第二个 pass 起不来、夹具 3 恒红。
  **pi 自己也是这样**（同一段代码），所以这不是缺陷、也不该绕 —— 而是要把剧本改成 **pi 的可达形状**：
  批次里放**两个工具 `[echo, boom]`**，顺序路径会先把 `echo` 的结果消息落进工作副本
  （`PiLoopTools:96-113` 逐调用成组），摘尾后尾部是工具结果消息 ⇒ `continue()` 遂行。
  ⇒ 落地夹具的批次 = `[echo, boom]`，`echo` 是**必需**的、不是装饰。
- 引擎的合成消息于是带 `errorMessage="connection refused"` ⇒ `PostRunRetry.isRetryableError` 为真
  ⇒ `_prepareRetry` 跑 ⇒ 发 `auto_retry_start`、摘工作副本尾、起第 2 个 pass；
- 断言：**(D1)** 重试观察者收到 `auto_retry_start`/`auto_retry_end`（**只有 catch 在 `while` 内才可能出现**
  —— 环住在循环里）；**(D2)** 转录里**留着**那条失败助手消息（日志保留、只摘副本，`:2937-2941`）；
  **(D3)** `FauxProvider.sequence` 第二 pass 正常收尾 ⇒ 终局成功（`retryAttempt == 1`）。
- ⚠️ **D1/D3 的观测面按模块分**：初稿写的是「会话事件流里出现 `auto_retry_start`」与
  「`status()` 是成功」—— 那是**宿主侧**的说法。夹具 3 落在 agent-core（与夹具 1 的 C 组同侧），
  引擎侧能直接看到的是 `RetryObserver` 回调与引擎自己的转录/记录 ⇒ 落地断言取这两个：
  `RetryObserver` 收到 `start|1|3|connection refused` + `end|true|1|null`，末条助手 `stopReason=="stop"`、
  末条 `OperationFinished` 为 `COMPLETED`。宿主的 `status()`／`retryAttempt` 由 §8.22 的既有用例守，
  不在这里重复。
- ⚠️ 若第 2 个 pass 也抛，就会走到预算耗尽/终局失败那条路 ⇒ 剧本给第二 pass 一条正常响应，保持确定性。
- ⚠️ D1 **只**靠「合成的失败消息进了 `checkAfterRun`」这一条；不要把 D1 和 C1 混在一条用例里
  ——C1 在「catch 在循环外」时**仍然是绿的**（消息照样合成，只是晚了一步）。

**变异探针（五条，各自必须恰一条红）**：

| 探针 | 回退 | 预期（**唯一**红灯） | 为什么别的都不红 | **实测（2026-09-19）** |
|---|---|---|---|---|
| P1 | 宿主内层 catch 只回退成 `catch (Exception)` | 夹具 2b ⇒ **B5**（`Error` 掉到外层 catch，那里是 `Throwable` ⇒ 空载荷 `AgentEnd` 冒出来） | B4 两条路都发 `StreamError`（见夹具 2b 的 ⚠️）；B3 由 `finally` 兜住；夹具 1/2a/3 走不到内层 catch | ✔ **恰一条红**：`SessionFailurePathTest.listenerErrorOnAgentEndReachesHostFailurePath` 的 **B5** |
| P2 | 删宿主 `finally` 里的两行 `complete` | 夹具 1 ⇒ **A1**（`TimeoutException`）、夹具 2a ⇒ **B1**、夹具 2b ⇒ **B3** ⇒ **三条红**（这是**唯一**允许多红的探针：它钉的是「兜底那一层」，那条纪律是所有路的公共出口） | — | ⚠️ **只有一条红**：`listenerErrorOnAgentSettledStillSettlesFutures` 的 **B1**（`TimeoutException`，11.16 s）。**A1 与 B3 没红** —— 两条路各自的 catch 体里的 `complete` 就是正常出口：A1 走的是「引擎**正常返回**、全程无异常」，B3 走的是内层 catch 的 `:181-182`（那条路 listener 只在 `AgentEnd` 上抛，catch 体里 `AgentEnd` 之后没有第二次抛出 ⇒ 尾部的 `complete` 跑得到）。⇒ **初稿把 `finally` 说成「所有路的公共出口」是错的**，它只是**外层 catch 体自身再抛**那一条路的出口。此结论使「P2 是唯一允许多红的探针」这一豁免**失去理由** —— 五条探针实际都是恰一条红 |
| P3 | 宿主外层 catch 只回退成 `catch (Exception)` | 夹具 2a ⇒ **B2**（`StreamError` 缺） | B1 由 `finally` 兜住；2a 的 `Error` 不经过内层 catch | ✔ **恰一条红**：`listenerErrorOnAgentSettledStillSettlesFutures` 的 **B2** |
| P4 | **引擎侧合成整体删掉**（异常照旧冒到宿主；第 1 步保留） | 夹具 1 ⇒ **C1/C2/C3**（没有合成消息） | A1 由第 1 步的 `catch (Throwable)` 保；A2/A3 会跟着红 —— 但它们**不是** P4 的目标断言，实施时把 C 组与 A 组**分在两个 `@Test`** 里，让 P4 的红点落在 C 组的用例上 | ✔ C 组**两个用例全红**（`engineThrowSynthesizesFailureMessage`、`synthesizedFailureMessageEntersTranscriptAndSettlesRunAsFailed`）＋ ⚠️ **多一条红**：`SessionFailurePathTest.engineInternalErrorSettlesRunWithErrorStatus` 的 **A3**（没有合成消息 ⇒ 流上只剩宿主外层 catch 补的那条 `StreamError`）。断言 **A2** 与 A3 在同一用例里 ⇒ A 组与 C 组**分不开**（初稿说「分在两个 `@Test` 里」只对了 C 组那一半）。A1 **没红**、且**不是** `finally` 保的，是**外层 `catch (Throwable)` 体里的两次 `complete`（`:181-182`）**保的 —— 与 P2 行同一个道理，见上 |
| P5 | **把引擎的 catch 移到 `while` 之外** | 夹具 3 ⇒ **D1**（`auto_retry_start` 不出现） | 夹具 1 的 C1/C2/C3 **仍绿**（消息照样合成，只是晚一步）⇒ P5 与 P4 钉的是**两件不同的事** | ✔ **恰一条红**：`EngineFailureSettlementTest.retryableFailureTextEntersPostRunRetryDecision` 的 **D1**；C 组与全部 coding-agent 用例绿 |

**探针执行方式（可复现）**：改前把 `PiLaneEngine.java`／`SessionRunner.java` 备份到 `/tmp/b5bak/`，
每条探针独立改、跑、记，跑完从备份恢复并用 `diff` 逐字节比对确认（恢复后 `diff` 三者皆空）。
**探针不入库**（不是代码，是一次性的验证动作）；这里记的是**结果**。

**P1/P3 的「恰一条红」依赖夹具 2a/2b 分家**：2a 的监听器挂在 `AgentSettled` 上、2b 的只挂在 pass 内事件上
—— 若用一个「什么都抛」的监听器，两条夹具会互相污染，P1/P2/P3 就都变成多红。**这是实施时最容易踩的坑**。

**全量**：`mvn -o -pl pi-java-coding-agent -am test`（**必须带 `-am`**，memory `jdk25-mvn-am`）
＋ `mvn -o -pl pi-java-agent-core -am test`（第 2 步落在引擎 ⇒ 夹具 1/3 住在 agent-core 的 L5 家族旁）
＋ `mvn -o clean verify`（14 模块、checkstyle 零违规）。

**覆盖面边界（必须写进实施记录）**：

- **第 1 步**改的是**会话层事件与 future**，按 §8.32.5 第 1 条，L5 剧本（帧级、直接驱动 `PiLoop`）
  **结构上覆盖不到** ⇒ 只有夹具 2a/2b 能守。
- **第 2 步**落在引擎内、且合成消息**走 `sink`** ⇒ 原则上帧上可见（属于 L5 的观测面）。但 L5 的剧本
  用的是不抛的桩 Stream 与 faux 工具 ⇒ **造不出引擎内抛出**，等价于覆盖不到 ⇒ 仍由夹具 1/3 守。
  **不要**因此认为 L5 覆盖了它：实施记录里要写清「L5 14 个剧本全绿**不能**作为第 2 步的证据」。

#### 8.36.7 本包**不做**与**附带登记**

**不做**（本包只做第 1 步 + 第 2 步，其余一律登记）：

- **宿主层失败路的 `AgentEnd` 形状**（路 C 的空数组载荷 `:160`、路 A 干脆不发）—— 详见登记 **-15**。
  第 2 步只让「引擎内抛出」这条失败路带上真消息（与 pi 一致），宿主层那两条路是**另一回事**。
- 会话层别的东西（`SessionEventHub.emit` 只隔离 `RuntimeException`；`HookSystem.fireVoid` 只 catch
  `Exception`）—— 两处都是「`Error` 穿出」的既有形状，改它们会动**非失败通路的**语义边界，
  本包不动、也不登记（它们**不是**缺陷：pi 的 TS 里 `catch` 无类型，`Error` 本来就会穿到
  最近的一个 `catch`；Java 的这两处只隔离 `RuntimeException`，**比 pi 更窄地**隔离，方向是对的，
  只是路线不同 —— 真正需要收口的地方就是本包收的那两处）。

**附带登记（本包只记，不改）**：

| # | 发现 | 可达性 | 处置 |
|---|---|---|---|
| **14** | **`RunLifecycle.begin` 之后抛出的东西会让 `activeRun` 泄漏** —— `startRun:52-72` 的顺序是 `begin(lane)`（`:145-157` 里就把 `activeRun` 装上了）→ `openRunSpan` → `fireBeforeRun` → `transcript.add` → `records.add` → `publishState`；而**保护它的 `try/finally` 在 `PiLaneEngine.drive` 里，不在 `run` 里** ⇒ 这一段的抛出既不由 `drive` 的 finally 收口，也没有别的收口 ⇒ 车道**永久忙**（后续每次 `prompt` 都撞 `begin:150` 的 `IllegalStateException("not idle")`，`waitForIdle` 永远等下去）。**与 B5 同因（收口缺 finally）但不同层** | 今天**无生产路径**：`fireBeforeRun` 的生产注册者**零**（`onBeforeRun` 全仓只有声明）；`publishState` 的唯一下游 `watch()` 也**零**生产调用者；`openRunSpan` 是遥测。⇒ 只有测试能触发 | **登记，不改**（同 §8.32.6 的「投机代码」口径）。触发条件：出现 `before_run`／lane `watch` 的生产注册者，或遥测实现开始抛 |
| **15** | **宿主层的失败路与 `agent_end` 的形状不一致**（两个面，同一个未决决定）：**（a）路 C 的载荷是空数组** —— `SessionRunner:179` `new AgentSessionEvent.AgentEnd(List.of(), false)`，而**同一事件的正常路径带的是整表**（`:235-236` `owner.accumulatedMessages()`）⇒ 前端整表替换（`client/main.ts:260`）会把 web 历史清空；**（b）路 A 根本不发 `AgentEnd`**（`:105` 的 catch 续走 `:123-168`，不经过 `:179`）⇒ RPC 的 `streaming` 仍为真、web 仍转圈。pi 四处 `agent_end` 发射点**没有一处**是空数组，也**没有一处**缺少它 —— `agent-loop.ts:217/253/272` 传本轮**累积**消息 `newMessages`（起点是本轮 prompt 消息，恒非空），`agent.ts:526` 传 `[failureMessage]` | **（a）今天可达**（路 C ＝ 引擎**之外**的抛出 ＋ catch 体自身抛 ⇒ 一次会话监听者的 `Error` 就够）；**（b）今天可达**（`harness.prompt` 抛出的宿主层 Throwable，见 §8.36.6 夹具 2b） | **登记，不改**（产品面：改的是 `AgentEnd` 的载荷与发射点语义，且要**先定**「宿主层的失败该发什么」—— pi 没有对应形状可抄，因为 pi 没有「引擎外抛出」这一层；**最可能的答案**是照 `:235-236` 统一用 `accumulatedMessages()`，但那要另出设计与验证）。触发条件：RPC/`get_state` 的 `streaming` 卡住被报，或用户报 web 历史丢失 |

#### 8.36.8 实施记录（包③ 第 1 步 ＋ 第 2 步）

**交付物**（四个文件，按可独立编译的模块分两次提交）：

| 模块 | 文件 | 性质 | 内容 |
|---|---|---|---|
| pi-java-agent-core | `harness/RunFailure.java`（新，93 行） | 生产 | `handleRunFailure` 的逐条移植：`synthesize`（消息字面量）＋ `settle`（四事件） |
| pi-java-agent-core | `harness/PiLaneEngine.java`（改） | 生产 | `runPass` 拆成 `startPass`/`drivePass`，中间夹 `catch (Throwable)` → `RunFailure.settle`；**位置在重试 `while` 体内** |
| pi-java-agent-core | `harness/EngineFailureSettlementTest.java`（新，333 行） | 测试 | 夹具 1 的 **C 组** ＋ 夹具 3（D 组），3 个用例 |
| pi-java-coding-agent | `core/SessionRunner.java`（改） | 生产 | 第 1 步：两处 `catch (Exception)` → `catch (Throwable)` ＋ `finally` 幂等兜底；第 2 步：`tailAssistant` 读尾 |
| pi-java-coding-agent | `core/SessionFailurePathTest.java`（新，244 行） | 测试 | 夹具 1 的 **A 组** ＋ 夹具 2a ＋ 夹具 2b，3 个用例 |

**与设计稿（`38f991c` 的 §8.36.5/§8.36.6）的六处偏差 —— 全部以 pi 原文为准改设计，不改代码去迁就稿子**：

| # | 稿子说 | 实际 | 为什么以实际为准 |
|---|---|---|---|
| 1 | 宿主读尾加 `if (!sawTerminal.get())` 闸 | **闸删掉**，无条件读 | pi `print-mode.ts:139-155` 无此条件；且加闸会**真出错**（多 pass ⇒ 前面的 `StreamDone` 已置位 ⇒ 崩溃 run 记成 `(0,"tool_use")`）。§8.36.5 已改写 |
| 2 | 判定用 `lastAssistant`（倒扫跳过非消息条目后**继续找助手**） | 改 `tailAssistant`（遇到第一条消息条目就定案，**不往回找**） | pi 是 `state.messages[last]?.role === "assistant"`。§8.36.5 已改写 |
| 3 | 夹具 2b 的监听器抛在 **`MessageUpdate`** | 抛在 **`AgentEnd`** | `broadcastStream`/`SessionEventHub.emit` 只隔离 `RuntimeException`，但 `MessageUpdate` 上抛的 `Error` 会被**引擎**吞掉（catch 体只把 `AgentEnd` 送到会话面）⇒ 造不出「逃出 `harness.prompt`」。§8.36.6 已改写 |
| 4 | 夹具 2b 的 B5 = 「没有 `AgentEnd`」 | = 「没有**载荷为空**的 `AgentEnd`」 | 注入点在 `AgentEnd` 上 ⇒ `passEvents` 那条**先发出去才抛**，流里必然有 `AgentEnd` |
| 5 | 夹具 3 的批次 = 只有一个抛错工具 | 批次 = **`[echo, boom]`** | 单元素批次**不可达**：摘尾后副本尾停在 `tool_use` 助手消息上，`Agent.continue()` 拒（`agent.ts:372`）。**pi 同形** ⇒ 复刻 pi 的可达形状。§8.36.6 已改写 |
| 6 | P2 ⇒ 三条红（A1/B1/B3）；「`finally` 是所有路的公共出口」 | P2 ⇒ **只有 B1 一条红** | `finally` 只在「外层 catch 体自身再抛」那条路才是出口（2a）；A1 走正常收尾、B3 走内层 catch 体尾部的 `complete`。§8.36.6 的探针表已按实测改写，并撤回「P2 是唯一允许多红的探针」这条豁免 |

**变异探针**：五条全部执行，结果见 §8.36.6 的探针表（P1/P3/P5 恰一条红；P2 恰一条红但**与预期集合不同**；
P4 ⇒ C 组两条 ＋ 宿主 A3 一条）。**每条都实测过、恢复后 `diff` 逐字节为空**。

**覆盖面边界（划界声明，防止后人拿错证据）**：

- **第 1 步**改的是会话层事件与 future ⇒ 按 §8.32.5 第 1 条，L5 剧本（帧级、直接驱动 `PiLoop`）
  **结构上覆盖不到** ⇒ 只有夹具 2a/2b 能守。
- **第 2 步**落在引擎内、合成消息**走 `sink`** ⇒ 帧上**原则上可见**（属于 L5 的观测面），但 L5 的剧本
  用不抛的桩 Stream 与 faux 工具 ⇒ **造不出引擎内抛出**，等价于覆盖不到。
  ⇒ **L5 的 14 个剧本全绿不能作为第 2 步的证据**；第 2 步的证据只有夹具 1（C 组）与夹具 3。
- 同理，`mvn clean verify` 全绿只说明「没打断既有面」，不构成对本包的**正面**证据 —— 正面证据是
  夹具 + 五条探针。

**全量验证（2026-09-19）**：

- `mvn -o clean verify` ⇒ **BUILD SUCCESS**，14 个模块全 SUCCESS（含 checkstyle）。
- 测试计数：ai 440、**agent-core 468**、session-backend-sqlite 35、**coding-agent 225**、
  telemetry 31、tui 14、protocol 2、client 37，零 Failures/Errors。
- **L5 的一致性剧本 `conformance.ConformanceTest` 14/14 绿** —— 但见上面的划界声明。
- 本包新增：`EngineFailureSettlementTest` **3/3**、`SessionFailurePathTest` **3/3**。
- checkstyle 每模块报 **0 violation**；`checkstyle-result.xml` 里的 warning 全部落在
  **本包未触碰**的既有文件上（`AgentHarness` 502 行、`PiLaneSink` 510 行、`AgentSession` 988 行、
  `RpcDispatcher` 601 行，及若干既有未用 import）—— 本包新增/改动的四个文件**零 warning**。

**顺带记录一条教训（形态 (7)）**：本包的夹具是**先写探针、后写夹具**（§8.36.6 的稿子先钉死「谁红」），
结果六处偏差里有**四处**是「稿子凭空想象注入形状」错掉的（#3/#5/#6，以及 #1/#2 的判定细节）——
**注入点必须在写下它的那一刻就跑一次**。稿子能钉住的只有「要钉的命题」，钉不住「用哪根钉子」。

---

## 9. 与既有文档的关系

| 文档 | 关系 |
|---|---|
| `docs/27` | 对齐规则与基线，本文的前提 |
| `docs/28` | 驱动层换成 `PiLoop` 的决策；本文是它的续篇（宿主层） |
| `docs/29` | L5 差分报告，本文 §7 验收第 1 条的依据 |
| `docs/30` | 折叠链退休；`records` 降级为旁路审计由它确立 |
| `docs/03` | 类级设计；§2.1-§2.3 已按本文的结论重写（§8.13），§2 以本文为准、本文以 pi 源码为准 |
| `docs/32` | **未结项台账** —— 本文各处 §8.x 登记表的合并索引（带行号引用与「谁挡着」），查「还有什么没做完」从它进 |
