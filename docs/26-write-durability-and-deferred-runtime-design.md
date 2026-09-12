# 26 — 写入清偿（pendingWrites）与 deferred 运行时

> 目标：修掉 `docs/22` 遗留的两处**功能缺失**，对齐 pi 的 **spec**（`packages/agent/docs/harness-v2.md`
> 与 `harness-v2-state-machine.md`）。
>
> **⚠️ 对齐口径（必读）**：这两项在 pi 侧**只有 spec，没有运行时** —— pi 的 `AgentHarness` 是
> `HarnessNotImplemented` 脚手架（`agent-harness.ts:355-357/413-417`），`apply_pending_write` 与
> `fetch_deferred`/`cancel_deferred` 只有 `ActionInfo` 类型、无生产者无执行者，
> `write_deferred` 记录**零生产者**，`fetchDeferred`/`cancelDeferred` 只有 faux 测试 provider 实现。
> 因此本文的对齐对象是 **pi 的 spec 文本**，不是 pi 的代码。
>
> 关联：`docs/21`（record 日志折叠）、`docs/22`（deferred 写入与 entry 溯源，§3.4 的声明本文修正）、
> `docs/23`（turn/tool 生命周期事件）。裁决记录：**A4 新增 `EntryDropped` 记录**；**A 与 B 同批实施**。

---

# Part A — `pendingWrites` 清偿（缺陷 a）

## A.0 现状与缺陷

| 落点 | 事实 |
|---|---|
| `SessionPersistence.java:41` | 唯一的写入循环遍历 **`snapshot.transcript()`**，从不看 `pendingWrites` |
| `AgentHarness.java:368-371` | 恢复时把 fold 的 owed writes 灌回 `lane.pendingWrites`，**不加进 transcript** ⇒ 这是「只存在于该列表的 entry」的唯一来源 |
| `ActionExecutor.java:327-330` | `executeApplyPendingWrite` **只从列表移除**，没有任何写入调用 |

**契约与实现不符（代码级）**：`Action.ApplyPendingWrite` 的 javadoc 写「Persist a provisioned entry to
storage（pi `apply_pending_write`）」，实现只做出列。pi 的 spec 定义该动作是**三步**：
「emit lifecycle → **append the entry** → remove from the pending set」（`harness-v2.md:2593`）。

**live run 碰巧安全**：每个 `pendingWrites.add` 都伴随 `transcript.add`（7 个写入点皆如此），run 结束由
`persistPending` 统一 flush。缺陷只在**崩溃恢复**链路发作，且**自续**：一旦出现一条 owed write，
之后每次 flush 都写 `write_deferred` 记录、不写 entry ⇒ 永远欠着；而恢复时循环发 `ApplyPendingWrite`
把 entry 出列、无人重写 ⇒ **该写入静默丢失**（`AgentHarness.java:361-367` 的注释已如实写明）。

## A.1 端口：`EntryPersister`（agent-core 定义，coding-agent 实现）

```java
// com.pijava.agent.harness.EntryPersister（agent-core，public）
@FunctionalInterface
public interface EntryPersister {
    /** 幂等持久化一条 entry：同 id 重复调用不得重复写入。失败必须抛出，调用方据此不出列。 */
    void persist(String lane, Entry entry);
}
```

- `AgentHarness` 增 `void attachEntryPersister(EntryPersister p)`（或并入现有的 attach 路径）；
  `ExecutionContext` 增加对应组件（可空）。
- **可空是关键**：agent-core 在没有 session 时（独立使用/测试）保持今天的行为（出列），不会因为缺端口而死锁。

## A.2 `ApplyPendingWrite` 真正持久化

```java
private Action executeApplyPendingWrite(LaneState lane, Action.ApplyPendingWrite apw) {
    var entry = lane.pendingWrites.stream().filter(e -> e.id().equals(apw.entryId())).findFirst().orElse(null);
    if (entry != null && ctx.entryPersister() != null) {
        ctx.entryPersister().persist(lane.laneName, entry);   // 抛异常 ⇒ 不出列，保留欠写
    }
    lane.pendingWrites.removeIf(e -> e.id().equals(apw.entryId()));
    return peekAction(lane.laneName);
}
```

配套（**必须**，否则写重）：`SessionPersistence` 抽出单条持久化入口，**共用一个** `persistedEntryIds`
水位（`AgentSession.java:100-105`）：

```java
// SessionPersistence：attach 时注册；persistPending 复用它（不再各写一份）
static void persistOne(AgentSession owner, Session<?> session, String lane, Entry entry) {
    if (owner.persistedEntryIds().add(entry.id())) {          // 幂等：见过的 id 直接跳过
        session.appendEntry(new ProvisionedEntry<>(entry), lane);
    }
}
```

> **不加这层去重会炸**：SQLite 的 `appendEntry` 有 `assertUnusedId`，run 末 flush 会因重复 id 抛错。

## A.3 run 末 flush 的写入源 = `transcript ∪ pendingWrites`

`SessionPersistence.java:41` 的循环改为并集（安全网：兜住「加入时不在 transcript」或「未走到 drain」的条目）：

```java
var snapshot = harness.snapshot(laneName);
var toWrite = LinkedHashSet<Entry>();       // 按 id 去重，保持 transcript 顺序在前
toWrite.addAll(snapshot.transcript());
toWrite.addAll(snapshot.pendingWrites());   // LaneSnapshot.pendingWrites 已存在（LaneSnapshot.java:27）
for (var entry : toWrite) persistOne(owner, session, laneName, entry);
```

`LaneSnapshot.pendingWrites` 目前**零消费者**，此改动即它的第一个消费者。

## A.4 恢复重放

`AgentHarness.restoreFromRecords`（`:368-371`）**保持不变**（仍只灌 `lane.pendingWrites`，不动 transcript
—— 补回 transcript 会复活重试/compaction 已丢弃的 entry）。落地 A.2 后，驱动循环的 `ApplyPendingWrite`
会自动把 owed write 写入存储并出列 ⇒ **清偿闭环**。

`persistedEntryIds` 在 attach 时已从存储重建（`SessionPersistence.java:73-74`），因此 owed entry 的 id
必然不在水位里 ⇒ `persistOne` 会真正写入（幂等性成立）。

## A.5 新增 `LaneRecord.EntryDropped`（裁决：新增，不删旧记录）

**为什么必须有**：pi 的 owed 谓词（`write_deferred` 记录存在 ∧ 目标 id 不在 entries 里）在
**从不删除 entry** 的模型里成立；pi-java 会删 entry（`docs/22 §3.4` 的「泄漏」），同谓词会把
**被有意丢弃、且从未持久化**的 entry 判为 owed ⇒ 恢复时把它写回存储，复活一条已被丢弃的消息。
另外「entry 被删除」这件事**现在日志里完全不可见**，本身是审计漏洞。

```java
// LaneRecord 新增变体（第 12 个）
/** 某些 entry 被有意从 lane 树中移除（重试回滚 / compaction 替换 / lane 迁移）。 */
record EntryDropped(
    String id, long seq, String lane, Instant timestamp, String runId,
    List<String> entryIds, DropReason reason
) implements LaneRecord {}

public enum DropReason { RETRY, COMPACTION, LANE_MOVE }   // 纯常量闭集 ⇒ enum（@JsonValue 输出小写串）
```

**三个发射点**（都在删除处、删除动作之后立刻发）：

| 位置 | 现状 | 改动 |
|---|---|---|
| `AgentHarness.dropTrailingErrorAssistant`（`:452-467`） | `entries.remove(size-1)`，**不发任何记录** | 发 `EntryDropped(List.of(id), RETRY)` |
| `CompactionExecutor`（`:86-87`） | `transcript.clear()` + `addAll(compacted)` | 对「被移除且未出现在 `compacted` 中」的 id 发一条 `EntryDropped(ids, COMPACTION)` |
| `AgentHarness.moveLane`（`:222-223`） | 源 lane `transcript.clear()` | 对移出的 id 发 `EntryDropped(ids, LANE_MOVE)`（语义：本 lane 树中已不存在；目标 lane 不重建 owed，其 entry 由存储承载） |

`ActionExecutor.reset()`（`:133` 的 `transcript.clear()`）**不发** —— 它是整 lane 重置，`records` 同处清空
（`:135`），日志整体归零，不存在欠写残留。

**fold 谓词修正**（`LaneOperationFold.pendingWrites`，`:117-137`）：

```java
// owed = write_deferred ∧ target 不在 entries 中 ∧ 该 id 未被本 operation 的 EntryDropped 覆盖
var dropped = operationRecords.stream()
    .filter(LaneRecord.EntryDropped.class::isInstance)
    .flatMap(r -> ((LaneRecord.EntryDropped) r).entryIds().stream())
    .collect(Collectors.toSet());
... && !ownEntryIds.contains(id) && !dropped.contains(id)
```

同步改动面（Phase 21 加 `QueueConsumed` 时的同款清单）：`LaneRecord`（`type()` / `committed()` /
`@JsonSubTypes`）、`RecordJsonCodec`、`SessionState.matchesRecordQuery`、`SqliteCodecs.recordRunId`、
`RecordLogValidator`（新变体至少不报 `unknown_operation`）、`LaneOperationFold`。

## A.6 验收（Part A）

- **清偿闭环**：构造「`write_deferred` 记录已落库、entry 未落库」的存储，恢复后驱动到收敛，
  断言该 entry **出现在存储里**、且 `pendingWrites` 为空（今天这个用例是 `DeferredWriteFoldTest:203`
  的 `never-persisted` id —— 现状是断言它**永不落库**，本项翻转该断言）。
- **幂等**：同一 owed entry 连续恢复两次，存储中只有一条（`persistedEntryIds` 生效）。
- **不复活**：`dropTrailingErrorAssistant` 之后落库 → 恢复 → 断言被丢弃的 error assistant
  **没有**被写回（`EntryDropped(RETRY)` 生效）。
- **不重复写**：run 末 flush 与 drain 都跑过，SQLite 不抛 `assertUnusedId`。
- **快照**：`LaneSnapshot.pendingWrites` 在 owed 状态下非空、清偿后为空。
- **回归**：`DeferredWriteFoldTest` 的 5 条断言中，泄漏相关的两条改为「声明为 owed 但因
  `EntryDropped` 被排除」；`docs/22 §3.4` 的「本阶段不做」段落改为指向本文。

## A.7 交付顺序（Part A）

1. `EntryDropped` 变体 + 三个发射点 + codec/validator/fold（**可独立交付、独立测**）
2. `EntryPersister` 端口 + `ApplyPendingWrite` 持久化 + `persistOne` 去重
3. flush 源并集
4. 恢复闭环测试（A.6 的前四条）

> 1 与 2/3 无依赖，可并行；4 依赖全部。

---

# Part B — deferred 运行时（缺陷 b）

> 规则一律取自 pi 的 spec：下称 **H2** = `pi/packages/agent/docs/harness-v2.md`、
> **SM** = `harness-v2-state-machine.md`、**T** = `pi/packages/ai/src/types.ts`、
> **F** = `pi/packages/ai/src/providers/faux.ts`。**pi 无运行时可比**：`AgentHarness` 是
> `HarnessNotImplemented` 脚手架，`fetch_deferred`/`cancel_deferred` 只有类型，
> `fetchDeferred`/`cancelDeferred` 只有 faux 实现。所以本 Part 是「照 spec 补一个连 pi 都还没写的运行时」。

## B.1 挂起态：不新增记录，由 fold 派生

spec（H2:964）：**「存储把有意挂起与崩溃表示成同一件事：一个开着但源未被赎回的 operation」**；
`run_suspend` 不算状态，`LaneState.operation.deferred` 才是事实（H2:1020）。

pi-java 已有全部素材：`LaneOperationFold` 的 `deferred` 派生（最新 own entry 是 `stopReason=deferred`
的助手消息 ⇒ 取 handle）+ `OperationStarted` 未闭合。因此：

| 项 | 设计 |
|---|---|
| 新增 phase | `RunPhase.Suspended` |
| 挂起判定 | `HarnessUtils.determineOutcome` 增加 `"deferred"`（**今天它被折叠成 `completed`**，是首要修正点） |
| 收尾点行为（`docs/23 §4.3`） | `status="deferred"` ⇒ **不发 `turn_end`、不跑钩子、`turnOpen` 保持 true**，置 `phase=Suspended`，返回 `null` 结束本次 drive（operation 保持打开） |
| 为什么不发 `turn_end` | pi 的 `Park` 在 `turn_end`(:224) **之前** unwind（`redeemDeferred` 在 `runTurn` 内抛 `Park`）⇒ pi 结构上就不发；且「ready 继续同一轮」要求本轮未收尾 |
| `LaneInfo.OperationInfo.status` | `"suspended"` 今天只在 Checkpoint（恢复态）出现（`SnapshotService:82-84`）⇒ 改为「有未赎回 deferred 源」时也报 suspended |
| 不变量 | `LoopInvariants` 允许 `Suspended` 下返回 `null`（parked）；该 phase 唯一合法的产出是 `Action.FetchDeferred` 或 abort 引发的 `FinishOperation` |

## B.2 赎回步 F（stable deferred-fetch step）

spec（H2:116/944/964）：**一个原始 deferred 响应只对应一个稳定的 fetch 步**，`resume()` 首次创建它时
**一次性拷贝**生成步的配置与归一化重试策略，之后各进程只读 F 的副本；出现第二个 fetch 步是**损坏**（H2:534）。

pi-java 落地（不需要 pi 的 `stepId`：记录的 `(runId, step, attempt)` 已能唯一定位步骤）：

- 新增 `Action.FetchDeferred(String laneName)`（**不**复用 `StreamAssistant`：spec 明确区分
  「generation step」与「deferred-fetch step」——后者不发起新生成）。
- 新增 `StepKind.DEFERRED_FETCH`；`LaneRecord.StepAttempt` 增加两个可选字段：
  `String sourceEntryId`（本次 poll 赎回的源）与 `List<String> activeTools`（**仅 F 的首次 attempt 写**，
  即「拷贝一次」；后续 attempt 留空，读侧回溯首个）。
- per-source 中断上限用 `RetryPolicy.maxAttempts` 的**拷贝**（见 B.4）。
- `RecordLogValidator` 增加两条：`deferred_fetch` 步在「原始 deferred 响应与其后续 pending 响应结算完」
  之前不得出现第二个；`attempt` 在同一 `(runId, step)` 内连续（既有规则已覆盖）。

## B.3 一次 poll 的原子序列（严格按此顺序）

spec（H2:934-962 的 trace、H2:120/516、H2:3408-3350）：

```
1. step_attempt(DEFERRED_FETCH, attempt=n, sourceEntryId=当前源, responseEntryId=预分配, usageRecordId=预分配)
2. fetchDeferred(model, handle, { wait: 0 })      ← 每次 resume 至多一次（H2:968）
3. 把响应 entry 写进 transcript（**预分配 id**）+ 立即落库（→ Part A 的 EntryPersister）
4. 写入 usage 记录（cause=deferred_fetch，预分配 id；**任何分类之前**）
5. 分类 → 按 B.4 结算
```

- `wait` **恒为 0**（H2:2557/3346/3866/4547）：一次状态检查，不阻塞。
- **第 3 步同时必须把 `lane.partial` 换成取回的那条助手消息**（含内容、`stopReason`、`deferred`）——
  `docs/23 §4.3` 的收尾点与两个钩子读的都是 `lane.partial`，② 的工具抽取也读它。不换的话：
  ready 路径的工具调用会被丢掉、`turn_end` 携带挂起时的空消息、`turnHadTools` 为假而**提前结束 run**。
  pending/interrupted 换成新的 deferred 消息；terminal 换成 error 消息。
- 轮询**不产生** `retry_scheduled`/`retry_start`/`retry_end`（H2:968/1693）。
- `RetryPolicy` **不设** deferred 轮询的总次数上限（H2:353/964）：只用于 B.4 的 per-source 中断上限与退避。
- 源选择：**最新的未赎回 deferred 响应**（H2:39/187/1020）：pending 的响应即使 handle 相同也是**新源**，
  中断/未知的响应**保留原源**（H2:189、`StepAttemptRecord.sourceEntryId` 注释 H2:385-388）。

## B.4 四种结算

| 分类 | 判据 | 写入 | 之后 | 计数 |
|---|---|---|---|---|
| **pending** | `stopReason="deferred"` 且 **handle 完整相等** | 新 deferred entry + usage | **重新挂起**（源推进到这条新 entry） | 新源**重置** per-source 计数（H2:971） |
| **interrupted** | **无 abort 标记**的 `aborted` 响应 | aborted entry + usage（**不进 provider 上下文**） | 低于上限：保留原源，下次 `resume()` 先按拷贝的 `retryDelay` 退避，再做一次 poll；到上限：**失败** | 计入「命名该 `sourceEntryId` 的 attempt 数」（H2:3323-3328） |
| **ready** | 正常消息（`stop`/`tool_use`/`length`） | 正常 assistant entry + usage | **继续本轮**：有工具调用则用 F 拷贝的 active tools 跑工具批次，然后走 `docs/23` 的收尾点 | — |
| **terminal** | `stopReason="error"`（过期/未知/已消费）或 fetch **被拒绝**（均转成同形 error 消息，H2:2557/3868） | error entry + usage | **失败**（普通 failure drain）；**绝不发起替代生成**；已接受的 steer/follow-up 仍可开新一轮（H2:973） | — |

- `pending` 的完整相等判据（H2:3870）：`provider`/`modelId`/`api`/`id` 相等、`expiresAt`/`pollAfterMs`
  的**存在性与值**相等、`data` 的 **JSON 深相等**（对象键序无关、数组有序）。
  **实现注意**：`DeferredHandle.data` 是 `Map<String,Object>`，Jackson 反序列化后数值可能是
  `Integer`/`Long` 混用 ⇒ 比较前需规范化（否则「JSON 深相等」会假阴性）。
- 上限处的合成结算：无响应且 `attempts ≥ 拷贝的 maxAttempts` ⇒ 在该 attempt 的**预分配响应 id** 上
  合成 `aborted`（interrupted）并转为 `RunFailed`（H2:3323-3328/3357）：

```java
if (prior != null && prior.response() == null
        && sourceAttempts.size() >= copiedPolicy.maxAttempts()) {
    // 合成 interrupted 响应（写在 prior 的预分配 responseEntryId 上），随后失败
    return failWith(providerInterruptionError(syntheticInterrupted(source)));
}
```

## B.5 端口与 provider 面

| 层 | 改动 |
|---|---|
| `pi-java-ai` | `ChatApi` 增**可选** `fetchDeferred(StreamRequest, DeferredHandle, DeferredFetchOptions)` / `cancelDeferred(...)`（`default` 抛「provider does not support deferred responses」）；`StreamOptions` 增 `DeferredMode deferred`（`null` \| 窗口 `PT15M/PT1H/PT24H`）；`DeferredFetchOptions(int waitMs)`。**方法存在即能力信号**（H2:3824-3826） |
| provider 适配 | 真实 provider **一律不实现**（与 pi 相同：anthropic/openai 全无）；只有 faux 实现 |
| `FauxProvider` | 四个分类的参考形状（F:293-305/589-642）：pending = `content:[] + stopReason="deferred" + 原样 handle`；ready = 正常消息；terminal = `createErrorMessage`（含抛异常转 error 事件）；interrupted = `stopReason="aborted"` 且**无 abort 标记**。测试计数器：`deferredFetchCount`/`cancelledDeferred`/`pendingFetches`/`pollAfterMs` |
| `AssistantStreamExecutor` | 生产端：`:183-184` 现在**硬传 `null`** ⇒ 改为 `lane.partial.deferred()`，并令 `lane.partial.stopReason()` 原样保留 `"deferred"` |

## B.6 abort（挂起态）

spec（H2:975/831/3498-3520）：

1. 写 abort 标记；
2. **尽力** `cancelDeferred(最新源 handle)` —— provider 无可解析实现时跳过（H2:975/3523），失败**只记遥测**；
3. 应用 pending writes（→ Part A）；
4. **保留所有 deferred entry**，**不追加**任何助手消息；
5. `FinishOperation("aborted")`。

pi-java 落地：`RunPhase.Suspended` 下的 abort ⇒ 生产 `Action.FinishOperation("aborted")`（沿用既有收口），
其中「尽力取消」是一个可失败且被吞掉遥测的可选步骤。挂起态**不得**因 abort 而启动 fetch 步（H2:529-530）。

> **必须同时改 `docs/23 §4.4` 的收口兜底**：它的条件是 `if (turnOpen) 发 TurnEnded`，而挂起态下
> `turnOpen` 仍为 `true`（B.1）⇒ abort 时会补发一个 `turn_end`，与「挂起态不发 `turn_end`」矛盾。
> 兜底条件改为 **`turnOpen && phase != RunPhase.Suspended`**；挂起轮的收尾统一由赎回路径（ready → 正常收尾、
> terminal/abort → 失败或中止）负责。挂起**跨进程**时该轮的 `turn_end` 因此可能永不出现（B.9 已记）。

## B.7 复用既有件（无需新设计）

| 规则 | pi-java 现状 |
|---|---|
| deferred 消息**投影为零条**（H2:977/164） | ✅ `ContextEntries.NON_PROJECTED_STOP_REASONS` 已含 `"deferred"` |
| `stopReason="deferred"` ⇒ **必须**带 handle（`invalid_deferred_handle`，R:272-283） | ✅ `RecordLogValidator.validateDeferredHandles:212-221` |
| terminal failure 来源：`error`，或**到上限的**无标记 `aborted`（H2:1023/2733-2738） | ✅ `LaneOperationFold.terminalFailure` 的 `deferred_fetch` 分支 |
| `UsageCause.DEFERRED_FETCH` | 枚举已存在，**本项给它发射点**（B.3 第 4 步） |

## B.8 验收（Part B）

- **faux 端到端**：请求 deferral → 挂起（`RunPhase.Suspended`、`prompt` 返回 suspended 结果、
  **无 `turn_end`**）→ 三次 poll：pending、pending、ready → 断言 3 条 `StepAttempt(DEFERRED_FETCH)` +
  3 条响应 entry + 3 条 `usage(cause=deferred_fetch)`，且**只有一条** fetch 步、attempt 连续、
  最终一轮正常收尾（`turn_end` 此时才发）。
- **新源推进**：pending 响应成为下一次 poll 的 `sourceEntryId`；handle 相等但 entry 不同。
- **中断**：无标记 `aborted` ⇒ 保留原源 + 退避；到 `maxAttempts` ⇒ 在预分配 id 上合成 interrupted 并失败。
- **terminal**：provider 返回 error（或抛异常转 error）⇒ error entry 落库 + 操作 `failed`，
  **不发起替代生成**。
- **abort**：挂起态 abort ⇒ 取消被调用一次（可解析时）、所有 deferred entry 保留、
  无新助手消息、操作 `aborted`。
- **恢复等价**：挂起后**换进程**恢复 ⇒ 从日志重建 `Suspended` 且能继续 poll（H2:964 的「挂起与崩溃同表示」）。
- **完整相等**：`data` 键序不同的同构 JSON 判为相等；数值类型混用（Integer/Long）不产生假阴性。
- **投影**：deferred entry 不进入 provider 请求（既有断言 + 挂起期间不新增请求）。

## B.9 我们对 spec 沉默处的裁决（必须记，避免后续审计误读）

| spec 沉默点 | 我们的裁决 | 代价/风险 |
|---|---|---|
| 赎回 poll 的 **turn 归属**（SM:1092/1096 自认未决） | deferred 轮**不发 `turn_end`**，`turnOpen` 保持；ready 后在同一轮收尾 | 跨进程挂起时，订阅者可能只看到孤立的 `turn_end`（继承既有「零订阅者丢弃」语义，`docs/23 §3`） |
| `run_resume` payload 自相矛盾（H2:1599 vs 2912） | 不实现 `run_resume` 事件（pi-java 无该事件族） | 无 |
| `deferred.window`（`15m`/`1h`/`24h`）语义未定义 | 只做透传：`StreamOptions.deferred` 带窗口，**不解释**；provider 自行映射 | 与 pi 同 |
| 同轮多个 handle 并存 | 只支持「一个原始 deferred 响应 + 其 pending 链」；第二个 fetch 步判损坏（H2:534） | 与 pi 同 |
| 无标记 `aborted` 的 cap 是 per-source | 采用 `RetryPolicy.maxAttempts` 拷贝（H2:344-346） | 与 pi 同 |
| handle 存在但 `stopReason != deferred` | **不校验**（pi 也未强制，R:272-283 只查单向） | 与 pi 同 |
