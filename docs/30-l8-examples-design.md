# 30 - L8 examples 复核补齐设计（plan-mode／subagent）

> 【背景摘要】新锚点 `a00a5048d` 下 L8 examples 严格重基线 86/88。本文只覆盖分母内被重写的两件：
> `examples/extensions/plan-mode/{index,utils}.ts` 与 `extensions/subagent/index.ts`。目标是把这两件
> 按新锚点补齐，使 L8 回到 88/88，同时保留 D235／D244 既有窄差。取证只走
> `git -C D:/workplaceForai/pi show a00a5048d:<path>`。

## 1. 范围与裁决

| 项 | 上游变化 | 本包处置 |
|---|---|---|
| plan-mode/utils.ts | 去 50 字符截断、取最后一个 `Plan:` 头、整行编号捕获、`[DONE 1]`／`Done: 1`／`Step N done` 三种完成标记、`allStepsComplete` | 全量补齐 |
| plan-mode/index.ts | textContent 含 thinking、turn_end 恒刷新、widget 60 字符截断、agent_end 整单完成回落、`deliverAs: "nextTurn"` | 全量补齐 |
| subagent/index.ts | 状态机、启动前中止、失败证据、两种截断、模式判定与参数面、unknown agent、project agent 确认、`tool_result` 错误补丁 | 补齐模型可见与工具协议面 |
| subagent 子进程段 | spawn／临时 prompt／SIGTERM→SIGKILL／JSON 流 | 保留 D244：注入式 runner，不真起进程 |
| subagent 渲染段 | `renderCall`／`renderResult`（约 360 行 TUI） | 不移植（examples 无 TUI 渲染移植口径；不计入本次缺项，docs/07 §5 未列为缺口） |
| `examples/tsconfig.json`、README、lockfile | 工程/文档 | 不移植（不影响行为） |

分母与注册表条数不变（70 项）。

## 2. plan-mode

### 2.1 `PlanModeUtils`（[PlanModeUtils.java](../pi-java-examples/src/main/java/com/pijava/examples/PlanModeUtils.java)）

1. `cleanStepText`：删除 `cleaned.length() > 50` 截断分支；其余（粗体/行内代码/动作前缀/折叠空白/首字母大写）逐字保留。
2. `extractTodoItems`：
   - 用 `Matcher` 遍历 `\*{0,2}Plan:\*{0,2}\s*\n`（`CASE_INSENSITIVE`），取**最后一个**匹配的 `index + 长度` 作为段起点。
   - 编号模式改为整行捕获 `^\s*(\d+)[.)]\s+(.+)$`（`MULTILINE`）。
   - 文本 `strip()` 后去掉尾部 `\*{1,2}$`；过滤条件为 `length > 5 && !startsWith("/") && !startsWith("-")`（删除旧的 `startsWith("`")` 判定，行内代码由 `cleanStepText` 去掉）。
   - 清洗后 `length > 3` 才收录；`step = items.size() + 1`。
3. `extractDoneSteps`：三条模式依次收集，最后按首次出现顺序去重：
   - `\[DONE\s*:?\s*(\d+)\]`（`CASE_INSENSITIVE`）
   - `\bDone\s*:\s*(\d+)\b`（`CASE_INSENSITIVE`）
   - `\bStep\s*(\d+)\s+(?:is\s+)?(?:done|complete|completed|finished)\b`（`CASE_INSENSITIVE`）
4. `markCompletedSteps`：对每个完成号找**第一个** `step` 相同且 `!completed` 的条目，置真并 `marked++`；返回 `marked`（不再返回标记总数）。
5. 新增 `allStepsComplete(String)`：
   - `(?:all|every)\s+steps?\s+(?:are|is\s+)?(?:done|complete|completed|finished)`（`CASE_INSENSITIVE`）
   - `\b(?:the\s+)?plan\s+(?:is\s+)?(?:complete|completed|done)\b`（`CASE_INSENSITIVE`）
   - 任一命中即真。

### 2.2 `PlanModeState`／`PlanModeExtension`

1. `PlanModeState.textContent`：除 `TextContent` 外追加 `ThinkingContent.text()`；块间仍以 `"\n"` 拼接（pi `getTextContent` 逐字）。
2. `PlanModeExtension` 的 `turn_end`：删除 `if (markCompletedSteps(...) > 0)` 门，改为先标记、再**无条件** `updateStatus(c)`，最后 `persistState(c)`。
3. `updateStatus` 的 widget 行文本：`text.length() > 60 ? text.substring(0, 57) + "..." : text`；`☑`／`☐` 前缀与 D235 纯文本降级不变。
4. `onAgentEnd` 执行态分支：在 `allMatch(completed)` 之前，取最后一条 `AssistantMessage`，若 `allStepsComplete(textContent)` 则把全部条目标记完成，再走既有 `plan-complete` 分支。
5. `plan-complete` 投递：`new SendMessageOptions(null, CustomMessageDelivery.NEXT_TURN)`（pi 只给 `deliverAs`，不设 `triggerTurn`）。
6. 其余（D235 文本过滤、恢复段 `??` 语义、工具集算法、`followUp` 两个发送点）保持现状。

## 3. subagent

### 3.1 数据模型（`SubagentExtension` 内）

- 新增 `enum ChildStatus { PENDING, RUNNING, COMPLETED, FAILED, ABORTED }`，`label()` 返回小写名。
- `record SingleResult(String agent, String agentSource, String task, ChildStatus status,
  Integer exitCode, String output, String stderr, String stopReason, String errorMessage,
  String terminationSignal, Integer step, String model)`；`isFailed()`＝`FAILED || ABORTED`。
- runner 改为 `SingleResult run(String agent, String task, String cwd, AbortSignal signal)`；
  默认实现仍抛 `UnsupportedOperationException`（D244）。
- `availableAgents` 保留（作为注入的发现结果）；`agentScope` 只参与确认门。
- `SubagentAgents.AgentConfig` 增加 `filePath`（确认框需要列出 `name: filePath`）。

### 3.2 工具协议与编排

1. `description()` 改为新锚点四句（join `" "`）：
   `Delegate tasks to specialized subagents with isolated context.` /
   `Modes: single (agent + task), parallel (tasks array), chain (sequential with {previous} placeholder).` /
   `Default agent scope is "user" (from <agentDir>/agents).` /
   `To enable project-local agents in <config>/agents, set agentScope: "both" (or "project").`
2. `inputSchema()`：`agent`、`task`、`tasks`（array）、`chain`（array）、`agentScope`（string enum `user|project|both`，default `user`）、`confirmProjectAgents`（boolean，default true）、`cwd`；键顺序同上。
3. 模式判定按**非空**：`hasChain = chain 非空`、`hasTasks = tasks 非空`、`hasSingle = agent 非空 && task 非空`；数量 ≠1 ⇒ `Invalid parameters. Provide exactly one mode.\nAvailable agents: <name (source), ... | none>`。
4. 每个条目先解析：`cwd = resolve(defaultCwd, item.cwd ?? ".")`；agent 在 `availableAgents` 中按名查找。
   未知 ⇒ 不启动，返回失败结果 `Unknown agent: "<name>". Available agents: "a", "b" | none.`。
5. 启动前中止：`signal != null && signal.isAborted()` ⇒ `ABORTED` + `Subagent was aborted before starting`，不调用 runner。
6. project agent 确认门：`(scope==project || scope==both) && confirmProjectAgents && ctx.hasUi()` 时，
   收集所选结果中 `source=="project"` 且（`!ctx.isProjectTrusted()`）的 agent；非空则 `ctx.ui().confirm("Run project-local agents?", "Sources:\n- name: path\n\nProject agents are repo-controlled. Only continue for trusted repositories.", null)`。
   拒绝 ⇒ 文本 `Canceled: project-local agents not approved.`（details 为空、isError=false）。
7. chain：逐条 `{previous}` 全量替换；失败即返回
   `truncateModelOutput("Chain stopped at step N (agent): " + resultOutput)`；成功返回最后一条
   `truncateModelOutput(output || "(no output)")`。
8. parallel：`>8` ⇒ `Too many parallel tasks (N). Max is 8.`；并发上限 4、保序。
   每条摘要 `### [agent] <status>[ (stopReason)]\n\n<output>`，其中
   `status = 失败且有 stopReason 且 stopReason != "end" ? "<status> (stopReason)" : "<status>"`；
   `output = truncateParallelOutput(resultOutput)`；首行 `Parallel: <s>/<n> succeeded`，条目间 `\n\n---\n\n`。
9. single：失败 ⇒ `truncateModelOutput("Agent " + (stopReason || status) + ": " + resultOutput)`；
   成功 ⇒ `truncateModelOutput(output || "(no output)")`。
10. 失败证据 `resultOutput`：失败时按 `Error`／`Signal`／`stderr`／`Assistant output` 顺序收集，
    去空白、去重（完全相同的文本只留一次），以 `"\n\n"` 连接；空 ⇒ `(no output)`。
    成功时 `output || "(no output)"`。
11. 截断：
    - `truncateParallelOutput`：UTF-8 字节 ≤ 50 KiB 原样；否则按字符回退到 ≤ 上限，追加
      `\n\n[Output truncated: <omitted> bytes omitted. Full output preserved in tool details.]`。
    - `truncateModelOutput`：`TruncationUtils.truncateHead(output)` 未截断 ⇒ 原样；否则用
      `maxLines = DEFAULT_MAX_LINES - 2`、`maxBytes = DEFAULT_MAX_BYTES - 256` 再截，notice 同上
      （`omitted = initial.totalBytes - body.outputBytes`），内容空时只返回 notice。
12. details：统一产出 `Map.of("mode", mode, "results", List<Map>, "isError", boolean)`；
    每个 result 含 `agent/agentSource/task/status/exitCode/output/stderr/stopReason/errorMessage/terminationSignal/step/model`；
    `isError = forceError || 任一 isFailed`。
13. `tool_result` 钩子：register 期订阅；`toolName=="subagent"` 且 details.isError 为真 ⇒
    `new ToolResultEventResult(null, null, null, Boolean.TRUE, null)`，否则 `null`。
14. 参数键由 `steps` 改为 `chain`（旧键不再接受，与上游一致）；测试同步替换。

### 3.3 窄差

- **D244**：runner 注入替代 spawn／临时 prompt／信号升级／JSON 流；因此不产生 streaming partial 更新
  （`onUpdate` 仍可留空），进程相关字段（exitCode/stderr/terminationSignal）由 runner 提供。
- **D235 同族**：TUI `renderCall`／`renderResult` 不移植。

## 4. 测试

- `PlanModeUtilsTest`：更新 `cleanStepText` 不截断；`extractTodoItems` 取最后头、整行捕获（含反引号与加粗）；
  `extractDoneSteps` 三种变体＋去重；`markCompletedSteps` 只计新标记；新增 `allStepsComplete` 正反例。
- `PlanModeExtensionTest`：新增 thinking 块完成标记、turn_end 无命中仍刷新 widget、widget 60 截断、
  agent_end 整单回落、`plan-complete` 以 NEXT_TURN 投递（断言不进当轮触发）；既有用例按新返回语义调整。
- `SubagentExtensionTest`：改写为 `SingleResult`；覆盖模式非空判定、unknown agent、启动前中止、
  失败证据顺序与去重、chain 失败文案、parallel 状态与逐任务 50 KiB 截断、single 文案、
  `truncateModelOutput` 的行/字节双限、`tool_result` 错误补丁注册。
- `SubagentAgentsTest`：`AgentConfig` 增列 `filePath` 后同步构造。
- `RegistryIntegrityTest`：条数不变，不改。

## 5. 验收

```powershell
$env:JAVA_HOME='D:\soft\jdk\jdk-25'
./mvnw test -pl pi-java-examples
./mvnw -pl pi-java-coding-agent checkstyle:check
```

要求：`pi-java-examples` 全绿；新增/修改文件 ≤500 行；Checkstyle 0 error。

## 6. 文档回填

- `docs/07` §5：改判 88/88，记录两件已补齐、D235／D244 保留。
- `docs/09`：L8 段与进度段改为 P11 完成、下一步 P12。
- `docs/04` §0 末尾追加 L8 收口条；`docs/map/04` 的 L8 行同步。
