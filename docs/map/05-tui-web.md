# 05 用户界面层（pi-java-tui / pi-java-web）

> 【背景摘要】读数：加权 **241.0/252＝95.6%**（未加权 **142/153＝92.8%**）；已对齐项不再逐条列出。
> 范围：用户界面层（pi-java-tui / pi-java-web）的屏幕、渲染、键绑定、TUI 组件、主题、web 面；已对齐证据见 git 历史。
> 差异结论：仅保留差异/窄差/出分母/web 面差异；剩余部分对齐（形状差）按权重为 S2(2)、T6(2)、S28(1)、T11(1)，开放缺口以 [`../04-open-items-register.md`](../04-open-items-register.md) 为准。
> 开放缺口收口（2026-11-26）：B38／B39／B46 已对齐；E5-31 余 7 个无 TUI 面的成员登记窄差 D323（权重 0）。


> 只保留差异：判定为「对齐」的单元不逐条列，逐条证据见 git 历史；窄差 D 项见 `docs/04` §6.5。
## 基准与口径

- 判定基准：pi `a00a5048d`（P12 发布锚点）。
- 判定单位＝**可被外部观察到的行为承诺**（一个屏幕 / 一个键绑定 / 一个渲染特性 / 一个主题能力 / 一个 TUI 组件 / 一个 web 能力）。
- 三档：**对齐**＝pi 有、pi-java 有、行为相同；**缺失**＝pi 有、pi-java 无；**部分对齐（形状差）**＝pi-java 有但行为/形状与 pi 不同（已核对确认差异存在；本档不再承担「未核」含义）。
- 所有证据均为 `file:line`。pi 侧路径前缀 `D:\workplaceForai\pi\packages\`，java 侧 `D:\workplaceForai\pi-java\`。

---
## 规模

| 模块 | pi 包 | pi LOC | java LOC | 比例 |
|---|---|---|---|---|
| TUI 框架层 | `packages/tui/src`（**46** 文件） | **19505** | —（对应物是**外部库 TamboUI**） | — |
| TUI 应用层 | `coding-agent/src/modes/interactive`（**62** TS 文件） | **21895** | — | — |
| 工具渲染器 | `coding-agent/src/core/tools/renderers`（8 文件） | **1053**（逻辑另在 `core/tools/render-utils.ts` 112 行） | **390**（`coding-agent/.../core/render/`：`BuiltInRenderers` 274＋`DisplayPath` 57＋`RenderText` 59） | **37.0%**（390/1053；文本级方言，判别见 R11） |
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

屏幕/面板：已对齐且无差异 19 条（逐条证据见 git 历史）；差异 16 条见下。

| # | 屏幕 | pi 有 | java 有 | 判定 | 权重 | 证据（pi 侧均对 `a00a5048d` 取数） |
|---|---|---|---|---|---|---|
| S2 | 全屏 alt-screen 模式（**默认模式**） | ✓ `tui/src/tui-alt-screen.ts:202 class TuiAltScreen`（1787 行） | ✓ `PiTuiLauncher.java:61 runFullscreen`（TamboUI ToolkitRunner） | **部分对齐（形状差）** | 2 | 已修：搜索条动态键位提示（`TranscriptSearchBar.hintText` 走 `KeybindingsManager.strokeFor`）、flash 栈式多条（`FlashNotice.messagesAt`）、SGR 5 blink（`ThemeColorMath.TextAttr.BLINK`）、双击选词/三击行选/拖选边缘自动滚动（`WordSegments.java:42` 分段与 joiner 合并、`TranscriptSelection` 点击计数/粒度/反向扩展、`ViewportInteraction` 50ms 自动滚动、X10 按钮无关 MOVE 释放与点击优先卡片切换）。残余：终端图像协议仍为 Kitty/iTerm2 子集（无 Kitty placement 元数据/全量清理、滚动裁剪 `cropKittyImageLine` 与离屏图片缓存上限，D299–D301 中②）；CJK 选词按单字分段（上游 `Intl.Segmenter` 词典不可复现）。默认模式两侧一致 |
| S28 | `/export`（HTML 导出） | ✓ `core/export-html/index.ts:236 exportSessionToHtml`＋`template.js:1716-1795`（marked.use breaks/gfm、hljs、scheme 白名单 `:692-701`） | ✓ `export/HtmlExporter.java:21`＋`MarkdownToHtml.java`（333 行） | **部分对齐（形状差）** | 1 | 已修：GFM 表格（`emitTable`/`isTableDelimiter`）、URL scheme 白名单（`sanitizeHref` 放行 http/https/mailto/tel/ftp＋相对路径、拦 javascript/data）、代码块固定 `class="hljs"`（去 `language-*`）。残余：pi 的 hljs 实际高亮 span 未产出，java 仍只转义代码块文本（TUI 侧 `SyntaxHighlighter` 九类着色属另一消费点）（D-W8-3） |
| S32 | 登录 URL 复制＋Radius 登录入口 | ✓ `components/auth-url.ts:7-34`（`copy()` 复制 URL＋`Copied URL to clipboard` 提示）＋Radius 登录/MCP | ✓ `LoginFlow`＋`LoginDialogScreen`（`authUrl`/`authUrlStatus`）＋Radius OAuth/MCP | **对齐** | 1 | 2026-11-26 修复：`notify(AuthUrl)` 以 `WebUrl.displayUrl` 保存可复制 URL，Ctrl+X 复制并渲染 `Copied URL to clipboard`／`Copy URL manually`；设备码路径按 pi 清空 URL 不提供复制。`LoginDialogDeviceCodeTest` 3 条新测试全过 |

> ~~S31 实验性 micro TUI~~ —— **作废**（pi `7fd478a2e` 删 `experimental/{micro,mini}`，裁决 R1：参照物被删即移出分母）。

---

## 渲染特性对齐

渲染特性：已对齐且无差异 10 条（逐条证据见 git 历史）；差异 23 条见下。

| # | 特性 | pi 有 | java 有 | 判定 | 权重 | 证据（pi 侧均对 `a00a5048d` 取数） |
|---|---|---|---|---|---|---|
| R7 | Mermaid 图渲染 | ✓ `components/mermaid.ts:60 createMermaidMarkdownTransformer` + `lovely-mermaid` 依赖 | ✗ | **缺失** | 1 | 不可达 D-W7-1：pi 依赖 `lovely-mermaid` JS 包（布局/主题/警告），Java 无等价库；围栏按代码块渲染（不进分子） |
| R12 | 工具执行卡：可折叠＋流式部分结果 | ✓ `tool-execution.ts` 的展开态与折叠提示 | ✓ `ChatMessage.ToolRunning`＋`SessionEventRouter` liveTools 原地 replaceAt＋`MessageBubble` 流中 `Elapsed X.Ys`／终态 `Took X.Ys`（`ToolDurations`）；折叠预览行＋省略行＋三态 bg 同前 | **对齐** | 2 | 2026-11-26 修复：`ToolRunning` 增 `startNanos`，update 事件在保留时刻下原地换部分结果卡，End 收口为终态卡无重复；`ToolExecutionStreamingTest` 7 条全过。窄差：Java 逻辑行模型无 pi 的 1Hz `invalidate` 重绘（计时仅事件到达时刷新），文本级 bg 只铺文本格 |
| R13 | bash 执行组件（实时增量输出 + 宽度自适应） | ✓ `components/bash-execution.ts:21`（**223 行**；`!!` 命令 dim 边框、`outputPad` 可配）+ `test/bash-execution-width.test.ts` | ✓ `ChatMessage.BashCard`＋`ChatMessage.BashRunning`＋`MessageCards.bash` | **对齐** | 2 | 2026-11-26 修复：`SessionMessagingLifecycle.executeBash` 传 `ShellOptions.outputSink`，每 chunk 发 `BashExecutionUpdate`（删除终局一次性事件）；TUI `SessionEventRouter.handleBashUpdate` 原地累积成 `BashRunning` 卡，`finalizeBash` 收终态；生产 `onEntry`（id 随机 UUID）与 `EntryAppended` 两条路径均经 `finalizedBashIds`/`liveCompletedToolCalls` 去重。`SessionLifecycleEventsTest`＋`ChatScreenSessionEventTest` 全过 |
| R21 | 回合分隔条（`Worked for Ns • Local tools: N calls`） | ✗（pi 无此构造） | ✓ `ChatScreen.java:170-181` | **非对齐项**（java 独有，Codex 风格） | 0 | 见「pi-java 独有」，**排除出分母** |
| R22 | 启动公告组件（`earendil-announcement`、`armin`） | ✓ `components/earendil-announcement.ts:27`、`armin.ts:62 class ArminComponent`（384 行） | ✗ | **缺失** | 1 | 旧的 `daxnuts.ts` 已删，彩蛋迁到 R27；**W8 复核维持缺失**（D-W8-2：无 Java 消费面，不移植） |
| R27 | **3D 彩蛋（pi logo / Armin）+ header logo**（全屏 overlay，惰性加载） | ✓ `components/easter-egg-3d.ts`（**1292 行**）+ `easter-egg-3d.lazy.ts` + `pi-logo.ts:15 piLogoLines`（40 行） | ✗ | **缺失** | 1 | java 无任何彩蛋/logo 动画；取代旧的 daxnuts（R22）；**W8 复核维持缺失**（D-W8-2：1292 行 3D 渲染器无 Java 等价/消费面） |
| R28 | **排队消息区（streaming 时聊天区与编辑器之间的 dim `Steering:`/`Follow-up:` 行）** | ✓ `interactive-mode.ts:461 pendingMessagesContainer`、`:4724 updatePendingMessagesDisplay`；流式期间 bash/工具组件也挂该区 | ✓ `PendingQueueView.runningCards`＋`ChatScreen` | **对齐** | 1 | 2026-11-26 修复：`PendingQueueView` 增 `List<Element> runningCards` 槽与 add/remove/clear API，`SessionEventRouter.handleBashUpdate` 流式期间挂卡、`finalizeBash` 终局移出；dequeue hint 与取回编辑同前。`ChatScreenSessionEventTest` 排队区交叉驱动断言通过 |
| ~~R29~~ | ~~安装变更警告（会话运行中 pi 被升级/替换 → 错误时提示重启命令）~~ | ✓ `interactive-mode.ts:2193 maybeShowInstallChangeWarning`＋`config.ts detectInstallChange` | ✗ | **出分母（不做）** | — | java 无 package.json 安装形态且无自更新通道（E6-10 终裁联动）⇒ 检测前提不成立；`maybeSuggestBugReport` 的恒 false 门即终局；docs/04 §4 C 类登记，触发条件＝分发通道落地 |
| R30 | 全屏滚动帧 O(viewport)（内容未变时复用 `LayoutFrame.renderCache`） | ✓ `tui.ts:613,1163`；`layout.ts:398 renderLayoutFrame(reusableOutput)`、`:84 renderCached` | ✗（TamboUI 布局内部不可达） | **缺失（窄差 D）** | 2 | Java 侧视口每帧 `TextLayout.wrap` 全量派生；TamboUI 为外部库，布局帧缓存无应用层等价接缝 ⇒ 登记窄差 D，不进分子 |
| R33 | ASCII/字素快路径＋`Text.setText` 相同文本保留换行 | ✓ `utils.ts:177-241 isStandaloneGraphemeUnit/graphemesOf`、`:251-262` 宽度缓存；`components/text.ts:25-32` | 部分：`TextLayout` 按码点处理（功能等价） | **缺失（窄差 D）** | 1 | Java 无 `Intl.Segmenter` 等价性能诉求、无 pi `Text` 组件；纯性能契约，R31 的源级解析缓存已承担「同源不重渲染」收益 ⇒ 登记窄差 D，不进分子 |

---

## 能力单元清单（其余）

### 键绑定 / 交互

键绑定/交互：已对齐且无差异 33 条（逐条证据见 git 历史）；差异 7 条见下。

| # | 能力单元 | 类别 | pi 侧证据 | pi-java 侧证据 | 判定 | 权重 | 台账条目 |
|---|---|---|---|---|---|---|---|
| K5 | `Alt+Enter` 排队 follow-up | 键绑定 | `keybindings.ts:67-70`：**Windows 上是 `ctrl+q`**，`alt+enter` 归 `tui.input.newLine` | ✓ `KeybindingsManager.installDefaults` 按 `windowsKeybindings` 分流；`TamboUIAdapter.isNewlineEnter` 只认 Shift/LF；`GlobalKeyRouter` followUp 臂（压缩中缓冲，B175） | **对齐** | 2 | 2026-11-26 修复：`FOLLOW_UP` 默认键 Windows/WSL ⇒ Ctrl+Q、其余 Alt+Enter；`isNewlineEnter` 不再吞 Alt+Enter，followUp 不被提前拦截。`KeybindingsManagerTest`／`TamboUIAdapterTest` 全过。窄差：设置页 followUp 提示仍硬编码 `Alt+Enter`（需跨文件接线，未做） |
| K12 | `Ctrl+Z` 挂起进程 | 键绑定 | `keybindings.ts:29-32 app.suspend`（非 Windows） | ✗ | **缺失** | 1 | — |
| K19 | 滚轮/触控板归一化（事件流 → 行滚动） | 交互 | `tui/src/wheel-scroll.ts`（`BURST_GAP_MS=5`／`GESTURE_GAP_MS=200`／`REFERENCE_GAP_MS=100`／`MAX_AUTO_LINES=6`）；`tui/src/tui-alt-screen.ts:491 scrollBy`、`:719-724`（SGR Alt 位 ×5） | ✓ `WheelScrollAccelerator.java:13`（pi 模型移植：速度加速 ≤6 行、`defaultAccelerate()` 的 macOS/SSH 分支、数字臂固定行数、分数进位）；`GlobalKeyRouter.java:94`（速度模型＋Alt 位 ×5＋invert）；`PiTuiLauncher.java:86`（`fullscreenWheelScrollLines` 数字/auto 分流）。legacy `ScrollInputNormalizer` 仅保留给显式旧配置与既有测试 | **对齐** | 3 | 2026-11-28 修复：按锚点 `a00a5048d` 移植 pi 滚轮模型；`WheelScrollAcceleratorTest`（7 用例，含上游 6 个场景与 fixed(0) clamp）与 `PiTuiAppScrollTest`（6 用例，含 Alt 位解析×5）全过 |
| K36 | 模式 2027 握手规避（ConPTY 字节泄漏修复） | 平台 | —（pi 无此构造） | `NoMode2027Backend.java:20` + `NoMode2027JLineBackend.java:1` | **非对齐项**（java 独有） | 0 | **排除出分母** |

### TUI 组件

TUI 组件：已对齐且无差异 14 条（逐条证据见 git 历史）；差异 7 条见下。

| # | 能力单元 | 类别 | pi 侧证据 | pi-java 侧证据 | 判定 | 权重 | 台账条目 |
|---|---|---|---|---|---|---|---|
| C14 | mouse-region（把鼠标事件限定到区域） | 组件 | `tui/components/mouse-region.ts:33`（三张消息卡用它，见 K37） | ✓ 元素级等价 | **对齐（窄差 N）** | 1 | `MouseRegion` 组件不移植；`ChatViewportElement` 的 `messageRowRanges` 行区间分发承担同可观察行为（区域＝卡类消息的整行块，点击切换 per-card `cardExpanded` 态，null 回落全局展开态） |
| C16 | 编辑组件（多行 + 折叠 + 历史） | 组件 | `tui/components/editor.ts:297 class Editor`（2473 行）+ `editor-component.ts:74` + `custom-editor.ts:148` | `EditorComponent.java`（已拆出 `EditorPaste`/`EditorJump`/`EditorHistory`/`KillOps`/`WordNav`）＋`EditorPaste.java`＋`EditorJump.java`＋`EditorHistory.java` | **对齐** | 3 | 2026-11-26 锚点复核翻正：K26 大粘贴折叠（`EditorPaste.java:21-26` marker 正则、`:76-87` >10 行/>1000 字符阈值、`:124-133` expand、`:150-189` 删后重编号）、K27 提示历史（`EditorHistory.java:19` MAX=100、`:64-90` 浏览）、K31 jump mode（`EditorJump.java:63-69` `ctrl+]`/`ctrl+alt+]`、`:71-101` 多行大小写敏感跳字符）均有实现＋`EditorComponentPasteTest`/`EditorComponentJumpTest`/`EditorHistoryTest` 逐行为测试；pi 侧对位 `editor.ts:31-35/:1314-1332/:1106-1120`、`:347-350/:461-472`、`:359-360/:704-722/:2151-2178`。旧注「仍缺 K26/K31」已失效 |
| C21 | render 只读/缓存契约（组件 `render` 返回只读 `string[]`；Container 在子节点/行未变时复用缓存数组） | 框架 | `tui.ts:130,369-393,470-472`、`box.ts:100,112` | TamboUI 外部组件协议（不可改） | **缺失（窄差 D）** | 1 | TamboUI 为外部库，组件 render 协议与 Container 缓存无应用层接缝；应用层以 R31 投影缓存承担等价意图 ⇒ 登记窄差 D，不进分子 |

### 主题 / 样式

主题/样式：已对齐且无差异 8 条（逐条证据见 git 历史）；差异 3 条见下。

| # | 能力单元 | 类别 | pi 侧证据 | pi-java 侧证据 | 判定 | 权重 | 台账条目 |
|---|---|---|---|---|---|---|---|
| T2 | 主题 token 覆盖（56 个语义色） | 主题 | `theme-tokens.ts:24-101` 的 56 键＋`dark.json:15-72` 的 56 色 | `ThemeToken.java:10-122` 的 56 enum＋`fromPiName:165-226`／`piName:228-288` 双向映射 | **对齐** | 3 | 2026-11-26 锚点复核翻正：pi 与 java 的 token 名集合、顺序、槽位、5 个 fallback 全量逐键相等（脚本比对 pi-only=[]／java-only=[]／顺序一致）；旧「约 70 个」与 java 注释「57 个」均为误记，实际两侧各 56。消费层裁剪（`SemanticColors` 现读 10 键）属另立窄差，不折入 token 表覆盖 |
| T6 | 语法高亮色板（10 token） | 主题 | `dark.json:55-63` 的 9 个 syntax token＋`theme.ts:892-918` 的 hljs 20+ 类映射 | `MarkdownTheme.java` 10 md* 键一致；`SyntaxHighlighter.java`（187 行）九类 accessor＋type/function/operator/punctuation 词法分支 | **部分对齐（形状差）** | 2 | 2026-11-26 修复：五个新 accessor 现读主题令牌，词法器识别类型（缺口已补）、函数名（后随 `(`）、操作符与标点；`SyntaxHighlighterTest` 7 条全过。残余：保守配色下未识别标识符保持 plain（不染 variable 色），无 hljs 20+ 类按语言 grammar 映射 |
| T11 | system 主题（由终端调色板派生，多层 fallback） | 主题 | `tui/src/terminal-colors.ts:11-15,38-49` OSC 10/11/4 解析＋`theme-controller.ts:20-39` 查询/超时/回落 | `SystemTheme`＋`SystemThemeRecipe` 算法层逐项对位；`TerminalColors.parseOscReply`＋`ThemeController` 注入缝 | **部分对齐（形状差）** | 1 | 2026-11-26 修复半面：`parseOscReply` 解析 OSC 10/11/4 回复（BEL/ST、hex6/12、rgb/rgba），`ThemeController.setTerminalColorsSource`/`queryTerminalColors` 提供注入缝。残余：TamboUI 0.4 后端无 OSC 查询发送与 stdin reply 队列，仍走 COLORFGBG／tier-3（D123） |

### web 面（对齐相关部分）

web 对齐面：已对齐且无差异 13 条（逐条证据见 git 历史）；差异 3 条见下。

> pi 仓库内**没有** web UI（`grep -rln "WebSocket" packages/*/src` 只命中 AI provider 的 HTTP/WS 客户端与 settings-selector，`find . -name "*web-ui*"` 零命中）。因此 web 的**呈现层**单列在「pi-java 独有」；下面只列**事件/线格式**这一面对齐相关的能力单元，其 pi 侧证据取自 pi 的 agent-loop 事件词汇与 pi-webui 基线协议。

| # | 能力单元 | 类别 | pi 侧证据 | pi-java 侧证据 | 判定 | 权重 | 台账条目 |
|---|---|---|---|---|---|---|---|
| W3 | `turn_end` 带 `{message, toolResults}` | web 协议 | `agent/types.ts:438` | `AgentEventTranslator.java:149-160` | **对齐**（残余：错误路 `message` 为 null ⇒ 省键） | 3 | B42/B49（已结案，残余登记） |
| W16 | `find()` 的范围（`--session <id>` 跨项目查找） | 行为 | pi `main.ts:284-291 findLocalSessionByExactId`（①项目内）＋`:293-324 resolveSessionPath`（③ `listAll` 跨项目，`{type:"global"}`）＋`:441-445` 的 fork 确认 | `findById` header-only 项目内＋`findWithScope`（`PersistentSessionRepositories.java:82-134`）同构阶梯③＋`SessionPersistence.java:301-329` 的 `Session found in different project`／fork 确认 | **对齐** | 1 | 2026-11-26 锚点复核翻正：旧行只引 `findLocalSessionByExactId` 项目内臂，漏引 pi 同文件 `:313-320` 的跨项目③与消费端 fork 提示；java 阶梯③与 pi 同形（精确优先、global 标、确认后 fork），非 java 独有形状差。原挂的 **D271** 实为 `docs/08` 的 codemode 渲染器条目（`:577`），与本行无关，撤销引用。残余仅提示着色（pi yellow vs java 无 B213 族），属窄差不扣分 |

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
| 滚轮/触控板归一化（Codex TUI2 PR#8357 移植） | `ScrollInputNormalizer.java:20`（274 行）—— legacy 回退路径；默认生产路径已于 2026-11-28 切至 pi 模型 `WheelScrollAccelerator`（K19） |
| 回合分隔条 `Worked for Ns • Local tools: N calls` | `ChatScreen.java:170-181`（Codex CLI 风格） |
| 元数据气泡 emoji 图标集（⚙/🧠/🗜/⎇/◆） | `MetaKind.java:15-29` |
| Codex 风格启动方框卡 | `WelcomeOverlay.java:41 buildCard` |
| 原生滚动回退内联 shell（`InlineTuiShell`） | `InlineTuiShell.java:1`（390 行） |

---

## 加权汇总

**权重规则**（已定，未自创）：权重 = **用户可观察影响 × 频率**，只看「如果用户用这个模块，这个单元有多重要」，**不掺排期优先级**。
`3` = 每轮对话都走／默认路径；`2` = 每次会话走／常用命令；`1` = 低频／边缘／纯内部；`0` = 非目标（排除出分母）。
**完成系数**：对齐 = 1.0 ／ 部分对齐（形状差） = 0.5 ／ 缺失 = 0。

**L7 读数**

| 域 | Σ权重 | Σ(w×系数) | 加权完成度 | 未加权完成度 |
|---|---:|---:|---:|---:|
| 屏幕/面板（35 单元） | 47 | 45.5 | **96.8%** | 94.3%（33/35） |
| 渲染特性（R1–R33，31 计分单元；R21 权重 0 已剔；R29 出分母） | 45 | 39.0 | **86.7%** | 83.9%（26/31） |
| 键绑定/交互（K1–K41，39 计分单元；K36 权重 0 已剔） | 67 | 66.0 | **98.5%** | 97.4%（38/39） |
| TUI 组件（C1–C21，21 单元） | 42 | 41.0 | **97.6%** | 95.2%（20/21） |
| 主题/样式（T1–T11，11 单元） | 18 | 16.5 | **91.7%** | 81.8%（9/11） |
| web 对齐面（W1–W16，16 单元） | 33 | 33.0 | **100%** | 100%（16/16） |
| **合计** | **252** | **241.0** | **95.6%** | **92.8%（142/153）** |

**权重 3 子集（26 条）**：Σ权重 **78** ／ Σ(w×c) **78.0** ／ **100%**（对齐 26 条＝78.0）。

> 2026-11-26 逐行复算：C16／T2／W16 部分对齐（形状差）→对齐（＋3.5）；同日 §3.1 修复批次 K5／S32／R12／R13／R28 翻正（＋4.0），六域之和＝合计＝239.5。旧发布读数 232.5/252 与逐行不自洽——逐行 232.0、逐域行合计 231.5，已按每行权重×系数重算。未加权旧值「渲染 22/31」同属误记。
>
> 2026-11-28：K19 按 pi 模型移植（速度加速 ≤6、macOS/SSH 平台分支、Alt×5、数字臂固定），部分对齐（形状差）→对齐（＋1.5）；六域之和＝合计＝241.0。

**读数口径**：未加权百分比的分母是计分单元数（153）；S31 作废、R21/R29/K36 出分母。

**权重 3 的差异项与剩余差距**：无（26 条权重 3 单元全部对齐）；K19（滚轮/触控板归一化）于 2026-11-28 收口，C16／T2 于 2026-11-26 锚点复核收口；逐条证据见 git 历史。
