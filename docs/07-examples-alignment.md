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

新锚点 `56cae7823` 分母 **88**（102 − 8 外部运行时 − 1 R5 − 5 范围面主动出分母，见 D232／D237）；**88/88＝100%**。

- 保留（16，pi 侧未变）：hello、structured-output、dynamic-tools；permission-gate、protected-paths、confirm-destructive、dirty-repo-guard、project-trust；input-transform、input-transform-streaming、inline-bash；pirate、claude-rules、prompt-customizer；event-bus、provider-payload。
- 主动出分母（5，D237）：overlay-test、overlay-qa-tests、snake、space-invaders、mac-system-theme。
- 其余 **72** 件按 W1–W8 实施并注册（注册表 70 项、模块 401 测 0 失败、SpotBugs 0 告警）；逐批明细见 git 历史与 `docs/map/04`，窄差见 D235–D253。

## 4. 新基线验收口径

1. 在 L8 以 `56cae7823` 重新清点 examples。
2. 每件标记为：保留、已修改、已删除或 R5 出分母。
3. 已修改示例重新提取行为断言，不能只复制旧 Java 实现。
4. 优先覆盖流式工具参数、`before_agent_start`、工具加载、MCP 和会话恢复等新行为。
5. 每个示例都有独立验收测试；外部系统使用假模型、假进程或隔离服务。
6. 新分母由本次清点产生（**88**＝102 − 8 外部运行时 − 1 R5 − 5 范围面主动出分母，D232／D237），旧 105/102 件不再作为翻“对齐”的条件。

## 5. 目标锚点 `a00a5048d` 预审计（2026-11-21）

- **分母仍为 88**：`packages/coding-agent/examples` 在两锚点间有 18 个文件变化（+695/−662），sandbox 整个目录删除——sandbox 早已按 D232（外部运行时）出分母，删除文件不改变计分件数；无新增示例，Java 注册表仍 70 项。
- **严格重基线：86/88 ≈ 97.7%**（旧锚点正式读数 88/88＝100%）。两件在分母内且被重写、Java 仍是旧行为：
  - **plan-mode**：新锚点加入 thinking 块、最后一个 `Plan:` 头、整行编号步骤、`[DONE: 1]`/`Step N done` 变体、`allStepsComplete` 回落、`deliverAs: nextTurn`；Java 只支持文本块、首个 Plan 头/50 字符截断/`[DONE:n]`、`triggerTurn=false`。**注意 D237 只排除 overlay/game/mac，不排除 plan-mode**。
  - **subagent**：563 行重写（状态/启动前中止/失败证据/`truncateHead`/`deliverAs` 变体）；Java 仍只有 error/completion 与字节截断。**D244 只是注入式 runner 的窄差，不排除 subagent**。
- 若 P11 按新锚点补齐这两件并保留 D235/D244 作窄差，可维持 88/88；否则按 86/88 登记。正式行级重判归 P12；口径见 [`05-module-alignment-map.md` §1.2](05-module-alignment-map.md)。

详细层级顺序见 [`09-pi-latest-reanchor-plan.md`](09-pi-latest-reanchor-plan.md)。
