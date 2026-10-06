# 包 21：历史摘要主 prompt 与请求参数对齐（B2 同族收尾）

> **状态：🔍 待审核（2026-10-06）—— 设计稿，未写生产代码。**

## 1. 问题

B2 原条目（compaction `details` 生产者）早已闭环（`4380796`），但其同族四处在
`原 docs/31 §8.30.7` 另立：**摘要 prompt / `previousSummary` / 请求参数 / split turn**。
包 18 落了 `previousSummary` 接通与 split turn（B171），但两处都是在 **Java 方言形状**
里落的 —— 真正的 prompt 文本与请求参数至今未对齐 pi。经逐行核实，剩余差距：

1. **系统提示词**不同（`LlmSummaryGenerator.java:53-57` vs pi `utils.ts:161-163`）；
2. **历史摘要主 prompt** 是本仓自造格式（`"Create a structured context checkpoint…
   ## Goal/## Constraints/## Progress/## Current State"` + `"Conversation:"` 前缀，
   `LlmSummaryGenerator.java:394-411`），pi 是 `<conversation>` /
   `<previous-summary>` 标签包裹 ＋ 逐字的 `SUMMARIZATION_PROMPT` /
   `UPDATE_SUMMARIZATION_PROMPT`；
3. pi 按 `previousSummary` 有无**切换两套 prompt**，Java 只有一套；
4. **历史摘要请求不发 `maxTokens`**，pi 发 `min(floor(0.8*reserveTokens), 模型 cap)`；
5. `summarize` 已收 `customInstructions` 参数但 buildPrompt **完全忽略**，pi 追加
   `\n\nAdditional focus: …`。

这是压缩线最后一块 prompt 文本面；自动压缩在 pi 里 customInstructions 恒为 undefined
（`agent-session.ts:3082`），所以第 5 项目前只通过手工 compact 路径可达。

## 2. pi 锚点事实（`200387122`，`git show` 取证）

### 2.1 系统提示词：`packages/coding-agent/src/core/compaction/utils.ts:161-163`

```ts
export const SUMMARIZATION_SYSTEM_PROMPT = `You are a context summarization assistant. Your task is to read a conversation between a user and an AI assistant, then produce a structured summary following the exact format specified.

Do NOT continue the conversation. Do NOT respond to any questions in the conversation. ONLY output the structured summary.`;
```

### 2.2 主 prompt 与参数选择：`compaction.ts:712-733`

```ts
	const maxTokens = Math.min(
		Math.floor(0.8 * reserveTokens),
		model.maxTokens > 0 ? model.maxTokens : Number.POSITIVE_INFINITY,
	);

	// Use update prompt if we have a previous summary, otherwise initial prompt
	let basePrompt = previousSummary ? UPDATE_SUMMARIZATION_PROMPT : SUMMARIZATION_PROMPT;
	if (customInstructions) {
		basePrompt = `${basePrompt}\n\nAdditional focus: ${customInstructions}`;
	}

	// Serialize conversation to text so model doesn't try to continue it
	// Convert to LLM messages first (handles custom types like bashExecution, custom, etc.)
	const llmMessages = convertToLlm(currentMessages);
	const conversationText = serializeConversation(llmMessages);

	// Build the prompt with conversation wrapped in tags
	let promptText = `<conversation>\n${conversationText}\n</conversation>\n\n`;
	if (previousSummary) {
		promptText += `<previous-summary>\n${previousSummary}\n</previous-summary>\n\n`;
	}
	promptText += basePrompt;
```

要点：JS 真值判定（空串＝假）；标签顺序固定 conversation → previous-summary → 指令。

### 2.3 `SUMMARIZATION_PROMPT`：`compaction.ts:507-538`

```ts
const SUMMARIZATION_PROMPT = `The messages above are a conversation to summarize. Create a structured context checkpoint summary that another LLM will use to continue the work.

Use this EXACT format:

## Goal
[What is the user trying to accomplish? Can be multiple items if the session covers different tasks.]

## Constraints & Preferences
- [Any constraints, preferences, or requirements mentioned by user]
- [Or "(none)" if none were mentioned]

## Progress
### Done
- [x] [Completed tasks/changes]

### In Progress
- [ ] [Current work]

### Blocked
- [Issues preventing progress, if any]

## Key Decisions
- **[Decision]**: [Brief rationale]

## Next Steps
1. [Ordered list of what should happen next]

## Critical Context
- [Any data, examples, or references needed to continue]
- [Or "(none)" if not applicable]

Keep each section concise. Preserve exact file paths, function names, and error messages.`;
```

### 2.4 `UPDATE_SUMMARIZATION_PROMPT`：`compaction.ts:540-579`

```ts
const UPDATE_SUMMARIZATION_INSTRUCTIONS = `Update the existing structured summary with new information. RULES:
- PRESERVE all existing information from the previous summary
- ADD new progress, decisions, and context from the new messages
- UPDATE the Progress section: move items from "In Progress" to "Done" when completed
- UPDATE "Next Steps" based on what was accomplished
- PRESERVE exact file paths, function names, and error messages
- If something is no longer relevant, you may remove it

Use this EXACT format:

## Goal
[Preserve existing goals, add new ones if the task expanded]

## Constraints & Preferences
- [Preserve existing, add new ones discovered]

## Progress
### Done
- [x] [Include previously done items AND newly completed items]

### In Progress
- [ ] [Current work - update based on progress]

### Blocked
- [Current blockers - remove if resolved]

## Key Decisions
- **[Decision]**: [Brief rationale] (preserve all previous, add new)

## Next Steps
1. [Update based on current state]

## Critical Context
- [Preserve important context, add new if needed]

Keep each section concise. Preserve exact file paths, function names, and error messages.`;

const UPDATE_SUMMARIZATION_PROMPT = `The messages above are NEW conversation messages to incorporate into the existing summary provided in <previous-summary> tags.

${UPDATE_SUMMARIZATION_INSTRUCTIONS}`;
```

### 2.5 请求参数门（本包不做，见 §6）：`compaction.ts:595-610`

```ts
function createSummarizationOptions(
	model: Model<any>,
	maxTokens: number,
	apiKey: string | undefined,
	headers: Record<string, string> | undefined,
	env: Record<string, string> | undefined,
	signal: AbortSignal | undefined,
	thinkingLevel: ThinkingLevel | undefined,
	sessionId: string | undefined,
): SimpleStreamOptions {
	const options: SimpleStreamOptions = { maxTokens, signal, apiKey, headers, env, sessionId };
	if (model.reasoning && thinkingLevel && thinkingLevel !== "off") {
		options.reasoning = thinkingLevel;
	}
	return options;
}
```

## 3. pi-java 现状（逐处核实）

- `LlmSummaryGenerator.SYSTEM_PROMPT`（`:53-57`）：首句 "Read a conversation…"
  且无空行分段，与 pi 不同；
- `buildPrompt`（`:394-411`）：自造 4 段格式，previousSummary 以
  `"Previous summary:\n"` 平铺在对话**之前**，无标签、无 UPDATE prompt；
- `produceOnce(List, String)`（`:280-282`）：历史路传 `OptionalInt.empty()`；
- `summarize`（`:123-138`）：`customInstructions` 收而不用；
- 序列化零件已齐备且是逐字移植：`ConversationSerializer.serialize`
  （pi `serializeConversation`，包 18 落，含 tool result 2000 字符截断与
  tool-call 参数渲染），直接复用，不再保留 `buildPrompt` 内的方言 textOf；
- `modelMaxOutputTokens` IntSupplier 已注入（turn-prefix 用），历史路共用即可。

## 4. 方案（R-A：照 pi 逐字替换 prompt 与历史路 maxTokens）

### 4.1 三个 prompt 常量

```java
/** pi SUMMARIZATION_SYSTEM_PROMPT（utils.ts:161-163）逐字。 */
private static final String SYSTEM_PROMPT = """
    You are a context summarization assistant. Your task is to read a conversation \
    between a user and an AI assistant, then produce a structured summary following \
    the exact format specified.

    Do NOT continue the conversation. Do NOT respond to any questions in the \
    conversation. ONLY output the structured summary.""";
```

（`\` 续行仅用于 Java 行宽，与现有 TURN_PREFIX 写法同形，产出字节无折行。）

```java
/** pi SUMMARIZATION_PROMPT（compaction.ts:507-538）逐字。 */
private static final String SUMMARIZATION_PROMPT = """
    The messages above are a conversation to summarize. Create a structured context \
    checkpoint summary that another LLM will use to continue the work.

    Use this EXACT format:
    …（§2.3 全文，结束行紧跟 """）
    Keep each section concise. Preserve exact file paths, function names, and error \
    messages.""";

/** pi UPDATE_SUMMARIZATION_INSTRUCTIONS（compaction.ts:540-575）逐字。 */
private static final String UPDATE_SUMMARIZATION_INSTRUCTIONS = """
    …（§2.4 INSTRUCTIONS 全文）
    messages.""";

/** pi UPDATE_SUMMARIZATION_PROMPT（compaction.ts:577-579）。 */
private static final String UPDATE_SUMMARIZATION_PROMPT =
    "The messages above are NEW conversation messages to incorporate into the existing "
    + "summary provided in <previous-summary> tags.\n\n" + UPDATE_SUMMARIZATION_INSTRUCTIONS;
```

### 4.2 prompt 组装（pi `:717-733`）

```java
private static String buildPrompt(List<Message> compressed, String previousSummary,
                                  String customInstructions) {
    String conversationText = ConversationSerializer.serialize(compressed);
    // pi :729-733：标签顺序 conversation → previous-summary → 指令。
    var sb = new StringBuilder()
        .append("<conversation>\n").append(conversationText).append("\n</conversation>\n\n");
    // pi 真值判定：空串即假（不用 isBlank，JS 空白串为真）。
    boolean updating = previousSummary != null && !previousSummary.isEmpty();
    if (updating) {
        sb.append("<previous-summary>\n").append(previousSummary)
            .append("\n</previous-summary>\n\n");
    }
    sb.append(updating ? UPDATE_SUMMARIZATION_PROMPT : SUMMARIZATION_PROMPT);
    if (customInstructions != null && !customInstructions.isEmpty()) {
        sb.append("\n\nAdditional focus: ").append(customInstructions);
    }
    return sb.toString();
}
```

### 4.3 历史路 maxTokens（pi `:712-715`）

```java
/** pi compaction.ts:712-715：min(floor(0.8*reserveTokens), cap > 0 ? cap : ∞)。 */
private int historyMaxTokens(int reserveTokens) {
    int eightyReserve = (int) Math.floor(0.8 * reserveTokens);
    int cap = modelMaxOutputTokens.getAsInt();
    return cap > 0 ? Math.min(eightyReserve, cap) : eightyReserve;
}
```

`produceOnce` 历史路改为带参：

```java
private Message.AssistantMessage produceOnce(List<Message> compressed, String previousSummary,
                                             String customInstructions, int reserveTokens) {
    return request(buildPrompt(compressed, previousSummary, customInstructions),
        OptionalInt.of(historyMaxTokens(reserveTokens)));
}
```

`summarize` 的 callWithRetries 闭包同步改参；turn-prefix 路径不动。删除方言
`textOf(Message)`。类 javadoc 与常量注释更新。

行为面：仅历史摘要一次出站请求的 system/prompt 文本与 maxTokens 改变；摘要的
落盘、合并、重试环语义零改动。

## 5. 实施步骤（严格 RED-first）

| # | 内容 | 预期 |
|---|---|---|
| 1 | 新增 `LlmHistorySummaryRequestTest`（见下，4 条） | 先红 |
| 2 | 落 §4 生产改动 | 转绿；存量 compaction 全测试连带绿 |
| 3 | 变异探针 §5.1 → 全部还原 | 有牙 |
| 4 | `mvn -o clean verify`（全 14 模块） | 零错误 |

新夹具沿用 `LlmTurnPrefixRequestTest` 的 Captured（捕获 Context/StreamOptions），
调 `generator.summarize(messages, previousSummary, customInstructions, 20_000,
"manual")`。用例：

1. `initialSummaryWrapsConversationAndSendsVerbatimPromptAtEightyPercentReserve`：
   prompt = `<conversation>\n` ＋ 序列化文本 ＋ `\n</conversation>\n\n` ＋
   SUMMARIZATION_PROMPT 全文；system prompt 与 §2.1 逐字相等；
   maxTokens = floor(0.8×20000) = **16000**；cacheRetention = none；
2. `previousSummarySwitchesToUpdatePromptAndTags`：previousSummary="Prior: build app"
   ⇒ conversation 块之后出现 `<previous-summary>\nPrior: build app\n</previous-summary>\n\n`，
   尾部指令以 `"The messages above are NEW conversation messages…"` 开头（钉切换）；
3. `customInstructionsAppendsAdditionalFocus`：prompt 以
   `\n\nAdditional focus: Focus on auth module` 结尾（钉参数没有被吞）；
4. `modelOutputCapClampsTheEightyPercentReserve`：cap=4096 ⇒ maxTokens 4096；
   cap=0 ⇒ 16000。

RED 预期：旧代码下 4 条全红（prompt 文本不符＋maxTokens 缺席＋focus 被忽略）。

### 5.1 变异探针（红数以实测为准）

| 探针 | 变异 | 预期红灯 |
|---|---|---|
| M1 | prompt 退回方言（去标签/自造 4 段） | ①②③ |
| M2 | 历史路不发 maxTokens（回 empty） | ①②④ |
| M3 | 不按 previousSummary 切换（恒 SUMMARIZATION） | ② |
| M4 | 删 Additional focus 后缀 | ③ |
| M5 | 系统提示词退回旧方言 | ① |

## 6. 明确不做（新登记）

- **B173（新）**：摘要请求未透传 `thinkingLevel`（pi `:606-608`：model.reasoning
  且 level 非 off 时 `options.reasoning = level`），历史路与 turn-prefix 路同缺。
  需要新增 `Supplier<ThinkingLevel>`（会话内可经 `setThinkingLevel` 改变，还要核
  Java 侧 `model.reasoning` 的事实源）⇒ 独立包裁决；
- **B174（新）**：`customInstructions` 无生产者 —— `CompactionService.compact`
  硬编码 null（`:151,166`）；pi 手工 `compact(customInstructions)` 经 RPC compact
  action options 可达（`agent-session.ts:3410`）。本包只保证参数被 prompt 正确消费，
  RPC/命令面接线另立；
- 不动摘要落盘、split-turn 合并字面量、重试环、缓存门、文件清单；
- 摘要请求的 routing `sessionId ?? uuidv7()` 仍属 B103 家族，不重开。

## 7. 收口清单

- docs/21 banner/§8 回填；docs/05：B2 同族行更新 ＋ 新增 B173/B174 两行
  （未结 +2；B2 本体早已闭环，本包是同族收尾，不产生 −1）；
- CLAUDE.md 设计文档表加 docs/21 行；memory 更新；全 reactor 验证后汇报。

## 8. 闭环记录

（闭环时回填。）
