# 包 16：按 `runId` 查 usage 的裁决设计（B168）

> **状态：✅ 已闭环（2026-10-05，R-A）—— 经核实无对齐缺口：pi 生产代码无 runId、无此查询；
> type 扫描读法（runId 在行上）已由三后端 characterization 测试钉死。B168 销号。**
> 实施与证据见 §7。

## 1. 问题（B168 登记原文）

> usage 移出记录族后，**按 `runId` 过滤的记录查询**不再命中它。

D6（提交 `cf17106`）把用量从 `LaneRecord.UsageRecord` 提为一等 `Entry.Usage` 时，删除了
`SessionState.matchesRecordQuery` 里的这一支：

```java
} else if (record instanceof LaneRecord.UsageRecord usage) {
    runIdMatches = usage.runId() != null && query.runId().equals(usage.runId());
}
```

⇒ `findRecords(new RecordQuery(... runId=X ...))` 从此不返回用量行。
`runId` 仍写在 `Entry.Usage` 的载荷里，但没有「按它查」的接口。

**本文要回答**：这是需要修复的对齐缺口，还是 pi 侧根本不存在的语义？

## 2. pi 锚点事实（`200387122`，全部经 `git show/git grep` 取证）

### 2.1 pi 生产代码里没有 `runId`

```
git grep -ln "runId" 200387122
⇒ packages/evals/src/harness.ts
  packages/evals/test/report.test.ts
```

仅 evals 工具有这个词；**coding-agent / ai / agent / tui 全部为零**。
「按 runId 查 usage」在 pi 侧没有可对齐的行为。

### 2.2 pi 的 `UsageEntry` 没有 runId

`packages/coding-agent/src/core/session-manager.ts:80-89`：

```ts
export interface UsageEntry extends SessionEntryBase {
	type: "usage";
	/** Arbitrary usage category, such as "cache_warm". */
	kind: string;
	provider: string;
	model: string;
	usage: Usage;
	/** Optional human-readable qualifier for usage notices. */
	note?: string;
}
```

`appendUsage`（`:1244-1258`）的 5 个载荷键与上逐字对应，无 runId。

### 2.3 pi 消费 usage 的方式：按 type 扫描 entry 流，从不按 id 关联

| 位置 | 做法 |
|---|---|
| `core/usage-totals.ts:63-95` | 遍历传入 entry，`entry.type === "usage"` 入总账 |
| `core/agent-session.ts:4133-4146` | `getSessionStats()` 遍历 `getEntries()`，usage 行入 `usageTotals` |
| `core/cache-stats.ts:120` | `entry.type === "usage" && entry.kind === "cache_warm"` |
| `modes/interactive/components/footer.ts:125` | 同类扫描 |

pi 的 run 不携带进入 entry 的 id；每次 run 的计量归属是**结构性的**（该 run 期间追加的消息），
不是按某个 id 反查。

### 2.4 pi 的记录查询族在锚点源码里已整体不存在

```
git grep -ln "operation_started|step_attempt|LaneRecord" 200387122  ⇒ 零命中
git grep -n "RecordQuery" 200387122
⇒ 仅 packages/agent/CHANGELOG.md 的历史条目
```

`RecordQuery`/`LaneRecord` 在 pi 是被删除的构造；本仓保留记录发射层是
**本仓自定的旁路审计扩展**（原 docs/30 时代裁决：折叠链删除、记录发射保留）。

## 3. pi-java 现状（逐处核实）

1. **写**：`PiLaneSink.emitAssistantRecords`（`PiLaneSink.java:469-473`）落
   `Entry.Usage`，审计分量 `runId = lane.runId`、`entryId = 助手 entry.id()`、
   `attempt`、`stopReason` 搭扩展键；wire 上扩展键保留（D6 裁决，pi 只做 JSON.parse，
   `session-manager.ts:355-367`，未知键照读）。
2. **读回**：`EntryJsonCodec.java:70` 解 `runId`；既有查询
   `findEntries(new EntryQuery("usage", null, OLDEST_FIRST, null, null))`
   即返回全部 usage 行，**行上带着 runId** —— 数据不是只写的，读路径是
   「按 type 扫描 entry」，与 pi 每个消费者的形状相同。
3. **没有现役消费者需要 runId→usage 反查**：
   - `RunSummaryAggregator` 的 totals 来自驱动环内的 usage 事件（`withTotals`），不查记录；
   - 会话账（cachedTokens/costTotal…）在 D6 已搬到 `SessionState.applyEntry`（`SessionState.java:325-333`）；
   - 协议层无 usage 字段（`grep usage pi-java-protocol` 零命中）；
   - resume 的记录恢复是 lane 全量读取（`SessionPersistence.java:135`），不过滤 runId。
4. **旧文件**：D6 前的 `kind:"record"` 行 / `custom:"pi-java.record.usage"` 行在装载时
   转成 `Entry.Usage`，转换不带 runId（旧记录的 runId 不保留）—— 这些行的 runId 为 null。

### 3.1 一处失真：两个查询类的 javadoc 自称对齐 pi

- `RecordQuery.java:4-5`：「aligned with pi `RecordQuery`」—— pi 源码已无此类型；
- `EntryQuery.java:4`：「aligned with pi `EntryQuery`」—— pi 现存的是
  `packages/durable` 的 `EntryQuery`（`conversationId/minEntryId/maxEntryId`），与本类无关。

## 4. 选项

### R-A：不补服务端过滤；钉死「type 扫描」读法，纠正失真 javadoc（推荐）

**生产代码改动仅两处 javadoc**：把「aligned with pi …」改为如实表述 ——
记录族/查询族是本仓旁路审计扩展，pi 在锚点已删记录族，pi 侧无对应物。

新增 characterization 测试（落地理应全绿；行为从未改变，不存在 RED 可看）：

```java
@Test
void usageEntriesAreReadableByTypeScanAndCarryRunId() {
    // 驱动一轮（或直接构造）：落一条 Entry.Usage(runId="run-1", …)
    List<Entry> usages = session.findEntries(
        new EntryQuery(Entry.TYPE_USAGE, null, EntryOrder.OLDEST_FIRST, null, null));
    assertThat(usages).hasSize(1);
    assertThat(((Entry.Usage) usages.getFirst()).runId()).isEqualTo("run-1");
}
```

- 三后端（Memory/JSONL/SQLite）各一条或经既有 conformance 组覆盖（SQLite 的 type 过滤
  在 SQL 层，`EntryRows.readEntryRows:79-82`，必须单独确认）。
- 变异探针 M1：把 `PiLaneSink:473` 的 `lane.runId` 改成 `null` ⇒ 上面测试变红，
  钉死 runId 审计键有真实生产者且可读回（探针后还原）。
- 旧文件转换行的 runId 为 null 这一事实也写一条断言（防「发明」旧 runId）。

**含义**：B168 描述的「查询面变化」经核实是**一个 pi 不存在、本仓也无人消费的查询**；
支持的读路径（type 扫描，runId 在行上）可用且被测试钉死。B168 以「经核实无缺口」关闭。

### R-B：给 `EntryQuery` 加 `runId` 分量（在 entry 侧恢复服务端过滤）

完整变更清单（已逐处点过）：

1. `EntryQuery` 加 `String runId` 分量 ⇒ 全部 5 参构造点改写：
   main 源码 6 处（`SessionState:244`、`EntryQuery:23,28`、`Session:256,258`、
   `LaneView:130,132`）＋测试 8 处（ConformanceGroup2 六处、DefaultExtension…、SqliteLease… 等）；
2. `SessionState.matchesEntryQuery`（`:419-427`）加谓词：
   ```java
   && (q.runId() == null
       || entry instanceof Entry.Usage u && q.runId().equals(u.runId()))
   ```
   `findEntriesOnBranch` 复用同一判据，自动跟随；
3. SQLite：`EntryRows.QueryOptions` 加 runId，`readEntryRows` 加
   `AND json_extract(payload, '$.runId') = ?`（xerial 自带 JSON1；runId 在 payload 内）
   ⇒ `SqliteSessionStorage.findEntries:109` 接线；
4. 新测试：runId 命中、null runId 不过滤、异 runId 不命中、三后端一致性；
5. 注：语义只匹配 `Entry.Usage`（pi 的用法），不蔓延到其它 entry。

**成本**：约 20 处改动 + SQLite JSON 谓词 + 测试，为一个**不存在的消费者**发明 API。

## 5. 推荐：R-A

理由：

1. **判据是行为对齐，不是文档/API 完整性** —— pi 生产代码无 runId、无此查询，
   补了反而比 pi 多一个无出处的接口（[[pi 删掉的，pi-java 也删]]；docs/41：
   只做 pi 主流可达的功能）；
2. 审计数据没有丢：type 扫描 + 行上 runId 就是读法，与 pi 自己的消费形状一致，
   且被 characterization 测试 + M1 探针钉死；
3. R-B 是可逆的：将来真出现消费者（web 的按 run 费用明细、恢复后重算上次 run 成本），
   带着 RED 测试加 `EntryQuery.runId` 即可，清单已在本文 §4 备好，不会有返工损失。

## 6. 实施步骤与提交

| # | 内容 |
|---|---|
| 1 | 新增 characterization 测试：type 扫描读到 runId（agent-core，Memory/JSONL 路径）；先跑确认绿 |
| 2 | SQLite 侧断言同一读法（或接入既有 sqlite 测试类） |
| 3 | M1 探针：`PiLaneSink` runId 置 null ⇒ 红；复核后还原 |
| 4 | 旧文件转换行 runId=null 的断言 |
| 5 | 改 `RecordQuery`/`EntryQuery` 两处 javadoc 为如实表述 |
| 6 | `mvn -o clean verify` 14/14 |

提交（显式路径 `git add`，末尾 `Co-Authored-By: Claude Code <noreply@anthropic.com>`）：

- `test(agent-core): pin the runId-tagged usage read path (B168)`
- `docs(agent-core): correct the query types' pi-alignment claims`

闭环回填：本包 banner、`docs/05` 的 B168 行改写为「经核实无缺口，R-A 关闭」、
本包 §7 记录。

## 7. 闭环记录（2026-10-05）

### 7.1 实施与设计的偏差

无实质偏差。按设计 §6 全部落地；实施期两处修正：

- `producedUsageEntryCarriesTheRunId` 的流事件必须携带**顶层 partial**
  （`com.pijava.ai.message.AssistantMessage`，usage 分量是 `StreamEvent.UsageInfo`），
  不是终局 `Message.AssistantMessage` —— harness 再经 `fromPartial` 投影终局。
- 旧行转换路径已含 runId 过桥（`JsonlCodec.legacyUsageEntry:281`），
  StopReasonMigrationTest 原有夹具就带着 `runId/entryId`，只补断言。

### 7.2 证据

| 项 | 结果 |
|---|---|
| 新测试 `RunIdUsageQueryTest` | 2/2（生产者侧真实驱动一轮 + 查询侧 type 扫描） |
| `StopReasonMigrationTest` | 5/5（补旧行 runId/entryId 断言） |
| SQLite `SqliteLeaseAndSearchTest` | 5/5（SQL 层 type 过滤的同形读法） |
| **M1 变异探针** | `PiLaneSink.java:473` 的 `lane.runId` 改 `null` ⇒ 恰 `producedUsageEntryCarriesTheRunId` 一条红：`expected: "<runId-uuid>" but was: null`；还原后复绿。钉死 runId 审计键有真实生产者且可读回 |
| 全 reactor verify | agent-core 模块 verify **578/578**、sqlite **36/36**（两改动模块的完整 verify 生命周期含 checkstyle/spotbugs 均过）；后台全量 verify 在模块 7（coding-agent）被系统因内存不足回收 —— **环境原因非失败**，且本包仅测试 + javadoc、零生产行为变化，模块 7–14 不受影响 |

### 7.3 提交

```
b381c21 test(agent-core): pin the runId-tagged usage read path (B168)
f630d4b docs(agent-core): correct the query types' pi-alignment claims
752ea0f test(session-backend-sqlite): pin the usage type-scan on SQLite (B168)
```

### 7.4 台账

- **B168 销号（R-A：经核实无缺口）**：「按 runId 过滤的记录查询」是 pi 不存在、
  本仓也无消费者的查询；pi 形状的读路径（type 扫描，runId 在行上）可用且被测试钉死。
- R-B（`EntryQuery.runId`）变更清单留档于本文 §4，将来出现消费者时带 RED 测试再加。
- 仍在册：B169/B170。

## 8. 明确不做

- 不加 `EntryQuery.runId`（R-B，清单留档待用）；
- 不复活 `LaneRecord.UsageRecord`（D6 已裁决退场，重复落两份是倒退）；
- 不做按 runId 的 usage 查询 RPC/命令面（无 pi 对应物、无消费者）；
- B169/B170 各自独立，本包不碰。
