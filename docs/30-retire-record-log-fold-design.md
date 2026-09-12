# 30 — 退休 record-log 折叠链（`docs/28 §5` 第 5 步）

> **状态**：裁决已下（2026-09-13）——**退休**。本文是实施蓝图。
> **上游**：`docs/28 §5` 第 5 步「把 `records` 发射点接到新循环上，`LaneOperationFold` /
> `LaneStateFolder` 退休」，以及 `docs/29 §9` 记录的实测发现。

## 1. 为什么退休（三条独立证据）

1. **参照实现已不存在。** `v0.85.1` 的 `packages/agent/src/harness/runtime/reducer.ts` 只有
   232 行，是**实时事件归约器**（`reduceLaneSnapshot`：把 `HarnessEvent` 归约进 lane 快照），
   不是日志折叠器。`deferredWrite` / `WriteDeferred` / `deriveToolBatch` / `reduceLaneState`
   在 `v0.85.1` **与**用户 `my-pi` 检出上**全仓零命中**。`LaneOperationFold` 的 javadoc 引用的
   `reducer.ts:479-486`、`:614-640` **指向 pi 已删除的 667 行旧版**。
2. **pi 明文禁止该模型。** `harness.md:1317` invariant 5：
   *"No read on a hot path may fold history or infer state from an absent value — no value history
   exists to fold."* pi 的替代品是 `OperationState` —— 13 个扁平叶子，称为
   *"the total durable restart point"*，**每次整份替换**，而不是折叠重建。
3. **它现在就是错的。** `docs/29 §9.2`：`deferredWriteIds` 使 `toolBatch()` 与
   `terminalFailure()` 在**任何真实日志**上恒为空（两条路径都复现）。修它需要先判定「排除条件
   过宽还是 `recordDeferredWrite` 过宽」，而没有参照可判 —— 两个方向都能让现有测试变绿。

## 2. 现状：折叠链的构成与唯一消费者

| 类 | 行数 | 职责 |
|---|---|---|
| `LaneStateFolder` | 120 | `FoldedState` / `EffectiveConfiguration` 形状 + 两个入口 |
| `LaneOperationFold` | 471 | 折叠算法 + `ToolBatch` / `TerminalFailure` 派生 |
| `RecordLogValidator` | 254 | 损坏规则（pi `reducer.ts` R8 子集） |
| `RecordLogCorruption` | 31 | 损坏异常 |
| **合计（main）** | **876** | |
| `LaneStateFoldTest` / `DeferredWriteFoldTest` / `ToolBatchFoldTest` / `TerminalFailureFoldTest` | 382 / 251 / 179 / 172 | 合计 **984** 行测试 |

**消费链只有一条**：

```
SessionPersistence.attach          (coding-agent)
  └─ SessionPersistence.restoreFromRecordLog
       └─ AgentHarness.restoreFromRecords
            └─ LaneStateFolder.fold ──► LaneOperationFold.fold ──► RecordLogValidator.validate
```

`AgentHarness.restoreFromRecords` 消费的字段只有七个：
`idle` / `runId` / `stepIndex` / `newestOwn` / 三个队列 / `pendingWrites`。
**`toolBatch()` 与 `terminalFailure()` 在 `src/main` 里零消费者**（`docs/29 §9.2`）。

## 3. 必须先修的缺陷：崩溃恢复在新驱动下整体不可用（P0）

折叠之外，第 5 步还压着一个**独立的、当前生效的缺陷**。实测（探针已跑，输出如下）：

```
lane phase after restore = OperationInfo[id=run-1, kind=run, status=suspended]
piEngine().run THREW IllegalStateException: Cannot start run: lane default is not idle
```

机制：

- 进程在运行中被杀 ⇒ 存储里留下一个只有 `OperationStarted`、没有 `OperationFinished` 的操作。
- `-c` / `-r` / `--web` 恢复 ⇒ `restoreFromRecords` 把 `lane.phase` 置为 **CHECKPOINT**。
- 旧驱动能收尾：步进链里有 `Action.TryFinishRun` → `FinishOperation`。
- **新驱动不能**：`PiLaneEngine.run` 第一句就是
  `if (!(lane.phase instanceof RunPhase.Idle)) throw ...`，而**没有任何东西**会替它收尾。

⇒ 崩溃后恢复的会话，**第一条 prompt 直接抛异常**。这条路径在切换驱动（`65d1285`）时就断了，
至今没有测试覆盖。**它必须与退休一并解决** —— 否则退休只会把这个洞藏起来。

## 4. 退休设计

### 4.1 恢复路径的新形态

`SessionPersistence.attach` 仍分两步，但第二步不再折叠：

| 步骤 | 旧 | 新 |
|---|---|---|
| 编排状态 | `restoreFromRecords` 折叠记录日志重建 | **不再重建** —— 见 4.2 与 4.3 |
| 显示上下文 | `seedTranscript(ContextEntries...)` | 不变 |

`AgentHarness.restoreFromRecords` 收窄为 `restoreRecords(laneName, records)`：

- `lane.records` ← 持久化记录（**保留**：`persistPending` 的去重靠 `persistedRecordIds`，
  可观察性也读它）
- `lane.phase` ← **IDLE**（4.2 保证存储侧已无未闭合操作）
- `lane.abortSignal` ← 新建（保持可中止）
- `lane.partial` ← `null`
- 三个队列 ← **空**（4.3）
- `lane.pendingWrites` ← **空**（4.3）
- `runId` / `stepIndex` / `newestOwn` ← **不再从日志推**：`runId` 由下一次
  `OperationStarted` 赋予；`stepIndex` 是 `PiLaneSink` 的实例字段、每次驱动从 0 起；
  `newestOwn` 由 `HarnessUtils.deriveNewestOwn(lane)` 在首条 entry 落盘时重算。

`SessionPersistence.restoreFromRecordLog` 随之失去 `anchor` / `ownEntries` /
`configurationEntries` 三段切片计算 —— 折叠没有了，切片就没有意义。

### 4.2 崩溃收尾（settle）落在 `SessionPersistence.attach`

存储只拦「同一车道第二个 `OperationStarted`」
（`JsonlSessionStorage:191-197`），**允许**为已开操作补一条 `OperationFinished`。
所以收尾可以、也应该在恢复边界完成：

```java
var open = opened.findOpenOperations(lane, 1);      // Session.findOpenOperations 已存在
if (!open.isEmpty()) {
    opened.appendRecord(new NewRecord<>(new LaneRecord.OperationFinished(
        UUID.randomUUID().toString(), 0, lane, null,
        open.getFirst().id(), OperationOutcome.ABORTED, null, null)));
}
```

三个选择及理由：

- **`ABORTED` 而非 `FAILED`**：进程被杀是中止，不是失败。`HarnessUtils.determineOutcome`
  对 aborted 保持 `"aborted"`，正是为了不把用户可见的停止污染成 `faulted`。
- **放在 coding-agent 而非 agent-core**：收尾要写存储，而 `Session` 在会话侧。
  agent-core 只负责「把车道恢复到可驱动」。
- **不尝试续跑未完成的操作**：pi 的 `OperationState` 是「整份替换的持久重启点」，
  恢复**不从半途继续**。这与 pi 一致，也让恢复路径不需要理解中断时刻的状态。

### 4.3 有意丢弃的三样东西

| 丢弃 | 理由 |
|---|---|
| 三个队列（steer / followUp / nextRun）的重建 | pi 语义下队列是**进程内**的：重启即丢。重建还会把「崩溃前的半句话」重新注入下一次运行，指向不可预期的上下文 |
| `pendingWrites` 的重建 | pi **没有** `pendingWrites`，且 `docs/22 D3` 已实证「派生不可靠」（折叠出的集合里的 entry 永不被写回，见 `AgentHarness:376-382` 的既有注释）。`docs/28 §6` 已记「选项 C 下 `pendingWrites` 整体消失」 |
| 损坏检测（`RecordLogCorruption`） | 它是**折叠的**守卫：不折叠就没有「折叠到一半发现日志不自洽」这个状态。`JsonlSessionStorage` 自己的写入门禁（重复 id、双开操作）仍在，未失去任何输入校验 |

> ⚠️ **本步不动 `pendingWrites` 机制本身**（仍由 `PiLaneSink.append` 维护、`finishRun` 清理），
> 只停止「从日志重建它」。整体退休归 `docs/27 §5.2`，属另一步。

## 5. 实施步骤（提交粒度）

每个 Step 跑 `mvn -o -am -pl pi-java-agent-core clean verify`（涉及 coding-agent 时加该模块），
再显式 `git add <path>` 提交。

### Step 1 — `fix(coding-agent): settle an operation left open by a crash on resume`

- `SessionPersistence.attach`：在 `restoreFromRecordLog` **之前**补 4.2 的收尾
- 新增测试 `SessionPersistenceResumeTest`：造一个「只有 OperationStarted」的会话，
  `attach` 后断言 ① 存储里该操作已闭合 ② `piEngine().run(...)` **不再抛异常**
- **独立可落地**：不依赖后面任何一步，且单独修好了 §3 的 P0

### Step 2 — `refactor(agent-core): restore lane records without folding the log`

- `AgentHarness.restoreFromRecords` → `restoreRecords(laneName, records)`，按 4.1 收窄
- `SessionPersistence.restoreFromRecordLog` 删掉三段切片计算
- 更新 `PiLaneEngineTest` 的两个折叠用例 → 改为断言「恢复后车道 IDLE 且可驱动」

### Step 3 — `refactor(agent-core): retire the record-log fold`

- 删 `LaneStateFolder` / `LaneOperationFold` / `RecordLogValidator` / `RecordLogCorruption`
- 删 `LaneStateFoldTest` / `DeferredWriteFoldTest` / `ToolBatchFoldTest` / `TerminalFailureFoldTest`
- 修悬挂引用：`HarnessUtils:95`、`QueueManager:90`、`CompactionExecutor:133` 的 javadoc
- `AgentHarness.restoreFromRecords` 的调用点（若有遗留）一并对齐

### Step 4 — `docs(agent-core): retire docs/21 and docs/22 with stop banners`

- `docs/21` / `docs/22` 加停止横幅（此前只有 `docs/23` / `docs/26` / `docs/23c` 有）
- `docs/28 §5` 第 5 步标注完成，指向本文

## 6. 验收

- `mvn -o clean verify` 全 reactor 绿（14 模块）
- `docs/28 §5` 第 5 步判据「run summary 相关测试保持通过」——`RunSummaryAggregatorTest`
  与 `PiLaneEngineTest` 的 run summary 断言全绿
- **崩溃恢复**：`SessionPersistenceResumeTest` 断言恢复后可驱动（§3 的 P0 关闭）
- 无 `@SuppressWarnings` 新增、无 `System.out.println`、文件 ≤ 500 行、checkstyle 零违规

## 7. 未覆盖与遗留

| 项 | 说明 |
|---|---|
| 恢复后不再还原队列 | 有意，见 4.3；若某消费者依赖它，需要单独的证据与设计 |
| `pendingWrites` 机制本体 | 仍在（`docs/27 §5.2`），本文只停掉「从日志重建」 |
| `WriteDeferred` 记录 | 保留发射（纯旁路审计），但退休后**无任何折叠消费者** |
| 中断时刻的部分工具输出 | 与 pi 一致：不从半途续跑，下次运行从已落盘的 entry 重建上下文 |
| **日志不变量不再有生产端守卫** | `RecordLogValidator` 的规则（未知操作号、finish 之后的记录、非连续 attempt、队列取消无对应入队…）此前只在**恢复时**执行 —— **不是写入门禁**。退休后它们**只在测试里**被钉住（`PiLaneEngineTest.assertLogIsWellFormed` 覆盖前两条 + attempt 连续性）。`QueueManager` / `CompactionExecutor` 的相关注释已改为「无人校验，但日志仍应保持诚实」 |
| 消费端 `RunSummaryAggregator` 未受影响 | 它读的是 `StepAttempt` / `ToolFinished` / `UsageRecord` 的**发射**，与折叠无关；两个折叠用例改写为直接断言日志后仍覆盖它 |
| `docs/22` 的 #2/#3 | `toolBatch` / `terminalFailure` 随折叠链删除，`docs/22` 已加横幅说明作废 |
