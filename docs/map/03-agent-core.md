# 03 Agent 运行时核心（pi-java-agent-core）

> 基准：pi @ **`ea448f454`**（2026-10-11）；pi-java @ **`edc8749d`**（main，2026-10-11）。
> 台账 = [`docs/04-open-items-register.md`](../04-open-items-register.md)。锚点事实 = [`ANCHOR.md`](ANCHOR.md)。
> 所有 pi 侧 `file:line` 均由 `git -C D:/workplaceForai/pi show ea448f454:<path>` 实读，**未读工作树**。

---

## 口径

**权重**（用户 2026-09-20 定）：权重 = 用户可观察影响 × 频率，不掺排期优先级。
`3` = 每轮对话都走 / 默认路径 · `2` = 每次会话走 / 常用命令 / 常用配置键 · `1` = 低频 / 边缘 / 纯内部 · `0` = 非目标（移出分母）。
**完成系数**：对齐 `1.0` ／ 存疑 `0.5` ／ 缺失 `0`。

### ★ 锚到两个 pi 面

pi 在 `7fd478a2e`（2026-10-01）**删除了 `packages/agent/src/harness/**`（108 文件）**，
连同 `agent/src/search/`、`agent/src/node.ts`。旧图 43 处引 `harness/*` 的参照物全部消失。

`harness/compaction`、`harness/messages`、`harness/session`、`harness/tools`
在主流 `coding-agent/src/core/*` 有**独立副本** —— 本图锚到两个面（均为 `bin pi` 主流可达）：

| 面 | pi 路径 | 规模 | 承担 |
|---|---|---:|---|
| **agent loop 本体** | `packages/agent/src/{agent-loop,agent,types,proxy,stream-fn,index}.ts` | **2,519 行** | 循环、事件、工具两相、proxy、stream-fn |
| **core 主流副本** | `packages/coding-agent/src/core/*` | 见规模节 | `agent-session` / `session-manager` / `compaction` / `messages` / `system-prompt` / `skills` / `prompt-templates` / `tools` / `settings-manager` / `model-config` / `provider-composer` |

实测支持（`git ls-tree -r ea448f454:packages/coding-agent/src/core/`）：上述文件**独立存在**；
唯一一处 "harness" 字样在 `core/system-prompt.ts:156`，**只是提示词里的人话**，非依赖。

### R1 应用（删掉的参照物 ⇒ 单元作废，移出分母）

**harness 专有、主流无副本**的行为一律作废（见「作废清单」21 条）。判据是「和 pi 表现一样」：
行为在 pi 主流已不存在（删或只活在扩展事件里）⇒ 无从对齐，移出分母。
`packages/durable`（61 文件 / 17,716 行）与 `pico3` 按 **R5 不可达**（import 者全在 `coding-agent/src/experimental/**`）⇒ 权 0。

### 判定三档

**对齐** / **缺失** / **存疑**（`（形状）`/`（旧 pi）`/`（扩展）`/`（台账）`）。**pi-java 侧已闭环的旧「缺失/存疑」按现状改判为对齐**。

---

## 规模（本锚实测）

| 模块 | pi 包 | pi LOC | java LOC | 比例 |
|---|---|---:|---:|---:|
| agent loop 本体 | `packages/agent/src` | **2,519**（6 文件） | —— | —— |
| core 主流副本 | `packages/coding-agent/src/core` | —— | `pi-java-agent-core/src/main` **19,795**（185 文件） | —— |
| 测试 | `packages/agent/test` | **4,058**（7 文件） | `src/test` **19,469**（119 文件） | —— |

**agent loop 本体逐文件**（`git show ea448f454:... | wc -l`）：

| 文件 | 行数 |
|---|---:|
| `agent/src/agent-loop.ts` | 949 |
| `agent/src/agent.ts` | 613 |
| `agent/src/types.ts` | 539 |
| `agent/src/proxy.ts` | 393 |
| `agent/src/stream-fn.ts` | 20 |
| `agent/src/index.ts` | 5 |
| **合计** | **2,519** |

**core 主流副本关键文件**（同法实测）：`agent-session.ts` **4,398** · `session-manager.ts` 2,013 ·
`settings-manager.ts` 1,428 · `compaction/compaction.ts` 1,119 · `provider-composer.ts` 732 ·
`skills.ts` 514 · `tools/bash.ts` 442 · `system-prompt.ts` 229 · `prompt-templates.ts` 320 · `messages.ts` 196。

> pi 测试 4,058 行含 `test/utils/` 3 个支持文件；仅 4 个 `*.test.ts` 为 3,969 行。

---

## 内置工具对齐

pi 工具定义现在**只有一处**：`packages/coding-agent/src/core/tools/`（`harness/tools/` 已删）。
`allToolNames`（8 个）在 `core/tools/index.ts:96`。pi-java 的 7 个工具在
`pi-java-agent-core/.../tool/builtin/`，由 `ToolSetFactory.java:25-35` 组装。

| # | 工具 | pi 有 | java 有 | 判定 | 权重 | 证据（**新锚实读**） |
|---|---|---|---|---|---|---|
| 1 | `bash` | ✅ | ✅ | **存疑** | **3** | pi `{command, timeout?}`，**无默认超时**，上限 `2147483 s`（`core/tools/bash.ts:22`，`resolveTimeoutMs :27-37`，schema `:40-43`）。java 同参数，但**默认 120 s / 上限 600 s**（`BashTool.java:36-39`，描述 `:64,:73`）⇒ 模型不传 timeout 时行为不同 |
| 2 | `read` | ✅ | ✅ | **对齐** | **3** | pi `{path, offset?, limit?}`（`core/tools/read.ts:15-17`）；java 同（`ReadTool.java:31`）。图片经批 9 缩放管线（`ReadTool.java` readImage：归一/转换/省略/非视觉注记，vs pi `core/tools/read.ts:88-153`） |
| 3 | `write` | ✅ | ✅ | **对齐** | **3** | pi `{path, content}`（`core/tools/write.ts`）；java 同（`WriteTool.java:31`）＋ 同形状 `FileMutationQueue`（`WriteTool.java:29`） |
| 4 | `edit` | ✅ | ✅ | **对齐** | **3** | pi `{path, edits:[{oldText,newText}]}`（`core/tools/edit.ts`）；java 同（`EditTool.java:39`） |
| 5 | `grep` | ✅ | ✅ | **存疑** | **3** | pi `{pattern, path?, glob?, ignoreCase?, literal?, context?, limit?}` + gitignore + 默认 100/50KB 截断（`core/tools/grep.ts:22-32,41`，截断常量 `core/tools/truncate.ts:11-12`）。java 只有 `{pattern, path?, glob?}`（`GrepTool.java:30`）⇒ **缺 4 参数** |
| 6 | `find` | ✅ `find` | ✅ `glob` | **存疑** | **2** | **名字不同**；pi `{pattern, path?, limit?}` 默认 1000，走 fd/ripgrep 尊重 gitignore（`core/tools/find.ts:27-31,41,112`）。java `{pattern, path?}`，NIO `PathMatcher` 全树 walk（`GlobTool.java:27`）⇒ 缺 limit + 无 gitignore + 无截断 |
| 7 | `ls` | ✅ | ✅ | **存疑** | **2** | pi `{path?, limit?}`（默认 500，`core/tools/ls.ts:12-13,23`）；java `{path?, recursive?}`（`LsTool.java:25,43-44`）⇒ **参数集不同** |
| 8 | `powershell` | ✅ | ❌ | **缺失** | **1** | pi `core/tools/powershell.ts`，工具名进 `allToolNames`（`core/tools/index.ts:96-104`）。java 仅系统提示词里按名条件拼过文案（`SystemPrompts.java:189-199`），**无工具实现** |
| 9 | `image`（读图处理器） | ✅ | ✅ | **对齐** | **1** | pi `core/tools/read.ts` + `utils/image-process.ts` + `utils/image-resize-core.ts` + `utils/mime.ts`；java **批 9（2026-10-16）**：`tool/image/{ImageProcessor,ImageResizer,ExifOrientation,WebpDimensions,ToolResultImages}.java`（ImageIO 替代 photon）＋ `PathUtils.detectImageMimeType` 加严（0xF7/aPNG/BMP 校验）＋ `PiToolRunner` after_tool 后统一归一；窄差见 docs/18 §5 |

**Σ权重 21**（对齐 10.0 ／ 存疑 5.0 ／ 缺失 0 ＝ 15.0，71.4%）。

---

## 能力单元清单（除工具）

类别：**L**=agent loop · **H**=钩子 · **E**=事件 · **N**=entry/消息 · **P**=prompt/模板 · **C**=上下文管理 · **S**=存储/会话 · **A**=API 面。
pi 侧证据锚定 `agent/src/*` 或 `coding-agent/src/core/*`。

| # | 能力单元 | 类别 | 判定 | 权重 | pi 侧证据（`ea448f454`） | pi-java 侧证据（`edc8749d`） | 台账 |
|---|---|---|---|---|---|---|---|
| 1 | 10 个 `AgentEvent` 变体 | E | **对齐** | **3** | `agent/src/types.ts:516-539` | `PiLoop.java:45-77`（L5 14 剧本） | —— |
| 2 | 双循环（内层工具+steering / 外层 follow-up） | L | **对齐** | **3** | `agent/src/agent-loop.ts:163-320` | `PiLoopRunner.java:45-160` | —— |
| 3 | `runAgentLoop`/`runAgentLoopContinue` 前置条件 | L | **对齐** | **3** | `agent/src/agent-loop.ts:135-150` | `PiLoop.java:266+`；`PiLaneEngine.java:87,122` | —— |
| 4 | 工具批次 `terminate` 取**全部**（`every`） | L | **对齐** | **2** | `agent/src/agent-loop.ts:690-692` | `PiLoopTools.java:51-52` | —— |
| 5 | `length` 截断 ⇒ 本回合**全部**工具调用判失败 | L | **对齐** | **2** | `agent/src/agent-loop.ts:264-272,470-503` | `PiLoopRunner.java:212+` | —— |
| 6 | **`finishTurn`（原 `shouldStopAfterTurn`）** | L | **存疑（形状）** | **3** | `agent/src/types.ts:146-157,264`；调用 `agent-loop.ts:252,286`——返 `{action:"continue"\|"end"}`，能强制多跑一轮 | `PiLoop.java:237`（`StopHook` 返布尔，`:278`）；`HookSystem.java:98,270`；`PiLaneEngine.java:439-441` | **B159** |
| 7 | `prepareNextTurn` + `AgentLoopTurnUpdate` | L | **对齐** | **3** | `agent/src/agent-loop.ts:186`；`types.ts:159-169,278-283` | `PiLoop.java:198-224`；`PiLoopRunner.java:169` | —— |
| 8 | `transformContext` | L | **对齐** | **3** | `agent/src/agent-loop.ts:390-391`；`types.ts:244` | `HookSystem.java:53,249` | —— |
| 9 | 工具两相端口（immediate / prepared）与 end 次序 | L | **对齐** | **3** | `agent/src/agent-loop.ts:508-660,707-945` | `PiLoop.java`（两相）；`PiLoopTools.java:90-186` | —— |
| 10 | 并行/顺序批次选择（Sequential ⇒ 整批降级） | L | **对齐** | **3** | `agent/src/agent-loop.ts:508-524` | `PiLoopTools.java:76-88` | —— |
| 11 | 重试环 A（post-run assistant 重试） | L | **对齐** | **2** | `core/agent-session.ts:1198-1213,1864-1876,3762+`（`_prepareRetry`/`_willRetryAfterAgentEnd`） | `PostRunRetry.java` | —— |
| 12 | 重试环 B（摘要重试） | L | **对齐** | **1** | `core/agent-session.ts:224-236`（`summarization_retry_*` 事件）；回调 `:3721` | `LlmSummaryGenerator.java` + `PostRunRetry.java` | —— |
| 13 | 队列：steer / followUp / nextRun | L | **对齐** | **2** | `agent/src/types.ts:293,306`（`getSteeringMessages`/`getFollowUpMessages`） | `QueueManager.java:40-62,120-159` | —— |
| 14 | `QueueMode.All` 的宿主层语义 | L | **存疑** | **2** | `agent/src/types.ts:52-55`（`"all" \| "one-at-a-time"`） | `QueueMode.java`；引擎侧 drain 分支 `QueueManager.java:149-157`；宿主门语义未全核 | **F1** |
| 15 | 溢出恢复（compact-and-retry 一次） | L | **对齐** | **2** | `core/agent-session.ts:405,2945-3041`（`_overflowRecoveryAttempted`） | `PostRunCompactionCheck.java` | —— |
| 16 | 轮内阈值压缩门 | L | **对齐** | **3** | `core/agent-session.ts:776-905`（`_compactBeforeNextAssistantResponse`） | `ContextUsageEstimator.java:200-201`（`shouldCompact`） | —— |
| 17 | **手动 `/compact` 先 `abort()` 当前运行** | L | **缺失** | **2** | `core/agent-session.ts:2764-2767`（`compact()` 开头 `await this.abort()`，随后才 `compaction_start`） | `CompactionExecutor.java:80-117` 与 `AgentSession.java:716-733`（直入 `harness.compact`）**均无 abort** | **A4/F4** |
| 18 | 压缩后重建工作副本（`NextTurnUpdate.context`） | L | **对齐** | **3** | `core/agent-session.ts`（compact → next turn context） | `PiLoop.java:198-224` | —— |
| 19 | 摘要 prompt 前缀/后缀逐字节 | C | **对齐** | **2** | `core/messages.ts:11-24`（`COMPACTION_SUMMARY_*` / `BRANCH_SUMMARY_*`） | `ContextEntries.java:33-38` | —— |
| 20 | 上下文计量（用量优先 + 尾字符估算） | C | **对齐** | **3** | `core/compaction/compaction.ts:196-225`（`estimateProjectedContextTokens :227`） | `ContextUsageEstimator.java:129-134` | —— |
| 21 | `getLastAssistantUsage` | C | **对齐** | **2** | `core/compaction/compaction.ts:166` | `ContextUsageEstimator.java:87`（`assistantUsageOf`） | —— |
| 22 | `findCutPoint` / `findTurnStartIndex` | C | **对齐** | **2** | `core/compaction/compaction.ts:446,412` | `CompactionService.java:211,268-269`（调用 `:83`） | —— |
| 23 | 输出截断（2000 行 / 头/尾两种） | C | **存疑** | **3** | pi `core/tools/truncate.ts:11-12` = **2000 行 / 50KB（51,200）**；read 描述 `core/tools/read.ts:96` | `TruncationUtils.java:16-17` = 2000 行 / **100,000 字节**（`ReadTool.java:42-43`）⇒ **字节预算约两倍**，模型可见文案即不同 | **B158** |
| 24 | **branch summary 生成** | C | **缺失** | **1** | `core/compaction/branch-summarization.ts:108,195`（`collectEntriesForBranchSummary`/`prepareBranchEntries`） | 只有 `Entry.BranchSummary` 类型 + 文案 + 读侧投影（`CompactionService.java:363`）；**无生成器** | **B1** |
| 25 | **会话树导航 `navigateTree`** | L | **缺失** | **1** | `core/agent-session.ts:3964-3990` | 全仓零命中 | B1 同族 |
| 26 | **per-model 压缩设置** | C | **对齐** | **3** | `core/settings-manager.ts:831-866`（`modelOverrides`，`getCompactionSettings(model)`） | `AgentSession.java:420`（`compactionResolver`）；`AgentHarness.java:136-140` 按车道当前模型现解析 | —— |
| 27 | **branch summary 设置**（`reserveTokens`/`skipPrompt`） | C | **缺失** | **1** | `core/settings-manager.ts:867-875`（`getBranchSummarySettings`） | 无 | B1 同族 |
| 28 | bash 流式 `tool_execution_update` | L | **对齐** | **2** | `core/tools/bash.ts:265,283-319`（`onUpdate`） | `BashTool.java` + `BashUpdateEmitter.java:37` | —— |
| 29 | bash 全量输出落盘 `fullOutputPath` | L | **对齐** | **1** | `core/tools/bash.ts:68` | `BashTool.java:44` | —— |
| 30 | ~~钩子 `before_run`~~ | H | **作废（R1）** | 0 | `harness/agent-harness.ts` 已删 | `HookSystem.java:43,112` | 见作废清单 |
| 31 | ~~钩子 `before_drive`~~ | H | **作废（R1）** | 0 | drive 运行时已删 | 零命中 | —— |
| 32 | 钩子 `before_run_end`（`followUp`）⇒ **`getFollowUpMessages`** | H | **对齐** | **2** | `agent/src/types.ts:306` | `HookSystem.java:93,240` | —— |
| 33 | 钩子 `transform_context` | H | **对齐** | **2** | 见 #8（`agent/src/types.ts:244`） | `HookSystem.java:53,249` | —— |
| 34 | 钩子 `before_request` ⇒ **`prepareRequest`** | H | **存疑（形状）** | **2** | `agent/src/types.ts:171-175,271`（返回 context/model/thinking 替换，**不含 messages**） | `HookSystem.java:58,124`（老 payload 形状） | —— |
| 35 | ~~钩子 `before_payload`~~ | H | **作废（R1）** | 0 | 载荷改写改在扩展事件 `before_provider_request`（`core/extensions/types.ts:888`）→ 属 04 | `HookSystem.java:63,134` | —— |
| 36 | ~~钩子 `after_response`~~ | H | **作废（R1）** | 0 | 扩展事件 `after_provider_response`（`core/extensions/types.ts:904`）→ 属 04 | `HookSystem.java:68,148` | —— |
| 37 | 钩子 `before_tool` ⇒ **`beforeToolCall`** | H | **对齐** | **2** | `agent/src/agent.ts:123`；`types.ts:326` | `HookSystem.java:73,158` | —— |
| 38 | 钩子 `after_tool` ⇒ **`afterToolCall`** | H | **对齐** | **2** | `agent/src/agent.ts:124`；`types.ts:341` | `HookSystem.java:78,185` | —— |
| 39 | ~~钩子 `before_compaction`~~ | H | **作废（R1）** | 0 | 扩展事件 `session_before_compact`（`core/extensions/types.ts:772`）→ 属 04 | `HookSystem.java:83,221` | —— |
| 40 | ~~钩子 `before_navigation`~~ | H | **作废（R1）** | 0 | 导航 hook 已删（导航本体见 #25） | `HookSystem.java:88,234` | —— |
| 41 | 钩子 `should_stop_after_turn`/`prepare_next_turn` ⇒ **`finishTurn`/`prepareNextTurn`** | H | **存疑（形状）** | **3** | `agent/src/types.ts:152-169,278` | `HookSystem.java:98,103,270,289` | **#6 同根 B159** |
| 42 | ~~钩子 `before_resume`（java 独有）~~ | H | **作废（R1）** | 0 | pi 无对应（旧图即标 java 多） | `HookSystem.java:48,118` | —— |
| 43 | ~~effect gate（`gate.admit`）~~ | A | **作废（R1）** | 0 | `git grep effect-gate ea448f454 -- packages/*/src` **零命中** | 零命中 | —— |
| 44 | ~~HarnessEvent 28 类~~ | E | **作废（R1）** | 0 | `lane_created`/`run_suspend`/`value_update` **零命中** | `HarnessEventBus.java` | A6 |
| 45 | ~~`run_suspend` + `DeferredHandle` 挂起驱动~~ | L | **作废（R1）** | 0 | `run_suspend` 零命中 | 零生产者 | —— |
| 46 | `AgentSessionEvent` 面（pi **24** 变体） | E | **存疑** | **3** | `core/agent-session.ts:196-237`（9 个被包装的 AgentEvent 变体 + 15 个会话变体） | `AgentSessionEvent.java:19-153`（**20** record；缺 agent_start/turn_start/turn_end/message_start/message_end 等面，attempt_start 两形状合一，另有 FlashStatus 方言） | B30 族 |
| 47 | `queue_update` 生产者 | E | **对齐** | **2** | `core/agent-session.ts:1045-1051`（`_emitQueueUpdate`），入队/出队点多处 | `AgentSession.java:655,660,706,739` 发射 + 公共 flush `:810,:828` | 已闭环 |
| 48 | **`session_info_changed` 生产者** | E | **缺失** | **1** | `core/agent-session.ts:3942-3948`（`setSessionName` 内 emit + 扩展 emit） | 只有定义（`AgentSessionEvent.java:73`）+ 映射（`JsonEventMapper.java:103`）；`AgentSession.java:529-534` **只改名不发事件** | **B30** |
| 49 | `thinking_level_changed` 生产者 | E | **对齐** | **2** | `core/agent-session.ts:2628`（isChanging 门内） | `AgentSession.java:509-516`（门同，`:513` 发射） | 已闭环 |
| 50 | `bash_execution_update` 生产者 | E | **对齐** | **2** | `core/agent-session.ts:3862` | `AgentSessionEvent.BashExecutionUpdate` | —— |
| 51 | `compaction_start`/`compaction_end` 事件 | E | **对齐** | **3** | 定义 `core/agent-session.ts:209-222`；手动发射 `:2767,2878-2902`；自动 `:3119,3205` | `CompactionObserver`（接线 `AgentSession.java:290-306`） | —— |
| 52 | `agent_end` 载荷 = **本 pass** `newMessages` | E | **存疑** | **3** | 引擎 `agent/src/agent-loop.ts:254,290,320`；会话层**透传引擎载荷** `core/agent-session.ts:1141,1288-1289` | 引擎对（`PiLoop.java:48`）；**会话层**发 `accumulatedMessages()`（`SessionRunner.java:259-268`） | **A8** |
| 53 | `turn_end` 颗粒度 | E | **存疑** | **2** | pi 引擎每回合 `agent/src/agent-loop.ts:253,287`；会话边界 `core/agent-session.ts:1290+` | 每**驱动**一条 `AgentSettled`（`SessionRunner.java:171-172,196`） | **B49** |
| 54 | Entry union | N | **存疑（形状）** | **3** | **11 类**（`core/session-manager.ts:65-176`：message/thinking_level_change/model_change/usage/compaction/branch_summary/custom/custom_message/context_edit/label/session_info）；v3 平铺（`:41` `CURRENT_SESSION_VERSION=3`） | 内存 union **9 类**（`Entry.java:46-294`，缺 label/session_info）；二者改以 fact 形态存活（`SessionMutation.FactName/FactLabel`），**线格式按 pi 原生拼写写出读入**（见 #73） | —— |
| 55 | `CompactionEntry.fromHook` / `BranchSummaryEntry.fromHook` | N | **缺失** | **1** | `core/session-manager.ts:101,115`（`fromHook?: boolean` 兼容字段） | 无该字段（仅 `CompactionFiles.java:65-68` javadoc 提及） | —— |
| 56 | `EntryProjector`（`ProjectedSessionEntry` / `buildSessionProjection`） | N | **存疑** | **1** | `core/session-manager.ts:209,543` | `ContextEntries.java:167-218`（`ProjectedEntry`/`projectEntries`） | A1 |
| 57 | 四个消息类型 | N | **存疑** | **2** | `core/messages.ts:29-66`（bashExecution/custom/branchSummary/compactionSummary） | `ContextEntries.java` + `CustomMessageContent.java` | A1 |
| 58 | `convertToLlm` | N | **存疑** | **3** | `core/messages.ts:148-196`（`case "system"` `:184`）；默认 `agent/src/agent.ts:38` | `ContextEntries.java:253-294` | **A1** |
| 59 | ~~`LaneConfiguration`~~ | N | **作废（R1）** | 0 | `harness/session/types.ts` 已删 | entry 化 | #54 同根 |
| 60 | `PendingEntry` / `InboxItem` ⇒ 队列契约 | N | **存疑** | **2** | `agent/src/types.ts:293,306` | `QueueManager.java` | —— |
| 61 | **`formatSkillsForSystemPrompt`** | P | **对齐** | **2** | `core/skills.ts:358`（`formatSkillsForPrompt`）；调用 `core/system-prompt.ts:180` | `SkillsPrompt.java:30-58`（三行指引 + XML 逐字） | 已闭环 |
| 62 | **技能调用展开（`/skill:` → `<skill>` 块）** | P | **缺失** | **2** | 主流无独立函数（harness 旧 `formatSkillInvocation` 已随删）；现行为内联在 `core/agent-session.ts:2149-2162`（`_expandSkillCommand`：读 SKILL.md、剥 frontmatter、组 `<skill name location>…</skill>`） | 技能加载齐（`SkillDiscovery`），**无 `/skill:` 展开/组块**，全仓零命中 | —— |
| 63 | `loadSkills` / `loadSourcedSkills` | P | **存疑** | **2** | `core/skills.ts:414,168`（`loadSkillsFromDir`） | `SkillDiscovery`（在 coding-agent） | —— |
| 64 | `loadPromptTemplates` 等四函数 | P | **存疑** | **2** | `core/prompt-templates.ts:222,304` | `PromptTemplates.java:24`（在 coding-agent） | —— |
| 65 | `sanitizeSurrogates` | A | **对齐** | **1** | pi `utils/sanitize-unicode.ts:21` | ✅ pi-java-ai 的 `SanitizeUnicode`，生产调用如 `AnthropicMessageConverter.java:55,135` —— **跨模块** | 已闭环 |
| 66 | ~~`AdaptivePublisher`~~ | A | **作废（R1）** | 0 | `harness/utils/adaptive-publisher.ts` 已删 | 无 | —— |
| 67 | ~~`applyShellOutputUpdate` / `ShellOutputView`~~ | A | **作废（R1）** | 0 | `harness/utils/output-capture.ts`/`shell-output.ts` 已删（主流量见 #28） | `ShellOutputSink.java` | —— |
| 68 | **`streamProxy`** | A | **缺失** | **1** | `agent/src/proxy.ts:107-111`（**存活**；本锚改返 pi-ai 的 `createAssistantMessageEventStream()`，本地 `ProxyMessageEventStream` 类已删） | 无 | —— |
| 69 | **`setDefaultStreamFn`** | A | **缺失** | **1** | `agent/src/stream-fn.ts:11`（**存活**）；`index.ts:4` 再导出 | 无 | —— |
| 70 | ~~`SessionSearchService`（6 方法）~~ | A | **作废（R1）** | 0 | `agent/src/search/` 已删；`SessionSearch` 无主流命中 | `SessionSearch.java` | —— |
| 71 | ~~`reduceLaneSnapshot`~~ | A | **作废（R1）** | 0 | `harness/runtime/reducer.ts` 已删 | 无 | —— |
| 72 | ~~错误面 15 个 `TaggedError`~~ | A | **作废（R1）** | 0 | `TaggedError` 零命中 | 4 个异常类 | —— |
| 73 | JSONL 存储（v3 平铺事务追加） | S | **对齐** | **3** | `core/session-manager.ts:41,1064-1072`（`appendFileSync`＋`{type:"session",version:3}`）；标签/名行 `:136,:143` | `PiV3Wire.java:38-45`（v3 头）、`RecordJsonCodec`（label/session_info 原生拼写）、`SessionWireShape`（消息原生拼写）、legacy 头惰性迁移 `JsonlSessionStorage.java:295-302`；双向夹具 `PiV3WireFormatTest`/`PiNativeSpelling*Test` | 已闭环（包 12–14） |
| 74 | ~~存储 conformance / benchmark 套件~~ | S | **作废（R1）** | 0 | `harness/session/testing/*` 已删 | agent-core 无 | A17 |
| 75 | session fork | S | **存疑** | **2** | `core/session-manager.ts:1815`（`SessionManager.forkFrom`） | `ForkOptions.java`（参数形状有，调用面未全核） | —— |
| 76 | `session_start` / `resources_discover` | S | **对齐** | **2** | `core/agent-session.ts:509,3695`；resources `:3285`；扩展侧 `core/extensions/runner.ts:1487-1490` | `AgentSession.java:104,453-460,1156` | —— |
| 77 | 会话恢复（resume seed + record replay） | S | **存疑** | **2** | `core/agent-session.ts:525,1809,4138`（`_restoreToolsFromTranscript`） | `RunLifecycle.java:284,330`（seed/restore） | A2 |
| 78 | `latest()` / `find()` 会话定位范围 | S | **存疑** | **3** | `core/session-manager.ts:749`（`findMostRecentSession` 只读一个项目目录） | `PersistentSessionRepositories`（coding-agent）走跨项目全量扫描 | **B53** |
| 79 | 会话列表 `onProgress` | S | **缺失** | **1** | `core/session-manager.ts:943-965` | list 同步全量 | **B55** |
| 80 | 宿主 `prompt()` 的 `streamingBehavior` | L | **存疑** | **2** | `core/agent-session.ts:320,1921,1997-2019`（steer/followUp 两门） | `AgentSession.java:640-651`（门在调用线程抛）；引擎门 `PiLaneEngine.java:87-91` | **A5/F4** |
| 81 | 工具线程安全契约 | A | **存疑** | **1** | `agent/src/agent-loop.ts:508-660`（契约：工具必须可并发） | 无显式声明/审计 | **A10/A11** |
| 82 | `retry.provider.*` ↔ `HTTP RetryPolicy` | A | **存疑** | **2** | `core/settings-manager.ts`（retry 设置） | HTTP 侧重试策略；映射未核 | **A7** |
| 83 | `transcript()` 下游消费者清点 | A | **存疑** | **1** | —— | —— | **A2** |
| 84 | 两道门的行为裁决 | L | **存疑** | **2** | 见 #17/#80 | —— | **F4** |
| 85 | `recordEvent` 无宿主跨度时的兜底 | A | **存疑** | **1** | —— | —— | **F2** |
| 86 | ~~`_emitSessionCompactFailed`~~ | E | **作废（R1）** | 0 | `core/agent-session.ts:1053-1057`：**只**向扩展 runner 发 `session_compact_failed`（`core/extensions/types.ts:796`），无公开会话事件 ⇒ 属 04；失败路公开事件是 `compaction_end{errorMessage}`（见 #51，java 已齐） | 注释同裁决（`PostRunCompactionCheck.java:168`，`CompactionExecutor.java:189`） | 见作废清单 |
| 87 | L5 夹具对 thinking 块不对称 | A | **存疑** | **1** | `conformance/pi/run.test.ts`（在 pi-java 仓） | `ScriptedStreams.java` | **E8** |
| 88 | ~~`ActiveToolsChange` 发射~~ | E | **作废（R1）** | 0 | `git grep active_tools_change ea448f454 -- packages/` **零命中**（旧锚同样零命中）—— pi 主流从不发射 | 不发射；裁决已落 `Entry.java:23-24`（R2：变体删除） | 见作废清单 |
| 89 | 批内 join 源序 vs `Promise.all` | L | **存疑** | **1** | `agent/src/agent-loop.ts:646`（`Promise.all` 收束保源序） | `PiLoopTools.java:144-186`（源序/完成序分家，注释自证，夹具未全钉） | **C2** |
| 90 | `transcript`/`messages`/`partial` 可见性 | S | **存疑** | **1** | —— | 写者唯一 | **C3** |
| 91 | `RunLifecycle.reset` 不 `close()` runSpan | S | **存疑** | **1** | —— | `RunLifecycle.java:250`（置 null，无 close） | **C4** |
| 92 | 遥测 `startSpan`/`openSpan` javadoc | A | **存疑** | **1** | —— | `TelemetryContext.java:16,40,47` | **D2/D3** |
| 93 | `SummaryGenerator.java:9-12` javadoc 过期 | A | **存疑** | **1** | —— | 仍写「`truncating()` 是 Phase 6 前占位」（`:55` 占位仍在），真路 `LlmSummaryGenerator` 已接 | **D6** |
| 94 | 文件超限：`PiLaneSink`/`AgentHarness` 行数 | A | **存疑** | **1** | —— | 实测 **562 / 595 行**（均超 500） | **E3/E4** |
| 95 | 夹具在集成层无牙 | A | **存疑** | **1** | —— | `ShellOutputStreamingTest.java`（仍非字节精确假 shell） | **A14** |
| 96 | 错误路 `AgentSettled` 无夹具 | A | **存疑** | **1** | —— | 载荷形有 `AgentSettledPayloadTest`、错误路有 `SessionFailurePathTest`，**非空 toolResults 断言守护仍缺**；发射点 `SessionRunner.java:196` | **A15** |
| 97 | **扩展开启（extended thinking）目录→请求投送** | C | **对齐** | **2** | `models.json thinkingLevelMap` → `core/provider-composer.ts` → `provider-composer`/`anthropic-messages` | `AgentHarness.java:136-145`；`PiLaneEngine.java:329-330`；`PiLoopRunner.java:173`；`DefaultProviders.java:255-261`（coding-agent） | 已闭环 |
| 98 | **`abortCompaction`** | A | **缺失** | **1** | `core/agent-session.ts:2914-2917`（另 `abortBranchSummary :2921`）；TUI Esc 换绑依赖 | 全仓零命中（压缩只借 lane abortSignal，无 per-operation 句柄） | **B36** |
| 99 | **工具装配变更声明**（`declareToolChanges`） | L | **对齐** | **2** | `agent/src/agent-loop.ts:333-374`（含 `ToolStateChanges`/`NO_CHANGES`，types 侧无独立声明） | `ToolChangeDeclaration.java` | 已闭环 |
| 100 | **系统提示作为 transcript 的 system 消息** | N | **存疑（形状）** | **3** | `packages/ai/src/types.ts:748-751`（`TranscriptContext` **只有 messages**）；`agent/src/agent.ts:85`；`agent/src/types.ts:500-505`（`AgentContext` 只有 messages/tools） | java 走 `Context.systemPrompt` 字段（`harness/Context.java:44`）+ `AgentHarness.java:506`；sections/差分已移植（`SystemPrompts.java`）⇒ 首条等价、会话中变更不等价 | —— |
| 101 | **`prepareNextTurn` 可返回 `messages`** | L | **对齐** | **2** | `agent/src/types.ts:163-169`；`agent-loop.ts:186,211` | `PiLoop.java:210,222-224`（已有 `messages`） | 已闭环 |
| 102 | `Agent.reset()` **保留基线 system 消息** | L | **对齐** | **1** | `agent/src/agent.ts:354-368`（`getCurrentSystemMessage` 保留首条） | `RunLifecycle.java:235-254` 清空 transcript 与工作副本；**系统提示载体是 `Context.systemPrompt` 字段而非首条消息，清空不丢基线、下次请求重新注入 ⇒ 行为一致** | **B70 已取证消解** |
| 103 | ~~`experimental/pico3` kernel~~ | S | **作废（R5）** | 0 | 已随 harness 删除 | 无 | —— |
| 104 | ~~`packages/durable`~~ | S | **作废（R5）** | 0 | 61 文件但 import 者全在 `experimental/` | 无 | —— |

> **不在本模块范围的台账条目**：B14/B17/B18/B19/B21/B23/B24（`pi-java-ai`）、
> B28/B29/B31/B32/B33/B48/B50/B51（RPC 线格式）、B38/B39/B47（TUI）、B52/A13/A16（已结案）、B54（web 路径）。

---

## 作废清单（R1／R5 —— 权重 0，移出分母）

**21 条能力单元**（旧编号保留，便于对照）：#30 `before_run` · #31 `before_drive` · #35 `before_payload` ·
#36 `after_response` · #39 `before_compaction` · #40 `before_navigation` · #42 `before_resume` ·
#43 effect gate · #44 HarnessEvent · #45 `run_suspend` · #59 `LaneConfiguration` · #66 `AdaptivePublisher` ·
#67 `output-capture`/`shell-output` · #70 `SessionSearchService` · #71 `reduceLaneSnapshot` · #72 `TaggedError` ·
#74 存储 conformance 套件 · **#86 `_emitSessionCompactFailed`（本锚新作废：仅扩展事件，归 04）** ·
**#88 `ActiveToolsChange`（本锚新作废：pi 两锚全树零发射）** · #103 pico3 · #104 durable。

**一条整块缺失**：① drive 运行时（`harness/runtime/drive/` 12 文件）—— 参照物被删。

> `before_payload`/`after_response`/`before_compaction`/`session_compact_failed` 的**替身是扩展事件**
> （`before_provider_request`/`after_provider_response`/`session_before_compact`/`session_compact_failed`），
> 属 `04-coding-agent` 的域。

---

## 整块缺失

**本轮无独立计权项** —— 旧表 11 条中：① drive（作废）、③ effect gate（作废）、④ 存储套件（作废）、
⑥ `AdaptivePublisher`（作废）、⑦ values.ts（作废）、⑩ pico3（作废）、⑪ durable（作废）；
② branch summary+导航、⑤ proxy、⑧ skills、⑨ sanitize 仍作**能力单元**（#24/#25、#68、#61/#62、#65）逐条计权。

---

## 汇总（未加权）

| 表 | 对齐 | 缺失 | 存疑 | 合计 | 完成度 |
|---|---:|---:|---:|---:|---:|
| 内置工具 | 4 | 1 | 4 | 9 | **44.4%** |
| 能力单元（除工具） | 38 | 11 | 34 | 83 | **45.8%** |
| 整块缺失（无独立计权项） | 0 | 0 | 0 | 0 | —— |
| **合计** | **42** | **12** | **38** | **92** | **45.7%** |

**未加权完成度 = 42 / 92 = 45.7%**。
（对齐＋存疑视为「有」：80/92 = **87.0%**）

---

## 加权汇总

| 域 | Σ权重 | Σ(w×系数) | 加权完成度 | 未加权完成度 |
|---|---:|---:|---:|---:|
| 内置工具（9） | 21 | 15.0 | **71.4%** | 44.4% |
| 能力单元（除工具，83） | 162 | 117.0 | **72.2%** | 45.8% |
| **模块合计（92）** | **183** | **132.0** | **72.1%** | **45.7%** |

**模块合计**：Σ权重 183 ／ Σ(w×c) 132.0 ／ **加权完成度 = 132.0/183 = 72.1%**（未加权 45.7%）。

按权重分层（能力单元表，83 条）：

| 权重 | 条数 | Σ权重 | 对齐 | 存疑 | 缺失 | Σ(w×c) | 层完成度 |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 3 | 22 | 66 | 13 | 9 | 0 | 52.5 | **79.5%** |
| 2 | 35 | 70 | 21 | 12 | 2 | 54.0 | **77.1%** |
| 1 | 26 | 26 | 4 | 13 | 9 | 10.5 | **40.4%** |
| 合计 | 83 | 162 | 38 | 34 | 11 | 117.0 | 72.2% |

### 权重 3 的单元清单（22 条能力单元 + 5 条工具 = 27 条）

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
| #26 per-model 压缩设置 | 对齐 |
| #41 `should_stop`/`prepare_next_turn` 钩子 | **存疑（形状）** |
| #46 `AgentSessionEvent` 面 | **存疑** |
| #51 `compaction_start`/`compaction_end` 事件 | 对齐 |
| #52 `agent_end` 载荷 | **存疑** |
| #54 Entry union | **存疑（形状）** |
| #58 `convertToLlm` | **存疑** |
| #73 JSONL 存储（v3 平铺） | 对齐 |
| #78 `latest()`/`find()` 会话定位范围 | **存疑** |
| #100 系统提示作为 system 消息 | **存疑（形状）** |

**权重 3 层 = 13 对齐 / 9 存疑 / 0 缺失**。

### 未加权 vs 加权的差（45.7% → 72.1%，**+26.4 pp**）

差来自分母内的权重分配。模块未得分（Σw−Σ(w×c) = 51.0）分布：

| 池 | 未得分 | 占比 |
|---|---:|---:|
| 能力单元 权重 3 | **13.5** | 26% |
| 能力单元 权重 2 | **16.0** | 31% |
| 能力单元 权重 1 | **15.5** | 31% |
| 内置工具 | 6.0 | 12% |
| 合计 | 51.0 | 100% |

⇒ **权重 2 层与权重 1 层并列最大未得分池**。**缺失的权重分布**：11 条缺失里 **0 条权重 3**、2 条权重 2（#17/#62）、9 条权重 1。

### 三条点名项的边际影响（分母 183）

| 条目 | 权重 | 补齐后 | 边际 |
|---|---:|---:|---:|
| **A4 `/compact` 缺 abort-first**（#17，缺失→对齐） | 2 | 133.0/183 = 72.68% | **+0.55 pp** |
| **#23 截断字节预算 →50KB**（B158，存疑→对齐） | 3 | 133.5/183 = 72.95% | **+0.82 pp** |
| **#6 `finishTurn` 形状对齐**（B159，存疑→对齐；含 #41 再加 1.5） | 3 | 133.5/183 = 72.95% | **+0.82 pp**（含 #41 共 **+1.64 pp**） |

---

## 本锚判定翻转（相对旧图）

| # | 旧判定 | 新判定 | 依据 |
|---|---|---|---|
| **#73** JSONL 存储 | 存疑（形状，v4 vs v3） | **对齐** | pi-java 落 pi v3 原生线：`PiV3Wire` 头、label/session_info/usage/context_edit 原生条目、消息原生拼写、方言行挂 custom 并链接入树、legacy 头惰性迁移；`PiV3WireFormatTest`/`PiNativeSpelling*Test` 双向夹具（2026-10-04/05，包 12–14） |
| **#102** reset 基线 system | 存疑 | **对齐** | **B70 已取证消解**：载体不同（pi 首条消息 vs java `Context.systemPrompt` 字段）、行为一致（清空工作副本不丢基线，下次请求重注入） |
| **#86** `_emitSessionCompactFailed` | 存疑（F3） | **作废（R1）** | pi 该方法**只**发扩展事件 `session_compact_failed`（归 04）；公开失败事件是 java 已齐的 `compaction_end{errorMessage}`；java 源码注释同裁决 |
| **#88** `ActiveToolsChange` | 存疑（C1） | **作废（R1）** | `active_tools_change` 在 pi 两锚全树零命中；java 早已据 R2 删变体（`Entry.java:23-24`） |
| #54 计数 | 11 vs **7** | 11 vs **9**（仍存疑） | java 内存 union 实测 9 record（新增 CustomMessage/ContextEdit）；label/session_info 改以 fact 存活、线格式原生读写 |
| #46 计数 | 24 vs 20 | **24 vs 20**（维持） | 两侧重数一致；pi 本锚给 `agent_settled` 加 `aborted` 字段（见下「新缺口」） |

### 新缺口（只报告，本图不登记编号）

1. **工具耗时 `durationMs`**：pi 本锚给 `tool_execution_end` 事件与 ToolResult 消息都加了单调时钟 `durationMs?`（`agent-loop.ts:825-945`、`types.ts:453-455,531-538`，会话层透传 `core/agent-session.ts:1355-1358`）。java `ToolResult.java:27-35` 与 `AgentSessionEvent.ToolExecutionEnd` 均无该字段。
2. **`agent_settled.aborted`**：pi `core/agent-session.ts:203,1083-1086` 现在随事件带中止标志；java `AgentSettled(message, toolResults)`（`AgentSessionEvent.java:46-63`）无。
3. **`--tools` 模式匹配与 MCP 保留**：pi allow/deny 名单改 glob 模式匹配（`createToolNameMatcher`）、新增 `defaultToolModifiers`（`+name/-name`）、空名单/`mcp__*` 名单下 MCP 工具仍注册（`core/agent-session.ts:271-289,501-510,1533-1557,3662-3690`）；java 侧为精确名集合，未核。
4. `streamProxy` 返型改为 pi-ai 工厂 `createAssistantMessageEventStream()`（`agent/src/proxy.ts:107-111`）—— #68 缺失判定不变，对齐时照新形状。

---

## 口径备注（可被推翻）

- **钩子作废只作废「harness 专有」的**：凡行为在 `agent/src/*` 存活（`beforeToolCall`/`afterToolCall`/
  `transformContext`/`prepareNextTurn`/`finishTurn`/`getFollowUpMessages`）的一律改指保留；
  harness 有、主流只在**扩展事件**里重生的作废并注记去向 04。
- **`streamProxy`（#68）/`setDefaultStreamFn`（#69）未作废**：二者在 `agent/src/proxy.ts`、`agent/src/stream-fn.ts`
  **逐字存活**（#68 仅返型工厂换源），pi-java 没有 ⇒ **仍判缺失**。
- **#62 的 pi 参照已改指**：harness 旧函数 `formatSkillInvocation` 已删；主流等价行为是
  `agent-session.ts:2149-2162` 的 `_expandSkillCommand`（`/skill:` 展开），pi-java 无。
- **#73 的对齐强度**：双向可读由合成夹具（`PiV3WireFormatTest` 等）保证，未拿真实 pi 进程跑往返；
  lane/record 方言行以 pi `custom` 条目挂载并链入树。
