# 包 18：split-turn 的 turn-prefix 二次摘要（B171）

> **状态：✅ 已闭环（2026-10-06，R-A）—— B171 销号。commits `73f13d1` / `2d94954` / `44aca0c`。**

## 1. 问题

B171（`docs/17 §6` 连带登记项）：当切点落在**一轮对话内部**（切点条目是助手消息、
其前还有本轮的 user 开头）时，pi 不把它当普通切点，而是：

- 被摘要的「历史」截到**本轮 user 之前**；
- 本轮 user → 切点助手之间的消息（turn prefix）用**另一个 prompt**
  （`TURN_PREFIX_SUMMARIZATION_PROMPT`）发**第二次**摘要调用；
- 两段摘要按固定格式拼成一条 summary。

pi-java 当前无论切点落哪都只发一次摘要、用同一份历史 prompt —— 本轮 user 会被
整段丢进历史摘要或整段保留，拼不出 pi 的形状。

## 2. pi 锚点事实（`200387122`，全部经 `git show` 取证）

### 2.1 切点结果带 turn 信息（`compaction.ts:790-870`）

```ts
function isProjectedTurnStart(entry: ProjectedSessionEntry): boolean {
	if (entry.sourceEntry.type === "compaction") return false;
	return entry.messages.some(isTurnStartMessage);
}

function findProjectedTurnStartIndex(entries, entryIndex, startIndex): number {
	for (let i = entryIndex; i >= startIndex; i--) {
		if (isProjectedTurnStart(entries[i])) return i;
	}
	return -1;
}
```

`isTurnStartMessage`（`:366-377`）：role 为 `user`（Java 侧 custom/bashExecution/
摘要消息投影后都是 UserMessage）⇒ true；`assistant`/`toolResult` ⇒ false。

`findProjectedCutPoint` 末尾（`:863-869`）：

```ts
const startsTurn = isProjectedTurnStart(entries[cutIndex]);
const turnStartIndex = startsTurn ? -1 : findProjectedTurnStartIndex(entries, cutIndex, startIndex);
return {
	firstKeptEntryIndex: cutIndex,
	turnStartIndex,
	isSplitTurn: !startsTurn && turnStartIndex !== -1,
};
```

### 2.2 prepare 的切分（`compaction.ts:872-940`）

```ts
const firstKeptEntry = projectedEntries[cutPoint.firstKeptEntryIndex]?.sourceEntry;
if (!firstKeptEntry?.id) return undefined;
const firstKeptEntryId = firstKeptEntry.id;
const historyEnd = cutPoint.isSplitTurn ? cutPoint.turnStartIndex : cutPoint.firstKeptEntryIndex;

const messagesToSummarize = projectedEntries
	.slice(boundaryStart, historyEnd)
	.flatMap(getMessagesFromProjectedEntryForCompaction);
const turnPrefixMessages = cutPoint.isSplitTurn
	? projectedEntries
			.slice(cutPoint.turnStartIndex, cutPoint.firstKeptEntryIndex)
			.flatMap(getMessagesFromProjectedEntryForCompaction)
	: [];

if (messagesToSummarize.length === 0 && turnPrefixMessages.length === 0) return undefined;

const fileOps = extractFileOperations(messagesToSummarize, sourceEntries, prevCompactionIndex);
if (cutPoint.isSplitTurn) {
	for (const msg of turnPrefixMessages) {
		extractFileOpsFromMessage(msg, fileOps);
	}
}
```

### 2.3 compact：两段摘要与逐字合并格式（`:942-1055`）

prompt 常量（`:942-956`，逐字）：

```ts
const TURN_PREFIX_SUMMARIZATION_PROMPT = `The messages above are earlier context from an ongoing conversation. Later messages are stored separately and do not need to be reconstructed.

Create a concise checkpoint of the user's request and the progress shown above. This checkpoint will be placed before the later messages so the conversation can continue with the necessary context.

## Original Request
[What did the user ask for?]

## Progress So Far
- [Key decisions and work completed in these messages]

## Context Needed to Continue
- [Information from these messages needed to understand the later work]

Only summarize information explicitly present above. Do not infer or recreate later messages.`;
```

compact 主体（`:992-1055`）：

```ts
if (isSplitTurn && turnPrefixMessages.length > 0) {
	let historyText = previousSummary ?? "No prior history.";
	let historyUsage: Usage | undefined;
	if (messagesToSummarize.length > 0) {
		const historyResult = await generateSummaryWithUsage(messagesToSummarize, …, previousSummary, …);
		historyText = historyResult.text;
		historyUsage = historyResult.usage;
	}
	const turnPrefixResult = await generateTurnPrefixSummary(turnPrefixMessages, …);
	summary = `${historyText}\n\n---\n\n**Turn Context (split turn):**\n\n${turnPrefixResult.text}`;
	summaryUsage = historyUsage ? combineUsage(historyUsage, turnPrefixResult.usage) : turnPrefixResult.usage;
} else {
	const result = await generateSummaryWithUsage(messagesToSummarize, …);
	summary = result.text;
	summaryUsage = result.usage;
}
```

### 2.4 turn-prefix 调用形状（`generateTurnPrefixSummary`，`:1076-1120`）

```ts
const maxTokens = Math.min(
	Math.floor(0.5 * reserveTokens),
	model.maxTokens > 0 ? model.maxTokens : Number.POSITIVE_INFINITY,
);
const llmMessages = convertToLlm(messages);
const conversationText = serializeConversation(llmMessages);
const promptText = `# Conversation\n${conversationText}\n\n# Instructions\n${TURN_PREFIX_SUMMARIZATION_PROMPT}`;
const response = await completeSummarization(model, buildSummarizationContext(promptText),
	createSummarizationOptions(model, maxTokens, …), streamFn, retry, callbacks);
const failure = getSummarizationFailure(response, "Turn prefix summarization");
if (failure) throw new Error(failure);
if (response.content.some((block) => block.type === "toolCall")) {
	throw new Error("Turn prefix summarization attempted to call a tool");
}
```

要点：① 输出上限取 `floor(0.5*reserveTokens)`（默认设置即 **8192**），再被模型
maxOutputTokens 封顶；② 与历史摘要同 system prompt、同 `completeSummarization`
（含 `cacheRetention:"none"` 与同一份 retry 环）；③ failure/toolCall 守卫的文案
前缀是 **"Turn prefix summarization"**，历史摘要是 "Summarization"。

`serializeConversation`（`compaction/utils.ts:114-153`）：各消息生成
`[User]: …`／`[Assistant]: …`／`[Assistant thinking]: …`／`[Assistant tool calls]: name(k=…)`
／`[Tool result]: …`，块间以 `\n\n` 连接；tool result 文本截断到
`TOOL_RESULT_MAX_CHARS = 2000`（`:94`），超长则
`{前2000字符}\n\n[... N more characters truncated]`。

### 2.5 `combineUsage`（`usage-totals.ts:31-53`）

逐字段相加（input/output/cacheRead/cacheWrite/totalTokens 与 cost 五分量）；
`cacheWrite1h`、`reasoning` 仅当任一侧存在时出现在结果中（缺席侧按 0）。

## 3. pi-java 现状（逐处核实）

1. `CompactionService.findCutPoint(…)` 返回**裸 int**，没有 turnStart/split 信息；
2. `Plan` 只有 `toSummarize`；`prepare` 的摘要区间恒为 `[boundaryStart, cut)`；
3. `compact()` 无条件单发一次 `summarize`；文件清单只收 toSummarize；
4. `SummaryGenerator` 是单方法接口（@FunctionalInterface），无 turn-prefix 形状；
5. `LlmSummaryGenerator`：`produceOnce` 的 `StreamOptions.maxTokens` 恒 empty；
   无 turn-prefix prompt；retry 环私有绑定在历史调用上；
6. **顺带发现**：pi 的 prepare 把上一份 compaction 的 `summary` 作为
   `previousSummary` 传进历史摘要（`:901-906,1001-1004`），Java `compact()` 恒传
   `null`（B2 同族项之一）。`LlmSummaryGenerator.buildPrompt` 本就支持非 null
   previousSummary（`LlmSummaryGenerator.java:319-321`），接通零障碍。

## 4. 方案（R-A：照 pi 全量落地）

| # | 文件 | 改动 |
|---|---|---|
| 1 | `CompactionService` | `findCutPoint` 改返回私有 record `CutPoint(index, turnStartIndex, splitTurn)`，新增 `isProjectedTurnStart`/`findProjectedTurnStartIndex` |
| 2 | `CompactionService.Plan` | 加 `turnStartIndex`、`splitTurn`、`turnPrefix`、`previousSummary` 四组件 |
| 3 | `CompactionService.prepare` | 记录 prevCompactionIndex、取 previousSummary；按 §2.2 切 historyEnd、拼两份消息；空集判据改「两份皆空」；文件清单消息集＝toSummarize＋turnPrefix |
| 4 | `CompactionService.compact` | 按 §2.3 分支：split 时 0~2 次历史摘要 + 1 次 turn-prefix，逐字合并格式与 usage 合并；非 split 维持原样（但接通 previousSummary） |
| 5 | `SummaryGenerator` | 加 `SummaryResult summarizeTurnPrefix(List<Message>, int reserveTokens, String reason)`；去掉 @FunctionalInterface；`truncating()` 两方法都实现（turn-prefix 确定性文案 `"Turn prefix checkpoint of N message(s)."`） |
| 6 | `LlmSummaryGenerator` | 加 TURN_PREFIX 常量与 turn-prefix 序列化（移植 serializeConversation，TOOL_RESULT 截断 2000）；retry 环重构为 `callWithRetries(Supplier, reason)` 两路共用；failure 加 label 参数；新增带 `IntSupplier modelMaxOutputTokens` 的构造器 |
| 7 | `UsageCombiner`（compaction 包新建包级类） | 移植 §2.5 combineUsage |
| 8 | `AgentSession` 装配（`:378`） | 改用六参构造器，传入 `() -> models.maxOutputTokens(model)` |

说明：第 8 步只接线模型上限解析；旧的两参/五参构造器保留（cap 缺席 ⇒ 仅
0.5*reserve 封顶，即 pi 的 `model.maxTokens` 缺席形状）。

## 5. 实施步骤（严格 RED-first；每步独立可编译即 commit）

| # | 内容 | 预期 |
|---|---|---|
| 1 | 新增 `SplitTurnCompactionTest`（agent-core）：① 合并文本逐字 + usage 合并；② 历史/prefix 消息切分；③ 无更早历史 ⇒ `"No prior history."` 字面量；④ 切点在 user ⇒ 不 split；⑤ turn-prefix 抛错传播；⑥ turn-prefix 中 read 工具调用进 `<read-files>` | 先红/编译失败 |
| 2 | `LlmTurnPrefixRequestTest`：脚本化 StreamFn 捕获第二次请求 ⇒ prompt 含 `# Conversation`/`# Instructions` 与逐字 TURN_PREFIX 指令、`maxTokens=8192`、cacheRetention NONE | 先红 |
| 3 | 落 §4 表第 1–5、7 行（service＋interface＋combiner），CapturingGenerator 类夹具补新方法 | ①③④⑤⑥ 转绿 |
| 4 | 落第 6、8 行（LlmSummaryGenerator＋AgentSession） | ② 转绿 |
| 5 | 存量夹具按新接口签名更新（truncating 已是两方法实现，旧 CapturingGenerator 共约 2–3 处补方法） | 全绿 |
| 6 | 变异探针（§6）→ 全部还原 | 探针有牙 |
| 7 | `mvn -o clean verify`（带 `-am`，全 14 模块） | 零错误 |

提交：

- `test(agent-core): pin the split-turn compaction contract (B171)`
- `feat(agent-core): summarize the turn prefix with the second checkpoint prompt (B171)`
- （如 Llm 部分独立成 commit）`feat(agent-core): cap the turn-prefix summary at half reserve (B171)`

## 6. 变异探针（实施时定稿，预期各 1 红）

| 探针 | 变异 | 预期红灯 |
|---|---|---|
| M1 | 合并字面量 `**Turn Context (split turn):**` 改名 | ① |
| M2 | `isProjectedTurnStart` 恒 true（永不 split） | ①②③ |
| M3 | usage 恒取 turn-prefix（不合并） | ① usage 断言 |
| M4 | turn-prefix 不发 maxTokens（回 empty） | ② |
| M5 | 无历史时 fallback 改空串 | ③ |

## 7. 明确不做

- 不改历史摘要的主 prompt 文本（B2 同族「摘要 prompt」项，Java 现有方言保留）；
- 不补 `customInstructions` 通道（pi compact 参数，Java 无调用点）；
- 不动 B170（投影下标兜底定位）、B172（环 A 持久 omission edit，`docs/19`）。

## 8. 闭环记录（2026-10-06）

**裁决：R-A 全量落地，B171 销号。**

实施与设计稿的偏差（实测修正，行为面无偏差）：

1. **探针红数与预案不一致**：
   - M1 合并字面量改名 ⇒ 实际 **2 红**（①③：③ 的断言同样钉了该字面量；预案只写 ①）；
   - M2 恒不 split ⇒ 实际 **4 红**（①②③⑤：⑤ 的 prefix 失败因调用被跳过、不再抛出；预案写 ①②③）；
   - M3/M4/M5 各恰 1 红，与预案一致。
2. **coding-agent 接线形状**：`models::maxOutputTokens` 是一参方法引用（匹配
   `ToIntFunction`），构造器要零参 `IntSupplier` ⇒ 改 `() -> models.maxOutputTokens(model)`
   （commit `44aca0c`）。

### 证据

- 新测试：`SplitTurnCompactionTest`（6 条）、`LlmTurnPrefixRequestTest`（3 条）；
  存量 3 个夹具按 split 新形状更新（`CompactionServiceTest`／`CompactionFileOpsTest`／
  `HarnessCompactionSummarySpanTest`），`CompactionProjectionTest` 的 CapturingGenerator 补方法。
- agent-core 586 ⇒ **598 tests**（含包 19 的 3 条）；全 reactor `mvn -o clean verify`
  **14/14 SUCCESS**（2026-10-06）。

### 提交

- `73f13d1` test(agent-core): pin the split-turn compaction contract (B171)
- `2d94954` feat(agent-core): summarize the turn prefix with the second checkpoint prompt (B171)
- `44aca0c` feat(coding-agent): resolve the model cap for the turn-prefix summary (B171)
