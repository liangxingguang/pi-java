# 03 Agent 运行时核心（pi-java-agent-core）

> 基准：pi-java @ **`351d199`**（main，2026-10-04）；pi @ **`200387122`**（2026-10-04）。
> 台账 = `docs/05-open-items-register.md`（编号已在 2026-10-04 重编，见 `docs/00`）。
> 裁决 = [`../11-pi-reanchor-ruling.md`](../11-pi-reanchor-ruling.md)。锚点事实 = [`ANCHOR.md`](ANCHOR.md)。
> 所有 pi 侧 `file:line` 均由 `git -C /d/workplaceForai/pi show 200387122:<path>` 实读，**未读工作树**。

---

## 口径

**权重**（用户 2026-09-20 定）：权重 = 用户可观察影响 × 频率，不掺排期优先级。
`3` = 每轮对话都走 / 默认路径 · `2` = 每次会话走 / 常用命令 / 常用配置键 · `1` = 低频 / 边缘 / 纯内部 · `0` = 非目标（移出分母）。
**完成系数**：对齐 `1.0` ／ 存疑 `0.5` ／ 缺失 `0`。

### ★ 锚点改指（本次重测的核心决定）

pi 在 `7fd478a2e`（2026-10-01）**删除了 `packages/agent/src/harness/**`（108 文件）**，
连同 `agent/src/search/`、`agent/src/node.ts`。旧图 43 处引 `harness/*` 的参照物全部消失。

`ANCHOR.md` 早已记过：「`harness/compaction`、`harness/messages`、`harness/session`、`harness/tools`
在主流 `coding-agent/src/core/*` 有**独立副本**」—— 当时未执行。**本轮执行**。

**本图从此锚到两个 pi 面**（均为 `bin pi` 主流可达，R5）：

| 面 | pi 路径 | 规模 | 承担 |
|---|---|---:|---|
| **agent loop 本体** | `packages/agent/src/{agent-loop,agent,types,proxy,stream-fn,index}.ts` | **2,513 行** | 循环、事件、工具两相、proxy、stream-fn |
| **harness 主流副本** | `packages/coding-agent/src/core/*` | 见规模节 | `agent-session` / `session-manager` / `compaction` / `messages` / `system-prompt` / `skills` / `prompt-templates` / `tools` / `settings-manager` / `model-config` / `provider-composer` |

实测支持（`git ls-tree -r 200387122:packages/coding-agent/src/core/`）：上述文件**独立存在**；
唯一一处 "harness" 字样在 `core/system-prompt.ts:147`，**只是提示词里的人话**，非依赖。

### R1 应用（删掉的参照物 ⇒ 单元作废，移出分母）

裁决 `docs/11 §2 R1-2/R1-5`。**harness 专有、主流无副本**的行为一律作废（见「作废清单」19 条）。
`packages/durable`（61 文件 / 17,716 行）与 `pico3` 按 **R5 不可达**（import 者全在 `coding-agent/src/experimental/**`）⇒ 权 0。

### 判定三档

**对齐** / **缺失** / **存疑**（`（形状）`/`（旧 pi）`/`（扩展）`/`（台账）`）。**pi-java 侧已闭环的旧「缺失/存疑」按现状改判为对齐**。

---

## 规模

| 模块 | pi 包 | pi LOC（旧锚 → 新锚） | java LOC | 比例 |
|---|---|---:|---:|---:|
| agent loop 本体 | `packages/agent/src` | 33,353 → **2,513**（**−92.5%**） | —— | —— |
| harness 主流副本 | `packages/coding-agent/src/core` | —— | `pi-java-agent-core/src/main` **17,599**（177 文件） | —— |
| 测试 | `packages/agent/test` | 28,570 → **1,446**（−94.9%） | `src/test` **15,929**（98 文件） | —— |

**agent loop 本体逐文件**（`git show 200387122:...`）：

| 文件 | 行数 |
|---|---:|
| `agent/src/agent-loop.ts` | 940 |
| `agent/src/agent.ts` | 613 |
| `agent/src/types.ts` | 529 |
| `agent/src/proxy.ts` | 406 |
| `agent/src/stream-fn.ts` | 20 |
| `agent/src/index.ts` | 5 |
| **合计** | **2,513** |

**harness 主流副本关键文件**：`agent-session.ts` **4,349** · `session-manager.ts` 2,013 ·
`settings-manager.ts` 1,533 · `compaction/compaction.ts` 1,119 · `provider-composer.ts` 732 ·
`skills.ts` 509 · `tools/bash.ts` 442 · `messages.ts` 196 · `system-prompt.ts` 216。

> 旧图口径「pi `src/` 混着 2,716 行测试支持」已随 `harness/session/testing/` 删除而消失；
> 旧「`durable` 新包」一节作废（那是 harness 的新家，R5 不可达）。

---

## 内置工具对齐

pi 工具定义现在**只有一处**：`packages/coding-agent/src/core/tools/`（`harness/tools/` 已删）。
`allToolNames`（8 个）在 `core/tools/index.ts:95-96`。pi-java 的 7 个工具在
`pi-java-agent-core/.../tool/builtin/`，由 `ToolSetFactory.java:26-35` 组装。

| # | 工具 | pi 有 | java 有 | 判定 | 权重 | 证据（**新锚实读**） |
|---|---|---|---|---|---|---|
| 1 | `bash` | ✅ | ✅ | **存疑** | **3** | pi `{command, timeout?}`，**无默认超时**，上限 `2147483 s`（`core/tools/bash.ts:41-42`，`resolveTimeoutMs :27-37`）。java 同参数，但**默认 120 s / 上限 600 s**（`BashTool.java:37,39`，描述 `:60-64`）⇒ 模型不传 timeout 时行为不同 |
| 2 | `read` | ✅ | ✅ | **对齐** | **3** | pi `{path, offset?, limit?}`（`core/tools/read.ts:15-17`）；java 同（`ReadTool.java:37-47`）。图片按 mime 分派成 `ImageContent`（`ReadTool.java:85-95` vs pi `core/tools/read.ts:108-113`） |
| 3 | `write` | ✅ | ✅ | **对齐** | **3** | pi `{path, content}`（`core/tools/write.ts`）；java 同（`WriteTool.java:39-45`）＋ 同形状 `FileMutationQueue` |
| 4 | `edit` | ✅ | ✅ | **对齐** | **3** | pi `{path, edits:[{oldText,newText}]}`（`core/tools/edit.ts`）；java 同（`EditTool.java:52-63`） |
| 5 | `grep` | ✅ | ✅ | **存疑** | **3** | pi `{pattern, path?, glob?, ignoreCase?, literal?, context?, limit?}` + gitignore + limit100/50KB 截断（`core/tools/grep.ts:22-32,78,294`）。java 只有 `{pattern, path?, glob?}`（`GrepTool.java:44-52`）⇒ **缺 4 参数** |
| 6 | `find` | ✅ `find` | ✅ `glob` | **存疑** | **2** | **名字不同**；pi `{pattern, path?, limit?}` 默认 1000，走 fd/ripgrep 尊重 gitignore（`core/tools/find.ts:27-31,47`）。java `{pattern, path?}`，NIO `PathMatcher` 全树 walk（`GlobTool.java:32,44-52,72-78`）⇒ 缺 limit + 无 gitignore + 无截断 |
| 7 | `ls` | ✅ | ✅ | **存疑** | **2** | pi `{path?, limit?}`（默认 500，`core/tools/ls.ts:12-13`）；java `{path?, recursive?}`（`LsTool.java:36-42`）⇒ **参数集不同** |
| 8 | `powershell` | ✅ | ❌ | **缺失** | **1** | pi `core/tools/powershell.ts:18`，工具名进 `allToolNames`（`core/tools/index.ts:95-96`）。java 全仓零命中 |
| 9 | `image`（读图处理器） | ✅ | ✅ | **对齐** | **1** | pi `core/tools/read.ts:6,108-113` + `utils/image-process.ts` + `utils/mime.ts`（**harness/tools/image.ts 已删**）；java `PathUtils.detectImageMimeType`/`encodeBase64`（`ReadTool.java:85-88`） |

**Σ权重 21**（与上版同）。**pi 侧 9 条工具引用全部改指 `core/tools/*`（harness 面作废）。**

---

## 能力单元清单（除工具）

类别：**L**=agent loop · **H**=钩子 · **E**=事件 · **N**=entry/消息 · **P**=prompt/模板 · **C**=上下文管理 · **S**=存储/会话 · **A**=API 面。
pi 侧证据**全部改指** `agent/src/*` 或 `coding-agent/src/core/*`。

| # | 能力单元 | 类别 | 判定 | 权重 | pi 侧证据（**新锚**） | pi-java 侧证据 | 台账 |
|---|---|---|---|---|---|---|---|
| 1 | 10 个 `AgentEvent` 变体 | E | **对齐** | **3** | `agent/src/types.ts:514-529` | `PiLoop.java:42-77`（L5 14 剧本） | —— |
| 2 | 双循环（内层工具+steering / 外层 follow-up） | L | **对齐** | **3** | `agent/src/agent-loop.ts:163-320` | `PiLoopRunner.java:45-160` | —— |
| 3 | `runAgentLoop`/`runAgentLoopContinue` 前置条件 | L | **对齐** | **3** | `agent/src/agent-loop.ts:135-139` | `PiLoop.java:266+`；`PiLaneEngine.java:87,122` | —— |
| 4 | 工具批次 `terminate` 取**全部**（`every`） | L | **对齐** | **2** | `agent/src/agent-loop.ts:689-691` | `PiLoopTools.java:51-52` | —— |
| 5 | `length` 截断 ⇒ 本回合**全部**工具调用判失败 | L | **对齐** | **2** | `agent/src/agent-loop.ts:264-268,478-502` | `PiLoopRunner.java:212+` | —— |
| 6 | **`finishTurn`（原 `shouldStopAfterTurn`）** | L | **存疑（形状）** | **3** | `agent/src/types.ts:146-171,264`；调用 `agent-loop.ts:252,286`——**已从布尔 `shouldStopAfterTurn` 改名/改义为 `{action:"continue"\|"end"}`** | `PiLoop.java:235-237`（`StopHook` 返布尔）；`HookSystem.java:98,270`；`PiLaneEngine.java:439-441` | **新** |
| 7 | `prepareNextTurn` + `AgentLoopTurnUpdate` | L | **对齐** | **3** | `agent/src/agent-loop.ts:186`；`types.ts:278-281` | `PiLoop.java:186,209-224`；`PiLoopRunner.java:169` | —— |
| 8 | `transformContext` | L | **对齐** | **3** | `agent/src/agent-loop.ts:390-391`；`types.ts:244` | `PiLoop.java`；`HookSystem.java:249` | —— |
| 9 | 工具两相端口（immediate / prepared）与 end 次序 | L | **对齐** | **3** | `agent/src/agent-loop.ts:508-660,707-922` | `PiLoop.java`（两相）；`PiLoopTools.java:90-186` | —— |
| 10 | 并行/顺序批次选择（Sequential ⇒ 整批降级） | L | **对齐** | **3** | `agent/src/agent-loop.ts:508-524` | `PiLoopTools.java:76-88` | —— |
| 11 | 重试环 A（post-run assistant 重试） | L | **对齐** | **2** | `core/agent-session.ts:1142-1172,1827-1834,3702-3730` | `PostRunRetry.java` | —— |
| 12 | 重试环 B（摘要重试） | L | **对齐** | **1** | `core/agent-session.ts:218-230`（`summarization_retry_*`） | `LlmSummaryGenerator.java` + `PostRunRetry.java` | —— |
| 13 | 队列：steer / followUp / nextRun | L | **对齐** | **2** | `agent/src/types.ts:293,306`（`getSteeringMessages`/`getFollowUpMessages`） | `QueueManager.java:41,51,62` | —— |
| 14 | `QueueMode.All` 的宿主层语义 | L | **存疑** | **2** | `agent/src/types.ts:55`（`"all" \| "one-at-a-time"`） | `QueueMode.java`；`QueueManager.java` | **F1** |
| 15 | 溢出恢复（compact-and-retry 一次） | L | **对齐** | **2** | `core/agent-session.ts:2884-2930`（`_overflowRecoveryAttempted` `:389`） | `PostRunCompactionCheck.java` | —— |
| 16 | 轮内阈值压缩门 | L | **对齐** | **3** | `core/agent-session.ts:738-755,811`（`_compactBeforeNextAssistantResponse`） | `ContextUsageEstimator.java:141` | —— |
| 17 | **手动 `/compact` 先 `abort()` 当前运行** | L | **缺失** | **2** | `core/agent-session.ts:2717-2719`（`await this.abort()` 后 `compaction_start`） | `CompactionExecutor.java:80-117` 与 `RunLifecycle.java:260` **均无 abort** | **A4/F4** |
| 18 | 压缩后重建工作副本（`NextTurnUpdate.context`） | L | **对齐** | **3** | `core/agent-session.ts`（compact → next turn context） | `PiLoop.java:209-224` | —— |
| 19 | 摘要 prompt 前缀/后缀逐字节 | C | **对齐** | **2** | `core/messages.ts:11-24`（`COMPACTION_SUMMARY_*` / `BRANCH_SUMMARY_*`） | `ContextEntries.java:32-37` | —— |
| 20 | 上下文计量（用量优先 + 尾字符估算） | C | **对齐** | **3** | `core/compaction/compaction.ts:196-225`（+ 新增 `estimateProjectedContextTokens :227`） | `ContextUsageEstimator.java:97-130` | —— |
| 21 | `getLastAssistantUsage` | C | **对齐** | **2** | `core/compaction/compaction.ts:166` | `ContextUsageEstimator.java:85` | —— |
| 22 | `findCutPoint` / `findTurnStartIndex` | C | **对齐** | **2** | `core/compaction/compaction.ts:446,412` | `CompactionService.java:77,96` | —— |
| 23 | 输出截断（2000 行 / 头/尾两种） | C | **存疑** | **3** | pi `core/tools/truncate.ts:11-12` = **2000 行 / 50KB**；`core/tools/read.ts:76` 描述 50KB | `TruncationUtils.java:15-16` = 2000 行 / **100KB**（`ReadTool.java:42,120-131`）⇒ **字节预算翻倍**，模型可见文案即不同 |
| 24 | **branch summary 生成** | C | **缺失** | **1** | `core/compaction/branch-summarization.ts:108,195`（`collectEntriesForBranchSummary`/`prepareBranchEntries`） | 只有 `Entry.BranchSummary` 类型 + 文案；**无生成器** | **B1** |
| 25 | **会话树导航 `navigateTree`** | L | **缺失** | **1** | `core/agent-session.ts:3915` | 无导航操作 | B1 同族 |
| 26 | **per-model 压缩设置** | C | **缺失** | **2** | `core/settings-manager.ts:929-966`（`modelOverrides` `:32`，`getCompactionSettings(model)`） | `HarnessConfig.java:89` 无模型参数 | **A9** |
| 27 | **branch summary 设置**（`reserveTokens`/`skipPrompt`） | C | **缺失** | **1** | `core/settings-manager.ts:962-967` | 无 | B1 同族 |
| 28 | bash 流式 `tool_execution_update` | L | **对齐** | **2** | `core/tools/bash.ts:265,278-284`（`onUpdate`） | `BashTool.java` + `BashUpdateEmitter.java` | —— |
| 29 | bash 全量输出落盘 `fullOutputPath` | L | **对齐** | **1** | `core/tools/bash.ts:68` | `BashTool.java:46` | —— |
| 30 | ~~钩子 `before_run`~~ | H | **作废（R1）** | 0 | `harness/agent-harness.ts` 已删 | `HookSystem.java:43,112` | 见作废清单 |
| 31 | ~~钩子 `before_drive`~~ | H | **作废（R1）** | 0 | drive 运行时已删 | 零命中 | —— |
| 32 | 钩子 `before_run_end`（`followUp`）⇒ **`getFollowUpMessages`** | H | **对齐** | **2** | `agent/src/types.ts:306` | `HookSystem.java:93,240` | —— |
| 33 | 钩子 `transform_context` | H | **对齐** | **2** | 见 #8（`agent/src/types.ts:244`） | `HookSystem.java:53,249` | —— |
| 34 | 钩子 `before_request` ⇒ **`prepareRequest`** | H | **存疑（形状）** | **2** | `agent/src/types.ts:271`（返回 context/model/thinking 替换；与旧 `before_payload` 合并） | `HookSystem.java:58,124` | —— |
| 35 | ~~钩子 `before_payload`~~ | H | **作废（R1）** | 0 | 载荷改写改在扩展事件 `before_provider_request`（`core/extensions/types.ts:878`）→ 属 04 | `HookSystem.java:63,134` | —— |
| 36 | ~~钩子 `after_response`~~ | H | **作废（R1）** | 0 | 扩展事件 `after_provider_response`（`core/extensions/types.ts:894`）→ 属 04 | `HookSystem.java:68,148` | —— |
| 37 | 钩子 `before_tool` ⇒ **`beforeToolCall`** | H | **对齐** | **2** | `agent/src/agent.ts:123`；`types.ts:326` | `HookSystem.java:73,158` | —— |
| 38 | 钩子 `after_tool` ⇒ **`afterToolCall`** | H | **对齐** | **2** | `agent/src/agent.ts:124`；`types.ts:341` | `HookSystem.java:78,185` | —— |
| 39 | ~~钩子 `before_compaction`~~ | H | **作废（R1）** | 0 | 扩展事件 `session_before_compact`（`core/extensions/types.ts:762`）→ 属 04 | `HookSystem.java:83,221` | —— |
| 40 | ~~钩子 `before_navigation`~~ | H | **作废（R1）** | 0 | 导航 hook 已删（导航本体见 #25） | `HookSystem.java:88,234` | —— |
| 41 | 钩子 `should_stop_after_turn`/`prepare_next_turn` ⇒ **`finishTurn`/`prepareNextTurn`** | H | **存疑（形状）** | **3** | `agent/src/types.ts:155-171,278` | `HookSystem.java:98,103,270,289` | **#6 同根** |
| 42 | ~~钩子 `before_resume`（java 独有）~~ | H | **作废（R1）** | 0 | pi 无对应（旧图即标 java 多） | `HookSystem.java:48,118` | —— |
| 43 | ~~effect gate（`gate.admit`）~~ | A | **作废（R1）** | 0 | `git grep effect-gate 200387122 -- packages/*/src` **零命中** | 零命中 | —— |
| 44 | ~~HarnessEvent 28 类~~ | E | **作废（R1）** | 0 | `lane_created`/`run_suspend`/`value_update` **零命中** | `HarnessEventBus.java` | A6 |
| 45 | ~~`run_suspend` + `DeferredHandle` 挂起驱动~~ | L | **作废（R1）** | 0 | `run_suspend` 零命中 | 零生产者 | —— |
| 46 | `AgentSessionEvent` 面（pi **24** 变体） | E | **存疑** | **3** | `core/agent-session.ts:191-231`（新增 `entry_appended`/`auto_retry_*`/`summarization_retry_*`） | `AgentSessionEvent.java`（**19** 变体） | B30/B34/B35 |
| 47 | **`queue_update` 生产者** | E | **缺失** | **2** | `core/agent-session.ts:1019` | 只有定义+映射，零 emit | **B34** |
| 48 | **`session_info_changed` 生产者** | E | **缺失** | **1** | `core/agent-session.ts:3895` | 同上 | **B30** |
| 49 | **`thinking_level_changed` 生产者** | E | **缺失** | **2** | `core/agent-session.ts:2581` | 同上 | **B35** |
| 50 | `bash_execution_update` 生产者 | E | **对齐** | **2** | `core/agent-session.ts:3813` | `AgentSessionEvent.BashExecutionUpdate` | —— |
| 51 | `compaction_start`/`compaction_end` 事件 | E | **对齐** | **3** | `core/agent-session.ts:203-214,2720,2831` | `CompactionObserver.java` | —— |
| 52 | `agent_end` 载荷 = **本 pass** `newMessages` | E | **存疑** | **3** | `agent/src/agent-loop.ts:254,290,320`；`core/agent-session.ts:1084,1112` | 引擎对（`PiLoopRunner.java`）；**会话层**发 `accumulatedMessages()` | **A8** |
| 53 | `turn_end` 颗粒度 | E | **存疑** | **2** | `agent/src/agent-loop.ts:253,287`；`core/agent-session.ts:840` | `AgentSessionEvent.AgentSettled` | **B49** |
| 54 | Entry union | N | **存疑（形状）** | **3** | **11 类**（`core/session-manager.ts:183-194`：message/thinking_level_change/model_change/usage/compaction/branch_summary/custom/custom_message/context_edit/label/session_info） | **7 类**（`Entry.java:43-190`） | pi 主流是 **v3 平铺**（`session-manager.ts:41` `CURRENT_SESSION_VERSION=3`） |
| 55 | `CompactionEntry.fromHook` / `BranchSummaryEntry.fromHook` | N | **缺失** | **1** | `core/session-manager.ts:101,115`（`fromHook?: boolean`，兼容字段） | 无该字段 | —— |
| 56 | `EntryProjector`（`ProjectedSessionEntry` / `buildSessionProjection`） | N | **存疑** | **1** | `core/session-manager.ts:209,543` | `ContextEntries.java:172` | A1 |
| 57 | 四个消息类型 | N | **存疑** | **2** | `core/messages.ts:29-66` | `ContextEntries.java` + `CustomMessageContent.java` | A1 |
| 58 | `convertToLlm` | N | **存疑** | **3** | `core/messages.ts:148-190`（`case "system"` `:184`）；默认 `agent/src/agent.ts:38` | `ContextEntries.java:159-186` | **A1** |
| 59 | ~~`LaneConfiguration`~~ | N | **作废（R1）** | 0 | `harness/session/types.ts` 已删 | entry 化 | #54 同根 |
| 60 | `PendingEntry` / `InboxItem` ⇒ 队列契约 | N | **存疑** | **2** | `agent/src/types.ts:293,306` | `QueueManager.java` | —— |
| 61 | **`formatSkillsForSystemPrompt`** | P | **对齐** | **2** | `core/skills.ts:355`（`formatSkillsForPrompt`）；`core/system-prompt.ts:167` | `SkillsPrompt.java:31`（**已落地**） | 已闭环 |
| 62 | **`formatSkillInvocation`** | P | **缺失** | **2** | `core/skills.ts`（技能内含调用格式） | 无 | —— |
| 63 | `loadSkills` / `loadSourcedSkills` | P | **存疑** | **2** | `core/skills.ts:409,168` | `SkillDiscovery`（在 coding-agent） | —— |
| 64 | `loadPromptTemplates` 等四函数 | P | **存疑** | **2** | `core/prompt-templates.ts:222,304` | `PromptTemplates`（在 coding-agent） | —— |
| 65 | **`sanitizeSurrogates`** | A | **缺失** | **1** | pi 多处 | 全仓零命中 | **B16** |
| 66 | ~~`AdaptivePublisher`~~ | A | **作废（R1）** | 0 | `harness/utils/adaptive-publisher.ts` 已删 | 无 | —— |
| 67 | ~~`applyShellOutputUpdate` / `ShellOutputView`~~ | A | **作废（R1）** | 0 | `harness/utils/output-capture.ts`/`shell-output.ts` 已删（主流量见 #28） | `ShellOutputSink.java` | —— |
| 68 | **`streamProxy`** | A | **缺失** | **1** | `agent/src/proxy.ts:120`（**存活**） | 无 | —— |
| 69 | **`setDefaultStreamFn`** | A | **缺失** | **1** | `agent/src/stream-fn.ts:11`（**存活**） | 无 | —— |
| 70 | ~~`SessionSearchService`（6 方法）~~ | A | **作废（R1）** | 0 | `agent/src/search/` 已删；`SessionSearch` 无主流命中 | `SessionSearch.java` | —— |
| 71 | ~~`reduceLaneSnapshot`~~ | A | **作废（R1）** | 0 | `harness/runtime/reducer.ts` 已删 | 无 | —— |
| 72 | ~~错误面 15 个 `TaggedError`~~ | A | **作废（R1）** | 0 | `TaggedError` 零命中 | 4 个异常类 | —— |
| 73 | JSONL 存储（事务 + `values.ts`） | S | **存疑（形状）** | **3** | 主流 = `core/session-manager.ts` **v3 平铺**（`appendFileSync`＋`{type:"session",version:3}`，`session-manager.ts:41,1064`） | `JsonlSessionStorage.java`（v4 平铺） | —— |
| 74 | ~~存储 conformance / benchmark 套件~~ | S | **作废（R1）** | 0 | `harness/session/testing/*` 已删 | agent-core 无 | A17 |
| 75 | session fork | S | **存疑** | **2** | `core/session-manager.ts:1815`（`SessionManager.forkFrom`） | `ForkOptions.java` | —— |
| 76 | `session_start` / `resources_discover` | S | **对齐** | **2** | `core/agent-session.ts:481,3646`；`core/extensions/runner.ts:1490` | `AgentSession.java` | —— |
| 77 | 会话恢复（resume seed + record replay） | S | **存疑** | **2** | `core/agent-session.ts:1762,4089`（`_restoreToolsFromTranscript`） | `RunLifecycle.java:272,318`（seed/restore） | A2 |
| 78 | `latest()` / `find()` 会话定位范围 | S | **存疑** | **3** | `core/session-manager.ts:749`（`findMostRecentSession` 只读一个项目目录） | `PersistentSessionRepositories.find()` 走 `list(all())` | **B53** |
| 79 | 会话列表 `onProgress` | S | **缺失** | **1** | `core/session-manager.ts:943-965` | `list` 是同步全量 | **B55** |
| 80 | 宿主 `prompt()` 的 `streamingBehavior` | L | **存疑** | **2** | `core/agent-session.ts:304,1967-1972` | `AgentSession.processPrompt:602`；门在 `PiLaneEngine.java:87` | **A5/F4** |
| 81 | 工具线程安全契约 | A | **存疑** | **1** | `agent/src/agent-loop.ts:508-660` | 无显式声明 | **A10/A11** |
| 82 | `retry.provider.*` ↔ `HTTP RetryPolicy` | A | **存疑** | **2** | —— | —— | **A7** |
| 83 | `transcript()` 下游消费者清点 | A | **存疑** | **1** | —— | —— | **A2** |
| 84 | 两道门的行为裁决 | L | **存疑** | **2** | 见 #17/#80 | —— | **F4** |
| 85 | `recordEvent` 无宿主跨度时的兜底 | A | **存疑** | **1** | —— | —— | **F2** |
| 86 | `_emitSessionCompactFailed` | E | **存疑** | **1** | `core/agent-session.ts:1025-1027` | 无 | **F3** |
| 87 | L5 夹具对 thinking 块不对称 | A | **存疑** | **1** | `conformance/pi/run.test.ts`（在 pi-java 仓） | `ScriptedStreams.java` | **E8** |
| 88 | `ActiveToolsChange` 发射 | E | **存疑** | **1** | —— | 不发射 | **C1** |
| 89 | 批内 join 源序 vs `Promise.all` | L | **存疑** | **1** | `agent/src/agent-loop.ts:646`（`Promise.all` 收束保源序） | `PiLoopTools.java:148-186` | **C2** |
| 90 | `transcript`/`messages`/`partial` 可见性 | S | **存疑** | **1** | —— | 写者唯一 | **C3** |
| 91 | `RunLifecycle.reset` 不 `close()` runSpan | S | **存疑** | **1** | —— | `RunLifecycle.java:236` | **C4** |
| 92 | 遥测 `startSpan`/`openSpan` javadoc | A | **存疑** | **1** | —— | `TelemetryContext` | **D2/D3** |
| 93 | `SummaryGenerator.java:12` javadoc 过期 | A | **存疑** | **1** | —— | 主源码 | **D6** |
| 94 | 文件超限：`PiLaneSink`/`AgentHarness` 行数 | A | **存疑** | **1** | —— | `wc -l` | **E3/E4** |
| 95 | 夹具在集成层无牙 | A | **存疑** | **1** | —— | `ShellOutputStreamingTest` | **A14** |
| 96 | 错误路 `AgentSettled` 无夹具 | A | **存疑** | **1** | —— | `SessionRunner` | **A15** |
| 97 | **扩展开启（extended thinking）目录→请求投送** | C | **对齐** | **2** | `models.json thinkingLevelMap` → `core/provider-composer.ts` → `provider-composer`/`anthropic-messages` | `AgentHarness.java:135`；`PiLaneEngine.java:330`；`PiLoopRunner.java:173`；`DefaultProviders.java:255-261`（**已接线**） | 已闭环 |
| 98 | **`abortCompaction`** | A | **缺失** | **1** | `core/agent-session.ts:2867`；TUI `modes/interactive/interactive-mode.ts` | 全仓零命中 | **B36** |
| 99 | **工具装配变更声明**（`declareToolChanges`） | L | **对齐** | **2** | `agent/src/agent-loop.ts:333-374`；`agent/src/types.ts:502-505` | `ToolChangeDeclaration.java`（**已落地**） | 已闭环 |
| 100 | **系统提示作为 transcript 的 system 消息** | N | **存疑（形状）** | **3** | `packages/ai/src/types.ts:748-751`（`TranscriptContext` **只有 messages**）；`agent/src/agent.ts:85`；`agent/src/types.ts:500-503`（`AgentContext` 只有 messages） | java 走 `Context.systemPrompt` 字段（`Context.java:44`）+ `AgentHarness.setSystemPrompt:458`；**sections/差分已移植**（`SystemPrompts.java:66,138`）⇒ 首条等价、会话中变更不等价 | **新** |
| 101 | **`prepareNextTurn` 可返回 `messages`** | L | **对齐** | **2** | `agent/src/types.ts:167-169`；`agent-loop.ts:186,211` | `PiLoop.NextTurnUpdate.java:209-224`（**已有 `messages`**） | 已闭环 |
| 102 | `Agent.reset()` **保留基线 system 消息** | L | **存疑** | **1** | `agent/src/agent.ts:355-367`（`getCurrentSystemMessage` 保留首条） | `RunLifecycle.java:236-245` 清空 transcript 与工作副本 | **新** |
| 103 | ~~`experimental/pico3` kernel~~ | S | **作废（R5）** | 0 | 已随 harness 删除 | 无 | —— |
| 104 | ~~`packages/durable`~~ | S | **作废（R5）** | 0 | 61 文件但 import 者全在 `experimental/` | 无 | —— |

> **不在本模块范围的台账条目**：B14/B17/B18/B19/B21/B23/B24（`pi-java-ai`）、
> B28/B29/B31/B32/B33/B48/B50/B51（RPC 线格式）、B38/B39/B47（TUI）、B52/A13/A16（已结案）、B54（web 路径）。

---

## 作废清单（R1／R5 —— 权重 0，移出分母）

**19 条能力单元**（旧编号保留，便于对照）：#30 `before_run` · #31 `before_drive` · #35 `before_payload` ·
#36 `after_response` · #39 `before_compaction` · #40 `before_navigation` · #42 `before_resume` ·
#43 effect gate · #44 HarnessEvent · #45 `run_suspend` · #59 `LaneConfiguration` · #66 `AdaptivePublisher` ·
#67 `output-capture`/`shell-output` · #70 `SessionSearchService` · #71 `reduceLaneSnapshot` · #72 `TaggedError` ·
#74 存储 conformance 套件 · #103 pico3 · #104 durable。

**一条整块缺失**：① drive 运行时（`harness/runtime/drive/` 12 文件）—— 参照物被删。

> **为什么作废而非改判「对齐」**（`docs/11 §7 J5`）：判据是「和 pi 表现一样」。这些行为在 pi 主流
> 已不存在（删或移到 R5 不可达的 `durable`/`experimental`）⇒ 无从对齐，只能移出分母。
> 其中 `before_payload`/`after_response`/`before_compaction` 的**替身是扩展事件**
> （`before_provider_request`/`after_provider_response`/`session_before_compact`），属 `04-coding-agent` 的域。

---

## 整块缺失

**本轮已无独立计权项** —— 旧表 11 条中：① drive（作废）、③ effect gate（作废）、④ 存储套件（作废）、
⑥ `AdaptivePublisher`（作废）、⑦ `values.ts`（作废）、⑩ pico3（作废）、⑪ durable（作废）；
② branch summary+导航、⑤ proxy、⑧ skills、⑨ sanitize 仍作**能力单元**（#24/#25、#68、#61/#62、#65）逐条计权。

---

## pi 新锚（`200387122`）相对旧锚（`3390bd936`）的实质变化

| 单元 | 旧判定 | 新判定 | 一句证据 |
|---|---|---|---|
| `agent/src/harness/**` 108 文件 | 参照物 | **删除** | `git diff --stat 3390bd936 200387122 -- packages/agent/src` = `−31,063` |
| **#6 `shouldStopAfterTurn`** | 对齐 | **存疑（形状）** | 改名 `finishTurn`，返 `{action:"continue"\|"end"}`（`types.ts:146-171,264`）；java 仍布尔 `StopHook` |
| **#34 `before_request`→`prepareRequest`** | 对齐 | **存疑（形状）** | 新 hook 返 context/model/thinking 替换（`types.ts:271`） |
| **#97 扩展开启目录→请求投送** | 缺失 | **对齐** | `thinkingLevelMap` 现有 3 处生产调用（`AgentHarness.java:135` 等） |
| **#99 `declareToolChanges`** | 缺失 | **对齐** | `ToolChangeDeclaration.java` 已落地（pi `agent-loop.ts:333-374`） |
| **#101 `prepareNextTurn` 返 `messages`** | 缺失 | **对齐** | `PiLoop.NextTurnUpdate.java:209-224` 已有 `messages` |
| **#61 `formatSkillsForSystemPrompt`** | 缺失 | **对齐** | `SkillsPrompt.java:31`（pi `core/skills.ts:355`） |
| **#23 输出截断字节预算** | 对齐 | **存疑** | pi 主流 50KB（`core/tools/truncate.ts:12`）vs java 100KB（`TruncationUtils.java:16`） |
| **#54 Entry union** | 存疑 | **存疑（缺口变大）** | pi 主流 11 类（`session-manager.ts:183-194`）vs java 7 类 |
| **#46 `AgentSessionEvent`** | 存疑 | **存疑（缺口变大）** | pi 24 变体（`agent-session.ts:191-231`）vs java 19 |
| `packages/durable` / `pico3` | 新包 | **R5 不可达，作废** | import 者全在 `experimental/` |

**pi 新增 hook（本图新增单元）**：`finishTurn`（并入 #6）、`prepareRequest`（并入 #34）。
`agent/src/agent.ts:130-131` 另有 `prepareNextTurnWithContext`（`Agent` 包装层，行为并入 #7）。

---

## 汇总（未加权）

| 表 | 对齐 | 缺失 | 存疑 | 合计 | 完成度 |
|---|---:|---:|---:|---:|---:|
| 内置工具 | 4 | 1 | 4 | 9 | **44.4%** |
| 能力单元（除工具） | 32 | 15 | 38 | 85 | **37.6%** |
| 整块缺失（无独立计权项） | 0 | 0 | 0 | 0 | —— |
| **合计** | **36** | **16** | **42** | **94** | **38.3%** |

**未加权完成度 = 36 / 94 = 38.3%**（旧 40 / 114 = 35.1%）
（对齐＋存疑视为「有」：78/94 = **83.0%**）

---

## 加权汇总

| 域 | Σ权重 | Σ(w×系数) | 加权完成度 | 未加权完成度 |
|---|---:|---:|---:|---:|
| 内置工具（9） | 21 | 15.0 | **71.4%** | 44.4% |
| 能力单元（除工具，85） | 163 | 108.0 | **66.3%** | 37.6% |
| **模块合计（94）** | **184** | **123.0** | **66.8%** | **38.3%** |

**模块合计**：Σ权重 184 ／ Σ(w×c) 123.0 ／ **加权完成度 = 123.0/184 = 66.8%**（未加权 38.3%）。
（旧：Σw 214 / Σ(w×c) 133.5 = **62.4%**；未加权 35.1%。）

> **换锚把加权完成度拉高 4.4 pp**，原因**不是 pi-java 变强**，而是重组：19 条作废单元 + 1 整块缺失里
> 大量是权重 1/2 的「缺失」（pico3/durable/drive/effect-gate/搜索/存储套件），它们**移出分母**；
> pi-java 侧另有 4 条真闭环（#61/#97/#99/#101）。同时 #6/#23/#34 三条新增/翻案为「存疑」抵消了一部分。

按权重分层（能力单元表，85 条）：

| 权重 | 条数 | Σ权重 | 对齐 | 存疑 | 缺失 | Σ(w×c) | 层完成度 |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 3 | 21 | 63 | 11 | 10 | 0 | 48.0 | **76.2%** |
| 2 | 36 | 72 | 19 | 12 | 5 | 50.0 | **69.4%** |
| 1 | 28 | 28 | 2 | 16 | 10 | 10.0 | **35.7%** |
| 合计 | 85 | 163 | 32 | 38 | 15 | 108.0 | 66.3% |

### 权重 3 的单元清单（21 条能力单元 + 5 条工具 = 26 条）

| 单元 | 判定 |
|---|---|
| `bash` 工具 | **存疑** |
| `read` / `write` / `edit` 工具 | 对齐（3 条） |
| `grep` 工具 | **存疑** |
| #1 `AgentEvent` 变体 | 对齐 |
| #2 双循环 | 对齐 |
| #3 `runAgentLoop`/`continue` 前置条件 | 对齐 |
| #6 `finishTurn`（原 `shouldStopAfterTurn`） | **存疑（形状）** |
| #7 `prepareNextTurn` + `AgentLoopTurnUpdate` | 对齐 |
| #8 `transformContext` | 对齐 |
| #9 工具两相端口与 end 次序 | 对齐 |
| #10 并行/顺序批次选择 | 对齐 |
| #16 轮内阈值压缩门 | 对齐 |
| #18 压缩后重建工作副本 | 对齐 |
| #20 上下文计量 | 对齐 |
| #23 输出截断 | **存疑** |
| #41 `should_stop`/`prepare_next_turn` 钩子 | **存疑（形状）** |
| #46 `AgentSessionEvent` 面 | **存疑** |
| #51 `compaction_start`/`compaction_end` 事件 | 对齐 |
| #52 `agent_end` 载荷 | **存疑** |
| #54 Entry union | **存疑（形状）** |
| #58 `convertToLlm` | **存疑** |
| #73 JSONL 存储（v4 vs v3） | **存疑（形状）** |
| #78 `latest()`/`find()` 会话定位范围 | **存疑** |
| #100 系统提示作为 system 消息 | **存疑（形状）** |

**权重 3 层 = 11 对齐 / 10 存疑 / 0 缺失**。

### 未加权 vs 加权的差（38.3% → 66.8%，**+28.5 pp**）

差仍**全部来自分母重新分配**：

| 池 | 未得分（Σw−Σ(w×c)） | 占比 |
|---|---:|---:|
| 能力单元 权重 2 | **22.0** | 36% |
| 能力单元 权重 1 | 18.0 | 30% |
| 能力单元 权重 3 | 15.0 | 25%* |
| 内置工具 | 6.0 | 10% |
| 合计 | 61.0 | 100% |

（*权重 3 层 10 条存疑 = 15.0 未得分，是本次「形态翻案」集中处。）

⇒ **权重 2 层（69.4%）仍是最大未得分池**。**缺失的权重分布**：15 条里 **0 条权重 3**、5 条权重 2、10 条权重 1。

### 三条点名项的边际影响（分母 184）

| 条目 | 权重 | 补齐后 | 边际 |
|---|---:|---:|---:|
| **A4 `/compact` 缺 abort-first**（#17，缺失→对齐） | 2 | 125.0/184 = 67.93% | **+1.09 pp** |
| **#23 截断字节预算 100KB→50KB**（存疑→对齐） | 3 | 124.5/184 = 67.66% | **+0.82 pp** |
| **#6 `finishTurn` 形状对齐**（存疑→对齐；含 #41 再加 1.5） | 3 | 124.5/184 = 67.66% | **+0.82 pp**（含 #41 共 **+1.63 pp**） |

---

## 与旧图的口径差（可被推翻）

- **钩子作废只作废「harness 专有」的**：凡行为在 `agent/src/*` 存活（`beforeToolCall`/`afterToolCall`/
  `transformContext`/`prepareNextTurn`/`finishTurn`/`getFollowUpMessages`）的一律改指保留；
  仅 harness 有、主流只在**扩展事件**里重生的（`before_payload`/`after_response`/`before_compaction`）作废并注记去向 04。
- **`streamProxy`（#68）/`setDefaultStreamFn`（#69）未作废**：二者在 `agent/src/proxy.ts`、`agent/src/stream-fn.ts`
  **逐字存活**，只是 pi-java 没有 ⇒ **仍判缺失**。
- **`#23` 由对齐降为存疑**：本次重测首次发现 java 侧字节预算（100KB）是 pi 主流（50KB）的两倍 —— 模型可见文案即不同。
