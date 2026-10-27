# docs/37 — coding-agent 批 26 设计稿：E5 扩展 API 面第三批（ctx 动作面收口）

【背景摘要】批 25（docs/36，tui Markdown 激活）闭环后，§3 计划表已清空。用户拍板批 26
＝ **E5 扩展 API 面第三批**（选题 A）：把扩展上下文（`ExtensionContext`，pi 的
`ExtensionAPI`）剩余 16 个缺失/存疑行中除「虚拟模型（E5-26/27）、扩展示例库（E5-33）、
UI 注入余面（E5-31）」之外的 14 行一次收口，并顺带收 map/05 R6（markdown transformer
消费缝）。E5 域当前 29.0/58 ≈ 50.0%，是模块内最大残域；批 4/5/16/17/23/24 已把事件面、
激活门、UI 注入层、MCP 注册面全部铺好，本批是纯消费面与动作面的收口。

术语（首现释义）：

- **ctx / ExtensionContext**：扩展拿到的上下文对象。pi 侧叫 `ExtensionAPI`（`pi.xxx()`），
  java 侧是 `ExtensionContext`＋`DefaultExtensionContext`（per-extension 委托）。
- **车道（lane）**：agent-core 的运行单元（`AgentHarness`/`PiLaneEngine`），持消息工作
  副本与 steer/followUp/nextRun 三个队列。
- **steer / followUp**：流式中插队的两种语义——steer＝当前轮结束后立刻插队，
  followUp＝整轮收尾后补一轮。
- **存疑/缺失/对齐**：map 三档判定（0.5/0/1.0 计分）。

---

## 1. 命题表（待逐条落地）

| # | map 行 | 现状 | 目标 | pi 锚点 | java 落点 |
|---|---|---|---|---|---|
| P1 | E5-12（w2，存疑） | `sendMessage` 仅落盘；`triggerTurn=true` 抛 UOE | 五臂全行为（§2.1） | agent-session.ts:2293-2329 | `AgentSession.sendCustomMessage` 新面＋`DefaultExtensionContext` 改委托 |
| P2 | E5-13（w2，缺失） | 无 `sendUserMessage` | 全形（§2.3） | agent-session.ts:2365-2393 | ctx 新面 → `processPrompt` |
| P3 | E5-14（w1，缺失） | 无 `appendEntry` | 落 custom 条目＋`entry_appended` | loader.ts:391-394＋agent-session.ts:3405-3411 | `AgentSession.appendCustomEntry`（已在，:2662）→ ctx 委托 |
| P4 | E5-15（w1，缺失） | 无 set/getSessionName | 规范化＋双发 | agent-session.ts:3942-3947 | `AgentSession.setSessionName`（已在，:1337）→ ctx 委托 |
| P5 | E5-16（w1，缺失） | 无 `setLabel` | 含「目标不存在抛错」 | session-manager.ts:1441-1462 | `AgentSession.setLabel`（已在，:1550）→ ctx 委托＋抛错修真 |
| P6 | E5-17（w2，缺失） | 无 `exec` | 全形（§2.5） | core/exec.ts:1-107 | 新件 `ExtensionExec` |
| P7 | E5-21（w2，缺失） | 无 `setModel` | auth 门⇒false（§2.4） | agent-session.ts:3429-3432＋:2477-2480 | ctx → `AgentSession.setModel`＋auth 检查 |
| P8 | E5-22（w2，缺失） | 无 get/setThinkingLevel | clamp＋双事件 | agent-session.ts:2612-2635 | ctx → `AgentSession.setThinkingLevel`（:1088）＋`harness.getThinkingLevel` |
| P9 | E5-6（w2，缺失） | 无 `registerShortcut` | 注册＋冲突三规则＋TUI 派发＋/hotkeys 段（§2.7） | runner.ts:95-135/:673-716；interactive-mode.ts:2238-2295/:6901-6915 | `ExtensionShortcuts` 注册表＋`PiTuiApp.onKeyEvent` 缝 |
| P10 | E5-8/E5-10（w2×2，缺失） | 无 message/entry renderer 注册面 | 注册表＋TUI 消费缝（§2.8） | runner.ts:775-805；interactive-mode.ts:3846-3866/:3889-3902 | 两注册表＋`ChatMessage.project` 两臂改查表 |
| P11 | E5-9（w2，缺失）＋R6（map/05，w1，缺失） | 无 transformer 注册与消费 | 注册＋渲染前变换链（§2.9） | markdown-transform.ts:1-29 | `MarkdownRenderer.toLogicalLines` 起手缝 |
| P12 | E5-28（w1，缺失） | 无 `events` | channel 总线＋失效退订（§2.6） | core/event-bus.ts:1-33 | 新件 `ExtensionChannelBus` |
| P13 | E5-5（w1，缺失） | 无 `unregisterProvider` | 删注册（§2.10） | loader.ts:220-224/:466-468 | ctx → `ProviderRegistry.remove` |

**不进本批**（挂账，§3 裁决①）：E5-26/E5-27（registerVirtualModel/unregisterVirtualModel，
虚拟模型整域零对应）、E5-33（examples 库，F 类变量 1）、E5-31 余面（维持存疑挂账）。

---

## 2. pi 规格逐字（关键段）

### 2.1 `sendCustomMessage` 五臂（agent-session.ts:2293-2329 逐字）

```ts
async sendCustomMessage<T = unknown>(
	message: Pick<CustomMessage<T>, "customType" | "content" | "display" | "details">,
	options?: { triggerTurn?: boolean; deliverAs?: "steer" | "followUp" | "nextTurn" },
): Promise<void> {
	const appMessage = {
		role: "custom" as const,
		customType: message.customType,
		// Untyped extensions can pass null/missing content; normalize at ingestion.
		content: message.content ?? [],
		display: message.display,
		details: message.details,
		timestamp: Date.now(),
	} satisfies CustomMessage<T>;
	if (options?.deliverAs === "nextTurn") {
		this._pendingNextTurnMessages.push(appMessage);
	} else if (this.isStreaming && options?.triggerTurn !== false) {
		if (options?.deliverAs === "followUp") {
			this.agent.followUp(appMessage);
		} else {
			this.agent.steer(appMessage);
		}
	} else if (options?.triggerTurn) {
		if (this._isEmittingAgentSettled) {
			this._deferredSettledActions.push(async () => await this._runAgentPrompt(appMessage));
			return;
		}
		await this._runAgentPrompt(appMessage);
	} else if (this.isStreaming) {
		// Appending now would put the message between an assistant tool call and its
		// result, which providers that validate message order reject on replay. Defer
		// to the end of the turn. Nothing is emitted yet: message events must not
		// describe messages the session tree does not contain.
		this._pendingCustomMessages.push(appMessage);
	} else {
		this._appendCustomMessage(appMessage);
	}
}
```

臂序即语义（判定是 if-else 链，次序不可换）：

1. `deliverAs="nextTurn"` ⇒ 进 `_pendingNextTurnMessages`，**无视** triggerTurn 与流式状态。
2. 流式中 且 `triggerTurn !== false` ⇒ `deliverAs="followUp"` 进 followUp 队列，**否则进
   steer 队列**（即流式默认臂＝steer）。
3. `triggerTurn=true`（非流式）⇒ 若正在发 `agent_settled` 事件（`_isEmittingAgentSettled`），
   进 `_deferredSettledActions` 延迟动作表；否则直接 `_runAgentPrompt` 起一轮。
4. 流式中且 `triggerTurn=false` ⇒ 进 `_pendingCustomMessages`（上下文-only 延迟落盘；
   此刻**不发任何消息事件**）。
5. 其余（空闲、无 triggerTurn）⇒ `_appendCustomMessage`：立即落盘＋发
   `message_start`/`message_end` 对（agent-session.ts:2331-2341）。

content 归一：`null`/`undefined` ⇒ `[]`（java 对位：`CustomMessageContent` 为 null ⇒ 空
Blocks）。

### 2.2 三缓冲的消费点（agent-session.ts）

- **`_pendingNextTurnMessages`**：`prompt()` 组装点注入（:2088-2092 逐字）——

```ts
		// Inject any pending "nextTurn" messages as context alongside the user message
		for (const msg of this._pendingNextTurnMessages) {
			messages.push(msg);
		}
		this._pendingNextTurnMessages = [];
```

- **`_pendingCustomMessages`**：`_flushPendingCustomMessages()`（:2348-2355）逐条
  `_appendCustomMessage`。四个调用点：turn_end 事件处理尾（:1187-1195）、run finally
  （:1843-1850）、before_settle 边界内（:1898-1904）、prompt 主路径起手（:2028-2030）。
- **`_deferredSettledActions`**：`_emitAgentSettled` 尾部 splice 全量顺序执行
  （:1078-1099）；prompt 起手同门（:1968-1972）。

triggerTurn 臂的持久化不在 `sendCustomMessage` 里——custom 消息经 `agent.prompt(messages)`
进 loop，loop 发 `message_end`，agent-session 的 message_end 处理器把 `role==="custom"`
落盘为 custom_message 条目（:1147-1155）。

### 2.3 `sendUserMessage`（agent-session.ts:2365-2393 逐字）

```ts
async sendUserMessage(
	content: string | (TextContent | ImageContent)[],
	options?: { deliverAs?: "steer" | "followUp"; expandPromptTemplates?: boolean },
): Promise<void> {
	// Normalize content to text string + optional images
	let text: string;
	let images: ImageContent[] | undefined;

	if (typeof content === "string") {
		text = content;
	} else {
		const textParts: string[] = [];
		images = [];
		for (const part of content) {
			if (part.type === "text") {
				textParts.push(part.text);
			} else {
				images.push(part);
			}
		}
		text = textParts.join("\n");
		if (images.length === 0) images = undefined;
	}

	await this.prompt(text, {
		expandPromptTemplates: options?.expandPromptTemplates ?? false,
		streamingBehavior: options?.deliverAs,
		images,
		source: "extension",
	});
}
```

要点：数组形 ⇒ 文本块 `"\n"` 拼接＋图片另表；**`expandPromptTemplates` 默认 false**
（不经扩展命令/skill/模板展开）；`source: "extension"`（input 事件 source 字段的
"extension" 生产者即此）。

### 2.4 setModel / thinking level / sessionName / setLabel

- 扩展面 `setModel`（agent-session.ts:3429-3432 逐字）：auth 未配置 ⇒ **返回 false 不抛**——

```ts
				setModel: async (model) => {
					if (!this._modelRuntime.hasConfiguredAuth(model.provider)) return false;
					await this.setModel(model);
					return true;
				},
```

本体 `setModel`（:2477-2496）：`checkAuth` 失败抛 `No API key for <provider>/<id>`；
`appendModelChange` 落盘；persist 门本批不走（扩展面恒不 persist）；模型切换的
thinking level 跟随 `_getThinkingLevelForModelSwitch`；发 `model_select`（源 `"set"`）。
- `setThinkingLevel`（:2612-2635 逐字已取）：clamp 到 `getAvailableThinkingLevels()`；
  级别真变才 `appendThinkingLevelChange`＋发 `thinking_level_changed`＋扩展事件
  `thinking_level_select`（载荷 `{level, previousLevel}`）。
- `setSessionName`（:3942-3947 逐字已取）：`appendSessionInfo`（换行消毒＋trim，
  session-manager.ts:1305）→ `session_info_changed` 双发（宿主事件＋扩展事件），载荷
  是**规范化后**的名。
- `appendLabelChange`（session-manager.ts:1441-1462 逐字已取）：目标 id 不在 `byId` ⇒
  抛 `` `Entry ${targetId} not found` ``；label 非空 ⇒ 写 `labelsById`＋`labelTimestampsById`，
  空 ⇒ 删两表。

### 2.5 `exec`（core/exec.ts:1-107 全文已取，要点）

- `spawn(command, args, { cwd, shell: false, stdio: ["ignore","pipe","pipe"] })`——**无壳**。
- 终止两段式：先 `SIGTERM`，5 秒未死再 `SIGKILL`（`killed` 标记只置一次）。
- `options.signal` 已中止 ⇒ 立即 kill；否则挂一次性 abort 监听。
- `timeout > 0` ⇒ 到点 kill。
- 正常退出 `code = code ?? 0`；**spawn/等待出错 ⇒ `code: 1` 吞异常**（catch 臂 resolve）。
- 结果 `{stdout, stderr, code, killed}`。
- loader.ts:411-413：`options?.cwd ?? <扩展加载 cwd>`。

### 2.6 `events`（core/event-bus.ts:1-33 全文已取，要点）

- `createEventBus()`：Node `EventEmitter` 包裹；`emit(channel, data)` 同步派发；
  `on(channel, handler)` 返回退订；**handler 异常被吞**（`console.error` 后续 handler
  照常）；`clear()` 全清。
- loader 接线：订阅经 `runtime.trackEventBusSubscription` 登记（loader.ts:517-522），
  运行时被 invalidate（会话替换/reload/加载失败）时统一退订（loader.ts:185-201）。

### 2.7 快捷键（runner.ts:95-135 / :673-716；interactive-mode.ts:2238-2295）

**保留表**（runner.ts:96-115 逐字，18 条）：

```ts
const RESERVED_KEYBINDINGS_FOR_EXTENSION_CONFLICTS = [
	"app.interrupt",
	"app.clear",
	"app.exit",
	"app.suspend",
	"app.thinking.cycle",
	"app.model.cycleForward",
	"app.model.cycleBackward",
	"app.model.select",
	"app.tools.expand",
	"app.thinking.toggle",
	"app.editor.external",
	"app.message.copy",
	"app.message.followUp",
	"tui.input.submit",
	"tui.select.confirm",
	"tui.select.cancel",
	"tui.input.copy",
	"tui.editor.deleteToLineEnd",
] as const;
```

**冲突三规则**（getShortcuts，runner.ts:673-716 逐字已取）：

1. 内置键 `restrictOverride === true`（在保留表）⇒ 扩展快捷键**跳过**＋诊断
   `` `Extension shortcut '${key}' from ${path} conflicts with built-in shortcut. Skipping.` ``。
2. `restrictOverride === false` ⇒ **扩展赢**＋诊断
   `` `Extension shortcut conflict: '${key}' is built-in shortcut for ${kb} and ${path}. Using ${path}.` ``。
3. 扩展互撞 ⇒ **后者赢**＋诊断
   `` `Extension shortcut conflict: '${key}' registered by both ${a} and ${b}. Using ${b}.` ``。
4. 键一律 `toLowerCase()` 归一；无 UI 时诊断走 `console.warn`；诊断同时进
   shortcutDiagnostics（warning 型，随资源诊断面上报）。

**派发缝**（interactive-mode.ts:2238-2295 逐字已取）：编辑器 `handleInput` **最先**查
`onExtensionShortcut`（custom-editor.ts:88-92——先于一切内置键处理）；命中即异步跑
handler（不阻塞输入），出错 `showError("Shortcut handler error: ...")`。handler 拿到的是
**每次现造**的 ExtensionContext（createContext 闭包）。

**/hotkeys 消费点**（interactive-mode.ts:6901-6915 逐字已取）：`**Extensions**` 段
markdown 表格（`| Key | Action |`），键名 `formatKeyText(key, {capitalize:true})`，
描述 `shortcut.description ?? shortcut.extensionPath`。

### 2.8 message/entry renderer（types.ts:1505-1535；runner.ts:775-805）

类型（types.ts:1505-1535 逐字已取）：`MessageRenderer<T> = (message: CustomMessage<T>,
options: {expanded, outputPad}, theme: Theme) => Component | undefined`；
`EntryRenderer<T> = (entry: CustomEntry<T>, options: {expanded}, theme) => Component |
undefined`。

分发（runner.ts:775-805 逐字已取）：按**扩展加载序**逐个查 `messageRenderers.get(
customType)`/`entryRenderers.get(customType)`，首个命中即返（first-wins）。

TUI 消费（interactive-mode.ts:3889-3902 / :3846-3866 逐字已取）：

- custom 消息：`display` 为真才渲；`CustomMessageComponent(message, renderer,
  markdownTheme, outputPad)`；renderer 缺席 ⇒ 组件内部回落默认渲染（`renderer` 可
  undefined 传入）。
- custom 条目：`getEntryRenderer` 缺席 ⇒ **直接不渲**（return，无回落）；
  `component.hasContent()` 空 ⇒ 不渲。

### 2.9 markdownTransformer（markdown-transform.ts:1-29 全文逐字已取）

- 链式串行：上一个的输出是下一个的输入；**handler 抛异常 ⇒ 保留当前文本继续下一个**
  （静默吞）；返回值非 string ⇒ 丢弃该次结果。
- context 三参：`messageType: "user" | "assistant" | "assistant-thinking"`、
  `isStreaming`、`availableWidth`。
- 应用点：assistant 文本（assistant-message.ts:113-117，渲染前 trim 后）、thinking 块
  （:146-162）、user 消息（user-message.ts:42-58）；interactive-mode.ts:2234-2236 的
  `getMarkdownTransformers()` 把 mermaid 变换器排在扩展变换器**前面**。
- 注册是**单槽**：`extension.markdownTransformer = transformer`（loader.ts:361），同扩展
  重复注册后者覆盖。

### 2.10 `unregisterProvider`

- 文档语义（types.ts:1833-1846）：移除该 provider 全部模型＋**恢复被其覆盖的内置模型**；
  未注册 ⇒ 无效果；装载期调用＝从待注册队列过滤（loader.ts:220-224 逐字已取），绑定后
  即时生效（runner.ts:533-538 → `modelRegistry.unregisterProvider(name)`）。
- java 侧：`ProviderRegistry.remove(String)`（:90）已在；java 的 provider 注册不覆盖
  catalog 模型（`DefaultProviders.resetProviders` 保留扩展注册、catalog 模型另表重建），
  「恢复被覆盖内置模型」在 java 无对应场景（裁决⑥）。

---

## 3. 裁决点（拍板清单）

### 裁决①：E5-26/27（虚拟模型注册面）挂账，收编 B162

- 【场景】pi 的 registerVirtualModel 需要虚拟模型整域支撑（model-registry 注册、每请求
  路由、页脚显示、压缩上限、会话恢复回落），java 侧零对应（台账 B162「tool-search /
  virtual-models / nested-tool-calls 三块零对应」）。只做注册面不做消费＝死代码
  （B65 先例：批 25 裁决「接线不删声明」正是为此）。
- 【决定】E5-26/27 不进本批。B162 注记更新：virtual-models 单列后续批候选。
- 【风险/代价】无影响。[内部，模型不可见]

### 裁决②：R6（map/05）随本批收口

- 【场景】E5-9 的注册面无消费者即死代码；批 25 已把 markdown 渲染管线落成单一静态入口
  `MarkdownRenderer.toLogicalLines`，变换缝只需在 parse 起手加一道链（§2.9）。
- 【决定】本批同时落注册面与消费缝，map/05 R6 缺失→对齐。§3.3 变量 3 的 R6 部分就此
  拍板（R7 mermaid/R8 LaTeX 仍不拍板）。
- 【风险/代价】边缘场景：变换器是扩展代码，异常吞掉语义逐字（§2.9）。[内部]

### 裁决③：message/entry renderer 走文本级接口（批 16 裁决⑦先例）

- 【场景】pi 的 MessageRenderer/EntryRenderer 返回 TUI Component（可交互、焦点、展开态）。
  java 的渲染管线是 `ChatMessage → List<LogicalLine>` 静态投影（批 16 工具渲染器已按
  文本级对齐，窄差 D92）。
- 【决定】java 接口 `MessageRenderer = (CustomMessageView, RenderOptions) →
  List<LogicalLine>`（空表 ≙ pi 返回 undefined）。组件级交互（展开/焦点）不做。
- 【风险/代价】[模型可见]文案差异不涉及；渲染形状差异随 D92 同族登记新窄差。[用户可见]

### 裁决④：transformer 的 context 三参两臂窄差

- 【场景】pi 传 `isStreaming`（流式渲染中为真）与 `availableWidth`（当前可用宽）。java
  的 markdown 管线是静态投影：渲染时不分流式/非流式（`MessageBubble.lines` 无流式参数）、
  宽度无感（批 25 管线既定）。
- 【决定】`isStreaming` 恒 false、`availableWidth` 恒 -1（缺席哨兵）；`messageType`
  三值照传（assistant / assistant-thinking 两臂有真实消费点）。
- 【风险/代价】边缘场景：依赖宽度/流式态的 pi 扩展变换器行为不同。登记窄差。[内部]

### 裁决⑤：user 型 transformer 无消费点（本批不接 user 臂）

- 【场景】pi 的变换器应用于 user/assistant/assistant-thinking 三型；批 25 裁决⑤已裁
  「user 消息不走 markdown 渲染」（pi 的 userMessageBg 满宽底色管线 java 表达不了）。
- 【决定】消费缝只接 assistant/assistant-thinking 两臂；user 型变换器永不被调用，
  登记窄差。与批 25 口径一致，不回头改 user 臂。
- 【风险/代价】边缘场景。[内部]

### 裁决⑥：`unregisterProvider` 收窄为「删注册」

- 【场景】pi 的语义含「恢复被覆盖的内置模型」。java 的 `ProviderRegistry` 注册即覆盖同名
  （`register` 的 put 语义），但内置模型在 catalog 层、扩展 provider 在注册表层，两表不串；
  「覆盖内置模型」场景在 java 不存在（扩展 registerProvider 同名内置 provider 才会覆盖，
  此时 remove 后内置面也不自动回来——`resetProviders` 才重建）。
- 【决定】ctx.unregisterProvider ⇒ `ProviderRegistry.remove(name)`，未注册静默无效果
  （pi 同）。内置恢复面不做，登记窄差。
- 【风险/代价】边缘场景：扩展覆盖同名内置 provider 后又 unregister，java 侧该 provider
  保持缺席直到 /reload。[内部]

### 裁决⑦：`setLabel` 目标不存在抛错逐字修真

- 【场景】pi `appendLabelChange` 对未知 entryId 抛 `Entry <id> not found`。java
  `SessionStorage.setLabel` 的现存语义需实施时核（`JsonlSessionStorage`/`MemorySessionStorage`
  两臂）——若静默不抛则修真为抛 `IllegalArgumentException("Entry " + id + " not found")`
  （文案逐字）。
- 【决定】按核实结果修真；抛错文案逐字对位。
- 【风险/代价】边缘场景：存量调用点若依赖静默语义会暴露（评审项）。[内部]

### 裁决⑧：steer/followUp 队列携 custom 变体（agent-core 地基）

- 【场景】pi 的 `agent.steer(appMessage)`/followUp 收任意 AgentMessage（含 custom）；
  java 的 `QueuedItem(String prompt, List<PromptImage> images, long seq)` 只携文本。
- 【决定】`QueuedItem` 扩为可携 `Entry.CustomMessage` 的变体（sealed 或 nullable 字段，
  实施定形）；`PiLaneEngine.toMessages` 两臂（文本 ⇒ buildUserMessage；custom ⇒
  custom 消息进 LLM 上下文，转换走 `CustomMessageContent.toBlocks` 既有先例）。本批
  唯一动 agent-core 的点。
- 【风险/代价】存量约束：队列消费面（dequeue 链、queue_update 帧、树选择器）对 custom
  变体的显示投影需要一个兜底文案。[内部]

### 裁决⑨：`PromptConfig` 加 `expandPromptTemplates` 门（D117 部分修真）

- 【场景】pi `sendUserMessage` 默认 `expandPromptTemplates: false`（不展开 skill/模板/
  扩展命令）；java `processPrompt` 恒展开（批 20 注记 D117：「java 无 SDK 开关面、恒真」）。
- 【决定】`PromptConfig` 加 `expandPromptTemplates` 字段，**默认 true**（CLI/TUI 存量
  行为不变）；ctx.sendUserMessage 按 options 传值（默认 false）。D117 注记更新为「SDK
  面无、CLI 面恒 true、扩展面可调」。
- 【风险/代价】无影响（存量路径默认值保行为）。[内部]

### 裁决⑩：`sendMessage` 返回时机与异步面

- 【场景】pi `sendMessage` 是 async（triggerTurn 臂 await 整轮）。java 现有签名同步返回
  entryId；triggerTurn 臂要起一轮 LLM 回合，同步阻塞会卡扩展调用线程（pi 的 void-fire
  对位在 agent-session.ts:3389-3396：runtime 面 catch 后 emitError、**不阻塞**）。
- 【决定】ctx.sendMessage 保持「返回 entryId 或 null（triggerTurn/队列臂无即时条目）」；
  triggerTurn 臂异步起轮（虚拟线程），失败走 `ExtensionEventBus.emitError`（pi 的
  `event: "send_message"` 逐字）。
- 【风险/代价】边缘场景：调用方拿不到 triggerTurn 轮的完成信号（pi 的 runtime 面同样
  拿不到——pi 文档语义一致）。[内部]

### 裁决⑪：`getSessionName` 读 stored 名

- 【场景】pi 读 `sessionManager.getSessionName()`（最新 session_info 条目，空名显式清除
  ⇒ undefined）。java 有 `storedSessionName()`（批 20，B32）与该语义逐字对位；显示名
  `sessionName()`（"session" 回落）是另一件事。
- 【决定】ctx.getSessionName ⇒ `storedSessionName()`，null ≙ undefined。
- 【风险/代价】无影响。[内部]

### 裁决⑫：快捷键键串解析与大小写

- 【场景】pi 的 KeyId 是 `"ctrl+shift+x"` 形字符串，`getShortcuts` 一律 toLowerCase 归一。
  java `KeyStroke.of(key, ctrl, alt, shift)` 无串解析面。
- 【决定】新 `KeyIdParser`（coding-agent core）：`+` 分段，前缀段 ctrl/alt/shift/meta 旗，
  末段键名小写归一；未识别段 ⇒ 注册时抛 `IllegalArgumentException`（pi 侧 KeyId 由
  typebox schema 校验，装载期失败面不同批，本批只保证解析失败不静默）。
- 【风险/代价】边缘场景：pi 键名全集（如 `ctrl+shift+up`、功能键）解析覆盖以存量
  KeyStroke 键名表为准，未覆盖名抛错（诊断可见）。[内部]

---

## 4. 实施任务（T1→T11，依赖序）

- **T1 队列地基（agent-core）**：`LaneInfo.QueuedItem` 携 custom 变体（裁决⑧）；
  `PiLaneEngine.toMessages` 两臂；`QueueManager` steer/followUp 加入口
  （`steerCustom(lane, Entry.CustomMessage)` 等）；queue_update 帧与 dequeue 链对 custom
  变体的投影兜底（文案 `<custom:TYPE>` 形，实施定）。
- **T2 `AgentSession.sendCustomMessage` 五臂**（coding-agent）：新公开面，pi :2293-2329
  臂序逐字（§2.1）。三缓冲落地：`pendingNextTurnMessages`（processPrompt 组装点注入，
  pi :2088-2092 对位）、`pendingCustomMessages`（flush 四点对位：turn_end 引擎钩尾、
  run finally、before_settle 桥内、processPrompt 起手）、settled 延迟动作表（复用
  `appendDeferredLaneEntry` 的 deferred-write 通道，`_isEmittingAgentSettled` 对位旗
  实施时核——若无则加）。triggerTurn 臂异步（裁决⑩）。
- **T3 ctx 消息面**：`SendMessageOptions` 扩 `DeliverAs` 枚举（STEER/FOLLOW_UP/NEXT_TURN，
  null ≙ 未给）；`DefaultExtensionContext.sendMessage` 删 UOE 改委托 T2；
  `ExtensionContext.sendUserMessage(content, options)` 新面（§2.3 归一逐字＋
  `PromptConfig.expandPromptTemplates` 门，裁决⑨；source "extension" 传透）。
- **T4 ctx 会话四件**：`appendEntry`（委托 `AgentSession.appendCustomEntry`，已在）；
  `setSessionName`/`getSessionName`（裁决⑪）；`setLabel`（委托＋裁决⑦修真）。
  per-extension 委托四件同步透传。
- **T5 ctx 模型三件**：`setModel(ModelId) → boolean`（auth 门裁决：读
  `ModelResolver`/auth 存储的 configured 面，实施时定数据源；未配置 ⇒ false 不抛；
  配置 ⇒ `AgentSession.setModel` 已有全链 ⇒ true）；`getThinkingLevel`（⇒ harness 读）；
  `setThinkingLevel`（委托 `AgentSession.setThinkingLevel:1088`，clamp 语义实施核，
  不齐则对齐 §2.4）。
- **T6 `ExtensionExec`**（coding-agent 新件）：§2.5 逐字——ProcessBuilder 无壳、
  双泵、timeout⇒destroy→5s destroyForcibly、signal（java 取消令牌对位 AbortSignal）、
  异常 ⇒ `ExecResult(stdout, stderr, 1, killed)`。`ExtensionContext.exec(command, args,
  options)` 委托，cwd 默认扩展加载 cwd（loader.ts:413 对位）。
- **T7 `ExtensionChannelBus`**（coding-agent 新件）：§2.6 逐字——emit/on(退订)/clear；
  handler 异常吞＋`java.util.logging`（ExtensionEventBus.emitError 先例）；
  `ExtensionContext.events()` 访问器；per-extension 订阅登记到失效面（批 17
  `onSessionInvalidated` 三路径退订，pi trackEventBusSubscription 对位）。
- **T8 `unregisterProvider`**：ctx → `ProviderRegistry.remove`（裁决⑥）；per-extension
  委托透传。
- **T9 快捷键面**：`ExtensionShortcut` record（keyId/description/handler/extensionPath）；
  注册进 per-extension 委托；`ExtensionShortcuts.resolve(keybindings)` 实§2.7 三规则
  （保留表 18 条逐字＋诊断文案三句逐字＋无 UI ⇒ stderr）；`KeyIdParser`（裁决⑫）；
  TUI 派发缝：`PiTuiApp.onKeyEvent` 起手（overlay 之后、内置 resolve 之前，pi
  custom-editor.ts:88-92「最先查」对位）查扩展表，命中异步跑 handler（出错
  `showError("Shortcut handler error: ...")` 逐字）；`/hotkeys` 加 Extensions 段
  （interactive-mode.ts:6901-6915 逐字，批 25 markdown 表格形）。
- **T10 message/entry renderer 面**：`MessageRendererRegistry`/`EntryRendererRegistry`
  （first-wins，runner.ts:775-805 逐字）；ctx 注册两件；TUI 静态持有者两件
  （`ToolRenderers.install` 先例）＋`ChatMessage.project` 两臂改查表（CustomMessage 臂
  display 门保留、renderer 缺席回落现存 System 投影——pi 是组件内回落，java 投影层
  回落等价；Custom 臂 renderer 缺席 ⇒ 不渲，pi :3848-3850 逐字）。文本级接口（裁决③）。
- **T11 transformer 面**：ctx `registerMarkdownTransformer`（单槽覆盖，loader.ts:361
  对位）；tui 新 `MarkdownTransformers` 静态持有者（install/chain）；`MarkdownRenderer.
  toLogicalLines` 起手应用链（§2.9 语义逐字：串行、异常吞、null 返回丢弃）；context
  三参（裁决④⑤）。map/05 R6 翻行。

## 5. 窄差预案（登记不认账）

- D-new1：transformer context 的 `isStreaming` 恒 false、`availableWidth` 恒 -1（裁决④）
  [内部·边缘]
- D-new2：transformer 的 user 型永无消费点（裁决⑤，批 25 裁决⑤连带）[内部·边缘]
- D-new3：message/entry renderer 文本级（无组件交互/展开态；D92 同族）[用户可见·渲染粒度]
- D-new4：unregisterProvider 无内置模型恢复面（裁决⑥）[内部·边缘]
- D-new5：exec 终止语义（pi SIGTERM→SIGKILL 双信号；java destroy/destroyForcibly，Windows
  无信号区分）[内部·边缘]
- D-new6：EventBus handler 异常走 `java.util.logging` 不走 console.error（面外）[内部]
- D-new7：扩展快捷键 handler 的 ExtensionContext 是**共享 per-extension 委托**（pi 每次
  现造；java 现造代价＝重组装，无行为差）[内部]
- D117 注记更新：expandPromptTemplates 门落地（扩展面可调；SDK 面仍无）[维持]
- D12 注记更新：input 事件 `source: "extension"` 生产者落地（T3）；images 面注记随实施核
- 行为变化注记（changelog 级）：`sendMessage(triggerTurn=true)` 从抛 UOE 变为起轮；
  custom 消息流式默认臂＝steer 插队（此前无此面）；/hotkeys 增 Extensions 段（有扩展
  快捷键时）。

## 6. 测试计划

- T1：QueuedItem custom 变体往返（queue/drain/序列化若涉）；toMessages 两臂（文本回归＋
  custom 进上下文块形）；dequeue 链 custom 投影。
- T2 五臂钉死：nextTurn 缓冲→下一 prompt 注入＋清空；流式 steer/followUp 两臂入队；
  triggerTurn 起轮（假 provider 钉一轮）＋settled 在飞延迟臂；流式 triggerTurn=false ⇒
  turn_end 后落盘＋message_start/end 对；空闲无 triggerTurn ⇒ 立即落盘＋双事件。
  content null ⇒ 空 Blocks 归一。
- T3：sendUserMessage 字符串/数组两臂归一（`\n` 拼接、空图片表⇒null）；expandPromptTemplates
  默认 false（skill 前缀原文透传钉）＋true 臂展开；source "extension" 进 input 事件。
- T4：appendEntry ⇒ EntryAppended＋落盘；setSessionName 规范化（换行→空格、trim、空名
  清除）＋session_info_changed 双发；getSessionName null 臂；setLabel 设置/清除/未知 id
  抛错逐字。
- T5：setModel auth 门两臂（false 不换模；true 换模＋model_select）；thinking level
  clamp＋真变才双事件（level/previousLevel 载荷）。
- T6：exec 正常回（stdout/stderr/code）、timeout kill 臂、signal 中止臂、spawn 失败
  code=1。
- T7：emit/on/退订/clear；handler 抛异常后续 handler 照常；会话失效退订（三路径之一钉）。
- T8：remove 已注册/未注册两臂。
- T9：三冲突规则各一钉（保留表跳过/非保留内置扩展赢/扩展互撞后者赢）＋诊断文案逐字；
  KeyIdParser 大小写归一与非法键抛错；TUI 派发优先于内置键（golden）；/hotkeys
  Extensions 段形状钉。
- T10：first-wins 两扩展序；display=false 不渲；entry renderer 缺席不渲；消息 renderer
  缺席回落。
- T11：链式串行（上出下进）；异常吞＋续链；null 返回丢弃；单槽覆盖。
- 回归：全量 `mvn clean verify`（含 spotbugs）。

## 7. 账面预算（逐行实算）

| 行 | 权重 | 翻动 | 得分 |
|---|---|---|---|
| E5-12 | 2 | 存疑→对齐 | +1.0 |
| E5-5/14/15/16/28 | 1×5 | 缺失→对齐 | +5.0 |
| E5-6/8/9/10/13/17/21/22 | 2×8 | 缺失→对齐 | +16.0 |
| **E5 域计** | | | **+22.0** ⇒ 29.0→**51.0/58 ≈ 87.9%**（对齐 17→31/35；余缺失 E5-26/27/33，存疑 E5-31） |
| map/05 R6 | 1 | 缺失→对齐 | +1.0 ⇒ 132.5→**133.5/244 ≈ 54.7%** |
| **模块（Σ485）** | | | 416.0→**438.0 ≈ 90.3%** |
| **全仓（Σ1379）** | | | 1,132.0→**1,155.0 ≈ 83.8%** |

## 8. verify 计划

1. 定向：`mvn test -pl pi-java-agent-core,pi-java-coding-agent,pi-java-tui -am`（每 T 落
   后即跑；底层改动必须 `-am`，批 25 再撞过旧 jar 坑）。
2. 收口：全量 `mvn clean verify`（exit 0；checkstyle 写明错误/警告各几；spotbugs 零钉；
   测试计数记录）。verify 日志不落 target/（clean 会 wipe，批 6 教训）。

## 9. 闭环记录（2026-10-28）

**实施细化（超出设计稿的定形）**：

1. **custom 消息的引擎通道**：`PiLaneEngine.run` 增 customs 重载——custom 投影经
   initialMessages 进 loop（sink 按 customOrigins 身份在 message_end 落
   `Entry.CustomMessage`），转录序＝pi（user 先于 customs）；nextTurn 注入点同缝。
2. **triggerTurn 起轮＝续跑**：条目先落（idle 臂自带工作副本重建）⇒
   `harness.continueRun`（尾部 user 投影 ⇒ runContinuation，pi `agent.continue`
   同门）——`SessionRunner.drive` 抽 `RunStep` 共享体，新增 `driveContinuation`。
3. **processPrompt 的 settled 延迟门**（pi :1968-1972）随 T3 一并落：deferral 的
   SessionResult 经桥接 futures＋队列泵 stream；disposition 新值 `"deferred"`
   （窄差 D158）。
4. **保留键解析**：pi 18 条保留动作中 java 已知 11 条经 KeybindingsManager 解析；
   其余 7 条（tui.* 四条＋app.suspend＋app.model.cycleBackward）硬编码等价键
   （enter/esc/ctrl+c/ctrl+u/ctrl+z/shift+ctrl+p|alt+p）。
5. **裁决⑦核实**：`SessionState.validateTarget` 已抛 `SessionError`——文案修真为
   pi 逐字 `Entry <id> not found`（三操作点共享同一校验）。
6. **裁决⑩异步面**：triggerTurn 臂 `Thread.startVirtualThread` 起轮，失败经
   `bus.emitError`（`"<runtime>"`/`"send_message"`，pi :3389-3396 逐字）。
7. **并发方言**（spotbugs 一钉撞出）：`emittingAgentSettled` ⇒ volatile；三缓冲
   （nextTurn/pendingCustom/deferredSettled）读写全进 synchronized 临界区——
   pi 单线程数组在 java 多线程下的对位纪律。
8. **存量断言修形 2 处**：`DefaultExtensionContextSendMessageTest` 的 UOE 臂语义
   移到回落路径（无宿主会话时 triggerTurn 仍 UOE）；`MessageBubbleTest` 的
   `Entry.Custom` 投影断言改钉「无渲染器 ⇒ 不渲」（pi :3848-3850）。

**窄差落号**：D150（transformer context 两臂恒值）／D151（user 型无消费点）／
D152（renderer 文本级，D92 同族）／D153（unregisterProvider 无内置恢复面）／
D154（exec 终止信号语义）／D155（EventBus 异常走 JUL）／D156（快捷键 ctx 共享）／
D157（custom 的 message_start/end 以 entry_appended 载）／D158（disposition
"deferred" 新值）／D159（pendingMessageCount 计 custom 项）。D12/D21 注记更新
（各落半）；D117 部分修真（门落地，SDK 面仍无）。

**行为变化（changelog 级）**：`sendMessage(triggerTurn=true)` 从 UOE 变起轮；
流式 custom 默认臂＝steer；/hotkeys 增 Extensions 段；无渲染器 custom 条目不渲；
processPrompt 在 agent_settled 发射窗内延迟执行；扩展 sendUserMessage 默认不展开
模板/命令。

**排障钉**：① 无 `-am` 拾旧 jar 再撞（NoClassDefFoundError QueuedItem$Custom——
批 22/25 同族，本批三撞终记牢）；② verify 管道吞退出码再撞（grep 过滤后 `$?` 是
grep 的——日志落盘＋单独 echo 才是真）；③ 两个 mvn 并发跑同模块互踩 target
（收口只跑单实例）；④ spotbugs 跨线程可见性钉（⑦）。

**测试**：新增/改写 6 件 43 用例——CustomQueuedMessageTest 3（agent-core）＋
AgentSessionSendCustomMessageTest 6（五臂＋settled 延迟门）＋
DefaultExtensionContextActionsTest 11（T3–T8 ctx 面）＋ExtensionChannelBusTest 5＋
ExtensionShortcutsTest 6（三规则＋保留键＋KeyIdParser）＋CustomRendererProjectionTest
6＋MarkdownTransformersTest 6（链语义＋两臂缝）。

**账面（实算）**：模块 416.0→**438.0/485 ≈ 90.3%**（+22.0，全在 E5 域）；E5 域
29.0→**51.0/58 ≈ 87.9%**（对齐 17→31/35）；map/05 132.5→**133.5/244 ≈ 54.7%**
（R6 +1.0）；全仓 1,132.0→**1,155.0/1379 ≈ 83.8%**。E5-26/27 挂账收编 B162；
E5-31 维持存疑；E5-33 维持 F 类。

**verify 史**：定向（三模块 test＋checkstyle）全绿 → 全量 clean verify #1 撞
spotbugs 一钉（`emittingAgentSettled` 跨线程；修真＝volatile＋三缓冲临界区）→
全量 clean verify #2 收口：exit 0 BUILD SUCCESS（**5,327 测 0 失败**，checkstyle
0 违规〔14 模块〕，spotbugs 零钉）。

**回填三处**：docs/04（§4 批 26 段＋B162 注记＋E1/E6 行数实测＋§7 下一包号
docs/38）＋docs/05（§1 总表/§2.4 批 26 条目/§2.5）＋docs/06（§1 四读数＋E5 行＋
§2 批 26 行＋§3.3 变量 3 落地注记）＋map/04（E5 域 14 行＋汇总＋域表＋D12/D21
注记＋扩展系统单独口径×2）＋map/05（R6 行＋合计＋批 26 注记）。
