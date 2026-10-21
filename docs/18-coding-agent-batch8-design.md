# coding-agent 批 8 设计稿：tree 两扩展事件＋TUI 摘要导航流（B245）

【背景摘要】本稿是 coding-agent 对齐批 8 的设计。闭环台账 B245（tree 扩展事件
`session_before_tree`/`session_tree`＋TUI 树导航摘要三选对话框流）。批 7（docs/17）
闭环后 D 域（扩展事件域）仅剩的 tree 两事件开放项随本批销号；TUI 侧把 `/tree` 从
「选 lane 后 fork」修为 pi 的「选 entry 后原地导航＋可选摘要」。

术语（首现释义）：

- **B245**：工单编号，见 `docs/04-open-items-register.md`。
- **lane（车道）**：java 侧会话内一条独立对话分支线；pi 无此概念，pi 的「分支」是
  entry 树上的指针移动。
- **navigateTree（树导航）**：pi `agent-session.ts` 的方法，在当前会话文件内把叶指针
  移到历史 entry；可先把被 abandon 的分支压缩成一条摘要 entry。
- **fromHook**：摘要 entry 上的布尔标记，`true` 表示摘要由扩展提供而非 pi 默认生成器。
- **扩展事件 / 结果臂**：扩展可订阅的回调点；「结果臂」指处理器返回值能改变宿主行为
  （取消、替换、覆盖），「观察型」只通知。
- **firer**：java 侧宿主发射器 `SessionEventFirer`，pi `ExtensionRunner.emit` 的对位。
- **窄差**：java 与 pi 无法完全对齐、登记在册的差异。

---

## §0 基线与预期翻动

| 口径 | 批 7 后基线 | 本批预期 | 依据 |
|---|---|---|---|
| D 域（map/04 §D，Σ权重 83） | 71.5（86.1%） | 73.0（88.0%） | D-12 存疑→对齐 +0.5；D-13 缺失→对齐 +1.0 |
| 模块（Σ 496） | 289.0（58.3%） | 290.5（58.6%） | 随 D 域 +1.5 |
| 全仓（Σ 1381） | 954.0（69.1%） | 955.5（69.2%） | 同上 |

TUI 族行（C-3 `/tree`、map/05 S12/S13）本批**不翻判定**（pi `tree-selector.ts`
1,435 行的 entry 树选择器本体不在本批范围），只更新证据列。

---

## §1 pi 侧事实（取证，锚点 `ea448f454`，只走 `git show`）

### 1.1 事件类型（`core/extensions/types.ts` 逐字）

`:818-830` TreePreparation：

```ts
/** Preparation data for tree navigation */
export interface TreePreparation {
	targetId: string;
	oldLeafId: string | null;
	commonAncestorId: string | null;
	entriesToSummarize: SessionEntry[];
	userWantsSummary: boolean;
	/** Custom instructions for summarization */
	customInstructions?: string;
	/** If true, customInstructions replaces the default prompt instead of being appended */
	replaceInstructions?: boolean;
	/** Label to attach to the branch summary entry */
	label?: string;
}
```

`:832-846` 两事件：

```ts
/** Fired before navigating in the session tree (can be cancelled) */
export interface SessionBeforeTreeEvent {
	type: "session_before_tree";
	preparation: TreePreparation;
	signal: AbortSignal;
}

/** Fired after navigating in the session tree */
export interface SessionTreeEvent {
	type: "session_tree";
	newLeafId: string | null;
	oldLeafId: string | null;
	summaryEntry?: BranchSummaryEntry;
	fromExtension?: boolean;
}
```

`:1488-1501` 结果类型：

```ts
export interface SessionBeforeTreeResult {
	cancel?: boolean;
	summary?: {
		summary: string;
		details?: unknown;
		usage?: Usage;
	};
	/** Override custom instructions for summarization */
	customInstructions?: string;
	/** Override whether customInstructions replaces the default prompt */
	replaceInstructions?: boolean;
	/** Override label to attach to the branch summary entry */
	label?: string;
}
```

`:1592-1596` 订阅面（before 是结果臂，tree 是观察型）：

```ts
on(
	event: "session_before_tree",
	handler: ExtensionHandler<SessionBeforeTreeEvent, SessionBeforeTreeResult>,
): () => void;
on(event: "session_tree", handler: ExtensionHandler<SessionTreeEvent>): () => void;
```

### 1.2 派发语义（`core/extensions/runner.ts` 逐字）

`:196-216`：`session_before_tree` 属 `SessionBeforeEvent` 联合，通用 `emit`
的「可取消 before 族」一员。`:1083-1118` 通用 emit 纪律：

```ts
private isSessionBeforeEvent(event: RunnerEmitEvent): event is SessionBeforeEvent {
	return (
		event.type === "session_before_switch" ||
		event.type === "session_before_fork" ||
		event.type === "session_before_compact" ||
		event.type === "session_before_tree"
	);
}

async emit<TEvent extends RunnerEmitEvent>(event: TEvent): Promise<RunnerEmitResult<TEvent>> {
	const ctx = this.createContext();
	let result: SessionBeforeEventResult | undefined;

	for (const { ext, handlers } of snapshotEventHandlers(this.extensions, event.type)) {
		for (const handler of handlers) {
			try {
				const handlerResult = await handler(event, ctx);

				if (this.isSessionBeforeEvent(event) && handlerResult) {
					result = handlerResult as SessionBeforeEventResult;
					if (result.cancel) {
						return result as RunnerEmitResult<TEvent>;
					}
				}
			} catch (err) {
				// …emitError，继续下一个处理器
			}
		}
	}
	return result as RunnerEmitResult<TEvent>;
}
```

要点：`cancel` 短路（后续处理器不跑）；否则**最后发言者赢**（返回非 undefined
的最后一个处理器的结果）；逐处理器 catch ⇒ emitError ⇒ 继续。`session_tree`
走同一 emit，返回值被忽略（观察型）。

### 1.3 宿主流（`core/agent-session.ts` navigateTree :3964-4156 逐字序）

1. `:3973-3982` 双门：`isStreaming` / `isCompacting` 各抛一句错文。
2. `:3989-3991` `targetId === oldLeafId` ⇒ `{ cancelled: false }` 不动。
3. `:3985-3987` `options.summarize && !this.model` ⇒
   `throw new Error("No model available for summarization")`。
4. `:3993-3999` 查目标 entry（缺 ⇒ throw）；`collectEntriesForBranchSummary`
   **无条件先收集**（preparation 载荷要）。
5. `:4006-4016` 组装 preparation（customInstructions/replaceInstructions/label
   先取 options 值）。
6. `:4018` `this._branchSummaryAbortController = new AbortController()` —
   **在事件发射前**（事件带它的 signal）。
7. `:4024-4051` 发射（`hasHandlers` 门）：

```ts
if (this._extensionRunner.hasHandlers("session_before_tree")) {
	const result = (await this._extensionRunner.emit({
		type: "session_before_tree",
		preparation,
		signal: this._branchSummaryAbortController.signal,
	})) as SessionBeforeTreeResult | undefined;

	if (result?.cancel) {
		return { cancelled: true };
	}

	if (result?.summary && options.summarize) {
		extensionSummary = result.summary;
		fromExtension = true;
	}

	// Allow extensions to override instructions and label
	if (result?.customInstructions !== undefined) {
		customInstructions = result.customInstructions;
	}
	if (result?.replaceInstructions !== undefined) {
		replaceInstructions = result.replaceInstructions;
	}
	if (result?.label !== undefined) {
		label = result.label;
	}
}
```

   注意：**扩展摘要仅在用户本来就要摘要时生效**（`result?.summary && options.summarize`）；
   三个 override 不受 summarize 门限制。
8. `:4054-4085` 默认生成器门：`options.summarize && entriesToSummarize.length > 0
   && !extensionSummary`。`result.aborted` ⇒ `{ cancelled: true, aborted: true }`；
   `result.error` ⇒ throw。extensionSummary 臂：text/details/usage 全取扩展值。
9. `:4089-4103` 新叶：user 消息/custom_message ⇒ 叶=父＋editorText；否则 叶=targetId。
10. `:4106-4115` 有摘要 ⇒ `branchWithSummary(newLeafId, summaryText, summaryDetails,
    fromExtension, summaryUsage)`（**fromExtension 透传**）；label 贴摘要 entry。
11. `:4117-4126` 无摘要 ⇒ resetLeaf/branch；label 贴 targetId。
12. `:4136-4137` `_refreshFinalizedContext()`＋`_restoreToolsFromTranscript()`。
13. `:4140-4147` 发射 session_tree：

```ts
await this._extensionRunner.emit({
	type: "session_tree",
	newLeafId: this.sessionManager.getLeafId(),
	oldLeafId,
	summaryEntry,
	fromExtension: summaryText ? fromExtension : undefined,
});
```

14. `:4153-4155` finally：`_branchSummaryAbortController = undefined`＋
    `_resolveIdleWaitIfIdle()`。

### 1.4 TUI 摘要流（`modes/interactive/interactive-mode.ts:5560-5690` 逐字要点）

树选择器 onSelect 回调：

1. `:5574-5579` 选中当前叶 ⇒ `done()`＋"Already at this point"。
2. `:5584` **先关选择器**。
3. `:5590` skipPrompt 门：`if (!this.settingsManager.getBranchSummarySkipPrompt())`
   才进对话循环（`branchSummary.skipPrompt` 默认 false，`settings-defaults.ts:15`）。
4. `:5592-5615` 三选对话框 `showExtensionSelector("Summarize branch?",
   ["No summary", "Summarize", "Summarize with custom prompt"])`：
   - Esc（undefined）⇒ `this.showTreeSelector(entryId)` 重开树选择器并返回；
   - "Summarize with custom prompt" ⇒ `showExtensionEditor("Custom summarization
     instructions")`，编辑器取消 ⇒ `continue` 回三选；
   - 完成选择 ⇒ break。
5. `:5618-5621` 提交导航前：`isStreaming` ⇒ `restoreQueuedMessagesToEditor()`＋
   `await this.session.abort()`。
6. `:5624-5629` 重查 `isCompacting` ⇒ showError 双门错文第二句，返回。
7. `:5631-5643` 摘要时：Esc 换绑＋状态指示器：

```ts
let showingSummaryIndicator = false;
const originalOnEscape = this.defaultEditor.onEscape;

if (wantsSummary) {
	this.defaultEditor.onEscape = () => {
		this.session.abortBranchSummary();
	};
	this.chatContainer.addChild(new Spacer(1));
	this.showStatusIndicator(new BranchSummaryStatusIndicator(this.ui));
	showingSummaryIndicator = true;
	this.ui.requestRender();
}
```

   换绑理由（`:3070-3082` 默认 onEscape）：默认 Esc 在非流式时**不会**中止摘要
   （走 bash/清编辑器/双击 Esc 分支），所以摘要期要单独绑。
8. `:5646-5684` `navigateTree(entryId, { summarize: wantsSummary,
   customInstructions })`，结局四分：
   - `result.aborted` ⇒ "Branch summarization cancelled"＋重开树选择器；
   - `result.cancelled` ⇒ "Navigation cancelled"；
   - 成功 ⇒ `chatContainer.clear()`＋`renderInitialMessages()`＋
     `if (result.editorText && !this.editor.getText().trim())
     this.editor.setText(result.editorText)`＋"Navigated to selected point"＋
     `void this.flushCompactionQueue({ willRetry: false })`；
   - catch ⇒ showError。
   - finally：清指示器＋**恢复 onEscape**。

### 1.5 pi 测试锚点（行为规格）

- `test/branch-summary-extensions.test.ts`：扩展给 summary（含 usage）＋
  `summarize:true` ⇒ summaryEntry `fromHook === true`、文本/usage 全取扩展值，
  usage 进会话总计（getSessionStats）。
- `test/suite/regressions/3688-tree-cancel-compacting.test.ts`：
  `session_before_tree` 返回 `{cancel:true}` ⇒ 结果恰为 `{cancelled:true}`、
  `isCompacting === false`、叶不动。
- `test/suite/regressions/9178-tree-during-compaction.test.ts`：压缩中树操作的互斥。

---

## §2 java 侧现状与缺口（实读）

### 2.1 扩展事件三件套（批 4/5 已立的模式）

- `extension/event/ExtensionEvent.java`：sealed ADT，before 族已有
  `SessionBeforeSwitchEvent`(:165)/`SessionBeforeForkEvent`(:173)；无 tree 两事件。
- `extension/event/ExtensionResults.java`：结果 record 集中地
  （`SessionBeforeForkResult` :66 等）；无 `SessionBeforeTreeResult`。
- `extension/event/ExtensionEventSpec.java`：spec 令牌；无 SESSION_BEFORE_TREE/
  SESSION_TREE。
- `extension/SessionEventFirer.java`：before 族两法（cancel 短路＋最后发言者赢，
  :113-153）已是 pi 通用 emit 的逐字对位——本批加一个同构方法即可。

### 2.2 `AgentSession.navigateTree`（`AgentSession.java:1041-1164`）

已落（批 agent-core-4）：双门错文逐字、noop 门、新叶三分支、label、
`replaceTranscript`＋`restoreToolsFromTranscript`、abort token finally 清零。

缺口（对照 §1.3 逐序）：

1. **无事件发射**——第 6/7/13 步全缺（`session_before_tree` 无发射点，
   `session_tree` 无事件）。
2. **收集时机差**：`BranchSummarization.collectEntries` 只在 summarize 分支内调
   （:1086），pi 是无条件先收集（preparation 载荷）。不重排则 preparation 给不出
   `entriesToSummarize`/`commonAncestorId`。
3. **缺模型门**：pi 第 3 步 `summarize && !model` ⇒ throw；java 无此检（
   `harness.getModel()` 直用）。
4. **abort token 创建时机**：java 在收集前（:1080）创建，pi 在 preparation 后、
   事件前（:4018）——事件要携带 signal，java 需保持「事件前已建」。
5. **`fromHook` 恒 FALSE**（:1132）：无扩展摘要臂。
6. **`TreeNavigation` 无 `aborted` 字段**（:1044）：pi 区分 `{cancelled:true}`
   （扩展取消）与 `{cancelled:true, aborted:true}`（摘要被中止），TUI 两臂文案与
   是否重开选择器都不同；java 现在把 aborted 合并进 cancelled（:1100）。
7. 双门错文第一句 java 用 `isRunning()` 对 pi `isStreaming`（已对齐，不动）。

### 2.3 死引擎钩 `onBeforeNavigation`

`pi-java-agent-core/.../hook/HookSystem.java:89`（`onBeforeNavigation`）＋
`:286`（`fireBeforeNavigation`）＋`BeforeNavigationHook.java`＋
`NavigationContext.java`：**全仓零调用点**（map/04 D-12 证据「引擎钩子
onBeforeNavigation:88 未桥接」）。pi 无引擎层 tree 钩——事件由 agent-session 直发。
留着是双轨隐患（两个 firing 点语义不同：引擎钩 void 无结果臂），本批删除。

### 2.4 TUI 现状

- `TreeSelectorScreen.java`（63 行）：列 **lane 名＋leafId**（:26-29），选中＝
  `forkFromEntry`（:56）。`/tree` 与 `/fork`（空参）共用同一 marker
  `CommandRegistry.UI_TREE_SELECTOR`（`SessionCommands.java:40,65`）⇒ `/tree`
  实际上是 fork，**导航路径从未接线**。
- `PiTuiApp.java:428-435`：UI_TREE_SELECTOR ⇒ 开 TreeSelectorScreen。
  overlay 机制：`onKeyEvent` 直到 `isDone()` ⇒ `apply(session, switcher)`（:310-320，
  **apply 跑在渲染线程**）。
- 已有可复用件：`SelectList`（三选对话框基座，Enter 确认/Esc 取消）；
  `EditorComponent`（自定义指令输入框基座，`onSubmit`/`setText`）；
  `StatusIndicator.BranchSummary`（:116，`Kind.BRANCH_SUMMARY`，重试事件已驱动）；
  `ChatPanel.clear()`（:41）；`ChatMessage.from(entry)`（弱形状分支摘要行已会渲染）；
  `chatScreen.isInputEmpty()/insertText()`（editorText 回填面）。
- `ChatScreen.setIndicator` 是 **private**（:318）；`clearIndicator(Kind)` 已 public
  （:331）。`showError`/`showStatus` 均 private（:357/:313）。
- 摘要期 Esc：java 全局 INTERRUPT ⇒ `mode.abort()` ⇒ `session.abort()`
  （`InteractiveMode.java:110-112`），`AgentSession.abort()` **无 isRunning 门、
  首两步即 abortCompaction/abortBranchSummary**（:991-1002）⇒ 摘要中按 Esc 已经
  能中止分支摘要。pi 需要换绑是因为其默认 onEscape 在非流式不碰摘要（§1.4.7）。
- 设置面：`SettingsAccessors.getBranchSummarySkipPrompt()`（:334）批 4 已备。
- 导航后整表重建：java 无「从 entry 重建聊天区」方法（B38 同族缺口）；本批为树导航
  加一个（`accumulatedEntries()` :870＋`ContextEntries.pathToLeaf`＋快照 leafId）。
- worker 线程范式：`CommandUtil.async`（虚拟线程）是既有先例；`navigateTree`
  内摘要是**同步阻塞** LLM 调用，绝不可跑渲染线程。

---

## §3 设计

三个任务：**T1**＝扩展事件三件套＋firer（coding-agent）；**T2**＝
`AgentSession.navigateTree` 重构（发射＋aborted 区分＋死钩删除）；
**T3**＝TUI 导航流（对话框＋接线＋重建）。

### T1：事件 ADT＋spec＋firer

#### 3.1 `ExtensionResults` 新增（pi §1.1 对位）

```java
/**
 * pi {@code TreePreparation}（types.ts:818-830）——导航准备数据。
 * {@code entriesToSummarize} 是<b>活列表</b>（与默认生成器共用同一引用，
 * pi 处理器原地改它会影响摘要输入，agent-session.ts:3995/4056 同构）。
 */
public record TreePreparation(String targetId, String oldLeafId, String commonAncestorId,
                              List<Entry> entriesToSummarize, boolean userWantsSummary,
                              String customInstructions, Boolean replaceInstructions,
                              String label) {}

/** pi {@code SessionBeforeTreeResult.summary}（types.ts:1490-1494）。 */
public record ExtensionTreeSummary(String summary, Object details, Usage usage) {}

/**
 * pi {@code SessionBeforeTreeResult}（types.ts:1488-1501）——cancel 短路；
 * summary 仅在用户本来要摘要时生效；三个 override 不受 summarize 门限制。
 */
public record SessionBeforeTreeResult(Boolean cancel, ExtensionTreeSummary summary,
                                      String customInstructions, Boolean replaceInstructions,
                                      String label) {}
```

（`Usage`＝`com.pijava.ai.Usage`；`null ≙ undefined`。）

#### 3.2 `ExtensionEvent` 新增两变体

```java
/**
 * pi {@code session_before_tree}（types.ts:832-838，可取消）——{@code signal}
 * 是分支摘要中止令牌（pi AbortSignal 对位 {@link CompactionAbortToken}，
 * 与 SessionBeforeCompactEvent 同型）。
 */
record SessionBeforeTreeEvent(ExtensionResults.TreePreparation preparation,
                              CompactionAbortToken signal) implements ExtensionEvent {}

/**
 * pi {@code session_tree}（types.ts:840-846，观察型）——{@code summaryEntry}/
 * {@code fromExtension} 可空；fromExtension 仅在有摘要时有值（pi :4145）。
 */
record SessionTreeEvent(String newLeafId, String oldLeafId,
                        Entry.BranchSummary summaryEntry, Boolean fromExtension)
        implements ExtensionEvent {}
```

#### 3.3 `ExtensionEventSpec` 两令牌

```java
/** pi {@code "session_before_tree"}（types.ts:1593，可取消/可给摘要/可覆盖）。 */
public static final ExtensionEventSpec<SessionBeforeTreeEvent, SessionBeforeTreeResult>
    SESSION_BEFORE_TREE = new ExtensionEventSpec<>("session_before_tree");

/** pi {@code "session_tree"}（types.ts:1596，观察型）。 */
public static final ExtensionEventSpec<SessionTreeEvent, Void> SESSION_TREE =
    new ExtensionEventSpec<>("session_tree");
```

#### 3.4 `SessionEventFirer` 两方法（pi §1.2 通用 emit 逐字）

```java
/**
 * pi {@code session_before_tree}（通用 emit，runner.ts:1089-1118）：
 * cancel 短路；否则<b>最后发言者赢</b>；逐处理器 catch ⇒ emitError ⇒ 继续。
 *
 * @return 最后一个非 null 结果；无订阅者/无人发言 ⇒ null
 */
public SessionBeforeTreeResult sessionBeforeTree(TreePreparation preparation,
                                                 CompactionAbortToken signal) {
    if (!bus.hasHandlers(SESSION_BEFORE_TREE)) {
        return null;
    }
    var event = new SessionBeforeTreeEvent(preparation, signal);
    SessionBeforeTreeResult result = null;
    for (var h : bus.snapshot(SESSION_BEFORE_TREE)) {
        try {
            var r = h.handle(event, ctx);
            if (r != null) {
                result = r; // 最后发言者赢（pi :1099）
                if (Boolean.TRUE.equals(r.cancel())) {
                    return result; // cancel 短路（pi :1100-1104）
                }
            }
        } catch (Throwable e) {
            bus.emitError(h.error(e));
        }
    }
    return result;
}

/** pi {@code session_tree}（观察型；通用 emit 返回值忽略）。 */
public void sessionTree(String newLeafId, String oldLeafId,
                        Entry.BranchSummary summaryEntry, Boolean fromExtension) {
    if (!bus.hasHandlers(SESSION_TREE)) {
        return;
    }
    var event = new SessionTreeEvent(newLeafId, oldLeafId, summaryEntry, fromExtension);
    for (var h : bus.snapshot(SESSION_TREE)) {
        try {
            h.handle(event, ctx);
        } catch (Throwable e) {
            bus.emitError(h.error(e));
        }
    }
}
```

### T2：`AgentSession.navigateTree` 重构（pi §1.3 逐字序）

#### 3.5 `TreeNavigation` 加 `aborted` 字段

```java
/** pi {@code navigateTree} 的返回：取消旗＋摘要中止旗＋编辑器回填文本＋摘要 entry（可无）。 */
public record TreeNavigation(boolean cancelled, boolean aborted, String editorText,
                             Entry.BranchSummary summaryEntry) {}
```

调用点只有两测试类（`NavigateTreeTest`/`RestoreToolsOnNavigateTest`），随批更新。

#### 3.6 主体重构（新序对照 pi :3989-4147）

```java
public TreeNavigation navigateTree(String targetId, boolean summarize,
                                   String customInstructions, boolean replaceInstructions,
                                   String label) {
    // ①② 双门＋noop 门不变（:1059-1074 现状保留）。
    // ③ pi :3985-3987 模型门（新增）：
    if (summarize && harness.getModel() == null) {
        throw new IllegalStateException("No model available for summarization");
    }
    // ④ 查目标（现状保留）后，pi :3995-3999 无条件先收集：
    var collected = BranchSummarization.collectEntries(oldLeafId, targetId, session);
    // ⑤ pi :4006-4016 preparation（本地覆盖变量先取入参）：
    String effectiveInstructions = customInstructions;
    boolean effectiveReplace = replaceInstructions;
    String effectiveLabel = label;
    // ⑥ pi :4018 token 在事件前建（现状 :1080 的位置上移到收集后、事件前）：
    branchSummaryAbort = new CompactionAbortToken();
    try {
        // ⑦ pi :4024-4051 发射＋三臂：
        String extensionText = null; Object extensionDetails = null;
        com.pijava.ai.Usage extensionUsage = null;
        boolean fromExtension = false;
        if (extensionFirer != null) {
            var preparation = new ExtensionResults.TreePreparation(targetId, oldLeafId,
                collected.commonAncestorId(), collected.entries(), summarize,
                customInstructions, replaceInstructions, label);
            var r = extensionFirer.sessionBeforeTree(preparation, branchSummaryAbort);
            if (r != null) {
                if (Boolean.TRUE.equals(r.cancel())) {
                    return new TreeNavigation(true, false, null, null); // pi :4032
                }
                if (r.summary() != null && summarize) { // pi :4036 summarize 门
                    extensionText = r.summary().summary();
                    extensionDetails = r.summary().details();
                    extensionUsage = r.summary().usage();
                    fromExtension = true;
                }
                if (r.customInstructions() != null) {
                    effectiveInstructions = r.customInstructions();
                }
                if (r.replaceInstructions() != null) {
                    effectiveReplace = r.replaceInstructions();
                }
                if (r.label() != null) {
                    effectiveLabel = r.label();
                }
            }
        }
        // ⑧ 默认生成器门：summarize && 非空 && 无扩展摘要（pi :4054）；
        //    Aborted ⇒ new TreeNavigation(true, true, null, null)（pi :4069 aborted 旗）；
        //    生成用 effectiveInstructions/effectiveReplace；扩展臂 text/details/usage 全取扩展值。
        // ⑨⑩⑪ 新叶/branchWithSummary/label（现状保留；两处改动：
        //    branchWithSummary 的 fromHook 参数 Boolean.FALSE ⇒ fromExtension；
        //    label 变量 ⇒ effectiveLabel；customInstructions ⇒ effectiveInstructions）。
        // ⑫ replaceTranscript＋restoreToolsFromTranscript（现状保留）。
        // ⑬ pi :4140-4147 发射 session_tree：
        if (extensionFirer != null) {
            extensionFirer.sessionTree(tree2.getLeafId()/* 导航后重读 */, oldLeafId,
                summaryEntry, summaryText != null ? fromExtension : null);
        }
        return new TreeNavigation(false, false, editorText, summaryEntry);
    } finally {
        branchSummaryAbort = null; // pi :4155
    }
}
```

要点：

- **collected.entries() 传活引用**进 preparation（§3.1 注释），默认生成器读同一
  列表——pi 处理器原地改数组会影响摘要输入的同构。
- **`harness.getModel()` 返回类型核对**：若现为非 null 契约（Optional 或抛异常），
  模型门改为对应形态，错文逐字 `"No model available for summarization"`【模型可见】。
- session_tree 的 newLeafId 是**导航后**的当前叶（pi 读
  `sessionManager.getLeafId()`）：有摘要时是摘要 entry id；java 对位＝
  `summaryEntry != null ? summaryEntry.id() : newLeafId`（即 :1147 已算的
  `leafId` 局部量，直接复用，不二次查存储）。
- pi `_resolveIdleWaitIfIdle()`（:4155）：java 的 `waitForIdle` 是车道 run 级
  （`AgentHarness.java:171`），树导航不是 run、无等待者 ⇒ 无对位需求（窄差 D36）。

#### 3.7 删死引擎钩（agent-core）

删除：`HookSystem.onBeforeNavigation`(:89)/`fireBeforeNavigation`(:286)＋
`BeforeNavigationHook.java`＋`NavigationContext.java`＋相关注册表分支。若有测试
引用一并清。map/04 D-12 证据列随之改写。

### T3：TUI 导航流

#### 3.8 marker 拆分：`/tree` ≠ `/fork`

`SessionCommands.java:40`（/fork 空参）改返新常量
`CommandRegistry.UI_FORK_SELECTOR`；`/tree`(:65) 保持 `UI_TREE_SELECTOR`。
`PiTuiApp.handleCommandResult`：`UI_FORK_SELECTOR` ⇒ 旧 `TreeSelectorScreen`
（fork 行为不变）；`UI_TREE_SELECTOR` ⇒ 进新导航流（§3.9）。

#### 3.9 `TreeNavigationFlow`（pi-java-tui，新文件，pi §1.4 对位）

编排类，由 `PiTuiApp` 持有并驱动（overlay 开关联 `openOverlay`/`dispatcher`，
都私有 ⇒ 放 app 包、构造注入所需句柄）。流程：

1. 开 `TreeSelectorScreen`（导航模式，见 §3.10）。选中＝当前叶 ⇒
   `appendSystemText("Already at this point")`（pi :5578 文案逐字）【模型可见】，止。
2. `session.services.settings().accessors().getBranchSummarySkipPrompt()` 为 true
   ⇒ `wantsSummary=false` 直进 5（pi :5590 门）。
3. 开三选 `SummaryChoiceScreen`（新，SelectList 三行，行文案逐字
   `"No summary"/"Summarize"/"Summarize with custom prompt"`，标题
   `"Summarize branch?"`）【模型可见】：
   - Esc ⇒ 重开树选择器（pi :5599 `showTreeSelector(entryId)`）；
   - 选 "Summarize with custom prompt" ⇒ 开 `CustomInstructionsScreen`
     （新，EditorComponent 单输入框；Enter 确认/Esc 取消）；取消 ⇒ 回三选（pi
     `continue` :5610）；
   - 定案 ⇒ `wantsSummary = choice != "No summary"`。
4. （合入 5 的 worker。）
5. **worker 虚拟线程**上：
   ```java
   if (session.isRunning()) {           // pi :5619-5621
       session.abort();
       session.harness().waitForIdle(session.laneName()); // pi 的 await abort()
   }
   if (session.isCompacting()) {        // pi :5624-5629 重查
       dispatcher.dispatch(() -> chatScreen.showError(
           "Wait for the current compaction or tree navigation to finish"
               + " before navigating the session tree."));
       return;
   }
   if (wantsSummary) {
       dispatcher.dispatch(chatScreen::showBranchSummaryIndicator); // pi :5640
   }
   try {
       var nav = session.navigateTree(entryId, wantsSummary, customInstructions,
           false, null);
       dispatcher.dispatch(() -> {
           if (nav.aborted()) {         // pi :5659-5664
               chatScreen.appendSystemText("Branch summarization cancelled");
               flow.reopen(entryId);    // 重开树选择器
               return;
           }
           if (nav.cancelled()) {       // pi :5665-5668
               chatScreen.appendSystemText("Navigation cancelled");
               return;
           }
           chatScreen.rebuildTranscript();            // pi :5671-5672，§3.11
           if (nav.editorText() != null && chatScreen.isInputEmpty()) {
               chatScreen.insertText(nav.editorText()); // pi :5673-5675
           }
           chatScreen.appendSystemText("Navigated to selected point");
       });
   } catch (Exception e) {
       dispatcher.dispatch(() -> chatScreen.showError(
           e.getMessage() == null ? String.valueOf(e) : e.getMessage()));
   } finally {
       if (wantsSummary) {
           dispatcher.dispatch(() ->
               chatScreen.clearIndicator(StatusIndicator.Kind.BRANCH_SUMMARY));
       }
   }
   ```
6. **Esc 不移植换绑**（裁决③）：摘要中 Esc 已能中止（§2.4）。

#### 3.10 `TreeSelectorScreen` 导航化（最小改）

- 构造加初始选中支持（重开时恢复上次选中，pi `initialSelectedId` 对位；lane 列表
  里定位 leafId 相等行，缺省 0）。
- `apply()` 分叉：`fork` 模式保持现状；`navigate` 模式不动会话，把选中 entryId
  暴露给 flow（`selectedEntryId()`）。选中行解析沿用 `:50-57` 的 `name @leafId`
  拆分（lane 列表形态不变——entry 树选择器本体是 S12 存疑项，非本批）。

#### 3.11 `ChatScreen` 三个新 public 面

```java
/** 置分支摘要指示器（pi showStatusIndicator(new BranchSummaryStatusIndicator)，:5640）。 */
public void showBranchSummaryIndicator() {
    setIndicator(new StatusIndicator.BranchSummary(interruptHint));
}

/** pi showError（:4273-4277）提 public：红字 "Error: " 前缀。 */
public void showError(String message) { /* 现 private 体上移修饰符 */ }

/**
 * 导航后整表重建（pi chatContainer.clear()＋renderInitialMessages()，:5671-5672）：
 * 清面板＋草稿，按当前叶 pathToLeaf 逐条 ChatMessage.from 重放。
 */
public void rebuildTranscript() {
    chatPanel.clear();
    assistantDraft.setLength(0);
    thinkingDraft.setLength(0);
    pendingUserText = null;
    var entries = session.accumulatedEntries(); // ChatScreen 需持有 session 引用（构造/更新注入）
    String leafId = session.harness().snapshot(session.laneName()).leafId();
    for (var e : ContextEntries.pathToLeaf(entries, leafId)) {
        var bubble = ChatMessage.from(e);
        if (bubble != null) {
            chatPanel.append(bubble);
        }
    }
    scrollToBottom();
}
```

（`ChatScreen` 现不持有 `AgentSession`——加包级 `bindSession(AgentSession)` 由
`PiTuiApp` 构造与 `switchSession` 时调用；或把 entries 作为参数传入。实现时取
改动小者，设计不锁定。）

#### 3.12 两个新 overlay

- `SummaryChoiceScreen`：`SelectList<String>` 三行（禁用字母过滤干扰——三项选择
  沿用 SelectList 现状即可，过滤是既有通用行为）；`apply()` 把选择回传 flow。
- `CustomInstructionsScreen`：包一个 `EditorComponent`（`onSubmit`＝确认；
  Esc＝取消——overlay `onKeyEvent` 先拦 `isCancel` 再转发编辑器）。渲染＝标题
  `"Custom summarization instructions"`＋编辑器区【模型可见，逐字】。

---

## §4 裁决条目

### 裁决① firing 点放 `AgentSession.navigateTree`，不桥引擎钩

- 【场景】map/04 D-12 旧证据把希望挂在引擎钩 `onBeforeNavigation` 上；但该钩
  是 java 早期自造（`NavigationContext{lane, targetLeafId}`、void 返回），装不下
  pi 的 preparation 载荷与结果臂（cancel/摘要/三覆盖）。
- 【决定】事件在 `navigateTree` 内经 `SessionEventFirer` 直发（pi 的
  `_extensionRunner.emit` 在 agent-session 内，同构）；删除死引擎钩三件套
  （§3.7）。
- 【风险/代价】[内部，模型不可见] agent-core 少一个公开注册口；零调用点实证，
  无行为面变化。HookSystem 钩数 15→14，map/03 证据列随批更新。

### 裁决② `session_before_tree` 复用通用 emit 纪律（cancel 短路＋最后发言者赢）

- 【场景】pi 对 before 族不做事件级特判，一律走通用 `emit`（runner.ts:1089-1118）。
- 【决定】firer 新方法照抄该纪律（§3.4），不为 tree 单设合并规则。
- 【风险/代价】[内部] 多处理器场景「后发言覆盖先发言」是 pi 语义本身，测试钉死。

### 裁决③ Esc 换绑不移植——java 的 Esc 链已含 abortBranchSummary

- 【场景】pi 在摘要期把 `defaultEditor.onEscape` 换绑成 `abortBranchSummary()`
  （:5637-5640），因为 pi 默认 onEscape 在非流式不中止摘要（:3070-3082）。
- 【决定】不建换绑机制。java 的 Esc＝INTERRUPT⇒`session.abort()`，`abort()` 无
  isRunning 门且首步即 `abortBranchSummary()`（`AgentSession.java:991-1002`），
  摘要期 Esc 行为已与 pi 换绑后等价。
- 【风险/代价】[内部] `abort()` 顺带置 `agentRunAbortRequested` 旗——空转期置位
  无害（既有注释 :998-1000）；无 [模型可见] 差异。

### 裁决④ 导航跑 worker 线程；abort 后 waitForIdle 再导航

- 【场景】java `navigateTree` 的摘要生成是同步阻塞 LLM 调用；overlay `apply()`
  在渲染线程。pi 是 async 函数，UI 不冻。
- 【决定】flow 第 5 步整体放虚拟线程（`CommandUtil.async` 先例）；运行中先
  `abort()`＋`harness.waitForIdle()`（pi `await this.session.abort()` :5621 对位），
  全部 UI 写经 `dispatcher` 回渲染线程。
- 【风险/代价】[内部] abort→settle 有窗口；重查 isCompacting（pi :5624）兜住
  窗口期被别的操作占线的情况。

### 裁决⑤ `/tree` 与 `/fork` 的 marker 拆分；选择器仍列 lane

- 【场景】现状两命令共用 `UI_TREE_SELECTOR`，`/tree` 实为 fork；pi 的 `/tree`
  是原地导航。但 pi 的 entry 树选择器本体（tree-selector.ts 1,435 行）是
  map/05 S12 存疑项，不在 B245 范围。
- 【决定】拆 marker（§3.8）；导航流复用 lane 列表选择器（选中＝该 lane 叶
  entry 导航）。S12 判定不动、证据更新。
- 【风险/代价】[模型可见] lane 列表形态与 pi 的 entry 树差距仍在（存量约束，
  非本批范围；窄差 D37）。

### 裁决⑥ 导航后整表重建本批落（仅树导航触发点）

- 【场景】pi 导航成功即 `chatContainer.clear()`＋`renderInitialMessages()`
  （:5671-5672）；java 无通用重建（B38 是 compaction_end 触发点的同族缺口）。
- 【决定】为树导航加 `ChatScreen.rebuildTranscript()`（§3.11）；B38 不随本批销
  （触发点不同：压缩结束 vs 树导航）。
- 【风险/代价】[模型可见] 重建后分支摘要以弱形状一行 `System("Branch: …")`
  呈现（map/05 R18 存疑项，非本批）；`flushCompactionQueue` 不重放——java 缓冲
  在 CompactionEnd 已无条件重放（`InteractiveMode.java:46-53`），导航时
  isCompacting 必为 false、缓冲必空 ⇒ pi :5683 的 flush 在 java 恒 no-op
  （窄差 D38）。

### 裁决⑦ `TreeNavigation` 加 `aborted` 字段（public record 改签名）

- 【场景】pi 区分扩展取消 `{cancelled:true}` 与摘要中止 `{cancelled:true,
  aborted:true}`，TUI 两臂文案与是否重开选择器不同；java 现合并。
- 【决定】record 加第二字段（§3.5），引擎层 Aborted 结局 `(true, true, …)`。
- 【风险/代价】[内部] 编译期破坏面＝两测试类，随批更新；无生产调用点。

---

## §5 窄差表（本批新增，接续批 7 的 D31–D35）

| # | 内容 | 等级 |
|---|---|---|
| D36 | pi navigateTree finally 的 `_resolveIdleWaitIfIdle()` 无对位——java waitForIdle 是车道 run 级，树导航不是 run | 无影响 |
| D37 | 树选择器仍是 lane 列表（pi 是 entry 树，1,435 行）；导航目标粒度＝lane 叶 | 存量约束，非本批范围（S12） |
| D38 | pi 导航成功后 `flushCompactionQueue{willRetry:false}` 在 java 恒 no-op（缓冲由 CompactionEnd 重放，导航时必空） | 无影响 |
| D39 | 三选/自定义指令对话框用 SelectList/EditorComponent 弱形状（pi 是带边框的 selector/editor 组件）；行文案逐字 | [模型可见] UI 形态差异 |
| D40 | 重开树选择器恢复选中＝lane 行定位（pi 恢复 entry 节点）；选择粒度不同同源 D37 | 边缘场景 |

---

## §6 测试计划

### coding-agent（T1/T2，复用批 4/5 扩展注入缝——`extensionFirer` 装配同 fork 测试）

新 `SessionBeforeTreeTest`（对照 pi §1.5）：

1. **cancel**：handler 返 `{cancel:true}` ⇒ 结果 `(true,false,null,null)`、叶不动、
   token finally 清零（再导航可正常进行）（pi 3688 逐字）。
2. **扩展摘要**：`summarize=true`＋handler 给 summary（含 usage）⇒ 默认生成器
   **不被调**（记录缝）、summaryEntry `fromHook()==true`、文本/usage 全扩展值。
3. **summarize 门**：`summarize=false`＋handler 给 summary ⇒ 无摘要 entry
   （pi :4036）。
4. **三覆盖**：handler 给 customInstructions/replaceInstructions/label ⇒ 默认
   生成器收到覆盖值；label 贴摘要 entry。
5. **最后发言者赢＋cancel 短路**：两 handler，前者给值后者给值 ⇒ 后者生效；
   前者 cancel ⇒ 后者不跑。
6. **异常隔离**：handler 抛 ⇒ emitError＋后续 handler 照跑＋导航照常。
7. **无订阅者快走**：零 handler ⇒ 事件零发射（探针断言）。
8. **session_tree 载荷**：newLeafId（有摘要时＝摘要 id）/oldLeafId/summaryEntry/
   fromExtension（扩展摘要 true；pi 生成 false；无摘要 null）。
9. **aborted 区分**：默认生成器收到中止 ⇒ `(true,true,null,null)`（对照 1 的
   `(true,false,…)`）。
10. **模型门**：`summarize=true` 且无模型 ⇒ 错文逐字
    `"No model available for summarization"`【模型可见】。

既有 `NavigateTreeTest`/`RestoreToolsOnNavigateTest` 更新新字段并保持全绿
（重构回归）。

### pi-java-tui（T3，不跑 runner，走 overlay 直驱）

新 `TreeNavigationFlowTest`：

1. skipPrompt=true ⇒ 不出三选，直接 `summarize=false` 导航。
2. 三选映射：No summary/Summarize/custom（custom 确认后带指令导航）。
3. custom 编辑器 Esc ⇒ 回三选；三选 Esc ⇒ 重开树选择器。
4. aborted ⇒ "Branch summarization cancelled"＋重开；cancelled ⇒
   "Navigation cancelled"。
5. 成功 ⇒ 重建调用＋editorText 仅在编辑器空时回填＋
   "Navigated to selected point"。
6. 运行中 ⇒ abort＋waitForIdle 后才 navigateTree（顺序探针）；重查
   isCompacting ⇒ 错文第二句逐字【模型可见】。
7. 摘要路径指示器置/清（BRANCH_SUMMARY kind 成对）。

## §7 验证口径

- `mvn test -pl pi-java-coding-agent,pi-java-tui -am`（底层 agent-core 删钩 ⇒
  必须 `-am`，批 7 教训）。
- 收口前全量 `mvn clean verify`（spotbugs 在 verify 阶段）；日志落文件再查退出码
  （管道吞码教训）。
- 零 `System.out.println` 残留；新文件 ≤500 行。

## §8 对账表（预期）

| map 行 | 前 | 后 | Δ |
|---|---|---|---|
| map/04 D-12 `session_before_tree`（w1） | 存疑 | 对齐 | +0.5 |
| map/04 D-13 `session_tree`（w1） | 缺失 | 对齐 | +1.0 |
| map/04 C-3 `/tree` | 存疑 | 存疑（证据更新：导航＋摘要流已接，选择器本体仍 lane 列表） | 0 |
| map/05 S12/S13 | 存疑/缺失 | 不动（证据更新） | 0 |

D 域 71.5→**73.0/83（88.0%）**；模块 289.0→**290.5/496（58.6%）**；全仓
954.0→**955.5/1381（≈69.2%）**。D 域剩余：存疑 0；缺失 6（#1/#11/#16/#26/#27/#36，
各随 B221/E5/子系统）。
