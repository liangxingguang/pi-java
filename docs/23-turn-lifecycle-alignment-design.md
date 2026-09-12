# 23 — Run / Turn / Tool 生命周期与事件对齐（重写版）

> 对齐基准：pi `packages/agent/src/agent-loop.ts`、`packages/agent/src/types.ts`、
> `packages/coding-agent/src/core/agent-session.ts`（pi HEAD `936aff0`）。
> 关联：`docs/19`（行为条款映射表 §10）、`docs/21`（record 日志折叠）、`docs/22`（deferred 写入）。
> 代码行号基准：`docs/22` D7 拆分之后（`ActionExecutor` 497 行，`AssistantStreamExecutor` 210 行）。
>
> **本文是重写版。** 旧版（`git log` 中的 538–852 行版本）范围定得过小：把「工具结果实时可见」
> 「钩子时序」「工具结果载荷」都划成了有意偏差，且含一处**不实的 pi 声明**（见 §1 末）。
> 评审记录保留在 `docs/23b-turn-lifecycle-design-review.md`（历史事实，不复改）。

**目标：功能等价。** 事件集合、时序、载荷与 pi 一致；实现方式（驱动模型、通道、序列化）可不同，
凡有差异处本文显式标注。

---

## 0. 差异一览

| # | pi 行为 | pi-java 现状 | 落点 |
|---|---|---|---|
| 1 | 工具一执行完即发工具结果消息（`message_start`+`message_end`） | **run 期间零事件**：消费者要等 `agent_end`；`EntryAppended` 只在 run 结束后发，且 Web 侧未翻译 | §6.3 |
| 2 | `turn_end → prepareNextTurn → shouldStopAfterTurn`，钩子拿到本轮 `toolResults` | 钩子在 `TryFinishRun` 内、**工具之前**触发，`toolResults` 恒为 `List.of()` | §5 |
| 3 | `turn_start`/`turn_end` 事件 | 全仓 **0 处发射**；Web 的 `turn_end` 是 `AgentSettled` 兜的假替身 | §4 |
| 4 | abort 前也发 `turn_start`，abort 后发 `turn_end` | abort 护栏直接转 CHECKPOINT，**零 turn 事件** | §4.3 |
| 5 | 工具结果的 `details`/`usage`/`addedToolNames` 端到端可见（RPC 原样序列化） | 在 `ToolOutcome` 构造处（`ToolExecutionPipeline:103-104`）**一次性丢弃** | §8 |
| 6 | `tool_execution_*` 由**执行阶段**发出，带 `toolName`/`args`/`result` | 译自 **LLM 流阶段**的 `StreamEvent.ToolCall*`，只有 `type` 字段 | §6 |
| 7 | `agent_start` 每 run 一次；`agent_settled` 是独立事件 | `agent_start` 每轮发一次；`agent_settled` 被译成 `turn_end` | §7.2 |
| 8 | 前端用 `AgentTool[]` 渲染工具卡片 | 协议只下发 `tools: string[]`，组件拿到硬编码 `[]` | §9 |

---

## 1. pi 语义约束（实施契约）

行号均为 `agent-loop.ts`，除注明外。

| # | 约束 |
|---|---|
| C1 | `agent_start` 每 run 一次（`:109`；续跑 `:138`） |
| C2 | `turn_start` 每轮一次，**无条件**（首轮 `:110`、第 2 轮起 `:176`）——信号已 abort 也照发（`:109-110` 前无任何 check） |
| C3 | 首轮 `turn_start` 早于用户消息的 `message_start`/`message_end`（`:110` vs `:111-114`） |
| C4 | `turn_end` 在**工具执行之后**，带 `message` + `toolResults`（`:224`）；`stopReason` 为 `error`/`aborted` 时走 `:196-200`，带 `toolResults: []` 且**跳过两个钩子**；`length` 不跳过（先失败化工具，再走正常收尾） |
| C5 | `turn_end(N)` 先于 `turn_start(N+1)`（`:224` vs `:176`）；session 层 `_turnIndex` 在 `turn_end` **之后**自增（`agent-session.ts:748`） |
| C6 | 钩子顺序：`turn_end` → `prepareNextTurn`（`:232`）→ `shouldStopAfterTurn`（`:248`）；两者入参都是 `{message, toolResults, context, newMessages}`（`types.ts:126-147`，`PrepareNextTurnContext` 是 `ShouldStopAfterTurnContext` 的纯别名） |
| C7 | 工具结果**作为普通消息**实时发出：`message_start` + `message_end`（`:793-796`；三处调用 `:402`/`:474`/`:546`）。并行批次里 `tool_execution_end` 按**完成序**、结果消息按 **assistant 源序** |
| C8 | `tool_execution_start` 在**参数校验与钩子之前**发射（`:445-450` vs `:452`；并行 `:499-505` vs `:507`），携带**模型原始** `args` ⇒ **被拒调用也有 `start`** |
| C9 | 被拒调用随后 `end`（`isError: true`，content = reason 或 `Tool execution was blocked`；`:514` + `:636-646`） |
| C10 | `tool_execution_end.result` 是完整 `AgentToolResult{content, details, usage?, addedToolNames?, terminate?}`（`types.ts:361-375`）；`details` 在扩展事件、消息、`agent_end`、RPC JSON 五条路径可见 |
| C11 | `tool_execution_update` 的 `partialResult` 也是完整 `AgentToolResult`；**不节流**（1:1 直发）；工具 promise settle 后的回调被丢弃（`:684`/`:698`/`:709`）。事件同时带 `args`（`types.ts:442`） |
| C12 | session 层事件带 `turnIndex`（`extensions/types.ts:730`/`:737`）；`agent_settled` 是**独立的 session 事件**，与 `turn_end` 无关（`agent-session.ts:148`） |

> **纠正**：pi **没有** `tool_result_message` 事件类型。`emitToolResultMessage` 只是发
> `message_start` + `message_end` 的辅助函数（`:793-796`）。约束是 C7，不是「第三个事件族」。
> 补充事实：pi 的 `harness/peekAction`/`executeAction` 是抛 `HarnessNotImplemented` 的桩
> （`harness/agent-harness.ts:413-418`），**pi 不存在 action 校验语义**。

---

## 2. 事件模型

### 2.1 agent-core（跨层，不引用 `AgentSessionEvent`）

```java
public sealed interface HarnessLifecycleEvent {

    /** 一个 run 开始（C1）。由 run/runContinue 发出，先于首轮 turn_start。 */
    record AgentStarted(String lane, String runId) implements HarnessLifecycleEvent {}

    record TurnStarted(String lane, String runId, int turnIndex) implements HarnessLifecycleEvent {}

    record TurnEnded(String lane, String runId, int turnIndex,
                     AssistantMessage message, List<Message> toolResults)
            implements HarnessLifecycleEvent {}

    /** 工具开始执行（C8）：args 为模型原始参数。 */
    record ToolStarted(String lane, String runId, String toolCallId, String toolName,
                       Map<String, Object> arguments) implements HarnessLifecycleEvent {}

    /** 执行中（C11）：args 同 start；partial 为部分结果。 */
    record ToolUpdated(String lane, String runId, String toolCallId, String toolName,
                       Map<String, Object> arguments, ToolResultPayload partial)
            implements HarnessLifecycleEvent {}

    /** 执行结束（C10）：result 为完整结果；isError 是 pi 的兄弟字段（不在 result 内）。 */
    record ToolEnded(String lane, String runId, String toolCallId, String toolName,
                     ToolResultPayload result, boolean isError) implements HarnessLifecycleEvent {}

    /** 工具结果消息（C7）：wire 上展开为 message_start + message_end 两帧。 */
    record ToolResultAdded(String lane, String runId, Message message) implements HarnessLifecycleEvent {}
}
```

```java
/** 工具结果载荷（对齐 pi `AgentToolResult`：content/details/usage?/addedToolNames?/terminate?，
 *  刻意不含 isError——pi 把它作为 `tool_execution_end` 的兄弟字段）。
 *  details 是各工具私有类型，pi 侧类型为 any，跨层无法定型 ⇒ 用 Object 承载，
 *  序列化契约：Jackson 原样输出（勿做类型注册）。
 *  usage 用 com.pijava.ai.Usage；来源是 `ToolResult.usage()`（`UsageInfo`，只有 input/output），
 *  转换时其余分量留空。 */
public record ToolResultPayload(
    List<ContentBlock> content,
    Object details,
    Usage usage,
    List<String> addedToolNames,
    boolean terminate) {}
```

### 2.2 coding-agent（`AgentSessionEvent` 新增 7 个变体）

```java
record TurnStart(int turnIndex) implements AgentSessionEvent {}
record TurnEnd(int turnIndex, AssistantMessage message, List<Message> toolResults) implements AgentSessionEvent {}

record ToolExecutionStart(String toolCallId, String toolName, Map<String, Object> arguments)
        implements AgentSessionEvent {}
record ToolExecutionUpdate(String toolCallId, String toolName, Map<String, Object> arguments,
                           ToolResultPayload partial) implements AgentSessionEvent {}
record ToolExecutionEnd(String toolCallId, String toolName, ToolResultPayload result, boolean isError)
        implements AgentSessionEvent {}

record ToolResultMessageReceived(Message message) implements AgentSessionEvent {}   // → message_start/end
record AgentStart() implements AgentSessionEvent {}                                  // C1
```

> `agent_settled` 已有（`AgentSettled`），保持原样，**不再译成 `turn_end`**（C12）。

---

## 3. 跨层通道

- 依赖方向 `agent-core ← coding-agent` ⇒ agent-core 不引用 `AgentSessionEvent`。
- `ExecutionContext` 增加组件 `lifecycleListener`（与 `streamListener` 同形：`Supplier` 或方法引用）；
  `AgentHarness` 增加运行时注册 `AutoCloseable onLifecycleEvent(Consumer<HarnessLifecycleEvent>)`，
  与 `onStreamEvent`（`AgentHarness:168`）同构、同用 `CopyOnWriteArrayList` 扇出。
- **不加** `HarnessConfig.lifecycleListener`：`HarnessConfig.streamListener`（`:67`）是死字段
  （全仓无人读 `config.streamListener()`），别复制该错误。
- **线程契约**：`ToolUpdated` 由工具虚拟线程的 `onUpdate` 回调发射，与驱动线程的 `ToolEnded` 并发
  ⇒ 监听器必须线程安全（与 `broadcastStreamEvent` 一致）。
- **旁路语义**：`HarnessLifecycleEvent` 是**只读实时旁路，不参与任何状态派生**；审计与恢复的唯一
  真相源仍是 record 日志（`docs/21`）。丢事件不影响折叠结果。
  实时事件用模型原始 `arguments`，钩子改写后的 `effectiveArgs` 只留在 record 层。
- **事件丢弃语义（既有，不修）**：session 侧的 `SessionEventHub.emit` 在**零订阅者时静默丢弃**
  （无缓冲、无异常）。新增的 turn/tool 事件继承该语义 ⇒ **迟到订阅者收不到此前的 `turn_start`**。
  这是既有行为（`MessageUpdate` 已如此），要「补历史」应走 snapshot/`watch()` 通道，不是事件缓冲。

---

## 4. turn 时序与发射点

### 4.1 状态

```java
int turnIndex;                  // 当前轮序号（0 起；run 重置；恢复时取自 fold 的 stepIndex）
boolean turnOpen;               // 本轮已开始、尚未收尾（每轮恰好一对事件由它保证）
boolean callsScheduled;         // 本轮的 tool_use 调用已抽取入队 —— 唯一能区分「第一遍/第二遍」的判据
boolean turnHadTools;           // 本轮存在需要模型继续的调用（对齐 pi 的 hasMoreToolCalls）
boolean turnTerminated;         // 本轮某工具要求 terminate（pi：hasMoreToolCalls = !terminate）
final List<Message> turnToolResults = new ArrayList<>();   // 本轮工具结果（收尾时读取并清空）
```

`turnIndex` **不复用** `lane.stepIndex`（那是 LLM **尝试**计数器：自增在 `AssistantStreamExecutor:83`，
被 `RunSpanFactory:51` 当 `attemptCount` 上报）。六个字段一起在 `run:73` / **`reset:140`** /
`runContinue:169` 三处清零；`callsScheduled`/`turnHadTools` 另在**每轮开始**清零（§4.2）；
`turnTerminated` 在收尾消费后清零。
（命名提示：`ExecutionContext.incrementTurn()` → `TokenCounter.turnCount` 是个 **run** 计数器，名字误导，
与 `turnIndex` 无关，建议顺带改名 `runCount`。）

### 4.2 轮开始

| 时机 | 位置 | 动作 |
|---|---|---|
| 首轮 | `ActionExecutor.run` / `runContinue`，写 user entry **之前**（满足 C3） | 发 `AgentStarted`（C1）→ 发 `TurnStarted(turnIndex)`，`turnOpen=true`，`callsScheduled=false`，`turnHadTools=false` |
| 第 2 轮起 | `AssistantStreamExecutor.execute` 开头，abort 检查（`:53`）之后、`applyPendingTurnUpdate`/`checkAutoCompact`（`:61-62`）**之前** | `turnOpen==false` ⇒ 发 `TurnStarted(turnIndex)`，同上置位 |

**轮开始必须清 `callsScheduled`/`turnHadTools`** —— 否则第二轮会继承上一轮的「已调度」而永不调度工具。

自动压缩因此**计入本轮**（在 turn_start 之后、LLM 请求之前发生）——见 §13。

### 4.3 轮收尾：**唯一收尾点 = `executeTryFinishRun`**

关键约束：**工具是一调用一个 Action**（`computeNextAction` 的 `lane.pendingToolCalls.remove(0)`，
`ActionExecutor:247`；`BashTool`/`EditTool`/`WriteTool` 返回 `ExecutionMode.Sequential`，会把整批降级为串行）
⇒ 收尾动作**不能**放在 `executeTool`/`executeToolBatch`，否则一轮会发 N 次 `turn_end` + N 次钩子。

改法：**工具全部执行完后让驱动回到 CHECKPOINT**，由 `TryFinishRun` 的第二次产出执行收尾。

```java
// executeTool / executeToolBatch 尾部：替换现有的 `lane.phase = ASSISTANT; return peekAction(...)`
if (outcome.terminate()) lane.turnTerminated = true;        // 批次用 allTerminate
lane.phase = lane.pendingToolCalls.isEmpty()
        ? RunPhase.CHECKPOINT      // ← 全部执行完：交回收尾点
        : RunPhase.ASSISTANT;      // ← 还有待执行：继续下一个 ExecuteTool
return peekAction(laneName);
// 并且：terminate 分支不再直接 `return new Action.FinishOperation(...)`——它只置 turnTerminated。
// 理由（对齐 pi）：terminate 判定在 :216，而 turn_end(:224) 与两个钩子(:232/:248) 照常执行。
```

```java
private Action executeTryFinishRun(String laneName, LaneState lane, Action.TryFinishRun tfr) {
    String status = tfr.outcome();
    var calls = HarnessUtils.extractToolCalls(lane.partial);   // 本轮助手消息里的调用（可能为空）

    // ① length：失败化工具调用（pi :207-213 —— 只在**有调用**时才失败化），结果计入本轮（§6.4）
    if ("length".equals(status) && !calls.isEmpty()) {
        HarnessUtils.failTruncatedToolCalls(laneName, lane, ctx.lifecycleListener());
        lane.turnHadTools = true;   // pi: failToolCallsFromTruncatedMessage 返回 terminate:false ⇒ 继续下一轮
    }
    // ② 本轮首次遇到 tool_use：**只调度工具，不是收尾**
    //    第二遍进入本方法时 callsScheduled 已为 true ⇒ 跳过（这是唯一能区分两遍的判据）
    else if ("tool_use".equals(status) && !lane.callsScheduled && !calls.isEmpty()) {
        lane.pendingToolCalls.addAll(calls);
        lane.callsScheduled = true;
        lane.turnHadTools = true;
        lane.phase = RunPhase.ASSISTANT;
        return peekAction(laneName);
    }

    // ③' deferred：**挂起** —— 不发 turn_end、不跑钩子、turnOpen 保持 true（见 docs/26 Part B）
    //     依据：pi 的 **spec**（非 agent-loop.ts）里 Park 在 turn_end 之前 unwind
    //     （harness-v2.md:3293 的 routeDeferredClassification → Park；:2863 park 语义）
    if ("deferred".equals(status)) {
        lane.phase = RunPhase.Suspended;    // pendingWrites 已由 peekAction 在产出本动作前抽干
        return null;                        // 结束本次 drive；operation 保持打开
    }

    // ── ③ 收尾点（唯一）：先取快照，再清状态 —— turn_end 与两个钩子必须读同一份 ──
    var results = List.copyOf(lane.turnToolResults);
    var message = lane.partial;
    boolean turnWasOpen = lane.turnOpen;
    if (turnWasOpen) {
        ctx.lifecycleListener().accept(new HarnessLifecycleEvent.TurnEnded(
            laneName, lane.runId, lane.turnIndex, message, results));
        lane.turnOpen = false;
        lane.turnIndex++;
        lane.turnToolResults.clear();
    }
    boolean stop = false;
    if (turnWasOpen && !"error".equals(status) && !"aborted".equals(status)) {   // C4：error/aborted 跳过钩子
        var newMessages = new ArrayList<Message>();       // 本轮产生的消息：assistant + 工具结果
        if (message != null) newMessages.add(message);
        newMessages.addAll(results);
        var upd = ctx.hookSystem().firePrepareNextTurn(laneName, new PrepareNextTurnContext(
            laneName, lane.runId, message, results, newMessages));
        if (upd != null) lane.pendingTurnUpdate = upd;
        stop = ctx.hookSystem().fireShouldStopAfterTurn(laneName, new ShouldStopAfterTurnContext(
            laneName, lane.runId, message, results, newMessages));
    }

    // ④ 去向（**无条件执行**；否则恢复后 turnOpen==false 的 lane 会在此打转）
    if ("error".equals(status))   return new Action.FinishOperation("failed");   // 保留既有映射（:433-434）
    if ("aborted".equals(status)) return new Action.FinishOperation("aborted");
    if (stop || lane.turnTerminated) {
        lane.turnTerminated = false;
        return new Action.FinishOperation(status, true);
    }
    if (lane.turnHadTools) {          // pi: hasMoreToolCalls == true ⇒ 内层 while 继续
        lane.phase = RunPhase.ASSISTANT;
        return peekAction(laneName);  // 下一轮 StreamAssistant（turn_start 处会清两个标志）
    }
    return new Action.FinishOperation(status);   // 本轮无调用 ⇒ **run 结束**（pi: hasMoreToolCalls == false）
}
```

要点：

- **④ 必须有「无调用 ⇒ 结束 run」这一支**（最后一行）：pi 的 `hasMoreToolCalls` 为假时内层 while 退出
  （`:206`→`:174`）。少了它，一次普通问答会无限循环（每次 `completed` 都回到 ASSISTANT 再发一次 LLM 请求），
  `OperationFinished` 永不写。**这是本机制最容易漏的一支。**
- **② 的判据是 `!callsScheduled && !calls.isEmpty()`，不是「`pendingToolCalls` 是否为空」**：`pendingToolCalls`
  在每次 `StreamAssistant` 之前就被抽干（`:239-248`），到 `TryFinishRun` 时**必然为空**；用它当判据会让
  工具永不被调度，或（若反向写成 `isEmpty()`）在第二遍重复抽取同一批调用而死循环。
- **第二遍的 `status` 仍是 `tool_use`**（`deriveNewestOwn` 只认助手条目，`HarnessUtils:68-78`），
  所以区分两遍只能靠 `callsScheduled`。
- **③ 先取 `results`/`message` 快照再 `clear()`**：turn_end 与两个钩子必须读同一份，否则钩子又拿到空列表
  （正是 §5 要修的缺陷）。
- **`status == "tool_use"` 但无调用**：② 跳过 ⇒ 走 ③ ⇒ ④ 落到 `FinishOperation("tool_use")`，
  `outcome()` 的 `default` 映射为 COMPLETED——与现状（先把 status 改成 `completed`）等价。
- **收尾时 `pendingToolCalls` 必为空**（② 之后的路径才到 ③）⇒ `shouldStopAfterTurn` 不会静默丢掉未执行的
  调用（`LoopInvariants` 不变量 1 保持）；`FinishOperation` 仍只从 CHECKPOINT 产出 ⇒ **不变量 7 无需修改**
  （`Suspended` phase 的产出集是 `docs/26` Part B 的范围）。
- **`status == "deferred"` 走 ③'**（对齐 pi 的 `Park` 在 `turn_end` 之前 unwind）：不发 `turn_end`、
  不跑钩子、`turnOpen` 保持 `true`，置 `Suspended` 后 `return null`。赎回后 ready 继续**同一轮**，那时才收尾。
  这也是「每轮恰好一对」的唯一跨进程例外：挂起跨越进程时订阅者可能只看到孤立的 `turn_end`
  （继承零订阅者丢弃语义，§3）。详见 `docs/26` Part B。

### 4.4 abort

- **轮内 abort**：`AssistantStreamExecutor:53`（进入前）把 `partial` 置成 `stopReason="aborted"`
  ⇒ 驱动到 CHECKPOINT ⇒ 收尾点 ③ 发 `TurnEnded(…, toolResults)`、**跳过钩子**（C4）。
  **`:102-105`（流中途 break）目前并不设置 stopReason**（只 `iter.close(); break;`），`StreamPartialBuilder`
  的 `stopReason` 只在 provider 正常结束时才被写上 ⇒ `determineOutcome` 落到 `"completed"`
  （`HarnessUtils:126`）。**这是必须补的一处**：break 之后补
  `lane.partial = lane.partial.withStopReason("aborted")`（与 `:55`/`:261` 同款），否则最常见的
  Ctrl-C（流中途中止）会**跑两个钩子并把操作记成 COMPLETED** —— 与 ④ 刚修掉的 `error` 误映射同一类。
  同族：若 provider 把中止抛成异常，会落到 `:125-127` 的 catch ⇒ `"error"` ⇒ FAILED；该 catch 也需在
  `abortSignal.isAborted()` 时归一到 `"aborted"`。

  **状态：已实证并修复**（`MidStreamAbortTest.abortDuringStreamingFinalizesAsAborted`；修前红：
  `expected: ABORTED but was: COMPLETED`）。两处改动都在 `AssistantStreamExecutor`：break 分支补写
  `stopReason="aborted"`、catch 分支按 `abortSignal` 归一。附带修掉第二个症状：中断的半成品
  此前因 `stopReason == null` **会被投影进后续请求的上下文**（`NON_PROJECTED_STOP_REASONS` 只认
  `deferred/error/aborted`），现在是 `aborted` ⇒ 投影为零条。
- **轮前 abort**：`computeNextAction` 的护栏（`:259-265`）转 CHECKPOINT ⇒ `TryFinishRun` ⇒ **同样经过收尾点 ③**
  （不是靠兜底）。
- **兜底**（`executeFinishOperation`，`phase = IDLE`(`:357`) 之前）：只覆盖「轮已打开、驱动未经收尾点就终止」
  的路径（外部直接 `finishOperation`、`close()` 等）。完整形式：

```java
if (lane.turnOpen && !(lane.phase instanceof RunPhase.Suspended)) {
    ctx.lifecycleListener().accept(new HarnessLifecycleEvent.TurnEnded(
        laneName, lane.runId, lane.turnIndex, lane.partial, List.copyOf(lane.turnToolResults)));
    lane.turnOpen = false;        // ← 必须清！否则此后所有第 2 轮起的 turn_start 都会被 §4.2 的守卫吃掉
    lane.turnIndex++;
    lane.turnToolResults.clear();
}
```

  `Suspended` 排除是因为挂起轮的 `turnOpen` 刻意保持 `true`（§4.3 ③'，`docs/26` Part B）；
  **不清 `turnOpen` 会永久静默掉该 lane 之后每一轮的 `turn_start`**（§4.2 第二处发射点的守卫是
  `turnOpen==false`）。

### 4.5 恢复

`AgentHarness.restoreFromRecords`（`:375`）把有未完成 operation 的 lane 恢复到 CHECKPOINT。恢复后
**`turnOpen = false`、`turnIndex = folded.stepIndex()`**（该 operation 内已发生的 LLM 轮数）
⇒ **不补发上一轮的 `turn_end`**：崩溃前那一轮的 `turn_start` 已发出，补发只会造出孤立事件；恢复视作
新的驱动会话起点。见 §13。

**六个标志必须在恢复处一并清零**（`:352-394` 现在只设 `stepIndex`/`partial`/`pendingWrites`，不清
`pendingToolCalls`）：同一个 `LaneState` 被第二次恢复复用时，残留的 `callsScheduled=true`/`turnHadTools=true`
会让首次 `TryFinishRun` 跳过 ②、并在 ④ 走 `turnHadTools` 分支**多发一次 LLM 请求**。
（首次恢复靠"新对象默认 false"侥幸正确——需显式清零。）

---

## 5. 钩子时序（C6）

现状：两个钩子在 `executeTryFinishRun` 内、**工具执行之前**触发，`toolResults` 恒传 `List.of()`
（`:393`/`:423`）——字段永远为空。

改法即 §4.3 的收尾点 ③：**工具全部执行完 → `turn_end` → `prepareNextTurn` → `shouldStopAfterTurn`**，
两个钩子的入参带真实 `toolResults`。

| 分支 | 收尾点行为 |
|---|---|
| `completed` / `length` | `turn_end` → `prepareNextTurn` → `shouldStopAfterTurn` |
| `tool_use`（工具已全部执行完） | 同上 |
| `error` / `aborted` | `turn_end` → **跳过两个钩子**（C4） |

- context 记录补实参：`toolResults` = 本轮真实结果；并补 `List<Message> newMessages`（本轮产生的消息：
  assistant + toolResults），对齐 C6 的入参形状。
- `prepareNextTurn` 的返回值仍写入 `lane.pendingTurnUpdate`，由下一轮 `AssistantStreamExecutor` 消费。
- `length` 的工具失败化必须在**收尾之前**完成，其结果计入 `turnToolResults`（pi `:381-406` 在该路径
  既有结果也有事件），见 §6.4。

> **改动面**：Action 序列变化 ⇒ `AgentLoopL2Test` 的 golden-trace（`:130` 起）与若干 record 顺序断言需同步。
> `LoopInvariants` **不变量 1/7 的语义不变**（`FinishOperation` 仍只从 CHECKPOINT 产出，收尾时
> `pendingToolCalls` 必为空）—— 但**注意**：一旦引入 `RunPhase.Suspended`（`docs/26` Part B），
> `RunPhase` 是 sealed 接口（`RunPhase.java:9-17`），两处穷尽 switch 必须补 arm 才能编译：
> `ActionExecutor:214`（`computeNextAction`）与 `LoopInvariants:51`；
> `Suspended` 下的合法产出集见 `docs/26` §B.1。

---

## 6. 工具执行事件与结果消息

### 6.1 发射层

**发射层是 `ToolExecutionPipeline`（执行阶段），不是 `StreamEvent` 翻译层。**
旧实现把「模型吐出工具调用」当成「工具开始执行」，是本项要修的根因。

### 6.2 三个发射点

```java
// ① C8/C6a：Stage 1 循环内（:64-69）、beforeToolDecision 之前 —— 顺序发射（C9 的并行要求）
ctx.lifecycleListener().accept(new HarnessLifecycleEvent.ToolStarted(
    laneName, lane.runId, et.toolCallId(), et.toolName(), et.arguments()));

// ② C11：执行中，透传真实回调
//    签名改动：runRawSafely 补 laneName（声明 :185，调用点 :79 串行 / :154 并行）
//             其上游 runRawBatch（声明 :143，调用点 :74）同步补 laneName
private RawToolResult runRawSafely(String laneName, LaneState lane, BeforeToolDecision decision) {
    var result = ctx.toolExecutor().executeRaw(
        decision.call().toolName(), decision.call().toolCallId(),
        decision.args(), lane.abortSignal,
        partial -> ctx.lifecycleListener().accept(new HarnessLifecycleEvent.ToolUpdated(
            laneName, lane.runId, decision.call().toolCallId(),
            decision.call().toolName(), decision.call().arguments(), toPayload(partial))));

// ③ C10：Stage 3，after_tool 钩子之后（outcome 已定型，含被拒调用）
ctx.lifecycleListener().accept(new HarnessLifecycleEvent.ToolEnded(
    laneName, lane.runId, et.toolCallId(), et.toolName(), toPayload(result), raw.isError()));
```

配套：

| 项 | 要求 |
|---|---|
| `executeRaw` 重载 | `ToolExecutor.executeRaw:106` 目前给 `onUpdate` 传 `null` ⇒ 新增带 `ToolUpdateCallback<?>` 的重载并透传 |
| 被拒调用 | **也发 `start`**（C8），随后发 `end`（content = 钩子给的 **reason**，缺省 `Tool execution was blocked`，`isError=true`，C9）。钩子的 reason **已经存在**：`BeforeToolResult.deny(reason)`/`denyAndTerminate(reason)`（`BeforeToolResult:49-51` 的 `deny`、`:53-56` 的 `denyAndTerminate`，现存在 `arguments={"reason":…}`），只是 `BeforeToolDecision.deny` 传了 `Map.of()` 把它丢了（`ToolExecutionPipeline:283-285`）⇒ 透传即可，无需新通道 |
| 节流 | **不做**（C11：pi 1:1 直发）。补「工具 `execute` 返回后回调丢弃」护栏（pi 的 `acceptingUpdates` 口径） |
| 截断路径（§6.4） | `HarnessUtils.failTruncatedToolCalls` 绕过 pipeline ⇒ 需补发 `start` + `end` + 结果消息（pi `:393-406` 三样都发），并把失败结果计入本轮。签名扩为 `(String laneName, LaneState lane, Consumer<HarnessLifecycleEvent> lifecycle)` |
| 并行批次 | `start` 顺序发射；`update`/`end` 由各虚拟线程并发发射，消费方按 `toolCallId` 关联（顺序差异见 §13） |

### 6.3 工具结果消息（C7，本项最大缺口）

`ToolExecutionPipeline.appendEntry`（`:116`/`:240`）写完 entry 后，发
`HarnessLifecycleEvent.ToolResultAdded(lane, runId, toolEntry.message())`；截断路径同样发。

- **wire 上展开成两帧** `message_start` + `message_end`（与 pi 一致）；
- **内部只有一个事件**：pi 的 `message_start`/`message_end` 对是「消息进出上下文」的通用动作，pi-java
  没有对应的消息生命周期抽象，新增一层抽象只为发两帧不划算 ⇒ **差异点：内部事件粒度与 pi 不同，
  wire 帧序列与 pi 相同**。

### 6.4 本轮工具结果的收集（`turnToolResults`）

**这是 C4/C6 能否成立的关键**：`turn_end.toolResults` 与两个钩子的入参都取自它，必须明确累加点。

```java
// executeTool / executeToolBatch：把 executeStages 的返回值与入参 calls 按位置 zip
// （executeStages 返回顺序 == calls 顺序，含被拒调用：ToolExecutionPipeline:85-108）
var outcomes = toolPipeline.executeStages(laneName, lane, calls);
for (int i = 0; i < calls.size(); i++) {
    var et = calls.get(i);
    var outcome = outcomes.get(i);
    lane.turnToolResults.add(new Message.ToolResultMessage(
        et.toolCallId(), et.toolName(), outcome.blocks(), outcome.isError()));
}
```

> 注意两点：①**不要**写成「`toolPipeline.appendEntry(...)` 之后累加」——`appendEntry` 是
> `ToolExecutionPipeline` 的**私有**方法（声明 `:116`，调用点 `:240`），`ActionExecutor` 调不到；
> ②`Message.ToolResultMessage` 的**组件名是 `toolUseId`**（不是 `toolCallId`，wire 键才叫
> `toolCallId`，见 `WebWireJson:34`），局部以位置传参无妨，但直接写 `record` 解构会踩坑。

截断路径（`length`，见 §4.3 ①）同样要收集：`failTruncatedToolCalls` 构造的每条
`ToolResultMessage` 既入 transcript，也入 `turnToolResults`——pi 在该路径 `turn_end.toolResults`
是**有内容**的（`:405` 返回 messages，`:216` 推进 toolResults）。

---

## 7. 消费方

### 7.1 wire 帧

| 帧 | payload |
|---|---|
| `agent_start` | `{type}` —— 每 run 一次（C1） |
| `turn_start` | `{type, turnIndex}` |
| `turn_end` | `{type, turnIndex, message, toolResults}` |
| `tool_execution_start` | `{type, toolCallId, toolName, args}` |
| `tool_execution_update` | `{type, toolCallId, toolName, args, partialResult:{content, details, usage?, addedToolNames?, terminate?}}` |
| `tool_execution_end` | `{type, toolCallId, toolName, result:{content, details, usage?, addedToolNames?, terminate?}, isError}` |
| `message_start` / `message_end`（工具结果） | `{type, message:{role:"toolResult", toolCallId, toolName, content, details, isError}}` |
| `message_start` / `message_end`（用户消息） | `{type, message:{role:"user", content}}` —— 由 `UserMessageReceived` 展开成两帧（对齐 pi `:111-114` 的 `message_start`+`message_end`） |
| `agent_settled` | `{type}`（不再占用 `turn_end`） |

**「一个内部事件 → 两帧」的规则**（工具结果 C7、用户消息 C3）统一按上表展开；帧序与 pi 相同：
`turn_start(0)` → `message_start/end(user)` → LLM 帧 → … （首轮 `turn_start` 在 `run()` 内发出，
用户消息帧在同一驱动循环内随后发出，故顺序自然成立）。

### 7.2 要删的三处旧翻译

```java
// ① AgentEventTranslator.java:73-78 —— 阶段错位
//    StreamEvent.ToolCallStart/Delta/End → tool_execution_start/update/end  ⇒ 删除
//    流阶段改发 message_update（对齐 pi :338-342）
case StreamEvent.ToolCallDelta d -> out.add(messageUpdate(d.partial()));

// ② AgentEventTranslator.java:67-70 —— agent_start 语义冒充
//    StreamEvent.Start → agent_start  ⇒ 删除（改由 AgentStart 事件提供）
//    仅保留 streaming = true 的内部状态维护

// ③ AgentEventTranslator.java:51-54 —— 假 turn_end 替身
//    AgentSettled → turn_end  ⇒ 删除，改发 agent_settled（C12）；保留 streaming = false
```

### 7.3 通路

**共 9 个改动点 = 7 个新 case + 1 个改（`UserMessageReceived`）+ 1 个重写（`AgentSettled`）**：

- Web：`AgentEventTranslator` 新增 7 个 case（`AgentStart`/`TurnStart`/`TurnEnd`/`ToolExecutionStart`/
  `ToolExecutionUpdate`/`ToolExecutionEnd`/`ToolResultMessageReceived`）；`UserMessageReceived`
  （现有 `:45-46`）由「一帧 `message_end`」改为「`message_start` + `message_end` 两帧」；
  `AgentSettled`（`:51-54`）由 `turn_end` 改为 `agent_settled`。payload 复用 `typeNode`/`WebWireJson.messageNode`。
- RPC：`JsonEventMapper` 新增同名 wire 映射（纯机械，`RpcDispatcher.emitEvent:312-321` 直通 `toWire`）。
  顺带补齐既有缺口：`UserMessageReceived` 目前落成 `unsupported_event`。
- 前端：`main.ts:298-314` 的 turn 分支**已存在**，payload 补齐后自动生效；新增的 `message_start`
  帧在 `main.ts:275` 是 `renderApp()` 空操作，无需改动。**工具卡片仍需 §9**（`main.ts:316-318` 的
  `tool_execution_*` 目前是忽略 payload 的空操作，渲染依赖 `.tools`）。

---

## 8. 工具结果载荷端到端（独立交付项）

**丢失点唯一**：`ToolExecutionPipeline:103-104` 构造 `ToolOutcome` 时只取
`content`/`isError`/`terminate`，`details`/`usage`/`addedToolNames` 在此丢弃；下游
`Message.ToolResultMessage`、JSONL/SQLite 编解码、Web/RPC、HTML 导出**全都没有这些字段**。
各工具确实在填充 `details`（`ReadTool.ReadDetails`、`BashTool`、`EditTool`）。

| # | 改动 |
|---|---|
| 1 | `ToolOutcome` 携带 `ToolResultPayload`（含 details/usage/addedToolNames） |
| 2 | `Message.ToolResultMessage` 增加 `details`/`usage`/`addedToolNames`（pi 为三个扁平可选字段，照此） |
| 3 | 构造点透传：`ToolExecutionPipeline:117-120`、`HarnessUtils:173`、`ToolExecutor:127`（`toolResultEntry` 有 3 个调用者 `:78`/`:88`/`:120`，**不是死代码**），外加 `MessageJsonCodec:43` 的解码构造 |
| 4 | JSONL 编解码（`SessionJson:62-66` / `MessageJsonCodec:43-48`）与 SQLite 的 JSON blob 同步，保证 resume 不丢 |
| 5 | `WebWireJson:33-37` 增加 `details`（pi-web-ui 的 `ToolResultMessage.details` 会读） |
| 6 | **编译影响（全量：5 处主代码 + ~14 处测试）**。Java record 元数变化会让**所有规范构造调用**失败，不只是解构：主代码 `HarnessUtils:173`、`ToolExecutionPipeline:119`、`MessageJsonCodec:43`、`ToolExecutor:127`；测试 `ToolBatchFoldTest:44`、`ConformanceSupport:39`、`ContextEntriesTest:28`、`MessageTest:94`、`AnthropicMessagesApiBuildParamsTest:92/118/142/145`、`OpenAICompletionsApiRequestTest:36`、`ChatApiConformanceSuite:141`、`MessageBubbleTest:188`、`AgentEventTranslatorTest:101`、`WebWireJsonTest:48/71`、`ResetContinueTest:123`、`MistralConversationsApi:208`（位置解构 `case Message.ToolResultMessage(var …)`，**必须**改）。`AnthropicMessagesApi:186/190/295`、`OpenAICompletionsApi:147`、`PiMessagesApi:189`、`ResponsesMessageConverter:115` 是 `instanceof`/参数类型用法，不受影响 |

`Entry`/`LaneRecord` **不改**：`Entry.Message` 包住 `Message`，details 随之落库；record 层按
`docs/21` 保持精简（`resultEntryId` 指向 entry 即可）。

---

## 9. 配套：前端工具 schema 下发（独立交付项）

前端 `@mariozechner/pi-web-ui` 的 `.tools` 要 `AgentTool[]`（`name`/`description`/`parameters`/`label`），
而现在协议只给 `tools: string[]`（`protocol.ts:114`，`main.ts:766`/`:773` 传空数组）。

**pi-java 已有现成 DTO**：`ToolDefinition(name, description, inputSchema, label, …)`
（`pi-java-ai/.../api/ToolDefinition.java`），且 `ToolRegistry.toToolDefinitions()` 已存在、
`AssistantStreamExecutor:73-78` 每轮都在算。因此只需：

1. `SerializedAgentState.tools` 由 `List<String>` 改为 schema 记录列表（web 侧定义，避免耦合 ai 模块 DTO）；
2. `WebDispatcher:207` 改为投影这 4 个字段；
3. `protocol.ts` 与 `main.ts` 三处改为传真实数组。

无需新端点（`stateSync` 已有该槽位）。另注：`toolResultsById` 早已接线（`main.ts:611→776`）。

---

## 10. 对齐检查表

| # | 约束 | 落点 | 状态 |
|---|---|---|---|
| C1 | `agent_start` 每 run 一次 | §2.1 `AgentStarted`（`run`/`runContinue` 发出）+ §7.2 删旧翻译 | ⬜ |
| C2 | `turn_start` 无条件（含 abort 前） | §4.1 / §4.3 | ⬜ |
| C3 | 首轮 `turn_start` 早于 user `message_end` | §4.1（`run`/`runContinue` 写 entry 之前发） | ⬜ |
| C4 | `turn_end` 在工具后，带 `message`+`toolResults`；error/aborted 跳过钩子 | §4.3 收尾点 ③ + §6.4（结果收集） | ⬜ |
| C5 | `turn_end(N)` 先于 `turn_start(N+1)`；`turn_end` 后自增 | §4.1 / §4.2 | ⬜ |
| C6 | 钩子顺序与入参（含 `toolResults`） | §5 | ⬜ |
| C7 | 工具结果实时作消息发出 | §6.3 | ⬜ |
| C8 | `tool_execution_start` 在校验/钩子之前，带原始 args | §6.2 ① | ⬜ |
| C9 | 被拒调用也有 start + end | §6.2 配套 | ⬜ |
| C10 | `tool_execution_end.result` 完整（含 details） | §6.2 ③ + §8 | ⬜ |
| C11 | `partialResult` 完整；不节流；settle 后丢弃 | §6.2 ② + 配套 | ⬜ |
| C12 | session 层带 `turnIndex`；`agent_settled` 独立 | §2.2 / §7.2 ③ | ⬜ |

---

## 11. 实施步骤

1. **通道**：`HarnessLifecycleEvent` + `ToolResultPayload`；`ExecutionContext` 组件；
   `AgentHarness.onLifecycleEvent`（不加 `HarnessConfig` 字段）。
2. **turn 状态**：`LaneState.turnIndex`/`turnOpen`/`turnTerminated`/`turnToolResults` + 三处清零；
   §4.2 的轮开始两处、§4.3 的收尾点、§4.4 的兜底。
3. **收尾点与钩子**（§4.3 / §5）：`executeTool`/`executeToolBatch` 尾部改为「按 `pendingToolCalls`
   是否为空决定回 CHECKPOINT 还是 ASSISTANT」+ 置 `turnTerminated`（不再直接 `FinishOperation`）；
   `executeTryFinishRun` 重写为 5 个分支（① length 失败化 / ② 首次调度工具 / ③' deferred 挂起 /
   ③ 收尾点 / ④ 去向含「无调用即结束 run」）；context 记录补 `toolResults`/`newMessages`；
   新增 `callsScheduled`/`turnHadTools` 两个标志。
   **这一批会改 Action 序列** ⇒ 同步更新 `AgentLoopL2Test` 的 golden-trace 与 record 顺序断言
   （`LoopInvariants` 的不变量 1/7 语义不变；引入 `RunPhase.Suspended` 后需给两处穷尽 switch 补 arm，
   见 §5 末尾注）。
4. **工具事件**（§6.2）：`executeRaw` 重载；`runRawSafely`/`runRawBatch` 补 `laneName`；
   `ToolExecutionPipeline` 三处发射 + 拒绝路径；`failTruncatedToolCalls` 扩签名补「事件 + 结果收集」。
5. **结果收集**（§6.4）：`executeTool`/`executeToolBatch` 按位置 zip 累加 `turnToolResults`。
6. **工具结果消息**（§6.3）：`ToolResultAdded` 发射 + session 桥接 + wire 展开两帧。
7. **coding-agent**：`AgentSessionEvent` 新增 7 变体；`AgentSession.assemble` 内、`AgentSession`
   构造**之后**注册 `harness.onLifecycleEvent(ev -> emitSessionEvent(switch (ev) { … }))`
   （switch 的对象是 `HarnessLifecycleEvent`；`assemble` 里没有 switch，别照旧文写成「assemble 的 switch」；
   之所以必须在构造之后注册：`assemble` 在 `:257` 先建 `HarnessConfig`、`:273` 才建 `AgentSession`，
   桥接闭包需要后者实例）。
8. **消费方**：Web 9 个改动点（§7.3）+ 删三处旧翻译；RPC 同名映射；用户消息与工具结果按 §7.1
   的「一个内部事件 → 两帧」规则展开。
9. **载荷**（§8，独立可交付）：`ToolOutcome`/`Message`/编解码/wire + 4 处编译点修复。
10. **前端 schema**（§9，独立可交付）。
11. **测试**：见 §12。

> 1–2 与 4–6 可并行；3 是唯一会改 Action 序列的步骤，建议单独一个提交批次。

---

## 12. 验收

- `mvn clean verify` 零错误零警告（含 SpotBugs/checkstyle）。
- `AgentEventTranslatorTest`：**改写 3 条**（`toolCallEventsEmitToolExecution:53`、
  `startEmitsAgentStart:33`、`agentSettledEmitsTurnEnd:92`）+ 新增：
  `turnStartEmittedPerTurn`、`turnEndEmittedOncePerTurn`（start/end 数与轮数相等）、
  `turnEndPrecedesNextTurnStart`、`turnEndCarriesTurnIndexMessageAndToolResults`、
  `toolExecutionStartCarriesToolNameAndArgs`、`toolExecutionUpdateCarriesPartialAndArgs`、
  `deniedToolCallEmitsStartAndEnd`、`toolResultMessageEmitsStartAndEndPair`、
  `abortBeforeFirstLlmStillEmitsTurnPair`。
- **`turnEndEmittedOncePerTurn` 必须覆盖「串行多工具」轮次**（`{Bash, Read}` 或 `ToolExecution.Sequential`
  下的多调用）——这是 §4.3 的收尾点唯一性最容易被改坏的地方（一轮 N 个 `ExecuteTool`）。
- `AgentLoopL2Test`：golden-trace 序列按 §4.3 重排后更新（含 `length` 路径、串行多工具、terminate 收口），
  并**必须**含两条终止性用例：①「无工具的一轮 ⇒ 恰好一次 `FinishOperation`、驱动返回 `null`」
  （§4.3 ④ 的最后一支，上一版缺它会导致驱动不终止）；②「工具执行完 ⇒ 恰好一次 `turn_end` 且继续下一轮」。
- **恢复路径**：有未完成 operation 的 lane 恢复后，断言**不产生** `turn_end`/`turn_start`
  （`turnOpen==false`，§4.5），且当 fold 判定为 `completed` 时**直接终结**（不发新的 LLM 请求）。
- 钩子：`HookTest` 增补「有工具轮次的 `toolResults` 非空且顺序 == calls 顺序」「`shouldStopAfterTurn`
  在有工具轮次生效且**不丢** `pendingToolCalls`」「`error`/`aborted` 轮次不触发钩子」「terminate
  轮次两个钩子照常触发」。
- 载荷：工具 `details` 经 **JSONL 往返**、SQLite 往返、Web wire、RPC wire 后仍在（§8）。
- 手工：WS 帧序列为
  `agent_start → turn_start(0) → message_start(user) → message_end(user) → message_update*`
  `→ tool_execution_start → tool_execution_end → message_start/end(toolResult)`
  `→ turn_end(0) → turn_start(1) → … → turn_end(1) → agent_end → agent_settled`。
- `docs/19 §10` 行为条款映射表增补 turn / tool 两组。

---

## 13. 明确不等价（9 条，各有理由）

| # | 项 | pi | pi-java | 理由 |
|---|---|---|---|---|
| 1 | 消息生命周期抽象 | `message_start`/`message_end` 是通用消息动作，工具结果复用它 | 内部只发一个 `ToolResultAdded`，wire 上展开两帧 | 差异在**内部粒度**，wire 帧序列一致；新增一层消息生命周期抽象无消费者（§6.3） |
| 2 | `prepareNextTurn` 的返回值能力 | 可返回 `{context, model, thinkingLevel}`，**替换上下文** | `TurnUpdate` 只能改 model/thinking | pi-java 的消息是 transcript 的**派生物**（`docs/21` 折叠模型），「替换 context」需表达为 entry；且当前无任何生产钩子实现 |
| 3 | 并行批次的 `tool_execution_end` 顺序 | 按**完成序**（各调用闭包各自发；被拒/未找到的调用在装配循环里**提前**发 `end`） | **源序**（Stage 3 单线程按 `calls` 顺序遍历） | 消费方按 `toolCallId` 关联，时序不可观察；`message_start/end(toolResult)` 两边都按源序（C7 已注明） |
| 4 | `agent_start` 的粒度 | 每个 `runAgentLoop` 一次（`:109`/`:138`） | 每个 **run** 一次；但一个 drive 内可由 follow-up 触发多次 run，而 `AgentEnd`/`AgentSettled` 每个 drive 只发一次（`SessionRunner:176`/`:212`） | `run()` 由 `runQueued`（ConsumeQueueItem）也会到达 ⇒ 队列 follow-up 会产生第二个 `agent_start` 而中间**没有** `agent_end`。前端当前只据此置 `isStreaming`（`main.ts:251`），无可观察影响；要严格对齐需把 `agent_end` 也改成每 run 一次 |
| 5 | `usage` 的载体类型 | pi 的 `Usage`（provider 原样） | `com.pijava.ai.Usage`，由 `ToolResult.usage()`（`UsageInfo(inputTokens, outputTokens)`）转换 | pi-java 的工具结果只统计 input/output；补齐需改 `ToolResult` 契约 |
| 6 | 恢复时的 turn 事件 | pi 该路径无运行时可比 | 恢复**不补发**上一轮 `turn_end`（§4.5） | 补发会造出孤立 `turn_end`；pi 的 session 层在 `agent_start` 归零 `_turnIndex`，同向 |
| 7 | auto-compaction 落在 turn 之内 | 压缩是独立操作，不是 turn | `AssistantStreamExecutor:61-62` 的 `applyPendingTurnUpdate`/`checkAutoCompact` 在 `turn_start` 之后、LLM 请求之前 ⇒ 计入本轮 | 压缩由本轮触发、结果供本轮使用；拆成独立 turn 只会让消费方多一次配对负担 |
| 8 | deferred 写入的崩溃恢复 / deferred 运行时 | pi 只有 spec，无运行时 | `pendingWrites` 不落库 ⇒ 恢复不工作；`DeferredHandle` 无生产者 | **已立项**：`docs/26-write-durability-and-deferred-runtime-design.md`（Part A 清偿 / Part B 运行时） |
| 9 | 轮前 abort 的终态 | `aborted` | `determineOutcome` 在 `newestOwn == null` 时返回 `"error"` ⇒ 记成 **FAILED**（`HarnessUtils:120` 的兜底；开口 abort 时尚无助手条目，`deriveNewestOwn` 返回 null） | **既有行为，本方案不改**（无助手条目时无从判断中止原因）；若要严格对齐，需给 abort 路径单独留痕 |

> 另记（非对齐项）：MANUAL 客户端若重放同一个 `StreamAssistant` action，会重复发 `TurnStart`
> （`AgentHarness.executeAction` 不校验 action 与 peek 的一致性）。pi 的 harness 是
> `HarnessNotImplemented` 桩，**无此语义可比**；AUTOMATIC 路径严格链式不受影响。
