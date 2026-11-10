# 51 - L8 W2 生命周期/压缩示例移植设计（8 件）

> 【背景摘要】本文档是 `docs/27` §4 工作包 W2 的详细设计：把 pi 锚点 `56cae7823` 的
> 8 个生命周期/压缩示例逐件移植到 `pi-java-examples`，并给出宿主 API 消费面、逐件实现要点、
> 验收断言与窄差登记。W2 的宿主前置（`docs/50` 的 B284/B285）已闭环，本包**不新增宿主成员**。
>
> **文档状态：设计待审。本稿只授权审核，不授权实施代码。**

- pi 锚点：`56cae782341c339d3bb820081f0f9425d8c5b1ec`
- 上游依据：`docs/27` §2.2/§2.5（W2 六件未移植＋两件已修改）、§4 W2 断言、§5 文本级口径
- 前置闭环：`docs/50`（B284 `ExtensionContext` 10 项、B285 `ExtensionSessionView` 9 项）
- 用户既有裁决：示例只移植注册形状与行为，不复刻真实网络；内部形状差登记窄差

## 0. 取证口径

1. pi 侧行为逐字取自锚点源码，路径均在 `packages/coding-agent/examples/extensions/`。
2. Java 侧只消费 `pi-java-coding-agent` 已有公开面：`ExtensionContext`、`ExtensionEvent`、
   `ExtensionResults`、`ExtensionUI`、`ExtensionModelRegistry`、`ExtensionSessionView`。
3. 文本断言优先：命令输出、通知、条目内容、活动工具集、压缩结果字段；TUI 颜色/动画不作断言。
4. 每个示例一个独立验收测试；测试只用 faux provider、临时目录与脚本 UI，不写真实 home。

## 1. 单元与对位

W2 共 **8 件**（6 件 `docs/27` §2.5 未移植＋2 件 §2.2 已修改）：

| # | pi 文件 | Java 类型 | 类别 |
|---|---|---|---|
| 1 | `plan-mode/utils.ts` | `PlanModeUtils`（静态工具） | 未移植 |
| 2 | `plan-mode/index.ts` | `PlanModeExtension` | 已修改 |
| 3 | `reload-runtime.ts` | `ReloadRuntimeExtension` | 已修改 |
| 4 | `trigger-compact.ts` | `TriggerCompactExtension` | 未移植 |
| 5 | `custom-compaction.ts` | `CustomCompactionExtension` | 未移植 |
| 6 | `send-user-message.ts` | `SendUserMessageExtension` | 未移植 |
| 7 | `system-prompt-header.ts` | `SystemPromptHeaderExtension` | 未移植 |
| 8 | `handoff.ts` | `HandoffExtension` | 未移植 |

## 2. 逐件设计

### 2.1 `plan-mode/utils.ts` → `PlanModeUtils`

纯函数，无宿主依赖；`TodoItem` 因 `markCompletedSteps` 需原地改 `completed`，用可变类而非 record。

- `isSafeCommand(String)`：先查 33 条破坏性正则，再查 50 条只读白名单正则；命中破坏性或未命中白名单 ⇒ false。
  JS `p.test(s)` 对非 global 正则即“任意位置匹配”，Java 用 `Pattern.matcher(s).find()`。
- 正则逐字迁移：`\b`（JS ASCII 词界 = Java 未开 `UNICODE_CHARACTER_CLASS` 时的 `\b`）、
  `(^|[^<])>(?!>)` 前瞻、`>>`、`(?i)` 大小写不敏感；`\s` 按 Java 默认 ASCII 空白（命令串场景等价）。
- `cleanStepText`：依次去粗体/斜体、去行内代码、去 `(Use|Run|…|Install)\s+(the\s+)?` 前缀、
  折叠空白、trim；首字符大写（`Locale.ROOT`）；长度 > 50 截前 47 加 `...`（UTF-16 单元，同 JS）。
- `extractTodoItems`：`(?i)\*{0,2}Plan:\*{0,2}\s*\n` 定位；之后按 `(?m)^\s*(\d+)[.)]\s+\*{0,2}([^*\n]+)`
  逐条取；文本 trim→去尾部 `*{1,2}`→trim；过滤长度 ≤ 5、以 `` ` ``/`/`/`-` 开头；
  `cleanStepText` 后长度 > 3 才收，`step = 已收数 + 1`。
- `extractDoneSteps`：`(?i)\[DONE:(\d+)\]` 全部取数。
- `markCompletedSteps(text, items)`：对每个标记找同 `step` 的项置 `completed=true`；
  **返回值是标记总数**（pi 逐字，含未命中项的标记）。

### 2.2 `plan-mode/index.ts` → `PlanModeExtension`

状态：`planModeEnabled`、`executionMode`、`todoItems`、`toolsBeforePlanMode`（null ≙ undefined）。

注册形状：

- flag `plan`（boolean，默认 false）。
- 命令 `plan`（切换）与 `todos`（列进度）；快捷键 `ctrl+alt+p`（切换）。
- 工具 `tool_call`：plan 模式下 `bash` 命令不经 `isSafeCommand` ⇒
  `ToolCallEventResult(block=true, reason=…)`，reason 逐字含命令文本。
- `context`：plan 模式关闭时剔除陈旧 plan 上下文（见 §2.2.1）。
- `before_agent_start`：`event.messageType() != "user"` 直接返回；plan 模式注入
  `plan-mode-context`（`display=false`）；执行模式且有未完成项注入 `plan-execution-context`。
- `turn_end`：执行模式下从助手消息提取 `[DONE:n]`，有变化则刷新状态并持久化。
- `agent_end`：执行完成发送 `plan-complete`；plan 模式下列出待办并经 `ui.select` 三选一
  （Execute/Stay/Refine）；Refine 走 `ui.editor` 后 `sendUserMessage(..., followUp)`。
- `session_start`：`plan` flag 置真则进入 plan 模式；读最后一条 `customType="plan-mode"` 条目恢复状态；
  恢复且执行中时从最后一条 `plan-mode-execute` 之后重扫助手消息重建完成标记；最后按需启用工具并刷新状态。

工具集算法（静态化便于单测）：`unique` 保序去重；
plan = 「当前活动 − {edit,write} ＋ {read,bash,grep,find,ls,questionnaire}」；
normal = 「{read,bash,edit,write} ＋ 当前活动 − {plan∪normal 受管集}」；
`questionnaire` 在 Java 未注册时由 `setActiveTools` 静默忽略（既有语义），不造空工具。

持久化：`appendEntry("plan-mode", data)`，`data` 键为 `enabled/todos/executing/toolsBeforePlanMode`；
`todos` 为 `[{step,text,completed}]`，`toolsBeforePlanMode` 为 null 时**省略键**（pi JSON 丢 `undefined`；
`Entry.Custom` 的 `Map.copyOf` 不收 null 值）。恢复时按 `Integer/Boolean/List/Map` 宽转。

为可测性拆出两个包内静态方法：`planModeTools(List<String>)`、`normalModeTools(List<String>)`、
`restoreState(List<Entry>)`、`rebuildCompleted(List<Entry>, List<TodoItem>)`；事件处理器只做编排。

#### 2.2.1 两处窄差（D235）

1. **主题色**：pi 用 `ctx.ui.theme.fg("accent"|"warning"|"success"|"muted")` 与 `strikethrough`；
   Java `ExtensionUI.theme()` 是主题名窄投影（无结构化 Theme）。状态/部件输出纯文本：
   `📋 1/3`、`⏸ plan`、`☑ text`、`☐ text`（无颜色、无删除线）。
2. **`context` 的 `customType` 臂**：pi 先按 `msg.customType === "plan-mode-context"` 剔除自定义消息；
   Java `context` 事件载荷是 `Message`（LLM 投影，无 customType）。注入内容投影为 `UserMessage`
   且必含 `[PLAN MODE ACTIVE]`，因此保留文本过滤即可等价剔除；`customType` 分支不实现。

### 2.3 `reload-runtime.ts` → `ReloadRuntimeExtension`

- 命令 `reload-runtime`：调用捕获的 `ExtensionContext.reload()`（`SlashContext` 无该成员，沿用示例既有的
  “命令闭包捕获 per-extension ctx”写法）。
- 工具 `reload_runtime`：空对象参数，`execute` 置 `reloadRequested=true`，返回
  `ToolResult(content, details, null, terminate=true, List.of())`。
- `agent_settled`：有待处理请求时复位并
  `ctx.sendUserMessage("/reload-runtime", new SendUserMessageOptions(null, Boolean.TRUE))`——
  `expandPromptTemplates=true` 走命令派发而非模型输入（L5 W1 已实现）。

### 2.4 `trigger-compact.ts` → `TriggerCompactExtension`

- `previousTokens: Integer`（null ≙ pi 的 undefined/null，两者在判定中等价）。
- `turn_end`：`ctx.getContextUsage()` 的 `tokens()` 为 null ⇒ 返回；跨线条件
  `previousTokens != null && previousTokens <= 100_000`；随后写入当前值；仅当跨线且当前 > 100_000 才触发。
- `triggerCompaction(ctx, customInstructions)`：有 UI 先通知 `Compaction started`；
  `ctx.compact(new ExtensionCompactOptions(instr, onComplete, onError))`；回调分别通知
  `Compaction completed` / `Compaction failed: <message>`（ERROR）。
- 命令 `trigger-compact`：`args.trim()` 空 ⇒ null，否则作为 `customInstructions`。
- 纯判定拆为包内静态 `crossed(Integer previous, int current)` 供单测。

### 2.5 `custom-compaction.ts` → `CustomCompactionExtension`

`session_before_compact` 处理器逐步：

1. 通知 `Custom compaction extension triggered`（INFO）。
2. `ctx.modelRegistry().find("google", "gemini-2.5-flash")`；空 ⇒ 警告
   `Could not find Gemini Flash model, using default compaction` 并返回 null（走默认压缩）。
3. 合并 `preparation.messagesToSummarize()` 与 `turnPrefixMessages()`；通知
   `Custom compaction: summarizing N messages (<tokens> tokens) with <modelId>...`
   （`tokensBefore` 用 `%,d` 按运行时 locale 分组，pi `toLocaleString` 同义）。
4. `ConversationSerializer.serialize(allMessages)`（对位 `serializeConversation(convertToLlm(...))`：
   两个入参列表已是 `Message`）；有 `previousSummary` 时附
   `\n\nPrevious session summary for context:\n<summary>`。
5. 组装单条 `UserMessage`（提示词逐字含 goals/decisions/code/state/blockers/next 六节与 `<conversation>` 包裹）。
6. `ctx.modelRegistry().complete(model, new Context(null, List.of(userMessage)), options)`：
   `maxTokens=8192`、`signal=event.signal()`、`cacheRetention=NONE`、`sessionId=UuidV7.INSTANCE.next()`。
7. 取响应文本块以 `\n` 拼接；空白 ⇒ 未中止时警告
   `Compaction summary was empty, using default compaction` 并返回 null；中止时静默返回 null。
8. 成功 ⇒ `SessionBeforeCompactResult(false, new SuppliedCompaction(summary, firstKeptEntryId, tokensBefore, usage, null))`。
9. 异常 ⇒ 通知 `Compaction failed: <message>`（ERROR）并返回 null（回落默认压缩）。

### 2.6 `send-user-message.ts` → `SendUserMessageExtension`

四个命令逐字：

- `ask`：空参警告 `Usage: /ask <message>`；非 idle 警告 `Agent is busy. Use /steer or /followup instead.`；
  否则 `sendUserMessage(args)`。
- `steer`：空参警告；idle ⇒ 普通发送；忙 ⇒ `deliverAs=STEER`。
- `followup`：空参警告；idle ⇒ 普通发送；忙 ⇒ `deliverAs=FOLLOW_UP` 并通知 `Follow-up queued`。
- `askwith`：非 idle 警告 `Agent is busy`；否则发两段文本块
  `User request: <args>`＋`Please respond concisely.`。

命令处理器使用捕获的 per-extension ctx（`isIdle/ui/sendUserMessage` 均在其上）。

### 2.7 `system-prompt-header.ts` → `SystemPromptHeaderExtension`

- `agent_start`：`setStatus("system-prompt", "System: " + ctx.getSystemPrompt().length() + " chars")`
  （Java `String.length()` 与 JS 同为 UTF-16 单元）。
- `session_shutdown`：`setStatus("system-prompt", null)`。

### 2.8 `handoff.ts` → `HandoffExtension`

分支消息构造（对位 `getHandoffMessages`）：

- `Entry.Message` ⇒ 其 `message()`；
- `Entry.BashExecution` ⇒ 按 `excludeFromContext` 与 `ContextEntries` 既有 bash 投影规则转 user 文本；
- `Entry.Compaction` ⇒ `ContextEntries` 的压缩摘要 user 文本（与 pi `convertToLlm(compactionSummary)` 同前缀）；
- 其余条目跳过；`toolResult` 等已由条目投影覆盖的形态不重复计入。
找不到压缩 ⇒ 全 branch 投影；找到 ⇒ 压缩条目＋`[firstKeptEntryId, compactionIndex)`＋压缩之后条目。

命令 `handoff`：

1. `!"tui".equals(ctx.mode())` ⇒ 错误 `handoff requires interactive mode`。
2. `ctx.getModel()` 空 ⇒ 错误 `No model selected`。
3. 目标为空 ⇒ 错误 `Usage: /handoff <goal for new thread>`。
4. 分支消息为空 ⇒ 错误 `No conversation to hand off`。
5. `ConversationSerializer.serialize(messages)`；取 `ctx.sessionManager().getSessionFile()`。
6. 生成提示词：`ctx.ui().custom(factory, null)`；工厂内**同步**执行
   `modelRegistry.complete(model, new Context(SYSTEM_PROMPT, List.of(userMessage)), options)`，
   `cacheRetention=NONE`、`sessionId=UuidV7`、`stopReason=ABORTED` ⇒ `done(null)`，异常 ⇒ `done(null)`，
   否则 `done(文本块以 "\n" 拼接)`；工厂返回只读文本组件（渲染 `Generating handoff prompt...`）。
7. `result == null` ⇒ 通知 `Cancelled`（INFO）。
8. `ctx.ui().editor("Edit handoff prompt", result)`；null ⇒ 通知 `Cancelled`。
9. `ctx.newSession(new ExtensionNewSessionOptions(currentSessionFile, replacementCtx -> { setEditorText(editedPrompt);
   notify("Handoff ready. Submit when ready.", INFO); }))`；`cancelled` ⇒ 通知 `New session cancelled`。

#### 2.8.1 窄差（D236）

pi 的 `BorderedLoader` 是异步加载组件（转圈＋Esc 取消＋`loader.signal`）。Java `ui.custom` 为阻塞方言、
`modelRegistry.complete` 同步：本移植在工厂内同步生成后立即 `done(text)`，不再驱动转圈动画；
可观察结果（生成文本/取消/null）与中止语义一致。转圈动画与 Esc 中止登记为 D236。

## 3. 宿主 API 与缺口

结论：**零新增宿主成员**。逐项对位：

| 消费 | 宿主来源 |
|---|---|
| `ctx.getContextUsage/compact/reload/isIdle/getSystemPrompt/newSession/getModel/modelRegistry` | B284（docs/50 §2.3） |
| `sessionManager.getEntries/getBranch/getLabel/getLeafEntry/getSessionFile` | B285 |
| `before_agent_start.messageType`、`turn_end.message`、`agent_end.messages`、`agent_settled`、`session_start`、`session_shutdown`、`session_before_compact` | 既有事件族 |
| `ToolCallEventResult`、`ContextEventResult`、`BeforeAgentStartEventResult`、`SessionBeforeCompactResult`、`SuppliedCompaction` | 既有结果族 |
| `setStatus/setWidget/select/editor/custom/notify/theme` | `ExtensionUI`（theme 为名投影，见 D235） |
| `appendEntry/registerFlag/getFlag/registerShortcut/getActiveTools/setActiveTools` | 既有扩展 API |
| `ConversationSerializer`、`UuidV7`、`ContextEntries` | `pi-java-agent-core` 既有 |

测试夹具增补（仅 test 源码）：

- `ExampleFixtures.ScriptedUi`：新增 `editor` 脚本应答、`custom`（同步调工厂并回传 `done` 值）、
  `statuses`（键→值，null 记删除）与 `widgets`（键→行集）录表。
- `ExampleFixtures.newSession` 增参重载，允许追加 `--plan` 等 unmatched 扩展 flag。

## 4. 验收测试

| 测试类 | 关键断言 |
|---|---|
| `PlanModeUtilsTest` | `isSafeCommand` 破坏性/白名单各取样；`cleanStepText` 去格式/前缀/截断；`extractTodoItems` 只收 `Plan:` 后的编号项；`[DONE:n]` 提取与完成标记（含未命中仍计数） |
| `PlanModeExtensionTest` | `/plan` 切换后活动工具集 = 期望；`tool_call` 拦截 `rm -rf` 放行 `ls`；`before_agent_start` 仅 `messageType=user` 注入；`context` 关闭时剔除 `[PLAN MODE ACTIVE]`；`--plan` 启动即 plan 模式；`agent_end` Execute 分支发出两条 followUp；`restoreState/rebuildCompleted` 静态单测 |
| `ReloadRuntimeExtensionTest` | 工具结果 `terminate=true`；命令调用触发扩展重载（静态计数 +1）；`agent_settled` 派发命令（轮询等待） |
| `TriggerCompactExtensionTest` | `crossed(100_000, 100_001)=true`、`crossed(100_001, 100_002)=false`、`crossed(null, …)=false`；`/trigger-compact` 通知 `Compaction started` 且以完成/失败通知收尾 |
| `CustomCompactionExtensionTest` | 自定义摘要写入 `Entry.Compaction`（`fromHook=true`、`tokensBefore`、`firstKeptEntryId`）；google faux 消费一次；空摘要 ⇒ 警告＋默认压缩（`fromHook` 非 true） |
| `SendUserMessageExtensionTest` | 空闲 ⇒ 直发并产生回合；忙 ⇒ `/ask` 警告、`/steer` 以 STEER 入队、`/followup` 以 FOLLOW_UP 入队并通知；`/askwith` 两段文本 |
| `SystemPromptHeaderExtensionTest` | `agent_start` 写入 `System: <len> chars`；`session_shutdown` 删除该键 |
| `HandoffExtensionTest` | 非 tui 模式报错；空分支报错；生成成功→编辑器回填→取消切换时通知 `New session cancelled`；成功后伴生扩展观察到 `session_shutdown{new}` |

测试运行：`mvn test -pl pi-java-examples -am`（编码闸门随 `validate`）。

## 5. 窄差与登记

| 编号 | 内容 | 处置 |
|---|---|---|
| D235 | plan-mode 主题色/删除线降级为纯文本；`context` 的 `customType` 分支不作判定（文本等价） | 本包实施后写入 docs/04 |
| D236 | handoff 加载器以同步方言实现，无转圈动画与 Esc 中止动画 | 本包实施后写入 docs/04 |

不新增账面条目：W2 完成后 examples 账面 +8（21 → 29/88）。分母已按用户裁决 2026-11-09 从 93 收到 88（D237：overlay×2、游戏×2、mac-system-theme 出分母），W2 本身不在排除清单内。

## 6. 实施顺序与验证

1. `PlanModeUtils`＋单测 → 2. `PlanModeExtension`＋测试 → 3. `TriggerCompact`/`CustomCompaction` →
   4. `SendUserMessage`/`SystemPromptHeader` → 5. `ReloadRuntime`/`Handoff` → 6. 全量
   `mvn test -pl pi-java-examples -am`。
2. 全绿后回填 `docs/04`（D235/D236）、`docs/06`（L8 行）、`docs/07` §3（+8 件清单）、
   `docs/map/04` E5 段。

## 7. 实施记录（2026-11-09）

### 7.1 单元落点

| # | Java 类 | 验收测试 |
|---|---|---|
| 1 | `PlanModeUtils`（静态纯函数＋可变 `TodoItem`） | `PlanModeUtilsTest`（11 项） |
| 2 | `PlanModeExtension` ＋纯逻辑 `PlanModeState` | `PlanModeExtensionTest`（9 项） |
| 3 | `ReloadRuntimeExtension`（命令＋`reload_runtime` 工具） | `ReloadRuntimeExtensionTest`（4 项） |
| 4 | `TriggerCompactExtension` | `TriggerCompactExtensionTest`（3 项） |
| 5 | `CustomCompactionExtension` | `CustomCompactionExtensionTest`（3 项） |
| 6 | `SendUserMessageExtension` | `SendUserMessageExtensionTest`（5 项） |
| 7 | `SystemPromptHeaderExtension` | `SystemPromptHeaderExtensionTest`（2 项） |
| 8 | `HandoffExtension`（＋`LoaderComponent` 占位） | `HandoffExtensionTest`（6 项） |

辅助件（非计分单元，包内私有）：`ExampleCommands`（`registerCommand` 的处理器形状）；`PlanModeState`（工具集算法／持久化／恢复／注入模板常量，从 `PlanModeExtension` 拆出以满足单文件 ≤500 行纪律）。§2.2 要求的 `planModeTools`／`normalModeTools`／`restoreState`／`rebuildCompleted` 均落为 `PlanModeState` 的包内静态方法，测试逐一覆盖。

### 7.2 实施期宿主发现（不改宿主，登记在案）

1. **`getFlag` 必须走 per-extension 上下文**：宿主基础上下文的 flag 面一律抛 `assertActive`，故 `plan-mode` 在 register 期捕获 per-extension ctx 读 `plan` flag（`appendEntry`／`setActiveTools`／`sendMessage`／`sessionManager` 走事件载荷的宿主上下文即可）。
2. **`getSystemPrompt` 的时点**：`agent_start` 窗内有值（示例状态段写 `System: <len> chars` 的实数），run 结束后渲染态归空；测试用伴生探针在同一时点取长度对账。

### 7.3 夹具增补（test 源码）

`ExampleFixtures`：`newSession(..., String... extraArgs)`（`--plan` 等扩展 flag）、`sequenceText(name, text, count)`；`ScriptedUi` 增 `editorAnswer`／`editor`、`custom`（同步调工厂并回传 `done`）、`statuses`（值 `null` ＝删键）、`widgets`、`editorTexts`。

### 7.4 验证结果

- `mvn -pl pi-java-examples verify`：**125 测 0 失败**（W1 后为 82，+43），Checkstyle 零错误、SpotBugs 零告警。
- 注册表 27 项（W1 后 20，+7 个扩展类；`PlanModeUtils`/`PlanModeState`/`ExampleCommands` 为辅助类不计）。
- 账面：examples **29/88＝32.95%**（21→29）；余 59 件（5 已修改＋54 未移植），下一步 W3 会话树/持久化。

### 7.5 与设计的偏离

1. **`trigger-compact` 的「压缩完成」通知在测试命中失败臂**：`CompactionService` 对「转录太小」抛 `Nothing to compact: transcript too small`，示例的 `onError` 回调因此通知 `Compaction failed: …`；验收口径为「started ⇒ 完成/失败必须来一条」（docs/27 §4 W2 原文即此），完成臂由 `onComplete` 直接通知，未在测试中强行构造 2 万 token 转录。
2. **`custom-compaction` 的「找不到模型」臂不可测**：`modelRegistry.find` 走内置目录（`google/gemini-2.5-flash` 恒可解析），该分支在 Java 恒定不可达；空摘要回落臂与正常接管臂均已覆盖。
3. **`plan-mode` 的 `--plan` 启动臂**：扩展 flag 经 CLI unmatched 绑定后才生效，`session_start` 在该绑定之后发射，故启动即计划态可测（测试已覆盖）。
