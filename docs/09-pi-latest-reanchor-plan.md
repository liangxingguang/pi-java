# 09 - 未对齐项清单（目标锚点 a00a5048d）

> 【背景摘要】本稿只保留**尚未对齐**的单元与仍可动作的窄差；已对齐或已完成的批次记录全部移除，历史在 git。
>
> 读数真源：`docs/05` §1（发布读数）；逐行证据：`docs/map/05-tui-web.md`、`docs/map/04-coding-agent.md`；未结项权威表：`docs/04` §1。当前锚点 `a00a5048d`（`CURRENT_PI_PIN`）。范围裁决（R1/R2/R5 等）见 `docs/04` §3，本稿不复写。

## 1. 剩余差距总览

> L0–L6 与 L8（88/88，按件）已全部对齐，不在本稿范围。

| 层 | 发布读数 | 未对齐项 |
|---|---:|---|
| L7 TUI/web（map/05） | **241.0/252＝95.6%** | 11 个非对齐计分单元（§3.1）；§3.2 开放缺口已清 |
| **全仓 L0–L7** | **1,375.0/1,386＝99.2%** | 仅 L7 |

## 2. L5 coding-agent（458/458＝100%）

settings 四条已清：E2-32／E2-54 对齐、E2-43 重判、E2-50 按 R5 出分母（`map/04` E2 处置表）。

### 2.1 仍可动作的窄差

无。

## 3. L7 TUI/web 未对齐（11.0 分）

> 153 个计分单元中 11 个非对齐（对齐 142，未加权 142/153＝92.8%）。逐行证据与 D 账见 `map/05`。
>
> 锚点 `a00a5048d` 逐项复核后，本轮对 §3.1 可修项实施修复：K5／S32／R12／R13／R28 翻正为对齐（平台键位与 newline 分流、登录 URL 复制、工具卡流式计时、bash 增量与排队区、生产 onEntry 终态去重均已落地并有定向测试），S2／S28／T6／T11 维持部分对齐（形状差）。读数按逐行复算 239.5/252（＋4.0）。
>
> 2026-11-28：K19 滚轮归一化按 pi `wheel-scroll.ts` 模型移植（速度加速 ≤6 行、macOS/SSH 平台分支、Alt+wheel×5、数字臂固定行数），部分对齐（形状差）→对齐；读数 241.0/252（＋1.5）。

### 3.1 非对齐计分单元

| 域 | 编号 | 判定 | 权重 | 当前缺口 |
|---|---|---:|---:|---|
| 屏幕/面板 | S2 | 部分对齐（形状差） | 2 | 已修：搜索条动态键位提示、flash 栈式多条、SGR 5 blink 属性。残余：双击选词/行选/边缘自动滚动未实现、图像为 Kitty/iTerm2 子集（D299–D301 中②）。 |
| 屏幕/面板 | S28 | 部分对齐（形状差） | 1 | 已修：GFM 表格、URL scheme 白名单、代码块 `class="hljs"`。残余：pi 的 hljs 实际高亮 span 未产出（java 仅转义代码块文本，TUI 侧 SyntaxHighlighter 已有九类着色）（D-W8-3）。 |
| 屏幕/面板 | S32 | 对齐 | 1 | `LoginDialogScreen` 已保存 `authUrl`，Ctrl+X 复制完整 URL 并渲染 `Copied URL to clipboard`／失败提示；设备码路径按 pi 不提供 URL 复制。 |
| 渲染特性 | R7 | 缺失 | 1 | Mermaid 渲染：pi 依赖 `lovely-mermaid`，Java 无等价库（D309；围栏按代码块渲染）。 |
| 渲染特性 | R12 | 对齐 | 2 | `ToolRunning` 实时卡原地更新＋`Elapsed X.Ys` 计时，终态转 `Took X.Ys` 无重复；事件级对位 pi `isPartial`。窄差：无 1Hz 定时重绘（仅事件到达时刷新）。 |
| 渲染特性 | R13 | 对齐 | 2 | `executeBash` 传 `outputSink` 每 chunk 发 `BashExecutionUpdate`；TUI `BashRunning` 卡原地累积、挂排队区，`EntryAppended` 与生产 `onEntry` 两条终态路径均去重收口。 |
| 渲染特性 | R22 | 缺失 | 1 | 启动公告组件无 Java 消费面，不移植（D-W8-2）。 |
| 渲染特性 | R27 | 缺失 | 1 | 3D 彩蛋/header logo（1292 行）无等价/消费面，不移植（D-W8-2）。 |
| 渲染特性 | R28 | 对齐 | 1 | `PendingQueueView` 增 `runningCards` 组件槽，bash 流式期间挂卡、终局移出；与 R13 同批交付。 |
| 渲染特性 | R30 | 缺失（窄差 D） | 2 | 全屏滚动帧 O(viewport)：TamboUI 布局内部无应用层接缝（D286–D288）。 |
| 渲染特性 | R33 | 缺失（窄差 D） | 1 | ASCII/字素快路径与 `Text.setText` 性能契约无 Java 等价（D286–D288）。 |
| 键绑定/交互 | K5 | 对齐 | 2 | `FOLLOW_UP` 按 `windowsKeybindings` 分流（Windows/WSL ⇒ Ctrl+Q，其余 Alt+Enter）；`isNewlineEnter` 收窄为 Shift+Enter／LF，followUp 不再被提前吞掉。窄差：设置页提示仍硬编码 Alt+Enter。 |
| 键绑定/交互 | K12 | 缺失 | 1 | `Ctrl+Z` 挂起进程：JVM 无 signal API（D316）。 |
| 键绑定/交互 | K19 | 对齐 | 3 | `WheelScrollAccelerator` 精确移植 pi 模型：事件间隔速度加速（≤6 行）、单 notch 突发不加速、`defaultAccelerate()` 在 macOS 本地终端返回 false（SSH 视为未知平台）；`PiTuiLauncher` 按 `fullscreenWheelScrollLines` 数字/auto 分流；`GlobalKeyRouter` 应用 SGR Alt 位 ×5 与 invert。`WheelScrollAcceleratorTest` 7 用例＋`PiTuiAppScrollTest` 6 用例全过。 |
| TUI 组件 | C21 | 缺失（窄差 D） | 1 | render 只读/缓存契约：TamboUI 组件协议不可改，无应用层接缝（D286–D288）。 |
| 主题/样式 | T6 | 部分对齐（形状差） | 2 | 已修：9 个 syntax token 全接主题 accessor，词法器新增 type/function/operator/punctuation 分支。残余：未识别标识符保守保持 plain（不染 variable 色），无 hljs 20+ 类按语言映射（D197 邻项）。 |
| 主题/样式 | T11 | 部分对齐（形状差） | 1 | 已修半面：`TerminalColors.parseOscReply` 解析 OSC 10/11/4（BEL/ST、hex6/12、rgb），`ThemeController` 暴露注入缝。残余：TamboUI 后端无 OSC 查询发送面，仍走 COLORFGBG／tier-3（D123）。 |

**权重 3 剩余差异项**：无（26 条权重 3 单元全部对齐；K19 于 2026-11-28 收口，C16／T2 于 2026-11-26 收口，证据见 `map/05` 对应行）。

### 3.2 开放缺口（已收口）

无。E5-31（余 7 个无 TUI 面的成员登记窄差 D323，权重 0）、B38、B39、B46、B145 均已对齐：B39 压缩窗口 Esc ⇒ `abortCompaction`；B38 压缩成功后按上下文条目整表重建并追加摘要卡/成本通知、中止/错误文案逐字；B46 TUI 消费工具执行实时卡（终态条目去重）；B145 补启动后台目录刷新点。

## 4. 关闭口径

1. 逐项修完后回填对应 `docs/map/` 行、`docs/05` §1 与 `docs/04` §1，读数按加权公式复算。
2. 无法消除的差异登记 D 窄差后再复算；未编号前不进入发布读数。
3. 范围裁决（R1/R2/R5、模型可见面优先等）维持 `docs/04` §3，不因本稿变化。
4. 验证：分模块 `mvn test -pl <module> -am`；收口时全仓 `mvn clean verify -Dfrontend.skip=true`（零错误、零告警）。
