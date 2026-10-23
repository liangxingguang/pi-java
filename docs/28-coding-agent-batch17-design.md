# coding-agent 批 17 设计稿：E5 扩展 API 面第二批——扩展 UI 注入层（`ctx.ui` TUI 实现＋widget/footer/header＋`ui_prompt` 事件）

【背景摘要】本批是 coding-agent 对齐批次总账（docs/20）§3 序 16，对应模块对齐地图
（docs/map/04）E5 域（扩展 API 面）第二批。范围＝序 16 原定的 `ctx.ui.custom`
（B221③／B214 的挂载面）＋D′ 注入层（widget/footer/header）＋随收的 D 域 #26/#27
（`ui_prompt_start`/`ui_prompt_end` 事件），联动 map/05 的 S22（扩展对话框四件套）与
S23（扩展 widget/footer/header/状态槽）。E5-32（RPC 扩展 UI 九方法通道）的 java 侧
生产者经本批接口全面化顺带接通，一并翻行。B214 只交付**挂载面**（`ctx.ui.custom`
可用），`/mcp` 管理器视图载荷依赖 MCP 扩展工厂（map/07 G 组全缺），不随本批。

术语（首现释义）：

- **扩展 UI 注入层**：pi 把扩展提供的组件/文本挂进交互界面固定槽位的机制——
  编辑器上下各一条 widget 槽、footer 槽、header 槽、状态行扩展段。
- **对话四件套**：`ctx.ui` 的四个阻塞式交互方法 `select/confirm/input/editor`
  （选择器、确认框、单行输入、多行编辑器）。pi 里它们换进编辑器槽位显示。
- **`ctx.ui.custom`**：扩展把**自有组件**带键盘焦点挂上屏的通用挂载面；
  pi 的 `/mcp` 管理器视图（B214）就是它的一个载荷。
- **ui_prompt 事件对**：pi 在上述五个阻塞方法（四件套＋custom）被调用时发射的
  `ui_prompt_start`/`ui_prompt_end` 扩展事件；带深度计数，嵌套调用只发最外层。
- **noOpUIContext**：pi 无 UI 环境（print 模式等）下 `ctx.ui` 的默认实现，
  全部方法立即返回取消值/空操作。
- **ScreenOverlay**：pi-java TUI 既有的模态层接口（`onKeyEvent/isDone/apply/render`），
  模型选择器、树选择器、/bug 流程都走它。本批扩展对话框也落在这一层。
- **九方法通道**：RPC 模式下扩展 UI 的线格式（`extension_ui_request`/`response`），
  九个 method：select/confirm/input/editor/notify/setStatus/setWidget/setTitle/
  set_editor_text。
- **挂载面 vs 载荷**：挂载面＝`ctx.ui.custom` 这个通用能力；载荷＝拿它显示的具体
  组件（如 `/mcp` 管理器视图 McpManagerView）。本批只做挂载面。
- **B221③**：台账 docs/04 工单 B221 缺口清单第③条＝`ctx.ui.custom`（即 B214）。
- **D′ 注入层**：docs/20 §3 序 16 对 widget/footer/header 三件注入槽位的简称。

---

## §0 基线与预期翻动

基线（批 16 闭环实算，2026-10-23）：模块 350.5/485 ≈ 72.3%；E5 域 22.0/58 ≈ 37.9%；
D 域 77.0/83 ≈ 92.8%；map/05 tui/web 118.5/245 ≈ 48.4%；全仓 1,030.0/1379 ≈ 74.7%。

| 行 | 前态 | 后态 | Δ（加权） | 手段 |
|---|---|---|---:|---|
| map/04 **E5-31** 扩展 UI 注入（TUI，w2） | 缺失 | 存疑 | +1.0 | T1/T4/T5/T6/T7（主体落，15 个成员余面挂账，见 §4） |
| **E5-32** 扩展 UI 通道（RPC 九方法，w1） | 存疑 | 对齐 | +0.5 | T3（typed 实现补齐＋晚绑定修真） |
| **D#26** `ui_prompt_start`（w1） | 缺失 | 对齐 | +1.0 | T2 |
| **D#27** `ui_prompt_end`（w1） | 缺失 | 对齐 | +1.0 | T2 |
| map/05 **S22** 扩展对话框四件套（w1，tui/web 图） | 缺失 | 对齐 | +1.0 | T4 |
| map/05 **S23** 扩展 widget/footer/header（w1，tui/web 图） | 缺失 | 对齐 | +1.0 | T6 |

**预期账面**：模块 350.5→**354.0/485 ≈ 73.0%**（+3.5）；E5 域 22.0→**23.5/58 ≈ 40.5%**；
D 域 77.0→**79.0/83 ≈ 95.2%**（余缺失 2 行＝#1/#11）；map/05 118.5→**120.5/245 ≈ 49.2%**；
全仓 1,030.0→**1,035.5/1379 ≈ 75.1%**（+5.5）。

> 欠账估算说明：docs/20 序 16 估「~22+15」，逐行实算为 +5.5——E5-31 的余面在地图里
> 不是独立行（全压在这一行 w2 内），S22/S23 各仅 w1。先例：批 1 起逐行实算为权威。

**工单**：B221 收编 ③（余 ① MCP 六事件／⑥ RPC `get_commands` 扩展臂）；
**B214 不销号、改注**（挂载面批 17 落地，载荷 `/mcp` 管理器视图随 MCP 扩展工厂批，
map/07 G 组 73–84/86–87 全缺是硬前提）。

---

## §1 pi 侧事实（取证，锚点 `ea448f454`，只走 `git show`）

> 文件路径相对 `packages/coding-agent/src/`，标注 `tui/` 前缀的相对 `packages/tui/src/`。

### 1.1 `ExtensionUIContext` 全面与 noOpUIContext

pi 的 `ctx.ui` 类型＝`ExtensionUIContext`（`core/extensions/types.ts:149-323`），
**28 个成员**。签名面（逐字，注释节略）：

```ts
export interface ExtensionUIContext {
	select(title: string, options: string[], opts?: ExtensionUIDialogOptions): Promise<string | undefined>;
	confirm(title: string, message: string, opts?: ExtensionUIDialogOptions): Promise<boolean>;
	input(title: string, placeholder?: string, opts?: ExtensionUIDialogOptions): Promise<string | undefined>;
	notify(message: string, type?: "info" | "warning" | "error"): void;
	onTerminalInput(handler: TerminalInputHandler): () => void;
	setStatus(key: string, text: string | undefined): void;
	setWorkingMessage(message?: string): void;
	setWorkingVisible(visible: boolean): void;
	setWorkingIndicator(options?: WorkingIndicatorOptions): void;
	setHiddenThinkingLabel(label?: string): void;
	setWidget(key, content: string[] | factory | undefined, options?: ExtensionWidgetOptions): void;
	setFooter(factory | undefined): void;
	setHeader(factory | undefined): void;
	setTitle(title: string): void;
	custom<T>(factory, options?: { overlay?: boolean; overlayOptions?: …; onHandle?: … }): Promise<T>;
	pasteToEditor(text: string): void;
	setEditorText(text: string): void;
	getEditorText(): string;
	editor(title: string, prefill?: string): Promise<string | undefined>;
	addAutocompleteProvider(factory: AutocompleteProviderFactory): void;
	setEditorComponent(factory: EditorFactory | undefined): void;
	getEditorComponent(): EditorFactory | undefined;
	readonly theme: Theme;
	getAllThemes(): { name: string; path: string | undefined }[];
	getTheme(name: string): Theme | undefined;
	setTheme(theme: string | Theme): { success: boolean; error?: string };
	getToolsExpanded(): boolean;
	setToolsExpanded(expanded: boolean): void;
}
```

选项类型（types.ts:114-140 逐字）：

```ts
export interface ExtensionUIDialogOptions {
	signal?: AbortSignal;   // 编程式关闭对话框
	timeout?: number;       // 毫秒；自动取消并带倒计时显示
}
export type WidgetPlacement = "aboveEditor" | "belowEditor";
export interface ExtensionWidgetOptions {
	placement?: WidgetPlacement;   // 默认 "aboveEditor"
}
```

无 UI 环境的默认实现（`core/extensions/runner.ts:324-352` 逐字）：

```ts
const noOpUIContext: ExtensionUIContext = {
	select: async () => undefined,
	confirm: async () => false,
	input: async () => undefined,
	notify: () => {},
	onTerminalInput: () => () => {},
	setStatus: () => {},
	setWorkingMessage: () => {},
	setWorkingVisible: () => {},
	setWorkingIndicator: () => {},
	setHiddenThinkingLabel: () => {},
	setWidget: () => {},
	setFooter: () => {},
	setHeader: () => {},
	setTitle: () => {},
	custom: async () => undefined as never,
	pasteToEditor: () => {},
	setEditorText: () => {},
	getEditorText: () => "",
	editor: async () => undefined,
	addAutocompleteProvider: () => {},
	setEditorComponent: () => {},
	getEditorComponent: () => undefined,
	get theme() { return theme; },
	getAllThemes: () => [],
	getTheme: () => undefined,
	setTheme: (_theme) => ({ success: false, error: "UI not available" }),
	getToolsExpanded: () => false,
	setToolsExpanded: () => {},
};
```

**晚绑定语义**（runner.ts:876-884）：`createContext()` 里 `ui` 是 getter，
调用时才解析 `runner.uiContext`——「Context values are resolved at call time, so
changes via bindCore/bindUI are reflected」。`hasUI()`＝`uiContext !== noOpUIContext`
（恒等比较，runner.ts:622）。

### 1.2 `ui_prompt` 事件与包装链（D#26/#27）

事件载荷（types.ts:1012-1030 逐字）：

```ts
export type UIPromptKind = "select" | "confirm" | "input" | "editor" | "custom";
export interface UIPromptStartEvent {
	type: "ui_prompt_start";
	reason: "ui_prompt";
	kind: UIPromptKind;
	title?: string;
}
export interface UIPromptEndEvent {
	type: "ui_prompt_end";
	reason: "ui_prompt";
	kind: UIPromptKind;
	title?: string;
}
```

包装与发射（runner.ts:565-622 逐字）：

```ts
setUIContext(uiContext?: ExtensionUIContext, mode: ExtensionMode = "print"): void {
	this.uiContext = uiContext ? this.wrapUIPromptContext(uiContext) : noOpUIContext;
	this.mode = mode;
}

private wrapUIPromptContext(ui: ExtensionUIContext): ExtensionUIContext {
	return {
		...ui,
		select: (title, options, opts) => this.withUIPrompt("select", title, () => ui.select(title, options, opts)),
		confirm: (title, message, opts) => this.withUIPrompt("confirm", title, () => ui.confirm(title, message, opts)),
		input: (title, placeholder, opts) =>
			this.withUIPrompt("input", title, () => ui.input(title, placeholder, opts)),
		editor: (title, prefill) => this.withUIPrompt("editor", title, () => ui.editor(title, prefill)),
		custom: (factory, options) => this.withUIPrompt("custom", undefined, () => ui.custom(factory, options)),
	};
}

private withUIPrompt<T>(kind: UIPromptKind, title: string | undefined, run: () => Promise<T>): Promise<T> {
	const outerPrompt = this.uiPromptDepth++ === 0;
	if (outerPrompt) {
		this.activeUIPrompt = { kind, title };
		this.emitUIPromptEvent({ type: "ui_prompt_start", reason: "ui_prompt", kind, ...(title ? { title } : {}) });
	}

	const finish = () => {
		if (--this.uiPromptDepth > 0) return;
		this.uiPromptDepth = 0;
		const prompt = this.activeUIPrompt ?? { kind, title };
		this.activeUIPrompt = undefined;
		this.emitUIPromptEvent({
			type: "ui_prompt_end", reason: "ui_prompt",
			kind: prompt.kind, ...(prompt.title ? { title: prompt.title } : {}),
		});
	};

	try {
		return run().finally(finish);
	} catch (err) {
		finish();
		throw err;
	}
}

private emitUIPromptEvent(event): void {
	queueMicrotask(() => { void this.emit(event); });
}
```

要点：

1. 只包**五个阻塞方法**（select/confirm/input/editor/custom）；notify/setStatus/
   setWidget 等不包。
2. **深度计数**：`uiPromptDepth` 是 runner 字段；嵌套（如 custom 里再 select）只有
   最外层发 start；深度归零才发 end，且 end 的 kind/title 取**最外层记录值**
   （`activeUIPrompt`），丢失时回落当前调用。
3. **异步发射**：`queueMicrotask`——事件不阻塞 UI 调用本身。
4. **异常安全**：`run()` 同步抛也先 `finish()` 再重抛（深度恒复位）。

### 1.3 对话四件套的 TUI 实现（S22）

装配（`modes/interactive/interactive-mode.ts:2635-2659` 逐字片段）：

```ts
private createExtensionUIContext(): ExtensionUIContext {
	return {
		select: (title, options, opts) => this.showExtensionSelector(title, options, opts),
		confirm: (title, message, opts) => this.showExtensionConfirm(title, message, opts),
		input: (title, placeholder, opts) => this.showExtensionInput(title, placeholder, opts),
		notify: (message, type) => this.showExtensionNotify(message, type),
		…
		editor: (title, prefill) => this.showExtensionEditor(title, prefill),
		…
	};
}
```

**selector**（:2690-2743 逐字，核心序）：

```ts
private showExtensionSelector(title, options, opts?,
	blocked: BlockedStatus = { kind: "question", message: title }): Promise<string | undefined> {
	return new Promise((resolve) => {
		if (opts?.signal?.aborted) { resolve(undefined); return; }
		const onAbort = () => { this.hideExtensionSelector(); resolve(undefined); };
		opts?.signal?.addEventListener("abort", onAbort, { once: true });

		this.extensionSelector = new ExtensionSelectorComponent(
			title, options,
			(option) => { opts?.signal?.removeEventListener("abort", onAbort); this.hideExtensionSelector(); resolve(option); },
			() =>     { opts?.signal?.removeEventListener("abort", onAbort); this.hideExtensionSelector(); resolve(undefined); },
			{ tui: this.ui, timeout: opts?.timeout, onToggleToolsExpanded: () => this.toggleToolOutputExpansion() },
		);
		this.disposeActiveSelector();          // 先处置其它活动选择器
		this.editorContainer.clear();          // 换进编辑器槽位
		this.editorContainer.addChild(this.extensionSelector);
		this.ui.setFocus(this.extensionSelector);
		this.programStatus.setBlocked("extension-dialog", blocked);
		this.ui.requestRender();
	});
}
private hideExtensionSelector(): void {
	this.extensionSelector?.dispose();
	this.editorContainer.clear();
	this.editorContainer.addChild(this.editor);   // 编辑器回槽
	this.extensionSelector = undefined;
	this.programStatus.setBlocked("extension-dialog", undefined);
	this.ui.setFocus(this.editor);
	this.ui.requestRender();
}
```

**confirm**（:2749-2758 逐字）＝selector 的特化：

```ts
private async showExtensionConfirm(title, message, opts?): Promise<boolean> {
	const result = await this.showExtensionSelector(`${title}\n${message}`, ["Yes", "No"], opts,
		{ kind: "permission", message: title });
	return result === "Yes";
}
```

**input**（:2766-2822）与 selector 同构：`ExtensionInputComponent` 换进编辑器槽位、
`signal.aborted` 预检＋abort 监听、submit→值 / cancel→`undefined`、
`programStatus.setBlocked("extension-dialog", { kind: "question", message: title })`。

**editor**（:2831-2865）：`ExtensionEditorComponent`（构造多带
`this.settingsManager.getExternalEditorCommand()`）；同样换槽＋focus＋blocked。

**notify**（:2954-2962 逐字）：

```ts
private showExtensionNotify(message: string, type?: "info" | "warning" | "error"): void {
	if (type === "error") { this.showError(message); }
	else if (type === "warning") { this.showWarning(message); }
	else { this.showStatus(message); }
}
```

**selector 组件**（`modes/interactive/components/extension-selector.ts`，117 行）行为
要点（逐字语义，java 对位依据）：

- 结构：DynamicBorder＋Spacer＋标题（accent 加粗）＋可选 description＋Spacer＋
  列表容器＋Spacer＋hint 行＋Spacer＋DynamicBorder。
- hint 行逐字：`rawKeyHint("↑↓","navigate") + "  " + keyHint("tui.select.confirm","select")
  + "  " + keyHint("tui.select.cancel","cancel")`。
- 键位：`app.tools.expand`→切换工具展开；`tui.select.up` **或 `k`**→上移（夹 0）；
  `tui.select.down` **或 `j`**→下移（夹 length-1）；`tui.select.confirm` **或 `\n`**
  →提交当前项（空表不提交）；`tui.select.cancel`→取消。
- 渲染：选中项 `→ ` 前缀＋accent，未选 `  ` 前缀＋text 色；无滚动窗（全量绘制）。
- timeout＞0 时挂 CountdownTimer：标题变 `"{baseTitle} ({Ns})"`，到期＝取消。

**input 组件**（extension-input.ts，94 行）：标题 accent **不加粗**（与 selector 差
一处 `theme.bold`）；hint 行 `{confirm submit}  {cancel cancel}`；`tui.select.confirm`
或 `\n`→提交输入框当前值；`tui.select.cancel`→取消；其余键转发给内部 Input。
Focusable 的 focused 透传到内部 Input（IME 光标）。

**editor 组件**（extension-editor.ts，141 行）：内嵌完整 Editor（EditorTheme、
EditorOptions 透传）；prefill 预填；`editor.onSubmit`→提交；`tui.select.cancel`→取消；
`app.editor.external`（ctrl+g）→`editInExternalEditor` 往返（tui.stop→外部编辑器→
complete 则回写→finally tui.start＋requestRender(true)）；外部编辑器命令回落链逐字：
`settings ?? VISUAL ?? EDITOR ?? (win32 ? "notepad" : "nano")`；hint 行
`{confirm submit}  {tui.input.newLine newline}  {cancel cancel}  {app.editor.external external editor}`。

**CountdownTimer**（countdown-timer.ts 逐字）：

```ts
constructor(timeoutMs, tui, onTick, onExpire) {
	this.remainingSeconds = Math.ceil(timeoutMs / 1000);
	this.onTick(this.remainingSeconds);              // 立即 tick 一次
	this.intervalId = setInterval(() => {
		this.remainingSeconds--;
		this.onTick(this.remainingSeconds);
		this.tui?.requestRender();
		if (this.remainingSeconds <= 0) { this.dispose(); this.onExpire(); }
	}, 1000);
}
```

**DynamicBorder**（dynamic-border.ts）：单行 `"─".repeat(Math.max(1, width))`，
默认 `theme.fg("border", …)` 着色。

### 1.4 `custom` 挂载面与 overlay（B221③／B214 之车）

`showExtensionCustom`（interactive-mode.ts:2966-3043 逐字，核心序）：

```ts
private async showExtensionCustom<T>(factory, options?): Promise<T> {
	const savedText = this.editor.getText();          // 非 overlay 臂要保存编辑器文本
	const isOverlay = options?.overlay ?? false;
	const restoreEditor = () => {
		this.editorContainer.clear();
		this.editorContainer.addChild(this.editor);
		this.editor.setText(savedText);
		this.ui.setFocus(this.editor);
		this.ui.requestRender();
	};
	return new Promise((resolve, reject) => {
		let component; let closed = false;
		const close = (result: T) => {
			if (closed) return;
			closed = true;
			if (isOverlay) this.ui.hideOverlay();
			else restoreEditor();
			resolve(result);
			try { component?.dispose?.(); } catch { /* ignore dispose errors */ }
		};
		Promise.resolve(factory(this.ui, theme, this.keybindings, close))
			.then((c) => {
				if (closed) return;
				component = c;
				if (isOverlay) {
					const resolveOptions = () => options?.overlayOptions
						? (typeof options.overlayOptions === "function" ? options.overlayOptions() : options.overlayOptions)
						: (component.width ? { width: component.width } : undefined);
					const handle = this.ui.showOverlay(component, resolveOptions());
					options?.onHandle?.(handle);
				} else {
					this.disposeActiveSelector();
					this.editorContainer.clear();
					this.editorContainer.addChild(component);
					this.ui.setFocus(component);
					this.ui.requestRender();
				}
			})
			.catch((err) => { if (closed) return; if (!isOverlay) restoreEditor(); reject(err); });
	});
}
```

要点：

1. factory 收 `(tui, theme, keybindings, done)`；`done(result)` 是**唯一**正常出口
   （幂等：`closed` 闸）。
2. factory 可返回 Promise（异步组件）；resolve 前若已 closed 则直接丢弃。
3. 非 overlay 臂：保存编辑器文本→换槽→focus；close 时恢复文本＋焦点。
4. overlay 臂：`showOverlay(component, options)` 返回 `OverlayHandle`
   （tui/tui.ts:307-330：`hide/setHidden/isHidden/focus/unfocus/isFocused/getBounds`），
   `OverlayOptions`（:254-304：width/minWidth/maxHeight/anchor/offset/row/col/margin/
   visible/nonCapturing）。java 无 overlay 栈（§2），本批裁窄（裁决④）。
5. factory 抛/拒：恢复编辑器＋reject。

### 1.5 注入层：widget/footer/header/status（S23）

**setWidget**（interactive-mode.ts:2398-2438 逐字）：

```ts
private setExtensionWidget(key, content, options?): void {
	const placement = options?.placement ?? "aboveEditor";
	const removeExisting = (map) => {
		const existing = map.get(key);
		if (existing?.dispose) existing.dispose();
		map.delete(key);
	};
	removeExisting(this.extensionWidgetsAbove);      // 同 key 两边都先清
	removeExisting(this.extensionWidgetsBelow);
	if (content === undefined) { this.renderWidgets(); return; }
	let component;
	if (Array.isArray(content)) {
		const container = new Container();
		for (const line of content.slice(0, InteractiveMode.MAX_WIDGET_LINES))
			container.addChild(new Text(line, 1, 0));
		if (content.length > InteractiveMode.MAX_WIDGET_LINES)
			container.addChild(new ThemedText(() => theme.fg("muted", "... (widget truncated)"), 1, 0));
		component = container;
	} else {
		component = content(this.ui, theme);            // 工厂臂
	}
	const targetMap = placement === "belowEditor" ? this.extensionWidgetsBelow : this.extensionWidgetsAbove;
	targetMap.set(key, component);
	this.renderWidgets();
}
```

`MAX_WIDGET_LINES = 10`（:2486）。**renderWidgets**（:2491-2516 逐字）：

```ts
private renderWidgets(): void {
	if (!this.widgetContainerAbove || !this.widgetContainerBelow) return;
	this.renderWidgetContainer(this.widgetContainerAbove, this.extensionWidgetsAbove, true, true);
	this.renderWidgetContainer(this.widgetContainerBelow, this.extensionWidgetsBelow, false, false);
	this.ui.requestRender();
}
private renderWidgetContainer(container, widgets, spacerWhenEmpty, leadingSpacer): void {
	container.clear();
	if (widgets.size === 0) {
		if (spacerWhenEmpty) container.addChild(new Spacer(1));
		return;
	}
	if (leadingSpacer) container.addChild(new Spacer(1));
	for (const component of widgets.values()) container.addChild(component);
}
```

布局槽位（:972-988）：component tree ＝ documentContainer（转录）、
pendingMessagesContainer、statusContainer、**widgetContainerAbove**、editorContainer、
**widgetContainerBelow**、footerContainer。即：上空槽位空时保留 Spacer(1)、
有内容时前导 Spacer(1)；下空槽位空时不加 Spacer。

**setFooter**（:2524-2549 逐字）：dispose 旧 custom→footerContainer.clear()→
有工厂则 `customFooter = factory(tui, theme, footerDataProvider)` 挂入，无则回挂内置
footer→requestRender。

**setHeader**（:2551-2595 逐字）：`builtInHeader` 未初始化直接 return；dispose 旧
custom→在 headerContainer 里**原位替换**（indexOf 当前 header，找不到则 unshift 到顶）
→isExpandable 同步 toolOutputExpanded→requestRender。

**setStatus / footer 扩展状态段**（:2300-2302＋`core/footer-data-provider.ts`）：
`setStatus(key, text)`→`footerDataProvider.setExtensionStatus`（Map：undefined＝删，
否则覆盖）。footer 渲染段（components/footer.ts:289-298 逐字）：

```ts
const extensionStatuses = this.footerData.getExtensionStatuses();
if (extensionStatuses.size > 0) {
	const sortedStatuses = Array.from(extensionStatuses.entries())
		.sort(([a], [b]) => a.localeCompare(b))          // 按 key 字典序
		.map(([, text]) => sanitizeStatusText(text));
	const statusLine = sortedStatuses.join(" ");
	lines.push(truncateToWidth(statusLine, width, theme.fg("dim", "...")));
}
```

`sanitizeStatusText`（footer.ts:14-20 逐字）：`[\r\n\t]`→空格→多空格折叠→trim。

### 1.6 `resetExtensionUI` 与调用点

逐字（interactive-mode.ts:2452-2483，本批相关段）：

```ts
private resetExtensionUI(): void {
	if (this.extensionSelector) this.hideExtensionSelector();
	if (this.extensionInput) this.hideExtensionInput();
	if (this.extensionEditor) this.hideExtensionEditor();
	this.ui.hideOverlay();
	this.clearExtensionTerminalInputListeners();
	this.setExtensionFooter(undefined);
	this.setExtensionHeader(undefined);
	this.clearExtensionWidgets();
	this.footerDataProvider.clearExtensionStatuses();
	this.footer.invalidate();
	this.autocompleteProviderWrappers = [];
	this.setCustomEditorComponent(undefined);
	…（working message/indicator、hiddenThinkingLabel 复位——本批未实现面）
}
```

两个调用点：

1. **会话失效前**（:613-615）：`runtimeHost.setBeforeSessionInvalidate(() =>
   this.resetExtensionUI())`——会话切换/替换前复位。
2. **`/reload`**（:6440）：`handleReloadCommand` 两道门（流式中/压缩中给警告）之后、
   真正 reload 之前调用。

### 1.7 RPC 模式 `ctx.ui` 与九方法通道（E5-32）

线格式（`modes/rpc/rpc-types.ts:253-297` 逐字摘要）：九种
`{type:"extension_ui_request", id, method, …}`——select（title/options/timeout?）、
confirm（title/message/timeout?）、input（title/placeholder?/timeout?）、
editor（title/prefill?）、notify（message/notifyType?）、setStatus（statusKey/
statusText）、setWidget（widgetKey/widgetLines/widgetPlacement?）、setTitle（title）、
set_editor_text（text）。应答 `extension_ui_response` 三形：`{id,value}`／
`{id,confirmed}`／`{id,cancelled:true}`。

RPC 模式实现面（`modes/rpc/rpc-mode.ts:136-260`）逐字语义：

- select/confirm/input/editor：经 `createDialogPromise`——`signal.aborted` 预检直返
  默认值；发请求后等应答；**signal abort 或本地 timeout 到点都 resolve 默认值**
  （:91-121；timeout 同时写进请求让宿主显示倒计时）；cancelled→undefined/false。
- notify/setStatus/setTitle/set_editor_text：**fire-and-forget**（随机 UUID，不等应答）。
- setWidget：**只支持字符串数组**臂；工厂臂静默忽略（注释：「Component factories
  are not supported in RPC mode」）。
- setFooter/setHeader/onTerminalInput：空操作。
- custom：`return undefined as never`（不支持）。
- pasteToEditor→回落 setEditorText；getEditorText→`""`（同步方法等不了应答）。
- setWorkingMessage/Visible/Indicator、setHiddenThinkingLabel：空操作（需 TUI 访问）。

### 1.8 键位默认与 hint

`tui/` keybindings.ts:143-158 逐字默认：

```ts
"tui.input.newLine": { defaultKeys: ["shift+enter", "ctrl+j"], … },
"tui.select.up":     { defaultKeys: "up", … },
"tui.select.down":   { defaultKeys: "down", … },
"tui.select.confirm":{ defaultKeys: "enter", … },
"tui.select.cancel": { defaultKeys: ["escape", "ctrl+c"], … },
```

hint 格式（components/keybinding-hints.ts）：`keyHint(id, desc)`＝
`dim(键文本) + muted(" " + desc)`；`rawKeyHint("↑↓", …)` 同形手写键文本。

### 1.9 B214 载荷边界（证明挂载面够用，载荷不随本批）

`extensions/mcp/ui.ts:244-258`（逐字）——`/mcp` 管理器就是 `ctx.ui.custom` 的
一个普通载荷：

```ts
export async function showMcpManager(ctx: ExtensionCommandContext,
	manage: (ui: McpUi) => Promise<void>): Promise<void> {
	await ctx.ui.custom<void>((tui, theme, keybindings, done) => {
		const view = new McpManagerView(tui, theme, keybindings);
		void manage(view).then(
			() => done(),
			(error: unknown) => {
				ctx.ui.notify(error instanceof Error ? error.message : String(error), "error");
				done();
			},
		);
		return view;
	});
}
```

`McpUi` 三面（menu 订阅重建／status／redirectUrl）与 `manage` 编排
（extensions/mcp/index.ts:878-1010，九处 `ui.menu/status/redirectUrl` 调用）依赖
MCP 会话内运行时（服务器列表、subscribe、signIn、exposure、enable/disable）——
map/07 G 组 73–84/86–87 全缺，载荷随该批。本批交付的 `custom` 挂载面（组件工厂＋
done 回调＋焦点＋dispose）正是它需要的全部。

---

## §2 java 侧现状（取证 2026-10-24，main 5d6221ee）

**通道与装配（coding-agent）**：

- `ExtensionUI`（extension/ExtensionUI.java:10-19）：单方法
  `request(RpcExtensionUIRequest)`＋`noop()`（立即 confirm(id,false)）。
  **没有 typed 方法面**——select/confirm/input/… 全部不存在。
- `ExtensionContext.ui()`（ExtensionContext.java:38-40）：默认返 noop；
  `DefaultExtensionContext` 构造注入 `final ExtensionUI ui`（:57-63，null⇒noop），
  **烘死**——pi 的晚绑定 getter（§1.1）无对位。
- `AgentSession`：`private ExtensionUI extensionUI = ExtensionUI.noop()`（:109）；
  setter（:1737）；`loadExtensions`（:2398）把字段烘进 context。初始 create 传
  noop（:364-365）；**RPC 在 `RpcDispatcher` 构造器 :68
  `session.extensionUI(this::extensionUiRequest)` 覆盖字段，但 context 里烘的
  还是 noop——生产者接了却到不了扩展**（E5-32 存疑的实质）。
- `RpcDispatcher.extensionUiRequest`（:381-402）：写请求→`uiQueue`（:55）
  阻塞 poll（5 分钟超时）；`handleLine`（:78-86）路由 extension_ui_response。
  九方法线格式类（RpcExtensionUIRequest/Response）已就位。
- 事件面：`ExtensionEventSpec` 常量集中声明（event/ExtensionEventSpec.java）；
  发射范式＝`SessionEventFirer`（:88-99：`hasHandlers` 快走→`bus.snapshot`→
  逐 handler `h.handle(event,ctx)`→异常 `bus.emitError`）。**无 ui_prompt 两 spec**。
- `com.pijava.ai.AbortSignal`（ai 模块）：`isAborted()/abort()/onAbort(Runnable)`
  ——对位 pi `AbortSignal` 的三件已够。

**TUI（pi-java-tui）**：

- 布局：`PiTuiApp` 全屏 root＝column(chatScreen, [overlay], statusBar)（:318-333）；
  inline 模式底部位＝column(completer, editor, statusBar)（ChatScreen.java:305-314）。
  **无 editorContainer 换槽概念**（ChatScreen.editor 是 final :37）；模态一律
  `ScreenOverlay`（screen/ScreenOverlay.java:14-27）＋`PiTuiApp.openOverlay`（:785），
  overlay 渲染位在转录与状态栏之间——视觉上与 pi 的编辑器槽位近似。
- 键流：`PiTuiApp.onKeyEvent`（:429-431）overlay 非空时按键全给 overlay——
  「扩展组件获焦」由此天然成立，无需 TamboUI FocusManager。
- 线程：`TuiEventDispatcher`（util/TuiEventDispatcher.java:19-45）dispatch→渲染
  线程 drain；`CountdownWake`（util/CountdownWake.java）1Hz 唤醒在役。
- 键位：`KeybindingsManager`（coding-agent core）双表（全局/overlay），
  `matches(actionId, stroke)`（:196-202）；**无 tui.select.\*/tui.input.newLine**；
  `app.editor.external`=ctrl+g、app.tools.expand=ctrl+o 已有。
- footer＝`StatusBar`（component/StatusBar.java）吃 SessionSnapshot 单行；
  **无扩展状态段、无换槽**。header：**无常驻槽位**（WelcomeOverlay 是转录消息）。
- 组件：`SelectList`（带 fuzzy——与 pi 扩展选择器语义不符，**不复用**，见 T4）；
  `EditorComponent`（主编辑器，Ctrl+G 外部编辑器已接
  `ExternalEditorSupport`，PiTuiApp.java:739/771）。
- 无 program-status（map/05 K39 已登记缺失）；TamboUI Terminal 无 setTitle API。

---

## §3 任务分解

> 序按依赖：T1（接口面）→T2（事件）/T3（RPC 臂）→T4/T5/T6（TUI 实现）→T7（装配）。

### T1：`ExtensionUI` 接口全面化＋`ExtensionContext` 晚绑定（coding-agent，~200 行＋测试）

**pi 对位**：§1.1（types.ts:149-323＋runner.ts:324-352 noOp＋:876-884 晚绑定）。

**改动点**：

1. `ExtensionUI` 从单方法接口扩为**全 28 成员**接口，每个成员给 default 实现，
   语义逐字对位 noOpUIContext（§1.1）：select→null、confirm→false、
   input/editor/custom→null、notify/setStatus/setWidget/setFooter/setHeader/setTitle/
   pasteToEditor/setEditorText/setWorkingMessage/setWorkingVisible/setWorkingIndicator/
   setHiddenThinkingLabel/addAutocompleteProvider/setEditorComponent→空操作、
   onTerminalInput→返回空退订、getEditorText→""、getEditorComponent→null、
   getAllThemes→空表、getTheme→null、setTheme→失败（错误文案逐字
   `"UI not available"`）、getToolsExpanded→false。
   原 `request(RpcExtensionUIRequest)` 保留为 RPC 臂的内部通道（不再是主接口面）。
2. 新类型（extension 包，record/class）：
   - `ExtensionUiDialogOptions(@Nullable AbortSignal signal, @Nullable Long timeoutMillis)`
     ——pi ExtensionUIDialogOptions（§1.1）对位。
   - `ExtensionWidgetOptions(@Nullable String placement)`＋常量
     `ABOVE_EDITOR/BELOW_EDITOR`（pi WidgetPlacement 对位，默认 aboveEditor）。
   - `NotifyType` 枚举（INFO/WARNING/ERROR，`@JsonValue` 小写）。
3. `ExtensionComponent` SPI（文本级，coding-agent 定义、不依赖 TamboUI——
   同批 16 裁决⑦的文本级先例）：

   ```java
   /** 扩展自有组件（pi Component 的文本级对位）。 */
   public interface ExtensionComponent {
       /** 按给定宽度渲染为终端行（pi render(width): string[]）。 */
       List<String> render(int width);
       /** 处理一次按键（pi handleInput(data)）；KeyStroke 为中性键表示。 */
       void handleInput(KeybindingsManager.KeyStroke key);
       /** 卸载清理（pi dispose?()）。 */
       default void dispose() { }
   }
   ```

   `custom` 的工厂签名：

   ```java
   <T> Object custom(ExtensionComponentFactory<T> factory, ExtensionCustomOptions options);
   // 实际签名：T custom(Function4<ExtensionUiHostEnv, PiThemeAccess, KeybindingsManager, Consumer<T>, T 组件>
   ```

   细化：`ExtensionComponentFactory<T>`＝
   `(ExtensionUiHostEnv env, KeybindingsManager keys, Consumer<T> done) ->
   ExtensionComponent`。`ExtensionUiHostEnv`（record）：终端宽/高 supplier＋
   `Runnable requestRender`＋`String colorize(String slot, String text)`（主题色槽位
   投影——pi 的 `theme.fg("accent",…)` 对位；tui 侧按当前主题解析）。
   `ExtensionCustomOptions`：`boolean overlay`（默认 false）。
4. `noop()` 改为**单例常量** `ExtensionUI.NOOP`（恒等比较需要——pi
   `uiContext !== noOpUIContext` 对位）；旧 `noop()` 工厂保留但委托常量。
5. **晚绑定修真**（E5-32 存疑的实质修复）：`DefaultExtensionContext` 的
   `final ExtensionUI ui` 改持 `Supplier<ExtensionUI>`；`ui()` 调用时现取
   （pi runner.ts:876-884 getter 逐字语义）。`loadExtensions` 从烘值改为传
   `owner::extensionUI` 引用。
6. `ExtensionContext` 增 `default String mode() { return "print"; }` 与
   `default boolean hasUi() { return false; }`（pi ExtensionContext.mode/hasUI
   对位；DefaultExtensionContext 覆盖为现读 owner 字段）。
7. `AgentSession`：`extensionUI(ExtensionUI ui, String mode)` 双参 setter
   （旧单参保留委托，mode 维持原值）；增 `extensionMode()` 现读。

### T2：`ui_prompt_start/end` 事件＋包装装饰器（coding-agent，~180 行＋测试）

**pi 对位**：§1.2（types.ts:1012-1030＋runner.ts:565-622）。

**改动点**：

1. `ExtensionEvent` 增两 record：`UiPromptStartEvent(String kind, @Nullable String title)`、
   `UiPromptEndEvent(String kind, @Nullable String title)`（reason 恒 "ui_prompt"，不列字段
   ——同既有 spec 惯例）；kind 取值集＝pi `UIPromptKind` 五字面量。
2. `ExtensionEventSpec` 增 `UI_PROMPT_START`/`UI_PROMPT_END` 两常量（观察型 Void）。
3. `SessionEventFirer` 增 `uiPromptStart(kind,title)`/`uiPromptEnd(kind,title)`——
   沿用 :88-99 范式（hasHandlers 快走→snapshot→逐 handler→emitError）。
4. 新 `UiPromptEventExtensionUI implements ExtensionUI`（装饰器，包级私有可见性
   即可）：构造收（delegate, firer）；**仅重写五方法** select/confirm/input/editor/
   custom，逐字对位 `withUIPrompt`：
   - `depth` 计数＋`activePrompt`（kind/title）字段；
   - 最外层（depth 0→1）发 start；委托返回后 depth--，归零发 end（kind/title 取
     activePrompt，丢失回落当前）；
   - 委托**同步抛**也要先 finish 再抛（pi try/catch 对位）；
   - **异步发射**：pi `queueMicrotask` 对位＝`Thread.ofVirtual().start(() -> firer.uiPrompt…)`；
   - 其余 23 个成员全部直委托。
5. 包装点：`DefaultExtensionContext.ui()`——现取 supplier 后，非 NOOP 才包
   （pi `uiContext ? wrap : noOp` 对位），按 delegate 引用缓存包装器（同实例复用，
   depth 状态随包装器）。

### T3：`RpcExtensionUI`——RPC 臂 typed 实现（coding-agent，~150 行＋测试）

**pi 对位**：§1.7（rpc-mode.ts:136-260＋createDialogPromise :91-121＋
rpc-types.ts:253-297）。

**改动点**：

1. 新 `RpcExtensionUI implements ExtensionUI`（rpc 包）：构造收
   `Function<RpcExtensionUIRequest, RpcExtensionUIResponse> channel`（即
   `RpcDispatcher::extensionUiRequest`）＋`Consumer<RpcExtensionUIRequest> fireAndForget`
   （直写不等应答——dispatcher 侧把 extensionUiRequest 拆出「只写不等」变体）。
2. 九方法逐字对位：
   - select/confirm/input/editor：组参（title/options/message/placeholder/prefill/
     timeout 按键名逐字）→阻塞等应答→`cancelled`→null/false；`value`/`confirmed`
     取值。`signal.aborted` 预检直返默认值；**signal abort＝本地解除阻塞**返默认值
     （pi onAbort resolve 对位——AbortSignal.onAbort 注册一次性监听，
     channel 等待循环加 signal 检查）；timeout 到点本地返默认值（同时已写进请求，
     pi 同）。
   - notify/setStatus/setTitle/set_editor_text：fire-and-forget（随机 UUID）。
   - setWidget：仅 `List<String>` 臂上线；组件工厂臂**静默忽略**（pi 注释逐字）。
   - setFooter/setHeader/onTerminalInput/custom/getEditorText/pasteToEditor：
     pi RPC 臂语义逐字（空操作／null／""；pasteToEditor→setEditorText）。
3. `RpcDispatcher` 构造器改注入 `new RpcExtensionUI(this::extensionUiRequest, …)`，
   mode="rpc"（T1 的双参 setter）。

### T4：TUI 对话四件套（tui，~450 行＋测试）

**pi 对位**：§1.3。

**改动点**：

1. `KeybindingsManager` overlay 表增五 id（§1.8 默认键逐字）：
   `tui.select.up`=up、`tui.select.down`=down、`tui.select.confirm`=enter、
   `tui.select.cancel`=[escape, ctrl+c]、`tui.input.newLine`=[shift+enter, ctrl+j]。
   （进 overlay 表——批 9 C1 教训：同表会污染主屏 resolve。）
2. 新 `screen/ExtensionSelectorOverlay implements ScreenOverlay`——pi
   ExtensionSelectorComponent 逐字语义：标题 accent 加粗、DynamicBorder 上下框线、
   `→ `/两空前缀列表、hint 行（`↑↓ navigate`＋confirm/cancel 键文本）、
   k/j 别名、ctrl+o 忽略（java 无工具展开态消费点于 overlay——窄差 D97）、
   空表不提交、timeout 倒计时（标题 `"{title} ({Ns})"`，到期＝取消）。
   倒计时实现：共享 `ScheduledExecutorService`（TuiExtensionUI 持有，daemon）1Hz
   ——tick 改标题＋dispatcher 标脏；0＝取消（pi CountdownTimer 逐字，§1.3）。
3. 新 `ExtensionInputOverlay`：标题 accent 不加粗、内嵌单行输入（复用
   EditorComponent 单行模式或 TamboUIAdapter.textArea 一行——实现时取既有资产，
   键转发语义逐字：confirm/`\n`→提交值、cancel→取消、其余转发输入框）。
4. 新 `ExtensionEditorOverlay`：内嵌 `EditorComponent`（多行＋预填＋onSubmit）、
   ctrl+g→`ExternalEditorSupport.edit` 往返（回写 complete 臂；命令回落链逐字
   settings??VISUAL??EDITOR??notepad/nano——java 读环境变量同序）、hint 四段
   （submit/newline/cancel/external editor）。
5. 新 `TuiExtensionUI implements ExtensionUI`（tui 模块 app 包）：
   - 阻塞四方法（select/confirm/input/editor）：`signal.aborted` 预检→
     dispatcher 开对应 overlay→**CompletableFuture 阻塞等**（confirm ＝ selector
     特化：标题 `title\nmessage`、选项 Yes/No、`"Yes"`→true——pi 逐字）；
     abort 监听→关 overlay＋完成取消值。
   - notify：type 映射 java 三通道（showError/showWarning/showStatus——
     ChatScreen 既有消息通道，pi showExtensionNotify 逐字）。
   - **渲染线程自调用守卫**：阻塞方法在渲染线程被调→`IllegalStateException`
     （防死锁；pi 单线程 Promise 无此形态，窄差 D98）。
6. `setStatus`：进 T6 的扩展状态段（TuiExtensionUI 写 ChatScreen 注入的
   statuses map）。

### T5：`custom` 挂载面（tui＋coding-agent，~200 行＋测试）

**pi 对位**：§1.4。

**改动点**：

1. `TuiExtensionUI.custom(factory, options)`：
   - 调工厂（env/keys/done）得 `ExtensionComponent`；工厂抛⇒异常上抛（pi reject
     对位——java 阻塞语义下即抛回调用方）。
   - 包成 `ExtensionComponentOverlay implements ScreenOverlay`（适配器：
     render→组件 render(width) 行集；onKeyEvent→KeyStroke 转换后喂
     component.handleInput；isDone＝done 已调；apply＝空——结果由 done 闭包直送）。
   - `done(result)`：幂等闸→关 overlay→`component.dispose()` 包 try（pi
     「ignore dispose errors」逐字）→完成 future。
   - **非 overlay 臂的编辑器文本保存/恢复**：pi 保存恢复编辑器文本（:2968-2975）
     ——java 换槽不存在，编辑器从未离屏 ⇒ 无需保存（结构差异，非窄差，注释记）。
   - `overlay:true`：java 无 overlay 栈——本批与 false 臂同路径（模态层），
     `onHandle` 给窄化 handle（hide/setHidden/isHidden 三件；setHidden(true)＝
     临时摘 overlay、false＝回挂）；OverlayOptions 定位/尺寸、focus/unfocus/
     getBounds 不支持（窄差 D99）。
2. `ExtensionCustomOptions`/`ExtensionOverlayHandle`（hide/setHidden/isHidden）
   入 T1 的 coding-agent 类型集。

### T6：widget/footer/header 注入层（tui，~380 行＋测试）

**pi 对位**：§1.5。

**改动点**：

1. `ChatScreen` 增两 widget 区：`widgetAbove`/`widgetBelow`（
   `Map<String, WidgetEntry>`，entry＝文本行集或 ExtensionComponent）。
   `setWidget(key, content, options)` 逐字对位 pi：placement 默认 aboveEditor；
   同 key 两 map 先清（dispose 旧组件臂）；null＝清＋重排；`List<String>` 臂
   **MAX_WIDGET_LINES=10** 截断＋`... (widget truncated)`（muted，文案逐字，
   [模型不可见] 用户可见）；工厂臂调工厂得 ExtensionComponent。渲染：
   上区空＝Spacer(1)（pi spacerWhenEmpty=true 逐字）、有内容前导 Spacer(1)；
   下区空不加 Spacer（pi 逐字）。全屏 root 与 inline 底部位两路都挂
   （上区在编辑器上、下区在编辑器下——pi 槽位序对位）。
2. `setFooter`：ChatScreen 状态槽换槽——有工厂则渲染扩展 footer 组件行集，
   null 回内置 StatusBar（pi setExtensionFooter 序：dispose 旧→清→挂新/回挂→
   标脏）。footer 工厂多收一个 `FooterDataView`（record：git 分支 supplier＋
   扩展状态表只读视图——pi ReadonlyFooterDataProvider 的 java 窄化投影，
   java footer 数据本无独立 provider，窄差并入 D100）。
3. `setHeader`：ChatScreen 增 header 槽（全屏：聊天区上方；inline：底部位顶部
   ——java 无常驻内置 header（WelcomeOverlay 是转录消息），内置臂＝空，set(null)
   ＝清槽）。dispose 旧 custom 逐字。
4. **扩展状态段**：ChatScreen 持 `Map<String,String> extensionStatuses`（并发安全，
   渲染线程读）；StatusBar 行上方增一行：key 字典序排序→值 sanitize
   （`[\r\n\t]`→空格→折叠→trim，逐字）→`join(" ")`→超宽截断＋dim `...`
   （pi footer.ts:289-298 逐字）。空表不渲染该行。
5. `TuiExtensionUI` 接四方法（setWidget/setFooter/setHeader/setStatus）——
   非阻塞，dispatcher 入队（渲染线程生效，窄差 D96 族）。

### T7：装配与生命周期（coding-agent＋tui，~130 行＋测试）

**pi 对位**：§1.1 晚绑定＋§1.6 resetExtensionUI。

**改动点**：

1. `PiTuiLauncher.runInteractive`：**ChatScreen/dispatcher 提前到
   `AgentSession.create` 之前构造**，新增 `AgentSession.create(args, ExtensionUI ui,
   String mode)` 重载（ui/mode 烘进 session 字段再走 loadExtensions——pi
   bindings.uiContext 构造期传入对位，agent-session.ts:3260）；TUI 传
   `new TuiExtensionUI(host), "tui"`。host＝新建 `TuiExtensionUiHost`（持
   dispatcher/ChatScreen/overlay 开合回调句/调度器），overlay 开合回调由
   PiTuiApp 构造后回填（晚绑定 holder）。
2. `ExtensionUI` 增 `default void onSessionInvalidated() { }`；
   `AgentSession` 在两处调（对位 pi 两调用点）：
   - 会话替换路径（`closeForReplacement`/`switchTo` 的失效前点——pi
     setBeforeSessionInvalidate 对位）；
   - `reload()` 七步序**首步之前**（pi handleReloadCommand:6440 对位——
     注意 pi 的两道门（流式/压缩中）在调用方，java 的 /reload 命令层已有同门，
     本批不动）。
   `TuiExtensionUI.onSessionInvalidated()`＝resetAll（dispatcher 入队）：
   关活动对话框 overlay（resolve 取消值）→清 widget 两 map→footer/header 回内置→
   清扩展状态段（pi resetExtensionUI 本批相关段逐字，§1.6；java 无 terminal input
   listeners/autocomplete/custom editor/working message 面——随后续批扩展）。
3. fork 路径：字段随会话携带（既有 :2398 机制），`UiPromptEventExtensionUI`
   包装在 context 读取侧按 delegate 缓存 ⇒ fork 新 context 自然重包，无改。

---

## §4 不做项与重排

| 项 | 去向 | 理由 |
|---|---|---|
| `ExtensionUIContext` 余 15 成员 TUI 臂（onTerminalInput／setWorkingMessage／setWorkingVisible／setWorkingIndicator／setHiddenThinkingLabel／pasteToEditor／addAutocompleteProvider／setEditorComponent／getEditorComponent／theme／getAllThemes／getTheme／setTheme／getToolsExpanded／setToolsExpanded） | E5 后续批；theme 四面挂批 20（JSON 主题系统联动先例：批 14 重排注记） | 各成员有独立消费缝（状态指示器/思考标签/补全包装/编辑器替换/主题系统），不属于本批「注入层」主题；接口 default 已给 pi noOp 语义 |
| `/mcp` 管理器视图载荷（McpManagerView＋menu 编排九调用点） | MCP 扩展工厂批（map/07 G 组 73–84/86–87） | B214 改注；挂载面本批已验 |
| `programStatus.setBlocked("extension-dialog")` | 窄差 D100（map/05 K39 program-status 缺失已登记） | java 无 program-status 概念 |
| `OverlayOptions` 定位/尺寸全参、`OverlayHandle.focus/unfocus/getBounds` | 窄差 D99 | java 无 overlay 栈；`onHandle` 窄化三件 |
| RPC 臂 `setWidget` 组件工厂 | 不实现（pi 同缺，rpc-mode.ts:206-208 注释逐字） | 对齐基线即「无」 |
| ctrl+o 工具展开在扩展对话框内的消费 | 窄差 D97 | java overlay 语境无工具展开态 |

---

## §5 窄差登记（接续 D94；台账格式【场景/决定/风险】随闭环入 docs/04）

| 号 | 窄差 | 类 |
|---|---|---|
| D95 | 扩展对话框落 ScreenOverlay 模态层（转录与状态栏之间），非 pi 的编辑器槽位替换——视觉近似、结构不同 | 内部结构 |
| D96 | TUI 注入非阻塞方法（notify/setStatus/setWidget/setFooter/setHeader）经 dispatcher 异步入队、渲染线程生效；pi 为调用线程同步 | 时序 |
| D97 | 扩展对话框内 ctrl+o（app.tools.expand）不消费（pi 转发 toggleToolOutputExpansion） | 行为缺失 |
| D98 | 阻塞对话方法渲染线程自调用⇒IllegalStateException（pi 无此形态） | 新增守卫 |
| D99 | custom 的 overlay 臂：仅 hide/setHidden/isHidden；OverlayOptions 定位/尺寸与 focus/unfocus/getBounds 不支持 | 面收窄 |
| D100 | FooterDataView 是 pi ReadonlyFooterDataProvider 的窄化投影（git 分支＋扩展状态表；无 provider 计数）；programStatus blocked 状态无对位 | 面收窄 |
| D101 | inline 模式 header 槽渲染在底部位顶部（pi 槽位序为聊天上方；java inline 无聊天容器，welcome 本就转录化） | 布局差异 |

## §6 裁决

**裁决①：ExtensionUI 接口全面化（28 成员 default 面）**
【场景】java 的 ExtensionUI 只有 RPC 单方法 request()，扩展在 TUI 下没有任何
对话/注入能力；逐方法后补会让接口持续破碎。
【决定】一次性声明 pi ExtensionUIContext 全 28 成员，default 逐字对位 pi
noOpUIContext；本批 TUI 实现 10 个、RPC 实现 9 方法，余 15 成员后续批 override。
【风险/代价】接口面一次性变大但均为 default，零破坏（既有 noop()/RPC lambda
调用点经适配保留）。[内部，模型不可见]

**裁决②：晚绑定修真（Supplier 替代烘死字段）**
【场景】DefaultExtensionContext 构造时烘死 UI 实例，RPC 后注入的生产者到不了
扩展（E5-32 存疑实质）；pi 明确「call time 解析」。
【决定】context 持 Supplier、ui() 现取；ui_prompt 包装在读取侧按 delegate 缓存。
【风险/代价】ui() 每次调用多一次间接；fork/reload 天然跟随最新值。[内部]

**裁决③：扩展组件 SPI 文本级**
【场景】custom/setWidget/setFooter/setHeader 的工厂在 pi 收 TUI Component 对象；
coding-agent 模块不依赖 TamboUI，SPI 不能引用 Element。
【决定】ExtensionComponent＝render(width)→行集＋handleInput(KeyStroke)＋dispose()
（批 16 裁决⑦文本级先例）；工厂收 ExtensionUiHostEnv（尺寸/requestRender/colorize）
而非 pi 的 TUI/Theme 对象。
【风险/代价】扩展写不出依赖 TamboUI 特性的组件——pi-java 扩展生态为零，无存量
约束；tui 侧适配层承担转换。[内部]

**裁决④：对话框落 ScreenOverlay 模态层（不换编辑器槽位）**
【场景】pi 把对话框换进 editorContainer；java 编辑器 final 无换槽概念，既有模态
（模型/会话/树选择器、/bug）全走 ScreenOverlay。
【决定】复用 ScreenOverlay：overlay 渲染位在转录与状态栏之间，视觉与 pi 的
编辑器槽位近似；overlay 非空时按键全给 overlay（PiTuiApp:429）天然解决焦点。
【风险/代价】结构与 pi 不同（编辑器不离屏、文本不保存恢复）；登窄差 D95。
无运行时焦点系统的问题由键流路由覆盖。[内部]

**裁决⑤：session_start 期扩展对话不可用**
【场景】pi 先 ui.start() 再初始化扩展，session_start 处理器可弹对话；java
loadExtensions/session_start 在 create 内同步发射，渲染循环尚未起步——阻塞等
渲染线程会死锁。
【决定】渲染循环起步前调阻塞对话方法⇒IllegalStateException（明确失败）；
非阻塞方法入队缓存，循环起步后生效。
【风险/代价】与 pi 有时序差；java 扩展生态为零、无实际触发者。登窄差（并 D98
族）。[内部]

**裁决⑥：B214 只交付挂载面**
【场景】B214＝/mcp 管理器视图，是 ctx.ui.custom 的载荷；其 menu 编排依赖 MCP
会话内运行时（map/07 G 组 73–84/86–87 全缺）。
【决定】本批落 custom 挂载面（B221③收编）；B214 台账改注「挂载面批 17 落地，
载荷随 MCP 扩展工厂批」，不销号。map/07 row 85 证据列更新。
【风险/代价】无——拆分在 docs/20 序 16 已预告。[内部]

**裁决⑦：RPC 臂信号/超时的本地解除**
【场景】pi createDialogPromise 在 signal abort/timeout 时本地 resolve 默认值
（同时 timeout 已写进请求让宿主倒计时）。
【决定】RpcExtensionUI 等待循环带 signal 检查＋超时点；应答迟到则丢弃。
【风险/代价】宿主侧对话框可能仍开着等用户——pi 同（宿主各自处理）。[内部]

## §7 测试计划

**coding-agent**：

1. `ExtensionUiNoOpTest`：28 成员 default 语义全过（取消值/空操作/错误文案
   "UI not available"）。
2. `UiPromptEventExtensionUITest`：五方法各发 start/end 对；嵌套（custom 内
   select）只发最外层一对＋end 取最外层 kind/title；委托同步抛→深度复位＋
   end 仍发＋异常上抛；NOOP 不包装不发事件；异步发射经 CountDownLatch 收。
3. `DefaultExtensionContextUiTest`：晚绑定——create 后换 UI（RPC 序对位：
   先 noop 后注入）ctx.ui() 见新值；hasUi/mode 现读；NOOP 恒等。
4. `RpcExtensionUITest`：九方法线格式参数键逐字（select/confirm/input/editor
   参数＋timeout 透传；notify/setStatus/setTitle/set_editor_text fire-and-forget
   不等应答）；cancelled/value/confirmed 三应答形；signal 预检/中途 abort；
   setWidget 工厂臂静默忽略。
5. `AgentSessionExtensionUiTest`：双参 setter＋fork 携带＋onSessionInvalidated
   在替换路径与 reload 首步前各发一次。

**tui**：

6. `ExtensionSelectorOverlayTest`：键序（up/down/k/j/enter/esc/ctrl+c）、空表不
   提交、hint 行文本、timeout 倒计时标题与到期取消（假调度器）。
7. `ExtensionInputOverlayTest`/`ExtensionEditorOverlayTest`：提交/取消/预填/
   外部编辑器回落链（mock ExternalEditorSupport 臂——命令解析与 notepad/nano
   回落单测在 coding-agent 侧）。
8. `TuiExtensionUITest`：四阻塞方法经假渲染线程驱动（dispatch 直执夹具）出值/
   取消/abort 三结局；渲染线程自调用守卫；confirm 的 Yes/No 映射逐字。
9. `ExtensionCustomOverlayTest`：done 幂等、dispose 吞异常、工厂抛上抛、
   overlay:true 同路径＋handle 三件。
10. `WidgetFooterHeaderTest`：setWidget 截断 10 行＋截断文案、同 key 两区清理、
    上区空 Spacer/下区空无 Spacer、placement 路由；setFooter/setHeader 换槽与
    回内置、dispose 序；扩展状态段排序/sanitize/截断/空表不渲染。
11. `ExtensionUiResetTest`：onSessionInvalidated 全清（活动对话框 resolve 取消值
    ＋widget/footer/header/statuses 复位）。

**回归**：`mvn clean verify` 全绿（含 spotbugs）；mvn test 带 `-am`（批 4 教训）。

## §8 对账表（判定列：前态→后态）

| 行 | 前态 | 后态 | 证据（闭环回填） |
|---|---|---|---|
| map/04 E5-31（w2） | 缺失 | 存疑 | TUI 十方法＋事件包装落；余 15 成员挂账 §4 |
| map/04 E5-32（w1） | 存疑 | 对齐 | RpcExtensionUI 九方法＋晚绑定修真 |
| map/04 D#26（w1） | 缺失 | 对齐 | UiPromptEventExtensionUI＋spec＋firer |
| map/04 D#27（w1） | 缺失 | 对齐 | 同上 |
| map/05 S22（w1） | 缺失 | 对齐 | 四件套 overlay |
| map/05 S23（w1） | 缺失 | 对齐 | widget/footer/header/status 段 |
| docs/04 B221 | 余 ①③⑥ | 余 ①⑥ | ③ 收编 |
| docs/04 B214 | 开放 | 开放（改注） | 挂载面落；载荷随 MCP 批 |

## §9 闭环回填

**闭环日 2026-10-24；提交 fcd87042（T1–T3）＋ade7bafd（T4 配键/回落链）＋
1c68f926（T4–T6 tui）＋066dc123（T7）＋08e93f3a（spotbugs 回补）＋评审回补笔。**

**实际账面（与 §0 预期全中）**：模块 350.5→**354.0/485 ≈ 73.0%**（+3.5）；
E5 域 22.0→**23.5/58 ≈ 40.5%**；D 域 77.0→**79.0/83 ≈ 95.2%**（余缺失 2 行＝#1/#11）；
map/05 118.5→**120.5/245 ≈ 49.2%**（S22/S23 各 +1.0）；
全仓 1,030.0→**1,035.5/1379 ≈ 75.1%**（+5.5）。

**§8 对账表证据回填**：

| 行 | 前态→后态 | 证据 |
|---|---|---|
| map/04 E5-31（w2） | 缺失→存疑 | `TuiExtensionUI` 13 方法＋`ChatScreen` 注入层＋事件包装；余 15 成员挂账 §4 |
| map/04 E5-32（w1） | 存疑→对齐 | `RpcExtensionUI` 九方法＋`RpcDispatcher` 注入＋晚绑定修真（`DefaultExtensionContext` Supplier） |
| map/04 D#26（w1） | 缺失→对齐 | `UiPromptEventExtensionUI`＋`UI_PROMPT_START` spec＋`SessionEventFirer.uiPromptStart` |
| map/04 D#27（w1） | 缺失→对齐 | 同上（end 取 activePrompt 回落逐字） |
| map/05 S22（w1） | 缺失→对齐 | 三 overlay＋`TuiExtensionUI` 阻塞面（51 用例绿） |
| map/05 S23（w1） | 缺失→对齐 | `ChatScreen` widget/footer/header/statuses（WidgetFooterHeaderTest 13 用例绿） |
| docs/04 B221 | 余 ①③⑥ | **余 ①⑥**（③ 收编） |
| docs/04 B214 | 开放 | 开放（改注：挂载面批 17 落地，载荷随 MCP 扩展工厂批） |

**评审回补（新鲜上下文评审：2 Important 修真＋2 Minor 随修，6 Minor 登台账 deferred）**：

- **I-1** RPC 应答按 id 路由：`pendingUiRequests` map（pi `pendingExtensionRequests`
  对位）——旧广播队列失配即弃，超时/abort 的孤儿通道线程会偷走后发对话的应答
  （RED→GREEN：`RpcExtensionUiRoutingTest` 并发乱序应答＋超时孤儿两臂）。
- **I-2** 会话替换重绑扩展 UI（pi `finishSessionReplacement→rebindSession` 对位）：
  `RpcDispatcher` new_session 重注入＋`AgentSession.switchTo` 注入换入会话＋
  `PiTuiApp.switchSession` 携带缝（RED→GREEN：`AgentSessionExtensionUiTest.switchTo…`
  ＋`RpcExtensionUiRoutingTest.newSession…`＋`SwitchSessionUiCarryTest`）。
- **M-1** `custom` 活动对话表结局后清账（`ExtensionCustomOverlayTest.completed…`）。
- **M-2** 装饰器透传 `onSessionInvalidated`（`UiPromptEventExtensionUITest.onSession…`）。
- deferred minor×6（台账批 17 段）：`AbortSignal.onAbort` 竞态〔ai 存量〕／selector
  空串选项提交门／`ChatScreen.render` 布局移位／`getEditorText` 跨线程读／widget
  文本缺 paddingX=1／notify(info) 无 back-to-back 去重〔既有通道同缺〕。

**Ruling（执行期裁决，非设计稿条目）**：

- R1：`RpcExtensionUIRequest.params` 经 `@JsonAnyGetter` 平铺顶层（pi spread 逐字）——
  旧嵌套 `params` 键与 pi 线格式不符；请求只序列化（宿主侧读），java 不反序列化请求
  ⇒ `extensionUiWireRoundTrip` 改钉平铺形。
- R2：ui_prompt 异步发射用 FIFO 单虚拟线程执行器（`queueMicrotask` 保序对位）——
  每事件独立 `Thread.start` 不保序（测试抓到 start/end 倒挂）。
- R3：fork 臂 `closeForFork` 也调 `onSessionInvalidated`（pi 的 invalidate 覆盖一切
  swap；设计稿 T7.2 只列两点，补位对齐）。
- R4：评审 I-2 的 TUI 携带缝落 `PiTuiApp.switchSession`（/new・/fork・/resume 同缝），
  RPC 臂落 `RpcDispatcher` new_session 重注入＋`AgentSession.switchTo` 注入。
- R5：倒计时集成测试用真 1Hz 调度器（timeout=1ms ⇒ ~1s）替代「假调度器」——
  tick/expire 语义已由 overlay 级单测确定性覆盖。

**修真（实现期抓到，非评审）**：`loadExtensions` 的 `--no-extensions` 早退臂曾跳过
UI 烘值（pi `setUIContext` 独立于扩展加载，runner.ts:580）——烘值上提至早退前；
无此修 `-ne` 下 TUI/RPC 注入落空。

**spotbugs 回补**：overlay 三件 `cancelled` volatile＋`selectedIndex` AtomicInteger
（verify 阶段 4 钉——批 4「收口前必须全量 clean verify」教训再验证）。

**排障注记**：mcp `StreamableHttpTransportTest.getStreamAnswered405StaysSilent` 全量跑
负载下抖动红（隔离复跑绿，与本批无涉）；mvn 输出接 `head` 会被 SIGPIPE 掐断成假绿
（验证一律落盘日志＋看 MVN_EXIT）。

**窄差实登**：D95–D101（docs/04 批 17 段逐条，与 §5 一致）。

**七处回填记录**：docs/04（B221/B214 行＋批 17 段＋E1/E2/E6/E7 行数）＋docs/05
（总表两行＋§2.4 域读数＋批 17 bullet）＋map/04（E5-31/E5-32/D#26/D#27 行＋E5/D
两域汇总行）＋map/05（S22/S23 行＋加权汇总＋批 17 注记）＋map/07（row 85 证据列）
＋docs/20（§1 总览＋§2 批 17 行＋§3 序 16 划线＋D 域余 2 缺失注记）＋本段。

**回归**：`mvn clean verify` 全仓绿（含 spotbugs；client/server/evals/dist 全过）。
