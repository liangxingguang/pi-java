# 30 - L8 examples 复核补齐任务表

> 对应设计：[`30-l8-examples-design.md`](30-l8-examples-design.md)。按序执行，每步可独立编译。

## T1 plan-mode 纯函数

- [x] `PlanModeUtils.cleanStepText` 去掉 50 字符截断。
- [x] `PlanModeUtils.extractTodoItems` 改取最后一个 `Plan:` 头＋整行编号捕获＋过滤条件对齐。
- [x] `PlanModeUtils.extractDoneSteps` 加 `[DONE 1]`／`Done: 1`／`Step N done` 并去重。
- [x] `PlanModeUtils.markCompletedSteps` 改为只计新标记。
- [x] 新增 `PlanModeUtils.allStepsComplete`。

## T2 plan-mode 编排

- [x] `PlanModeState.textContent` 纳入 `ThinkingContent`。
- [x] `turn_end` 恒刷新状态；widget 文本 60/57 截断。
- [x] `agent_end` 增加 `allStepsComplete` 整单回落。
- [x] `plan-complete` 改用 `CustomMessageDelivery.NEXT_TURN`。

## T3 subagent 数据模型

- [x] 新增 `ChildStatus`、`SingleResult`；runner 增 `AbortSignal` 入参。
- [x] `SubagentAgents.AgentConfig` 增 `filePath` 并同步加载与测试。

## T4 subagent 编排与协议

- [x] 新 `description()` 与 `inputSchema()`（`chain`／`agentScope`／`confirmProjectAgents`／`cwd`）。
- [x] 非空模式判定、agent 解析与 unknown agent 文案、启动前中止。
- [x] project agent 确认门（`ui.confirm`＋`isProjectTrusted`）。
- [x] chain／parallel／single 文案、失败证据、两种截断。
- [x] details 形状与 `tool_result` isError 补丁。

## T5 测试

- [x] 更新 `PlanModeUtilsTest` 并补新用例。
- [x] 更新 `PlanModeExtensionTest`（thinking／恒刷新／截断／整单回落／NEXT_TURN）。
- [x] 重写 `SubagentExtensionTest` 覆盖状态机与证据文本。
- [x] 同步 `SubagentAgentsTest` 构造参数。

## T6 验证与回填

- [x] `./mvnw test -pl pi-java-examples` 全绿。
- [x] Checkstyle 0 error。
- [x] 回填 `docs/07` §5、`docs/09`（L8／进度／P11）、`docs/04` §0、`docs/map/04`。
- [x] 不提交，等待用户指示。
