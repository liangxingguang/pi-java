# 03 Agent 运行时核心（pi-java-agent-core）

> 基准：pi `D:\workplaceForai\pi` @ **`3390bd936`**（2026-09-20，前一次测于 `71dca871b`，**111 个提交**）；
> pi-java `D:\workplaceForai\pi-java` @ `34849a2`（未动）。
> 台账 = `docs/32-open-items-register.md`。所有 `file:line` 均为**新 HEAD 实读**。
>
> ⚠️ **测后披露**：测量期间 pi 的工作树被外部 checkout 回 `71dca871b`（`git reflog`: `checkout: moving from main to 71dca871b`）。
> 新 HEAD 的数据与行号全部经 `git show 3390bd936:<path>` 读取，**未改动工作树**。
>
> **权重口径**（用户 2026-09-20 定）：权重 = 用户可观察影响 × 频率，**不掺排期优先级**。
> `3` = 每轮对话都走 / 默认路径 · `2` = 每次会话走 / 常用命令 / 常用配置键 · `1` = 低频 / 边缘 / 纯内部 · `0` = 非目标（排除出分母）。
> **完成系数**：对齐 `1.0` ／ 存疑 `0.5` ／ 缺失 `0`。
> 三条局部口径（可被推翻）：① **钩子**（#30–#42）按「会话开始与结束 / 常用配置键」归 **2**，压缩/导航/resume 三个非默认路的归 **1**；
> ② **每轮都会跑但只在特定条件下才产生可观察差**的行为归 **2**；③ **测试基建**归 **1**；
> ④ **新增**：`experimental/` 子路径下的子系统（pico3）与只导出记录契约的新包（durable）归 **1**（不是默认路径）。

---

## 规模

| 模块 | pi 包 | pi LOC（旧 → 新） | java LOC | 比例 |
|---|---|---:|---:|---:|
| 主源码 | `packages/agent/src` | 25,305 → **33,353**（**+31.8%**） | `pi-java-agent-core/src/main` **16,386**（176 文件） | 64.8% → **49.1%** |
| 测试 | `packages/agent/test` | 21,238 → **28,570**（+34.5%） | `src/test` **14,219**（91 文件） | 67.0% → **49.8%** |
| **新包** | `packages/durable`（src） | 0 → **757**（13 文件） | **无对应物** | 0% |
| （同） | `packages/durable`（test） | 0 → 636 | —— | —— |

⚠️ 口径问题（与上版同）：pi 的 `src/` 里混着 **2716 行测试支持**（`harness/session/testing/`）；
扣掉后 pi 主源码 = 30,637 ⇒ java/pi = **53.5%**。

### 子目录对照（`packages/agent/src`，旧 → 新）

| pi 子目录 | 旧 | 新 | Δ | java 对应 | 备注 |
|---|---:|---:|---:|---|---|
| **`harness/pico3/`** | **0** | **7,994** | **+7,994** | **无** | **新增**，见下节 |
| `harness/runtime/`（含 `drive/` 12 文件） | 7,505 | 7,473 | −32 | 无 | 只删了 `addedToolNames` 相关，见「实质变化」 |
| `harness/session/`（含 testing 2,716） | 7,108 | 7,108 | 0 | `session/` 4,194 | 逐文件行数全等 |
| `harness/tools/` | 1,209 | 1,209 | 0 | `tool/`（含 builtin）3,265 | 全等 |
| `harness/compaction/` | 1,297 | 1,297 | 0 | `compaction/`+`context/` 1,072 | 全等 |
| `harness/execution/` | 449 | 444 | −5 | （并入 harness） | `tools.ts` 删 `addedToolNames` |
| `harness/utils/` | 822 | 822 | 0 | （并入 tool/） | 全等 |
| `harness/env/` | 924 | 924 | 0 | —— | 全等 |
| 顶层（`agent/src/*.ts` + `harness/*.ts`） | 5,991 | 6,082 | +91 | `PiLoop`/`PiLoopRunner`/`PiLaneEngine` 等 | `agent-loop`+98 / `agent`+51 / `types`+47 / `proxy`+8 |
| 合计 | 25,305 | 33,353 | +8,048 | 16,386 | —— |

---

## `packages/durable` —— 新包（Pico v5 记录契约 + 脱离式内存存储）

**是什么**：`@earendil-works/pi-durable`，由提交 `080160162 feat(durable): move Pico into dedicated package` 从 agent 包**迁出**的 Pico v5 运行时。
自述（`packages/durable/README.md`）：*"Durable conversation, task, and document runtime for Pi… Its current public API provides the durable record contracts and detached in-memory storage implementation"*。
公开 API 只有两样：`MemoryStorage`、`ROOT_CONVERSATION_ID`，其余全是类型（`src/index.ts:1-23`）。

| # | 能力单元 | 位置 | 说明 |
|---|---|---|---|
| D1 | **四张表模型** | `src/types.ts` | `ConversationRecord` / `EntryRecord` / `TaskRecord` / `Input`（会话、条目、任务、输入） |
| D2 | **三份文档** | `src/types.ts` | session 文档 + per-conversation rewindable + per-conversation sticky（`RewindableState` / `StickyState` 经 agent 侧 `pico3/types.ts` 暴露） |
| D3 | `ConversationRecord.parent`（fork 源 + 含入父条目） | `src/types.ts:20-27` | 历史继承语义 |
| D4 | `ConversationRecord.owner`（创建者边） | `src/types.ts:29-33` | 用于**授权、子树中止、子树空闲等待** |
| D5 | `ContextEdit`（`omit` / `replace`） | `src/types.ts:36-51` | 对某条可见条目的模型上下文贡献做**不可变覆写** |
| D6 | `EntryRecord`（`kind` / `model` / `data` / `head` / `edits` / `byTaskId`） | `src/types.ts:54-70` | 模型面与应用面载荷**分离** |
| D7 | `EntryDraft.head: Id \| "self"` | `src/types.ts:72-75` | `"self"` = 活动上下文从新条目自身开始 |
| D8 | `Input` 生命周期 + `requestId` 去重键 | `src/types.ts:78+` | 宿主提供的会话内去重键 |
| D9 | `TaskRecord` / `TaskState` / `TaskOutcome` / `StoredError` | `src/types.ts` | 任务状态机 + JSON 安全错误快照 |
| D10 | `Cursor` / `Page` / `EntryQuery` / `TaskQuery` / `StorageWrite` / `Storage` | `src/types.ts` | 分页查询与写事务契约 |
| D11 | **`MemoryStorage`**（脱离式内存实现） | `src/memory-storage.ts:1-372` | 四张表的 Map 索引 + 按状态分桶 + 写时克隆；`memory-storage.test.ts`（636 行测试） |
| D12 | `ROOT_CONVERSATION_ID = 1` | `src/types.ts:11` | 根会话保留 ID |

**pi-java 有无对应物**：**无**。`pi-java` 全仓（`grep -rln "Pico\|Durable"`）零命中；无 `Scheduler`/`TaskRecord`/`ConversationRecord` 概念；
11 个 Maven 模块里没有对应包（`pom.xml:17-29`）。
最近似的现有物是 `pi-java-agent-core/.../session/`（JSONL v4 平铺 entry + `Session`/`SessionRepository`），
但那是 **pi `harness/session`（v4）** 的对应物，不是 durable（v5）。⇒ **判定：缺失，权重 1**（新包、脱离式实现、pi 生产未接线）。

> 注：`packages/durable` 在 pi 侧**也尚未被任何包依赖**（`grep -rn "pi-durable" packages/*/package.json` 只命中它自己）⇒ 与 pico3 同属**未接线**状态。

---

## `packages/agent/src/harness/pico3` —— 新子系统（experimental Pico3 kernel，7,994 行 / 24 文件）

**是什么**：自述 `harness-v3 — records, documents, storage, kinds, transactions, runtime, tools, hooks`（`pico3/types.ts:1-8`）。
导出路径 `@earendil-works/pi-agent-core/experimental/pico3`（`packages/agent/package.json:21-24`），
注释明写 *"This subpath is intentionally separate from the package root while the kernel and its Chord integration are being validated"*（`pico3/index.ts:1-5`）。

| # | 能力单元 | 位置 | LOC |
|---|---|---|---:|
| P1 | **`Harness`**（kind 注册 + `applyEnvelope` + `captureActiveTranscript` + `WATCH_CAPACITY`） | `pico3/harness.ts` | 812 |
| P2 | **`Session`**（事务 `TxImpl`、checkpoint、`Closed`/`CollapseInProgress`/`ConversationBusy`） | `pico3/session.ts` | 1,497 |
| P3 | **`Scheduler`**（任务调度 + `InvocationToken`/`Invoker`） | `pico3/scheduler.ts` | 486 |
| P4 | **kind 系统**（`tool` / `generation` / `job` / `plugin` / `post-tools` / `collapse` / `entries` / `frames` / `task-api`） | `pico3/kinds/*.ts` | 2,275 |
| P5 | **`System` 段注册**（`defineSystemSection` / `systemSections` / `effectiveTools`） | `pico3/system.ts` | 376 |
| P6 | **`ViewManager` / `Watch`**（对话视图订阅、`applyImmutable` 增量） | `pico3/view.ts` | 477 |
| P7 | **存储**：`JsonlStorage` + `MemoryStorage` | `pico3/jsonl.ts` / `pico3/memory.ts` | 617 |
| P8 | **`Membrane`**（事务作用域的可撤销文档代理，`revoke()` 后任何保留包装器抛） | `pico3/membrane.ts` | 123 |
| P9 | **`Bounded`**（字节/换行双预算的头尾收集器，逐字节记账） | `pico3/bounded.ts` | 100 |
| P10 | **`bashTool`**（pico 版工具声明，`output` 预算由内核给） | `pico3/bash.ts` | 29 |
| P11 | **Chord 集成**（`attachChordView` / `createPicoConversationService` / `PicoConversationService` / `PicoHarnessService`） | `pico3/chord.ts` | 178 |
| P12 | 类型面（`types.ts`，1,038 行：`Entry`/`Kind`/`Task`/`Checkpoint`/`Outcome`/`Envelope`/`HostTx`/…） | `pico3/types.ts` | 1,038 |
| P13 | 上下文/钩子（`context.ts` / `hooks.ts` / `index.ts`） | —— | 305 |
| P14 | **测试** | `test/harness/pico3/*`（7,135 行，13 个文件） | —— |

**pi-java 有无对应物**：**无**。⇒ **判定：缺失，权重 1**。
**未接线证据**：`grep -rn "pico3" packages/` 的命中**全部**在 `packages/agent/src/harness/pico3/` 自身与 `packages/agent/test/harness/pico3/` 里；
`coding-agent` / `client` / `server` / `tui` 零引用 ⇒ **pi 生产不走它**。

---

## 内置工具对齐

pi 的工具定义分两处：`packages/agent/src/harness/tools/`（5 个，harness 面）与
`packages/coding-agent/src/core/tools/`（8 个，产品面）。`allToolNames`（8 个）在
`packages/coding-agent/src/core/tools/index.ts:96`（旧 `:201`）。pi-java 的 7 个工具全在
`pi-java-agent-core/.../tool/builtin/`，由 `ToolSetFactory.java:26-35` 组装。

| # | 工具 | pi 有 | java 有 | 判定 | 权重 | 证据（**新 HEAD 行号**） |
|---|---|---|---|---|---|---|
| 1 | `bash` | ✅ | ✅ | **存疑** | **3** | pi 参数 `{command, timeout?}`，**无默认超时**，上限 `2147483 s`（`harness/tools/bash.ts:8,13`；`coding-agent/core/tools/bash.ts:22`）。java 同参数，但**默认 120 s / 上限 600 s**（`BashTool.java:35,37,104-107`）⇒ 模型不传 timeout 时行为不同。描述文案也不同 |
| 2 | `read` | ✅ | ✅ | **对齐** | **3** | pi `{path, offset?, limit?}`（`core/tools/read.ts:14-17`）；java 同（`ReadTool.java:37-47`）。图片按 mime 分派成 `ImageContent`（`ReadTool.java:85-95` vs pi `core/tools/read.ts:73`） |
| 3 | `write` | ✅ | ✅ | **对齐** | **3** | pi `{path, content}`（`core/tools/write.ts:11-13`）；java 同（`WriteTool.java:39-45`）＋ 同形状 `FileMutationQueue`（`WriteTool.java:28`） |
| 4 | `edit` | ✅ | ✅ | **对齐** | **3** | pi `{path, edits:[{oldText,newText}]}`（`core/tools/edit.ts:32-36`）；java 同（`EditTool.java:52-63`） |
| 5 | `grep` | ✅ | ✅ | **存疑** | **3** | pi `{pattern, path?, glob?, ignoreCase?, literal?, context?, limit?}` + gitignore + limit100/100KB 截断（`core/tools/grep.ts:21-32,78`）。java 只有 `{pattern, path?, glob?}`（`GrepTool.java:44-52`）⇒ **缺 4 参数** |
| 6 | `find` / `glob` | ✅ `find` | ✅ `glob` | **存疑** | **2** | **名字不同**；pi `{pattern, path?, limit?}` 默认 1000，走 fd/ripgrep 尊重 gitignore（`core/tools/find.ts:26-31,47`）。java `{pattern, path?}`，NIO `PathMatcher` 全树 walk（`GlobTool.java:32,44-52,72-78`）⇒ 缺 limit + 无 gitignore + 无截断 |
| 7 | `ls` | ✅ | ✅ | **存疑** | **2** | pi `{path?, limit?}`（`core/tools/ls.ts:11-13,62`）；java `{path?, recursive?}`（`LsTool.java:36-42`）⇒ **参数集不同** |
| 8 | `powershell` | ✅ | ❌ | **缺失** | **1** | pi `core/tools/powershell.ts:59`，工具名进 `allToolNames`（`core/tools/index.ts:96`）。java 全仓零命中 |
| 9 | `image`（读图处理器） | ✅ | ✅ | **对齐** | **1** | pi `harness/tools/image.ts:3-19`；java `PathUtils.detectImageMimeType`/`encodeBase64`（`ReadTool.java:85-88`） |

**Σ权重 21**（与上版同）。**pi 侧 9 条工具引用全部只漂行号，判定无变化。**

---

## 能力单元清单（除工具外）

类别：**L**=agent loop · **H**=钩子 · **E**=事件 · **N**=entry/消息 · **P**=prompt/模板 · **C**=上下文管理 · **S**=存储/会话 · **A**=API 面

| # | 能力单元 | 类别 | 判定 | 权重 | pi 侧证据（**新 HEAD**） | pi-java 侧证据 | 台账 |
|---|---|---|---|---|---|---|---|
| 1 | 10 个 `AgentEvent` 变体 | E | **对齐** | **3** | `types.ts:448-463`（旧 431-446） | `PiLoop.java:41-69`（L5 14 剧本） | —— |
| 2 | 双循环（内层工具+steering / 外层 follow-up） | L | **对齐** | **3** | `agent-loop.ts:179-278`（旧 171-272） | `PiLoopRunner.java:39-145` | —— |
| 3 | `runAgentLoop`/`runAgentLoopContinue` 前置条件 | L | **对齐** | **3** | `agent-loop.ts:135-139`（旧 71-77） | `PiLoop.java:288-300`；`PiLaneEngine.java:137,150` | —— |
| 4 | 工具批次 `terminate` 取**全部**（`every`） | L | **对齐** | **2** | `agent-loop.ts:644`（旧 590） | `PiLoop.java:124`；`PiLoopTools.java:51-52` | —— |
| 5 | `length` 截断 ⇒ 本回合**全部**工具调用判失败 | L | **对齐** | **2** | `agent-loop.ts:231-241`（旧 206-208） | `PiLoopRunner.java:106-110` | —— |
| 6 | `shouldStopAfterTurn` | L | **对齐** | **3** | `agent-loop.ts:258`；`types.ts:230`（旧 252/223） | `PiLoop.java:211-215` | —— |
| 7 | `prepareNextTurn` + `AgentLoopTurnUpdate`（context 整体替换） | L | **对齐** | **3** | `agent-loop.ts:184-190`；`types.ts:143-150`（旧 177-183/138-145） | `PiLoop.java:194-208`；`PiLoopRunner.java:59-63` | A3 ⇒ 存疑 |
| 8 | `transformContext` | L | **对齐** | **3** | `agent-harness.ts:443`（未漂） | `PiLoop.java:219-223` | —— |
| 9 | 工具两相端口（immediate / prepared）与 end 次序 | L | **对齐** | **3** | `agent-loop.ts:552-602`（旧 506-545） | `PiLoop.java:132-142`；`PiLoopTools.java:90-151` | —— |
| 10 | 并行/顺序批次选择（Sequential ⇒ 整批降级） | L | **对齐** | **3** | `agent-loop.ts:476-486`（旧 431-485） | `PiLoopTools.java:63-88` | —— |
| 11 | 重试环 A（post-run assistant 重试） | L | **对齐** | **2** | `agent-session.ts:770,1239,2990`（旧 1123） | `PostRunRetry.java` | —— |
| 12 | 重试环 B（摘要重试） | L | **对齐** | **1** | `agent-session.ts:3008-3022`（旧 2894-2908） | `LlmSummaryGenerator.java` + `PostRunRetry.java` | —— |
| 13 | 队列：steer / followUp / nextRun | L | **对齐** | **2** | `types.ts:252,265`（旧 245,258） | `QueueManager.java:117-159` | —— |
| 14 | `QueueMode.All` 的宿主层语义 | L | **存疑** | **2** | `types.ts:55`（旧 50） | `QueueMode.java:14-23`；`QueueManager.java:149-155` | **F1** |
| 15 | 溢出恢复（compact-and-retry 一次） | L | **对齐** | **2** | `agent-session.ts:2312-2352`（旧 2180-2210） | `PostRunCompactionCheck.java` | —— |
| 16 | 轮内阈值压缩门 | L | **对齐** | **3** | `agent-session.ts:565`（旧 548） | `ContextUsageEstimator.java` | —— |
| 17 | **手动 `/compact` 先 `abort()` 当前运行** | L | **缺失** | **2** | `agent-session.ts:2089-2090`（旧 1967-1968） | `CompactionExecutor.java:77-83` 与 `AgentSession.java:641` **均无 abort** | **A4/F4** |
| 18 | 压缩后重建工作副本（`NextTurnUpdate.context`） | L | **对齐** | **3** | `agent-session.ts:588`（旧 557-577） | `PiLoop.java:203-206` | —— |
| 19 | 摘要 prompt 前缀/后缀逐字节 | C | **对齐** | **2** | `messages.ts:4-17`（未漂） | `ContextEntries.java:32-37` | —— |
| 20 | 上下文计量（用量优先 + 尾字符估算） | C | **对齐** | **3** | `compaction.ts:215-245`（未漂） | `ContextUsageEstimator.java:60-122` | —— |
| 21 | `getLastAssistantUsage` | C | **对齐** | **2** | `compaction.ts:183-193`（未漂） | `ContextUsageEstimator.java:86-90` | —— |
| 22 | `findCutPoint` / `findTurnStartIndex` | C | **对齐** | **2** | `compaction.ts:343,370`（未漂） | `CompactionService.java` | —— |
| 23 | 输出截断（2000 行 / 100KB，头/尾两种） | C | **对齐** | **3** | `utils/truncate.ts:1-350`（未漂） | `TruncationUtils.java` | —— |
| 24 | **branch summary 生成** | C | **缺失** | **1** | `branch-summarization.ts:219,242`（未漂，300 行） | 只有 `Entry.BranchSummary` 类型 + 文案；**无生成器** | **B1** |
| 25 | **会话树导航 `navigateTree`** | L | **缺失** | **1** | `agent-session.ts:3255`（旧 3136） | 无导航操作 | B1 同族 |
| 26 | **per-model 压缩设置** | C | **缺失** | **2** | `settings-manager.ts:896-906`（旧 891-901） | `HarnessConfig.java:155` 无模型参数 | **A9** |
| 27 | **branch summary 设置**（`reserveTokens`/`skipPrompt`） | C | **缺失** | **1** | `settings-manager.ts:908-916`（旧 903-911） | 无 | B1 同族 |
| 28 | bash 流式 `tool_execution_update` | L | **对齐** | **2** | `core/tools/bash.ts:265,297`（未漂） | `BashTool.java:113-114` + `BashUpdateEmitter.java` | **B46（已修）** |
| 29 | bash 全量输出落盘 `fullOutputPath` | L | **对齐** | **1** | `harness/tools/bash.ts:18-20`（未漂） | `BashTool.java:46` | —— |
| 30 | 钩子 `before_run` | H | **对齐** | **2** | `agent-harness.ts:431`（旧 432） | `HookSystem.java:43` | —— |
| 31 | **钩子 `before_drive`** | H | **缺失** | **2** | `agent-harness.ts:435`（未漂）；`runtime/drive.ts:37` | 全仓零命中 | —— |
| 32 | 钩子 `before_run_end`（`followUp`） | H | **对齐** | **2** | `agent-harness.ts:439-442`（未漂） | `HookSystem.java:93` | —— |
| 33 | 钩子 `transform_context` | H | **对齐** | **2** | `agent-harness.ts:443`（未漂） | `HookSystem.java:53` | —— |
| 34 | 钩子 `before_request` | H | **对齐** | **2** | `agent-harness.ts:447`（旧 445） | `HookSystem.java:58` | —— |
| 35 | 钩子 `before_payload` | H | **对齐** | **2** | `agent-harness.ts:456`（旧 454） | `HookSystem.java:63` | —— |
| 36 | 钩子 `after_response` | H | **对齐** | **2** | `agent-harness.ts:460`（旧 458） | `HookSystem.java:68` | —— |
| 37 | 钩子 `before_tool` | H | **对齐** | **2** | `agent-harness.ts:464`（旧 462） | `HookSystem.java:73` | —— |
| 38 | 钩子 `after_tool` | H | **对齐** | **2** | `agent-harness.ts:468`（旧 465） | `HookSystem.java:78` | —— |
| 39 | 钩子 `before_compaction` | H | **对齐** | **1** | `agent-harness.ts:488`（旧 481） | `HookSystem.java:83` | —— |
| 40 | 钩子 `before_navigation` | H | **存疑** | **1** | `agent-harness.ts:496`（旧 489） | `HookSystem.java:88` | 无导航操作（#25） |
| 41 | 钩子 `should_stop_after_turn`/`prepare_next_turn` | H | **对齐** | **3** | `agent.ts:123-129`（旧 108-112） | `HookSystem.java:98,103` | —— |
| 42 | 钩子 `before_resume`（java 独有） | H | **存疑** | **1** | —— | `HookSystem.java:48` | java 比 pi 多 |
| 43 | **effect gate**（`gate.admit`） | A | **缺失** | **2** | `execution/effect-gate.ts:1-64`（未漂） | 全仓零命中 | —— |
| 44 | HarnessEvent 28 类（含 `run_suspend`/`lane_created`/`usage`/`value_update`/`config_update`） | E | **存疑** | **3** | `agent-harness.ts:255-373`（未漂；`run_suspend` `:258`） | `HarnessEventBus.java`（快照广播） | A6 |
| 45 | **`run_suspend` + `DeferredHandle` 挂起驱动** | L | **缺失** | **1** | `runtime/drive/deferred.ts`（未漂）；`agent-harness.ts:258` | 只有类型 + 解码，零生产者 | §9.3 |
| 46 | `AgentSessionEvent` 面（pi 22 变体） | E | **存疑** | **3** | `agent-session.ts:145-194`（旧 145-185） | `AgentSessionEvent.java:22-131`（19 变体） | B30/B34/B35 |
| 47 | **`queue_update` 生产者** | E | **缺失** | **2** | `agent-session.ts:631`（旧 594） | 只有定义+映射，零 emit | **B34** |
| 48 | **`session_info_changed` 生产者** | E | **缺失** | **1** | `agent-session.ts:3235`（旧 3116） | 同上 | **B30** |
| 49 | **`thinking_level_changed` 生产者** | E | **缺失** | **2** | `agent-session.ts:1952`（旧 1830） | 同上 | **B35** |
| 50 | `bash_execution_update` 生产者 | E | **对齐** | **2** | `agent-session.ts:3146`（旧 3027） | `AgentSessionEvent.BashExecutionUpdate` | —— |
| 51 | `compaction_start`/`compaction_end` 事件 | E | **对齐** | **3** | `agent-session.ts:2092`（手动）/`:2421`（自动）/`:2214`（end） | `CompactionObserver.java` | —— |
| 52 | `agent_end` 载荷 = **本 pass** `newMessages` | E | **存疑** | **3** | `agent-loop.ts:223,259,278`；`agent-session.ts:704`（旧 217,253,272/770） | 引擎对（`PiLoopRunner.java:98,127,144`）；**会话层** `SessionRunner.java:260-261` 发 `accumulatedMessages()` | **A8** |
| 53 | `turn_end` 颗粒度 | E | **存疑** | **2** | `agent-loop.ts:249`（旧 231） | `AgentSessionEvent.AgentSettled` | **B49** |
| 54 | Entry union | N | **存疑** | **3** | **4 类**（`session/types.ts:16,64`，未漂） | **8 类**（`Entry.java:24-32`） | java 是 pi **v3** 形状（`jsonl/legacy-v3.ts:39,66,72,77`，未漂） |
| 55 | `CompactionEntry.fromHook` / `BranchSummaryEntry.fromHook` | N | **缺失** | **1** | `session/types.ts:40,49`（旧 37,49） | 无该字段 | —— |
| 56 | `EntryProjector` | N | **存疑** | **1** | `session/types.ts:59-62`（未漂） | `ContextEntries.java:175-176` | A1 |
| 57 | 四个消息类型 | N | **存疑** | **2** | `messages.ts:19-62`（未漂） | `ContextEntries.java` + `CustomMessageContent.java` | A1 |
| 58 | `convertToLlm` | N | **存疑** | **3** | `messages.ts:124`（未漂）—— **新增 `case "system"`** | `ContextEntries.toMessages` | **A1** |
| 59 | `LaneConfiguration` | N | **存疑** | **2** | `session/types.ts:69-73`（未漂） | entry 化 | #54 同根 |
| 60 | `PendingEntry` / `InboxItem` | N | **存疑** | **2** | `session/types.ts:129,350`（未漂） | `QueueManager.java` | —— |
| 61 | **`formatSkillsForSystemPrompt`** | P | **缺失** | **2** | `system-prompt.ts:3-32`（未漂） | 无（`SystemPromptBuilder` 形状不同） | —— |
| 62 | **`formatSkillInvocation`** | P | **缺失** | **2** | `skills.ts:39`（未漂） | 无 | —— |
| 63 | `loadSkills` / `loadSourcedSkills` | P | **存疑** | **2** | `skills.ts:51,86`（未漂） | `SkillDiscovery`（在 coding-agent） | —— |
| 64 | `loadPromptTemplates` 等四函数 | P | **存疑** | **2** | `prompt-templates.ts:31,226,252,268`（未漂） | `PromptTemplates`（在 coding-agent） | —— |
| 65 | **`sanitizeSurrogates`** | A | **缺失** | **1** | pi 54 处 | 全仓零命中 | **B16** |
| 66 | **`AdaptivePublisher`** | A | **缺失** | **1** | `utils/adaptive-publisher.ts`（87 行，未漂） | 无 | —— |
| 67 | `applyShellOutputUpdate` / `ShellOutputView` | A | **存疑** | **2** | `utils/output-capture.ts`（238）/`shell-output.ts`（112）（未漂） | `ShellOutputSink.java` + `BashUpdateEmitter.java` | —— |
| 68 | **`streamProxy`** | A | **缺失** | **1** | `proxy.ts:120`（未漂；文件 402→406 行） | 无 | —— |
| 69 | **`setDefaultStreamFn`** | A | **缺失** | **1** | `stream-fn.ts:11`（旧 20） | 无 | —— |
| 70 | `SessionSearchService`（6 方法） | A | **存疑** | **1** | `search/index.ts:20-28`（未漂） | `SessionSearch.java`（2 方法） | —— |
| 71 | `reduceLaneSnapshot` | A | **存疑** | **1** | `runtime/reducer.ts:22`（未漂） | 无 | —— |
| 72 | 错误面 15 个 `TaggedError` | A | **存疑** | **1** | `result.ts:53-100`（未漂） | 4 个异常类 | —— |
| 73 | JSONL v4 存储（事务 + `values.ts`） | S | **存疑** | **3** | `session/jsonl/storage.ts:1-267`；`session/values.ts`（195）（未漂） | `JsonlSessionStorage.java`（平铺） | —— |
| 74 | 存储 conformance / benchmark 套件 | S | **缺失** | **1** | `session/testing/*`（**2716**，未漂） | agent-core 无 | A17 |
| 75 | session fork | S | **存疑** | **2** | `session/fork.ts`（119）/`jsonl/fork.ts`（328）/`fork-policy.ts`（67）（未漂） | `ForkOptions.java` | —— |
| 76 | `session_start` / `resources_discover` | S | **对齐** | **2** | `agent-session.ts:410,2977` / `:2609`（旧 394,2494） | `AgentSession.java:444` | —— |
| 77 | 会话恢复（resume seed + record replay） | S | **存疑** | **2** | `runtime/restore.ts`；`drive/recovery.ts`（未漂） | `RunLifecycle.seedTranscript:255`；`restoreRecords:295` | A2 |
| 78 | `latest()` / `find()` 会话定位范围 | S | **存疑** | **3** | `session-manager.ts:660,1654`（`findMostRecentSession` 只读一个项目目录） | `PersistentSessionRepositories.find()` 走 `list(all())` | **B53** |
| 79 | 会话列表 `onProgress` | S | **缺失** | **1** | `session-manager.ts:854,1761`（旧 772,812） | `list` 是同步全量 | **B55** |
| 80 | 宿主 `prompt()` 的 `streamingBehavior` | L | **存疑** | **2** | `agent-session.ts:1340-1352`（旧 1219-1232）；压缩中 `:1312-1316` | `AgentSession.processPrompt:591-609` 无门；门在 `PiLaneEngine.java:90-91` | **A5/F4** |
| 81 | 工具线程安全契约 | A | **存疑** | **1** | `agent-loop.ts:495-570`（旧 490-560） | 无显式声明 | **A10/A11** |
| 82 | `retry.provider.*` ↔ `HTTP RetryPolicy` | A | **存疑** | **2** | —— | —— | **A7** |
| 83 | `transcript()` 下游消费者清点 | A | **存疑** | **1** | —— | —— | **A2** |
| 84 | 两道门的行为裁决 | L | **存疑** | **2** | 见 #17/#80 | —— | **F4** |
| 85 | `recordEvent` 无宿主跨度时的兜底 | A | **存疑** | **1** | —— | —— | **F2** |
| 86 | `_emitSessionCompactFailed` | E | **存疑** | **1** | `agent-session.ts:637,2234`（旧 602） | 无 | **F3** |
| 87 | L5 夹具对 thinking 块不对称 | A | **存疑** | **1** | `conformance/pi/run.test.ts`（在 pi-java 仓） | `ScriptedStreams.java:73-79` | **E8** |
| 88 | `ActiveToolsChange` 发射 | E | **存疑** | **1** | —— | 不发射 | **C1** |
| 89 | 批内 join 源序 vs `Promise.all` | L | **存疑** | **1** | `agent-loop.ts:602`（旧 547） | `PiLoopTools.java:144` | **C2** |
| 90 | `transcript`/`messages`/`partial` 可见性 | S | **存疑** | **1** | —— | 写者唯一 | **C3** |
| 91 | `RunLifecycle.reset` 不 `close()` runSpan | S | **存疑** | **1** | —— | `RunLifecycle.java:228` | **C4** |
| 92 | 遥测 `startSpan`/`openSpan` javadoc | A | **存疑** | **1** | —— | `TelemetryContext:32-39` | **D2/D3** |
| 93 | `SummaryGenerator.java:12` javadoc 过期 | A | **存疑** | **1** | —— | 主源码 | **D6** |
| 94 | 文件超限：`PiLaneSink` 510 / `AgentHarness` **514** 行 | A | **存疑** | **1** | —— | `wc -l` | **E3/E4** |
| 95 | 夹具在集成层无牙 | A | **存疑** | **1** | —— | `ShellOutputStreamingTest` | **A14** |
| 96 | 错误路 `AgentSettled` 无夹具 | A | **存疑** | **1** | —— | `SessionRunner.java:188` | **A15** |
| 97 | **扩展开启（extended thinking）目录→请求投送** | C | **缺失** | **2** | `models.json` `thinkingLevelMap`（`model-config.ts:175,196`）→ `provider-composer.ts:113` → `anthropic-messages.ts:842` | `ThinkingLevelMap.of(` **4 个调用者全在 `src/test`**；`AgentSession.java:381-410` 无 `.thinkingLevelMap(...)` | **B15** |
| 98 | **`abortCompaction`** | A | **缺失** | **1** | `agent-session.ts:2250`（旧 2121）；TUI `modes/interactive/interactive-mode.ts:3481` | 全仓零命中 | **B36** |
| **99** | **工具装配变更声明**（system 消息的 `toolsAdded`/`toolsRemoved`，`declareToolChanges`） | L | **缺失** | **2** | `agent-loop.ts:281-331`（新函数）；`pi-ai` `types.ts:502-505`（`SystemMessage.toolsAdded/toolsRemoved`）+ `utils/transcript.ts:150`（`getToolStateChanges`） | 无对应机制；`Context.tools` 是整体替换 | **新**（`addedToolNames` 的替代物，见「实质变化」①） |
| **100** | **系统提示作为 transcript 的 system 消息**（含 `sections` 命名段替换/删除） | N | **存疑** | **3** | `pi-ai types.ts:491-507`（`SystemMessage.sections`）；`agent.ts:73-90`（`createInitialSystemMessage`）；`AgentContext` 已**无** `systemPrompt`（`types.ts:434-439`） | java 走 `Context.systemPrompt` 字段 + `AgentHarness.setSystemPrompt`：**首条 prompt 等价，会话中变更不等价** | **新** |
| **101** | **`prepareNextTurn` 可返回 `messages`**（追加消息 + 正常生命周期事件） | L | **缺失** | **2** | `types.ts:146-147`；`agent-loop.ts:184-186,209-214` | `PiLoop.NextTurnUpdate` 只有 `(model, thinking, context)` | **新** |
| **102** | `Agent.reset()` **保留基线 system 消息** | L | **存疑** | **1** | `agent.ts:344-355` | `RunLifecycle.reset:217-235` 清空 transcript 与工作副本 | **新** |
| **103** | **`experimental/pico3` kernel**（24 文件 / 7,994 行，见上节 P1–P14） | S | **缺失** | **1** | `harness/pico3/`；导出 `package.json:21-24` | 无 | **新** |
| **104** | **`packages/durable`**（13 文件 / 757 行，见上节 D1–D12） | S | **缺失** | **1** | `packages/durable/src/` | 无 | **新** |

> **不在本模块范围的台账条目**：B14/B17/B18/B19/B21/B23/B24（`pi-java-ai`）、
> B28/B29/B31/B32/B33/B48/B50/B51（RPC 线格式）、B38/B39/B47（TUI）、B52/A13/A16（已结案）、B54（web 路径）。

---

## 整块缺失

| 子系统 | pi LOC | 判定 | 权重 | 说明 |
|---|---:|---|---:|---|
| ① **durable drive 运行时**（`harness/runtime/drive/` 12 文件） | 7,505 → **7,473** | **缺失** | **2** | 内容**未变**（本次唯一改动是删 `addedToolNames`）。可观察差仍是 `run_suspend`/`run_resume`/`operation_abort`/`fault`/`lane_created` 五类事件在 java 侧不存在。**权重 2 而非 3 的理由**：java 的阻塞式驱动已跑通默认路径，缺的是崩溃/挂起那一半 |
| ② branch summary + 会话树导航 | 300 | 缺失 | *(计 #24/#25)* | 同上一版 |
| ③ effect gate | 64 | 缺失 | *(计 #43)* | 同上一版 |
| ④ storage conformance / benchmark 套件 | 2,716 | 缺失 | *(计 #74)* | 同上一版 |
| ⑤ `proxy.ts` | 402 → 406 | 缺失 | *(计 #68)* | 同上一版 |
| ⑥ `utils/adaptive-publisher.ts` | 87 | 缺失 | *(计 #66)* | 同上一版 |
| ⑦ JSONL v4 `values.ts` | 195 | 存疑 | *(计 #73)* | 同上一版 |
| ⑧ `formatSkillsForSystemPrompt` / `formatSkillInvocation` | 46 | 缺失 | *(计 #61/#62)* | 同上一版 |
| ⑨ `sanitizeSurrogates` | —— | 缺失 | *(计 #65)* | 同上一版 |
| **⑩ `harness/pico3/`（新）** | **7,994** | 缺失 | *(计 #103)* | **新增**，见 pico3 专节 |
| **⑪ `packages/durable`（新）** | **757** | 缺失 | *(计 #104)* | **新增**，见 durable 专节 |

---

## pi 前进 111 提交（`71dca871b` → `3390bd936`）的实质变化

### `agent` 包 +8,048 行到底加了什么

| 分条 | 增量 | 证据 |
|---|---:|---|
| **① 新增 `harness/pico3/` 子系统**（24 文件） | **+7,994** | `git diff --stat 71dca871b..HEAD -- packages/agent/src` 全部标 `+`；提交 `46b66c59a feat(agent): add hardened pico3 kernel` |
| **② `agent-loop.ts` 新增 `declareToolChanges()`** —— 工具装配变更声明 | +98 | `agent-loop.ts:281-331`；配套 `pi-ai` 的 `SystemMessage.toolsAdded/toolsRemoved`（`types.ts:502-505`）与 `getToolStateChanges`（`utils/transcript.ts:150`） |
| **③ 系统提示改为 transcript 的 system 消息** | +47（`types.ts`）+51（`agent.ts`） | `AgentContext` 去掉 `systemPrompt`（`types.ts:434-439`）；`StreamFn` 参数 `Context`→`TranscriptContext`；`AgentState.systemPrompt` 变只读 getter；`defaultConvertToLlm` 放行 `system`；`convertToLlm` 加 `case "system"`（`messages.ts:159`）；`Agent.reset()` 保留基线 system 消息（`agent.ts:344-355`）；`AgentEvent` 注释改「system, user, assistant, toolResult」 |
| **④ `AgentLoopTurnUpdate` 新增 `messages?`** | （含在 ③） | `types.ts:146-147`；`agent-loop.ts:184-186,209-214` |
| **⑤ 删除 `addedToolNames`** | −（含在 ②④） | `harness/execution/tools.ts` 两处删除；`runtime/drive/tool-placement.ts` 删 `laneConfig` 的 `activeToolNames` 追加逻辑（−44）；**`grep -rn addedToolNames packages/` 全仓零命中** |
| ⑥ `proxy.ts` 类型改名 `Context`→`TranscriptContext` | +4 | `proxy.ts:10-20,120-124` |
| ⑦ `types.ts` 类型收窄（`ToolResultMessage<unknown>`→`ToolResultMessage` 等） | 少量 | `runtime/drive/tool-placement.ts`、`runtime/drive/tools.ts` |

> **不是新增的**：`harness/runtime/`（−32）、`harness/session/`（0）、`harness/tools/`（0）、`harness/compaction/`（0）、`harness/utils/`（0）、`harness/env/`（0）—— 逐文件行数全等。

### 实质变化清单（判定需要改的）

| 单元 | 旧判定 | 新判定 | 一句证据 |
|---|---|---|---|
| **台账 C11（原 B4）`addedToolNames` 的 provider 层消费者** | 已裁决**不做**（C 类，带触发条件） | **作废（pi 已删除）** | `grep -rn addedToolNames --include=*.ts packages/` **全仓零命中**；pi 用 `SystemMessage.toolsAdded/toolsRemoved` 通用机制替代（`types.ts:502-505`）。按**裁决 R1**（pi 删了 ⇒ 作废），C11 的触发条件①「扩展系统落地且真产出 `addedToolNames`」**永不成立** |
| **#99 工具装配变更声明**（新单元） | —— | **缺失** | 同上；pi-java 无对应机制 |
| **#100 系统提示作为 system 消息**（新单元） | —— | **存疑** | `AgentContext` 已无 `systemPrompt`（`types.ts:434-439`）；java 仍是字段（`Context.java` + `AgentHarness.setSystemPrompt`）⇒ 首条等价、会话中变更不等价 |
| **#101 `prepareNextTurn` 返回 `messages`**（新单元） | —— | **缺失** | `types.ts:146-147` vs `PiLoop.NextTurnUpdate(model, thinking, context)` |
| **#102 `Agent.reset()` 保留基线 system 消息**（新单元） | —— | **存疑** | `agent.ts:344-355` vs `RunLifecycle.reset:217-235` |
| **#103 pico3**（新单元） | —— | **缺失** | 见专节 |
| **#104 durable**（新单元） | —— | **缺失** | 见专节 |
| **#58 `convertToLlm`** | 存疑（A1） | **存疑（缺口变大）** | pi 新增 `case "system"`（`messages.ts:159`）⇒ 转换面从「三选一」变「四选一」 |
| **#1–#57、#59–#98 全部** | 不变 | **不变** | 只漂行号（见下） |

### 只漂行号的引用：**58 条**（pi 侧 `.ts:line` 引用共 100 条，42 条行号未变）

| 文件 | 旧 | 新 |
|---|---|---|
| `agent-harness.ts` | 432 / 445 / 454 / 458 / 462 / 465 / 481 / 489 | 431 / 447 / 456 / 460 / 464 / 468 / 488 / 496 |
| `agent-loop.ts` | 71-77 / 171-272 / 177-183 / 206-208 / 217,253,272 / 231 / 252 / 431-485 / 490-560 / 506-545 / 547 / 590 | 135-139 / 179-278 / 184-190 / 231-241 / 223,259,278 / 249 / 258 / 476-486 / 495-570 / 552-602 / 602 / 644 |
| `agent-session.ts` | 1123 / 1219-1232 / 145-185 / 1830 / 1967-1968 / 1968 / 1970,2290,2085-2098 / 2121 / 2180-2210 / 2894-2908 / 3027 / 3116 / 3136 / 394,2494 / 548 / 557-577 / 559-563 / 594 / 602 / 770 | 770,1239,2990 / 1340-1352 / 145-194 / 1952 / 2089-2090 / 2090 / 2092,2421,2214 / 2250 / 2312-2352 / 3008-3022 / 3146 / 3235 / 3255 / 410,2977,2609 / 565 / 588 / 588 / 631 / 637 / 704 |
| `agent.ts` | 108-112 | 123-129 |
| `types.ts`（agent） | 50 / 138-145 / 223 / 245,258 / 431-446 | 55 / 143-150 / 230 / 252,265 / 448-463 |
| `session/types.ts` | 37,49 | 40,49 |
| `coding-agent/core/tools/index.ts` | 201 / 200-209 | 96 / 95-104 |
| `coding-agent/core/tools/find.ts` | 33-37 | 26-31 |
| `coding-agent/core/tools/bash.ts` | 21-22 | 22 |
| `settings-manager.ts` | 891 / 891-901 / 903-911 | 896 / 896-906 / 908-916 |
| `stream-fn.ts` | 20 | 11 |
| `session-manager.ts` | 772,812 | 854,1761 |
| `interactive-mode.ts` | `core/interactive-mode.ts:3391-3394` | **`modes/interactive/interactive-mode.ts:3481`**（**路径迁移**） |
| `packages/ai/src/types.ts` | 713-714 | 824（`allowEmptySignature`） |

**行号未变的 42 条**（文件内容与行号都逐字相同）：`messages.ts:4-17/19-62/124`、`agent-harness.ts:255-372/435/439-442/443`、
`session/types.ts:16,64/59-62/69-73/129,350`、`runtime/reducer.ts:22`、`runtime/drive.ts:36-42`、`execution/effect-gate.ts:1-64`、
`compaction.ts:183-193/215-245/343,370`、`branch-summarization.ts:219,242`、`harness/tools/bash.ts:8,11-13/20-23`、`harness/tools/image.ts:3-19`、
`system-prompt.ts:3-32`、`skills.ts:39/51,86`、`prompt-templates.ts:31,226,252,268`、`utils/truncate.ts:1-350`、`result.ts:53-100/68`、
`search/index.ts:20-28`、`jsonl/legacy-v3.ts:39,66,72,77`、`session/jsonl/storage.ts:1-267`、`core/tools/read.ts:14-17/73`、
`core/tools/write.ts:11-13`、`core/tools/edit.ts:32-36`、`core/tools/grep.ts:21-32`、`core/tools/ls.ts:11-13,62`、
`core/tools/powershell.ts:59`、`core/tools/bash.ts:265,297`、`proxy.ts:120`。

---

## B15 的新 HEAD 复核结论

**结论：B15 的判定不变（缺失，权重 2）—— 断链 100% 在 pi-java 一侧，pi 侧从来没有断过。**

| 环节 | pi 旧 HEAD | pi 新 HEAD | 说明 |
|---|---|---|---|
| 目录生成 | `generate-models.ts:478-532,917,1013-1014,1172` | 同 + `:2251,2794`（新增 `DEEPSEEK_V4_FLASH_THINKING_LEVEL_MAP`） | **旧 HEAD 就已生成** |
| models.json schema | `model-config.ts:168,181` | `model-config.ts:175,196`（+ `:206`） | **旧 HEAD 就已暴露** |
| provider 合成 | `provider-composer.ts:108-110,157` | `provider-composer.ts:113` | **旧 HEAD 就已应用** |
| 请求侧 | `anthropic-messages.ts:830` | `anthropic-messages.ts:842` | **旧 HEAD 就已读** |

⇒ 我上一版把 `B15` 写成「pi 有、java 无」是**对的**，但行号指向的是**旧** HEAD；
四条 pi 侧引用**全部只漂行号**，**无一条实质变化**。

**pi-java 侧（未动，`34849a2`）**：`ThinkingLevelMap.of(` 全仓**恰 4 个调用者全在 `src/test`**；
`HarnessConfig.Builder` 默认 `empty()`（`HarnessConfig.java:159`）；`AgentSession.java:381-410` 的 builder 链**无 `.thinkingLevelMap(...)`** ⇒ `forLevel(Enabled)` 恒 `ThinkingConfig.OFF` ⇒ `DefaultProviders.java:113-115` 门恒假。
**断链未修，判定不变。**

---

## 汇总（未加权）

| 表 | 对齐 | 缺失 | 存疑 | 合计 | 完成度 |
|---|---:|---:|---:|---:|---:|
| 内置工具 | 4 | 1 | 4 | 9 | **44.4%** |
| 能力单元（除工具） | 36 | 26 | 42 | 104 | **34.6%** |
| 整块缺失（仅 ① 独立计） | 0 | 1 | 0 | 1 | **0%** |
| **合计** | **40** | **28** | **46** | **114** | **35.1%** |

**未加权完成度 = 40 / 114 = 35.1%**（旧 108 单元 / 37.0%）
（对齐＋存疑视为「有」：86/114 = **75.4%**）

---

## 加权汇总

| 域 | Σ权重 | Σ(w×系数) | 加权完成度 | 未加权完成度 |
|---|---:|---:|---:|---:|
| 内置工具（9） | 21 | 15.0 | **71.4%** | 44.4% |
| 能力单元（除工具，104） | 191 | 118.5 | **62.0%** | 34.6% |
| 整块缺失（仅 ① 独立计，1） | 2 | 0 | **0%** | 0% |
| **模块合计（114）** | **214** | **133.5** | **62.4%** | **35.1%** |

**模块合计**：Σ权重 214 ／ Σ(w×c) 133.5 ／ **加权完成度 = 133.5/214 = 62.4%**（未加权 35.1%）
（旧：Σw 204 / Σ(w×c) 131.5 = **64.5%**；**新 HEAD 把加权完成度拉低 2.1 pp**）

按权重分层（能力单元表，104 条）：

| 权重 | 条数 | Σ权重 | 对齐 | 存疑 | 缺失 | Σ(w×c) | 层完成度 |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 3 | 22 | 66 | 14 | 8 | 0 | 54.0 | **81.8%** |
| 2 | 43 | 86 | 19 | 13 | 11 | 51.0 | **59.3%** |
| 1 | 39 | 39 | 3 | 21 | 15 | 13.5 | **34.6%** |
| 合计 | 104 | 191 | 36 | 42 | 26 | 118.5 | 62.0% |

### 权重 3 的单元清单（22 条能力单元 + 5 条工具 = 27 条）

| 单元 | 判定 |
|---|---|
| `bash` 工具 | **存疑** |
| `read` / `write` / `edit` 工具 | 对齐（3 条） |
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
| #54 Entry union（java 是 pi **v3** 形状） | **存疑** |
| #58 `convertToLlm` | **存疑**（A1，**缺口变大**） |
| #73 JSONL v4 存储 | **存疑** |
| #78 `latest()`/`find()` 会话定位范围 | **存疑**（B53） |
| **#100 系统提示作为 system 消息**（**新**） | **存疑** |

**权重 3 层 = 14 对齐 / 8 存疑 / 0 缺失**。新增的第 8 条存疑是 #100（系统提示载体改变）。

### 未加权 vs 加权的差（35.1% → 62.4%，**+27.3 pp**）

差仍**全部来自分母重新分配**：

| 池 | 未得分 | 占比 |
|---|---:|---:|
| 能力单元 权重 2 | **35.0** | 43% |
| 能力单元 权重 1 | 25.5 | 32% |
| 能力单元 权重 3 | 12.0 | 15% |
| 内置工具 | 6.0 | 7% |
| 整块缺失 ① | 2.0 | 3% |
| 合计 | 80.5 | 100% |

⇒ **权重 2 层（59.3%）仍是最大未得分池**（35/80.5），且本次新增的 4 条缺失里有 **2 条在权重 2**（#99、#101）。
**缺失的权重分布**：28 条里 **0 条权重 3**、11 条权重 2、16 条权重 1、1 条整块缺失（权重 2）。

### 三条点名项的边际影响（分母 214）

| 条目 | 权重 | 补齐后 | 边际 |
|---|---:|---:|---:|
| **B15 扩展思考不可达**（#97） | 2 | 135.5/214 = 63.32% | **+0.93 pp** |
| **A4 `/compact` 缺 abort-first**（#17） | 2 | 135.5/214 = 63.32% | **+0.93 pp** |
| **durable drive 整块缺失**（①） | 2 | 135.5/214 = 63.32% | **+0.93 pp** |
| 三条合计 | 6 | 139.5/214 = **65.19%** | **+2.80 pp** |

**三条权重相同（都是 2），边际完全等量，各拉低模块约 0.93 pp。**
（比上一版的 0.98 pp 略小，只因分母从 204 涨到 214。）

### 本次新增的两条口径（可被推翻）

- **pico3（#103）与 durable（#104）各归权重 1**：两者都**未被 pi 生产接线**（pico3 在 `experimental/` 子路径且全仓引用只在自身与其测试；durable 无任何包依赖它）⇒ 不是默认路径。若按 LOC 给权重会严重失真（pico3 7,994 行是 agent 包增量的 99%）。
- 由此得出的判断：**+8,048 行里 7,994 行（99.3%）是未接线的实验子系统** ⇒ 这次 pi 的前进**对 pi-java 的实际对齐压力几乎为零**（加权完成度只降 2.1 pp，且其中 1.5 pp 来自 #99–#102 那四条真正的行为变化）。
