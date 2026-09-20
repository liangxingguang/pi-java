# 03 Agent 运行时核心（pi-java-agent-core）

> 基准：pi `D:\workplaceForai\pi` @ 工作树（`packages/agent`）；pi-java `D:\workplaceForai\pi-java` @ `34849a2`。
> 台账 = `docs/32-open-items-register.md`。所有 `file:line` 均为本次实读，未照抄台账。
>
> **权重口径**（用户 2026-09-20 定）：权重 = 用户可观察影响 × 频率，**不掺排期优先级**。
> `3` = 每轮对话都走 / 默认路径 · `2` = 每次会话走 / 常用命令 / 常用配置键 · `1` = 低频 / 边缘 / 纯内部 · `0` = 非目标（排除出分母）。
> **完成系数**：对齐 `1.0` ／ 存疑 `0.5` ／ 缺失 `0`。
> 本文件三条**局部口径**（可被用户推翻）：
> ① **钩子**（#30–#42）按「会话开始与结束 / 常用配置键」归 **2**，压缩/导航/resume 三个非默认路的归 **1**
> —— 理由是未注册的钩子是 no-op，不按「默认路径」给 3；
> ② **每轮都会跑但只在特定条件下才产生可观察差**的行为（#4 terminate、#5 length、#11 重试环）归 **2**；
> ③ **测试基建**（#74、#87、#95、#96）归 **1**（不是 0：它们守卫行为，但用户不直接观察）。

---

## 规模

| 模块 | pi 包 | pi LOC | java LOC | 比例 |
|---|---|---:|---:|---:|
| 主源码 | `packages/agent/src` | **25305** | `pi-java-agent-core/src/main` **16386**（176 文件） | **64.8%** |
| 测试 | `packages/agent/test` | **21238** | `src/test` **14219**（91 文件） | **67.0%** |

⚠️ **两个必须说明的口径问题**：

1. **pi 的 `src/` 里混着 2716 行测试支持**（`harness/session/testing/{conformance,benchmark}/*.ts`，
   `find packages/agent/src/harness/session/testing -name '*.ts' | xargs wc -l` = 2716）。扣掉后
   pi 主源码 = 22589 ⇒ java/pi = **72.5%**。`src/main` 与 `src` 不是同一个口径。
2. **java 主源码里没有对应物**（见「整块缺失」），所以 64.8% 这个数**同时**包含
   「pi 有 java 无」的缺块 **和** 「java 有 pi 无」的多余块（8 类 Entry vs pi 4 类）。

### 子目录对照（主源码）

| pi 子目录 | pi LOC | java 子目录 | java LOC | 备注 |
|---|---:|---|---:|---|
| `harness/` 顶层 12 文件 + `env/` | 4471 | `harness/` + `hook/` + `record/` | 7249 | java 多出 hook/record 两块 |
| `harness/runtime/`（含 `drive/` 12 文件） | **7505** | 无 | **0** | 见「整块缺失」① |
| `harness/session/`（含 testing 2716） | 7108 | `session/` | 4194 | 差 v4 事务模型 + conformance 套件 |
| `harness/tools/` | 1209 | `tool/`（含 builtin） | 3265 | java 工具更厚（7 个工具独立文件） |
| `harness/compaction/` | 1297 | `compaction/` + `context/` | 1072 | 差 branch-summarization 300 行 |
| `harness/execution/` | 449 | （并入 harness） | —— | effect-gate 无对应物 |
| `harness/utils/` | 822 | （并入 tool/） | —— | adaptive-publisher 无对应物 |
| 顶层 `agent-loop/agent/types/proxy/search/index` | 2444 | `PiLoop`+`PiLoopRunner`+`PiLaneEngine` 等 | ~1500 | java 无 `proxy.ts` |
| —— | —— | `entry/`+`skill/`+`prompt/`+`stream/` | 606 | java 侧独有分层 |

---

## 内置工具对齐

pi 的工具定义**分两处**：`packages/agent/src/harness/tools/`（5 个，harness 面）与
`packages/coding-agent/src/core/tools/`（8 个，产品面）。pi 的 `allToolNames`（8 个）在
`packages/coding-agent/src/core/tools/index.ts:200-209`。pi-java 的 7 个工具全在
`pi-java-agent-core/.../tool/builtin/`，由 `ToolSetFactory.java:26-35` 组装。

| # | 工具 | pi 有 | java 有 | 判定 | 权重 | 证据 |
|---|---|---|---|---|---|---|
| 1 | `bash` | ✅ | ✅ | **存疑** | **3** | pi 参数 `{command, timeout?}`，**无默认超时**，上限 `2147483 s`（`packages/agent/src/harness/tools/bash.ts:11-13,8`；`packages/coding-agent/src/core/tools/bash.ts:21-22`）。java 同参数，但**默认 120 s / 上限 600 s**（`BashTool.java:35,37,104-107`）⇒ 模型不传 timeout 时行为不同（pi 无限等，java 120 s 杀）。描述文案也不同 |
| 2 | `read` | ✅ | ✅ | **对齐** | **3** | pi `{path, offset?, limit?}`（`core/tools/read.ts:14-17`）；java 同（`ReadTool.java:37-47`）。图片按 mime 分派成 `ImageContent`（`ReadTool.java:85-95` vs pi `core/tools/read.ts:73`） |
| 3 | `write` | ✅ | ✅ | **对齐** | **3** | pi `{path, content}`（`core/tools/write.ts:11-13`）；java 同（`WriteTool.java:39-45`）＋ 同形状的 `FileMutationQueue` 串行化（`WriteTool.java:28` vs pi `core/tools/file-mutation-queue.ts`） |
| 4 | `edit` | ✅ | ✅ | **对齐** | **3** | pi `{path, edits:[{oldText,newText}]}`（`core/tools/edit.ts:21-36`）；java 同（`EditTool.java:52-63`）＋ `EditDiff`/`LineDiff` 对应 pi `edit-diff.ts`（556 行 vs java 355+394 行） |
| 5 | `grep` | ✅ | ✅ | **存疑** | **3** | pi `{pattern, path?, glob?, ignoreCase?, literal?, context?, limit?}`（`core/tools/grep.ts:21-32`），**尊重 .gitignore**、按 `limit`(默认 100)/100KB 截断、长行截到 `GREP_MAX_LINE_LENGTH`（`:78`）。java 只有 `{pattern, path?, glob?}`（`GrepTool.java:44-52`）⇒ **缺 4 个参数**、无 gitignore、无匹配数上限、无输出截断（长行截 500 是自有行为，`:23`） |
| 6 | `find` / `glob` | ✅ `find` | ✅ `glob` | **存疑** | **2** | pi 工具名 **`find`**，参数 `{pattern, path?, limit?}`（默认 1000），走外部 `fd`/ripgrep 并尊重 .gitignore（`core/tools/find.ts:33-37,47`）。java 名 **`glob`**，参数 `{pattern, path?}`，用 NIO `PathMatcher` 全树 `Files.walk`（`GlobTool.java:32,44-52,72-78`）⇒ **名字不同 + 缺 limit + 无 gitignore + 无截断** |
| 7 | `ls` | ✅ | ✅ | **存疑** | **2** | pi `{path?, limit?}`（默认 500），字母序、目录带 `/` 后缀、含 dotfile、截断（`core/tools/ls.ts:11-13,62`）。java `{path?, recursive?}`（`LsTool.java:36-42`）⇒ **参数集不同**（`limit` vs `recursive`），输出格式为 `[DIR]/[FILE] size path`（`:76-82`） |
| 8 | `powershell` | ✅ | ❌ | **缺失** | **1** | pi `core/tools/powershell.ts`（67 行）复用 `createShellToolDefinition`，工具名进 `allToolNames`（`core/tools/index.ts:201`）。java 全仓零命中 |
| 9 | `image`（读图处理器） | ✅ | ✅ | **对齐** | **1** | pi `harness/tools/image.ts:3-19`（`detectSupportedImageMimeType` + `encodeBase64`）；java `PathUtils.detectImageMimeType` / `encodeBase64`（`ReadTool.java:85-88`） |

**工具计数**：pi 声明 8 个工具名（`read|bash|powershell|edit|write|grep|find|ls`），java 实现 7 个
（`bash|read|write|edit|grep|ls|glob`）。**对齐 4 / 存疑 4 / 缺失 1 / 合计 9**；**Σ权重 21**。

---

## 能力单元清单（除工具外）

类别代号：**L**=agent loop 行为 · **H**=钩子 · **E**=事件 · **N**=entry/消息类型 · **P**=prompt/模板 · **C**=上下文管理 · **S**=存储/会话 · **A**=API 面

| # | 能力单元 | 类别 | 判定 | 权重 | pi 侧证据 | pi-java 侧证据 | 台账条目 |
|---|---|---|---|---|---|---|---|
| 1 | 10 个 `AgentEvent` 变体（agent_start/end、turn_start/end、message_start/update/end、tool_execution_start/update/end） | E | **对齐** | **3** | `types.ts:431-446` | `PiLoop.java:41-69`（逐字对齐，L5 14 剧本） | —— |
| 2 | 双循环（内层工具+steering / 外层 follow-up） | L | **对齐** | **3** | `agent-loop.ts:171-272` | `PiLoopRunner.java:39-145` | —— |
| 3 | `runAgentLoop` / `runAgentLoopContinue` 前置条件（空上下文、末条 assistant ⇒ 抛） | L | **对齐** | **3** | `agent-loop.ts:71-77` | `PiLoop.java:288-300`；`PiLaneEngine.java:137,150` | —— |
| 4 | 工具批次 `terminate` 取**全部**（`every`） | L | **对齐** | **2** | `agent-loop.ts:590` | `PiLoop.java:124`；`PiLoopTools.java:51-52` | —— |
| 5 | `length` 截断 ⇒ 本回合**全部**工具调用判失败 | L | **对齐** | **2** | `agent-loop.ts:206-208` | `PiLoopRunner.java:106-110` | —— |
| 6 | `shouldStopAfterTurn` | L | **对齐** | **3** | `agent-loop.ts:252`；`types.ts:223` | `PiLoop.java:211-215`；`HookSystem.onShouldStopAfterTurn` | —— |
| 7 | `prepareNextTurn` + `AgentLoopTurnUpdate`（context 整体替换） | L | **对齐** | **3** | `agent-loop.ts:177-183`；`types.ts:138-145` | `PiLoop.java:194-208`；`PiLoopRunner.java:59-63` | A3（thinkingLevel 差分覆盖不到）⇒ 存疑 |
| 8 | `transformContext` | L | **对齐** | **3** | `agent-loop.ts`（hook 派发） | `PiLoop.java:219-223` | —— |
| 9 | 工具两相端口（immediate / prepared）与 end 次序 | L | **对齐** | **3** | `agent-loop.ts:506-545` | `PiLoop.java:132-142`；`PiLoopTools.java:90-151` | —— |
| 10 | 并行/顺序批次选择（Sequential 工具 ⇒ 整批降级） | L | **对齐** | **3** | `agent-loop.ts:431-485` | `PiLoopTools.java:63-88` | —— |
| 11 | 重试环 A（post-run assistant 重试） | L | **对齐** | **2** | `agent-session.ts:1123` 等 | `PostRunRetry.java` | —— |
| 12 | 重试环 B（摘要重试，同预算 + throw 撤截断兜底） | L | **对齐** | **1** | `agent-session.ts:2894-2908` | `LlmSummaryGenerator.java` + `PostRunRetry.java` | —— |
| 13 | 队列：steer / followUp / nextRun | L | **对齐** | **2** | `types.ts:245,258` | `QueueManager.java:117-159` | —— |
| 14 | `QueueMode.All` 的宿主层语义 | L | **存疑** | **2** | `types.ts:50`（`"all"｜"one-at-a-time"`） | `QueueMode.java:14-23`；`QueueManager.java:149-155` | **F1**（待用户拍板） |
| 15 | 溢出恢复（compact-and-retry **一次**） | L | **对齐** | **2** | `agent-session.ts:2180-2210` | `PostRunCompactionCheck.java`；`PostRunOverflowDriveTest` | —— |
| 16 | 轮内阈值压缩门 | L | **对齐** | **3** | `agent-session.ts:548` | `CompactionThresholdGateTest`；`ContextUsageEstimator.java` | —— |
| 17 | **手动 `/compact` 先 `abort()` 当前运行** | L | **缺失** | **2** | `agent-session.ts:1968`（`compact()` 首行 `await this.abort()`） | `CompactionExecutor.java:77-83` 与 `AgentSession.java:641` **均无 abort**；`RunLifecycle.java:237-239` 无 `isRunning` 门 | **A4 / F4** ⇒ 台账纠错① |
| 18 | 压缩后重建工作副本（走 `NextTurnUpdate.context`） | L | **对齐** | **3** | `agent-session.ts:557-577` | `PiLoop.java:203-206`；`CompactionExecutor` | —— |
| 19 | 摘要 prompt 前缀/后缀逐字节 | C | **对齐** | **2** | `messages.ts:4-17` | `ContextEntries.java:32-37` | —— |
| 20 | 上下文计量（用量优先 + 尾字符估算） | C | **对齐** | **3** | `compaction.ts:215-245` | `ContextUsageEstimator.java:60-122` | —— |
| 21 | `getLastAssistantUsage`（有效助手用量） | C | **对齐** | **2** | `compaction.ts:183-193` | `ContextUsageEstimator.java:86-90`（内联私有方法） | —— |
| 22 | `findCutPoint` / `findTurnStartIndex` | C | **对齐** | **2** | `compaction.ts:343,370` | `CompactionService.java`（`findCutPoint` 有；`findTurnStartIndex` 内联） | —— |
| 23 | 输出截断（2000 行 / 100KB，头/尾两种） | C | **对齐** | **3** | `utils/truncate.ts:1-350` | `TruncationUtils.java` | —— |
| 24 | **branch summary 生成** | C | **缺失** | **1** | `compaction/branch-summarization.ts:219,242`（300 行） | 全仓只有 `Entry.BranchSummary` 类型 + `branchSummaryText` 文案；**无生成器** | **B1** |
| 25 | **会话树导航 `navigateTree`** | L | **缺失** | **1** | `agent-session.ts:3136` | 全仓只有 hook/record/context 形状，无导航操作 | B1 同族 |
| 26 | **per-model 压缩设置** | C | **缺失** | **2** | `settings-manager.ts:891-901`（`getCompactionSettings(model)`） | `HarnessConfig.compactionSettings` 是 `CompactionSettings` 无模型参数（`HarnessConfig.java:155`） | **A9** |
| 27 | **branch summary 设置**（`reserveTokens` / `skipPrompt`） | C | **缺失** | **1** | `settings-manager.ts:903-911` | 无 | B1 同族 |
| 28 | bash 流式 `tool_execution_update` | L | **对齐** | **2** | `core/tools/bash.ts:265,297` | `BashTool.java:113-114` + `BashUpdateEmitter.java` + `Utf8ChunkStream.java` | **B46（已修）** |
| 29 | bash 全量输出落盘 `fullOutputPath` | L | **对齐** | **1** | `harness/tools/bash.ts:20-23` | `BashTool.java:46`（`BashDetails`） | —— |
| 30 | 钩子 `before_run` | H | **对齐** | **2** | `agent-harness.ts:432` | `HookSystem.java:43` | —— |
| 31 | **钩子 `before_drive`** | H | **缺失** | **2** | `agent-harness.ts:435`；调用点 `runtime/drive.ts:36-42` | 全仓零命中 | —— |
| 32 | 钩子 `before_run_end`（`followUp` 结果） | H | **对齐** | **2** | `agent-harness.ts:439-442` | `HookSystem.java:93` | —— |
| 33 | 钩子 `transform_context` | H | **对齐** | **2** | `agent-harness.ts:443` | `HookSystem.java:53` | —— |
| 34 | 钩子 `before_request` | H | **对齐** | **2** | `agent-harness.ts:445` | `HookSystem.java:58` | —— |
| 35 | 钩子 `before_payload` | H | **对齐** | **2** | `agent-harness.ts:454` | `HookSystem.java:63` | —— |
| 36 | 钩子 `after_response` | H | **对齐** | **2** | `agent-harness.ts:458` | `HookSystem.java:68` | —— |
| 37 | 钩子 `before_tool` | H | **对齐** | **2** | `agent-harness.ts:462` | `HookSystem.java:73` | —— |
| 38 | 钩子 `after_tool` | H | **对齐** | **2** | `agent-harness.ts:465` | `HookSystem.java:78` | —— |
| 39 | 钩子 `before_compaction` | H | **对齐** | **1** | `agent-harness.ts:481` | `HookSystem.java:83` | —— |
| 40 | 钩子 `before_navigation` | H | **存疑** | **1** | `agent-harness.ts:489` | `HookSystem.java:88` | 注册面在，但导航操作本身缺失（#25） |
| 41 | 钩子 `should_stop_after_turn` / `prepare_next_turn`（pi 在 `agent.ts` 层，不在 HookMap） | H | **对齐** | **3** | `agent.ts:108-112` | `HookSystem.java:98,103`；`agent-session.ts:559-563` 生产已接线 | —— |
| 42 | 钩子 `before_resume`（java 独有，pi 无对应） | H | **存疑** | **1** | —— | `HookSystem.java:48` | java 比 pi 多；pi 侧由 `NothingToResume` 错误替代（`result.ts:68`） |
| 43 | **effect gate**（`gate.admit` 串行化 + 中止传播） | A | **缺失** | **2** | `execution/effect-gate.ts:1-64`；drive 全线调用 | 全仓零命中 | —— |
| 44 | HarnessEvent 28 类（含 `run_suspend` / `lane_created` / `usage` / `value_update` / `config_update`） | E | **存疑** | **3** | `agent-harness.ts:255-372` | `HarnessEventBus.java`（**快照**广播，非事件流）；`LaneSnapshot`/`SessionSnapshot` | A6（并发契约未声明） |
| 45 | **`run_suspend` + `DeferredHandle` 挂起驱动** | L | **缺失** | **1** | `runtime/drive/deferred.ts`；`agent-harness.ts:258` | 只有 `DeferredHandle` 类型 + 解码（`MessageJsonCodec.java:82-89`），**零生产者** | H 类 §9.3「`DeferredHandle` 无生产者」 |
| 46 | `AgentSessionEvent` 面（pi 22 变体） | E | **存疑** | **3** | `agent-session.ts:145-185` | `AgentSessionEvent.java:22-131`（19 变体） | B30/B34/B35 |
| 47 | **`queue_update` 生产者** | E | **缺失** | **2** | `agent-session.ts:594` | 只有定义（`AgentSessionEvent.java:69`）+ 映射（`JsonEventMapper.java:99`），**零 emit** | **B34** |
| 48 | **`session_info_changed` 生产者** | E | **缺失** | **1** | `agent-session.ts:3116` | 只有定义（`:73`）+ 映射（`JsonEventMapper.java:104`），**零 emit** | **B30** |
| 49 | **`thinking_level_changed` 生产者** | E | **缺失** | **2** | `agent-session.ts:1830` | 只有定义（`:76`）+ 映射（`JsonEventMapper.java:108`），**零 emit** | **B35** |
| 50 | `bash_execution_update` 生产者 | E | **对齐** | **2** | `agent-session.ts:3027` | `AgentSessionEvent.BashExecutionUpdate`（`:104`） | —— |
| 51 | `compaction_start` / `compaction_end` 事件（含两入口置位点相反） | E | **对齐** | **3** | `agent-session.ts:1970,2290,2085-2098` | `CompactionObserver.java`；`CompactionExecutor.java:81-102` | —— |
| 52 | `agent_end` 载荷 = **本 pass** `newMessages` | E | **存疑** | **3** | `agent-loop.ts:217,253,272`；`agent-session.ts:770` | `PiLoopRunner.java:98,127,144` 用 `newMessages` ✅；但**会话层** `SessionRunner.java:260-261` 发 `accumulatedMessages()` | **A8** |
| 53 | `turn_end` 颗粒度（pi 每回合 / java 每次驱动） | E | **存疑** | **2** | `agent-loop.ts:231` | `AgentSessionEvent.AgentSettled` | **B49** |
| 54 | Entry union | N | **存疑** | **3** | **4 类**（`session/types.ts:16,64`） | **8 类**（`Entry.java:24-32`） | java 多出 `model_change`/`thinking_level_change`/`active_tools_change`/`custom_message` —— pi 侧只在 **v3 legacy 迁移**里出现（`jsonl/legacy-v3.ts:39,66,72,77`）⇒ java 的 entry 面是 pi **v3** 形状 |
| 55 | `CompactionEntry.fromHook` / `BranchSummaryEntry.fromHook` | N | **缺失** | **1** | `session/types.ts:37,49` | `Entry.Compaction`（`:135`）/`Entry.BranchSummary`（`:154`）**无该字段** | —— |
| 56 | `EntryProjector`（custom entry → 模型上下文） | N | **存疑** | **1** | `session/types.ts:59-62` | `ContextEntries.java:175-176` | A1 |
| 57 | `BashExecutionMessage`/`CustomMessage`/`BranchSummaryMessage`/`CompactionSummaryMessage` | N | **存疑** | **2** | `messages.ts:19-62` | `ContextEntries.java` + `CustomMessageContent.java` | A1 |
| 58 | `convertToLlm` | N | **存疑** | **3** | `messages.ts:124` | `ContextEntries.toMessages` | **A1**（八种类型逐项未核） |
| 59 | `LaneConfiguration`（模型/思考级别/激活工具随车道存，非 entry） | N | **存疑** | **2** | `session/types.ts:69-73` | 走 `Entry.ModelChange` 等 entry 化 | #54 同根 |
| 60 | `PendingEntry` / `InboxItem` | N | **存疑** | **2** | `session/types.ts:129-134,350-352` | `QueueManager.java` + `LaneRecord.Queue*` | —— |
| 61 | **`formatSkillsForSystemPrompt`**（`<available_skills>` XML 块 + 转义） | P | **缺失** | **2** | `system-prompt.ts:3-32`（包导出，生产未用） | 无；`SystemPromptBuilder.skills()` 输出 `## Active Skills` + 裸 `systemPrompt`（`SystemPromptBuilder.java:41-55`） | —— |
| 62 | **`formatSkillInvocation`** | P | **缺失** | **2** | `skills.ts:39` | 无 | —— |
| 63 | `loadSkills` / `loadSourcedSkills` | P | **存疑** | **2** | `skills.ts:51,86` | `SkillDiscovery`（在 `pi-java-coding-agent`，非 agent-core） | —— |
| 64 | `loadPromptTemplates` / `parseCommandArgs` / `substituteArgs` / `formatPromptTemplateInvocation` | P | **存疑** | **2** | `prompt-templates.ts:31,226,252,268` | `PromptTemplates`（在 `pi-java-coding-agent`） | —— |
| 65 | **`sanitizeSurrogates`**（孤对代理清理，pi 54 处跨全部车道） | A | **缺失** | **1** | 全仓 54 处调用 | 全仓**零**命中 | **B16** |
| 66 | **`AdaptivePublisher`** | A | **缺失** | **1** | `utils/adaptive-publisher.ts`（87 行） | 无 | —— |
| 67 | `applyShellOutputUpdate` / `ShellOutputView` | A | **存疑** | **2** | `utils/output-capture.ts`（238 行）；`utils/shell-output.ts`（112 行） | `ShellOutputSink.java` + `BashUpdateEmitter.java` | —— |
| 68 | **`streamProxy`**（把 LLM 调用代理到服务端） | A | **缺失** | **1** | `proxy.ts:120`（402 行，包导出） | 无（java 的 `ProxyDetector` 是 HTTP 代理探测，不同物） | —— |
| 69 | **`setDefaultStreamFn`** | A | **缺失** | **1** | `stream-fn.ts:20` | 无 | —— |
| 70 | `SessionSearchService`（6 方法：searchSessions/searchEntries?/sync/notify/remove/close） | A | **存疑** | **1** | `search/index.ts:20-28` | `SessionSearch.java`（**只有 2 个**：search/close）；实现 `SqliteSessionSearch` | —— |
| 71 | `reduceLaneSnapshot`（远端转录消费者的事件折叠） | A | **存疑** | **1** | `runtime/reducer.ts:22`（包导出） | 无（java 直接发 `LaneSnapshot` 快照） | —— |
| 72 | 错误面：15 个 `TaggedError`（LaneBusy/OperationMismatch/NoActiveRun/NothingToResume/InvalidNavigation/UnknownTarget/InvalidLane/…） | A | **存疑** | **1** | `result.ts:53-100` | 只有 `HarnessClosedException` / `NothingToCompactException` / `SessionError`(8 code) / `UnknownSkillException` | —— |
| 73 | JSONL v4 存储（事务 + `values.ts` 值模型） | S | **存疑** | **3** | `session/jsonl/storage.ts:1-267`；`session/values.ts`（195 行） | `JsonlSessionStorage.java`（平铺 entry 列表 + `JsonlCodec`） | 架构不同（v4 事务 vs 平铺），未逐字段核 |
| 74 | 存储 conformance / benchmark 套件 | S | **缺失** | **1** | `session/testing/*`（**2716 行**） | agent-core 无；sqlite 模块有 `SqliteConformanceGroup1Test` | A17 |
| 75 | session fork（`fork.ts` / `fork-policy.ts` / `jsonl/fork.ts`） | S | **存疑** | **2** | `session/fork.ts`；`session/jsonl/fork.ts`（328 行） | `ForkOptions.java` | —— |
| 76 | `session_start` / `resources_discover` | S | **对齐** | **2** | `agent-session.ts:394,2494` | `AgentSession.java:444`（`applySessionStartResources`） | —— |
| 77 | 会话恢复（resume seed + record replay） | S | **存疑** | **2** | `session/restore.ts`；`drive/recovery.ts` | `RunLifecycle.seedTranscript:255`；`restoreRecords:295` | A2 |
| 78 | `latest()` / `find()` 会话定位范围 | S | **存疑** | **3** | `session-manager.ts`（`findMostRecentSession` 只读**一个**项目目录） | `PersistentSessionRepositories.find()` 走 `list(all())`（**跨项目全扫**）；`latest(cwd)` 已修 | **B53**（`find` 未修）、**A12（已结案）** |
| 79 | 会话列表 `onProgress` 异步进度 | S | **缺失** | **1** | `session-manager.ts:772,812` | `list` 是同步全量 | **B55** |
| 80 | 宿主 `prompt()` 的 `streamingBehavior`（运行中改走 steer/followUp） | L | **存疑** | **2** | `agent-session.ts:1219-1232`（无 `streamingBehavior` ⇒ **抛**「Agent is already processing」）；压缩中 ⇒ 抛（`:1192-1196`） | `AgentSession.processPrompt:591-609` **无任何门**；门只在引擎层（`PiLaneEngine.java:90-91`，抛 `IllegalStateException`），且**不路由**到 steer/followUp | **A5 / F4** ⇒ 台账纠错② |
| 81 | 工具线程安全契约（工具必须可并发执行） | A | **存疑** | **1** | `agent-loop.ts:490-560`（`Promise.all`） | 无显式声明；`PiLoopTools` 真并发 | **A11**（审计未开始）、**A10** |
| 82 | `retry.provider.*` ↔ HTTP `RetryPolicy` 映射 | A | **存疑** | **2** | —— | —— | **A7**（独立清点项，未做） |
| 83 | `transcript()` 下游消费者清点 | A | **存疑** | **1** | —— | —— | **A2** |
| 84 | 运行中手动压缩 / 并发 prompt 两道门的**行为裁决** | L | **存疑** | **2** | 见 #17 / #80 | —— | **F4**（待用户拍板） |
| 85 | `recordEvent` 无宿主跨度时的兜底语义 | A | **存疑** | **1** | —— | —— | **F2** |
| 86 | `_emitSessionCompactFailed`（扩展层事件） | E | **存疑** | **1** | `agent-session.ts:602` | 无 | **F3** |
| 87 | L5 夹具对 thinking 块不对称 | A | **存疑** | **1** | `conformance/pi/run.test.ts` | `ScriptedStreams.java:73-79` | **E8** |
| 88 | `ActiveToolsChange` 发射 | E | **存疑** | **1** | —— | 不发射 | **C1**（裁决：不加） |
| 89 | 批内 join 源序 vs pi `Promise.all` | L | **存疑** | **1** | `agent-loop.ts:547` | `PiLoopTools.java:144` | **C2** |
| 90 | `transcript`/`messages`/`partial` 可见性 | S | **存疑** | **1** | —— | 写者唯一（引擎线程） | **C3** |
| 91 | `RunLifecycle.reset` 不 `close()` runSpan | S | **存疑** | **1** | —— | `RunLifecycle.java:228` | **C4** |
| 92 | 遥测 `startSpan`/`openSpan` javadoc 未文档化局部行为 | A | **存疑** | **1** | —— | `TelemetryContext:32-39` | **D2/D3** |
| 93 | `SummaryGenerator.java:12` javadoc 过期 | A | **存疑** | **1** | —— | 主源码 | **D6** |
| 94 | 文件超限：`PiLaneSink` 510 行 / `AgentHarness` **514** 行 | A | **存疑** | **1** | —— | 实测 `wc -l` | **E3 / E4**（E4 台账记 502，已漂到 514） |
| 95 | 夹具在集成层无牙（`Utf8ChunkStream` 只被单元喂） | A | **存疑** | **1** | —— | `ShellOutputStreamingTest` | **A14** |
| 96 | 错误路 `AgentSettled` 无夹具 | A | **存疑** | **1** | —— | `SessionRunner.java:188` 发空 `AgentEnd` | **A15** |
| 97 | **扩展开启（extended thinking）目录元数据 → 请求投送** | C | **缺失** | **2** | `models.json` 的 `reasoning:true` → `Model.thinkingLevelMap` → `thinking.budgetTokens` | `ThinkingLevelMap.of(` **全仓 4 个调用者全在 `src/test`**；`HarnessConfig.Builder` 默认 `empty()`（`:159`）且 `AgentSession.java:381-410` 的 builder 链**无 `.thinkingLevelMap(...)`** ⇒ `forLevel(Enabled)` 恒 `ThinkingConfig.OFF`（`ThinkingLevelMap.java:26-34`）⇒ `DefaultProviders.java:113-115` 门恒假 | **B15**（本次补入表；原表漏列） |
| 98 | **`abortCompaction`**（取消压缩，TUI/宿主面） | A | **缺失** | **1** | `agent-session.ts:2121`；TUI 绑 Esc（`interactive-mode.ts:3391-3394`） | `grep -rn abortCompaction --include=*.java` **零命中**（java 有 `abortRetry`，无 `abortCompaction`） | **B36**（本次补入表；原表漏列） |

> **不在本模块范围的台账条目**（修在别处，仅供索引）：B14/B17/B18/B19/B21/B23/B24（`pi-java-ai`）、
> B28/B29/B31/B32/B33/B48/B50/B51（RPC 线格式，`coding-agent`）、B38/B39/B47（TUI）、
> B52/A13/A16（已结案）、B54（web 路径 `--no-session`，`coding-agent`）。

---

## 整块缺失

> 本表**只有 ① 是能力单元表里没有的单元**，故只有它进加权分母；②–⑨ 已分别落在
> #24/#25、#43、#74、#68、#66、#73、#61/#62、#65 上，**不重复计权**。

| 子系统 | pi LOC | 判定 | 权重 | 说明 |
|---|---:|---|---:|---|
| ① **durable drive 运行时**（`harness/runtime/drive/` 12 文件：boundary / checkpoint / deferred / generation / reconcile / recovery / response / retry / structural / terminal / tool-placement / tools + `drive.ts`） | **7505** | **缺失** | **2** | pi 的驱动是**可挂起、可恢复、可对账**的：每次推进是一个 `Drive` 步骤，带 checkpoint（`Continuation = need_assistant / may_finish`）、`DeferredHandle` 轮询挂起（`run_suspend`）、崩溃后 `reconcile` 对账、`recovery` 把被打断的 assistant 消息补成 `interruptedAssistantMessage`。pi-java 的对应物是 `PiLaneEngine`(475) + `PiLaneSink`(510) + `RunLifecycle`(315) + `PiLoopRunner`(378) ≈ 1700 行**阻塞式单次驱动**，只有 `restoreRecords` 一个粗粒度恢复口。**可观察差**：`run_suspend` / `run_resume` / `operation_abort` / `fault` / `lane_created` 五类事件在 java 侧不存在。**权重 2 而非 3 的理由**：默认路径 java 已经跑通（阻塞式驱动），缺的是崩溃/挂起这一半 ⇒ 频率低 |
| ② **branch summary 生成 + 会话树导航** | 300（`compaction/branch-summarization.ts`）+ `navigateTree` | 缺失 | *(已计 #24/#25)* | `Entry.BranchSummary` 类型、`branchSummaryText` 文案、`StepKind.BRANCH_SUMMARY`、`UsageCause.BRANCH_SUMMARY`、`before_navigation` 钩子**全都在**，但**没有任何代码生成它或执行导航**。`RetryObserver:44` 的 `"branchSummary"` 是死通道。台账 **B1** |
| ③ **effect gate** | 64（`execution/effect-gate.ts`） | 缺失 | *(已计 #43)* | pi 用 `Gate.admit()` 把「钩子 + 其后动作」作为一个**被准入的效果**串行化，并携带中止信号传播。java 全仓零命中 ⇒ 钩子与动作之间无准入边界 |
| ④ **storage conformance / benchmark 套件** | **2716**（`session/testing/`，在 `src/` 里） | 缺失 | *(已计 #74)* | pi 有一套 storage/session-repo 的 conformance 套件 + 两个 benchmark（`session-repo.bench.ts` / `storage.bench.ts`）。agent-core 侧无；只有 sqlite 模块有局部 conformance |
| ⑤ **`proxy.ts`（LLM 代理 stream fn）** | 402 | 缺失 | *(已计 #68)* | 给「应用把 LLM 调用路由到服务端」的场景：`streamProxy(model, context, options)` 走 WebSocket/HTTP 代理协议（`ProxyMessageEventStream`）。java 无对应物 |
| ⑥ **`utils/adaptive-publisher.ts`** | 87 | 缺失 | *(已计 #66)* | 自适应发布节奏（背压）工具。java 无 |
| ⑦ **JSONL v4 `values.ts` 值模型** | 195 | 存疑 | *(已计 #73)* | pi v4 把存储建模成 `ValueList`/`StoredValue`/`ListWrite`（事务化），java 是平铺 entry 数组。属架构差异，未逐字段核 |
| ⑧ **`formatSkillsForSystemPrompt` / `formatSkillInvocation`** | 34 + ~12 | 缺失 | *(已计 #61/#62)* | 包导出的 API，pi-java 无（java 的 `SystemPromptBuilder` 是另一套形状） |
| ⑨ **`sanitizeSurrogates`** | —— | 缺失 | *(已计 #65)* | 见能力单元 #65（台账 B16） |

---

## 台账纠错

| 条目 | 台账说什么 | 实际 | 证据 |
|---|---|---|---|
| **A4** | 「运行中手动 `/compact` **有没有门** —— 先取证 pi。**pi 侧未取证**」 | **pi 没有 `isRunning` 门，但有 `await this.abort()` 作为 `compact()` 的第一行**（先中止在飞运行，再压缩）。pi-java **两者都没有** ⇒ 这不是「门的有无」问题，是「缺 abort-first」的真缺口。台账的措辞（`docs/31:2503`）把问题问错了 | pi `agent-session.ts:1967-1968`；java `CompactionExecutor.java:77-83`、`RunLifecycle.java:237-239`、`AgentSession.java:641` |
| **A5** | 「并发 prompt **有没有门** —— 同样先取证 pi」 | **pi-java 有门，只是层次不同**：`PiLaneEngine.java:90-91` 在 `lane.isRunning()` 时抛 `IllegalStateException`。真差的是 pi 的两条**路由语义**：① 运行中 prompt 必须显式带 `streamingBehavior`（`'steer'｜'followUp'`），否则抛**带指引的**错误；② **压缩中禁 prompt**。java 既不路由也无压缩门 | pi `agent-session.ts:1219-1232`（streamingBehavior）、`:1192-1196`（压缩中）；java `AgentSession.java:591-609`（无门）、`PiLaneEngine.java:90-91` |
| **E4** | 「`AgentHarness.java` **502** 行」 | 实测 **514** 行（`wc -l`） | `pi-java-agent-core/.../harness/AgentHarness.java` |
| **B46** | 已修（包⑧） | ✅ **核实为真**：`BashTool.java:113-114` 已接 `BashUpdateEmitter` | `BashTool.java:111-114` |
| **B53** | `PersistentSessionRepositories.find()` 走 `all()` | ✅ **核实为真**：`find()` 里 `repo.list(JsonlSessionListOptions.all())`（`latest(cwd)` 已按 A12 修好，`find` 未修） | `PersistentSessionRepositories.java:60-66` |
| **B30 / B34 / B35** | 无生产者 | ✅ **核实为真**：三者在全仓只命中「定义 + `JsonEventMapper` 映射」，零 emit | `AgentSessionEvent.java:69,73,76`；`JsonEventMapper.java:99,104,108` |
| **B16** | `sanitizeSurrogates` 全仓无对应物 | ✅ **核实为真**：`grep -rn sanitizeSurrogates --include=*.java` 零命中 | —— |
| **B1** | branch summary 无实现 | ✅ **核实为真**：`grep branchSummary` 只命中类型/文案/record-kind，无生成器 | `Entry.java:31,57,74,154`；`ContextEntries.java:175-176,192` |
| **A9** | per-model 压缩设置 | ✅ **核实为真**：pi `getCompactionSettings(model)`（`settings-manager.ts:891`）带模型参数；java `CompactionSettings` 无模型参数 | `settings-manager.ts:891-901`；`HarnessConfig.java:155` |
| **B15** | 扩展开启在生产上不可达 | ✅ **核实为真且更强**：`ThinkingLevelMap.of(` 全仓**恰好 4 个调用者，全部在 `src/test`**（`ThinkingTranslationTest.java:39`、`StreamSimpleTest.java:95`、`ThinkingLevelTest.java:87,98`）；生产侧 `HarnessConfig.Builder.thinkingLevelMap` 默认 `empty()`（`:159`），且 `AgentSession.java:381-410` 的 builder 链**没有 `.thinkingLevelMap(...)` 这一行** ⇒ `forLevel(Enabled)` 恒返回 `ThinkingConfig.OFF`（`ThinkingLevelMap.java:26-34`）⇒ `DefaultProviders.java:113-115` 的门恒假 ⇒ 请求里永无 `thinking.budgetTokens` | 同上 |
| **A8** | 「pi = 本 pass `newMessages`；pi-java = `accumulatedMessages`」 | ✅ **核实为真**（引擎层已对、**会话层**不对）：`PiLoopRunner` 三处都发 `newMessages`；但 `SessionRunner.java:260-261` 发 `owner.accumulatedMessages()` | `agent-loop.ts:217,253,272`；`PiLoopRunner.java:98,127,144`；`SessionRunner.java:260-261` |
| **A12** | 已结案（`latest()` → `latest(cwd)`） | ✅ 核实：`latest(cwd)` 走 `list(cwd)`；`find()` 仍走 `all()`（见 B53） | `PersistentSessionRepositories.java:60-70` |
| **A17** | sqlite 写租约 flake，未定位 | 本次未复现、未触碰（属 `session-backend-sqlite` 模块） | —— |

**本次新增的两条自纠**：能力单元表原先**漏列了 B15（#97）与 B36（#98）** —— 我在台账纠错与缺失清单里都提到了它们，
却没有给它们行。现已补入（合计 105 → **107** 个单元）。这正是「台账/清单与表不同源」的同一形态。

---

## 汇总（未加权）

| 表 | 对齐 | 缺失 | 存疑 | 合计 | 完成度 |
|---|---:|---:|---:|---:|---:|
| 内置工具 | 4 | 1 | 4 | 9 | **44.4%** |
| 能力单元（除工具） | 36 | 22 | 40 | 98 | **36.7%** |
| 整块缺失（仅 ① 独立计） | 0 | 1 | 0 | 1 | **0%** |
| **合计** | **40** | **24** | **44** | **108** | **37.0%** |

**未加权完成度 = 40 / 108 = 37.0%**

> 口径说明：本表**不把「存疑」算作对齐**。存疑里有相当一部分是「形状存在、语义未逐项核」
> （A1/A2 类），也有一部分是「已裁决不改」（C 类）与「结构债」（E 类）—— 后两类不是欠账，
> 但也不是对齐，故仍列存疑。若只按「有/无」计（对齐＋存疑视为有），则是 **84/108 = 77.8%**。

### 存疑清单（全部 44 条）

**工具 4 条**：`bash`（#1 超时默认值/上限不同）· `grep`（#5 缺 4 参数 + gitignore + limit）·
`find`/`glob`（#6 名字不同 + 缺 limit + gitignore）· `ls`（#7 参数集不同）

**能力单元 40 条（按表内序号）**：

| 序号 | 存疑点 | 权重 | 台账 |
|---|---|---:|---|
| 7 | `prepareNextTurn` 的 `thinkingLevel` 差分覆盖不到 | 3 | **A3** |
| 14 | `QueueMode.All` 的宿主层语义 | 2 | **F1** |
| 40 | `before_navigation` 有注册面但无导航操作 | 1 | B1 同族 |
| 42 | `before_resume` 钩子（java 独有，pi 无对应物） | 1 | —— |
| 44 | HarnessEvent 28 类 vs java 的快照广播 | 3 | **A6** |
| 46 | `AgentSessionEvent` 面 22 vs 19 变体 | 3 | B30/B34/B35 |
| 52 | `agent_end` 载荷（引擎对、会话层不对） | 3 | **A8** |
| 53 | `turn_end` 颗粒度（每回合 vs 每次驱动） | 2 | **B49** |
| 54 | Entry union 8 vs 4（java 多的是 pi **v3** 形状） | 3 | —— |
| 56 | `EntryProjector` | 1 | **A1** |
| 57 | 四个消息类型（BashExecution/Custom/BranchSummary/CompactionSummary） | 2 | **A1** |
| 58 | `convertToLlm` | 3 | **A1** |
| 59 | `LaneConfiguration`（车道状态 vs entry 化） | 2 | #54 同根 |
| 60 | `PendingEntry` / `InboxItem` | 2 | —— |
| 63 | `loadSkills`（在 `pi-java-coding-agent`，不在 agent-core） | 2 | —— |
| 64 | `loadPromptTemplates` / `parseCommandArgs` / `substituteArgs` / `formatPromptTemplateInvocation`（同上） | 2 | —— |
| 67 | `applyShellOutputUpdate` / `ShellOutputView` | 2 | —— |
| 70 | `SessionSearchService` 6 方法 vs 2 方法 | 1 | —— |
| 71 | `reduceLaneSnapshot` | 1 | —— |
| 72 | 错误面 15 个 `TaggedError` vs java 4 个异常 | 1 | —— |
| 73 | JSONL v4 事务/值模型 vs 平铺 entry 列表 | 3 | —— |
| 75 | session fork（`fork.ts` / `fork-policy.ts` / `jsonl/fork.ts`） | 2 | —— |
| 77 | 会话恢复（resume seed + record replay） | 2 | **A2** |
| 78 | `latest()`/`find()` 会话定位范围 | 3 | **B53**（`find` 未修） |
| 80 | 宿主 `prompt()` 的 `streamingBehavior` 路由与压缩门 | 2 | **A5 / F4** |
| 81 | 工具线程安全契约未声明 | 1 | **A10 / A11** |
| 82 | `retry.provider.*` ↔ HTTP `RetryPolicy` 映射 | 2 | **A7** |
| 83 | `transcript()` 下游消费者未清点 | 1 | **A2** |
| 84 | 两道门的行为裁决 | 2 | **F4** |
| 85 | `recordEvent` 无宿主跨度时的兜底语义 | 1 | **F2** |
| 86 | `_emitSessionCompactFailed`（扩展层事件） | 1 | **F3** |
| 87 | L5 夹具对 thinking 块不对称 | 1 | **E8** |
| 88 | `ActiveToolsChange` 发射 | 1 | **C1**（裁决：不加） |
| 89 | 批内 join 源序 vs pi `Promise.all` | 1 | **C2** |
| 90 | `transcript`/`messages`/`partial` 可见性 | 1 | **C3** |
| 91 | `RunLifecycle.reset` 不 `close()` runSpan | 1 | **C4** |
| 92 | 遥测 `startSpan`/`openSpan` javadoc 未文档化局部行为 | 1 | **D2 / D3** |
| 93 | `SummaryGenerator.java:12` javadoc 过期 | 1 | **D6** |
| 94 | 文件超限：`PiLaneSink` 510 / `AgentHarness` **514** 行 | 1 | **E3 / E4** |
| 95 | 夹具在集成层无牙（`Utf8ChunkStream` 只被单元喂） | 1 | **A14** |
| 96 | 错误路 `AgentSettled` 无夹具 | 1 | **A15** |

> 另有三条**已知未覆盖**（`docs/31 §8.27.4` 如实登记，无台账行）：可见性、运行中替换 transcript、
> 压缩 attempt 读-改-写（后两者同根＝`/compact` 无门）。
> 另有一条**已结案为「java 比 pi 多」**：遥测跨度词汇（`docs/31 §8.28`）。

### 缺失清单（全部 24 条）

**工具 1 条**：#8 `powershell`（权重 1）—— pi `core/tools/powershell.ts:59`；java 全仓零命中。

**能力单元 22 条（按表内序号）**：

| 序号 | 缺失项 | 权重 | pi 证据 | 台账 |
|---|---|---:|---|---|
| 17 | **手动 `/compact` 先 `abort()` 当前运行** | 2 | `agent-session.ts:1968` | **A4 / F4** |
| 24 | **branch summary 生成** | 1 | `branch-summarization.ts:219,242` | **B1** |
| 25 | **会话树导航 `navigateTree`** | 1 | `agent-session.ts:3136` | B1 同族 |
| 26 | **per-model 压缩设置** | 2 | `settings-manager.ts:891-901` | **A9** |
| 27 | **branch summary 设置**（`reserveTokens` / `skipPrompt`） | 1 | `settings-manager.ts:903-911` | B1 同族 |
| 31 | **钩子 `before_drive`** | 2 | `agent-harness.ts:435`；`drive.ts:36-42` | —— |
| 43 | **effect gate** | 2 | `execution/effect-gate.ts:1-64` | —— |
| 45 | **`run_suspend` + `DeferredHandle` 挂起驱动** | 1 | `drive/deferred.ts`；`agent-harness.ts:258` | §9.3 |
| 47 | **`queue_update` 生产者** | 2 | `agent-session.ts:594` | **B34** |
| 48 | **`session_info_changed` 生产者** | 1 | `agent-session.ts:3116` | **B30** |
| 49 | **`thinking_level_changed` 生产者** | 2 | `agent-session.ts:1830` | **B35** |
| 55 | **`CompactionEntry.fromHook` / `BranchSummaryEntry.fromHook`** | 1 | `session/types.ts:37,49` | —— |
| 61 | **`formatSkillsForSystemPrompt`**（`<available_skills>` XML 块） | 2 | `system-prompt.ts:3-32` | —— |
| 62 | **`formatSkillInvocation`** | 2 | `skills.ts:39` | —— |
| 65 | **`sanitizeSurrogates`** | 1 | pi 54 处；java 0 | **B16** |
| 66 | **`AdaptivePublisher`** | 1 | `utils/adaptive-publisher.ts`（87 行） | —— |
| 68 | **`streamProxy`** | 1 | `proxy.ts:120` | —— |
| 69 | **`setDefaultStreamFn`** | 1 | `stream-fn.ts:20` | —— |
| 74 | **storage conformance / benchmark 套件**（2716 行） | 1 | `session/testing/` | A17（sqlite 模块另有局部套件） |
| 79 | **会话列表 `onProgress` 异步进度** | 1 | `session-manager.ts:772,812` | **B55** |
| 97 | **扩展开启（extended thinking）目录→请求投送** | 2 | `Model.thinkingLevelMap` → `thinking.budgetTokens` | **B15** |
| 98 | **`abortCompaction`** | 1 | `agent-session.ts:2121` | **B36** |

**整块缺失 1 条**：① durable drive 运行时（权重 2）。

---

## 加权汇总

| 域 | Σ权重 | Σ(w×系数) | 加权完成度 | 未加权完成度 |
|---|---:|---:|---:|---:|
| 内置工具（9） | 21 | 15.0 | **71.4%** | 44.4% |
| 能力单元（除工具，98） | 181 | 116.5 | **64.4%** | 36.7% |
| 整块缺失（仅 ① 独立计，1） | 2 | 0 | **0%** | 0% |
| **模块合计（108）** | **204** | **131.5** | **64.5%** | **37.0%** |

**模块合计**：Σ权重 204 ／ Σ(w×c) 131.5 ／ **加权完成度 = 131.5/204 = 64.5%**（未加权 37.0%）

按权重分层的分母（能力单元表，98 条）：

| 权重 | 条数 | Σ权重 | 对齐 | 存疑 | 缺失 | Σ(w×c) | 层完成度 |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 3 | 21 | 63 | 14 | 7 | 0 | 52.5 | **83.3%** |
| 2 | 41 | 82 | 19 | 13 | 9 | 51.0 | **62.2%** |
| 1 | 36 | 36 | 3 | 20 | 13 | 13.0 | **36.1%** |
| 合计 | 98 | 181 | 36 | 40 | 22 | 116.5 | 64.4% |

### 权重 3 的单元清单（21 条能力单元 + 5 条工具 = 26 条）

| 单元 | 判定 |
|---|---|
| `bash` 工具 | **存疑** |
| `read` 工具 | 对齐 |
| `write` 工具 | 对齐 |
| `edit` 工具 | 对齐 |
| `grep` 工具 | **存疑** |
| #1 10 个 `AgentEvent` 变体 | 对齐 |
| #2 双循环 | 对齐 |
| #3 `runAgentLoop`/`continue` 前置条件 | 对齐 |
| #6 `shouldStopAfterTurn` | 对齐 |
| #7 `prepareNextTurn` + `AgentLoopTurnUpdate` | **存疑**（A3） |
| #8 `transformContext` | 对齐 |
| #9 工具两相端口与 end 次序 | 对齐 |
| #10 并行/顺序批次选择 | 对齐 |
| #16 轮内阈值压缩门 | 对齐 |
| #18 压缩后重建工作副本 | 对齐 |
| #20 上下文计量 | 对齐 |
| #23 输出截断 | 对齐 |
| #41 `should_stop_after_turn`/`prepare_next_turn` 钩子 | 对齐 |
| #44 HarnessEvent 28 类 | **存疑**（A6） |
| #46 `AgentSessionEvent` 面 | **存疑**（B30/B34/B35） |
| #51 `compaction_start`/`compaction_end` 事件 | 对齐 |
| #52 `agent_end` 载荷 | **存疑**（A8） |
| #54 Entry union | **存疑**（v3 形状） |
| #58 `convertToLlm` | **存疑**（A1） |
| #73 JSONL v4 存储 | **存疑** |
| #78 `latest()`/`find()` 会话定位范围 | **存疑**（B53） |

**权重 3 层结论**：21 条能力单元里 **14 对齐 / 7 存疑 / 0 缺失**；工具层 5 条里 3 对齐 / 2 存疑。
⇒ **默认路径上没有整块缺失**，但有 **7 条权重 3 的存疑**（集中在「事件/载荷形状」与「格式代差」两类），
其中 **#54 Entry union（v3 形状）** 与 **#52 `agent_end` 载荷** 是唯一两条「默认路径上形状确定不对」的。

### 未加权 vs 加权的差（37.0% → 64.5%，**+27.5 pp**）

差全部来自**分母的重新分配**（分子/判定一个字没改）：

1. **权重 3 的 21 条里 14 条对齐** —— agent loop 主体、10 个事件、上下文计量、截断、压缩门、核心钩子。
   未加权时它们只占分母 21/108；加权后占 63/204。这是 +27.5 pp 的**主来源（约 +19 pp）**。
2. **权重 1 的 36 条里 33 条不是「对齐」**（20 存疑 + 13 缺失）—— 未加权时它们是分母的 33/108（31%）；
   加权后只占 36/204（18%）。这些正是「形状存在、语义未核」「结构债」「已裁决不改」「测试基建」类。
3. **缺失的权重分布极度偏斜**：24 条缺失里 **0 条权重 3**、9 条权重 2、13 条权重 1、1 条整块缺失（权重 2）。
   ⇒ pi-java 缺的**全是边缘/低频项**，默认路径基本完整 —— 这是加权后 64% 而加权前 37% 的结构性原因。

**反向读法（更该看的）**：`Σw − Σ(w×c) = 72.5` 的**未得分池**分布是 ——

| 池 | 未得分 | 占比 |
|---|---:|---:|
| 能力单元 权重 2 | **31.0** | 43% |
| 能力单元 权重 1 | 23.0 | 32% |
| 能力单元 权重 3 | 10.5 | 14% |
| 内置工具 | 6.0 | 8% |
| 整块缺失 ① | 2.0 | 3% |
| 合计 | 72.5 | 100% |

⇒ **权重 2 层（62.2%）才是最大的未得分池**（31/72.5），不是权重 1 层。
权重 2 层里 9 条缺失 + 13 条存疑，是「先修谁」的真正答案。

### 三条点名项的边际影响（各自补齐 = 系数从 0 变 1.0）

| 条目 | 权重 | 现 Σ(w×c) | 补齐后 | 现完成度 | 补齐后 | 边际 |
|---|---:|---:|---:|---:|---:|---:|
| **B15 扩展思考不可达**（#97） | 2 | 131.5 | 133.5 | 64.46% | 65.44% | **+0.98 pp** |
| **A4 `/compact` 缺 abort-first**（#17） | 2 | 131.5 | 133.5 | 64.46% | 65.44% | **+0.98 pp** |
| **durable drive 整块缺失**（①） | 2 | 131.5 | 133.5 | 64.46% | 65.44% | **+0.98 pp** |
| 三条合计 | 6 | 131.5 | 137.5 | 64.46% | **67.40%** | **+2.94 pp** |

**结论**：三条**权重相同（都是 2）**，故边际影响**完全等量**（各 +0.98 pp）。
它们各自拉低模块约 **1 个百分点**，合计约 **2.9 pp** —— 都不是主要缺口。

> ⚠️ **两条可被用户推翻的局部口径**（本文最影响数字的两处）：
> ① **durable drive 取 2 而非 3**：若判为 3（「每轮对话都走默认路径」的读法），
> 模块分母变 205、完成度降到 **64.15%**，该条补齐的边际变 **+1.46 pp**（3/205）。
> 我取 2 的理由：java 的阻塞式驱动**已经跑通默认路径**，缺的只是崩溃/挂起那一半 ⇒ 频率低。
> ② **钩子（#30–#42 共 12 条，不含 #41）取 2 而非 3**：若全改判 3，
> Σw 204→216、Σ(w×c) 131.5→141.5 ⇒ 完成度 **65.51%**（+1.05 pp），方向不变。
> 我取 2 的理由：未注册的钩子是 no-op，不按「默认路径」给 3。
