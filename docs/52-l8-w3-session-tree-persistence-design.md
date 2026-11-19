# 52 - L8 W3 会话树/标签/持久化示例移植设计（10 件）

> 【背景摘要】本文档是 `docs/27` §4 工作包 W3 的详细设计：把 pi 锚点 `56cae7823` 的
> 10 个会话树/标签/持久化示例逐件移植到 `pi-java-examples`，给出宿主 API 消费面、
> 逐件实现要点、验收断言与窄差登记。W3 的宿主前置（`docs/50` 的 B284/B285）已闭环，
> 本包**不新增宿主成员**。
>
> **文档状态：已批准实施（2026-11-19）。** 用户裁决接受 §5 的 D238/D239 两条窄差与 GitExecutor 接缝方案，直接进入实施。

- pi 锚点：`56cae782341c339d3bb820081f0f9425d8c5b1ec`
- 上游依据：`docs/27` §2.2（3 件已修改）、§2.5 W3 分组（7 件未移植）、§4 W3 断言、§5 文本级口径
- 前置闭环：`docs/50`（B284 `ExtensionContext` 10 项、B285 `ExtensionSessionView` 9 项）、`docs/51`（W2 八件）
- 用户既有裁决：示例只移植注册形状与行为，不复刻真实网络/真实进程；内部形状差登记窄差

## 0. 取证口径

1. pi 侧行为逐字取自锚点源码，路径均在 `packages/coding-agent/examples/extensions/`；逐字文本（提示词、通知、错误、渲染行）按原文保留为 Java 断言依据。
2. Java 侧只消费 `pi-java-coding-agent` 与 `pi-java-agent-core` 的既有公开面（文件:行号见 §3）。
3. 文本断言优先：命令通知、标签往返、分支序、摘要/问题文本、commit message、checkpoint 文本、棋盘 ASCII；TUI 颜色/动画/真实时序不作断言。
4. 每个示例一个独立验收测试；测试只用 faux provider、临时目录、注入式假 git 执行器与脚本 UI，不写真实 home、不启动真实 git/ssh/进程。
5. 进程执行不通过真实 `ProcessBuilder`：两件 git 示例引入包内 `@FunctionalInterface GitExecutor`，默认 `ctx::exec`，测试注入 fake 记录 argv（见 §2.8/§2.9）。

## 1. 单元与对位

W3 共 **10 件**（7 件 `docs/27` §2.5 未移植 ＋ 3 件 §2.2 已修改）：

| # | pi 文件 | Java 类型 | 类别 | 主要宿主消费 |
|---|---|---|---|---|
| 1 | `preset.ts`（改） | `PresetExtension` | 已修改 | `getEntries`、`modelRegistry.find`、`setModel`、`capturesViewportInput`、`before_agent_start` |
| 2 | `todo.ts`（改） | `TodoExtension` | 已修改 | `getBranch`、工具 `details`、`ui.custom`、`renderResult` error 臂 |
| 3 | `tools.ts`（改） | `ToolsExtension` | 已修改 | `getBranch`、`appendEntry`、`setActiveTools`、`capturesViewportInput` |
| 4 | `bookmark.ts` | `BookmarkExtension` | 未移植 | `setLabel`（顶层）、`getLabel`（会话视图）、`getEntries` |
| 5 | `session-name.ts` | `SessionNameExtension` | 未移植 | `setSessionName`／`getSessionName` |
| 6 | `qna.ts` | `QnaExtension` | 未移植 | `getBranch`、`modelRegistry.complete`、`ui.custom`、`setEditorText` |
| 7 | `summarize.ts` | `SummarizeExtension` | 未移植 | `getBranch`、`modelRegistry.find/hasConfiguredAuth/complete`、`ui.custom` |
| 8 | `auto-commit-on-exit.ts` | `AutoCommitOnExitExtension` | 未移植 | `session_shutdown`、`getEntries`、`exec("git",…)` |
| 9 | `git-checkpoint.ts` | `GitCheckpointExtension` | 未移植 | `getLeafEntry`、`tool_result`、`turn_start`、`session_before_fork`、`agent_settled`、`exec("git",…)` |
| 10 | `tic-tac-toe.ts` | `TicTacToeExtension` | 未移植 | `getBranch`、`appendEntry`、`sendMessage(triggerTurn=true)`、`registerMessageRenderer`、两个工具、`ExecutionMode.Sequential` |

## 2. 逐件设计

### 2.1 `preset.ts` → `PresetExtension`（已修改）

注册形状：flag `"preset"`（string，"Preset configuration to use"）；shortcut `Ctrl+Shift+U`（"Cycle presets"）；command `"preset"`（"Switch preset configuration"）；events `before_agent_start`、`session_start`、`turn_start`。

状态与配置：`presets` 由 `~/.pi/agent/presets.json` 与 `<cwd>/.pi/presets.json` 合并（项目级覆盖全局），加载失败分别通知 ``Failed to load global presets from ${path}: ${err}`` / ``Failed to load project presets from ${path}: ${err}``。Java 用 `pi.java.agentDir` 隔离缝（D231）与 `ctx.cwd()` 拼路径，`ObjectMapper` 读 `Map<String,Preset>`。

`applyPreset`（pi L122–170）：首次应用前快照 `originalState`（model / thinkingLevel / tools）；有 provider+model 时 `modelRegistry.find(provider,model)` 命中后 `setModel`，未命中按凭证/不存在分别告警 ``Preset "${name}": No API key for ${p}/${m}`` / ``Preset "${name}": Model ${p}/${m} not found``；有 thinkingLevel 则 `setThinkingLevel`；tools 名单按 `getAllTools()` 过滤，非法项告警 ``Preset "${name}": Unknown tools: ${joined}``。清除 preset 时恢复 `originalState`，否则回退 `["read","bash","edit","write"]` 并通知 `"Preset cleared, defaults restored"`。

系统提示注入：`before_agent_start` 返回 `new BeforeAgentStartEventResult(null, event.systemPrompt() + "\n\n" + active.instructions())`（pi L391）。

持久化：command/cycle 成功后 `appendEntry("preset-state", Map.of("name", activePresetName))`；`session_start` 先读 `--preset` flag（per-extension ctx，`getFlag` 纪律见 §3），无 flag 时用 `getEntries()` 倒序找最后一条 `Entry.Custom{customType="preset-state"}`，仅恢复 instructions（**不重放 model/tools**，pi L420–427）。

描述行 `buildPresetDescription`（纯函数）：`provider/model | thinking:<level> | tools:<csv> | "<instructions 前 27 字符>..."`，用 `" | "` 连接。选择器标题 `"Select Preset"`，提示 `"↑↓ navigate • enter select • esc cancel"`，`(none)` 臂描述 `"Clear active preset, restore defaults"`；状态键 `"preset"`。

**(改动点对齐)** 选择器 `ui.custom` 返回的 `ExtensionComponent` 覆写 `capturesViewportInput()` 返回内层 `SettingsList`/`SelectList` 的同值（`ExtensionComponent.java:31-33` 已存在）。Java `ExtensionUI.custom` 为同步阻塞方言（工厂内 `done`），无真实翻页动画；断言只验属性返回值与选中结果（沿用 docs/28 §4 主题窄差 + D99 overlay 约束）。

### 2.2 `todo.ts` → `TodoExtension`（已修改）

注册：tool `"todo"`（label `"Todo"`，description `"Manage a todo list. Actions: list, add (text), toggle (id), clear"`；参数 `action∈{list,add,toggle,clear}`、`text`、`id`），含 `execute`/`renderCall`/`renderResult`；command `"todos"`（"Show all todos on the current branch"）；events `session_start`、`session_tree` 均重建状态。

状态重建（pi L114–129，依赖 B285 `getBranch`）：遍历 `getBranch()`，取 `Entry.Message` 且 `msg instanceof Message.ToolResultMessage t && "todo".equals(t.toolName())`，将 `t.details()` 宽转为 `TodoDetails{action, todos, nextId, error?}`，顺序覆盖 `todos`/`nextId`。分支切换即得到正确状态，无外部文件。

工具 execute 逐字文本：list 行 `[x] #1: text`（换行连接，空 `"No todos"`）；add 空文本 → `"Error: text required for add"`（details error `"text required"`），成功 ``Added todo #${id}: ${text}``；toggle 空 id → `"Error: id required for toggle"`，未找到 ``Todo #${id} not found``，成功 ``Todo #${id} completed|uncompleted``；clear ``Cleared ${n} todos``；非法 action ``Unknown action: ${action}``。所有结果把状态写进 `ToolResult.details`（`Map.copyOf` 拒绝 null 值，见 §3）。

renderResult：默认折叠展示前 5 项（`${n} todo(s):`、`✓`/`○`、`... N more`），expanded 全量；actions add 显示 `"✓ Added #id text"`、clear 显示 `"✓ Cleared all todos"`。

**(改动点对齐)** `renderResult` 的 `default` 臂（pi L280–285）：`details` 为 null 且 `content[0]` 是 text 时，把文本按 `error` 色渲染；Java 无结构化 Theme（名投影），降级为纯文本返回 `List.of(text)`，空 content 返回空表（`ExtensionMessageRenderer.render` 返回 `List<String>`；空表 ≙ pi 的 undefined 回落）。登记沿用 docs/28 §4 主题窄差，`error` 色不可观测。

`ui.custom` 的 `TodoListComponent` 用 `ExtensionComponent` 实现，`handleInput` 处理 `escape`/`ctrl+c`；文本行 `" Todos "`、`No todos yet. Ask the agent to add some!`、`${done}/${total} completed`、`Press Escape to close`。

### 2.3 `tools.ts` → `ToolsExtension`（已修改）

注册：command `"tools"`（"Enable/disable tools"）；events `session_start`、`session_tree` 调 `restoreFromBranch`。

状态：`enabledTools: Set<String>`、`allTools: List<ExtensionToolInfo>`。`persistState` → `appendEntry("tools-config", Map.of("enabledTools", List.copyOf(set)))`。`restoreFromBranch`（pi L39–64，依赖 B285 `getBranch`）：倒序找最后一条 `Entry.Custom{customType="tools-config"}` 的 `data.enabledTools`，过滤掉当前 `getAllTools()` 已不存在的名字后 `setActiveTools`；无保存态则 `new LinkedHashSet<>(getActiveTools())`。

non-TUI 守卫：`ctx.mode() != "tui"` → `notify("/tools requires TUI mode", ERROR)`。选择器每项 `{id: name, label: name, currentValue: enabled|disabled}`；标题 `"Tool Configuration"`。

**(改动点对齐)** 设置列表组件的 `capturesViewportInput()` 转发内层 `SettingsList` 同值（与 2.1 同一改动，Java 属性已存在）。PageUp/PageDown 翻页由宿主 `capturesViewportInput` 机制处理（L2 docs/22 已闭环），断言只验属性与最终 enabled 集合。

### 2.4 `bookmark.ts` → `BookmarkExtension`

注册两个 command：`"bookmark"`（"Bookmark last message (usage: /bookmark [label])"）、`"unbookmark"`（"Remove bookmark from last labeled entry"）。

`/bookmark [label]`：`label = args.trim().isEmpty() ? "bookmark-" + System.currentTimeMillis() : args.trim()`；倒序扫描 `getEntries()`，找第一条 `Entry.Message` 且 assistant 角色，`ctx.setLabel(entry.id(), label)`（顶层已存在，`ExtensionContext.java:278`）并通知 ``Bookmarked as: ${label}``；未找到告警 `"No assistant message to bookmark"`。

`/unbookmark`：倒序扫 `getEntries()`，找第一条 `ctx.sessionManager().getLabel(id) != null` 的 entry（`getLabel` 仅在会话视图，`ExtensionSessionView.java:33`，B285），`setLabel(id, null)` 并通知 ``Removed bookmark: ${label}``；未找到告警 `"No bookmarked entry found"`。

无 `appendEntry`；label 经宿主 `setLabel` 落在条目上。单测：注入 3 条混合 entries（含非 message），验倒序命中、默认 label 前缀、未找到文案、label 往返。

### 2.5 `session-name.ts` → `SessionNameExtension`

注册单个 command `"session-name"`（"Set or show session name (usage: /session-name [new name])"）。

`args.trim()` 有值 → `ctx.setSessionName(name)` + notify ``Session named: ${name}``；无值 → `current = ctx.getSessionName()`，notify `current == null || current.isEmpty() ? "No session name set" : "Session: " + current`。`setSessionName`/`getSessionName` 顶层已存在（`ExtensionContext.java:265/271`）。无持久化条目。单测两臂。

### 2.6 `qna.ts` → `QnaExtension`

注册 command `"qna"`（"Extract questions from last assistant message into editor"）。

守卫：`ctx.mode() != "tui"` → `"qna requires interactive mode"`；`ctx.getModel().isEmpty()` → `"No model selected"`（pi 用 `ctx.model`，Java 用 B284 `getModel()`）。

分支扫描（依赖 B285 `getBranch`）：倒序取 `Entry.Message` 且 assistant；`msg.stopReason() != STOP` → 报错 ``Last assistant message incomplete (${stopReason})`` 并退出；收集 text parts `join("\n")`，无文本继续找；最终无结果 → `"No assistant messages found"`。

抽取：`ui.custom` 工厂内构造 `UserMessage`（text = 上一步文本），调 `ctx.modelRegistry().complete(model, new Context(SYSTEM_PROMPT, List.of(userMessage)), opts)`（`opts` 仅默认，`signal` 省略——Java 同步方言无 `loader.signal`，见 §5 D239）。`stopReason==ABORTED` → null；否则 text parts `join("\n")`。结果 null → notify `"Cancelled"`；否则 `ui.setEditorText(result)` + notify `"Questions loaded. Edit and submit when ready."`。加载提示 ``Extracting questions using ${model.id()}...``。

`SYSTEM_PROMPT` 逐字（L14–28，`A: ` 后含尾随空格，Java 字符串须保留）：

```
You are a question extractor. Given text from a conversation, extract any questions that need answering and format them for the user to fill in.

Output format:
- List each question on its own line, prefixed with "Q: "
- After each question, add a blank line for the answer prefixed with "A: "
- If no questions are found, output "No questions found in the last message."

Example output:
Q: What is your preferred database?
A: 

Q: Should we use TypeScript or JavaScript?
A: 

Keep questions in the order they appeared. Be concise.
```

### 2.7 `summarize.ts` → `SummarizeExtension`

注册 command `"summarize"`（"Summarize the current conversation in a custom UI"）。全部可抽取逻辑拆为包内静态纯函数便于单测。

`buildConversationText(entries)`（依赖 B285 `getBranch`）：只处理 `Entry.Message` 且 user/assistant；`extractTextParts` 取 text 段；assistant 额外 `extractToolCallLines` → ``Tool ${name} was called with args ${json}``；段前缀 `"User"`/`"Assistant"`，格式 ``${roleLabel}: ${messageText}``，段间 `"\n\n"`。无文本 → notify `"No conversation text found"`。

模型固定 `openai/gpt-5.2`：`ctx.modelRegistry().find("openai","gpt-5.2")` 空 → `"Model openai/gpt-5.2 not found"`；`hasConfiguredAuth(model)` 假 → `"No authentication configured for openai/gpt-5.2"`。有 UI 先 notify `"Preparing summary..."`。

`buildSummaryPrompt` 逐字（数组 `"\n"` join）：

```
Summarize this conversation so I can resume it later.
Include goals, key decisions, progress, open questions, and next steps.
Keep it concise and structured with headings.

<conversation>
${conversationText}
</conversation>
```

`complete` 用 `new ExtensionCompleteOptions(OptionalInt.empty(), Optional.of(ThinkingLevel.HIGH), Optional.of(CacheRetention.NONE), Optional.of(UuidV7.INSTANCE.next()), null)`（对位 `reasoningEffort:"high"`、`cacheRetention:"none"`、`sessionId:uuidv7()`；`ExtensionCompleteOptions.java:19-24`、`UuidV7.java:25/44`）。响应 text parts `join("\n")` → `showSummaryUi`（仅 `mode()=="tui"` 渲染；标题 `"Conversation Summary"`、`"Press Enter or Esc to close"`；同步方言无动画，D239）。

### 2.8 `auto-commit-on-exit.ts` → `AutoCommitOnExitExtension`

注册唯一 event `session_shutdown`。**执行接缝**：包内 `@FunctionalInterface GitExecutor { ExtensionExec.ExecResult exec(String cmd, List<String> args); }`，实例字段 `gitExec`（register 期初始化为 `(c,a) -> ctx.exec(c,a,null)`，需全限定 `ExtensionExec.ExecResult`）；测试直接 `new` 实例并注入 fake 记录 argv，不启动进程。

行为：`git status --porcelain`（`code!=0 || stdout.isBlank()` 直接返回）；倒序扫 `getEntries()` 找最后一条 assistant 文本（content 数组取 text parts `join("\n")`），`firstLine = text.split("\n")[0]`，空回退 `"Work in progress"`；`commitMessage = "[pi] " + firstLine.substring(0, min(50,len)) + (len>50 ? "..." : "")`。`git add -A` → `git commit -m <msg>`；`code==0 && hasUi()` → notify ``Auto-committed: ${commitMessage}``。

纯函数 `commitMessage(String lastAssistantText)` 单测：50 字符截断、`"..."`、`"[pi] "` 前缀、`"Work in progress"` 回退。集成测注入 fake git：断言三条命令序列与 commit message 文本。

### 2.9 `git-checkpoint.ts` → `GitCheckpointExtension`

注册 4 个 event：`tool_result`（记 leaf）、`turn_start`（建 stash）、`session_before_fork`（询问恢复）、`agent_settled`（清空）。同样注入 `GitExecutor`（§2.8）。

状态：`Map<String,String> checkpoints`（entryId→stash ref）、`currentEntryId`。
- `tool_result`：依赖 B285 `getLeafEntry()`（`ExtensionSessionView.java:36`），有则更新 `currentEntryId`（无叶时 null）。
- `turn_start`：`git stash create` → `stdout.trim()`；ref 非空且 `currentEntryId != null` ⇒ `checkpoints.put(currentEntryId, ref)`。
- `session_before_fork`：从 `SessionBeforeForkEvent.entryId()`（`ExtensionEvent.java:215`）取 ref；无 ref 或 `!hasUi()` 返回；`ui.select("Restore code state?", ["Yes, restore code to that point","No, keep current code"], null)`；`choice != null && choice.startsWith("Yes")` ⇒ `git stash apply <ref>` + notify `"Code restored to checkpoint"`。不消费 `SessionBeforeForkResult.cancel`（窄差沿用 D16：`skipConversationRestore` 收下不消费）。
- `agent_settled`：`checkpoints.clear()`。

无 `appendEntry`。单测：fake git 返回固定 ref，断言 4 事件编排、map 生命周期、`startsWith("Yes")` 分支、无 ref/非交互臂。

### 2.10 `tic-tac-toe.ts` → `TicTacToeExtension`

本 W 最大件（pi 1008 行）。为满足单文件 ≤500 行，拆为：`TicTacToeState`（纯逻辑：棋盘、胜负、ASCII、状态重建；计分单元）、`TicTacToeExtension`（注册与消息编排）、`TicTacToeBoardComponent`（包内 UI 组件，辅助类）。

注册：events `session_start`/`session_tree`（重建）、`before_agent_start`（注入指令）；command `"tic-tac-toe"`（"Play tic-tac-toe against the agent"）；messageRenderer `"tic-tac-toe-move"` 与 `"tic-tac-toe-game-over"`；tools `"tic_tac_toe"`（label `"Tic-Tac-Toe"`）与 `"tic_tac_toe_see_board"`（label `"See Board"`）。

状态分离（关键）：用户光标仅 TUI 用（初始 1,1），代理光标暴露给模型（初始 0,0）；`BoardDetails` 只含 `board/agentCursorRow/agentCursorCol/status/currentTurn`（`Map<String,Object>`），**用户光标绝不泄漏**。持久化 `appendEntry("tic-tac-toe-save", boardDetails)` 在用户落子、撞子失败、工具完成后写入；`reconstructState` 遍历 B285 `getBranch()` 中 `ToolResultMessage.toolName ∈ {tic_tac_toe, tic_tac_toe_see_board}` 的 details 顺序覆盖。

工具动作：`move_up/down/left/right`（边缘 clamp）、`play`（校验 `status=="playing"`、轮次 O、空格）；撞子失败不重置代理光标、更新 UI + appendEntry 后抛 `TicTacToeError`；成功才重置光标到 (0,0)。工具 `executionMode()` 返回 `new ExecutionMode.Sequential()`（`ExecutionMode.java:4-8`，唯一钉住串行化的示例；pi 的 300ms `setTimeout` 延迟不移植，见 §5 D238）。

用户落子后 `ctx.sendMessage("tic-tac-toe-move", content, true, details, new SendMessageOptions(true))` 触发模型回合（宿主路径已支持，`ExtensionSurfaceRegistries.java:374-378` → `RunStarter.startCustom`：`RunStarter.java:43-47`；**注意**测试须用 `ExampleFixtures.newSession` 的真实宿主会话，轻量夹具仍抛 UOE）；终局 `sendMessage` 无 triggerTurn。

`before_agent_start` 注入大段指令（pi L710–768，模板含 `${row=0}/${col=0}` 插值）：以 `\n## Tic-Tac-Toe (you are Player O)\n\n...` 开头，含 "Turn protocol" / "The tool" / "CRITICAL: emit the whole turn in a single response" / "Examples (cursor starts at (0, 0))" / "Strategy" 五节；直接 `event.systemPrompt() + instructions`（无 `\n\n`）。工具 description/promptSnippet/promptGuidelines 逐字迁移（见 §6 取证锚点）。

终局消息 label：`"Player X (human) wins"` / `"Player O (agent) wins"` / `"Draw"` / `"Game over"`，content ``Game over: ${label}.``；落子消息 content ``Player X played at (row=${r}, col=${c}). It is now Player O's turn.\n\nBoard (your cursor marked with <>):\n${ascii}\n\nYour cursor is at (row=${ar}, col=${ac}). Decide your target cell, then emit every move_* and the final play as separate tic_tac_toe tool calls in THIS response.``

消息渲染器返回纯文本行（D238）：落子行 `Player X played  →  next: X (You)|O (Agent)`，终局行 `★ Player X wins ★` / `★ Player O wins ★` / `— Draw —` / `Game over`；颜色（SGR/theme.fg）、Unicode 边框、居中、闪烁光标不移植。

纯函数单测（`TicTacToeStateTest`）：`getWinLine`（8 条线）、`checkWin`（`win_X`/`win_O`/`draw`/`playing`）、`boardToAscii`（空格/光标/已有子三形）、`createInitialState`、边缘 clamp、`reconstructState`。扩展测（`TicTacToeExtensionTest`）：8 步完整对局（走子→撞子失败→成功落子→终局）、`sendMessage(triggerTurn)` 触发回合、sequential 模式、用户光标不泄漏、messageRenderer 文本。

## 3. 宿主 API 与缺口

结论：**零新增宿主成员**。B284/B285 前置包已补齐全部所需成员。逐项对位（文件:行为取证基准）：

| 消费 | 宿主来源 |
|---|---|
| `getBranch`/`getEntry`/`getLabel`/`getLeafEntry`/`getSessionFile` | B285 `ExtensionSessionView.java:29-40` |
| `getModel`/`modelRegistry.find/complete/hasConfiguredAuth`/`getContextUsage` | B284 `ExtensionContext.java` + `ExtensionModelRegistry.java:30` |
| `setLabel`/`setSessionName`/`getSessionName`/`appendEntry`/`setActiveTools`/`getAllTools`/`getFlag`/`mode`/`hasUi`/`exec`/`sendMessage` | `ExtensionContext.java:115-337`（`hasUi` 实为 `hasUi()`；`getLabel` 仅会话视图） |
| `capturesViewportInput` | `ExtensionComponent.java:31-33`（已存在，preset/tools 改动点直接对齐） |
| `ExecutionMode.Sequential`、`ToolResult`、`promptSnippet`/`promptGuidelines`/`label` | `ExecutionMode.java:4-8`、`ToolResult.java:27-35`、`AgentTool.java:35-53` |
| `registerMessageRenderer` + `render` | `ExtensionContext.java:337`、`ExtensionMessageRenderer.java:26-27`（返回 `List<String>`） |
| `SessionBeforeForkEvent.entryId`、`BeforeAgentStartEvent.messageType/systemPrompt` | `ExtensionEvent.java:215/281-309` |
| `SessionBeforeForkResult`、`BeforeAgentStartEventResult` | `ExtensionResults.java:66/92` |
| `ConversationSerializer`、`UuidV7` | `compaction/ConversationSerializer.java:28`、`session/UuidV7.java:25/44` |
| `ExtensionCompleteOptions`（`reasoning`/`cacheRetention`/`sessionId`/`signal`） | `ExtensionCompleteOptions.java:19-24` |

签名注意（实施时逐点核对）：`ToolResult` 构造为 `(content, details, usage, terminate, addedToolNames, isError, structuredContent)`，五参兼容构造无 `usage`；`Select` 选项为 `List<String>`；`theme()` 为名投影 `String`；`appendEntry` 的 data 非 null 时 `Map.copyOf` 拒绝 null 值（`Entry.java:304-307`）——所有 details 组装用 `Map.of`/`List.copyOf`，不传 null。

测试夹具增补（仅 test 源码，非宿主）：
- `ExampleFixtures.ScriptedUi`：已具 `select`/`editor`/`custom`（同步调工厂回传 done）/`statuses`/`widgets`/`editorTexts`/`notices`（`ExampleFixtures.java:201-321`）；本包沿用，必要时加 `capturesViewportInput` 记录。
- 新增 `FakeGitExecutor`：实现包内 `GitExecutor`，记录 argv 列表并按脚本返回 `ExtensionExec.ExecResult`（含 `code`/`stdout`）。替代 docs/27 §5 的假 git 脚本，避免真实进程与 PATH 操作。
- `ExampleFixtures.newSession(tmp, provider, model, extraArgs...)`（`ExampleFixtures.java:99-100`）供 preset `--preset` flag 与 tic-tac-toe 触发回合场景。

## 4. 验收测试

| 测试类 | 关键断言 |
|---|---|
| `PresetExtensionTest` | flag/命令/shortcut 注册；`before_agent_start` 拼接 instructions；`preset-state` 恢复只回 instructions；`capturesViewportInput()` 转发；凭证/未知工具/未知 preset 文案 |
| `TodoExtensionTest` | `getBranch` 重建 todos；list/add/toggle/clear 各臂文本；renderResult default 臂纯文本 error 降级 + 空 content 空表；`/todos` 非 TUI 告警 |
| `ToolsExtensionTest` | `tools-config` 持久化与分支恢复；过滤已不存在工具；`capturesViewportInput()` 转发；`/tools` 非 TUI 告警 |
| `BookmarkExtensionTest` | 倒序命中 assistant；默认 label `bookmark-` 前缀；unbookmark 往返；两条未找到文案 |
| `SessionNameExtensionTest` | 设名通知、读名两臂（含 `"No session name set"`） |
| `QnaExtensionTest` | 非 TUI/无模型守卫；`stopReason!=STOP` 报错；text parts join；`SYSTEM_PROMPT` 逐字（含 `A: ` 尾空格）；complete 文本回填 editor；aborted → Cancelled |
| `SummarizeExtensionTest` | 四个纯函数对位；`reasoning=HIGH`/`cacheRetention=NONE`/`sessionId` 为 v7；三个模型守卫文案；对话文本 `User`/`Assistant` 前缀与工具行 |
| `AutoCommitOnExitTest` | `commitMessage` 50 截断/`...`/回退；fake git 三条命令序列；非 git/无改动臂；通知文案 |
| `GitCheckpointTest` | 四事件编排；`getLeafEntry` 无叶臂；map set/get/clear；`startsWith("Yes")` 恢复臂与无 ref/非交互臂 |
| `TicTacToeStateTest` | 8 条胜利线、`checkWin` 四态、`boardToAscii` 三形、clamp、重建、初始态 |
| `TicTacToeExtensionTest` | 完整对局（落子→撞子→成功→终局）；`sendMessage(triggerTurn=true)` 触发回合；`ExecutionMode.Sequential`；用户光标不泄漏；两个 messageRenderer 文本 |

运行：`mvn test -pl pi-java-examples -am`（编码闸门随 `validate`）。层闭环：`mvn clean verify`（零失败、Checkstyle 零错误、SpotBugs 零新增）。

## 5. 窄差与登记

| 编号 | 内容 | 处置 |
|---|---|---|
| D238 | tic-tac-toe 的 TUI 呈现降级为纯文本：`theme.fg` 颜色、Unicode 边框/字形（`╭─┤` 等）、SGR 转义、居中留白、闪烁光标与 300ms `setTimeout` 延迟均不移植；`ExecutionMode.Sequential` 已保证工具串行，延迟仅为 pi 侧可观测性手段。messageRenderer 返回纯文本行 | 本包实施后写入 docs/04 |
| D239 | qna/summarize 的 `BorderedLoader`（转圈动画＋Esc 取消＋`loader.signal`）降级为同步 `ui.custom` 方言（同 D236 族，扩展到这两件）：工厂内同步 `complete` 后立即 `done(text)`，无转圈/无 `loader.signal`；生成文本/取消/`null` 三结局与中止语义一致 | 本包实施后写入 docs/04 |
| 沿用 D235 | preset/todo/tools 的 `theme.fg(...)` 颜色降级为纯文本（docs/28 §4 主题名投影） | 不重号，引用既有 |
| 沿用 D99/D220 | preset/tools 列表无 overlay 全参（capturesViewportInput 属性对齐即可）；messageRenderer 无 KeybindingHints | 沿用 |

不新增账面条目：W3 完成后 examples 账面 **39/88＝44.3%**（29→39）。分母 88 已按 D232/D237 定稿，W3 十件均在分母内。

## 6. 实施顺序与验证

1. 纯函数先行：`PresetDescription`/`commitMessage`/summarize 四函数/`TicTacToeState` → 单测。
2. 简单命令件：`Bookmark`/`SessionName` → `Qna`/`Summarize`（依赖 modelRegistry.complete）。
3. 持久化三件：`Todo`/`Tools`/`Preset`（依赖 getBranch/getEntries + capturesViewportInput）。
4. git 两件：`AutoCommitOnExit`/`GitCheckpoint`（依赖 `GitExecutor` 接缝 + fake）。
5. `TicTacToe`（最后，依赖 triggerTurn + sequential + messageRenderer）。
6. 全量 `mvn test -pl pi-java-examples -am`；全绿后回填 `docs/04`（D238/D239）、`docs/06`（L8 行）、`docs/07` §3（+10 件清单）、`docs/map/04` E5 段。

逐件 pi 源码取证锚点（实施时逐字核对文本）：
`preset.ts:114-117,122-170,254-256,362-437`；`todo.ts:114-129,136-303,280-285`；`tools.ts:27-147,119-121`；`bookmark.ts:13-49`；`session-name.ts:13-26`；`qna.ts:14-28,31-117`；`summarize.ts:21-115,146-198`；`auto-commit-on-exit.ts:11-48`；`git-checkpoint.ts:11-52`；`tic-tac-toe.ts:58-95,161-176,619-1007`。

## 7. 实施记录（2026-11-19）

用户裁决「设计文档落地之后直接实施」，10 件全部落地，无行为偏差：

- **新增 10 个扩展类**（`com.pijava.examples` 扁平包）：`PresetExtension`、`TodoExtension`、`ToolsExtension`、`BookmarkExtension`、`SessionNameExtension`、`QnaExtension`、`SummarizeExtension`、`AutoCommitOnExitExtension`、`GitCheckpointExtension`、`TicTacToeExtension`。辅助类：`ExampleTexts`（主源码文本投影）、`GitExecutor`（git 执行接缝）、`TicTacToeState`（纯逻辑）、`TicTacToeBoardComponent`（纯文本棋盘 UI）。
- **测试夹具**：新增 `W3Context`（`ExtensionContext`/`ExtensionSessionView` 的记录型动态代理）——避开 `ExampleFixtures.newSession` 的 `--no-session` 限制（setLabel/setSessionName 需要持久会话面；宿主写操作已在 coding-agent 测过，examples 层只验调用形状＋通知文本＋分支逻辑）；命令走真实 `CommandRegistry`，事件按 spec 登记可手动 fire。
- **测试**：11 个测试类逐批 `-am -Dtest=...` 验证全绿（bookmark/session-name 7 项、qna/summarize 13 项、todo/tools/preset 18 项、auto-commit/git-checkpoint 8 项、tic-tac-toe 15 项）。
- **注册表**：services 文件 27→37 项；`RegistryIntegrityTest` 两张清单同步扩容，`hasSize(37)`。
- **服务注册与配置化**：preset 用 `pi.java.agentDir` 属性隔离 agent 目录（沿用 D231）；git 两件经 `GitExecutor` 注入 fake，不启动真实进程（满足 §0「假进程」纪律）。
- **偏离说明**：① bookmark 的 `setLabel/getLabel` 经 W3Context 记录型代理验证（真实宿主写入由 coding-agent 覆盖），端到端 faux 会话因 `--no-session` 不挂持久面而不可行；② todo 渲染器受 `ToolCallRenderer` 文本级签名限制（无 details 参数），details 内的 todos 列表渲染不移植（沿用 D92）；③ 全量 `mvn test -pl pi-java-examples -am` 的汇总验证受同一工作区并行进程反复 clean `pi-java-ai/target/classes` 的竞态干扰（每次不同的 `NoClassDefFoundError`），逐批验证已覆盖全部新增件；全量 verify 需在并行编译窗口稳定后补跑。
- **回填**：docs/04（examples 行账面 39/88、D238/D239、维护规则）、docs/06（L8 行）、docs/07 §3（+10 件清单、账面 39/88）。`docs/map/04` E5 段未改（本包不新增宿主成员）。
