# coding-agent 批 10 设计稿：`/fork`＝user-message 选择器（B254）＋S20 排队消息选择器

【背景摘要】本稿是 coding-agent 对齐批 10 的设计，收两条同组件欠账（docs/20 §3
批 10 行）：**B254**——pi 的 `/fork` 不走树选择器，走 **user-message 选择器**
（`user-message-selector.ts`，155 行：列全部用户消息、初始选中最后一条、选中即
fork＋编辑器回填选中消息文本）；**S20**（map/05）——排队消息选择器（Alt+Up 把
steer/followUp 两队列＋压缩缓冲的全部排队文本取回编辑器）。两者在 pi 同文件同域
（消息选取/取回），且 S20 落地后批 8 窄差 **D41** 获得重裁条件（restore 取用面
出现）。java 侧 `/fork` 现为 lane 平表（`TreeSelectorScreen`，批 9 后唯一用户），
dequeue 键常量已备但未接线（`PiTuiApp.java:494` default 分支吞掉）。

术语（首现释义）：

- **B254 / S20 / D41**：`docs/04-open-items-register.md` 工单／`docs/map/05-tui-web.md`
  屏幕行／批 8 窄差编号。
- **user-message 选择器**：pi `UserMessageSelectorComponent`＋`UserMessageList`——
  无过滤、环绕导航、每消息两行（文本行＋序位行）的平表。
- **fork（position "before"）**：pi 默认叉位——目标 entry 必须是 user 消息，
  新会话种子到该消息的**父** entry，选中消息文本回填编辑器供改后重发。
- **dequeue**：pi `app.message.dequeue`（默认 Alt+Up；Windows/WSL **Alt+Q**）——
  清空全部排队（引擎 steer/followUp 两队列＋压缩缓冲）并合并回填编辑器。
- **压缩缓冲**：java `CompactionInputBuffer`（pi `compactionQueuedMessages` 对位），
  压缩窗口期用户输入的暂存，CompactionEnd 重放。
- **窄差**：java 与 pi 无法完全对齐、登记在册的差异。

---

## §0 基线与预期翻动

| 口径 | 批 9 后基线 | 本批预期 | 依据 |
|---|---|---|---|
| D 域（map/04 §D，Σ权重 83） | 73.0（88.0%） | 73.0（88.0%） | 本批不动 D 域行 |
| 模块（Σ 487，去重） | 286.0（58.7%） | 288.0（59.1%） | map/04 #15 `/fork` 存疑→对齐 +1.0（权重 2）、map/05 S20 缺失→对齐 +1.0（权重 1） |
| 全仓（Σ 1381） | 958.0（69.4%） | 960.0（69.5%） | 同上 +2.0 |

**B254 销号**；**D41 重裁**（§3 T5／裁决④——restore 面落地后两臂移植、一臂无缝
残留改述）。新登窄差：D49–D51（§5）。RPC 两点修真（fork `text` 载荷恒 `""`、
`get_fork_messages` 数据源单车道）属缺陷闭环，map 无行记分，不翻动账面。
map/05 R28（排队消息区，存疑）**不翻**：hint 行与取回流补齐，但「流式 bash/工具
组件挂该区」仍缺，证据列更新。

---

## §1 pi 侧事实（取证，锚点 `ea448f454`，只走 `git show`）

### 1.1 选择器本体（`components/user-message-selector.ts` 全文 155 行）

数据项（`:5-9`）：`{id: entryId, text, timestamp?}`——timestamp 字段存在但
**渲染与逻辑均不消费**（占位字段，非差异）。

`UserMessageList`（`:14-107`）状态机与渲染逐字：

```ts
constructor(messages: UserMessageItem[], initialSelectedId?: string) {
	this.messages = messages;   // 时间序（旧→新）
	const initialIndex = initialSelectedId ? messages.findIndex((m) => m.id === initialSelectedId) : -1;
	// 有初值用初值，否则默认最后一条
	this.selectedIndex = initialIndex >= 0 ? initialIndex : Math.max(0, messages.length - 1);
}

render(width: number): string[] {
	if (this.messages.length === 0) {
		lines.push(theme.fg("muted", "  No user messages found"));
		return lines;
	}
	// 可视窗：maxVisible=10，以选中项为中心滑动
	const startIndex = Math.max(0,
		Math.min(this.selectedIndex - Math.floor(this.maxVisible / 2), this.messages.length - this.maxVisible));
	const endIndex = Math.min(startIndex + this.maxVisible, this.messages.length);
	for (let i = startIndex; i < endIndex; i++) {
		const isSelected = i === this.selectedIndex;
		const normalizedMessage = message.text.replace(/\n/g, " ").trim();   // 单行化
		const cursor = isSelected ? theme.fg("accent", "› ") : "  ";
		const truncatedMsg = truncateToWidth(normalizedMessage, width - 2);  // 光标占 2 列
		lines.push(cursor + (isSelected ? theme.bold(truncatedMsg) : truncatedMsg));
		lines.push(theme.fg("muted", `  Message ${i + 1} of ${this.messages.length}`));
		lines.push("");                                                       // 条间空行
	}
	if (startIndex > 0 || endIndex < this.messages.length) {                 // 滚动指示
		lines.push(theme.fg("muted", `  (${this.selectedIndex + 1}/${this.messages.length})`));
	}
	return lines;
}

handleInput(keyData: string): void {
	if (kb.matches(keyData, "tui.select.up")) {          // 上＝更旧，到顶环绕到底
		this.selectedIndex = this.selectedIndex === 0 ? this.messages.length - 1 : this.selectedIndex - 1;
	} else if (kb.matches(keyData, "tui.select.down")) { // 下＝更新，到底环绕到顶
		this.selectedIndex = this.selectedIndex === this.messages.length - 1 ? 0 : this.selectedIndex + 1;
	} else if (kb.matches(keyData, "tui.select.confirm")) {
		this.onSelect?.(this.messages[this.selectedIndex].id);
	} else if (kb.matches(keyData, "tui.select.cancel")) {
		this.onCancel?.();
	}
}
```

容器层 `UserMessageSelectorComponent`（`:112-155`）：Spacer(1) ⇒ 粗体
`"Fork from Message"` ⇒ muted `"Select a user message to copy the active path up
to that point into a new session"` ⇒ Spacer(1) ⇒ DynamicBorder ⇒ Spacer(1) ⇒
列表 ⇒ Spacer(1) ⇒ DynamicBorder。空列表时 `setTimeout(() => onCancel(), 100)`
（`:147-149`——防御臂，主流不可达，见 §1.2 宿主预检）。

`DynamicBorder`（`dynamic-border.ts:16-18`）逐字：`"─".repeat(Math.max(1, width))`，
色 `theme.fg("border")`。

### 1.2 宿主流（`interactive-mode.ts`）

`/fork` 命令面无参（`:3273-3277`，精确匹配 `"/fork"`）：

```ts
if (text === "/fork") {
	this.showUserMessageSelector();
	this.editor.setText("");
	return;
}
```

slash 描述（`core/slash-commands.ts:34`）：
`{ name: "fork", description: "Create a new fork from a previous user message" }`。
另有 `app.session.fork` 动作（`:3120`）但**默认键为空**
（`core/keybindings.ts:81` `defaultKeys: []`）——选择器只经 `/fork`（与
double-escape 设置）可达。

`showUserMessageSelector`（`:5500-5538`）逐字：

```ts
private showUserMessageSelector(): void {
	const userMessages = this.session.getUserMessagesForForking();
	if (userMessages.length === 0) {
		this.showStatus("No messages to fork from");      // 空 ⇒ 不开选择器
		return;
	}
	const initialSelectedId = userMessages[userMessages.length - 1]?.entryId;
	this.showSelector((done) => {
		const selector = new UserMessageSelectorComponent(
			userMessages.map((m) => ({ id: m.entryId, text: m.text })),
			async (entryId) => {
				done();
				try {
					const result = await this.runtimeHost.fork(entryId);
					if (result.cancelled) { this.ui.requestRender(); return; }  // before_fork cancel
					this.editor.setText(result.selectedText ?? "");
					this.showStatus("Forked to new session");
				} catch (error) {
					this.showError(error instanceof Error ? error.message : String(error));
				}
			},
			() => { done(); this.ui.requestRender(); },   // Esc：只关，无后续
			initialSelectedId,
		);
		return { component: selector, focus: selector.getMessageList() };
	});
}
```

### 1.3 fork 语义（`core/agent-session-runtime.ts:262-360`）

默认 `position = "before"`（`:267`）：先发 `session_before_fork`（cancel ⇒
`{cancelled: true}` 直接返回，`:270-273`）；`getEntry(entryId)` 不存在 ⇒
**抛** `"Invalid entry ID for forking"`（`:275-278`）；position "before" 时目标
必须是 user 消息，否则同错抛出（`:281-284`）；`targetLeafId = 选中 entry 的
parentId`、`selectedText = extractUserMessageText(content)`（`:285-287`）。
`extractUserMessageText`（`:56-64`）：text 块 `.join("")`——**空串分隔**。

```ts
function extractUserMessageText(content) {
	if (typeof content === "string") return content;
	return content
		.filter((part) => part.type === "text" && typeof part.text === "string")
		.map((part) => part.text)
		.join("");      // ← 空串分隔
}
```

### 1.4 数据源（`core/agent-session.ts:4161-4178`）

```ts
getUserMessagesForForking(): Array<{ entryId: string; text: string }> {
	const entries = this.sessionManager.getEntries();     // 全会话文件（含所有分支）
	const result = [];
	for (const entry of entries) {
		if (entry.type !== "message") continue;
		if (entry.message.role !== "user") continue;
		const text = contentText(entry.message.content, "");   // ← 空串分隔
		if (text) {                                            // ← 空文本滤掉
			result.push({ entryId: entry.id, text });
		}
	}
	return result;
}
```

`sessionManager.getEntries()`（`session-manager.ts:1520-1522`）＝**会话文件全部
条目**（非当前分支子集，排除 header）。`contentText(content, "")`
（`ai/utils/text.ts:6-11`）：text 块拼接，第二参是分隔符。

### 1.5 dequeue 链（S20 本体）

键位（`core/keybindings.ts:71-74`）：**Windows/WSL ⇒ `alt+q`，其余 `alt+up`**
（平台判定 `:16-20`：`win32`，或 `linux`＋`WSL_DISTRO_NAME`/`WSL_INTEROP`）。

`handleDequeue`（`:4507-4513`）＋ `restoreQueuedMessagesToEditor`（`:4742-4763`）
＋ `clearAllQueues`（`:4708-4722`）逐字：

```ts
private handleDequeue(): void {
	const restored = this.restoreQueuedMessagesToEditor();
	if (restored === 0) {
		this.showStatus("No queued messages to restore");
	} else {
		this.showStatus(`Restored ${restored} queued message${restored > 1 ? "s" : ""} to editor`);
	}
}

private clearAllQueues(): { steering: string[]; followUp: string[] } {
	const { steering, followUp } = this.session.clearQueue();           // 引擎两队列
	const compactionSteering = this.compactionQueuedMessages
		.filter((msg) => msg.mode === "steer").map((msg) => msg.text);
	const compactionFollowUp = this.compactionQueuedMessages
		.filter((msg) => msg.mode === "followUp").map((msg) => msg.text);
	this.compactionQueuedMessages = [];                                  // 压缩缓冲
	return {
		steering: [...steering, ...compactionSteering],                  // 引擎在前，缓冲在后
		followUp: [...followUp, ...compactionFollowUp],
	};
}

private restoreQueuedMessagesToEditor(options?: { abort?: boolean; currentText?: string }): number {
	const { steering, followUp } = this.clearAllQueues();
	const allQueued = [...steering, ...followUp];                        // steer 前 followUp 后
	if (allQueued.length === 0) {
		this.updatePendingMessagesDisplay();
		if (options?.abort) void this.session.abort();
		return 0;
	}
	const queuedText = allQueued.join("\n\n");
	const currentText = options?.currentText ?? this.editor.getText();
	const combinedText = [queuedText, currentText].filter((t) => t.trim()).join("\n\n");
	this.editor.setText(combinedText);                                   // 排队在前，草稿在后
	this.updatePendingMessagesDisplay();
	if (options?.abort) void this.session.abort();
	return allQueued.length;
}
```

hint 行（`updatePendingMessagesDisplay :4724-4740`）：两队列任一非空时，dim 行
序列末尾追加 `` ↳ ${getAppKeyDisplay("app.message.dequeue")} to edit all queued
messages ``（`:4736-4738`）。

### 1.6 `restoreQueuedMessagesToEditor` 的另三处调用（D41 重裁素材）

| 调用点 | 场景 | 形态 |
|---|---|---|
| `:3071-3073` | **Esc 且流式中**（`defaultEditor.onEscape` 首臂） | `restore({abort: true})`——排队回填＋abort |
| `:5618-5621` | **树导航提交后**（`/tree` onSelect，摘要三选之后） | 流式中 ⇒ `restore()`（不带 abort）＋`await abort()` |
| `:1975-1978`／`:2259-2262` | 扩展 `ctx.abort()`／UI context `abort` | `restore({abort: true})`——扩展面 |

### 1.7 RPC 面（`modes/rpc/rpc-mode.ts`）

```ts
case "fork": {                                                    // :611-617
	const result = await runtimeHost.fork(command.entryId);
	if (!result.cancelled) await rebindSession();
	return success(id, "fork", { text: result.selectedText, cancelled: result.cancelled });
}
case "get_fork_messages": {                                       // :631-634
	const messages = session.getUserMessagesForForking();
	return success(id, "get_fork_messages", { messages });          // [{entryId, text}]
}
```

---

## §2 java 侧现状与缺口（实读）

### 2.1 `/fork` 现状：lane 平表

`SessionCommands.java:33-47`：`/fork` 空参 ⇒ `UI_FORK_SELECTOR` marker；带参 ⇒
`forkCopy(name)`（**java 独有便捷路**，pi 无此臂——登记窄差，见 §5 D50）。
`PiTuiApp.java:447-455`：marker ⇒ `new TreeSelectorScreen(snapshot)`——
`TreeSelectorScreen` 列 **lane 名＋leafId**（121 行平表），选中 ⇒
`session.forkFromEntry(entryId)`（fork-before，position 语义已与 pi 一致，
`AgentSession.java:1676-1688`）。批 9 后 `/tree` 已改道 `TreeNavigationFlow`，
`TreeSelectorScreen` 只剩 `/fork` 一个用户。

### 2.2 `getUserMessagesForForking` 已存在，三处偏差

`AgentSession.java:1901-1908`（RPC `get_fork_messages` 的数据源）：

```java
public List<Entry.Message> getUserMessagesForForking() {
    return harness().snapshot(laneName()).transcript().stream()   // ① 单车道转录（pi＝全会话文件）
        .filter(Entry.Message.class::isInstance)
        .map(Entry.Message.class::cast)
        .filter(e -> "user".equals(e.message().role()))
        .toList();                                                // ② 空文本不滤（pi if (text)）
}
```

③ 文本提取在 RPC 侧 `RpcDispatcher.forkMessageText`（`:652-663`）`.join("\n")`
——pi 两处（选择器数据源与 `extractUserMessageText`）均 `.join("")`。

### 2.3 RPC `fork` 的 `text` 载荷恒空

`RpcDispatcher.java:263-276`：成功臂 `Map.of("text", "", "cancelled", false)`
——pi 是 `{text: result.selectedText, ...}`（§1.7）。

### 2.4 dequeue：键已备、链未接

- `KeybindingsManager.java:36` 常量 `DEQUEUE = "app.message.dequeue"`、`:96`
  绑定 `alt+up`（**平台规则未落**——pi Windows/WSL 是 `alt+q`，§1.5）。
- `PiTuiApp.java:494`：default 分支注释「dequeue → Phase 6」吞掉。
- `PendingQueueView.java:37-51`：只有 `Steering:`/`Follow-up:` 两类 dim 行，
  **缺 hint 行**（pi §1.5 `:4736-4738`）。
- 可复用件已齐：`AgentSession.clearQueue()`（`:1424-1434`，清引擎两队列＋
  发射 queue_update{空}，返回 `ClearedQueue{steering, followUp}`）；
  `CompactionInputBuffer.contents()/clear()`（压缩缓冲读/清）；
  `EditorComponent.setText`（`:435`，替换语义＋undo 快照——pi `editor.setText`
  对位）；ChatScreen 缺 `setText` 透传（现有 `clearInput`/`insertText`/
  `inputText`，`:470-503`）。
- 状态文案先例：`ChatScreen.appendSystemText`＝pi `showStatus` 落形
  （TreeNavigationFlow "Already at this point" 同手法）。

### 2.5 D41 两臂的 java 对位

- Esc 流式：`PiTuiApp.java:467` `INTERRUPT -> mode.abort()`——无 restore。
- 树导航：`TreeNavigationFlow.startNavigation`（`:180-185`）`session.abort()`＋
  `waitForIdle`——无 restore。
- 扩展 abortHandler 两臂：java 扩展面尚无 `ctx.abort()` 缝（E5 域）——无挂点。

### 2.6 批 10 后 `TreeSelectorScreen` 无用户 ⇒ 删除

`/tree` 走 `TreeSelectorOverlay`（批 9），`/fork` 本批改道后 `TreeSelectorScreen`
零用户。按「pi 删掉的，pi-java 也删」对偶（java 独有替身且无用户）删除整个文件。

---

## §3 设计

### T1：`getUserMessagesForForking` 修真＋RPC 两点（coding-agent，~60 行改动）

#### 3.1 `AgentSession.getUserMessagesForForking` 改数据源与形状

返回类型从 `List<Entry.Message>` 收平为 pi 形 `List<ForkMessage>`（新 record，
同文件或 `core/` 小文件）：

```java
/** pi getUserMessagesForForking 的返回项（{entryId, text}）。 */
public record ForkMessage(String entryId, String text) {}
```

```java
/**
 * 可供 fork 的用户消息（pi {@code getUserMessagesForForking}，
 * agent-session.ts:4161-4178 逐字）：数据源＝{@link #accumulatedEntries()}
 * （持久会话全分支提交序，pi {@code sessionManager.getEntries()} 对位；
 * 无持久面 harness 转录兜底——既有兜底语义保留）；text 块空串拼接
 * （pi {@code contentText(content, "")}），空文本滤掉（pi {@code if (text)}）。
 */
public List<ForkMessage> getUserMessagesForForking() {
    var result = new ArrayList<ForkMessage>();
    for (var entry : accumulatedEntries()) {
        if (entry instanceof Entry.Message m && "user".equals(m.message().role())) {
            var text = textOf(m.message().content(), "");
            if (!text.isEmpty()) {
                result.add(new ForkMessage(m.id(), text));
            }
        }
    }
    return result;
}
```

`textOf`（`:1296-1303`）加分隔符参数（现恒 `"\n"`；导航 editorText 臂
`:1219`/`:1222` 保持 `"\n"` 不动——pi `contentText` 默认分隔符就是 `"\n"`，
两处分叉是 pi 原生行为）。

#### 3.2 `RpcDispatcher` 两点修真

```java
case RpcCommand.GetForkMessages g -> {
    // pi rpc-mode.ts:631-634：{messages: [{entryId, text}]}——数据源即
    // session.getUserMessagesForForking()（本批修真后＝全分支＋空串分隔）。
    var messages = session.getUserMessagesForForking().stream()
        .map(m -> new RpcPayloads.RpcForkMessage(m.entryId(), m.text()))
        .toList();
    out.write(RpcResponse.ok(g.id(), "get_fork_messages",
        new RpcPayloads.RpcForkMessagesData(messages)));
}
```

`fork` 成功臂（`:274-275`）text 载荷修真——fork 前从 fork 消息表查 text
（pi 的 selectedText 就是该表同一条目的 text，同序同源）：

```java
case RpcCommand.Fork fk -> {
    // pi rpc-mode.ts:611-617：{text: selectedText, cancelled}——selectedText＝
    // 目标 user 消息的 text（position "before"）。先查表再 fork（fork 后
    // 旧会话已关，查不到）。
    var selectedText = session.getUserMessagesForForking().stream()
        .filter(m -> m.entryId().equals(fk.entryId()))
        .map(AgentSession.ForkMessage::text).findFirst().orElse("");
    var forked = session.forkFromEntry(fk.entryId());
    ...
    out.write(RpcResponse.ok(fk.id(), "fork",
        Map.of("text", selectedText, "cancelled", false)));
}
```

`forkMessageText`（`:652-663`）随两点改道后无用户 ⇒ 删除。

### T2：`UserMessageSelectorScreen`（tui，新文件 ~200 行）

pi `UserMessageList`＋容器层（§1.1）的 java 落形，沿用批 9
`TreeSelectorOverlay` 的 overlay 模式（`ScreenOverlay`＋confirmed/cancelled＋
`apply` 交回调；tui.select.* 层沿 SelectList 纪律直配 `KeyEvent` helper——
`isUp/isDown/isConfirm/isCancel`）。

```java
/**
 * pi {@code UserMessageSelectorComponent}（user-message-selector.ts:112-155）
 * ＋ {@code UserMessageList}（:14-107）的 java 落形（docs/21 §T2，B254）：
 * {@code /fork} 的 user-message 选择器。无过滤、环绕导航、每消息两行
 * （单行化文本＋序位行），可视窗 10 条以选中项为中心滑动。
 */
public final class UserMessageSelectorScreen implements ScreenOverlay {

    private static final int MAX_VISIBLE = 10;   // pi :19

    private final List<AgentSession.ForkMessage> messages;   // 时间序（旧→新）
    private final IntSupplier terminalCols;
    private final Consumer<String> onSelect;     // entryId
    private final Runnable onCancel;
    private int selectedIndex;                   // 初值：initialSelectedId 命中，否则最后一条（pi :26-28）
    private boolean confirmed;
    private boolean cancelled;

    public UserMessageSelectorScreen(List<AgentSession.ForkMessage> messages,
                                     String initialSelectedId,
                                     IntSupplier terminalCols,
                                     Consumer<String> onSelect, Runnable onCancel) { ... }

    /** pi handleInput（:81-106）分派序：up/down 环绕、Enter 选、Esc 取消。 */
    public boolean onKeyEvent(KeyEvent event) {
        if (event.isUp()) {
            selectedIndex = selectedIndex == 0 ? messages.size() - 1 : selectedIndex - 1;
            return true;
        }
        if (event.isDown()) {
            selectedIndex = selectedIndex == messages.size() - 1 ? 0 : selectedIndex + 1;
            return true;
        }
        if (event.isConfirm()) {
            confirmed = true;   // apply 里 onSelect(messages.get(selectedIndex).entryId())
            return true;
        }
        if (event.isCancel()) {
            cancelled = true;
            return true;
        }
        return false;
    }

    @Override public boolean isDone() { return confirmed || cancelled; }

    @Override public void apply(AgentSession session, Consumer<AgentSession> switcher) {
        if (cancelled) { onCancel.run(); return; }
        onSelect.accept(messages.get(selectedIndex).entryId());
    }

    @Override public Element render() { /* §1.1 逐字行形，见下 */ }
}
```

渲染（pi `render(width)` 逐字行形；宽度＝`terminalCols`，同批 9 裁决⑧的
ResizeEvent 跟踪源）：

- 容器头（pi `:120-130`）：空行 ⇒ 粗体 `"Fork from Message"` ⇒ MUTED
  `"Select a user message to copy the active path up to that point into a new
  session"` ⇒ 空行 ⇒ border 行 ⇒ 空行；
- 列表区：空表 ⇒ MUTED `"  No user messages found"`（防御臂，宿主预检后
  不可达）；否则窗口 `[startIndex, endIndex)`＝pi `:44-48` 公式逐字
  （`startIndex = max(0, min(sel − 5, n − 10))`，`endIndex = min(start + 10, n)`），
  每条三行：① 光标行——选中 `› `（ACCENT）否则两空格，文本单行化
  （`\n`→空格，trim）按 `width − 2` 截断，选中加粗；② MUTED
  `"  Message {i+1} of {n}"`；③ 空行；
- 滚动指示（`startIndex > 0 || endIndex < n`）：MUTED `"  ({sel+1}/{n})"`；
- 容器尾（pi `:143-144`）：空行 ⇒ border 行。
- border 行＝pi DynamicBorder（裁决⑤）：`"─" × max(1, width)`，色 MUTED 近似。

截断：pi `truncateToWidth` 是显示宽截断；java 用既有 substring 式 helper 会
在宽字符（CJK/emoji）处超宽——沿批 9 `TreeListRenderer` 的列宽纪律写一个
小包内工具（按 `dev.tamboui` 文本宽度逐 codepoint 累计到 `width − 2`）。

### T3：`/fork` 接线＋`TreeSelectorScreen` 删除（tui＋coding-agent，~80 行）

#### 3.3 `PiTuiApp` `UI_FORK_SELECTOR` 改道（pi `showUserMessageSelector` 对位）

```java
case CommandRegistry.UI_FORK_SELECTOR -> {
    // docs/21（T3）：/fork＝user-message 选择器（B254，pi :5500-5538 对位）。
    var forkMessages = session.getUserMessagesForForking();
    if (forkMessages.isEmpty()) {                       // pi :5503-5506
        chatScreen.appendSystemText("No messages to fork from");
    } else {
        var initialSelectedId = forkMessages.get(forkMessages.size() - 1).entryId();
        openOverlay(new UserMessageSelectorScreen(forkMessages, initialSelectedId,
            this::terminalCols,
            entryId -> forkFromMessage(entryId, forkMessages),
            () -> { }));                                // Esc：pi 的 onCancel 只关选择器
    }
}
```

`forkFromMessage`（PiTuiApp 私有，pi onSelect 回调 `:5514-5528` 逐字序）：

```java
private void forkFromMessage(String entryId, List<AgentSession.ForkMessage> messages) {
    try {
        var forked = session.forkFromEntry(entryId);    // position "before"（既有，批 5/6）
        if (forked.isEmpty()) {                         // session_before_fork cancel ⇒ pi {cancelled}
            return;                                     // pi :5517-5520：仅 requestRender
        }
        switchSession(forked.get());
        // pi :5522：editor.setText(selectedText ?? "")——selectedText＝选中消息 text
        var selectedText = messages.stream()
            .filter(m -> m.entryId().equals(entryId))
            .map(AgentSession.ForkMessage::text).findFirst().orElse("");
        chatScreen.setEditorText(selectedText);
        chatScreen.appendSystemText("Forked to new session");
    } catch (Exception e) {                             // pi :5526-5527
        chatScreen.showError(e.getMessage() == null ? String.valueOf(e) : e.getMessage());
    }
}
```

`ChatScreen` 加透传（`EditorComponent.setText` 已备，`:435`）：

```java
/** Replace the editor content（pi {@code editor.setText}；dequeue/fork 回填用）。 */
public void setEditorText(String text) {
    editor.setText(text);
    completer.update(editor.getText());
}
```

#### 3.4 `SessionCommands` `/fork` 描述对齐＋`TreeSelectorScreen` 删除

- `/fork` description 改 pi 逐字 `"Create a new fork from a previous user message"`
  （`slash-commands.ts:34`；【模型可见】补全目录/帮助文案）。带参臂
  （`forkCopy(name)`）保留——java 独有便捷路，登记窄差 D50。
- `TreeSelectorScreen.java` 删除（§2.6）；其测试同步删除。

### T4：dequeue 链（S20 本体；coding-agent＋tui，~120 行）

#### 3.5 `InteractiveMode.restoreQueuedMessages`（pi `clearAllQueues`＋合并逻辑落点）

```java
/**
 * pi {@code clearAllQueues}（interactive-mode.ts:4708-4722）＋
 * {@code restoreQueuedMessagesToEditor} 的队列侧（:4742-4763）：清空引擎两
 * 队列＋压缩缓冲，返回 pi 序合并文本（steer 前 followUp 后、引擎在前缓冲在
 * 后、`"\n\n"` 连接）；空 ⇒ {@code null}。queue_update 由 clearQueue/本方法
 * 发射（清空后两队列皆空）。编辑器回填与 abort 由调用方按 pi 调用点形态组合。
 *
 * @return 合并后的排队文本；无排队 ⇒ {@code null}
 */
public String restoreQueuedMessages() {
    var cleared = session.clearQueue();              // 引擎两队列＋queue_update{空}
    var buffered = buffer.contents();                // 压缩缓冲
    buffer.clear();
    if (!buffered.isEmpty()) {
        session.emitQueueUpdate();                   // 缓冲清空后的真状态（引擎队列已空）
    }
    var all = new ArrayList<String>();
    all.addAll(cleared.steering());
    buffered.stream().filter(q -> q.mode() == StreamingBehavior.STEER)
        .forEach(q -> all.add(q.text()));
    all.addAll(cleared.followUp());
    buffered.stream().filter(q -> q.mode() == StreamingBehavior.FOLLOW_UP)
        .forEach(q -> all.add(q.text()));
    return all.isEmpty() ? null : String.join("\n\n", all);
}
```

（序说明：pi 是 `steering: [...engine, ...compaction]`、`followUp: [...engine,
...compaction]` 再 `[...steering, ...followUp]`——上表逐字等价。）

#### 3.6 `PiTuiApp` DEQUEUE 臂（pi `handleDequeue :4507-4513`）

```java
case KeybindingsManager.DEQUEUE -> {
    // docs/21（T4，S20）：pi handleDequeue——排队取回编辑器（含压缩缓冲）。
    var queuedText = mode.restoreQueuedMessages();
    if (queuedText == null) {
        chatScreen.appendSystemText("No queued messages to restore");
    } else {
        // pi :4754-4756：queuedText 在前、当前草稿在后，空白段丢弃，"\n\n" 连接
        var combined = java.util.stream.Stream.of(queuedText, chatScreen.inputText())
            .filter(t -> !t.isBlank())
            .collect(java.util.stream.Collectors.joining("\n\n"));
        chatScreen.setEditorText(combined);
        int count = /* restoreQueuedMessages 返回条数——见下 */;
        chatScreen.appendSystemText(
            "Restored " + count + " queued message" + (count > 1 ? "s" : "") + " to editor");
    }
}
```

条数随文本一起返回：`restoreQueuedMessages` 改返
`record RestoredQueue(String combinedText, int count)`（null 代空）——文案
复数形依赖条数（pi `:4512`）。

#### 3.7 `PendingQueueView` hint 行（pi `:4736-4738`）

构造注入键名串（PiTuiApp 装配处从 `KeybindingsManager` 取默认键显示——
`strokeFor(DEQUEUE)` 格式化 `"alt+up"`/`"alt+q"`；用户覆写不支持是既有约束
D45 同类，不另登记）：

```java
// pi :4736-4738：两队列任一非空 ⇒ 末行 dim `↳ {key} to edit all queued messages`
if (!isEmpty()) {
    ...
    rows.add(TamboUIAdapter.markupText("[dim]↳ " + dequeueKeyHint
        + " to edit all queued messages[/]"));
}
```

#### 3.8 `KeybindingsManager` 平台规则（pi `:71-74`＋`:16-20`）

```java
/** pi {@code useWindowsKeybindings}（keybindings.ts:16-20）：win32 或
 *  linux＋WSL 环境变量。包内静态、可测（osName/env 注入）。 */
static boolean useWindowsKeybindings(String osName, Map<String, String> env) {
    var name = osName.toLowerCase(Locale.ROOT);
    return name.contains("windows")
        || (name.contains("linux")
            && (env.containsKey("WSL_DISTRO_NAME") || env.containsKey("WSL_INTEROP")));
}

// 构造处：pi :71-74 —— Windows/WSL ⇒ alt+q，其余 alt+up
bindings.put(DEQUEUE, useWindowsKeybindings(System.getProperty("os.name"), System.getenv())
    ? List.of(KeyStroke.of("q", false, true, false))
    : List.of(KeyStroke.of("up", false, true, false)));
```

### T5：D41 重裁——两臂移植（tui，~40 行）

#### 3.9 Esc 流式臂（pi `:3071-3073`）

`PiTuiApp.handleAction` INTERRUPT 分流（pi `onEscape` 首臂对位：流式中 ⇒
回填＋abort；其余臂——bash 运行/bashMode/双击——java 无对位面，不在本批）：

```java
case KeybindingsManager.INTERRUPT -> {
    // docs/21（T5，D41 重裁）：pi :3071-3073——流式中 Esc 先把排队消息
    // 回填编辑器再 abort（不带 currentText 参 ⇒ 含当前草稿合并）。
    if (session.isRunning()) {
        var queuedText = mode.restoreQueuedMessages();
        if (queuedText != null) {
            var combined = Stream.of(queuedText, chatScreen.inputText())
                .filter(t -> !t.isBlank()).collect(Collectors.joining("\n\n"));
            chatScreen.setEditorText(combined);
        }
    }
    mode.abort();
}
```

（pi `{abort: true}` 的 abort 在 restore 内触发；java 拆开等价——restore 是
同步清队列，abort 紧随其后，无交错窗口。）

#### 3.10 树导航臂（pi `:5618-5621`）

`TreeNavigationFlow.startNavigation` 的 abort 段前插 restore。flow 跑 worker
虚拟线程（批 8 裁决④），队列清理线程安全（`clearQueue`/`buffer` 均
synchronized/并发安全），**编辑器写经 dispatcher**；`InteractiveMode` 经构造
参数注入（新增 `mode` 形参，PiTuiApp 构造点同步）：

```java
if (session.isRunning()) { // pi :5619-5621
    var queuedText = mode.restoreQueuedMessages();   // pi restore()——不带 abort 参
    if (queuedText != null) {
        dispatcher.dispatch(() -> {
            var combined = Stream.of(queuedText, chatScreen.inputText())
                .filter(t -> !t.isBlank()).collect(Collectors.joining("\n\n"));
            chatScreen.setEditorText(combined);
        });
    }
    session.abort();
    session.harness().waitForIdle(session.laneName());
}
```

扩展 abortHandler 两臂（pi `:1975-1978`/`:2259-2262`）：java 扩展面无
`ctx.abort()` 缝（E5 域欠账），D41 残余改述后**保留登记**（触发条件随 E5
批次）。合并逻辑三处重复 ⇒ 抽 `ChatScreen.restoreToEditor(String queuedText)`
（合并＋setText），三臂共用。

---

## §4 裁决条目

### 裁决① `ForkMessage{entryId, text}` record 收平返回面

`getUserMessagesForForking` 返回类型从 `List<Entry.Message>` 改为
`List<ForkMessage>`——pi 形就是 `{entryId, text}` 平表（RPC 线 `get_fork_messages`
载荷同形），文本提取（空串分隔）收进会话层单一事实点，RPC/TUI 两消费者不再
各自提文本（现有 `RpcDispatcher.forkMessageText` 的 `"\n"` 分隔偏差随之消除）。
唯一既有消费者是 `RpcDispatcher.GetForkMessages`，改型无第三方破面。

### 裁决② `forkFromEntry` 签名不动，selectedText 由调用侧查表

pi 的 `runtimeHost.fork` 返回 `{cancelled, selectedText}`；java
`forkFromEntry` 返回 `Optional<AgentSession>`（批 5/6 形，cancel⇔empty 已对齐）。
selectedText 不改签名携带——TUI/RPC 两侧都在 fork 前持有/可查
`getUserMessagesForForking()` 表，按 entryId 查得同值（pi 的 selectedText 与
该表同一条目的 text 必然相等：同 `extractUserMessageText` 语义、同 entry）。
代价：RPC 臂多一次 O(n) 查表（fork 是低频操作，忽略）。

### 裁决③ dequeue 键落 pi 平台规则（Windows/WSL ⇒ alt+q）

`KeybindingsManager` 构造按 `os.name`＋WSL 环境变量二选一绑键（pi
`useWindowsKeybindings` 逐字），不双绑（pi 单键；双绑会让 Alt+Q 在非 Windows
吃掉编辑器输入）。`os.name` 在 JVM 的 Windows 值含 `"windows"`、Linux 含
`"linux"`，判定串与 pi 的平台集合对齐。hint 行键名随绑定表取（`strokeFor`
首键格式化），不再是写死文案。

### 裁决④ D41 两臂移植、扩展臂残留改述

restore 面（T4）落地后，Esc 流式臂与树导航臂的移植成本各 ~15 行且共用
`ChatScreen.restoreToEditor`——本批一并落（D41 的「无既有取用面」前提消失）。
扩展 `ctx.abort()` 两臂在 java 无挂点（扩展 UI context 是 E5 域欠账），D41
改述为「扩展 abortHandler 的 restore 随 E5 批次」保留登记。D42（摘要窗口期
followUp 滞留压缩缓冲）**维持登记不动**——dequeue 给了手动取回通道，但
自动重放时机差仍在，不重裁。

### 裁决⑤ DynamicBorder 落形：`─ × width`，色 MUTED 近似

pi `theme.fg("border")` 是主题边色；java 主题面无 border 语义色（批 9
裁决「语义色近似值常量」先例），取 `SemanticColors.MUTED`。宽度源同批 9
裁决⑧（ResizeEvent 跟踪的 terminalCols）。

### 裁决⑥ 空表不开选择器；组件内 100ms 自动取消不移植

pi 主流不可达防御（宿主 `:5503-5506` 已预检 "No messages to fork from"）。
java 落宿主预检臂；组件内 `setTimeout(onCancel, 100)` 需要 TUI 定时机制，
为不可达路径引定时器不值——组件空表渲染（`"  No user messages found"`）
作为同形防御保留，Esc 可退。

### 裁决⑦ 合并/回填逻辑收 `ChatScreen.restoreToEditor`，三臂共用

pi 的合并式（`[queuedText, currentText].filter(trim).join("\n\n")`）在
dequeue／Esc 流式／树导航三处逐字相同。java 三臂分属 PiTuiApp×2＋
TreeNavigationFlow，收一个 `ChatScreen.restoreToEditor(String queuedText)`
（内部读 `inputText()`、合并、`setEditorText`），杜绝三份抄写法漂移。

### 裁决⑧ `TreeSelectorScreen` 删除而非留档

`/tree`（批 9）与 `/fork`（本批）改道后零用户；其 lane 平表形态在 pi 无
对位（pi 的 lane 概念不存在），留档只会再制造「同名两选择器」认知差
（docs/19 §7 已记该风险）。测试同步删；git 历史即档案。

---

## §5 窄差表（本批新增，接续批 9 的 D43–D46/D48）

| 编号 | 内容 | 影响 |
|---|---|---|
| D49 | `app.message.followUp` 键平台分叉未落：pi Windows/WSL 是 `ctrl+q`（`keybindings.ts:64-67`），java 恒 `alt+enter`（本批 dequeue 取证撞出；同裁决③规则，但 followUp 换键会动既有用户肌肉记忆，单独裁决过） | [用户可见，边缘] |
| D50 | `/fork <name>` 带参臂（`forkCopy`）是 java 独有便捷路：pi `/fork` 精确匹配无参（`:3273`），带参文本不落 fork | [用户可见，边缘] |
| D51 | RPC `fork` 对非 user 消息 entry 不报错：pi position "before" 校验目标必须是 user 消息否则抛 `"Invalid entry ID for forking"`（runtime `:281-284`）；java `forkFromEntry` 对任意 entry fork-before | [边缘] |

D41 改述保留（裁决④）；D42 维持不动（裁决④）。

---

## §6 测试计划

### coding-agent

- **`getUserMessagesForForking` 修真**（持久会话＋内存两路）：① 多分支会话——
  他枝 user 消息也在列（pi getEntries 全文件语义，先 RED：现行单车道源漏
  他枝）；② 空文本 user 消息（纯 image 块）滤掉；③ 多 text 块 `""` 拼接
  （RED：现 RPC 侧 `"\n"`）；④ 时间序（旧→新）保持。
- **RPC 两点**：`get_fork_messages` 载荷 text 空串分隔；`fork` 成功臂
  `{text: 选中消息 text, cancelled: false}`（RED：现恒 `""`）、cancel 臂
  `{text: "", cancelled: true}` 不动。
- `textOf` 分隔符参数化后既有 `"\n"` 调用点（导航 editorText）回归不破。

### tui

- **`UserMessageSelectorScreenTest`**（overlay 直驱不跑 runner——批 8/9 手法）：
  ① 初始选中最后一条；② up 到顶环绕到底、down 到底环绕到顶（pi :84-90
  逐字）；③ 窗口滑动公式（n=15：sel=0 ⇒ [0,10)、sel=7 ⇒ [2,12)、sel=14 ⇒
  [5,15)）；④ render 行形逐字断言——光标列 `› `/两空格、选中加粗、单行化
  （`\n`→空格 trim）、序位行 `  Message i of n`、条间空行、滚动指示
  `  (sel+1/n)` 的出现条件（startIndex>0 || endIndex<n）；⑤ Enter ⇒
  `onSelect(entryId)`、Esc ⇒ `onCancel`；⑥ 容器头尾行（粗体标题/muted 副标/
  border 行 `"─"×width`）。
- **dequeue 流**：① `InteractiveMode.restoreQueuedMessages`——引擎 steer＋
  followUp＋压缩缓冲混合 ⇒ pi 序（引擎 steer、缓冲 steer、引擎 followUp、
  缓冲 followUp）＋`"\n\n"` 连接＋count；空 ⇒ null；② 清理后 queue_update
  真空（缓冲臂）；③ `ChatScreen.restoreToEditor` 合并三态（有草稿/空草稿/
  空白草稿 ⇒ pi `filter(t.trim())` 逐字）；④ 状态文案逐字（`No queued
  messages to restore`／`Restored 1 queued message …`／`Restored 2 queued
  messages …`）；⑤ hint 行渲染（非空 ⇒ 末行 `↳ alt+up to edit all queued
  messages`，键名随平台表）。
- **`KeybindingsManager` 平台规则**：`useWindowsKeybindings` 四态
  （windows／linux＋WSL_DISTRO_NAME／linux＋WSL_INTEROP／linux 纯）＋构造后
  `matches(DEQUEUE, …)` 命中正确键。
- **Esc 流式臂 / 树导航臂**：PiTuiApp 级（流式中 INTERRUPT ⇒ 编辑器含排队
  文本＋abort 调用）；TreeNavigationFlow 直驱（restore 先行于 abort，编辑器
  写经 dispatcher——批 8 既有 rig 扩展）。
- **`/fork` 流**：空表 ⇒ `No messages to fork from` 不开 overlay；选中 ⇒
  `forkFromEntry`＋switchSession＋`setEditorText(选中 text)`＋
  `Forked to new session`；cancel（before_fork 门）⇒ 无切换无文案；异常 ⇒
  showError。

### 验证口径（§7 同批 9 纪律）

`mvn test -pl pi-java-coding-agent,pi-java-tui -am` 全绿 ⇒ `mvn checkstyle:check`
⇒ 收口前 `mvn clean verify` 全量绿（~34 min；日志勿放 `target/`）。

---

## §7 风险与开放点

- **`ForkMessage` 改型**破面仅限 `RpcDispatcher`（唯一消费者，裁决①已核）；
  若有遗漏编译即抓。
- **平台换键**：Windows 上 dequeue 从 `alt+up` 变 `alt+q`——pi 逐字，但是本批
  唯一的用户肌肉记忆变化（java 侧该键从未接线，无存量用户，实为零成本）。
- **`accumulatedEntries` 兜底序**：持久面空时落 harness 转录（单车道）——
  与 pi 全文件语义在「持久空会话」边缘仍有差，属既有兜底方言不单列。
- **double-escape → fork**：pi 双击 Esc 也可开 fork 选择器（`:3081-3097`），
  java 的 double-escape 设置项存在但未实现（SettingsScreen 字段悬空）——不属
  本批（settings 域），开放点注明。
- **E6 读数**：PiTuiApp（559 行）本批 +~50（fork 流＋dequeue/INTERRUPT 臂），
  闭环实测回填；拆分随 E6 立项，不本批。

---

## §8 对账表（预期）

| 行 | 前 | 后 | Δ |
|---|---|---|---|
| map/04 #15 `/fork`（权重 2） | 存疑 | **对齐** | +1.0 |
| map/05 S20 排队消息选择器（权重 1） | 缺失 | **对齐** | +1.0 |
| map/05 R28 排队消息区（权重 1） | 存疑 | 存疑（证据更新：hint 行＋取回流齐，流式 bash/工具挂载仍缺） | 0 |
| 模块 Σ487 | 286.0 | **288.0（59.1%）** | +2.0 |
| 全仓 Σ1381 | 958.0 | **960.0（69.5%）** | +2.0 |
| D 域 Σ83 | 73.0 | 73.0（88.0%） | 0 |

工单：**B254 销号**；D41 改述保留（扩展臂随 E5）；新登 D49–D51。

---

## §9 闭环回填

**闭环日 2026-10-22**（设计审核通过当日落地）。

### commits（4 笔）

| commit | 内容 |
|---|---|
| `41c3c197` | feat(coding-agent): T1——ForkMessage 收平＋全分支源＋RPC 两点修真 |
| `b646157b` | feat(tui): T2——UserMessageSelectorScreen＋11 规格测 |
| `9e0eea86` | feat(coding-agent): T3/T4——dequeue 平台键＋restoreQueuedMessages＋/fork 描述 |
| `482c10f6` | feat(tui): T3/T4/T5——/fork 改道＋dequeue 链＋D41 两臂＋TreeSelectorScreen 删 |

### 落地对账（实算）

| 行 | 前 | 后 | Δ |
|---|---|---|---|
| map/04 #15 `/fork`（权重 2） | 存疑 | **对齐** | +1.0 |
| map/04 E3-8 `/fork`（权重 2） | 存疑 | **对齐** | +1.0（**设计对账表漏列**——同能力行按逐行判定规则同翻，闭环实算补） |
| map/05 S20 排队消息选择器（权重 1） | 缺失 | **对齐** | +1.0 |
| map/05 K11 dequeue 键（权重 1） | 缺失 | **对齐** | +1.0（**设计对账表漏列**，同前） |
| map/05 R28 排队消息区（权重 1） | 存疑 | 存疑（证据更新：hint 行＋取回流齐，流式 bash/工具挂载仍缺） | 0 |
| 模块 Σ487 | 286.0 | **288.0（59.1%）** | +2.0（设计预期恰为此值——但构成是 #15＋E3-8，非 #15＋S20；S20/K11 落在 tui/web 域） |
| tui/web Σ245 | 110.5（域表） | **112.5（45.9%）** | +2.0（S20＋K11；总表行 108.5→110.5/45.1%，旧账漂移 2.0 未随批追） |
| 全仓 Σ1381 | 958.0 | **962.0（69.7%）** | +4.0 |
| D 域 Σ83 | 73.0 | 73.0（88.0%） | 0 |

工单：**B254 销号**；**B66 核账 6→4**（DEQUEUE 本批接、MODEL_CYCLE ai 批 3b 早接——原记两处滞后）。D41 重裁两臂落地、扩展臂残留改述保留（随 E5）；D42 维持登记。

### 窄差

新登 **D49–D51**（§5 逐字）：followUp 平台键未落／带参 `forkCopy` 独有／RPC `fork` 不校验 user 目标。

### 超设计落地（闭环实测/修真记录）

1. **对账表漏两行**：E3-8／K11 与 #15／S20 是同能力的重复登记行，逐行判定规则要求同翻——账面 Δ 从设计 +2.0 实算为 **+4.0**（模块口径数值不变：S20/K11 记 tui/web 域）。
2. **hint 行键名大写形**：§3.7 草样写 `"alt+up"` 小写，pi 实际走 `getAppKeyDisplay`＝`keyDisplayText`（**首字母大写**，`keybinding-hints.ts:38-40`）——落 `KeybindingHints.displayText`，hint 行为 `↳ Alt+Up/Alt+Q to edit all queued messages`（§1.5 取证行更权威）。
3. **容器头副标词换行**：pi `Text(paddingX=1)` 按 width−2 词换行（80 列下副标折两行）——`wrapWords` 助手落形，测试按两行/三行断言。
4. **pi `truncateToWidth` 默认带 `...` 省略号**（pi-tui utils.ts:1121）：截断助手按 `maxWidth−3` 累计＋省略号；§T2 只写「列宽截断」，省略号是取证补正。
5. **引擎/缓冲混合序分测**：手动 compact 先 abort 在飞 run，「run 在飞＋压缩窗口」同存态构造不出——引擎臂、缓冲臂分测（pi 混合序由两数组拼接逐字对拍），见 `InteractiveModeRestoreTest` 类注释。
6. **KeybindingsManager 平台旗可注入构造**（包内 `KeybindingsManager(boolean)`）：`os.name` 系统探测不可测，测试面注入——设计只画了静态 `useWindowsKeybindings`。
7. **E6 实测 +56**（559→615；设计预估 +~50）；E1 2,060→2,083（+23）；E2 674→667（−7）。

### 验证口径（全绿）

`mvn test -pl pi-java-tui,pi-java-coding-agent -am`（tui 299 测）⇒ `mvn checkstyle:check` ⇒ **`mvn clean verify` 全量绿**（9:41 min，EXIT=0，2026-10-22；日志在系统临时目录，未放 `target/`）。新增测试 29：coding-agent 8（ForkMessages 2＋RPC 1＋Keybindings 2＋Restore 3）、tui 21（Selector 11＋ForkFlow 5＋Dequeue 4＋TreeFlow +1）；中途两修：fork 流测试编辑器残留回填文本（clearInput 补）、dequeue 测试 abort 后缺 waitForIdle（close 与 run 收尾并发双写持久面）。

### 台账回填清单（四处＋）

- docs/04：B254 销号标记、B66 核账、批 10 闭环条目（D41 改述＋D49–D51＋账面）、E1/E2/E6 实测行数。
- docs/05：总表（coding-agent 行＋tui/web 行＋合计行＋头部注记）、§2.4 批 10 条目、§2.5 tui/web 读数、§4 死功能清单 row 2。
- docs/map/04：#15 行＋E3-8 行翻转、C 域/E3 两汇总行。
- docs/map/05：S20/K11 行翻转、R28 证据更新、B66 行、缺口列举（K11 出列）、加权汇总（域表两行＋模块合计＋权重 1 档＋漂移注）。
- docs/20：§1 四项读数、§2 批 10 行、§3 批 10 行移除＋触发条件型欠账 D41/D42 改述。
- 记忆：批 10 闭环＋**下一包号 docs/22**。
