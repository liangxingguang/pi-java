# 58 - L8 W6 交互/命令示例移植设计（4 件）

> 【背景摘要】本文档是 `docs/27` §4 工作包 W6（L8 最后一个包）的详细设计：把 pi 锚点
> `56cae7823` 的 4 个交互/命令类扩展示例移植到 `pi-java-examples`——`timed-confirm`、
> `commands`、`question`、`questionnaire`。四项消费的宿主面
> （`confirm`/`select`/`custom`／`getCommands`／`registerTool`＋`registerToolRenderer`）
> 全部已存在，本包 **零新增宿主成员**，只余两处窄差（D253、D254）。

- pi 锚点：`56cae782341c339d3bb820081f0f9425d8c5b1ec`
- 上游依据：`docs/27` §2.5 W6 分组（4 件）、§4 W6 断言、§5 文本级口径
- 用户既有裁决：文本级逐字断言；D99（overlay）与 D220（`keyHint`）约束不变
- **文档状态：2026-11-19 设计并实施完成；W6 完成后 L8 分母 88 件全部实施。**

## 0. 取证结论

| # | pi 文件 | 行数 | 注册形状 | 宿主消费 |
|---|---|---|---|---|
| 1 | `timed-confirm.ts` | 70 | 3 commands | `ui.confirm`/`ui.select` ＋ `ExtensionUiDialogOptions(signal, timeoutMillis)` |
| 2 | `commands.ts` | 72 | 1 command ＋ `getArgumentCompletions` | `ctx.getCommands()`、`ui.select`、`ui.confirm`、`ui.notify` |
| 3 | `question.ts` | 286 | 1 tool ＋ renderCall/renderResult | `ui.custom`（组件工厂＋done）、`AgentTool`、`registerToolRenderer` |
| 4 | `questionnaire.ts` | 448 | 1 tool ＋ renderCall/renderResult | 同上（多页签状态机） |

Java 侧对应能力（均已存在，零新增）：
`ExtensionUI#confirm/select/custom`（`ExtensionUiDialogOptions(signal, timeoutMillis)`、
`ExtensionComponentFactory<T>.create(env, keybindings, done)`）、
`ExtensionContext#getCommands()`（`SlashCommandInfo(name, description, source, sourceInfo)`）、
`ToolRegistry#register`、`registerToolRenderer(ToolRendererResolver)`、
`AbortSignal.create()/abort()/isAborted()`。

## 1. 逐件设计

### 1.1 `timed-confirm.ts` → `TimedConfirmExtension`

三条命令逐字：
- `/timed`：`confirm("Timed Confirmation", "This dialog will auto-cancel in 5 seconds. Confirm?", {timeout: 5000})` ⇒ `"Confirmed by user!"`／`"Cancelled or timed out"`（INFO）。
- `/timed-select`：`select("Pick an option", ["Option A","Option B","Option C"], {timeout: 10000})` ⇒ `"Selected: x"`／`"Selection cancelled or timed out"`。
- `/timed-signal`：手写 `AbortController`，5 秒后 `abort()`；先通知 `"Dialog will auto-cancel in 5 seconds..."`，`finally` 清定时器；三结局 `"Confirmed by user!"`／`"Dialog timed out (auto-cancelled)"`（**warning**）／`"Cancelled by user"`。

超时本身是宿主能力（`timeoutMillis` 传给 TUI 做倒计时），示例不自建计时。
`/timed-signal` 的 5 秒中止沿 **D246** 用可注入 `TimeoutScheduler.schedule(delay, task) → Runnable(取消柄)`；
默认实现不排程（不建真定时器），测试用捕获/立即执行两种实现覆盖三结局。

### 1.2 `commands.ts` → `CommandsExtension`

`/commands [extension|prompt|skill]`：
1. `ctx.getCommands()`；按 `source` 过滤（pi 是严格相等，java 侧大小写不敏感以免 TUI 来源名大小写差异成为陷阱；空过滤 ⇒ 全量）；
2. 空集 ⇒ `"No commands found"`／`"No <src> commands found"`；
3. 三组固定顺序 `Extensions`／`Prompts`／`Skills`，组内有命令才出表头 `"--- <组名> ---"`，条目 `"/name - description"`（无描述则只 `"/name"`）；
4. `ui.select("Available Commands", items)`；选中表头或以 `---` 开头 ⇒ 直接返回；
5. 剥前导 `/` 与 `" - "` 尾巴得命令名，回查命令；`sourceInfo.path` 非空才问 `confirm(cmd.name, "View source path?\n<path>")`，确认后 `notify(path)`。

**D253**：pi 的 `pi.registerCommand(..., getArgumentCompletions)` 在 java `SlashCommand` 上无对位
（java 只有扩展级 autocomplete provider 空接口，D242）。因此补全降级为纯函数
`sourceCompletions(prefix)`（三个来源前缀匹配、无匹配返回 `null`），只做断言不入键盘面。

### 1.3 `question.ts` → `QuestionExtension`

工具 `question`（`ExecutionMode.Sequential`），schema：`question: string`、`options: [{label, description?}]`（requires `label`）。
- 非 `tui` ⇒ `"Error: UI not available (running in non-interactive mode)"`；空选项 ⇒ `"Error: No options provided"`；两者 details 都含 `question`/`options`/`answer: null`。
- 显示项 = 入参选项 + `"Type something."`（`isOther`）。
- `ui.custom` 组件 `QuestionComponent`：↑↓ 移光标、Enter 选中（普通项直接 `done`，附加项进编辑态）、Esc 在编辑态回列表并在列表取消、内联编辑器 Enter 提交（trim 后非空 ⇒ `{answer, wasCustom:true}`，空 ⇒ 复位回列表）。
- 结果三臂：`"User selected: N. label"`／`"User wrote: text"`／`"User cancelled the selection"`；details `{question, options: labels, answer, wasCustom}`。
- `renderCall`／`renderResult`（**D254**，D241 同族文本级）：参数行 `question <q>` ＋可选 `\n  Options: 1. a, 2. b, 3. Type something.`；结果行按 `textOutput` 前缀复原 `✓ …`／`✓ (wrote) …`／`Cancelled`。

### 1.4 `questionnaire.ts` → `QuestionnaireExtension`

工具 `questionnaire`（`ExecutionMode.Sequential`），schema：`questions: [{id, label?, prompt, options:[{value,label,description?}], allowOther?}]`。
- 规范化：`label` 缺省 `Q<n>`、`allowOther` 缺省 `true`；非 `tui`／空问题两条错误臂（`errorResult` ⇒ `cancelled=true`）。
- 单问题退化为无页签列表；多问题出现页签条 `← □/■ Label … ✓ Submit →`，Tab/→ 下一页、Shift+Tab/← 上一页（含环绕）。
- 提交页：全答才允许 Enter 提交，未答列出 `"Unanswered: A, B"`；Esc 取消。
- 选项列表含 `"Type something."`（value `__other__`）；手写答案留空落 `"(no response)"`；答完自动推进（单问题直接提交，多问题下一页/提交页）。
- 内容行 `${label}: user selected: N. x`／`${label}: user wrote: x`；取消 ⇒ `"User cancelled the questionnaire"`。
- 组件因文件行数纪律拆为 `QuestionnaireComponent`（pi 侧是同一 `registerTool` 内的闭包），状态机与 pi L110-394 一一对应。

## 2. 测试

4 个独立测试类（43 项），沿用 `W3Context`＋`ScriptedUi`：
- `TimedConfirmExtensionTest`（7）：超时选项值、两条超时文案、AbortSignal 三结局（捕获式与立即执行两种 scheduler）、定时器清除。
- `CommandsExtensionTest`（9）：补全纯函数、条目格式、三组分序、空集/无匹配、选中→路径确认、表头与无 `path` 臂。
- `QuestionExtensionTest`（13）：两条错误臂、取消臂、结果三臂、schema、显示项、光标夹取、编辑态提交/空提交/Esc、渲染行、渲染器。
- `QuestionnaireExtensionTest`（14）：规范化缺省、`allowOther` 两臂、两条错误臂、取消臂、单问题直提、多问题页签推进与环绕、提交页未答提示、手写臂与空手写、Esc 两段、内容行模板、渲染器。

## 3. 窄差登记

| 编号 | 内容 | 落点 |
|---|---|---|
| **D253** | `SlashCommand` 无 `getArgumentCompletions`：命令级参数补全只有纯函数对位 | commands |
| **D254** | `renderCall`/`renderResult` 是文本级（无 theme、无 `result.details`）：结果行按文本前缀复原 | question、questionnaire |
| D246（沿用） | 无注入式定时器：`/timed-signal` 走注入 `TimeoutScheduler` | timed-confirm |
| D241（沿用） | 工具渲染器文本级方言 | question、questionnaire |
| D92（沿用） | 消息/条目渲染器文本级 | — |
| D99／D220 | overlay 定位与 `keyHint`：本包零消费 | — |

## 4. 实施记录（2026-11-19）

- 4 个 `main` 类＋1 个组件类（`QuestionnaireComponent`）；`META-INF/services` 66 → **70 项**；
  `RegistryIntegrityTest` 同步（70 类/70 名）。
- 夹具扩展（仅 test）：`ScriptedUi` 增 `dialogOptions` 录表（`select`/`confirm`/`input` 三处），
  `W3Context` 增 `commandInfos`（`getCommands` 应答）。
- 4 个测试类 **43 项**全绿；模块 **401 测 0 失败**；`mvn -pl pi-java-examples -am install`
  SpotBugs 全模块 0 告警、BUILD SUCCESS。
- **L8 状态**：分母 88 件全部实施（88/88），`docs/07` §3 已按新清单重写为闭环口径；
  `docs/05` 总表待 L8 全仓 `mvn clean verify` 后替换（docs/27 §7 第 5 条）。
