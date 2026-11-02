# 05 用户界面层（pi-java-tui / pi-java-web）

> 【背景摘要】本图是用户界面层（pi-java-tui 与 pi-java-web）的全量加权盘点：屏幕/面板、渲染特性、键绑定、TUI 组件、主题、web 面逐单元判定与证据。开放缺口的权威台账是 [`../04-open-items-register.md`](../04-open-items-register.md)。

## 基准与口径

- 基准：pi-java @ `edc8749d`（main，2026-10-11）；pi @ `ea448f454`（2026-10-11，v1.1.0-22）。
- 判定单位＝**可被外部观察到的行为承诺**（一个屏幕 / 一个键绑定 / 一个渲染特性 / 一个主题能力 / 一个 TUI 组件 / 一个 web 能力）。
- 三档：**对齐**＝pi 有、pi-java 有、行为相同；**缺失**＝pi 有、pi-java 无；**存疑**＝pi-java 有但行为/形状与 pi 不同或未核。
- 所有证据均为 `file:line`。pi 侧路径前缀 `D:\workplaceForai\pi\packages\`，java 侧 `D:\workplaceForai\pi-java\`。
- pi 侧旧基线取证为 `git show ea448f454:<path>`；新目标锚点与重测规则见 docs/08 和 map/ANCHOR。

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
4. **删除面（R1）**：`coding-agent/src/experimental/{micro,mini}/` 已由 pi 删除，不在分母；主题 JSON 与 Schema 的当前实现见 T3。

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
| S6 | 模型选择器：搜索过滤 | ✓ `model-search.ts`＋`model-selector.ts` | `ModelSelectorScreen`＋`ScopedModelsScreen` 已有搜索与过滤 | **对齐（旧基线）** | 1 | 新锚点搜索排名在 L7 重测 |
| S7 | scoped-models 选择器（Ctrl+P 循环集） | ✓ `components/scoped-models-selector.ts:97`（401 行） | ✓ `ScopedModelsScreen.java`＋`ScopedModelController`＋`ScopedModelRefresh` | **对齐** | 1 | **批 31（docs/43 T4/T5）**：搜索、单项/全选/清空/provider 切换、排序、不可用项；`All` 与显式空表分流，修改仅会话 scope、Ctrl+S 持久化；15s 刷新＋代次取消（D181） |
| S8 | thinking level 选择器 | ✓ `components/thinking-selector.ts:36`（154 行） | ✓ `ThinkingLevelScreen.java:36`（批 34，docs/46） | **对齐** | 2 | **批 34 翻判定**：标题/cycle hint/描述列（`LEVEL_DESCRIPTIONS` 逐字）/默认级 ` · default`/选中 `✓ `/自绘搜索行/`ctrl+s` 存默认＋Enter 只设会话级/底行三键提示；`SelectList` 读点 `visibleItems`/`selectedIndex`；窄差 D196（自绘搜索行） |
| S9 | theme 选择器 | ✓ `components/theme-selector.ts:13`（67 行）＋`settings-selector.ts` ThemeSubmenu | ✓ `ThemeSubmenuScreen.java`＋`SettingsScreen` Theme 子菜单入口 | **对齐** | 1 | **批 21（docs/32 T11）**：single/automatic 两模式＋预览＋Esc 还原＋对形持久化；独立 ThemeSelectorComponent（pi 仅导出未自用）不移植（窄差 D130） |
| S10 | 会话选择器（列表 / 选 / 确认） | ✓ `session-selector.ts:694 class SessionSelectorComponent`（1045 行） | ✓ `SessionListScreen.java:16`（50 行） | **对齐** | 2 | java：`session.listSessions()` + 前 8 位 id |
| S11 | 会话选择器：搜索 / 排序 / 命名过滤 / 路径切换 / 重命名 / 删除 / 进度 | ✓ `session-selector.ts:76-116`、`session-selector-search.ts`、`user-message-selector.ts`；`onProgress` 带 `partialSessions` 增量渲染 + `AbortController` 取消 | ✓ `component/session/`（批 14，docs/25：搜索/三档排序/命名过滤/路径开关/删除流/双 scope 进度全落） | **存疑** | 1 | **批 14 翻判定**：picker 语境与 pi 逐字节一致；**rename 面板 UI 不移植**（picker 臂 pi 本就无生产者，裁决⑦——rename 生产者是会话内 `/resume` 面，随交互选择器批收编）；`SessionListScreen`（S10 的 `/resume` 旧屏）未换引擎 |
| S12 | 会话树选择器（分支列表） | ✓ `components/tree-selector.ts:1336 class TreeSelectorComponent`（~1400 行） | ✓ `component/tree/TreeSelectorOverlay.java`＋`TreeListModel`＋`TreeListRenderer`（批 9，docs/19） | **对齐** | 2 | 批 9（docs/19）：entry 树本体落地（D37 销号）——树形缩进/连接符/gutter、折叠、活动路径、多根虚拟根全对位；窄差 D43–D46/D48 登记；`/fork` 的 lane 平表留在旧 `TreeSelectorScreen`（pi 实为 user-message 选择器，B254） |
| S13 | 树选择器：折叠展开 / 标签编辑 / 过滤模式 / 搜索 / 复制 | ✓ `tree-selector.ts:112 filterMode`（5 种）、`:632 copySelected`、`:637 updateNodeLabel`、`:1004 handleInput` | ✓ `TreeListModel.java`（折叠传染/五过滤/搜索 AND/段跳）、`TreeSelectorOverlay.java`（label 编辑臂/copy 三结局）、`AgentSession.setLabel`（持久化＋时间戳） | **对齐** | 1 | 批 9（docs/19）：折叠/标签编辑（含 `[+label time]`）/五过滤/搜索/复制全落（pi tree-selector.test.ts 13 用例对位移植 23 测） |
| S14 | 设置页（字段列表 + 循环取值） | ✓ `settings-selector.ts:465 class SettingsSelectorComponent`（~960 行） | ✓ `SettingsScreen.java:21`（153 行） | **对齐** | 2 | java `SettingsScreen.java:30-47`；pi 侧字段含 `tuiMode`/`fullscreenExitOutput`/`fullscreenScrollbar`/`fullscreenWheelScrollLines`/`outputPad`（本锚点起说明文案改为「messages, tool output, and command output」）等，java 列表是其子集 |
| S15 | 设置页：子菜单 / 主题页 / 警告页 / 实验项 | ✓ `settings-submenu.ts:31 SelectSubmenu` + `:178 SteppedSubmenu` | ✓ `tui/screen/settings/`（SettingsItems＋子菜单屏） | **对齐** | 1 | **批 31（docs/43 T1–T3）**：完整设置项表、warnings/theme/model-thinking 子菜单、三态与嵌套写回；终端消费面/鼠标不扩 scope（D177/D178） |
| S16 | config 选择器（`/config`） | ✓ `components/config-selector.ts` | `ConfigSelectorModel`＋`ConfigSelectorComponent`＋`ConfigSelectorRunner` 已落地 | **对齐（旧基线）** | 1 | — |
| S17 | OAuth / 登录对话框 | ✓ `login-dialog.ts:12` + `oauth-selector.ts:58` | ✓ `LoginFlow.java`＋`LoginDialogScreen`＋`LoginProviderScreen` | **对齐** | 2 | **批 31（docs/43 T6–T9）**：认证方式/provider 选择与搜索、API key 掩码/OAuth 双法、迟到选择保护、Radius MCP 提议；文本级对话框（D179） |
| S18 | 首次设置向导 | ✓ `components/first-time-setup.ts:33` | ✗ | **缺失** | 1 | 一次性路径 |
| S19 | 项目信任选择器 | ✓ `components/trust-selector.ts` | `ProjectTrustPrompter`＋`TrustPromptRunner` 已接项目信任弹窗 | **对齐（旧基线）** | 2 | 新锚点保存的 do-not-trust 优先级在 L5/L7 重测 |
| S20 | 排队消息选择器（Alt+Up 取回并编辑） | ✓ `components/user-message-selector.ts:110`（155 行） | ✓ `InteractiveMode.restoreQueuedMessages`＋`PiTuiApp` DEQUEUE 臂＋`ChatScreen.restoreToEditor`（批 10，docs/21） | **对齐** | 1 | 批 10（docs/21）：dequeue 全链接地——引擎两队列＋压缩缓冲清空、pi 序合并 `"\n\n"` 回填编辑器（排队在前草稿在后）、文案逐字＋复数形、hint 行随绑定表取键名；键平台规则同批落（Windows/WSL `alt+q`，K11 同翻） |
| S21 | show-images 选择器 | ✓ `components/show-images-selector.ts:13`（50 行） | ✗ | **缺失** | 1 | — |
| S22 | 扩展 UI：选择器 / 输入框 / 确认框 / 编辑器 | ✓ `interactive-mode.ts:2690 showExtensionSelector`/`:2773 showExtensionInput`/`:2831 showExtensionEditor` | ✓ `ExtensionSelectorOverlay`/`ExtensionInputOverlay`/`ExtensionEditorOverlay`＋`TuiExtensionUI`（pi-java-tui） | **对齐** | 1 | **批 17（2026-10-24，docs/28 T4）**：ScreenOverlay 模态层落位（裁决④/窄差 D95）、k/j 别名＋空表不提交＋hint 行逐字、倒计时标题 ({N}s)＋到期取消、confirm＝selector 特化 Yes/No、ctrl+g 外部编辑器回落链逐字；窄差 D95–D98 |
| S23 | 扩展 widget / footer / header / 状态槽 | ✓ `interactive-mode.ts:2398 setExtensionWidget`、`:2524 setExtensionFooter`、`:2551 setExtensionHeader` | ✓ `ChatScreen` 注入层（widgetsAbove/Below＋customFooter/customHeader＋extensionStatuses）＋`TuiExtensionUI` 四方法 | **对齐** | 1 | **批 17（2026-10-24，docs/28 T6）**：同 key 两区先清、MAX_WIDGET_LINES=10 截断＋逐字标记行、spacer 序逐字、footer/header dispose 序、状态段字典序＋sanitize 逐字＋截断 dim "..."；窄差 D100/D101 |
| S24 | `/hotkeys` 与 `/help` 面板 | ✓ `interactive-mode.ts` + `core/slash-commands.ts:33 hotkeys`（Markdown 渲染） | ✓ `MiscCommands.java:34`、`:122` | **对齐** | 1 | 批 25（docs/36 T8）：两命令输出改产 markdown 源（粗体标题＋管道表格），TUI 经 `ChatMessage.Markdown` 走 Markdown 渲染（pi Markdown 组件对位）——旧「纯文本落差」消除 |
| S25 | 会话统计面（`/session`） | ✓ `/session` 与 footer 用量统计 | `SessionStats`＋TUI `/session` 全段已落地，web 另有统计面板 | **对齐（旧基线）** | 2 | D68 已关闭 |
| S26 | `/changelog` 面板 | ✓ `core/slash-commands.ts:32` | ✓ `MiscCommands.java:120` | **对齐** | 1 | 均为文本输出 |
| S27 | `/share`（会话分享） | ✓ `session-share.ts` | `SessionShareService`＋Radius 上传＋无 Radius 凭证时的私有 gist 备用路径 | **存疑** | 1 | 上传主链已有；加载指示器及新 `sessionShareDestination` 待复核 |
| S28 | `/export`（HTML 导出） | ✓ `core/export-html/index.ts:236 exportSessionToHtml` | ✓ `export/HtmlExporter.java:21`（288 行） | **存疑** | 1 | pi 用 vendored marked.min.js + highlight.min.js + `template.html/css/js`；java 自写 `MarkdownToHtml.java`（201 行）—— 输出形状未逐项核 |
| S29 | `/bug` 故障上报流程（同意提示 → 可选摘要 → 上传或导出 zip） | ✓ `bug-report.ts:50 reportBug`（298 行）+ `core/bug-report.ts`（375 行）+ `utils/zip.ts`；命令注册 `core/slash-commands.ts:28` | ✓ `BugReportFlow.java`＋三屏（`BugReportInputScreen`/`BugReportChoiceScreen`/`BugReportLoaderScreen`）＋`core/bugreport/`＋`core/util/ZipArchives.java` | **对齐** | 1 | **批 15（2026-10-23，docs/26 T7）**：reportBug 逐段（hint 输入→转录→摘要→递交→离线门→摘要 loader→组包→上传 fallback/导出→会话尾记录）；窄差 D79/D82/D84–D86/D88 |
| S30 | **崩溃记录 + 下次启动提示**（写 crash log，下次启动提示「Run /bug」；`/bug` 提示每会话最多一次） | ✓ `core/crash-log.ts`；`interactive-mode.ts` 的 `takeUnnotifiedCrash`/`recordCrash`/`suggestBugReport`；本锚点另加死终端（EIO/EPIPE/ENOTCONN/**ENOTTY**）静默退出（stdin/stdout/stderr 三流监听，`:4332 emergencyTerminalExit`，调用点 `:4355/:4411`） | ✓ `core/crash/CrashLog.java`＋`CrashIntegration.java`＋`PiTuiLauncher`（启动提示/未捕获钩）＋`PiTuiApp.maybeSuggestBugReport` | **对齐** | 1 | **批 15（2026-10-23，docs/26 T1/T2）**：末 5 条/7 天窗/全量 notified 回写逐字＋启动提示＋uncaught 钩＋`/import` fatal（B256）＋`/bug` 建议门序逐字（R29 门恒 false 裁决④）；窄差 D80/D81/D83/D87（死终端面不做、dim 文本、JAR 名针） |
| S32 | 登录 URL 复制＋Radius 登录入口 | ✓ `components/auth-url.ts`＋Radius 登录/MCP 设置 | `LoginFlow`＋`LoginDialogScreen`＋Radius OAuth/MCP 提议已落地；独立 URL 复制键未证实 | **存疑** | 1 | 旧“仅 readPassword”已失效 |

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
| R12 | 工具执行卡：可折叠＋流式部分结果 | ✓ `tool-execution.ts` 的展开态与折叠提示 | 全局 Ctrl+O 展开态已接入渲染参数；完整工具执行卡、折叠提示和流式部分结果仍缺 | **缺失** | 2 | D197；旧“无折叠态”已失效 |
| R13 | bash 执行组件（实时增量输出 + 宽度自适应） | ✓ `components/bash-execution.ts:21`（**223 行**；`!!` 命令 dim 边框、`outputPad` 可配）+ `test/bash-execution-width.test.ts` | ✗ | **缺失** | 2 | java TUI 无 bash 屏；web 侧有 `bashOutput` 帧（`AgentEventTranslator.java:130`） |
| R14 | 图片渲染（Kitty / iTerm2 图像协议 + 单元格尺寸探测 + 降级） | ✓ `tui/src/terminal-image.ts`（**763 行**；本锚点加 **Herdr 终端识别**：转 OSC 8 超链接、关图像协议）+ `tui/src/components/image.ts:55 class Image`（167 行） | ✗ | **缺失** | 1 | java 只有 `[image: mimeType]` 占位文本（`MessageBubble.java:102`） |
| R15 | 图片粘贴（剪贴板 → 附件） | ✓ `app.clipboard.pasteImage`（`core/keybindings.ts:75`）+ `utils/clipboard-image.ts` + 测试 | ✗ | **缺失** | 1 | java 全仓无 clipboard-image |
| R16 | 元数据气泡分型（模型切换/思考等级/工具集/压缩/分支/自定义，各带图标+色） | ✓ `interactive-mode.ts:3868 addMessageToChat` 各 message 组件 | ✓ `MetaKind.java:15-29`（6 型 enum） | **对齐** | 2 | 图标/颜色集不同（java 自选 emoji + hex），**承诺相同** |
| R17 | 压缩摘要消息卡 | ✓ `compaction-summary-message.ts:10`（68 行；鼠标左键点击展开/折叠；本锚点吃 `outputPad`） | ✓（弱形状） | **存疑** | 2 | java 走 `ChatMessage.java:50-51` 的 `System("Compacted context: …", COMPACTION)` 一行；无独立卡、无 `usage` 开销提示（`interactive-mode.ts:4121 addCompactionCostNotice`）、无点击展开 |
| R18 | 分支摘要消息卡 | ✓ `branch-summary-message.ts` | `Entry.BranchSummary` 已有生产与一行弱形状渲染；独立卡、Markdown 和点击展开仍缺 | **存疑** | 1 | 旧“生产功能本体仍缺”已失效 |
| R19 | skill 调用消息卡 | ✓ `skill-invocation-message.ts:11`（64 行；点击展开；本锚点吃 `outputPad`） | ✗ | **缺失** | 1 | java `ChatMessage.from` 无 skill 分支 |
| R20 | 自定义消息卡（`custom_message`，`display` 门） | ✓ `custom-message.ts:12`（113 行；本锚点默认渲染也套 `outputPad`） | ✓ `ChatMessage.java:56-59` | **对齐** | 1 | java 用 `[customType] plainText`，pi 用 Markdown 渲染 `content` |
| R21 | 回合分隔条（`Worked for Ns • Local tools: N calls`） | ✗（pi 无此构造） | ✓ `ChatScreen.java:170-181` | **非对齐项**（java 独有，Codex 风格） | 0 | 见「pi-java 独有」，**排除出分母** |
| R22 | 启动公告组件（`earendil-announcement`、`armin`） | ✓ `components/earendil-announcement.ts:27`、`armin.ts:62 class ArminComponent`（384 行） | ✗ | **缺失** | 1 | 旧的 `daxnuts.ts` 已删，彩蛋迁到 R27 |
| R23 | 窗口/终端标题更新 | ✓ `interactive-mode.ts:1132 updateTerminalTitle` | ✗ | **缺失** | 1 | — |
| R24 | 缓存预热用量行（聊天区 `formatCacheWarmingUsage` + `/session` 的 `formatCacheWarmingStatus`／missCost／warmCost；footer 计入 `usage` 条目） | ✓ `core/cache-warmer.ts:449/433`；`interactive-mode.ts:4110 addCacheWarmingUsage` | 批 13：`ChatMessage.cacheWarmNotice`（pi :449-453 逐字经 `CacheWarmingFormatting.formatUsage`）＋`ChatScreen.appendUsageNotice` 门控＋`/session` Cache Warming 段＋footer `AgentHarness.addTokens` | **对齐** | 1 | **批 13（docs/24）翻判定**：生产者（CacheWarmer）落地 ⇒ 自有 `cache_warm` 条目照渲染；文案改 pi 逐字（"Cache warmed[: (note)]: $cost"，原自造 "Cache warming: N tokens" 删）；门 `getShowCacheMissNotices()` 三处调用点全接（onEntry/rebuild/EntryAppended 即时臂）；`/session` 段逐字（Mode/Status/decision 两行＋Cache Re-billed 挂段尾＝D68 结构差）；footer 计入（pi getSessionStats usage 条目臂对位） |
| R25 | thinking 块被丢弃提示（`Anthropic dropped N thinking blocks …`） | ✓ `interactive-mode.ts:4134 countDroppedThinkingBlocks`、`:4150 maybeShowThinkingDropNotice` | ✗ | **缺失** | 1 | 只在与上一条 assistant 消息的丢弃数**增加**时显示；java 无此构造 |
| R26 | 工具调用参数渲染（MCP 与 fallback 显式展示 args） | ✓ `core/tools/render-utils.ts`＋`tool-execution.ts` | `McpToolCallRenderer` 与责任链接口已存在；通用工具参数渲染覆盖仍不完整 | **存疑** | 1 | 旧“无 MCP 渲染器”已失效 |
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
| K6 | `Ctrl+P` 模型循环（前/后） | 键绑定 | `keybindings.ts:41-48 app.model.cycleForward/Backward` | ✓ `GlobalKeyRouter.java:245-248 MODEL_CYCLE → cycleModelForward()`（B137/ai 批 3b 已接） | **对齐** | 2 | ~~B66~~ 批 34 账面翻账（docs/46，接线早于台账） |
| K7 | `Shift+Tab` 循环 thinking level | 键绑定 | `keybindings.ts:33 app.thinking.cycle` | ✓ `GlobalKeyRouter` THINKING_CYCLE 臂（批 34，docs/46）＋`AgentSession.cycleThinkingLevel`＋转录系统行 | **对齐** | 2 | ~~B66~~ 批 34 接线（docs/46） |
| K8 | `Ctrl+O` 展开/折叠工具输出 | 键绑定 | `keybindings.ts:50 app.tools.expand` | ✓ `GlobalKeyRouter` TOOLS_EXPAND 臂＋`PiTuiApp.toggleToolsExpanded`→`MessageBubbleVisibility.installToolsExpanded` 渲染现读（批 34，docs/46）；扩展选择器 `ctrl+o` 转发（D97 销） | **对齐** | 2 | ~~B66~~ 批 34 接线；R12 工具卡折叠/流式整块仍缺（窄差 D197） |
| K9 | `Ctrl+T` 显隐 thinking 块 | 键绑定 | `keybindings.ts:51 app.thinking.toggle` | ✓ `GlobalKeyRouter` THINKING_TOGGLE 臂＋`MessageBubbleVisibility.install` 现读（批 34，docs/46）；设置项 `hideThinkingBlock` 口径 | **对齐** | 2 | ~~B66~~ 批 34 接线（docs/46） |
| K10 | `Ctrl+G` 外部编辑器 | 键绑定 | `keybindings.ts:59 app.editor.external` + `external-editor.ts:46` | ✓ `GlobalKeyRouter.java:250 EXTERNAL_EDITOR → openExternalEditor()`（批 12 T3 已接） | **对齐** | 1 | ~~B66~~ 批 34 账面翻账（docs/46，接线早于台账） |
| K11 | `Alt+Up` 取回排队消息 | 键绑定 | `keybindings.ts:71-73 app.message.dequeue`（**Windows 默认改为 `alt+q`**） | `KeybindingsManager.java`（`useWindowsKeybindings` 逐字：Windows/WSL `alt+q`、其余 `alt+up`）＋`PiTuiApp` DEQUEUE 臂 | **对齐** | 1 | ~~B66~~ 批 10（docs/21，2026-10-22）接线 |
| K12 | `Ctrl+Z` 挂起进程 | 键绑定 | `keybindings.ts:29-32 app.suspend`（非 Windows） | ✗ | **缺失** | 1 | — |
| K13 | `Ctrl+X` 复制消息到剪贴板 | 键绑定 | `keybindings.ts:63 app.message.copy`（描述「Copy selection or last assistant message」） | ✗（有 `/copy` 命令 `MiscCommands.java:95-96`，无键位） | **缺失** | 1 | — |
| K14 | `Ctrl+V`/`Alt+V` 粘贴图片 | 键绑定 | `keybindings.ts:75-78 app.clipboard.pasteImage` | ✗ | **缺失** | 1 | — |
| K15 | `Ctrl+N` 命名会话过滤 | 键绑定 | `keybindings.ts:55 app.session.toggleNamedFilter` | ✓ `KeybindingsManager.SESSION_TOGGLE_NAMED_FILTER`（overlay 表）＋`SessionSelectorComponent` 消费（批 14，docs/25 T3/T10） | **对齐** | 1 | **批 14 同翻**（设计对账表只列 K16，闭环逐行实算补——命名过滤键注册并被选择器消费） |
| K16 | `app.session.*` 家族（new/tree/fork/resume + 树/会话选择器内键位） | 键绑定 | `core/keybindings.ts:79-115` | ✓ `KeybindingsManager`（批 14，docs/25 T3）：选择器六键入 overlay 表（ctrl+n/p/s/r/d＋ctrl+backspace，与全局撞名分表——批 9 C1 同构）＋ new/tree/fork/resume 四空绑定注册（pi `defaultKeys: []` 对位） | **对齐** | 2 | **批 14 翻判定**：六键被选择器消费；四空绑定 `resolve` 永不命中、`/hotkeys` 渲染 `""`（pi `formatKeys([])` 对位）；`tui.select.*` 沿批 10 KeyEvent-helper 先例不入表（裁决⑥，窄差 D73） |
| K17 | 用户键位覆盖（`keybindings.json`） | 配置 | `core/keybindings.ts`（本锚点改用 typebox `KeybindingValueSchema` 校验、跳过 `$schema`；schema 文件另出 `schemas/keybindings.schema.json`）+ migration 测试 | ✗（`KeybindingsManager.java:14` 注释「User overrides from keybindings.json → Phase 6」） | **缺失** | 1 | H-9.2-⑩ |
| K18 | 键位提示渲染（`keyText`/`keyHint`/`rawKeyHint`） | 组件 | `components/keybinding-hints.ts:48` | `KeybindingHints.java:19`（只有 `keyText:29`，无 `keyHint`/`rawKeyHint`） | **存疑** | 2 | — |
| K19 | 滚轮/触控板归一化（事件流 → 行滚动） | 交互 | `tui/src/tui-alt-screen.ts:491 scrollBy` + `tui/src/wheel-scroll.ts` | `ScrollInputNormalizer.java:20`（274 行，Codex TUI2 PR#8357 移植）+ `ScrollConfig.java:35` | **存疑** | 3 | — |
| K20 | 键盘滚动（空编辑器时 ↑↓/PgUp/PgDn/Home/End 驱动转录） | 交互 | pi：`tui/src/keybindings.ts:208-209` —— **本锚点起 top/bottom 默认键改为 `ctrl+home`/`ctrl+end`**（`home`/`end` 让还给编辑器行首行尾，`:96-101`）；↑↓/PgUp/PgDn 仍是 `tui.altScreen.*`（`:160-182`） | ✓ `GlobalKeyRouter` `isAltScreenScroll`/`isInlinePageScroll`/`scrollViewport`（批 34，docs/46）：裸 Home/End/↑↓ 归还编辑器（↑↓ 另由 K27 历史接手），PgUp/PgDn 仅空编辑器滚视口，Ctrl+Home/End 视口 top/bottom（全屏＋内联双臂） | **对齐** | 2 | ~~B238①~~ 批 34 销号（D195/D200） |
| K21 | 转录搜索 | 交互 | `tui/src/alt-screen-search.ts:156-197` | ✗ | **缺失** | 1 | — |
| K22 | 鼠标文本选择 / copy-on-select | 交互 | `tui-alt-screen.ts:305 hasActiveSelection`、`:1464 copySelectionToClipboard`（失败时具体错误文案 + 5 s flash） | ✗ | **缺失** | 1 | — |
| K23 | 滚动条（绘制 + 拖拽 + 悬停加宽） | 交互 | `tui-alt-screen` scrollbar + `test/scrollbar-theme.test.ts` | `ChatViewportElement.java:129-190`、`:224-283` | **对齐** | 3 | — |
| K24 | 斜杠命令补全弹层 | 交互 | `autocomplete.ts:303 CombinedAutocompleteProvider`（835 行；前导空白后仍补全） | `SlashCompleter.java:17`（146 行，前缀匹配） | **对齐** | 2 | — |
| K25 | `@文件` 路径补全（fuzzy + `fd`） | 交互 | `autocomplete.ts:421-477`（file 分支）、`:737 scoreEntry`；CJK 标点作分隔符（`tui/src/utils.ts:58-63`）、`skill:` 按裸名模糊排序、wrapper 后补路径 | ✗ | **缺失** | 2 | — |
| K26 | 大粘贴折叠为 `[paste #N +L lines]` | 交互 | `tui/components/editor.ts:31-55`（paste marker 原子段）+ `test/editor.test.ts` | ✗ | **缺失** | 1 | — |
| K27 | 提示历史 ↑/↓ 导航（含 draft 还原、100 条上限） | 交互 | `editor.ts:347 history/historyIndex`、`:428 addToHistory` | ✓ `EditorHistory.java`（143 行，批 34，docs/46）＋`EditorComponent` ↑/↓ 三臂：首/末行导航历史、draft 还原、100 条上限、非导航键重置浏览 | **对齐** | 3 | ~~H-9.2-②~~ 批 34 接线（docs/46） |
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
| C16 | 编辑组件（多行 + 折叠 + 历史） | 组件 | `tui/components/editor.ts:297 class Editor`（2473 行）+ `editor-component.ts:74` + `custom-editor.ts:148` | `EditorComponent.java:27`（500 行）＋`EditorHistory.java`（143 行，批 34） | **存疑** | 3 | 批 34 补 K27（提示历史）；仍缺 K26 大粘贴折叠／K31 jump mode；见「存疑」清单 |
| C17 | 模糊匹配 | 工具 | `tui/src/fuzzy.ts:12 fuzzyMatch`（**子序列**匹配 + `-5×连续 / +2×间隔 / -10×词边界 / +0.1×位置`）、`:100 fuzzyFilter`（按空白/斜杠**分词**、每词都要匹配、分数求和） | `FuzzyMatcher.java:50 score()` 是**子串**匹配（`startsWith`→0、`indexOf`→下标、否则不匹配），**无分词** | **存疑** | 2 | B78 |
| C18 | 布局引擎（constraint/flex/嵌套） | 框架 | `tui/src/layout.ts:449` + `layout-node.ts:51` | TamboUI 布局（外部） | **对齐**（经 TamboUI） | 3 | — |
| C19 | stdin 缓冲（CSI 分片重组） | 框架 | `tui/src/stdin-buffer.ts:444` + `test/stdin-buffer.test.ts` | TamboUI `Backend` | **对齐**（经 TamboUI） | 3 | — |
| C20 | 假光标（APC 标记的反白光标，与硬件光标互斥） | 组件 | `tui/src/tui.ts` 的 renderFakeCursor 与合成逻辑 | `showHardwareCursor` 设置已存在；APC 假光标标记及互斥解析未实现 | **缺失** | 1 | B238④；旧“无 hardware-cursor 开关”已失效 |

### 主题 / 样式

| # | 能力单元 | 类别 | pi 侧证据 | pi-java 侧证据 | 判定 | 权重 | 台账条目 |
|---|---|---|---|---|---|---|---|
| T1 | 内置 dark + light 主题 | 主题 | `theme/dark.json`（78 行）、`theme/light.json`（78 行；`$schema` URL 本锚点改指 `coding-agent/schemas/theme.schema.json`） | `themes/pi-dark.tcss`（37 行）、`pi-light.tcss`（37 行）+ `PiTheme.java:23` | **对齐** | 2 | — |
| T2 | 主题 token 覆盖（约 70 个语义色） | 主题 | `theme/dark.json` 与主题 token 表 | `ThemeToken`＋`SemanticColors`＋`ThemeApplicator` 已接主题消费；覆盖完整性待新锚点逐键复核 | **存疑** | 3 | 旧“仅 8 条 CSS 规则”已失效 |
| T3 | 用户自定义主题（JSON + schema 校验） | 主题 | `theme/theme-schema.ts`、主题 token 表和发布 Schema | `ThemeJson`＋`ThemeJsonValidator`＋`ThemeLoader` 已支持 JSON 主题与校验；`.tcss` 仅为 TUI 适配产物 | **对齐（旧基线）** | 1 | 新 Schema 的 `vars` 等变化在 L5/L7 重测 |
| T4 | 主题热重载 / 切换重渲染 | 主题 | `theme-controller.ts` 与主题文本失效重建 | `ThemeWatcher`＋`ThemeController`＋会话 reload 主题臂已接线 | **对齐（旧基线）** | 1 | — |
| T5 | 思考等级 → 边框/文字颜色映射 | 主题 | `theme.ts` 的 thinking 色映射 | `Theme.thinkingBorderColor` 覆盖 off/minimal/low/medium/high/xhigh/max，未知级别回落 thinkingOff | **对齐（旧基线）** | 2 | — |
| T6 | 语法高亮色板（10 token） | 主题 | `dark.json` 的 syntax token | `SyntaxHighlighter` 已改读 `MarkdownTheme`/主题令牌，不再固定三色；token 覆盖仍需复核 | **存疑** | 2 | — |
| T7 | 导出专用主题色（`export.pageBg/cardBg/infoBg`） | 主题 | `dark.json` 的 export 对象与 HTML 导出 | `ExportThemeVars` 已为 HTML 导出派生主题变量 | **对齐（旧基线）** | 1 | — |
| T8 | 主题解析优先级（CLI > settings > 扩展候选 > dark） | 主题 | `theme.ts`（**1086 行**）`:568 getThemeByName` + `:672 setRegisteredThemes`（`interactive-mode.ts:667`） | `PiTheme.java:71 resolveTheme` | **对齐** | 2 | — |
| T9 | 主题按扩展贡献注册 | 主题 | `interactive-mode.ts:667 setRegisteredThemes` | `PiTheme.resolveTheme(candidates)`（`PiTheme.java:71`） | **对齐** | 1 | — |
| T10 | OKLab / OKLCH / OKHSL 色彩系统与混色 | 主题 | `tui/src/colors.ts`＋`tui/src/oklab.ts` | `ThemeColor`＋`Oklab`＋`ThemeColorMath` 已落地并有 golden 测试 | **对齐（旧基线）** | 2 | — |
| T11 | system 主题（由终端调色板派生，多层 fallback） | 主题 | `theme/system-theme.ts` 与终端颜色探测 | `SystemTheme`＋`SystemThemeRecipe` 已落地；无 OSC 探测时使用既有 tier-3 路径，终端查询面待复核 | **存疑** | 1 | D123 |

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
| W14 | `--no-session` 在 web 路径生效 | 行为 | 不落盘 | web 已使用 ephemeral/no-session 路径 | **对齐（旧基线）** | 1 | B54 已关闭 |
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

## 整块视图（非独立计分）

整块视图不再重复计分。具体行为以逐单元表为准；旧“Markdown 生产零调用”“扩展 UI 仅 RPC”“主题只有 .tcss”等滞后结论已撤销。

---


## 加权汇总

**权重规则**（已定，未自创）：权重 = **用户可观察影响 × 频率**，只看「如果用户用这个模块，这个单元有多重要」，**不掺排期优先级**。
`3` = 每轮对话都走／默认路径；`2` = 每次会话走／常用命令；`1` = 低频／边缘／纯内部；`0` = 非目标（排除出分母）。
**完成系数**：对齐 = 1.0 ／ 存疑 = 0.5 ／ 缺失 = 0。

| 域 | Σ权重 | Σ(w×系数) | 加权完成度 | 未加权完成度 |
|---|---:|---:|---:|---:|
| 屏幕/面板（35 单元） | 47 | 26.5 | **56.4%** | 42.9% |
| 渲染特性（R1–R29，28 单元；R21 权重 0 已剔；R29 批 22 出分母） | 39 | 17.5 | **44.9%** | 33.3% |
| 键绑定/交互（K1–K39，39 单元；K36 权重 0 已剔） | 66 | 46.5 | **70.5%** | 47.4% |
| TUI 组件（C1–C20，20 单元） | 41 | 22.5 | **54.9%** | 35.0% |
| 主题/样式（T1–T11，11 单元） | 18 | 6.5 | **36.1%** | 27.3% |
| web 对齐面（W1–W16，16 单元） | 33 | 27.0 | **81.8%** | 56.3% |

**旧基线量化快照**：Σ权重 **244** ／ Σ(w×c) **152.5** ／ **62.5%**。本图修正了若干滞后行，但不借文档清理重算旧分数；L7 逐行重测后发布新读数。

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
| K27 | 提示历史 ↑/↓ 导航 | **对齐**（批 34/docs/46） |
| K28 | undo（fish 式合并） | **对齐** |
| K32 | Kitty 键盘协议解析 | **存疑** |
| C1 | 滚动视图 | **对齐** |
| C5 | loader / spinner | **缺失** |
| C6 | working 指示器 | **缺失** |
| C12 | stack / spacer / text 布局基元 | **对齐** |
| C16 | 编辑组件 | **存疑** |
| C18 | 布局引擎 | **对齐** |
| C19 | stdin 缓冲 | **对齐** |
| T2 | 主题 token 覆盖（~70 语义色） | **存疑** |
| W1 | 流式事件词汇 | **对齐** |
| W2 | `tool_execution_*` 真实源 | **对齐** |
| W3 | `turn_end` 带 `{message, toolResults}` | **对齐** |
| W4 | `message_update` 带 `usage` | **对齐** |
| W5 | `toolcall_start` 带 `id`+`toolName` | **对齐** |
| W6 | 消息 wire 形状 | **对齐** |
| W7 | 终局 `usage` 非空 | **对齐** |
| W10 | `agent_end.messages` 整表替换 | **存疑** |

权重 3 的当前判定以本表为准。R11、C5、C6 仍是明确缺失；T2 已有主题消费实现但覆盖待核，记存疑。旧权重 3 百分比不在本次清理中重算，L7 统一发布新读数。
