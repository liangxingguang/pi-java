# 48 · Provider 错误体透传对齐设计（B135 + B141）

> ✅ **已闭环**（2026-10-11，`fd51f478`→`3d2522e8`，6 commits）。B135/B141 销号，
> 新登记 B224（pi-messages 错误格式）。闭环记录见 §9。
> 锚点：pi `200387122`（取证一律 `git show 200387122:<path>`）。

## 1. 范围速览

| 项 | 裁决 |
|---|---|
| 移植 `utils/error-body.ts`（normalize/format/truncate/cap 4000） | **做**，新建 `ai/utils/ProviderErrors.java` |
| openai 系 4 车道接入（completions / responses / azure / openrouter-images） | **做** |
| **B135** OpenRouter `metadata.raw` 追加（completions 车道） | **做** |
| **B141** bodyless 400/413 文案 `400 status code (no body)` ⇒ 救回 Cerebras 溢出归类 | **做** |
| openai-responses 前缀 `<Provider> API error` ＋ ChatGPT 用量链接 | **做** |
| Mistral 裸 fetch 车道 `Mistral API error (N): …`（**B141 台账点名的 `PiHttpClient:152`**） | **做**（异常体携带 body，措辞不动） |
| Google / Anthropic 车道 | **不做**（normalizer 在这两条 SDK 上恒等／pi 也不接，见 §7） |
| Bedrock / vertex 独立车道 / system-one / llama-cpp-classify / codex-responses | **不做**（Java 无车道） |
| pi-messages 车道自己的错误格式 + `pi_messages_response_failure` 诊断 | **不做**，新登记 **B224** |

全程只动 **pi-java-ai** 一个模块；agent-core / coding-agent 零改动。

## 2. 台账与现象

- **B135**：OpenRouter 在错误体 `error.metadata.raw` 里给上游补充信息；pi 在 completions 车道 catch 末尾按「未含则追加」拼接，Java 无此逻辑。
- **B141**：bodyless 400/413 文案两侧不同，而 `ContextOverflow` 的 Cerebras bodyless 门只认 pi 文案：
  - pi（openai-node 7.19.0）：`400 status code (no body)`；
  - Java 两套 HTTP 栈两个样：openai-java SDK 给 **`400: Unknown`**（`BadRequestException.kt`），裸 JDK 栈给 **`HTTP 400`**（`PiHttpClient.java:152`，body 被整体丢弃）。
  - `ContextOverflow.java:71-72` 的正则 `^4(?:00|13)\s*(?:status code)?\s*\(no body\)` 对这两个样**都不命中** ⇒ Cerebras 网关门（provider 经 models.json 可达，`CompatResolver.java:75`）把 bodyless 400/413 溢出判成普通错误，丢自动压缩恢复。

## 3. pi 证据（逐字）

### 3.1 `packages/ai/src/utils/error-body.ts`（全文要点，锚点 :16-141）

```ts
export const MAX_PROVIDER_ERROR_BODY_CHARS = 4000;

export function normalizeProviderError(error: unknown): NormalizedProviderError {
	if (!(error instanceof Error)) {
		return { message: safeJsonStringify(error), messageCarriesBody: false };
	}
	const sdkError = error as SdkErrorShape;
	const status = extractStatus(sdkError);
	const body = extractBody(sdkError);
	const messageCarriesBody = body === undefined || error.message.includes(body);
	return { status, body, message: error.message, messageCarriesBody };
}
```

- 状态探测序：`statusCode`(Mistral) → `status`(openai / @google/genai) → `$metadata.httpStatusCode` → `$response.statusCode`（Bedrock）。
- body 探测序：`body` 字符串（Mistral）→ **plain non-empty object 的 `error`**（openai SDK 的 `this.error`）→ `$response.body` 字符串 → 流对象（有 `pipe`）跳过 → plain object 才 JSON 化。**空对象、类实例、流一律视为无 body**（:100-124 大段注释：AWS SDK 包装类被串成 `{"_events":…}` 曾顶掉真 message）。
- body 先 trim、空串视为无、再截到 cap。
- `extractBody` 对 openai 形状的判定只有「**plain object 且非空**」一条；`error.error` 是字符串时**不**取作 body（文本在 message 里）。

```ts
export function formatProviderError(norm: NormalizedProviderError, prefix?: string): string {
	if (norm.messageCarriesBody || norm.status === undefined || norm.body === undefined) {
		return prefix !== undefined && norm.status !== undefined
			? `${prefix} (${norm.status}): ${norm.message}`
			: norm.message;
	}
	return prefix !== undefined ? `${prefix} (${norm.status}): ${norm.body}` : `${norm.status}: ${norm.body}`;
}

export function truncateErrorText(text: string, maxChars: number): string {
	if (text.length <= maxChars) return text;
	return `${text.slice(0, maxChars)}... [truncated ${text.length - maxChars} chars]`;
}
```

**四分支真值表**（前缀 × 有无 body）：

| messageCarriesBody / status / body | 无前缀 | 有前缀 |
|---|---|---|
| carries=true 或 status 缺 或 body 缺 | `message` | status 有：`<prefix> (<status>): <message>`；status 缺：`message`（**不加前缀**） |
| carries=false，status/body 齐 | `<status>: <body>` | `<prefix> (<status>): <body>` |

注意第二行：内部错误（无 HTTP 状态，如收尾 throw 的 `Stream ended without finish_reason`）即使在前缀车道也**不套前缀**。

### 3.2 openai-node 7.19.0 的消息是怎么造出来的

`packages/ai/package.json` 钉 `"openai": "7.19.0"`。`core/error.mjs`（unpkg 取证，版本钉死）：

```js
constructor(status, error, message, headers) {
    super(`${APIError.makeMessage(status, error, message)}`);
    this.status = status; …
    this.error = error;               // = errorResponse?.['error']（嵌套错误对象）
}
static makeMessage(status, error, message) {
    const msg = error?.message
        ? typeof error.message === "string" ? error.message : JSON.stringify(error.message)
        : error ? JSON.stringify(error) : message;
    if (status && msg) return `${status} ${msg}`;
    if (status) return `${status} status code (no body)`;
    if (msg) return msg;
    return '(no status code or body)';
}
static generate(status, errorResponse, message, headers) {
    …
    const error = errorResponse?.['error'];
    if (status === 400) return new BadRequestError(status, error, message, headers);
    …
}
```

即：`this.error` 是 body 的**嵌套 `error` 节点**；bodyless ⇒ message 逐字 `` `${status} status code (no body)` ``。

**Java 侧 openai-java 4.42.0**（`~/.m2` sources 取证）另造一套：`BadRequestException` 消息是
`"400: " + (error._message known ?: (isMissing ? "Unknown" : json))`——冒号、`Unknown`，两处都不同；
但 `OpenAIServiceException` 暴露了 `int statusCode()` 与 `JsonValue body()`（bodyless 时是 `JsonMissing`；
未知字段经 `ErrorObject.additionalProperties`（`@JsonAnySetter`）保留），**status + 解析后 body 两件原料齐全**，
所以 Java 侧不沿用 SDK 文案，而是用这两件原料**重放 makeMessage**，再过 normalize/format，达到逐字对齐。

### 3.3 各车道 catch 落点（pi 锚点）

| pi 文件:行 | 前缀 | Java 对应 |
|---|---|---|
| `api/openai-completions.ts:719-727` | 无 | `OpenAICompletionsApi.java:361-366` |
| `api/openai-responses.ts:222-229` | `` `${model.provider === "openai" ? "OpenAI" : model.provider} API error` ``＋ChatGPT 链接 | `OpenAIResponsesApi`（异常目前**逃逸**到 `AbstractChatApi:91`，无前缀） |
| `api/openai-codex-responses.ts:491` | 无 | Java 无此车道（Protocol 枚举无 codex） |
| `api/azure-openai-responses.ts:53-55` | `"Azure OpenAI API error"` | `AzureOpenAIResponsesApi`（同样逃逸） |
| `api/google-generative-ai.ts:296`、`google-vertex.ts:304` | 无 | `GoogleGenerativeAiApi.java:299-303` |
| `api/openrouter-images.ts:113-115` | 无（ImageApi 非流式） | `OpenRouterImagesApi.java:79-85` |
| `api/mistral-conversations.ts:271-281` | 自有格式（见 §3.5） | `MistralConversationsApi.java:148-151` |
| `api/anthropic-messages.ts:894` | **不接** normalizer，裸 `error.message` | `AnthropicMessagesApi.java:394`（同形，不动） |
| `api/bedrock-converse-stream.ts:394` / `system-one-shared.ts:234` / `llama-cpp-classify.ts:455` | — | 无车道 |

responses 车道逐字（:220-230）：

```ts
const errorMessage = formatProviderError(
	normalizeProviderError(error),
	`${model.provider === "openai" ? "OpenAI" : model.provider} API error`,
);
// Sign in with ChatGPT shares the subscription's usage limit with other apps.
output.errorMessage = errorMessage.includes("subscription_sharing_usage_limit_exceeded")
	? `${errorMessage}\nCheck your ChatGPT usage: ${CHATGPT_USAGE_URL}`
	: errorMessage;
```

`CHATGPT_USAGE_URL = "https://chatgpt.com/settings/usage"`（`:34`）。Azure 为同文件私有函数
`formatAzureOpenAIError`（:52-55）。

### 3.4 B135：OpenRouter metadata.raw（`openai-completions.ts:720-727`）

```ts
output.errorMessage = formatProviderError(normalizeProviderError(error));
// Some providers via OpenRouter give additional information in this field.
// normalizeProviderError already stringifies the parsed body (error.error)
// into errorMessage, so only append the raw metadata when it is not already
// present to avoid double-printing it.
const rawMetadata = (error as any)?.error?.metadata?.raw;
if (rawMetadata && !output.errorMessage.includes(String(rawMetadata))) {
	output.errorMessage += `\n${rawMetadata}`;
}
```

常态下 `raw` 已在 body 的 JSON 串里（追加是 no-op，回归测试钉的就是「恰一次」）；
实际发火点是 raw 含换行等被 JSON 转义、或 body 走 message 折叠路时。**仅 completions 车道**追加；
images/responses/azure 都没有这段。

回归测试（`test/provider-error-body-regression.test.ts:149-172`）：body
`{message:"Provider returned error", code:403, metadata:{raw:"upstream WAF blocked policy XYZ"}}`
⇒ 文案含 raw **且 `match(/…/g)` 长度恰为 1**。

### 3.5 Mistral：裸 fetch + 自有格式（`mistral-conversations.ts:271-281, 318-332`）

pi 的 Mistral **不用 SDK**，裸 fetch 抛自有错误类：

```ts
class MistralHttpError extends Error {
	statusCode: number; body: string;
	constructor(statusCode, body, statusText) {
		super(statusText || `Request failed with status ${statusCode}`);
		…
	}
}
function formatMistralError(error: unknown): string {
	if (error instanceof Error) {
		const httpError = error as Error & { statusCode?: unknown; body?: unknown };
		const statusCode = typeof httpError.statusCode === "number" ? httpError.statusCode : undefined;
		const bodyText = typeof httpError.body === "string" ? httpError.body.trim() : undefined;
		if (statusCode !== undefined && bodyText) {
			return `Mistral API error (${statusCode}): ${truncateErrorText(bodyText, 4000)}`;
		}
		if (statusCode !== undefined) return `Mistral API error (${statusCode}): ${error.message}`;
		return error.message;
	}
	return safeJsonStringify(error);
}
```

`MAX_MISTRAL_ERROR_BODY_CHARS = 4000`（`:29`）。

### 3.6 Cerebras bodyless 溢出门（消费侧，已在 Java、本次救活）

`pi-java` 已逐字移植：`ContextOverflow.java:71-72,112-116`——正则
`^4(?:00|13)\s*(?:status code)?\s*\(no body\)`，且**只在 `provider=="cerebras"` 时**生效。
`ContextOverflowTest.java:88-102` 已把三种文案当夹具，但没有任何生产车道真的产出它们（夹具悬空）。

### 3.7 pi-messages 车道是另一套（本次不做）

`pi-messages.ts:117-135` 有自己的 `PiMessagesResponseError` 与格式
`` `${response.status} ${response.statusText}: ${message ?? body}${code ? ` (${code})` : ""}` ``
（8192 截断 + `pi_messages_response_failure` 诊断事件）。Java `PiMessagesApi` 与 `PiHttpClient:152`
今天只产 `HTTP 400`，属于同族但**不同函数**，登记 **B224** 单独收口（JDK `java.net.http` 不暴露
reason phrase 是已知方言点，随 B224 裁决）。

## 4. Java 现状

| 车道 | 异常来源 | 今天的终局 errorMessage |
|---|---|---|
| OpenAICompletionsApi | openai-java `OpenAIServiceException`（裸 catch `:361`） | SDK 原文：bodyless `400: Unknown`；有 body `400: <msg/json>`（冒号样，body 短文本时被折叠而非给整 JSON） |
| OpenAIResponsesApi / Azure | 同 SDK；异常**逃出** `streamInternal` → `AbstractChatApi:86-92` closeExceptionally → onError settle | 同上，且**永不带 pi 前缀**；连 `start` 之后的 builder 错误格式都不是车道产的 |
| OpenRouterImagesApi | 同 SDK（非流式 create） | `e.getMessage()`，`ImageResult.errorMessage`（`:84`） |
| MistralConversationsApi | `PiHttpException`（裸 JDK） | `HTTP 400`——**body 在 `PiHttpClient:152` 被丢弃**（`providerErrorMessage(body)` 只喂了 retry 退避） |
| PiMessagesApi | 同上 | `HTTP 400`（B224） |
| Google | `com.google.genai.errors.ApiException`（`"%d %s. %s"`，有 code、**无 body 访问器**） | SDK message 已含 body 文本 |
| Anthropic | anthropic-java | `e.getMessage()`（与 pi 同形） |

**RetryableError 影响面**（`utils/RetryableError.java`）：白名单按 errorMessage 正则分类。变化后：
400/403 bodyless 文案不含任何白名单词（`400` 不在表中）⇒ 分类不变；429/5xx 本来就含状态码数字，
新文案仍含；body 透出后若含 `rate limit`/`overloaded` 等词会变成可重试——这正是 pi 行为（pi 看到的就是同一段文本）。

## 5. 设计

### 5.1 新工具类 `pi-java-ai/src/main/java/com/pijava/ai/utils/ProviderErrors.java`（约 190 行）

```java
public final class ProviderErrors {
    public static final int MAX_PROVIDER_ERROR_BODY_CHARS = 4000;

    /** pi NormalizedProviderError（error-body.ts:20-27）。 */
    public record Normalized(Integer status, String body, String message,
                             boolean messageCarriesBody) {}

    /** 字段已由各 SDK 适配器取出后的核心（normalizeProviderError 主体）。 */
    public static Normalized normalize(Integer status, String rawBody, String message) { … }

    /** formatProviderError 的两个重载。 */
    public static String format(Normalized n) { return format(n, null); }
    public static String format(Normalized n, String prefix) { … }

    /** truncateErrorText：超长按 `前4000... [truncated N chars]`。 */
    public static String truncate(String text, int maxChars) { … }

    /**
     * openai-java 4.x 适配器（completions/responses/azure/openrouter-images 共用）。
     * OpenAIServiceException ⇒ 取 statusCode()/body()，重放 openai-node makeMessage
     * 后再过 normalize；其他 Throwable ⇒ status/body 缺席、message 透传。
     */
    public static Normalized fromOpenAi(Throwable error) { … }

    /** B135：嵌套 error 节点的 metadata.raw 文本（仅文本/数字/布尔标量），缺席给 null。 */
    public static String openRouterRawMetadata(Throwable error) { … }
}
```

`fromOpenAi` 要点（对应 §3.2 的两语言差异）：

```java
if (error instanceof OpenAIServiceException service) {
    int status = service.statusCode();
    JsonNode nested = service.body() instanceof JsonMissing ? null
        : ObjectMappers.jsonMapper().convertValue(service.body(), JsonNode.class);
    if (nested != null && nested.isNull()) nested = null;
    String message = makeOpenAiNodeMessage(status, nested); // 逐字重放 makeMessage
    String bodyText = nested != null && nested.isObject() && nested.size() > 0
        ? writeJsonOrNull(nested) : null;                    // pi：只认真·plain 非空对象
    return normalize(status, bodyText, message);
}
return normalize(null, null, error.getMessage());
```

- `makeOpenAiNodeMessage`：`message` 字段是文本串取文本、非文本 JSON 化；否则节点 truthy 则整体 JSON 化
  （JS truthiness：`null`/`false`/`0`/`""` 为假，写一个 `jsTruthy(JsonNode)` 小谓词）；
  齐 ⇒ `status + " " + msg`，否则 ⇒ `status + " status code (no body)"`（**逐字**，Cerebras 门就靠它）。
- `normalize` 里 body trim/空→null/截 4000；`messageCarriesBody = body==null || message.contains(body)`。
- JSON 化统一走 `ObjectMappers.jsonMapper()`；序列化抛错回落 `message`（对应 pi safeJsonStringify
  的 catch → `String(value)`，JsonNode 实际不会抛，仅防御）。
- **未知字段保留**（metadata 可达性）由 SDK `ErrorObject.additionalProperties` 保证；夹具用真 HTTP 桩钉死，
  若 4.42.0 实测丢失，再升级本设计（SDK 无原始 body 文本 API，可能需等上游或换读法）。

### 5.2 completions 车道（`OpenAICompletionsApi.java` catch :361）

```java
} catch (Exception e) {
    stampStreamedDetails(builder, streamedDetails);            // 原有，次序不动
    String message = ProviderErrors.format(ProviderErrors.fromOpenAi(e));   // 无前缀
    String raw = ProviderErrors.openRouterRawMetadata(e);      // B135
    if (raw != null && !raw.isEmpty() && !message.contains(raw)) {
        message += "\n" + raw;
    }
    publisher.submit(builder.emitError("error", new IllegalStateException(message, e)));
}
```

收尾 throw 的 `Provider returned an error stop reason` / `Stream ended without finish_reason`
等内部错误：`fromOpenAi` 判非 SDK 异常 ⇒ status 缺 ⇒ format 原样透传（四分支表第二行）。

### 5.3 responses / azure：把「请求获取」纳入车道 catch，错误文案改由 processor 参数注入

今天 SDK HTTP 错误发生在 `client.responses().createStreaming(params)` 获取期，**在**
`ResponsesStreamProcessor.process` 的 try 之外，所以逃逸到了 `AbstractChatApi`。改形：

```java
static void process(
        java.util.function.Supplier<StreamResponse<ResponseStreamEvent>> streamSupplier,
        SubmissionPublisher<StreamEvent> publisher, ModelInfo model,
        Map<String, String> grammarProperties,
        java.util.function.Function<Throwable, String> errorFormatter) {
    …
    try {
        publisher.submit(builder.emitStart());
        try (var stream = streamSupplier.get()) {     // 获取期错误也落车道 catch
            for (var event : stream.stream().toList()) { … }
        }
        …
    } catch (Exception e) {
        publisher.submit(builder.emitError("error",
            new IllegalStateException(errorFormatter.apply(e), e)));
    }
}
```

（`try-with-resources` 包获取后，`sawTerminal` 等收尾逻辑位置随实际缩进调整；`emitStart` 仍在获取前。）

- `OpenAIResponsesApi`：supplier 包现有的 `ProviderRetry.retry(...)`；formatter 按
  `request.modelId().provider()` 造前缀（`"openai"` → `"OpenAI"`，其余原样小写，null 不给前缀），
  format 后按 §3.3 追加 ChatGPT 链接。
- `AzureOpenAIResponsesApi`：formatter 恒为前缀 `"Azure OpenAI API error"`。
- processor 直接调用点只有这两处 + 同包测试，签名一次改齐。

### 5.4 images（`OpenRouterImagesApi.java:79-85`）

```java
String message = ProviderErrors.format(ProviderErrors.fromOpenAi(e));
return new ImageResult(…, aborted ? ABORTED : ERROR, message, timestamp);
```

无 metadata 追加（pi 该车道没有）。

### 5.5 Mistral + PiHttpException 携带 body

- `PiHttpException` 增 `private final String body`（永不 null，默认 `""`）+ 访问器；
  旧两参构造器保留（委托 `body=""`）。
- `PiHttpClient.java:151-153`：
  `throw new PiHttpException(status, "HTTP " + status, body == null ? "" : body);`
  ——**异常 message 一字不改**（PiMessages 车道输出保持 `HTTP 400`，零连带；retry 日志断言不动），
  只是把响应体带上。
- `MistralConversationsApi.java:148-151` catch 改调本类私有 `formatMistralError(e)`（§3.5 逐字移植；
  bodyless 分支用 pi 兜底字面量 `"Request failed with status N"`，不回显 `"HTTP N"`；cap 用
  `ProviderErrors.MAX_PROVIDER_ERROR_BODY_CHARS` + `truncate`），非 `PiHttpException` 透传 message。

### 5.6 明确不做

- **Google**：`ApiException` 只给 `code/status/message`，无独立 body；normalizer 套上去是恒等
  （body 恒缺 ⇒ carries=true ⇒ 原文返回），行为面零变化，不接线。
- **Anthropic**：pi 也是裸 `error.message`（`anthropic-messages.ts:894`），不接线。
- **Bedrock / vertex 独立文件 / system-one / llama-cpp-classify / openai-codex-responses**：无车道。
- **PiMessages 错误格式 + 诊断事件**：登记 B224。

## 6. 测试计划（先红后绿）

### 6.1 新增 `utils/ProviderErrorsTest.java`（纯单元，对应 error-body.test.ts 全表）

1. 有 body 未折叠 ⇒ `format(n)` = `403: {"error":"x"}`；`messageCarriesBody=false`
2. message 已含 body ⇒ carries=true，format 回 message
3. body trim / 空串视为无
4. 截长：4050 字符 ⇒ 后缀逐字 `... [truncated 50 chars]`
5. 前缀 + body：`OpenAI API error (403): {"error":"x"}`（逐字）
6. 前缀 + carries：`OpenAI API error (403): <message>`
7. 前缀但 status 缺（内部错误）⇒ **裸 message，无前缀**（M4 的牙）
8. `fromOpenAi`：直接用 SDK builder 造 `BadRequestException`（headers 必填、error 可空）：
   - error 缺 ⇒ message 逐字 `400 status code (no body)`、body 缺、carries
   - error 仅 message 字段 ⇒ 合成 message `400 x`，归一 body 为整对象 JSON，format 得 `400: {…}`
9. 非 SDK 异常 ⇒ status/body 缺、message 透传

### 6.2 新增 `protocol/ProviderErrorBodyLanesTest.java`（JDK `RecordingHttpServer` 真桩）

| # | 车道 | 桩响应 | 断言 |
|---|---|---|---|
| L1 | completions | 403 `{"error":"blocked by gateway WAF"}`（嵌套是**字符串**） | errorMessage 含 `403` 与文本，≠ no-body 文案 |
| L2 | completions | 400 **空体** | 逐字 `400 status code (no body)` |
| L3 | completions | 413 空体，model provider=`cerebras` | `ContextOverflow.isContextOverflow(msg, null)` = **true** |
| L4 | completions | 400 空体，provider=`openrouter` | overflow=false（门控仍在） |
| L5 | completions | 403 OpenRouter metadata 体（§3.4） | raw 出现**恰 1 次** |
| L6 | completions | metadata.raw 带 `\n`（JSON 里被转义） | 末尾**另起一行**追加 raw 原文 |
| L7 | responses | 403 `{"error":{…}}`，provider openai | `OpenAI API error (403): …` 且含 body |
| L8 | responses | provider openrouter | `openrouter API error (403): …`（小写） |
| L9 | responses | body 含 `subscription_sharing_usage_limit_exceeded` | 含 `Check your ChatGPT usage: https://chatgpt.com/settings/usage` |
| L10 | responses | 200 SSE 回 `response.failed` 帧 | 文案**不套**前缀（内部 throw 路径） |
| L11 | azure | 403 带体 | `Azure OpenAI API error (403): …` |
| L12 | images | 403 带体 / 400 空体 | ImageResult.ERROR + body 文本 / no-body 文案 |
| L13 | mistral | 403 带体（裸 JDK 桩） | 逐字 `Mistral API error (403): <body>` |
| L14 | mistral | 400 空体 | `Mistral API error (400): Request failed with status 400` |
| L15 | mistral | 4500 字符体 | 后缀 `... [truncated 500 chars]`，无 `HTTP 4` 字样 |

另在 L7/L1 顺带断言 `RetryableError` 不退化（403 普通错误体 false；429 带 "rate limit" 体 true）。

### 6.3 既有夹具审计（实现时先跑红，按新文案更新断言而非放松）

`RecordingHttpServer` 默认桩就是 400 带体——凡断言 completions/responses/azure 错误**文本**的旧用例
（如 `OpenAICompletionsApiTest`、`AzureOpenAIResponsesApiTest`、`ChatApiTerminalSettlementTest` 等）
逐条改期望；只断 stopReason/content 的不动。Mistral 现有三条 `Provider stopped with …` 夹具是
SSE 200 路（finish reason），不经过 HTTP 错误分支，不动。

### 6.4 变异探针（绿后）

| 探针 | 变异 | 预期红集 |
|---|---|---|
| M1 | `fromOpenAi` 退回 `e.getMessage()` 透传 | L1、L2、L3、L5、L7、L8、L11、L12（no-body/JSON 折叠差异处） |
| M2 | no-body 文案改成 SDK 的 `400: Unknown` | 单测 8a、L2、L3 |
| M3 | metadata 追加改成无条件 | L5（恰 1 次） |
| M4 | 前缀忽略「status 缺」分支 | 单测 7、L10 |
| M5 | Mistral 不读 body（只拼 message） | L13、L15 |

每条变异后 grep 复核落地（CRLF 教训：不用 `$` 跨行锚），跑红、记录、恢复、复绿。

## 7. 文件与提交

| 文件 | 改动 | 估行 |
|---|---|---|
| `ai/utils/ProviderErrors.java` | 新建 | ~190 |
| `ai/protocol/OpenAICompletionsApi.java` | catch 改形 | +10 |
| `ai/protocol/ResponsesStreamProcessor.java` | supplier + formatter 参数 | +20 |
| `ai/protocol/OpenAIResponsesApi.java` / `AzureOpenAIResponsesApi.java` | 调用点 + formatter | 各 +15 |
| `ai/protocol/OpenRouterImagesApi.java` | catch 一行 | +2 |
| `ai/http/PiHttpException.java` / `PiHttpClient.java` | body 分量 + 抛出携带 | +20 |
| `ai/protocol/MistralConversationsApi.java` | formatMistralError | +25 |
| 测试 2 个新文件 + 既有夹具更新 | — | 新 ~350 |
| docs | `04` 销 B135/B141、登 B224；`map/02-ai.md` 行；`41` §7；本文件 §8 | — |

全部文件 ≤500 行（最大受影响文件 OpenAICompletionsApi 现 433 行，裕量内）。
提交序：①docs(48) 设计稿（本提交）→ ②`feat(ai): port provider error-body normalizer…`（util + 单测先红后绿）
→ ③车道接线（可按 completions / responses 系 / mistral+http / images 分 2-3 个可编译提交）→ ④docs 闭环。

## 8. 验收

1. 红：6.1/6.2 用例在实现前失败（文案不符/编译失败），记录。
2. M1-M5 探针红集与 §6.4 表一致（不一致先查夹具牙口），全部恢复。
3. `mvn -pl pi-java-ai -am test` 全绿；下游 agent-core/coding-agent/tui/evals reactor 绿。
4. checkstyle 0；无 `System.out.println`；ai 计数按模块汇总行更新。
5. B135/B141 销号、B224 登记；docs/41 §7、map/02 回填；§9 闭环记录补全。

## 9. 闭环记录（2026-10-11）

### 9.1 提交序列

| commit | 内容 |
|---|---|
| `6d279f4f` | docs(48) 设计稿 |
| `fd51f478` | `ProviderErrors` util + 9 单测（先编译红后绿） |
| `bab2ecf7` | completions 车道（format + metadata.raw） |
| `e74b1115` | responses processor supplier/formatter 改形 + openai/azure 车道 |
| `8c3b3765` | `PiHttpException` 带 body（措辞不动）+ Mistral formatter + images |
| `dfb46cc2` | 16 条车道真 HTTP 夹具（L1–L15 + rate-limit 一条） |
| `3d2522e8` | fix：文案未变时发原异常，保留异常类型身份 |
| `3f201205` | refactor：拆 `MistralMessages`（文件 542→336 行）；Mistral formatter 归 `ProviderErrors` |

### 9.2 先红记录

- 单测：类不存在编译红（9 用例），实现后 9/9 绿。
- 车道：接线前 16 条 **13 红 3 绿**；3 绿是刻意的锚点——L4（门控对非 cerebras 不开）、
  L10（内部 throw 今天就不带前缀）、rate-limit（429 文本今天已在白名单）。
- 实施中一处现网回归：Mistral/OpenAI 两条 retry-cap 夹具要求 `StreamError.error()`
  **就是** `RetryDelayTooLongException`（按类型识别），无脑包 `IllegalStateException`
  会把它换成包装类型 ⇒ 加「格式化文案等于原 message 时发原异常」的身份保持
  （`3d2522e8`），与 pi 归一化对非 HTTP 错误恒等同构。
- SDK 实测补证（设计 §5.1 留的两个实测点全部按预期）：
  - openai-java 传输层 `ErrorHandler.errorBodyHandler` 对无 `error` 外层的体
    **整节点回退**（等价 openai-node `makeStatusError` 的包回 `{error: body}`），
    `body()` 正是归一化后的嵌套节点；
  - 嵌套 error 为**字符串**时 SDK 保留标量 JsonValue（L1 实际今日文案
    `403: "blocked…"`，重放后为 pi 的 `403 "blocked…"`）；
  - `ErrorObject.additionalProperties` 确实保留 `metadata`（L5 真 HTTP 线上验证）。

### 9.3 变异探针（grep 均复核落地；CRLF 教训：不用 `$` 跨行锚）

| 探针 | 设计预期 | 实际红集 | 裁决 |
|---|---|---|---|
| M1 `fromOpenAi` 退回 `getMessage()` | L1,L2,L3,L5,L7,L8,L11,L12 | L1,L2,L3,**L6**,L7,L8,L11,L12 ＋单测 8（9 红） | L5 在 M1 下仍恰 1 次（append 独立于重放，真牙口在 L6 的 body JSON 曝光）；单测 8 同红。夹具牙口成立 |
| M2 no-body 文案 → `: Unknown` | 单测 8a、L2、L3 | ＋ **L12**（4 红） | L12 含 images 空体子断言，多红合理 |
| M3 metadata 无条件追加 | L5 | L5（1 红） | 一致 |
| M4 前缀忽略 status 缺 | 单测 7、L10 | 单测 7 **+9**、L10 ＋ **9 条既有 responses 内部错误夹具**（12 红） | 既有 responses 失败族全靠这道门，牙口比预期更充分 |
| M5 Mistral 不读 body | L13、L15 | L13、L15（2 红） | 一致 |

全部恢复后 53 条相关用例复绿。

### 9.4 偏差与方言点

1. **异常身份**（见 9.2）：completions/processor/Mistral 三处统一为「文案变了才包装」，
   设计稿只写了包装，实施时补上 pi 恒等语义对应的类型保持。
2. **非 JSON 纯文本错误体**不可恢复：openai-node 透传原文，openai-java 解析失败只留
   `JsonMissing` 且无原始体文本 API ⇒ 落 no-body 文案；已写进 `ProviderErrors`
   类 javadoc，不登记（主流可达网关错误体均为 JSON）。
3. wrapperless `{}`/JSON 标量等病理形状经 `ErrorHandler` 源码核对与 pi 归一化后同形
   （空对象 ⇒ `N {}`；null/空体 ⇒ no-body），无夹具但源码等价性已证。
4. pi-messages 同族错误格式按计划登记 **B224**，本包未动。
5. 单测 8 的 `ErrorObject.builder()` 四字段（code/message/param/type）全部必填
   （线格反序列化不经 builder，仅测试构造受影响）。
6. **文件行数纠偏**：`MistralConversationsApi` 本包接线后 542 行（存量 510 已超
   500，§7 估算漏计）⇒ 把消息转换块（pi `:787-874`）整体抽到包级
   `MistralMessages`（与既有 `MistralContent`/`MistralTools` 同风格，纯移动），
   `formatMistralError` 归 `ProviderErrors`；车道降到 **336 行**，行为零变化。
   顺带消解了 E 类未登记的存量超限。

### 9.5 验证与计数

- ai 模块全量 **1425** 测试全绿（新增 25：9 单测 + 16 车道）；checkstyle 0；
  改动文件无 `System.out.println`。
- 反应堆：`-pl pi-java-evals -am`（telemetry/ai/mcp/agent-core/sqlite/coding-agent/evals）
  **BUILD SUCCESS**（01:17）；`-pl pi-java-tui -am` **BUILD SUCCESS**（TUI 212 测试
  1 skip，04:44）。protocol/client/server 不依赖 ai，不在影响面。
- 台账：B135/B141 销号、B224 新登记（B 类计数 103→102）；map/02-ai 新增单元 102
  并在单元 70 标注 Cerebras 门生产者已接通；docs/41 §7 已登记。
