# 05 用户界面层（pi-java-tui / pi-java-web）

> 【背景摘要】本图是用户界面层（pi-java-tui 与 pi-java-web）的全量加权盘点：屏幕/面板、渲染特性、键绑定、TUI 组件、主题、web 面逐单元判定与证据。开放缺口的权威台账是 [`../04-open-items-register.md`](../04-open-items-register.md)。

## 基准与口径

- 基准：pi-java @ `edc8749d`（main，2026-10-11）；pi @ `ea448f454`（2026-10-11，v1.1.0-22）。
- 判定单位＝**可被外部观察到的行为承诺**（一个屏幕 / 一个键绑定 / 一个渲染特性 / 一个主题能力 / 一个 TUI 组件 / 一个 web 能力）。
- 三档：**对齐**＝pi 有、pi-java 有、行为相同；**缺失**＝pi 有、pi-java 无；**存疑**＝pi-java 有但行为/形状与 pi 不同或未核。
- 所有证据均为 `file:line`。pi 侧路径前缀 `D:\workplaceForai\pi\packages\`，java 侧 `D:\workplaceForai\pi-java\`。
- pi 侧取证一律 `git show ea448f454:<path>`（不读 pi 工作树）；本锚点对前锚点 `200387122` 的逐条漂移复核见文末。

---
## 规模

| 模块 | pi 包 | pi LOC | java LOC | 比例 |
|---|---|---|---|---|
| TUI 框架层 | `packages/tui/src`（**46** 文件） | **19505** | —（对应物是**外部库 TamboUI**） | — |
| TUI 应用层 | `coding-agent/src/modes/interactive`（**62** TS 文件） | **21895** | — | — |
| 工具渲染器 | `coding-agent/src/core/tools/renderers`（8 文件） | **1053**（逻辑另在 `core/tools/render-utils.ts` 112 行） | 0 | 0% |
| HTML 导出 | `coding-agent/src/core/export-html`（3 TS：`index`+`ansi-to-html`+`tool-renderer`） | **748** | 288+ | — |
| CLI 启动 UI | `coding-agent/src/cli`（startup-ui/session-picker/config-selector/list-models） | **484** | 见 coding-agent | — |
| **UI 合计（pi，剔 micro）** | | **43685** | — | — |
| **pi-java-tui（main）** | | 43685 | **6799**（48 文件） | **15.6%** |
| pi-java-tui（test） | pi `tui/test` **55 文件 / 20410 行** | — | **4310**（34 文件） | — |
| pi-java-web（java main） | **pi 仓库内无对应物** | 0 | **1912**（9 文件） | — |
| pi-java-web（前端 TS） | **pi 仓库内无对应物** | 0 | **1654**（8 文件）+ `vite.config.ts` 26 | — |
| pi-java-web（前端 CSS） | 同上 | 0 | **675**（app.css） | — |

**口径说明（重要）**：

1. pi 的 `packages/tui` 是一个**自带组件库的通用终端框架**；pi-java 的对应物是**外部库 TamboUI**（`dev.tamboui.*`，见 `pi-java-tui/src/main/java/dev/tamboui/tui/event/EventParser.java:20-29` 的 same-package 覆写注释）。因此含框架层的 LOC 口径**低估了 pi-java**：真正的同层比较应把 pi 的 `tui` 框架层 19505 行算作 TamboUI（外部），只剩应用层 21895 行可比。
2. 按**应用层口径**：6799 / 21895 = **31.1%**。能力缺口集中在整块子系统（图片协议、LaTeX、搜索、扩展 UI、主题系统、工具渲染器），而不是零散小功能。
3. **测试量差距**：pi `packages/tui/test` **20410 行 / 55 个测试文件** vs pi-java-tui **4310 行 / 34 个测试文件**（**4.7×**）。pi 的 `coding-agent/test` 现 **315** 个测试文件，其中约 **92 个**直接针对 UI（renderer/tui/interactive/keybind/editor/theme/message 组件等），pi-java-tui 对应面仍为 **0**。
4. **删除面（裁决 R1 适用）**：`coding-agent/src/experimental/{micro,mini}/`（微 TUI，pi `7fd478a2e` 删除）不在分母（旧 S31 作废）；本锚点**无行为删除**，仅主题校验代码搬家（见文末漂移复核与 T3）。

---

## 屏幕/面板对齐

| # | 屏幕 | pi 有 | java 有 | 判定 | 权重 | 证据（pi 侧均对 `ea448f454` 取数） |
|---|---|---|---|---|---|---|
| S1 | 主聊天屏（transcript 视口 + 输入行 + 状态行） | ✓ | ✓ | **对齐** | 3 | pi `interactive-mode.ts:3991 renderSessionItems` / `:4208 renderInitialMessages`；java `ChatScreen.java:191 render()`、`PiTuiApp.java:187 root()` |
| S2 | 全屏 alt-screen 模式（**默认模式**） | ✓ `tui/src/tui-alt-screen.ts:202 class TuiAltScreen`（1787 行） | ✓ `PiTuiLauncher.java:61 runFullscreen`（TamboUI ToolkitRunner） | **存疑** | 2 | java 用 TamboUI 通用 runner，pi 的 alt-screen 自带搜索/选择/复制/图像/闪烁（见 S2a-d、K20/K21；OSC 7501 状态见 K39）；滚动条由 java 自绘（`ChatViewportElement.java:261 renderScrollbar`）。默认模式两侧一致（pi `cli/args.ts:340` "fullscreen (default)"；java `PiTuiLauncher.java:111` 默认 fullscreen） |
| S2a | 全屏：转录搜索（`/` 查询、上一个/下一个匹配） | ✓ `tui/src/alt-screen-search.ts:156-197`（327 行） | ✗ | **缺失** | 1 | java 全树无 search 命中（`grep -rni "search" pi-java-tui/src/main` 只命中 `FuzzyMatcher.java:18` 注释） |
| S2b | 全屏：鼠标文本选择 + 自动滚动 + copy-on-select | ✓ `tui-alt-screen.ts:305 hasActiveSelection`、`:1464 copySelectionToClipboard` | ✗ | **缺失** | 1 | 同上 grep：java 无 selection/copy 命中。本锚点 pi 新增 `:317 resetTextSelection`（转录替换时清选区，#9311），java 无选区可清 |
| S2c | 全屏：flash 提示（短暂状态行） | ✓ `tui-alt-screen.ts:665 flash()` + `tui/src/components/alt-screen-flash.ts:1`（51 行） | ✗ | **缺失** | 1 | — |
| S2d | 全屏：图像渲染（Kitty/iTerm2） | ✓ `tui-alt-screen.ts:358`（stop 时 `setCapabilities(… images: null)`） | ✗ | **缺失** | 1 | 见 R14 |
| S3 | regular（非备用屏）inline 模式 | ✓ `tui/src/tui-main-screen.ts`（655 行） | ✓ `InlineTuiShell.java:1`（390 行） | **对齐** | 1 | 两者都是「终端原生 scrollback + 底部固定输入区」；**两侧均为非默认**（`--tui-mode regular`，pi `args.ts:340`，java `PiTuiLauncher.java:111`） |
| S4 | 启动欢迎卡（**不列 themes**） | ✓ `interactive-mode.ts:1772 showLoadedResources` | ✓ `WelcomeOverlay.java:41 buildCard` | **对齐** | 2 | 形状不同（pi 列 context/skills/prompts/extensions、不列 themes；java 是 Codex 风格方框），但承诺相同：启动即告知模型/目录/版本 |
| S5 | 模型选择器（列表 / 上下选 / 确认） | ✓ `components/model-selector.ts:40`（421 行） | ✓ `ModelSelectorScreen.java:17`（58 行） | **对齐** | 2 | java：`ModelsJsonConfig.allModels()` 平铺排序 + `SelectList` |
| S6 | 模型选择器：搜索过滤 | ✓ `model-search.ts:7,17` + `model-selector.ts` | ✗ | **缺失** | 1 | java 无过滤输入 |
| S7 | scoped-models 选择器（Ctrl+P 循环集） | ✓ `components/scoped-models-selector.ts:97`（401 行） | ✗ | **缺失** | 1 | `PiTuiApp.java:419` 直接 `appendSystemText("Use /scoped-models +<model> …")` |
| S8 | thinking level 选择器 | ✓ `components/thinking-selector.ts:36`（154 行） | ✗ | **缺失** | 2 | java 只在 `SettingsScreen.java:36` 里循环 6 个值 |
| S9 | theme 选择器 | ✓ `components/theme-selector.ts:13`（67 行）＋`settings-selector.ts` ThemeSubmenu | ✓ `ThemeSubmenuScreen.java`＋`SettingsScreen` Theme 子菜单入口 | **对齐** | 1 | **批 21（docs/32 T11）**：single/automatic 两模式＋预览＋Esc 还原＋对形持久化；独立 ThemeSelectorComponent（pi 仅导出未自用）不移植（窄差 D130） |
| S10 | 会话选择器（列表 / 选 / 确认） | ✓ `session-selector.ts:694 class SessionSelectorComponent`（1045 行） | ✓ `SessionListScreen.java:16`（50 行） | **对齐** | 2 | java：`session.listSessions()` + 前 8 位 id |
| S11 | 会话选择器：搜索 / 排序 / 命名过滤 / 路径切换 / 重命名 / 删除 / 进度 | ✓ `session-selector.ts:76-116`、`session-selector-search.ts`、`user-message-selector.ts`；`onProgress` 带 `partialSessions` 增量渲染 + `AbortController` 取消 | ✓ `component/session/`（批 14，docs/25：搜索/三档排序/命名过滤/路径开关/删除流/双 scope 进度全落） | **存疑** | 1 | **批 14 翻判定**：picker 语境与 pi 逐字节一致；**rename 面板 UI 不移植**（picker 臂 pi 本就无生产者，裁决⑦——rename 生产者是会话内 `/resume` 面，随交互选择器批收编）；`SessionListScreen`（S10 的 `/resume` 旧屏）未换引擎 |
| S12 | 会话树选择器（分支列表） | ✓ `components/tree-selector.ts:1336 class TreeSelectorComponent`（~1400 行） | ✓ `component/tree/TreeSelectorOverlay.java`＋`TreeListModel`＋`TreeListRenderer`（批 9，docs/19） | **对齐** | 2 | 批 9（docs/19）：entry 树本体落地（D37 销号）——树形缩进/连接符/gutter、折叠、活动路径、多根虚拟根全对位；窄差 D43–D46/D48 登记；`/fork` 的 lane 平表留在旧 `TreeSelectorScreen`（pi 实为 user-message 选择器，B254） |
| S13 | 树选择器：折叠展开 / 标签编辑 / 过滤模式 / 搜索 / 复制 | ✓ `tree-selector.ts:112 filterMode`（5 种）、`:632 copySelected`、`:637 updateNodeLabel`、`:1004 handleInput` | ✓ `TreeListModel.java`（折叠传染/五过滤/搜索 AND/段跳）、`TreeSelectorOverlay.java`（label 编辑臂/copy 三结局）、`AgentSession.setLabel`（持久化＋时间戳） | **对齐** | 1 | 批 9（docs/19）：折叠/标签编辑（含 `[+label time]`）/五过滤/搜索/复制全落（pi tree-selector.test.ts 13 用例对位移植 23 测） |
| S14 | 设置页（字段列表 + 循环取值） | ✓ `settings-selector.ts:465 class SettingsSelectorComponent`（~960 行） | ✓ `SettingsScreen.java:21`（153 行） | **对齐** | 2 | java `SettingsScreen.java:30-47`；pi 侧字段含 `tuiMode`/`fullscreenExitOutput`/`fullscreenScrollbar`/`fullscreenWheelScrollLines`/`outputPad`（本锚点起说明文案改为「messages, tool output, and command output」）等，java 列表是其子集 |
| S15 | 设置页：子菜单 / 主题页 / 警告页 / 实验项 | ✓ `settings-submenu.ts:31 SelectSubmenu` + `:178 SteppedSubmenu` | ✗ | **缺失** | 1 | java 是单一平铺列表 |
| S16 | config 选择器（`/config`） | ✓ `components/config-selector.ts:875`（~940 行） | ✗ | **缺失** | 1 | java 无 config 命令 |
| S17 | OAuth / 登录对话框 | ✓ `login-dialog.ts:12` + `oauth-selector.ts:58` | ✗ | **缺失** | 2 | java 的 `/login` 是 `System.console().readPassword`（`MiscCommands.java:134-148`），无对话框 |
| S18 | 首次设置向导 | ✓ `components/first-time-setup.ts:33` | ✗ | **缺失** | 1 | 一次性路径 |
| S19 | 项目信任选择器 | ✓ `components/trust-selector.ts:32` | ✗ | **缺失** | 2 | java 只有 `SettingsScreen.java:40-42` 的 `Project trust` 循环 |
| S20 | 排队消息选择器（Alt+Up 取回并编辑） | ✓ `components/user-message-selector.ts:110`（155 行） | ✓ `InteractiveMode.restoreQueuedMessages`＋`PiTuiApp` DEQUEUE 臂＋`ChatScreen.restoreToEditor`（批 10，docs/21） | **对齐** | 1 | 批 10（docs/21）：dequeue 全链接地——引擎两队列＋压缩缓冲清空、pi 序合并 `"\n\n"` 回填编辑器（排队在前草稿在后）、文案逐字＋复数形、hint 行随绑定表取键名；键平台规则同批落（Windows/WSL `alt+q`，K11 同翻） |
| S21 | show-images 选择器 | ✓ `components/show-images-selector.ts:13`（50 行） | ✗ | **缺失** | 1 | — |
| S22 | 扩展 UI：选择器 / 输入框 / 确认框 / 编辑器 | ✓ `interactive-mode.ts:2690 showExtensionSelector`/`:2773 showExtensionInput`/`:2831 showExtensionEditor` | ✓ `ExtensionSelectorOverlay`/`ExtensionInputOverlay`/`ExtensionEditorOverlay`＋`TuiExtensionUI`（pi-java-tui） | **对齐** | 1 | **批 17（2026-10-24，docs/28 T4）**：ScreenOverlay 模态层落位（裁决④/窄差 D95）、k/j 别名＋空表不提交＋hint 行逐字、倒计时标题 ({N}s)＋到期取消、confirm＝selector 特化 Yes/No、ctrl+g 外部编辑器回落链逐字；窄差 D95–D98 |
| S23 | 扩展 widget / footer / header / 状态槽 | ✓ `interactive-mode.ts:2398 setExtensionWidget`、`:2524 setExtensionFooter`、`:2551 setExtensionHeader` | ✓ `ChatScreen` 注入层（widgetsAbove/Below＋customFooter/customHeader＋extensionStatuses）＋`TuiExtensionUI` 四方法 | **对齐** | 1 | **批 17（2026-10-24，docs/28 T6）**：同 key 两区先清、MAX_WIDGET_LINES=10 截断＋逐字标记行、spacer 序逐字、footer/header dispose 序、状态段字典序＋sanitize 逐字＋截断 dim "..."；窄差 D100/D101 |
| S24 | `/hotkeys` 与 `/help` 面板 | ✓ `interactive-mode.ts` + `core/slash-commands.ts:33 hotkeys`（Markdown 渲染） | ✓ `MiscCommands.java:34`、`:122` | **对齐** | 1 | 批 25（docs/36 T8）：两命令输出改产 markdown 源（粗体标题＋管道表格），TUI 经 `ChatMessage.Markdown` 走 Markdown 渲染（pi Markdown 组件对位）——旧「纯文本落差」消除 |
| S25 | 会话统计面（`/session`） | ✓ `core/slash-commands.ts:31 session` + `components/footer.ts:120-137` 用量（计入 `usage` 条目） | ✗（TUI） / ✓（web `panels/stats.ts:1`） | **缺失** | 2 | java TUI 无统计屏 |
| S26 | `/changelog` 面板 | ✓ `core/slash-commands.ts:32` | ✓ `MiscCommands.java:120` | **对齐** | 1 | 均为文本输出 |
| S27 | `/share`（会话分享） | ✓ `session-share.ts:217`（`shareSession:57`；Radius 网关 + `BorderedLoader`） | ✓ `MiscCommands.java:76`（GitHub gist） | **存疑** | 1 | 承诺相同、**后端不同**（pi=Radius，java=gist），且 java 无加载指示器 |
| S28 | `/export`（HTML 导出） | ✓ `core/export-html/index.ts:236 exportSessionToHtml` | ✓ `export/HtmlExporter.java:21`（288 行） | **存疑** | 1 | pi 用 vendored marked.min.js + highlight.min.js + `template.html/css/js`；java 自写 `MarkdownToHtml.java`（201 行）—— 输出形状未逐项核 |
| S29 | `/bug` 故障上报流程（同意提示 → 可选摘要 → 上传或导出 zip） | ✓ `bug-report.ts:50 reportBug`（298 行）+ `core/bug-report.ts`（375 行）+ `utils/zip.ts`；命令注册 `core/slash-commands.ts:28` | ✓ `BugReportFlow.java`＋三屏（`BugReportInputScreen`/`BugReportChoiceScreen`/`BugReportLoaderScreen`）＋`core/bugreport/`＋`core/util/ZipArchives.java` | **对齐** | 1 | **批 15（2026-10-23，docs/26 T7）**：reportBug 逐段（hint 输入→转录→摘要→递交→离线门→摘要 loader→组包→上传 fallback/导出→会话尾记录）；窄差 D79/D82/D84–D86/D88 |
| S30 | **崩溃记录 + 下次启动提示**（写 crash log，下次启动提示「Run /bug」；`/bug` 提示每会话最多一次） | ✓ `core/crash-log.ts`；`interactive-mode.ts` 的 `takeUnnotifiedCrash`/`recordCrash`/`suggestBugReport`；本锚点另加死终端（EIO/EPIPE/ENOTCONN/**ENOTTY**）静默退出（stdin/stdout/stderr 三流监听，`:4332 emergencyTerminalExit`，调用点 `:4355/:4411`） | ✓ `core/crash/CrashLog.java`＋`CrashIntegration.java`＋`PiTuiLauncher`（启动提示/未捕获钩）＋`PiTuiApp.maybeSuggestBugReport` | **对齐** | 1 | **批 15（2026-10-23，docs/26 T1/T2）**：末 5 条/7 天窗/全量 notified 回写逐字＋启动提示＋uncaught 钩＋`/import` fatal（B256）＋`/bug` 建议门序逐字（R29 门恒 false 裁决④）；窄差 D80/D81/D83/D87（死终端面不做、dim 文本、JAR 名针） |
| S32 | **登录 URL 复制 + Radius 登录入口**（`/login` 展示 URL，`app.message.copy` 复制整条 URL；提供 Radius 登录/MCP 设置） | ✓ `components/auth-url.ts:9 class AuthUrlComponent`（39 行）+ `components/radius-login-selector.ts`（114 行）+ `login-dialog.ts`（238 行） | ✗ | **缺失** | 1 | java `/login` 只 `System.console().readPassword`（`MiscCommands.java:134-148`），无 URL 展示/复制/Radius |

> ~~S31 实验性 micro TUI~~ —— **作废**（pi `7fd478a2e` 删 `experimental/{micro,mini}`，裁决 R1：参照物被删即移出分母）。

---

## 渲染特性对齐

| # | 特性 | pi 有 | java 有 | 判定 | 权重 | 证据（pi 侧均对 `ea448f454` 取数） |
|---|---|---|---|---|---|---|
| R1 | Markdown 渲染（标题/粗斜体/列表/引用/代码块） | ✓ `tui/src/components/markdown.ts:236 class Markdown`（1025 行） | ✓（批 25 接线） | **对齐** | 3 | **批 25（docs/36）接线落地**：`MarkdownRenderer.toLogicalLines` 排放器＋`MarkdownBlocks` 行级 parser＋`InlineMarkup`＋`MarkdownTheme` 十键；`MessageBubble` 助手/thinking 两臂接入（pi assistant-message.ts:110-117/:146-162 对位）；**B65 销号**。子集窄差 D143/D147（行级 parser vs marked 全 CommonMark／行内不嵌套） |
| R2 | Markdown：管道表格 | ✓ markdown.ts token 渲染 | ✓（批 25） | **对齐** | 1 | 批 25（docs/36）：全边框 `┌─┬─┐/├─┼─┤/└─┴─┘`＋表头 bold＋markup 感知列宽，接入聊天管线；自然宽不收缩不 wrap（窄差 D145） |
| R3 | Markdown：删除线（严格 tokenizer） | ✓ `markdown.ts:9 StrictStrikethroughTokenizer` | ✓（批 25） | **对齐** | 1 | 批 25（docs/36）：`InlineMarkup.STRICT_STRIKETHROUGH`＝pi markdown.ts:7 正则逐字（`~~ a ~~` 不认） |
| R4 | Markdown：链接（OSC 8 超链接） | ✓ `markdown.ts` + `tui/src/terminal.ts` hyperlinks | ✓（批 25） | **对齐** | 1 | 批 25（docs/36 裁决④ **实证通过**）：`[link=href]` 标签＋underline＋mdLink 色只显文本（pi :704-707 OSC 8 臂）；MarkupParser 携 `Style.hyperlink`、`AnsiCellWriter`（AbstractBackend 渲染路）吐 OSC 8 序列——双测试钉死 |
| R5 | Markdown：流式未闭合围栏修剪 | ✓ `markdown.ts:146 trimPartialClosingFences` | ✓（批 25） | **对齐** | 2 | 批 25（docs/36 裁决⑨）：未闭合代码块尾行＝开栏符同字符真前缀 ⇒ 修剪（`MarkdownBlocks.trimPartialClosingFence`，`` ` ``/`` `` ``/完整闭合三臂测试钉死） |
| R6 | Markdown：扩展 transformer 钩子 | ✓ `markdown-transform.ts` + `interactive-mode.ts:2234 getMarkdownTransformers` | ✓（批 26 接线） | **对齐** | 1 | **批 26（docs/37 T11）落地**：`registerMarkdownTransformer` 注册面（单槽覆盖，loader.ts:361）＋`MarkdownTransformers` 链（异常吞/null 丢弃逐字）＋`MessageBubble` assistant/thinking 两臂缝；窄差 D150（isStreaming 恒 false／availableWidth 恒 -1）/D151（user 型无消费点，批 25 裁决⑤连带） |
| R7 | Mermaid 图渲染 | ✓ `components/mermaid.ts:60 createMermaidMarkdownTransformer` + `grok-mermaid` 依赖 | ✗ | **缺失** | 1 | 批 25 起 mermaid 围栏按普通代码块渲染（pi 无 transformer 时同形——D57 注记更新；本体仍缺） |
| R8 | LaTeX 数学（行内 `$…$` 与块级 `$$…$$`） | ✓ `tui/src/latex.ts`（**1506 行**）+ `test/latex.test.ts` | ✗ | **缺失** | 1 | java 全仓无 latex 命中 |
| R9 | 代码块语法高亮（多语言、highlight.js 词法） | ✓ `utils/syntax-highlight.ts`（**219 行**）+ `loadAllHighlightLanguages`（`interactive-mode.ts:133` import、`:1122` 调用）、theme 里 10 个 `syntax*` token | ✓（批 25 接线，子集） | **对齐** | 2 | 批 25（docs/36）：`SyntaxHighlighter` 四色改主题令牌（syntaxKeyword/String/Number/Comment）＋代码块消费链接通（高亮门＝lang 非空）。窄差 D146：高亮器＝关键字/字符串/数字/注释子集（pi 是 highlight.js 全词法＋supportsLanguage 白名单门） |
| R10 | Diff 渲染（+/−/hunk 着色） | ✓ `components/diff.ts:79 renderDiff`（147 行）+ `toolDiffAdded/Removed/Context` token | ✓ `DiffView.java:18`（64 行） | **存疑** | 3 | java 按行首字符给色（`DiffView.java:52-63`），无 hunk 头/行号/词级 diff；pi 的 diff 走 `tool-execution.ts` 的富渲染 |
| R11 | 逐工具渲染器（read/write/edit/bash/grep/find/ls 各自形状） | ✓ `core/tools/renderers/*.ts`（**1053 行 / 8 文件**；逻辑拆至 `core/tools/render-utils.ts` 112 行）。本锚点：bash 结果显示记录在事件上的单调 **`Took …`（durationMs，重载后仍准）**；edit 渲染吃 `outputPad` | ✗ | **缺失** | 3 | java 只有一张通用 `ToolCallCard.java:13`（39 行：`name`+`status`+截断 200 字的 args） |
| R12 | 工具执行卡：可折叠 + `Ctrl+O` 展开 + `… (N more lines, ctrl+o to expand)` | ✓ `tool-execution.ts:47 expanded`、`:157` 折叠提示、`:202 setExpanded`（408 行） | ✗ | **缺失** | 2 | java 无折叠态，`TOOLS_EXPAND` 未接线（`PiTuiApp.java:450`） |
| R13 | bash 执行组件（实时增量输出 + 宽度自适应） | ✓ `components/bash-execution.ts:21`（**223 行**；`!!` 命令 dim 边框、`outputPad` 可配）+ `test/bash-execution-width.test.ts` | ✗ | **缺失** | 2 | java TUI 无 bash 屏；web 侧有 `bashOutput` 帧（`AgentEventTranslator.java:130`） |
| R14 | 图片渲染（Kitty / iTerm2 图像协议 + 单元格尺寸探测 + 降级） | ✓ `tui/src/terminal-image.ts`（**763 行**；本锚点加 **Herdr 终端识别**：转 OSC 8 超链接、关图像协议）+ `tui/src/components/image.ts:55 class Image`（167 行） | ✗ | **缺失** | 1 | java 只有 `[image: mimeType]` 占位文本（`MessageBubble.java:102`） |
| R15 | 图片粘贴（剪贴板 → 附件） | ✓ `app.clipboard.pasteImage`（`core/keybindings.ts:75`）+ `utils/clipboard-image.ts` + 测试 | ✗ | **缺失** | 1 | java 全仓无 clipboard-image |
| R16 | 元数据气泡分型（模型切换/思考等级/工具集/压缩/分支/自定义，各带图标+色） | ✓ `interactive-mode.ts:3868 addMessageToChat` 各 message 组件 | ✓ `MetaKind.java:15-29`（6 型 enum） | **对齐** | 2 | 图标/颜色集不同（java 自选 emoji + hex），**承诺相同** |
| R17 | 压缩摘要消息卡 | ✓ `compaction-summary-message.ts:10`（68 行；鼠标左键点击展开/折叠；本锚点吃 `outputPad`） | ✓（弱形状） | **存疑** | 2 | java 走 `ChatMessage.java:50-51` 的 `System("Compacted context: …", COMPACTION)` 一行；无独立卡、无 `usage` 开销提示（`interactive-mode.ts:4121 addCompactionCostNotice`）、无点击展开 |
| R18 | 分支摘要消息卡 | ✓ `branch-summary-message.ts:10`（67 行；点击展开；本锚点吃 `outputPad`） | ✓（弱形状） | **存疑** | 1 | **本锚点翻判定**：java 并非无对应物 —— `ChatMessage.java:53-54` 对跨读 pi 会话的 `Entry.BranchSummary` 渲染 `System("Branch: …", BRANCH)` 一行，与 R17 同属「弱形状」；但分支摘要生产功能本体（B1）仍缺、无独立卡/Markdown/点击 |
| R19 | skill 调用消息卡 | ✓ `skill-invocation-message.ts:11`（64 行；点击展开；本锚点吃 `outputPad`） | ✗ | **缺失** | 1 | java `ChatMessage.from` 无 skill 分支 |
| R20 | 自定义消息卡（`custom_message`，`display` 门） | ✓ `custom-message.ts:12`（113 行；本锚点默认渲染也套 `outputPad`） | ✓ `ChatMessage.java:56-59` | **对齐** | 1 | java 用 `[customType] plainText`，pi 用 Markdown 渲染 `content` |
| R21 | 回合分隔条（`Worked for Ns • Local tools: N calls`） | ✗（pi 无此构造） | ✓ `ChatScreen.java:170-181` | **非对齐项**（java 独有，Codex 风格） | 0 | 见「pi-java 独有」，**排除出分母** |
| R22 | 启动公告组件（`earendil-announcement`、`armin`） | ✓ `components/earendil-announcement.ts:27`、`armin.ts:62 class ArminComponent`（384 行） | ✗ | **缺失** | 1 | 旧的 `daxnuts.ts` 已删，彩蛋迁到 R27 |
| R23 | 窗口/终端标题更新 | ✓ `interactive-mode.ts:1132 updateTerminalTitle` | ✗ | **缺失** | 1 | — |
| R24 | 缓存预热用量行（聊天区 `formatCacheWarmingUsage` + `/session` 的 `formatCacheWarmingStatus`／missCost／warmCost；footer 计入 `usage` 条目） | ✓ `core/cache-warmer.ts:449/433`；`interactive-mode.ts:4110 addCacheWarmingUsage` | 批 13：`ChatMessage.cacheWarmNotice`（pi :449-453 逐字经 `CacheWarmingFormatting.formatUsage`）＋`ChatScreen.appendUsageNotice` 门控＋`/session` Cache Warming 段＋footer `AgentHarness.addTokens` | **对齐** | 1 | **批 13（docs/24）翻判定**：生产者（CacheWarmer）落地 ⇒ 自有 `cache_warm` 条目照渲染；文案改 pi 逐字（"Cache warmed[: (note)]: $cost"，原自造 "Cache warming: N tokens" 删）；门 `getShowCacheMissNotices()` 三处调用点全接（onEntry/rebuild/EntryAppended 即时臂）；`/session` 段逐字（Mode/Status/decision 两行＋Cache Re-billed 挂段尾＝D68 结构差）；footer 计入（pi getSessionStats usage 条目臂对位） |
| R25 | thinking 块被丢弃提示（`Anthropic dropped N thinking blocks …`） | ✓ `interactive-mode.ts:4134 countDroppedThinkingBlocks`、`:4150 maybeShowThinkingDropNotice` | ✗ | **缺失** | 1 | 只在与上一条 assistant 消息的丢弃数**增加**时显示；java 无此构造 |
| R26 | **工具调用参数渲染（MCP 与 fallback 渲染工具显式展示 args）** | ✓ `core/tools/render-utils.ts:78 formatToolCallWithArgs` + `tool-execution.ts:143` | ✗ | **缺失** | 1 | java `ToolCallCard.java:13-37` 对全部工具走同一「name+status+截断 args」，且无 MCP 渲染器 |
| R27 | **3D 彩蛋（pi logo / Armin）+ header logo**（全屏 overlay，惰性加载） | ✓ `components/easter-egg-3d.ts`（**1292 行**）+ `easter-egg-3d.lazy.ts` + `pi-logo.ts:15 piLogoLines`（40 行） | ✗ | **缺失** | 1 | java 无任何彩蛋/logo 动画；取代旧的 daxnuts（R22） |
| R28 | **排队消息区（streaming 时聊天区与编辑器之间的 dim `Steering:`/`Follow-up:` 行）** | ✓ `interactive-mode.ts:461 pendingMessagesContainer`、`:4724 updatePendingMessagesDisplay`（末尾另有 `↳ <dequeue 键> to edit all queued messages` 提示行）；流式期间 bash/工具组件也挂该区 | ✓ `PendingQueueView.java`（含 hint 行，批 10）+ `ChatScreen.java`（`QueueUpdate` 驱动） | **存疑** | 1 | 批 10（docs/21）证据更新：dequeue hint 行（键名随绑定表大写形）＋取回编辑整条流程已补齐（S20/K11 翻对齐）；**「流式 bash/工具组件挂该区」仍缺** ⇒ 判定维持存疑 |
| ~~R29~~ | ~~安装变更警告（会话运行中 pi 被升级/替换 → 错误时提示重启命令）~~ | ✓ `interactive-mode.ts:2193 maybeShowInstallChangeWarning`＋`config.ts detectInstallChange` | ✗ | **出分母（不做）** | — | **批 22（docs/33 T3，裁决②）终裁**：java 无 package.json 安装形态且无自更新通道（E6-10 终裁联动）⇒ 检测前提不成立；`maybeSuggestBugReport` 的恒 false 门即终局；docs/04 §4 C 类登记，触发条件＝分发通道落地 |

---

## 能力单元清单（其余）

### 键绑定 / 交互

| # | 能力单元 | 类别 | pi 侧证据 | pi-java 侧证据 | 判定 | 权重 | 台账条目 |
|---|---|---|---|---|---|---|---|
| K1 | `Esc` 中断当前运行 | 键绑定 | `core/keybindings.ts:26 app.interrupt` | `PiTuiApp.java:426`、`KeybindingsManager.java:19` | **对齐** | 3 | — |
| K2 | `Ctrl+C` 清空输入 | 键绑定 | `keybindings.ts:27 app.clear` | `PiTuiApp.java:442` | **对齐** | 2 | — |
| K3 | `Ctrl+D` 空输入时退出 | 键绑定 | `keybindings.ts:28 app.exit` | `PiTuiApp.java:443-447` | **对齐** | 2 | — |
| K4 | `Ctrl+L` 打开模型选择器 | 键绑定 | `keybindings.ts:49 app.model.select` | `PiTuiApp.java:448-449` | **对齐** | 2 | — |
| K5 | `Alt+Enter` 排队 follow-up | 键绑定 | `keybindings.ts:67-70`：**Windows 上是 `ctrl+q`**，`alt+enter` 归 `tui.input.newLine` | `KeybindingsManager.java:28` 绑 `alt+enter`（默认表 `:67`）；`TamboUIAdapter.isNewlineEnter` 走 `shift+enter`；followUp 处理 `PiTuiApp.java:427-438`（压缩中也缓冲，B175） | **存疑** | 2 | H-9.2-② |
| K6 | `Ctrl+P` 模型循环（前/后） | 键绑定 | `keybindings.ts:41-48 app.model.cycleForward/Backward` | 常量存在（`KeybindingsManager.java:22`）但 `PiTuiApp.java:450` **default 分支＝死码** | **缺失** | 2 | B66 |
| K7 | `Shift+Tab` 循环 thinking level | 键绑定 | `keybindings.ts:33 app.thinking.cycle` | 同上（`KeybindingsManager.java:24` → 死码） | **缺失** | 2 | B66 |
| K8 | `Ctrl+O` 展开/折叠工具输出 | 键绑定 | `keybindings.ts:50 app.tools.expand` | 同上（`:25` → 死码），且无折叠态（R12） | **缺失** | 2 | B66 |
| K9 | `Ctrl+T` 显隐 thinking 块 | 键绑定 | `keybindings.ts:51 app.thinking.toggle` | 同上（`:26` → 死码） | **缺失** | 2 | B66 |
| K10 | `Ctrl+G` 外部编辑器 | 键绑定 | `keybindings.ts:59 app.editor.external` + `external-editor.ts:46` | 同上（`:27` → 死码） | **缺失** | 1 | B66 |
| K11 | `Alt+Up` 取回排队消息 | 键绑定 | `keybindings.ts:71-73 app.message.dequeue`（**Windows 默认改为 `alt+q`**） | `KeybindingsManager.java`（`useWindowsKeybindings` 逐字：Windows/WSL `alt+q`、其余 `alt+up`）＋`PiTuiApp` DEQUEUE 臂 | **对齐** | 1 | ~~B66~~ 批 10（docs/21，2026-10-22）接线 |
| K12 | `Ctrl+Z` 挂起进程 | 键绑定 | `keybindings.ts:29-32 app.suspend`（非 Windows） | ✗ | **缺失** | 1 | — |
| K13 | `Ctrl+X` 复制消息到剪贴板 | 键绑定 | `keybindings.ts:63 app.message.copy`（描述「Copy selection or last assistant message」） | ✗（有 `/copy` 命令 `MiscCommands.java:95-96`，无键位） | **缺失** | 1 | — |
| K14 | `Ctrl+V`/`Alt+V` 粘贴图片 | 键绑定 | `keybindings.ts:75-78 app.clipboard.pasteImage` | ✗ | **缺失** | 1 | — |
| K15 | `Ctrl+N` 命名会话过滤 | 键绑定 | `keybindings.ts:55 app.session.toggleNamedFilter` | ✓ `KeybindingsManager.SESSION_TOGGLE_NAMED_FILTER`（overlay 表）＋`SessionSelectorComponent` 消费（批 14，docs/25 T3/T10） | **对齐** | 1 | **批 14 同翻**（设计对账表只列 K16，闭环逐行实算补——命名过滤键注册并被选择器消费） |
| K16 | `app.session.*` 家族（new/tree/fork/resume + 树/会话选择器内键位） | 键绑定 | `core/keybindings.ts:79-115` | ✓ `KeybindingsManager`（批 14，docs/25 T3）：选择器六键入 overlay 表（ctrl+n/p/s/r/d＋ctrl+backspace，与全局撞名分表——批 9 C1 同构）＋ new/tree/fork/resume 四空绑定注册（pi `defaultKeys: []` 对位） | **对齐** | 2 | **批 14 翻判定**：六键被选择器消费；四空绑定 `resolve` 永不命中、`/hotkeys` 渲染 `""`（pi `formatKeys([])` 对位）；`tui.select.*` 沿批 10 KeyEvent-helper 先例不入表（裁决⑥，窄差 D73） |
| K17 | 用户键位覆盖（`keybindings.json`） | 配置 | `core/keybindings.ts`（本锚点改用 typebox `KeybindingValueSchema` 校验、跳过 `$schema`；schema 文件另出 `schemas/keybindings.schema.json`）+ migration 测试 | ✗（`KeybindingsManager.java:14` 注释「User overrides from keybindings.json → Phase 6」） | **缺失** | 1 | H-9.2-⑩ |
| K18 | 键位提示渲染（`keyText`/`keyHint`/`rawKeyHint`） | 组件 | `components/keybinding-hints.ts:48` | `KeybindingHints.java:19`（只有 `keyText:29`，无 `keyHint`/`rawKeyHint`） | **存疑** | 2 | — |
| K19 | 滚轮/触控板归一化（事件流 → 行滚动） | 交互 | `tui/src/tui-alt-screen.ts:491 scrollBy` + `tui/src/wheel-scroll.ts` | `ScrollInputNormalizer.java:20`（274 行，Codex TUI2 PR#8357 移植）+ `ScrollConfig.java:35` | **存疑** | 3 | — |
| K20 | 键盘滚动（空编辑器时 ↑↓/PgUp/PgDn/Home/End 驱动转录） | 交互 | pi：`tui/src/keybindings.ts:208-209` —— **本锚点起 top/bottom 默认键改为 `ctrl+home`/`ctrl+end`**（`home`/`end` 让还给编辑器行首行尾，`:96-101`）；↑↓/PgUp/PgDn 仍是 `tui.altScreen.*`（`:160-182`） | `PiTuiApp.java:340-360 isChatNavigation`/`scrollByKey`，其中 **HOME/END 直接滚视口**（`:346`、`:357-358`） | **存疑** | 2 | — |
| K21 | 转录搜索 | 交互 | `tui/src/alt-screen-search.ts:156-197` | ✗ | **缺失** | 1 | — |
| K22 | 鼠标文本选择 / copy-on-select | 交互 | `tui-alt-screen.ts:305 hasActiveSelection`、`:1464 copySelectionToClipboard`（失败时具体错误文案 + 5 s flash） | ✗ | **缺失** | 1 | — |
| K23 | 滚动条（绘制 + 拖拽 + 悬停加宽） | 交互 | `tui-alt-screen` scrollbar + `test/scrollbar-theme.test.ts` | `ChatViewportElement.java:129-190`、`:224-283` | **对齐** | 3 | — |
| K24 | 斜杠命令补全弹层 | 交互 | `autocomplete.ts:303 CombinedAutocompleteProvider`（835 行；前导空白后仍补全） | `SlashCompleter.java:17`（146 行，前缀匹配） | **对齐** | 2 | — |
| K25 | `@文件` 路径补全（fuzzy + `fd`） | 交互 | `autocomplete.ts:421-477`（file 分支）、`:737 scoreEntry`；CJK 标点作分隔符（`tui/src/utils.ts:58-63`）、`skill:` 按裸名模糊排序、wrapper 后补路径 | ✗ | **缺失** | 2 | — |
| K26 | 大粘贴折叠为 `[paste #N +L lines]` | 交互 | `tui/components/editor.ts:31-55`（paste marker 原子段）+ `test/editor.test.ts` | ✗ | **缺失** | 1 | — |
| K27 | 提示历史 ↑/↓ 导航（含 draft 还原、100 条上限） | 交互 | `editor.ts:347 history/historyIndex`、`:428 addToHistory` | ✗（↑/↓ 是 `moveCursorUp/Down`，`EditorComponent.java:145-146`） | **缺失** | 3 | H-9.2-② |
| K28 | undo（fish 式连续字符合并） | 交互 | `editor.ts:368 undoStack` + `undo-stack.ts:28` | `EditorComponent.java:312-328` + `EditorComponentUndoKillRingTest` | **对齐** | 3 | — |
| K29 | kill ring / yank / yank-pop | 交互 | `editor.ts:337-338` + `kill-ring.ts:46` | `KillRing.java:1` + `EditorComponent.java:287-311` | **对齐** | 2 | — |
| K30 | 词导航（`Ctrl+←/→`、`Alt+B/F`） | 交互 | `word-navigation.ts:117` | `EditorWordNav.java:1`（199 行） | **对齐** | 2 | — |
| K31 | jump mode（`Ctrl+]` / `Ctrl+Alt+]` 跳转到字符） | 交互 | `tui/keybindings.ts:106-110 tui.editor.jumpForward/Backward` | ✗ | **缺失** | 1 | — |
| K32 | Kitty 键盘协议 / `modifyOtherKeys` 解析 | 协议 | `tui/src/keys.ts:731`（modifyOtherKeys 格式注释）、`:602 parseKittySequence`、`:1292 parseKey`（**1455 行**；本锚点加 Shift 符号逻辑匹配：`Ctrl++` 可命中物理 `Shift+=`，`:792 matchesPrintableModifyOtherKeys`，且裸键也走 modifyOtherKeys 匹配） | `EventParser.java:224,309 parseCsiU`（CSI-u 有；`modifyOtherKeys` 无；无 Shift 符号归一） | **存疑** | 3 | — |
| K33 | bracketed paste 解析 | 协议 | `tui/editor.ts:328-329` | `EventParser.java:230 readPasteContent` | **对齐** | 2 | — |
| K34 | 原生剪贴板 helper（macOS/Windows/Linux） | 平台 | `native-platform.ts:1-63` + `native-module-path.ts:31` + native 测试（本锚点 clipboard 另补 Termux 路径与报错） | ✗（只有 `java.awt.Toolkit` 兜底，`MiscCommands.java:108`） | **缺失** | 1 | — |
| K35 | 终端能力探测（trueColor / hyperlinks / images / tmux 转发） | 平台 | `terminal-image.ts:140 detectCapabilities` + `terminal-colors.ts:73`（本锚点 Herdr 识别） | ✗ | **缺失** | 1 | — |
| K36 | 模式 2027 握手规避（ConPTY 字节泄漏修复） | 平台 | —（pi 无此构造） | `NoMode2027Backend.java:20` + `NoMode2027JLineBackend.java:1` | **非对齐项**（java 独有） | 0 | **排除出分母** |
| K37 | 消息卡鼠标左键点击展开/折叠（压缩摘要 / 分支摘要 / skill 调用三卡） | 交互 | ✓ `MouseRegion` 包装 + `setExpanded(!expanded)`：`compaction-summary-message.ts:10`、`branch-summary-message.ts:10`、`skill-invocation-message.ts:11` | ✗ | **缺失** | 1 | pi 的 `MouseRegion` 组件本身 pi-java 也没有（C14 缺失）⇒ 该交互在 java 侧无基座 |
| K38 | **全屏滚轮加速 + 行数配置**（按事件间隔速度自适应 1→6 行；`fullscreenWheelScrollLines` 设 1–100/auto） | 交互 | ✓ `tui/src/wheel-scroll.ts:35 class WheelScrollAccelerator`（82 行，接入 `tui-alt-screen.ts:271,:707`）+ `settings-manager.ts:1285 fullscreenWheelScrollLines` | 部分：`ScrollInputNormalizer.java:14` 有**有界加速**（Codex 实测模型：`ScrollConfig.java:28-36 wheelLines/trackpadAccel*`），但**无用户设置项** | **存疑** | 1 | pi 按速度模型 + 可配；java 用 Codex 固定默认 ⇒ 都有加速、模型与配置面不同 |
| K39 | **OSC 7501 程序状态上报**（idle/working/blocked/done/error；特性探测查询；`PI_PROGRAM_STATUS` 覆盖；stop 时 clear） | 平台 | ✓ **新锚点新增** `tui/src/program-status.ts:42 formatProgramStatus`（53 行）+ `tui/src/terminal.ts:576 setProgramStatus`（查询/DA 哨兵握手 `:291-300`）+ coding-agent `program-status-reporter.ts:18 class ProgramStatusReporter`（111 行）；接线 `interactive-mode.ts:551`（字段）、`:1000`（启动 report）、`:3412`（每事件）、`:2729`（扩展对话框 blocked） | ✗（全树无 7501/programStatus 命中） | **缺失** | 1 | 终端集成型行为，支持的终端（含未来的会话/任务列表）可见；java 无任何 OSC 状态输出 |

### TUI 组件

| # | 能力单元 | 类别 | pi 侧证据 | pi-java 侧证据 | 判定 | 权重 | 台账条目 |
|---|---|---|---|---|---|---|---|
| C1 | 滚动视图（行级 offset + follow 语义 + resize 重排） | 组件 | `tui/components/scroll-view.ts:224` + `chat-viewport.ts:46` | `ChatViewportElement.java:35`（300 行，`ScrollState` 内嵌） | **对齐** | 3 | — |
| C2 | 选择列表（上下选 + 确认/取消） | 组件 | `tui/components/select-list.ts:273` | `SelectList.java:1`（145 行） | **存疑** | 2 | pi 有分组/模糊/多选；java 只有单选线性列表 |
| C3 | 设置列表（bool/enum/string/number 多类型编辑） | 组件 | `tui/components/settings-list.ts:328` | ✗（`SettingsScreen` 用裸 Element 拼行） | **缺失** | 2 | — |
| C4 | 输入框（单行，`components/input.ts`） | 组件 | `tui/components/input.ts:32 class Input`（501 行） | ✗（统一走 `TextAreaState`） | **缺失** | 1 | — |
| C5 | loader / spinner（braille 10 帧动画 + 文案 + 颜色函数） | 组件 | `tui/components/loader.ts:13-60`（101 行） | ✗（`StatusBar.java:27-31` 是静态 `● running`） | **缺失** | 3 | — |
| C6 | working 指示器（spinner + 文案 + 计时） | 组件 | `interactive-mode.ts:2346 showWorkingStatusIndicator`、`:2361 setWorkingVisible` + `status-indicator.ts` `WorkingStatusIndicator` | ✗ | **缺失** | 3 | — |
| C7 | bordered loader（可取消的加载框） | 组件 | `components/bordered-loader.ts:68` | ✗ | **缺失** | 1 | — |
| C8 | cancellable loader | 组件 | `tui/components/cancellable-loader.ts:40` | ✗ | **缺失** | 1 | — |
| C9 | 倒计时定时器组件 | 组件 | `components/countdown-timer.ts:39` | `StatusIndicator.java:61-68` + `CountdownWake.java:1`（按帧重算，等价） | **对齐** | 2 | B3（已结案） |
| C10 | 状态指示器槽（单槽 + kind 守卫 + retry/compaction/branch 文案） | 组件 | `interactive-mode.ts:2312 showStatusIndicator` + `:2325 clearStatusIndicator` + `status-indicator.ts` | `StatusIndicator.java:18`、`ChatScreen.java:269-304`（另消费 `QueueUpdate`/`FlashStatus`） | **对齐** | 2 | B3（已结案） |
| C11 | 盒/边框面板 | 组件 | `tui/components/box.ts:14 class Box`（本锚点加 `setPaddingX`） | TamboUI `panel`（`TamboUIAdapter`） | **对齐** | 2 | — |
| C12 | stack / h-stack / v-stack / spacer / text | 组件 | `tui/components/stack.ts:154`、`h-stack.ts:44`、`v-stack.ts:33`、`spacer.ts:28`、`text.ts:7` | TamboUI `column/row/spacer/text` | **对齐** | 3 | 每轮渲染都走（布局基元） |
| C13 | truncated-text / visual-truncate | 组件 | `tui/components/truncated-text.ts:65`、`components/visual-truncate.ts` | 部分：`TextLayout.java:1`（232 行）含 displayWidth/wrap | **存疑** | 2 | — |
| C14 | mouse-region（把鼠标事件限定到区域） | 组件 | `tui/components/mouse-region.ts:33`（三张消息卡用它，见 K37） | ✗（`ChatViewportElement.handleMouseEvent:205` 是元素级） | **缺失** | 1 | — |
| C15 | dynamic-border（编辑框边框随思考等级变色） | 组件 | `components/dynamic-border.ts:25` + `interactive-mode.ts:4516 updateEditorBorderColor` | ✗ | **缺失** | 1 | — |
| C16 | 编辑组件（多行 + 折叠 + 历史） | 组件 | `tui/components/editor.ts:297 class Editor`（2473 行）+ `editor-component.ts:74` + `custom-editor.ts:148` | `EditorComponent.java:27`（461 行） | **存疑** | 3 | 缺 K26/K27/K31；见「存疑」清单 |
| C17 | 模糊匹配 | 工具 | `tui/src/fuzzy.ts:12 fuzzyMatch`（**子序列**匹配 + `-5×连续 / +2×间隔 / -10×词边界 / +0.1×位置`）、`:100 fuzzyFilter`（按空白/斜杠**分词**、每词都要匹配、分数求和） | `FuzzyMatcher.java:50 score()` 是**子串**匹配（`startsWith`→0、`indexOf`→下标、否则不匹配），**无分词** | **存疑** | 2 | B78 |
| C18 | 布局引擎（constraint/flex/嵌套） | 框架 | `tui/src/layout.ts:449` + `layout-node.ts:51` | TamboUI 布局（外部） | **对齐**（经 TamboUI） | 3 | — |
| C19 | stdin 缓冲（CSI 分片重组） | 框架 | `tui/src/stdin-buffer.ts:444` + `test/stdin-buffer.test.ts` | TamboUI `Backend` | **对齐**（经 TamboUI） | 3 | — |
| C20 | **假光标（APC 标记的反白光标，与硬件光标互斥；IME 定位标记在前）** | 组件 | ✓ **新锚点新增** `tui/src/tui.ts:205 renderFakeCursor`、`:1478 resolveFakeCursors`（聚焦组件在硬件光标显示时丢假光标）；消费方 `components/editor.ts:591,:596`、`components/input.ts:433,:491`，alt/main 两屏合成前统一解析 | ✗（无 hardware-cursor 开关、无假光标标记） | **缺失** | 1 | 仅 `showHardwareCursor=true` 时可见；⚠️ TamboUI 内部可能本就自绘反白光标（默认形态差距不大），未逐字节核，存此注脚 |

### 主题 / 样式

| # | 能力单元 | 类别 | pi 侧证据 | pi-java 侧证据 | 判定 | 权重 | 台账条目 |
|---|---|---|---|---|---|---|---|
| T1 | 内置 dark + light 主题 | 主题 | `theme/dark.json`（78 行）、`theme/light.json`（78 行；`$schema` URL 本锚点改指 `coding-agent/schemas/theme.schema.json`） | `themes/pi-dark.tcss`（37 行）、`pi-light.tcss`（37 行）+ `PiTheme.java:23` | **对齐** | 2 | — |
| T2 | 主题 token 覆盖（约 70 个语义色：`mdHeading`/`toolDiffAdded`/`syntax*`/`thinking*`/`bashMode`/`export.*`） | 主题 | `theme/dark.json`（`okhsl(...)` 值，含 `appearance` 字段） | ✗（8 条 CSS 规则：Screen/ChatPanel/scrollbar×4/StatusBar/EditorComponent/Separator） | **缺失** | 3 | 每条消息的配色都走 |
| T3 | 用户自定义主题（JSON + schema 校验） | 主题 | **本锚点搬家**：校验器 `theme/theme-schema.ts:1`（typebox，146 行，按需加载省 ~17 MB 模块图）、token 描述表 `theme/theme-tokens.ts:1`（121 行）、发布的 JSON Schema `coding-agent/schemas/theme.schema.json`（365 行）；旧 `theme/theme-json.ts`（148 行）与内嵌 `theme-schema.json`（361 行）**已删** | 部分：`.tcss` 文件（`PiTheme.java:42-89`），**无 schema、格式不同** | **存疑** | 1 | — |
| T4 | 主题热重载 / 切换重渲染（文件 watch + `themed-text` 失效重建） | 主题 | `theme-controller.ts:58 InteractiveThemeController`（**253 行**）+ `components/themed-text.ts:12 class ThemedText` + 测试 | ✗ | **缺失** | 1 | — |
| T5 | 思考等级 → 边框/文字颜色映射 | 主题 | `theme.ts:348 getThinkingBorderColor`/`thinkingOff…Max` 8 档 | ✗ | **缺失** | 2 | — |
| T6 | 语法高亮色板（10 token） | 主题 | `dark.json`：`syntaxComment/Keyword/Function/Variable/String/Number/Type/Operator/Punctuation` | `SyntaxHighlighter.java:21-23`（3 色） | **存疑** | 2 | — |
| T7 | 导出专用主题色（`export.pageBg/cardBg/infoBg`） | 主题 | `dark.json` 的 `export` 对象 + `export-html/index.ts` | ✗ | **缺失** | 1 | — |
| T8 | 主题解析优先级（CLI > settings > 扩展候选 > dark） | 主题 | `theme.ts`（**1086 行**）`:568 getThemeByName` + `:672 setRegisteredThemes`（`interactive-mode.ts:667`） | `PiTheme.java:71 resolveTheme` | **对齐** | 2 | — |
| T9 | 主题按扩展贡献注册 | 主题 | `interactive-mode.ts:667 setRegisteredThemes` | `PiTheme.resolveTheme(candidates)`（`PiTheme.java:71`） | **对齐** | 1 | — |
| T10 | **OKLab / OKLCH / OKHSL 色彩系统 + 混色**（`Color` 三态、`oklch`/`okhsl` 值、`mix`；theme 的 `okhsl(...)` 值靠它解析） | 主题 | ✓ `tui/src/colors.ts`（367 行）+ `tui/src/oklab.ts`（233 行，Björn Ottosson 参考实现移植） | ✗（全树无 oklab/oklch/okhsl/混色命中；TamboUI 亦无） | **缺失** | 2 | pi 的 dark/light/system 全部主题色经此换算 |
| T11 | **system 主题（由终端调色板派生，三层 fallback）** | 主题 | ✓ `theme/system-theme.ts`（655 行：色族/对比度曲线/OKHSL 生成）+ `theme.ts:143 setTerminalColors`/`:615 detectColorFgBgTheme` | ✗（`PiTheme` 只有 dark/light 两个 `.tcss`，无终端派生） | **缺失** | 1 | — |

### web 面（对齐相关部分）

> pi 仓库内**没有** web UI（`grep -rln "WebSocket" packages/*/src` 只命中 AI provider 的 HTTP/WS 客户端与 settings-selector，`find . -name "*web-ui*"` 零命中）。因此 web 的**呈现层**单列在「pi-java 独有」；下面只列**事件/线格式**这一面对齐相关的能力单元，其 pi 侧证据取自 pi 的 agent-loop 事件词汇与 pi-webui 基线协议。

| # | 能力单元 | 类别 | pi 侧证据 | pi-java 侧证据 | 判定 | 权重 | 台账条目 |
|---|---|---|---|---|---|---|---|
| W1 | 流式事件词汇（`agent_start`/`message_update`） | web 协议 | `interactive-mode.ts:3400 subscribeToAgent`（`:3406 handleEvent`）+ pi-webui `shared/protocol.ts` | `AgentEventTranslator.java:39-104` | **对齐** | 3 | B40（已结案） |
| W2 | `tool_execution_start/update/end` 由**真实工具执行事件**驱动 | web 协议 | pi `agent-loop.ts` 的工具执行事件（事件另带 `durationMs`，见 R11） | `AgentEventTranslator.java:162-186` | **对齐** | 3 | B43（已结案） |
| W3 | `turn_end` 带 `{message, toolResults}` | web 协议 | `agent/types.ts:438` | `AgentEventTranslator.java:149-160` | **对齐**（残余：错误路 `message` 为 null ⇒ 省键） | 3 | B42/B49（已结案，残余登记） |
| W4 | `message_update` 顶层带 `usage` | web 协议 | `json-event.ts:11-15,56-60` | `AgentEventTranslator.java:100-104`（经 `WebWireJson.assistantNode`） | **对齐** | 3 | B28（已结案） |
| W5 | `toolcall_start` 带 `id`+`toolName` | web 协议 | `json-event.ts:23-30` | `StreamPartialBuilder.emitToolCallStart(id,name):390` | **对齐** | 3 | B29（已结案） |
| W6 | 消息 wire 形状（`role`、`toolCallId`、块判别字面量 `toolCall`/`thinking`） | web 协议 | pi-ai `types.ts` 消息形状 | `WebWireJson.java:26-100` | **对齐** | 3 | B48（已结案） |
| W7 | 终局 assistant 消息 `usage` 非空 | web 协议 | `types.ts:439` | `WebWireJson.java:92`（`B41` 已修） | **对齐** | 3 | B41（已结案） |
| W8 | `queue_update` / `compaction_*` / `auto_retry_*` 推送前端 | web 协议 | pi `agent-session.ts:1047`（queue_update 发射）、压缩/重试事件 | ✗ `AgentEventTranslator.java:63-65` 明确「暂不推前端」 | **缺失** | 1 | H-9.2-⑨、B34、B35 |
| W9 | 消息 `timestamp` 上 wire | web 协议 | pi-ai 消息必填 `timestamp`（`types.ts:417-421`） | ✗ **刻意偏差**（`WebWireJson.java:58` 注释「维持既有『wire 无消息 timestamp』的有意偏离」） | **存疑** | 1 | B50、H-9.2-⑧ |
| W10 | `agent_end.messages` 整表替换 | web 协议 | pi `agent_end` 带本 pass `newMessages`（本锚点 `agent_settled` 另带 `aborted`，不影响本面） | `AgentEventTranslator.java:113-124`（空消息时不带 `messages` 键，防前端清空） | **存疑** | 3 | A8 |
| W11 | 宿主消费者并发契约声明（TUI/RPC/web 三面） | 架构 | —— | 收敛到 `PiLaneSink.emit` 锁，无显式声明 | **存疑** | 1 | A6 |
| W12 | `transcript()` 读取点清点（web 三处） | 架构 | —— | `WebDispatcher.java:235,349,380` | **存疑** | 1 | A2 |
| W13 | web 会话范围（`--session-dir` / `latest(cwd)`） | 行为 | pi `findMostRecentSession(dir)` 只读一个项目目录 | `PersistentSessionRepositories.latest(cwd)`（包⑫ 已修） | **对齐** | 2 | A12（已结案） |
| W14 | `--no-session` 在 web 路径生效 | 行为 | pi 语义「别落盘」 | ✗ `SessionPersistence.resolvePersistentWeb` 的 `args` 形参一句话都没用 | **缺失** | 1 | **B54** |
| W15 | 会话列表 API 带 `onProgress`（异步进度 + 增量结果 + 可取消） | 行为 | pi `core/session-manager.ts:884-889 SessionListProgress`（带 **`partialSessions`** 第三参 + `AbortSignal`；`:943 onProgress`）；`session-selector.ts:76-116` 消费 | ✓ repo 层批 14（listRich 进度＋取消）＋TUI 选择器批 14＋**web 面批 22** | **对齐** | 1 | **批 22（docs/33 T1）**：`sessionsProgress`/`cancelSessions` 协议帧＋代次闸＋虚拟线程富列表＋前端渐进渲染（picker 关闭发 cancelSessions）；B55 批 14 已销 |
| W16 | `find()` 的范围（`--session <id>` 跨项目查找） | 行为 | pi `findLocalSessionByExactId(id, cwd, sessionDir)` 项目内 | `PersistentSessionRepositories.find():59` 走 `all()` ⇒ O(全部)、跨项目 | **缺失** | 1 | **B53** |

---

## pi-java 独有（非对齐项）

> pi 仓库内**零对应物**。**不计入**完成度分母，**权重一律记 0**（下表不设「权重」列）。

| 能力 | 说明 |
|---|---|
| **本地 Web UI 整体**（`pi-java-web`，1912 Java + 1654 TS + 675 CSS） | pi 仓库无 web 包。基线取自**仓库外**的第三方项目 `Zetaphor/pi-webui`，前端复用外部 npm 包 `@mariozechner/pi-web-ui` 的 `<message-list>` / `<streaming-message-container>` / `<message-editor>` / `<theme-toggle>` 自定义元素（`frontend/package.json:9-16`、`client/main.ts:809-870`） |
| WebSocket 网关 + 网关令牌鉴权 | `PiWebServer.java:1`（**325** 行，本锚点期修了 listener 就绪竞态）、`GatewayToken.java:1`（91 行）、`PiWebServerAuthTest` + `PiWebServerStartupTest` |
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
| 回合分隔条 `Worked for Ns • Local tools: N calls` | `ChatScreen.java:170-181`（Codex CLI 风格） |
| 元数据气泡 emoji 图标集（⚙/🧠/🗜/⎇/◆） | `MetaKind.java:15-29` |
| Codex 风格启动方框卡 | `WelcomeOverlay.java:41 buildCard` |
| 原生滚动回退内联 shell（`InlineTuiShell`） | `InlineTuiShell.java:1`（390 行） |

---

## 整块缺失

| 子系统 | pi LOC | 说明 |
|---|---|---|
| **终端图像协议**（Kitty / iTerm2） | **930**（`terminal-image.ts` 763 + `tui/src/components/image.ts` 167） | 含能力探测、单元格尺寸探测、base64 尺寸解析、非 PNG 转码、WezTerm 滚动保留、Herdr 识别、降级占位；测试 `terminal-image.test.ts` + `block-images.test.ts` |
| **LaTeX 数学渲染** | **1506**（`tui/src/latex.ts`） | 行内/块级 `$…$`、Unicode 转换、tokenizer、堆叠脚本、字体切换；测试 `latex.test.ts` |
| **Markdown 组件** | **1025**（`tui/components/markdown.ts`） | 严格删除线 tokenizer、链接、hr、任务列表、流式围栏修剪、缓存、padding/背景；测试 `markdown.test.ts`（pi-java 有 `MarkdownRenderer` 221 行但**生产零调用**） |
| **alt-screen 搜索 + 选择** | **327 + ~500**（`alt-screen-search.ts` + `tui-alt-screen.ts` 的选择/复制/闪烁部分） | 搜索索引、匹配导航、文本选择、自动滚动、copy-on-select、OSC133 zone |
| **扩展 UI 组件族** | **~1000**（`extension-selector/input/editor` + `interactive-mode.ts:2398-2870`） | `custom<T>()` / `setFooter` / `setHeader` / widget / 通知 / 错误显示；pi-java 仅 RPC 通道 |
| **选择器族（未实现 9 个）** | **~2900**（config 875 + scoped-models 401 + session-selector 1045 非基础部分 + thinking 154 + theme 67 + trust 134 + oauth 214 + login 238 + first-time-setup + show-images 50 + user-message 155） | 见 S6-S9、S11、S15-S21、S32（S13 批 9 已闭环） |
| **逐工具渲染器** | **1053 + 112**（`core/tools/renderers/*.ts` + `render-utils.ts`） | read/write/edit/bash/grep/find/ls 各自展示形状；MCP/fallback 工具的 args 展示；记录在案的 `Took` 计时 |
| **工具执行卡（可折叠 + 流式）** | **631**（`tool-execution.ts` 408 + `bash-execution.ts` 223） | `Ctrl+O` 展开、部分结果、diff、图像 |
| **主题系统（JSON + schema + 热重载 + OKLab 色值 + system 主题 + 70 token）** | **3105**（`theme.ts` 1086 + `theme-controller.ts` 253 + `theme/theme-schema.ts` 146 + `schemas/theme.schema.json` 365 + `theme/system-theme.ts` 655 + `tui/src/colors.ts` 367 + `tui/src/oklab.ts` 233） | pi-java 只有 2 个各 37 行的 `.tcss`，**无 schema、无色值系统、无 system 主题** |
| **提示历史 / 大粘贴折叠 / jump mode** | **~400**（`editor.ts` 相应段） | 见 K26/K27/K31 |
| **loader / spinner / bordered-loader** | **~260**（`loader.ts:101` + `bordered-loader.ts:68` + `cancellable-loader.ts:40` + `alt-screen-flash.ts:51`） | pi-java 无任何动画指示器 |
| **原生剪贴板 / 终端能力探测** | **~170**（`native-platform.ts:63` + `native-module-path.ts:31` + `terminal-colors.ts:91`） | 含 macOS/Windows/Linux native `.node` helper（另含 Termux 路径） |
| **`@文件` 补全（fuzzy + fd）** | **~500**（`autocomplete.ts:303+` 的文件分支） | pi-java `SlashCompleter` 只做前缀匹配 |
| **故障上报 + 崩溃日志** | **~840**（`bug-report.ts:298` + `core/bug-report.ts:375` + `core/crash-log.ts:170` + `utils/zip.ts`） | `/bug` 全流程 + 崩溃落盘与下次启动提示；见 S29/S30 |
| **彩蛋族（3D pi logo / 3D Armin / header logo / 公告）** | **~1700**（`easter-egg-3d.ts:1292` + `easter-egg-3d.lazy.ts` + `pi-logo.ts:40` + `armin.ts:384` + `earendil-announcement.ts`） | 见 R22/R27 |
| **OSC 7501 程序状态** | **164**（`tui/src/program-status.ts` 53 + `program-status-reporter.ts` 111） | 终端任务列表/状态集成；见 K39 |
| **前端测试** | **0（pi 侧无 web）** | pi-java 前端**零测试**：`src/main/frontend/package.json` 无 `test` 脚本、无 vitest/jest/playwright 依赖、无 `*.test.*`/`*.spec.*` 文件；Maven `frontend-maven-plugin` 只跑 `npm ci` + `npm run build` |

---

## 台账纠错

| 条目 | 台账说什么 | 实际 | 证据 |
|---|---|---|---|
| **H-9.2-⑦** | 「Anthropic 路径的**思考内容仍按 TextContent 处理**」 | **已过期** —— Anthropic 车道已发 `ThinkingStart`/`ThinkingContent` | `pi-java-ai/.../protocol/AnthropicMessagesApi.java:207`、`:226`；`ContentBlock.ThinkingContent` 已带 `signature`/`redacted`（`MessageBubble.java:90-92` 的 `case ContentBlock.ThinkingContent(var text, _, _)`） |
| **H-9.2-⑧** | 「webui 未决字段**待 Stage A 实机钉死**」，指 `message_end` 的 timestamp 去重 | **已定但结论与设计稿相反** —— 落地的是**不给消息带 timestamp**（有意偏离），前端直贴不判重 | `WebWireJson.java:58` 注释原文；`client/main.ts` 的 `message_end` 分支无判重 |
| **H-9.2-⑨** | 「web 端 `queue_update` / `compaction_*` / `auto_retry_*` **暂不推前端**」 | **仍成立**（措辞与行为都对） | `AgentEventTranslator.java:63-65` `default -> { // queue_update / compaction_* / auto_retry_* 等暂不推前端 }` |
| **H-9.2-⑩** | 「`keybindings.json` 用户覆盖 → Phase 6」 | **仍成立** | `KeybindingsManager.java:14` 同句原文；`bindings` 表只有硬编码默认（`:58-68`），无文件读取 |
| **H-9.2-②** | 「`app.message.followUp`（Alt+Enter 排队）与 Alt+Enter=换行 **键位占用**」 | **仍成立，且比台账记的更严重** —— pi 在 **Windows 上 followUp 是 `ctrl+q`**、dequeue 是 `alt+q`，`alt+enter` 归换行 | pi `core/keybindings.ts:67-73`（`windowsKeybindings ? "ctrl+q" : "alt+enter"`；dequeue `? "alt+q" : "alt+up"`）；java `KeybindingsManager.java:67` 把 `alt+enter` 绑给 `FOLLOW_UP`，`PiTuiApp.java:307` 的 `isNewlineEnter` 走 `shift+enter` |
| **H-9.2-③** | 「`StreamPartialBuilder`/`ToolCallAccumulator` 仍为**单工具调用模型**，需多槽位」 | **仍成立**（字段仍是单槽） | `StreamPartialBuilder.java:60-72`：`toolCallId`/`toolCallName`/`toolBlockIndex`/`toolArgBuf` 各一个。⚠️ 属 `pi-java-ai` 面 |
| **E5** | 「`EventParser.java` **515** 行 —— 已登记例外」 | **行数正确**，例外理由成立（只覆写 `parseControlChar` 一处） | `EventParser.java` 实测 515 行；类注释 `:20-29` 明写「only `parseControlChar(int, Bindings)` below differs from upstream 0.4.0」 |
| **B45** | 「`pi-java-tui` 的 `spotbugs:check` 4 条 finding」 | **已修**（结案无误） | `ChatScreen.java:44/:59/:62` 已加 `volatile`、`:66-67` 已换 `AtomicInteger`、`finishRun:171` 一次读 |
| **B37 / B3 / A12 / B40-B44** | 均已结案 | **核实无误** | `ChatScreen.java:138-145`（错误只进聊天区）、`:269-304`（会话事件面）、`WebWireJson.java:92`、`AgentEventTranslator.java:162-186` |
| **A2** | 「TUI / web / RPC / SQLite 四个读取点**未清点**」 | **部分已清**：TUI **不读** `transcript()`；web 读**三处**；coding-agent 内部读多处 | TUI 侧 `pi-java-tui/src` 对 `transcript()` 零命中；web `WebDispatcher.java:235,349,380`。⇒ TUI 那半可结案，web/RPC/SQLite 那半仍开 |
| **B47** | 「`ChatScreen.runToolCalls` 由 `StreamEvent.ToolCallStart` 计数，pi 由真实工具执行事件驱动」 | **仍成立** | `ChatScreen.java:133 case StreamEvent.ToolCallStart ignored -> runToolCalls.incrementAndGet()` |
| **B46** | 「BashTool 从不调 onUpdate；tool_execution_update 生产上永不发射（仅测试桩）」 | **台账措辞已过期（半修）**：生产侧已落 —— `BashTool.java:121` 生产路径构造 `BashUpdateEmitter`（agent-core），事件过 `PiToolRunner`/`SessionRunner`/`AgentSessionEvent.ToolExecutionUpdate`/`JsonEventMapper`；**TUI 消费面仍无**（ChatScreen 无 bash 事件分支，R13 缺失；web 有 `bashOutput` 帧） | `BashUpdateEmitter.java:92`、`BashTool.java:121` |
| **B38** | 「compaction_end 后不重建聊天区；Java TUI 对压缩一无所知」 | **台账措辞部分过期**：java 现消费 `CompactionStart/End`（状态指示器，`ChatScreen.java:295-299`）、压缩摘要条目经 EntryObserver 以一行 `System` 追加（`ChatMessage.java:50`，R17 弱形状）、队列另有 `QueueUpdate`/`FlashStatus`；仍缺 pi 的整表 clear＋重渲染与摘要卡 | `ChatScreen.java:295-303` |
| **B39** | 「压缩窗口 Esc 换绑，随 B36」 | **仍开**：B36（无 `abortCompaction` 句柄）台账仍开，Esc 在压缩窗口未换绑 | `PiTuiApp.java:426`（中断不区分压缩窗口） |
| **B65** | MarkdownRenderer/SyntaxHighlighter 死代码 | **仍成立**：`MarkdownRenderer` 生产零调用；`SyntaxHighlighter` 唯一调用点是输入编辑器 `EditorElement.java:123` | 全仓 grep（非 test）仅命中自身与 `EditorElement.java` |
| **B66** | ~~6~~ 4 个已声明键位未接线 | **仍成立（核账后剩 4）**：THINKING_CYCLE/TOOLS_EXPAND/THINKING_TOGGLE/EXTERNAL_EDITOR 走 `PiTuiApp.java` default 分支；MODEL_CYCLE 随 ai 批 3b（B137）已接、DEQUEUE 随批 10（docs/21）已接 | 同左 |
| **B78** | FuzzyMatcher 语义不同 | **仍成立**（子串 vs pi 子序列+分词），挂载点仍只服务 `SelectList.java:142` | `FuzzyMatcher.java:50` vs `fuzzy.ts:12,100` |
| **B145** | 启动/登录后定向目录刷新未接线 | **本次未核**（目录刷新在 coding-agent/RPC 面，超出 TUI 判定），保持台账原文 | —— |
| **H-9.2-①④⑤⑥** | inline 超长草稿 artifact / STDIN 共享管道 / Codex logo 动画 / 命令恢复 | **本次未核**（超出 TUI 判定面或需实机复现），**保持台账原文** | —— |

---

## 汇总

> **当前单元集 148 条**（判定单位；另 2 条 java 独有非对齐项 R21/K36 已剔出分母）。

| 档 | 条数 |
|---|---:|
| **对齐** | **40** |
| **缺失** | **83** |
| **存疑** | **25** |
| 非对齐项（pi-java 独有，**不计入分母**） | 2 |
| **合计（枚举）** | **150** |
| **完成度（未加权）** | **42 / 148 = 28.4%** |

**分面完成度（148）**

| 面 | 对齐 | 缺失 | 存疑 | 判定单位 | 完成度 |
|---|---:|---:|---:|---:|---:|
| 屏幕/面板（S1–S30 + S2a–d + S32，35） | 12 | 20 | 3 | 35 | **34.3%** |
| 渲染特性（R1–R29，28 单元〔R29 批 22 出分母〕，含 R21） | 2 | 20 | 5 | 27 | **7.4%** |
| 键绑定/交互（K1–K39，39 单元，含 K36） | 10 | 22 | 6 | 38 | **26.3%** |
| TUI 组件（C1–C20，20 单元） | 7 | 9 | 4 | 20 | **35.0%** |
| 主题/样式（T1–T11，11 单元） | 3 | 6 | 2 | 11 | **27.3%** |
| web 对齐面（W1–W16，16 单元） | 9 | 3 | 4 | 16 | **56.3%** |
| **合计** | **42** | **82** | **24** | **148** | **28.4%** |

> 非对齐项 2 条（R21 回合分隔条、K36 模式 2027 规避）已从分母剔除。

**LOC 口径 vs 能力单元口径**

| 口径 | pi | pi-java | 比例 |
|---|---|---|---|
| UI 总 LOC（含 pi 的框架层） | **43685** | 6799 | **15.6%** |
| 应用层 LOC（剔 pi `packages/tui` 框架层） | **21895** | 6799 | **31.1%** |
| **能力单元** | — | — | **27.0%** |

**结论**：「TUI 大约只对齐 30%」这个线索 —— **能力单元口径下勉强成立**（**27.0%**，40/148）。三个口径分叉的成因：

1. **含框架层的 LOC 口径（15.6%）偏低**：pi 的 `packages/tui`（19505 行）在 pi-java 侧被**外部库 TamboUI** 顶替，把它算进 pi-java 的分母不成立。
2. **应用层 LOC 口径（31.1%）偏高**：LOC 对「整块缺失」不敏感 —— pi 的 21895 行里有 **12000+ 行**属于 **17 个整块子系统**（图像协议、LaTeX、Markdown 组件、alt-screen 搜索、扩展 UI、选择器族、工具渲染器、主题系统、彩蛋族、OSC 7501…），这些在 pi-java 侧是 **0 行**，而不是「少写了几行」。
3. **能力单元口径（27.0%）最贴近判据**：它把「有没有这个行为承诺」与「实现得多厚」分开计。**渲染特性面只有 7.1%** 是最大短板（28 项判定里 21 项缺失，且 `MarkdownRenderer`/`SyntaxHighlighter` 两块**存在但是死代码**）；**web 对齐面 50.0%** 是最高面（web 的事件/线格式面已被包⑥⑦⑨⑩⑪逐条对齐过）。

---

## 存疑清单（全部 25 条）

| # | 能力单元 | 为何存疑 | 证据 |
|---|---|---|---|
| S2 | 全屏 alt-screen 模式 | java 用 TamboUI 通用 runner，pi 是自带搜索/选择/复制/图像的专用实现；承诺「全屏 + 内部视口」相同，能力面差 4 块（S2a-d；OSC 7501 状态另见 K39） | `PiTuiLauncher.java:61` vs `tui-alt-screen.ts:202` |
| S12 | 会话树选择器 | ~~java 列 lane 名 + leafId~~ 批 9（docs/19）已对齐：entry 树本体（`TreeSelectorOverlay`＋`TreeListModel`＋`TreeListRenderer`） | `component/tree/*` vs `tree-selector.ts:1336` |
| S27 | `/share` | 承诺相同、**后端不同**（pi=Radius 网关，java=GitHub gist），且 java 无 `BorderedLoader` | `session-share.ts:57` vs `MiscCommands.java:76-92` |
| S28 | `/export` HTML | pi 用 vendored marked/highlight + 模板，java 自写 `MarkdownToHtml.java`；输出形状未逐项核 | `export-html/index.ts:236` vs `HtmlExporter.java:21` |
| R10 | Diff 渲染 | java 按行首字符着色，无 hunk 头/行号/词级；pi 的 diff 走富渲染器 | `DiffView.java:52-63` vs `components/diff.ts:79` |
| R17 | 压缩摘要消息卡 | java 一行 `System`，pi 是独立卡 + `usage` 开销提示 + **鼠标点击展开** | `ChatMessage.java:50` vs `compaction-summary-message.ts:10` + `interactive-mode.ts:4121` |
| **R18** | **分支摘要消息卡** | **本锚点翻判定**：java 有跨读条目的一行弱形状（旧图误记「无」），但无卡/Markdown/点击，生产者 B1 缺 | `ChatMessage.java:53-54` vs `branch-summary-message.ts:10` |
| **R24** | **缓存预热用量行** | ~~java 仅渲染跨读 pi 会话的 `cache_warm` 条目~~ 批 13（docs/24）已对齐：生产者落地＋文案逐字＋门＋`/session` 段＋footer 计入 | `ChatMessage.java:114` vs `cache-warmer.ts:449` + `interactive-mode.ts:4110` |
| **R28** | **排队消息区** | dim Steering/Follow-up 行对齐；缺 dequeue 提示行/取回编辑流程（S20）、流式 bash/工具挂载 | `PendingQueueView.java:15` vs `interactive-mode.ts:4724-4740` |
| K5 | `Alt+Enter` 排队 follow-up | 键位与 pi 的 Windows 默认（`ctrl+q`）冲突；「Alt+Enter=换行」被 java 换成 Shift+Enter | `KeybindingsManager.java:67` vs `keybindings.ts:67-70` |
| K18 | 键位提示渲染 | java 只有 `keyText`，无 `keyHint`/`rawKeyHint`/`keyDisplayText` | `KeybindingHints.java:29` vs `keybinding-hints.ts:48` |
| K19 | 滚轮/触控板归一化 | java 移植自 Codex TUI2 PR#8357，pi 现代码是 `wheel-scroll.ts` ⇒ 都有归一化、模型不同 | `ScrollInputNormalizer.java:20` vs `tui-alt-screen.ts:491` |
| **K20** | **键盘滚动（Home/End）** | **本锚点翻判定**：pi 把视口 top/bottom 改成 `Ctrl+Home/End`，裸 Home/End 归还编辑器；java 仍是空编辑器时 Home/End 滚视口。↑↓/PgUp/PgDn 两侧一致 | `PiTuiApp.java:346,357-358` vs `tui/keybindings.ts:96-101,208-209` |
| K32 | Kitty 键盘协议 | java 有 CSI-u（`parseCsiU:309`）但无 `modifyOtherKeys`（`CSI 27;m;k~`）、无 Shift 符号逻辑匹配；覆盖率未逐序列核 | `EventParser.java:224,309` vs `keys.ts:731,792,1292` |
| K38 | 全屏滚轮加速 + 行数配置 | pi 有专用 `WheelScrollAccelerator`（速度模型 + `fullscreenWheelScrollLines` 1–100/auto），java 有 Codex 固定默认的有界加速、**无用户设置项** | `wheel-scroll.ts:35` vs `ScrollInputNormalizer.java:14` + `ScrollConfig.java:28-36` |
| C2 | 选择列表 | pi 有分组/模糊/多选，java 是单选线性列表 | `SelectList.java:1` vs `select-list.ts:273` |
| C13 | truncated-text / visual-truncate | java 的截断能力散在 `TextLayout`/`ToolCallCard.truncate`，无独立组件、行为未逐项核 | `TextLayout.java:1`、`ToolCallCard.java:33-37` |
| C16 | 编辑组件 | 核心编辑语义对齐（undo/kill-ring/词导航），但缺 3 项（K26/K27/K31）⇒ 不能判「对齐」 | `EditorComponent.java:27` vs `editor.ts:297` |
| C17 | 模糊匹配 | pi 是**子序列**匹配 + 按空白/斜杠分词（`fuzzy.ts:12,100`），pi-java 是**子串**匹配 + 无分词（`FuzzyMatcher.java:50`）；且挂载点错位 | `FuzzyMatcher.java:50` vs `fuzzy.ts:12,100` + `select-list.ts:61` |
| T3 | 用户自定义主题 | java 支持 `.tcss`，pi 是 JSON + typebox schema 校验（本锚点 schema 拆为 `theme-schema.ts` + 发布 `schemas/theme.schema.json`）⇒ 格式/校验能力都不同 | `PiTheme.java:42-89` vs `theme/theme-schema.ts:1` |
| T6 | 语法高亮色板 | java 3 色 vs pi 10 token；且 java 的代码块路径是死代码（R9） | `SyntaxHighlighter.java:21-23` vs `dark.json` `syntax*` |
| W9 | 消息 timestamp 上 wire | **刻意偏差**（`WebWireJson.java:58` 明写），与 pi 必填冲突；台账 B50 仍开 | `WebWireJson.java:58` vs `ai/src/types.ts:417-421` |
| W10 | `agent_end.messages` 整表替换 | java 在空消息时**不带 `messages` 键**（防前端清空），pi 带 `newMessages` ⇒ 载荷不同 | `AgentEventTranslator.java:113-124` |
| W11 | 宿主并发契约 | 无显式声明（台账 A6） | —— |
| W12 | `transcript()` 读取点清点 | TUI 半已清（不读），web/RPC/SQLite 半未清 | 见台账纠错 A2 行 |

> 注：W11/W12 与台账 A2/A6 同源，属「架构面」。上表 25 行即全部存疑条目。

---

## 缺失清单（全部 85 条）

**屏幕/面板（20）**：S2a 转录搜索 · S2b 文本选择/copy-on-select · S2c flash 提示 · S2d 全屏图像 · S6 模型搜索 · S7 scoped-models 选择器 · S8 thinking 选择器 · S9 theme 选择器 · S11 会话选择器高级功能 · S15 设置子菜单 · S16 config 选择器 · S17 OAuth/登录对话框 · S18 首次设置向导 · S19 信任选择器 · S20 排队消息选择器 · S21 show-images 选择器 · S22 扩展 UI 四件套 · S23 扩展 widget/footer · S25 会话统计面

**渲染特性（21）**：R1 Markdown 渲染（死代码）· R2 表格 · R3 删除线 · R4 链接 · R5 流式围栏修剪 · R6 transformer 钩子 · R7 mermaid · R8 LaTeX · R9 代码块高亮（死代码）· R11 逐工具渲染器 · R12 工具卡折叠 · R13 bash 执行组件 · R14 图像协议 · R15 图片粘贴 · R19 skill 调用卡 · R22 公告 · R23 终端标题 · R25 thinking 丢弃提示 · R26 工具调用参数渲染 · R27 3D 彩蛋/logo · **R29 安装变更重启警告（新锚点新增）**

**键绑定/交互（21）**：K6 Ctrl+P · K7 Shift+Tab · K8 Ctrl+O · K9 Ctrl+T · K10 Ctrl+G · K12 Ctrl+Z · K13 Ctrl+X · K14 Ctrl+V · K15 Ctrl+N · K16 app.session.* · K17 keybindings.json · K21 转录搜索 · K22 鼠标选择 · K25 @文件补全 · K26 大粘贴折叠 · K27 提示历史 · K31 jump mode · K34 原生剪贴板 · K35 终端能力探测 · K37 消息卡鼠标点击展开 · **K39 OSC 7501 程序状态（新锚点新增）**（~~K11~~ 批 10 已翻对齐）

**TUI 组件（9）**：C3 settings-list · C4 input · C5 loader/spinner · C6 working 指示器 · C7 bordered loader · C8 cancellable loader · C14 mouse-region · C15 dynamic-border · **C20 假光标（新锚点新增）**

**主题（6）**：T2 主题 token 覆盖 · T4 热重载 · T5 thinking 颜色映射 · T7 导出主题色 · T10 OKLab/OKLCH 色彩系统 · T11 system 主题

**web 对齐面（4）**：W8 queue/compaction/retry 推送 · W14 `--no-session`（**B54**）· W15 `onProgress`（**B55**）· W16 `find()` 范围（**B53**）

---

## pi 侧漂移复核（`200387122` → `ea448f454`）

**方法**：一律 `git show ea448f454:<path>` 取数（不读 pi 工作树）；范围 `git diff --name-status 200387122 ea448f454 -- packages/tui packages/coding-agent/src`（剔 test），本图被引文件中 **20 个**有改动，逐个对 diff；未改动文件的 file:line 原样有效。

### A. 结果分类（按单元行计）

| 类 | 行数 | 条目 |
|---|---:|---|
| **判定翻转** | **3** | **K20** 对齐→存疑（视口 top/bottom 改 Ctrl+Home/End）；**R18** 缺失→存疑（java 早有一行弱形状，旧图误记「无」）；**R24** 缺失→存疑（java 新增跨读 `cache_warm` 渲染） |
| **新单元** | **4** | **R28** 排队消息区（存疑；pi 旧锚点已有、旧图漏列，本锚点 java 追上 dim 行）；**R29** 安装变更警告（缺失）；**K39** OSC 7501 程序状态（缺失）；**C20** 假光标（缺失） |
| **实质变化、判定不变** | **11** | S2/S2b/S2d、R11、R13、R14、R17-R20、K11、K17、K32、K38、S14、S30、T3 等（见 B） |
| **只漂行号** | **30+** | 见 C |
| **行号未动** | 其余全部 | 含 `markdown.ts`、`latex.ts`、`fuzzy.ts`、`layout.ts`、`autocomplete.ts`、所有选择器组件（model/session/tree/config/scoped/thinking/theme/trust/oauth/login/first-time/show-images/user-message）、`wheel-scroll.ts`、`colors.ts`/`oklab.ts`/`system-theme.ts`、`cache-warmer.ts`、slash-commands 等 |

### B. 关键变化逐条核实

| 变化 | 影响单元 | 结论 |
|---|---|---|
| **OSC 7501 程序状态**：新 `tui/src/program-status.ts`（53）+ `terminal.ts:576`（查询与 DA 哨兵复用握手、`PI_PROGRAM_STATUS` 覆盖、stop clear）+ coding-agent `program-status-reporter.ts`（111，working/compaction/blocked/done/error） | **K39**（新）/ S22 / S17 | java 全树零命中 ⇒ 缺失 w1；扩展对话框/login 的 blocked 状态随 S17/S22 已缺 |
| **假光标机制**：`tui.ts:205 renderFakeCursor`（APC 零宽标记）+ `:1478 resolveFakeCursors`（硬件光标显示时丢聚焦假光标），editor/input 与两屏合成统一走它 | **C20**（新）/ C16 | java 无硬件光标开关与假光标 ⇒ 缺失 w1（TamboUI 默认自绘光标未核，见 C20 注脚） |
| **安装变更警告**：`interactive-mode.ts:2193 maybeShowInstallChangeWarning`（错误路径与 `tool_execution_end` isError 两处调用，每会话一次，附 resume 命令） | **R29**（新）/ S30 | java 无 ⇒ 缺失 w1 |
| **Home/End 改键**：`tui/keybindings.ts` 编辑器行首行尾去掉 ctrl+home/ctrl+end（`:96-101`），altScreen.top/bottom 由 home/end 改为 **ctrl+home/ctrl+end**（`:208-209`） | **K20** | java 仍 Home/End 滚视口 ⇒ **翻存疑**（↑↓/PgUp/PgDn 不变） |
| **outputPad 贯通**：设置项旧锚点已有，本锚点从「user/assistant/custom」扩到**工具卡/bash 卡/三张消息卡/custom-entry**（`setOutputPad` 全套——⚠️ **批 12 取证更正（docs/23 §1.5）**：本锚点 `box.ts`/`text.ts` **无** `setOutputPad`，padding 只是构造参数，「加同名方法」描述有误；切换时连 `pendingMessagesContainer` 一起热更新，不再 rebuild） | S14 / R11 / R13 / R17-R20 / R28 | **批 12（2026-10-22，docs/23 T6）**：java outputPad 消费链接通（设置 accessor＋`ChatMessage.Padded` 烘焙＋`ChatViewportElement` 宽 −2×pad，窄差 D62 不回溯）⇒ map/04 E2-40 翻对齐；S14/R 系各判定不变（渲染器域） |
| **工具结果 durationMs**：`tool_execution_end` 事件与工具卡上下文带记录在案的 `durationMs`，bash 渲染器优先显示单调 `Took …`（重载后仍准），渲染器自身时钟降为 fallback | R11 / R12 | java 通用卡无计时行 ⇒ 不变（缺失） |
| **键盘 Shift 符号逻辑匹配**：Kitty 与 modifyOtherKeys 两路都把物理 `Shift+=` 解析为逻辑 `+`（保留 Ctrl/Alt/Super）；裸键（无修饰）也允许 modifyOtherKeys 匹配；`parseKeyId` 严化（拒绝重复修饰与非法 `+`） | **K32** | java CSI-u 之外两路皆无 ⇒ 仍存疑 |
| **键位配置校验**：`keybindings.json` 解析改用 typebox 编译的 `KeybindingValueSchema`（新 `core/key-id-schema.ts` 21 行/`keybindings-schema.ts` 21 行），跳过 `$schema`；发布 `schemas/keybindings.schema.json` | K17 | java 无文件读取 ⇒ 不变 |
| **dequeue Windows 默认改 `alt+q`**（非 Windows 仍 `alt+up`） | K11 | 键常量仍死码 ⇒ 不变（缺失），证据更新 |
| **主题文件重构（theme-json 去向）**：旧 `theme/theme-json.ts`（148）**删除**，职责三拆 —— ① 校验器 → **`theme/theme-schema.ts`（typebox，146 行，惰性加载）**；② token 清单/fallback 元数据 → **`theme/theme-tokens.ts`（121 行，fallback 改表驱动）**；③ 发布的 JSON Schema → **`packages/coding-agent/schemas/theme.schema.json`（365 行，旧内嵌 361 行随删）**；`theme.ts` 1159 → **1086** 行 | T3 / T8 / 整块表 | 能力不变（JSON+schema 校验）⇒ T3 仍存疑；整块 LOC 3176 → **3105** |
| **死终端处理**：错误码加 `ENOTTY`，stdin 也挂 error handler，死终端走 `emergencyTerminalExit`（不记 crash） | S30 | java 无任何一侧 ⇒ 不变 |
| **图像小改**：terminal-image 757 → **763**（Herdr：只给 OSC 8、关图像协议）；resize worker 回复加类型 tag（防 `node --watch` 串消息） | R14 / K35 | 不变（缺失） |
| **剪贴板 Termux**：Android（非 linux platform）也走 termux 命令，报错前移 | K34 / R15 | 不变 |
| **`resetTextSelection`**：转录替换前清选区与多击历史（#9311） | S2b / K22 | java 无选区 ⇒ 不变 |
| **`--no-mcp`、`--tools` 模式/通配/`+name`/`-name`、`mcp__*`**（args.ts + settings-manager 抽 `settings-schema.ts` 474 行/`settings-defaults.ts` 66 行；agent-session MCP 工具注册/激活分级） | 非 UI 面 | 不在本图判定，归 coding-agent 地图跟进 |
| 主题 dark/light json 仅 `$schema` URL 改向；`syntax-highlight.ts` HTML 高亮按行包 formatter（214→219）；export-html tool-renderer 补 durationMs/outputPad 占位；custom-message 默认渲染补 pad；settings-selector 仅默认值取常量化与文案 | T1 / R9 / S28 / R20 / S14 | 判定全不变 |

### C. 仅行号漂移（判定不变）

| 单元 | 旧行号 | 新行号 |
|---|---|---|
| S1 | `:3926` / `:4140` | **`:3991` / `:4208`** |
| S2 | tui-alt-screen 1778 行 | **1787 行**（类仍 `:202`） |
| S2b/S2c/S2d | `:305`/`:198`、`:657`、`:352` | `:305`、**`:1464`/`:198`**、**`:665`**、**`:358`** |
| S4 | `:1760` | **`:1772`** |
| S14 | `settings-selector.ts:463` | **`:465`** |
| S22 | `:2654` / `:2730` / `:2786` | **`:2690` / `:2773` / `:2831`** |
| S23 | `:2362` / `:2488` / `:2515` | **`:2398` / `:2524` / `:2551`** |
| R6 | `:2198` | **`:2234`** |
| R9 | `:132` / `:1110`；214 行 | **`:133` / `:1122`**；**219 行** |
| R11 | renderers 1033 行 | **1053 行**（render-utils 仍 112） |
| R12 | `:46` / `:151` / `:194`；393 行 | **`:47` / `:157` / `:202`**；**408 行** |
| R13 | bash-execution 220 行 | **223 行**（类仍 `:21`） |
| R14 | terminal-image 757；image.ts「127」 | **763**；`tui/src/components/image.ts` **167 行**（旧图 127 有误，两锚点皆 167），类仍 `:55` |
| R16 | `:3817` | **`:3868`** |
| R17 | `:4053 addCompactionCostNotice` | **`:4121`** |
| R23 | `:1120` | **`:1132`** |
| R24 | `:4042` | **`:4110`** |
| R25 | `:4066` / `:4082` | **`:4134` / `:4150`** |
| R26 | `tool-execution.ts:137` | **`:143`** |
| C6/C10 | `:2310-2332` / `:2276-2299` | **`:2346-2369` / `:2312-2340`** |
| C15 | `:2101` | **`:4516`** |
| C16 | `editor.ts:296`（2472 行） | **`:297`（2473 行）**；history `:347`、addToHistory `:428`、undoStack `:368` |
| K1-K15 | `core/keybindings.ts:93-142` | 文件重构（接口下沉、APP_KEYBINDINGS 独立块）：**`:26-78`**（interrupt `:26`…followUp `:67-70`、dequeue `:71-73`、pasteImage `:75-78`） |
| K16 | `:32-44,146-186` | **`:79-115`** |
| K19 | `tui-alt-screen.ts:483` | **`:491`** |
| K20 | tui keybindings `:45-58` | 默认键表 **`:160-209`**（top/bottom `:208-209`） |
| K31 | tui keybindings `:19-20` | **`:106-110`** |
| K32 | keys.ts 1401 行、`:705`、`:1252` | **1455 行**；modifyOtherKeys **`:731`**、parseKittySequence `:602`、parseKey **`:1292`** |
| K38 | wheel `:271,:699`；settings-manager `:188` | **`:271,:707`**；**`:1285`**（wheel-scroll 类仍 `:35`） |
| T5/T8/T11 | theme.ts `:412`/`:642`/`:746`、`:200`/`:689`（1159 行） | **`:348` / `:568` / `:672`、`:143` / `:615`（1086 行）**；interactive setRegisteredThemes `:667` |
| W1 | `:3353` / `:3359` | **`:3400` / `:3406`** |
| W8 | agent-session `:594` | **`:1047`**（queue_update 类型 `:205`；另 `agent_settled` 加 `aborted`） |
| S2/S3 | args.ts `:329` | **`:340`** |
| java 侧 | — | 本锚点期 tui/web 主源码仅 **4 个文件**改动：`PiTuiApp.java`（followUp 压缩门 `:435-436`、行号整体下移 ~4）、`ChatScreen.java`（新 `PendingQueueView` 挂载 `:194-197`、`QueueUpdate`/`FlashStatus` 分支 `:300-303`；ToolCallStart 计数 `:133`；finishRun `:170-181`）、`ChatMessage.java`（+`cacheWarmNotice:75-83`、ContextEdit 隐藏、joinText 升 public `:107`）、新增 `PendingQueueView.java`（59）；web `PiWebServer.java` 275 → **325** 行（listener 就绪竞态修复）+ `PiWebServerStartupTest`。规模：tui main **6707 → 6799**（47 → **48** 文件）、tui test 4240 → **4310**、web java 1862 → **1912**；前端 TS/CSS 未动（1654/675） |

### D. 键位数量：未变

`core/keybindings.ts` app 动作 **43**、`tui/src/keybindings.ts` tui 动作 **47**（本锚点只改默认值：undo/previousPrompt/nextPrompt/search 平台差异保留，新增前述 Home/End 两组变化）。

### E. 需别的地图/台账跟进（不在本图登记）

- CLI `--no-mcp`、`--tools` 通配/`+/-name`/`mcp__*` 与 agent-session 的 MCP 工具注册/激活分级 → coding-agent 地图。
- settings 类型大搬家（`settings-schema.ts`/`settings-defaults.ts`/`settings.schema.json`）→ coding-agent 地图。
- `agent_settled` 加 `aborted`、tool 事件加 `durationMs` → agent/web 协议面（W 面判定不受影响）。
- 台账措辞过期但条目仍开：**B38**（java 现知压缩，但无整表重建/摘要卡）、**B46**（生产侧 BashUpdateEmitter 已落，TUI 消费面仍缺，见台账纠错）。
- **新缺口只报告不登记台账**：R29 安装变更警告、K39 OSC 7501、C20 假光标、K20 Home/End 分化、K32 Shift 符号逻辑匹配、R11 durationMs 计时行、outputPad 热更新面。

---

## 加权汇总

**权重规则**（已定，未自创）：权重 = **用户可观察影响 × 频率**，只看「如果用户用这个模块，这个单元有多重要」，**不掺排期优先级**。
`3` = 每轮对话都走／默认路径；`2` = 每次会话走／常用命令；`1` = 低频／边缘／纯内部；`0` = 非目标（排除出分母）。
**完成系数**：对齐 = 1.0 ／ 存疑 = 0.5 ／ 缺失 = 0。

| 域 | Σ权重 | Σ(w×系数) | 加权完成度 | 未加权完成度 |
|---|---:|---:|---:|---:|
| 屏幕/面板（35 单元） | 47 | 24.5 | **52.1%** | 40.0% |
| 渲染特性（R1–R29，28 单元；R21 权重 0 已剔；R29 批 22 出分母） | 39 | 17.5 | **44.9%** | 33.3% |
| 键绑定/交互（K1–K39，39 单元；K36 权重 0 已剔） | 66 | 33.5 | **50.8%** | 28.9% |
| TUI 组件（C1–C20，20 单元） | 41 | 22.5 | **54.9%** | 35.0% |
| 主题/样式（T1–T11，11 单元） | 18 | 6.5 | **36.1%** | 27.3% |
| web 对齐面（W1–W16，16 单元） | 33 | 27.0 | **81.8%** | 56.3% |

**模块合计**：Σ权重 **244**（R29 批 22 出分母） ／ Σ(w×c) **133.5** ／ **加权完成度 = 133.5/244 ≈ 54.7%**（未加权 **53/147 ≈ 36.1%**，**+18.6pp**）

> 批 10（2026-10-22，docs/21）：S20 缺失→对齐（+1.0）、K11 缺失→对齐（+1.0）；R28 证据更新不翻（流式 bash/工具挂载仍缺）。
> 批 13（2026-10-23，docs/24）：R24 存疑→对齐（+0.5，cache-warmer 子系统批随收——生产者/文案逐字/门//session 段/footer）。
> 批 14（2026-10-23，docs/25）：**K16 缺失→对齐（+2.0，app.session.* 键位族）＋K15 缺失→对齐（+1.0，对账表漏列同翻）＋S11 缺失→存疑（+0.5，picker 臂全落、rename 面板留白裁决⑦）**。Σ 113.0→**116.5/245 ≈ 47.6%**。
> 批 15（2026-10-23，docs/26）：**S29 `/bug` 流程 缺失→对齐（+1.0）＋S30 崩溃记录＋启动提示 缺失→对齐（+1.0）**；R29 安装变更警告挂起注记（裁决④，随自更新批）。Σ 116.5→**118.5/245 ≈ 48.4%**。
> 批 17（2026-10-24，docs/28）：**S22 扩展对话框四件套 缺失→对齐（+1.0）＋S23 扩展 widget/footer/header/状态槽 缺失→对齐（+1.0）**。Σ 118.5→**120.5/245 ≈ 49.2%**。
> 批 21（2026-10-25，docs/32）：**S9 theme 选择器 缺失→对齐（+1.0，ThemeSubmenuScreen＋设置页入口）**。Σ 120.5→**121.5/245 ≈ 49.6%**。
> 批 22（2026-10-25，docs/33）：**W15 web 会话列表 onProgress 缺失→对齐（+1.0，sessionsProgress/cancelSessions＋代次闸＋前端渐进渲染）＋R29 终裁不做出分母（分母 245→244）**。Σ 121.5→**122.5/244 ≈ 50.2%**。
> 批 25（2026-10-27，docs/36）：**Markdown 激活批——R1（w3）/R2/R3/R4/R5/R9 六行缺失→对齐（+10.0）**；S24 判定不动（注记更新：纯文本落差消除）。Σ 122.5→**132.5/244 ≈ 54.3%**。
> 批 26（2026-10-28，docs/37）：R6 缺失→对齐（+1.0，扩展 markdown transformer 钩随 E5-9 落地）。Σ 132.5→**133.5/244 ≈ 54.7%**。

> 较前锚点（144 单元：241 权 / 108.0 / **44.8%**）变化 **−0.5pp**，构成：① K20 对齐→存疑（w2）分子 −1.0；② R18/R24 缺失→存疑（w1×2）分子 +1.0；③ 新增 4 单元（R28 存疑 w1 +0.5；R29/K39/C20 缺失 w1×3）分母 +4、分子 +0.5。净效果：分子 +0.5、分母 +4。

**权重分布（按档）**

| 权重 | 单元数 | Σ权重 | Σ(w×c) | 加权完成度 |
|---|---:|---:|---:|---:|
| 3（每轮／默认路径） | 26 | 78 | 55.5 | **71.2%** |
| 2（每次会话／常用命令） | 45 | 90 | 46.0 | **51.1%** |
| 1（低频／边缘／纯内部） | 77 | 77 | 12.5 | **16.2%** |
| 0（非对齐项，已剔） | 2 | 0 | 0 | — |
| **合计** | **150** | **245** | **111.0** | **45.3%** |

> ⚠️ 本表与上方域表旧账差 2.0（既有漂移，批 10 未追账；域表 113.0 为权威）。

**权重 3 的单元清单（26 条 —— 最该先修）**

| # | 单元 | 判定 |
|---|---|---|
| S1 | 主聊天屏 | **对齐** |
| R1 | Markdown 渲染 | **对齐**（批 25/docs/36 接线，B65 销号） |
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

⇒ 权重 3 里 **16 对齐 / 5 缺失 / 5 存疑**（**71.2%**——批 25 R1 翻对齐）。缺失的 5 条全部是 **TUI 的「每轮都看得见」的能力**：`R11` 逐工具渲染器、`K27` 提示历史、`C5` spinner、`C6` working 指示器、`T2` 主题 token。**这 5 条是加权口径下的最高收益点**；本锚点新增/翻转的单元无一进入这个子集（最高权重 2）。

**未加权 vs 加权的差（+20.0pp）差在哪**

| 判定 | 单元数 | Σ权重 | 平均权重 |
|---|---:|---:|---:|
| 对齐 | 38 | 86 | **2.26** |
| 存疑 | 25 | 45 | 1.80 |
| 缺失 | 85 | 114 | **1.34** |

缺失单元里多数是权重 1（低频选择器、扩展 UI、彩蛋、原生 helper、内部契约形状、新锚点新增项…），它们把**未加权**完成度压到 27.0%，但在**加权**口径下只占 77/245 = 31.4% 的权重。反过来，**权重 3 的 26 条里 15 条已对齐**（web 事件词汇 7 条 + TUI 核心管线 8 条）—— 这正是 pi-java 的真实形态：**核心管线对齐得不错，外围能力大面积空缺**。

**两项重点发现各自的加权拖累（按新锚点重算，分母 245）**

| 发现 | 涉及单元 | Σ权重 | 若修好 | 模块加权完成度 | 模块增量 | 该面增量 |
|---|---|---:|---:|---:|---:|---:|
| **Markdown 渲染是死码** | R1（w3） | 3 | 111.5/245 | **45.5%** | **+1.2pp** | R 面 17.5% → **25.0%**（+7.5pp） |
| **Markdown 死码（含代码块高亮）** | R1 + R9（w3+2） | 5 | 113.5/245 | **46.3%** | **+2.0pp** | R 面 17.5% → **30.0%**（+12.5pp） |
| **6 个键位未接线** | K6/K7/K8/K9（w2×4）+ K10/K11（w1×2） | 10 | 118.5/245 | **48.4%** | **+4.0pp** | K 面 44.7% → **59.8%**（+15.2pp） |
| 两项合计 | R1+R9+K6–K11 | 15 | 123.5/245 | **50.4%** | **+6.1pp** | — |

口径提醒：**这两项加起来只值 +6.1pp**，但它们**在权重 3 子集里的分量远大于此**。⇒ **加权完成度这个总量指标对「单条高频缺失」不敏感，看权重 3 子集才看得出。**（批 25 落地注记：R1/R9 已翻对齐——R1 修好把权重 3 子集从 67.3% 拉到 **71.2%**，预言兑现；K6–K11 键位族在批 12–14 陆续接线。）
