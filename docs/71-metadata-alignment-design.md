# 71 — 模型/消息 metadata 对齐设计

> 对齐基准：pi 锚点 `3390bd93630965a12a0a1a5c36ce890ec22f7e1d`（复核时 = pi HEAD，无漂移）
> 归属：`docs/48 §5` 行 E「模型/消息 metadata 对齐（现有车道主流字段）」—— 该清单的**最后一项**。
> 编制日期：2026-10-03。
>
> **✅ 状态：已批准（R1–R7 全按建议，用户「继续」）—— 实施中。**
> 实施记录见 §12。

---

## 0. 范围

行 E 的「遗留」列抄了 **18 个字段名**，备注自称「多项可能已落，实施前逐项核现状」。本设计的第一步就是把这 18 项**逐条核到 pi 的 `file:line` 与 Java 现状**（§1/§2）。

**核对结果**：18 项里 **8 项已落**、**3 项半落**、**7 项缺失**。而 7 个缺失里有 **4 个**是 `Message.java:144-150` 早已裁决过的「不移植」、**3 个**是整块缺席的子系统 —— 也就是说，**这一行的真实剩余量不是 18 项，而是 3 个尾巴 ＋ 7 条裁决**。

**本包做**（3 个半落项的收尾）：

1. `UserMessage`/`ToolResultMessage` 补 **消息级 `timestamp`**（pi 四个变体皆必填，本仓只有两个有）；
2. OpenAI **Responses 车道的 `textSignature` 双向接线**（回执 `id`/`phase` 的存与取，含 `phase` → `stopReason`）；
3. **同车道 `refusal` 在 `output_item.done` 的合并**（现在只有流式 delta 那一条路）。

**本包不做**（逐条理由见 §5，决议见 §6）：

| 项 | 处置 | 一句话理由 |
|---|---|---|
| `responseId`、`endTurn` | 维持不移植 | 复核后仍是**零消费者** |
| `responseModel`、`diagnostics` | 维持不移植 **＋ 更正理由** | pi 有消费者，但**消费模块本仓不存在**（不是「pi 没有消费者」） |
| `providerThinkingLevel` | 维持不移植 **＋ 更正理由** | 唯一消费者在 ai 自己的 Anthropic 车道，但被 **B99**（SDK 写不出 `block_binding`）挡着 |
| `metadata` / `user_id` | 不做 | pi **自己没有生产者**（纯宿主扩展点）⇒ 移植＝加一个没有生产者的通道 |
| `Model.promptCache` ＋ CacheWarmer | **另立包** | 整块子系统（TTL 元数据＋经济学模型＋定时器＋SDK/会话/UI 接线） |
| `error.metadata.raw`（**B135**） | **另立包** | 需先移植 `error-body.ts` 的错误体归一化层 |

---

## 1. pi 事实（逐行取证）

### 1.1 `timestamp`：四个消息变体**皆必填**

`packages/ai/src/types.ts`：

```ts
506	timestamp: number; // Unix timestamp in milliseconds      // SystemMessage
512	timestamp: number; // Unix timestamp in milliseconds      // UserMessage
536	timestamp: number; // Unix timestamp in milliseconds      // AssistantMessage
549	timestamp: number; // Unix timestamp in milliseconds      // ToolResultMessage
```

**生产者（全部 `Date.now()`）**：

| 变体 | 位置 |
|---|---|
| user | `packages/agent/src/agent.ts:422` `return [{ role: "user", content, timestamp: Date.now() }];` |
| assistant | 每条适配器构造输出消息时写：`ai/api/anthropic-messages.ts:538`、`openai-completions.ts:323`、`openai-responses.ts:138`、`google-generative-ai.ts:83`、`mistral-conversations.ts:228`、`bedrock-converse-stream.ts:150` |
| system | `agent/agent-loop.ts:317` `withToolChanges({ role: "system", content: "", timestamp: Date.now() }, changes)` |
| toolResult | `agent/harness/execution/tools.ts:203`、`runtime/drive/tools.ts:146` |

**消费者（四处，都是行为）**：

1. **事件载荷**：`agent/src/types.ts:456-459` —— `message_start`/`message_update`/`message_end` 各带**整条** `AgentMessage`，时间戳随事件走。
2. **上下文估算的时间守卫**：`ai/src/utils/estimate.ts:81/91`
   ```ts
   assistant.timestamp >= latestPrefixTimestamp   // :81
   latestPrefixTimestamp = Math.max(latestPrefixTimestamp, message.timestamp);   // :91
   ```
3. **压缩与陈旧判定**：`agent/harness/compaction/compaction.ts:660`；`coding-agent/core/agent-session.ts:2304/:2374`（`assistantMessage.timestamp <= 压缩 entry 的时间戳`）。
4. **落盘与会话列表**：entry 内嵌整条 message（`agent/harness/session/types.ts:27-31`），`coding-agent/core/session-manager.ts:1040` 逐行 `JSON.stringify` ⇒ 消息自带的时间戳**在磁盘上**；`session-manager.ts:696-708` 的 `getMessageActivityTime` **优先读消息自己的 timestamp**，回退 entry 的。

### 1.2 `TextSignatureV1`：Responses 的 message `id` / `phase`

**共享载体** `ai/src/types.ts:358-368`：

```ts
export interface TextSignatureV1 { v: 1; id: string; phase?: "commentary" | "final_answer"; }
export interface TextContent { type: "text"; text: string;
	textSignature?: string; // e.g., for OpenAI responses, message metadata (legacy id string or TextSignatureV1 JSON)
}
```

**编解码** `ai/src/api/openai-responses-shared.ts:52-76`：

```ts
function encodeTextSignatureV1(id: string, phase?: TextSignatureV1["phase"]): string {
	const payload: TextSignatureV1 = { v: 1, id };
	if (phase) payload.phase = phase;
	return JSON.stringify(payload);
}
function parseTextSignature(signature: string | undefined): { id: string; phase?: ... } | undefined {
	if (!signature) return undefined;
	if (signature.startsWith("{")) {
		try { const parsed = JSON.parse(signature) as Partial<TextSignatureV1>;
			if (parsed.v === 1 && typeof parsed.id === "string") { /* phase 合法则带上 */ return { id: parsed.id, phase: ... }; }
		} catch { /* 落到 legacy 纯字符串 */ }
	}
	return { id: signature };              // legacy：裸 id 字符串
}
```

**产出侧**（响应 → 消息）`openai-responses-shared.ts:699-708`（`response.output_item.done`）：

```ts
} else if (item.type === "message" && slot?.type === "text") {
	slot.block.text = item.content?.map((c) => (c.type === "output_text" ? c.text : c.refusal)).join("") || "";
	slot.block.textSignature = encodeTextSignatureV1(item.id, item.phase ?? undefined);
	stream.push({ type: "text_end", contentIndex: slot.contentIndex, content: slot.block.text, partial: output });
	outputSlots.delete(event.output_index);
}
```

**消费侧**（消息 → 请求回放）`openai-responses-shared.ts:267-287`：

```ts
} else if (block.type === "text") {
	const parsedSignature = parseTextSignature(textBlock.textSignature);
	const fallbackMessageId = textBlockIndex === 0 ? `msg_pi_${msgIndex}` : `msg_pi_${msgIndex}_${textBlockIndex}`;
	textBlockIndex++;
	let msgId = parsedSignature?.id;                       // OpenAI 要求 id ≤ 64 字符
	if (!msgId) msgId = fallbackMessageId;
	else if (msgId.length > 64) msgId = `msg_${shortHash(msgId)}`;
	output.push({ type: "message", role: "assistant",
		content: [{ type: "output_text", text: sanitizeSurrogates(textBlock.text), annotations: [] }],
		status: "completed", id: msgId, phase: parsedSignature?.phase });
}
```

**`phase` → `stopReason`** `openai-responses-shared.ts:442-446`：`item.phase === "final_answer"` ⇒ `output.stopReason = "stop"`。

**Google 用同一个槽位** `google-generative-ai.ts:159-164`（`currentBlock.textSignature = retainThoughtSignature(...)`）—— 即该字段是**两车道共用的字符串槽**，语义由生产者定。

### 1.3 `refusal`：delta 与 `done` 两处

**流式 delta** `openai-responses-shared.ts:643-652`：`response.refusal.delta` 与 `output_text.delta` **同一条路**（累加到同一个 text 块并发 `text_delta`）。

**收尾合并**：见 §1.2 引的 `:700` —— `output_item.done` 时用**权威内容**覆盖累计文本：`output_text` 取 `c.text`，其余取 `c.refusal`，再 `join("")`。

### 1.4 四条「不移植」裁决的复核 —— **结论被推翻一半**

Java 侧的原话（`pi-java-ai/src/main/java/com/pijava/ai/message/Message.java:144-150`）：

> pi 类型上的 `responseModel`/`responseId`/`providerThinkingLevel`/`diagnostics`/`endTurn` 在对齐面（`packages/agent/src`）**没有任何消费者**……故不移植。

逐条复核（全文 ripgrep，已剔除同名局部量：`PromptTemplateDiagnostic`/`SkillDiagnostic`/CLI `result.diagnostics`/局部 `const responseId`）：

| 字段 | 定义 | 生产者 | 消费者 | 复核结论 |
|---|---|---|---|---|
| `responseId` | `types.ts:522` | 六条车道 | **仅同车道内**（`openai-codex-responses.ts:1534/:1546`） | ✅ 维持不移植 |
| `endTurn` | `types.ts:531-535` | 仅 `openai-codex-responses.ts:751` | **零** | ✅ 维持不移植 |
| `responseModel` | `types.ts:521` | `openai-completions.ts:560`、`anthropic-messages.ts:607` | **`coding-agent/core/usage-totals.ts:44`** `key = \`${...provider}/${...responseModel ?? ...model}\``；**`core/cache-warmer.ts:345`** 用量归属 | ❌ **前提证伪** |
| `diagnostics` | `types.ts:525`（类型 `utils/diagnostics.ts:10-15`） | `anthropic-messages.ts:802`、`pi-messages.ts:173/:338` 等 | **`core/bug-report.ts:194`** `const diagnostics = message.diagnostics ?? [];`；**`modes/interactive/interactive-mode.ts:3917`** 数 `anthropic_input_transformations` | ❌ **前提证伪** |
| `providerThinkingLevel` | `types.ts:523-524` | `anthropic-messages.ts:528`、`pi-messages.ts:202/:214`、`agent/proxy.ts:387/:396`（**设置**） | **`ai/api/anthropic-messages.ts:1370-1376`**：`isAnthropicEffort(msg.providerThinkingLevel)` ⇒ `assistantLevels.set(...)`，`:1440` 读出来做**中途 effort 绑定** | ⚠️ **「无生产者层读点」的措辞也不成立** |

**但**：`usage-totals` / `bug-report` / `cache-warmer` 这三个消费模块，**本仓一个都没有**（`RunSummaryAggregator` 是按 run 汇总，不是按 `provider/responseModel` 分组；全仓 `bugReport`/`CacheWarm` 零命中）。⇒ 现在补字段会变成「**只有生产者、没有消费者**」。

### 1.5 `metadata` / `user_id`：pi 自己**没有生产者**

`types.ts:224-229`（`StreamOptions`）：

```ts
	/**
	 * Optional metadata to include in API requests.
	 * Providers extract the fields they understand and ignore the rest.
	 * For example, Anthropic uses `user_id` for abuse tracking and rate limiting.
	 */
	metadata?: Record<string, unknown>;
```

`simple-options.ts:49` 原样透传（`metadata: options?.metadata`）；**唯一真消费者**是 Anthropic 车道 `anthropic-messages.ts:1184-1189`：

```ts
	if (options?.metadata) {
		const userId = options.metadata.user_id;
		if (typeof userId === "string") { params.metadata = { user_id: userId }; }
	}
```

⚠️ 其余六条车道**一个都不读**（`openai-completions`/`responses`/`google`/`vertex`/`mistral`/`azure` 零命中）。而且**全仓没有任何地方写 `StreamOptions.metadata`** —— 它是**宿主/SDK 的扩展点**（嵌入方自己传），pi 只负责转发。

### 1.6 `Model.promptCache` 与 CacheWarmer

`types.ts:111-115` / `:973-974`：

```ts
export type ModelPromptCache = Partial<Record<Exclude<CacheRetention, "none">, number>>;  // 秒
	/** Prompt cache lifetimes per retention tier. Unset when the provider's cache behavior is unknown. */
	promptCache?: ModelPromptCache;
```

**生产者是生成期常量**：`scripts/generate-models.ts:935-948` 硬编 `ANTHROPIC_PROMPT_CACHE = { short: 300, long: 3600 }`，**只给直连 anthropic**；models.dev 不提供该字段。

**唯一行为消费者**是缓存保温器 `coding-agent/core/cache-warmer.ts:32-35`：

```ts
export function getPromptCacheTtlMs(model, options): number | undefined {
	const retention = options?.cacheRetention ?? (getProviderEnvValue("PI_CACHE_RETENTION", options?.env) === "long" ? "long" : "short");
	if (retention === "none") return undefined;
	const seconds = model.promptCache?.[retention];
	return seconds === undefined ? undefined : seconds * 1000;
}
```

它喂 `getCacheWarmingDelayMs`（TTL 的 90%、下限 10 s）与整个 `CacheWarmer` 经济学（`CacheWarmingDecision`）。

### 1.7 `error.metadata.raw` 与 `error-body.ts`

`openai-completions.ts:701-724`（catch 支）：

```ts
		output.stopReason = options?.signal?.aborted ? "aborted" : "error";
		output.errorMessage = formatProviderError(normalizeProviderError(error));
		// Some providers via OpenRouter give additional information in this field.
		const rawMetadata = (error as any)?.error?.metadata?.raw;
		if (rawMetadata && !output.errorMessage.includes(String(rawMetadata))) {
			output.errorMessage += `\n${rawMetadata}`;
		}
```

前置的一层在 `ai/src/utils/error-body.ts`（**已复核存在**，6444 字节）：

- `MAX_PROVIDER_ERROR_BODY_CHARS = 4000`（`:16`）
- `normalizeProviderError(error): {status?, body?, message, messageCarriesBody}`（`:38-54`）—— `extractStatus` 依次探 `statusCode`→`status`→`$metadata.httpStatusCode`→`$response.statusCode`；`extractBody` 探 `body` 字符串→`error` 对象的 JSON→`$response.body`，按 4000 字符截断。
- `formatProviderError(norm, prefix?)`（`:128-135`）—— 消息已含 body 就原样返回，否则拼 `"<status>: <body>"`。

被 **8 条车道**用于 HTTP 层错误（azure/bedrock/google/vertex/openai-codex/completions/responses/openrouter-images）。

---

## 2. Java 现状（逐文件实测）

### 2.1 `timestamp`：只落了一半

| 变体 | Java 现状 |
|---|---|
| `SystemMessage` | ✅ 有 `Instant timestamp`（`Message.java:54`）；生产者 `ToolChangeDeclaration.java:118/127`；消费 `PiLaneSink.java:368-373`；落盘 `SessionJson.java:131-133`；回读 `MessageJsonCodec.java:66` |
| `Message.AssistantMessage` | ✅ 有（`Message.java:164`）；生产者 `AbstractChatApi.java:78/215-219`（`withIdentity(...)`）；消费 `Estimate.java:245-247`、`PostRunCompactionCheck.java:240-243`；落盘 `SessionJson.java:110-114`；回读 `MessageJsonCodec.java:53` |
| 流式 `com.pijava.ai.message.AssistantMessage` | ✅ 有（`:60`） |
| `UserMessage` | ❌ **无字段**（`Message.java:32` `record UserMessage(List<ContentBlock> content)`）；无生产者（`HarnessUtils.java:71` 等直接 `new Message.UserMessage(content)`）；`MessageJsonCodec.java:44` 解码不读、`SessionJson` 不写 |
| `ToolResultMessage` | ❌ **无字段**（`Message.java:285`）；生产者 `PiToolRunner.java:204`、`PiLoopTools.java:254/272`；`MessageJsonCodec.java:56-63` 只读七个键 |

`MessageJsonCodec.decodeTimestamp`（`:259-267`）**已存在**，只是只被 assistant/system 调用。

**连带后果**：`Estimate.java:218-219/244` 自陈「java 里没有时间戳的消息不参与推进」，其时间守卫对 user/toolResult **恒退化**。

**外加一条既有偏离**：web/RPC wire 连 assistant 的 timestamp 都不带 —— `WebWireJson.java:58-59`：

```java
            // timestamp 不带上：维持既有「wire 无消息 timestamp」的有意偏离
            // （client/main.ts:261/286 直贴不判重）。省略规则同 toolResult 支。
```

### 2.2 `textSignature`：字段在，Responses 车道**两个方向都没接**

- **载体在**：`ContentBlock.java:37` `record TextContent(String text, String textSignature)`；落盘 `SessionJson.java:170-173`、回读 `MessageJsonCodec.java:308-310`；流式写原语 `StreamPartialBuilder.retainTextSignature`（`:179-188`）；**Google 是唯一调用者**（`GoogleGenerativeAiApi.java:224-226`），Google 回放也读（`GoogleMessageConverter.java:120`）。
- **请求侧不读**：`ResponsesMessageConverter.java:349-352` 只看 `tc.text()`，然后在 `:384-396` 无条件写死 `msg_pi_<msgIndex>`：

```java
        if (!text.isEmpty()) {
            items.add(ResponseInputItem.ofResponseOutputMessage(
                ResponseOutputMessage.builder()
                    .id("msg_pi_" + msgIndex)
                    ...
```
  即：**没有 `parseTextSignature`、没有 `phase`、没有 64 字符 `shortHash` 分支**，而且把所有 text 块**拼成一条** output message（pi 是**一文本块一条**，第二条起用 `_${textBlockIndex}` 后缀）。

- **响应侧不写**：`ResponseItemHandlers.done` 的消息支（`:52-55`）只发 `emitTextEnd()`：

```java
        } else if (item.message().isPresent()
                && ResponsesStreamProcessor.isSlot(ctx.slotTypes, outputIndex, ResponsesStreamProcessor.TEXT)) {
            ctx.publisher.submit(ctx.builder.emitTextEnd());
```
  从不读 `item.message().get().id()/.phase()/.content()`（对照同文件 `:47-51` 的 reasoning 支就调了 `reasoningCapture.capture(...)`）。`emitTextEnd()`（`StreamPartialBuilder.java:207-210`）不带参数、用 delta 累计的 `textBuf` ⇒ 产出的 `TextContent.textSignature` 恒为 `null`。全 `ai/protocol` 包里 **`phase` 只出现在注释里**。

- **javadoc 已过期**：`ResponsesMessageConverter.java:334-339` 仍写「{@code ContentBlock.TextContent} carries no signature at all (registered as B23)」—— 而 `B23` 的描述（`docs/32:293`）说的是「`TextContent(String text)` **只有 1 个组件**」，那是 Batch F 之前的事实。**B23 的前提已作废，只剩「Responses 车道不用它」这一半成立。**

### 2.3 `refusal`：delta 有，`done` 合并没有

`ResponsesStreamProcessor.java:112-116` 已把 `response.refusal.delta` 按 text delta 走（与 pi 同义）；缺的正是 §2.2 那个 `done` 支 —— 于是「只在收尾内容里给的 refusal」会**丢**。

### 2.4 错误文案：无归一化层

- 唯一文案来源 `StreamEvent.errorTextOf`（`:109-115`）＝ `cause.getMessage()`；
- 各车道只把捕获到的异常对象交给 `emitError`（`StreamPartialBuilder.java:488-493`）；
- `PiHttpClient.providerErrorMessage`（`:186-203`）**确实**解析了错误体，但只用于**重试延迟诊断**（`:135/:141`），最终抛的是 `new PiHttpException(status, "HTTP " + status)`（`:151-153`）—— 错误体文案**到不了消息**。

---

## 3. 差距清单

| # | 差距 | pi 对应 | 严重度 |
|---|---|---|---|
| G1 | `UserMessage`/`ToolResultMessage` 无消息级 `timestamp`（生产者/落盘/回读/消费四条链全缺） | §1.1 | 中（**格式变更**，见 R1） |
| G2 | Responses 请求侧合成 id，不读 `textSignature`；无 `phase`、无 64 字符分支、多文本块被拼成一条 | §1.2 `:267-287` | 中 |
| G3 | Responses 响应侧 `output_item.done` 不写 `textSignature`、不读权威 content ⇒ 丢 id/phase/refusal 收尾 | §1.2 `:699-708` | 中 |
| G4 | `phase === "final_answer"` ⇒ `stopReason = "stop"` 未落 | §1.2 `:442-446` | 小 |
| G5 | `Message.java:144-150` 的「不移植」理由**与事实不符**（4 条里 2 条前提证伪、1 条措辞错） | §1.4 | 文档型（零行为） |
| G6 | `B23` 的前提（`TextContent` 只有 1 个组件）已作废 | §2.2 | 文档型 |
| G7 | `Model.promptCache` ＋ CacheWarmer 整块缺席 | §1.6 | **另立包** |
| G8 | 错误体归一化层（`error-body.ts`）缺席 ⇒ `error.metadata.raw`（B135）不可达 | §1.7 | **另立包** |

---

## 4. 实施设计（Java 草图）

### 4.1 G1 —— 两个变体补消息级 `timestamp`

```java
    record UserMessage(List<ContentBlock> content, java.time.Instant timestamp) implements Message {
        /** 兼容构造：无时间戳的旧数据/流式未成（null ≙ pi 的 undefined）。 */
        public UserMessage(List<ContentBlock> content) { this(content, null); }
        public UserMessage { content = List.copyOf(content); }
    }

    record ToolResultMessage(String toolUseId, String toolName,
                             List<ContentBlock> content,
                             Object details, Object usage, List<String> addedToolNames,
                             boolean isError, java.time.Instant timestamp) implements Message {
        // 既有两个便捷构造器全部改成委派到全参，最后一参给 null
    }
```

- **落盘**：`SessionJson.messageNode` 的 user 支（`:52-62`）与 tool 支（`:63-86`）各加一段「非 null 才写」——与 assistant/system 同形。
- **回读**：`MessageJsonCodec` 的 `case "user"`（`:44`）与 `case "tool"`（`:56-63`）各加 `decodeTimestamp(node)`（**该私有方法已存在**，`:258-267`）。
- **生产者**：`HarnessUtils.java:71`、`LlmSummaryGenerator.java:220`、`ContextEntries.java:189/205`、`PiToolRunner.java:204`、`PiLoopTools.java:254/272` 传 `Instant.now()`。
- **消费**：`Estimate.java:244` 的 `epochMillis` 扩到四个变体（时间守卫自此对 user/toolResult 生效）。
- ⚠️ **不是** `LocalDateTime`/`long`：沿用既有 `Instant`（assistant/system 已用），JSON 形状与 `decodeTimestamp` 一致。

### 4.2 G2/G3/G4 —— Responses 车道的 `textSignature` 与 `phase`

新增一个 **package-private 的 `TextSignatureV1`**（`com.pijava.ai.protocol`），逐行照 §1.2 的两个函数：

```java
    /** pi {@code TextSignatureV1}（types.ts:358-365）的编解码，逐行照 openai-responses-shared.ts:52-76。 */
    record TextSignatureV1(int v, String id, String phase) {
        static String encode(String id, String phase) { ... }        // {"v":1,"id":...}，phase 空则省键
        static Optional<Parsed> parse(String signature) { ... }      // "{" 开头才试 JSON；失败落到 legacy 裸 id
    }
```

**请求侧**（`ResponsesMessageConverter.addAssistantItems`）：把「拼接所有 text 块 + 写死 id」改成 **每个 text 块一条 output message**，id 走 `parsed.id ?? fallback`（含 `>64` ⇒ `msg_${shortHash(id)}`；`shortHash` 本仓已有 `ai/utils/ShortHash`），`phase` 一并带上。

**响应侧**（`ResponseItemHandlers.done` 的消息支）：

```java
            var message = item.message().get();
            // 权威内容覆盖累计缓冲（pi :700）：output_text 取 text、其余取 refusal
            ctx.builder.replaceText(textOf(message.content()));
            ctx.builder.retainTextSignature(TextSignatureV1.encode(message.id(), message.phase().orElse(null)));
            if ("final_answer".equals(message.phase().orElse(null))) {   // pi :442-446
                ctx.builder.forceStopReason("stop");
            }
            ctx.publisher.submit(ctx.builder.emitTextEnd());
```

其中 `replaceText` / `forceStopReason` 是 `StreamPartialBuilder` 上新增的两个小原语（G3 与 G4 各一个；G3 同时把 §2.3 的 refusal 收尾合并一并解决 —— **它们是同一个 done 支的两半，不拆**）。

**设计期 SDK 取证**（`openai-java-core:4.39.1`，`javap` 实测，2026-10-03）—— 先证「表达得了」再设计，避免 B99/B140 那种写了才发现 SDK 不给的情况：

| SDK 面 | 实测 | 影响 |
|---|---|---|
| `ResponseOutputMessage.phase()` | `Optional<Phase>` | ✅ G4 可表达 |
| `ResponseOutputMessage.Builder.phase(Phase)` / `phase(Optional)` | 两者皆在 | ✅ G2 可表达 |
| `Phase.COMMENTARY` / `FINAL_ANSWER` / `of(String)` | 皆为 `public static final` | 未知值可直通（同 pi 的字符串 passthrough） |
| `Content.outputText()` → `Optional<ResponseOutputText>`、`Content.refusal()` → `Optional<ResponseOutputRefusal>` | **两个独立变体**（`ResponseOutputText` **没有** `refusal()`） | pi 的 `c.type === "output_text" ? c.text : c.refusal` 在 Java 要写成下面的 `textOf` |
| `ResponseOutputMessage.id()` | `String` | ✅ |

```java
    /** pi `openai-responses-shared.ts:700` 的 Java 形：output_text 取 text、其余取 refusal。 */
    private static String textOf(List<ResponseOutputMessage.Content> content) {
        var out = new StringBuilder();
        for (var part : content) {
            out.append(part.outputText().map(ResponseOutputText::text)
                .orElseGet(() -> part.refusal()
                    .map(ResponseOutputRefusal::refusal).orElse("")));
        }
        return out.toString();
    }
```

⚠️ **与 pi 的一处刻意偏差**：pi 的 `slot.block.text` 覆盖是**无条件**的；Java 若照做，会在「done 的 content 为空/缺席」时把已流出的文本**清空**。实现取「**content 非空才覆盖**」并在夹具里钉住两支（R4）。

### 4.3 G5/G6 —— 纯 javadoc 更正（零行为改动）

- `Message.java:144-150`：把 5 个字段逐个写明**真实**理由（`responseId`/`endTurn` 零消费者；`responseModel`/`diagnostics` 消费模块本仓未移植；`providerThinkingLevel` 被 B99 挡），并指向本设计的 §1.4。
- `ResponsesMessageConverter.java:334-339`：删掉「carries no signature at all (B23)」这句，改成「字段在、Responses 车道本包接通」。

---

## 5. 明确不做（理由归档）

见 §0 的表。补充两条口径：

- **`metadata`/`user_id` 不做的判据**不是「pi 没有」，而是「**pi 也没有生产者**」——移植它等于在本仓加一个恒空的请求侧通道，与本仓 A-20（无生产者清理）的方向相反。若将来本仓要暴露宿主扩展点，再连同「谁来写」一起裁。
- **`Model.promptCache` 与 CacheWarmer 必须同包**：只有 TTL 元数据而没有保温器＝加一个零消费者字段；只有保温器而没有 `promptCache` 又算不出 TTL。

---

## 6. 裁决项（请回复 R 项决议）

| # | 裁决 | 建议 |
|---|---|---|
| **R1** | **`timestamp` 的可空性**：pi 四个变体皆**必填**，但本仓 user/toolResult 的既有会话文件**没有**这个键 | **可空（`Instant` 允许 null）＋ 旧数据解码为 null**；不写「迁移」「默认 `now()`」——凭空补时间戳是发明行为。代价：`Estimate` 的时间守卫对新会话立即生效、对旧会话仍退化（与今天同） |
| **R2** | **web/RPC wire 是否同步带上 timestamp** | **本包不带**，维持 `WebWireJson.java:58-59` 的既有偏离（那是**另一条**已登记的偏离，与消息模型无关）；改它要连 client 侧一起动 |
| **R3** | **Responses 请求侧「一文本块一条 output message」** | **照 pi 改**（现在是拼成一条）——否则 `phase`/id 无处安放。代价：既有 wire 夹具要按新形状更新 |
| **R4** | **`done` 覆盖文本的条件** | **content 非空才覆盖**（见 §4.2 的偏差说明）；夹具钉「有 content」「content 空」两支 |
| **R5** | **`responseModel`/`diagnostics` 的处置** | **维持不移植，但更正 `Message.java:144-150` 的理由**（前提已证伪，结论因「消费模块缺席」而仍成立）＋ 在 `docs/32` 登记「等 usage-totals/bug-report 移植时一并做」 |
| **R6** | **`providerThinkingLevel` 的处置** | **维持不移植**，理由改挂 **B99**（不是「无消费者」） |
| **R7** | **`Model.promptCache`/CacheWarmer 与 `error.metadata.raw`** | **本包不做**，各自另立包；`B135` 维持开 |

---

## 7. 测试计划（RED 先于实现）

### 7.1 新夹具

1. **`timestamp` round-trip**：user/toolResult 写入 ⇒ JSONL 有 `timestamp` 键 ⇒ 回读等值；旧文件（无该键）⇒ 解码 `null` 不抛。
2. **生产者**：一次 `processPrompt` 后，四条消息的 timestamp 均非空且**单调不减**。
3. **`Estimate` 时间守卫**：给 user 消息一个比 assistant 早的 timestamp ⇒ 守卫按 pi 的分支走（这是 G1 的**唯一行为**，必须钉）。
4. **`TextSignatureV1` 编解码**：`{"v":1,"id":"msg_x"}` / 带 `phase` / 裸 legacy id / 非法 JSON ⇒ 各自解析结果（对照 pi `:52-76`）。
5. **请求侧 id**：带 signature 的文本块 ⇒ 用原 id；无 ⇒ `msg_pi_<i>`；>64 字符 ⇒ `msg_<shortHash>`；**多文本块** ⇒ 多条 output message 且后缀正确。
6. **响应侧**：`output_item.done` 带 `id`/`phase` ⇒ 文本块的 `textSignature` 是 `TextSignatureV1` JSON；`phase=final_answer` ⇒ `stopReason="stop"`。
7. **refusal**：只有 delta ⇒ 文本为 delta 之和；只有 done content（refusal）⇒ 文本为 refusal；两者都有且不一致 ⇒ 取 done 的权威值。
8. **`Message.java` javadoc 更正**：零行为，由既有全量回归守。

### 7.2 RED 安排

- G1（结构新增）走编译红；G2/G3/G4（行为）用 wire 夹具先红——⚠️ **本仓第 6 种「零红」成因（夹具写在实现之后）在 docs/70 刚犯过一次**，本包**先写夹具、跑红、再实现**。

### 7.3 变异探针（GREEN 后）

- **M1** 落盘时无条件写 `timestamp`（旧数据补 `now()`）⇒ 第 1 条用例红。
- **M2** `decodeTimestamp` 对 user/tool 不调用 ⇒ 第 1 条红。
- **M3** `Estimate` 守卫忽略 user 时间戳 ⇒ 第 3 条红。
- **M4** `parseTextSignature` 不做 `"{"` 判定（恒当裸 id）⇒ 第 4/5 条红。
- **M5** 去掉 `>64` 的 `shortHash` 分支 ⇒ 第 5 条红。
- **M6** done 支不覆盖文本 ⇒ 第 7 条的「只有 done content」红。
- **M7** `phase` 不触发 `stopReason` ⇒ 第 6 条红。

零红则登记「变异体语义等价」，或补夹具。

---

## 8. 文件大小约束

- 新增/修改文件 ≤500 行；新逻辑优先新类（`TextSignatureV1` 独立成文件）。
- `Message.java` 是**存量超限**的大文件之一，本次只在两个 record 上加一个分量 ＋ 两个便捷构造器；若越限，拆「便捷构造器」到同包 helper，不趁机重构。
- `ResponsesMessageConverter` 的 `addAssistantItems` 改动应控制在该方法内。

---

## 9. 实施步骤（每步全相关模块绿）

1. **G1 结构**：两个 record 加分量 ＋ 便捷构造器 ＋ 生产者传值 ＋ 落盘/回读 ＋ `Estimate` 扩展；夹具 1–3 先红后绿。
2. **G2/G3/G4 结构**：`TextSignatureV1` 新类 ＋ `StreamPartialBuilder` 两个新原语；夹具 4 先红后绿。
3. **请求侧**：`addAssistantItems` 改「一文本块一条 ＋ id 链」；夹具 5 先红后绿。
4. **响应侧**：`ResponseItemHandlers.done` 写 signature/phase/refusal 合并；夹具 6–7 先红后绿。
5. **G5/G6**：两处 javadoc 更正（零行为）。
6. **收尾**：全模块回归 ＋ checkstyle/spotbugs ＋ §12 与 `docs/48 §5 行 E`、`docs/32`（B23 收窄、B135 维持、新增登记）回填。

跨模块：ai 改 `Message`/`MessageJsonCodec`/`SessionJson`/`Responses*` → agent-core（Estimate/生产者）→ coding-agent `-am`；tui/web 无调用面（R2）。

---

## 10. 验收门槛

1. RED：旧实现下目标测试失败（结构项编译红；行为项 wire 夹具真红）。
2. GREEN：目标夹具＋集成测试通过。
3. 变异：M1–M7 记录红集；零红须登记或补夹具。
4. 回归：ai/agent-core/coding-agent（`-am`）全绿；checkstyle／**spotbugs** 0 新违规（⚠️ `mvn test` 不跑 spotbugs，必须 `verify`）。
5. 无调试残留；改动文件 ≤500。
6. §12 与 `docs/48 §5 行 E` 在收尾提交中回填（此行是清单最后一项，闭环后 `docs/48` 应无 `⬜`）。

---

## 11. 风险与备注

- **持久化格式变更**（G1）是本包唯一的兼容风险面：只**新增可选键**，不删不改既有键 ⇒ 旧文件可读、新文件对旧版本不可逆（本仓无版本协商，接受）。
- **R3 会改请求形状**（多个 message item）：必须核对既有 Responses wire 夹具，逐条按 pi 的新形状更新，不能靠放宽断言过关。
- **R4 是刻意偏差**：与 pi 的无条件覆盖不同，理由写进代码注释与夹具 javadoc。
- **B23 的收窄**要在 `docs/32` 显式做（原文说「只有 1 个组件」，实测已是 2 个）。

---

## 12. 实施记录

### 12.1 commit 序列

| 步骤 | commit | 内容 |
|---|---|---|
| 设计 | `26cbb42` | docs/71 设计稿（待评审） |
| 1 | `62d38a8` | **G1**：user/toolResult 的消息级 `timestamp`（字段＋生产者＋落盘/回读＋Estimate 守卫） |
| 2+3 | `64fda0f` | **G2 请求侧**：`TextSignatureV1` 编解码 ＋ id 链（一文本块一条 output message） |
| 4 | `7b5cf61` | **G3/G4 响应侧**：回执签名、refusal 权威内容合并、`phase=final_answer ⇒ stop` ＋ builder 两个原语 |
| 5 | `c66d4b2` | **G5/G6** javadoc 更正 ＋ 三模块夹具的 8 参构造适配 |
| 6 | （本提交） | §12 ＋ docs/48 §5 行 E ＋ docs/32 回填（收尾提交的 hash 不自引） |

### 12.2 RED 实测

| 步骤 | RED 类型 | 实红 |
|---|---|---|
| 1 | **编译红** | `UserMessage(content, timestamp)` / 8 参 `ToolResultMessage` 不存在。⚠️ 连带打到**主源码 3 处**（`TransformMessages:188/:200` 的全参复制、`MistralConversationsApi:338` 的 record 解构模式 `case Message.UserMessage(var content)`）＋夹具 8 处（ai 5、agent-core 3） |
| 2 | 编译红 | `TextSignatureV1` 找不到符号（10 处） |
| 3 | **真红 8/11** | 三条本来就成立（无签名回退、无 phase、无文本块）—— 其余 8 条正是 id 链/phase/一条一块的差异 |
| 4 | **真红 5/8** | 三条本来就成立（refusal 两路一致、空 content 不覆盖、无 phase 对照） |
| 5 | — | 零行为（javadoc） |

⚠️ 步骤 1 还**翻了两个既有夹具的期望值**：`OpenAIResponsesSurrogateSanitizeTest.assistantTextIsSanitizedPerBlockNotAcrossBlocks` 原先断言两块**拼接**成 `"AB"` —— 那是本仓的形状，pi 一直是「一块一条」⇒ 拼接才是偏差。夹具改为反向禁止拼接形态。

### 12.3 变异探针（§7.3 的 M1–M7）

每次变异后 `grep` 复核落地，测完回滚。

| 变异 | 红集 |
|---|---|
| **M1** 落盘无条件写 timestamp（缺席补 `now()`） | 恰 1：`messagesWithoutTimestampOmitTheKey` |
| **M2** user 支不读 timestamp | 2：`decodeReadsTimestampForUserAndToolResult`、`writeThenReadRoundTripsTheTimestamp` |
| **M2b** tool 支不读 timestamp | 恰 1：`decodeReadsTimestampForUserAndToolResult` |
| **M3** `Estimate.epochMillis` 不含 user/toolResult | 恰 2：`userMessageTimestampAdvancesThePrefixGuard`、`toolResultMessageTimestampAdvancesThePrefixGuard` |
| **M4** `parse` 不做 `"{"` 判定（恒当裸 id） | 8：`TextSignatureV1Test` 4（`parseV1Json`、`…WithoutPhase`、`…UnknownPhaseDrops`、`roundTrip`）＋ `ResponsesTextSignatureReplayTest` 4（`signatureIdWinsOverTheFallback`、`overlong…`、`exactlySixtyFour…`、`signaturePhaseIsReplayed`） |
| **M5** 去掉 `> 64` 的 `shortHash` 分支 | 恰 1：`overlongSignatureIdIsShortHashed` |
| **M6** `done` 支不合并权威内容 | 恰 2：`doneContentOverridesAccumulatedDeltas`、`refusalDeliveredOnlyInDoneContentSurvives` |
| **M7** `phase` 不触发 stop | 恰 1：`finalAnswerPhaseSetsStopOnTheTextEndPartial` |

**M1–M7 无零红。**

### 12.4 判定与偏离（Ruling）

| # | 判定 | 理由 | 判错的代价 |
|---|---|---|---|
| **A** | 合成 user 消息取 **entry 自己的时间戳**，不是 `now()` | pi `messages.ts:141-160` 的 `timestamp: m.timestamp` —— 这些消息是从**已落盘**的 entry 投影出来的（compaction/branchSummary/custom 三支）。只有**活提示词**（`agent.ts:422`）与**摘要请求**（`compaction.ts:582`）用 `Date.now()`。设计稿 §4.1 只写了后者 | 投影消息会拿到「读出来的时刻」而不是「写下去的时刻」⇒ 与 pi 的 `estimate.ts` 守卫和会话活动时间判据都不一致 |
| **B** | **不留 7 参兼容构造器**（全参必须完整） | 留了就会让「忘了给时间戳」在编译期静默通过。代价是一次性改 13 处（6 个模块） | 未来新增字段时同样要全量改一遍 |
| **C** | 步骤 2 与 3 合并成一次提交 | 只有 codec、没有消费者的一次提交＝一个零调用者的类 | 提交粒度与步骤编号不再一一对应 |
| **D** | `StreamPartialBuilder` 的两个原语放进**步骤 4**（不在步骤 2） | 它们的使用者那一刻才出现；提前加＝造一段死代码 | 无 |
| **E** | **G4 的观察面是 `text_end` 的 `partial`**，不是终局 reason | pi `:590` 的终局映射会**覆盖** phase 设的 stop（本仓 `ResponsesStreamProcessor:228` 同形）⇒ 「最终 reason 是 stop」这个断言没有判别力（改造前也是 stop）。夹具因此钉 `text_end`，并配一条反面对照 | 若哪天终局映射改了，这条夹具会随之失效 |
| **F** | `done` 的**空 content 不覆盖**（pi 无条件覆盖） | 见 §4.2 的偏差说明：防「收尾不带 content 的 provider」把正文清空。夹具双向钉住 | 与 pi 在这一支上行为不同（登记如下） |
| **G** | Responses 回放的 item **分组**维持「reasoning ⇒ 文本 ⇒ 工具调用」，不跟 pi 的**块序** | pi 在同一个循环里按块 push；本仓按类型分组。改它会动一条与本包无关的既有形状 ⇒ 登记 **B147** | 块序敏感的 provider（罕见）可能看到不同顺序 |
| **H** | `ConformanceRunner` 的桩消息时间戳传 `null` | L5 比对的是帧序，pi 录制里没有可复现的时间戳；桩给值反而造出对不上的帧 | 无 |

### 12.5 教训

1. **`-fae` 会连 `clean` 一起跳过「依赖了失败模块」的模块**。首次 `clean test-compile -fae` 时 coding-agent 编译失败 ⇒ **web 模块整个被 SKIP**（它的 `target/` 里还是 17:00 的旧 class）；我随后那次 `test-compile` **漏写了 `clean`**，于是 Maven 对着陈旧 class 报「Nothing to compile - all classes are up to date」，一路装绿到运行时才以 `NoSuchMethodError` 现形。
   ⇒ **判据**：`NoSuchMethodError` 指向**本仓另一个模块**的构造器时，先比对 `target/**/*.class` 与源码的 **mtime**，别信「up to date」。
2. **给 record 加分量会从三个方向打穿**：主源码的 record **解构模式**、跨模块的**全参**构造调用、以及下游模块**未重编的旧 class**（只有第三种在运行期才现形）。
3. **`mvn -Dtest='A+B'` 的 `+` 不是分隔符**（M4 第一次跑就撞上：一个用例没跑、`BUILD SUCCESS`、连 `Tests run` 行都不打）—— 该坑 `docs/57` 已记，本包第三次兑现。
4. 「夹具写在实现之后」在本包**没有重犯**：步骤 1/2 编译红、步骤 3/4 真红。

### 12.6 回归与门禁

- **全 reactor `mvn -o clean test`：14/14 SUCCESS**（telemetry 31 ／ ai **1344** ／ agent-core **534** ／ sqlite 35 ／ coding-agent 319 ／ TUI ／ protocol ／ client ／ server ／ web ／ evals 44（18 skip）／ dist）
- ai 1312 ⇒ **1344**（+32：Estimate 3、TextSignatureV1 10、ResponsesTextSignatureReplay 11、ResponsesOutputItemDoneCapture 8）
- agent-core 527 ⇒ **534**（+7：MessageTimestampRoundTrip 6、HarnessUtilsUserTimestamp 1）
- 三个模块的既有夹具按 8 参构造适配：coding-agent 4 处、web 3 处、sqlite 2 处

### 12.7 门禁对照（§10）

| # | 门槛 | 结果 |
|---|---|---|
| 1 | RED 先于实现 | 步骤 1/2 编译红；步骤 3 **8/11**、步骤 4 **5/8** 真红 |
| 2 | GREEN | 目标夹具全绿（见 §12.6 的增量） |
| 3 | 变异 M1–M7 记录红集 | §12.3（无零红） |
| 4 | 回归全绿；checkstyle／spotbugs 0 新违规 | ✅（全 reactor clean test 14/14；见 §12.8 的 verify 记录） |
| 5 | 无调试残留；改动文件 ≤500 | ✅（最大新增 `ResponsesOutputItemDoneCaptureTest` 约 250 行） |
| 6 | §12 与 docs/48 §5 行 E 在收尾提交中回填 | ✅ —— 行 E 是清单**最后一项**，闭环后 `docs/48 §5` 无 `⬜` |

### 12.8 新登记

| # | 内容 | 处置 |
|---|---|---|
| **B147** | **Responses 回放的 item 分组与 pi 的块序不同** —— pi 在一个循环里按**块序** push（reasoning/文本/工具调用交错），本仓按「reasoning ⇒ 文本 ⇒ 工具调用」分组（`ResponsesMessageConverter.addAssistantItems` 的注释已写明） | 本包**不动**（与 G2 无关的既有形状）；块序敏感的 provider 极少，但若哪天要 1:1 对齐，改这里 |
| **B148** | **`responseModel`/`diagnostics` 的移植条件** —— pi 有真消费者（`usage-totals.ts:44`／`cache-warmer.ts:345`／`bug-report.ts:194`／`interactive-mode.ts:3917`），但那些模块**本仓不存在** ⇒ 字段维持不移植，**等模块移植时一并做** | `Message.java` 的 javadoc 已按此更正（旧措辞「pi 没有任何消费者」是错的）；`providerThinkingLevel` 的同族更正挂 **B99**（真理由是 SDK 写不出 `block_binding`，不是「无消费者」） |
| **B23** | **收窄**：`TextContent` **已有** `textSignature` 组件（Batch F 加的），原文「只有 1 个组件」作废；`ResponsesMessageConverter` 里那句「carries no signature at all (B23)」已在步骤 3 删掉 | 本包已改注释；B23 只剩「Responses 车道曾经不用它」这半句，**结案** |

### 12.9 未覆盖

- **web/RPC wire 仍不带消息 `timestamp`**（R2：维持 `WebWireJson.java:58-59` 的既有刻意偏离，改动要连 client 侧一起动）。
- **`Model.promptCache` ＋ CacheWarmer**（R7：整块子系统，另立包）。
- **`error.metadata.raw`（B135）**（R7：需先移植 `error-body.ts` 的错误体归一化层，另立包）。
- **`metadata`/`user_id`**（R7：pi 自己都没有生产者 ⇒ 不做）。

