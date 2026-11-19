# 07 - examples 对齐结论

> 【背景摘要】本文档只保留 examples 的当前有效裁决、已交付能力和新基线验收口径。旧件数、旧分组和旧批次排期不再作为执行依据。

## 1. 有效裁决

### 主流 examples 全做

- 【场景】pi 的 examples 同时是扩展示例和可执行行为规格。
- 【决定】新锚点中仍属主流面的 examples 全部做 Java 移植，并配验收测试。
- 【风险/代价】[内部，模型不可见] 工作量大，但不得以代表子集代替全量行为覆盖。

### 不做 TypeScript 扩展运行时

- 【场景】pi 示例直接导入宿主 TypeScript 模块。
- 【决定】pi-java 只支持 Java 扩展，使用顶层 public `PiExtension`、ServiceLoader 和 JAR 装载，不引入 TS 运行时。
- 【风险/代价】[用户可见] `-e` 的路径形态为 JAR，而不是 `.ts` 文件或目录。

### Chord plugin 不做

- 【场景】plugin 示例依赖 Chord。
- 【决定】按 R5 排除，不进入新 examples 分母。
- 【风险/代价】无影响；若未来单独决定实现 Chord，再重新盘点。

## 2. 当前交付形态

- Maven 模块：`pi-java-examples`。
- 示例位于 main 源码集，验收测试位于 test 源码集。
- classpath 上存在该模块时，ServiceLoader 可发现其中全部示例；需要裁剪时使用既有扩展选择机制。
- 不进入 `pi-java-dist` 发行。
- 测试不得写真实用户 home、settings 或会话目录。

## 3. 已具备的主流示例能力

新锚点 `56cae7823` 分母 **88**（102 − 8 外部运行时 − 1 R5 − 5 范围面主动出分母，见 D232／D237）；**88 件全部有判定并已移植（88/88＝100%）**，W0–W8 全层闭环：

- 保留（16，pi 侧未变）：hello、structured-output、dynamic-tools；permission-gate、protected-paths、confirm-destructive、dirty-repo-guard、project-trust；input-transform、input-transform-streaming、inline-bash；pirate、claude-rules、prompt-customizer；event-bus、provider-payload。
- W1 新增／重移植（5）：custom-provider-anthropic（`CustomAnthropicStream` 按 block index 装配文本／思考／工具流、畸形最终参数走 `argumentsError`）、debug-provider（`provider-debug` 条目取证，`keyHint` 维持 D220）、custom-provider-gitlab-duo（9 模型＋Anthropic/OpenAI 双协议）、custom-provider-gitlab-duo/test、jev-router（虚拟模型 `jev/auto` 四分支路由）。

**已补齐（2026-11-09，docs/50）**：`ExtensionContext` 新增 10 项、`ExtensionSessionView` 新增 9 项（详见 docs/04 与 docs/map/04 E5 段），W2/W3 的上下文与会话只读前置就位。原「剩 4 件按 W6 推进」已于 2026-11-19 完成（见下方 W6 行），分母 88 件全部实施完毕。
- W2 新增／重移植（8，docs/51）：plan-mode/utils（`PlanModeUtils` 纯函数）、plan-mode/index（`/plan`＋`ctrl+alt+p`、工具集收窄、bash 白名单拦截、`[DONE:n]` 追进度、会话恢复重建，窄差 D235）、reload-runtime（`terminate`＋`agent_settled` 派发 `/reload-runtime`）、trigger-compact（10 万 token 跨线＋`/trigger-compact`）、custom-compaction（Gemini Flash 全量摘要接管，`fromHook=true`）、send-user-message（ask/steer/followup/askwith）、system-prompt-header（`System: n chars` 状态段）、handoff（分支投影＋`ui.custom` 生成＋`newSession` 切换，窄差 D236）。
- W3 新增／重移植（10，docs/52，2026-11-19 实施）：preset（`--preset` flag＋`/preset`＋Ctrl+Shift+U、`before_agent_start` 注入、`preset-state` 恢复、capturesViewportInput）、todo（`todo` 工具状态机＋`getBranch` 重建＋renderResult error 臂）、tools（`/tools` 启停＋`tools-config` 持久化＋capturesViewportInput）、bookmark（`setLabel/getLabel` 往返）、session-name（设名/显示）、qna（`getBranch`＋`modelRegistry.complete` 抽取问题回填编辑器，D239）、summarize（四个纯函数＋固定模型摘要，D239）、auto-commit-on-exit（`git` 自动提交，GitExecutor 接缝）、git-checkpoint（git stash checkpoint＋fork 询问恢复）、tic-tac-toe（State/Extension/Component 拆分、两工具 `ExecutionMode.Sequential`、`sendMessage(triggerTurn)`、消息渲染器，D238）。注册表 37 项、W3 逐批测试全绿（W4 完成后全量 examples 测试已跑通）。
- W4 新增／重移植（10，docs/54，2026-11-19 实施）：minimal-mode（7 工具渲染覆盖＋read 行区间 `ReadLineRange.format` 改动点）、subagent/index（single/parallel/chain 三模式编排＋注入式 runner）、subagent/agents（frontmatter 解析＋discoverAgents 纯逻辑，不单独注册）、bash-spawn-hook（spawnHook 变换纯函数，D240）、built-in-tool-renderer（4 渲染器＋截断/diff/exit 纯逻辑，D241）、tool-override（同名 read 覆盖＋7 阻断正则＋访问日志，完整对齐）、truncated-tool（rg 工具＋fake 执行＋截断通知）、ssh（bash executorOverride 远程后端＋user_bash Operations＋cwd 改写，文件形状对齐 D243）、interactive-shell（user_bash Result 臂＋59 交互命令判定＋fake shell）、github-issue-autocomplete（注册形状＋token/repo/issue 过滤纯逻辑，D242）。注册表 46 项、W4 逐批测试全绿。
- W7 新增／重移植（5，docs/55，2026-11-19 实施）：notify（agent_settled 通知＋OSC 777/99/toast 纯函数逐字，D245）、titlebar-spinner（10 帧盲文＋标题模板＋注入 TickDriver 80ms，D245/D246）、file-trigger（注入 FileSource 手动触发回调＋file-trigger 消息 triggerTurn＋清空，D246）、git-merge-and-resolve（GitExecutor 假 git 命令序列＋冲突状态机＋formatConflicts 纯函数＋followUp 报告）、shutdown-command（/quit＋finish_and_exit/deploy_and_exit 两工具＋ctx.shutdown＋onUpdate 序列）。注册表 51 项、W7 逐批测试全绿（25 项）。
- W8 新增／等价改写（17，docs/56，2026-11-19 实施）：sdk/01–13 共 13 件（配置型 04/05/07/08/09/10/11 纯函数投影＋运行型 01/02/03/06/12/13 真实 AgentSession faux 集成，D247/D251）、rpc-client（默认提示词/事件过滤/prompt 形状，D248）、rpc-extension-ui（九方法/三类回包/四纯函数/事件顺序，D250/D252）、rpc-demo（扩展入册，3 command+5 event+危险命令正则）、dynamic-resources（扩展入册，sessionStartResources 贡献 SKILL.md/dynamic.md/dynamic.json 三路径，D249）。注册形状零新增 facade，注册表 53 项（仅 2 扩展入册）；逐批测试全绿（13+14 项）。
- W5 新增／重移植（13，docs/57，2026-11-19 实施）：status-line（`status-demo` 三段文案＋回合计数）、model-status（`model_select` 通知/状态、`restore` 静默臂）、hidden-thinking-label（默认 `Pondering...`＋设/复位两臂）、widget-placement（两区键位与 `belowEditor`）、working-message-test（裸 24bit 文案＋单帧指示器）、working-indicator（五模式帧表/间隔＋命令四臂）、custom-footer（分支用量汇总 `↑in ↓out $cost`＋`<model> (<branch>)`、JS 模板数值格式）、custom-header（吉祥物 8 行＋副标题＋仅 tui 挂槽）、message-renderer（`[LEVEL]` 前缀＋展开时间串＋`/status` 载荷）、entry-renderer（`[status]` 卡片＋`undefined`/`No data` 两缺省臂＋`appendEntry`）、modal-editor（NORMAL/INSERT 键位映射与底边框标签）、rainbow-editor（七色向白插值＋三位闪光＋20 帧循环＋注入 tick）、border-status-editor（`fitBorder` 两段削平＋`ctx ?` 回落＋`~` 缩短＋spinner 起停＋首末行状态段）。**零新增宿主成员**；共享件 `ExampleEditor`／`ExampleText`／`ThemeText`；注册表 **66 项**、13 个测试类 63 项全绿，模块 358 测 0 失败。窄差不新开编号：docs/27 §3.4（handler 层无主题色通道）、D92（渲染器文本级）、D100（无 `onBranchChange`）、D246（注入式 tick）；D99/D220 影响面为零。
- W6 新增／重移植（4，docs/58，2026-11-19 实施）：timed-confirm（`confirm`/`select` 携 5000/10000ms 超时＋手写 `AbortSignal` 三结局，D246 注入式单次定时器）、commands（`getCommands()` 三源分组选择器＋来源过滤＋`sourceInfo.path` 确认，D253 命令级参数补全无对位）、question（`question` 工具：`Type something.` 附加项＋内联编辑器＋结果三臂＋`ExecutionMode.Sequential`）、questionnaire（多问题页签/提交页＋`allowOther` 缺省＋`__other__` 手写＋答案行模板，组件拆 `QuestionnaireComponent`）。工具渲染器走 D241 文本级（无 `details`/theme）；注册表 **70 项**、4 个测试类 43 项全绿，模块 **401 测 0 失败**，SpotBugs 全模块 0 告警。


- 主动出分母（5，用户裁决 2026-11-09，D237）：overlay-test、overlay-qa-tests（D99 已裁决 overlay 全参不移植，验收只剩文本行且需等 L7）、snake、space-invaders（除游戏帧外不验证宿主语义）、mac-system-theme（macOS 专属，无对位）。不写降级假移植；W5 13 件／W6 4 件／W7 5 件均已实施。

## 4. 新基线验收口径

1. 在 L8 以 `56cae7823` 重新清点 examples。
2. 每件标记为：保留、已修改、已删除或 R5 出分母。
3. 已修改示例重新提取行为断言，不能只复制旧 Java 实现。
4. 优先覆盖流式工具参数、`before_agent_start`、工具加载、MCP 和会话恢复等新行为。
5. 每个示例都有独立验收测试；外部系统使用假模型、假进程或隔离服务。
6. 新分母由本次清点产生（**88**＝102 − 8 外部运行时 − 1 R5 − 5 范围面主动出分母，D232／D237），旧 105/102 件不再作为翻“对齐”的条件。

详细层级顺序见 [`08-pi-rebaseline-master-plan.md`](08-pi-rebaseline-master-plan.md)。
