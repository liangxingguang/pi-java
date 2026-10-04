# 05 用户界面层（pi-java-tui / pi-java-web）

> 基准：pi-java @ 351d199（main，2026-10-04）；pi @ 200387122（2026-10-04）。
>
> 判定单位＝**可被外部观察到的行为承诺**（一个屏幕 / 一个键绑定 / 一个渲染特性 / 一个主题能力 / 一个 TUI 组件 / 一个 web 能力）。
> 三档：**对齐**＝pi 有、pi-java 有、行为相同；**缺失**＝pi 有、pi-java 无；**存疑**＝pi-java 有但行为/形状与 pi 不同或未核。
> 所有证据均为 file:line。pi 侧路径前缀 `D:\workplaceForai\pi\packages\`，java 侧 `D:\workplaceForai\pi-java\`。
>
> **⚠️ 换锚（2026-10-04 重测）**：pi 侧全部 `file:line` 与规模数字**已对 `200387122`（2026-10-04）重测**，
> 自 `3390bd936`（2026-09-20）起 pi 前进 **251** 个提交；其中 `7fd478a2e` 删掉 105,456 行，
> **`coding-agent/src/experimental/{micro,mini}/` 整块被删** ⇒ 旧图 **S31「实验性 micro TUI」按裁决 R1 作废**
> （见 [`../11-pi-reanchor-ruling.md`](../11-pi-reanchor-ruling.md) §2 R1-3）。逐条漂移复核见文末。

---

## 规模

| 模块 | pi 包 | pi LOC（旧 `3390bd936` → 新 `200387122`） | java LOC | 比例 |
|---|---|---|---|---|
| TUI 框架层 | `packages/tui/src`（42 → **45** 文件） | 18267 → **19277**（+1010） | —（对应物是**外部库 TamboUI**） | — |
| TUI 应用层 | `packages/coding-agent/src/modes/interactive`（54 → **60** TS 文件） | 19187 → **21626**（+2439） | — | — |
| 工具渲染器 | `coding-agent/src/core/tools/renderers`（8 文件） | 1057 → **1033**（−24，逻辑迁往 `core/tools/render-utils.ts` 112 行） | 0 | 0% |
| HTML 导出 | `coding-agent/src/core/export-html`（3 TS：`index`+`ansi-to-html`+`tool-renderer`） | 746 → **746** | 288+ | — |
| CLI 启动 UI | `coding-agent/src/cli`（startup-ui/session-picker/config-selector/list-models） | 477 → **484** | 见 coding-agent | — |
| ~~实验性 micro TUI~~（**已删**） | ~~`coding-agent/src/experimental/micro`（7 文件 1485 行）~~ | 1485 → **0** | — | — |
| **UI 合计（pi，剔 micro）** | | 39734 → **43166**（+3432，**+8.6%**） | — | — |
| **pi-java-tui（main）** | | 43166 | **6707**（47 文件，旧 6712） | **15.5%** |
| pi-java-tui（test） | pi `tui/test` 38 → **54 文件** / 19097 → **20101 行** | — | **4240**（34 文件） | — |
| pi-java-web（java main） | **pi 仓库内无对应物** | 0 | **1862**（9 文件） | — |
| pi-java-web（前端 TS） | **pi 仓库内无对应物** | 0 | **1654**（8 文件）+ `vite.config.ts` 26 | — |
| pi-java-web（前端 CSS） | 同上 | 0 | **675**（app.css） | — |

**口径说明（重要）**：

1. pi 的 `packages/tui` 是一个**自带组件库的通用终端框架**；pi-java 的对应物是**外部库 TamboUI**（`dev.tamboui.*`，见 `pi-java-tui/src/main/java/dev/tamboui/tui/event/EventParser.java:20-29` 的 same-package 覆写注释）。因此含框架层的 LOC 口径**低估了 pi-java**：真正的同层比较应把 pi 的 `tui` 框架层 19277 行算作 TamboUI（外部），只剩应用层 21626 行可比。
2. 按**应用层口径**：6707 / 21626 = **31.0%**（旧 35.0%）。⚠️ **本轮又降 4.0pp 的成因是 pi 应用层 +2439 行而 pi-java 未动**（不是 pi-java 退步）；且**能力单元口径更低**（见加权汇总）—— 缺失集中在整块子系统（图片协议、LaTeX、搜索、扩展 UI、主题系统、工具渲染器），而不是零散小功能。
3. **测试量差距**：pi `packages/tui/test` **20101 行 / 54 个测试文件**（旧 19097/38）vs pi-java-tui **4240 行 / 34 个测试文件**（**4.7×**）。pi 的 `coding-agent/test` 现 **353** 个文件（旧 196），其中约 **107 个**直接针对 UI（旧 48/54；本轮新增 `codemode-renderer` / `easter-egg` / `clipboard-paste-file-paths` / `10285-mcp-tool-renderers` / `4167-thinking-toggle-pending-tool-render` / `footer-data-provider` 等），pi-java-tui 对应面仍为 **0**。
4. **删除面（裁决 R1 适用 —— 本轮 1 条）**：`coding-agent/src/experimental/{micro,mini}/`（D4）；`components/daxnuts.ts`（D6，被 3D 彩蛋取代）。逐条见文末漂移复核。

---

## 屏幕/面板对齐

| # | 屏幕 | pi 有 | java 有 | 判定 | 权重 | 证据（pi 侧已对 `200387122` 重测） |
|---|---|---|---|---|---|---|
| S1 | 主聊天屏（transcript 视口 + 输入行 + 状态行） | ✓ | ✓ | **对齐** | 3 | pi `interactive-mode.ts:3926 renderSessionItems` / `:4140 renderInitialMessages`；java `ChatScreen.java:189 render()`、`PiTuiApp.java:187 root()` |
| S2 | 全屏 alt-screen 模式（**本轮起为默认模式**） | ✓ `tui/src/tui-alt-screen.ts:202 class TuiAltScreen`（1778 行） | ✓ `PiTuiLauncher.java:61 runFullscreen`（TamboUI ToolkitRunner） | **存疑** | 2 | java 用 TamboUI 通用 runner，pi 的 alt-screen 自带搜索/选择/复制/图像/闪烁（见 S2a-e、K20/K21）；滚动条由 java 自绘（`ChatViewportElement.java:261 renderScrollbar`）。**默认模式两侧现在一致**（pi `cli/args.ts:329` "fullscreen (default)"；java `PiTuiLauncher.java:111` 默认 fullscreen） |
| S2a | 全屏：转录搜索（`/` 查询、上一个/下一个匹配） | ✓ `tui/src/alt-screen-search.ts:156-197`（327 行） | ✗ | **缺失** | 1 | java 全树无 search 命中（`grep -rni "search" pi-java-tui/src/main` 只命中 `FuzzyMatcher.java:18` 注释） |
| S2b | 全屏：鼠标文本选择 + 自动滚动 + copy-on-select | ✓ `tui-alt-screen.ts:305 hasActiveSelection`、`:198 copySelection` | ✗ | **缺失** | 1 | 同上 grep：java 无 selection/copy 命中 |
| S2c | 全屏：flash 提示（短暂状态行） | ✓ `tui-alt-screen.ts:657 flash()` + `components/alt-screen-flash.ts:1`（51 行） | ✗ | **缺失** | 1 | — |
| S2d | 全屏：图像渲染（Kitty/iTerm2） | ✓ `tui-alt-screen.ts:352 setCapabilities`（本轮加 WezTerm 图像保留修复 + 非 PNG 转码） | ✗ | **缺失** | 1 | 见 R14 |
| S3 | regular（非备用屏）inline 模式 | ✓ `tui/src/tui-main-screen.ts`（655 行） | ✓ `InlineTuiShell.java:1`（390 行） | **对齐** | 1 | 两者都是「终端原生 scrollback + 底部固定输入区」；**两侧均为非默认**（`--tui-mode regular`，pi `args.ts:329`，java `PiTuiLauncher.java:111`） |
| S4 | 启动欢迎卡（本轮 pi 起**去掉 themes 段**） | ✓ `interactive-mode.ts:1760 showLoadedResources` | ✓ `WelcomeOverlay.java:41 buildCard` | **对齐** | 2 | 形状不同（pi 列 context/skills/prompts/extensions，本轮起**不再列 themes**；java 是 Codex 风格方框），但承诺相同：启动即告知模型/目录/版本 |
| S5 | 模型选择器（列表 / 上下选 / 确认） | ✓ `components/model-selector.ts:40`（421 行） | ✓ `ModelSelectorScreen.java:17`（58 行） | **对齐** | 2 | java：`ModelsJsonConfig.allModels()` 平铺排序 + `SelectList` |
| S6 | 模型选择器：搜索过滤 | ✓ `model-search.ts:7,17` + `model-selector.ts` | ✗ | **缺失** | 1 | java 无过滤输入 |
| S7 | scoped-models 选择器（Ctrl+P 循环集） | ✓ `components/scoped-models-selector.ts:97`（401 行） | ✗ | **缺失** | 1 | `PiTuiApp.java:414-415` 直接 `appendSystemText("Use /scoped-models +<model> …")` |
| S8 | thinking level 选择器 | ✓ `components/thinking-selector.ts:36`（154 行） | ✗ | **缺失** | 2 | java 只在 `SettingsScreen.java:36` 里循环 6 个值 |
| S9 | theme 选择器 | ✓ `components/theme-selector.ts:13`（67 行） | ✗ | **缺失** | 1 | java 只在 `SettingsScreen.java:30` 里循环 `dark/light` |
| S10 | 会话选择器（列表 / 选 / 确认） | ✓ `session-selector.ts:694 class SessionSelectorComponent`（1045 行） | ✓ `SessionListScreen.java:16`（50 行） | **对齐** | 2 | java：`session.listSessions()` + 前 8 位 id |
| S11 | 会话选择器：搜索 / 排序 / 命名过滤 / 路径切换 / 重命名 / 删除 / 进度 | ✓ `session-selector.ts:76-116`（setScope/setSortMode/setNameFilter/setShowPath…）、`session-selector-search.ts`、`user-message-selector.ts`；`onProgress` 带 `partialSessions` 增量渲染 + `AbortController` 取消 | ✗ | **缺失** | 1 | java 全部无（`SessionListScreen.java` 全文 50 行无一处） |
| S12 | 会话树选择器（分支列表） | ✓ `components/tree-selector.ts:1336 class TreeSelectorComponent`（~1400 行） | ✓ `TreeSelectorScreen.java:16`（63 行） | **存疑** | 2 | java 列的是 **lane 名 + leafId**（`TreeSelectorScreen.java:26-27`），不是 pi 的 entry 树；选中＝从该 entry fork（`:55`） |
| S13 | 树选择器：折叠展开 / 标签编辑 / 过滤模式 / 搜索 / 复制 | ✓ `tree-selector.ts:112 filterMode`（5 种）、`:632 copySelected`、`:637 updateNodeLabel`、`:1004 handleInput` | ✗ | **缺失** | 1 | — |
| S14 | 设置页（字段列表 + 循环取值） | ✓ `settings-selector.ts:463 class SettingsSelectorComponent`（960 行） | ✓ `SettingsScreen.java:21`（153 行） | **对齐** | 2 | java `SettingsScreen.java:30-47`（pi 侧字段本轮增 `tuiMode`/`fullscreenExitOutput`/`fullscreenScrollbar`/`fullscreenWheelScrollLines` 等） |
| S15 | 设置页：子菜单 / 主题页 / 警告页 / 实验项 | ✓ `settings-submenu.ts:31 SelectSubmenu` + `:178 SteppedSubmenu` | ✗ | **缺失** | 1 | java 是单一平铺列表 |
| S16 | config 选择器（`/config`） | ✓ `components/config-selector.ts:875`（~940 行） | ✗ | **缺失** | 1 | java 无 config 命令 |
| S17 | OAuth / 登录对话框 | ✓ `login-dialog.ts:12` + `oauth-selector.ts:58` | ✗ | **缺失** | 2 | java 的 `/login` 是 `System.console().readPassword`（`MiscCommands.java:134-148`），无对话框 |
| S18 | 首次设置向导 | ✓ `components/first-time-setup.ts:33` | ✗ | **缺失** | 1 | 一次性路径 |
| S19 | 项目信任选择器 | ✓ `components/trust-selector.ts:32` | ✗ | **缺失** | 2 | java 只有 `SettingsScreen.java:40` 的 `Project trust` 循环 |
| S20 | 排队消息选择器（Alt+Up 取回并编辑） | ✓ `components/user-message-selector.ts:110`（155 行） | ✗ | **缺失** | 1 | java 的 `DEQUEUE` 常量存在（`KeybindingsManager.java:29`）但未接线（`PiTuiApp.java:437` default 分支） |
| S21 | show-images 选择器 | ✓ `components/show-images-selector.ts:13`（50 行） | ✗ | **缺失** | 1 | — |
| S22 | 扩展 UI：选择器 / 输入框 / 确认框 / 编辑器 | ✓ `extension-selector.ts:19`、`extension-input.ts:18`、`extension-editor.ts:26`、`interactive-mode.ts:2654 showExtensionSelector`/`:2730 showExtensionInput`/`:2786 showExtensionEditor` | ✗ | **缺失** | 1 | java 只有 RPC 通道 `ExtensionUI.java:16`（`AgentSession.java:98` 默认 `noop()`；两者在 `pi-java-coding-agent`），TUI 无实现 |
| S23 | 扩展 widget / footer / header / 状态槽 | ✓ `interactive-mode.ts:2362 setExtensionWidget`、`:2488 setExtensionFooter`、`:2515 setExtensionHeader` | ✗ | **缺失** | 1 | — |
| S24 | `/hotkeys` 与 `/help` 面板 | ✓ `interactive-mode.ts` + `core/slash-commands.ts:33 hotkeys`（Markdown 渲染） | ✓ `MiscCommands.java:34`、`:122` | **对齐** | 1 | 都是往聊天区追加纯文本；pi 走 Markdown 组件 |
| S25 | 会话统计面（`/session`） | ✓ `core/slash-commands.ts:31 session` + `components/footer.ts:120-137` 用量（计入 `usage` 条目） | ✗（TUI） / ✓（web `panels/stats.ts:1`） | **缺失** | 2 | java TUI 无统计屏 |
| S26 | `/changelog` 面板 | ✓ `core/slash-commands.ts:32` | ✓ `MiscCommands.java:120` | **对齐** | 1 | 均为文本输出 |
| S27 | `/share`（会话分享） | ✓ `session-share.ts:217`（`shareSession:57`；Radius 网关 + `BorderedLoader`） | ✓ `MiscCommands.java:76`（GitHub gist） | **存疑** | 1 | 承诺相同、**后端不同**（pi=Radius，java=gist），且 java 无加载指示器 |
| S28 | `/export`（HTML 导出） | ✓ `core/export-html/index.ts:236 exportSessionToHtml` | ✓ `export/HtmlExporter.java:21`（288 行） | **存疑** | 1 | pi 用 vendored marked.min.js + highlight.min.js + `template.html/css/js`；java 自写 `MarkdownToHtml.java`（201 行）—— 输出形状未逐项核 |
| S29 | `/bug` 故障上报流程（同意提示 → 可选摘要 → 上传或导出 zip） | ✓ `bug-report.ts:50 reportBug`（298 行）+ `core/bug-report.ts`（375 行）+ `utils/zip.ts`；命令注册 `core/slash-commands.ts:28` | ✗ | **缺失** | 1 | pi slash 命令 **24 → 26**（本轮加 `bug` 于 :28）；java 的 slash 注册表无 `bug` |
| S30 | **崩溃记录 + 下次启动提示**（写 crash log，下次启动提示「Run /bug」；`/bug` 提示每会话最多一次） | ✓ `core/crash-log.ts`；`interactive-mode.ts` 的 `takeUnnotifiedCrash`/`recordCrash`/`suggestBugReport` | ✗ | **缺失** | 1 | java 全仓无 crash-log 命中 |
| S32 | **登录 URL 复制 + Radius 登录入口**（`/login` 展示 URL，`app.message.copy` 复制整条 URL；提供 Radius 登录/MCP 设置） | ✓ **本轮新增** `components/auth-url.ts:9 class AuthUrlComponent`（39 行，`:copy()` 走 `app.message.copy`）+ `components/radius-login-selector.ts`（114 行）+ `login-dialog.ts`（238 行）；`ced72c2f0` / `ed8b3bcc1` | ✗ | **缺失** | 1 | java `/login` 只 `System.console().readPassword`（`MiscCommands.java:134-148`），无 URL 展示/复制/Radius |

> ~~S31 实验性 micro TUI~~ —— **本轮删除**（pi `7fd478a2e` 删 `experimental/{micro,mini}`，裁决 R1-3 作废）。

---

## 渲染特性对齐

| # | 特性 | pi 有 | java 有 | 判定 | 权重 | 证据（pi 侧已对 `200387122` 重测） |
|---|---|---|---|---|---|---|
| R1 | Markdown 渲染（标题/粗斜体/列表/引用/代码块） | ✓ `tui/src/components/markdown.ts:236 class Markdown`（1025 行） | ✗（**死代码**） | **缺失** | 3 | `MarkdownRenderer.java:21` 存在但**生产零调用点** —— 全仓 grep 只命中自身与 `MarkdownRendererTest.java`；助手消息实际走 `MessageBubble.java:78` 的 `TextLayout.split(escapeMarkup(text))`（纯文本） |
| R2 | Markdown：管道表格 | ✓ markdown.ts token 渲染 | ✗（死代码） | **缺失** | 1 | `MarkdownRenderer.java:57 renderTable` 无调用者 |
| R3 | Markdown：删除线（严格 tokenizer） | ✓ `markdown.ts:9 StrictStrikethroughTokenizer` | ✗ | **缺失** | 1 | — |
| R4 | Markdown：链接（OSC 8 超链接） | ✓ `markdown.ts` + `tui/src/terminal.ts` hyperlinks | ✗ | **缺失** | 1 | java `MarkdownRenderer` 无链接分支（`Block` 只有 7 种，无 Link） |
| R5 | Markdown：流式未闭合围栏修剪 | ✓ `markdown.ts:146 trimPartialClosingFences` | ✗ | **缺失** | 2 | 仅当流式期间有未闭合围栏时可见 |
| R6 | Markdown：扩展 transformer 钩子 | ✓ `markdown-transform.ts` + `interactive-mode.ts:2198 getMarkdownTransformers` | ✗ | **缺失** | 1 | 扩展系统整体未落地 |
| R7 | Mermaid 图渲染 | ✓ `components/mermaid.ts:60 createMermaidMarkdownTransformer` + `grok-mermaid` 依赖 | ✗ | **缺失** | 1 | java `MarkdownRenderer.java:39-42` 只画 `"[mermaid diagram]\n"+code` 占位框 |
| R8 | LaTeX 数学（行内 `$…$` 与块级 `$$…$$`） | ✓ `tui/src/latex.ts`（**1506 行**）+ `test/latex.test.ts` | ✗ | **缺失** | 1 | java 全仓无 latex 命中 |
| R9 | 代码块语法高亮（多语言、highlight.js 词法） | ✓ `utils/syntax-highlight.ts`（214 行）+ `loadAllHighlightLanguages`（`interactive-mode.ts:132` import、`:1110` 调用）、theme 里 10 个 `syntax*` token | ✗（**死代码**） | **缺失** | 2 | java `SyntaxHighlighter.java:16` 只有 3 色 + 40 个硬编码关键字；调用点只有 `EditorElement.java:123`（输入编辑器当前行），**代码块路径 `MarkdownRenderer.java:98` 无人调** |
| R10 | Diff 渲染（+/−/hunk 着色） | ✓ `components/diff.ts:79 renderDiff`（147 行）+ `toolDiffAdded/Removed/Context` token | ✓ `DiffView.java:18`（64 行） | **存疑** | 3 | java 按行首字符给色（`DiffView.java:52-63`），无 hunk 头/行号/词级 diff；pi 的 diff 走 `tool-execution.ts` 的富渲染 |
| R11 | 逐工具渲染器（read/write/edit/bash/grep/find/ls 各自形状） | ✓ `core/tools/renderers/*.ts`（**1033 行 / 8 文件**；逻辑拆至 `core/tools/render-utils.ts` 112 行） | ✗ | **缺失** | 3 | java 只有一张通用 `ToolCallCard.java:13`（39 行：`name`+`status`+截断 200 字的 args） |
| R12 | 工具执行卡：可折叠 + `Ctrl+O` 展开 + `… (N more lines, ctrl+o to expand)` | ✓ `tool-execution.ts:46 expanded`、`:151` 折叠提示、`:194 setExpanded`（393 行） | ✗ | **缺失** | 2 | java 无折叠态，`TOOLS_EXPAND` 未接线（`PiTuiApp.java:437`） |
| R13 | bash 执行组件（实时增量输出 + 宽度自适应） | ✓ `components/bash-execution.ts:21`（220 行）+ `test/bash-execution-width.test.ts` | ✗ | **缺失** | 2 | java TUI 无 bash 屏；web 侧有 `bashOutput` 帧（`AgentEventTranslator.java:130`） |
| R14 | 图片渲染（Kitty / iTerm2 图像协议 + 单元格尺寸探测 + 降级） | ✓ `terminal-image.ts`（**757 行**）+ `components/image.ts:55 class Image` + 测试（本轮加 **WezTerm 滚动图像保留** + **非 PNG→PNG 转码** + **按纵横比选 Kitty 尺寸**） | ✗ | **缺失** | 1 | java 只有 `[image: mimeType]` 占位文本（`MessageBubble.java:102`） |
| R15 | 图片粘贴（剪贴板 → 附件） | ✓ `app.clipboard.pasteImage`（`core/keybindings.ts:142`）+ `utils/clipboard-image.ts` + 测试 | ✗ | **缺失** | 1 | java 全仓无 clipboard-image |
| R16 | 元数据气泡分型（模型切换/思考等级/工具集/压缩/分支/自定义，各带图标+色） | ✓ `interactive-mode.ts:3817 addMessageToChat` 各 message 组件 | ✓ `MetaKind.java:15-29`（6 型 enum） | **对齐** | 2 | 图标/颜色集不同（java 自选 emoji + hex），**承诺相同** |
| R17 | 压缩摘要消息卡 | ✓ `compaction-summary-message.ts:10`（68 行；鼠标左键点击展开/折叠） | ✓（弱形状） | **存疑** | 2 | java 走 `ChatMessage.java:50` 的 `System("Compacted context: …", COMPACTION)` 一行；无独立卡、无 `usage` 开销提示（`interactive-mode.ts:4053 addCompactionCostNotice`）、无点击展开 |
| R18 | 分支摘要消息卡 | ✓ `branch-summary-message.ts:10`（67 行；点击展开） | ✗ | **缺失** | 1 | 依赖 B1（branch summary 功能本体缺失） |
| R19 | skill 调用消息卡 | ✓ `skill-invocation-message.ts:11`（64 行；点击展开） | ✗ | **缺失** | 1 | java `ChatMessage.from` 无对应分支 |
| R20 | 自定义消息卡（`custom_message`，`display` 门） | ✓ `custom-message.ts:12`（113 行） | ✓ `ChatMessage.java:56-58` | **对齐** | 1 | java 用 `[customType] plainText`，pi 用 Markdown 渲染 `content` |
| R21 | 回合分隔条（`Worked for Ns • Local tools: N calls`） | ✗（pi 无此构造） | ✓ `ChatScreen.java:167-176` | **非对齐项**（java 独有，Codex 风格） | 0 | 见「pi-java 独有」，**排除出分母** |
| R22 | 启动公告组件（`earendil-announcement`、`armin`） | ✓ `components/earendil-announcement.ts:27`、`armin.ts:62 class ArminComponent`（384 行） | ✗ | **缺失** | 1 | ⚠️ 旧的 `daxnuts.ts` **已删**（`1c1e9c0ef`），彩蛋迁到 R27 |
| R23 | 窗口/终端标题更新 | ✓ `interactive-mode.ts:1120 updateTerminalTitle` | ✗ | **缺失** | 1 | — |
| R24 | 缓存预热用量行（聊天区 `formatCacheWarmingUsage` + `/session` 里的 `formatCacheWarmingStatus`／missCost／warmCost；footer 计入 `usage` 条目） | ✓ `core/cache-warmer.ts:449 formatCacheWarmingUsage`/`:433 formatCacheWarmingStatus`；`interactive-mode.ts:4042 addCacheWarmingUsage` | ✗ | **缺失** | 1 | 门：`settingsManager.getShowCacheMissNotices()`；java 全仓无 cache-warmer |
| R25 | thinking 块被丢弃提示（`Anthropic dropped N thinking blocks …`） | ✓ `interactive-mode.ts:4066 countDroppedThinkingBlocks`、`:4082 maybeShowThinkingDropNotice` | ✗ | **缺失** | 1 | 只在与上一条 assistant 消息的丢弃数**增加**时显示；java 无此构造 |
| R26 | **工具调用参数渲染（MCP 与 fallback 渲染工具显式展示 args）** | ✓ **本轮新增** `core/tools/render-utils.ts:78 formatToolCallWithArgs` + `tool-execution.ts:137`；`5257d0d5f` / `11449730c` | ✗ | **缺失** | 1 | java `ToolCallCard.java:13-37` 对全部工具走同一「name+status+截断 args」，且无 MCP 渲染器 |
| R27 | **3D 彩蛋（pi logo / Armin）+ header logo**（全屏 overlay，惰性加载） | ✓ **本轮新增** `components/easter-egg-3d.ts`（**1292 行**）+ `easter-egg-3d.lazy.ts` + `pi-logo.ts:15 piLogoLines`（40 行）；`c450f2c0f` / `7fbbd5f4a` / `b35af04f4` | ✗ | **缺失** | 1 | java 无任何彩蛋/logo 动画；取代旧的 daxnuts（R22） |

---

## 能力单元清单（其余）

### 键绑定 / 交互

| # | 能力单元 | 类别 | pi 侧证据 | pi-java 侧证据 | 判定 | 权重 | 台账条目 |
|---|---|---|---|---|---|---|---|
| K1 | `Esc` 中断当前运行 | 键绑定 | `core/keybindings.ts:93 app.interrupt` | `PiTuiApp.java:422`、`KeybindingsManager.java:19` | **对齐** | 3 | — |
| K2 | `Ctrl+C` 清空输入 | 键绑定 | `keybindings.ts:94 app.clear` | `PiTuiApp.java:429` | **对齐** | 2 | — |
| K3 | `Ctrl+D` 空输入时退出 | 键绑定 | `keybindings.ts:95 app.exit` | `PiTuiApp.java:430-434` | **对齐** | 2 | — |
| K4 | `Ctrl+L` 打开模型选择器 | 键绑定 | `keybindings.ts:116 app.model.select` | `PiTuiApp.java:435` | **对齐** | 2 | — |
| K5 | `Alt+Enter` 排队 follow-up | 键绑定 | `keybindings.ts:134-137`：**Windows 上是 `ctrl+q`**，`alt+enter` 归 `tui.input.newLine` | `KeybindingsManager.java:28` 绑 `alt+enter`；`TamboUIAdapter.isNewlineEnter` 走 `shift+enter` | **存疑** | 2 | H-9.2-② |
| K6 | `Ctrl+P` 模型循环（前/后） | 键绑定 | `keybindings.ts:108-115 app.model.cycleForward/Backward` | 常量存在（`KeybindingsManager.java:22`）但 `PiTuiApp.java:437` **default 分支＝死码** | **缺失** | 2 | — |
| K7 | `Shift+Tab` 循环 thinking level | 键绑定 | `keybindings.ts:100 app.thinking.cycle` | 同上（`KeybindingsManager.java:24` → 死码） | **缺失** | 2 | — |
| K8 | `Ctrl+O` 展开/折叠工具输出 | 键绑定 | `keybindings.ts:117 app.tools.expand` | 同上（`:25` → 死码），且无折叠态（R12） | **缺失** | 2 | — |
| K9 | `Ctrl+T` 显隐 thinking 块 | 键绑定 | `keybindings.ts:118 app.thinking.toggle` | 同上（`:26` → 死码） | **缺失** | 2 | — |
| K10 | `Ctrl+G` 外部编辑器 | 键绑定 | `keybindings.ts:126 app.editor.external` + `external-editor.ts:46` | 同上（`:27` → 死码） | **缺失** | 1 | — |
| K11 | `Alt+Up` 取回排队消息 | 键绑定 | `keybindings.ts:138 app.message.dequeue` | 同上（`:29` → 死码） | **缺失** | 1 | — |
| K12 | `Ctrl+Z` 挂起进程 | 键绑定 | `keybindings.ts:96-99 app.suspend`（非 Windows） | ✗ | **缺失** | 1 | — |
| K13 | `Ctrl+X` 复制消息到剪贴板 | 键绑定 | `keybindings.ts:130 app.message.copy`（描述本轮改「Copy selection or last assistant message」） | ✗（有 `/copy` 命令 `MiscCommands.java:95-96`，无键位） | **缺失** | 1 | — |
| K14 | `Ctrl+V`/`Alt+V` 粘贴图片 | 键绑定 | `keybindings.ts:142 app.clipboard.pasteImage`（本轮加：粘贴 Finder 文件路径而非图标） | ✗ | **缺失** | 1 | — |
| K15 | `Ctrl+N` 命名会话过滤 | 键绑定 | `keybindings.ts:122 app.session.toggleNamedFilter` | ✗ | **缺失** | 1 | — |
| K16 | `app.session.*` 家族（new/tree/fork/resume + 树/会话选择器内键位） | 键绑定 | `core/keybindings.ts:32-44,146-186` | ✗ | **缺失** | 2 | — |
| K17 | 用户键位覆盖（`keybindings.json`） | 配置 | `core/keybindings.ts` + `test/keybindings-migration.test.ts` | ✗（`KeybindingsManager.java:14` 注释「User overrides from keybindings.json → Phase 6」） | **缺失** | 1 | H-9.2-⑩ |
| K18 | 键位提示渲染（`keyText`/`keyHint`/`rawKeyHint`） | 组件 | `components/keybinding-hints.ts:48` | `KeybindingHints.java:19`（只有 `keyText:29`，无 `keyHint`/`rawKeyHint`） | **存疑** | 2 | — |
| K19 | 滚轮/触控板归一化（事件流 → 行滚动） | 交互 | `tui/src/tui-alt-screen.ts:483 scrollBy` + `tui/src/wheel-scroll.ts` | `ScrollInputNormalizer.java:20`（274 行，Codex TUI2 PR#8357 移植）+ `ScrollConfig.java:35` | **存疑** | 3 | — |
| K20 | 键盘滚动（空编辑器时 ↑↓/PgUp/PgDn/Home/End 驱动转录） | 交互 | `tui/src/keybindings.ts:45-58` 的 `tui.altScreen.*` 键位 | `PiTuiApp.java:340-361 isChatNavigation`/`scrollByKey` | **对齐** | 2 | — |
| K21 | 转录搜索 | 交互 | `tui/src/alt-screen-search.ts:156-197` | ✗ | **缺失** | 1 | — |
| K22 | 鼠标文本选择 / copy-on-select | 交互 | `tui-alt-screen.ts:305 hasActiveSelection`（失败时具体错误文案 + 5 s flash） | ✗ | **缺失** | 1 | — |
| K23 | 滚动条（绘制 + 拖拽 + 悬停加宽） | 交互 | `tui-alt-screen` scrollbar + `test/scrollbar-theme.test.ts` | `ChatViewportElement.java:129-190`、`:224-283` | **对齐** | 3 | — |
| K24 | 斜杠命令补全弹层 | 交互 | `autocomplete.ts:303 CombinedAutocompleteProvider`（835 行；本轮加：前导空白后仍补全） | `SlashCompleter.java:17`（146 行，前缀匹配） | **对齐** | 2 | — |
| K25 | `@文件` 路径补全（fuzzy + `fd`） | 交互 | `autocomplete.ts:421-477`（file 分支）、`:737 scoreEntry`；CJK 标点作分隔符（`tui/src/utils.ts:58-63`）、`skill:` 按裸名模糊排序、wrapper 后补路径 | ✗ | **缺失** | 2 | — |
| K26 | 大粘贴折叠为 `[paste #N +L lines]` | 交互 | `tui/components/editor.ts` + `test/editor.test.ts` | ✗ | **缺失** | 1 | — |
| K27 | 提示历史 ↑/↓ 导航（含 draft 还原、100 条上限） | 交互 | `editor.ts:344-347 history/historyIndex`、`:427 addToHistory` | ✗（↑/↓ 是 `moveCursorUp/Down`，`EditorComponent.java:145-146`） | **缺失** | 3 | H-9.2-② |
| K28 | undo（fish 式连续字符合并） | 交互 | `editor.ts:367 undoStack` + `undo-stack.ts:28` | `EditorComponent.java:312-328` + `EditorComponentUndoKillRingTest` | **对齐** | 3 | — |
| K29 | kill ring / yank / yank-pop | 交互 | `editor.ts:337-338` + `kill-ring.ts:46` | `KillRing.java:1` + `EditorComponent.java:287-311` | **对齐** | 2 | — |
| K30 | 词导航（`Ctrl+←/→`、`Alt+B/F`） | 交互 | `word-navigation.ts:117` | `EditorWordNav.java:1`（199 行） | **对齐** | 2 | — |
| K31 | jump mode（`Ctrl+]` / `Ctrl+Alt+]` 跳转到字符） | 交互 | `tui/keybindings.ts:19-20 tui.editor.jumpForward/Backward` | ✗ | **缺失** | 1 | — |
| K32 | Kitty 键盘协议 / `modifyOtherKeys` 解析 | 协议 | `tui/src/keys.ts:705`（`CSI 27 ; m ; k ~`）、`:1252`（kitty 序列分派），1401 行 | `EventParser.java:224,309 parseCsiU`（CSI-u 有；`modifyOtherKeys` 无） | **存疑** | 3 | — |
| K33 | bracketed paste 解析 | 协议 | `tui/editor.ts:328-329` | `EventParser.java:230 readPasteContent` | **对齐** | 2 | — |
| K34 | 原生剪贴板 helper（macOS/Windows/Linux） | 平台 | `native-platform.ts:1-63` + `native-module-path.ts:31` + 3 个 native 测试 | ✗（只有 `java.awt.Toolkit` 兜底，`MiscCommands.java:108`） | **缺失** | 1 | — |
| K35 | 终端能力探测（trueColor / hyperlinks / images / tmux 转发） | 平台 | `terminal-image.ts:140 detectCapabilities` + `terminal-colors.ts:73` | ✗ | **缺失** | 1 | — |
| K36 | 模式 2027 握手规避（ConPTY 字节泄漏修复） | 平台 | —（pi 无此构造） | `NoMode2027Backend.java:20` + `NoMode2027JLineBackend.java:1` | **非对齐项**（java 独有） | 0 | **排除出分母** |
| K37 | 消息卡鼠标左键点击展开/折叠（压缩摘要 / 分支摘要 / skill 调用三卡） | 交互 | ✓ `MouseRegion` 包装 + `setExpanded(!expanded)`：`compaction-summary-message.ts:10`、`branch-summary-message.ts:10`、`skill-invocation-message.ts:11` | ✗ | **缺失** | 1 | pi 的 `MouseRegion` 组件本身 pi-java 也没有（C14 缺失）⇒ 该交互在 java 侧无基座 |
| K38 | **全屏滚轮加速 + 行数配置**（按事件间隔速度自适应 1→6 行；`fullscreenWheelScrollLines` 设 1–100/auto） | 交互 | ✓ **本轮新增** `tui/src/wheel-scroll.ts:38 class WheelScrollAccelerator`（82 行；`f1927c2d5`，接入 `tui-alt-screen.ts:271,:699`）+ `settings-manager.ts:188 fullscreenWheelScrollLines` | 部分：`ScrollInputNormalizer.java:14` 有**有界加速**（Codex 实测模型：`ScrollConfig.java:28-36 wheelLines/trackpadAccel*`），但**无用户设置项** | **存疑** | 1 | pi 按速度模型 + 可配；java 用 Codex 固定默认 ⇒ 都有加速、模型与配置面不同 |

### TUI 组件

| # | 能力单元 | 类别 | pi 侧证据 | pi-java 侧证据 | 判定 | 权重 | 台账条目 |
|---|---|---|---|---|---|---|---|
| C1 | 滚动视图（行级 offset + follow 语义 + resize 重排） | 组件 | `tui/components/scroll-view.ts:224` + `chat-viewport.ts:46` | `ChatViewportElement.java:35`（300 行，`ScrollState` 内嵌） | **对齐** | 3 | — |
| C2 | 选择列表（上下选 + 确认/取消） | 组件 | `tui/components/select-list.ts:273` | `SelectList.java:1`（145 行） | **存疑** | 2 | pi 有分组/模糊/多选；java 只有单选线性列表 |
| C3 | 设置列表（bool/enum/string/number 多类型编辑） | 组件 | `tui/components/settings-list.ts:328` | ✗（`SettingsScreen` 用裸 Element 拼行） | **缺失** | 2 | — |
| C4 | 输入框（单行，`components/input.ts`） | 组件 | `tui/components/input.ts:494` | ✗（统一走 `TextAreaState`） | **缺失** | 1 | — |
| C5 | loader / spinner（braille 10 帧动画 + 文案 + 颜色函数） | 组件 | `tui/components/loader.ts:13-60`（101 行） | ✗（`StatusBar.java:27-31` 是静态 `● running`） | **缺失** | 3 | — |
| C6 | working 指示器（spinner + 文案 + 计时） | 组件 | `interactive-mode.ts:2310 showWorkingStatusIndicator`、`:2325 setWorkingVisible` + `status-indicator.ts` `WorkingStatusIndicator` | ✗ | **缺失** | 3 | — |
| C7 | bordered loader（可取消的加载框） | 组件 | `components/bordered-loader.ts:68` | ✗ | **缺失** | 1 | — |
| C8 | cancellable loader | 组件 | `tui/components/cancellable-loader.ts:40` | ✗ | **缺失** | 1 | — |
| C9 | 倒计时定时器组件 | 组件 | `components/countdown-timer.ts:39` | `StatusIndicator.java:61-68` + `CountdownWake.java:1`（按帧重算，等价） | **对齐** | 2 | B3（已结案） |
| C10 | 状态指示器槽（单槽 + kind 守卫 + retry/compaction/branch 文案） | 组件 | `interactive-mode.ts:2276 showStatusIndicator` + `:2289 clearStatusIndicator` + `status-indicator.ts` | `StatusIndicator.java:18`、`ChatScreen.java:239-296` | **对齐** | 2 | B3（已结案） |
| C11 | 盒/边框面板 | 组件 | `tui/components/box.ts:167` | TamboUI `panel`（`TamboUIAdapter`） | **对齐** | 2 | — |
| C12 | stack / h-stack / v-stack / spacer / text | 组件 | `tui/components/stack.ts:154`、`h-stack.ts:44`、`v-stack.ts:33`、`spacer.ts:28`、`text.ts:107` | TamboUI `column/row/spacer/text` | **对齐** | 3 | 每轮渲染都走（布局基元） |
| C13 | truncated-text / visual-truncate | 组件 | `tui/components/truncated-text.ts:65`、`components/visual-truncate.ts` | 部分：`TextLayout.java:1`（232 行）含 displayWidth/wrap | **存疑** | 2 | — |
| C14 | mouse-region（把鼠标事件限定到区域） | 组件 | `tui/components/mouse-region.ts:33`（本轮被三张消息卡用上，见 K37） | ✗（`ChatViewportElement.handleMouseEvent:205` 是元素级） | **缺失** | 1 | — |
| C15 | dynamic-border（编辑框边框随思考等级变色） | 组件 | `components/dynamic-border.ts:25` + `interactive-mode.ts:2101 updateEditorBorderColor` | ✗ | **缺失** | 1 | — |
| C16 | 编辑组件（多行 + 折叠 + 历史） | 组件 | `tui/components/editor.ts:296 class Editor`（2472 行）+ `editor-component.ts:74` + `custom-editor.ts:148` | `EditorComponent.java:27`（461 行） | **存疑** | 3 | 缺 K26/K27/K31；见「存疑」清单 |
| C17 | 模糊匹配 | 工具 | `tui/src/fuzzy.ts:12 fuzzyMatch`（**子序列**匹配 + `-5×连续 / +2×间隔 / -10×词边界 / +0.1×位置`）、`:100 fuzzyFilter`（按空白/斜杠**分词**、每词都要匹配、分数求和） | `FuzzyMatcher.java:50 score()` 是**子串**匹配（`startsWith`→0、`indexOf`→下标、否则不匹配），**无分词** | **存疑** | 2 | 名字对上了、算法不同 —— 例：query `abc` vs `a-b-c`，pi 命中、pi-java 不命中。挂载点也不同：pi 的 `fuzzyFilter` 服务 autocomplete/session-selector/tree-selector，pi-java 的只服务 `SelectList.java:142` |
| C18 | 布局引擎（constraint/flex/嵌套） | 框架 | `tui/src/layout.ts:449` + `layout-node.ts:51` | TamboUI 布局（外部） | **对齐**（经 TamboUI） | 3 | — |
| C19 | stdin 缓冲（CSI 分片重组） | 框架 | `tui/src/stdin-buffer.ts:444` + `test/stdin-buffer.test.ts` | TamboUI `Backend` | **对齐**（经 TamboUI） | 3 | — |

### 主题 / 样式

| # | 能力单元 | 类别 | pi 侧证据 | pi-java 侧证据 | 判定 | 权重 | 台账条目 |
|---|---|---|---|---|---|---|---|
| T1 | 内置 dark + light 主题 | 主题 | `theme/dark.json`（78 行）、`theme/light.json`（78 行） | `themes/pi-dark.tcss`（37 行）、`pi-light.tcss`（37 行）+ `PiTheme.java:23` | **对齐** | 2 | — |
| T2 | 主题 token 覆盖（约 70 个语义色：`mdHeading`/`toolDiffAdded`/`syntax*`/`thinking*`/`bashMode`/`export.*`） | 主题 | `theme/dark.json`（`okhsl(...)` 值，含 `appearance` 字段） | ✗（8 条 CSS 规则：Screen/ChatPanel/scrollbar×4/StatusBar/EditorComponent/Separator） | **缺失** | 3 | 每条消息的配色都走 |
| T3 | 用户自定义主题（JSON + schema 校验） | 主题 | `theme-schema.json`（**361 行**，本轮加 `appearance` 枚举 + `okhsl/oklch` 值）+ `theme-json.ts:148` | 部分：`.tcss` 文件（`PiTheme.java:42-89`），**无 schema、格式不同** | **存疑** | 1 | — |
| T4 | 主题热重载 / 切换重渲染（文件 watch + `themed-text` 失效重建） | 主题 | `theme-controller.ts:58 InteractiveThemeController`（**253 行**）+ `components/themed-text.ts:12 class ThemedText` + 测试 | ✗ | **缺失** | 1 | — |
| T5 | 思考等级 → 边框/文字颜色映射 | 主题 | `theme.ts:412 getThinkingBorderColor`/`thinkingOff…Max` 8 档 | ✗ | **缺失** | 2 | — |
| T6 | 语法高亮色板（10 token） | 主题 | `dark.json`：`syntaxComment/Keyword/Function/Variable/String/Number/Type/Operator/Punctuation` | `SyntaxHighlighter.java:21-23`（3 色） | **存疑** | 2 | — |
| T7 | 导出专用主题色（`export.pageBg/cardBg/infoBg`） | 主题 | `dark.json` 的 `export` 对象 + `export-html/index.ts` | ✗ | **缺失** | 1 | — |
| T8 | 主题解析优先级（CLI > settings > 扩展候选 > dark） | 主题 | `theme.ts`（1159 行）`getThemeByName:642` + `setRegisteredThemes:746`（`interactive-mode.ts:656`） | `PiTheme.java:71 resolveTheme` | **对齐** | 2 | — |
| T9 | 主题按扩展贡献注册 | 主题 | `interactive-mode.ts:656 setRegisteredThemes` | `PiTheme.resolveTheme(candidates)`（`PiTheme.java:71`） | **对齐** | 1 | — |
| T10 | **OKLab / OKLCH / OKHSL 色彩系统 + 混色**（`Color` 三态、`oklch`/`okhsl` 值、`mix`；theme 的 `okhsl(...)` 值靠它解析） | 主题 | ✓ **本轮新增** `tui/src/colors.ts`（367 行）+ `tui/src/oklab.ts`（233 行，Björn Ottosson 参考实现移植）；`567469096` / `bf8e4b953` | ✗（全树无 oklab/oklch/okhsl/混色命中；TamboUI 亦无） | **缺失** | 2 | pi 的 dark/light/system 全部主题色经此换算 |
| T11 | **system 主题（由终端调色板派生，三层 fallback）** | 主题 | ✓ **本轮新增** `theme/system-theme.ts`（655 行：色族/对比度曲线/OKHSL 生成）+ `theme.ts:200 setTerminalColors`/`:689 detectColorFgBgTheme` | ✗（`PiTheme` 只有 dark/light 两个 `.tcss`，无终端派生） | **缺失** | 1 | `bf8e4b953` `System theme (#10067)` |

### web 面（对齐相关部分）

> pi 仓库内**没有** web UI（`grep -rln "WebSocket" packages/*/src` 只命中 AI provider 的 HTTP/WS 客户端与 settings-selector，`find . -name "*web-ui*"` 零命中）。因此 web 的**呈现层**单列在「pi-java 独有」；下面只列**事件/线格式**这一面对齐相关的能力单元，其 pi 侧证据取自 pi 的 agent-loop 事件词汇与 pi-webui 基线协议。

| # | 能力单元 | 类别 | pi 侧证据 | pi-java 侧证据 | 判定 | 权重 | 台账条目 |
|---|---|---|---|---|---|---|---|
| W1 | 流式事件词汇（`agent_start`/`message_update`） | web 协议 | `interactive-mode.ts:3353 subscribeToAgent`（`:3359 handleEvent`）+ pi-webui `shared/protocol.ts` | `AgentEventTranslator.java:39-104` | **对齐** | 3 | B40（已结案） |
| W2 | `tool_execution_start/update/end` 由**真实工具执行事件**驱动 | web 协议 | pi `agent-loop.ts` 的工具执行事件 | `AgentEventTranslator.java:162-186` | **对齐** | 3 | B43（已结案） |
| W3 | `turn_end` 带 `{message, toolResults}` | web 协议 | `agent/types.ts:438` | `AgentEventTranslator.java:149-160` | **对齐**（残余：错误路 `message` 为 null ⇒ 省键） | 3 | B42/B49（已结案，残余登记） |
| W4 | `message_update` 顶层带 `usage` | web 协议 | `json-event.ts:11-15,56-60` | `AgentEventTranslator.java:100-104`（经 `WebWireJson.assistantNode`） | **对齐** | 3 | B28（已结案） |
| W5 | `toolcall_start` 带 `id`+`toolName` | web 协议 | `json-event.ts:23-30` | `StreamPartialBuilder.emitToolCallStart(id,name):390` | **对齐** | 3 | B29（已结案） |
| W6 | 消息 wire 形状（`role`、`toolCallId`、块判别字面量 `toolCall`/`thinking`） | web 协议 | pi-ai `types.ts` 消息形状 | `WebWireJson.java:26-100` | **对齐** | 3 | B48（已结案） |
| W7 | 终局 assistant 消息 `usage` 非空 | web 协议 | `types.ts:439` | `WebWireJson.java:92`（`B41` 已修） | **对齐** | 3 | B41（已结案） |
| W8 | `queue_update` / `compaction_*` / `auto_retry_*` 推送前端 | web 协议 | pi `agent-session.ts:594`（queue_update）、压缩/重试事件 | ✗ `AgentEventTranslator.java:63-65` 明确「暂不推前端」 | **缺失** | 1 | H-9.2-⑨、B34、B35 |
| W9 | 消息 `timestamp` 上 wire | web 协议 | pi-ai 消息必填 `timestamp`（`types.ts:417-421`） | ✗ **刻意偏差**（`WebWireJson.java:58` 注释「维持既有『wire 无消息 timestamp』的有意偏离」） | **存疑** | 1 | B50、H-9.2-⑧ |
| W10 | `agent_end.messages` 整表替换 | web 协议 | pi `agent_end` 带本 pass `newMessages` | `AgentEventTranslator.java:113-124`（空消息时不带 `messages` 键，防前端清空） | **存疑** | 3 | A8 |
| W11 | 宿主消费者并发契约声明（TUI/RPC/web 三面） | 架构 | —— | 收敛到 `PiLaneSink.emit` 锁，无显式声明 | **存疑** | 1 | A6 |
| W12 | `transcript()` 读取点清点（web 三处） | 架构 | —— | `WebDispatcher.java:235,349,380` | **存疑** | 1 | A2 |
| W13 | web 会话范围（`--session-dir` / `latest(cwd)`） | 行为 | pi `findMostRecentSession(dir)` 只读一个项目目录 | `PersistentSessionRepositories.latest(cwd)`（包⑫ 已修） | **对齐** | 2 | A12（已结案） |
| W14 | `--no-session` 在 web 路径生效 | 行为 | pi 语义「别落盘」 | ✗ `SessionPersistence.resolvePersistentWeb` 的 `args` 形参一句话都没用 | **缺失** | 1 | **B54** |
| W15 | 会话列表 API 带 `onProgress`（异步进度 + 增量结果 + 可取消） | 行为 | `core/session-manager.ts:884-889 SessionListProgress`（带 **`partialSessions`** 第三参 + `AbortSignal`；`:943 onProgress`）；`session-selector.ts:76-116` 消费 | ✗ 同步全量 | **缺失** | 1 | **B55** |
| W16 | `find()` 的范围（`--session <id>` 跨项目查找） | 行为 | pi `findLocalSessionByExactId(id, cwd, sessionDir)` 项目内 | `PersistentSessionRepositories.find():59` 走 `all()` ⇒ O(全部)、跨项目 | **缺失** | 1 | **B53** |

---

## pi-java 独有（非对齐项）

> pi 仓库内**零对应物**。**不计入**完成度分母，**权重一律记 0**（下表不设「权重」列）。

| 能力 | 说明 |
|---|---|
| **本地 Web UI 整体**（`pi-java-web`，1862 Java + 1654 TS + 675 CSS） | pi 仓库无 web 包（`packages/` 各包无 web；`find . -name "*web-ui*"` 零命中）。基线取自**仓库外**的第三方项目 `Zetaphor/pi-webui`，前端复用外部 npm 包 `@mariozechner/pi-web-ui` 的 `<message-list>` / `<streaming-message-container>` / `<message-editor>` / `<theme-toggle>` 自定义元素（`frontend/package.json:9-16`、`client/main.ts:809-870`） |
| WebSocket 网关 + 网关令牌鉴权 | `PiWebServer.java:1`（275 行）、`GatewayToken.java:1`（91 行）、`PiWebServerAuthTest` |
| 前端面板：会话侧栏（新建/切换/重命名/克隆/导出） | `client/main.ts:554-658 renderSidebar`、`:448-482` |
| 前端面板：代码调试（文件浏览器 / git status·diff·history / 终端 / skills 四 tab） | `client/panels/debug.ts:1`（321 行）+ `FileBrowserService.java`（87）、`GitService.java`（154） |
| 前端面板：树 / fork 可视化 | `client/panels/tree.ts:1`（137 行） |
| 前端面板：会话统计弹层 | `client/panels/stats.ts:1`（68 行） |
| 前端面板：设置（web 子集 6 项） | `client/panels/settings.ts:1`（73 行） |
| 前端：静默钟 + 三点等待指示（全 UI 唯一等待指示） | `client/main.ts:56-105,703-708` |
| 前端：错误块内联 + Retry 按钮 | `client/main.ts:845-857` |
| 前端：移动端侧栏覆盖层 / Tailwind 响应式 | `client/app.css`（675 行）、`vite.config.ts:5`（`@tailwindcss/vite`） |
| web 共享协议（29 条客户端消息 + 服务端消息族） | `shared/protocol.ts:1`（117 行）、`WebProtocol.java:1`（418 行） |
| **模式 2027 握手规避后端** | `NoMode2027Backend.java:20`（ConPTY 字节泄漏修复）+ `NoMode2027JLineBackend.java:1` + 2 个测试 |
| 滚轮/触控板归一化（Codex TUI2 PR#8357 移植） | `ScrollInputNormalizer.java:20`（274 行）—— pi 现代码是 `wheel-scroll.ts`（K38），两者模型不同 |
| 回合分隔条 `Worked for Ns • Local tools: N calls` | `ChatScreen.java:167-176`（Codex CLI 风格） |
| 元数据气泡 emoji 图标集（⚙/🧠/🗜/⎇/◆） | `MetaKind.java:15-29` |
| Codex 风格启动方框卡 | `WelcomeOverlay.java:41 buildCard` |
| 原生滚动回退内联 shell（`InlineTuiShell`） | `InlineTuiShell.java:1`（390 行） |

---

## 整块缺失

| 子系统 | pi LOC | 说明 |
|---|---|---|
| **终端图像协议**（Kitty / iTerm2） | **884**（`terminal-image.ts` 757 + `components/image.ts` 127） | 含能力探测、单元格尺寸探测、base64 尺寸解析、非 PNG 转码、WezTerm 滚动保留、降级占位；测试 `terminal-image.test.ts` + `block-images.test.ts` |
| **LaTeX 数学渲染** | **1506**（`tui/src/latex.ts`） | 行内/块级 `$…$`、Unicode 转换、tokenizer、堆叠脚本、字体切换；测试 `latex.test.ts` |
| **Markdown 组件** | **1025**（`tui/components/markdown.ts`） | 严格删除线 tokenizer、链接、hr、任务列表、流式围栏修剪、缓存、padding/背景；测试 `markdown.test.ts`（pi-java 有 `MarkdownRenderer` 221 行但**生产零调用**） |
| **alt-screen 搜索 + 选择** | **327 + ~500**（`alt-screen-search.ts` + `tui-alt-screen.ts` 的选择/复制/闪烁部分） | 搜索索引、匹配导航、文本选择、自动滚动、copy-on-select、OSC133 zone |
| **扩展 UI 组件族** | **~1000**（`extension-selector/input/editor` + `interactive-mode.ts:2362-2800`） | `custom<T>()` / `setFooter` / `setHeader` / widget / 通知 / 错误显示；pi-java 仅 RPC 通道 |
| **选择器族（未实现 9 个）** | **~2900**（config 875 + scoped-models 401 + session-selector 1045 非基础部分 + thinking 154 + theme 67 + trust 134 + oauth 214 + login 238 + first-time-setup + show-images 50 + user-message 155） | 见 S6-S9、S11、S13、S15-S21、S32 |
| **逐工具渲染器** | **1033**（`core/tools/renderers/*.ts` + `render-utils.ts`） | read/write/edit/bash/grep/find/ls 各自展示形状；MCP/fallback 工具的 args 展示 |
| **工具执行卡（可折叠 + 流式）** | **613**（`tool-execution.ts:393` + `bash-execution.ts:220`） | `Ctrl+O` 展开、部分结果、diff、图像 |
| **主题系统（JSON + schema + 热重载 + OKLab 色值 + system 主题 + 70 token）** | **~3176**（`theme.ts:1159` + `theme-controller.ts:253` + `theme-json.ts:148` + `theme-schema.json:361` + `theme/system-theme.ts:655` + `tui/src/colors.ts:367` + `tui/src/oklab.ts:233`） | pi-java 只有 2 个各 37 行的 `.tcss`，**无 schema、无色值系统、无 system 主题** |
| **提示历史 / 大粘贴折叠 / jump mode** | **~400**（`editor.ts` 相应段） | 见 K26/K27/K31 |
| **loader / spinner / bordered-loader** | **~260**（`loader.ts:101` + `bordered-loader.ts:68` + `cancellable-loader.ts:40` + `alt-screen-flash.ts:51`） | pi-java 无任何动画指示器 |
| **原生剪贴板 / 终端能力探测** | **~170**（`native-platform.ts:63` + `native-module-path.ts:31` + `terminal-colors.ts:91`） | 含 macOS/Windows/Linux native `.node` helper |
| **`@文件` 补全（fuzzy + fd）** | **~500**（`autocomplete.ts:303+` 的文件分支） | pi-java `SlashCompleter` 只做前缀匹配 |
| **故障上报 + 崩溃日志** | **~840**（`bug-report.ts:298` + `core/bug-report.ts:375` + `core/crash-log.ts:170` + `utils/zip.ts`） | `/bug` 全流程 + 崩溃落盘与下次启动提示；见 S29/S30 |
| **彩蛋族（3D pi logo / 3D Armin / header logo / 公告）** | **~1700**（`easter-egg-3d.ts:1292` + `easter-egg-3d.lazy.ts` + `pi-logo.ts:40` + `armin.ts:384` + `earendil-announcement.ts`） | 见 R22/R27 |
| **前端测试** | **0（pi 侧无 web）** | pi-java 前端**零测试**：`src/main/frontend/package.json` 无 `test` 脚本、无 vitest/jest/playwright 依赖、无 `*.test.*`/`*.spec.*` 文件；Maven `frontend-maven-plugin` 只跑 `npm ci` + `npm run build` |

---

## 台账纠错

| 条目 | 台账说什么 | 实际 | 证据 |
|---|---|---|---|
| **H-9.2-⑦** | 「Anthropic 路径的**思考内容仍按 TextContent 处理**」 | **已过期** —— 包①（`原 docs/31 §8.33`）后 Anthropic 车道已发 `ThinkingStart`/`ThinkingContent` | `pi-java-ai/.../protocol/AnthropicMessagesApi.java:207`、`:226`；`ContentBlock.ThinkingContent` 已带 `signature`/`redacted`（`MessageBubble.java:90-92` 的 `case ContentBlock.ThinkingContent(var text, _, _)`） |
| **H-9.2-⑧** | 「webui 未决字段**待 Stage A 实机钉死**」，指 `message_end` 的 timestamp 去重 | **已定但结论与设计稿相反** —— 落地的是**不给消息带 timestamp**（有意偏离），前端直贴不判重 | `WebWireJson.java:58` 注释原文；`client/main.ts` 的 `message_end` 分支无判重 |
| **H-9.2-⑨** | 「web 端 `queue_update` / `compaction_*` / `auto_retry_*` **暂不推前端**」 | **仍成立**（措辞与行为都对） | `AgentEventTranslator.java:63-65` `default -> { // queue_update / compaction_* / auto_retry_* 等暂不推前端 }` |
| **H-9.2-⑩** | 「`keybindings.json` 用户覆盖 → Phase 6」 | **仍成立** | `KeybindingsManager.java:14` 同句原文；`bindings` 表只有硬编码默认（`:58-68`），无文件读取 |
| **H-9.2-②** | 「`app.message.followUp`（Alt+Enter 排队）与 Alt+Enter=换行 **键位占用**」 | **仍成立，且比台账记的更严重** —— pi 在 **Windows 上 followUp 是 `ctrl+q`**，`alt+enter` 归换行 | pi `core/keybindings.ts:134-137`（`windowsKeybindings ? "ctrl+q" : "alt+enter"`）；java `KeybindingsManager.java:67` 把 `alt+enter` 绑给 `FOLLOW_UP`，`PiTuiApp.java:307` 的 `isNewlineEnter` 走 `shift+enter` |
| **H-9.2-③** | 「`StreamPartialBuilder`/`ToolCallAccumulator` 仍为**单工具调用模型**，需多槽位」 | **仍成立**（字段仍是单槽） | `StreamPartialBuilder.java:60-72`：`toolCallId`/`toolCallName`/`toolBlockIndex`/`toolArgBuf` 各一个。⚠️ 属 `pi-java-ai` 面 |
| **E5** | 「`EventParser.java` **515** 行 —— 已登记例外」 | **行数正确**，例外理由成立（只覆写 `parseControlChar` 一处） | `EventParser.java` 实测 515 行；类注释 `:20-29` 明写「only `parseControlChar(int, Bindings)` below differs from upstream 0.4.0」 |
| **B45** | 「`pi-java-tui` 的 `spotbugs:check` 4 条 finding」 | **已修**（结案无误） | `ChatScreen.java:57,60` 已加 `volatile`、`:64-65` 已换 `AtomicInteger`、`:167` 一次读 |
| **B37 / B3 / A12 / B40-B44** | 均已结案 | **核实无误** | `ChatScreen.java:135-144`（错误只进聊天区）、`:239-296`（会话事件面）、`WebWireJson.java:92`、`AgentEventTranslator.java:162-186` |
| **A2** | 「TUI / web / RPC / SQLite 四个读取点**未清点**」 | **部分已清**：TUI **不读** `transcript()`；web 读**三处**；coding-agent 内部读多处 | TUI 侧 `pi-java-tui/src` 对 `transcript()` 零命中；web `WebDispatcher.java:235,349,380`。⇒ TUI 那半可结案，web/RPC/SQLite 那半仍开 |
| **B47** | 「`ChatScreen.runToolCalls` 由 `StreamEvent.ToolCallStart` 计数，pi 由真实工具执行事件驱动」 | **仍成立** | `ChatScreen.java:131 case StreamEvent.ToolCallStart ignored -> runToolCalls.incrementAndGet()` |
| **H-9.2-①④⑤⑥** | inline 超长草稿 artifact / STDIN 共享管道 / Codex logo 动画 / 命令恢复 | **本次未核**（超出 TUI 判定面或需实机复现），**保持台账原文** | —— |

---

## 汇总

> **两套数**：`139` ＝ 对 `3390bd936` 的原始单元集（**本轮重测后**：作废 1 条 S31，新增 6 条 S32/R26/R27/K38/T10/T11）；
> `144` ＝ **当前**单元集（139 − 1 + 6）。**对外引用请用 144。**

| 档 | 条数（当前 144） | 条数（原 139 集） |
|---|---:|---:|
| **对齐** | **39** | **39** |
| **缺失** | **84** | **80** |
| **存疑** | **21** | **20** |
| 非对齐项（pi-java 独有，**不计入分母**） | 2 | 2 |
| **合计（判定单位）** | **144** | **139** |
| **完成度** | **39 / 144 = 27.1%** | **39 / 139 = 28.1%** |

**分面完成度（当前 144）**

| 面 | 对齐 | 缺失 | 存疑 | 判定单位 | 完成度 | 旧完成度（139 集） |
|---|---:|---:|---:|---:|---:|---:|
| 屏幕/面板（S1–S30 + S2a–d + S32，35） | 8 | 23 | 4 | 35 | **22.9%** | 22.9% |
| 渲染特性（R1–R27，27 单元，含 R21） | 2 | 22 | 2 | 27 | **7.4%** | 8.3% |
| 键绑定/交互（K1–K38，38 单元，含 K36） | 11 | 21 | 5 | 38 | **28.9%** | 30.6% |
| TUI 组件（C1–C19，19 单元） | 7 | 8 | 4 | 19 | **36.8%** | 36.8% |
| 主题/样式（T1–T11，11 单元） | 3 | 6 | 2 | 11 | **27.3%** | 33.3% |
| web 对齐面（W1–W16，16 单元） | 8 | 4 | 4 | 16 | **50.0%** | 50.0% |
| **合计** | **39** | **84** | **21** | **144** | **27.1%** | 28.1% |

> 非对齐项 2 条（R21 回合分隔条、K36 模式 2027 规避）已从分母剔除。各面明细见上表逐行 `判定` 列。

**LOC 口径 vs 能力单元口径（已按新锚点更新）**

| 口径 | pi（旧 → 新） | pi-java | 比例（旧 → 新） |
|---|---|---|---|
| UI 总 LOC（含 pi 的框架层） | 39734 → **43166** | 6707 | 16.9% → **15.5%** |
| 应用层 LOC（剔 pi `packages/tui` 框架层） | 19187 → **21626** | 6707 | 35.0% → **31.0%** |
| **能力单元** | — | — | 28.1% → **27.1%** |

**结论**：「TUI 大约只对齐 30%」这个线索 —— **能力单元口径下成立**（当前 **27.1%**，39/144）。三个口径分叉的成因：

1. **含框架层的 LOC 口径（15.5%）偏低**：pi 的 `packages/tui`（19277 行）在 pi-java 侧被**外部库 TamboUI** 顶替，把它算进 pi-java 的分母不成立。
2. **应用层 LOC 口径（31.0%）偏高**：LOC 对「整块缺失」不敏感 —— pi 的 21626 行里有 **12000+ 行**属于 **17 个整块子系统**（图像协议、LaTeX、Markdown 组件、alt-screen 搜索、扩展 UI、选择器族、工具渲染器、主题系统、彩蛋族…），这些在 pi-java 侧是 **0 行**，而不是「少写了几行」。
3. **能力单元口径（27.1%）最贴近判据**：它把「有没有这个行为承诺」与「实现得多厚」分开计。**渲染特性面只有 7.4%** 是最大短板（27 项里 22 项缺失，且 `MarkdownRenderer`/`SyntaxHighlighter` 两块**存在但是死代码**）；**web 对齐面 50.0%** 是最高面（web 的事件/线格式面已被包⑥⑦⑨⑩⑪逐条对齐过）。

**本轮（251 提交）对完成度的影响**：`28.1% → 27.1%`（−1.0pp），**构成为**：① **pi 新增 6 个单元**（S32/R26/R27/K38/T10/T11，仅 K38 判「存疑」贡献 0.5 权重，其余全「缺失」权重 1–2）⇒ 分母 +7 权重、分子 +0；② **作废 1 条**（S31 微 TUI，裁决 R1-3）⇒ 分母 −1。**没有一条既有判定因 pi 的改动而变差**。

---

## 存疑清单（全部 21 条）

| # | 能力单元 | 为何存疑 | 证据（pi 侧已对新锚点重测） |
|---|---|---|---|
| S2 | 全屏 alt-screen 模式 | java 用 TamboUI 通用 runner，pi 是自带搜索/选择/复制/图像的专用实现；承诺「全屏 + 内部视口」相同，能力面差 4 块（S2a-d） | `PiTuiLauncher.java:61` vs `tui-alt-screen.ts:202` |
| S12 | 会话树选择器 | java 列 **lane 名 + leafId**，pi 是 entry 级真树；「树选择器」这个名字下两者不是同一对象 | `TreeSelectorScreen.java:26-29` vs `tree-selector.ts:1336` |
| S27 | `/share` | 承诺相同、**后端不同**（pi=Radius 网关，java=GitHub gist），且 java 无 `BorderedLoader` | `session-share.ts:57 shareSession` vs `MiscCommands.java:76-92` |
| S28 | `/export` HTML | pi 用 vendored marked/highlight + 模板，java 自写 `MarkdownToHtml.java`；输出形状未逐项核 | `export-html/index.ts:236` vs `HtmlExporter.java:21` |
| R10 | Diff 渲染 | java 按行首字符着色，无 hunk 头/行号/词级；pi 的 diff 走富渲染器 | `DiffView.java:52-63` vs `components/diff.ts:79` |
| R17 | 压缩摘要消息卡 | java 一行 `System`，pi 是独立卡 + `usage` 开销提示 + **鼠标点击展开** | `ChatMessage.java:50` vs `compaction-summary-message.ts:10` + `interactive-mode.ts:4053` |
| K5 | `Alt+Enter` 排队 follow-up | 键位与 pi 的 Windows 默认（`ctrl+q`）冲突；「Alt+Enter=换行」这条被 java 换成 Shift+Enter | `KeybindingsManager.java:67` vs `keybindings.ts:134-137` |
| K18 | 键位提示渲染 | java 只有 `keyText`，无 `keyHint`/`rawKeyHint`/`keyDisplayText` | `KeybindingHints.java:29` vs `keybinding-hints.ts:48` |
| K19 | 滚轮/触控板归一化 | java 移植自 Codex TUI2 PR#8357，pi 现代码是 `wheel-scroll.ts` ⇒ 都有归一化、模型不同 | `ScrollInputNormalizer.java:20` vs `tui-alt-screen.ts:483` |
| K32 | Kitty 键盘协议 | java 有 CSI-u（`parseCsiU:308`）但无 `modifyOtherKeys`（`CSI 27;m;k~`）；覆盖率未逐序列核 | `EventParser.java:224,309` vs `keys.ts:705` |
| **K38** | **全屏滚轮加速 + 行数配置** | **本轮新增**：pi 有专用 `WheelScrollAccelerator`（速度模型 + `fullscreenWheelScrollLines` 设置 1–100/auto），java 有 `ScrollInputNormalizer` 的**有界加速**（Codex 固定默认模型），**无用户设置项** ⇒ 都有加速、模型与配置面不同 | `wheel-scroll.ts:38` vs `ScrollInputNormalizer.java:14` + `ScrollConfig.java:28-36` |
| C2 | 选择列表 | pi 有分组/模糊/多选，java 是单选线性列表 | `SelectList.java:1` vs `select-list.ts:273` |
| C13 | truncated-text / visual-truncate | java 的截断能力散在 `TextLayout`/`ToolCallCard.truncate`，无独立组件、行为未逐项核 | `TextLayout.java:1`、`ToolCallCard.java:33-37` |
| C16 | 编辑组件 | 核心编辑语义对齐（undo/kill-ring/词导航），但缺 3 项（K26/K27/K31）⇒ 不能判「对齐」 | `EditorComponent.java:27` vs `editor.ts:296` |
| C17 | 模糊匹配 | pi 是**子序列**匹配 + 按空白/斜杠分词（`fuzzy.ts:12,100`），pi-java 是**子串**匹配 + 无分词（`FuzzyMatcher.java:50 score()`）；且挂载点错位 | `FuzzyMatcher.java:50` vs `fuzzy.ts:12,100` + `select-list.ts:61` |
| T3 | 用户自定义主题 | java 支持 `.tcss`，pi 是 JSON + schema 校验（本轮 schema 加 `appearance` + `okhsl/oklch` 值）⇒ 格式/校验能力都不同 | `PiTheme.java:42-89` vs `theme-schema.json:1` |
| T6 | 语法高亮色板 | java 3 色 vs pi 9 token；且 java 的代码块路径是死代码（R9） | `SyntaxHighlighter.java:21-23` vs `dark.json` `syntax*` |
| W9 | 消息 timestamp 上 wire | **刻意偏差**（`WebWireJson.java:58` 明写），与 pi 必填冲突；台账 B50 仍开 | `WebWireJson.java:58` vs `ai/src/types.ts:417-421` |
| W10 | `agent_end.messages` 整表替换 | java 在空消息时**不带 `messages` 键**（防前端清空），pi 带 `newMessages` ⇒ 载荷不同 | `AgentEventTranslator.java:113-124` |
| W11 | 宿主并发契约 | 无显式声明（台账 A6） | —— |
| W12 | `transcript()` 读取点清点 | TUI 半已清（不读），web/RPC/SQLite 半未清 | 见台账纠错 A2 行 |

> 注：W11/W12 与台账 A2/A6 同源，属「架构面」。上表 21 行即全部存疑条目。

---

## 缺失清单（全部 84 条）

**屏幕/面板（23）**：S2a 转录搜索 · S2b 文本选择/copy-on-select · S2c flash 提示 · S2d 全屏图像 · S6 模型搜索 · S7 scoped-models 选择器 · S8 thinking 选择器 · S9 theme 选择器 · S11 会话选择器高级功能 · S13 树选择器折叠/标签/过滤/搜索 · S15 设置子菜单 · S16 config 选择器 · S17 OAuth/登录对话框 · S18 首次设置向导 · S19 信任选择器 · S20 排队消息选择器 · S21 show-images 选择器 · S22 扩展 UI 四件套 · S23 扩展 widget/footer · S25 会话统计面 · S29 `/bug` 故障上报 · S30 崩溃记录 + 启动提示 · **S32 登录 URL 复制 + Radius 登录（本轮新增）**

**渲染特性（22）**：R1 Markdown 渲染（死代码）· R2 表格 · R3 删除线 · R4 链接 · R5 流式围栏修剪 · R6 transformer 钩子 · R7 mermaid · R8 LaTeX · R9 代码块高亮（死代码）· R11 逐工具渲染器 · R12 工具卡折叠 · R13 bash 执行组件 · R14 图像协议 · R15 图片粘贴 · R18 分支摘要卡 · R19 skill 调用卡 · R22 公告 · R23 终端标题 · R24 缓存预热用量行 · R25 thinking 丢弃提示 · **R26 工具调用参数渲染（本轮新增）** · **R27 3D 彩蛋/logo（本轮新增）**

**键绑定/交互（21）**：K6 Ctrl+P · K7 Shift+Tab · K8 Ctrl+O · K9 Ctrl+T · K10 Ctrl+G · K11 Alt+Up · K12 Ctrl+Z · K13 Ctrl+X · K14 Ctrl+V · K15 Ctrl+N · K16 app.session.* · K17 keybindings.json · K21 转录搜索 · K22 鼠标选择 · K25 @文件补全 · K26 大粘贴折叠 · K27 提示历史 · K31 jump mode · K34 原生剪贴板 · K35 终端能力探测 · K37 消息卡鼠标点击展开

**TUI 组件（8）**：C3 settings-list · C4 input · C5 loader/spinner · C6 working 指示器 · C7 bordered loader · C8 cancellable loader · C14 mouse-region · C15 dynamic-border

**主题（6）**：T2 主题 token 覆盖 · T4 热重载 · T5 thinking 颜色映射 · T7 导出主题色 · **T10 OKLab/OKLCH 色彩系统（本轮新增）** · **T11 system 主题（本轮新增）**

**web 对齐面（4）**：W8 queue/compaction/retry 推送 · W14 `--no-session`（**B54**）· W15 `onProgress`（**B55**）· W16 `find()` 范围（**B53**）

---

## pi 侧漂移复核（`3390bd936` → `200387122`，251 提交）

**方法**：一律 `git show <sha>:<path>` 取数（**不读工作树** —— 本地 pi HEAD 仍停在 `3390bd936`），逐文件 `git diff --name-status 3390bd936..200387122 -- packages/tui packages/coding-agent/src/modes packages/coding-agent/src/{core,cli}` 定范围，再对**每一个**被引用的符号重新取行号。

### A. 复核结果分类（按**单元行**计）

| 类 | 行数 | 说明 |
|---|---:|---|
| **作废**（裁决 R1） | **1** | S31 实验性 micro TUI：pi `7fd478a2e` 删 `coding-agent/src/experimental/{micro,mini}`（D4） |
| **pi 新增能力**（新单元） | **6** | S32 登录 URL 复制 + Radius · R26 工具调用参数渲染 · R27 3D 彩蛋/logo · K38 滚轮加速+配置 · T10 OKLab 色彩系统 · T11 system 主题 |
| **实质变化**（判定改动） | **0** | 无既有单元因 pi 改动而改判；唯一删除面（S31）按裁决直接移出分母 |
| **只漂行号**（判定一字不改） | **33** | 见 C 栏 |
| **行号未动**（原引用原样有效） | **99** | 含全部 `core/keybindings.ts`（43 键位）、`tui/keybindings.ts`（47 键位）、`markdown.ts` 三处（`:9`/`:146`/`:236`）、`tui/keys.ts:705`、`fuzzy.ts:12,100`、`latex.ts`（1506）、`layout.ts:449`、`stdin-buffer.ts:444`、`sidebar/message 组件类尾行号` 等 |
| **复核总行数** | **139** | 1 + 6 + 0 + 33 + 99 = 139 ✓ |

### B. 关键提交的逐条核实

| 提交 | 影响的单元 | 核实结论 |
|---|---|---|
| `7fd478a2e` remove the experimental harness | **S31** | **作废**（R1-3）。`coding-agent/src/experimental/{micro,mini}` 删除 23 文件，含 `micro/tui.ts` |
| `567469096` add color values and theme styling (#8398) | **T10**（新） | `tui/src/colors.ts` 367 行；`Color` 三态（indexed/rgb/oklch）+ `TextStyle` |
| `bf8e4b953` System theme (#10067) | **T10 / T11**（新） | 新增 `tui/src/oklab.ts` 233 行 + `theme/system-theme.ts` 655 行 + `components/{themed-text,pi-logo}.ts`；`dark.json`/`light.json` 值改为 `okhsl(...)`；`theme-schema.json` 加 `appearance` |
| `f1927c2d5` configurable fullscreen wheel scrolling with auto acceleration | **K38**（新）/ **K19** | `tui/src/wheel-scroll.ts` 82 行；接入 `tui-alt-screen.ts:271,:699`；设置项 `settings-manager.ts:188` |
| `88ff80b98` make fullscreen the default TUI mode | **S2 / S3** | 默认模式翻转为 fullscreen（`args.ts:329` "fullscreen (default)"；java 侧默认亦 fullscreen）⇒ 判定不变 |
| `ced72c2f0` copy OAuth sign-in URLs with app.message.copy | **S32**（新） | `components/auth-url.ts:9` |
| `ed8b3bcc1` offer Radius sign-in and MCP setup in /login | **S32**（新） | `components/radius-login-selector.ts` 114 行 |
| `c450f2c0f` / `7fbbd5f4a` / `b35af04f4` 3D 彩蛋 | **R27**（新） | `easter-egg-3d.ts` 1292 行 + lazy + `pi-logo.ts` |
| `1c1e9c0ef` remove daxnuts easter egg | **R22** | `components/daxnuts.ts` **删除**（D6）；R22 保留（earendil-announcement/armin 仍在） |
| `5257d0d5f` / `11449730c` show tool call arguments for MCP and fallback | **R26**（新） | `core/tools/render-utils.ts:78 formatToolCallWithArgs` + `tool-execution.ts:137` |
| `2b0a123de` remove themes section from startup banner | **S4** | banner 不再列 themes（`interactive-mode.ts:1760`）⇒ 判定不变 |
| `a276dabe5` / `672000c80` / `7cf037c21` 图像 | **R14 / S2d** | WezTerm 保留 + 非 PNG 转码 + 按纵横比选 Kitty 尺寸；`terminal-image.ts` 696 → **757 行** ⇒ 判定不变（缺失） |
| `b485fa312` reduce render cost of theme changes | **T4** | 加 `themed-text.ts`（主题切换重建字符串）⇒ 判定不变（缺失） |

### C. 只漂行号的条目（判定不变，仅更新引用）

| 单元 | 旧行号 | 新行号 |
|---|---|---|
| S1 | `interactive-mode.ts:3776` / `:3986` | **`:3926` / `:4140`** |
| S2 | `tui-alt-screen.ts:197` | **`:202`** |
| S2b | `tui-alt-screen.ts:296` / `:193` | **`:305` / `:198`** |
| S2c | `tui-alt-screen.ts:643` | **`:657`** |
| S2d | `tui-alt-screen.ts:338` | **`:352`** |
| S4 | `interactive-mode.ts:1677` | **`:1760`** |
| S5 | `model-selector.ts:1` | **`:40`（类声明）** |
| S7 | `scoped-models-selector.ts:401` | **`:97`（类声明）** |
| S8 | `thinking-selector.ts:154` | **`:36`（类声明）** |
| S9 | `theme-selector.ts:67` | **`:13`（类声明）** |
| S12 | `tree-selector.ts:1329` | **`:1336`** |
| S13 | `tree-selector.ts:112` / `:628` / `:633` / `:997` | **`:112` / `:632` / `:637` / `:1004`** |
| S14 | `settings-selector.ts:447` | **`:463`** |
| S15 | `settings-submenu.ts:258` | **`:31`（`SelectSubmenu`；`:178 SteppedSubmenu`）** |
| S16 | `config-selector.ts:942` | **`:875`** |
| S17 | `login-dialog.ts:233` / `oauth-selector.ts:214` | **`login-dialog.ts:12` / `oauth-selector.ts:58`（类声明）** |
| S18 | `first-time-setup.ts:145` | **`:33`（类声明）** |
| S19 | `trust-selector.ts:134` | **`:32`（类声明）** |
| S20 | `user-message-selector.ts:155` | **`:110`（类声明）** |
| S21 | `show-images-selector.ts:50` | **`:13`（类声明）** |
| S22 | `interactive-mode.ts:2558-2740` | **`:2654` / `:2730` / `:2786`** |
| S23 | `interactive-mode.ts:2266-2450` | **`:2362` / `:2488` / `:2515`** |
| S25 | `footer.ts:84-200` | **`footer.ts:120-137`** |
| S29 | `bug-report.ts:294` | **`:50 reportBug`** |
| R6 | `interactive-mode.ts:2102` | **`:2198`** |
| R9 | `interactive-mode.ts:126` / `:1037` | **`:132`（import）/ `:1110`（调用）** |
| R11 | renderers `1057` 行 | **`1033` 行**（+`render-utils.ts:78`） |
| R12 | `tool-execution.ts:61` / `:166-170` / `:244` | **`:46` / `:151` / `:194`**（文件 433 → 393 行） |
| R14 | `terminal-image.ts:696` / `image.ts:127` | **`terminal-image.ts` 757 行 / `image.ts:55 class Image`** |
| R16 | `interactive-mode.ts:3667` | **`:3817`** |
| R23 | `interactive-mode.ts:1047` | **`:1120`** |
| R24 | `interactive-mode.ts:3892` | **`:4042`** |
| R25 | `interactive-mode.ts`（无行号） | **`:4066` / `:4082`** |
| C6/C10 | `:2214-2250` / `:2180-2213` | **`:2310-2332` / `:2276-2299`** |
| C15 | `interactive-mode.ts:4284` | **`:2101 updateEditorBorderColor`** |
| C16 | `editor.ts:284` | **`:296`** |
| K19 | `tui-alt-screen.ts:467` | **`:483`** |
| K24 | `autocomplete.ts:278` | **`:303`** |
| K25 | `autocomplete.ts:390-477,702` | **`:421-477`, `:737`** |
| K27 | `editor.ts:332-335,412-477` | **`:344-347`, `:427`** |
| K28 | `editor.ts:355` | **`:367`** |
| T8/T9 | `interactive-mode.ts:577` | **`:656`** |
| W1 | `interactive-mode.ts:3165` | **`:3353`（`:3359 handleEvent`）** |
| java 侧 PiTuiLauncher | :69 / :99 | **`:61` / `:111`** |

### D. pi 侧键位数量：**未变**

| 侧 | 旧 | 新 |
|---|---:|---:|
| `packages/coding-agent/src/core/keybindings.ts` | 43 | **43** |
| `packages/tui/src/keybindings.ts` | 47 | **47** |

唯一改动仍是 `app.message.copy` 的**描述文字**（反映 K22 copy-on-select 已接入该键），**键位本身与默认值 `ctrl+x` 不变** ⇒ K13 判定不变（缺失）。

### E. 需要别的地图/台账跟进的连带项（不在本图范围，仅登记）

- `docs/05` 的 **B55** 措辞：pi 的 `onProgress` 带 `partialSessions` + `AbortSignal`（`session-manager.ts:884-889,943`）。
- pi 新增包 **`durable`**（17,716 行）—— 非 UI 面，本图不覆盖（R5 不可达，权 0）。
- pi 新增 `mcp` / `codemode` 包 —— 非 UI 面。
- `docs/06` 的规模表需按本轮新 LOC 更新（本图「规模」节已更新）。

### F. pi-java 侧行号更正（`34849a2` → `351d199`，253 提交）

pi-java 侧**逐条核实**了本图全部 `file:line`（方法：`grep`/`sed` 直读工作树，module 已核）。**更正 53 处**（判定均不变，仅引用行号/模块路径）：

- **跨模块路径更正**：`KeybindingsManager` · `MiscCommands` · `ExtensionUI` · `AgentSession` · `StreamPartialBuilder` · `HtmlExporter` · `MarkdownToHtml` 均**不在 `pi-java-tui`**，而在 `pi-java-coding-agent` / `pi-java-ai`（引用写作 basename 时应带模块）。
- **仍未变**：`ChatScreen.java:131/167-176/239-296`、`PiTuiApp.java:307/422/429-435/437`、`ModelSelectorScreen` 系列、`KeybindingsManager.java` 键位常量（19/22/24/28/29/67）、`MiscCommands.java:34/76/96/108/120/122`、`WebDispatcher.java:235,349,380`、`WebWireJson.java:92`、`AgentEventTranslator.java:63-65/100-104`、`EventParser.java:230`。
- **主要漂移**：`AgentEventTranslator` 各方法整体下移 ~5 行（`agentEnd:113`、`turnEnd:149`、`toolExecution*:162-186`）；`StreamPartialBuilder.emitToolCallStart:278→390`（文件 531 行）；`SyntaxHighlighter`/`MarkdownRenderer`/`ChatMessage`/`MessageBubble`/`MetaKind`/`DiffView` 等组件类声明与用法行普遍下移 2–7 行；`PiTheme.java` 因重构整体降 ~30 行（`DARK:20→23`、`resolveTheme:66→71`）；`SettingsScreen`/`SessionListScreen`/`TreeSelectorScreen` 等屏幕类声明行大幅变化（旧图多用文件尾行号，本图统一改为**类声明行**）。

---

## 加权汇总

**权重规则**（已定，未自创）：权重 = **用户可观察影响 × 频率**，只看「如果用户用这个模块，这个单元有多重要」，**不掺排期优先级**。
`3` = 每轮对话都走／默认路径；`2` = 每次会话走／常用命令；`1` = 低频／边缘／纯内部；`0` = 非目标（排除出分母）。
**完成系数**：对齐 = 1.0 ／ 存疑 = 0.5 ／ 缺失 = 0。

| 域 | Σ权重 | Σ(w×系数) | 加权完成度 | 未加权完成度 |
|---|---:|---:|---:|---:|
| 屏幕/面板（S1–S30 + S2a–d + S32，35 单元） | 47 | 17.0 | **36.2%** | 22.9% |
| 渲染特性（R1–R27，27 单元；R21 权重 0 已剔） | 38 | 5.5 | **14.5%** | 7.4% |
| 键绑定/交互（K1–K38，38 单元；K36 权重 0 已剔） | 65 | 30.5 | **46.9%** | 28.9% |
| TUI 组件（C1–C19，19 单元） | 40 | 22.5 | **56.2%** | 36.8% |
| 主题/样式（T1–T11，11 单元） | 18 | 6.5 | **36.1%** | 27.3% |
| web 对齐面（W1–W16，16 单元） | 33 | 26.0 | **78.8%** | 50.0% |

**模块合计**：Σ权重 **241** ／ Σ(w×c) **108.0** ／ **加权完成度 = 108.0/241 = 44.8%**（未加权 **27.1%**，**+17.7pp**）

> 旧锚点（139 单元）的同口径值：Σ权重 235 ／ Σ(w×c) 107.5 ／ **45.7%**（未加权 28.1%）。
> **本轮 −0.9pp 的构成**（45.74% → 44.81%）：① pi 新增 6 个单元（S32/R26/R27/T11 缺失 w1 + T10 缺失 w2 + K38 存疑 w1）⇒ 分母 +7、分子 +0.5 ⇒ **−0.9pp**；② 作废 S31（缺失 w1）⇒ 分母 −1、分子 ±0 ⇒ **+0.1pp**。

**权重分布（按档）**

| 权重 | 单元数 | Σ权重 | Σ(w×c) | 加权完成度 |
|---|---:|---:|---:|---:|
| 3（每轮／默认路径） | 26 | 78 | 52.5 | **67.3%** |
| 2（每次会话／常用命令） | 45 | 90 | 47.0 | **52.2%** |
| 1（低频／边缘／纯内部） | 73 | 73 | 8.5 | **11.6%** |
| 0（非对齐项，已剔） | 2 | 0 | 0 | — |
| **合计** | **144** | **241** | **108.0** | **44.8%** |

**权重 3 的单元清单（26 条 —— 最该先修；本轮未增未减）**

| # | 单元 | 判定 |
|---|---|---|
| S1 | 主聊天屏 | **对齐** |
| R1 | Markdown 渲染 | **缺失**（死代码） |
| R10 | Diff 渲染 | **存疑** |
| R11 | 逐工具渲染器 | **缺失** |
| K1 | `Esc` 中断 | **对齐** |
| K19 | 滚轮/触控板归一化 | **存疑** |
| K23 | 滚动条 | **对齐** |
| K27 | 提示历史 ↑/↓ 导航 | **缺失** |
| K28 | undo（fish 式合并） | **对齐** |
| K32 | Kitty 键盘协议解析 | **存疑** |
| C1 | 滚动视图 | **对齐** |
| C5 | loader / spinner | **缺失** |
| C6 | working 指示器 | **缺失** |
| C12 | stack / spacer / text 布局基元 | **对齐** |
| C16 | 编辑组件 | **存疑** |
| C18 | 布局引擎 | **对齐** |
| C19 | stdin 缓冲 | **对齐** |
| T2 | 主题 token 覆盖（~70 语义色） | **缺失** |
| W1 | 流式事件词汇 | **对齐** |
| W2 | `tool_execution_*` 真实源 | **对齐** |
| W3 | `turn_end` 带 `{message, toolResults}` | **对齐** |
| W4 | `message_update` 带 `usage` | **对齐** |
| W5 | `toolcall_start` 带 `id`+`toolName` | **对齐** |
| W6 | 消息 wire 形状 | **对齐** |
| W7 | 终局 `usage` 非空 | **对齐** |
| W10 | `agent_end.messages` 整表替换 | **存疑** |

⇒ 权重 3 里 **15 对齐 / 6 缺失 / 5 存疑**（**67.3%**）。缺失的 6 条全部是 **TUI 的「每轮都看得见」的能力**：`R1` Markdown、`R11` 逐工具渲染器、`K27` 提示历史、`C5` spinner、`C6` working 指示器、`T2` 主题 token。**这 6 条是加权口径下的最高收益点**；本轮 pi 新增的 6 个单元**无一进入这个子集**（最高权重 2）。

**未加权 vs 加权的差（+17.7pp）差在哪**

| 判定 | 单元数 | Σ权重 | 平均权重 |
|---|---:|---:|---:|
| 对齐 | 39 | 88 | **2.26** |
| 存疑 | 21 | 40 | 1.90 |
| 缺失 | 84 | 113 | **1.35** |

缺失单元里 **73/84 条是权重 1**（低频选择器、扩展 UI、彩蛋、原生 helper、内部契约形状、本轮 pi 新增的 6 条…），它们把**未加权**完成度压到 27.1%，但在**加权**口径下只占 73/241 = 30.3% 的权重。反过来，**权重 3 的 26 条里 15 条已对齐**（web 事件词汇 7 条 + TUI 核心管线 8 条）—— 这正是 pi-java 的真实形态：**核心管线对齐得不错，外围能力大面积空缺**。

**两项重点发现各自的加权拖累（按新锚点重算）**

| 发现 | 涉及单元 | Σ权重 | 若修好 | 模块加权完成度 | 模块增量 | 该面增量 |
|---|---|---:|---:|---:|---:|---:|
| **Markdown 渲染是死码** | R1（w3） | 3 | 111.0/241 | **46.1%** | **+1.3pp** | R 面 14.5% → **22.4%**（+7.9pp） |
| **Markdown 死码（含代码块高亮）** | R1 + R9（w3+2） | 5 | 113.0/241 | **46.9%** | **+2.1pp** | R 面 14.5% → **27.6%**（+13.2pp） |
| **6 个键位未接线** | K6/K7/K8/K9（w2×4）+ K10/K11（w1×2） | 10 | 118.0/241 | **49.0%** | **+4.2pp** | K 面 46.9% → **62.3%**（+15.4pp） |
| 两项合计 | R1+R9+K6–K11 | 15 | 123.0/241 | **51.0%** | **+6.2pp** | — |

口径提醒：**这两项加起来只值 +6.2pp**，但它们**在权重 3 子集里的分量远大于此** —— `R1` 仍是权重 3 的**单条最大缺失**（修好它把权重 3 子集从 67.3% 拉到 **71.2%**）。⇒ **加权完成度这个总量指标对「单条高频缺失」不敏感，看权重 3 子集才看得出。**
