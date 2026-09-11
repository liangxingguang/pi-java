# 21 — Record 日志补全 + LaneState 可派生（对齐 pi harness/reducer.ts）

> 上一篇：`docs/20-agent-loop-optimization-plan.md`（agent-loop 状态机对齐）。
> 本篇是**记录日志（LaneRecord）完整性** + **记录派生（fold）** 阶段设计。
> 编制日期：2026-09-11。基准：pi `packages/agent/src/harness/reducer.ts`（667 行纯函数 `reduceLaneState`）。
>
> **范围**：① 补 P0 记录发射缺口；② 移植 pi reducer 子集 → `LaneStateFolder.fold(records, ownEntries, configurationEntries)`
> + `validateRecordLog` 子集校验；③ resume 恢复改为 **pi 式有界切片**；④ 哨兵测试 `LaneStateFoldTest`。
> **Out（明确推迟）**：见 §6。

## 0. 目标

把「LaneState 是可变的独立对象 + 并行审计日志」改为 **「LaneState 是 record 日志的纯函数折叠」**——
两条核心收益：

1. **审计闭环**：LaneRecord 全部 11 变体（含新增 `QueueConsumed`）都有发射点，日志不再是残缺的；
2. **可恢复**：`fold(records, ownEntries, configurationEntries)` 能脱离 live driver 重建编排状态，
   且**恢复是有界的**（O(op 范围) 而非 O(全部 entry)）、**损坏可检测**（`validateRecordLog` 拒绝而非静默）。

对齐基准是 pi 的 `harness/reducer.ts`。pi 的 reducer 是纯函数（`reduceLaneState(input)`），
生产消费方是 resume/restore 层。

> **方案升级（用户确认 2026-09-11）**：fold 输入从 `transcript` 全量 → **pi 式有界切片**
> `(records, ownEntries, configurationEntries)`。期望「和 pi 一样」的 resume 扩展性/健壮性：
> 存储层已具备查询能力（`RecordQuery` 按 runId 过滤 + `EntryQuery` 按 seq cursor 切片），无需改 schema。

---

## 1. 现状盘点：P0 记录发射缺口

`LaneRecord.java` 现有 10 变体。逐项核对发射点：

| 变体 | 现状 | 缺口 |
|---|---|---|
| `OperationStarted` | run 发射（`ActionExecutor.run`） | ✅ |
| `OperationFinished` | L3 收敛为 `executeFinishOperation` 单点发射 | ✅ |
| `StepAttempt` | run 的 ASSISTANT 尝试发射（`ActionExecutor.executeStreamAssistant`） | ⚠️ **缺 stopReason 字段**（D3） |
| `ToolStarted` | run 执行工具时发射 | ✅ |
| `ToolFinished` | 同 | ✅ |
| `AbortRequested` | `AgentHarness.abort()` 非空闲时发射 | ⚠️ **`close()` 不发射**（D5） |
| `QueueEnqueued` | **从不发射** | 🔴 P0 |
| `QueueCancelled` | **从不发射** | 🔴 P0 |
| `WriteDeferred` | **从不发射** | 🔴 P0（pi-java 无 deferred 运行时，§6-1） |
| `UsageRecord` | run 结束时发射 | ⚠️ **仅 tokens>0 时发射**（D2） |

顺带：`AgentHarness.run()` / `runContinue()` 里 `records.clear()`（`:92, :143, :180`）是破坏性清空，
与「append-only 记录日志」相悖，需移除（D6）。

---

## 2. 关键设计决策

| # | 决策 | 理由 |
|---|---|---|
| **D1** | 移植 reducer **子集**（operation-state 派生 + 配置派生），不搬全量 | 派生集正是核心；deferred 无运行时（§6-1） |
| **D2** | fold 输入 = **pi 式有界切片** `fold(records, ownEntries, configurationEntries)`，不是全量 transcript | 恢复 O(op 范围)；`EntryQuery` 按 seq cursor 切片、`RecordQuery` 按 runId 过滤已支持（`EntryQuery.java:13`、`RecordQuery.java:16`），无需改 schema |
| **D3** | `StepAttempt` 加 `stopReason` 字段（agent-core 内 schema 变更） | 否则 newestOwn.stopReason / phase 在 tokens==0、error/aborted 路径不可派生 |
| **D4** | **新增 `QueueConsumed` 记录变体**（pi-java 独有，偏离 pi 9 变体，需在设计文档明示） | pi 靠 entry-presence 推断消费，pi-java 把 drain 合并成单条 user entry，结构上无法按条推断；`QueueCancelled` 仅表达显式取消 |
| **D5** | compaction 是 **step 而非嵌套 operation**：run 中发射 `StepAttempt(COMPACTION)`；空闲时发射 `OperationStarted(Compaction)`+`StepAttempt`+`OperationFinished` 三连 | `JsonlSessionStorage.appendRecord:191-197` 拒绝每 lane 第二个 open operation，run 中发射 OperationStarted(Compaction) 会破坏持久化 |
| **D6** | `lane.records` 改 **append-only**（移除 `run()`/`runContinue()` 的 clear，保留 `reset()`） | fold 需跨 run 历史（run N 入队 nextRun 在 run N+1 消费）；`persistPending` 已 id 去重 |
| **D7** | fold 是**纯函数** `LaneStateFolder.fold(records, ownEntries, configurationEntries)`，前置 **`validateRecordLog` 子集**校验（损坏→`RecordLogCorruption`）；live driver 继续原地改 LaneState。不做增量 apply | pi reducer 即纯函数 + 校验先行；两个消费方都不需要增量 |
| **D8** | **abort outcome 修正**：`HarnessUtils.determineOutcome` 把 `"aborted"` → `"aborted"`（现映射到 error→FAILED，污染 `faulted`） | pi 对齐；⚠️ 行为变更，需查现有断言 |
| **D9** | **resume 恢复改为有界切片**：`SessionPersistence.attach` 用 `findRecords(RecordQuery(lane, null, null,...))` + `findEntries(cursor afterSeq)` 有界取数据 → `LaneStateFolder.fold` → 重建 LaneState，替代全量 `seedTranscript` | 恢复不随会话增长；compaction 也能正确重建（record 日志是源，不依赖 transcript 完整性） |

> **为什么 fold 是双投影**：pi 的 reducer 输入含 `ownEntries` + `configurationEntries`（entry 查询），
> 而 entry 与 record 在存储层分开持久化（`SessionStorage.appendEntry`/`appendRecord` 两个 API）。
> pi-java 的 `Entry` 走独立持久化（transcript + `seedTranscript` 路径），不重复进 record 日志。
> 所以 fold 签名 = `fold(records, ownEntries, configurationEntries)`，**与 pi 同构**。

---

## 3. 目标类签名

### 3.1 `LaneRecord.StepAttempt` 增字段

```java
record StepAttempt(
    String id, long seq, String lane, Instant timestamp,
    String runId, StepKind step, int attempt, String resultEntryId,
    String compactionReason, String model, Integer messageCount, Integer toolCount,
    String thinking, Long durationMs,
    String stopReason        // NEW: newestOwn.stopReason 派生的来源（assistant step 才有值）
) implements LaneRecord {}
```

JSONL 旧文件解码 → null，向后兼容（SQLite 记录 payload 是 JSON blob，无表迁移）。

### 3.2 新增 `LaneRecord.QueueConsumed`（D4）

```java
/** A queue item was consumed (drained and merged into the transcript). pi-java 独有。 */
record QueueConsumed(
    String id, long seq, String lane, Instant timestamp,
    String runId,                 // 消费它的 run；空闲消费为 ""
    QueueKind queue,              // steer | followUp | nextRun
    List<ProvisionedEntry<?>> targets   // 该次 drain 消费的全部项（QueueMode 整体合并）
) implements LaneRecord {}
```

- `QueueEnqueued`（现有）在 `QueueManager.steer/followUp/nextRun` 发射（Step 2）。
- `QueueCancelled`（现有）在 `QueueManager.cancelQueued` 发射（Step 2）。
- `QueueConsumed`（新增）在 `ActionExecutor.executeConsumeQueueItem` 发射（Step 2）。

### 3.3 `LaneStateFolder`（核心，新文件 ~350 行）

```java
package com.pijava.agent.harness;

/**
 * 纯函数：把 lane 的 record 日志 + 有界 entry 切片折叠回编排状态。
 * 对齐 pi harness/reducer.ts 的 reduceLaneState（operation-state + 配置子集）。
 * 纯函数——不依赖 live driver，供哨兵测试与 resume 恢复消费。
 */
final class LaneStateFolder {

    /** 折叠结果：与 LaneState 对应字段对齐的不可变快照。 */
    record FoldedState(
        String lane,
        RunPhase phase,                 // IDLE | RUNNING | CHECKPOINT（fold 归一化，见 R5）
        String runId,                   // 当前 open operation 的 id；空闲为 null
        int stepIndex,                  // 当前 run 已完成的 step 数
        NewestOwn newestOwn,            // 最近一条 own entry 的摘要（含 stopReason）
        boolean faulted,                // 最近一次 operation 以 error 结束
        boolean aborted,                // 最近一次 operation 被 abort
        EffectiveConfiguration effectiveConfiguration,
        List<LaneInfo.QueuedItem> pendingSteer,
        List<LaneInfo.QueuedItem> pendingFollowUp,
        List<LaneInfo.QueuedItem> pendingNextRun
    ) {}

    /** 派生出的有效配置（对齐 pi EffectiveLaneConfiguration，但无 provider 后缀）。 */
    record EffectiveConfiguration(
        ModelId<?> model,
        String thinkingLevel,       // null = 未变（继承 harness 默认）
        List<String> activeToolNames // null = 未变（继承 harness 默认）
    ) {}

    /**
     * fold(records, ownEntries, configurationEntries) 的入口。
     * records 按 seq 升序 fold；ownEntries = open operation 追加的条目（旧在前）；
     * configurationEntries = 配置类 entry（ModelChange/ThinkingLevelChange/ActiveToolsChange，旧在前）。
     */
    static FoldedState fold(
        String lane,
        List<LaneRecord> records,
        List<Entry> ownEntries,
        List<Entry> configurationEntries
    ) { ... }
}
```

fold 逻辑（对齐 pi `reduceLaneState` 的相关分支）：
- **open operation**：按 seq 找最后一条 `OperationStarted`，其后无 `OperationFinished` → 该 op 是当前 run；
- **pendingSteer / pendingFollowUp / pendingNextRun**：按 seq 收集 `QueueEnqueued`，扣除已被
  `QueueConsumed` / `QueueCancelled` 消化的项；nextRun 的 `target.id` 永不进入 transcript → 视为仍 pending；
- **newestOwn**：ownEntries 里最后一个 `role=assistant` 的 `Entry.Message`，stopReason 取对应
  `StepAttempt.stopReason`（若无 → null）；
- **effectiveConfiguration**：扫描 configurationEntries，`ModelChange`/`ThinkingLevelChange`/`ActiveToolsChange`
  按 seq 顺序覆盖默认值（对齐 pi `deriveEffectiveConfiguration:400-427`）；
- **phase**：有 open op → CHECKPOINT（fold 归一化，见 R5）；无 → IDLE；
- **faulted / aborted**：最近一次 `OperationFinished` 的 outcome 是 `"error"`（D8 后 `"aborted"` 不再计入）；
- **stepIndex**：open op 内 `StepAttempt` 数。

fold 不派生：deferred（无运行时）、toolBatch、terminalFailure（§6）。

### 3.4 `validateRecordLog` 子集（D7）

```java
/** 记录日志损坏（对齐 pi RecordLogCorruptionReason，子集）。 */
final class RecordLogCorruption extends RuntimeException {
    record Reason(String code, String message) {}
    static RecordLogCorruption of(String code, String message) { ... }
}
```

子集范围（裁剪 deferred 依赖 + queue provisioned 深度校验）：

| 保留 | pi 位置 | 含义 |
|---|---|---|
| `multiple_open_operations` | :313-315 | 每 lane 只能有一个 open operation |
| `unknown_operation` | :335-337 | record 引用了不存在的 operation |
| `record_after_finish` | :338-342 | finish 之后还有该 run 的 record |
| `non_consecutive_attempt` | :180-205 | step_attempt 的 attempt 序号必须连续 |
| `invalid_compaction_reason` | :169-178 | compaction step 必须有合法 reason；非 compaction 不得有 |
| `queue_after_abort` | :361-367 | abort 后仍入队 |
| `invalid_queue_cancellation` | :371-382 | 取消的项没有对应的 pending enqueue |

裁剪：`invalid_deferred_handle`（依赖 deferred 运行时）、`tool_call_mismatch`/`duplicate_tool_invocation`/
`provisioned_entry_mismatch`/`inconsistent_step`（依赖 entry 深度校验，本阶段 fold 不需要——R6/R8）。

### 3.5 `AgentHarness.close()` 补 AbortRequested

```java
@Override
public void close() {
    closed = true;
    for (var lane : lanes.values()) {
        if (lane.abortSignal != null) lane.abortSignal.abort();
        if (!(lane.phase instanceof RunPhase.Idle)) {
            lane.records.add(new LaneRecord.AbortRequested(
                UUID.randomUUID().toString(), 0, lane.laneName, null,
                lane.runId == null ? "" : lane.runId));
        }
        snapshotService.publishState(lane.laneName);
    }
}
```

### 3.6 `SessionPersistence.attach` 改为有界恢复（D9）

```java
// attach 尾部：有界恢复 — findRecords + findEntries(cursor) → fold → 重建 LaneState
var records = opened.findRecords(new RecordQuery(lane, null, null, null, null,
    EntryOrder.OLDEST_FIRST, null));
var openOps = opened.findOpenOperations(lane, 1);
String anchorSeq = openOps.isEmpty() ? null : /* open op 的 seq */;
// ownEntries = seq > anchorSeq 的 entries（op 追加的）
var ownEntries = opened.findEntries(new EntryQuery(null, null,
    EntryOrder.OLDEST_FIRST, null, new EntryCursor(anchorSeq)));
// configurationEntries = 配置类 entry（按类型过滤，OLDEST_FIRST）
var configurationEntries = opened.findEntries(new EntryQuery(null, null,
    EntryOrder.OLDEST_FIRST, null, null))
    .stream().filter(Entry::isConfiguration).toList();
var folded = LaneStateFolder.fold(lane, records, ownEntries, configurationEntries);
owner.harness().restoreFromFold(folded);   // 重建 phase/runId/queues/records/newestOwn
```

> **现有 `seedTranscript` 路径保留**：`restoreFromFold` 重建编排状态（phase/queues/records），
> transcript 仍由 `ContextEntries.contextEntries(pathToLeaf(...))` 提供（compaction-aware）。
> 两条路径并行：fold 管「编排状态」，seedTranscript 管「显示上下文」。

---

## 4. 数据流（mermaid）

### 4.1 记录发射点（live driver 侧）

```mermaid
flowchart LR
    subgraph Live["live driver（原地改 LaneState）"]
        A["run()/runContinue()"] -->|OperationStarted| R
        B["executeStreamAssistant"] -->|StepAttempt(stopReason)| R
        C["executeConsumeQueueItem"] -->|QueueConsumed| R
        D["QueueManager.steer/followUp/nextRun"] -->|QueueEnqueued| R
        E["QueueManager.cancelQueued"] -->|QueueCancelled| R
        F["CompactionExecutor"] -->|StepAttempt(COMPACTION)| R
        G["executeFinishOperation"] -->|OperationFinished| R
        H["ToolExecutionPipeline"] -->|ToolStarted/ToolFinished| R
        I["close()/abort()"] -->|AbortRequested| R
        J["run 结束"] -->|UsageRecord(stopReason)| R
        R[(LaneRecord 日志)]
    end
```

### 4.2 消费方（fold 侧，pi 式有界）

```mermaid
flowchart LR
    R[(LaneRecord 日志)] --> V["validateRecordLog 子集"]
    E[(entry 存储)] -->|ownEntries 有界切片| K["LaneStateFolder.fold(records, ownEntries, configurationEntries)"]
    E -->|configurationEntries 有界切片| K
    V -->|RecordLogCorruption| X["拒绝恢复"]
    V -->|通过| K
    K -->|FoldedState| S["哨兵测试 LaneStateFoldTest（== live snapshot）"]
    K -->|FoldedState| X2["resume 恢复（restoreFromFold，替代全量 seedTranscript）"]
```

---

## 5. 测试清单

| 测试 | 文件（新） | 断言 |
|---|---|---|
| 哨兵：user→tool→follow-up→compaction 全驱动 | `LaneStateFoldTest` | 每个边界 `fold(records, ownEntries, configurationEntries)` 派生的 phase/runId/stepIndex/newestOwn/effectiveConfiguration 与 live `LaneState` 相等（phase 在 tool_use-stream 后单一瞬态点跳过，R5） |
| 队列消费折叠 | 同 | enqueue→consume 后 fold 的 pendingFollowUp == live 队列剩余 |
| compaction step | 同 | run 中 compaction → fold 产出 `StepAttempt(COMPACTION)`，pendingWrites 正确 |
| abort 路径 | 同 | `abort()` 后 fold 的 `aborted=true`，phase 不为 RUNNING |
| 空闲三连 | 同 | 空闲 compaction → `OperationStarted(Compaction)`+`StepAttempt`+`OperationFinished` |
| validateRecordLog | 同 | 构造损坏日志（第二个 open op / record-after-finish / 非连续 attempt / abort 后入队）→ 断言 `RecordLogCorruption` |
| queue 记录发射 | `QueueRecordEmissionTest` | steer/followUp/nextRun/cancelQueued/consume 各发射对应记录 |
| resume 有界恢复 | `coding-agent` 恢复测试 + 新增 | 持久化后重建 harness，fold 重建的 LaneState 与持久化前一致（compaction 场景也验证） |
| 现有回归 | 复用 `AgentLoopL2Test` / `RunToCompletionTest` / `HookTest` | append-only + D8 行为变更不破坏现有语义 |

---

## 6. Out（明确推迟）项：为什么不做

| Out 项 | 为什么不做 | 何时做 |
|---|---|---|
| **1. deferred 执行机制** | pi-java 只有 schema 碎片，**无运行时**：`DeferredHandle` 全仓库 0 命中，`fetch_deferred`/`cancel_deferred` 无对应 Action，`WriteDeferred` record 无发射点。fold 派生的 deferred 字段无消费者 | P0 之后专门立项（需先做 write-deferred 运行时 + prompt-template 消费者） |
| **2. toolBatch 派生** | pi `deriveToolBatch` 依赖 pi 的 entry-presence 语义（按条 entry 推断消费/结果）；pi-java 把同轮 drain 合并成单条 user entry，结构上无法按条对齐；toolBatch 主要用于 resume 续跑批量的校验，pi-java 无 `toolBatch` resume | 等 resume 完整性专项 |
| **3. terminalFailure 溯源** | 依赖 assistant `stopReason==="error"` + 判定错误是 step 还是 deferred_fetch 产物——后者无运行时（见 1）；且 pi-java 的 error 路径语义不同 | 随 deferred 立项一并考虑 |
| **4. entry-内嵌 stopReason（pi 做法）** | pi 把 stopReason 存在 assistant entry 的 message 上；pi-java 的 `StepAttempt` 无 stopReason 字段、`determineOutcome` 从内存 `lane.newestOwn` 读。若走 pi 路线 = 改 Entry 持久化 schema（JSONL/SQLite 双写）+ 全仓 message 消费方，blast radius 过大 | 本阶段用 D3 折中（StepAttempt 加 stopReason 字段） |
| **5. queue 消费按 entry-presence 推断** | pi 每条 queue item 对应一条 entry，消费 = entry 出现；pi-java 把 drain 合并成单条 user entry（QueueMode 整体合并），无逐条对应 | 本阶段用 D4 折中（新增 QueueConsumed record） |

> **effectiveConfiguration 已移入 In**（从 Out 移除）：配置 Entry（ModelChange/ThinkingLevelChange/ActiveToolsChange）
> 本就进 transcript（`ContextAssembler:42/60`、`ActionExecutor:113` 确认），fold 的 `configurationEntries` 直接读它们
> 即可派生，无需新发射点。

---

## 7. 实施节奏

> **第一步（本阶段唯一交付物，写完即停）**：本篇 + `docs/02`/`docs/03` addendum，提交
> `docs(agent-core): 21 record-log fold design (pi-style bounded slices) + 02/03 addendum`。
> **完成后停下，交人审核。**

**第二步（审核通过后实施，5 个代码提交）**：

| Step | 提交 | 主要文件 |
|---|---|---|
| 1 | `feat(agent-core): persist step stopReason on StepAttempt + abort outcome` | `record/LaneRecord.java`、`session/jsonl/RecordJsonCodec.java`、`harness/ActionExecutor.java`、`harness/HarnessUtils.java` + 4 处测试 fixture |
| 2 | `feat(agent-core): emit queue_enqueued/queue_cancelled/queue_consumed records` | `record/LaneRecord.java`（+QueueConsumed）、`harness/QueueManager.java`、`JsonlCodec`/`RecordJsonCodec`/`SessionState`/`SqliteCodecs` + 测试 |
| 3 | `feat(agent-core): compaction records, close abort, append-only records` | `harness/CompactionExecutor.java`、`harness/AgentHarness.java`（close + seedRecords）、`harness/ActionExecutor.java`（去 clear） |
| 4 | `feat(agent-core): LaneStateFolder — record-log fold + validateRecordLog` | `harness/LaneStateFolder.java`（新，~350 行，含 fold + validateRecordLog 子集 + FoldedState + EffectiveConfiguration + RecordLogCorruption） |
| 5 | `feat(agent-core): LaneStateFoldTest sentinel + resume bounded restore` | `harness/LaneStateFoldTest.java`（新）、`coding-agent/SessionPersistence.java`（attach 改有界切片→fold→restoreFromFold） |

分支 `phase21-record-fold`；显式 `git add <path>`，禁 `-A`/`.`。

---

## 8. 风险

| # | 风险 | 缓解 |
|---|---|---|
| **R1** | StepAttempt.stopReason ripple | 已全枚举（LaneRecord/RecordJsonCodec/ActionExecutor + 4 fixtures）；SQLite 记录 payload 是 JSON blob 无需表迁移，JSONL 旧文件解码 null 兼容 |
| **R2** | QueueConsumed 新变体漏配 case | `SessionState.matchesRecordQuery` 与 `SqliteCodecs.recordRunId` **必须**补 case，否则 findRecords(runId) / SQLite run_id 列漏掉新类型 |
| **R3** | append-only records 使 `LaneSnapshot.records()` 增长（长会话 web wire 膨胀） | 默认接受，审阅反对时再限流 |
| **R4** | 空闲 compaction 用 compaction op id 作 runId；harness 保持 idle | TryFinishRun 不会对 compaction op 跑 |
| **R5** | phase 微态歧义：records 无法区分「tool_use stream 后 CHECKPOINT 瞬态」与「TryFinishRun(tool_use) 后 ASSISTANT」 | fold 返回 settle 值（ASSISTANT）；哨兵在该单点跳过 phase 断言。可选后续：TryFinishRun 决策记录（推迟） |
| **R6** | QueueEnqueued.target.id 用 `Long.toString(seq)`，永不对应真实 transcript entry（prompts 合并） | fold 仅靠 QueueConsumed；`validateRecordLog` 子集跳过 queue 的 provisioned 深度校验（pi `validateExactProvisionedEntry`），避免误报损坏 |
| **D8** | abort 终局 outcome FAILED→ABORTED | 查受影响测试（如 HookTest abort 用例）后一并改 |
| **R7** | append-only 后 `reset()` 仍 clear → fold 历史截断 | reset 语义明确是「清空 lane」，保留；fold 只对 reset 之后的记录负责 |
| **R8** | `validateRecordLog` 子集裁剪范围 | 裁剪 deferred 依赖 + entry 深度校验（tool_call_mismatch/duplicate/provisioned_entry_mismatch/inconsistent_step）；保留的 7 类都是 records 内部一致性，不依赖 entry lookup |
| **R9** | resume 有界恢复改动 `SessionPersistence.attach` 的 seedTranscript 路径 | 现有 coding-agent 恢复测试 + 新增有界恢复测试覆盖；fold 重建编排状态与 seedTranscript 管显示上下文并行不冲突 |
| **R10** | 配置派生依赖 Entry 已进 transcript | `ContextAssembler:42/60`、`ActionExecutor:113` 确认 `ModelChange`/`ThinkingLevelChange` 已发射；`ActiveToolsChange` 由 harness `setActiveTools` 写入（HookTest:196/227 佐证） |

---

## 9. 验收标准

- `mvn clean verify` 零错误零警告，全模块测试通过（agent-core → sqlite → coding-agent）
- `LaneStateFoldTest` 全绿：fold == live snapshot（哨兵）+ validateRecordLog 损坏检测
- `QueueRecordEmissionTest` 全绿：三队列 + 取消 + 消费各发射记录
- `LaneRecord` 11 变体全部有发射点（无一变体「从未发射」）
- `SessionPersistence.attach` 有界恢复：fold 重建的 LaneState 与持久化前一致（含 compaction 场景）
- 无 `System.out.println` 残留，spotbugs/checkstyle 零违规
- 文件 ≤ 500 行（`LaneStateFolder` 若超则拆 `LaneStateQueueFold` / `LaneStateOpFold` / `RecordLogValidator`）
