# coding-agent 批 5 设计稿：结果臂事件族＋会话生命周期发射点（B249＋B250）

> **状态：✅ 已闭环（2026-10-20，用户授意免人工审核：设计稿落地后直接实施、当日闭环）**。
> 基线：pi `ea448f454`（2026-10-11）；pi-java main（coding-agent 批 4 闭环后，`849d154f`）。
> 所有 pi 侧 `file:line` 均经 `git -C D:/workplaceForai/pi show ea448f454:<path>` 实读，未读 pi 工作树。
> 前批：`14-coding-agent-batch4-design.md`（已闭环，D 域地基：订阅面＋双源桥接）。
>
> **闭环 commits**：`bc71e909`（本稿）→ `b5e76e93`（T1 引擎扩形：message_end/before_settle 两新钩、finishTurn 载荷修真、before_compaction 三臂、compactionEntry 穿透）→ `2b7ff003`（T2 事件 ADT＋12 spec）→ `d7c8d6cf`（T3 `SessionEventFirer`＋生命周期/结果臂接线＋调用点改签）→ `4db99998`（T4 `BoundaryBridge`＋messageEnd/beforeCompaction 适配器）＋ docs 回填（本稿＋map/04＋docs/04＋docs/05＋spotbugs 排除）。
> **验证**：新测试 35 例全绿（`Batch15EngineHooksTest` 8＋`SessionEventFirerTest` 6＋`SessionLifecycleEventsTest` 10＋`Batch15BridgeAdaptersTest` 10＋`SessionEventBridgeTest` 改 1——message_end 无双发断言）；agent-core 852/852、coding-agent 758/758、tui 219、web 56 绿（`-am` 全 reactor ai 1,716 同绿）；全量 `mvn clean verify` BUILD SUCCESS（`[ERROR]` 0 条、无 `System.out.println` 残留；`RichSessionListTest` 存量红排除见偏差⑨）。
> **实施偏差**（均不改变裁决；台账/map 同载）：① §3.2.3 before_settle firing 点从「checkAfterRun 内部」改为 **drive 循环**（pi :1839 同位更准——设计稿内已修订）；② `ShouldStopAfterTurnContext` 修真伴随**组件类型变更**（顶层 AssistantMessage→`Message.AssistantMessage` ADT、toolResults 收紧 `List<Message.ToolResultMessage>`）——旧喂的 `lane.partial` 本就是错型错值（流中快照），修真一并正型；③ `ExtensionManager.loadAll` 改 TCCL（生产 TCCL＝app loader 不变；端到端夹具注入作用域 loader 所迫，设计稿未载）；④ 「Invalid boundary entries」防御臂无夹具（java 四型草稿装配构造不出失败路径，语义位置保留）；⑤ `switchTo` 的 session_start 在**新侧** firer 发射（内存路 found 是另一对象；持久路原地 attach 同一对象——设计稿 §3.7 未细分，实施期修正）；⑥ 夹具发现「处理器每轮 continue=true 且每轮排队」会无限续跑——pi 同形（非 java 方言），夹具定为首轮 continue＋次轮弃权；⑦ message_end 对 loadout 系统消息同样发射（sink 过所有定案消息——pi 对每个 AgentMessage 火 message_end，一致，补记在案）；⑧ spotbugs `NP_BOOLEAN_RETURN_NULL` 排除＋1（`BoundaryBridge.beforeSettle` 三态返回，B144 同族）；⑨ `RichSessionListTest` 存量红（干净 main 复现、时序敏感、非本批引入）——全量 verify 以 `-Dtest='!RichSessionListTest'` 排除；⑩ `SessionCompactEvent` 组件序与 pi 不同（compactionEntry 追加在末——进程内对象，线格式不涉及）；⑪ E1 涨到 **1,841** 行（+221）。

| 项 | 内容 |
|---|---|
| 批次 | coding-agent 批 5（docs/15）＝台账 **B249**（结果臂事件族）＋**B250**（会话生命周期发射点）合包；pi 锚点 `ea448f454`；判定权威＝map/04 逐行 |
| 重件 | boundary 派发子系统（`turn_end`/`agent_before_settle`：草稿四型＋上下文预览＋continue 语义，§3.5）；`session_before_compact` cancel＋扩展压缩产物臂＋`session_compact` 补 `compactionEntry`（§3.6）；`message_end` 替换臂（§3.4） |
| 中件 | 会话生命周期：`session_start`（reason 五级＋previousSessionFile）/`session_shutdown`（§3.7）；`input`/`user_bash`/`before_agent_start` 三宿主臂（§3.3）；`session_before_switch`/`session_before_fork` cancel（§3.8） |
| 不碰 | provider 请求面四件（**B248**，ai 层注入口缺席，等 ai 包）；`session_before_tree`/`session_tree`（**B245**）；reload 臂（**B246**，java 无 reload 面）；D′ 扩展 UI 注入面 |
| 一句话 | 批 4 把「观察面」桥完了（扩展能订、能看）；本批把「结果面」补上——扩展能改写（message_end 替换 / input 变换 / before_agent_start 改 systemPrompt / boundary 注入条目与续跑）、能否决（before_switch/fork/compact cancel）、能接管（user_bash 替换执行 / before_compact 自带压缩产物），并把会话生命周期的两个发射点（start/shutdown）按 pi 的 reason 语义点着。 |

预期账面（对账表见 §8）：D 域 12 行翻转（8 缺失→对齐、4 存疑→对齐），逐行 Δ＝**+22.0**；D 域 44.0→**66.0/83＝79.5%**；模块 261.5→**283.5/496≈57.2%**。

---

## §1 pi 侧事实（取证，锚点 `ea448f454`，只走 `git show`）

### 1.1 结果臂总线：六个 emit 的逐字语义

#### 1.1.1 `emitMessageEnd`（runner.ts:1144-1181，逐字）——链式替换＋role 校验

```ts
async emitMessageEnd(event: MessageEndEvent): Promise<AgentMessage | undefined> {
	const ctx = this.createContext();
	let currentMessage = event.message;
	let modified = false;

	for (const { ext, handlers } of snapshotEventHandlers(this.extensions, "message_end")) {
		for (const handler of handlers) {
			try {
				const currentEvent: MessageEndEvent = { ...event, message: currentMessage };
				const handlerResult = (await handler(currentEvent, ctx)) as MessageEndEventResult | undefined;
				if (!handlerResult?.message) continue;

				if (handlerResult.message.role !== currentMessage.role) {
					this.emitError({
						extensionPath: ext.path,
						event: "message_end",
						error: "message_end handlers must return a message with the same role",
					});
					continue;
				}

				currentMessage = handlerResult.message;
				modified = true;
			} catch (err) { /* emitError + 继续 */ }
		}
	}

	return modified ? currentMessage : undefined;
}
```

要点：① 逐处理器链式——后续处理器看到前面的替换结果；② role 不同 ⇒ emitError（文本逐字）＋跳过该替换；③ 全程无替换 ⇒ `undefined`；④ 逐处理器 catch ⇒ emitError ⇒ 继续。

消费点（agent-session.ts:1316-1332，逐字）——**替换发生在持久化之前**：

```ts
} else if (event.type === "message_end") {
	const extensionEvent: MessageEndEvent = { type: "message_end", message: event.message };
	const replacement = await this._extensionRunner.emitMessageEnd(extensionEvent);
	if (replacement) {
		// Untyped extension handlers can return messages with null/missing content;
		// normalize so it never enters agent state or session history.
		const normalized =
			(replacement.role === "user" || ... ) && replacement.content == null
				? ({ ...replacement, content: [] } as AgentMessage)
				: replacement;
		this._replaceMessageInPlace(event.message, normalized);
	}
}
```

`_replaceMessageInPlace`（:1267-1280，逐字要点）：agent-core 在发射 message_end **之前**已把定案消息对象存进 state；SessionManager 持久化发生在之后、用的同一个对象引用 ⇒ **原地改写**（删键＋Object.assign）让 agent state、后续事件、监听者、最终持久化全看到替换。注释逐字：*"Mutating this object in place keeps agent state, later turn/agent events, listeners, and the eventual SessionManager.appendMessage(event.message) persistence in sync."*

#### 1.1.2 `emitInput`（runner.ts:1520-1558，逐字）——变换链＋handled 短路

```ts
async emitInput(text, images, source, streamingBehavior): Promise<InputEventResult> {
	const ctx = this.createContext();
	let currentText = text;
	let currentImages = images;

	for (const { ext, handlers } of snapshotEventHandlers(this.extensions, "input")) {
		for (const handler of handlers) {
			try {
				const event: InputEvent = { type: "input", text: currentText, images: currentImages, source, streamingBehavior };
				const result = (await handler(event, ctx)) as InputEventResult | undefined;
				if (result?.action === "handled") return result;
				if (result?.action === "transform") {
					currentText = result.text;
					currentImages = result.images ?? currentImages;
				}
			} catch (err) { /* emitError + 继续 */ }
		}
	}
	return currentText !== text || currentImages !== images
		? { action: "transform", text: currentText, images: currentImages }
		: { action: "continue" };
}
```

#### 1.1.3 `emitUserBash`（runner.ts:1262-1296，逐字）——首个发言者赢＋异常重抛

```ts
async emitUserBash(event: UserBashEvent): Promise<UserBashEventResult | undefined> {
	const ctx = this.createContext();
	for (const { ext, handlers } of snapshotEventHandlers(this.extensions, "user_bash")) {
		for (const handler of handlers) {
			try {
				const handlerResult = await handler(event, ctx);
				if (handlerResult === undefined) continue;
				if (!isUserBashEventResult(handlerResult)) {
					throw new Error("Invalid user_bash handler result: ...");
				}
				return handlerResult;
			} catch (err) {
				this.emitError({...});
				throw err;   // ← 上报后重抛：调用方不得回落本地执行
			}
		}
	}
	return undefined;
}
```

消费点两处。**interactive-mode.ts:6987-7000**：`try { eventResult = await emitUserBash(...) } catch { return; }`——注释逐字 *"The extension runner already reported the error. Do not fall back to local execution."*；`eventResult?.result` ⇒ 直接展示＋`recordBashResult`；否则本地执行（可带 `eventResult?.operations`）。**rpc-mode.ts:561-580**：同形但**无 try/catch**——异常沿 RPC 错误链走（pi 自身的模式间不对称，见窄差）。

#### 1.1.4 `emitBeforeAgentStart`（runner.ts:1420-1472，逐字要点）

- 事件载荷：`{type, prompt, images, get systemPrompt()（惰性渲染当前 options）, systemPromptOptions（可变）}`；ctx 上挂 `getSystemPrompt()`。
- 结果消费：`result.message` ⇒ 攒进 `messages[]`（多个处理器可各追加一条）；`result.systemPrompt !== undefined` ⇒ `currentOptions.forceSystemPrompt = result.systemPrompt`（后续处理器看到的 `systemPrompt` 已是改写值）。
- 逐处理器 catch ⇒ emitError ⇒ 继续。返回 `{messages, systemPromptOptions}`。

消费点（agent-session.ts:2061-2110）：selectedTools 被显式编辑则以编辑为准、否则以活动工具表为准；`result.messages` 逐条转 `custom` 角色消息插进本轮（content 空则归一 `[]`）；`_preparePromptAndToolLoadout(result.systemPromptOptions)` 产出 loadout 更新消息。

#### 1.1.5 通用 `emit` 的 cancel 短路（runner.ts:1080-1118，逐字要点）

`isSessionBeforeEvent`（:1082-1087）＝`session_before_switch`/`session_before_fork`/`session_before_compact`/`session_before_tree` 四件。通用 `emit` 对这四件：处理器结果非空 ⇒ 记为 result，`result.cancel` 为真 ⇒ **立即返回**（后续处理器不跑）；无 cancel ⇒ 最后发言者赢。逐处理器 catch ⇒ emitError ⇒ 继续。

#### 1.1.6 `emitBoundary`（runner.ts:1029-1078，逐字）

```ts
async emitBoundary(baseEvent, buildContext): Promise<BoundaryDispatchResult> {
	const ctx = this.createContext();
	let entries: SessionBoundaryDraft[] = [];
	let shouldContinue = false;
	let context = await buildContext(entries);
	let valid = true;

	for (const { ext, handlers } of snapshotEventHandlers(this.extensions, baseEvent.type)) {
		for (const handler of handlers) {
			const event = { ...baseEvent, entries, continue: shouldContinue, context } as TurnEndEvent | AgentBeforeSettleEvent;
			try {
				const handlerResult = (await handler(event, ctx)) as BoundaryResult | undefined;
				if (handlerResult?.entries !== undefined) entries = handlerResult.entries;
				if (handlerResult?.continue !== undefined) shouldContinue = handlerResult.continue;
			} catch (err) { this.emitError({...}); }

			try {
				context = await buildContext(entries);   // ← 每个处理器之后都重建预览
				valid = true;
			} catch (err) {
				valid = false;
				this.emitError({ extensionPath: ext.path, event: baseEvent.type,
					error: `Invalid boundary entries: ${err instanceof Error ? err.message : String(err)}`, ...});
			}
		}
	}

	return valid
		? { entries, continue: shouldContinue, context, valid: true }
		: { entries: [], continue: false, context, valid: false };
}
```

要点：① `entries`/`continue` 链式累积（后者覆盖前者）；② **每个处理器之后**都用最新 entries 重建 context 预览，构建失败 ⇒ emitError（`"Invalid boundary entries: ..."`）＋`valid=false`；③ 无效 ⇒ 返回空 entries＋continue=false。

#### 1.1.7 `emitSessionShutdownEvent`（runner.ts:255-267，逐字）

```ts
export async function emitSessionShutdownEvent(
	extensionRunner: ExtensionRunner,
	event: SessionShutdownEvent,
): Promise<boolean> {
	if (extensionRunner.hasHandlers("session_shutdown")) {
		await extensionRunner.emit(event);
		return true;
	}
	return false;
}
```

### 1.2 载荷与结果类型（types.ts 逐字）

#### 1.2.1 会话生命周期与 before 族（types.ts:740-819）

```ts
/** Fired when a session is started, loaded, or reloaded */
export interface SessionStartEvent {
	type: "session_start";
	/** Why this session start happened. */
	reason: "startup" | "reload" | "new" | "resume" | "fork";
	/** Previously active session file. Present for "new", "resume", and "fork". */
	previousSessionFile?: string;
}

export interface SessionBeforeSwitchEvent {
	type: "session_before_switch";
	reason: "new" | "resume";
	targetSessionFile?: string;
}

export interface SessionBeforeForkEvent {
	type: "session_before_fork";
	entryId: string;
	position: "before" | "at";
}

export interface SessionBeforeCompactEvent {
	type: "session_before_compact";
	preparation: CompactionPreparation;
	branchEntries: SessionEntry[];
	customInstructions?: string;
	reason: "manual" | "threshold" | "overflow";
	willRetry: boolean;
	signal: AbortSignal;
}

export interface SessionCompactEvent {
	type: "session_compact";
	compactionEntry: CompactionEntry;
	fromExtension: boolean;
	reason: "manual" | "threshold" | "overflow";
	willRetry: boolean;
}

export interface SessionShutdownEvent {
	type: "session_shutdown";
	reason: "quit" | "reload" | "new" | "resume" | "fork";
	/** Destination session file when shutting down due to session replacement. */
	targetSessionFile?: string;
}
```

`CompactionPreparation`（compaction.ts:772-788）：`{firstKeptEntryId, messagesToSummarize, turnPrefixMessages, isSplitTurn, tokensBefore, previousSummary?, fileOps, settings}`。
`CompactionEntry`（session-manager.ts:91-104）：`{type:"compaction", summary, firstKeptEntryId, tokensBefore, details?, usage?, fromHook?, systemMessage?}`＋`SessionEntryBase`（id/parentId/timestamp）。

#### 1.2.2 before 族结果类型（types.ts:1473-1490）

```ts
export interface SessionBeforeSwitchResult { cancel?: boolean; }
export interface SessionBeforeForkResult { cancel?: boolean; skipConversationRestore?: boolean; }
export interface SessionBeforeCompactResult { cancel?: boolean; compaction?: CompactionResult; }
```

`CompactionResult`（compaction.ts:105-113）：`{summary, firstKeptEntryId, tokensBefore, estimatedTokensAfter?, usage?, details?}`。

#### 1.2.3 `input`/`user_bash`（types.ts:1120-1160）

```ts
export interface UserBashEvent {
	type: "user_bash";
	command: string;
	/** True if !! prefix was used (excluded from LLM context) */
	excludeFromContext: boolean;
	cwd: string;
}

export type InputSource = "interactive" | "rpc" | "extension";

export interface InputEvent {
	type: "input";
	text: string;
	images?: ImageContent[];
	source: InputSource;
	/** How the input will be delivered during streaming, or undefined when idle */
	streamingBehavior?: "steer" | "followUp";
}

export type InputEventResult =
	| { action: "continue" }
	| { action: "transform"; text: string; images?: ImageContent[] }
	| { action: "handled" };
```

`UserBashEventResult`（types.ts:1437-1450）＝互斥二支：`{operations: BashOperations, result?: never}`｜`{operations?: never, result: BashResult}`。

#### 1.2.4 boundary 族（types.ts:942-1044）

```ts
export type AgentActivityOutcome = "completed" | "aborted" | "error";

export interface CustomEntryDraft { type: "custom"; customType: string; data?: unknown; }
export interface CustomMessageEntryDraft {
	type: "custom_message"; customType: string;
	content: string | (TextContent | ImageContent)[]; display: boolean; details?: unknown;
}
export interface ContextEditEntryDraft {
	type: "context_edit"; targetId: string; replacement: ContextEditEntry["replacement"];
}
export interface CompactionEntryDraft {
	type: "compaction"; summary: string;
	/** Null creates a self-retaining compaction that keeps no preceding entries. */
	firstKeptEntryId: string | null; details?: unknown; usage?: Usage;
}
export type SessionBoundaryDraft = CustomEntryDraft | CustomMessageEntryDraft
	| ContextEditEntryDraft | CompactionEntryDraft;

export interface BoundaryContextPreview {
	contextEntries: ProjectedSessionEntry[];
	contextMessages: AgentMessage[];
	llmMessages: Message[];
	pendingMessages: AgentMessage[];
	canContinue: boolean;
}

export interface BoundaryState {
	entries: SessionBoundaryDraft[];
	continue: boolean;
	context: BoundaryContextPreview;
	outcome: AgentActivityOutcome;
}

export interface BoundaryResult { entries?: SessionBoundaryDraft[]; continue?: boolean; }

export interface AgentBeforeSettleEvent extends BoundaryState { type: "agent_before_settle"; }

export interface TurnEndEvent extends BoundaryState {
	type: "turn_end";
	turnIndex: number;
	message: AssistantMessage;
	toolResults: ToolResultMessage[];
	messageEntryId: string;
	toolResultEntryIds: string[];
}
```

`MessageEndEventResult`（types.ts:1463-1465）：`{message?: AgentMessage}`——*替换后的消息必须保持原 role*。
`BeforeAgentStartEventResult`（types.ts:1467-1471）：`{message?: Pick<CustomMessage,"customType"|"content"|"display"|"details">, systemPrompt?: string}`。

### 1.3 发射点与消费链

#### 1.3.1 `turn_end` boundary（agent-session.ts:846-896）

`_dispatchTurnEndBoundary(message, toolResults)`（:846-882）：
1. `_lastActivityOutcome` 由 `message.stopReason` 映射（`aborted`/`error`/其余 `completed`）；
2. `_findPersistedMessageEntryId` 取 assistant 消息的持久化条目 id，**取不到 ⇒ emitError（`extensionPath:"<boundary>"`，文本逐字 `"turn_end could not resolve the persisted assistant entry ID"`）⇒ 不派发**；
3. `hasHandlers("turn_end")` 为假 ⇒ 返回 false（零成本快走）；
4. `emitBoundary({type:"turn_end", turnIndex, message, toolResults, messageEntryId, toolResultEntryIds, outcome}, buildContext)`；
5. `_commitBoundaryDrafts(boundary.entries)`——草稿落 SessionManager＋逐条 `_emit({type:"entry_appended"})`；
6. `boundary.continue && !canContinue` ⇒ `_reportInvalidBoundaryContinuation`（文本：`"${event} requested continuation without runnable model context"`）⇒ false。

安装点（`_installAgentBoundaryHooks` :884-896，逐字）：包住 `agent.finishTurn`——**先跑 boundary，再跑原 finishTurn 决策**；原决策 `end` 优先；`extensionContinue || 原决策 continue` ⇒ `{action:"continue"}`。

`_buildBoundaryContext`（:999-1022）：预览管理器（in-memory SessionManager＝header＋当前 branch，应用草稿）→ projection；`pendingMessages = [...agent.peekQueuedMessages(), ..._pendingCustomMessages]`；`canContinue`＝（有非系统消息且末条非 assistant）｜有待发 custom｜（turn_end：有排队消息；before_settle：末条是 assistant 且有排队消息）。

#### 1.3.2 `agent_before_settle` boundary（agent-session.ts:1893-1919）

`_runBeforeSettleBoundary`：`hasHandlers` 为假 ⇒ 返回 `agent.hasQueuedMessages()`（零成本快走）；否则 `emitBoundary({type:"agent_before_settle", outcome}, ...)` ⇒ 提交草稿 ⇒ `_flushPendingCustomMessages()` ⇒ `result.continue || hasQueuedMessages()`（`continue=true` 但上下文不可续 ⇒ 同上报错⇒false）。调用方在 settle 前的重试判定链里（:1884-1891：压缩检查之后、排队的最后机会）。

#### 1.3.3 `input`（agent-session.ts:1921-1942＋1991-2003）

`_runInputHandlers`：`hasHandlers` 为假 ⇒ 原样返回；`handled` ⇒ `undefined`（调用方 `preflightResult?.("handled")` 后**直接返回**，不展开模板、不排队、不驱动）；`transform` ⇒ 换 text/images。发射点在 `prompt()` 内**模板/skill 展开之前**（:1991 注释逐字 *"Emit input event for extension interception (before skill/template expansion)"*），source 默认 `"interactive"`，流式时带 `streamingBehavior`。

#### 1.3.4 `before_agent_start`（agent-session.ts:2061-2110）

发射在「模型/凭证校验、`_checkCompaction` 之后，图片归一化之前」（:2056-2062 注释：扩展驱动的模型选择决定图片 resize 档）。仅**非流式驱动路**触发（流式排队路 :2011-2026 不触发）。

#### 1.3.5 `session_before_compact`＋`session_compact`（agent-session.ts:2763-2900 手动路 / :3097-3230 自动路）

两路同构（手动路 reason="manual"、willRetry=false；自动路 reason∈{overflow,threshold}）：
1. `hasHandlers("session_before_compact")` 门 ⇒ 发射 `{preparation, branchEntries, customInstructions, reason, willRetry, signal}`；
2. `result?.cancel` ⇒ `cancelledByExtension=true`＋`throw new Error("Compaction cancelled")` ⇒ 走中止臂（compaction_end aborted=true、errorMessage=undefined，再发 `session_compact_failed`）；
3. `result?.compaction` ⇒ **跳过默认摘要生成**，用扩展产物（summary/firstKeptEntryId/tokensBefore/usage/details），`fromExtension=true`；
4. `appendCompaction(...)` 后从 `getEntries()` 找回**已存条目**（`find(e => e.type==="compaction" && e.summary===summary)`）⇒ 发射 `session_compact{compactionEntry, fromExtension, reason, willRetry}`。

#### 1.3.6 会话生命周期（agent-session-runtime.ts ＋ agent-session.ts）

**`session_start`**：`bindExtensions()`（agent-session.ts:3258-3282）内 `await this._extensionRunner.emit(this._sessionStartEvent)`，随后才 `extendResourcesFromExtensions`；三个模式（interactive:1973/print:76/rpc:319）装配后各调一次 `bindExtensions`。事件本体来自 `config.sessionStartEvent ?? {type:"session_start", reason:"startup"}`（:509）——**初始会话恒 `startup`**（main.ts:865 初次建 runtime 不传 event，即便 CLI resume）；替换路径由 runtime 构造（五级 reason 映射）：

| runtime 流 | before 事件 | shutdown reason | session_start |
|---|---|---|---|
| `switchSession`（:194-214） | `session_before_switch{reason:"resume", targetSessionFile}` | `"resume"`＋target | `{reason:"resume", previousSessionFile}` |
| `newSession`（:216-253） | `session_before_switch{reason:"new"}`（无 target） | `"new"` | `{reason:"new", previousSessionFile}` |
| `fork`（:255-345） | `session_before_fork{entryId, position}` | `"fork"` | `{reason:"fork", previousSessionFile}` |
| `importFromJsonl`（:355-397） | `session_before_switch{reason:"resume", target}` | `"resume"`＋target | `{reason:"resume", ...}` |
| `reload`（agent-session.ts:3658-3698） | — | `"reload"` | `{reason:"reload"}`（无 previous） |
| `dispose`（:399-407） | — | `"quit"` | — |

before 事件 `cancel` ⇒ **整个流中止**（`:201-203` 逐字：`if (beforeResult.cancelled) return beforeResult;`——旧会话不动）；`teardownCurrent`（:168-178）：先 `session.abort()` 落定当前回合 ⇒ `emitSessionShutdownEvent{reason, targetSessionFile}` ⇒ `session.dispose()`。

**`session_shutdown`**：`emitSessionShutdownEvent` 三处调用——runtime 的 `teardownCurrent`/`dispose`（quit）、agent-session `reload`。交互模式退出（interactive-mode.ts:4290-4320）：信号路先 dispose（发 shutdown）再动终端；普通退出先停 TUI 再发。

### 1.4 `on()` 重载对位（types.ts:1569-1636）

本批 12 事件的重载行：`session_start:1574`、`session_before_switch:1577`、`session_before_fork:1581`、`session_before_compact:1585`、`session_compact:1588`、`session_shutdown:1590`、`before_agent_start:1611`、`agent_before_settle:1617`、`turn_end:1624`、`message_end:1627`（结果型）、`user_bash:1635`、`input:1636`。java 侧仍走 spec-token 单方法（批 4 裁决①），新增 12 个 spec 常量。

---

## §2 java 侧现状（证据）

### 2.1 批 4 地基（本批的直接依托）

- `ExtensionEventBus`（extension/ExtensionEventBus.java，113 行）：`on/snapshot/hasHandlers/onError/emitError` 全备；`RegisteredHandler.error(Throwable|String)` 按扩展归属构造错误。
- `ExtensionEvent` ADT（152 行，18 事件＋3 结果）＋ `ExtensionEventSpec`（125 行，18 常量）。`MESSAGE_END` 当前 R=Void（观察臂）；`SessionCompactEvent` 无 `compactionEntry`（窄差 D4 在册）。
- `SessionEventBridge`（161 行）：14 事件宿主→扩展直译；`TurnEnd` 只 `turnIndex++`（boundary 臂挂本批）；`fire()` 观察型发射（无订阅者快走＋逐处理器 catch Throwable）。
- `EngineHookBridge`（272 行）：`transform_context`/`before_tool`/`after_tool` 三适配器，装配期恒装（裁决②先例）。
- `AgentSession.loadExtensions`（:1565-1591）：bus/context 是**局部变量**——装配后会话本体拿不到扩展面（本批要提为字段）；接线序：bus → context → loadAll → EngineHookBridge.install → subscribe(SessionEventBridge) → onError → applySessionStartResources。

### 2.2 message_end：持久化先于宿主事件，无缝（关键取证）

- 引擎生产点 `PiLoopRunner.java:377`（`emit.emit(new Event.MessageEnd(finalMessage))`）；prompt/steer/followUp/工具结果同样经 `PiLoop.java:368-369`、`PiLoopRunner.java:97-98`、`PiLoopTools.java:108-109,205-206,262-263` —— **全部过 `PiLaneSink.emit`**。
- `PiLaneSink.emit`（:237-255，synchronized 漏斗）→ `onMessageEnd`（:271-338）：`lane.messages.add` → `append`（:341-347，消息对象作为 `Entry.Message` 载荷进 transcript）→ downstream。
- 宿主事件在 `SessionRunner.java:240-244`：**先 `flush`（落盘）后 `emit`**。`Message` 是不可变 record，同一引用进 `lane.messages`/`Entry.Message`/事件载荷。
- 结论：宿主层（SessionRunner）插钩子太晚（transcript 已是旧引用且已落盘）；**唯一「换引用即全局生效」的缝在 `PiLaneSink.emit` 的 `onMessageEnd` 之前**——pi 的 `_replaceMessageInPlace`（§1.1.1）在 java 只能落在这里（裁决①）。

### 2.3 turn 结束与 settle 链

- `should_stop_after_turn` 钩（HookSystem.java:99-101）：`ShouldStopAfterTurnHook.shouldStopAfterTurn(ctx) -> PiLoop.TurnDecision{CONTINUE,END,null}`；`fireFinishTurn`（:314-330）任一 END 赢、无 END 有 CONTINUE ⇒ CONTINUE、全弃权 null。firing 于 `PiLoopRunner.java:160`（`config.finishTurn().apply(lastCompletedTurn)`），**在 `TurnEnd` 事件（:162）与决策消费（:165-196）之前**——与 pi 的 finishTurn 包裹点同位。
- ⚠️ 现状窄差：接线 `PiLaneEngine.java:343` 的 lambda 是 `next -> fireFinishTurn(laneName, lane)`——**丢掉了 `next`**，上下文喂的是 `lane.partial`＋`toolResults=List.of()`（:446-449）。pi 给的是定案消息＋真工具结果。本批顺手修真（boundary 载荷正需要）。
- settle 判定在引擎 post-run：`PostRunCompactionCheck.checkAfterRun`（:87-104）＝pi `_handlePostAgentRun` 全序（①重试→终局失败→②压缩→③队列 `CompactionExecutor.hasQueuedMessages`），全 false ⇒ drive 循环 break ⇒ finishRun ⇒ 宿主 `AgentSettled`（SessionRunner.java:175-176）。
- `before_run_end` 钩（RunLifecycle.java:209）是 **void 观察型**，在 finishRun 收口处——表达不了 continue 语义，不能用作 before_settle 的缝（裁决②）。
- continue 能力现成：`PiLaneEngine.drive` 的 `continuePrompts`＋`lifecycle.startContinue`（:218-220）；`AgentHarness.continueRun`（:130）。

### 2.4 压缩链

- `CompactionExecutor.applyCompaction`（:274-345）：telemetry → **`fireBeforeCompaction`（:290，载荷 `CompactionContext(lane, transcript, currentTokens)`，plan.keepEntries 臂）** → `compactTranscript`（:296）→ 中止检查（:301-304）→ 整体替换 transcript（:310-311）→ rebuild → `onEnd(reason, result, false, willRetry, null)`（:339-340）。
- `CompactionService.prepare`（:67-97）产 `Plan{projected, boundaryStart, cut, toSummarize, turnPrefix, splitTurn, previousSummary, firstKeptId}`——pi `CompactionPreparation` 的 java 对位**已存在**，但在 `compactTranscript` 内部现算，钩子时点拿不到（要前置，§3.6）。
- `Entry.Compaction` 构造点 :460-464（compactTranscript 内），组件：`summary, firstKeptEntryId, retainedTail, tokensBefore, details, usage, fromHook`（Entry.java:191-208）。
- `CompactionObserver.onEnd(reason, result, aborted, willRetry, errorMessage)`（5 参）——**不带条目本体**（窄差 D4 的根）。AgentSessionEvent.CompactionEnd 同形（AgentSessionEvent.java:78-80）。
- 手动路 `compact`（:81-126）：customInstructions 有参；取消 ⇒ `IllegalStateException("Compaction cancelled")`（:115-119，与 pi 抛错文案一致）。自动路 `runAutoCompaction`（:200-253）：reason/willRetry 有参、customInstructions 恒 null（pi 同）。

### 2.5 输入链与 bash

- `AgentSession.processPrompt`（:808-867）：**第一行就是 `expandUserPrompt`**（:819，/skill:＋模板两步展开）→ PROMPT_ROUTER 路由门（QUEUE_STEER/QUEUE_FOLLOW_UP）→ systemPrompt/thinking 覆盖（:838-843，`harness.setSystemPrompt` 是**全局态**）→ 虚拟线程 `SessionRunner.drive`。**无 input 拦截点、无 before_agent_start 发射点**。
- `PromptConfig`（:13-17）：仅 `systemPrompt, thinkingLevel, streamingBehavior`——**无 images、无 source**。
- `executeBash`（:1417-1432）：`toolContext.shell().execute(...)` → `BashExecutionUpdate` → `recordBashResult`（:1446-1461，运行中暂存 pendingBashMessages）。唯一 chokepoint——RPC（RpcDispatcher.java:231-238）与 Web（WebDispatcher.java:317-336）都经此。**无扩展拦截**；`ShellExecutor` 是 ToolContext 级注入（无 per-call operations 缝）。
- TUI 无 `!`/`!!` 前缀识别（存量；pi 的前缀识别在 interactive-mode，java 的 user_bash 面只服务 RPC/Web 调用链）。

### 2.6 会话切换/分叉/关闭

- `findSession`（:1318-1326）：持久路 **原地 attach**（`SessionPersistence.attach(this, ...)` 后 `return this`）——/resume、RPC switch_session（:244-252）、Web loadSession（:157-171）三个调用点的**公共 chokepoint**。
- `forkCopy`（:1339）/`forkFromEntry`（:1364）：实例方法，建新 AgentSession。调用点：TreeSelectorScreen.java:55、SessionCommands.java:42/:51、RpcDispatcher.java:253-271、WebDispatcher.java:339-362。**旧会话一律不 close**（GC 丢弃）；**forked 会话不加载扩展**（`forkInMemory` :1406-1413 直 new 私有构造，不过 assemble/loadExtensions——存量缺口，§6 新登记）。
- /new：`SessionCommands.java:65` `AgentSession.create(ctx.session().sessionArgs())`；RPC new_session：:134-139；Web newSession：:143-155（**全仓唯一切时 close 旧会话的路径**）。
- `close()`（:1538-1552）：abortRetry → persistPending＋session.close → repo close → settings flush → harness.close。**无任何事件发射**。TUI 退出从不 close（PiTuiApp.exit :500-513 不动 session——存量，本批只保证「调了 close 就有 shutdown 事件」）。
- `existingSession`＝`resumeLike(args)`（:276-279：continue_/resume/session/sessionId/fork 任一）。pi 初始会话恒 `startup`（§1.3.6）⇒ java 初始 create 也恒 `startup`（含 CLI resume——与 pi 逐字一致）。
- 会话标识是 **String id**（UuidV7，JsonlSessionRepository.java:239；SQLite 路同形 id/前缀）——pi 的 `previousSessionFile`/`targetSessionFile` 是文件路径，java 无文件路径面（裁决⑤）。

### 2.7 投影与队列读口

- `ContextEntries.projectEntries(List<Entry>, leafId)`（:171-200）→ `ProjectedEntry(source, List<Message> messages)`——pi `_entryIdsByMessage` 与 `contextEntries` 预览的全部原料；`pathToLeaf`（:49）取当前 branch。
- 队列只读：`AgentHarness.queuedSteering/queuedFollowUp`（HarnessBootstrap.java:200-207）；`CompactionExecutor.hasQueuedMessages(lane)`（:256-258）。
- `AgentHarness.appendLaneEntry(lane, entry)` 存在且含 rebuild（recordBashResult 空闲路在用）；持久化走 `SessionPersistence.persistPending`。

---

## §3 设计

### 3.0 总形：三类落点

| pi 发射机制 | java 落点 | 事件 |
|---|---|---|
| 宿主 await 事件＋原地改写（message_end） | **引擎新钩子**（sink 前替换，裁决①） | message_end |
| finishTurn 包裹 / post-run 队列判定（boundary） | **引擎钩子**（should_stop_after_turn 修真＋新 before_settle 钩，裁决②） | turn_end、agent_before_settle |
| 引擎钩子（before_compaction） | 引擎钩子载荷/结果扩形（裁决⑥） | session_before_compact |
| 宿主层方法内发射（prompt/bash/生命周期） | **宿主发射器** `SessionEventFirer`（裁决③） | input、user_bash、before_agent_start、session_start、session_shutdown、session_before_switch、session_before_fork |

装配变化：`loadExtensions` 把 bus/context 提为 `AgentSession` 字段（`extensionFirer` 一并建好，noExtensions 时全 null）；`EngineHookBridge.install` 增加 4 个适配器（messageEnd/turnEndBoundary/beforeSettleBoundary/beforeCompaction——后三者共用新 `BoundaryBridge` 与压缩适配器逻辑）。

### 3.1 事件 ADT 扩形（extension/event/）

`ExtensionEvent.java` 新增 12 个事件 record（超 500 行则结果/支撑类型拆 `ExtensionResults.java` 顶层 record，同包）：

```java
// ── 生命周期（pi types.ts:740-819）──
record SessionStartEvent(String reason, String previousSessionFile) implements ExtensionEvent {}
record SessionShutdownEvent(String reason, String targetSessionFile) implements ExtensionEvent {}
record SessionBeforeSwitchEvent(String reason, String targetSessionFile) implements ExtensionEvent {}
record SessionBeforeForkEvent(String entryId, String position) implements ExtensionEvent {}

// ── 压缩（types.ts:771-795）；preparation 为 CompactionPreparation 视图记录（§3.6）──
record SessionBeforeCompactEvent(CompactionPreparation preparation, List<Entry> branchEntries,
    String customInstructions, String reason, boolean willRetry,
    CompactionAbortToken signal) implements ExtensionEvent {}
// SessionCompactEvent 加组件：Entry.Compaction compactionEntry（D4 销号）

// ── 输入与 bash（types.ts:1120-1160）──
record InputEvent(String text, List<ContentBlock> images, String source,
                  String streamingBehavior) implements ExtensionEvent {}  // images 恒 null（D12）
record UserBashEvent(String command, boolean excludeFromContext, String cwd) implements ExtensionEvent {}

// ── before_agent_start（types.ts:917-928）──
record BeforeAgentStartEvent(String prompt, String systemPrompt) implements ExtensionEvent {}
                                            // systemPromptOptions 可变对象不移植（D11）

// ── boundary（types.ts:942-1044）──
record TurnEndEvent(int turnIndex, Message.AssistantMessage message,
    List<Message.ToolResultMessage> toolResults, String messageEntryId,
    List<String> toolResultEntryIds, List<SessionBoundaryDraft> entries,
    boolean shouldContinue, BoundaryContextPreview context, String outcome) implements ExtensionEvent {}
record AgentBeforeSettleEvent(List<SessionBoundaryDraft> entries, boolean shouldContinue,
    BoundaryContextPreview context, String outcome) implements ExtensionEvent {}
```

结果与支撑类型（pi 逐字语义，`null ≙ undefined`）：

```java
record MessageEndEventResult(Message message) {}                    // :1463；role 必须同
record SessionBeforeSwitchResult(Boolean cancel) {}                 // :1473
record SessionBeforeForkResult(Boolean cancel, Boolean skipConversationRestore) {} // :1476（skip 臂 java 无消费面 ⇒ 收下不消费，D-差）
record SessionBeforeCompactResult(Boolean cancel, SuppliedCompaction compaction) {} // :1481
record SuppliedCompaction(String summary, String firstKeptEntryId, long tokensBefore,
                          ToolResult.UsageInfo usage, Map<String,Object> details) {} // CompactionResult 子集
record BeforeAgentStartEventResult(CustomMessageDraft message, String systemPrompt) {} // :1467
record CustomMessageDraft(String customType, Object content, boolean display, Map<String,Object> details) {}

sealed interface InputEventResult {                                  // :1156 判别联合
    record Continue() implements InputEventResult {}
    record Transform(String text, List<ContentBlock> images) implements InputEventResult {}
    record Handled() implements InputEventResult {}
}
record UserBashEventResult(ShellResult result) {}                    // operations 臂不移植（D10）

// boundary 支撑（types.ts:942-1044 逐字）
sealed interface SessionBoundaryDraft {                              // 四型
    record Custom(String customType, Map<String,Object> data) implements SessionBoundaryDraft {}
    record CustomMessage(String customType, Object content, boolean display,
                         Map<String,Object> details) implements SessionBoundaryDraft {}
    record ContextEdit(String targetId, Entry.ContextEdit.Replacement replacement) implements SessionBoundaryDraft {}
    record Compaction(String summary, String firstKeptEntryId,
                      Map<String,Object> details, ToolResult.UsageInfo usage) implements SessionBoundaryDraft {}
}
record BoundaryContextPreview(List<ProjectedEntry> contextEntries, List<Message> contextMessages,
    List<Message> llmMessages, List<Message> pendingMessages, boolean canContinue) {}
record BoundaryResult(List<SessionBoundaryDraft> entries, Boolean shouldContinue) {} // 键名 continue ⇒ 组件名 shouldContinue
```

`ExtensionEventSpec` 新增 12 常量（线名逐字）；**`MESSAGE_END` 的 R 由 Void 改为 `MessageEndEventResult`**（观察臂处理器返回 null 即不发言——`SessionEventBridge.fire` 泛型放宽为忽略返回值）。

### 3.2 引擎扩形 T1（agent-core，先行 commit）

1. **新钩子 `message_end`**：`HookSystem.onMessageEnd(lane, MessageEndHook)`／`fireMessageEnd(laneName, message) -> Message`（替换或 null）。firing 点 `PiLaneSink.emit`：事件为 `MessageEnd` 时**先于** `onMessageEnd` 调用，返回值非 null 则以替换消息重建事件对象再下发（换引用即 lane/transcript/下游全换，§2.2）。钩子异常按「其余族」纪律记账弃权（HookSystem :19-27）。
2. **`ShouldStopAfterTurnContext` 修真**：组件由 `(lane, runId, lane.partial, List.of())` 改为 `(lane, runId, next.message(), next.toolResults())`——`PiLaneEngine.java:343` 的 lambda 改用 `next`（:446-449 构造点同改）。既有钩子消费者全仓 grep 改签（若有）。
3. **新钩子 `before_settle`**：`onBeforeSettle(lane, BeforeSettleHook)`／`fireBeforeSettle(laneName, ctx) -> Boolean`（任一 TRUE ⇒ TRUE；全弃权 null）。firing 点 `PiLaneEngine.drive` 的 post-run 检查返回 false 之后、break 之前（pi :1839 同位——①②③ 全说收才到这扇门）；`!TRUE ⇒ break`——无桥时行为逐比特不变（此刻队列必空，与 pi 无处理器返回 `hasQueuedMessages()` 同值）。
4. **压缩三扩**：① `CompactionContext` 加组件（plan、reason、willRetry、customInstructions、abortToken）；② `applyCompaction` 前置 `CompactionService.prepare`，plan 复用于钩子载荷与 `compactTranscript`（消除二次 prepare）；③ before_compaction 结果扩形：`keepEntries` 臂保留，新增 `cancel` 与 `suppliedCompaction` 臂——cancel ⇒ 走中止臂（onEnd aborted=true，手动路抛 "Compaction cancelled" 既有）；supplied ⇒ 跳过摘要生成，直接以供应产物构造 `Entry.Compaction`（**fromHook=true**）。
5. **`CompactionObserver.onEnd` 加第 6 参 `Entry.Compaction entry`**（成功路非 null，其余 null）→ `AgentSessionEvent.CompactionEnd` 加同名组件 → `SessionCompactEvent.compactionEntry`（D4 销号）。

### 3.3 宿主发射器 `SessionEventFirer`（extension/ 新类，裁决③）

pi 的 runner 宿主发射法在 java 的合订本。持有 bus＋ctx；全部**无订阅者快走**；错误纪律逐族照 pi：

```java
// pi bindExtensions 发射（agent-session.ts:3279）——观察型
void sessionStart(String reason, String previousSessionFile);
// pi emitSessionShutdownEvent（runner.ts:258-267）——hasHandlers 门＋观察型
void sessionShutdown(String reason, String targetSessionFile);
// pi 通用 emit 的 cancel 短路（runner.ts:1089-1118）：cancel 立即返回、否则最后发言者赢
boolean sessionBeforeSwitch(String reason, String targetSessionFile);   // ⇒ cancelled
boolean sessionBeforeFork(String entryId, String position);             // ⇒ cancelled
// pi emitInput（runner.ts:1520-1558 逐字）：handled 短路、transform 链、无改 ⇒ continue
InputOutcome input(String text, String source, String streamingBehavior);
// pi emitUserBash（:1262-1296 逐字）：首个发言者赢；异常 emitError 后**重抛**
Optional<ShellResult> userBash(String command, boolean excludeFromContext, String cwd);
// pi emitBeforeAgentStart（:1420-1472 逐字）：message 累积、systemPrompt 链式覆盖
BeforeAgentStartOutcome beforeAgentStart(String prompt, Supplier<String> currentSystemPrompt);
```

### 3.4 message_end 替换臂（EngineHookBridge 第 4 适配器）

pi `emitMessageEnd`（§1.1.1）逐字：链式 currentMessage、role 校验（java＝同 variant 类；不同 ⇒ `emitError` 文本逐字 `"message_end handlers must return a message with the same role"`＋跳过）、逐处理器 catch ⇒ emitError ⇒ 继续、全程无替换 ⇒ null。

**`SessionEventBridge` 的 `MessageEnd` case 删除**——pi 里 message_end 只火一次（替换＋观察同批处理器按注册序）；java 若 sink 适配器与宿主桥双发则每个处理器火两次，错。删宿主 case 后：处理器在 sink 时点统一火一次（观察处理器看到链中值——比 pi 的同序处理器略早见到最终值的差并入窄差 D9）。宿主 `AgentSessionEvent.MessageEnd` 事件本体照常广播（TUI/RPC 消费不受影响，且拿到的已是替换后引用——pi 原地改写同效）。

### 3.5 boundary 子系统（新 `BoundaryBridge`＋两适配器）

`BoundaryBridge`（extension/bridge/，构造参数 harness/owner/bus/ctx）：

- **`emitBoundary`**（pi runner.ts:1029-1078 逐字，§1.1.6）：entries/continue 链式累积；每处理器后 `buildContext(entries)` 重建预览，失败 ⇒ `emitError("Invalid boundary entries: " + msg)`＋valid=false ⇒ 返回空草稿＋false。
- **`buildContext`**（pi :999-1022）：`ContextEntries.pathToLeaf` 取 branch → 尾部追加草稿**投影副本**（草稿→Entry 纯函数，不动真 transcript）→ `projectEntries` → contextEntries/contextMessages/llmMessages（java 同源，D15）；pendingMessages＝队列文本构 UserMessage＋（java 无 pendingCustom 面，D 差）；canContinue 逐字（末条 role 判定＋排队判定；turn_end/before_settle 两形分支照 pi）。
- **`commit`**（pi `_commitBoundaryDrafts` :1024-1028＋`_applyBoundaryDrafts` :944-987）：逐草稿建 Entry → `owner.appendBoundaryEntry(entry)`（AgentSession 新方法：harness.appendLaneEntry＋`emitSessionEvent(EntryAppended)`＋persistPending）。四型：Custom/CustomMessage 直构；ContextEdit 直构；**Compaction**：retainedTail 用 `ContextEntries` 对 kept 后缀投影计算、tokensBefore 用 `ContextUsageEstimator.estimateContextTokens`（pi :966-976 同构），fromHook=true。
- **entry id 映射**（pi `_entryIdsByMessage`）：派发时按需从投影建「消息引用 → entryId」identity 映射。

`EngineHookBridge` 两适配器：

- **turnEndBoundary**（should_stop_after_turn 钩，pi `_dispatchTurnEndBoundary` :846-882＋`_installAgentBoundaryHooks` :884-896 逐字）：① 先记 outcome（stopReason→completed/aborted/error，**无订阅者也记**——before_settle 要用）；② `!hasHandlers(TURN_END)` ⇒ null 弃权；③ messageEntryId 解析不到 ⇒ `emitError("<boundary>", "turn_end could not resolve the persisted assistant entry ID")` ⇒ 弃权；④ emitBoundary ⇒ commit ⇒ `continue && !canContinue` ⇒ emitError `"turn_end requested continuation without runnable model context"` ⇒ 弃权；⑤ continue ⇒ `TurnDecision.CONTINUE`。
- **beforeSettleBoundary**（before_settle 钩，pi `_runBeforeSettleBoundary` :1893-1919 逐字）：`!hasHandlers` ⇒ null 弃权（引擎回落 hasQueuedMessages——pi 同值）；否则 emitBoundary ⇒ commit ⇒ 中止检查（lane.abortSignal）⇒ `continue || hasQueuedMessages`（continue=true 但不可续 ⇒ 同形上报＋false）。

### 3.6 session_before_compact（EngineHookBridge 第 5 适配器）＋ compactionEntry

pi :2792-2811/:3124-3144（§1.3.5）逐字。适配器挂引擎 `before_compaction` 钩（载荷已按 §3.2.4 扩形）：

- `!hasHandlers(SESSION_BEFORE_COMPACT)` ⇒ null（引擎走原 plan/生成路）。
- 事件载荷：`preparation` 由 Plan 直译（firstKeptEntryId=firstKeptId、messagesToSummarize=toSummarize、turnPrefixMessages=turnPrefix、isSplitTurn=splitTurn、tokensBefore=钩子 currentTokens、previousSummary、settings；**fileOps 不译**——java 的 fileOps 在 compact() 内收集、prepare 时点没有，窄差 D22）、branchEntries=transcript、customInstructions/reason/willRetry 透传、signal=abortToken。
- 结果：`cancel` ⇒ 引擎中止臂（pi 的 `cancelledByExtension` 同路）；`compaction` ⇒ 引擎 supplied 臂（§3.2.4③，Entry.Compaction fromHook=true）⇒ `session_compact` 事件的 `fromExtension` 随之真——**D6「恒 false」窄差同步销号**（SessionEventBridge 的 compaction() 改读事件载荷而非写死）。
- 逐处理器 catch ⇒ emitError ⇒ 继续；cancel 短路（pi 通用 emit 纪律）。

### 3.7 生命周期接线（B250）

| 触发 | java 落点 | 序列 |
|---|---|---|
| 初始装配（含 CLI resume，恒 startup） | `loadExtensions` 内（桥接线后、applySessionStartResources 前——pi :3279-3281 同序） | `firer.sessionStart("startup", null)` |
| 退出/关闭 | `close()` 首行；`closeForReplacement(reason, target)` 公共变体 | `firer.sessionShutdown("quit", null)` 后走原流程 |
| 原地 resume（/resume、RPC switch_session、Web loadSession） | `AgentSession.findSession` 不动签名；新增公共 `switchTo(String idOrPrefix)` 集中四步 | beforeSwitch(cancel⇒返回 CANCELLED) → 记 prevId → shutdown("resume", target) → findSession(attach) → start("resume", prevId) |
| fork（/fork /clone、树选择器、RPC、Web） | `forkCopy`/`forkFromEntry` 内部（返回改 `Optional<AgentSession>`，4 调用点改签） | beforeFork(entryId, position)（cancel⇒empty）→ shutdown("fork", null)（旧 bus）→ 原流程建 forked →（forked 无扩展面，start 臂随 §6 新登记项落地） |
| /new、RPC new_session、Web newSession | 新公共静态 `createReplacing(Args, previousSessionId)`（startInfo 穿进 assemble/loadExtensions）＋调用点对旧会话 `fireSessionShutdown("new", newId)` | 旧 shutdown 在新 start 之后（目标 id 先知才行——pi 序相反，窄差 D20） |

`sessionBeforeSwitch/Fork` 的 `skipConversationRestore` 结果臂：java 无对应消费面（fork 总带种子条目）——收下不消费，窄差 D23。

### 3.8 调用点改签清单

- coding-agent：`AgentSession`（processPrompt 加 input/before_agent_start 两发射点——**input 在 expandUserPrompt 之前**（pi :1991），before_agent_start 在覆盖应用后、drive 起线程前；executeBash 加 userBash；close/closeForReplacement；switchTo；fork 两法；appendBoundaryEntry）、`SessionCommands`（/new → createReplacing＋旧会话 shutdown；/resume → switchTo；/fork//clone → Optional 改签）、`RpcDispatcher`（new_session → createReplacing＋shutdown；SwitchSession → switchTo，cancelled 回 `{cancelled:true}`——pi rpc 同形；fork/clone → Optional 改签；handlePrompt → PromptConfig 加 source="rpc"）、`PromptConfig`（加 `source` 组件，默认 "interactive"）、`SessionResult`（"handled" 状态：空流＋已完结 futures）。
- tui：`TreeSelectorScreen`（forkFromEntry Optional 改签）。
- web：`WebDispatcher`（newSession → createReplacing＋`previous.closeForReplacement("new", newId)`；loadSession → switchTo；forkFrom/cloneSession → Optional 改签）。

---

## §4 裁决

**裁决①：message_end 替换臂走引擎新钩子（sink 前替换），观察/结果同次发射**
- 【场景】pi 是宿主 await 事件＋`_replaceMessageInPlace` 原地改写（持久化在事件之后，全引用同步）。java 的 Message 不可变、宿主事件在 flush 之后（§2.2），宿主层无任何可替换的缝。
- 【决定】`HookSystem` 加 `message_end` 钩，`PiLaneSink.emit` 在 `onMessageEnd` 前 firing、换引用即全局生效；`SessionEventBridge` 删宿主 case 防双发。
- 【风险/代价】引擎钩子增至 14 个；处理器在 sink 锁内同步执行（synchronized 可重入，同线程回话安全——文档钉死「处理器不得跨线程回话」）；观察处理器看到的链中值时点与 pi 有出入（D9）。[内部；模型可见面＝替换生效]

**裁决②：boundary 落引擎钩子（turn_end 修真 should_stop_after_turn；agent_before_settle 新增 before_settle 钩），桥恒装、无订阅弃权**
- 【场景】pi 的 turn_end 包裹 finishTurn、before_settle 替换 post-run ③ 队列判定。java 的 `before_run_end` 是 void 观察型、在 finishRun 收口处，表达不了 continue。
- 【决定】turn_end 复用 `should_stop_after_turn`（位置与 pi 同位：决策消费前），顺手修真上下文载荷（喂 lane.partial＋空工具结果的存量窄差）；before_settle 新钩挂在 `checkAfterRun` 末尾，弃权 ⇒ 回落 `hasQueuedMessages`——agent-core 独立使用行为逐比特不变。
- 【风险/代价】引擎又增 1 钩（15 个）；CONTINUE 会被工具结果/steer/followUp 自然请求清零（PiLoopRunner:174-184 既有语义）——pi 的 extensionContinue 同受自然请求抑制，同构。[内部]

**裁决③：宿主发射集中为 `SessionEventFirer`，同步执行、错误纪律逐族照 pi**
- 【场景】pi 的发射全是 await 的；java eventHub 无 await 语义（批 4 裁决⑤已登记）。
- 【决定】java 扩展处理器本就是同步签名——firer 在调用线程同步跑完即等价 pi 的 await 完成时点；`user_bash` 异常重抛、`input`/`before_agent_start` 逐处理器捕获、before_switch/fork cancel 短路，各族纪律按 §1.1 逐字。
- 【风险/代价】处理器跑在 TUI/RPC 调用线程，慢处理器阻塞 UI——pi 同（await 在同一线程）。[内部]

**裁决④：reason 映射集中三处，初始恒 startup；reload 臂不动**
- 【场景】pi 的五级 reason 由 runtime 替换流构造；java 的替换流形状不同（原地 attach／fork 新建／create 新建）。
- 【决定】startup＝loadExtensions（初始恒 startup，CLI resume 也是——pi 逐字）；resume＝`switchTo` 集中；fork＝fork 两法集中；new＝`createReplacing`＋调用点；reload 无 java 面（B246 在册）。
- 【风险/代价】TUI 退出不 close ⇒ quit 臂在 TUI 永不发射（存量关闭面缺口，非本批范围；RPC/print/Web 的 close 路径全覆盖）。[用户可见，边缘]

**裁决⑤：文件路径载荷位装会话 id**
- 【场景】pi 的 `previousSessionFile`/`targetSessionFile` 是 JSONL 文件路径；java 持久后端是 SQLite/JSONL 仓，公共标识是 String 会话 id（无文件路径面）。
- 【决定】字段名照 pi，内容装会话 id。
- 【风险/代价】跨实现的扩展若把该字段当路径读会失望——java 方言，文档钉死。[内部]

**裁决⑥：before_compact 走引擎钩子载荷/结果扩形，保留既有 keepEntries plan 臂**
- 【场景】java 已有 `before_compaction` 钩（plan.keepEntries 臂），pi 的结果是 {cancel, compaction}。
- 【决定】结果三臂并存：cancel＞supplied＞keepEntries＞默认生成；载荷前置 prepare 补全（pi 的 preparation 在 java 就是 CompactionService.Plan）。
- 【风险/代价】applyCompaction 重排（prepare 前置）；plan 在钩子时点必有（prepare 返回 null 时 applyCompaction 根本不进——两道守卫已在）。supplied 臂的 tokensBefore 以扩展值为准（pi 同）。[模型可见：扩展可否决/接管压缩]

**裁决⑦：`SessionCompactEvent` 直接携带 `Entry.Compaction`**
- 【场景】pi 的 compactionEntry 是 SessionManager 条目；java 的对位就是 `Entry.Compaction`（含 retainedTail 方言字段、无 systemMessage）。
- 【决定】不另造投影类型，事件载荷＝引擎落库的同一条目引用。
- 【风险/代价】事件类型暴露 agent-core Entry 体系（扩展 API 面扩大，但 ExtensionEvent 本就用 agent-core 类型）。[内部]

---

## §5 窄差表（本批新增，接续批 4 的 D1–D7）

| # | 内容 | 等级 |
|---|---|---|
| D9 | message_end 观察处理器在 sink 时点与结果处理器同批火一次（看到链中值）；pi 观察与结果同序但后续观察看到链终值。替换对宿主监听者/TUI/RPC 的效果与 pi 原地改写一致 | [内部] |
| D10 | `user_bash` 的 `{operations}` 臂不移植（java 无 per-call BashOperations 缝；ToolContext 级 ShellExecutor 注入是既有等价物）；`{result}` 替换臂全落 | [内部] |
| D11 | `before_agent_start` 的 `systemPromptOptions` 可变对象不移植（java 无 BuildSystemPromptOptions 面；selectedTools 编辑臂随之不移植）；`systemPrompt` 覆盖是 harness **全局态**（粘性），非 pi 的 per-prompt options | [模型可见：覆盖持续生效] |
| D12 | `input` 事件 `images` 恒 null（java prompt 无图片面）；`source` 无 "extension" 生产者（sendMessage(triggerTurn) 未实现在册 E5-12） | [内部，边缘] |
| D13 | （预留，实施期发现再填） | |
| D14 | `previousSessionFile`/`targetSessionFile` 载荷装会话 **id**（裁决⑤） | [内部] |
| D15 | boundary 预览的 `contextMessages`/`llmMessages` 同源（java 无 pi 的 AgentMessage→LLM Message 二次转换缝） | [内部] |
| D16 | `session_before_fork` 的 `skipConversationRestore` 结果臂收下不消费（java fork 无会话恢复跳过面） | [内部] |
| D17 | `forkCopy`（整枝拷贝）映射 `before_fork{entryId:当前叶, position:"at"}`；pi 的 fork 恒显式 entryId | [内部] |
| D18 | pi 的 emitUserBash 模式间不对称（interactive catch＋不回落、rpc 传播）；java 单发射点（executeBash），异常统一沿调用方错误链 | [内部] |
| D19 | `before_agent_start` 的 result.message 落盘序为「custom 条目先于用户消息」（java 在 drive 前 append；pi 插在本轮 user 之后）——LLM 上下文内同为 user 序消息，相对序有差 | [内部，边缘] |
| D20 | /new 臂的 shutdown 在 start 之后（java 需先建会话才知目标 id；pi 先 teardown 后 create） | [内部] |
| D21 | boundary 预览 `pendingMessages` 由队列**文本**重建 UserMessage（java QueueManager 只读文本）；java 无 `_pendingCustomMessages` 面（sendMessage 即写） | [内部] |
| D22 | `session_before_compact` 载荷 `preparation.fileOps` 不译（java 的 fileOps 在 compact() 内收集，prepare 时点缺席） | [内部] |
| D23 | 原地 resume 的 shutdown/start 在**同一条 bus** 上发射（pi 是旧/新两条 runner） | [内部] |

## §6 新登记开放项（闭环时入台账 docs/04）

| 号 | 内容 |
|---|---|
| **B251** | **forked 会话不加载扩展**：`forkInMemory`/持久 fork 路直 new 私有构造、不过 loadExtensions ⇒ fork/clone 后扩展机制整体消失（工具/命令/事件订阅全无）；`session_start{fork}` 臂随之无消费者。触发条件＝fork 路径重用 assemble 的扩展装配 |
| **B252** | **TUI/RPC 切换路径旧会话不 close**：/new /resume /fork /clone、RPC new_session/fork/clone 均摘订阅即弃（harness 线程池/资源待 GC）；TUI 退出亦不 close ⇒ `session_shutdown{quit}` 在 TUI 永不发射。触发条件＝会话生命周期统一治理 |

## §7 测试计划

pi 侧参照：同批 4——扩展事件无独立测试文件可整体移植，测试全为 java 新写，语义锚 §1 逐字段。

1. **message_end（引擎钩＋适配器）**：替换 ⇒ lane/transcript/下游事件三处同见新引用（对拍 §2.2 链）；role 不同 ⇒ emitError 文本逐字＋跳过；链式（两处理器顺序替换，后者见前者产物）；处理器抛错 ⇒ 上报＋继续；无替换 ⇒ 原引用同一性（`==`）；宿主桥无双发（处理器恰火一次）。
2. **boundary turn_end**：无订阅 ⇒ 钩子弃权且 outcome 照记（before_settle 读到）；entryId 解析失败 ⇒ "<boundary>" 上报文本逐字＋不派发；草稿四型逐型 commit ⇒ transcript 条目＋EntryAppended＋投影生效（context_edit 剔除目标、compaction 条目 fromHook=true）；continue=true ⇒ CONTINUE；continue 但不可续 ⇒ 上报文本逐字＋弃权；每处理器后预览重建（第二处理器看到第一处理器的 entries 与更新后 context）；无效草稿 ⇒ `Invalid boundary entries: ` 前缀＋空返回。
3. **boundary before_settle**：无订阅 ⇒ 引擎回落 hasQueuedMessages（无桥行为逐比特不变回归）；continue ⇒ drive 再 continue 一 pass（runId 轮换）；continue 不可续 ⇒ 上报＋settle；abort 中 ⇒ false。
4. **before_compact**：cancel ⇒ 手动路抛 "Compaction cancelled"、自动路 CompactionEnd aborted=true 且无 errorMessage（pi 同）；supplied ⇒ 默认生成器未被调、Entry.Compaction 载荷全等于供应值＋fromHook=true、`session_compact.fromExtension=true`（D6 销号对拍）；`compactionEntry` 载荷＝落库同一条目（摘要/首保 id/tokensBefore/usage/details 逐字段）；载荷 preparation 字段对拍 Plan。
5. **input**：transform 链（两处理器）⇒ 展开/路由/驱动拿到终值（断言 expandUserPrompt 收到变换后文本——**input 先于展开**对拍 pi :1991）；handled ⇒ SessionResult "handled"、零驱动；无订阅 ⇒ 零成本原文。
6. **user_bash**：`{result}` ⇒ 不执行真命令、recordBashResult 落 entry（excludeFromContext 透传）；处理器抛错 ⇒ emitError＋异常沿调用方链（无回落执行）。
7. **before_agent_start**：`systemPrompt` ⇒ harness 全局生效且后续处理器看到链中值；`message` ⇒ custom 条目入 transcript；抛错 ⇒ 上报＋继续。
8. **生命周期**：初始装配 ⇒ session_start{startup}（含 resumeLike 的 CLI resume——pi 同）；close ⇒ shutdown{quit}；switchTo 全序（cancel 中止 attach／正常路 shutdown→attach→start、previousSessionFile＝旧 id）；fork cancel ⇒ Optional.empty＋零新建；createReplacing ⇒ start{new, previous}；noExtensions ⇒ 全 firing 零效果（before 系返回不取消）。
9. **规格面**：12 新 spec 线名逐字；MESSAGE_END R 改签后存量观察订阅编译/行为兼容。

**验证任务 V1**（T1 先行）：`PiLaneSink.emit` 替换缝对「全部 MessageEnd 生产者」的覆盖核查（PiLoop/PiLoopRunner/PiLoopTools/RunFailure 六处 grep 对拍——漏一处则该源的消息替换不生效，须在设计稿闭环偏差中登记）。

## §8 对账表（map/04 逐行翻转预期）

D 域（基线：Σ(w×c)＝44.0/83 逐行实算；模块 261.5/496；全仓 926.5/1381）：

| 行 | 权重 | 今 | 后 | Δ(w×c) |
|---|---:|---|---|---:|
| D-3 session_start | 2 | 缺失 | **对齐** | +2.0 |
| D-5 session_before_switch | 2 | 缺失 | **对齐** | +2.0 |
| D-6 session_before_fork | 2 | 缺失 | **对齐** | +2.0 |
| D-7 session_before_compact | 2 | 存疑 | **对齐**（cancel＋全载荷＋supplied 臂） | +1.0 |
| D-8 session_compact | 2 | 存疑 | **对齐**（compactionEntry 落，D4 销号） | +1.0 |
| D-10 session_shutdown | 2 | 缺失 | **对齐** | +2.0 |
| D-21 before_agent_start | 3 | 缺失 | **对齐**（D11 窄差） | +3.0 |
| D-24 agent_before_settle | 2 | 缺失 | **对齐** | +2.0 |
| D-29 turn_end | 2 | 缺失 | **对齐**（B49 残余面随之销号复核） | +2.0 |
| D-32 message_end | 2 | 存疑 | **对齐**（替换臂落） | +1.0 |
| D-40 user_bash | 1 | 缺失 | **对齐**（D10 窄差） | +1.0 |
| D-41 input | 3 | 缺失 | **对齐**（D12 窄差） | +3.0 |

D 域 Δ＝**+22.0**：44.0 → **66.0/83＝79.5%**（对齐 29／存疑 5〔#12/#17/#18/#19 加 #8 翻后剩 #7 翻 ⇒ 复核〕／缺失 7）。
模块合计：261.5 → **283.5/496 ≈ 57.2%**；全仓 926.5 → **948.5/1381 ≈ 68.7%**。
（闭环按当时真实基线逐行重算——批 4 已知「汇总行与逐行不符」同族教训。）

## §9 不碰清单

provider 请求面四件（**B248**，等 ai 层注入口）；`session_before_tree`/`session_tree`（**B245**）；reload 臂与 `usesDefaultTools`（**B246**）；forked 会话扩展加载（新登记 **B251**）；旧会话 close 治理（新登记 **B252**）；D′ 扩展 UI 注入面；E5/E6 其余；`AgentSession.java` 拆分（E1，本批继续在 1,620 行上加约 200 行——拆分债在册不扩大范围）；ui_prompt_*/model_select/cache_warming_decision/mcp_servers_change/project_trust（各随子系统）。
