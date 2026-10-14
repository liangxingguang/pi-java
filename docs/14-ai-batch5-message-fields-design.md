# 14 ai 批 5 详稿：消息字段与终局计量（B148 responseModel/diagnostics ＋ B234 durationMs ＋ 消息级 thinkingLevel）

> 状态：**✅ 已闭环（2026-10-15）**。commits `dcb74b43`（ai）、`de0e3a1d`（agent-core）、`13f00b41`（coding-agent/web）。
> 全量 `mvn clean verify` BUILD SUCCESS：ai 1638／agent-core 674／coding-agent 547／tui 213／web 53 测；
> 加权 92.2%→**92.7%**（#37 +1 ⇒ 203.0/219）。
> 基准：pi `ea448f454`（2026-10-11，v1.1.0-22）；pi 证据一律 `git show ea448f454:<path>`，不读工作树。
> 上游判据：`bin pi` 主流发布版行为。
> 路线：[`08`](08-ai-layer-alignment-roadmap.md) §3 批 5＝台账 **B148 ＋ B234 ＋消息级 thinkingLevel**（B110 残留半）。
> 前序：[`13`](13-ai-batch4-thinking-effort-design.md)（批 4 已闭环；`providerThinkingLevel` 响应侧盖章在 ai/JSONL/JSON 终局帧/pi-messages 四线已通，本批补齐消息字段其余四件）。
> SDK：anthropic-java **2.66.0**（探针实测：未知块经 `_json()` 逃生槽可达；该制品 JsonValue 继承裸类型 JsonField，raw 读取走 `convert(JsonNode.class)`）；openai-java 4.42.0。

---

## 0. 范围一览

| 条目 | pi 行为 | 本批动作 |
|---|---|---|
| **B148a** `responseModel` | 流内生产者两家：anthropic `message_start.model` 与请求 id 不同即写（并按 `compat.allowedFallbackModels[].cost` 归属成本）；openai-completions 首个不同的非空 `chunk.model`（`||=` 首值胜） | 两层 AssistantMessage 加字段；两条车道流内触点；anthropic fallback 计价 |
| **B148b** `diagnostics` | 三类流内诊断：anthropic `anthropic_input_transformations`（末值胜）、anthropic 中段模型回退**硬错**（`"fallback"` 块在已有内容后）、pi-messages `pi_messages_response_failure`（HTTP 错误信封，body 8192+U+2026 截断）＋ `pi_messages_rewrite`（网关重写摘要） | 诊断类型＋builder 累加器＋三个生产者；JSONL/两条 wire 投影 |
| **B148c** 中段回退块 | `content_block_start` 的 `"fallback"` 块：**无内容时跳过**、已有内容时抛 `Anthropic performed an unsupported mid-output model fallback` | anthropic 车道事件分发最前置 raw 判定（SDK 无 typed 变体，走 `_json()`，实施第 0 步探针 settle） |
| **B234a** 响应计时 | `AssistantMessageEventStream` 自建单调钟：done/error/end(result) 的终局消息盖 `durationMs`（`Math.max(0, round(now-start))`）；已有值保留；消息时间戳早于本流（deferred 取回）不盖 | 身份缝同生命周期加盖（`AbstractChatApi`），不进 builder |
| **B234b** 工具计时 | `executePreparedToolCall` 单调钟**只围 `tool.execute()`**（成功/抛错都有，Math.round），不含校验/before·after 钩子；未执行（unknown/校验失败/blocked/执行前 abort/截断）无字段；进 `AgentToolCallOutcome` → `tool_execution_end?` 与 `ToolResultMessage.durationMs?` | ToolRegistry 新增 `executeTimed`（审批/校验/prepareArguments 在钟外）；事件/消息/wire 透传；**TUI Took 卡不做（§4 裁决⑦）** |
| **thinkingLevel** | agent-loop 在终局结果上盖**请求侧** pi 级别：`Object.assign(await response.result(), { thinkingLevel: config.reasoning ?? "off" })`（agent-loop.ts:409），含 `"off"`；流中 partial 不盖、start 帧克隆不复制 | 只在**会话记录层**加字段；`PiLoopRunner` 终局定案后单点盖 `config.thinking().label()`（null→`"off"`）；JSONL/wire 跟随 |

**加权影响**：B148/B234 是台账行为项（B 编号，不占单元）；`#37 AssistantMessage 字段` 在批 0 账面推演里计 **+1**（responseId/endTurn 随 codex R5 出分母，本批落 responseModel/diagnostics/durationMs/thinkingLevel 四件）⇒ wc 202.0→**203.0/219＝92.7%**。销号回填 docs/04、docs/06、docs/08、map/02。

**与批 4 的边界**：`providerThinkingLevel`（响应侧 provider-native effort）批 4 已落；本批的 `thinkingLevel` 是**请求侧 pi 级别**（off/minimal/…/max），两个字段、两个生产者，勿混。

---

## 1. pi 取证（逐字）

### 1.1 消息字段定义（`packages/ai/src/types.ts:567-596`、`:625-636`）

```ts
export interface AssistantMessage {
	role: "assistant";
	content: (TextContent | ThinkingContent | ToolCall)[];
	api: Api;
	provider: ProviderId;
	model: string;
	responseModel?: string; // Concrete model reported by the provider when different from the requested `model`
	responseId?: string; // Provider-specific response/message identifier when the upstream API exposes one
	/** Exact provider-native effort level used for this response. Absent for legacy or unmanaged responses. */
	providerThinkingLevel?: string;
	/** Pi thinking level the agent loop requested for this response. Absent outside the agent loop and for legacy responses. */
	thinkingLevel?: ModelThinkingLevel;
	diagnostics?: AssistantMessageDiagnostic[]; // Redacted provider/runtime diagnostics for failures and recoveries.
	usage: Usage;
	stopReason: StopReason;
	deferred?: DeferredHandle;
	errorMessage?: string;
	rawStopReason?: string;
	endTurn?: boolean;
	/** Unix timestamp in milliseconds when the request started. */
	timestamp: number;
	/**
	 * Milliseconds from `timestamp` until the response ended, measured with a monotonic clock. Set by
	 * `AssistantMessageEventStream` on the final message of a response it saw start; absent for legacy messages and
	 * for deferred results fetched later.
	 */
	durationMs?: number;
}
```

`ToolResultMessage`（`:635`）：`/** Milliseconds the tool's execution took, measured with a monotonic clock. Absent for legacy results. */ durationMs?: number;` —— 位置在 `timestamp` 之后。

键序即声明序：`model, responseModel?, responseId?, providerThinkingLevel?, thinkingLevel?, diagnostics?, usage … timestamp, …, durationMs?`。
`ModelThinkingLevel = ["off", "minimal", "low", "medium", "high", "xhigh", "max"]`（`providers/model-schema.ts:3-10`）。
**responseId/endTurn 本批不做**（docs/08 §2.4：随 codex 车道 R5 出分母）。

start 帧的字段克隆白名单（`utils/assistant-message-frame.ts:77-92` `cloneStartMessage`）：

```ts
		...(message.responseModel === undefined ? {} : { responseModel: message.responseModel }),
		...(message.responseId === undefined ? {} : { responseId: message.responseId }),
		...(message.providerThinkingLevel === undefined ? {} : { providerThinkingLevel: message.providerThinkingLevel }),
		...(message.diagnostics === undefined ? {} : { diagnostics: structuredClone(message.diagnostics) }),
```

**`thinkingLevel` 与 `durationMs` 不在 start 帧**——前者是终局后才盖，后者只盖终局消息。

### 1.2 diagnostics 类型与工具函数（`packages/ai/src/utils/diagnostics.ts` 全文 46 行）

```ts
export interface DiagnosticErrorInfo {
	name?: string;
	message: string;
	stack?: string;
	code?: string | number;
}

export interface AssistantMessageDiagnostic {
	type: string;
	timestamp: number;
	error?: DiagnosticErrorInfo;
	details?: JsonObject;
}

export function extractDiagnosticError(error: unknown): DiagnosticErrorInfo {
	if (!(error instanceof Error)) return { name: "ThrownValue", message: formatThrownValue(error) };
	const code = (error as Error & { code?: unknown }).code;
	return {
		name: error.name || undefined,
		message: error.message || error.name,
		stack: error.stack,
		code: typeof code === "string" || typeof code === "number" ? code : undefined,
	};
}

export function createAssistantMessageDiagnostic(
	type: string, error: unknown, details?: JsonObject,
): AssistantMessageDiagnostic {
	return { type, timestamp: Date.now(), error: extractDiagnosticError(error), details };
}

export function appendAssistantMessageDiagnostic<T extends { diagnostics?: AssistantMessageDiagnostic[] }>(
	message: T, diagnostic: AssistantMessageDiagnostic,
): void {
	message.diagnostics = [...(message.diagnostics ?? []), diagnostic];
}
```

要点：`error`/`details` 都可缺（rewrite 诊断只有 type/timestamp/details）；`stack` 随诊断落盘（bug-report 消费者 B79 另案）。

### 1.3 响应计时（`packages/ai/src/utils/event-stream.ts:91-130`）

```ts
export class AssistantMessageEventStream extends EventStream<AssistantMessageEvent, AssistantMessage> {
	readonly #startedAt = Date.now();
	readonly #startedAtMonotonic = performance.now();
	// …
	override push(event: AssistantMessageEvent): void {
		if (event.type === "done") this.#time(event.message);
		else if (event.type === "error") this.#time(event.error);
		super.push(event);
	}
	override end(result?: AssistantMessage): void {
		if (result !== undefined) this.#time(result);
		super.end(result);
	}
	#time(message: AssistantMessage): void {
		if (this.done || message.durationMs !== undefined || message.timestamp < this.#startedAt) return;
		message.durationMs = Math.max(0, Math.round(performance.now() - this.#startedAtMonotonic));
	}
}
```

四个守卫（夹具 `packages/ai/test/event-stream.test.ts:121-170` 钉死）：
1. 终局只盖一次（`this.done`：晚到的第二条终局不盖）；
2. 已有 `durationMs` 保留（**转发流保留内层测量**，outer stream 不覆盖 inner）；
3. `timestamp < startedAt` 不盖（deferred 稍后取回的结果、别处开打的流）；
4. 起点＝**流对象创建**（`stream()` 调用时），不是首帧、不是订阅时；done/error/end(result) 三个入口同法。

CHANGELOG（`packages/ai/CHANGELOG.md:17-18`）：「every API implementation, including direct calls. Deferred results fetched later stay untimed」。

### 1.4 anthropic 车道三流内触点（`packages/ai/src/api/anthropic-messages.ts`）

**(a) message_start：responseModel ＋ fallback 成本归属（`:666-678`）**

```ts
if (event.type === "message_start") {
	output.responseId = event.message.id;
	const transformations = event.message.input_transformations;
	if (Array.isArray(transformations)) inputTransformations = transformations;
	const responseModel = event.message.model;
	if (responseModel !== model.id) output.responseModel = responseModel;
	const fallbackCost =
		responseModel === model.id
			? undefined
			: model.compat?.allowedFallbackModels?.find(
					(fallback) => fallback.provider === model.provider && fallback.model === responseModel,
				)?.cost;
	usageModel = fallbackCost ? { ...model, id: responseModel, cost: fallbackCost } : model;
	// …随后 calculateCost(usageModel, output.usage)
```

- responseModel 与请求 id 相同 ⇒ **不写键**；
- 计价模型切换条件：响应 id 不同 **且** `allowedFallbackModels` 里有同 provider 同 model 的条目 ⇒ 用条目的 `cost`；找不到条目仍写 responseModel，但**计价沿用请求模型**；
- `message_start` 与每次 `message_delta` 的 `calculateCost` 都用 `usageModel`。

**(b) content_block_start 的 `"fallback"` 块（`:689-695`，最前置判定）**

```ts
} else if (event.type === "content_block_start") {
	if (event.content_block.type === "fallback") {
		if (output.content.length > 0) {
			throw new Error("Anthropic performed an unsupported mid-output model fallback");
		}
		continue;
	}
	if (event.content_block.type === "text") { … }
```

无内容 ⇒ `continue`（块不入 content，不发事件；responseModel 已在 message_start 盖过）；有内容 ⇒ 抛进外层 catch ⇒ error 终局，errorMessage 即这句原文，已流内容保留。

**(c) input_transformations 诊断（`:815-817` ＋ `:871-885`）**

```ts
} else if (event.type === "message_delta") {
	const transformations = event.input_transformations;
	if (Array.isArray(transformations)) inputTransformations = transformations;
	// …
// 收尾，stop 检查通过、push done 之前：
if (inputTransformations && inputTransformations.length > 0) {
	appendAssistantMessageDiagnostic(output, {
		type: "anthropic_input_transformations",
		timestamp: Date.now(),
		details: {
			transformations: inputTransformations.map((transformation) => ({
				type: transformation.type ?? undefined,
				path: transformation.path ?? undefined,
				reason: transformation.reason ?? undefined,
			})),
		},
	});
}
```

`message_delta.input_transformations`（事件**顶层**，不在 delta 里）是数组就**整体替换** start 的列表；夹具名「uses the serving model input transformations **from the final stream event**」（`anthropic-sse-parsing.test.ts:317-357`：start 一条、delta 一条，诊断只见 delta 那条）。诊断只在成功收尾路径产出（pending/error/aborted 的 throw 都在它之前）。

### 1.5 openai-completions 车道：chunk.model（`openai-completions.ts:563-567`）

```ts
for await (const chunk of openaiStream) {
	await options?.onProviderStreamEvent?.(chunk, model);
	if (!chunk || typeof chunk !== "object") continue;
	output.responseId ||= chunk.id;
	if (typeof chunk.model === "string" && chunk.model.length > 0 && chunk.model !== model.id) {
		output.responseModel ||= chunk.model;
	}
```

首值胜（`||=`）；空串/缺失忽略；与请求 id 相同不写。**responseId 不做**。夹具 `openai-completions-response-model.test.ts` 三例（路由模型上 responseModel 且 model/provider 不变／回显同 id 无键／空 model 忽略）。

### 1.6 pi-messages 车道：失败信封 ＋ rewrite 诊断

**HTTP 错误详情对象（`pi-messages.ts:91-152`）**

```ts
export class PiMessagesResponseError extends Error {
	code?: string;
	readonly diagnosticDetails: JsonObject;
	constructor(message: string, code: string | undefined, diagnosticDetails: JsonObject) { … }
}

function truncateDiagnosticString(value: string): string {
	const maxLength = 8192;
	return value.length > maxLength ? `${value.slice(0, maxLength)}…` : undefined : value;
}

function createPiMessagesResponseError(model, url, response, body): PiMessagesResponseError {
	const errorBody = parsePiMessagesErrorBody(body);
	const code = typeof errorBody?.error?.code === "string" ? errorBody.error.code : undefined;
	return new PiMessagesResponseError(formatPiMessagesResponseError(response, body, errorBody), code, {
		version: 1,
		provider: model.provider,
		model: model.id,
		url: url.toString(),
		status: response.status,
		statusText: response.statusText,
		...(errorBody?.error === undefined ? {} : { error: errorBody.error as JsonValue }),
		...(errorBody ? {} : { body: truncateDiagnosticString(body) }),
		timestampMs: Date.now(),
	});
}
```

- errorBody 解析成功（根对象、`error` 是非数组对象）⇒ details 带**原始 error 节点**；解析失败 ⇒ 带 `body`（8192 截断，尾字符 U+2026 `…`，**不是** `... [truncated N chars]`——那是另一条 4000 字符的错误体规则）；
- errorMessage 文案＝B224 已对齐的 `"<status> <statusText>: <message ?? body> (<code>?)"` 半。

**错误信封（`:318-340` `createErrorEvent`）**

```ts
if (!aborted && error instanceof PiMessagesResponseError) {
	appendAssistantMessageDiagnostic(
		assistantMessage,
		createAssistantMessageDiagnostic("pi_messages_response_failure", error, error.diagnosticDetails),
	);
}
```

中止不附诊断；**流内 `error` SSE 事件不附诊断**（夹具 `pi-messages.test.ts:218-245`：401 HTTP 故障有诊断且 details.status=401；服务器推的 error 事件无诊断）。

**rewrite 诊断（`:165-180`，done/error 两个转换分支都调）**

```ts
function appendRewriteDiagnostic(message: AssistantMessage, rewrite: PiMessagesRewriteImpact | undefined): void {
	if (!rewrite) return;
	appendAssistantMessageDiagnostic(message, {
		type: "pi_messages_rewrite",
		timestamp: Date.now(),
		details: { ...rewrite },
	});
}
```

`pi-messages.ts:71-87` 的 done/error wire 已带 `rewrite?`（Java `PiMessagesEvent`/`RewriteImpact` 已解析，**当前无消费者**——本批接通）。该类型 pi 仓无夹具，按代码自证。

### 1.7 工具计时（`packages/agent/src/agent-loop.ts`）

结果类型（`:674-679`、types.ts:449-455）：

```ts
type ExecutedToolCallOutcome = {
	result: AgentToolResult<any>;
	isError: boolean;
	durationMs: number;
};
export interface AgentToolCallOutcome {
	toolCall: AgentToolCall;
	result: AgentToolResult<any>;
	isError: boolean;
	/** Milliseconds `execute()` took, measured with a monotonic clock; absent when the tool did not run. */
	durationMs?: number;
}
```

钟只围 `tool.execute`（`:836-855`），停钟在 `await Promise.all(updateEvents)` **之前**：

```ts
	const startedAt = performance.now();
	const elapsed = () => Math.round(performance.now() - startedAt);
	try {
		const result = await prepared.tool.execute(
			prepared.toolCall.id, prepared.args as never, signal,
			(partialResult) => { if (!acceptingUpdates) return; updateEvents.push(…); },
		);
		const durationMs = elapsed();
		acceptingUpdates = false;
		await Promise.all(updateEvents);
		return { result, isError: result.isError === true, durationMs };
	} catch (error) {
		const durationMs = elapsed();
		acceptingUpdates = false;
		await Promise.all(updateEvents);
		return { result: createErrorToolResult(…), isError: true, durationMs };
	}
```

`finalizeExecutedToolCall` 原样透传 `durationMs: executed.durationMs`（afterToolCall 钩子耗时**不在内**；钩子抛错也保留执行时长，`:866-912`）。

出口两处，可选展开（`:918-942`）：

```ts
await emit({ type: "tool_execution_end", …, isError,
	...(finalized.durationMs === undefined ? {} : { durationMs: finalized.durationMs }) });
// …
return { role: "toolResult", …, isError,
	...(finalized.durationMs === undefined ? {} : { durationMs: finalized.durationMs }),
	timestamp: Date.now() };
```

**没有 durationMs 的出口**（immediate outcomes，逐分支核过）：unknown tool、参数校验/prepareArguments 失败、beforeToolCall block、两处执行前 abort 检查、并行任务执行时已 abort 的 `"Operation aborted"`、length 截断整批（`failToolCallsFromTruncatedMessage:486-505` 直接造 outcome 无钟）。

事件面（`packages/agent/src/types.ts:531-539`）：

```ts
| { type: "tool_execution_end"; toolCallId: string; toolName: string;
    result: any; isError: boolean;
    /** Milliseconds `execute()` took, measured with a monotonic clock; absent when the tool did not run. */
    durationMs?: number; };
```

coding-agent 扩展事件逐字转发（`agent-session.ts:1350-1360`，`...(event.durationMs === undefined ? {} : { durationMs: event.durationMs })`）；
JSON 线文档 `packages/coding-agent/docs/json.md:107`：`tool_execution_end | toolCallId, toolName, result, isError, durationMs`；
夹具 `packages/agent/test/agent-loop.test.ts:403-455`（#10549）：execute sleep 30ms、beforeToolCall sleep 100ms ⇒ ran.durationMs ∈ [25,100)（**不含钩子**）；blocked 调用 isError=true 且**无** durationMs 属性。

### 1.8 消息级 thinkingLevel（agent-loop.ts:409、`:439-453`）

```ts
// Record the requested level, whichever stream function answered.
const result = async () => Object.assign(await response.result(), { thinkingLevel: config.reasoning ?? "off" });
// …
case "done":
case "error": {
	const finalMessage = await result();
	// …替换 context 槽位…
	await emit({ type: "message_end", message: finalMessage });
	return finalMessage;
}
```

- 值＝`AgentLoopConfig.reasoning`（ThinkingLevel，无 off 档）**空时恒 "off"**——所以每条经 agent loop 的助手消息（含 error/aborted）都有该键；
- 只改终局结果对象；流中 message_update 不盖、start 帧克隆不复制（§1.1）；
- 消费面：pi 仓 tui/rpc/agent-loop **零按名读取**，语义是持久化记录（message-types.md:131/143）。

---

## 2. Java 现状（触点盘点）

### 2.1 已存在（不用重写）

| 能力 | 位置 |
|---|---|
| fallback 元数据（B98 请求半已落） | `catalog/AllowedFallbackModel(provider, model, PricingInfo cost)`、`ModelCompat.allowedFallbackModels()`、`CompatResolver.forAnthropic(m, baseUrl)`；内置 `claude-fable-5` 目录带两条许可（`AnthropicFallbacksWireTest`） |
| 计价入口 | `model/CostCalculator.calculateCost(PricingInfo, Usage)`；`AnthropicUsageState` 当前只持 `ModelInfo`，reprice 用 `model.pricing()` |
| RewriteImpact 已解析无消费 | `protocol/RewriteImpact.java`、`PiMessagesEvent.Done/Error.rewrite()` |
| pi-messages 错误文案半（B224） | `utils/ProviderErrors.formatPiMessagesError(status, body)`；private `piMessagesErrorNode`、private `reasonPhrase` |
| 身份缝（每流一 timestamp） | `protocol/AbstractChatApi.java:88-114`（stream）、`:208-258`（IdentitySubscriber）、`:134-164`（streamBlocking 两处合成终局） |
| HTTP 异常原料 | `http/PiHttpException.statusCode()/body()`（JDK 客户端无 reason phrase，ProviderErrors 有 RFC 短语表） |
| 请求侧级别 | `PiLoop.Config.thinking()`（`ModelThinkingLevel`，含 `Off`）、`HarnessConfig.thinkingLevel`；`ModelThinkingLevel.label()` 即 `"off"`/级别名（`thinking/ModelThinkingLevel.java:48-53`） |
| 单调钟先例 | 全仓直接 `System.nanoTime()`（`PiLaneSink.java:200/252/494` 等），无公共 StopWatch |
| 可观测性 durationMs（**不同口径**） | `PiLaneSink.toolStartNanos` 从 prepare 前量到 message_end（含钩子/审批/排队）→ LaneRecord/OTel；**不进 PiLoop 事件**。本批不替换它 |

### 2.2 要改的文件（按模块）

**pi-java-ai**

1. `message/AssistantMessage.java`（流式，11→14 组件）：加 `String responseModel`、`List<AssistantMessageDiagnostic> diagnostics`、`Long durationMs`（位置贴 pi 键序，见 §3.1）；全参/兼容构造、全部 wither 同步；
2. `message/Message.java`（会话记录，AssistantMessage 11→15 组件）：加 `String responseModel`、`String thinkingLevel`、`List<AssistantMessageDiagnostic> diagnostics`、`Long durationMs`；`ToolResultMessage` 8→9 组件加 `Long durationMs`；`fromPartial` 携带（thinkingLevel 恒 null）；兼容构造保留；更新 `:159-179` 五字段裁决注释（responseModel/diagnostics 移出、thinkingLevel 移出）；
3. **新** `message/AssistantMessageDiagnostic.java`：`(String type, long timestamp, DiagnosticErrorInfo error, JsonNode details)`；同文件嵌套或邻文件 `DiagnosticErrorInfo(String name, String message, String stack, JsonNode code)`；
4. `stream/StreamPartialBuilder.java`：`responseModel` 字段＋`noteResponseModel`（首值胜）、`diagnostics` 列表＋`appendDiagnostic`、`hasContentBlocks()`；snapshot 携带；
5. `protocol/AbstractChatApi.java`：响应计时（§3.3）；
6. `protocol/AnthropicMessagesApi.java`＋`protocol/AnthropicUsageState.java`：§3.4 三触点；
   **新逻辑落新文件** `protocol/AnthropicResponseExtras.java`（包私有，持 servingPricing/transformations，
   含 fallback 查找、raw JsonNode→三键投影、诊断组装）——AnthropicMessagesApi 现 543 行已过 500 规约，
   内联会到 ~590 行；AnthropicUsageState 108 行可收 pricing override 入参；
7. `protocol/OpenAICompletionsApi.java`：chunk.model 触点；
8. `protocol/PiMessagesApi.java`＋`utils/ProviderErrors.java`：§3.6 信封/rewrite；
9. `api/TransformMessages.java`：`gateAssistant:247` 与两处 tool 重写（`:188/:200`）转发新字段（pi 是 `{...msg}` 展开，全字段保留）。

**pi-java-agent-core**

10. `tool/ToolRegistry.java`：新增 `executeTimed`（sealed `Execution.Success/Failure`，钟只围 raw execute）；旧 `execute` 委托解包保持兼容；
11. `harness/PiToolRunner.java:145-181`：消费 `Execution`，duration 透传到 `toOutcome`（加 `Long durationMs` 参；immediate/errorOutcome 传 null，after 钩子抛错保留执行时长）；
12. `harness/PiLoop.java:74-76`：`Event.ToolExecutionEnd` 加 `Long durationMs`（保留 4 参兼容构造）；
13. `harness/PiLoopTools.java`：4 个发射点（:105/:159/:173/:257）从 `outcome.message().durationMs()` 取值；截断/abort 两处手工消息走 8 参兼容构造（null 时长）；
14. `harness/PiLoopRunner.java:301`：markAborted 之后单点盖 thinkingLevel（§3.8）；
15. `session/SessionJson.java:63-139`：assistant 写 responseModel/thinkingLevel/diagnostics/durationMs、tool 写 durationMs（显式判空，键序 §3.1）；
16. `session/jsonl/MessageJsonCodec.java:46-68`：decode 四新键（optionalString/optionalLong/diagnostics `treeToValue`）；
17. `session/ContextEntries.java:242-249`：assistant 重投影转发新字段（tool 侧同步）。

**pi-java-coding-agent**

18. `core/AgentSessionEvent.java:148-149`：`ToolExecutionEnd` 加 `Long durationMs`（保留 4 参；更新「一个 `?` 都没有」旧注释——#10549 后 pi 加了可选键）；
19. `core/SessionRunner.java:241-244`：转发 durationMs；
20. `mode/JsonEventMapper.java:199-206`：tool 帧非 null 时写 `durationMs`；`messageNode:226-251` 经 valueToTree 自动流新消息字段（null 被 `isAbsentOptional` 兜底省略），无需逐键投影；`terminalWireNode:353-389` **不动**（§4 裁决⑥）。

**pi-java-web**

21. `WebWireJson.java:26-90`：assistant 支补 responseModel/providerThinkingLevel（**顺带补批 4 漏同步**）/thinkingLevel/diagnostics；tool 支补 durationMs；`assistantNode:93-101` 流式投影保持精简不动；
22. `AgentEventTranslator.java:179-187`：tool 帧补 durationMs（与 JsonEventMapper 两份手写帧）。

**测试**（§5）；**pi-java-tui 零改动**（§4 裁决⑦）；protocol/server 零触点（TranscriptItem 是 Map 袋子；server 不读消息字段）。

---

## 3. 设计

### 3.1 记录形状与键序

流式 `com.pijava.ai.message.AssistantMessage`（无 thinkingLevel）：

```java
public record AssistantMessage(
    String id,
    List<ContentBlock> content,
    StreamEvent.UsageInfo usage,
    StopReason stopReason,
    String api,
    String provider,
    String model,
    String responseModel,            // 新：B148
    String providerThinkingLevel,    // 批 4
    List<AssistantMessageDiagnostic> diagnostics, // 新：B148
    Instant timestamp,
    String errorMessage,
    String rawStopReason,
    Long durationMs                  // 新：B234
) { … }
```

会话记录 `Message.AssistantMessage`：

```java
record AssistantMessage(
    List<ContentBlock> content,
    StopReason stopReason,
    DeferredHandle deferred,
    String api, String provider, String model,
    String responseModel,                 // 新
    String providerThinkingLevel,         // 批 4
    String thinkingLevel,                 // 新：请求侧 pi 级别（含 "off"）
    List<AssistantMessageDiagnostic> diagnostics, // 新
    com.pijava.ai.Usage usage,
    Instant timestamp,
    String errorMessage,
    String rawStopReason,
    Long durationMs                       新
) implements Message { … }
```

`Message.ToolResultMessage` 末组件加 `Long durationMs`（8 参兼容构造保留，旧盘/合成路径零改签）。

纪律同批 4：nullable 新组件全部「**null ≙ pi 的 undefined**」；旧的 4 参/10 参/11 参/8 参便捷构造保留并委托 null。record 组件声明序决定 coding-agent `messageNode` 的 valueToTree 字段序——按 pi 声明序放置即逐字对齐。

### 3.2 诊断类型

```java
/** pi packages/ai/src/utils/diagnostics.ts 的镜像。 */
public record AssistantMessageDiagnostic(
    String type, long timestamp,
    DiagnosticErrorInfo error,          // null ≙ error 键缺席（rewrite 诊断）
    JsonNode details                    // null ≙ details 键缺席；ObjectNode/ArrayNode 原样保真
) {}

public record DiagnosticErrorInfo(
    String name, String message, String stack, JsonNode code) {}
```

- `timestamp` 用 primitive long（诊断元数据，pi 恒 `Date.now()`，不存在旧盘缺席态；decode 缺席给 0 与 pi `JSON.parse` 的 undefined 同语义：不参与任何行为）；
- `details` 用 **JsonNode 不用 Map**：网关 error 节点要逐字节保留未知键（pi 是 `as JsonValue` 直挂），Jackson 序列化/JSONL/`valueToTree` 全链路透传；
- `code` 同理用 JsonNode（pi 类型 `string | number`）；
- 小工厂放同文件：`fromError(String type, Throwable t, JsonNode details)`（name 取异常类简单名、message 兜底 toString、stack＝JDK `StringWriter/PrintWriter` 栈串、code 由调用方按解析结果给——`PiHttpException` 自身无 code 概念）。

builder 侧累加（与 pi 同语义：每次快照拿**不可变拷贝**）：

```java
public void appendDiagnostic(AssistantMessageDiagnostic diagnostic) {
    var copy = new ArrayList<>(diagnostics == null ? List.of() : diagnostics);
    copy.add(diagnostic);
    this.diagnostics = List.copyOf(copy);
}
public boolean hasContentBlocks() { return !blocks.isEmpty(); }
```

### 3.3 响应计时缝（AbstractChatApi）

不加进 `StreamPartialBuilder`（builder 是纯累加器、conformance 脚本直接用它；计时是**流生命周期**职责）。在 `stream()` 与 IdentitySubscriber 同生命周期建一个小计时器：

```java
// stream() 内，与 timestamp 同时：
final long startNano = System.nanoTime();
// IdentitySubscriber 构造多收 (startNano, timestamp) 两个参数
```

`onNext` 现有身份分支内，终局事件在 withTerminalUsage 同处加盖：

```java
if (event instanceof StreamEvent.StreamDone || event instanceof StreamEvent.StreamError) {
    identified = stampDuration(identified, startNano, timestamp, terminalDone);
}
```

```java
/** 对位 event-stream.ts #time 的后两个守卫（this.done 由「终局只来一次」的流契约保证，晚到终局在 publisher 关闭后不可达）。 */
private static AssistantMessage stampDuration(AssistantMessage m, long startNano, boolean firstTerminal) {
    if (!firstTerminal || m.durationMs() != null) return m;          // 已有值保留（转发流）
    // timestamp 是本缝刚挂的 startedAt（恒等于流起点）⇒ 「timestamp < startedAt」一支在缝管事件上不可达；
    // 早于本流的消息只可能走 api!=null 的生产者成形通道，那条路整体不经本缝（与身份挂载同界）。
    long ms = Math.max(0L, Math.round((System.nanoTime() - startNano) / 1_000_000.0));
    return m.withDurationMs(ms);
}
```

- `streamBlocking` 的两处合成终局（onError/onComplete 的 `identityBase` 消息，:143-161）：在方法入口建同款钟，settle 后、offer 前加盖（这是 send()/直调路径——CHANGELOG 明言「including direct calls」）；
- `send()` 终局经 `fromPartial` 自动带上；
- `api()!=null` 的生产者成形事件（conformance FauxChatApi 一类）在 onNext 早返回，**不挂身份也不计时**——窄差登记（§4 裁决②）；
- 多订阅者各自经 IdentitySubscriber 加盖，值同源同时钟、各自不可变快照（JSONL 消费者只有 streamBlocking 一个）。

### 3.4 Anthropic 三触点

`streamInternal` 起手取一次 fallback 表（与 buildParams 的 compat 解析同源、各自解析沿用现状）：

```java
var fallbacks = request.model() == null ? null
    : CompatResolver.forAnthropic(request.model(), baseUrl).allowedFallbackModels();
var extras = new StreamExtras();   // 本流可变状态：PricingInfo servingPricing、List<JsonNode> transformations
```

**message_start 分支（mapEvent 内，五字段 noteUsage 之前）**：

```java
var wireModel = event.asMessageStart().message().model().asString();   // SDK 2.66.0 实测有 asString()
if (request.model() != null && !wireModel.equals(request.modelId().modelName())) {
    builder.noteResponseModel(wireModel);
    var fallbackCost = findFallbackCost(fallbacks, request.modelId().provider(), wireModel);
    if (fallbackCost != null) extras.servingPricing = fallbackCost;
}
extras.replaceTransformations(transformationsOf(
    event.asMessageStart().message()._additionalProperties().get("input_transformations")));
usageState.onMessageStart(…五字段…, extras.servingPricing);
```

- `findFallbackCost`：provider 与 model 双等，取 `AllowedFallbackModel.cost()`；
- `AnthropicUsageState` 加 `PricingInfo override` 入参/setter，reprice＝`override != null ? override : model.pricing()`（pi `usageModel` 同效，不造伪 ModelInfo）；
- `input_transformations` 从 stable `Message._additionalProperties()` 取（SDK 只有 beta typed 类型，stable 走 raw map，javap 已实测）；抽取 `{type,path,reason}` 三键、原样保留，非数组/缺席不动当前列表。

**message_delta 分支**：先读 `event._additionalProperties().get("input_transformations")`（**事件顶层**），数组即整体替换；再走现有 stop/usage 逻辑；reprice 带同一 override。

**content_block_start 最前置**（在所有 isToolUse/isThinking 判定之前）：

```java
var rawBlock = event.asContentBlockStart().contentBlock();
if (rawBlock._json().isPresent()
        && "fallback".equals(rawBlock._json().get().asObject().get("type").asString().orElse(null))) {
    if (builder.hasContentBlocks()) {
        throw new IllegalStateException(
            "Anthropic performed an unsupported mid-output model fallback");
    }
    return null;   // pi 的 continue：不建块、不发事件
}
```

> ⚠️ **实施第 0 步：loopback 探针夹具 settle 访问面**（同批 4 beta 头探针先例）。SDK 2.66.0 的 ContentBlock 联合无 `isFallback()`（javap 实测只有 12 个已知变体 + 末尾 `JsonValue _json()` 逃生槽），但需用假 SSE 服务器证实：未知 type 时**不调** `type()`/已知访问器（避免 SDK 先抛），`_json()` 内有原文，type 字段路径可靠。固化为 `AnthropicFallbackBlockProbeTest`。

异常被 `mapEvent` 现有 catch(:424) 收成 emitError——errorMessage 逐字、已流块保留、`stop.errored=true` 保证 submitTail 不补第二个终局（既有机制，无需新代码）。

**收尾（submitTail 成功分支，emitDone 之前）**：`extras.transformations` 非空 ⇒ `builder.appendDiagnostic(new AssistantMessageDiagnostic("anthropic_input_transformations", now, null, details))`，details＝`{"transformations":[{type,path,reason},…]}`（缺席的键不写，pi 的 `?? undefined`）。StopState 与 extras 合并传参（submitTail 签名加 extras）。

### 3.5 openai-completions 触点

chunk 循环最顶（`OpenAICompletionsApi.java:204` 循环体内、choices 空判定**之前**——usage-only chunk 也可能先带 model）：

```java
chunk.model().ifPresent(m -> {
    if (!m.isEmpty() && request.model() != null
            && !m.equals(request.modelId().modelName())) {
        builder.noteResponseModel(m);   // builder 内首值胜（||=）
    }
});
```

builder `noteResponseModel` 自身守「已设不覆盖」（pi `||=`；调用点判不同 id 与 pi `!== model.id` 同效，双保险保留）。

### 3.6 pi-messages：失败信封 ＋ rewrite

**ProviderErrors 开放两个小件**（不新建类）：
- `public static JsonNode parsePiMessagesErrorNode(String body)`（现 private `piMessagesErrorNode` 改名开放）；
- `public static String reasonPhrase(int status)`（现 private 开放，PiMessagesApi 详情对象要用）；
- 新增 `public static final int DIAGNOSTIC_BODY_MAX = 8192;` 与 `truncateDiagnosticString`（`len>8192 ? prefix + "…"`，U+2026，逐字 pi）。

**PiMessagesApi catch 分支**（:183-190 的 PiHttpException 支），emitError 之前：

```java
if (e instanceof PiHttpException http && http.statusCode() > 0) {
    String message = ProviderErrors.formatPiMessagesError(http.statusCode(), http.body());
    long now = System.currentTimeMillis();
    builder.appendDiagnostic(AssistantMessageDiagnostic.fromError(
        "pi_messages_response_failure",
        new PiMessagesResponseErrorShape(message, codeOrNull(http.body())),  // 仅为 name/stack 形状
        failureDetails(request, baseUrl + "/messages", http.statusCode(), http.body(), now)));
    publisher.submit(builder.emitError(StopReason.ERROR,
        message.equals(e.getMessage()) ? e : new IllegalStateException(message, e)));
}
```

details 逐字 `createPiMessagesResponseError`：`version:1, provider, model, url, status, statusText(reasonPhrase), error?(原始节点) : body?(截断), timestampMs`——errorBody 解析成功带原始 `error` 节点（code 同时喂 DiagnosticErrorInfo.code），失败带截断 body；两态互斥。
异常名字面值 `"PiMessagesResponseError"`（不用 Java 真实类名；`DiagnosticErrorInfo` 加包私有静态工厂直接收 name/message/stack/code，避免造伪异常）。stack＝`http` 异常栈串。

**rewrite**（Done/Error 两分支，emitDone/emitError 之前）：

```java
if (done.rewrite() != null) {
    builder.appendDiagnostic(new AssistantMessageDiagnostic(
        "pi_messages_rewrite", System.currentTimeMillis(),
        null, MAPPER.valueToTree(done.rewrite())));
}
```

`RewriteImpact` 组件名与 wire 逐字一致（policyId/policyVersion/changed/tokenCountChange/messageCountChange/systemPromptChanged）。
流内 SSE error 事件分支（:160-171）**不**附诊断（仅 HTTP 故障信封），中止态同样不附。

### 3.7 工具计时

**ToolRegistry 新增计时执行**（审批/校验/prepareArguments 在钟外——pi 里它们属于 prepare 相，许可在 pi 是 beforeToolCall 钩子也在钟外）：

```java
/** B234：execute() 本体的计时结果（agent-loop.ts:836-855）。审批/校验/prepareArguments 在钟外。 */
public sealed interface Execution {
    long durationMs();
    record Success(ToolResult<?> result, long durationMs) implements Execution {}
    record Failure(Exception cause, long durationMs) implements Execution {}
}

public Execution executeTimed(String toolName, String toolCallId, Map<String,Object> arguments,
                              AbortSignal signal, ToolUpdateCallback<?> onUpdate, ToolContext context)
        throws Exception {
    var tool = tools.get(toolName);
    if (tool == null) throw new IllegalArgumentException("Tool not found: " + toolName);
    if (approvalHandler != null && !approvalHandler.approve(toolName, arguments))
        throw new SecurityException("Tool call not approved: " + toolName);   // 钟外：无时长（＝blocked）
    … cast …
    ToolArgumentsValidator.validate(rawTool.inputSchema(), arguments);       // 钟外：校验失败无时长
    var prepared = rawTool.prepareArguments(arguments);                      // 钟外
    long start = System.nanoTime();
    try {
        return new Execution.Success(
            rawTool.execute(toolCallId, prepared, signal, cast(onUpdate), context),
            elapsedMillis(start)));
    } catch (Exception e) {
        return new Execution.Failure(e, elapsedMillis(start));               // 工具抛错也有时长
    }
}

public ToolResult<?> execute(…) throws Exception {   // 旧签名保留给非 runner 调用方：解包/重抛
    return switch (executeTimed(…)) {
        case Execution.Success s -> s.result();
        case Execution.Failure f -> { throw f.cause(); }
    };
}
```

`elapsedMillis = Math.round((System.nanoTime()-start)/1_000_000.0)`（半入整，pi `Math.round`；不用整除）。

**PiToolRunner.execute** 改消费 Execution：

```java
Long duration;
ToolResult<?> executed; boolean isError;
try {
    var ex = registry.executeTimed(call.toolName(), call.toolCallId(), state.args(), signal, …, toolContext);
    if (ex instanceof Execution.Success s) { executed = s.result(); isError = executed.isError(); duration = s.durationMs(); }
    else { var f = (Execution.Failure) ex; executed = PiLoopTools.createErrorToolResult(messageOf(f.cause())); isError = true; duration = f.durationMs(); }
} catch (Exception e) {   // 审批拒绝/校验失败：钟外异常 ⇒ 无时长（immediate 语义）
    executed = PiLoopTools.createErrorToolResult(messageOf(e)); isError = true; duration = null;
} finally { acceptingUpdates.set(false); }
// afterToolCall：成功→toOutcome(…,duration)；钩子抛错→errorOutcome(…,duration)（pi finalize catch 保留 executed.durationMs）
```

`toOutcome`/`errorOutcome` 加 `Long durationMs` 参，消息走 9 参构造；`immediate()` 路径传 null。

**事件**：`Event.ToolExecutionEnd(…, Object result, boolean isError, Long durationMs)`，4 参构造传 null。PiLoopTools 4 个发射点统一 `outcome.message().durationMs()`（消息是唯一权威，ToolOutcome 不另加组件——pi 两处都从 finalized 取，同一值）；截断 :257 手工消息无时长，读出来即 null。Jackson 投影见 §3.10。

**ToolResultMessage 构造点**：PiToolRunner.toOutcome 传值；PiLoopTools.failTruncated/abortedOutcome 保留 8 参（null）；TransformMessages 两处方转发 `msg.durationMs()`；OrphanToolResults 4 参兼容（补占位文本，本无时长）；MessageJsonCodec decode 入值。

### 3.8 thinkingLevel 盖章（单点）

`PiLoopRunner.streamAssistantResponse`，`:301` markAborted 之后、写回 context/MessageEnd 之前：

```java
finalMessage = markAborted(finalMessage, signal, cutShort);
// B148/批5（agent-loop.ts:409）：请求侧 pi 级别，流不盖、只盖终局；config.reasoning 缺席恒 "off"。
finalMessage = finalMessage.withThinkingLevel(
    config.thinking() == null ? "off" : config.thinking().label());
```

- error/aborted/无终局合成消息（:297-299）同盖（pi 是 `await response.result()` 无差别 Object.assign）；
- 流式 partial（`com.pijava.ai.message.AssistantMessage`）**不加**该字段——Start/MessageUpdate 投影保持 null，只有 MessageEnd 终局消息带值（与 pi 流中不可观察、message_end 可观察一致）；
- `withThinkingLevel` 是全字段复制 rewrite（仿 withStopReason:302-306）；
- 不放在 AbstractChatApi 钩子：直调 `send()` 不经 agent loop，pi 那边同样缺席（"Absent outside the agent loop"）；StreamRequest.reasoning 把 off 折成 empty，拿不回 "off"，盖章原料只能在 loop 层。

### 3.9 JSONL 编解码（SessionJson/MessageJsonCodec）

**encode**（SessionJson 手写，显式判空省略，键序 §3.1）：
- assistant：model 之后 `responseModel?`、`providerThinkingLevel?`（批 4 已在）、其后插 `thinkingLevel?`；diagnostics＝`node.set("diagnostics", MAPPER.valueToTree(list))`（非 null 才写）；rawStopReason 之后写 `durationMs?`（long）；
- tool：timestamp 之后 `durationMs?`。

**decode**（MessageJsonCodec）：`optionalString` 两个、`optionalLong(node,"durationMs")`（助手与工具两处）、diagnostics：

```java
var diagNode = node.get("diagnostics");
List<AssistantMessageDiagnostic> diagnostics = diagNode == null ? null
    : MAPPER.convertValue(diagNode, new TypeReference<>() {});
```

旧盘无键 ⇒ null ⇒ 写侧不出键，向前兼容（同批 4 providerThinkingLevel 路线；夹具模板 `ProviderThinkingLevelRoundTripTest`）。

### 3.10 宿主线投影

- **SessionRunner.passEvents:241** → **AgentSessionEvent.ToolExecutionEnd 加 Long durationMs** → JsonEventMapper:199-206 与 AgentEventTranslator:179-187 两份手写帧：`if (e.durationMs() != null) node.put("durationMs", e.durationMs());`，键位在 isError 后（pi json.md:107 同序）；
- **消息面自动流**：JsonEventMapper.messageNode 的 valueToTree 泛投影 + `isAbsentOptional` null 兜底 ⇒ 4 个新标量/数组字段自动上线，键序由 record 声明序保证；
- **SessionRunner 合成消息/AgentSettled**：AgentSettled 的 toolResults 就是 transcript 里的 ToolResultMessage，消息自带 durationMs，record 不另加字段（pi 同：AgentSettled 不另有时长字段）；
- **WebWireJson**（web WS 第三份手写投影，§2.2 #21）：assistant 支按同键序补 4 键并**补回批 4 漏掉的 providerThinkingLevel**；tool 支补 durationMs；
- **terminalWireNode 不动**：proxy 形状终局帧（type/reason/usage/providerThinkingLevel?）pi proxy.ts:34-46 只有这四件，新四件一个都不在——message_end 的整条消息才是它们的载体（pi json-event.ts 对非 message_update 事件原样透传）。
- **对拍归一器不改**：`conformance/FrameNormalizer.java` 的 messageOf/tool_end 显式列举字段，timestamp 已被豁免、durationMs 天然不进帧；录制 S2-S14 无任一新字段（已 grep 验证）。

---

## 4. 裁决（请逐条过）

1. **响应计时归身份缝（AbstractChatApi），不进 StreamPartialBuilder**：时钟随 `stream()` 创建（pi＝event stream 创建即起算），done/error 与 streamBlocking 两处合成终局同法；守卫「已有值保留」复刻，「晚到终局」依赖流契约，「早于本流的消息」在 Java 不可达（生产者成形通道整体不经缝）。
2. **api!=null 的生产者成形事件（Faux/conformance 桩）不计时不挂身份**，沿用批 3a 身份缝既有边界；这些路径的终局消息无 durationMs，台账记窄差（pi 的 faux 经真 event stream 会有 ≈0ms）。
3. **工具钟只围 raw `AgentTool.execute()`**：新增 ToolRegistry.executeTimed（sealed Success/Failure），审批拒批/JSON 校验/prepareArguments 全部在钟外、异常无时长（对齐 pi 的「blocked 无 durationMs」与 #10549 夹具的钩子排除）；旧 `execute` 保留委托。`Math.round` 半入整毫秒。
4. **ToolOutcome 不另加 durationMs 组件**：消息是唯一权威，4 个事件发射点读 `outcome.message().durationMs()`；afterToolCall 抛错保留已测得的执行时长（pi 同）。
5. **thinkingLevel 只加在会话记录层**（流式 partial 不加），PiLoopRunner markAborted 后单点盖 `config.thinking().label()`（null→"off"）；不做成 AbstractChatApi 钩子（agent loop 外缺席是 pi 明文语义）。
6. **proxy 形状终局帧（terminalWireNode）不加任何新键**（pi proxy.ts 不带）；新字段随 message_end 整条消息上线；WebWireJson 是独立的第三份手写消息投影，必须显式同步（并顺带补 providerThinkingLevel 的批 4 漏网）。
7. **TUI「Took …」卡本批不做**：Java TUI 不订阅 ToolExecution* 事件（ChatScreen.onSessionEvent 无 case），ToolCallCard 仅 39 行、无 toolCallId/无原地更新通路，也没有 bash 专属渲染器；数据链（事件/wire/JSONL）全通后，Took 卡随 tui bash 渲染器另案（同 B148 消费侧 /session、/bug、cache-warmer 的另案口径，docs/08 §2.3）。
8. **diagnostics.details/error.code 用 JsonNode**（不用 Map/Object）：网关错误体逐字节保真；stack 用 JDK PrintWriter 栈串（pi 落 JS 栈，bug-report 另案消费）；夹具只断言结构不比对栈文本。
9. **responseModel/diagnostics/durationMs/thinkingLevel 加在两层消息（thinkingLevel 除外，仅会话层）**；responseId/endTurn 继续不做（随 codex R5 出分母）；bedrock `bedrock_response_failure`、codex `provider_transport_failure` 两个诊断生产者不做（w0/R5）。
10. **pi-messages 流内 SSE error 事件不附失败诊断**（只有 HTTP 故障信封附）；aborted 不附；rewrite 在 done/error 两分支都附、无夹具按代码自证。
11. **JSONL 旧盘兼容**：全部新键缺席即 null 且写侧省略；diagnostics 经 Jackson 反向构造；不改既有兼容构造器的可见性。

## 5. 测试计划

**pi-java-ai（对齐 pi 夹具）**

1. `ResponseTimingTest`（新，AbstractChatApi 脚本桩）：done/error 终局 durationMs≥0 且 send() 结果携带；预设值不被覆盖；IdentitySubscriber 后多帧值稳定；
2. `AnthropicFallbackBlockProbeTest`（实施第 0 步探针，固化 `_json()` 访问面）；
3. `AnthropicResponseModelWireTest`（新，移植 sse-parsing.test.ts:142-243/317-357 四例）：代理改标模型 ⇒ responseModel＋内容保留；fallback 模型 ⇒ responseModel 且 cost 用 fallback 定价；已有内容后来 fallback 块 ⇒ error 终局、errorMessage 含原文、块保留；input_transformations start 一条 delta 一条 ⇒ 诊断只见末值，三键投影；
4. `CompletionsResponseModelWireTest`（新，移植三例：路由模型/回显同 id 无键/空串缺失忽略，首值胜）；
5. `PiMessagesDiagnosticsTest`（新）：401 JSON 错误体 ⇒ stopReason=error、errorMessage 含 401/文案/code、诊断 type/details.status/details.error.code；非 JSON body ⇒ details.body（8193 字符夹具验截断与 U+2026）；流内 error SSE 无诊断；rewrite 在 done/error 两分支产出；
6. 消息层：`MessageTest` fromPartial 携带 responseModel/diagnostics/durationMs 且不携带 thinkingLevel；StreamPartialBuilderTest 诊断累加/首值胜/hasContentBlocks。

**pi-java-agent-core**

7. `ToolDurationTest`（移植 #10549）：execute sleep 30ms＋before 钩子 sleep 100ms ⇒ ran ∈ [25,100)ms（边界放宽按 CI），blocked isError=true 且 ToolResultMessage/事件**无** durationMs 键；审批拒批无时长；工具抛错有时长；after 钩子抛错保留时长；截断/abort 合成无时长；
8. `AssistantThinkingLevelStampTest`：默认 config（null thinking）⇒ 终局消息 "off"；medium ⇒ "medium"；message_start/update 路径无该键、message_end 有；aborted 终局也盖；
9. `Batch5MessageFieldsRoundTripTest`（仿 ProviderThinkingLevelRoundTripTest）：assistant 五新键（含 diagnostics 数组逐值）/tool durationMs 落盘再读回；旧盘无键 ⇒ 全 null/省略。

**pi-java-coding-agent / pi-java-web**

10. `JsonEventMapperToolExecutionTest`：现有键数断言拆「执行过（6 键含 durationMs）/没执行（5 键无该键）」两态；messageNode 新字段逐键（含 null 省略、键序）；terminalWireNode 断言**不含**新键；
11. `AgentSessionToolExecutionEventTest` 扩：durationMs 从 PiLoop 事件透传到会话事件；
12. web：`AgentEventTranslatorToolExecutionTest` end 帧两态；`WebWireJsonTest` 补 assistant 五键（含 providerThinkingLevel 回归）与 tool durationMs。

**回归**：ai 全量＋agent-core（现 661）＋coding-agent 542＋tui 213＋web；checkstyle；收尾全量 `mvn clean verify`（~34 min，批 5 触下游模块）。

## 6. 明确不做

- responseId/endTurn（随 codex R5 出分母）；bedrock/codex 的诊断生产者（w0/R5）；
- B148 消费侧：usage-totals `/session` 费用分组、bug-report diagnostics.json、cache-warmer 归属、interactive-mode UI 计数（docs/08 §2.3 各自模块另案）；
- B234 消费侧：TUI/bash「Took」卡（裁决⑦）；PiLaneSink 既有可观测性口径不动（那是含钩子/审批的宽口径，另有用途）；
- 不把工具计时做进 PiLaneSink/OTel 既有 durationMs（不改 LaneRecord 语义）；
- 不新增 CLI 参数/slash/settings；不动目录数据；
- nestedCalls 的 durationMs（#93 已 R5）；durable 包 pi.tool-result 记录面（Java 无 durable 模块）。

## 7. 落地顺序与提交粒度

1. **commit 1（ai）**：两层消息新组件＋诊断类型＋builder＋AbstractChatApi 计时缝＋anthropic 三触点（探针先行）＋completions 触点＋pi-messages 信封/rewrite＋TransformMessages 转发＋ai 测试。`mvn -pl pi-java-ai -am test`。
2. **commit 2（agent-core）**：ToolRegistry.executeTimed＋runner/事件/消息时长＋thinkingLevel 盖章＋JSONL＋ContextEntries＋core 测试。
3. **commit 3（宿主）**：AgentSessionEvent/SessionRunner/JsonEventMapper/web 双投影＋测试；全量 `mvn clean verify`。
4. **commit 4（docs）**：台账 B148/B234 销号（消费侧另案项保留指针）、docs/06、docs/08 批 5 行闭环 banner（92.2%→**92.7%**，#37 +1）、map/02 加权、本稿头部 banner；记忆追加。

预估：产品新文件 2 个（诊断类型文件、`AnthropicResponseExtras`）＋测试类 ~9 个，
产品文件改 ~22 个（§2.2 清单），与批 4 体量相当（L）。
