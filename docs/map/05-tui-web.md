# 05 用户界面层（pi-java-tui / pi-java-web）

> 【背景摘要】读数：加权 **232.5/252＝92.3%**（未加权 **133/153＝86.9%**）；已对齐项不再逐条列出。
> 范围：用户界面层（pi-java-tui / pi-java-web）的屏幕、渲染、键绑定、TUI 组件、主题、web 面；已对齐证据见 git 历史。
> 差异结论：仅保留差异/窄差/出分母/web 面差异；剩余缺口为 K19、C16、T2，开放缺口以 [`../04-open-items-register.md`](../04-open-items-register.md) 为准。


> 只保留差异：判定为「对齐」的单元不逐条列，逐条证据见 git 历史；窄差 D 项见 `docs/04` §6.5。
## 基准与口径

- 判定基准：pi `a00a5048d`（P12 发布锚点）。
- 判定单位＝**可被外部观察到的行为承诺**（一个屏幕 / 一个键绑定 / 一个渲染特性 / 一个主题能力 / 一个 TUI 组件 / 一个 web 能力）。
- 三档：**对齐**＝pi 有、pi-java 有、行为相同；**缺失**＝pi 有、pi-java 无；**存疑**＝pi-java 有但行为/形状与 pi 不同或未核。
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
| S2 | 全屏 alt-screen 模式（**默认模式**） | ✓ `tui/src/tui-alt-screen.ts:202 class TuiAltScreen`（1787 行） | ✓ `PiTuiLauncher.java:61 runFullscreen`（TamboUI ToolkitRunner） | **存疑** | 2 | java 用 TamboUI 通用 runner，pi 的 alt-screen 自带搜索/选择/复制/图像/闪烁（见 S2a-d、K20/K21；OSC 7501 状态见 K39）；滚动条由 java 自绘（`ChatViewportElement.java:261 renderScrollbar`）。默认模式两侧一致（pi `cli/args.ts:340` "fullscreen (default)"；java `PiTuiLauncher.java:111` 默认 fullscreen） |
| S28 | `/export`（HTML 导出） | ✓ `core/export-html/index.ts:236 exportSessionToHtml` | ✓ `export/HtmlExporter.java:21`（288 行） | **存疑** | 1 | pi 用 vendored marked.min.js + highlight.min.js + `template.html/css/js`；java 自写 `MarkdownToHtml.java`（201 行）—— 输出形状未逐项核；**W8 复核维持**（D-W8-3：vendored marked/highlight 与自写转换的输出形状未逐项核） |
| S32 | 登录 URL 复制＋Radius 登录入口 | ✓ `components/auth-url.ts`＋Radius 登录/MCP 设置 | `LoginFlow`＋`LoginDialogScreen`＋Radius OAuth/MCP 提议已落地；独立 URL 复制键未证实 | **存疑** | 1 | 旧“仅 readPassword”已失效 |

> ~~S31 实验性 micro TUI~~ —— **作废**（pi `7fd478a2e` 删 `experimental/{micro,mini}`，裁决 R1：参照物被删即移出分母）。

---

## 渲染特性对齐

渲染特性：已对齐且无差异 10 条（逐条证据见 git 历史）；差异 23 条见下。

| # | 特性 | pi 有 | java 有 | 判定 | 权重 | 证据（pi 侧均对 `a00a5048d` 取数） |
|---|---|---|---|---|---|---|
| R7 | Mermaid 图渲染 | ✓ `components/mermaid.ts:60 createMermaidMarkdownTransformer` + `lovely-mermaid` 依赖 | ✗ | **缺失** | 1 | 不可达 D-W7-1：pi 依赖 `lovely-mermaid` JS 包（布局/主题/警告），Java 无等价库；围栏按代码块渲染（不进分子） |
| R12 | 工具执行卡：可折叠＋流式部分结果 | ✓ `tool-execution.ts` 的展开态与折叠提示 | 全局 Ctrl+O 展开态已接入渲染参数；折叠预览行＋省略行随 docs/60（工具族）；fallback 臂补**三态 bg**（`TOOL_PENDING/SUCCESS/ERROR_BG`，文本级只铺文本格＝窄差 D）＋**通用回落折叠 10 行**＋`... N more lines` 省略行（`FALLBACK_PREVIEW_LINES`）＋R26 参数形；**流式部分结果仍缺** | **存疑** | 2 | D197（`ToolCallRenderer` 无 `isPartial` 通道）维持——Java 渲染是 entry 投影、无流式卡槽；卡形面按对齐计 1/2 |
| R13 | bash 执行组件（实时增量输出 + 宽度自适应） | ✓ `components/bash-execution.ts:21`（**223 行**；`!!` 命令 dim 边框、`outputPad` 可配）+ `test/bash-execution-width.test.ts` | ✓ `ChatMessage.BashCard`＋`MessageCards.bash` | **存疑** | 2 | **漏项#7 落地**：bash 条目从 `User` 纯文本改为卡——`$ cmd` 头（`!!` ⇒ dim ≙ pi bashMode→dim）＋输出尾部 20 行预览（展开＝全部）＋状态行逐字（`(cancelled)` warning／`(exit N)` error／`Output truncated. Full output: …` warning）＋K37 点击展开；上下文截断在采集侧已做（Entry 带 truncated/fullOutputPath），卡内不重复；**运行中流式增量臂缺**（`BashExecutionUpdate` 无消费面）＝窄差 D，流式 pending 区随 R28；卡形按 1/2 计 |
| R21 | 回合分隔条（`Worked for Ns • Local tools: N calls`） | ✗（pi 无此构造） | ✓ `ChatScreen.java:170-181` | **非对齐项**（java 独有，Codex 风格） | 0 | 见「pi-java 独有」，**排除出分母** |
| R22 | 启动公告组件（`earendil-announcement`、`armin`） | ✓ `components/earendil-announcement.ts:27`、`armin.ts:62 class ArminComponent`（384 行） | ✗ | **缺失** | 1 | 旧的 `daxnuts.ts` 已删，彩蛋迁到 R27；**W8 复核维持缺失**（D-W8-2：无 Java 消费面，不移植） |
| R27 | **3D 彩蛋（pi logo / Armin）+ header logo**（全屏 overlay，惰性加载） | ✓ `components/easter-egg-3d.ts`（**1292 行**）+ `easter-egg-3d.lazy.ts` + `pi-logo.ts:15 piLogoLines`（40 行） | ✗ | **缺失** | 1 | java 无任何彩蛋/logo 动画；取代旧的 daxnuts（R22）；**W8 复核维持缺失**（D-W8-2：1292 行 3D 渲染器无 Java 等价/消费面） |
| R28 | **排队消息区（streaming 时聊天区与编辑器之间的 dim `Steering:`/`Follow-up:` 行）** | ✓ `interactive-mode.ts:461 pendingMessagesContainer`、`:4724 updatePendingMessagesDisplay`（末尾另有 `↳ <dequeue 键> to edit all queued messages` 提示行）；流式期间 bash/工具组件也挂该区 | ✓ `PendingQueueView.java`（含 hint 行）+ `ChatScreen.java`（`QueueUpdate` 驱动） | **存疑** | 1 | 证据更新：dequeue hint 行（键名随绑定表大写形）＋取回编辑整条流程已补齐（S20/K11 翻对齐）；**「流式 bash/工具组件挂该区」仍缺** ⇒ 判定维持存疑；**W8 复核维持**（D-W8-4） |
| ~~R29~~ | ~~安装变更警告（会话运行中 pi 被升级/替换 → 错误时提示重启命令）~~ | ✓ `interactive-mode.ts:2193 maybeShowInstallChangeWarning`＋`config.ts detectInstallChange` | ✗ | **出分母（不做）** | — | java 无 package.json 安装形态且无自更新通道（E6-10 终裁联动）⇒ 检测前提不成立；`maybeSuggestBugReport` 的恒 false 门即终局；docs/04 §4 C 类登记，触发条件＝分发通道落地 |
| R30 | 全屏滚动帧 O(viewport)（内容未变时复用 `LayoutFrame.renderCache`） | ✓ `tui.ts:613,1163`；`layout.ts:398 renderLayoutFrame(reusableOutput)`、`:84 renderCached` | ✗（TamboUI 布局内部不可达） | **缺失（窄差 D）** | 2 | Java 侧视口每帧 `TextLayout.wrap` 全量派生；TamboUI 为外部库，布局帧缓存无应用层等价接缝 ⇒ 登记窄差 D，不进分子 |
| R33 | ASCII/字素快路径＋`Text.setText` 相同文本保留换行 | ✓ `utils.ts:177-241 isStandaloneGraphemeUnit/graphemesOf`、`:251-262` 宽度缓存；`components/text.ts:25-32` | 部分：`TextLayout` 按码点处理（功能等价） | **缺失（窄差 D）** | 1 | Java 无 `Intl.Segmenter` 等价性能诉求、无 pi `Text` 组件；纯性能契约，R31 的源级解析缓存已承担「同源不重渲染」收益 ⇒ 登记窄差 D，不进分子 |

---

## 能力单元清单（其余）

### 键绑定 / 交互

键绑定/交互：已对齐且无差异 33 条（逐条证据见 git 历史）；差异 7 条见下。

| # | 能力单元 | 类别 | pi 侧证据 | pi-java 侧证据 | 判定 | 权重 | 台账条目 |
|---|---|---|---|---|---|---|---|
| K5 | `Alt+Enter` 排队 follow-up | 键绑定 | `keybindings.ts:67-70`：**Windows 上是 `ctrl+q`**，`alt+enter` 归 `tui.input.newLine` | `KeybindingsManager.java:28` 绑 `alt+enter`（默认表 `:67`）；`TamboUIAdapter.isNewlineEnter` 走 `shift+enter`；followUp 处理 `PiTuiApp.java:427-438`（压缩中也缓冲，B175） | **存疑** | 2 | H-9.2-② |
| K12 | `Ctrl+Z` 挂起进程 | 键绑定 | `keybindings.ts:29-32 app.suspend`（非 Windows） | ✗ | **缺失** | 1 | — |
| K19 | 滚轮/触控板归一化（事件流 → 行滚动） | 交互 | `tui/src/tui-alt-screen.ts:491 scrollBy` + `tui/src/wheel-scroll.ts` | `ScrollInputNormalizer.java:20`（274 行，Codex TUI2 PR#8357 移植）+ `ScrollConfig.java:35` | **存疑** | 3 | — |
| K36 | 模式 2027 握手规避（ConPTY 字节泄漏修复） | 平台 | —（pi 无此构造） | `NoMode2027Backend.java:20` + `NoMode2027JLineBackend.java:1` | **非对齐项**（java 独有） | 0 | **排除出分母** |

### TUI 组件

TUI 组件：已对齐且无差异 14 条（逐条证据见 git 历史）；差异 7 条见下。

| # | 能力单元 | 类别 | pi 侧证据 | pi-java 侧证据 | 判定 | 权重 | 台账条目 |
|---|---|---|---|---|---|---|---|
| C14 | mouse-region（把鼠标事件限定到区域） | 组件 | `tui/components/mouse-region.ts:33`（三张消息卡用它，见 K37） | ✓ 元素级等价 | **对齐（窄差 N）** | 1 | `MouseRegion` 组件不移植；`ChatViewportElement` 的 `messageRowRanges` 行区间分发承担同可观察行为（区域＝卡类消息的整行块，点击切换 per-card `cardExpanded` 态，null 回落全局展开态） |
| C16 | 编辑组件（多行 + 折叠 + 历史） | 组件 | `tui/components/editor.ts:297 class Editor`（2473 行）+ `editor-component.ts:74` + `custom-editor.ts:148` | `EditorComponent.java:27`（500 行）＋`EditorHistory.java`（143 行） | **存疑** | 3 | 补 K27（提示历史）；仍缺 K26 大粘贴折叠／K31 jump mode；见「存疑」清单 |
| C21 | render 只读/缓存契约（组件 `render` 返回只读 `string[]`；Container 在子节点/行未变时复用缓存数组） | 框架 | `tui.ts:130,369-393,470-472`、`box.ts:100,112` | TamboUI 外部组件协议（不可改） | **缺失（窄差 D）** | 1 | TamboUI 为外部库，组件 render 协议与 Container 缓存无应用层接缝；应用层以 R31 投影缓存承担等价意图 ⇒ 登记窄差 D，不进分子 |

### 主题 / 样式

主题/样式：已对齐且无差异 8 条（逐条证据见 git 历史）；差异 3 条见下。

| # | 能力单元 | 类别 | pi 侧证据 | pi-java 侧证据 | 判定 | 权重 | 台账条目 |
|---|---|---|---|---|---|---|---|
| T2 | 主题 token 覆盖（约 70 个语义色） | 主题 | `theme/dark.json` 与主题 token 表 | `ThemeToken`＋`SemanticColors`＋`ThemeApplicator` 已接主题消费；覆盖完整性待新锚点逐键复核 | **存疑** | 3 | 旧“仅 8 条 CSS 规则”已失效 |
| T6 | 语法高亮色板（10 token） | 主题 | `dark.json` 的 syntax token | `SyntaxHighlighter` 已改读 `MarkdownTheme`/主题令牌，不再固定三色；token 覆盖仍需复核 | **存疑** | 2 | — |
| T11 | system 主题（由终端调色板派生，多层 fallback） | 主题 | `theme/system-theme.ts` 与终端颜色探测 | `SystemTheme`＋`SystemThemeRecipe` 已落地；无 OSC 探测时使用既有 tier-3 路径，终端查询面待复核 | **存疑** | 1 | D123 |

### web 面（对齐相关部分）

web 对齐面：已对齐且无差异 13 条（逐条证据见 git 历史）；差异 3 条见下。

> pi 仓库内**没有** web UI（`grep -rln "WebSocket" packages/*/src` 只命中 AI provider 的 HTTP/WS 客户端与 settings-selector，`find . -name "*web-ui*"` 零命中）。因此 web 的**呈现层**单列在「pi-java 独有」；下面只列**事件/线格式**这一面对齐相关的能力单元，其 pi 侧证据取自 pi 的 agent-loop 事件词汇与 pi-webui 基线协议。

| # | 能力单元 | 类别 | pi 侧证据 | pi-java 侧证据 | 判定 | 权重 | 台账条目 |
|---|---|---|---|---|---|---|---|
| W3 | `turn_end` 带 `{message, toolResults}` | web 协议 | `agent/types.ts:438` | `AgentEventTranslator.java:149-160` | **对齐**（残余：错误路 `message` 为 null ⇒ 省键） | 3 | B42/B49（已结案，残余登记） |
| W16 | `find()` 的范围（`--session <id>` 跨项目查找） | 行为 | pi `findLocalSessionByExactId(id, cwd, sessionDir)` 项目内 | 精确臂已是 header-only 项目内（`findById`），但阶梯 ③ 仍跨项目扫描；`findWithScope` 带 `global` 标＋确认提示 | **存疑** | 1 | 登记 **D271**——保留跨项目能力（显式可感知），项目内命中路径与 pi 一致；新增 `exactIdLaneStaysInsideTheProject` 断言钉住 header-only/项目内；**W8 复核维持**（跨项目能力保留的既有裁决不变） |

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

## 加权汇总

**权重规则**（已定，未自创）：权重 = **用户可观察影响 × 频率**，只看「如果用户用这个模块，这个单元有多重要」，**不掺排期优先级**。
`3` = 每轮对话都走／默认路径；`2` = 每次会话走／常用命令；`1` = 低频／边缘／纯内部；`0` = 非目标（排除出分母）。
**完成系数**：对齐 = 1.0 ／ 存疑 = 0.5 ／ 缺失 = 0。

**L7 读数**

| 域 | Σ权重 | Σ(w×系数) | 加权完成度 | 未加权完成度 |
|---|---:|---:|---:|---:|
| 屏幕/面板（35 单元） | 47 | 45.5 | **96.8%** | 91.4%（32/35） |
| 渲染特性（R1–R33，31 计分单元；R21 权重 0 已剔；R29 出分母） | 45 | 35.5 | **78.9%** | 71.0%（22/31） |
| 键绑定/交互（K1–K41，39 计分单元；K36 权重 0 已剔） | 67 | 63.5 | **94.8%** | 92.3%（36/39） |
| TUI 组件（C1–C21，21 单元） | 42 | 39.5 | **94.0%** | 90.5%（19/21） |
| 主题/样式（T1–T11，11 单元） | 18 | 15.0 | **83.3%** | 72.7%（8/11） |
| web 对齐面（W1–W16，16 单元） | 33 | 32.5 | **98.5%** | 93.8%（15/16） |
| **合计** | **252** | **232.5** | **92.3%** | **86.9%（133/153）** |

**权重 3 子集（26 条）**：Σ权重 **78** ／ Σ(w×c) **73.5** ／ **94.2%**（对齐 23 条＝69.0、存疑 3 条＝4.5）。

**读数口径**：未加权百分比的分母是计分单元数（153）；S31 作废、R21/R29/K36 出分母。

**权重 3 的差异项与剩余差距（按可观察影响排序）**：K19（模型不同）、C16（大粘贴折叠/jump mode 缺）、T2（token 覆盖未逐键核）；其余 23 条权重 3 对齐项不再逐条列出（证据见 git 历史）。
