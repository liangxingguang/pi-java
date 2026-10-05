# 包 17：压缩切点改读 edit 后投影（B169）

> **状态：✅ 已闭环（2026-10-06，R-A）—— B169 销号。commits `3eab2da` / `42a3742`。**

## 1. 问题

B169（`docs/05`）：pi-java 的压缩切点按**原始 transcript** 累加字符估算，
pi 的生产路径按 **context_edit 剔除/替换后的投影** 累加：

- 被 omission edit 剔除的消息（失败助手、其工具结果）在 pi-java 切点里仍按原尺寸占位；
- 被 replacement edit 替换的消息按原文尺寸而不是替换后尺寸；
- 切点候选仍把已剔除条目当合法切点；
- 送进摘要生成器的「被丢弃消息」是**原文**——失败助手的文本可能被摘要污染；
- `tokensBefore` 信的用量锚点可能早于后来的 edit（pi 已专门修掉）。

台账原分析称「仅摘要前缀的条目边界略有差别、触发面窄」。本次取证修正两点：
**摘要输入污染是模型可见差异**（不只边界），且 **auto-retry 路径也会让 edit 先于压缩存在**。

## 2. pi 锚点事实（`200387122`，全部经 `git show/git grep` 取证）

### 2.1 生产路径：`prepareCompaction` → 投影 → `findProjectedCutPoint`

`packages/coding-agent/src/core/compaction/compaction.ts:872-917`：

```ts
export function prepareCompaction(
	pathEntries: SessionEntry[],
	settings: CompactionSettings,
): CompactionPreparation | undefined {
	if (pathEntries.length > 0 && pathEntries[pathEntries.length - 1].type === "compaction") {
		return undefined;
	}

	const projection = buildSessionProjection(pathEntries);
	const projectedEntries = projection.entries;
	// …boundaryStart = 上一份 compaction 下标 + 1（:893-899）
	const tokensBefore = estimateProjectedContextTokens(projection, pathEntries).tokens;
	const cutPoint = findProjectedCutPoint(projectedEntries, boundaryStart, boundaryEnd, settings.keepRecentTokens);
	// …
	const messagesToSummarize = projectedEntries
		.slice(boundaryStart, historyEnd)
		.flatMap(getMessagesFromProjectedEntryForCompaction);
```

裸 `findCutPoint`（`compaction.ts:446`，读 `sessionEntryToContextMessages`、不应用 edit）
**只有测试和 re-export 引用**（`git grep` 核过：src 内无调用点）——生产唯一的路是投影版。

### 2.2 投影的构造

`packages/coding-agent/src/core/session-manager.ts:543-575`：

```ts
export function buildSessionProjection(
	entries: SessionEntry[],
	leafId?: string | null,
	byId?: Map<string, SessionEntry>,
): SessionProjection {
	const path = buildSessionPath(entries, leafId, byId);
	const contextEntries = buildContextEntries(entries, leafId, byId);
	const edits = new Map<string, ContextEditEntry>();
	for (const entry of contextEntries) {
		if (entry.type === "context_edit") edits.set(entry.targetId, entry);
	}
	const projectedEntries = contextEntries.map(
		(sourceEntry, index): ProjectedSessionEntry => ({
			sourceEntry,
			messages:
				sourceEntry.type === "compaction" && index > 0
					? []
					: projectContextEntry(sourceEntry, edits.get(sourceEntry.id)),
		}),
	);
```

`ProjectedSessionEntry`（`:209-213`）：

```ts
export interface ProjectedSessionEntry {
	/** Raw append-only entry that owns this projected contribution. */
	sourceEntry: SessionEntry;
	/** Model-visible messages after context edits. Empty for state-only entries and omissions. */
	messages: AgentMessage[];
}
```

注意：pi 的投影**不**按 stopReason 过滤 error/aborted 助手——过滤在请求门
`packages/ai/src/api/transform-messages.ts:197-203`（"Skip errored/aborted assistant
messages entirely"）。切点累加因此**会**把无 omission edit 的 error 助手按原尺寸计入。

### 2.3 `findProjectedCutPoint`（`compaction.ts:803-870`）

```ts
function findProjectedCutPoint(
	entries: ProjectedSessionEntry[],
	startIndex: number,
	endIndex: number,
	keepRecentTokens: number,
): CutPointResult {
	const cutPoints: number[] = [];
	for (let i = startIndex; i < endIndex; i++) {
		const entry = entries[i];
		if (entry.sourceEntry.type !== "compaction" && entry.messages.some(isCutPointMessage)) cutPoints.push(i);
	}
	if (cutPoints.length === 0) {
		return { firstKeptEntryIndex: startIndex, turnStartIndex: -1, isSplitTurn: false };
	}

	let accumulatedTokens = 0;
	let exceededBudget = false;
	let cutIndex = cutPoints[0];
	for (let i = endIndex - 1; i >= startIndex; i--) {
		const messageTokens = entries[i].messages.reduce((sum, message) => sum + estimateTokens(message), 0);
		if (messageTokens === 0) continue;
		accumulatedTokens += messageTokens;
		if (accumulatedTokens >= keepRecentTokens) {
			exceededBudget = true;
			cutIndex = cutPoints.find((candidate) => candidate >= i) ?? cutPoints[cutPoints.length - 1];
			break;
		}
	}
```

其后两段（逐字移植）：① recovery-omission 闭后缀把 cutIndex 推进一格
（`isIntrinsicallyVisible`/`isOmitted`/`hasExternalReplacement`/`isRecoveryOmissionSuffix`，
`:833-860`）；② 向前吸收相邻 context-invisible 元数据条目
（`:862-866`：`previous.sourceEntry.type === "compaction" || previous.messages.length > 0` 即停）。

切点消息判定（`:362-378`）：`user/assistant/bashExecution/custom/branchSummary/compactionSummary`
为合法切点，`toolResult` 不是；turn-start 少一个 `assistant`。

### 2.4 摘要输入与文件清单

`getMessagesFromProjectedEntryForCompaction`（`:98-102`）：

```ts
function getMessagesFromProjectedEntryForCompaction(entry: ProjectedSessionEntry): AgentMessage[] {
	if (entry.sourceEntry.type === "compaction") return [];
	// System messages are prompt state, not conversation; the compaction entry carries their replay.
	return entry.messages.filter((message) => message.role !== "system");
}
```

即被 omission 的消息**不进摘要请求**。文件清单从同一份投影后消息抽 toolCall
（`extractFileOperations`，`:60-88`）。

### 2.5 `estimateProjectedContextTokens`（`:226-262`）

```ts
/** Estimate projected context without trusting usage captured before a later edit or compaction. */
export function estimateProjectedContextTokens(
	projection: SessionProjection,
	branchEntries: SessionEntry[],
): ContextUsageEstimate {
	const estimate = estimateContextTokens(projection.messages);
	if (estimate.lastUsageIndex !== null) {
		// 定位用量消息所属源条目 id；找分支上最后一条 context_edit/compaction 的下标；
		// 用量条目在该失效条目**之后** ⇒ 信用量；否则落到下面的纯字符重算。
	}
	const currentSystem = getCurrentSystemMessage(projection.messages);
	let tokens = currentSystem ? estimateTokens(currentSystem) : 0;
	for (const message of projection.messages) {
		if (message.role !== "system") tokens += estimateTokens(message);
	}
	return { tokens, usageTokens: 0, trailingTokens: tokens, lastUsageIndex: null };
}
```

### 2.6 edit 的两个生产时点（触发面）

| 时点 | 位置 | 后续 |
|---|---|---|
| 溢出/截断恢复：剔除最后失败助手＋其工具结果 | `agent-session.ts:2996` `_omitRecoveryAttempt(assistantMessage, toolResults)` | 立即 `_runAutoCompaction("overflow", willRetry)` |
| 可重试错误的自动重试：剔除失败助手 | `agent-session.ts:3738`（`_prepareRetry` 内，"Keep the failed attempt in raw history while durably omitting it from model projection"） | 退避后 continue，**压缩可能在更晚才发生** |
| replacement edit（边界预览草稿） | `agent-session.ts:935` `appendContextEdit(draft.targetId, draft.replacement)` | 同形状，尺寸按替换后 |

## 3. pi-java 现状（逐处核实）

1. **切点**：`CompactionService.findCutPoint`（`pi-java-agent-core/.../compaction/CompactionService.java:77-94`）
   对 `Entry.Message` 直接累加 `ContextUsageEstimator.estimateTokens(msg.message())`——
   无 edit、无投影分组；`safeCut`（`:96-114`）把已剔除条目照样当切点。
2. **摘要输入**：`compact`（`:47-53`）取 raw discarded entries 的 raw messages。
3. **文件清单**：`CompactionFiles.collect(discardedMessages, transcript)`
   （`.../compaction/CompactionFiles.java:48`）——形状已对，调用方改传投影后消息即可。
4. **tokensBefore**：`CompactionExecutor.contextTokens`
   （`.../harness/CompactionExecutor.java:126-129`）= `estimateContextTokens(lane.messages)`，
   缺 §2.5 的失效重算。
5. **投影部件已存在**：`ContextEntries`（`.../session/ContextEntries.java`）有
   `pathToLeaf` / `contextEntries` / `project` / `applyEdit`，但 `toMessages`（`:160-184`）
   输出扁平消息列表，切点需要「源条目 × 投影消息」分组。
   另：pi-java 的 `project`（`:218-222`）比 pi 多一道 error/aborted/deferred 停因过滤
   （旧锚点移植，pi 在请求门做）——本包不动它，但后缀判定按 pi 口径走（见 §5）。
6. **邻近发现**：`PostRunRetry.prepareRetry`（`.../harness/PostRunRetry.java:62-90`）
   只摘工作副本尾，**未落 pi :3738 的持久 omission edit**。

## 4. 选项

### R-A：全量对齐 pi 生产形状（推荐）

- `ContextEntries` 新增按源条目分组的投影，`toMessages` 改为它的扁平视图（单一事实源）；
- `CompactionService` 切点/摘要输入全部改读投影，逐字移植 §2.3 的两段边界修正；
- `ContextUsageEstimator` 新增 `estimateProjectedContextTokens`，`contextTokens` 改读它；
- 无合法切点的非空转录照 pi 判为不可压缩（现在会硬走兜底切点）。

收益：切点、摘要内容、tokensBefore 三者与 pi 同源；摘要不再可能被失败助手文本污染。
成本：4 个主文件改动 + 一组 RED→GREEN 测试 + 变异探针。

### R-B：只修摘要输入污染（最小行为修复）

只把送摘要生成器的消息改成「投影后、剔除 system」，切点仍按原始字符。
切点边界差异保留——而边界差异会改变哪些消息进摘要，R-B 在边界不一致时仍是错的。
不推荐：半成品，且省不掉投影构建。

### R-C：裁决无缺口，销号

不成立——§2.4 的摘要输入差异是模型可见的（失败文本可经摘要进入后续上下文），
不符合「经核实无缺口」的销号条件。

## 5. 推荐方案与实施步骤

**推荐 R-A。** 一个关键移植细节：§2.3 后缀判定中的 `isIntrinsicallyVisible` 必须按 pi
口径（**不**走 pi-java 的停因过滤），否则溢出恢复那条被 omission 的 error 助手在 Java
侧被分类成 context-invisible，后缀推进永不触发。Java 侧用源条目形状直接判：

```java
// pi: type !== "context_edit" && sessionEntryToContextMessages(source).length > 0
static boolean intrinsicallyVisible(Entry e) {
    return !(e instanceof Entry.ContextEdit)
        && (e instanceof Entry.Message
            || e instanceof Entry.CustomMessage
            || (e instanceof Entry.BranchSummary bs && bs.summary() != null)
            || e instanceof Entry.Compaction);
}
```

步骤（严格 RED-first；每步独立可编译即 commit）：

| # | 内容 | 预期红灯 |
|---|---|---|
| 1 | 新增测试（agent-core）：① 摘要输入不含被 omission 的失败助手文本；② raw 与投影尺寸不同导致切点位置不同；③ 无合法切点 ⇒ 抛异常；④ 恢复 omission 闭后缀推进；⑤ 相邻元数据回吸 | 先全红 |
| 2 | `ContextEntries` 加 `ProjectedEntry(source, messages)` + `projectEntries(...)`，`toMessages` 改为其扁平视图 | 测试编译通过、行为测试仍红 |
| 3 | `CompactionService` 重写：投影 → 切点（cutPoints/累加/后缀/元数据回吸）→ 投影后摘要输入；文件清单改接投影后消息 | ①②③④⑤ 转绿 |
| 4 | `ContextUsageEstimator.estimateProjectedContextTokens` + `CompactionExecutor.contextTokens` 改读；补一条「edit 晚于用量 ⇒ 重算」测试 | 新测试 RED→GREEN |
| 5 | 变异探针：M1 摘要输入走回 raw ⇒ ① 红；M2 累加走回 raw 消息 ⇒ ② 红；M3 删后缀推进 ⇒ ④ 红；M4 删失效重算 ⇒ tokensBefore 测试红。全部还原 | 探针有牙 |
| 6 | `mvn -o clean verify`（带 `-am`；全量 14 模块） | 零错误 |

提交（显式路径 `git add <path>`，消息末 `Co-Authored-By: Claude Code <noreply@anthropic.com>`）：

- `test(agent-core): pin the post-edit projection contract for compaction (B169)`
- `feat(agent-core): project entries by source for the compaction cut point (B169)`
- `feat(agent-core): align tokensBefore with the post-edit projection (B169)`

## 6. 连带登记（本包不做）

- **新登 B171**：split-turn 摘要形状——切点落在助手消息上时 pi 用
  `TURN_PREFIX_SUMMARIZATION_PROMPT` 发**第二次**摘要调用，合并格式
  `\n\n---\n\n**Turn Context (split turn):**\n\n…`（`compaction.ts:992-1038`、
  prompt 见 `:856-876`）。pi-java 无论切点落哪都只发一次摘要。独立特性，单立包做。
- **新登 B172（低）**：`PostRunRetry.prepareRetry` 未落持久 omission edit
  （pi `agent-session.ts:3738` 有）。等价性论证：两侧的 error 助手分别由
  pi-java 停因过滤 / pi 请求门过滤，resume 与互读均不可观察；仅持久状态形状不同。
  建议裁决不改，登记留痕；若用户要求镜像 pi，约 3 行改动。

## 7. 闭环记录（2026-10-06）

**裁决：R-A 全量对齐，B169 销号。**

实施中两处取证修正（相对本设计稿的预案）：

1. **空切点不抛**：pi `findProjectedCutPoint` 对空 cutPoints 返回
   `{ firstKeptEntryIndex: startIndex }` 而不是抛错；prepare 的空摘要检查兜住它
   返回 undefined。Java 已照改（原预案写的是抛 ISE，已撤）——测试③ 钉住
   可观察行为：compact 路径仍抛 ISE。
2. **回吸判据读投影消息**：pi `previous.messages.length > 0` 读的是投影，不是
   本征可见性——本征可见、但被 omission 的条目投影为空，照样回吸。已按 pi 改
   （本设计稿 §5 的代码块写的是本征可见性，以此条为准），并补测试
   `omittedAssistantBeforeTheCutIsAbsorbedIntoTheDiscardedPrefix` 钉住差异。

另核实：pi 投影中 **index>0 的 compaction 贡献为空**（`session-manager.ts:562-564`），
`ContextEntriesTest.latestOfTwoCompactionsWins` 旧断言（更旧 compaction 出第二条
摘要消息）是本仓发明，已按锚点改为 3 条投影消息。

### 证据

- 新测试：`CompactionProjectionTest`（6 条）、`EstimateProjectedContextTokensTest`（2 条）；
  旧 compaction 相关夹具按新形状（parentId 链、keep=1）更新。
- 变异探针五颗，全部恰 1 红：
  M1 摘要走回 raw 消息；M2 累加走回 raw；M3 删后缀推进；
  M4 恒信 provider 用量；M5 回吸条件改回本征可见性。
- 全量验证：`mvn -o clean verify` **14/14 SUCCESS**（2026-10-06，6:27）；
  agent-core 586 tests。

### 保留的偏差（既有方言，本包不动）

- Java `project` 对 error/aborted/deferred 停因的过滤（pi 在请求门
  `transform-messages.ts:197-203` 做）：无 omission edit 时两侧投影形状不同，
  本 §8 已登记，不重开；后缀/回吸判定均按 pi 口径走。
- B171（split-turn 二次摘要）、B172（PostRunRetry 持久 omission edit）
  已登记，本包不做；B170 独立项不动。

### 提交

- `3eab2da` feat(agent-core): project entries by source for the compaction cut point (B169)
- `42a3742` feat(agent-core): align tokensBefore with the post-edit projection (B169)

## 8. 明确不做

- 不做 split-turn 的 turn-prefix 二次摘要（B171 独立包）；
- 不删 pi-java `project` 的停因过滤（请求形状的既有方言，且本包后缀判定已按 pi 口径绕过）；
- 不改 `RecoveryOmissions` 的身份扫描定位（B170 独立项）；
- 不在本包给 B172 补 edit（除非用户审核时改判）。
