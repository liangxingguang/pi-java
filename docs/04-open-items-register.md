# 04 - 未结项与有效裁决

> 【背景摘要】本文档只保留当前未结项、待用户决定事项、已闭环批次的最终读数与仍有效的范围裁决。实施过程与旧账面沿革不在这里保存，历史在 git。
>
> 当前锚点为 pi `a00a5048d`（2026-11-24 P12 收口后切换，见 `docs/map/ANCHOR.md`）。下方条目均已随所属层重测；个别行仍引 `56cae7823`／`ea448f454` 作对照证据，冲突时以当前锚点为准。
>
> **历史设计稿引用**：现行保留的设计/计划稿——`docs/08-b161-codemode-mechanism-design.md`（B161 codemode 机制设计，**设计待审、尚未实施；已从原 `docs/61` 重编号**）、`docs/09-pi-latest-reanchor-plan.md`（pi 换锚与对齐计划，**2026-11-24 P12 收口后归档保留**）、`docs/10-schema-tool-validation-design.md`（P1 工具参数 pi-schema 校验设计），以及换锚实施期各批次的 L1–L8 系列设计稿（`docs/11`–`docs/30`）。其余旧批次设计/进度稿（原 `docs/06`、`docs/62`–`docs/66` 等）已删除。正文与代码注释中残留的 `docs/NN` 只是历史溯源标记，不构成有效链接。**现行结论以本文档 + `docs/map/` + `docs/05` 为准**，历史过程在 git。

## 0. 口径

- **B**：功能或行为缺口。已用到 **B293**（L6 #20 `context footer` 的 `SessionSnapshot` 上下文窗口字段仍缺，见 §4）；B289–B292 是 L1 收口四项，随 [`docs/11`](11-l1-ai-completion-design.md) 直接在代码里落地、未在 §1 立项，故无销号动作；L1 余项复验（strict 无类型节点判据、暖请求 `disableContinuation`）与第二次复验（Anthropic 非 strict 根 schema、`session-resources` 清理接线）同样直接落地、未新开号，见 [`14`](14-l1-residual-alignment-design.md)、[`15`](15-l1-residual-audit-followup-design.md)；B288 仍是 codemode 提案拟新增接缝，未批准；新工单从 **B294** 起。
- **D**：已登记的窄差。已用到 **D316**（L7 W1–W8 期间以 N／D-W* 记的窄差已由 P12 统一编号，D279–D316，见 §6.5）；新窄差从 **D317** 起，明细存于对应 `docs/map/`。
- **读数视图（2026-11-22 起）**：真源是各层地图 `docs/map/NN-*.md`；`docs/05` §1 是**发布读数**、§1.2 是**换锚过程（历史）**、`docs/09` §3 是**进度视图**，三者必须与真源同步改。设计稿（`docs/11`／`docs/12`…）只记设计与收口结论、**不发布读数**，其中的分数一律视为各自写作时的基线。
- **L1 #6 迁移（2026-11-22）**：缓存预热策略按 [`docs/16`](16-l1-cache-warming-policy-design.md) 迁入 `pi-java-ai`
  （`com.pijava.ai.api.CacheWarmingPolicy`），coding-agent 改为消费者；行为／数字／wire 不变，未新开 B／D 号，
  `map/02` 的 #6 改判对齐 ⇒ L1 **246/246＝100%**。
- **L3 P5 收口（2026-11-22）**：内置工具六行（find／grep／read／edit／bash／截断）按
  [`docs/17`](17-l3-tool-semantics-design.md) 落地新锚点语义（含 powershell 5.1 退出码后缀）；行为不变量
  保持工具名／参数集／截断预算／临时文件机制，未新开 B／D 号，D1–D8 原样保留（D5/D8 的方言部分收窄），
  `map/03` 六行改判对齐 ⇒ L3 **178/178＝100%**。
- **L4 复核（2026-11-22）**：存储后端 20 行按目标锚点 `a00a5048d` 复核（[`docs/18`](18-l4-session-recheck-design.md)）：
  L4-R1 `buildSessionInfo` EOF 后补检查（#11956）、L4-R2 列表主体外层 catch、L4-R3 候选 stat 重试；
  行为不变量（v3 线形／文件命名／惰性创建／fork 语义）不变，未新开 B／D 号，`map/06` 20 行判定不变
  ⇒ L4 **44/44＝100%**。`session-cwd` 的 Continue/Fork/Cancel 与 `session-picker` 的 `flushStdout`
  按归属留给 L5／L7，不影响 L4 读数。
- **L5 收口（2026-11-23）**：coding-agent 按 [`docs/19`](19-l5-coding-agent-design.md) W1–W11 落地——13 个重开单元
  恢复「对齐」，9 个新单元落地（llama.cpp 默认端口按 F1／R2 出分母）；`map/04` 记 L5 **461/461＝100%**。
  行为不变量保持（工具名/参数集、slash 命令集、RPC 33 命令、session JSONL 线形、扩展注册面不变，无新模块）。
  新窄差 D274（重复信号强退）、D275（`/export` OSC8）、D277（会话内 cwd 三臂只走非交互错误臂）、
  D278（capture 输出无 TUI 安装触发点）；未新开 B 号。
- **L6 复核（2026-11-23）**：评测域 24 行按目标锚点 `a00a5048d` 复核（[`docs/20`](20-l6-evals-recheck-design.md)）：
  两锚点间 `packages/evals` 仅 `package.json` 变化（版本 1.4.0→1.6.0、依赖同步、`clean` 脚本 shx→node），
  `src`／`evals/`／`test/` 逐字节未变 ⇒ **零代码**、24 行判定与 pi 证据全部续用，未新开 B／D 号；
  唯一存疑 #20（TUI 审计 oracle 形态差）维持，收口条件＝L7 落地 context footer 状态行
  ⇒ L6 **48.0/49＝98.0%**（复核读数，投影转正式）。L8 examples 已于 2026-11-24 随 P11 收口（见下条）。
- **L7 对账（2026-11-23）**：UI 层按 [`docs/21`](21-l7-recheck-plan.md) 完成复核对账（不写码）——
  docs/09 十行全部成立（4 子机制补记）；预审计 12 项成立（主体在 `packages/tui`）＋漏项 11 项登记；
  旧基线 10 行复核：S6/S19/S25/T3/T4/T5/T7/T10/W14 翻正「对齐」，S16 降「存疑」（query 门控缺失）
  ⇒ 旧锚点正式读数修正 **178.5/244＝73.2%**。无法闭环预判：C21/R30（TamboUI 外部框架不可达）、
  K40/K12（JVM 无 signal API）、R33（性能契约）按窄差 D 处置（正式编号在分段设计时落）。
  分段实施 W1（状态面）→W8（收尾）按 docs/21 §7 推进。
- **L7 W1 收口（2026-11-23）**：状态面按 [`docs/22`](22-w1-status-design.md) 落地——C5 loader/spinner
  （`LoaderSpinner` 时间驱动帧计算＋指示器渲染 span 组）、C6 working 指示器（`StatusIndicator.Working`＋
  TurnStart 置/AgentEnd 清＋扩展 `setWorkingMessage/Visible/Indicator` TUI 臂＋reset 的
  `"Working (esc to interrupt)"` quirk 逐字）、C7 bordered loader（`BorderedLoaderFrame`，消费点
  bug-report/session-share）、C8 cancellable loader（Esc 取消语义收编）⇒ 四行缺失→对齐；
  C10 文案复核成立。静态 `● running` 删除。窄差 N1（embedded editor indicator）、N2（IdleStatus/
  clearOnShrink）登记待正式编号。未新开 B 号；`map/05` 读数 **186.5/244＝76.4%**（投影回算 190.0/259）。
- **L7 W2 收口（2026-11-23）**：工具/消息卡按 [`docs/23`](23-w2-design.md) 落地——R10 diff 渲染
  （`DiffView` 重写：parseDiffLine 逐字＋1:1 词级 `WordDiff`＋`[reversed]` 反白＋公共缩进剥离＝漏项#5）、
  R17/R18/R19 三消息卡（`CompactionCard`/`BranchCard`/`SkillCard`＋`SkillBlocks.parse`＋collapsed/expanded
  文案逐字＋SUMMARY/CUSTOM 变换＋messageId/timestamp＝漏项#6）、R26 `FormatToolArgs`（collapsed 100 截/
  expanded key: value＋JSON 缩进）、R25 复核翻正（`ReasoningDropNotice` 实现已接线，零代码）、
  K37/C14 点击展开（`ChatViewportElement` 行区间＋`DragHandler.onDragEnd` 拖拽通道等价＝窄差 N）翻对齐；
  R12 工具卡三态 bg＋fallback 折叠 10 行、R13 bash 卡（漏项#7：`BashCard`＋`MessageCards.bash`）缺失→存疑
  （流式通道 D197/`BashExecutionUpdate` 无消费面维持）。窄差：customMessageBg 不铺底（R17–R19）、
  bg 只铺文本格（R12）、skill collapsed label 色压平。未新开 B 号；`map/05` 读数 **196.0/244＝80.3%**
  （投影回算 199.5/259＝77.0%）。
- **L7 W3 收口（2026-11-23）**：渲染缓存契约按 [`docs/24`](24-w3-design.md) 落地——新增锚点单元
  R30(2)/R31(2)/R32(1)/R33(1)/C21(1)；R31 `MarkdownRenderCache`（源词法缓存＋顶层块 record 复用＋
  帧界裁剪＋主题/默认样式失配＝pi `blocks`/`invalidate` 对位）接线 `ChatViewportElement.flatten` 与
  `MessageBubble` 三个 markdown 臂、R32 `MarkdownBlocks.stripHtmlComments`（围栏/行内代码感知、注释独占行
  清空合并空行、两侧空白吞后侧）⇒ 两行**对齐**（＋3.0）；R30 全屏帧缓存、R33 ASCII/字素快路径、
  C21 render 只读/组件缓存按 docs/21 §6 登记窄差 D（TamboUI 外部框架/无 Segmenter 性能契约，不进分子）。
  窄差 N：三消息卡内部 Markdown 仍走静态路径、图片硬换行无观察面（R14）。以上 D/N 编号统一在 P12 落。
  未新开 B 号；`map/05` 读数
  **199.0/251＝79.3%**（未加权 101/152；投影回算 202.5/259＝78.2%）。
- **L7 W4 收口（2026-11-23）**：补全与选择器按 [`docs/25`](25-w4-design.md) 落地——S16 config 选择器补
  「query 未变不重过滤」（pi `config-selector.ts:504-508`；同族 thinking/scoped/settings 核查无同形缺陷）、
  K25 `@文件`/路径补全（`FileCompleter`＋`FileCompletionPaths`：前缀提取/打分/引号形/Tab 强制；
  fd→NIO 有界遍历＝窄差 N）、K26 大粘贴折叠（`EditorPaste`：>10 行/>1000 字符标记、提交展开、
  撤销快照携带注册表、整标记退格与前向删、高位下移重编号）、K31 jump mode（`EditorJump`：
  `Ctrl+]`/`Ctrl+Alt+]`、多行区分大小写）、新增 K41(1) 斜杠命令 Enter 上下文
  （`ChatInputPortal.enterWithCompletion()`：slash 名/无补全 ⇒ 提交；文件补全只应用）、C2 选择列表复核
  （确认 pi 无多选/分组，原注误记；补 ↑↓ wrap＋滚动 `(i/n)`＋无匹配文案）⇒ 六行**对齐**（＋6.5，分母 +1）；
  extension-selector 补 10 行居中窗口＋`(i/n)`（漏项#4）。窄差 N：K25 fd/普通路径自然触发、
  K26 词删/行删原子性与 tmux CSI-u、K31 键位不读 `keybindings.json`（K17 另账）、C2 描述列。
  未新开 B 号；`map/05` 读数 **205.5/252＝81.5%**（未加权 107/153；投影回算 209.0/260＝80.4%）。
- **L7 W5 收口（2026-11-24）**：平台/协议按 [`docs/26`](26-w5-design.md) 落地——K35 终端能力探测
  （`TerminalCapabilities`：tmux/screen/Herdr/Rex/Orca/Kitty/Ghostty/WezTerm/Warp/iTerm2/WT/Alacritty/
  vscode/zed/JetBrains/Windows/unknown 逐臂＋`PI_*` 覆盖；`SettingsScreen` 图片门、`PiTuiLauncher`
  truecolor 同源）、K34 原生剪贴板（`NativeClipboard`：Termux/pbcopy/PowerShell+clip/wl-copy/xclip/xsel
  ⇒ AWT 回退，2 s 超时；`/copy`/`ClipboardSupport` 同源）、K39 OSC 7501（`ProgramStatusProtocol`＋
  `ProgramStatusReporter`：turn/消息/压缩/settled 状态机、blocked 来源栈、去重、退出 clear；
  `extension-dialog`/`extension-overlay:N`＋`custom().blocked`）、K32 `modifyOtherKeys`＋CSI-u 备用键组
  ＋`SHIFTED_SYMBOLS` 逻辑 Shift 归一、K38 复核（设置行/accessor 1–100/"auto"/`ScrollConfig` 消费早具备）
  ⇒ 五行**对齐**（＋5.0，未新增分母）。窄差 N：N1 OSC 7501 无查询/DA 握手（TamboUI 独占 stdin）、
  N2 OSC 8 不输出、N3 `baseLayoutKey` 不消费、N4 helper→平台 CLI、N5 登录 auth、N6 APC 标记无对应物；
  D60 `"auto"` 维持 Codex 有界加速模型。以上 D/N 编号统一在 P12 落。未新开 B 号；`map/05` 读数
  **210.5/252＝83.5%**（未加权 112/153；投影回算 214.0/260＝82.3%）。
- **L7 W6 收口（2026-11-24）**：全屏长尾按 [`docs/27`](27-w6-design.md) 落地——S2a/K21 转录搜索
  （`TranscriptSearchIndex`＋`TranscriptSearchController`＋三行搜索条；`ctrl+shift+f`／Enter／`ctrl+g`／
  Shift+Enter／`ctrl+shift+g`／Esc）、S2b/K22 拖拽选区＋copy-on-select（`ViewportInteraction`＋
  `TranscriptSelection`，W5 `ClipboardSupport` 复制，`ctrl+c` 有选区不清输入）、S2c flash（`FlashNotice`，
  默认 1000 ms、复制失败 5000 ms）、C3 设置列表宽度感知（`SettingsListElement`：标签对齐≤36、值截断、
  描述折行、`(i/n)`、滚轮/点击）、C4 单行输入（`SingleLineInput`＋`SingleLineInputElement`，搜索条消费）、
  C13 `TruncatedText`＋`TextColumns`、C15 `DynamicBorder`＋`ThinkingBorderPalette`（编辑器上下横线随
  思考等级）、C20 `EditorElement` 反白假光标与 `showHardwareCursor` 互斥、K18 `keyHint/rawKeyHint`
  ⇒ 十一行**对齐**（＋12.0，未新增分母）。窄差 N：N1 搜索条位置（顶部条代替浮动 overlay）、N2 非 ASCII
  码点粒度、N3 选择长尾（双击选词/边缘自动滚动）、N4 flash tick 计时/单条、N5 无 APC 光标标记、
  N6 子菜单 `navigateTo`、N7 提示返回 `Element`；D2 bash-mode 边框（Java 无 bash 输入模式态）。
  #20（L6 context footer）顺延 W8（`SessionSnapshot` 无上下文窗口字段，改 record 不属 W6）。以上 D/N
  编号统一在 P12 落。未新开 B 号；`map/05` 读数 **222.5/252＝88.3%**（未加权 123/153；投影回算
  226.0/260＝86.9%）。
- **L7 W7 收口（2026-11-24）**：屏幕与图按 [`docs/28`](28-w7-design.md) 落地——R14/S2d 图片协议
  （`TerminalImage` 编码/尺寸/单元格/降级＋`ImageElement`（`RawOutputCapable`）＋`ImageRenderSupport`
  视口装配，`showImages`/能力双门控）、R15/K14 剪贴板图片（`ClipboardImage`＋`ClipboardPasteFlow`，
  Windows PowerShell/wl-paste/xclip → 临时 png → 路径插编辑器，文本兜底）、S18 首次设置向导
  （`FirstRunSetup`＋`FirstTimeSetupScreen`＋`FirstTimeSetupRunner`，`PI_EXPERIMENTAL=1` 且无
  settings.json）、S21 show-images（设置布尔项已对位，独立导出组件不移植）、K13 `Ctrl+X`
  （`MESSAGE_COPY` 全局＋`copyLastAssistantMessage`）、R8 LaTeX（`util/latex/` 符号/解析/布局/扫描，
  行内与块级接入 Markdown）⇒ 八行**对齐**（＋8.0）。窄差 N：N-W7-1 图片实机验收、N-W7-2 macOS
  Finder 路径、N-W7-3 主题预览不重查 system 色、N-W7-4 LaTeX 符号长尾；D-W7-1 Mermaid
  （`lovely-mermaid` 无 Java 等价）、D-W7-2 独立 show-images 组件。以上 D/N 编号统一在 P12 落。
  未新开 B 号；`map/05` 读数 **230.5/252＝91.5%**（未加权 131/153；投影回算 234.0/260＝90.0%）。
- **L7 W8 收口（2026-11-24）**：收尾段按 [`docs/29`](29-w8-design.md) 落地——K17
  `keybindings.json` 用户覆盖（`KeybindingsManager.create/reload`＋`KeybindingsNames` 迁移表；
  跳过 `$schema`、字符串/数组值、空数组解绑、新旧同存新名优先）缺失→**对齐**；S27 `/share`
  复核（`SessionShareService` 的 `destination` 现读＋`Stage`、`ShareFlow` 的 W1 loader）存疑→**对齐**
  ⇒ 两行翻正（＋2.0）。同段对账：missing-cwd 三选／reasoning drop 泛化／changelog 写回／
  model-selector 刷新保偏好均已在 W2–W5 落地（零代码）；"to resume" 独立成行＋exit flush、
  伙伴 provider `✦` 标记落地。窄差：N-W8-1（品牌 shimmer/glow 动画，`SelectList` 纯字符串行不可达）、
  N-W8-2（`tui.editor.*` 等编辑器直读键不消费覆盖）；D-W8-2（R22/R27 彩蛋不移植）、
  D-W8-3（`/export` HTML 输出形状未逐项核）、D-W8-4（R28 流式 bash/工具组件不进 pending 区）。
  **#20（L6 context footer）仍开放**：`SessionSnapshot` 无上下文窗口字段，未在本段改 record。
  以上 D/N 编号统一在 P12 落。未新开 B 号；`map/05` 读数 **232.5/252＝92.3%**（未加权 133/153；
  投影回算 236.0/260＝90.8%）。**L7 W1–W8 分段收口。**
- **L8 examples 收口（2026-11-24，[`docs/30`](30-l8-examples-design.md)）**：新锚点分母内被重写的两件已补齐——
  plan-mode（thinking 块、最后一个 `Plan:` 头、整行编号、`[DONE 1]`／`Done: 1`／`Step N done`、`allStepsComplete` 回落、
  widget 60 截断、`deliverAs: nextTurn`）与 subagent（状态机、启动前中止、失败证据、
  `truncateParallelOutput`／`truncateModelOutput`、`chain` 参数面、unknown agent、project agent 确认、`tool_result` isError）。
  D235（主题/删除线文本降级）与 D244（注入式 runner、TUI 渲染不移植）原样保留 ⇒ L8 **88/88＝100%**；
  模块 412 测 0 失败、Checkstyle 0 error。
- **P12 收口（2026-11-24）**：全仓 `mvn clean verify -Dfrontend.skip=true` 零失败、零告警；L7 W1–W8 期间以 N／D-W* 记的窄差统一编号 **D279–D316**（§6.5），B238③（OSC 7501）／B238④（假光标）随 W5／W6 落地销号；L6 唯一存疑 #20 立 **B293**。发布读数 **1,368.5/1,389＝98.5%**（L0–L7），L8 按件 **88/88**；`CURRENT_PI_PIN` 切至 `a00a5048d`。`docs/09` 归档保留，不删除。
- **F**：需要用户拍板的事项。
- **R1**：pi 已删除的面，不为旧参照物补功能。
- **R2**：Provider 只覆盖已批准的主流范围。Qwen／Xiaomi Token Plan 家族（团队／个人、国际／中国、AMS／SGP）已按 `docs/09` P3 余项纳入（26 个内置 provider）；其余长尾 provider 仍排除。
- **R5**：codemode、durable、chord、pico3、micro 等面外子系统不做对齐、不进分母。
- **模型可见面**：模型能读到的提示、工具声明、参数、结果和错误文本，优先逐字对齐。

## 1. 待用户拍板

| 编号 | 问题 | 待决定事项 |
|---|---|---|
| B58 | 固定 pi 与当前 pi-java 的 OpenAI 默认 wire 均已是 `openai-responses`；旧“Java 默认 completions”的描述已过期 | 是否确认按现状对齐并销号，还是登记刻意偏差。AI 层设计时单独提交裁决，不擅自修改。 |

Chord plugin 已按 R5 决定不做，不再是待拍板项。

### codemode 机制提案（尚未批准、尚未实施；R5 现状仍是「不做、不进分母」）

完整设计保留在 `docs/08-b161-codemode-mechanism-design.md`（设计待审、未实施），下列 F1–F5 是其待拍板摘要。

| 编号 | 问题 | 建议与依据 |
|---|---|---|
| F1 | R5 是否改为「实现 codemode **能力版**、仍不进对齐分母」？ | 建议同意。模型可见语言保持 JavaScript；B161（机制）与 B177（MCP 默认激活）随之重开。 |
| F2 | 引擎选型 | 建议 **Endive 1.1.0 + quickjs-wasi** 优先（2026-11-20 实测运行时全能力可用，压缩体积仅 +0.95 MB；1.2.0 有编译器 bug 故锁 1.1.0），**GraalJS 兜底**（+64 MB、零 ABI 绑定），Chicory 备选；若 Java 绑定超预算回落 GraalJS。 |
| F3 | 是否批准新接缝 `B288 AgentTool.prepareLoadout(ToolLoadout)` 对位 `mode`/内联目录？ | 建议批准；否则降级并另登新窄差。原稿曾预占编号 D264–D272，该批编号已被 L7 正式占用，若采纳必须重新分配。 |
| F4 | `models` 命名空间 v1 是否直接不做？ | 建议不做：classifier 无对位；**provider 级图像生成已具备**（`OpenRouterImagesApi`／`OpenAIImagesApi`），但脚本面按 R5 不暴露，调用报 `models is not defined`。 |
| F5 | web 模式是否默认允许 codemode？ | 建议与 CLI 同策略：本地单用户默认可用、可关闭；多租户/远程默认关闭或改用进程后端。 |

## 2. 主流功能与协议开放项

| 编号 | 当前结论 | 所属层 |
|---|---|---|
| B116 | 对外 JSON usage 已改为 pi 扁平形状；Java 内部 `StreamDone` 嵌套方言**已由 AI 重测裁决保留**（`docs/map/02-ai.md:238`），出口由 `JsonEventMapper` 展平；新增投影不得把内部方言直接序列化。 | L1 AI |
| B156 | `ModelCompat` 逐组件重建存在漏字段风险；新增组件时必须同时检查构造点和 `withPartial` 类重建点。 | L1 AI |
| E5-31 | 扩展 UI 仍有 working 指示器、终端输入、补全、编辑器替换等 TUI 成员未收口；与 D′ 旧行去重重测。 | L7 TUI |
| B287 | 文件工具 operations 接缝未对位（B286 的姊妹缺口）：`read`/`write` 走 `ToolContext.fs()` 但工厂不可注入，`edit` 混用 NIO 与 `fs`，`grep`/`find`/`ls` 直接 NIO ⇒ Docker/SSH 类文件后端无法只替换文件工具。范围与 pi `ReadOperations`/`WriteOperations`/`EditOperations` 对位（D243 同族）。**后续路线（用户 2026-11-20 决定）**：先补本项，再写 SSH 扩展提供远程 bash + 文件工具；不迁移 chord/env/durable，详细设计后续完善。 | L3 agent-core |

已闭环批次的账面与台账结论见 `docs/05` §1、`docs/07-examples-alignment.md` 与 `docs/map/`；实施过程按维护规则第 1 条不在此保存，历史在 git。

## 3. MCP 开放项与复核项

以下条目已在 L2 按当时的换锚目标（`56cae7823`，现为 `REFERENCE_PI_PIN`）重测。开放项与「已判对齐但必须持续保持」的回归约束分列；B210/B211/B216 已判对齐（`docs/map/07-mcp.md:139-140,170`），按维护规则第 3 条从开放表移除，历史在 git。

**仍需动作／补测**

| 编号 | 当前待复核结论 |
|---|---|
| B220 | 尾部裁剪字符集与 pi 不同，窄角空格等边界需补逐字测试（`docs/map/07-mcp.md:128` 仅覆盖 stderr tail 对齐）。 |

**持续约束（已判对齐，改动对应代码时必须回归）**

| 编号 | 当前结论 |
|---|---|
| B185 | `OAuthMetadataParsers.optionalBoolean` 的 SpotBugs 排除仍需保留或有代码级替代（`spotbugs-exclude.xml:22-31`）。 |
| B205 | MCP 的 AgentTool 适配位于 coding-agent，这是模块依赖方向决定的现状，不强行搬入 mcp 模块（`docs/map/04-coding-agent.md:550`）。 |
| B208 | `ImageContent(mediaType, data)` 与 MCP 转换链的参数序不同，必须维持换序点测试（`docs/map/07-mcp.md:112`）。 |
| B219 | `mcp_servers` 排序使用 `String.compareTo`，非 ASCII 名称可能与 `localeCompare` 不同（`docs/map/07-mcp.md:49`）。 |

**跨层转出（docs/22，2026-11-04 用户裁决）**：以下条目不属 MCP 层，保留在 `map/07` 供追溯但权重记 0、不进 MCP 分母；归属由各层重测时确认。

- `#5/B182` → L5 配置子系统（`piConfig.configDir`）
- `#6/B183` → 共享 JSON 解析方言（V8 与 Jackson 错误正文）
- `#27/B190` → 平台异常分类方言（`TypeError` 与 `IOException`）
- `#30/B191/B197` → 平台 URL 归一（WHATWG 与 `java.net.URI`）
- `#38/B193` → 共享 JSON 序列化方言（键序／显式 `null`）
- `#40/B198` → L1 ai 共享 `OAuthPage` 模板
- `#41/B212` → JVM 平台方言（`isatty` 无对位）
- `#43/B192` → OAuth 身份托管基础设施（pi.dev 域名）
- `#71/B213` → 全仓品牌改名方言（`pi-java`／`.pi-java`）

## 4. TUI 开放项

TUI 已在 L7 按新锚点重测；下表只列仍未收口的面。

| 编号 | 当前缺口 |
|---|---|
| B38 | compaction 后缺少整表重建和完整压缩摘要消息卡。 |
| B39 | 压缩窗口的 Esc 行为尚未完成换绑。 |
| B46 | Bash `onUpdate` 已有生产链和 web 消费，TUI 实时输出消费者仍缺。 |
| B145 | 启动、登录后等定向模型目录刷新点未全部接线（登录后已落，其余启动点证据不足）。 |
| B293 | L6 #20 `context footer` oracle：`SessionSnapshot` 无上下文窗口字段，L7 context footer 状态行未落地（L6 唯一存疑的收口条件）。 |

~~B238③（OSC 7501）／B238④（APC 假光标）~~ 随 L7 W5（`ProgramStatusProtocol`）与 W6（`EditorElement` 反白假光标）落地销号；剩余协议窄差见 D293／D298。

R29 安装变更重启警告维持不做；若新的 `selfUpdateCommand` 形成真实安装变更通道，再重新裁决。

## 5. 面外与删除面结论

### 5.1 R5：不做且不进分母

- codemode 沙箱与工具，包含 B161 和 MCP 默认 codemode 激活 B177。
- durable、chord、pico3、micro。
- 新锚点 telemetry 的 links/propagation、TelemetryStore 与 renderTrace（map/01 T18–T20）：T18 生产消费者为 Chord＋Durable，T19 为 Chord，T20 未见主流生产消费者，均属既定 R5 面；AI 所需 carrier/exemplar 不在此排除项内。主流消费者出现时原位重开。
- 分类器及统一模型设施，除非未来进入不依赖 codemode 的主流路径。
- R2 范围外的长尾 Provider 和对应鉴权。
- `streamProxy`，除非 pi-java 增加服务端托管鉴权代理车道。

共享主流行为不能随 R5 跳过。例如 tool_search、MCP 数据形状和 OAuth 仍按消费链对齐。扩展/API 可达本身也不等于 R5；AI #33/#93/#100 已在 L1 回到分母。

### 5.2 R1：不为已删除参照物补功能

- pi 已删除的 experimental harness、session-backends、旧 server/client/plugin 发布面。
- `PendingEntry`、`InboxItem` 等已删除实验类型。
- 其他删除项必须给 pi 删除提交或新锚点缺席证据后，才允许出分母。

SQLite 后端是 pi-java 独立实现，保留模块，不对齐已删除 schema。

## 6. 仍有效的实现边界

> D 编号是**不可消除的窄差总账**，不是实施过程；统一记为「编号 → 一句当前边界 → 证据指针」。完整源码取证在对应 `docs/map/`，编号不复用，新差异从 **D274** 起。

### 6.1 AI / 协议 / 会话与 CLI（证据：`docs/map/02-ai.md`、`docs/map/03-agent-core.md`、`docs/map/04-coding-agent.md`）

| 编号 | 当前边界 | 证据 |
|---|---|---|
| B18（已销号） | pi 锚点已有同一 `POST /messages` SSE 协议；旧「pi-java 自有 wire」描述失效，细差归 #110 | map/02 |
| D201 | telemetry 只保留运行时身份/父绑定/元数据合并；TS 条件类型的编译期约束不复刻 | map/02 |
| D202 | malformed 工具参数沿用 Jackson 前缀，pi 文案逐字保留 | map/02 |
| D203（已关闭） | `pi.ai.poll` 已有生产入口（deferred + Faux）；其他 provider 保持「方法不存在」 | map/02:238 |
| D204 | identity 合并落在 `DefaultProviders`／`ConfigurableProvider`，请求头行为对齐 | map/02 |
| D205 | `User-Agent` 品牌/运行时 token 不同，归因头仍用 pi 品牌值（D77 族工具自述差异） | map/02 |
| D206 | `StreamError.Throwable` 不得进序列化投影，一律走 `textOf` | map/02 |
| D207 | `Model.input/reasoning` → `ModelCapability`；未知模型按支持图像输入（Java 独有状态） | map/02 |
| D208 | `ToolResultMessage.details/usage` 用 `Object`，避免 ai 反向依赖 agent，保持 JSON 兼容 | map/02 |
| D209 | 静态协议注册表按 `api` 派发，不能加载 JS api 模块 | map/02 |
| D210 | 无动态模块加载；setup 失败同步抛错，deferred 懒包装无对位 | map/02 |
| D211 | `PiMessagesEvent` 是 Java record 方言；扩到请求/响应外壳须复核 #7/#63 | map/02 |
| D212 | `DeferredHandle.data` 用 `Map` 承载 `JsonValue`；顶层数组/标量需复核，纵贯测试已补 | map/02 |
| D213 | `contentIndex` 缺键严格拒绝（Jackson 填 `null` 会误判合法下标） | map/02 |
| D214 | Faux 工厂仅同步返回；真异步夹具出现时复核 | map/02 |
| D215 | Faux usage 文本只投影 Tool 四字段；差异仅影响测试 token 数字 | map/02 |
| D216 | 无 `AbortSignal` 通道；deferred 不移植块间中止，终局可显式 `ABORTED` | map/02 |
| D218 | 分支用 Java lane 表达 pi 单 leaf 树；`lane` 是 pi 忽略的内部键（D190），行为由 `TreeCompaction` 对齐 | map/06 |
| D219 | 工具激活序取注册插入序（pi 用 runner `activationOrder`）；loadout 由注册序＋`reachesTools` 决定 | map/04 |
| D220 | `KeybindingHints` 不进扩展渲染 API（D92 文本级方言），键位提示由宿主 TUI 展示；`blocked` 随 E5-31 归 L7 | map/04 |
| D221 | `selfUpdateCommand` 的 `{version}`：pi 为目标版本、Java 为当前版本，配置命令自行处理「已最新」 | map/04 |
| D222 | npm 特有 CLI 行为由 HTTP registry 臂等价实现；批装改为逐包顺装（D113），「版本没变」消息逐字对齐 | map/04 |
| D223 | 无 lockfile 目录层，`auth.json` 用 `FileChannel.lock()`，EPERM 重试分支不适用 | map/07 |
| D224 | tool_search 用 JDK `BreakIterator`（UAX#29）替代 `Intl.Segmenter`；仅无空格连写脚本不同 | map/04 |
| D225 | HTML 导出自带 CJK 强调放宽，不引入 `marked-cjk-friendly`；判定以渲染结果为准 | map/05 |
| D226 | macOS 进程组 `SIGSTOP` 先手不移植；`destroyForcibly()`＋`descendants()` 后序遍历 | map/04 |
| D227 | 模型解析多两条 Java 臂：运行时注册 provider 的自定义 id、`provider/id` 前缀回落；裸未知模型仍报错 | map/04 |

### 6.2 评测（证据：`docs/map/06-session-evals-chord.md`）

| 编号 | 当前边界 | 证据 |
|---|---|---|
| D182 | Docker 真实构建/执行不进 CI；假进程钉住命令契约与 JUnit XML 读取，两锚点 docker 源逐字节相同 | map/06 |
| D183 | TUI 文档审计用文档问答＋Levenshtein 0.75 替代 pi 的 context-footer oracle；判分核心未变，维持存疑 | map/06 |
| D184 | 扩展作者 eval 额外允许 `bash`（JAR 需编译），其余 docs eval 仍只用六件 | map/06 |
| D185 | eval 用静态注册表发现、Docker 报告读 JUnit XML；字段/语义对齐，文本不逐字 | map/06 |
| D186 | provider/model eval 用内联探针代写 `models.json`（Java read/write 限 workspace） | map/06 |
| D187 | 扩展作者 eval 经 `sessionCustomizer`＋内联 `install_extension` 装载 JAR（pi 用 `/reload` 扫描目录，D90 裁决③） | map/06 |
| D188 | `SmokeEval` 仍会被 CLI 全量 lift 配对；`--audit` 使 `--host` 只跑非审计套件 | map/06 |
| D189 | `RecordingFooterOracle` 自复刻 footer 文本，宽度按每 codepoint 一列近似（无宽字符两列） | map/06 |

### 6.3 评测产物 / 范围裁决（证据：`docs/map/06-session-evals-chord.md`、`docs/07`）

| 编号 | 当前边界 | 证据 |
|---|---|---|
| D228 | `report.json` 升 `schemaVersion=3`：per-eval-set `comparisons`＋变体 `operationalTotals`，lift 用 pass-rate 差；旧产物不互读 | map/06 |
| D229 | 每臂只写单 `<testcase>`，`readTaskObservation` 的 fullName 预过滤无对应场景，落产物等价 | map/06 |
| D230 | 审计只读种入 workspace 的实现源子集（`AuditSourceIndex` 按页面选源），判分口径不变 | map/06 |
| D231 | Java 独有 `pi.java.agentDir` 属性把 agent 目录指向隔离根；项目配置目录为 `.pi-java` | map/06 |
| D232 | examples 8 个外部运行时文件出分母（沙箱/micro-VM/WASM/JS 解析无对位），不写降级假移植 | docs/07 |
| D233 | `ModelRegistry.findOfType`/`classify` 无 classifier 面；jev-router 用显式模型 id | map/04 |
| D234 | `NewSessionOptions.setup` 不提供（零消费且与只读门面冲突）；只落 `parentSession`/`withSession` | map/04 |
| D237 | examples 另有 5 件范围面出分母（overlay〔D99 裁决不移植〕/game/mac 主题）；分母 93→88，ExtensionUI 槽位仍由余件覆盖 | docs/07 |

### 6.4 examples / 扩展降级（证据：`docs/map/04-coding-agent.md`、`docs/07`）

| 编号 | 当前边界 | 证据 |
|---|---|---|
| D235/D238/D250/D254 | TUI 颜色/边框/动画/ANSI 绘制统一降级为纯文本或文本前缀（D92/D100 同族）；questionnaire 逐题答案从内容行前缀复原 | map/04 |
| D236/D239/D245/D246 | loader、通知与定时器用同步/注入式方言（`TickDriver`/`FileSource`），三结局与中止语义保持 | map/04 |
| D240 | `ShellExecutor` 返回完整 `ShellResult`，外部后端自填截断/行列计数；`spawnHook` 无对位，改写 `tool_call.input` | map/04 |
| D241/D242/D253 | 工具渲染器/补全/参数补全收敛为纯文本 `ToolCallRenderer` 与空接口（D92 同族）；建议内容不入键盘面 | map/04 |
| D243 | 文件工具 Operations 未注入（B287）；ssh 只作同名注册＋本地执行＋远程命令纯函数演示 | map/04 |
| D244 | subagent 用注入式假 runner 验 single/parallel/chain，不真起子进程 | map/04 |
| D247/D248/D251/D252 | SDK/RPC/runtime 入口名差，用等价投影＋纯函数断言；Java `RpcExtensionUI.setTitle` 是行为增强差 | map/04 |
| D249 | `dynamic.json` theme 仅作测试资源，断言键数与字段存在，无结构化类型 | map/04 |

### 6.4a L5 收口新增窄差（证据：`docs/map/04-coding-agent.md`）

| 编号 | 当前边界 | 证据 |
|---|---|---|
| D274 | 首次 SIGTERM/SIGHUP 的会话清理已做；Java 无公开信号 API、`Runtime.addShutdownHook` 不能区分重复信号 ⇒ pi「第二次信号立即强退」不移植 | map/04 |
| D275 | `/export` HTML 工具文本中的 OSC 8 超链接不转 `<a>`（Java 导出无 ANSI/OSC 层，保持纯 escape 文本） | map/04 |
| D277 | 会话内 `/resume` `/import` 命中 cwd 缺失时只走非交互错误臂；交互式 Continue/Fork/Cancel 仅在启动期三臂提供（TUI 需重启） | map/04 |
| D278 | capture 输出模式（非交互 git env、尾部 8000 字符、凭据提示）已就绪，但 TUI 无安装触发点，仅 CLI／包命令路径消费 | map/04 |

### 6.5 TUI / Web（证据：`docs/map/05-tui-web.md`）

| 编号 | 当前边界 | 证据 |
|---|---|---|
| D255/D256/D257 | TamboUI 外部库边界：Kitty 键解析、框架层整块面（全屏搜索/选区/flash/图像/OSC）、行级 markup 无 SGR 直写；K5/K32/R3 维持存疑 | map/05 |
| D259 | 工具行计时流中臂（每秒重绘 `Elapsed`）无对位；终局臂取持久化 `durationMs`，逐字对齐且跨重载仍准 | map/05 |
| D260/D261/D262/D263 | 渲染器文本级窄差（D92 同族）：read 分类行不移植、折叠按逻辑行、截断警告不重复产出、edit 不读盘 preview（diff 仍上色） | map/05 |
| D264/D265/D266 | 列表：别名与整行同色（D92）、`capturesViewportInput` 只给补全弹层一个门（D99/D256）、分页按「项」不按可视行 | map/05 |
| D267/D268/D269/D270 | 排序用 `compareTo` 求确定性、消息文本每次按键重算、路径键 UNC/短名边角不保证逐字、`FuzzyMatch` 无命中下标 | map/05 |
| D271 | `--session <id>` 保留跨项目 `find()` 阶梯（pi 仅项目内精确查找）；项目内命中行为一致 | map/05 |
| D272 | 扩展对话框 `blocked` 无状态行/输入拦截消费面；栈化生命周期已对齐 | map/05 |
| D273 | 技能 collision 行尾 ` (source)` 不移植；`--skills` 静态发现的诊断仍未上抛 | map/05 |

**L7 W1–W8 窄差统一编号（P12 落定，D279–D316）**：以下条目在分段收口时以 N／D-W* 临时记法登记于 `docs/22`–`docs/29` 与 `map/05`，现统一并入 D 账；原临时记法作废。

| 编号 | 当前边界 | 证据 |
|---|---|---|
| D279 | 无 embedded editor 挂点：指示器恒走状态槽，边框色不随 thinking level | map/05（W1） |
| D280 | IdleStatus／clearOnShrink 不移植（无对应状态面） | map/05（W1） |
| D281 | `customMessageBg` 全卡底色不铺底（R17/R18/R19 以 label 色承担区分） | map/05（W2） |
| D282 | skill 折叠行 label 色压平：markup 无自定义色标签，整行 text 色＋bold 标签 | map/05（W2） |
| D283 | 卡背景只铺文本格：文本级渲染无 bg 盒模型（R12 三态 bg） | map/05（W2） |
| D284 | 三消息卡内部 Markdown 仍走静态路径；`WeakRef` 以帧界裁剪替代 | map/05（W3） |
| D285 | 多行内联图片硬换行无观察面：`Block.Image` 恒单行占位 | map/05（W3） |
| D286 | R30 全屏滚动帧 O(viewport) 不可达：TamboUI 布局帧缓存无应用层接缝（不进分子） | map/05（W3） |
| D287 | C21 render 只读/缓存契约：TamboUI 组件协议不可改，应用层以 R31 投影缓存承担 | map/05（W3） |
| D288 | R33 ASCII/字素快路径：Java 无 `Intl.Segmenter` 等价性能诉求（纯性能契约） | map/05（W3） |
| D289 | K25 文件补全仅 `@` 自动＋Tab 强制触发；fd/普通路径自然触发未接 | map/05（W4） |
| D290 | K26 词删/行删非原子；tmux CSI-u 粘贴不解码 | map/05（W4） |
| D291 | K31 jump mode 键位内置，不读 `keybindings.json`（K17 覆盖另账） | map/05（W4） |
| D292 | C2 选择列表描述列：Java 消费点无 description | map/05（W4） |
| D293 | K39 OSC 7501 无查询/DA 握手（TamboUI 独占 stdin）；以 `PI_PROGRAM_STATUS` 覆盖＋缺省乐观 | map/05（W5） |
| D294 | OSC 8 超链接不输出：已探测但 `InlineMarkup` 只显文本 | map/05（W5） |
| D295 | K32 `baseLayoutKey` 解析但不消费；非拉丁布局 alternate match 不做 | map/05（W5） |
| D296 | K34 原生剪贴板由预编译 `.node` helper 改平台 CLI 链；Termux 路径/报错文案近似 | map/05（W5） |
| D297 | OSC 7501 登录态 `auth` 不做 | map/05（W5） |
| D298 | APC 光标定位标记无对应物；假光标以反白实现 | map/05（W5/W6） |
| D299 | 搜索条以上方三行条代替浮动 overlay | map/05（W6） |
| D300 | 搜索匹配按码点而非字素（非 ASCII 粒度） | map/05（W6） |
| D301 | 选择长尾：无双击选词／边缘自动滚动定时器 | map/05（W6） |
| D302 | flash 由 tick 驱动到期、单条覆盖非栈 | map/05（W6） |
| D303 | 设置子菜单 `navigateTo` 未接、hover 无高亮 | map/05（W6） |
| D304 | `KeybindingHints` 返回 TamboUI `Element` 而非 ANSI 串 | map/05（W6） |
| D305 | 图片实机终端验收不在自动化面（Kitty/iTerm2） | map/05（W7） |
| D306 | macOS Finder 图片粘贴路径不移植（`ClipboardImage` 平台链） | map/05（W7） |
| D307 | 主题预览不重查 system 主题色 | map/05（W7） |
| D308 | LaTeX 长尾符号子集；未收录命令回落原文 | map/05（W7） |
| D309 | Mermaid 不移植：`lovely-mermaid` 无 Java 等价，围栏按代码块渲染（R7 不进分子） | map/05（W7） |
| D310 | 独立 show-images 导出组件不移植（pi 内置消费面已对位） | map/05（W7） |
| D311 | 品牌 shimmer/glow 动画不可达：`SelectList` 纯字符串行 | map/05（W8） |
| D312 | `tui.editor.*` 等编辑器直读键不消费 `keybindings.json` 覆盖 | map/05（W8） |
| D313 | R22/R27 彩蛋（announcement／3D logo）不移植：无 Java 消费面 | map/05（W8） |
| D314 | `/export` HTML 输出形状未逐项核（自写转换 vs vendored marked/highlight） | map/05（W8） |
| D315 | R28 流式 bash／工具组件不进 pending 区 | map/05（W8） |
| D316 | K12/K40 `Ctrl+Z` 挂起（SIGTSTP）不移植：JVM 无公开信号 API | map/05（W3/W5） |

### 6.6 其他保留边界（证据：`docs/map/03-agent-core.md`）

| 编号 | 当前边界 |
|---|---|
| B83 | pi 自身三处 sanitize 缺口保持同样行为；pi 修补时同步。 |
| B100 | compat 探测的 baseUrl 在内置模型上行为等价；出现可观察差异再重裁。 |
| B126 | Codex 车道及其 transport 等随 R2/R5 出分母。 |
| B147 | Responses 回放保留分组顺序；发现 provider 配对校验差异再重裁。 |
| B130 | 当前携带的 zai 模型有效 reasoning effort 与 pi 相同；引入新模型时由数据重新判定。 |
| B132 | together/baseten/fireworks 不内置；自定义 models.json 的显式配置能力保留。 |
| map/03 #54/#58/#78/#100 | Entry 表示、投影合并、会话查找/排序和系统提示载体按已登记边界视为行为等价或刻意差异。 |
| map/03 #14/#80/#84/#82/#90/#91 | 队列、prompt 门、重试映射和转录可见性维持已验证结论；新锚点重测时复查。 |
| map/03 #57/#60 | bashExecution 窄差按消费面保留；已删除 PendingEntry/InboxItem 永久出分母。 |

这些结论不是新锚点免检证明。若对应源码已变化，必须在所属层重新判定。

## 7. 维护规则

1. 本文只留当前结论，不追加批次实施过程。
2. 新缺口先给新锚点源码证据，再登记 B；新差异无法消除时登记 D。
3. 条目关闭后从开放表移除，历史由 git 保存，编号不复用。
4. 每层重测后同步更新对应地图；全部层完成前不发布新全仓百分比。
5. 每个实施工作包仍需独立设计稿和用户审核。
6. 批次设计稿不属于本仓库的最终产物：**实施前**的设计稿按需临时编号，闭环后结论回写 `docs/04` 与 `docs/map/`，稿件删除（历史在 git）。截至 2026-11-20 已清理所有**已闭环**批次设计稿；**进行中的 `docs/08`（B161 codemode 机制，原 `docs/61`，设计待审）、`docs/09`（pi 换锚计划）、`docs/10`（P1 工具参数校验设计）例外保留**，直至对应工作闭环后再回写删除。现行结论入口为 `docs/01`–`docs/05`、`docs/07`、`docs/map/`。
