# 59 - L7 TUI / web 兼容面重测与实施设计

> 【背景摘要】本文档是 L7（`TUI / web 兼容面`）的新锚点重测结论与实施设计。L7 是唯一
> 尚未闭环的层；它承接 L5 转出的 **E3-3**（会话选择器）与 **E5-31**（扩展 UI 余 TUI 成员），
> 以及 D220 里随 E5-31 转来的 `blocked` 选项。重测以 pi `56cae7823` 对齐 `ea448f454`
> 的逐文件 diff 为取证单位：**有 diff 的行逐条重判，无 diff 的文件行为未变、判定沿用**。

- pi 锚点：`56cae782341c339d3bb820081f0f9425d8c5b1ec`（旧基线 `ea448f454`）
- 上游依据：`docs/map/05-tui-web.md`（149 单元）、`docs/04` E5-31／D220、`docs/25` §14
- 用户裁决：2026-11-19「开始 L7」——设计落地后直接实施
- **文档状态：2026-11-19 重测完成，W1 起实施。**

## 0. 口径

1. 判定单位与三档沿用 map/05「基准与口径」节（对齐／存疑／缺失；权重 3/2/1/0）。
2. **框架层归属**：pi `packages/tui/src` 是自带组件库的终端框架，Java 的对位物是外部库
   **TamboUI**。凡 diff 落在框架层且 Java 无同名构造（Kitty 协议解析、stdin 缓冲、图像协议、
   全屏选区/搜索/flash、SGR 直写），一律登记窄差、不伪装实现（docs/27 §3 同款纪律）。
3. web 呈现层仍按 map/05 §「pi-java 独有」出分母（权重 0），只有**线格式/行为契约**（W 系列）计分。

## 1. 新锚点变更面（71 文件）

两次只读取证（pi 侧逐文件 diff → 用户可见行为；Java 侧 15 问对位核查）结论：

| 域 | 变更文件 | 用户可见变更 |
|---|---|---|
| 工具渲染 | `render-utils.ts`(+82)、`renderers/bash.ts`、`renderers/read.ts`、`tool-execution.ts` | ① 行内计时 `Elapsed X.Xs`（流中每秒重绘）→ `Took X.Xs`（终局，优先 `durationMs`）；② `formatReadLineRange(offset, limit)` 接受 `unknown`（字符串数字也认，非法整段省略）；③ 路径缩写统一 `shortenHomePath`；④ 流式参数改为每帧应用一次 |
| 列表/选择器 | `select-list.ts`、`settings-list.ts`、`components/{model,scoped-models,oauth,session,user-message,settings-submenu,tree}-selector.ts`、`editor.ts`、`custom-editor.ts` | PgUp/PgDn 整页翻页（选择列表、设置列表、模型/scoped/oauth/会话/用户消息/子菜单）＋「补全菜单打开时占视口输入」 |
| 模糊/搜索 | `fuzzy.ts`、`session-selector-search.ts`、`session-selector.ts`、`model-search.ts` | 模糊打分新增「连续整段最优对齐」；会话搜索分级（名字 > 文本 > 模糊）＋逐字段匹配；会话「同一会话」用规范化＋Windows 折大小写比较键；模型别名 `(name)` muted 显示（别名只是 id 改写时不显示） |
| 扩展 UI | `interactive-mode.ts`（扩展对话框部分）、`extension-editor.ts`、`custom-entry.ts`、`custom-message.ts`、`tool-execution.ts`、`export-html/tool-renderer.ts` | 对话框**栈化**（关闭回上一层，blocked 随栈顶）；custom entry/message 与工具渲染上下文新增 `keyText`/`keyHint`；扩展编辑器内 Ctrl+G 失败在框内以 warning 显示 |
| 启动/标题 | `interactive-mode.ts`、`terminal.ts`、`SettingsItems` 邻域 | `[Skill conflicts]` → `[Skill warnings]`；`Model scope:` 从 stdout 改渲染聊天 header；终端标题过滤控制字符 |
| 外部编辑器 | `external-editor.ts`、`interactive-mode.ts` | 失败文案具体化：`Failed to launch external editor: <cmd>` / `External editor exited with code N; prompt unchanged` |
| 导出 | `export-html/{index,template.{html,css,js},tool-renderer}.ts` | 内嵌 marked-cjk-friendly（emStrong）；system 条目就地渲染（sections±/tools±）；树迭代化（防深递归）；页头费用用 `cost.total`；read 行范围接受字符串 |
| 主题 | `theme/{dark,light}.json` | 仅 `$schema` URL 变更（色板未动） |
| CLI | `args.ts`、`credential-print.ts`、`cli/experimental/*`（6 文件删除） | `--help` 文案重写、`--export <file> [output]` 第二参数、`PI_SELF_UPDATE_COMMAND`；`pi auth` 走模糊引用解析；`pi experimental server|client` 整组删除 |
| 框架层 | `keys.ts`(+1455 行内改)、`stdin-buffer.ts`、`terminal-image.ts`、`terminal.ts`、`tui-alt-screen.ts`、`tui-main-screen.ts`、`tui.ts`、`layout.ts`、`utils.ts`、`markdown.ts`、`autocomplete.ts`、`program-status.ts`、`keybindings.ts` | F1–F12 键识别、OSC 应答串等待终止符、Herdr/Rex/Orca 图像能力、`ESC+CR`＝Shift+Enter、退出兜底复位、双击选区连接符、OSC 133 丢弃、删除线行尾 `ESC[29m`、CJK emStrong/裸 URL 截断/块级公式回退、`@` 补全正则转义、组件 `capturesViewportInput` |

## 2. 逐行重判

### 2.1 有 diff 的行（判据＝pi 新行为 vs Java 现状）

| 行 | 单元 | 旧判定 | 新判定 | 依据 |
|---|---|---|---|---|
| R11 | 逐工具渲染器 | 缺失 | **缺失**（缺口收窄） | `Took/Elapsed` 计时与 read 行范围未进渲染；`ToolCallCard` 仍只有 name/status/args |
| R12 | 工具执行卡折叠/流式 | 缺失 | **缺失** | 流式参数每帧应用未做；折叠提示仍缺（D197） |
| R10 | Diff 渲染 | 存疑 | **存疑** | pi 本锚点 diff 面未变，Java 仍行首着色 |
| R6 | Markdown transformer | 对齐 | **对齐** | 上下文 `messageId`/`timestamp` Java 已有（`MarkdownTransformContext:14-16`） |
| R23 | 终端标题 | 缺失 | **缺失**（登记 D258） | pi 加控制字符过滤；Java `setTitle` TUI 臂仍空操作 |
| R3 | 删除线 | 对齐 | **对齐**（窄差 D257 更新） | pi 行尾补 `ESC[29m`；Java 走 TamboUI markup，无 SGR 直写面 |
| R1/R2 | Markdown CJK | 对齐 | **对齐**（窄差 D143 不变） | pi 引入 marked-cjk-friendly emStrong／裸 URL 截断；Java 行级 parser 子集，行为面等价于旧判定 |
| R20 | 自定义消息卡 | 对齐 | **对齐**（D220 扩面） | pi 渲染上下文新增 keyText/keyHint；Java 无（D220 维持不移植） |
| S4 | 启动资源区 | 对齐 | **对齐**（本锚点新增 skill 区段缺 → 计入 S4 权重内） | pi 文案改 `[Skill warnings]`；Java `StartupNotices` 无 skill 区段 |
| S5 | 模型选择器 | 对齐 | **存疑** | 别名 `(name)` 未显示、无页翻 |
| S7 | scoped-models | 对齐 | **存疑** | 同上 |
| S11 | 会话选择器搜索/排序 | 存疑 | **存疑**（缺口明确） | 分级排序缺；「同一会话」未用比较键 |
| S14 | 设置页 | 对齐 | **对齐** | `sessionShareDestination` 已在 `SettingsItems:74-77` |
| S16 | config 选择器 | 对齐 | **对齐** | 仅内部改写 |
| S17 | 登录对话框 | 对齐 | **对齐** | device-code 提示行与复制动作 Java 已有 |
| S20 | 排队消息选择器 | 对齐 | **存疑** | 本锚点加页翻；Java 未接 |
| S22 | 扩展 UI 对话框 | 对齐 | **存疑** | 无对话框栈，关闭直接回落编辑器 |
| S27 | `/share` | 存疑 | **对齐** | `SessionShareService:84-104` 三分流＋radius 文案逐字 |
| S28 | `/export` HTML | 存疑 | **存疑**（缺口收窄） | CJK emStrong／system 就地渲染 Java 已有；缺页头 `cost.total`、无树迭代面 |
| C2 | 选择列表 | 存疑 | **存疑** | 无 PgUp/PgDn |
| C3 | 设置列表 | 缺失 | **缺失** | 仍用裸 Element 拼行 |
| C17 | 模糊匹配 | 存疑 | **存疑** | `FuzzyMatch` 子序列打分已有，取「最优对齐」缺；`FuzzyMatcher` 子串旧实现仍在 |
| K5 | Shift+Enter | 存疑 | **存疑** | pi 加 `ESC+CR`（Apple Terminal）；Java 由 TamboUI 解析 |
| K10 | 外部编辑器 | 对齐 | **对齐**（文案面缺） | 失败文案仍是 `External editor failed: <msg>` |
| K24 | 斜杠补全弹层 | 对齐 | **对齐**（抢占面缺） | 补全打开时 PgUp/PgDn 应先翻列表 |
| K25 | `@文件` 补全 | 缺失 | **缺失** | 正则转义面一并缺 |
| K32 | Kitty 键盘协议 | 存疑 | **存疑**（登记 D255） | F1–F35 识别属 TamboUI 解析面 |

### 2.2 无 diff 的行

`git diff ea448f454 56cae7823` 对 map/05 其余行对应文件**零变更**（S1/S2/S3/S6/S8–S10/S12/S13/S15/S18–S21/S23–S26/S29/S30/S32、R4/R5/R7/R8/R9/R13–R19/R21/R22/R24–R29、K1–K4/K6–K9/K11–K23/K26–K31/K33–K39、C1/C4–C16/C18–C20、T1–T11、W1–W16 的非 components 面），按「参照物未变 ⇒ 行为承诺未变」沿用旧判定。**唯一例外**：W 系列（web）不依赖 pi 的 TUI 文件，L7 单独复核（见 §3 W6）。

## 3. 实施包（W1–W6）

| 包 | 覆盖行 | 内容 | 权重和 |
|---|---|---|---|
| **W1** | R11、R10 邻域 | 工具行计时（`formatToolDuration` 逐字：`<60s ⇒ X.Ys`／`<60m ⇒ Nm Rs`／`else Hh Mm Rs`；流中 `Elapsed`→终局 `Took`，优先 `durationMs`）；内置 read 渲染接入 `ReadLineRange`；`ToolCallCard` 扩 `durationMs`/`partial`。**已实施（2026-11-19）**：计时走 `ToolDurations`＋`Message.ToolResultMessage.durationMs`；8 个内置渲染器见 **docs/60**（`BuiltInRenderers` 坐扩展链尾） | 3 |
| **W2** | C2、S5、S7、S20、K24 | `SelectList`＋设置页＋模型/scoped/oauth/会话/用户消息选择器 PgUp/PgDn 整页翻；模型别名 muted `(name)`（别名去重规则逐字）；补全菜单打开时抢占 PgUp/PgDn。**已实施（2026-11-20）见 docs/62**（别名去重＝`ModelLabels`；补全门＝`GlobalKeyRouter.completerTakesPageKeys`） | 2+1+1+1+2 |
| **W3** | S11、C17 | 会话搜索分级排序（名字 > id/消息/cwd > 模糊，逐字段匹配）；会话「同一会话」改用规范化折大小写比较键；`FuzzyMatch` 取最优连续段对齐。**已实施（2026-11-20）见 docs/63**（`SessionPathKey`；`FuzzyMatcher` 与 `FuzzyMatch` 合一） | 1+2 |
| **W4** | S22、K10 | overlay 栈化（对话框内再开、关闭回上层，`blocked` 随栈顶）；扩展编辑器内 Ctrl+G 失败 warning 显示；外部编辑器失败文案逐字。**已实施（2026-11-20）见 docs/64**（`OverlayManager` 改栈；`Result.Failed(String)` 两条逐字文案；框内 warning） | 1+1 |
| **W5** | S4、S5、R23 | 启动资源区补 `[Skill warnings]` 区段；`Model scope:` 改渲染聊天 header（不再 stderr）；TUI 终端标题实现（含控制字符过滤）。**已实施（2026-11-20）见 docs/65**（`SkillDiagnostics`／`ModelScopeNotice`／`TerminalTitle`） | 2+1 |
| **W6** | W8–W12、W16（web 面） | `queue_update`/`compaction_*`/`auto_retry_*` 推前端（W8）；消息 `timestamp` 上 wire（W9）；`agent_end.messages` 整表（W10）；宿主并发契约声明（W11）；`transcript()` 读取点（W12）；`find()` 按项目内查找（W16）。**已实施（2026-11-20）见 docs/66**（复用 `JsonEventMapper.toWire`；timestamp 毫秒；空表也写 `messages`；D271） | 1+1+3+1+1+1 |

顺序：W1 → W2 → W3 → W4 → W5 → W6。每包自带独立测试，完成后回填 docs/04／06／map/05／docs/07（web 面）。

## 4. 窄差登记（新增 D255–D259）

| 编号 | 内容 |
|---|---|
| **D255** | pi 本锚点扩了 Kitty 协议/键解析（F1–F12 识别、`ESC+CR`＝Shift+Enter、F13–F35 不注入）：Java 的按键解析在 **TamboUI**（外部库），扩展需改库；登记不移植，K5/K32 维持存疑。 |
| **D256** | 框架层整块面归属 TamboUI：全屏转录搜索（S2a）、鼠标选区/copy-on-select（S2b/K22）、flash（S2c）、图像协议与能力探测（S2d/R14/K35）、OSC 应答串终止符（C19）、OSC 133 丢弃、双击选区连接符、`capturesViewportInput` 路由消费点。Java 均无同名构造，登记不移植。 |
| **D257** | 删除线行尾复位（`ESC[29m`）与跨行样式隔离：Java 渲染走 TamboUI markup（行级样式由库输出），无 SGR 直写面；R3 判定不变。 |
| **D258** | 终端标题：pi `setTitle` 过滤控制字符后写 OSC 0/2；Java `ExtensionUI.setTitle` 的 TUI 臂无窗口标题 API（W5 已登记空操作）、TamboUI 无对应导出面 ⇒ L7 只补「聊天 header 内 Model scope」，标题写入仍空操作。 |
| **D259** | 工具行计时的**流中臂**（`Elapsed X.Ys`，pi `render-utils.ts:146-164` 每 1 秒 `context.invalidate()` 重绘）无对位：Java 的逻辑行是一次性投影（`LogicalLine` 列表），没有逐行定时重绘面。终局臂逐字对齐且来源更硬——直接用持久化 `Message.ToolResultMessage.durationMs`（B234，pi `types.ts:635`，跨重载仍准），经 `ChatMessage.ToolResult` 渲染为结果行末的 `Took X.Ys`；`ToolDurations.timerText` 两臂仍按 pi 实现并各有测试。 |
| D220（扩面） | 本锚点又把 `keyText`/`keyHint` 加进 custom entry／custom message／工具渲染上下文／导出 tool-renderer 四处；Java 维持「键位提示由宿主 TUI 展示」，不移植。 |

## 5. 测试与验证门

1. 每包独立测试类，纯函数优先（`formatToolDuration`、分级排序、别名去重、`formatReadLineRange` 复用）。
2. 选择器页翻与 dialog 栈用组件级测试（驱动 `KeyStroke` / `openOverlay` 序列），不启真终端。
3. web 面用协议帧断言（`agentEventTranslator`/`WebDispatcher` 纯逻辑），不启 WebSocket。
4. 全仓 `mvn clean verify -Dfrontend.skip=true` 零错误零警告、SpotBugs 零告警；Checkstyle 对新文件零告警。
5. 闭环时：docs/06 L7 行翻「已闭环」、map/05 重算加权汇总（新读数）、docs/05 总表整表替换。

## 6. 风险

- **权重 3 的 R11 不会因 W1 变「对齐」**：Java 仍无 8 个逐工具渲染器（只有通用卡＋计时），W1 只把「缺失」里的可移植面补齐，判定需在 W1 完成后按「形状等价」重裁（预计仍为缺失或部分）。
- TamboUI 面（D255/D256）不可移植，L7 闭环后仍留 5 个存疑/缺失行，须在 map/05 汇总里如实反映。
- overlay 栈化触及 `OverlayManager` 与 `TuiExtensionUI.runDialog` 的共享状态，需回归 W5 扩展 UI 的 13 件示例测试。
