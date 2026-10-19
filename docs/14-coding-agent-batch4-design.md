# coding-agent 批 4 设计稿：D 域地基——扩展事件订阅面＋双源桥接

> **状态：✅ 已闭环（2026-10-20，用户审核通过后当日实施、当日闭环）**。
> 基线：pi `ea448f454`（2026-10-11）；pi-java main（coding-agent 批 3 闭环后，`ff766677`）。
> 所有 pi 侧 `file:line` 均经 `git -C D:/workplaceForai/pi show ea448f454:<path>` 实读，未读 pi 工作树。
> 前批：`13-coding-agent-batch3-design.md`（已闭环）。
>
> **闭环 commits**：`334928c8`（T1 agent-core 扩形＋修真）→ T2 订阅面（ADT/spec/handler/bus/forExtension）→ T3 `EngineHookBridge` 三适配器 → T4 `SessionEventBridge`＋`ExtensionError` 通道＋接线（tui 另 1 commit）→ docs 回填（本稿＋map/04＋docs/04＋docs/05）。
> **验证**：新测试 42 例全绿（`AfterToolPatchPreservationTest` 4＋`ExtensionEventBusTest` 7＋`EngineHookBridgeTest` 16＋`SessionEventBridgeTest` 16——含 14 事件逐一对拍、turnIndex/previousLevel、CompactionEnd 两支、V1 投影——＋`CrossExtensionOrderTest` 1＋`JsonEventMapperExtensionErrorTest` 1，减去共享类复数）；agent-core 850/850 绿；全量 `mvn clean verify` BUILD SUCCESS（7:56 min），`[ERROR]` 0 条、无 `System.out.println` 残留。
> **实施偏差**（均不改变裁决；台账 Ruling 同载）：① ~~宿主 `ToolExecutionEnd` 不带 `args` ⇒ 窄差 D8~~ **闭环评审推翻**：pi `ToolExecutionEndEvent`（types.ts:1086-1097）是独立 interface、**本无 `args`**——§1.5 表的「上＋」系误读；扩展事件误加的 `args` 组件已删，D8 作废（载荷与 pi 逐字段一致，无差）；② §7.7「--no-extensions ⇒ 零 firing」未做 AgentSession 端到端夹具（测试 classpath 无全局 META-INF/services，`ExtensionManagerTest` 走作用域 loader 防污染的先例；noExtensions 早退是结构保证），跨扩展序以 `loadAll(ClassLoader)` 夹具覆盖；③ `ExtensionContext.forExtension` 落成接口 default 方法（返回 this）——`ExtensionManager` 字段保持 `ExtensionContext` 类型、存量测试构造零改签；④ `SessionRunnerFunnelTest`/web `AgentEventTranslator` 的 switch 有 default 分支，新变体自然落默认即设计行为，未加显式 case（ChatScreen 按设计补显式 case 钉 D5）；⑤ V1 通过——`message_update` 的 `message` 经 `StreamEvent.partial()`＋`Message.AssistantMessage.fromPartial` 投影（无快照的 `UsageInfo` 帧不发射），D-31 按对齐翻；⑥ **§8 算术修正**（闭环评审发现）：D 域逐行 Δ 加总为 **+32.5**（§8 正文写 +30.5，系本稿对账表加总错误），D 域基线逐行实算 **11.5**（map/04 旧汇总行 10.5，存疑 8 行被计成 7 行）⇒ 实际账面 D 域 **44.0/83＝53.0%**、模块 **261.5/496＝52.7%**（非预期的 258.5/52.1%）、全仓 **926.5/1381 ≈ 67.1%**；⑦ 闭环评审补修：扩展处理器抛 `Error`（非 Exception）的捕获链——pi catch 一切，java 原 `catch (Exception)` 会让 `AssertionError` 炸穿 prepare；`tool_call` 包 `RuntimeException`（保 message）走错误结果链、其余族改 `Throwable` 逐处理器上报（两测试 RED→GREEN）。

| 项 | 内容 |
|---|---|
| 批次 | coding-agent 批 4（docs/14）；pi 锚点 `ea448f454`（v1.1.0-22）；判定权威＝map/04 逐行 |
| 重件 | `ExtensionEventBus`（订阅/退订/错误上报，§3.3）；引擎钩子族桥 3 适配器（§3.4：`context` 两阶段、`tool_call`、`tool_result`）；宿主事件族桥 14 事件（§3.5） |
| 中件 | 事件 ADT＋spec-token 订阅 API（§3.1/§3.2）；`AfterToolPatch` 扩 structuredContent＋`applyAfterToolPatch` 存量修真（§3.4.4）；`AgentSessionEvent.ExtensionError` 新变体＋RPC `extension_error` 线（§3.6） |
| 裁决 | ① spec-token 单 `on()`（非 41 便捷方法、非裸字符串）；② 引擎适配器装配期恒装、内部空订阅快走（pi 同形）；③ `tool_call` 处理器异常**向上传播**（pi 逐字），其余族逐处理器捕获上报；④ `context` 事件原地可变列表＋**引用相等**快照比对（pi `===` 同构）；⑤ 桥在装配期订阅 eventHub，先于模式层（pi「扩展先」同向）；⑥ 错误通道走 `AgentSessionEvent.ExtensionError` 新变体（RPC/TUI 同管道） |
| 不碰 | 结果臂事件族（message_end 替换 / turn_end·agent_before_settle boundary / input / user_bash / before_agent_start / session_before_switch·fork —— 新登记 **B249**）；provider 请求面三件套＋流事件（ai 模块注入口缺席——新登记 **B248**）；`session_before_compact` cancel＋全载荷（挂 B249）；`session_before_tree`/`session_tree`（**B245** 在册）；会话生命周期发射点 `session_start`/`session_shutdown`（新登记 **B250**）；D′ 扩展 UI 注入面；E6 包管理器 |
| 一句话 | pi 的扩展事件面＝`ExtensionAPI.on()` 41 重载＋loader 订阅表＋runner 发射语义。java 的引擎钩子（agent-core `HookSystem` 13 钩）与宿主事件（`AgentSessionEvent` 24 变体）早已各自生产，但 `ExtensionContext` 不暴露订阅面——扩展一个事件也订不到。本批建订阅面，并把「引擎钩子族 4 事件＋宿主事件族 14 事件」按 pi runner 语义桥上去。 |

预期账面（对账表见 §8）：D 域 17 行翻对齐、2 行翻存疑，E5-35 翻对齐；模块加权 45.6% → **52.1%**（226.0 → 258.5/496，+32.5）。**实际闭环账面**（§8 算术修正后，见闭环头偏差⑥）：D 域 44.0/83＝53.0%、模块 **52.7%**（261.5/496）、全仓 67.1%（926.5/1381）。

---

## §1 pi 侧事实（取证，锚点 `ea448f454`，只走 `git show`）

### 1.1 订阅面：`ExtensionAPI.on()`（loader.ts:272-288，逐字）

```ts
on(event: string, handler: HandlerFn): () => void {
	assertActive();
	const registeredHandler: HandlerFn = (...args) => handler(...args);
	const list = extension.handlers.get(event) ?? [];
	list.push(registeredHandler);
	extension.handlers.set(event, list);

	return () => {
		const handlers = extension.handlers.get(event);
		if (!handlers) return;
		const handlerIndex = handlers.indexOf(registeredHandler);
		if (handlerIndex === -1) return;
		handlers.splice(handlerIndex, 1);
		if (handlers.length === 0) extension.handlers.delete(event);
	};
},
```

语义要点：
1. 订阅表挂在**单个扩展**上（`extension.handlers`：event → handler 数组），注册序即触发序。
2. 返回退订函数：按 handler 实例移除；空表删键。同一 handler 重复注册两次则各火一次。
3. 41 个重载（types.ts:1569-1636）只是类型约束，运行时全走这一个 `on()`。

### 1.2 发射总线：runner.ts 的公共纪律

`snapshotEventHandlers`（runner.ts:269-271，逐字）——跨扩展的触发序＝扩展加载序 × 扩展内注册序：

```ts
function snapshotEventHandlers(extensions: Extension[], event: ExtensionEvent["type"]) {
	return extensions.map((ext) => ({ ext, handlers: ext.handlers.get(event)?.slice() ?? [] }));
}
```

`hasHandlers`（:765-773）：任一扩展对该事件有订阅即为真；pi 多处触发点先查它再决定是否构造载荷（如 sdk.ts:361、agent-session.ts:667）。

`emitError`（:742-746）：错误**不进事件流**，走独立的 `errorListeners` 集合：

```ts
emitError(error: ExtensionError): void {
	for (const listener of this.errorListeners) {
		listener(error);
	}
}
```

消费者：会话装配时 `runner.onError(bindings.onError)`（agent-session.ts:3341-3342）；RPC 模式把它写成线上事件（rpc-mode.ts:349，逐字）：

```ts
output({ type: "extension_error", extensionPath: err.extensionPath, event: err.event, error: err.error });
```

### 1.3 引擎钩子族：四个 emit 的逐字语义

#### 1.3.1 `emitToolCall`（runner.ts:1242-1260，逐字）——**不捕获异常**

```ts
async emitToolCall(event: ToolCallEvent): Promise<ToolCallEventResult | undefined> {
	const ctx = this.createContext();
	let result: ToolCallEventResult | undefined;

	for (const { handlers } of snapshotEventHandlers(this.extensions, "tool_call")) {
		for (const handler of handlers) {
			const handlerResult = await handler(event, ctx);

			if (handlerResult) {
				result = handlerResult as ToolCallEventResult;
				if (result.block) {
					return result;
				}
			}
		}
	}

	return result;
}
```

要点：① 处理器异常**直接传播**（全函数无 try/catch）——经 `_beforeToolCall`（agent-session.ts:668-680）重抛，由 agent-loop 转成工具错误结果（agent-loop.ts:668-673）；② `block` 短路，后续处理器不跑；③ 无 block 时**最后一个发言的处理器赢**；④ `event.input` 原地可变（types.ts:1224-1227 注释：*"Mutate it in place to patch tool arguments… Later `tool_call` handlers see earlier mutations"*）。

#### 1.3.2 `emitToolResult`（runner.ts:1183-1240）——逐字段合并＋structuredContent 丢弃规则

合并规则（逐字在 §1.3.1 上方代码块，此处归纳）：
- `content` 被替换而 `structuredContent` 未同换 ⇒ **丢弃** structuredContent（`delete currentEvent.structuredContent`）。
- `details`/`structuredContent`/`isError`/`usage` 各自独立合并（`!== undefined` 即覆盖）。
- 逐处理器 try/catch：异常 → `emitError` → **继续下一个**。
- 全程无修改 ⇒ 返回 `undefined`（调用方不动原结果）。

调用方 `_afterToolCall`（agent-session.ts:690-719）在钩子之后跑图片归一化，并用 `hookResult?.isError ?? isError` 定终值——java `PiToolRunner.execute`（:185-204）已是同构（含 B85 图片归一化段），**此处零改动**。

#### 1.3.3 `emitContext`（runner.ts:1298-1359）——两阶段

阶段一（`context` 事件，逐处理器）：
1. `visibleMessages = currentMessages.filter(m => m.role !== "system")`——处理器**看不到系统消息**。
2. 快照 `visibleSnapshot = visibleMessages.slice()`；处理器可返回 `{messages}` 或**原地改** `event.messages`。
3. `returned = handlerResult?.messages ?? (sameMessages(visible, snapshot) ? undefined : visible)`——`sameMessages` 是**逐元素 `===` 引用比较**（:273-275）。
4. 有 returned ⇒ `currentMessages = restoreSystemMessages(current, snapshot, returned)`。

`restoreSystemMessages`（:283-292，逐字）——会话未变则整条 `current` 原样保留（含会话中间的系统消息）；变了则重放头部系统消息：

```ts
function restoreSystemMessages(
	current: AgentMessage[],
	visible: AgentMessage[],
	returned: AgentMessage[],
): AgentMessage[] {
	if (sameMessages(returned, visible)) return current;
	const head = getCurrentSystemMessage(current);
	return head ? [head, ...returned] : returned;
}
```

阶段二（`context_with_system` 事件）：处理器看**全量**（含系统消息），返回即采用；若弄丢头部系统消息 → `emitError` 报告但**仍采用**（错误文本逐字：`"Handler removed the leading system message; the request has no prompt or initial tool declarations. Keep it at index 0 or replace a dropped prefix with getCurrentSystemMessage()."`）。

两阶段共用一次 `transformContext` 触发（sdk.ts:437-440）：`transformContext: async (messages) => runner.emitContext(messages)`，挂在 pi agent 包的 `transformContext` 钩子上（agent-loop.ts:390-391）。

#### 1.3.4 provider 请求面（本批**不做**，登记 B248）

pi 的三件套经 ai 层注入口 firing：`onPayload` → `emitBeforeProviderRequest`（sdk.ts:384-386）、`transformHeaders` → `emitBeforeProviderHeaders`（sdk.ts:356-363）、`onResponse` → `after_provider_response`（sdk.ts:388-395）。java 的 ai 模块**没有这三个注入口**（`SimpleOptions.java:45` 明文「fetch/onPayload/transport/metadata/env … 不移植：子系统缺席」），java 引擎的 `before_payload`/`before_navigation` 钩子**连 firing 点都没有**（全仓 grep 零命中）。这不是桥接能解决的，是 ai 层先开口子的事。

### 1.4 宿主事件族：`_emitExtensionEvent`（agent-session.ts:1284-1362）

触发模型（:1140 前后，逐字）：

```ts
// Emit to extensions first, then notify public listeners.
await this._emitExtensionEvent(event);
this._emit(event.type === "agent_end" ? { ...event, willRetry: this._willRetryAfterAgentEnd(event) } : event);
```

要点：
1. **扩展先于公开监听者**（TUI/RPC），且是 await 的。
2. 本批覆盖的翻译（:1286-1362）：`agent_start`（顺带 `_turnIndex = 0`）→ `{type:"agent_start"}`；`agent_end` → `{messages}`；`turn_start` → `{turnIndex, timestamp: Date.now()}`；`message_start`/`message_update`/`message_end` → 各带 `message`（update 另带 `assistantMessageEvent`；end 走 `emitMessageEnd` 有**替换臂**，本批不桥）；`tool_execution_start/update/end` → 字段直译（end 的 `durationMs` 可选键）；`turn_end` 分支做 boundary 派发并 `_turnIndex++`（:1298-1301，boundary 臂本批不桥，但 **turnIndex 递增语义本批要复制**）。
3. `agent_settled`（:1084）→ `{aborted}`；`session_compact`（:2858/:3188）→ `{compactionEntry, fromExtension, reason, willRetry}`；`session_compact_failed`（:1054-1056）→ `{reason, errorMessage?, aborted, willRetry, fromExtension}`。
4. `session_info_changed` / `thinking_level_select` 由各自 setter 发射；后者载荷 `{level, previousLevel}`（types.ts:1114-1118）。

### 1.5 本批 18 事件的载荷类型（types.ts 逐字要点）

| 事件 | 行 | 载荷（结果型除外） |
|---|---|---|
| `context` | :871 | `{messages: AgentMessage[]}` → 结果 `ContextEventResult{messages?}`（:1415） |
| `context_with_system` | :882 | 同上 |
| `tool_call` | :1165-1228 | 判别联合：`{toolCallId, parentToolCallId?, toolName, input}`（input 原地可变）→ `ToolCallEventResult{block?, reason?, terminate?}`（:1426） |
| `tool_result` | :1239-1316 | `{toolCallId, parentToolCallId?, input, content, structuredContent?, isError, usage?, toolName, details}` → `ToolResultEventResult{content?, details?, structuredContent?, isError?, usage?}`（:1455） |
| `agent_start` | :929 | `{}` |
| `agent_end` | :934 | `{messages}` |
| `agent_settled` | :956-960 族 | `{aborted}`（两锚点间新增字段） |
| `turn_start` | :1030 | `{turnIndex, timestamp}` |
| `message_start` | :1046 | `{message}` |
| `message_update` | :1052 | `{message, assistantMessageEvent}` |
| `message_end` | :1062 | `{message}`（结果 `MessageEndEventResult{message?}`＝替换臂，本批不桥） |
| `tool_execution_start` | :1067 | `{toolCallId, toolName, args, parentToolCallId?}` |
| `tool_execution_update` | :1076 | 上＋`partialResult` |
| `tool_execution_end` | :1087 | 上＋`result, isError, durationMs?` |
| `session_compact` | :787 | `{compactionEntry, fromExtension, reason, willRetry}` |
| `session_compact_failed` | :799 | `{reason, errorMessage?, aborted, willRetry, fromExtension}` |
| `session_info_changed` | :750 | `{name: string \| undefined}` |
| `thinking_level_select` | :1114 | `{level, previousLevel}` |

---

## §2 java 侧现状（证据）

### 2.1 订阅面：零

- `ExtensionContext`（extension/ExtensionContext.java:17-65）只有 tools/slashCommands/providers/skills/settings/ui/sendMessage，**无 `on()`**。
- `PiExtension`（extension/PiExtension.java:9-32）装配期一次性 `register(ctx)`，无退订概念——map/04 E5-35 判**缺失**。
- `DefaultExtensionContext` 是**全扩展共享单实例**（AgentSession.java:1563 只 `new` 一次），没有 per-extension 身份 ⇒ pi 的错误按 `extensionPath` 归属这一点，java 现在表达不了。

### 2.2 引擎钩子族：源在、未桥接；两个 firing 点是死的

agent-core `HookSystem`（agent-core/.../hook/HookSystem.java）13 钩，与本批相关的 firing 点：

| 钩子 | firing 点 | 状态 |
|---|---|---|
| `transform_context` | `ContextAssembler.java:180`（`PiLaneEngine.java:392` 喂 `List.copyOf(lane.messages)`） | **活** |
| `before_tool` / `after_tool` | `PiToolRunner.java:112` / `:191` | **活** |
| `before_compaction` | `CompactionExecutor.java:290` | 活（载荷薄，本批不桥，挂 B249） |
| `before_payload` / `before_navigation` | **全仓零 firing 点**（grep 证实） | **死**——B248/B245 的事 |
| `before_request`（pi 的 prepareRequest） | `PiLaneEngine.java:341` | 活，但它是「换 context/model/thinking」的钩子，**不是** pi 的 `before_provider_headers`——map/04 D-18 行的对应关系写错了，本批 D-18 归 B248 |

`HookSystem` 的错误纪律（:19-27 注释）：两工具钩子先记账再**向上重抛**（pi 同构），其余族非致命——与 §1.3 的 pi 语义天然吻合，桥只需遵同一纪律。

### 2.3 引擎侧的存量 bug：`applyAfterToolPatch` 洗掉 `isError`/`structuredContent`

`ToolResult` 是 8 组件 record（tool/ToolResult.java:27-34：`content, details, usage, terminate, addedToolNames, isError, structuredContent`），但 `HookSystem.applyAfterToolPatch`（:239-246）用 **5 参构造器**重建：

```java
return new ToolResult<>(
    p.content() != null ? p.content() : cur.content(),
    p.details() != null ? p.details() : cur.details(),
    p.usage() != null ? p.usage() : cur.usage(),
    p.terminate() != null ? p.terminate() : cur.terminate(),
    cur.addedToolNames());
```

5 参构造器把 `isError` 归 `false`、`structuredContent` 归 `null`（ToolResult.java:88 同形）⇒ **任何补丁**（哪怕只改 details）都会洗掉结果的 isError 字段与 structuredContent。pi 的丢弃规则只发生在「content 被替换且 structuredContent 未同换」（§1.3.2）。这是本批顺带要修的真 bug（`AfterToolOutcome.isError` 另走一路，所以此前没炸，但 record 自字段与 pi 结果对象语义不符）。

### 2.4 宿主事件族：源全在、有订阅口

- `AgentSessionEvent`（core/AgentSessionEvent.java）24 变体；本批要的 14 个**全部有生产者**（SessionRunner.java 11 处＋AgentSession.java 3 处，已逐个 grep 核实：AgentStart/TurnStart/TurnEnd/AgentEnd/AgentSettled/MessageStart/MessageUpdate/MessageEnd/ToolExecution×3/ThinkingLevelChanged:513/SessionInfoChanged:706/CompactionEnd）。
- 订阅口：`AgentSession.subscribe(Consumer)` → `AutoCloseable`（AgentSession.java:1148-1150），`RpcDispatcher` 已经这么订（rpc/RpcDispatcher.java:69 等 4 处）。
- `CompactionEnd(reason, result, aborted, willRetry, errorMessage)`（AgentSessionEvent.java:78-80）——`CompactionResult`（agent-core compaction 包：summary/firstKeptEntryId/tokens/usage/details）**不含 compactionEntry 条目本体** ⇒ `session_compact` 桥出来缺 `compactionEntry` 载荷，判**存疑**（窄差 D4）。
- `MessageUpdate(StreamEvent)`——`assistantMessageEvent` 直给；`message`（partial）需从 StreamEvent 提取，实现时验证（§7 验证任务 V1），提不出则该行改判存疑。
- `ThinkingLevelChanged(level)` 无 previousLevel、`TurnStart` 无 turnIndex——桥内状态跟踪补齐（§3.5）。
- `Message.SystemMessage`＋`Transcripts.getCurrentSystemMessage`（ContextAssembler.java:147 在用）——§1.3.3 的 restore 可逐字移植。

### 2.5 错误上报：无通道

`ExtensionManager.sessionStartResources()`（:93-97）的扩展异常只进 `java.util.logging`。没有 pi `emitError` → `extension_error` 线的等价物。

---

## §3 设计

### 3.1 事件 ADT：`extension/event/ExtensionEvent.java`（新）

pi 对位：types.ts:1377-1409 `ExtensionEvent` 联合＋§1.5 各载荷。

按 CLAUDE.md 编码规范（变体携带不同字段 ⇒ sealed interface + record）：

```java
/** pi ExtensionEvent 联合（types.ts:1377-1409）的本批子集——只收已有 firing 源的 18 个，
 *  其余随 B248/B249/B250/B245 逐批扩。 */
public sealed interface ExtensionEvent {

    // ── 引擎钩子族（结果型）──
    /** pi ContextEvent（types.ts:871）。list 原地可变（pi 同构，裁决④）。 */
    record ContextEvent(List<Message> messages) implements ExtensionEvent {}
    /** pi ContextWithSystemEvent（types.ts:882）。 */
    record ContextWithSystemEvent(List<Message> messages) implements ExtensionEvent {}
    /** pi ToolCallEvent（types.ts:1165-1228）。java 不做逐工具判别子类型——
     *  input 恒为 Record<String,Object> 形（窄差 D2）。 */
    record ToolCallEvent(String toolCallId, String toolName,
                         Map<String, Object> input) implements ExtensionEvent {}
    /** pi ToolResultEvent（types.ts:1239-1316）。 */
    record ToolResultEvent(String toolCallId, String toolName, Map<String, Object> input,
                           List<ContentBlock> content, Object details, Object structuredContent,
                           boolean isError, ToolResult.UsageInfo usage) implements ExtensionEvent {}

    // ── 宿主事件族（观察型）──
    record AgentStartEvent() implements ExtensionEvent {}
    record AgentEndEvent(List<Message> messages) implements ExtensionEvent {}
    record AgentSettledEvent(boolean aborted) implements ExtensionEvent {}
    record TurnStartEvent(int turnIndex, long timestamp) implements ExtensionEvent {}
    record MessageStartEvent(Message message) implements ExtensionEvent {}
    record MessageUpdateEvent(Message message, StreamEvent assistantMessageEvent) implements ExtensionEvent {}
    record MessageEndEvent(Message message) implements ExtensionEvent {}
    record ToolExecutionStartEvent(String toolCallId, String toolName, Map<String, Object> args) …
    record ToolExecutionUpdateEvent(…, Object partialResult) …
    record ToolExecutionEndEvent(…, Object result, boolean isError, Long durationMs) …
    record SessionCompactEvent(String reason, boolean willRetry, boolean fromExtension) …
    record SessionCompactFailedEvent(String reason, String errorMessage, boolean aborted,
                                     boolean willRetry, boolean fromExtension) …
    record SessionInfoChangedEvent(String name) implements ExtensionEvent {}  // null ≙ pi undefined
    record ThinkingLevelSelectEvent(ModelThinkingLevel level, ModelThinkingLevel previousLevel) …

    // ── 结果类型（pi types.ts:1415-1464）──
    record ContextEventResult(List<Message> messages) {}
    record ToolCallEventResult(Boolean block, String reason, Boolean terminate) {}
    record ToolResultEventResult(List<ContentBlock> content, Object details,
                                 Object structuredContent, Boolean isError, ToolResult.UsageInfo usage) {}
}
```

注意两点：
1. `SessionCompactEvent` 暂**无 `compactionEntry` 字段**（java 宿主事件给不出，§2.4）——加字段是 B249 级的事，先按窄差 D4 登记，事件行判存疑。
2. pi 的 `parentToolCallId` 属于嵌套工具调用（G-10，java 零对应）——本批事件不带该字段，嵌套落地时补。

### 3.2 订阅 API：spec-token 单方法（裁决①）

pi 对位：types.ts:1569-1636 的 41 个 `on()` 重载。

```java
/** 事件规格令牌：线名 + 事件/结果类型参数。常量随事件 ADT 逐批扩。 */
public final class ExtensionEventSpec<E extends ExtensionEvent, R> {
    public static final ExtensionEventSpec<ContextEvent, ContextEventResult> CONTEXT = new ("context");
    public static final ExtensionEventSpec<ContextWithSystemEvent, ContextEventResult> CONTEXT_WITH_SYSTEM = …;
    public static final ExtensionEventSpec<ToolCallEvent, ToolCallEventResult> TOOL_CALL = …;
    public static final ExtensionEventSpec<ToolResultEvent, ToolResultEventResult> TOOL_RESULT = …;
    /* 观察型 R = Void：*/ public static final ExtensionEventSpec<AgentStartEvent, Void> AGENT_START = …;
    …
}

@FunctionalInterface
public interface ExtensionEventHandler<E extends ExtensionEvent, R> {
    /** pi ExtensionHandler（event, ctx）=> result | undefined；返回 null ≙ undefined。 */
    R handle(E event, ExtensionContext ctx) throws Exception;
}
```

`ExtensionContext` 加一个方法：

```java
/** 订阅扩展事件（pi ExtensionAPI.on()）。返回退订闭包（loader.ts:272-288 语义）。 */
<E extends ExtensionEvent, R> AutoCloseable on(ExtensionEventSpec<E, R> spec,
                                               ExtensionEventHandler<E, R> handler);
```

### 3.3 `ExtensionEventBus`（新，extension/ExtensionEventBus.java）

pi 对位：loader.ts:272-288（订阅/退订）＋ runner.ts:269-271（序）＋:742-746（emitError）＋:765-773（hasHandlers）。

```java
public final class ExtensionEventBus {
    /** 订阅表：线名 → 注册序列表。条目 = (扩展名, spec, handler)。 */
    private final Map<String, List<RegisteredHandler>> handlers = new LinkedHashMap<>();
    private final List<Consumer<ExtensionError>> errorListeners = new CopyOnWriteArrayList<>();

    /** pi loader on()：注册序追加；退订按实例移除、空表删键。 */
    <E extends ExtensionEvent, R> AutoCloseable on(String extensionName,
            ExtensionEventSpec<E, R> spec, ExtensionEventHandler<E, R> handler);

    boolean hasHandlers(ExtensionEventSpec<?, ?> spec);           // pi hasHandlers
    List<RegisteredHandler> snapshot(ExtensionEventSpec<?, ?> spec); // pi snapshotEventHandlers
    void onError(Consumer<ExtensionError> listener);              // pi runner.onError
    void emitError(ExtensionError error);                         // pi runner.emitError
}

/** pi ExtensionError（extensionPath 在 java 是扩展名）。 */
public record ExtensionError(String extensionName, String event, String message, String stack) {}
```

per-extension 身份：`ExtensionManager.load()`（:102-105）改为 `ext.register(context.forExtension(ext.name()))`；`DefaultExtensionContext.forExtension` 返回委托实例，其 `on()` 把扩展名带给 bus——错误归属与 pi 的 `extensionPath` 对齐。

### 3.4 引擎钩子族桥：`extension/bridge/EngineHookBridge.java`（新）

装配期对 3 个引擎钩子各装**一个**适配器（裁决②：恒装＋空订阅快走，pi 的 Agent 选项同形）。适配器内部跑 bus 订阅者，pi runner 语义逐字。

#### 3.4.1 ContextAdapter（`transform_context` 钩）

pi 对位：runner.ts:1298-1359（§1.3.3）。java 的触发点 `ContextAssembler.transformContext`（:179-182）给的列表**可含 `Message.SystemMessage`**（loadout 补丁，§2.4）⇒ pi 的过滤/恢复有真实对象可作用。

```java
List<Message> transformContext(List<Message> messages) {
    if (!bus.hasHandlers(CONTEXT) && !bus.hasHandlers(CONTEXT_WITH_SYSTEM)) return null; // 不改
    var current = new ArrayList<>(messages);
    // 阶段一：context（pi :1310-1336）
    for (var h : bus.snapshot(CONTEXT)) {
        try {
            var visible = current.stream().filter(m -> !(m instanceof Message.SystemMessage)).collect(toCollection(ArrayList::new));
            var snapshot = new ArrayList<>(visible);                 // pi visibleSnapshot
            var result = h.handle(new ContextEvent(visible), ctx);   // 原地改或返回
            List<Message> returned = result != null && result.messages() != null ? result.messages()
                : sameByReference(visible, snapshot) ? null : visible;
            if (returned != null) current = restoreSystemMessages(current, snapshot, returned);
        } catch (Exception e) { bus.emitError(h.error("context", e)); }
    }
    // 阶段二：context_with_system（pi :1338-1357）
    for (var h : bus.snapshot(CONTEXT_WITH_SYSTEM)) {
        try {
            boolean hadLeading = !current.isEmpty() && current.get(0) instanceof Message.SystemMessage;
            var result = h.handle(new ContextWithSystemEvent(current), ctx);
            if (result != null && result.messages() != null) current = new ArrayList<>(result.messages());
            if (hadLeading && (current.isEmpty() || !(current.get(0) instanceof Message.SystemMessage))) {
                bus.emitError(h.error("context_with_system",
                    "Handler removed the leading system message; the request has no prompt or initial "
                  + "tool declarations. Keep it at index 0 or replace a dropped prefix with "
                  + "getCurrentSystemMessage()."));                  // pi 逐字
            }
        } catch (Exception e) { bus.emitError(h.error("context_with_system", e)); }
    }
    return current;
}
```

- `sameByReference`：逐元素 `==`（pi `sameMessages` 的 `===` 同构，裁决④——java record 的 `equals` 会把「重建的同值对象」误判为未变，跳过 restore，与 pi 不符）。
- `restoreSystemMessages`：逐字移植 §1.3.3（头系统取自 `Transcripts.getCurrentSystemMessage`）。
- pi 的 `structuredClone` 不需要：java `Message` 是不可变 record（裁决④附注）。

#### 3.4.2 ToolCallAdapter（`before_tool` 钩）

pi 对位：runner.ts:1242-1260（§1.3.1）。

```java
BeforeToolResult beforeTool(ToolCallContext ctx) {
    if (!bus.hasHandlers(TOOL_CALL)) return null;
    var input = new LinkedHashMap<>(ctx.arguments());      // 原地可变链（pi event.input）
    ToolCallEventResult result = null;
    for (var h : bus.snapshot(TOOL_CALL)) {
        var r = h.handle(new ToolCallEvent(ctx.toolCallId(), ctx.toolName(), input), extCtx);
        // ↑ 异常不捕获（pi 逐字）——经 HookSystem.fireBeforeTool 记账重抛，
        //   PiToolRunner.prepare 的 catch 转工具错误结果（pi agent-loop.ts:668-673 同链）
        if (r != null) {
            result = r;
            if (Boolean.TRUE.equals(r.block())) {
                return new BeforeToolResult(false, Map.of("reason",
                    r.reason() != null ? r.reason() : ""), Boolean.TRUE.equals(r.terminate()));
            }
        }
    }
    return inputChanged ? new BeforeToolResult(true, input) : null;
}
```

#### 3.4.3 ToolResultAdapter（`after_tool` 钩）

pi 对位：runner.ts:1183-1240（§1.3.2）。两处引擎扩形先行：

1. **`ToolResultContext` 加 `arguments` 字段**（pi `ToolResultEventBase.input`，types.ts:1247）——`PiToolRunner.java:191` 构造点传 `state.args()`（改写后参数，pi 的 `prepared.args`）。
2. **`AfterToolPatch` 加 `structuredContent`＋`dropStructuredContent`**（§3.4.4）。

```java
AfterToolPatch afterTool(ToolResultContext ctx) {
    if (!bus.hasHandlers(TOOL_RESULT)) return null;
    // 局部跑 pi 的合并状态机（§1.3.2 逐字）：content 换而 structured 未同换 ⇒ 丢弃；
    // 逐处理器 catch → emitError 后继续；全程无改 ⇒ null。
    …
    return new AfterToolPatch(content, details, structured, /*dropStructured*/ drop, null, isError);
}
```

#### 3.4.4 引擎修真：`applyAfterToolPatch`（§2.3 的存量 bug）

```java
private static ToolResult<Object> applyAfterToolPatch(ToolResult<?> cur, AfterToolPatch p) {
    return new ToolResult<>(
        p.content() != null ? p.content() : cur.content(),
        p.details() != null ? p.details() : cur.details(),
        p.usage() != null ? p.usage() : cur.usage(),
        p.terminate() != null ? p.terminate() : cur.terminate(),
        cur.addedToolNames(),
        cur.isError(),                                    // 修真：不再被补丁洗成 false
        p.dropStructuredContent() ? null
            : p.structuredContent() != null ? p.structuredContent()
            : cur.structuredContent());                   // 修真＋pi 丢弃规则
}
```

`AfterToolPatch` 现有 5 组件构造点全仓 grep 改签（compact 构造器兜底旧形状，尽量零改签）。

### 3.5 宿主事件族桥：`extension/bridge/SessionEventBridge.java`（新）

pi 对位：agent-session.ts:1284-1362（§1.4）。装配期订阅 `AgentSession.subscribe`（裁决⑤：此时模式层 TUI/RPC 尚未订阅，与 pi「扩展先」同向）。全部观察语义：逐处理器 try/catch → `emitError` → 继续。

翻译表（宿主事件 → 扩展事件；⭐＝w3）：

| 宿主（AgentSessionEvent） | 扩展事件 | 备注 |
|---|---|---|
| `AgentStart` | `agent_start` | 顺带 `turnIndex = 0`（pi :1286） |
| `TurnStart` | `turn_start` | `{turnIndex, timestamp=currentTimeMillis()}` |
| `TurnEnd` | —（不发射） | `turnIndex++`（pi :1301）；boundary 臂挂 B249 |
| `AgentEnd(messages,…)` | `agent_end` | `{messages}` |
| `AgentSettled(aborted)` | `agent_settled` | pi :1084 |
| `MessageStart(m)` | `message_start` | |
| `MessageUpdate(se)` ⭐ | `message_update` | `message` 从 StreamEvent 提取（验证任务 V1） |
| `MessageEnd(m)` | `message_end` | 观察臂；替换臂挂 B249 ⇒ 该行判**存疑** |
| `ToolExecutionStart/Update/End` | 同名 | 字段直译；`durationMs` 可空 |
| `ThinkingLevelChanged(l)` | `thinking_level_select` | `previousLevel` 桥内跟踪，种子＝装配时当前等级 |
| `SessionInfoChanged(name)` | `session_info_changed` | null ≙ pi undefined |
| `CompactionEnd`（成功） | `session_compact` | `{reason, willRetry, fromExtension:false}`；**无 compactionEntry**（窄差 D4 ⇒ 存疑） |
| `CompactionEnd`（失败/中止） | `session_compact_failed` | `{reason, errorMessage, aborted, willRetry, fromExtension:false}`——成功判定＝`!aborted && errorMessage==null` |
| 其余 10 变体 | — | 无对应扩展事件，不映射 |

### 3.6 接线＋错误通道

`AgentSession.loadExtensions`（:1556-1576）改写：

```java
var bus = new ExtensionEventBus();
var context = new DefaultExtensionContext(services, harness.skillManager(), ui, bus);
context.bindSession(() -> owner.session());
var manager = new ExtensionManager(context);
manager.loadAll(); /* JAR 同前 */
EngineHookBridge.install(harness.hookSystem(), owner.laneName(), bus, context);
owner.subscribe(new SessionEventBridge(bus, context, owner)::onEvent);   // 裁决⑤
bus.onError(err -> owner.emitSessionEvent(new AgentSessionEvent.ExtensionError(err)));
applySessionStartResources(...);  // 照旧
```

错误通道（裁决⑥）：`AgentSessionEvent` 加变体

```java
/** pi extension_error（rpc-mode.ts:349）。extensionPath 在 java 是扩展名。 */
record ExtensionError(com.pijava.coding.agent.extension.ExtensionError error) implements AgentSessionEvent {}
```

- `JsonEventMapper` 加 case，线形逐字：`{type:"extension_error", extensionPath, event, error}`（stack 不上线，pi 同）。
- `ChatScreen` 的 switch 补 case（本批先忽略——TUI 通知面是 D′ 域的事，窄差 D5）。
- 测试夹具 `SessionRunnerFunnelTest` 的 switch 补 case。
- `--no-extensions`：整段不建（pi 无扩展＝runner 空表，行为等价）。

---

## §4 裁决

**裁决①：订阅 API 用 spec-token 单方法**
- 【场景】pi 是 41 个 `on()` 重载。java 照搬要 41 个 default 方法，事件集每扩一批就改 `ExtensionContext` 接口；用裸字符串则丢类型安全。
- 【决定】`on(ExtensionEventSpec<E,R> spec, ExtensionEventHandler<E,R> handler)` 单方法；spec 常量是类型令牌，扩事件不动接口。
- 【风险/代价】调用点比 pi 多一个 token 参数（`on(TOOL_CALL, h)` vs `on("tool_call", h)`）。[内部，模型不可见]

**裁决②：引擎适配器装配期恒装、空订阅快走**
- 【场景】可以「首个订阅者出现时再装引擎钩子」（lazy），也可以恒装。
- 【决定】恒装。pi 的 Agent 选项（transformContext 等）就是恒装、emit 内部空循环；恒装让「订阅晚于 firing 点建立」的时序类 bug 不存在。
- 【风险/代价】无扩展订阅时每轮多一次空表查询，可忽略。[内部]

**裁决③：`tool_call` 处理器异常向上传播，其余族逐处理器捕获**
- 【场景】pi `emitToolCall` 全函数无 try/catch（异常→工具错误结果），而 `emitToolResult`/`emit`/`emitContext` 都是逐处理器捕获后继续。
- 【决定】按族照抄：`tool_call` 桥不捕获（走 `HookSystem` 记账重抛 → `PiToolRunner` 转错误结果的既有链）；其余桥逐处理器 catch → `emitError` → 继续。
- 【风险/代价】扩展 `tool_call` 抛错会把该次工具调用变成错误结果——这是 pi 的既定行为，不是事故。[模型可见：错误文本进工具结果]

**裁决④：`context` 事件原地可变列表＋引用相等快照**
- 【场景】pi 处理器可返回新列表、也可原地改 `event.messages`；判定「改没改」用逐元素 `===`。
- 【决定】`ContextEvent.messages()` 给活的可变 `ArrayList`；快照比对用 `==`（不用 record `equals`——同值重建对象在 pi 算「变了」要触发 restore，java 用 equals 会漏）。
- 【风险/代价】java 里可变列表是不寻常形状，文档注释钉死缘由；pi 的 `structuredClone` 因 java Message 不可变而省略。[内部]

**裁决⑤：桥在装配期订阅 eventHub，先于模式层**
- 【场景】pi 是「扩展事件 await 完才通知公开监听者」（:1140）。
- 【决定】`loadExtensions` 内订阅（TUI/RPC 的 `session.subscribe` 发生在会话构造之后）⇒ 序与 pi 同向。
- 【风险/代价】java eventHub 是同步广播，无 await 语义；观察事件无持久化后果，序差仅存于「同一事件内扩展与 UI 的先后」——已对齐。message_end 替换臂才有持久化后果，挂 B249。[内部]

**裁决⑥：错误通道走 `AgentSessionEvent.ExtensionError` 新变体**
- 【场景】pi 的 `emitError` 不进事件流，走独立 listener；RPC 写成 `extension_error` 线。
- 【决定】bus 暴露 `onError`；`AgentSession` 把它桥成新的会话事件变体——RPC 经既有 `session.subscribe` 通道上线（线形逐字 pi），TUI 后续可同管道消费。
- 【风险/代价】sealed 接口加变体，全仓 3 个 switch 点要补 case（JsonEventMapper/ChatScreen/一测试）。[RPC 线可见；TUI 暂不展示 ⇒ 窄差 D5]

**裁决⑦：`session_compact` 先桥后补载荷**
- 【场景】java `CompactionEnd` 给不出 `compactionEntry`（CompactionResult 无条目本体）。
- 【决定】本批桥 `{reason, willRetry, fromExtension:false}`，行判存疑、窄差 D4 登记；补载荷挂 B249（需 CompactionExecutor 把条目本体带进事件）。
- 【风险/代价】扩展处理器读不到 compactionEntry。[边缘场景]

---

## §5 窄差表（本批新增）

| # | 内容 | 等级 |
|---|---|---|
| D1 | `context` 快照用引用相等（pi `===` 同构）；java 无 `structuredClone`（Message 不可变，无需） | 无影响 |
| D2 | `tool_call`/`tool_result` 事件不做 pi 的逐工具判别子类型（`BashToolCallEvent` 等 9 支），`input` 恒 map 形 | [内部] |
| D3 | `message_update` 的 `message` 提取自 StreamEvent partial（实现验证 V1；提不出则该行降存疑再登记） | 边缘场景 |
| D4 | `session_compact` 无 `compactionEntry` 载荷（CompactionResult 无条目本体） ⇒ 该行判存疑，挂 B249 | [模型可见：扩展读不到该字段] |
| D5 | `extension_error` TUI 暂不展示（RPC 线已逐字）；TUI 通知面归 D′ 域 | [用户可见，边缘] |
| D6 | `fromExtension` 恒 false——java 无扩展触发压缩的路径（`sendMessage(triggerTurn)` 未实现），与当前全部可达行为一致 | 无影响 |
| D7 | pi 的 `parentToolCallId` 不带（嵌套工具调用 G-10 零对应，落地时补） | 存量约束，非本批范围 |

## §6 新登记开放项（闭环时入台账 docs/04）

| 号 | 内容 |
|---|---|
| **B248** | provider 请求面注入口：`before_provider_request`/`before_provider_headers`/`after_provider_response`/`provider_stream_event`——ai 模块 `onPayload`/`transformHeaders`/`onResponse`/`onProviderStreamEvent` 子系统缺席（SimpleOptions.java:45），引擎 `before_payload` 钩子 firing 点也是死的；map/04 D-17/18/19/20 四行挂此 |
| **B249** | 扩展事件结果臂族：`message_end` 替换（agent-session.ts:1316-1333）、`turn_end`/`agent_before_settle` boundary（emitBoundary, runner.ts:1029）、`input`、`user_bash`、`before_agent_start`（systemPrompt 改写）、`session_before_switch`/`session_before_fork` cancel、`session_before_compact` cancel＋全载荷＋compactionEntry（D4） |
| **B250** | 会话生命周期发射点：`session_start`（reason 五级映射＋previousSessionFile）、`session_shutdown`——java 有装配/关闭点但无事件发射 |

（`session_before_tree`/`session_tree`＝**B245** 在册；`mcp_servers_change` 挂 B221；`project_trust`/`ui_prompt_*`/`model_select`/`cache_warming_decision` 各随子系统。）

## §7 测试计划

pi 侧参照：扩展事件无独立测试文件可整体移植（行为规格在 `examples/` 的 105 个 `.ts`，E5-33 整块缺失在册）——本批测试全为 java 新写，语义锚 §1 逐字段。

1. **Bus**：注册序触发；退订按实例移除、空表删键（pi :278-286 逐字情形）；重复注册各火一次；`hasHandlers`；错误按扩展名归属（两个扩展各订同名事件，一个抛错，错误对象 extensionName 正确、另一个照跑）。
2. **ContextAdapter**：① 阶段一系统消息过滤（SystemMessage 不出现在 event.messages）；② 会话被改 ⇒ 头系统重放＋中间系统消息丢（pi restore 语义）；③ 会话未改（引用相等）⇒ `current` 原样保留（含中间系统消息）；④ 原地改列表被检出（`==` 快照）；⑤ 阶段二弄丢头系统 ⇒ emitError 报告**且仍采用**（错误文本逐字断言）；⑥ 处理器抛错 ⇒ 上报＋后续处理器照跑。
3. **ToolCallAdapter**（harness 集成）：block ⇒ 工具不执行、错误结果带 reason、terminate 透传；block 短路后续处理器；input 原地改 ⇒ 后续处理器看到、且执行拿到改后参数；处理器抛错 ⇒ 工具错误结果（pi 链）。
4. **ToolResultAdapter**：content 换而 structured 未同换 ⇒ structured 被丢；details-only 补丁 ⇒ **isError/structuredContent 保留**（§2.3 修真夹具）；isError 翻转；无修改 ⇒ 原结果同一引用；处理器抛错 ⇒ 上报＋继续。
5. **SessionEventBridge**：turnIndex 复位/递增；previousLevel 跟踪（种子→首迁）；CompactionEnd 成功/失败两支分发；`message_update` 载荷；14 事件逐个「宿主进 ⇒ 扩展出」对拍。
6. **RPC**：`extension_error` 线形 `{type, extensionPath, event, error}` 四键逐字。
7. **装配**：`--no-extensions` ⇒ 零 firing；两个扩展（ServiceLoader 夹具）跨扩展序＝加载序。

**验证任务 V1**（实现期先做）：`MessageUpdate(StreamEvent)` 的 partial assistant message 提取路径——提取不到则 D-31 改判存疑、D3 登记实差。

## §8 对账表（map/04 逐行翻转预期）

D 域（基线：对齐 1／存疑 8〔逐行实算；汇总行写 7，已知不符〕／缺失 32）：

| 行 | 权重 | 今 | 后 | Δ(w×c) |
|---|---:|---|---|---:|
| D-4 session_info_changed | 1 | 缺失 | **对齐**（生产者已有，:706；map 行的「B30 零生产者」已过期） | +1.0 |
| D-7 session_before_compact | 2 | 存疑 | 存疑（挂 B249） | 0 |
| D-8 session_compact | 2 | 缺失 | **存疑**（D4） | +1.0 |
| D-9 session_compact_failed | 1 | 缺失 | **对齐** | +1.0 |
| D-12/13 session_before_tree/session_tree | 1+1 | 存疑/缺失 | 不动（B245） | 0 |
| D-14 context ⭐ | 3 | 存疑 | **对齐** | +1.5 |
| D-15 context_with_system ⭐ | 3 | 缺失 | **对齐** | +3.0 |
| D-17/18/19/20 provider 四面 | 3+2+2+2 | 存疑×3/缺失 | 不动（B248） | 0 |
| D-22 agent_start | 2 | 缺失 | **对齐** | +2.0 |
| D-23 agent_end | 2 | 缺失 | **对齐** | +2.0 |
| D-25 agent_settled | 2 | 缺失 | **对齐** | +2.0 |
| D-28 turn_start | 2 | 缺失 | **对齐** | +2.0 |
| D-29 turn_end | 2 | 缺失 | 缺失（B249） | 0 |
| D-30 message_start | 2 | 缺失 | **对齐** | +2.0 |
| D-31 message_update ⭐ | 3 | 缺失 | **对齐**（V1 不过则存疑 +1.5） | +3.0 |
| D-32 message_end | 2 | 缺失 | **存疑**（替换臂 B249） | +1.0 |
| D-33/34/35 tool_execution×3 | 2×3 | 缺失 | **对齐** | +6.0 |
| D-37 thinking_level_select | 2 | 缺失 | **对齐** | +2.0 |
| D-38 tool_call ⭐ | 3 | 存疑 | **对齐** | +1.5 |
| D-39 tool_result ⭐ | 3 | 存疑 | **对齐** | +1.5 |

D 域 Δ＝**+30.5**；E5-35（`on()` 退订，w2）缺失→**对齐** Δ＝+2.0。
模块合计：226.0 → **258.5/496 ≈ 52.1%**（D 域 10.5→41.0/83；若按逐行实算基线 11.5 则 42.0/83＝50.6%——闭环按真实基线重算）。翻转后 D 域：对齐 17／存疑 7（#7/#8/#12/#17/#18/#19/#32）／缺失 17。

## §9 不碰清单

D′ 扩展 UI 注入面（9 行）；E5 其余 30 行（含 E5-12 triggerTurn、E5-31/32 UI 通道）；E6 包管理器；`session_start`/`session_shutdown` 发射（B250）；provider 面（B248）；结果臂（B249）；tree 两事件（B245）；`AgentSession.java` 拆分（E1，1,605 行在册——本批只动 `loadExtensions` 一段，不拆分）；`RpcDispatcher.java` 拆分（E2，661 行在册）。
