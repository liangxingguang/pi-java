# 21 agent-core 批 2 设计 —— 事件面漏斗 ＋ prepareRequest ＋ 四条裁决

> 基线：pi `ea448f454`（2026-10-11）；pi-java main（批 1 闭环后 `5ecabb6e`）。
> 所有 pi 侧 `file:line` 均经 `git -C D:/workplaceForai/pi show ea448f454:<path>` 实读（本批两路取证代理＋主线抽验），未读 pi 工作树。
> 前批：[`20-agent-core-batch1-design.md`](20-agent-core-batch1-design.md)（B158＋B159＋#17，已闭环）。

## 0. 批次卡

| 项 | 值 |
|---|---|
| 范围（用户 grill 两轮锁定） | w3 存疑六条（#46/#52/#54/#58/#78/#100）＋ w2 #34 `prepareRequest`；`agent_settled.aborted` 随 #46 收编；`durationMs` 核对销号；`--tools` glob 登记留痕 |
| 基线账面 | 138.5/183 ≈ **75.7%**（未加权 45/92 = 48.9%） |
| 目标账面 | **148.5/183 ≈ 81.1%**（+10.0：六条 w3 存疑→对齐各 +1.5，#34 +1.0）；未加权 52/92 ≈ 56.5%；**权重 3 层 22/22 = 100%** |
| 重件 | #46 族（事件面重构，Q4=A 全认账） |
| 中件 | #34 prepareRequest（Q5=A 三样全带） |
| 裁决 | #54/#58/#78/#100（Q6 全接受——**其中 #58/#78 补证后部分翻转**，见 §4/§5） |
| 跨模块口径（Q2） | 生产者在哪改哪；commit 按模块拆 |

**补证翻转表**（grill 第 2 轮立场 → 取证后结论）：

| 条目 | 原立场 | 补证结论 | 翻转后 |
|---|---|---|---|
| #52 粒度 | 「pi 每 run vs java 每 pass，续跑可区分」 | **两侧粒度逐情形相等**（每 pass/runLoop 一条；retry 各 2 条；settled 同为每 drive 一条） | 只剩载荷内容差，改造面缩小 |
| #58 | 消解（投影覆盖等价类） | 现行 pi 主流**不过滤** stopReason（`isContextMessage` 已随 harness 删除）；java 的剔除是真行为差 | **认账**（§4）；case 合并部分仍消解（§5.2） |
| #78 | 消解 | latest 等价 ✓；但 pi `listSessions` 排序键是**最后活动时间**（mtime 仅兜底），java list 是 mtime desc | 消解维持，排序键差写进裁决留痕（§5.3） |
| #54/#100 | 消解 | 补证成立（无 Entry 身份消费者；provider 边界同点折叠） | 消解（§5.1/§5.4） |

---

## 1. #46 族：会话事件面重构（认账 · 重件）

### 1.1 pi 参照（逐字）

**AgentEvent 全 10 变体**（`agent/src/types.ts:510-539`；9 个字段全必填，唯一可选键是 `tool_execution_end.durationMs?`）：

```ts
export type AgentEvent =
	| { type: "agent_start" }
	| { type: "agent_end"; messages: AgentMessage[] }
	| { type: "turn_start" }
	| { type: "turn_end"; message: AgentMessage; toolResults: ToolResultMessage[] }
	| { type: "message_start"; message: AgentMessage }
	| { type: "message_update"; message: AgentMessage; assistantMessageEvent: AssistantMessageEvent }
	| { type: "message_end"; message: AgentMessage }
	| { type: "tool_execution_start"; toolCallId: string; toolName: string; args: any }
	| { type: "tool_execution_update"; toolCallId; toolName; args; partialResult: any }
	| { type: "tool_execution_end"; toolCallId; toolName; result; isError; durationMs?: number };
```

**AgentSessionEvent 24 变体**（`core/agent-session.ts:193-234`）= 9 个包装 AgentEvent
（`WithParentToolCallId<Exclude<AgentEvent, {type:"agent_end"}>>`）＋ 15 个会话变体，含：

```ts
| { type: "agent_end"; messages: AgentMessage[]; willRetry: boolean }   // 覆写版
| { type: "agent_settled"; aborted: boolean }                           // :203
```

**单一漏斗全量转发**（`agent-session.ts:1139-1142`，逐字）：

```ts
// Emit to extensions first, then notify public listeners.
await this._emitExtensionEvent(event);
this._emit(event.type === "agent_end" ? { ...event, willRetry: this._willRetryAfterAgentEnd(event) } : event);
```

**`agent_settled` 的 aborted 来源**（`agent-session.ts:1083` `const aborted = this._agentRunAbortRequested;`；
旗在 `abort()` 开头置位 `:2434-2436`（`if (this._isAgentRunActive) …`），`_runAgentPrompt` 起手清零 `:1823`；
发射在 run 收尾 `:1849`，每次 `_runAgentPrompt` 恰一条）。

**引擎发射时序**（`agent-loop.ts`）：`agent_start` `:117`（continue 路 `:146`）→ `turn_start` `:118`/`:147`/`:207`
→ initial/pending 消息逐条 `message_start`+`message_end`（`:119-123`、`:212-213`）→ …→ `agent_end{messages: newMessages}`
三处（`:254`/`:290`/`:320`，载荷均为**本 run 的 newMessages**）。失败合成路 `agent.ts:530-547` 连发
`message_start`→`message_end`→`turn_end{failureMessage,[]}`→`agent_end{[failureMessage]}`。

### 1.2 改造总览：引擎零改动

取证坐实：java 引擎（`PiLoop`）**已发全** pi 的 10 事件——`PiLoop.run:340-345`（AgentStart→TurnStart→
initial 消息 MessageStart/End）、`PiLoopRunner:88/:97-98/:116-117/:144/:148/:181/:288/:293/:339/:341`、
`RunFailure.settle:60-63`（失败四条逐字同序）。缺的一律在**会话层转发**（`SessionRunner` 手工挑选）。

| 件 | 模块 | 改动 |
|---|---|---|
| `AgentSessionEvent` 加 5 record＋`AgentSettled` 重塑＋删 `UserMessageReceived` | coding-agent | §1.3 |
| `SessionRunner` 漏斗化＋`agent_end` 改本 pass 载荷＋`AgentSettled{aborted}` | coding-agent | §1.4 |
| `AgentSession` aborted 旗 | coding-agent | §1.5 |
| `JsonEventMapper` 五新帧＋`agent_settled` 改载荷；超 500 行拆分 | coding-agent | §1.6 |
| `AgentEventTranslator` 直译化＋`transcriptSource` 吸收全量帧 | web | §1.7 |
| `RpcDispatcher` streaming 闸微调 | coding-agent | §1.8 |

### 1.3 `AgentSessionEvent`：五新 record ＋ 重塑 ＋ 删除

**新增**（放在 `MessageUpdate` 之后，注释给 pi 锚）：

```java
/** pi {@code { type: "agent_start" }}（types.ts:511）。 */
record AgentStart() implements AgentSessionEvent {}

/** pi {@code { type: "turn_start" }}（types.ts:514）。 */
record TurnStart() implements AgentSessionEvent {}

/** pi {@code { type: "turn_end"; message; toolResults }}（types.ts:517）——两字段必填。 */
record TurnEnd(Message.AssistantMessage message, List<Message.ToolResultMessage> toolResults)
    implements AgentSessionEvent {}

/** pi {@code { type: "message_start"; message }}（types.ts:520）——system/user/assistant/toolResult 全角色。 */
record MessageStart(Message message) implements AgentSessionEvent {}

/** pi {@code { type: "message_end"; message }}（types.ts:526）。 */
record MessageEnd(Message message) implements AgentSessionEvent {}
```

**重塑**（pi `agent-session.ts:203` 逐字形状；`AgentSettledPayloadTest` 等随之迁移）：

```java
/** pi {@code { type: "agent_settled"; aborted: boolean }}（agent-session.ts:203）——
 *  每次驱动（≙ pi {@code _runAgentPrompt}）恰一条；aborted 取自会话层 abort 请求旗
 *  （pi {@code _agentRunAbortRequested}，:1083）。 */
record AgentSettled(boolean aborted) implements AgentSessionEvent {}
```

原 `AgentSettled(Message, List<Message>)` 的载荷角色由真 `TurnEnd` 继承（每回合、引擎直供）——
同型异义雷（RPC 叫它 `agent_settled`、web 叫它 `turn_end`）就此拆除；「错误路 message 为 null」
的残余偏差（原 docs/36 §10 登记）随之消解：失败路的 `TurnEnd` 由 `RunFailure` 合成消息填充，恒非 null；
空载荷兼容构造器 `AgentSettled()` 删除。

**删除** `UserMessageReceived`（:25）：引擎 `PiLoop.run:342-345` 已对 prompt 发 `MessageStart/MessageEnd(user)`，
吸收后 user 回显走真 `message_end` 帧，RPC 线同步补齐（现状是落 `unsupported_event`）。

### 1.4 `SessionRunner`：漏斗化

`passEvents`（现 :222-275）整体改写为全量转发（pi `agent-session.ts:1141` 形状）：

```java
private static PiLoop.Sink passEvents(AgentSession owner, String laneName) {
    return event -> {
        // pi 漏斗（agent-session.ts:1139-1142）：引擎事件全量成为会话事件，
        // 仅 agent_end 过漏斗时装饰 willRetry。
        // ⚠️ MessageUpdate 刻意不在此列：会话层的 MessageUpdate 由 :92 的原始帧
        // 广播链喂（含 UsageInfo 等非生命周期帧），漏斗再转一手会双发。
        switch (event) {
            case PiLoop.Event.AgentStart ignored ->
                owner.emitSessionEvent(new AgentSessionEvent.AgentStart());
            case PiLoop.Event.TurnStart ignored ->
                owner.emitSessionEvent(new AgentSessionEvent.TurnStart());
            case PiLoop.Event.TurnEnd e ->
                owner.emitSessionEvent(new AgentSessionEvent.TurnEnd(e.message(), e.toolResults()));
            case PiLoop.Event.MessageStart m ->
                owner.emitSessionEvent(new AgentSessionEvent.MessageStart(m.message()));
            case PiLoop.Event.MessageEnd m -> {
                // 原 docs/27 §2.1：每条 entry 产生后即写（崩溃窗口 = 一条 entry）。
                flush(owner, laneName);
                owner.emitSessionEvent(new AgentSessionEvent.MessageEnd(m.message()));
            }
            case PiLoop.Event.ToolExecutionStart s ->
                owner.emitSessionEvent(new AgentSessionEvent.ToolExecutionStart(
                    s.toolCallId(), s.toolName(), s.args()));
            case PiLoop.Event.ToolExecutionUpdate u ->
                owner.emitSessionEvent(new AgentSessionEvent.ToolExecutionUpdate(
                    u.toolCallId(), u.toolName(), u.args(), u.partialResult()));
            case PiLoop.Event.ToolExecutionEnd e ->
                owner.emitSessionEvent(new AgentSessionEvent.ToolExecutionEnd(
                    e.toolCallId(), e.toolName(), e.result(), e.isError(), e.durationMs()));
            case PiLoop.Event.AgentEnd end -> {
                flush(owner, laneName);
                var willRetry = willRetryAfter(owner, end.messages());
                // #52：载荷改 pi 形状 —— 本 pass 的 newMessages（agent-loop.ts:254/290/320），
                // 不再是 accumulatedMessages() 全历史。web 的整表替换由
                // AgentEventTranslator 的 transcriptSource 吸收（§1.7）。
                owner.emitSessionEvent(new AgentSessionEvent.AgentEnd(end.messages(), willRetry));
            }
            case PiLoop.Event.MessageUpdate ignored -> { /* :92 广播已喂，见 switch 注释 */ }
        }
    };
}
```

- 删：`echoed` AtomicBoolean 与 `AgentStart` 上的 `UserMessageReceived` 合成段（:246-262）。
- 驱动收尾（:174-175）改：`new AgentSessionEvent.AgentSettled(owner.agentRunAbortRequested())`；
  兜底 catch（:199）同形改造。`tailAssistant`/`driveToolResults` 采集随旧载荷删除
  （`:161-170` 的 `driveToolResults` 收集段一并删，`EntryAppended` 发射保留）。
- ⚠️ `willRetryAfter` 保持读 `end.messages()`（本 pass），与 pi `_willRetryAfterAgentEnd` 倒扫
  本 pass 消息一致（现状即如此，不变）。

### 1.5 `AgentSession`：aborted 旗（pi `_agentRunAbortRequested` 同构）

```java
// pi agent-session.ts:1083/2434-2436/1823：settled 时读旗；abort() 置位；run 起手清零。
private final AtomicBoolean agentRunAbortRequested = new AtomicBoolean();

/** pi {@code _agentRunAbortRequested}：settled 事件的 aborted 载荷源（包内可见）。 */
boolean agentRunAbortRequested() {
    return agentRunAbortRequested.get();
}
```

- `abort()`（:788-793）内 `harness.abort(laneName())` 之前 `agentRunAbortRequested.set(true)`。
  pi 有 `_isAgentRunActive` 门；java 侧旗在**驱动起手清零**（下条），空转期置位无害。
- 清零点在 `SessionRunner.drive` 起手（pi `_runAgentPrompt:1823` 同位）：`owner.agentRunAbortRequested.set(false)`。
- `AgentSession` 已 988 行（E 项在册），本批净增 ≤10 行，与批 1 同纪律不拆。

### 1.6 RPC `JsonEventMapper`：五新帧 ＋ `agent_settled` 改载荷 ＋ 拆分

**先拆分**（现 480 行，加帧必破 500）：把 `messageNode`/`contentArray`/`blockNodeForWire`/`isAbsentOptional`
（:230-302，约 73 行）抽为同包（`mode`）包私有 `WireMessageNode`，`JsonEventMapper` 委托
（`JsonEventMapper.messageNode(m)` 保留为一行委托，web 侧复用点不动）。拆分后 ≈407 行，加帧后 ≈445 行。

**新帧**（pi 字段全必填 ⇒ 一律不省键；`message` 走 `messageNode`）：

```java
case AgentSessionEvent.AgentStart ignored -> node.put("type", "agent_start");
case AgentSessionEvent.TurnStart ignored -> node.put("type", "turn_start");
case AgentSessionEvent.TurnEnd e -> {
    node.put("type", "turn_end");
    node.set("message", messageNode(e.message()));
    var results = node.putArray("toolResults");
    for (var m : e.toolResults()) { results.add(messageNode(m)); }
}
case AgentSessionEvent.MessageStart m -> {
    node.put("type", "message_start");
    node.set("message", messageNode(m.message()));
}
case AgentSessionEvent.MessageEnd m -> {
    node.put("type", "message_end");
    node.set("message", messageNode(m.message()));
}
```

**`agent_settled` 改写**（替换现 :80-94 整支）：

```java
case AgentSessionEvent.AgentSettled s -> {
    // pi { type: "agent_settled"; aborted: boolean }（agent-session.ts:203）——单字段必填。
    node.put("type", "agent_settled");
    node.put("aborted", s.aborted());
}
```

`agent_end` case 代码不变（载荷内容随事件本体变 pi 形状）。

### 1.7 web `AgentEventTranslator`：直译化 ＋ 适配层吸收

构造注入全量转录源（`WebDispatcher` 接线 `session::accumulatedMessages`）：

```java
private final java.util.function.Supplier<List<com.pijava.ai.message.Message>> transcriptSource;

AgentEventTranslator(Supplier<List<Message>> transcriptSource) { this.transcriptSource = transcriptSource; }
```

switch 改写要点：

- `AgentStart` → `streaming = true` ＋ `agent_start` 帧（**新真源**）；
  `translateStream` 的 `StreamEvent.Start → agent_start` 合成支**删除**（防双发；会话 AgentStart 恒先到）。
- `TurnStart` → `turn_start` 帧（`main.ts:317` 仅 `renderApp()`，无副作用）。
- `TurnEnd` → `turn_end` 帧 `{message, toolResults}`（**每回合**；`main.ts:341-353` 按 toolCallId 去重追加，
  天然耐受多条；**不动 streaming** —— 多回合 run 的第一个 turn_end 不能收流式态）。
- `MessageStart` → `message_start` 帧；`MessageEnd` → `message_end` 帧（`WebWireJson.messageNode`，全角色；
  `main.ts:327-339` 的 user 回显路径不变，assistant 的 `streamingMessage = null` 语义正确）。
- `AgentEnd` → `streaming = false` ＋ `agent_end` 帧，**messages 数组由 `transcriptSource.get()` 供**
  （前端整表替换语义逐字节不动；空 ⇒ 省键＋warn 的现状保留）。`agentEnd(e)` 改为只取 `e.willRetry()`。
- `AgentSettled` → `streaming = false`，**不发帧**（`main.ts` 无 `agent_settled` 消费；保留它对
  streaming 的兜底收束）。
- 删：`UserMessageReceived` 支与 `userMessageEnd`、`turnEnd(AgentSettled)`。
- `MessageUpdate`/工具三支/BashExecutionUpdate 不动。

`main.ts` **零改**（消费面逐字读过 `:280-360`：`agent_start`/`turn_start`/`message_start` 仅 renderApp；
`turn_end` 去重追加；`message_end(user)` 回显；`agent_end` 整表替换）。

### 1.8 `RpcDispatcher` / `ChatScreen`

- `RpcDispatcher.emitEvent`（:362-376）：`AgentEnd || AgentSettled → streaming = false` 现状保留
  （per-pass AgentEnd 收 false、下一 pass AgentStart 前由 prompt 命令路再置 —— 与今日行为相同，不扩大改动）。
- `ChatScreen`（TUI）：新事件落 `default` 忽略（:303），零改；`FlashStatus` 方言不动。

### 1.9 #46 族测试

**迁移**（断言形状随事件面变化）：
`AgentSettledPayloadTest`（→ `aborted` 载荷）、`SessionFailurePathTest`、`SessionFlushTimingTest`、
`AgentSessionRetryEventOrderTest`、`UserEchoTest`（→ `message_end(user)` 帧）、`AgentSessionCompactAbortTest`、
`AgentSessionSubscribeTest`、`AgentSessionToolExecutionEventTest`、`JsonEventMapperTest`、
`RpcWireMessageShapeTest`、`RpcDispatcherTest`、`RpcModeEndToEndTest`、
`AgentEventTranslatorTest`、`AgentEventTranslatorToolCallVisibilityTest`、`ChatScreenSessionEventTest`。

**新增**（`SessionRunnerFunnelTest`，coding-agent；假 provider 剧本复用存量手法）：
1. 单轮：`agent_start → turn_start → message_start/end(user) → message_start(assistant) → … → message_end → turn_end → agent_end → agent_settled{aborted:false}` 全序钉死（pi 引擎时序 :117-123/:207/:253/:287/:320）。
2. 工具回合：两条 `turn_end`（每回合一条）＋`agent_end` 一条。
3. **#52 载荷**：两轮剧本下 `agent_end.messages` == 本 pass 新增（不含首轮消息）。
4. 失败路（provider 抛错）：`RunFailure` 四条上会话面，`agent_end.messages == [failureMessage]`。
5. abort 路（批 1 心跳假 provider 手法）：`agent_settled{aborted:true}` 且 `turn_end` 的 message 是 aborted 合成消息。
6. retry 路：两条 `agent_end`（第一条 `willRetry=true`），粒度与 pi 相同（补证 §0 表）。

---

## 2. #34：`prepareRequest` 写钩子（认账 · 中件）

### 2.1 pi 参照（逐字，主线抽验一致）

`types.ts:159-176`：

```ts
export interface AgentLoopTurnUpdate {
	context?: AgentContext;
	messages?: AgentMessage[];      // ← prepareNextTurn 才有
	model?: Model<any>;
	thinkingLevel?: ThinkingLevel;
}
export interface PrepareRequestContext {
	context: AgentContext;
	model: Model<any>;
	thinkingLevel: ThinkingLevel;
}
/** Replacement runtime state for the provider request being prepared. */
export type AgentRequestUpdate = Omit<AgentLoopTurnUpdate, "messages">;
```

`types.ts:186-190`：`PrepareRequest = (request: PrepareRequestContext, signal?) => AgentRequestUpdate | void | Promise<…>`
（注释：「Called immediately before **every** conversational provider request, including the first.
Pending messages have already been appended and emitted when this callback runs.」）

调用点 `agent-loop.ts:219-239`（逐字要点）：

```ts
const requestUpdate = await config.prepareRequest?.(
	{ context: currentContext, model: config.model, thinkingLevel: config.reasoning ?? "off" }, signal);
if (requestUpdate) {
	currentContext = requestUpdate.context ?? currentContext;
	config = { ...config,
		model: requestUpdate.model ?? config.model,
		reasoning: requestUpdate.thinkingLevel === undefined ? config.reasoning
			: requestUpdate.thinkingLevel === "off" ? undefined : requestUpdate.thinkingLevel };
}
```

写回是 **run 内局部**（`config` 是 runLoop 的局部变量）⇒「对本次及后续请求生效」＝本 run 后续轮，
**不跨 run**（java 无需写回 `LaneState`，与既有 `prepareNextTurn` 写回同构）。

### 2.2 `PiLoop` 新类型（harness 包，与 `FinishTurnHook` 同处）

```java
/** pi {@code PrepareRequestContext}（types.ts:171-175）：每次 provider 请求前的运行时快照。 */
public record PrepareRequestContext(Context context, ModelId<?> model, ModelThinkingLevel thinking) {}

/**
 * pi {@code AgentRequestUpdate}（types.ts:176，= {@code Omit<AgentLoopTurnUpdate,"messages">}）。
 * 三组件全可选，{@code null} ≙ {@code undefined}（不改）。{@code context} 是整体替换。
 * {@code thinking} 的 {@link ModelThinkingLevel.Off} ≙ pi 的 {@code "off"} ⇒ 请求侧「不传」
 * （pi 的 {@code reasoning: undefined} 三分支由密封类型天然编码，无需特判）。
 */
public record RequestUpdate(Context context, ModelId<?> model, ModelThinkingLevel thinking) {}

/** pi {@code PrepareRequest}（types.ts:186-190）。返回 {@code null} ≙ {@code void}。 */
@FunctionalInterface
public interface PrepareRequestHook {
    RequestUpdate apply(PrepareRequestContext request);
}
```

`Config` 增组件 `PrepareRequestHook prepareRequest`（置于 `transformContext` 之后、
`prepareNextTurn` 之前——pi 的请求时序：prepareRequest `:219` → transformContext `:390`）；
`PiLoopRunner.withModelAndThinking`（:199-209）与新增的 `withRequestUpdate` 都要带上新组件。

### 2.3 `PiLoopRunner` 调用点（:102 与 :104 之间）

```java
// pi: prepareRequest（agent-loop.ts:219-239）——每次 provider 请求前都调（含第一轮），
// 此时 pending 已 append 进 context（:95-101）。可整体替换 context 或改写
// model/thinking；写回 config ⇒ 对本 run 后续请求生效（pi 的 config 是 run 局部）。
if (config.prepareRequest() != null) {
    var requestUpdate = config.prepareRequest().apply(
        new PiLoop.PrepareRequestContext(context, config.model(), config.thinking()));
    if (requestUpdate != null) {
        if (requestUpdate.context() != null) {
            context = copyOf(requestUpdate.context());   // pi: currentContext = u.context ?? currentContext
        }
        config = withRequestUpdate(config, requestUpdate); // pi: config = {...config, model, reasoning}
        messages = context.messages();                   // context 可能已换，重指活列表
    }
}
```

### 2.4 `HookSystem`：链式合并 ＋ 删老形状

新增（仿 `fireFinishTurn` 合并段）：

```java
/** pi {@code prepareRequest}（types.ts:186-190）。注册键保留 {@code "before_request"}（B221 族先例）。 */
public void onPrepareRequest(String laneName, PrepareRequestHook hook) { … }

/**
 * 链式合并：按注册序逐钩调用，后钩看到前钩的替换结果；任一钩抛异常记遥测并弃权
 * （与 fireFinishTurn 同纪律）；全弃权 ⇒ {@code null}。
 */
public PiLoop.RequestUpdate firePrepareRequest(String laneName, PiLoop.PrepareRequestContext request) { … }
```

hook 包新增 `PrepareRequestHook`（扩展面接口，方法签名同上，仿 `ShouldStopAfterTurnHook` 留住接口名的先例；
javadoc 注「pi 唯一名 prepareRequest」）。

**删除老形状**：`BeforeRequestHook.java`、`hook/RequestContext.java`、`HookSystem.onBeforeRequest/fireBeforeRequest`、
`PiLaneSink.beginRequest` 的钩子行（:197-199）与 `systemPrompt` 字段及回填
（`PiLaneEngine:293-294` 的 `sink.systemPrompt(systemPrompt)`）——该字段唯一读者是老钩子。
`PiLaneSink.beginRequest` 保留跨度职责（`llm.request` 打开）。

### 2.5 `PiLaneEngine` 接线（`configFor` :329-345）

`transformContext` 槽的 `ignored -> beforeRequest(laneName, lane, sink)` **保留**（它包着
`sink.beginRequest` 的跨度开销）；新增槽位：

```java
request -> ctx.hookSystem().firePrepareRequest(laneName, request),
```

**留痕**（架构裁决，不写码）：① `llm.request` 跨度的 model/thinking 标签读车道 supplier
（`ctx.model().get()`），prepareRequest 的 run 内替换不反映到跨度标签——与 pi 的遥测口径未核，记窄差；
② java 现有宿主层模型切换（`AgentHarness.setModel` 族）与 prepareRequest 重定向并存——
后者是请求发出前最后一次改写机会（pi 同形，`agent-session.ts:778-789` 的模型路由即走此路），不冲突。

### 2.6 #34 测试

**迁移**：`HookTest`（老钩子用例改新钩子）、`ModelSwitchRealReproTest`（注册点迁移，观察语义不变——返回 null）。

**新增**（`PiLoopPrepareRequestTest`，agent-core）：
1. 每轮请求前都调（含第一轮）——两轮剧本计数 2。
2. 调用时机：pending 已 append（钩子的 `request.context()` 含 steering 消息）且在 transformContext 之前。
3. context 整体替换：替换后请求用的是新 context（断言 streamFn 收到的 llmContext）。
4. model/thinking 替换对本 run 后续轮生效（第二轮请求的 model 是替换值）；`Off` ⇒ 请求侧 `reasoning` 为空。
5. null 返回 ⇒ 一切不变。
6. `HookSystem` 链式：两钩串联，后钩入参含前钩替换；全弃权 ⇒ null；抛异常钩弃权不炸循环。

---

## 3. #58：删除 stopReason 投影剔除（补证翻转 · 认账）

**补证结论**（推翻原消解立场）：现行 pi 主流把 error/aborted 的 assistant 消息**原样发给 provider**——
循环层只终止（`agent-loop.ts:245-256`），写回 context（`:441-450`），转换链无过滤（`:389-397`、
`agent.ts:38-46` 只按 role），session-manager 投影侧零 stopReason 处理（`:440` 起 `return [message]`）。
java 的 `NON_PROJECTED_STOP_REASONS` 移植自已删除的 harness `isContextMessage`（R1：参照物被删 ⇒ 移植依据作废）。

**改动**（agent-core `ContextEntries.java`）：
- 删 `NON_PROJECTED_STOP_REASONS`（:42-54）与 `project` 里的剔除支（:261-265）⇒
  `Entry.Message` 恒 `return m.message();`（:266 原位）。
- `DEFERRED` 随删失去最后读者：主代码零生产者（全仓 grep 仅枚举定义/注释），ai 层枚举保留（词表）。
- 注释联动：`ContextEntries` 类注与 `PiLoopRunner.markAborted` javadoc（:346-366）里两处
  引 `NON_PROJECTED_STOP_REASONS` 的段落改写——`markAborted` 的「落定停因」不变量**保留**
  （它服务重试判定/读尾/估算，不再服务投影剔除；A8 的「残缺响应投影」担忧按现行 pi 行为消解：
  pi 本来就把 aborted 消息内容发给 provider）。

**新测试**（`ContextEntriesTest` 或并入存量）：stopReason=ERROR／ABORTED 的 assistant entry
投影后**在列**（翻转前断言不在列的用例反向迁移）。

## 4. 四条裁决（写 `docs/04` §4；map/03 翻转依据）

### 4.1 #54 Entry union —— 消解成立

补证：label/session_info 的全部消费者 pattern-match 的都是 `SessionMutation`/`LogItem`，
**零 Entry 身份消费者**；wire 读写 pi 原生行（`PiV3Wire:209-223/:234-244`）＋fact 链进树（parentId）⇒
磁盘全互通、树位等价；pi 的 `SessionTreeNode.label`（`session-manager.ts:203-206`，`labelsById`+`getLabel :1430-1433`）
在 java 的对应物是 `SessionState.labels` map + `Session.getLabel`（:144-150）——同构访问器，树节点本身两侧都不带 label。
裁决：**行为等价，不认账**；内存 union 不补 2 record（认了要牵 7 个生产文件的双表示归一，买的是不可观察的形状洁癖）。

### 4.2 #58 convertToLlm 的 case 合并部分 —— 消解成立

pi 的「消息级 convertToLlm」与「entry 级 buildSessionContext 投影」在 java 合并为一条 entry 投影管线，
case 语义逐条可对上（custom→user、branchSummary/compactionSummary 加前后缀、四角色透传）；
唯一真差 bashExecution 角色不在对齐范围（harness 层三角色，原 docs/31 §1.2）。
stopReason 剔除部分**不消解**——已转 §3 认账。

### 4.3 #78 find/latest 范围 —— 消解 ＋ 排序键留痕

- latest/resume：等价 ✓（java `latest(cwd)` 单项目目录，注释含 1082ms→5ms 实测；pi `continueRecent` 同范围）。
- **排序键差**（留痕）：pi `listSessions` 按**最后活动时间** desc（`session-manager.ts:858-864`：
  max(message entry timestamp)，mtime 仅兜底）；java `JsonlSessionRepository.list` 按**文件 mtime** desc（:85-90）。
  分叉场景：改名/打标追加 fact 行只 bump mtime 不 bump 活动时间 ⇒ pi 选择器序不变、java 变。
  裁决：**不认账**——mtime desc 与 pi `findMostRecentSession:757` 同源同键（latest 路径等价），
  而 pi 的 lastActivity 需要对每个会话文件**全量扫 entry**（正是 java 优化掉的成本）；追加写下 mtime 序 ≈ 活动序。
- `find(idOrPrefix)` 跨项目扫描：java 多出面，CLI `--resume <id>` 需要 ⇒ 裁决保留。

### 4.4 #100 系统提示载体 —— 消解 ＋ 注释重写

补证：pi 主流**无会话中变更路径**（`setSystemPrompt` 全仓零命中；写入点全在启动期）；
java provider 边界 `DefaultProviders:276-278` 把 `context.systemPrompt()` 交 `ContextNormalizer:25-38`
**折成首条系统消息**——与 pi `normalizeContext`（`transcript.ts:30-34`）同点折叠，请求线行为等价。
`AgentHarness.setSystemPrompt`（:505-509）是超集能力（pi 无），留痕不删。

**`Context.java` 类注重写**（:9-26 引 6 处旧版 pi 行号，且「pi 的 Message 没有 system 角色」已被现行 pi 推翻）：

```java
/**
 * 一次 LLM 请求的上下文 —— pi {@code Context} 与 {@code AgentContext} 在 pi-java 侧的统一对应物。
 *
 * <p><b>载体对齐（本锚 ea448f454 重核）</b>：pi 的请求上下文 {@code TranscriptContext}
 * <b>只有 messages</b>（{@code packages/ai/src/types.ts:780-783}），系统提示由
 * {@code normalizeContext}（{@code utils/transcript.ts:30-34}）折成<b>首条 system 消息</b>；
 * pi-java 在 provider 边界经 {@code ContextNormalizer} 于<b>同一点</b>做同一折叠
 * （{@code DefaultProviders} 交出本记录的 {@code systemPrompt} 字段）⇒ 请求线行为等价。
 * pi 主流没有会话中变更系统提示的路径（{@code setSystemPrompt} 全仓零命中）；
 * {@code AgentHarness.setSystemPrompt} 是 pi-java 的超集能力，留痕。</p>
 *
 * <p>（保留原有两段：两消息模型合并的缘由、{@code AgentTool} 宽窄定义的 2026-09-13 更正，
 * 行号锚更新为本锚：{@code agent/types.ts:501-507}、{@code agent-loop.ts:508-524}。）</p>
 */
```

## 5. 销号与登记

- **`durationMs` 销号**（新缺口①）：事件（`PiLoop.java:74-88`／`AgentSessionEvent.java:148-161`）、
  消息（`Message.java:386-395`）、单调钟（`ToolRegistry.java:150-164`，`System.nanoTime` 只围本体）、
  生产链（`PiToolRunner:157`→`PiLoopTools`→事件）与两条消费链（`JsonEventMapper:207-208`、
  `AgentEventTranslator:187-188`＋`WebWireJson`）全部在案，null⇒键省略一致 ⇒ map/03「新缺口」节划掉①。
- **`agent_settled.aborted`**（新缺口②）：随 §1 收编，销号。
- **`--tools` glob 匹配＋MCP 保留**（新缺口③）：CLI/设置域，登记留痕（docs/07 新缺口节保留③，
  闭环时给 B 号），归 coding-agent 批。

## 6. 风险与留痕汇总

| 点 | 处置 |
|---|---|
| RPC/web 线格式破坏式变更（五新帧＋`agent_settled` 载荷＋`agent_end` 载荷） | 0.1.0-SNAPSHOT、消费者全在仓内（protocol/client/server/evals 零命中）；测试随批迁移 |
| 会话 `MessageUpdate` 不随漏斗（`:92` 广播链已喂，含非生命周期帧） | switch 注释钉死，防后人补「漏」 |
| `llm.request` 跨度标签读车道态，不反映 prepareRequest 的 run 内替换 | 留痕（§2.5 ①） |
| 防御性空 `AgentEnd`（`SessionRunner:198` 兜底 catch，pi 无对应） | 保留并注释「防御方言，正常路径不可达」 |
| `AgentSession` 988 行 E 项 | 净增 ≤10 行不拆 |
| `JsonEventMapper` 拆 `WireMessageNode` | 纯搬移，无行为变 |

## 7. commit 切分与验证

1. `docs(agent-core): batch-2 design — event funnel + prepareRequest + four rulings`（本稿）
2. `feat(agent-core): #34 prepareRequest replacing hook (drop void before_request)`＋两测试迁移
   （`HookTest` 在同模块；`ModelSwitchRealReproTest` 在 coding-agent，随本件一并迁移保可编译）
3. `feat(agent-core): #58 stop filtering error/aborted from projection + #100 Context javadoc rewrite`
4. `feat(coding-agent): session event funnel — five wrapped faces + agent_settled{aborted} + per-pass agent_end`
5. `feat(web): translator passthrough + transcriptSource-fed agent_end frame`
6. `docs(agent-core): batch-2 closure — weighted 75.7->81.1%`

验证：每步 `mvn test -pl <module>`（跨模块带 `-am` 与 `-Dsurefire.failIfNoSpecifiedTests=false`）；
收口前全量 `mvn clean verify` 零错误。JDK25/Maven 显式路径按惯例。

## 8. 目标账面（闭环时回填）

| 表 | 变动 |
|---|---|
| map/03 #34/#46/#52/#54/#58/#78/#100 | 存疑 → 对齐（#54/#78/#100 附裁决号；#58 注「剔除已删」） |
| map/03 新缺口节 | ① durationMs 划掉；② aborted 划掉；③ 保留＋B 号 |
| docs/07 §2.1 | 删 6 行（w3 存疑清零）；§2.2 删 #34 |
| docs/04 §4 | 新增四条裁决（#54/#58 合并部/#78/#100） |
| docs/05 | agent-core 138.5→148.5/183 ≈ **81.1%**（未加权 52/92 ≈ 56.5%）；w3 层 22/22 = **100%**；合计 824.5→834.5/1385 ≈ 60.3% |
