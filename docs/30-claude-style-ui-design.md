# 30 - Claude 风格 TUI 设计稿（可选紧凑模式）

> 【背景摘要】用户反馈 pi/pi-java 自带 TUI「太难用」，希望得到 Claude Code CLI 那样的终端体验。本稿给出一个**可选、非默认**的 Claude 风格模式（下称 `claude-ui`）的设计与实施计划；默认 pi 对齐行为、对齐评分与全屏模式全部保持不变。本稿只做设计，未写代码。
>
> 【术语】
> - **fullscreen**：备用屏（alternate screen）全屏渲染，pi 默认模式，应用自绘滚动条并接管鼠标。
> - **inline / regular**：不切备用屏，对话写进终端原生 scrollback，只有底部输入区就地重绘。
> - **Claude 风格**：Claude Code CLI 的排版习惯——原生滚动、紧凑工具卡、底部圆角输入框、单行提示与状态、`✻` 活动指示。
> - **doctor 判据**：以「用户可见行为」为准，不以内部实现形态为准。

## 1. 目标与非目标

| 项 | 内容 |
|---|---|
| 目标 | 新增 `claude-ui` 模式：终端原生滚动/选中/搜索、紧凑转录、工具卡单行化、底部圆角输入框、常驻状态行（模型与上下文用量）、运行中流式输出与排队提示 |
| 默认不变 | `uiStyle` 缺省 `pi`；所有现有默认行为、pi 对齐读数、既有测试夹具零变化 |
| 完成判据 | ① `--ui-style claude` 与 settings 均可进入；② 同一会话在两种风格间可切换；③ `mvn test -pl pi-java-tui -am` 全绿；④ 文档台账回填（§8） |
| 不做项 | 逐工具权限确认（沿用 pi 的项目信任）、替换默认 TUI、把 pi 全屏模式改掉、Mermaid/3D 彩蛋、重写 TamboUI 事件框架 |

## 2. 现状证据（当前工作区）

| 事实 | 证据 |
|---|---|
| 默认全屏，`tuiMode` 只有 `fullscreen`/`regular` 两值 | `PiTuiLauncher.java:181-184`、`:357-362`；`ArgsParser.java:157-158`、`:223-226`；`Settings.java:99` |
| 全屏用 `ToolkitRunner`：33ms tick、备用屏、鼠标捕获、隐藏光标 | `TamboUIAdapter.java:61-76`；`PiTuiLauncher.java:254-268` |
| inline 用 `InlineTuiShell`：不进备用屏、不捕获鼠标、底部区就地重绘 | `InlineTuiShell.java:24-45`、`:91-103`；`PiTuiLauncher.java:292-321` |
| 底部区当前是「补全弹层＋扩展槽＋编辑器＋状态栏」，**没有**排队区与活动指示行 | `ChatScreenLayout.java:96-115`、`:124-155` |
| 状态栏只有 会话名／总 token／模型 | `StatusBar.java:15-23`；`SessionSnapshot.java:8-16` 无上下文字段 |
| inline 模式**不流式**：草稿刻意不写 scrollback，TextEnd 才整块打印 | `ScrollbackTranscript.java:71-89`；`InlineTuiShell.java:149-175` 已有 `println`/`replaceLastBlock` 能力但未被草稿路径使用 |
| 工具调用与结果分两条气泡渲染，非合并卡；折叠靠全局 Ctrl+O | `ChatMessage.java:40-56`；`MessageBubble.java:63-72`、`:226-254` |
| thinking 草稿用 `🧠` 前缀伪装成普通文本，Ctrl+T 只对回放块生效 | `TranscriptProjector.java:126-138`；`MessageBubble.java:179-193` |
| inline 的 `Alt+Enter` 走换行分支，早于 FOLLOW_UP 动作解析 | `GlobalKeyRouter.java:143-157`；`TamboUIAdapter.java:311-319`；`KeybindingsManager.java:328` |
| inline 事件由自家循环直接分发，bracketed paste 可达编辑器 | `GlobalKeyRouter.java:73-76`；`InlineTuiShell.java:248-294` |
| 上下文窗口解析器已在 harness 配置里：`config.contextWindow()` | `HarnessBootstrap.java:97-100`、`:123`；`HarnessConfig.java:41-43`、`:148` |
| 当前会话 cwd 可从会话读 | `AgentSession.java:213` |
| 现有 TUI 文件均 ≤500 行（`MessageBubble` 463 行，已逼近闸门） | `wc -l` 实测 |

## 3. 方案总览

`uiStyle` 是**新增的一维**，与 `tuiMode` 正交：

```
渲染风格  uiStyle : "pi"（默认） | "claude"
终端模式  tuiMode : "fullscreen" | "regular"
```

选择链：

1. `--ui-style <pi|claude>` ＞ `PI_UI_STYLE` ＞ `settings.uiStyle` ＞ `"pi"`。
2. 终端模式：`--tui-mode` 显式传入者胜；否则 `uiStyle=claude` 时缺省 `regular`（原生 scrollback），`uiStyle=pi` 时沿用现有 `tuiMode`（缺省 `fullscreen`）。
3. `uiStyle=claude` + 显式 `--tui-mode fullscreen` 合法：紧凑排版照用，但仍由应用接管滚动（用户明确要全屏时尊重选择）。

目标画面（inline）：

```
 ✻ Thinking… (esc to interrupt)        ← 运行中才出现，位于输入框上方
 ● Bash(npm test)
   ⎿ 12 passed (2.1s)
 ● Read(src/App.java)
   ⎿ 120 lines

 ╭───────────────────────────────────╮
 │ › _                               │
 ╰───────────────────────────────────╯
   ? for shortcuts      opus-5.5   42% ctx
```

## 4. 关键裁决（三段式）

**R1 模式形态：可选开关，不动默认**
> 【场景】直接改默认会推翻 pi 全屏对齐、已有 L7 读数与全部回归夹具。
> 【决定】新增 `uiStyle`（settings 字段＋CLI `--ui-style`＋环境变量），缺省 `pi`；`claude` 必须显式开启。
> 【风险/代价】多一个配置面，需在 help、settings 选择器与文档登记。[用户可见]

**R2 终端模式归属**
> 【场景】若 `uiStyle` 与 `tuiMode` 各自决定，会互相打架。
> 【决定】两层正交；`claude` 只改变**缺省**终端模式（→ regular），显式 `--tui-mode` 永远优先。
> 【风险/代价】`claude + fullscreen` 组合下没有原生滚动，只得到紧凑排版；文档需写明。[用户可见]

**R3 渲染策略注入点：`MessageBubble.lines` 分派**
> 【场景】转录渲染同时被全屏视口（`ChatViewportElement`）与 scrollback 打印（`ScrollbackTranscript`）消费，若只改一处会风格不一致。
> 【决定】在 `MessageBubble.lines` 保留原 pi 实现，在其入口按 `TranscriptStyle.current()` 分派到新件 `ClaudeMessageRenderer`；两处消费点零改动。
> 【风险/代价】`MessageBubble` 已 463 行，只加分派不内联实现；新渲染器独立成件守 500 行闸门。[内部]

**R4 thinking 改为显式消息类型**
> 【场景】现状 thinking 草稿与回放块走两条路（`🧠` 文本 vs `ThinkingContent`），Ctrl+T 行为不一致。
> 【决定】新增 `ChatMessage.Thinking(String text, boolean live)`；`TranscriptProjector` 在 ThinkingStart/Delta/End 与 StreamDone 时产出该类型；渲染层统一处理折叠/展开/隐藏。
> 【风险/代价】sealed 接口加变体会触发所有穷举 switch 的编译错误，需一次补齐（`MessageBubble`、`ScrollbackTranscript`、相关测试）。[用户可见]

**R5 工具卡：调用与结果合并为单卡视图**
> 【场景】两条独立气泡占 3-5 行，长会话滚动成本高。
> 【决定】Claude 渲染器输出：首行 `● Name(摘要)`（running=accent、成功=success、失败=error）；成功后紧随 `⎿ 首行/共 N 行`；折叠预览 3 行，`Ctrl+O` 或点击展开全部；参数摘要按工具族（bash 显示命令、read 显示路径、其余 key=value 截 80 字）。
> 【风险/代价】Java 侧调用与结果在消息模型里是两条记录，不做真正的「合并对象」，只做**相邻行合并排版**；流式参数增量仍不显示（沿用现状，D197）。[用户可见]

**R6 底部 dock 结构**
> 【场景】inline 底部区当前无排队区、无活动行、无提示行。
> 【决定】Claude 分支的 `renderBottomArea` 顺序：排队区 → 活动指示行（运行时）→ 圆角输入框 → 提示/状态行；不渲染扩展 header 与状态槽之外的旧元素时保持扩展槽位可用（扩展 widget 仍可插入）。
> 【风险/代价】`ChatScreenLayout` 已有 339 行，Claude 分支抽到新件 `ClaudeDockLayout`，原路径零改动。[用户可见]

**R7 活动指示位置**
> 【场景】Claude Code 的 `✻ Thinking…` 贴在输入框上方；pi-java 现在只有底部一行状态栏。
> 【决定】Claude 模式下把既有 `StatusIndicator` 的富文本行移到输入框上方；底部状态行只保留常驻信息（模型/上下文/快捷键提示）。指示器数据源与四类文案完全复用，不改 `SessionEventRouter`。
> 【风险/代价】`Ctrl+O` 展开提示等旧文案位置变化；不影响 pi 模式。[用户可见]

**R8 常驻状态行：上下文用量与 cwd**
> 【场景】`SessionSnapshot` 无上下文窗口字段，无法显示 `42% ctx`（L6 #20 也卡在同一处，已随 B293 关闭）。
> 【决定】`SessionSnapshot` 追加四个字段：`contextTokens`（用 `ContextUsageEstimator.estimateContextTokens(lane.messages)` 估算）、`contextWindow`（`config.contextWindow()` 按当前模型解析）、`cwd`、`queuedCount`；`SnapshotService` 构造参数补一个窗口供应器。`contextWindow<=0` ⇒ 只显示 token 数，不显示百分比。
> 【风险/代价】record 加组件会触发全部构造点（`SnapshotService:96` 与测试夹具）编译错误；`queuedCount` 需接 `QueueManager` 现读。[用户可见]
> [注] 该字段同时解 L6 #20 的「context footer 顺延」悬案（已随 B293 关闭），属附带收益，不在本稿验收口径内。

**R9 inline 流式草稿（默认开，可关）**
> 【场景】inline 现状要等 TextEnd 才出整段回复，长回答期间界面静止。
> 【决定】Claude 模式启用 `ScrollbackTranscript` 已有但未用的 `replaceLastBlock` 路径：草稿逐帧改写最后一块；返回 `false`（块已滚出屏或高度不安全）⇒ 立刻停止改写，静默等 TextEnd 整块打印。提供 `PI_INLINE_STREAM=0` 逃生口。
> 【风险/代价】Windows ConPTY 下就地改写曾与 insert-line 竞争导致乱码（`ScrollbackTranscript.java:83-88` 的注释即为此）；因此必须保留「一次失败即永久回退」的熔断，并在假后端上测失败臂。若实机仍复现，按 R2 让用户切 `--tui-mode fullscreen`。[用户可见]

**R10 输入键位：Claude 语义只在 claude 模式生效**
> 【场景】`Alt+Enter` 现在恒走换行，排队/追发动作不可达；用户抱怨键位不顺手。
> 【决定】`uiStyle=claude` 时：空闲 `Enter` 提交、`Shift+Enter` 换行；运行中 `Enter` 排队追发（等价 pi 的 follow-up），`Alt+Enter` 仍为换行。判定顺序改为：运行中先查 FOLLOW_UP 绑定，再查换行。`pi` 模式保持现状。
> 【风险/代价】同一按键两套语义，需在 `GlobalKeyRouter` 集中判定并以测试钉死；`docs/map/05` K5 的「存疑」在 claude 模式外不变。[用户可见]

**R11 权限模型不变**
> 【场景】Claude Code 的逐调用确认会让长任务频繁打断，且 pi 的语义是项目信任。
> 【决定】沿用 `ProjectTrustOption` 启动信任，不引入逐工具审批；`ApprovalHandler` 保持 `null`。
> 【风险/代价】与 Claude Code 不完全一致，属**有意差异**；在本文档与 `docs/04` 登记。[用户可见]

**R12 输入框换行：显示层软换行**
> 【场景】编辑器目前按逻辑行横向裁剪，长提示只能左右滚，Claude Code 是整段软换行。
> 【决定】只改 `EditorElement` 的**显示**：按可用宽度软换行，光标行/列由换行后的视觉行反查；逻辑行、撤销、历史、粘贴标记等数据面不动。
> 【风险/代价】光标定位与 CJK 宽度（`TextColumns`）是该模式最容易回归的点，需补视觉行映射单测。[用户可见]

**R13 全屏模式下的空格规则**
> 【场景】全屏视口当前每条消息之间固定插一个空行。
> 【决定】`TranscriptStyle` 暴露 `separatorRows()`：pi=1，claude=0（用户消息前除外，由渲染器自带前置空行）。
> 【风险/代价】仅 claude 全屏组合可见；inline 本就不插空行。[用户可见]

## 5. 变更清单（按模块）

### 5.1 pi-java-coding-agent

| 文件 | 改动 |
|---|---|
| `core/Settings.java:99` 附近 | 新增 `public String uiStyle;`（`"pi"`/`"claude"`） |
| `core/SettingsAccessors.java` | 新增 `getUiStyle()` / `setUiStyle()`（沿用 `markModified("uiStyle")` 形） |
| `cli/ArgsParser.java:157-226` | 新增 `--ui-style`，白名单 `pi|claude`，错误文案与 `--tui-mode` 同形 |
| `cli/Args.java:100` | 追加 `uiStyle` 组件 |
| `cli/HelpText.java:109` 附近 | 帮助行补一行 |
| `tui/screen/settings/SettingsItems.java` | 设置选择器新增 `ui-style` 循环行（`pi`/`claude`） |

### 5.2 pi-java-agent-core

| 文件 | 改动 |
|---|---|
| `harness/SessionSnapshot.java:8-16` | 追加 `contextTokens`、`contextWindow`、`cwd`、`queuedCount` |
| `harness/SnapshotService.java:24-33`、`:89-104` | 构造参数补 `ToIntFunction<ModelId<?>> contextWindow` 与 `Supplier<String> cwd`；构建时估算上下文 token、统计排队数 |

### 5.3 pi-java-tui

| 文件 | 改动 |
|---|---|
| `component/TranscriptStyle.java`（新） | 枚举 `PI`/`CLAUDE`，`current()` 现读＋`separatorRows()` |
| `component/ClaudeMessageRenderer.java`（新） | 紧凑渲染：用户行、助手正文、thinking、工具卡、结果行、错误、卡类摘要、轮次分隔 |
| `component/MessageBubble.java:60-71` | 入口分派到 `ClaudeMessageRenderer`（原实现改名私有、零字节改动） |
| `component/ChatMessage.java:14-124` | 新增 `Thinking(text, live)` 变体 |
| `component/ClaudeDockLayout.java`（新） | 底部区装配：排队区→活动行→输入框→提示/状态行 |
| `component/ClaudeHintsBar.java`（新） | `? for shortcuts`、模型名、`N% ctx`、cwd 的窄差化单行 |
| `component/EditorComponent.java:69-89` / `util/EditorElement.java` | 圆角框变体（仅 claude）＋显示层软换行（R12） |
| `screen/ChatScreenLayout.java:96-115` | `uiStyle=claude` 时委托 `ClaudeDockLayout` |
| `screen/TranscriptProjector.java:126-138`、`:174-186` | thinking 产出 `ChatMessage.Thinking`；轮次分隔文案改 `✻ Cooked for …`（仅 claude） |
| `util/ScrollbackTranscript.java:71-89` | 启用 `replaceLastBlock` 草稿臂＋熔断＋`PI_INLINE_STREAM` 开关 |
| `app/PiTuiLauncher.java:181-184`、`:357-362` | `uiStyle` 解析、终端模式缺省、`TranscriptStyle` 安装 |
| `app/PiTuiApp.java:248-267`、`:310-319` | 根装配的空格规则；inline 底部高度公式纳入活动行 |
| `app/GlobalKeyRouter.java:143-157` | claude 模式下的运行中 Enter 排队、Alt+Enter 换行判定 |
| `screen/WelcomeOverlay.java`（可选） | claude 模式改用单行版本提示（保留 cwd/model） |

## 6. 分阶段实施

| 阶段 | 内容 | 验收 |
|---|---|---|
| **W1 接线** | `uiStyle` settings/CLI/env；`TranscriptStyle`；`MessageBubble` 分派；启动器选择 | 新单测：选择链优先级、默认 `pi` 下全量既有测试零变化 |
| **W2 紧凑渲染** | `ClaudeMessageRenderer`＋`ChatMessage.Thinking`＋工具卡/结果/分隔/空格规则 | golden 文本逐行钉（示例会话夹具）；穷举 switch 编译补齐 |
| **W3 底部 dock** | `ClaudeDockLayout`＋活动行位置＋提示/状态行＋软换行；`SessionSnapshot` 四字段 | 布局行序单测；`N% ctx` 与 `contextWindow<=0` 回落；快照构造点全改 |
| **W4 流式与键位** | inline 草稿改写＋熔断＋env 开关；运行中 Enter 排队；粘贴回归 | 假后端测「改写成功/失败回退/开关关闭」三臂；键位表驱动测试 |
| **W5 收口** | 帮助文本、settings 行、文档台账、窄差登记 | `mvn test -pl pi-java-tui -am` 全绿；`mvn clean verify` 零错误零警告 |

## 7. 测试与验证

**自动化**

1. `ClaudeMessageRendererTest`：用户行/正文/thinking live 与回放/tool 调用/结果成功/失败/截断/卡类摘要/分隔行，逐行断言（不依赖主题色）。
2. `TranscriptStyleDispatchTest`：同一 `ChatMessage` 在两种风格下的行数与形状差异；`separatorRows()`。
3. `ClaudeDockLayoutTest`：空闲/运行中/有排队三态的行序与高度。
4. `SnapshotServiceTest`：`contextTokens/contextWindow/cwd/queuedCount`；窗口 0 回落。
5. `ScrollbackTranscriptTest` 扩展：草稿改写成功、改写失败回退、`PI_INLINE_STREAM=0`。
6. `GlobalKeyRouterTest` 扩展：claude 运行中 Enter⇒排队、Alt+Enter⇒换行；pi 模式语义不变。
7. `SettingsAccessorsTest` / `ArgsParserTest`：`uiStyle` 读写、非法值报错、优先级。

**手工**

```bash
# 构建
set JAVA_HOME=D:\soft\jdk\graalvm-jdk-25
./mvnw -q -pl pi-java-tui -am test

# 进入 claude 风格（临时 agent 目录隔离配置）
pi-java --ui-style claude
```

检查清单：① 长回复持续流式不闪烁；② 鼠标滚轮走终端原生 scrollback；③ 终端原生选中/复制可用；④ 工具卡单行且 Ctrl+O 可展开；⑤ 底部输入框、提示行、`N% ctx` 常驻；⑥ 运行中 Enter 进入排队区；⑦ 退出后 scrollback 完整保留（inline 不倾倒备用屏）；⑧ `--ui-style pi` 回到现状。

## 8. 台账与文档

- `docs/04-open-items-register.md`：登记「claude-ui 为 Java-only 可选能力」，并记 R11 的有意差异。
- `docs/map/05-tui-web.md`：加入「pi-java-only」清单（与 welcome 卡、`Worked for` 同表），**不进入 pi 对齐分子/分母**。
- `docs/05-module-alignment-map.md` §1：注明 L7 读数不受影响。
- `docs/03-detailed-design.md` §7：补一节模式选择（现文仍以全屏为唯一形态）。
- 本稿在 W5 收口后按既有惯例清理或留档。

## 9. 风险汇总

| 风险 | 等级 | 缓解 |
|---|---|---|
| inline 就地改写回归 Windows ConPTY 乱码 | 中 | 一次失败永久熔断＋env 开关＋假后端三臂测试；必要时切 fullscreen |
| `SessionSnapshot` 加组件引发构造点漏改 | 中 | 编译器穷举（record 组件）＋全仓测试；按「record 加组件要搜全部构造点」既有教训执行 |
| 软换行光标定位/CJK 宽度回归 | 中 | 视觉行映射单测；复用 `TextColumns` |
| 两套键位语义混淆 | 低 | 集中在 `GlobalKeyRouter` 单点判定，表驱动测试 |
| `MessageBubble` 逼近 500 行 | 低 | 只加分派，实现放新件；`FileLength` 是 error 级闸门 |
| 用户误以为默认行为被改 | 低 | 缺省 `pi`、help/文档显式说明 |
