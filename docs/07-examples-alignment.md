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

当前模块已经覆盖以下 16 类行为：

- hello、structured-output、dynamic-tools；
- permission-gate、protected-paths、confirm-destructive、dirty-repo-guard、project-trust；
- input-transform、input-transform-streaming、inline-bash；
- pirate、claude-rules、prompt-customizer；
- event-bus、provider-payload。

宿主已提供 `ExtensionContext.cwd()` 和 `ExtensionSessionView.getEntries()`。其余只读会话成员按新示例的真实消费点补齐，不为凑接口清单一次性空实现。

这些是现有能力清单，不是新锚点的 16/102 完成度。

## 4. 新基线验收口径

1. 在 L8 以 `56cae7823` 重新清点 examples。
2. 每件标记为：保留、已修改、已删除或 R5 出分母。
3. 已修改示例重新提取行为断言，不能只复制旧 Java 实现。
4. 优先覆盖流式工具参数、`before_agent_start`、工具加载、MCP 和会话恢复等新行为。
5. 每个示例都有独立验收测试；外部系统使用假模型、假进程或隔离服务。
6. 新分母由本次清点产生，旧 105/102 件不再作为翻“对齐”的条件。

详细层级顺序见 [`08-pi-rebaseline-master-plan.md`](08-pi-rebaseline-master-plan.md)。
