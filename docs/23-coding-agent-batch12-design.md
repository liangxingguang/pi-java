# coding-agent 批 12 设计稿：settings 消费点批（TUI 行为键 ×6＋声明面消费点 ×9＋setter TUI 入口）

【背景摘要】本稿是 coding-agent 对齐批 12 的设计，收 docs/20 §3 批 12 行
「settings 消费点批（批 11 后半）」的可落地部分。批 11（docs/22）落了 21 键的
声明面与模块内消费链；本批把**消费点在 TUI 域**的键接通：TUI 行为键 8 个中
落地 6 个（`terminal` 七键、`markdown` 两键因消费面无对位/依赖未激活子系统
留在原判，见 §4），批 11 声明面 14 键中落地 9 个的消费点（changelog 流 ×2、
analytics 核销 ×2、硬件光标、全屏三面、warnings；cache 两键、websocket、
install-telemetry、copyOnSelect 留 §4 重排），外加 `setModelThinkingLevel`
的 TUI 入口（批 11 T2 留白）。取证另发现 E2-37 `treeFilterMode` **账面滞后**
（消费点批 9 已随树选择器落地），零代码核销。

术语（首现释义）：

- **E2 域**：`docs/map/04-coding-agent.md` 的 settings.json 键清单（54 行入分母）。
- **消费点**：设置键的实际生效点（accessor 不算）。本批多为 TUI 模块接线，
  涉及 E2 判定口径修订（裁决①）。
- **核销**：零代码翻行——消费点早已落地、map 行未翻（账面滞后），或 pi 侧
  零消费、声明面即全量。
- **窄差（D 编号）**：与 pi 无法完全对齐、登记在册的差异。本批接续批 11 的
  D52–D54，新登 D55 起。
- **R1 / S2a-d / K38**：`docs/map/05-tui-web.md` 的行号（Markdown 渲染组件／
  alt-screen 选区子能力／滚轮加速模型），本批与其联动但不翻这些行。

---

## §0 基线与预期翻动

| 口径 | 批 11 后基线 | 本批预期 | 依据 |
|---|---|---|---|
| 模块（Σ 485，去重） | 303.5（62.6%） | **311.0（64.1%）** | §8 对账表，+7.5 |
| E2 域（Σ权重 84） | 67.5（80.4%） | **75.0（89.3%）** | 同上 |
| 全仓（Σ 1379） | 977.5（70.9%） | **985.0（71.4%）** | 同上 +7.5 |

翻动构成（+7.5，全部存疑→对齐，每行 +0.5×w）：

- 核销（零代码）：E2-37 `treeFilterMode`（裁决②）、E2-23 `enableAnalytics`＋
  E2-24 `trackingId`（裁决③：pi 侧零消费 ⇒ 声明面即全量）。
- TUI 行为键（本批接线）：E2-36 `doubleEscapeAction`、E2-15 `externalEditor`、
  E2-39 `editorPaddingX`、E2-41 `autocompleteMaxVisible`、E2-40 `outputPad`。
- 声明面消费点：E2-1 `lastChangelogVersion`＋E2-21 `collapseChangelog`
  （changelog 流）、E2-42 `showHardwareCursor`、E2-52 `fullscreenExitOutput`、
  E2-53 `fullscreenScrollbar`、E2-55 `fullscreenWheelScrollLines`、
  E2-44 `warnings`。
- 无账面：T14 `setModelThinkingLevel` TUI 入口（E2-5 批 11 已对齐，本批补
  setter 的 TUI 入口）。

**不做（§4 重排/窄差）**：E2-32 `terminal`（D56）、E2-43 `markdown`
（D57＋随 R1 批）、E2-14/E2-49 cache（独立子系统批）、E2-50
`websocketConnectTimeoutMs`（D58）、E2-22 `enableInstallTelemetry`
（D55＋B253）、E2-54 `fullscreenCopyOnSelect`（D59，挂 S2a-d）。

行为变化注记（changelog 级，【模型可见】）：

1. `autocompleteMaxVisible` 默认 **8⇒5**（pi 对齐，裁决④）；
2. `doubleEscapeAction` 默认 **"tree" 生效**——此前双击 Esc 无任何行为，
   本批后空编辑器双击 Esc 打开树选择器（pi 默认同）；
3. fullscreen 退出默认 **dump transcript** 到主屏（pi 默认 `transcript`；
   此前 java 静默退出什么都不输出）。

---

## §1 pi 侧事实（取证，锚点 `ea448f454`，只走 `git show`）

> 路径均相对 pi 仓根；schema 实际路径
> `packages/coding-agent/src/core/settings-schema.ts`（下省前缀
> `packages/coding-agent/src/`）。

### 1.1 `doubleEscapeAction`（T2）

```ts
// settings-schema.ts:332-337
doubleEscapeAction: Type.Optional(
    Type.Union([Type.Literal("fork"), Type.Literal("tree"), Type.Literal("none")], {
        description: "Action for double-escape with an empty editor.",
        default: SETTINGS_DEFAULTS.doubleEscapeAction,   // "tree"（settings-defaults.ts:44）
    }),
),
// accessor：settings-manager.ts:1341-1348（get 回退默认；set 写全局）
// 唯一行为点 interactive-mode.ts:3067-3097（defaultEditor.onEscape 内，空编辑器臂）：
} else if (!this.editor.getText().trim()) {
    const action = this.settingsManager.getDoubleEscapeAction();
    if (action !== "none") {
        const now = Date.now();
        if (now - this.lastEscapeTime < 500) {
            if (action === "tree") { this.showTreeSelector(); }
            else { this.showUserMessageSelector(); }   // fork 入口（:5500）
            this.lastEscapeTime = 0;
        } else { this.lastEscapeTime = now; }
    }
}
```

生效前提：编辑器为空且前面没有更高优先级 Escape 消费（流式中断、bash 中止、
bash-mode 退出——pi onEscape 各臂在前短路）。设置页行
settings-selector.ts:581。

### 1.2 `externalEditor`（T3）

```ts
// settings-schema.ts:245-247：无 default（undefined 走 env/平台回落）
// settings-manager.ts:946-956（accessor 回落链，逐字）：
getExternalEditorCommand(): string {
    const configuredEditor = this.settings.externalEditor;
    if (typeof configuredEditor === "string" && configuredEditor.trim() !== "") {
        return configuredEditor;
    }
    const environmentEditor = process.env.VISUAL || process.env.EDITOR;
    if (environmentEditor) { return environmentEditor; }
    return process.platform === "win32" ? "notepad" : "nano";
}
// 键位：keybindings.ts:59-62 "app.editor.external" ctrl+g
// 主消费点 interactive-mode.ts:4596-4611（逐字）：
private async handleOpenExternalEditor(): Promise<void> {
    const editorCmd = this.settingsManager.getExternalEditorCommand();
    const content = this.editor.getExpandedText?.() ?? this.editor.getText();
    this.ui.stop();
    try {
        const result = await editInExternalEditor({ command: editorCmd, content });
        if (result.status === "complete") { this.editor.setText(result.content); }
    } finally {
        this.ui.start();
        this.ui.requestRender(true);
    }
}
// 底层 external-editor.ts:14-44：tmpdir/pi-editor-*/prompt.md；command.split(" ")
// 拆 argv（不支持引号转义）；spawn stdio inherit（Windows shell:true）；
// 退出码 0 ⇒ 读回（stripBom、去尾部换行），非 0 ⇒ 编辑器内容不动。
```

### 1.3 `editorPaddingX`（T4）

```ts
// settings-schema.ts:358-362：Number，默认 0（settings-defaults.ts:46）
// accessor settings-manager.ts:1373-1380（set clamp 0..3）
// 消费：interactive-mode.ts:646-651 构造注入 CustomEditor；:2080-2086 热更；
// tui editor.ts:523-524/560：
const paddingX = Math.min(this.paddingX, maxPadding);
const contentWidth = Math.max(1, width - paddingX * 2);
const leftPadding = " ".repeat(paddingX);   // 每行左前缀；命中测试 x - paddingX（:642-646）
```

### 1.4 `autocompleteMaxVisible`（T5）

```ts
// settings-schema.ts:370-374：Number，默认 5（settings-defaults.ts:48）
// accessor settings-manager.ts:1393-1395（set clamp 3..20）
// 消费：editor.ts:2241 new SelectList(items, this.autocompleteMaxVisible, ...)；
// select-list.ts:175-179 以 maxVisible 算滚动窗口 startIndex/endIndex。
```

### 1.5 `outputPad`（T6）

```ts
// settings-schema.ts:364-368：0|1 字面量联合，默认 1（settings-defaults.ts:47）
// accessor settings-manager.ts:1383-1385（逐字）：
getOutputPad(): 0 | 1 { return this.settings.outputPad === 0 ? 0 : SETTINGS_DEFAULTS.outputPad; }
// 消费面：interactive-mode.ts:664/:2073/:6477 this.outputPad = getOutputPad()；
// 构造注入全部内容卡（assistant-message/bash-execution/compaction/branch-summary/
// skill/custom/user-message/tool-execution，各自 setOutputPad 方法）；
// 热更 :5078-5089 遍历 chatContainer+pendingMessagesContainer duck-type 调
// setOutputPad（不回溯重建）；扩展渲染器经 ToolRenderContext.outputPad 自取。
// 注意（取证更正）：本锚点 tui 包 box.ts/text.ts 无 setOutputPad——map/05 :422
// 「box.ts/text.ts 加同名方法」描述有误，padding 只是构造参数。
```

### 1.6 changelog 流（T7：E2-1＋E2-21）

```ts
// 数据：包根 CHANGELOG.md（config.ts:459-461 getChangelogPath）
// 解析器 utils/changelog.ts：parseChangelog(:111，按 ## [x.y.z] 头切分)、
// compareVersions(:173)、getNewEntries(:182-192，逐字)：
export function getNewEntries(entries: ChangelogEntry[], lastVersion: string): ChangelogEntry[] {
    const parts = lastVersion.split(".").map(Number);
    const last: ChangelogEntry = { major: parts[0]||0, minor: parts[1]||0, patch: parts[2]||0, content: "" };
    return entries.filter((entry) => compareVersions(entry, last) > 0);
}
// 启动比较＋回写 interactive-mode.ts:1332-1357（getChangelogForDisplay，逐字要点）：
//   会话已有消息 ⇒ undefined（resume/continue 不显示）；
//   !lastVersion ⇒ 全新安装：setLastChangelogVersion(VERSION)＋reportInstallTelemetry，
//                  不显示；
//   有新条目 ⇒ setLastChangelogVersion(VERSION)＋reportInstallTelemetry，返回条目
//              markdown（normalizeChangelogLinks 拼绝对链接）；
// 调用点 :948-949（init）。
// collapse 门控 :843-872（showStartupNoticesIfNeeded）：
if (this.settingsManager.getCollapseChangelog()) {
    // 一行：Updated to vX.Y.Z. Use /changelog to view full changelog.
} else {
    // "What's New" 加粗标题＋Spacer＋Markdown(changelogMarkdown.trim())
}
// /changelog 命令 :6774-6793：全量条目 reverse（最新在前）拼接渲染，
// **不回写** lastChangelogVersion（回写只在上面两臂）。
// accessor：settings-manager.ts:678-685（lastChangelogVersion）、
// :1023-1030（collapseChangelog，?? false，settings-defaults.ts:30）。
```

### 1.7 analytics 核销取证（T8：E2-23＋E2-24）

全锚点 grep：除 `setEnableAnalytics` 的 UUID 生成（settings-manager.ts:1043-1058，
批 11 T7 已对位）、实验性首次设置向导写入（startup-ui.ts:193）、bug-report
**脱敏剔除**（bug-report.ts:62）外，**pi 运行时零消费者**——无 analytics
客户端、无上报端点、trackingId 不进 install ping。docs/settings.md:168 自述
"Currently used only by the experimental first-run setup."。
⇒ **pi 侧零消费（纯声明）**，声明面即全量（裁决③）。

### 1.8 `showHardwareCursor`（T9：E2-42）

```ts
// settings-manager.ts:1363-1365（逐字，设置优先、env 兜底、默认关）：
getShowHardwareCursor(): boolean {
    return this.settings.showHardwareCursor ?? process.env.PI_HARDWARE_CURSOR === "1";
}
// 生效：tui.ts:1477-1484 resolveFakeCursors——开 ⇒ 聚焦组件假光标标记删除
// （真终端光标，仍为 IME 定位而移动）；关 ⇒ 假光标渲染反色块 \x1b[7m…\x1b[27m。
// CHANGELOG 注：曾默认开启后改默认关闭。
```

### 1.9 全屏三面（T10/T11/T12：E2-52/53/55）

```ts
// accessor settings-manager.ts:1251-1295（默认：transcript / auto / true / auto）：
getFullscreenExitOutput()  { return this.settings.fullscreenExitOutput === "resume-hint"
    ? "resume-hint" : SETTINGS_DEFAULTS.fullscreenExitOutput; }
getFullscreenScrollbar()   { const m = this.settings.fullscreenScrollbar;
    return m === "always" || m === "hidden" ? m : SETTINGS_DEFAULTS.fullscreenScrollbar; }
getFullscreenWheelScrollLines() { const l = this.settings.fullscreenWheelScrollLines;
    return typeof l === "number" && Number.isFinite(l)
        ? Math.max(1, Math.min(100, Math.floor(l))) : SETTINGS_DEFAULTS.fullscreenWheelScrollLines; }

// exitOutput 两臂 interactive-mode.ts:881-887（逐字）：
private stopInteractiveTui(fullscreenExitOutput: FullscreenExitOutput): void {
    if (this.renderer.mode === "fullscreen" && fullscreenExitOutput === "transcript") {
        while (this.renderer.hasOverlayEntries) this.renderer.hideOverlay();
        this.switchTuiMode("regular", false, false);   // 切主屏渲染器
        this.renderer.renderNow();                     // transcript 内联重绘进主缓冲
    }
    this.ui.stop({ preserveScreen: this.renderer.mode === "fullscreen" });
}
// resume-hint 臂：tui-alt-screen.ts:406-408 只发 \x1b[?1049l\x1b[?25h，alt 屏内容
// 被终端丢弃。两臂退出后统一打印（:4322-4328）：
//   "To resume this session: pi [--session-dir X] --session <id>"（formatResumeCommand
//   :339-351，仅 TTY＋已持久化会话）。

// scrollbar 语义 scroll-view.ts:75-77,94（逐字）：
if (this.scrollbar === "always") return this.currentViewportHeight > 0;
return (this.scrollbar === "auto" && this.contentHeight > this.currentViewportHeight
    && this.transientScrollbarVisible);
// always＝常驻且内容区让出一列；auto＝内容超高且刚滚动过短暂显示；hidden＝永不。
// 热更 interactive-mode.ts:2048-2050 setScrollbar。

// wheel：tui-alt-screen.ts:271 new WheelScrollAccelerator(options.wheelScrollLines ?? 1)；
// :707-710 每滚轮事件 lines ×（Alt ? 5 : 1）（ALT_WHEEL_SCROLL_MULTIPLIER=5, :77）；
// wheel-scroll.ts：数字 ⇒ 固定行数；"auto" ⇒ mac 本地（无 SSH_* env）恒 1，
// 其他平台按事件速率加速（GESTURE_GAP_MS=200/REFERENCE_GAP_MS=100/MAX_AUTO_LINES=6，
// 带小数 carry）。
```

### 1.10 `warnings.anthropicExtraUsage`（T13：E2-44）

```ts
// settings-schema.ts:144-146：默认 true（settings-defaults.ts:54）
// accessor settings-manager.ts:1419-1426（浅拷贝 get；set 写全局）
// 唯一门控 interactive-mode.ts:5261-5264（maybeWarnAboutAnthropicSubscriptionAuth 开头）：
if (this.settingsManager.getWarnings().anthropicExtraUsage === false) { return; }
// 触发条件（:5267-5286）：每会话一次（anthropicSubscriptionWarningShown）；
// model.provider === "anthropic"；checkAuth("anthropic") 为 oauth，或 apiKey 以
// sk-ant-oat 开头（isAnthropicSubscriptionAuthKey :325-327）。异常静默吞掉。
// 文案（:321-323，逐字）：
const ANTHROPIC_SUBSCRIPTION_AUTH_WARNING =
    "Anthropic subscription auth is active. Third-party harness usage draws from extra usage "
    + "and is billed per token, not your Claude plan limits. Manage extra usage at "
    + "https://claude.ai/settings/usage. Disable this warning in /settings.";
// 触发时机：:1228（init 后）、:4550（模型选择结果）、:5207/:5352（切换/登录后）、
// :6120/:6126（/model 流程）。/settings warnings 子菜单 settings-selector.ts:157-176。
```

### 1.11 `setModelThinkingLevel` TUI 入口（T14）

唯一 UI 触发点＝/settings 的 "Default thinking level per model" 子菜单（非
model selector、非 slash 命令；全锚点 grep 仅 settings-manager.ts:776 定义＋
interactive-mode.ts:5010 调用两处）：

```ts
// settings-selector.ts:607-610 菜单项；:611-680 两级 SteppedSubmenu：
//   step "model"：config.availableDefaultModels（当前模型置顶、可搜索、
//     description 显示现有 override）；
//   step "level"：(model.reasoning ? getSupportedThinkingLevels(model) : ["off"])，
//     已有 override 追加 (clear override) 项；
// 提交回调 :685-700：clear ⇒ onModelThinkingLevelRemove，否则 onModelThinkingLevelChange。
// interactive-mode.ts:5009-5019（逐字，对当前模型即时生效）：
onModelThinkingLevelChange: (provider, modelId, level) => {
    this.settingsManager.setModelThinkingLevel(provider, modelId, level);
    const current = this.session.model;
    if (current && current.provider === provider && current.id === modelId) {
        this.session.setThinkingLevel(level);
        this.footer.invalidate();
        this.updateEditorBorderColor();
    }
},
onModelThinkingLevelRemove: (provider, modelId) => {
    this.settingsManager.removeModelThinkingLevel(provider, modelId);
    const current = this.session.model;
    if (current && current.provider === provider && current.id === modelId) {
        const globalDefault = this.settingsManager.getDefaultThinkingLevel() ?? DEFAULT_THINKING_LEVEL;
        this.session.setThinkingLevel(globalDefault);
        ...
    }
},
```

### 1.12 核销取证：`treeFilterMode`（T1，E2-37）

pi 语义（interactive-mode.ts:5562 → tree-selector.ts:137/:367-388/:1049-1083）：
**设置只决定打开 /tree 时的初始 filter**；选择器内快捷键可循环切换，**不回写
设置**。java `TreeNavigationFlow.java:97-98` 初值读 accessor 传入
`TreeSelectorOverlay`（:99-104；overlay 内 :141-149 切换、`FilterMode` 五值
同形），测试 `TreeNavigationFlowTest.java:579-592` 钉「初值读全局、切换不
回写」——与 pi 同形，消费点批 9 已落地（账面滞后，裁决②核销）。

---

## §2 java 侧现状（取证 2026-10-22）

| 键/面 | 现状 | 证据 |
|---|---|---|
| doubleEscapeAction | 键位 esc 已绑（`KeybindingsManager:26/95`）；`PiTuiApp:500-511` INTERRUPT 臂只有「流式回填＋abort」；全仓无双击时间窗逻辑；设置页行已有（`SettingsScreen:48-50`） | accessor `SettingsAccessors:242-249` **直读无默认回落** |
| externalEditor | Ctrl+G 已绑（`KeybindingsManager:34/103`）但 `PiTuiApp:550` default 臂空操作；无 $EDITOR/$VISUAL 读取、无 spawn | accessor `SettingsAccessors:212-219` |
| editorPaddingX | 字段 `Settings:65`，**无 accessor 零引用**；编辑器渲染硬编码 `"> "`（`EditorComponent:54-58`） | — |
| autocompleteMaxVisible | 字段 `Settings:67`，无 accessor；`SlashCompleter:38` 硬编码 `MAX_ROWS = 8`（消费 :124/:143-144） | — |
| outputPad | 字段 `Settings:66`，无 accessor；工具卡 `ToolCallCard:13-31` 两行 record 无 pad | 渲染链 `ChatScreen.onEntry → ChatMessage.from:40 → ChatViewportElement.flatten:240-252` |
| changelog | `MiscCommands:24-30` 静态串＋`:120` 注册；无数据文件、无版本比较 | install 访问器 `SettingsInstallAccessors:32-50` 零消费者 |
| showHardwareCursor | accessor 已有（`SettingsUiPrefsAccessors:45-48`，`?? env=="1"`）；`TamboUIAdapter:44-58` 写死 `hideCursor(true)` | — |
| fullscreenExitOutput | `PiTuiLauncher.runFullscreen:61-83` 正常退出**什么都不输出**（仅异常路径 :74 恢复主屏）；`ScrollbackTranscript`（tui/util）只服务 regular 内联模式（`PiTuiApp:160-175`） | accessor `SettingsUiPrefsAccessors:58-66` |
| fullscreenScrollbar | 自绘 `ChatViewportElement:261-271`（hover/drag 宽 1→2；`inScrollbar:279-286`）——现状≈常驻 | accessor `SettingsUiPrefsAccessors:68-78` |
| fullscreenWheelScrollLines | java 自有 `tui.scroll*` 10 键配置面（`ScrollConfig:26-117`，`from(Settings, int):75-98`＋`ScrollInputNormalizer` 有界加速）已接线 `PiTuiLauncher:42-46`；pi 键 accessor（`SettingsUiPrefsAccessors:98-112`，返回 `Object`＝`"auto"`\|`Integer`）零消费 | K38 差异已登记 |
| warnings | `Warnings` record＋访问器（`SettingsUiPrefsAccessors:32-40`）零消费；无超额检测 | auth 判据可复用：ai 层 `Credentials`/`OAuthCredential`＋`AnthropicMessagesApi:146` `OAUTH_TOKEN_MARKER = "sk-ant-oat"` |
| setModelThinkingLevel 入口 | setter 访问器已有（批 11 T2）；TUI 零调用；`SettingsScreen` 纯平铺循环（:27-28 自承 free-form/子菜单出界）；`ModelSelectorScreen:29-33` 模型源＝`ModelsJsonConfig.allModels().listModels()` | — |
| treeFilterMode | **消费已在**：`TreeNavigationFlow:97-98` 初值读 accessor；overlay 内切换不回写（测试钉死） | 核销 |

---

## §3 任务分解

> accessor 归域：`editorPaddingX`/`autocompleteMaxVisible`/`outputPad` 进
> `SettingsUiPrefsAccessors`（与 fullscreen 四键同域）；`doubleEscapeAction`
> 默认回落在 `SettingsAccessors` 原位补。

### T1：E2-37 `treeFilterMode` 核销（零代码，存疑→对齐，w1）

map/04 行翻对齐，证据：消费点 `TreeNavigationFlow.java:97-98`（初值读全局）
＋`TreeSelectorOverlay.java:141-149`（选择器内切换不回写）＋测试
`TreeNavigationFlowTest.java:579-592`；pi 对位 interactive-mode.ts:5562＋
tree-selector.ts:1049-1083。**裁决②**。

### T2：`doubleEscapeAction` 双击 Esc（E2-36，存疑→对齐，w1）

**accessor 默认回落**（pi settings-manager.ts:1341-1343）：
`SettingsAccessors.getDoubleEscapeAction` 改：

```java
/** pi {@code getDoubleEscapeAction}（:1341-1343）：非法/缺席 ⇒ 默认 "tree"。 */
public String getDoubleEscapeAction() {
    var v = manager.effective().doubleEscapeAction;
    return "fork".equals(v) || "none".equals(v) ? v : "tree";
}
```

（行为变化：缺席⇒"tree"，双击 Esc 从「无行为」变「开树选择器」——pi 默认同，
§0 注记 2。）

**PiTuiApp**（INTERRUPT 臂扩，pi interactive-mode.ts:3067-3097 对位）：

```java
case KeybindingsManager.INTERRUPT -> {
    if (session.isRunning()) {
        // 既有首臂：流式中断消费本次 Esc，不计双击（pi :3071-3073 短路）
        var restored = mode.restoreQueuedMessages();
        if (restored != null) {
            chatScreen.restoreToEditor(restored.combinedText());
        }
        mode.abort();
        lastEscapeAt = 0L;
    } else if (chatScreen.isInputEmpty()) {
        // pi :3067-3097：空编辑器双击 Esc（500ms 窗）
        var action = session.services().settings().accessors().getDoubleEscapeAction();
        if (!"none".equals(action)) {
            long now = nowMillis();
            if (now - lastEscapeAt < 500) {
                if ("tree".equals(action)) { openTreeSelector(); }
                else { openForkSelector(); }
                lastEscapeAt = 0L;
            } else {
                lastEscapeAt = now;
            }
        }
    } else {
        mode.abort();   // 既有：非空编辑器/空闲 abort（幂等）
    }
}
```

- `openTreeSelector()`/`openForkSelector()`：把 `PiTuiApp:444-459` 的
  `UI_TREE_SELECTOR`/`UI_FORK_SELECTOR` 两臂原样抽为私有方法，两处调用。
- `nowMillis()`：包私有 `LongSupplier` 字段（默认 `System::currentTimeMillis`），
  测试注入。
- 字段 `private long lastEscapeAt;`。

### T3：`externalEditor` Ctrl+G 外部编辑器（E2-15，存疑→对齐，w1）

**accessor 升级**（pi :946-956 回落链逐字对位；环境变量注入便于测试）：

```java
// SettingsAccessors
public String getExternalEditorCommand() {
    return externalEditorCommand(System::getenv);
}
String externalEditorCommand(java.util.function.Function<String, String> env) {
    var configured = manager.effective().externalEditor;
    if (configured != null && !configured.isBlank()) {
        return configured;
    }
    var fromEnv = env.apply("VISUAL");
    if (fromEnv == null || fromEnv.isEmpty()) {
        fromEnv = env.apply("EDITOR");
    }
    if (fromEnv != null && !fromEnv.isEmpty()) {
        return fromEnv;
    }
    return isWindows() ? "notepad" : "nano";
}
```

**新文件 `ExternalEditorSupport`**（coding-agent core，~110 行；pi
external-editor.ts:14-44 对位）：

```java
public final class ExternalEditorSupport {

    /** pi editInExternalEditor 结果：complete 带读回内容；其余编辑器不动。 */
    public sealed interface Result {
        record Complete(String content) implements Result {}
        record Failed(int exitCode) implements Result {}
    }

    public static Result edit(String command, String content)
            throws IOException, InterruptedException {
        // pi：tmpdir/pi-editor-*/prompt.md
        var dir = Files.createTempDirectory("pi-editor-");
        var file = dir.resolve("prompt.md");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        try {
            // pi：command.split(" ") 简单拆（不支持引号转义）——逐字对位
            var argv = command.split(" ");
            var pb = isWindows()
                // pi Windows shell:true ⇒ cmd /c 整串
                ? new ProcessBuilder("cmd", "/c", command + " \"" + file + "\"")
                : new ProcessBuilder(append(argv, file.toString()));
            pb.inheritIO();
            int code = pb.start().waitFor();
            if (code != 0) {
                return new Result.Failed(code);
            }
            var text = Files.readString(file, StandardCharsets.UTF_8);
            // pi：stripBom＋去尾部换行
            if (text.startsWith("﻿")) {
                text = text.substring(1);
            }
            text = text.replaceAll("[\\r\\n]+$", "");
            return new Result.Complete(text);
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }
}
```

**PiTuiApp 接线**（pi :4596-4611 对位；`default` 臂注释里的
`editor.external → Phase 6` 落实）：

```java
case KeybindingsManager.EXTERNAL_EDITOR -> {
    var command = session.services().settings().accessors().getExternalEditorCommand();
    var content = chatScreen.inputText();
    leaveAlternateScreen();                    // pi ui.stop() 对位，见下
    try {
        var result = ExternalEditorSupport.edit(command, content);
        if (result instanceof ExternalEditorSupport.Result.Complete(var text)) {
            chatScreen.setEditorText(text);    // 既有（:491 同款）
        }
    } catch (Exception e) {
        chatScreen.showError("External editor failed: " + e.getMessage());
    } finally {
        enterAlternateScreen();                // pi ui.start()＋requestRender(true)
    }
}
```

- `leaveAlternateScreen()/enterAlternateScreen()`：`TamboUIAdapter` 加两静态
  helper（退出/重进 alt 屏 `\033[?1049l\033[?25h`／`\033[?1049h`，复用
  `PiTuiLauncher:74` 恢复序列），enter 后触发全量重绘。**执行风险**：TamboUI
  runner 对「外部程序写终端」无显式挂起面——inheritIO 的子进程与 runner 渲染
  交错由 alt 屏退出/重进序列隔离；若实测 TamboUI 状态错位，退化为先记
  `externalEditorSupported=false` 并登记窄差（实现时验证，评审点）。
- 非全屏（regular 内联模式）：无 alt 屏，两 helper 空操作。

### T4：`editorPaddingX`（E2-39，存疑→对齐，w1）

**accessor 新增**（`SettingsUiPrefsAccessors`；pi :1373-1380）：

```java
/** pi {@code getEditorPaddingX}：缺席 ⇒ 默认 0。 */
public int getEditorPaddingX() {
    var v = manager.effective().editorPaddingX;
    return v == null ? 0 : Math.min(3, Math.max(0, v));
}
/** pi set clamp 0..3。 */
public void setEditorPaddingX(int padding) {
    manager.global().editorPaddingX = Math.min(3, Math.max(0, padding));
    manager.markModified("editorPaddingX");
}
```

**EditorComponent**（pi editor.ts:523-524/560 对位；live-read 热更）：

```java
// 构造注入 IntSupplier paddingX（PiTuiApp 传 settings().uiPrefs()::getEditorPaddingX）
public Row render() {
    int pad = Math.min(paddingX.getAsInt(), 3);
    return TamboUIAdapter.row(
        TamboUIAdapter.text(" ".repeat(pad) + "> ").cyan(),
        element,
        TamboUIAdapter.text(" ".repeat(pad))     // 右侧等宽留白（pi 双侧 padding）
    );
}
```

（行宽/命中测试：java 编辑器无鼠标命中面，仅渲染留白；右留白用尾格占位，
内容可用宽度随 Row 布局自然收缩。）

### T5：`autocompleteMaxVisible`（E2-41，存疑→对齐，w1）

**accessor 新增**（pi :1393-1395；**默认 5**——裁决④，java 现状硬编码 8）：

```java
/** pi {@code getAutocompleteMaxVisible}：缺席 ⇒ 默认 5。 */
public int getAutocompleteMaxVisible() {
    var v = manager.effective().autocompleteMaxVisible;
    return v == null ? 5 : Math.min(20, Math.max(3, v));
}
public void setAutocompleteMaxVisible(int max) {
    manager.global().autocompleteMaxVisible = Math.min(20, Math.max(3, max));
    manager.markModified("autocompleteMaxVisible");
}
```

**SlashCompleter**：`MAX_ROWS = 8` 常量删除，构造加 `IntSupplier maxVisible`
（调用处 `ChatScreen`/`PiTuiApp` 传 settings live-read——改值即热更，pi
applyRuntimeSettings 同效）；`:124` 改 `Math.min(matches.size(), maxVisible.getAsInt())`，
`:143-144` lineCount 同步。

### T6：`outputPad`（E2-40，存疑→对齐，w1）

**accessor 新增**（pi :1383-1385 逐字对位）：

```java
/** pi {@code getOutputPad}：仅显式 0 ⇒ 0，否则默认 1。 */
public int getOutputPad() {
    var v = manager.effective().outputPad;
    return v != null && v == 0 ? 0 : 1;
}
public void setOutputPad(int pad) {
    manager.global().outputPad = pad == 0 ? 0 : 1;
    manager.markModified("outputPad");
}
```

**消费接线**（java 无 pi 的组件卡族，消费面＝聊天区三处现有渲染）：

- `ChatScreen.onEntry` → `ChatMessage.from(entry)` 链：`ChatScreen` 持
  `IntSupplier outputPad`（`PiTuiApp` 注入 settings live-read），
  `ChatMessage.from(entry, pad)` 新签名（旧签名委托默认 1 保测试面）；
- `MessageBubble.lines()`/`ToolCallCard.lines()`：每个 `LogicalLine` 的左
  indent 加 pad、右侧预留 pad 列（`ChatViewportElement.flatten` 的内容宽减
  `2*pad`）；
- 块间空行（`flatten:243-245`）不动（pi 的 pad 是水平向）。

**窄差 D62**：改值不回溯已渲染条目（pi :5078-5089 遍历容器 duck-type 热更；
java 条目 flatten 后落地，新条目用新值）。设置页行（pi settings-selector
有 outputPad 行）：`SettingsScreen` 加 "Output pad"（0/1 循环）。

### T7：changelog 流（E2-1＋E2-21，存疑→对齐，w1×2）

**数据文件（裁决⑦）**：`pi-java-coding-agent/src/main/resources/CHANGELOG.md`，
pi 同格式 `## [x.y.z]` 头分节；首发内容一节（当前版本
`Version.VERSION`＝"0.1.0-SNAPSHOT"，条目版本取数字三段 "0.1.0"）。

**新文件 `ChangelogSupport`**（coding-agent core，~140 行；pi
utils/changelog.ts:111/173/182-192 对位）：

```java
public final class ChangelogSupport {

    /** pi ChangelogEntry：数字三段版本＋该节 markdown 正文。 */
    public record ChangelogEntry(int major, int minor, int patch, String content) {}

    /** pi parseChangelog（:111）：按 `## [x.y.z]` 头切分，保持文件序。 */
    public static List<ChangelogEntry> parse(String markdown) { /* `^## \[(\d+)\.(\d+)\.(\d+)\]` 分节 */ }

    /** pi compareVersions（:173）：major→minor→patch 逐段比。 */
    public static int compareVersions(ChangelogEntry a, ChangelogEntry b) { /* ... */ }

    /** pi getNewEntries（:182-192 逐字对位）：lastVersion 解析失败段按 0（pi Number() NaN || 0）。 */
    public static List<ChangelogEntry> getNewEntries(List<ChangelogEntry> entries, String lastVersion) {
        var parts = lastVersion.split("\\.");
        var last = new ChangelogEntry(num(parts, 0), num(parts, 1), num(parts, 2), "");
        return entries.stream().filter(e -> compareVersions(e, last) > 0).toList();
    }

    /** 读内置资源（pi getChangelogPath 对位：发行包根 CHANGELOG.md）。 */
    public static String loadBundled() throws IOException {
        try (var in = ChangelogSupport.class.getResourceAsStream("/CHANGELOG.md")) {
            if (in == null) {
                return "";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * pi getChangelogForDisplay（interactive-mode.ts:1332-1357）三臂：
     * 会话已有消息 ⇒ empty；无 lastVersion ⇒ 全新安装只写版本号不显示；
     * 有新条目 ⇒ 写回版本号＋返回新条目拼接。install ping 不移植（裁决⑤）。
     */
    public static Optional<String> forDisplay(SettingsManager settings, String version,
            boolean sessionHasMessages) {
        if (sessionHasMessages) {
            return Optional.empty();
        }
        var install = settings.install();
        var last = install.getLastChangelogVersion();
        var entries = parse(loadBundledUnchecked());
        if (last == null || last.isBlank()) {
            install.setLastChangelogVersion(version);
            return Optional.empty();
        }
        var newEntries = getNewEntries(entries, last);
        if (newEntries.isEmpty()) {
            return Optional.empty();
        }
        install.setLastChangelogVersion(version);
        return Optional.of(newEntries.stream()
            .map(ChangelogEntry::content).collect(Collectors.joining("\n\n")));
    }
}
```

（`normalizeChangelogLinks`：java 首发 changelog 无相对链接，**不移植**——
有链接需求出现再补，登记进实现注释，不占窄差。写回走 markModified＋会话关闭
flush，批 11 setter 惯例。）

**TUI 接线**：`PiTuiLauncher.runInteractive`（session 建后、runner 启动前）：

```java
var changelog = ChangelogSupport.forDisplay(
    session.services().settings(), Version.VERSION, sessionHasMessages(session));
// 传入 app；app.start 后（dispatcher 首帧前）：
changelog.ifPresent(md -> {
    if (session.services().settings().install().getCollapseChangelog()) {
        // pi :843-872 折叠臂（文案逐字）：
        var v = latestVersionOf(md).orElse(Version.VERSION);
        chatScreen.appendSystemText(
            "Updated to v" + v + ". Use /changelog to view full changelog.");
    } else {
        chatScreen.appendSystemText("What's New\n\n" + md.trim());
    }
});
```

**窄差 D63**：changelog 渲染为纯文本（pi 是 Markdown 组件渲染；java
`MarkdownRenderer` 未激活，随 R1 批收）。`sessionHasMessages`：会话条目
非空即 true（pi 判 `state.messages.length > 0`，java 对位取 transcript
是否为空——resume/continue 不显示）。

**`/changelog` 命令**（`MiscCommands:120` 改；pi :6774-6793 对位——全量
reverse 最新在前、**不回写**）：

```java
registry.register(simple("changelog", "Show changelog", "", (args, ctx) -> {
    var entries = ChangelogSupport.parse(ChangelogSupport.loadBundledUnchecked());
    if (entries.isEmpty()) {
        return "No changelog entries found.";
    }
    var reversed = new ArrayList<>(entries);
    Collections.reverse(reversed);
    return "What's New\n\n" + reversed.stream()
        .map(ChangelogSupport.ChangelogEntry::content)
        .collect(Collectors.joining("\n\n"));
}));
```

（静态 `CHANGELOG` 串 :24-30 删除。）设置页行：`SettingsScreen` 加
"Collapse changelog"（false/true，`install()` 域——SettingField 当前绑定
`SettingsAccessors`，扩一个访问器面参数或该行特判，实现时选最小改）。

### T8：analytics 核销（E2-23/E2-24，零代码，存疑→对齐，w1×2）

§1.7：pi 运行时零消费者，声明面＋UUID 生成批 11 T7 已对位 ⇒ 声明面即全量
（**裁决③**，口径先例）。触发条件注记：bug-report 的 trackingId/deviceId
脱敏（pi bug-report.ts:62）随批 14（/bug＋crash-log）落地时带上。

### T9：`showHardwareCursor`（E2-42，存疑→对齐，w1）

`TamboUIAdapter.createRunner()` 参数化：

```java
public static ToolkitRunner createRunner(boolean showHardwareCursor) {
    return ToolkitRunner.create(TuiConfig.builder()
        ...
        .hideCursor(!showHardwareCursor)   // pi tui.ts:1477-1484 对位：
                                           // 开＝真终端光标；关＝自绘假光标
        ...
        .build());
}
```

`PiTuiLauncher.runFullscreen` 传
`session.services().settings().uiPrefs().getShowHardwareCursor()`（accessor
批 11 已含 `?? env PI_HARDWARE_CURSOR=="1"` 回落，pi :1363-1365 同形）。
旧无参重载保留委托 `createRunner(false)`（默认关＝现状，pi 默认同）。
设置页行："Hardware cursor"（false/true，uiPrefs 域）。
**执行注记**：TamboUI 的硬件光标定位与 pi 的 IME 定位面不完全同域——若实测
光标落位异常属 TamboUI 后端行为，评审时裁定是否登记窄差。

### T10：`fullscreenExitOutput`（E2-52，存疑→对齐，w1）

`PiTuiLauncher.runFullscreen` 的 `runner.run(app::root)` 正常返回后（pi
:881-887＋:4322-4328 对位）：

```java
var uiPrefs = session.services().settings().uiPrefs();
if ("resume-hint".equals(uiPrefs.getFullscreenExitOutput())) {
    // resume-hint 臂：alt 屏内容随终端丢弃，什么都不重绘
} else {
    // transcript 臂（默认）：主屏重绘对话滚动回退
    TranscriptDump.print(session);   // 复用 ScrollbackTranscript 的 stdout 打印面
}
printResumeHint(session);            // 两臂统一（pi :4322-4328）
```

- `TranscriptDump`（tui/util 新文件，~80 行）：遍历会话 entries →
  `ChatMessage.from` → flatten 行 → MarkupParser＋主题渲染 ANSI 打印——复用
  `ScrollbackTranscript` 既有打印路径（regular 模式同款），不新造渲染管线。
- `printResumeHint`：仅会话已持久化时打印（pi 仅 TTY＋已持久化）；
  **窄差 D64**：文案按 java CLI 能力成形（pi 打印精确恢复命令
  `pi [--session-dir X] --session <id>`；java `-r` 是选择器开关、无
  `--session <id>` 直给——打印 `pi-java -r` 指引，`-r` 交互选择器随批 13）。
- regular 模式（非 fullscreen）：无 alt 屏，transcript 本就在主屏滚动回退
  里，两臂都不动（pi resume-hint 在非 fullscreen 也只打提示——java regular
  退出已紧邻提示行，打印 `printResumeHint` 同样接上）。

### T11：`fullscreenScrollbar`（E2-53，存疑→对齐，w1）

`ChatViewportElement` 加三态（构造注入，PiTuiApp 建 ChatScreen 时读
`uiPrefs().getFullscreenScrollbar()`）：

```java
enum ScrollbarMode { AUTO, ALWAYS, HIDDEN }   // accessor 值 "auto"/"always"/"hidden" 映射
```

- `HIDDEN`：`renderScrollbar` 不渲染、`inScrollbar` 恒 false、内容区不让列；
- `ALWAYS`：现状（常驻渲染、宽 1→2 hover/drag）；
- `AUTO`：**同 ALWAYS**（窄差 D61：pi auto＝内容超高且刚滚动过短暂显示
  transient；java 无 transient 计时面，auto/always 同形常驻——现状即此，
  零行为变化）。

设置页行："Fullscreen scrollbar"（auto/always/hidden 循环；改值下一帧生效，
java 渲染期现读⇒天然热更）。

### T12：`fullscreenWheelScrollLines`（E2-55，存疑→对齐，w1）

`ScrollConfig.from` 加第三参（裁决⑥）：

```java
public static ScrollConfig from(Settings settings, int defaultEventsPerTick,
        Object fullscreenWheelScrollLines) {
    var base = from(settings, defaultEventsPerTick);
    // pi wheel-scroll.ts：数字 ⇒ 固定行数（accessor 已 clamp 1-100）；
    // "auto"/缺席 ⇒ 维持既有 Codex 有界加速模型（窄差 D60＝K38 已登记差异）
    if (fullscreenWheelScrollLines instanceof Integer lines) {
        return base.withWheelLines(lines);
    }
    return base;
}
```

`PiTuiLauncher:42-46` 传 `session.services().settings().uiPrefs()
.getFullscreenWheelScrollLines()`（accessor 批 11 已落，`Object`＝
`"auto"`\|`Integer`）。设置页行不动（数值键 free-form，pi 在
settings-selector 有行——java SettingsScreen :27-28 自承数值出界，维持）。

### T13：`warnings.anthropicExtraUsage`（E2-44，存疑→对齐，w1）

**新文件 `AnthropicExtraUsageWarning`**（coding-agent core，~90 行；pi
:5261-5286＋:321-327 对位）：

```java
public final class AnthropicExtraUsageWarning {

    /** pi :321-323 逐字【模型可见】。 */
    public static final String TEXT =
        "Anthropic subscription auth is active. Third-party harness usage draws from "
        + "extra usage and is billed per token, not your Claude plan limits. Manage extra "
        + "usage at https://claude.ai/settings/usage. Disable this warning in /settings.";

    private boolean shown;   // pi anthropicSubscriptionWarningShown：每会话一次

    /**
     * pi maybeWarnAboutAnthropicSubscriptionAuth（:5261-5286）：
     * warnings.anthropicExtraUsage == false ⇒ 静默；非 anthropic ⇒ 静默；
     * 订阅凭证（oauth 或 key 以 sk-ant-oat 开头）⇒ 每会话一次警告。
     */
    public Optional<String> maybeWarn(ModelId<?> currentModel,
            Supplier<Optional<String>> anthropicCredential) {
        if (shown) {
            return Optional.empty();
        }
        if (Boolean.FALSE.equals(
                warnings().anthropicExtraUsage())) {   // uiPrefs().getWarnings()
            return Optional.empty();
        }
        if (currentModel == null || !"anthropic".equals(currentModel.provider())) {
            return Optional.empty();
        }
        var credential = anthropicCredential.get();    // 异常静默吞掉（pi 同）
        if (credential.isEmpty() || !isSubscriptionAuth(credential.get())) {
            return Optional.empty();
        }
        shown = true;
        return Optional.of(TEXT);
    }

    /** pi isAnthropicSubscriptionAuthKey（:325-327）：值判据。 */
    static boolean isSubscriptionAuth(String credentialOrKey) {
        return credentialOrKey.startsWith("sk-ant-oat");
    }
}
```

- 凭证供给：`anthropicCredential` 闭包由接线处注入——读 ai 层 `Credentials`
  解析结果（oauth 种类 ⇒ 直接判订阅；api key ⇒ 值判据）。oauth 种类判据与
  `AnthropicMessagesApi:146` 的 `OAUTH_TOKEN_MARKER` 同源（实现时取
  `Credentials` 实有种类面；若种类不可达则退化为 key 值判据并在实现注释记
  差异——pi 的 checkAuth 也是「种类 or 值」双判据之一即中）。
- **TUI 接线**（pi 四时机对位）：`PiTuiApp` 持 guard 实例（随
  `switchSession` 重建——每会话一次）；①启动后（init）、②
  `ModelSelectorScreen.apply` 后、③`cycleModelForward` 后、④登录命令后——
  统一一个私有 `maybeWarnSubscriptionAuth()`：命中则
  `chatScreen.appendSystemText(TEXT)`。
- 设置页行："Warn Anthropic extra usage"（true/false，`setWarnings` 写全局）。

### T14：`setModelThinkingLevel` TUI 入口（无账面；E2-5 setter 入口补全）

**新文件 `ModelThinkingLevelScreen`**（tui/screen，~190 行；pi
settings-selector.ts:607-700 两级 SteppedSubmenu 对位）：

```java
public final class ModelThinkingLevelScreen implements ScreenOverlay {
    // 状态机：step MODEL → step LEVEL → done（Esc 回退/取消）
    // MODEL 步：ModelsJsonConfig.allModels().listModels()（ModelSelectorScreen:29-33
    //   同源），当前模型置顶；每行 description＝现有 override（getModelThinkingLevel）
    // LEVEL 步：model.reasoning() ? ThinkingLevels 全档 : ["off"]；
    //   已有 override ⇒ 追加 "(clear override)" 项（pi :611-680）
}
```

提交（pi :685-700＋interactive-mode.ts:5009-5019 对位，`apply()` 内）：

```java
if (clearOverride) {
    accessors.removeModelThinkingLevel(provider, id);
    if (isCurrentModel(session, provider, id)) {
        // pi：回退全局默认（?? DEFAULT_THINKING_LEVEL）
        session.setThinkingLevel(
            Objects.requireNonNullElse(accessors.getDefaultThinkingLevel(), "off"));
    }
} else {
    accessors.setModelThinkingLevel(provider, id, level);
    if (isCurrentModel(session, provider, id)) {
        session.setThinkingLevel(level);   // 当前模型即时生效
    }
}
session.services().settings().flush();     // SettingsScreen 写后 flush 惯例
```

**SettingsScreen 入口**：FIELDS 加子菜单行 "Default thinking level per model"
（当前值摘要＝override 计数，pi `modelThinkingOverridesSummary` 对位）；
Enter 命中该行不循环取值而置 `modelThinkingRequested`；`PiTuiApp` 的 overlay
完成处理点检出 `SettingsScreen.pollModelThinkingRequest()` ⇒ 不关 overlay
改 `openOverlay(new ModelThinkingLevelScreen(session))`。

差异注记（不登记窄差）：pi 模型步可搜索（SteppedSubmenu 自带）；java
`SelectList` 无搜索面——与 ModelSelectorScreen 现状一致的 UX 域差异（S14
判定内）。

---

## §4 不做项与重排（docs/20 §3 联动）

| 键 | 处置 | 理由 |
|---|---|---|
| E2-32 `terminal` 七键 | **窄差 D56**，维持存疑 | pi 消费面＝能力覆盖（hyperlinks/trueColor/images 短路终端探测）、OSC 9;4 进度、工具卡 inline 图片、clearOnShrink——全部依赖 TamboUI 后端能力面或 java 没有的终端协议面（图片协议随 K20/K21 域、OSC 状态序列随 K39 域）；可移植子集（OSC 9;4/clearOnShrink）随 TUI 屏域拍板（变量③）再裁 |
| E2-43 `markdown` 两键 | **窄差 D57**（mermaid 无 grok-mermaid 对位库）＋`codeBlockIndent` 随 **MarkdownRenderer 激活批** | 消费前提是 `MarkdownRenderer` 接入 `MessageBubble`——即 map/05 R1（w3，pi 组件 1,025 行域）＋R2–R6 联动，独立成批并计入拍板变量③ |
| E2-14/E2-49 cache 两键 | **独立「cache-warmer 子系统批」**（docs/20 §3 插行） | pi `cache-warmer.ts` 449 行＋sdk.ts:334-339/428 装配＋agent-session 四面接线＋扩展 decide 钩——是子系统不是键接线；showCacheMissNotices 的 5 处通知门（:3999/:4110/:4121/:4151/:4185）与 R24 同批收 |
| E2-50 `websocketConnectTimeoutMs` | **窄差 D58**，维持存疑 | pi 唯一消费者是 openai-codex-responses 的 WebSocket 传输（connect 握手超时，默认 15s、0 禁用）；java 无 WS 传输（codex 车道走 HTTP/SSE） |
| E2-22 `enableInstallTelemetry` | **窄差 D55**，维持存疑 | pi 两消费：changelog 版本推进后 ping `pi.dev/api/report-install`＋provider 归因头（B253，批 19）。ping 端点不移植（裁决⑤：pi.dev 是 pi 自有遥测端点，复刻不应污染）；归因头随 B253 |
| E2-54 `fullscreenCopyOnSelect` | **窄差 D59**，维持存疑 | 依赖 alt-screen 选区/复制子系统（map/05 S2a-d，未排期；含关闭臂 Ctrl+X 选区优先） |

---

## §5 窄差登记（接续 D54；台账格式【场景/决定/风险】随闭环入 docs/04）

| # | 内容 | 面 |
|---|---|---|
| D55 | install telemetry ping 不移植（裁决⑤）；`PI_OFFLINE`/`PI_TELEMETRY` 门与 provider 归因头随 B253 批 | 内部 |
| D56 | `terminal` 七键消费面无对位（能力覆盖/OSC 9;4/inline 图片/clearOnShrink）；可移植子集随 TUI 屏域拍板 | 用户可见（终端集成） |
| D57 | `markdown.mermaid` 无 grok-mermaid 对位（pi 渲染 Unicode 字符画；java 硬编码 panel 且 MarkdownRenderer 生产零调用）；`codeBlockIndent` 随 R1 激活批 | 用户可见 |
| D58 | `websocketConnectTimeoutMs` 无 WS 传输对位（java codex 车道 HTTP/SSE） | 内部 |
| D59 | `fullscreenCopyOnSelect` 依赖 alt-screen 选区子系统（S2a-d 未排期） | 用户可见 |
| D60 | `fullscreenWheelScrollLines` auto 臂＝既有 Codex 有界加速（非 pi 速度模型：mac/SSH 判定＋速率加速 ≤6＋Alt×5）；数字臂对齐。同 K38 | 用户可见（手感） |
| D61 | `fullscreenScrollbar` auto/always 同形常驻（pi auto＝滚动后短暂显示）；hidden 臂对齐 | 用户可见 |
| D62 | `outputPad` 改值不回溯已渲染条目（pi 遍历容器 duck-type 热更；java 新条目用新值） | 内部 |
| D63 | changelog 渲染为纯文本（pi Markdown 组件；java MarkdownRenderer 未激活，随 R1 批） | 用户可见 |
| D64 | resume-hint 文案按 java CLI 能力成形（pi 打印 `pi --session <id>` 精确命令；java 打印 `-r` 指引，`-r` 选择器随批 13） | 用户可见 |

---

## §6 裁决

1. **裁决①（E2 判定口径修订）**：E2 行的「非零消费者」**计入 TUI 模块消费
   点**（批 12 起）。理由：本批整体就是跨模块接线，若 TUI 消费不计，E2 中
   8 个 TUI 行为键永远无法翻行；map/04 行（settings 键）与 map/05 行（屏/
   组件单元）是不同单元，不构成重复计分。先例：E2-11 证据已引 TUI 消费点
   （`TreeNavigationFlow:169`）。map/04 E2 表头口径注记同步修订。
2. **裁决②（E2-37 核销）**：消费点批 9 已落地（§1.12），账面滞后翻行。
3. **裁决③（E2-23/24 核销＋口径先例）**：**pi 侧零消费 ⇒ 声明面即全量对
   齐**（pi 自己都没有的生效点不构成对齐缺口；此后同类键同口径）。
4. **裁决④**：`autocompleteMaxVisible` 默认 8⇒5（pi 对齐，行为变化，
   §0 注记 1）。
5. **裁决⑤**：install telemetry **ping 不移植**（pi.dev 为 pi 自有端点，
   复刻发送会污染其遥测；D55）。
6. **裁决⑥**：`fullscreenWheelScrollLines` 数字臂接 `ScrollConfig`
   （固定行数）；auto 臂维持既有 Codex 加速模型（D60/K38），不新造 pi
   速度模型加速器。
7. **裁决⑦**：changelog 数据为 java 自建内置资源（pi 同 `## [x.y.z]`
   格式）；内容天然各自维护（数据文件分叉非窄差）；渲染纯文本（D63）。
8. **裁决⑧**：`fullscreenScrollbar` auto/always 同形常驻（D61）——不为
   pi 的 transient 语义新造计时面；hidden 臂真实落地。
9. **裁决⑨（重排）**：cache-warmer 两键、markdown 激活、terminal 可移植
   子集移出本批——docs/20 §3 批 12 行按 §4 改述并插「cache-warmer 子系统
   批」行；markdown 激活计入拍板变量③（TUI 屏域）。

---

## §7 测试计划

| 任务 | 测试 | 断言要点 |
|---|---|---|
| T1 | 既有 `TreeNavigationFlowTest:579-592` | 已钉「初值读全局、切换不回写」，零新增 |
| T2 | `PiTuiAppDoubleEscapeTest`（新） | 空闲＋空编辑器＋500ms 内双击 ⇒ tree/fork overlay 开；>500ms ⇒ 不开；action=none ⇒ 不开；流式中 ⇒ abort 消费不计双击（lastEscapeAt 归零）；编辑器非空 ⇒ 不开；默认缺席 ⇒ tree；时间注入走 `nowMillis` |
| T3 | `ExternalEditorSupportTest`（新） | 回落链四臂（configured/blank ⇒ VISUAL ⇒ EDITOR ⇒ 平台默认，env 注入）；退出码 0 ⇒ Complete（BOM＋尾换行剥离）；非 0 ⇒ Failed 内容不动；`command.split(" ")` 简单拆parity（带空格路径不转义） |
| T4 | accessor clamp 测试＋`EditorComponentTest` | 0..3 clamp、缺席 0；pad=2 ⇒ 行首 `"  > "`、尾部留白 |
| T5 | accessor clamp 测试＋`SlashCompleterTest` | 3..20 clamp、缺席 5；max=3 ⇒ 渲染 ≤3 行＋`… N more` |
| T6 | accessor 测试＋`ChatMessageTest`/`ToolCallCardTest` | 仅显式 0 ⇒ 0 否则 1；pad=1 ⇒ 行 indent +1、内容宽 −2 |
| T7 | `ChangelogSupportTest`（新） | parse 分节（`## [x.y.z]`）；compareVersions 逐段；getNewEntries（lastVersion 非法段按 0）；forDisplay 三臂（有消息⇒empty／全新安装写版本不显示／有更新写回＋返回拼接）；/changelog 全量 reverse 且**不写**版本；collapse 臂一行文案逐字 |
| T9 | `TamboUIAdapterTest` | `createRunner(true)` ⇒ `hideCursor(false)`（TuiConfig 反射/构建器断言） |
| T10 | `PiTuiLauncherExitTest`（新） | 默认 ⇒ transcript 臂调 TranscriptDump；resume-hint ⇒ 不调；持久化会话 ⇒ 提示行打印；未持久化 ⇒ 不打 |
| T11 | `ChatViewportElementTest` | hidden ⇒ 无滚动条列、命中恒 false；always/auto ⇒ 现状（宽 1→2） |
| T12 | `ScrollConfigTest` | Integer ⇒ wheelLines 固定；"auto"/null ⇒ 既有默认不动 |
| T13 | `AnthropicExtraUsageWarningTest`（新） | warnings=false ⇒ empty；非 anthropic ⇒ empty；普通 key ⇒ empty；`sk-ant-oat*` ⇒ TEXT 逐字且仅一次；oauth 种类 ⇒ TEXT |
| T14 | `ModelThinkingLevelScreenTest`（新） | 两步状态机；clear 臂删 override＋当前模型回退全局默认；change 臂写 override＋当前模型即时 setThinkingLevel；非当前模型不动会话；flush 调用 |

收口：`mvn clean verify`（全量，~34 min；模块内迭代 `mvn test -pl
pi-java-tui,pi-java-coding-agent -am`——**必须带 -am**，批 4/7 撞过旧构件
假绿）。

---

## §8 对账表（E2 域 15 行翻动；判定列：前态→后态）

| # | 键 | 权重 | 前态 | 后态 | 任务 |
|---|---|---:|---|---|---|
| E2-37 | `treeFilterMode` | 1 | 存疑 0.5 | 对齐 1.0 | T1（核销，裁决②） |
| E2-36 | `doubleEscapeAction` | 1 | 存疑 0.5 | 对齐 1.0 | T2 |
| E2-15 | `externalEditor` | 1 | 存疑 0.5 | 对齐 1.0 | T3 |
| E2-39 | `editorPaddingX` | 1 | 存疑 0.5 | 对齐 1.0 | T4 |
| E2-41 | `autocompleteMaxVisible` | 1 | 存疑 0.5 | 对齐 1.0 | T5（裁决④） |
| E2-40 | `outputPad` | 1 | 存疑 0.5 | 对齐 1.0 | T6（D62） |
| E2-1 | `lastChangelogVersion` | 1 | 存疑 0.5 | 对齐 1.0 | T7 |
| E2-21 | `collapseChangelog` | 1 | 存疑 0.5 | 对齐 1.0 | T7（D63） |
| E2-23 | `enableAnalytics` | 1 | 存疑 0.5 | 对齐 1.0 | T8（核销，裁决③） |
| E2-24 | `trackingId` | 1 | 存疑 0.5 | 对齐 1.0 | T8（核销，裁决③） |
| E2-42 | `showHardwareCursor` | 1 | 存疑 0.5 | 对齐 1.0 | T9 |
| E2-52 | `fullscreenExitOutput` | 1 | 存疑 0.5 | 对齐 1.0 | T10（D64） |
| E2-53 | `fullscreenScrollbar` | 1 | 存疑 0.5 | 对齐 1.0 | T11（D61，裁决⑧） |
| E2-55 | `fullscreenWheelScrollLines` | 1 | 存疑 0.5 | 对齐 1.0 | T12（D60，裁决⑥） |
| E2-44 | `warnings` | 1 | 存疑 0.5 | 对齐 1.0 | T13 |

不动行（维持存疑，证据更新）：E2-32（D56）、E2-43（D57）、E2-14/E2-49
（cache 子系统批）、E2-50（D58）、E2-22（D55）、E2-54（D59）。

E2 域后态：对齐 29→**44**／缺失 2／存疑 23→**8**／出分母 1；
Σ(w×c) 67.5→**75.0**，加权 80.4%→**89.3%**（75.0/84）。

模块：303.5＋7.5＝**311.0/485 ≈ 64.1%**。全仓：977.5＋7.5＝**985.0/1379 ≈ 71.4%**。

结构债注记（闭环实测更新）：E6 `PiTuiApp` 预计 615→~675（T2/T3/T13/T14
接线）；新文件均 <500 行；`SettingsAccessors`/`SettingsUiPrefsAccessors`
 accessor 追加后行数闭环实测。

---

## §9 闭环回填

闭环时（按 docs/20 §4 三处）：

1. `docs/04-open-items-register.md`：批 12 条目（【场景/决定/风险】三段式）；
   窄差 D55–D64 入表；裁决③口径先例注记；B15-残留系无新增。
2. `docs/05-module-alignment-map.md`＋`docs/map/04-coding-agent.md`：E2 域
   15 行翻动＋7 行证据更新＋汇总行＋**E2 表头口径注记修订（裁决①）**；
   模块/全仓账面；map/05 注记更正（outputPad 的 setOutputPad 不在
   box.ts/text.ts——§1.5 取证更正）。
3. `docs/20-coding-agent-batch-plan.md`：§2 追加批 12 行；§1 四项读数重填；
   §3 批 12 行移除、插「cache-warmer 子系统批」行（E2-14/E2-49＋R24＋
   5 通知门）、批 13 行不变、拍板变量③补 markdown 激活（R1 联动 E2-43）；
   **下一包号 docs/24**。
4. 记忆：批 12 闭环节点（含下一包号与裁决③口径先例）。
