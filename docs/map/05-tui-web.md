# 05 用户界面层（pi-java-tui / pi-java-web）

> 判定单位＝**可被外部观察到的行为承诺**（一个屏幕 / 一个键绑定 / 一个渲染特性 / 一个主题能力 / 一个 TUI 组件 / 一个 web 能力）。
> 三档：**对齐**＝pi 有、pi-java 有、行为相同；**缺失**＝pi 有、pi-java 无；**存疑**＝pi-java 有但行为/形状与 pi 不同或未核。
> 所有证据均为 file:line。pi 侧路径前缀 `D:\workplaceForai\pi\packages\`，java 侧 `D:\workplaceForai\pi-java\`。
>
> **⚠️ 锚点（2026-09-20 重测）**：pi 侧全部 `file:line` 与规模数字**已对 `3390bd936`（2026-09-20）重测**，
> 自 `71dca871b`（2026-09-11）起 pi 前进 **111** 个提交。旧锚点的引用一律作废 ——
> 逐条漂移复核见文末「pi 侧漂移复核」一节。

---

## 规模

| 模块 | pi 包 | pi LOC（旧 `71dca871b` → 新 `3390bd936`） | java LOC | 比例 |
|---|---|---|---|---|
| TUI 框架层 | `packages/tui/src`（41 → **42** 文件） | 18107 → **18267**（+160） | —（对应物是**外部库 TamboUI**） | — |
| TUI 应用层 | `packages/coding-agent/src/modes/interactive`（54 TS 文件） | 18631 → **19187**（+556） | — | — |
| 工具渲染器 | `coding-agent/src/core/tools/renderers`（8 文件） | 1049 → **1057** | 0 | 0% |
| HTML 导出 | `coding-agent/src/core/export-html`（TS） | 746 → **746** | 489 | 66% |
| CLI 启动 UI | `coding-agent/src/cli`（startup-ui/session-picker/config-selector/list-models） | 477 → **477** | 见 coding-agent | — |
| **实验性 micro TUI**（**新增**） | `coding-agent/src/experimental/micro`（7 文件） | 0 → **1485** | 0 | 0% |
| **UI 合计（pi）** | | 39010 → **41219**（+2209） | — | — |
| **pi-java-tui（main）** | | 41219 | **6712**（47 文件，**未变**） | **16.3%** |
| pi-java-tui（test） | pi `tui/test` 17573/37 → **19097/38** | — | **4243**（33 文件） | — |
| pi-java-web（java main） | **pi 仓库内无对应物** | 0 | **1865**（9 文件） | — |
| pi-java-web（前端 TS） | **pi 仓库内无对应物** | 0 | **1654**（8 文件） | — |
| pi-java-web（前端 CSS） | 同上 | 0 | **675**（app.css） | — |

**口径说明（重要）**：

1. pi 的 `packages/tui` 是一个**自带组件库的通用终端框架**；pi-java 的对应物是**外部库 TamboUI**（`dev.tamboui.*`，见 `pi-java-tui/src/main/java/dev/tamboui/tui/event/EventParser.java:20-29` 的 same-package 覆写注释）。因此含框架层的 LOC 口径**低估了 pi-java**：真正的同层比较应把 pi 的 `tui` 框架层 18267 行算作 TamboUI（外部），只剩应用层 19187 行可比。
2. 按**应用层口径**：6712 / 19187 = **35.0%**（旧 36.0%）；且**能力单元口径更低**（见加权汇总）—— 缺失集中在整块子系统（图片协议、LaTeX、搜索、扩展 UI、主题系统、工具渲染器），而不是零散小功能。
3. **测试量差距**：pi `packages/tui/test` **19097 行 / 38 个测试文件**（旧 17573/37）vs pi-java-tui **4243 行 / 33 个测试文件**（**4.5×**）。pi 的 `coding-agent/test` 现 **196** 个文件（旧 191），其中约 **54 个**直接针对 UI（旧 48；本轮新增 `bug-report.test.ts` / `interactive-mode-bug-report-hint.test.ts` / `cache-warmer.test.ts` / `clipboard.test.ts` / `clipboard-command.test.ts`），pi-java-tui 对应面为 **0**。
4. 本轮 pi 侧**无任何 UI 文件被删除或改名**（`git diff --diff-filter=DR` 零命中）⇒ 按裁决 R1 的「pi 已删除 ⇒ 作废」**本轮 0 条适用**。

---

## 屏幕/面板对齐

| # | 屏幕 | pi 有 | java 有 | 判定 | 权重 | 证据（pi 侧已对 `3390bd936` 重测） |
|---|---|---|---|---|---|---|
| S1 | 主聊天屏（transcript 视口 + 输入行 + 状态行） | ✓ | ✓ | **对齐** | 3 | pi `interactive-mode.ts:3776 renderSessionItems` / `:3986 renderInitialMessages`；java `ChatScreen.java:189 render()`、`PiTuiApp.java:187 root()` |
| S2 | 全屏 alt-screen 模式 | ✓ `tui-alt-screen.ts:197 class TuiAltScreen`（1742 行） | ✓ `PiTuiLauncher.java:69 runFullscreen`（TamboUI ToolkitRunner） | **存疑** | 2 | java 用 TamboUI 通用 runner，pi 的 alt-screen 自带搜索/选择/复制/图像/闪烁（见 S2a-e、K20/K21）；滚动条由 java 自绘（`ChatViewportElement.java:243`） |
| S2a | 全屏：转录搜索（`/` 查询、上一个/下一个匹配） | ✓ `alt-screen-search.ts:156-197`（327 行） | ✗ | **缺失** | 1 | java 全树无 search 命中（`grep -rni "search" pi-java-tui/src/main` 只命中 `FuzzyMatcher.java:18` 注释） |
| S2b | 全屏：鼠标文本选择 + 自动滚动 + copy-on-select | ✓ `tui-alt-screen.ts:296 hasActiveSelection`、`:193 copySelection`（本轮新增失败文案 + 5 s flash） | ✗ | **缺失** | 1 | 同上 grep：java 无 selection/copy 命中 |
| S2c | 全屏：flash 提示（短暂状态行） | ✓ `tui-alt-screen.ts:643 flash()` + `components/alt-screen-flash.ts:1`（51 行） | ✗ | **缺失** | 1 | — |
| S2d | 全屏：图像渲染（Kitty/iTerm2） | ✓ `tui-alt-screen.ts:338 setCapabilities`（本轮加 WezTerm 图像保留修复） | ✗ | **缺失** | 1 | 见 R14 |
| S3 | regular（非备用屏）inline 模式 | ✓ `tui-main-screen.ts`（655 行） | ✓ `InlineTuiShell.java:1`（390 行） | **对齐** | 1 | 两者都是「终端原生 scrollback + 底部固定输入区」；非默认模式（`--tui-mode regular`），默认是 fullscreen（`PiTuiLauncher.java:99`） |
| S4 | 启动欢迎卡 | ✓ `interactive-mode.ts:1677 showLoadedResources` | ✓ `WelcomeOverlay.java:20 buildCard` | **对齐** | 2 | 形状不同（pi 列 context/skills/prompts/extensions/themes；java 是 Codex 风格方框），但承诺相同：启动即告知模型/目录/版本 |
| S5 | 模型选择器（列表 / 上下选 / 确认） | ✓ `components/model-selector.ts:1`（421 行） | ✓ `ModelSelectorScreen.java:20`（58 行） | **对齐** | 2 | java：`ModelsJsonConfig.allModels()` 平铺排序 + `SelectList` |
| S6 | 模型选择器：搜索过滤 | ✓ `model-search.ts:21` + `model-selector.ts` | ✗ | **缺失** | 1 | java 无过滤输入 |
| S7 | scoped-models 选择器（Ctrl+P 循环集） | ✓ `components/scoped-models-selector.ts:401` | ✗ | **缺失** | 1 | `PiTuiApp.java:413-415` 直接 `appendSystemText("Use /scoped-models +<model> …")` |
| S8 | thinking level 选择器 | ✓ `components/thinking-selector.ts:154` | ✗ | **缺失** | 2 | java 只在 `SettingsScreen.java:33-36` 里循环 6 个值 |
| S9 | theme 选择器 | ✓ `components/theme-selector.ts:67` | ✗ | **缺失** | 1 | java 只在 `SettingsScreen.java:31-32` 里循环 `dark/light` |
| S10 | 会话选择器（列表 / 选 / 确认） | ✓ `session-selector.ts:694 class SessionSelectorComponent`（1045 行） | ✓ `SessionListScreen.java:50`（50 行） | **对齐** | 2 | java：`session.listSessions()` + 前 8 位 id |
| S11 | 会话选择器：搜索 / 排序 / 命名过滤 / 路径切换 / 重命名 / 删除 / 进度 | ✓ `session-selector.ts:76-116`（setScope/setSortMode/setNameFilter/setShowPath…）、`session-selector-search.ts:194`、`user-message-selector.ts:155`；**本轮新增**：`onProgress` 带 `partialSessions` 增量渲染 + `AbortController` 取消 + 刷新保选中 | ✗ | **缺失** | 1 | java 全部无（`SessionListScreen.java` 全文 50 行无一处） |
| S12 | 会话树选择器（分支列表） | ✓ `components/tree-selector.ts:1329 class TreeSelectorComponent`（1428 行） | ✓ `TreeSelectorScreen.java:63`（63 行） | **存疑** | 2 | java 列的是 **lane 名 + leafId**（`TreeSelectorScreen.java:26-29`），不是 pi 的 entry 树；选中＝从该 entry fork（`:55`） |
| S13 | 树选择器：折叠展开 / 标签编辑 / 过滤模式 / 搜索 / 复制 | ✓ `tree-selector.ts:112 filterMode`（5 种）、`:628 copySelected`、`:633 updateNodeLabel`、`:997 handleInput` | ✗ | **缺失** | 1 | — |
| S14 | 设置页（字段列表 + 循环取值） | ✓ `settings-selector.ts:447 class SettingsSelectorComponent`（960 行） | ✓ `SettingsScreen.java:153`（153 行，10 字段） | **对齐** | 2 | java `SettingsScreen.java:30-51` |
| S15 | 设置页：子菜单 / 主题页 / 警告页 / 实验项 | ✓ `settings-submenu.ts:258` + `settings-selector.ts` | ✗ | **缺失** | 1 | java 是单一平铺列表 |
| S16 | config 选择器（`/config`） | ✓ `components/config-selector.ts:942` | ✗ | **缺失** | 1 | java 无 config 命令 |
| S17 | OAuth / 登录对话框 | ✓ `login-dialog.ts:233` + `oauth-selector.ts:214` | ✗ | **缺失** | 2 | java 的 `/login` 是 `System.console().readPassword`（`MiscCommands.java:134-158`），无对话框 |
| S18 | 首次设置向导 | ✓ `components/first-time-setup.ts:145` | ✗ | **缺失** | 1 | 一次性路径 |
| S19 | 项目信任选择器 | ✓ `components/trust-selector.ts:134` | ✗ | **缺失** | 2 | java 只有 `SettingsScreen.java:37-39` 的 `defaultProjectTrust` 循环 |
| S20 | 排队消息选择器（Alt+Up 取回并编辑） | ✓ `components/user-message-selector.ts:155` | ✗ | **缺失** | 1 | java 的 `DEQUEUE` 常量存在（`KeybindingsManager.java:29`）但未接线（`PiTuiApp.java:437` default 分支） |
| S21 | show-images 选择器 | ✓ `components/show-images-selector.ts:50` | ✗ | **缺失** | 1 | — |
| S22 | 扩展 UI：选择器 / 输入框 / 确认框 / 编辑器 | ✓ `extension-selector.ts:112`、`extension-input.ts:87`、`extension-editor.ts:132`、`interactive-mode.ts:2558-2740`；**本轮新增**：三者的 `description` + `initialValue` 选项 | ✗ | **缺失** | 1 | java 只有 RPC 通道 `ExtensionUI.java:19`（`AgentSession.java:114` 默认 `noop()`），TUI 无实现 |
| S23 | 扩展 widget / footer / header / 状态槽 | ✓ `interactive-mode.ts:2266-2450` | ✗ | **缺失** | 1 | — |
| S24 | `/hotkeys` 与 `/help` 面板 | ✓ `interactive-mode.ts:3111` + `:6512-6581`（Markdown 渲染） | ✓ `MiscCommands.java:34`、`:122` | **对齐** | 1 | 都是往聊天区追加纯文本；pi 走 Markdown 组件 |
| S25 | 会话统计面（`/session`） | ✓ pi `/session` 命令 + `components/footer.ts:84-200` 用量（本轮起计入 `usage` 条目） | ✗（TUI） / ✓（web `panels/stats.ts:1`） | **缺失** | 2 | java TUI 无统计屏 |
| S26 | `/changelog` 面板 | ✓ | ✓ `MiscCommands.java:120` | **对齐** | 1 | 均为文本输出 |
| S27 | `/share`（会话分享） | ✓ `session-share.ts:217`（`shareSession:57`；Radius 网关 + `BorderedLoader`） | ✓ `MiscCommands.java:76`（GitHub gist） | **存疑** | 1 | 承诺相同、**后端不同**（pi=Radius，java=gist），且 java 无加载指示器 |
| S28 | `/export`（HTML 导出） | ✓ `core/export-html/index.ts:236 exportSessionToHtml` | ✓ `export/HtmlExporter.java` | **存疑** | 1 | pi 用 vendored marked.min.js + highlight.min.js + `template.html/css/js`；java 自写 `MarkdownToHtml.java`（201 行）—— 输出形状未逐项核 |
| S29 | **`/bug` 故障上报流程**（同意提示 → 可选摘要 → 上传或导出 zip） | ✓ **本轮新增** `bug-report.ts:294` + `core/bug-report.ts`/`bug-report-upload.ts`/`utils/zip.ts`；命令注册 `core/slash-commands.ts:28` | ✗ | **缺失** | 1 | pi slash 命令 **23 → 24**；java 的 slash 注册表无 `bug`（`MiscCommands.java` 等 6 个 builtin 类全表见下） |
| S30 | **崩溃记录 + 下次启动提示**（写 crash log，下次启动提示「Run /bug」；`/bug` 提示每会话最多一次） | ✓ **本轮新增** `core/crash-log.ts`；`interactive-mode.ts` 的 `takeUnnotifiedCrash`/`recordCrash`/`crashReportInstructions`/`suggestBugReport` | ✗ | **缺失** | 1 | java 全仓无 crash-log 命中 |
| S31 | **实验性 micro TUI**（单进程 Pico3 编码代理，自带 TUI） | ✓ **本轮新增** `experimental/micro/`（7 文件 1485 行，`tui.ts:567`） | ✗ | **缺失** | 1 | `experimental/micro/README.md:1-20`；旧 HEAD 该目录 **0 文件**（`git ls-tree 71dca871b`）。实验性、非默认 CLI 路径 |

---

## 渲染特性对齐

| # | 特性 | pi 有 | java 有 | 判定 | 权重 | 证据（pi 侧已对 `3390bd936` 重测） |
|---|---|---|---|---|---|---|
| R1 | Markdown 渲染（标题/粗斜体/列表/引用/代码块） | ✓ `tui/components/markdown.ts:236 class Markdown`（1015 行） | ✗（**死代码**） | **缺失** | 3 | `MarkdownRenderer.java:21` 存在但**生产零调用点** —— 全仓 grep 只命中自身与 `MarkdownRendererTest.java`；助手消息实际走 `MessageBubble.java:80` 的 `TextLayout.split(escapeMarkup(text))`（纯文本） |
| R2 | Markdown：管道表格 | ✓ markdown.ts token 渲染 | ✗（死代码） | **缺失** | 1 | `MarkdownRenderer.java:51 renderTable` 无调用者 |
| R3 | Markdown：删除线（严格 tokenizer） | ✓ `markdown.ts:9 StrictStrikethroughTokenizer` | ✗ | **缺失** | 1 | — |
| R4 | Markdown：链接（OSC 8 超链接） | ✓ `markdown.ts` + `terminal.ts` hyperlinks | ✗ | **缺失** | 1 | java `MarkdownRenderer` 无链接分支（`Block` 只有 7 种，无 Link） |
| R5 | Markdown：流式未闭合围栏修剪 | ✓ `markdown.ts:146 trimPartialClosingFences` | ✗ | **缺失** | 2 | 仅当流式期间有未闭合围栏时可见 |
| R6 | Markdown：扩展 transformer 钩子 | ✓ `markdown-transform.ts:29` + `interactive-mode.ts:2102 getMarkdownTransformers` | ✗ | **缺失** | 1 | 扩展系统整体未落地 |
| R7 | Mermaid 图渲染 | ✓ `components/mermaid.ts:1-89` + `grok-mermaid` 依赖 | ✗ | **缺失** | 1 | java `MarkdownRenderer.java:41-44` 只画 `"[mermaid diagram]\n"+code` 占位框 |
| R8 | LaTeX 数学（行内 `$…$` 与块级 `$$…$$`） | ✓ `tui/latex.ts`（1394 → **1506 行**）+ `test/latex.test.ts`（本轮加 `\sub`/`\sup` 堆叠脚本布局、`\bf`/`\it`/`\sf`/`\tt`/`\rm`/`\cal`/`\sl` 字体切换、脚本值归一化） | ✗ | **缺失** | 1 | java 全仓无 latex 命中 |
| R9 | 代码块语法高亮（多语言、highlight.js 词法） | ✓ `utils/syntax-highlight.ts` + `loadAllHighlightLanguages`（`interactive-mode.ts:126` import、`:1037` 调用）、theme 里 10 个 `syntax*` token | ✗（**死代码**） | **缺失** | 2 | java `SyntaxHighlighter.java:19` 只有 3 色 + 40 个硬编码关键字；调用点只有 `EditorElement.java:123`（输入编辑器当前行），**代码块路径 `MarkdownRenderer.java:91` 无人调** |
| R10 | Diff 渲染（+/−/hunk 着色） | ✓ `components/diff.ts:147` + `toolDiffAdded/Removed/Context` token | ✓ `DiffView.java:12`（64 行） | **存疑** | 3 | java 按行首字符给色（`DiffView.java:50-63`），无 hunk 头/行号/词级 diff；pi 的 diff 走 `tool-execution.ts` 的富渲染 |
| R11 | 逐工具渲染器（read/write/edit/bash/grep/find/ls 各自形状） | ✓ `core/tools/renderers/*.ts`（1049 → **1057 行**，8 文件；本轮 `bash.ts` 加 `>60s` 时长格式 `1m 30s`/`1h 5m`） | ✗ | **缺失** | 3 | java 只有一张通用 `ToolCallCard.java:12`（39 行：`name`+`status`+截断 200 字的 args） |
| R12 | 工具执行卡：可折叠 + `Ctrl+O` 展开 + `… (N more lines, ctrl+o to expand)` | ✓ `tool-execution.ts:61 expanded`、`:166-170` 折叠提示、`:244 setExpanded`（本轮加**过期图片转换丢弃**守卫：缓存带 `sourceData`/`sourceMimeType`，异步转换回来时比对当前结果，不匹配则丢） | ✗ | **缺失** | 2 | java 无折叠态，`TOOLS_EXPAND` 未接线（`PiTuiApp.java:437`） |
| R13 | bash 执行组件（实时增量输出 + 宽度自适应） | ✓ `components/bash-execution.ts:220` + `test/bash-execution-width.test.ts` | ✗ | **缺失** | 2 | java TUI 无 bash 屏；web 侧有 `bashOutput` 帧（`AgentEventTranslator.java:130`） |
| R14 | 图片渲染（Kitty / iTerm2 图像协议 + 单元格尺寸探测 + 降级） | ✓ `terminal-image.ts:696` + `components/image.ts:127` + `test/terminal-image.test.ts`、`block-images.test.ts`（本轮加 **WezTerm 修复**：Kitty 图像格先清行再绘制，避免 EL 擦掉相交图像格） | ✗ | **缺失** | 1 | java 只有 `[image: mimeType]` 占位文本（`MessageBubble.java:96-100`） |
| R15 | 图片粘贴（剪贴板 → 附件） | ✓ `app.clipboard.pasteImage`（`core/keybindings.ts:142`）+ `clipboard-image.ts` + `test/clipboard-image.test.ts` | ✗ | **缺失** | 1 | java 全仓无 clipboard-image |
| R16 | 元数据气泡分型（模型切换/思考等级/工具集/压缩/分支/自定义，各带图标+色） | ✓ `interactive-mode.ts:3667 addMessageToChat` 各 message 组件 | ✓ `MetaKind.java:16-34`（7 型） | **对齐** | 2 | 图标/颜色集不同（java 自选 emoji + hex），**承诺相同** |
| R17 | 压缩摘要消息卡 | ✓ `compaction-summary-message.ts:10 class CompactionSummaryMessageComponent`（68 行；**本轮加鼠标左键点击展开/折叠**，`MouseRegion`） | ✓（弱形状） | **存疑** | 2 | java 走 `ChatMessage.java:66-68` 的 `System("Compacted context: …", COMPACTION)` 一行；无独立卡、无 `usage` 开销提示（`interactive-mode.ts:3902 addCompactionCostNotice`）、无点击展开 |
| R18 | 分支摘要消息卡 | ✓ `branch-summary-message.ts:10`（67 行；本轮同样加点击展开） | ✗ | **缺失** | 1 | 依赖 B1（branch summary 功能本体缺失） |
| R19 | skill 调用消息卡 | ✓ `skill-invocation-message.ts:11 class SkillInvocationMessageComponent`（64 行；本轮加点击展开） | ✗ | **缺失** | 1 | java `ChatMessage.from` 无对应分支 |
| R20 | 自定义消息卡（`custom_message`，`display` 门） | ✓ `custom-message.ts:113` | ✓ `ChatMessage.java:71-74` | **对齐** | 1 | java 用 `[customType] plainText`，pi 用 Markdown 渲染 `content` |
| R21 | 回合分隔条（`Worked for Ns • Local tools: N calls`） | ✗（pi 无此构造） | ✓ `ChatScreen.java:166-178` | **非对齐项**（java 独有，Codex 风格） | 0 | 见「pi-java 独有」，**排除出分母** |
| R22 | 启动公告 / 彩蛋组件（`earendil-announcement`、`armin`、`daxnuts`） | ✓ `components/earendil-announcement.ts:53`、`armin.ts:382`、`daxnuts.ts:164` | ✗ | **缺失** | 1 | 一次性路径 |
| R23 | 窗口/终端标题更新 | ✓ `interactive-mode.ts:1047 updateTerminalTitle` | ✗ | **缺失** | 1 | — |
| R24 | **缓存预热用量行**（聊天区 `formatCacheWarmingUsage` + `/session` 里的 `formatCacheWarmingStatus`／missCost／warmCost；footer 计入 `usage` 条目） | ✓ **本轮新增** `core/cache-warmer.ts`；`interactive-mode.ts:3892 addCacheWarmingUsage`、`footer.ts:84-88`（`usage` 条目计入总量） | ✗ | **缺失** | 1 | 门：`settingsManager.getShowCacheMissNotices()`；java 全仓无 cache-warmer |
| R25 | **thinking 块被丢弃提示**（`Anthropic dropped N thinking blocks (details in session)`） | ✓ **本轮新增** `interactive-mode.ts` 的 `countDroppedThinkingBlocks`/`maybeShowThinkingDropNotice` | ✗ | **缺失** | 1 | 只在与上一条 assistant 消息的丢弃数**增加**时显示；java 无此构造 |

---

## 能力单元清单（其余）

### 键绑定 / 交互

| # | 能力单元 | 类别 | pi 侧证据 | pi-java 侧证据 | 判定 | 权重 | 台账条目 |
|---|---|---|---|---|---|---|---|
| K1 | `Esc` 中断当前运行 | 键绑定 | `core/keybindings.ts:93 app.interrupt` | `PiTuiApp.java:422`、`KeybindingsManager.java:19` | **对齐** | 3 | — |
| K2 | `Ctrl+C` 清空输入 | 键绑定 | `keybindings.ts:94 app.clear` | `PiTuiApp.java:429` | **对齐** | 2 | — |
| K3 | `Ctrl+D` 空输入时退出 | 键绑定 | `keybindings.ts:95 app.exit` | `PiTuiApp.java:430-434` | **对齐** | 2 | — |
| K4 | `Ctrl+L` 打开模型选择器 | 键绑定 | `keybindings.ts:116 app.model.select` | `PiTuiApp.java:435-436` | **对齐** | 2 | — |
| K5 | `Alt+Enter` 排队 follow-up | 键绑定 | `keybindings.ts:134-137`：**Windows 上是 `ctrl+q`**，`alt+enter` 归 `tui.input.newLine` | `KeybindingsManager.java:28` 绑 `alt+enter`；`TamboUIAdapter.isNewlineEnter` 走 `shift+enter` | **存疑** | 2 | H-9.2-② |
| K6 | `Ctrl+P` 模型循环（前/后） | 键绑定 | `keybindings.ts:107-115 app.model.cycleForward/Backward` | 常量存在（`KeybindingsManager.java:22`）但 `PiTuiApp.java:437` **default 分支＝死码** | **缺失** | 2 | — |
| K7 | `Shift+Tab` 循环 thinking level | 键绑定 | `keybindings.ts:100 app.thinking.cycle` | 同上（`KeybindingsManager.java:24` → 死码） | **缺失** | 2 | — |
| K8 | `Ctrl+O` 展开/折叠工具输出 | 键绑定 | `keybindings.ts:117 app.tools.expand` | 同上（`:25` → 死码），且无折叠态（R12） | **缺失** | 2 | — |
| K9 | `Ctrl+T` 显隐 thinking 块 | 键绑定 | `keybindings.ts:118 app.thinking.toggle` | 同上（`:26` → 死码） | **缺失** | 2 | — |
| K10 | `Ctrl+G` 外部编辑器 | 键绑定 | `keybindings.ts:126 app.editor.external` + `external-editor.ts:46` | 同上（`:27` → 死码） | **缺失** | 1 | — |
| K11 | `Alt+Up` 取回排队消息 | 键绑定 | `keybindings.ts:138 app.message.dequeue` | 同上（`:29` → 死码） | **缺失** | 1 | — |
| K12 | `Ctrl+Z` 挂起进程 | 键绑定 | `keybindings.ts:96-99 app.suspend`（非 Windows） | ✗ | **缺失** | 1 | — |
| K13 | `Ctrl+X` 复制消息到剪贴板 | 键绑定 | `keybindings.ts:130 app.message.copy` | ✗（有 `/copy` 命令，无键位） | **缺失** | 1 | — |
| K14 | `Ctrl+V`/`Alt+V` 粘贴图片 | 键绑定 | `keybindings.ts:142 app.clipboard.pasteImage` | ✗ | **缺失** | 1 | — |
| K15 | `Ctrl+N` 命名会话过滤 | 键绑定 | `keybindings.ts:122 app.session.toggleNamedFilter` | ✗ | **缺失** | 1 | — |
| K16 | `app.session.*` 家族（new/tree/fork/resume + 树/会话选择器内 11 个键位） | 键绑定 | `core/keybindings.ts:32-44,146-186` | ✗ | **缺失** | 2 | — |
| K17 | 用户键位覆盖（`keybindings.json`） | 配置 | `core/keybindings.ts` + `test/keybindings-migration.test.ts` | ✗（`KeybindingsManager.java:14` 注释「User overrides from keybindings.json → Phase 6」） | **缺失** | 1 | H-9.2-⑩ |
| K18 | 键位提示渲染（`keyText`/`keyHint`/`rawKeyHint`） | 组件 | `components/keybinding-hints.ts:48` | `KeybindingHints.java:22`（只有 `keyText`，无 `keyHint`/`rawKeyHint`） | **存疑** | 2 | — |
| K19 | 滚轮/触控板归一化（事件流 → 行滚动） | 交互 | pi 在 `tui-alt-screen.ts:469 scrollBy` / `tui-main-screen.ts` 各自处理 | `ScrollInputNormalizer.java:20`（274 行，Codex TUI2 PR#8357 移植）+ `ScrollConfig.java:35` | **存疑** | 3 | — |
| K20 | 键盘滚动（空编辑器时 ↑↓/PgUp/PgDn/Home/End 驱动转录） | 交互 | `tui-alt-screen` 的 `tui.altScreen.*` 键位（`tui/keybindings.ts:46-61`） | `PiTuiApp.java:340-361 isChatNavigation`/`scrollByKey` | **对齐** | 2 | — |
| K21 | 转录搜索 | 交互 | `alt-screen-search.ts:156-197` | ✗ | **缺失** | 1 | — |
| K22 | 鼠标文本选择 / copy-on-select | 交互 | `tui-alt-screen.ts:296 hasActiveSelection`（本轮加：失败时显示具体错误文案 + 5 s flash） | ✗ | **缺失** | 1 | — |
| K23 | 滚动条（绘制 + 拖拽 + 悬停加宽） | 交互 | `tui-alt-screen` scrollbar + `test/scrollbar-theme.test.ts` | `ChatViewportElement.java:130-190`、`:243-290` | **对齐** | 3 | — |
| K24 | 斜杠命令补全弹层 | 交互 | `autocomplete.ts:283 CombinedAutocompleteProvider`（835 行） | `SlashCompleter.java:17`（146 行，前缀匹配） | **对齐** | 2 | — |
| K25 | `@文件` 路径补全（fuzzy + `fd`） | 交互 | `autocomplete.ts:421-477`（file 分支）、`:711 scoreEntry`；**本轮加**：CJK 标点作分隔符（`utils.ts` 的 `cjkPunctuationRegex`/`autocompleteSeparatorRegex`/`autocompleteBoundaryRegex`）、`skill:` 按裸名参与模糊排序、quoted path 可含空白 | ✗ | **缺失** | 2 | — |
| K26 | 大粘贴折叠为 `[paste #N +L lines]` | 交互 | `tui/components/editor.ts:28-51` + `test/editor.test.ts` | ✗ | **缺失** | 1 | — |
| K27 | 提示历史 ↑/↓ 导航（含 draft 还原、100 条上限） | 交互 | `editor.ts:343-344 history/historyIndex`、`:425 addToHistory` | ✗（↑/↓ 是 `moveCursorUp/Down`，`EditorComponent.java:146-147`） | **缺失** | 3 | H-9.2-② |
| K28 | undo（fish 式连续字符合并） | 交互 | `editor.ts:365 undoStack` + `undo-stack.ts:28` | `EditorComponent.java:312-328` + `EditorComponentUndoKillRingTest` | **对齐** | 3 | — |
| K29 | kill ring / yank / yank-pop | 交互 | `editor.ts:337-338` + `kill-ring.ts:46` | `KillRing.java:1` + `EditorComponent.java:287-311` | **对齐** | 2 | — |
| K30 | 词导航（`Ctrl+←/→`、`Alt+B/F`） | 交互 | `word-navigation.ts:117` | `EditorWordNav.java:1`（199 行） | **对齐** | 2 | — |
| K31 | jump mode（`Ctrl+]` / `Ctrl+Alt+]` 跳转到字符） | 交互 | `tui/keybindings.ts` `tui.editor.jumpForward/Backward` | ✗ | **缺失** | 1 | — |
| K32 | Kitty 键盘协议 / `modifyOtherKeys` 解析 | 协议 | `tui/keys.ts:705`（`CSI 27 ; m ; k ~`）、`:1252`（kitty 序列分派）、1401 行 | `EventParser.java:220,308 parseCsiU`（CSI-u 有；`modifyOtherKeys` 无） | **存疑** | 3 | — |
| K33 | bracketed paste 解析 | 协议 | `tui/editor.ts:328-329` | `EventParser.java:230 readPasteContent` | **对齐** | 2 | — |
| K34 | 原生剪贴板 helper（macOS/Windows/Linux） | 平台 | `native-platform.ts:1-63` + `native-module-path.ts:31` + 3 个 native 测试 | ✗（只有 `java.awt.Toolkit` 兜底，`MiscCommands.java:108`） | **缺失** | 1 | — |
| K35 | 终端能力探测（trueColor / hyperlinks / images / tmux 转发） | 平台 | `terminal-image.ts:14-80` + `terminal-colors.ts:73` | ✗ | **缺失** | 1 | — |
| K36 | 模式 2027 握手规避（ConPTY 字节泄漏修复） | 平台 | —（pi 无此构造） | `NoMode2027Backend.java:14` + `NoMode2027JLineBackend.java:1` | **非对齐项**（java 独有） | 0 | **排除出分母** |
| K37 | **消息卡鼠标左键点击展开/折叠**（压缩摘要 / 分支摘要 / skill 调用三卡） | 交互 | ✓ **本轮新增** `MouseRegion` 包装 + `setExpanded(!expanded)`：`compaction-summary-message.ts:10`、`branch-summary-message.ts:10`、`skill-invocation-message.ts:11` | ✗ | **缺失** | 1 | pi 的 `MouseRegion` 组件本身 pi-java 也没有（C14 缺失）⇒ 该交互在 java 侧无基座 |

### TUI 组件

| # | 能力单元 | 类别 | pi 侧证据 | pi-java 侧证据 | 判定 | 权重 | 台账条目 |
|---|---|---|---|---|---|---|---|
| C1 | 滚动视图（行级 offset + follow 语义 + resize 重排） | 组件 | `components/scroll-view.ts:224` + `chat-viewport.ts:46` | `ChatViewportElement.java:39`（300 行，`ScrollState` 内嵌） | **对齐** | 3 | — |
| C2 | 选择列表（上下选 + 确认/取消） | 组件 | `components/select-list.ts:273` | `SelectList.java:1`（145 行） | **存疑** | 2 | pi 有分组/模糊/多选；java 只有单选线性列表 |
| C3 | 设置列表（bool/enum/string/number 多类型编辑） | 组件 | `components/settings-list.ts:328` | ✗（`SettingsScreen` 用裸 Element 拼行） | **缺失** | 2 | — |
| C4 | 输入框（单行，`components/input.ts`） | 组件 | `components/input.ts:494` | ✗（统一走 `TextAreaState`） | **缺失** | 1 | — |
| C5 | loader / spinner（braille 10 帧动画 + 文案 + 颜色函数） | 组件 | `components/loader.ts:13-60` | ✗（`StatusBar.java:29-31` 是静态 `● running`） | **缺失** | 3 | — |
| C6 | working 指示器（spinner + 文案 + 计时） | 组件 | `interactive-mode.ts:2214 showWorkingStatusIndicator`、`:2229 setWorkingVisible` + `status-indicator.ts` `WorkingStatusIndicator` | ✗ | **缺失** | 3 | — |
| C7 | bordered loader（可取消的加载框） | 组件 | `components/bordered-loader.ts:68` | ✗ | **缺失** | 1 | — |
| C8 | cancellable loader | 组件 | `components/cancellable-loader.ts:40` | ✗ | **缺失** | 1 | — |
| C9 | 倒计时定时器组件 | 组件 | `components/countdown-timer.ts:39` | `StatusIndicator.java:62-79` + `CountdownWake.java:1`（按帧重算，等价） | **对齐** | 2 | B3（已结案） |
| C10 | 状态指示器槽（单槽 + kind 守卫 + retry/compaction/branch 文案） | 组件 | `interactive-mode.ts:2180 showStatusIndicator` + `:2193 clearStatusIndicator` + `status-indicator.ts` | `StatusIndicator.java:21`、`ChatScreen.java:262-325` | **对齐** | 2 | B3（已结案） |
| C11 | 盒/边框面板 | 组件 | `components/box.ts:167` | TamboUI `panel`（`TamboUIAdapter`） | **对齐** | 2 | — |
| C12 | stack / h-stack / v-stack / spacer / text | 组件 | `components/stack.ts:154`、`h-stack.ts:44`、`v-stack.ts:33`、`spacer.ts:28`、`text.ts:107` | TamboUI `column/row/spacer/text` | **对齐** | 3 | 每轮渲染都走（布局基元） |
| C13 | truncated-text / visual-truncate | 组件 | `components/truncated-text.ts:65`、`components/visual-truncate.ts:50` | 部分：`TextLayout.java:1`（232 行）含 displayWidth/wrap | **存疑** | 2 | — |
| C14 | mouse-region（把鼠标事件限定到区域） | 组件 | `components/mouse-region.ts:33`（本轮被三张消息卡用上，见 K37） | ✗（`ChatViewportElement.handleMouseEvent:205` 是元素级） | **缺失** | 1 | — |
| C15 | dynamic-border（编辑框边框随思考等级变色） | 组件 | `components/dynamic-border.ts:25` + `interactive-mode.ts:4284 updateEditorBorderColor` | ✗ | **缺失** | 1 | — |
| C16 | 编辑组件（多行 + 折叠 + 历史） | 组件 | `components/editor.ts:294 class Editor`（2470 行）+ `editor-component.ts:74` + `custom-editor.ts:148` | `EditorComponent.java:27`（461 行） | **存疑** | 3 | 缺 K26/K27/K31；见「存疑」清单 |
| C17 | 模糊匹配 | 工具 | `fuzzy.ts:12 fuzzyMatch`（**子序列**匹配：`indexOf` 逐个找 query 字符、可跨字符；`-5×连续 / +2×间隔 / -10×词边界 / +0.1×位置`）、`:100 fuzzyFilter`（按空白/斜杠**分词**、每词都要匹配、分数求和） | `FuzzyMatcher.java:31 score()` 是**子串**匹配（`startsWith`→0、`indexOf`→下标、否则不匹配），**无分词** | **存疑** | 2 | **本轮判定变化（对齐 → 存疑）**：名字对上了、算法不同 —— 例：query `abc` vs `a-b-c`，pi 命中、pi-java 不命中。且挂载点也不同：pi 的 `fuzzyFilter` 服务 autocomplete/session-selector/tree-selector，pi-java 的只服务 `SelectList.java:142`（而 pi 的 `select-list.ts:61 setFilter` 用的是**纯 `startsWith`**，不是 fuzzy）⇒ 两侧三处错位 |
| C18 | 布局引擎（constraint/flex/嵌套） | 框架 | `layout.ts:449` + `layout-node.ts:51` | TamboUI 布局（外部） | **对齐**（经 TamboUI） | 3 | — |
| C19 | stdin 缓冲（CSI 分片重组） | 框架 | `stdin-buffer.ts:444` + `test/stdin-buffer.test.ts` | TamboUI `Backend` | **对齐**（经 TamboUI） | 3 | — |

### 主题 / 样式

| # | 能力单元 | 类别 | pi 侧证据 | pi-java 侧证据 | 判定 | 权重 | 台账条目 |
|---|---|---|---|---|---|---|---|
| T1 | 内置 dark + light 主题 | 主题 | `theme/dark.json:1`（91 行）、`theme/light.json:1`（90 行） | `themes/pi-dark.tcss`（37 行）、`pi-light.tcss`（37 行）+ `PiTheme.java:20-21` | **对齐** | 2 | — |
| T2 | 主题 token 覆盖（约 70 个语义色：`mdHeading`/`toolDiffAdded`/`syntax*`/`thinking*`/`bashMode`/`export.*`） | 主题 | `theme/dark.json:19-88` | ✗（8 条 CSS 规则：Screen/ChatPanel/scrollbar×4/StatusBar/EditorComponent/Separator） | **缺失** | 3 | 每条消息的配色都走 |
| T3 | 用户自定义主题（JSON + schema 校验） | 主题 | `theme-schema.json`（356 行）+ `theme-json.ts:146` | 部分：`.tcss` 文件（`PiTheme.java:74-90`），**无 schema、格式不同** | **存疑** | 1 | — |
| T4 | 主题热重载（文件 watch） | 主题 | `theme-controller.ts:172` + `test/theme-controller.test.ts` | ✗ | **缺失** | 1 | — |
| T5 | 思考等级 → 边框/文字颜色映射 | 主题 | `theme.ts` `getThinkingBorderColor`/`thinkingOff…Max` 8 档 | ✗ | **缺失** | 2 | — |
| T6 | 语法高亮色板（10 token） | 主题 | `dark.json:69-79 syntax*` | `SyntaxHighlighter.java:24-26`（3 色） | **存疑** | 2 | — |
| T7 | 导出专用主题色（`export.pageBg/cardBg/infoBg`） | 主题 | `dark.json:84-88` + `export-html/index.ts:80-125` | ✗ | **缺失** | 1 | — |
| T8 | 主题解析优先级（CLI > settings > 扩展候选 > dark） | 主题 | `theme.ts`（1234 行）`getThemeByName` + `setRegisteredThemes`（`interactive-mode.ts:604`） | `PiTheme.java:66-77 resolveTheme` | **对齐** | 2 | — |
| T9 | 主题按扩展贡献注册 | 主题 | `interactive-mode.ts:604 setRegisteredThemes` | `PiTheme.resolveTheme(candidates)`（`PiTheme.java:73-76`） | **对齐** | 1 | — |

### web 面（对齐相关部分）

> pi 仓库内**没有** web UI（`grep -rln "WebSocket" packages/*/src` 只命中 AI provider 的 HTTP/WS 客户端与 settings-selector，`find . -name "*web-ui*"` 零命中）。因此 web 的**呈现层**单列在「pi-java 独有」；下面只列**事件/线格式**这一面对齐相关的能力单元，其 pi 侧证据取自 pi 的 agent-loop 事件词汇与 pi-webui 基线协议。

| # | 能力单元 | 类别 | pi 侧证据 | pi-java 侧证据 | 判定 | 权重 | 台账条目 |
|---|---|---|---|---|---|---|---|
| W1 | 流式事件词汇（`agent_start`/`message_update`） | web 协议 | `interactive-mode.ts:3249 handleEvent`（`:3243 subscribeToAgent`）+ pi-webui `shared/protocol.ts` | `AgentEventTranslator.java:75-96` | **对齐** | 3 | B40（已结案） |
| W2 | `tool_execution_start/update/end` 由**真实工具执行事件**驱动 | web 协议 | pi `agent-loop.ts` 的工具执行事件 | `AgentEventTranslator.java:60-62,157-186` | **对齐** | 3 | B43（已结案） |
| W3 | `turn_end` 带 `{message, toolResults}` | web 协议 | `agent/types.ts:438` | `AgentEventTranslator.java:143-155` | **对齐**（残余：错误路 `message` 为 null ⇒ 省键） | 3 | B42/B49（已结案，残余登记） |
| W4 | `message_update` 顶层带 `usage` | web 协议 | `json-event.ts:11-15,56-60` | `AgentEventTranslator.java:100-104`（经 `WebWireJson.assistantNode`） | **对齐** | 3 | B28（已结案） |
| W5 | `toolcall_start` 带 `id`+`toolName` | web 协议 | `json-event.ts:23-30` | `StreamPartialBuilder.emitToolCallStart(id,name):278` | **对齐** | 3 | B29（已结案） |
| W6 | 消息 wire 形状（`role`、`toolCallId`、块判别字面量 `toolCall`/`thinking`） | web 协议 | pi-ai `types.ts` 消息形状 | `WebWireJson.java:27-101` | **对齐** | 3 | B48（已结案） |
| W7 | 终局 assistant 消息 `usage` 非空 | web 协议 | `types.ts:439` | `WebWireJson.java:75`（`B41` 已修） | **对齐** | 3 | B41（已结案） |
| W8 | `queue_update` / `compaction_*` / `auto_retry_*` 推送前端 | web 协议 | pi `agent-session.ts:594`（queue_update）、压缩/重试事件 | ✗ `AgentEventTranslator.java:63-65` 明确「暂不推前端」 | **缺失** | 1 | H-9.2-⑨、B34、B35 |
| W9 | 消息 `timestamp` 上 wire | web 协议 | pi-ai 消息必填 `timestamp`（`types.ts:417-421`） | ✗ **刻意偏差**（`WebWireJson.java:65-66` 注释「维持既有『wire 无消息 timestamp』的有意偏离」） | **存疑** | 1 | B50、H-9.2-⑧ |
| W10 | `agent_end.messages` 整表替换 | web 协议 | pi `agent_end` 带本 pass `newMessages` | `AgentEventTranslator.java:117-127`（空消息时不带 `messages` 键，防前端清空） | **存疑** | 3 | A8 |
| W11 | 宿主消费者并发契约声明（TUI/RPC/web 三面） | 架构 | —— | 收敛到 `PiLaneSink.emit` 锁，无显式声明 | **存疑** | 1 | A6 |
| W12 | `transcript()` 读取点清点（web 三处） | 架构 | —— | `WebDispatcher.java:236,350,381` | **存疑** | 1 | A2 |
| W13 | web 会话范围（`--session-dir` / `latest(cwd)`） | 行为 | pi `findMostRecentSession(dir)` 只读一个项目目录 | `PersistentSessionRepositories.latest(cwd)`（包⑫ 已修） | **对齐** | 2 | A12（已结案） |
| W14 | `--no-session` 在 web 路径生效 | 行为 | pi 语义「别落盘」 | ✗ `SessionPersistence.resolvePersistentWeb` 的 `args` 形参一句话都没用 | **缺失** | 1 | **B54** |
| W15 | 会话列表 API 带 `onProgress`（异步进度 + 增量结果 + 可取消） | 行为 | `session-manager.ts` `SessionListProgress`（本轮加 **`partialSessions`** 第三参）＋ `AbortSignal`；`session-selector.ts:76-116` 消费 | ✗ 同步全量 | **缺失** | 1 | **B55**（⚠️ 本轮漂移：pi 侧行号从 `:768`/`:812` 漂到新位置，且**能力本身也变强了** —— 不只是「有 onProgress」，还带增量快照与取消 ⇒ B55 的措辞需同步更新） |
| W16 | `find()` 的范围（`--session <id>` 跨项目查找） | 行为 | pi `findLocalSessionByExactId(id, cwd, sessionDir)` 项目内 | `PersistentSessionRepositories.find():52` 走 `all()` ⇒ O(全部)、跨项目 | **缺失** | 1 | **B53** |

---

## pi-java 独有（非对齐项）

> pi 仓库内**零对应物**。**不计入**完成度分母，**权重一律记 0**（下表不设「权重」列；17 条整块独有能力按加权规则全为 0 ⇒ 对加权完成度零贡献、零拖累）。

| 能力 | 说明 |
|---|---|
| **本地 Web UI 整体**（`pi-java-web`，1865 Java + 1654 TS + 675 CSS） | pi 仓库无 web 包（`packages/` 11 个包无 web；`find . -name "*web-ui*"` 零命中）。基线取自**仓库外**的第三方项目 `Zetaphor/pi-webui`（见 `docs/15-phase7-webui-design.md:21-33`），前端复用外部 npm 包 `@mariozechner/pi-web-ui` 的 `<message-list>` / `<streaming-message-container>` / `<message-editor>` / `<theme-toggle>` 自定义元素（`frontend/package.json:9-16`、`client/main.ts:809-870`） |
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
| **模式 2027 握手规避后端** | `NoMode2027Backend.java:14`（ConPTY 字节泄漏修复）+ `NoMode2027JLineBackend.java:1` + 2 个测试 |
| 滚轮/触控板归一化（Codex TUI2 PR#8357 移植） | `ScrollInputNormalizer.java:20`（274 行）—— pi 无此构造（pi 的滚动是 alt-screen 内的直接处理） |
| 回合分隔条 `Worked for Ns • Local tools: N calls` | `ChatScreen.java:166-178`（Codex CLI 风格） |
| 元数据气泡 emoji 图标集（⚙/🧠/🔧/🗜/⎇/◆） | `MetaKind.java:16-34` |
| Codex 风格启动方框卡 | `WelcomeOverlay.java:20-72` |
| 原生滚动回退内联 shell（`InlineTuiShell`） | `InlineTuiShell.java:1`（390 行） |

---

## 整块缺失

| 子系统 | pi LOC | 说明 |
|---|---|---|
| **终端图像协议**（Kitty / iTerm2） | **823**（`terminal-image.ts:696` + `components/image.ts:127`） | 含能力探测、单元格尺寸探测、base64 尺寸解析、图像 ID 分配、降级占位；测试 `terminal-image.test.ts` + `block-images.test.ts` + `image-test.ts` |
| **LaTeX 数学渲染** | **1394 → 1506**（`latex.ts`） | 行内/块级 `$…$`、Unicode 转换、tokenizer；**本轮加** `ScriptNode` 堆叠脚本 + 字体切换命令；测试 `latex.test.ts` |
| **Markdown 组件** | **1015**（`components/markdown.ts`） | 严格删除线 tokenizer、链接、hr、任务列表、流式围栏修剪、缓存、padding/背景；测试 `markdown.test.ts`（pi-java 有 `MarkdownRenderer` 221 行但**生产零调用**） |
| **alt-screen 搜索 + 选择** | **327 + ~500**（`alt-screen-search.ts` + `tui-alt-screen.ts` 的选择/复制/闪烁部分） | 搜索索引、匹配导航、文本选择、自动滚动、copy-on-select、OSC133 zone |
| **扩展 UI 组件族** | **~700**（`extension-selector/input/editor` + `interactive-mode.ts:2558-2740`） | `custom<T>()` / `setFooter` / `setHeader` / widget / 通知 / 错误显示；**本轮加** `description`/`initialValue` 选项；pi-java 仅 RPC 通道 |
| **选择器族（未实现 9 个）** | **~2900**（config 942 + scoped-models 401 + session-selector 1045 的非基础部分 + thinking 154 + theme 67 + trust 134 + oauth 214 + login 233 + first-time-setup 145 + show-images 50 + user-message 155） | 见 S6-S9、S11、S13、S15-S21 |
| **逐工具渲染器** | **1049 → 1057**（`core/tools/renderers/*.ts`） | read/write/edit/bash/grep/find/ls 各自的展示形状；**本轮** bash 时长格式支持 `>60s` |
| **工具执行卡（可折叠 + 流式）** | **641 → 653**（`tool-execution.ts:433` + `bash-execution.ts:220`） | `Ctrl+O` 展开、部分结果、diff、图像；**本轮加**过期图片转换守卫 |
| **主题系统（JSON + schema + 热重载 + 70 token）** | **~1900**（`theme.ts:1234` + `theme-controller.ts:172` + `theme-json.ts:146` + `theme-schema.json:356`） | pi-java 只有 2 个各 37 行的 `.tcss` |
| **提示历史 / 大粘贴折叠 / jump mode** | **~400**（`editor.ts` 相应段） | 见 K26/K27/K31 |
| **loader / spinner / bordered-loader** | **~250**（`loader.ts:101` + `bordered-loader.ts:68` + `cancellable-loader.ts:40` + `alt-screen-flash.ts:51`） | pi-java 无任何动画指示器 |
| **原生剪贴板 / 终端能力探测** | **~170**（`native-platform.ts:63` + `native-module-path.ts:31` + `terminal-colors.ts:73`） | 含 macOS/Windows/Linux native `.node` helper |
| **`@文件` 补全（fuzzy + fd）** | **~500**（`autocomplete.ts` 的文件分支） | pi-java `SlashCompleter` 只做前缀匹配 |
| **故障上报 + 崩溃日志**（**本轮新增**） | **~600**（`bug-report.ts:294` + `core/bug-report.ts` + `core/bug-report-upload.ts` + `core/crash-log.ts` + `utils/zip.ts`） | `/bug` 全流程 + 崩溃落盘与下次启动提示；见 S29/S30 |
| **实验性 micro TUI**（**本轮新增**） | **1485**（`experimental/micro/` 7 文件） | 单进程 Pico3 编码代理；见 S31 |
| **前端测试** | **0（pi 侧无 web）** | pi-java 前端**零测试**：`package.json` 无 `test` 脚本、无 vitest/jest/playwright 依赖、无 `*.test.*`/`*.spec.*` 文件；Maven `frontend-maven-plugin` 只跑 `npm ci` + `npm run build`（`pi-java-web/pom.xml:74-95`） |

---

## 台账纠错

| 条目 | 台账说什么 | 实际 | 证据 |
|---|---|---|---|
| **H-9.2-⑦** | 「Anthropic 路径的**思考内容仍按 TextContent 处理**」（`docs/08b:628`） | **已过期** —— 包①（`docs/31 §8.33`）后 Anthropic 车道已发 `ThinkingStart`/`ThinkingContent` | `pi-java-ai/.../protocol/AnthropicMessagesApi.java:207`、`:226` 两处 `emitThinkingStart(...)`；`ContentBlock.ThinkingContent` 已带 `signature`/`redacted`（`MessageBubble.java:90-92` 的 `case ContentBlock.ThinkingContent(var text, _, _)`） |
| **H-9.2-⑧** | 「webui 未决字段**待 Stage A 实机钉死**」（`docs/15:150`），指 `message_end` 的 timestamp 去重 | **已定但结论与设计稿相反** —— Stage A-D 全部完成（`docs/15:199-218`），落地的是**不给消息带 timestamp**（有意偏离），前端直贴不判重 | `WebWireJson.java:64-66` 注释原文「timestamp 不带上：维持既有『wire 无消息 timestamp』的有意偏离（client/main.ts:261/286 直贴不判重）」；`client/main.ts:315-340` 的 `message_end` 分支无判重 |
| **H-9.2-⑨** | 「web 端 `queue_update` / `compaction_*` / `auto_retry_*` **暂不推前端**」（`AgentEventTranslator.java:57`） | **仍成立**（行号已漂移到 `:63-65`，措辞与行为都对） | `AgentEventTranslator.java:63-65` `default -> { // queue_update / compaction_* / auto_retry_* 等暂不推前端 }` |
| **H-9.2-⑩** | 「`keybindings.json` 用户覆盖 → Phase 6」（`KeybindingsManager.java:14`） | **仍成立** | `KeybindingsManager.java:14` 同句原文；`bindings` 表只有 11 项硬编码默认（`:57-68`），无文件读取 |
| **H-9.2-②** | 「`app.message.followUp`（Alt+Enter 排队）与 Alt+Enter=换行 **键位占用**」（`docs/08b:506`） | **仍成立，且比台账记的更严重** —— pi 在 **Windows 上 followUp 是 `ctrl+q`**，`alt+enter` 归换行 | pi `core/keybindings.ts:134-137`（`windowsKeybindings ? "ctrl+q" : "alt+enter"`）；java `KeybindingsManager.java:67` 把 `alt+enter` 绑给 `FOLLOW_UP`，`PiTuiApp.java:307` 的 `isNewlineEnter` 走 `shift+enter` |
| **H-9.2-③** | 「`StreamPartialBuilder`/`ToolCallAccumulator` 仍为**单工具调用模型**，需多槽位」（`docs/08b:525`） | **仍成立**（本次未核生产可达性，仅确认字段仍是单槽） | `StreamPartialBuilder.java:281-285`：`toolCallId`/`toolCallName`/`toolBlockIndex`/`toolArgBuf` 各一个；`emitToolCallDelta:293` 只在 `toolBlockIndex < 0` 时新建块。⚠️ 属 `pi-java-ai` 面，本次未展开 |
| **E5** | 「`EventParser.java` **515** 行 —— 已登记例外（TamboUI same-package 覆写）」 | **行数正确**，且例外理由成立（只覆写 `parseControlChar` 一处） | `EventParser.java` 实测 515 行；类注释 `:20-29` 明写「only `parseControlChar(int, Bindings)` below differs from upstream 0.4.0」 |
| **B45** | 「`pi-java-tui` 的 `spotbugs:check` 4 条 finding」 | **已修**（结案无误） | `ChatScreen.java:57,60` 已加 `volatile`、`:64-65` 已换 `AtomicInteger`、`:167` 一次读；与台账 G 类记载一致 |
| **B37 / B3 / A12 / B40-B44** | 均已结案 | **核实无误**，代码与台账一致 | `ChatScreen.java:136-143`（错误只进聊天区，无 `lastError` 字段）、`:262-295`（会话事件面）、`WebWireJson.java:75`、`AgentEventTranslator.java:157-186` |
| **H-9.2-①④⑤⑥** | inline 超长草稿 artifact / STDIN 共享管道 / Codex logo 动画 / 命令恢复 | **本次未核**（超出 TUI 判定面或需实机复现），**保持台账原文** | —— |
| **A2** | 「TUI / web / RPC / SQLite 四个读取点**未清点**」 | **部分已清**：TUI **不读** `transcript()`（走 `EntryObserver` + `watchSession` 快照）；web 读**三处**；coding-agent 内部读 7 处 | TUI 侧 `pi-java-tui/src` 对 `transcript()` 零命中；web `WebDispatcher.java:236,350,381`；`AgentSession.java:558,574,652,866,976`、`SessionPersistence.java:43,59`、`RpcDispatcher.java:376`。⇒ TUI 那半可结案，web/RPC/SQLite 那半仍开 |
| **B47** | 「`ChatScreen.runToolCalls` 由 `StreamEvent.ToolCallStart` 计数，pi 由真实工具执行事件驱动」 | **仍成立** | `ChatScreen.java:131 case StreamEvent.ToolCallStart ignored -> runToolCalls.incrementAndGet()`；包⑦ 已把 web 面换源（`AgentEventTranslator.java:82-88`）但**刻意没动 TUI** |

---

## 汇总

> **两套数**：`133` ＝ 对 `71dca871b` 的原始单元集（**重测后判定只改了 1 条**：C17 对齐→存疑）；
> `139` ＝ 加上本轮 pi 新增的 6 个单元后的**当前**单元集。**对外引用请用 139。**

| 档 | 条数（当前 139） | 条数（原 133 集） |
|---|---:|---:|
| **对齐** | **39** | **39** |
| **缺失** | **80** | **74** |
| **存疑** | **20** | **20** |
| 非对齐项（pi-java 独有，**不计入分母**） | 2 | 2 |
| **合计（判定单位）** | **139** | **133** |
| **完成度** | **39 / 139 = 28.1%** | **39 / 133 = 29.3%** |

**分面完成度（当前 139）**

| 面 | 对齐 | 缺失 | 存疑 | 判定单位 | 完成度 | 旧完成度（133 集） |
|---|---:|---:|---:|---:|---:|---:|
| 屏幕/面板（S1–S31 + S2a–d） | 8 | 23 | 4 | 35 | **22.9%** | 25.0% |
| 渲染特性（R1–R25） | 2 | 20 | 2 | 24 | **8.3%** | 9.1% |
| 键绑定/交互（K1–K37） | 11 | 21 | 4 | 36 | **30.6%** | 31.4% |
| TUI 组件（C1–C19） | 7 | 8 | 4 | 19 | **36.8%** | 42.1% |
| 主题/样式（T1–T9） | 3 | 4 | 2 | 9 | **33.3%** | 33.3% |
| web 对齐面（W1–W16） | 8 | 4 | 4 | 16 | **50.0%** | 50.0% |
| **合计** | **39** | **80** | **20** | **139** | **28.1%** | 29.3% |

> 非对齐项 2 条（R21 回合分隔条、K36 模式 2027 规避）已从分母剔除。各面明细见上表逐行 `判定` 列。

**LOC 口径 vs 能力单元口径（已按新 HEAD 更新）**

| 口径 | pi（旧 → 新） | pi-java | 比例（旧 → 新） |
|---|---|---|---|
| UI 总 LOC（含 pi 的框架层） | 39010 → **41219** | 6712 | 17.2% → **16.3%** |
| 应用层 LOC（剔 pi `packages/tui` 框架层） | 18631 → **19187** | 6712 | 36.0% → **35.0%** |
| **能力单元** | — | — | 30.1% → **28.1%** |

**结论**：「TUI 大约只对齐 30%」这个线索 —— **能力单元口径下成立**（当前 **28.1%**，39/139）。三个口径分叉的成因：

1. **含框架层的 LOC 口径（16.3%）偏低**：pi 的 `packages/tui`（18267 行）在 pi-java 侧被**外部库 TamboUI** 顶替，把它算进 pi-java 的分母不成立。
2. **应用层 LOC 口径（35.0%）偏高**：LOC 对「整块缺失」不敏感 —— pi 的 19187 行里有约 **12000+ 行**属于 **16 个整块子系统**（图像协议、LaTeX、Markdown 组件、alt-screen 搜索、扩展 UI、选择器族、工具渲染器、主题系统、故障上报、micro TUI…），这些在 pi-java 侧是 **0 行**，而不是「少写了几行」。
3. **能力单元口径（28.1%）最贴近判据**：它把「有没有这个行为承诺」与「实现得多厚」分开计。**渲染特性面只有 8.3%** 是最大短板（24 项里 20 项缺失，且 `MarkdownRenderer`/`SyntaxHighlighter` 两块**存在但是死代码**）；**web 对齐面 50.0%** 是最高面（web 的事件/线格式面已被包⑥⑦⑨⑩⑪逐条对齐过）。

**本轮（111 提交）对完成度的影响**：`29.3% → 28.1%`（−1.2pp），**全部来自 pi 新增的 6 个缺失单元**（权重全为 1），**没有一条既有判定因 pi 的改动而变差**；唯一一条判定变化（C17 对齐→存疑）是本次读透两侧算法后对**原判**的更正，与 pi 的 111 提交无关。

---

## 存疑清单（全部 20 条）

| # | 能力单元 | 为何存疑 | 证据（pi 侧已对新 HEAD 重测） |
|---|---|---|---|
| S2 | 全屏 alt-screen 模式 | java 用 TamboUI 通用 runner，pi 是自带搜索/选择/复制/图像的专用实现；承诺「全屏 + 内部视口」相同，能力面差 4 块（S2a-d） | `PiTuiLauncher.java:69` vs `tui-alt-screen.ts:197` |
| S12 | 会话树选择器 | java 列 **lane 名 + leafId**，pi 是 entry 级真树；「树选择器」这个名字下两者不是同一对象 | `TreeSelectorScreen.java:26-29` vs `tree-selector.ts:1329` |
| S27 | `/share` | 承诺相同、**后端不同**（pi=Radius 网关，java=GitHub gist），且 java 无 `BorderedLoader` | `session-share.ts:57 shareSession` vs `MiscCommands.java:76-92` |
| S28 | `/export` HTML | pi 用 vendored marked/highlight + 模板，java 自写 `MarkdownToHtml.java`；输出形状未逐项核 | `export-html/index.ts:143 generateHtml` vs `HtmlExporter.java:1` |
| R10 | Diff 渲染 | java 按行首字符着色，无 hunk 头/行号/词级；pi 的 diff 走富渲染器 | `DiffView.java:50-63` vs `components/diff.ts:147` |
| R17 | 压缩摘要消息卡 | java 一行 `System`，pi 是独立卡 + `usage` 开销提示 + **鼠标点击展开** | `ChatMessage.java:66-68` vs `compaction-summary-message.ts:10` + `interactive-mode.ts:3902` |
| K5 | `Alt+Enter` 排队 follow-up | 键位与 pi 的 Windows 默认（`ctrl+q`）冲突；「Alt+Enter=换行」这条被 java 换成 Shift+Enter | `KeybindingsManager.java:67` vs `keybindings.ts:134-137` |
| K18 | 键位提示渲染 | java 只有 `keyText`，无 `keyHint`/`rawKeyHint`/`keyDisplayText` | `KeybindingHints.java:22` vs `keybinding-hints.ts:48` |
| K19 | 滚轮/触控板归一化 | java 移植自 Codex TUI2 PR#8357，pi 侧**无此构造** ⇒ 无法说「行为相同」，也无法说「缺失」 | `ScrollInputNormalizer.java:20` vs `tui-alt-screen.ts:469 scrollBy` |
| K32 | Kitty 键盘协议 | java 有 CSI-u（`parseCsiU:308`）但无 `modifyOtherKeys`（`CSI 27;m;k~`）；覆盖率未逐序列核 | `EventParser.java:220,308` vs `keys.ts:705` |
| C2 | 选择列表 | pi 有分组/模糊/多选，java 是单选线性列表 | `SelectList.java:1` vs `select-list.ts:273` |
| C13 | truncated-text / visual-truncate | java 的截断能力散在 `TextLayout`/`ToolCallCard.truncate`，无独立组件、行为未逐项核 | `TextLayout.java:1`、`ToolCallCard.java:32-37` |
| C16 | 编辑组件 | 核心编辑语义对齐（undo/kill-ring/词导航），但缺 3 项（K26/K27/K31）⇒ 不能判「对齐」 | `EditorComponent.java:27` vs `editor.ts:294` |
| **C17** | **模糊匹配** | **本轮判定变化（对齐 → 存疑）**：pi 是**子序列**匹配 + 按空白/斜杠分词（`fuzzy.ts:12,100`），pi-java 是**子串**匹配 + 无分词（`FuzzyMatcher.java:31 score()`）；且挂载点错位（pi 的 `fuzzyFilter` 服务 autocomplete/session-selector/tree-selector，pi-java 的只服务 `SelectList`，而 pi 的 `select-list.ts:61 setFilter` 用的是纯 `startsWith`） | `FuzzyMatcher.java:31` vs `fuzzy.ts:12,100` + `select-list.ts:61` |
| T3 | 用户自定义主题 | java 支持 `.tcss`，pi 是 JSON + schema 校验 ⇒ 格式/校验能力都不同 | `PiTheme.java:74-90` vs `theme-schema.json:1` |
| T6 | 语法高亮色板 | java 3 色 vs pi 10 token；且 java 的代码块路径是死代码（R9） | `SyntaxHighlighter.java:24-26` vs `dark.json:69-79` |
| W9 | 消息 timestamp 上 wire | **刻意偏差**（`WebWireJson.java:64-66` 明写），与 pi 必填冲突；台账 B50 仍开 | `WebWireJson.java:64-66` vs `ai/src/types.ts:417-421` |
| W10 | `agent_end.messages` 整表替换 | java 在空消息时**不带 `messages` 键**（防前端清空），pi 带 `newMessages` ⇒ 载荷不同 | `AgentEventTranslator.java:117-127` |
| W11 | 宿主并发契约 | 无显式声明（台账 A6） | —— |
| W12 | `transcript()` 读取点清点 | TUI 半已清（不读），web/RPC/SQLite 半未清 | 见台账纠错 A2 行 |

> 注：W11/W12 与台账 A2/A6 同源，属「架构面」。上表 20 行即全部存疑条目。

---

## 缺失清单（全部 80 条）

**屏幕/面板（23）**：S2a 转录搜索 · S2b 文本选择/copy-on-select · S2c flash 提示 · S2d 全屏图像 · S6 模型搜索 · S7 scoped-models 选择器 · S8 thinking 选择器 · S9 theme 选择器 · S11 会话选择器高级功能 · S13 树选择器折叠/标签/过滤/搜索 · S15 设置子菜单 · S16 config 选择器 · S17 OAuth/登录对话框 · S18 首次设置向导 · S19 信任选择器 · S20 排队消息选择器 · S21 show-images 选择器 · S22 扩展 UI 四件套 · S23 扩展 widget/footer · S25 会话统计面 · **S29 `/bug` 故障上报（本轮新增）** · **S30 崩溃记录 + 启动提示（本轮新增）** · **S31 实验性 micro TUI（本轮新增）**

**渲染特性（20）**：R1 Markdown 渲染（死代码）· R2 表格 · R3 删除线 · R4 链接 · R5 流式围栏修剪 · R6 transformer 钩子 · R7 mermaid · R8 LaTeX · R9 代码块高亮（死代码）· R11 逐工具渲染器 · R12 工具卡折叠 · R13 bash 执行组件 · R14 图像协议 · R15 图片粘贴 · R18 分支摘要卡 · R19 skill 调用卡 · R22 公告/彩蛋 · R23 终端标题 · **R24 缓存预热用量行（本轮新增）** · **R25 thinking 丢弃提示（本轮新增）**

**键绑定/交互（21）**：K6 Ctrl+P · K7 Shift+Tab · K8 Ctrl+O · K9 Ctrl+T · K10 Ctrl+G · K11 Alt+Up · K12 Ctrl+Z · K13 Ctrl+X · K14 Ctrl+V · K15 Ctrl+N · K16 app.session.* · K17 keybindings.json · K21 转录搜索 · K22 鼠标选择 · K25 @文件补全 · K26 大粘贴折叠 · K27 提示历史 · K31 jump mode · K34 原生剪贴板 · K35 终端能力探测 · **K37 消息卡鼠标点击展开（本轮新增）**

**TUI 组件（8）**：C3 settings-list · C4 input · C5 loader/spinner · C6 working 指示器 · C7 bordered loader · C8 cancellable loader · C14 mouse-region · C15 dynamic-border

**主题（4）**：T2 主题 token 覆盖 · T4 热重载 · T5 thinking 颜色映射 · T7 导出主题色

**web 对齐面（4）**：W8 queue/compaction/retry 推送 · W14 `--no-session`（**B54**）· W15 `onProgress`（**B55**）· W16 `find()` 范围（**B53**）

---

## pi 侧漂移复核（`71dca871b` → `3390bd936`，111 提交）

**方法**：`git archive 3390bd936 packages/tui packages/coding-agent/src` 导出到临时目录逐条读（⚠️ 测量期间 pi 工作树被别的流程 checkout 回了旧锚点，故不依赖工作树）。逐文件 `git diff --stat` 定范围，再对**每一个**被引用的符号重新取行号。

### A. 复核结果分类（按**单元行**计；6 张表的 139 行全部逐条重取行号）

| 类 | 行数 | 说明 |
|---|---:|---|
| **实质变化**（判定改动） | **1** | C17 模糊匹配：**对齐 → 存疑**（见下。⚠️ **与 pi 的 111 提交无关** —— pi 的 `fuzzyMatch` 语义未变，是本次把两侧算法读透后对**原判**的更正：原判只比了「两边都有个 fuzzy 类」，没比算法） |
| **pi 新增能力**（新单元） | **6** | S29 `/bug` · S30 崩溃记录+提示 · S31 实验性 micro TUI · R24 缓存预热用量行 · R25 thinking 丢弃提示 · K37 消息卡点击展开 |
| **pi 删除 / 改名** | **0** | `git diff --diff-filter=DR 71dca871b..HEAD -- packages/tui packages/coding-agent/src/modes packages/coding-agent/src/core/{keybindings.ts,tools/renderers,export-html} packages/coding-agent/src/cli` **零命中** ⇒ 裁决 R1「pi 已删除 ⇒ 作废」本轮**不适用** |
| **只漂行号**（判定一字不改） | **21** | 见 C 栏（33 处引用行号更新） |
| **行号未动**（原引用原样有效） | **111** | 含全部 `core/keybindings.ts:*`（15 条）、`markdown.ts` 三处（`:9`/`:146`/`:236`）、`tui/keys.ts:705`、`terminal-image.ts:696`、`components/*.ts` 的类尾行号、`layout.ts:449`、`stdin-buffer.ts:444` 等 |
| **复核总行数** | **139** | 1 + 6 + 0 + 21 + 111 = 139 ✓ |

### B. 6 个已知提交的逐条核实（用户点名，全部核实）

| 提交 | 影响的单元 | 核实结论 |
|---|---|---|
| `803f0e906` preserve fullscreen images in WezTerm | **R14** / **S2d** | **判定不变**（pi 有、java 无 ⇒ 缺失）。变化内容：Kitty 图像格「先清行、再绘制」，避免 WezTerm 用 EL 擦掉相交图像格。行号 `tui-alt-screen.ts:336` → **`:338`** |
| `d7951ec36` rank skill autocomplete by bare name (#9120) | **K25** | **判定不变**（缺失）。变化内容：`skill:` 前缀在模糊排序时剥掉，让 `foo` 能排到 `skill:foo` |
| `fa0e1f48a` improve LaTeX compatibility and layouts | **R8** | **判定不变**（缺失）。变化内容：新增 `ScriptNode` 堆叠上下标布局、`\bf`/`\cal`/`\it`/`\rm`/`\sf`/`\sl`/`\tt` 字体切换命令、脚本值归一化。文件 1394 → **1506 行** |
| `bfa686240` handle CJK punctuation in file autocomplete (#9746) | **K25** / **K26** | **判定不变**（缺失）。变化内容：`utils.ts` 新增 `cjkPunctuationRegex`/`autocompleteSeparatorRegex`/`autocompleteBoundaryRegex`；`editor.ts` 的触发判据与 `autocomplete.ts` 的分词都改用它们 |
| `21b8cc1a4` ignore stale tool image conversions (#8743) | **R11** / **R12** | **判定不变**（缺失）。变化内容：`tool-execution.ts` 的 `convertedImages` 缓存加 `sourceData`/`sourceMimeType`，异步 `convertToPng` 回来时比对**当前**结果，不匹配即丢弃（防旧图覆盖新图）。文件 421 → **433 行** |
| `60740991c` cache compiled Node CLI modules / `6dff740fa` OSC 52 clipboard fallback | **T4 无关 / K22 边缘** | `tui-alt-screen.ts:193 copySelection` 的**返回类型**由 `Promise<boolean>` 放宽为 `Promise<boolean \| string>`（可回具体错误文案），失败时 flash 时长 5 s。**判定不变**（K22 仍缺失）。`60740991c` 不落在 UI 面 |

### C. 只漂行号的 21 条（判定不变，仅更新引用）

| 单元 | 旧行号 | 新行号 |
|---|---|---|
| S1 | `interactive-mode.ts:3686` / `:3871` | **`:3776` / `:3986`** |
| S2 | `tui-alt-screen.ts:195` | **`:197`** |
| S2b | `tui-alt-screen.ts:285-305` | **`:296`（`hasActiveSelection`）** |
| S2c | `tui-alt-screen.ts:641` | **`:643`** |
| S4 | `interactive-mode.ts:1642` | **`:1677`** |
| S10 | `session-selector.ts:1031` | **`:694`** |
| S11 | `session-selector.ts:69-116` | **`:76-116`** |
| S12 | `tree-selector.ts:1427` | **`:1329`** |
| S13 | `tree-selector.ts:619-1067` | **`:112` / `:628` / `:633` / `:997`** |
| S14 | `settings-selector.ts:945` | **`:447`** |
| S22 | `interactive-mode.ts:2480-2650` | **`:2558-2740`** |
| S23 | `interactive-mode.ts:2188-2380` | **`:2266-2450`** |
| S24 | `interactive-mode.ts:3027` / `:6359-6427` | **`:3111` / `:6512-6581`** |
| S25 | `footer.ts:94-180` | **`:84-200`** |
| S27 | `session-share.ts:206` | **`:217`（`shareSession:57`）** |
| R6 | `interactive-mode.ts:2024` | **`:2102`** |
| R9 | `interactive-mode.ts:111` | **`:126`（import）/ `:1037`（调用）** |
| R12 | `tool-execution.ts:145-175` | **`:166-170`** |
| R16 | `interactive-mode.ts:3579` | **`:3667`** |
| R17/R18/R19 | `:59` / `:58` / `:55` | **`:10` / `:10` / `:11`**（类声明行） |
| R23 | `interactive-mode.ts:1020` | **`:1047`** |
| C6/C10 | `:2136-2170` / `:2095-2134` | **`:2214-2250` / `:2180-2213`** |
| C15 | `interactive-mode.ts:4166` | **`:4284`** |
| C16 | `editor.ts:284` | **`:294`** |
| K19 | `tui-alt-screen.ts:467` | **`:469`** |
| K24 | `autocomplete.ts:278` | **`:283`** |
| K25 | `autocomplete.ts:390-477,702` | **`:421-477`, `:711`** |
| K27 | `editor.ts:332-335,412-477` | **`:343-344`, `:425`** |
| K28 | `editor.ts:355` | **`:365`** |
| C17 | `fuzzy.ts:12,99` | **`:12`, `:100`** |
| T9 | `interactive-mode.ts:577` | **`:604`** |
| W1 | `interactive-mode.ts:3165` | **`:3249`（`:3243 subscribeToAgent`）** |

> 上表按**引用行**计 33 处；按**单元**计 **21 个**（R17/R18/R19 合并计 1 行；C6/C10 合并计 1 行）。

### D. pi 侧键位数量：**未变**

| 侧 | 旧 | 新 |
|---|---:|---:|
| `packages/coding-agent/src/core/keybindings.ts` | 43 | **43** |
| `packages/tui/src/keybindings.ts` | 47 | **47** |

唯一改动是 `app.message.copy` 的**描述文字**：`"Copy message to clipboard"` → **`"Copy selection or last assistant message"`**（`core/keybindings.ts:131`）—— 反映 K22 的 copy-on-select 已接入该键，**键位本身与默认值 `ctrl+x` 不变** ⇒ K13 判定不变（缺失）。

### E. 需要别的地图/台账跟进的连带项（不在本图范围，仅登记）

- `docs/32` 的 **B55** 措辞要更新：pi 的 `onProgress` 现在带 `partialSessions` + `AbortSignal`（不只是「有进度回调」）。
- pi 新增包 **`durable`**（757 行，`080160162 feat(durable): move Pico into dedicated package`）—— 非 UI 面，本图不覆盖。
- pi 新增 `experimental/micro/`（本图已作 **S31** 纳入）与 `utils/zip.ts`、`utils/wsl.ts`。
- `docs/40` 的规模表需按本轮新 LOC 更新（本图「规模」节已更新）。

---

## 加权汇总

**权重规则**（已定，未自创）：权重 = **用户可观察影响 × 频率**，只看「如果用户用这个模块，这个单元有多重要」，**不掺排期优先级**。
`3` = 每轮对话都走／默认路径；`2` = 每次会话走／常用命令；`1` = 低频／边缘／纯内部；`0` = 非目标（排除出分母）。
**完成系数**：对齐 = 1.0 ／ 存疑 = 0.5 ／ 缺失 = 0。
⚠️ 「TUI 优先级最低」是**排期**裁决，不是权重 —— `R1 Markdown 渲染` 按判据仍是 **3**（TUI 的每条消息都走）。排期见 `docs/40`。

| 域 | Σ权重 | Σ(w×系数) | 加权完成度 | 未加权完成度 |
|---|---:|---:|---:|---:|
| 屏幕/面板（S1–S31 + S2a–d，35 单元） | 47 | 17.0 | **36.2%** | 22.9% |
| 渲染特性（R1–R25，24 单元；R21 权重 0 已剔） | 36 | 5.5 | **15.3%** | 8.3% |
| 键绑定/交互（K1–K37，36 单元；K36 权重 0 已剔） | 64 | 30.0 | **46.9%** | 30.6% |
| TUI 组件（C1–C19，19 单元） | 40 | 22.5 | **56.2%** | 36.8% |
| 主题/样式（T1–T9，9 单元） | 15 | 6.5 | **43.3%** | 33.3% |
| web 对齐面（W1–W16，16 单元） | 33 | 26.0 | **78.8%** | 50.0% |

**模块合计**：Σ权重 **235** ／ Σ(w×c) **107.5** ／ **加权完成度 = 107.5/235 = 45.7%**（未加权 **28.1%**，**+17.6pp**）

> 旧锚点（133 单元）的同口径值：Σ权重 229 ／ Σ(w×c) 108.5 ／ **47.4%**（未加权 30.1%）。
> **本轮 −1.6pp 的构成**（47.38% → 45.74%）：① pi 新增 6 个缺失单元（权重全 1）⇒ **−1.2pp**（47.38% → 46.17%，纯分母增大）；② C17 对齐→存疑（权重 2）⇒ **−0.4pp**（46.17% → 45.74%）。

**权重分布（按档）**

| 权重 | 单元数 | Σ权重 | Σ(w×c) | 加权完成度 |
|---|---:|---:|---:|---:|
| 3（每轮／默认路径） | 26 | 78 | 52.5 | **67.3%** |
| 2（每次会话／常用命令） | 44 | 88 | 47.0 | **53.4%** |
| 1（低频／边缘／纯内部） | 69 | 69 | 8.0 | **11.6%** |
| 0（非对齐项，已剔） | 2 | 0 | 0 | — |
| **合计** | **139** | **235** | **107.5** | **45.7%** |

**权重 3 的单元清单（26 条 —— 最该先修；本轮 111 提交未增未减）**

| # | 单元 | 判定 |
|---|---|---|
| S1 | 主聊天屏（transcript 视口 + 输入行 + 状态行） | **对齐** |
| R1 | Markdown 渲染 | **缺失**（死代码） |
| R10 | Diff 渲染 | **存疑** |
| R11 | 逐工具渲染器 | **缺失** |
| K1 | `Esc` 中断 | **对齐** |
| K19 | 滚轮/触控板归一化 | **存疑** |
| K23 | 滚动条（绘制+拖拽+悬停加宽） | **对齐** |
| K27 | 提示历史 ↑/↓ 导航 | **缺失** |
| K28 | undo（fish 式合并） | **对齐** |
| K32 | Kitty 键盘协议解析 | **存疑** |
| C1 | 滚动视图 | **对齐** |
| C5 | loader / spinner | **缺失** |
| C6 | working 指示器（spinner + 计时） | **缺失** |
| C12 | stack / spacer / text 布局基元 | **对齐** |
| C16 | 编辑组件 | **存疑** |
| C18 | 布局引擎 | **对齐** |
| C19 | stdin 缓冲 | **对齐** |
| T2 | 主题 token 覆盖（~70 语义色） | **缺失** |
| W1 | 流式事件词汇（`agent_start`/`message_update`） | **对齐** |
| W2 | `tool_execution_*` 真实源 | **对齐** |
| W3 | `turn_end` 带 `{message, toolResults}` | **对齐** |
| W4 | `message_update` 带 `usage` | **对齐** |
| W5 | `toolcall_start` 带 `id`+`toolName` | **对齐** |
| W6 | 消息 wire 形状 | **对齐** |
| W7 | 终局 `usage` 非空 | **对齐** |
| W10 | `agent_end.messages` 整表替换 | **存疑** |

⇒ 权重 3 里 **15 对齐 / 6 缺失 / 5 存疑**（**67.3%**）。缺失的 6 条全部是 **TUI 的「每轮都看得见」的能力**：`R1` Markdown、`R11` 逐工具渲染器、`K27` 提示历史、`C5` spinner、`C6` working 指示器、`T2` 主题 token。**这 6 条是加权口径下的最高收益点**；本轮 pi 新增的 6 个单元**权重全为 1**，不进这个子集。

**未加权 vs 加权的差（+17.6pp）差在哪**

成因一句话：**对齐的单元平均更重**。

| 判定 | 单元数 | Σ权重 | 平均权重 |
|---|---:|---:|---:|
| 对齐 | 39 | 88 | **2.26** |
| 存疑 | 20 | 39 | 1.95 |
| 缺失 | 80 | 108 | **1.35** |

缺失单元里 **69/80 条是权重 1**（低频选择器、扩展 UI、彩蛋、原生 helper、内部契约形状、本轮 pi 新增的 6 条…），它们把**未加权**完成度压到 28.1%，但在**加权**口径下只占 69/235 = 29.4% 的权重。反过来，**权重 3 的 26 条里 15 条已对齐**（web 事件词汇 7 条 + TUI 核心管线 8 条）—— 这正是 pi-java 的真实形态：**核心管线对齐得不错，外围能力大面积空缺**。未加权口径把这两件事混在一起，所以低报。

**两项重点发现各自的加权拖累（按新 HEAD 重算）**

| 发现 | 涉及单元 | Σ权重 | 若修好 | 模块加权完成度 | 模块增量 | 该面增量 |
|---|---|---:|---:|---:|---:|---:|
| **Markdown 渲染是死码** | R1（w3） | 3 | 110.5/235 | **47.0%** | **+1.3pp** | R 面 15.3% → **23.6%**（+8.3pp） |
| **Markdown 死码（含代码块高亮）** | R1 + R9（w3+2） | 5 | 112.5/235 | **47.9%** | **+2.1pp** | R 面 15.3% → **29.2%**（+13.9pp） |
| **6 个键位未接线** | K6/K7/K8/K9（w2×4）+ K10/K11（w1×2） | 10 | 117.5/235 | **50.0%** | **+4.3pp** | K 面 46.9% → **62.5%**（+15.6pp） |
| 两项合计 | R1+R9+K6–K11 | 15 | 122.5/235 | **52.1%** | **+6.4pp** | — |

口径提醒：**这两项加起来只值 +6.4pp**（因为 R 面总权重仅 36、K 面 64），但它们**在权重 3 子集里的分量远大于此** —— `R1` 是权重 3 的**单条最大缺失**（修好它把权重 3 子集从 67.3% 拉到 **71.2%**）。⇒ **加权完成度这个总量指标对「单条高频缺失」不敏感，看权重 3 子集才看得出。**
