# 49 · pi-messages 车道错误格式对齐设计（B224）

> 🚧 **待审核**（2026-10-11）。用户批准后才许写生产代码。
> 锚点：pi `200387122`（取证一律 `git show 200387122:<path>`，不读 pi 工作树）。
> 上一包：`docs/48`（B135/B141，已闭环）——本包是它点名拆出的同族收口。

## 1. 范围速览

| 项 | 裁决 |
|---|---|
| HTTP 非 2xx 的终局 errorMessage 文案 `` `${status} ${statusText}: ${message ?? body}${code ? ` (${code})` : ""}` `` | **做** |
| reason phrase 方言（JDK `java.net.http` 不暴露状态行） | **做**：RFC 标准短语表，未知码 ⇒ 空串（§4.2） |
| `PiMessagesApi` 车道 catch 接线（仅 `PiHttpException` statusCode>0 成形） | **做** |
| models.json 自定义 provider `"api": "pi-messages"` 的派发缺臂 | **做**（补一臂，车道由此首次生产可达，§4.4） |
| `pi_messages_response_failure` 诊断信封 ＋ `AssistantMessage.diagnostics` 字段 ＋ 8192 截断 ＋ 帧/JSONL 透传 | **不做，拆 B225**（§4.3） |
| Radius 内建 provider（catalog/OAuth 接线） | 不在本包，新登记 **B226** |
| 非 HTTP 错误（建连失败/中止/无 key/无终局事件/SSE 内 `error` 事件） | **不动**（pi 也是原文案透传） |

全程只动 **pi-java-ai** 一个模块；`PiHttpClient:154` 的 `"HTTP N"` 异常措辞**仍不改**
（retry 日志断言、Mistral 车道都依赖它；pi-messages 的成形只发生在车道 catch，与 docs/48 同形）。

## 2. pi 证据（锚点逐字）

### 2.1 错误类与格式 —— `packages/ai/src/api/pi-messages.ts:95-152`

```ts
export class PiMessagesResponseError extends Error {
	code?: string;
	readonly diagnosticDetails: JsonObject;

	constructor(message: string, code: string | undefined, diagnosticDetails: JsonObject) {
		super(message);
		this.name = "PiMessagesResponseError";
		this.code = code;
		this.diagnosticDetails = diagnosticDetails;
	}
}

function parsePiMessagesErrorBody(body: string): PiMessagesErrorBody | undefined {
	try {
		const parsed = JSON.parse(body) as PiMessagesErrorBody | null;
		const error = parsed?.error;
		return parsed && typeof error === "object" && error !== null && !Array.isArray(error) ? parsed : undefined;
	} catch {
		return undefined;
	}
}

function truncateDiagnosticString(value: string): string {
	const maxLength = 8192;
	return value.length > maxLength ? `${value.slice(0, maxLength)}…` : value;
}

function formatPiMessagesResponseError(
	response: Response,
	body: string,
	errorBody: PiMessagesErrorBody | undefined,
): string {
	const message = typeof errorBody?.error?.message === "string" ? errorBody.error.message : undefined;
	const code = typeof errorBody?.error?.code === "string" ? errorBody.error.code : undefined;
	const suffix = message ?? body;
	const codeSuffix = code ? ` (${code})` : "";
	return `${response.status} ${response.statusText}: ${suffix}${codeSuffix}`;
}
```

要点（逐条，含边界）：

1. **抛出的错误 message 不截断**。8192 截断只用在诊断 details 的裸 `body` 上（`:133-150`），
   后缀字符是 **U+2026 `…`**（不是 docs/48 的 ASCII `... [truncated N chars]`）。
   诊断信封不移植（B225）⇒ 本包**没有任何截断**。
2. body 解析门（`:109-117`）：JSON parse 成功 ∧ 根是对象 ∧ `error` 是非数组对象。
   根是标量/数组、`error` 是数组/标量、parse 失败 ⇒ 一律按「无结构错误体」处理。
3. `message` 只认真字符串，**空串也算字符串**（`typeof "" === "string"`）；
   但 `message ?? body` 的 `??` 只挡 null/undefined ⇒ **空消息时 suffix 是空串，不回落 body**。
4. `code` 同样只认真字符串，再经 **JS 真值**门（`:130` `code ?`）⇒ 空串不加 ` (code)`。
5. `statusText` 是 fetch `Response.statusText` 原样插入，空串时文案形如 `` `401 : x` ``
   （冒号前一个空格，见 §4.2 方言裁决）。

`createPiMessagesResponseError`（`:133-152`，本包不移植部分）的 details 形状：

```ts
new PiMessagesResponseError(formatPiMessagesResponseError(response, body, errorBody), code, {
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
```

即：解析成功 ⇒ details 带整个 `error` 对象；解析失败 ⇒ details 带 8192 截断的裸 body。

### 2.2 诊断只挂在「抛出的响应错误」上 —— `:321-338`

```ts
function createErrorEvent(model: Model<"pi-messages">, error: unknown, aborted: boolean): AssistantMessageEvent {
	const reason = aborted ? "aborted" : "error";
	const assistantMessage: AssistantMessage = { /* … */
		errorMessage: error instanceof Error ? error.message : String(error),
	};

	if (!aborted && error instanceof PiMessagesResponseError) {
		appendAssistantMessageDiagnostic(
			assistantMessage,
			createAssistantMessageDiagnostic("pi_messages_response_failure", error, error.diagnosticDetails),
		);
	}

	return { type: "error", reason, error: assistantMessage };
}
```

- **SSE 流内 `error` 事件**（`createEventConverter` 的 `case "error"`，`:201-216`）只透传
  `event.errorMessage`，**不挂诊断**。Java 现状同形（`PiMessagesApi:152-159`），不动。
- 中止（aborted）不挂诊断。Java 车道没有 signal 入参（既有登记），中止路根本不进这个 catch 形状。
- 诊断信封 = `{type, timestamp, error:{name,message,stack,code}, details}`
  （`utils/diagnostics.ts:31-44`）。

### 2.3 诊断字段的消费者清单（锚点全仓 grep）

`AssistantMessage.diagnostics` 的生产读点只有两个：

1. `ai/src/utils/assistant-message-frame.ts:87` —— start 帧 structuredClone 透传；
2. `coding-agent/src/core/bug-report.ts:194` —— `collectBugReportDiagnostics` 收集失败助手轮。

落盘面：pi 会话 entry 整体 stringify，diagnostics 随 AssistantMessage 进 JSONL。
**pi-java 没有 bug-report 功能，没有帧格式，`AssistantMessage` 也没有该字段**
（`Message.java:167-173` 已记录刻意不移植的裁决，连消费者清单都列过）。这是拆 B225 的依据。

### 2.4 pi 测试夹具 —— `packages/ai/test/pi-messages.test.ts:222-236`

```ts
it("surfaces backend error responses with diagnostics", async () => {
	const { baseUrl } = await startServer({
		status: 401,
		rawBody: JSON.stringify({ error: { message: "Token expired", code: "unauthorized" } }),
	});
	/* … */
	expect(message.errorMessage).toContain("401");
	expect(message.errorMessage).toContain("Token expired");
	expect(message.errorMessage).toContain("unauthorized");
	expect(message.diagnostics?.[0]?.type).toBe("pi_messages_response_failure");
});
```

夹具用 node:http（HTTP/1.1），只设 `statusCode` ⇒ Node 自动补标准 statusMessage
（"Unauthorized"）。断言只 `toContain`、不钉 phrase —— phrase 来自传输层。

**传输事实**：Node fetch（undici）只说 HTTP/1.1，`statusText` = 服务端状态行短语；
主流服务器/网关发送的都是 RFC 标准短语（Node 自身默认、Node 测试桩亦然）。

## 3. Java 现状

| 位置 | 现状 |
|---|---|
| `PiMessagesApi.java:166-168` | `catch (Exception e) { publisher.submit(builder.emitError("error", e)); }` ⇒ HTTP 错误终局文案是 `"HTTP 400"` |
| `PiHttpClient.java:151-156` | 非 2xx 抛 `new PiHttpException(status, "HTTP " + status, body)`；docs/48 让它带上 body，**message 一字未改** |
| `PiHttpException.java` | `statusCode()` + `body()`（永不 null，缺席 `""`）齐备，本包直接消费 |
| `ProviderErrors.java`（287 行） | 已有 `formatMistralError(Throwable)` 同款「裸 fetch 车道自成一家」格式，本包加同族方法 |
| `ModelsJsonProvider.java:75-85` | switch 只有 OPENAI_COMPLETIONS / ANTHROPIC_MESSAGES 两臂；`PI_MESSAGES` 落 default ⇒ **加载认、用时抛** `"protocol pi-messages is not supported yet"` |
| `Protocol.java:29-30,55-61` | `PI_MESSAGES` 枚举与 `fromWire("pi-messages")` 都在（models.json 加载期不拦） |
| `Message.java:167-173` | `diagnostics` 字段刻意不移植的记录裁决（消费者模块本仓不存在） |
| 生产可达性 | 全仓 `new PiMessagesApi(…)` **只有测试三处**；无 Radius 内建 provider |
| 既有测试 | `PiMessagesApiTest`（流内 SSE error 透传，不断言 HTTP 文案）、`PiMessagesNoRetryTest`（500 恰好 1 次请求，不断言文案） |
| `"HTTP N"` 外部断言 | 仅 `PiHttpClientRetryLogTest:69`（日志，记的是 int status 不是异常文案）与 mcp/SessionShare 各自独立的 HTTP 代码；本包零影响 |

## 4. 设计与裁决

### 4.1 文案真值表

入参：`int status`（必 >0）、`String rawBody`（PiHttpException.body，永不 null）。

| # | body | 输出 |
|---|---|---|
| T1 | `{"error":{"message":"Token expired","code":"unauthorized"}}`，401 | `401 Unauthorized: Token expired (unauthorized)` |
| T2 | `{"error":{"message":"bad"}}`，400 | `400 Bad Request: bad` |
| T3 | `{"error":{"message":"","code":""}}`，400 | `400 Bad Request: `（suffix 空、code 空串不加后缀；**尾冒号后一个空格**） |
| T4 | 空体（byte[0]），400 | `400 Bad Request: `（parse 失败 ⇒ `message ?? body` = 空串） |
| T5 | 纯文本 `overflow`，413 | `413 Payload Too Large: overflow` |
| T6 | `{"error":"boom"}`（error 是标量），400 | `400 Bad Request: {"error":"boom"}`（解析门不通过 ⇒ 裸 body） |
| T7 | `{"error":[]}`，400 | 同上，裸 body |
| T8 | `{"error":{"message":42}}`（message 非字符串），400 | `400 Bad Request: {"error":{"message":42}}` |
| T9 | 10000 字符纯文本，400 | 全文 10000 字符，**无截断**（8192 是 B225 诊断 details 的事） |
| T10 | 任意 body，状态 599 | `599 : <suffix>`（未知码短语空串，空格位置照 pi 模板） |

### 4.2 reason phrase 方言裁决

`java.net.http.HttpResponse` 不暴露状态行，JDK 无公开 reason-phrase API
（`com.sun.net.httpserver` 是服务端，同样不给）。裁决：

- 维护一张 **RFC 9110 / 常见 4xx-5xx（含 3xx 重定向兜底）标准短语表**
  （400 Bad Request、401 Unauthorized、413 Payload Too Large、429 Too Many Requests、
  500 Internal Server Error …，与 Node `http.STATUS_CODES`、JDK  sun.net.www 表同源）；
- **未知码 ⇒ `""`**，文案精确复现 pi 的空 phrase 形状（`"599 : …"`，冒号前单空格）；
- 不可恢复偏差：服务端发**自定义**短语时（HTTP/1.1 允许），Java 给标准短语、pi 原样转发。
  短语在 HTTP/2 已删除，主流网关不自定义，记为方言不登记差距。
- 取短语只认 status int；不试图从任何响应头推断。

### 4.3 诊断信封拆 B225（本包不做）

`pi_messages_response_failure` 信封要真正对齐，需要：①`AssistantMessage` 加第 11 个分量
`diagnostics`（兼容构造器、`fromPartial`、SessionJson JSONL 落盘/回读、帧透传、互读测试）；
②details 的 `error`/`body` 二选一投影与 8192 U+2026 截断；③error 信封
`{name,message,stack,code}`。而它的**唯一行为消费者是 bug-report**（§2.3），该模块本仓不存在
——做了就是纯生产者，`Message.java:167-173` 的既有裁决明确「等那些模块移植时一并做」。

故：B224 以**文案半**销号，新登记 **B225**（诊断信封整体，触发点＝bug-report 模块移植）。
文案半与诊断半在 pi 是同函数的两个返回分量，但 Java 侧两者生命周期独立、无共享代码
（details 需要的 provider/model/url 文案函数根本不收）。

### 4.4 models.json 派补发臂（生产可达性）

pi 的 models.json 自定义 provider 允许 `"api": "pi-messages"`（TypeBox 只校验非空串；
Java `ModelsJsonConfig.java:239-252` 的注释自己记录了这点）。Java 加载期已放行、
`ModelsJsonProvider.createChatApi` 用时抛异常 —— 这是本仓自身的「加载认、用时拒」断口，
补一臂即闭合（自定义 Radius 兼容网关由此可用；内建 Radius provider 本身另登记 B226）：

```java
case PI_MESSAGES -> new PiMessagesApi(options, null);
```

`null` envVar 与另两臂同形（AbstractChatApi 占位 key 回落，本地/自带鉴权端点）；
models.json 的 inline apiKey 在 `withInlineKey` 已先注入 options。

### 4.5 异常身份（docs/48 教训）

成形后的文案几乎不可能等于异常原 message（`"HTTP N"`），但仍按 docs/48 的统一规矩：
**格式化文案等于 `e.getMessage()` 时发原异常**，保住未来可能的类型识别；
非 HTTP 异常（statusCode 0）原样发，不包装。

## 5. 待写 Java 代码

### 5.1 `ai/utils/ProviderErrors.java`（+约 75 行 → ~362）

```java
// ── pi-messages 车道（B224，对齐 pi pi-messages.ts:102-131）──────────────

/**
 * pi {@code formatPiMessagesResponseError}（pi-messages.ts:124-131）。
 *
 * <p>裸 fetch 车道（异常是 {@link PiHttpException}），自成一家：
 * {@code "<status> <phrase>: <message ?? body> (<code>)"}。结构错误体的
 * message/code 只认 {@code error} 纯对象下的字符串字段；空消息是真字符串、
 * 不回落裸 body（{@code ??} 只挡 null/undefined）；code 空串不加后缀。
 * 抛出文案<b>不截断</b>（8192 U+2026 截断属于诊断 details，随 B225）。</p>
 *
 * <p>phrase：JDK HTTP 客户端不暴露 reason phrase（方言，见 docs/49 §4.2），
 * 用 RFC 标准短语表；未知码给空串，精确复现 pi 空 statusText 的
 * {@code "<status> : <suffix>"} 形状。</p>
 */
public static String formatPiMessagesError(int status, String rawBody) {
    String body = rawBody == null ? "" : rawBody;
    String message = null;
    String code = null;
    JsonNode error = piMessagesErrorNode(body);
    if (error != null) {
        JsonNode messageNode = error.get("message");
        if (messageNode != null && messageNode.isTextual()) {
            message = messageNode.asText(); // 可能为 ""：?? 不回落 body
        }
        JsonNode codeNode = error.get("code");
        if (codeNode != null && codeNode.isTextual() && !codeNode.asText().isEmpty()) {
            code = codeNode.asText();
        }
    }
    String suffix = message != null ? message : body;
    return status + " " + reasonPhrase(status) + ": " + suffix
        + (code != null ? " (" + code + ")" : "");
}

/** pi parsePiMessagesErrorBody（:109-117）：根对象 ∧ error 非数组对象，否则 null。 */
private static JsonNode piMessagesErrorNode(String body) {
    if (body == null || body.isBlank()) {
        return null; // JSON.parse("") 抛错
    }
    try {
        JsonNode root = ObjectMappers.jsonMapper().readTree(body);
        JsonNode error = root instanceof ObjectNode ? root.get("error") : null;
        return error instanceof ObjectNode ? error : null;
    } catch (JsonProcessingException e) {
        return null;
    }
}

/** RFC 标准 reason phrase；未知码 ⇒ ""（≙ 空 statusText）。 */
static String reasonPhrase(int status) {
    return REASON_PHRASES.getOrDefault(status, "");
}

private static final Map<Integer, String> REASON_PHRASES = Map.ofEntries(
    Map.entry(301, "Moved Permanently"),
    Map.entry(302, "Found"),
    Map.entry(307, "Temporary Redirect"),
    Map.entry(308, "Permanent Redirect"),
    Map.entry(400, "Bad Request"),
    Map.entry(401, "Unauthorized"),
    Map.entry(402, "Payment Required"),
    Map.entry(403, "Forbidden"),
    Map.entry(404, "Not Found"),
    Map.entry(405, "Method Not Allowed"),
    Map.entry(406, "Not Acceptable"),
    Map.entry(407, "Proxy Authentication Required"),
    Map.entry(408, "Request Timeout"),
    Map.entry(409, "Conflict"),
    Map.entry(410, "Gone"),
    Map.entry(411, "Length Required"),
    Map.entry(412, "Precondition Failed"),
    Map.entry(413, "Payload Too Large"),
    Map.entry(414, "URI Too Long"),
    Map.entry(415, "Unsupported Media Type"),
    Map.entry(416, "Range Not Satisfiable"),
    Map.entry(417, "Expectation Failed"),
    Map.entry(418, "I'm a Teapot"),
    Map.entry(421, "Misdirected Request"),
    Map.entry(422, "Unprocessable Entity"),
    Map.entry(423, "Locked"),
    Map.entry(424, "Failed Dependency"),
    Map.entry(425, "Too Early"),
    Map.entry(426, "Upgrade Required"),
    Map.entry(428, "Precondition Required"),
    Map.entry(429, "Too Many Requests"),
    Map.entry(431, "Request Header Fields Too Large"),
    Map.entry(451, "Unavailable For Legal Reasons"),
    Map.entry(500, "Internal Server Error"),
    Map.entry(501, "Not Implemented"),
    Map.entry(502, "Bad Gateway"),
    Map.entry(503, "Service Unavailable"),
    Map.entry(504, "Gateway Timeout"),
    Map.entry(505, "HTTP Version Not Supported"),
    Map.entry(506, "Variant Also Negotiates"),
    Map.entry(507, "Insufficient Storage"),
    Map.entry(508, "Loop Detected"),
    Map.entry(510, "Not Extended"),
    Map.entry(511, "Network Authentication Required"));
```

（需补 `import java.util.Map;`。`reasonPhrase` 包级静态以便单测直测。）

### 5.2 `ai/protocol/PiMessagesApi.java`（catch 替换，+约 10 行 → ~344）

```java
} catch (Exception e) {
    // B224：HTTP 错误响应按 pi pi-messages.ts:124-131 成形
    // （"N Phrase: message ?? body (code)"）；非 HTTP 故障（建连失败/中止/
    // body 构造）原样发，保留异常身份。SSE 流内 error 事件在 switch 里已透传，
    // 不走这里（与 pi createErrorEvent 只包 PiMessagesResponseError 同界）。
    if (e instanceof com.pijava.ai.http.PiHttpException http && http.statusCode() > 0) {
        String message = ProviderErrors.formatPiMessagesError(http.statusCode(), http.body());
        publisher.submit(builder.emitError("error",
            message.equals(e.getMessage()) ? e : new IllegalStateException(message, e)));
    } else {
        publisher.submit(builder.emitError("error", e));
    }
}
```

（补 `import com.pijava.ai.utils.ProviderErrors;`。）

### 5.3 `ai/provider/ModelsJsonProvider.java`（+3 行）

```java
case PI_MESSAGES -> new PiMessagesApi(options, null);
```

default 异常文案改为 `…(openai-completions / anthropic-messages / pi-messages only)`，
补 import `com.pijava.ai.protocol.PiMessagesApi`。

## 6. 测试计划（先红后绿）

### 6.1 车道级真 HTTP 夹具（先写，先跑出红）

新文件 `pi-java-ai/src/test/java/com/pijava/ai/protocol/PiMessagesErrorBodyTest.java`
（同包复用 `RecordingHttpServer`；`api.send(request, options)` 取终局消息，
脚手架照 `ProviderErrorBodyLanesTest`）：

- **E1** 401 + T1 body ⇒ `errorMessage()` **逐字** `401 Unauthorized: Token expired (unauthorized)`，恰好 1 次请求
- **E2** 400 空体（`new byte[0]`）⇒ 逐字 `400 Bad Request: `
- **E3** 413 纯文本 `policy overflow` ⇒ 逐字 `413 Payload Too Large: policy overflow`
- **E4** 599 + 纯文本 ⇒ 逐字 `599 : weird`（未知码空短语的空格形状）
- **E5** 400 + 10000 字符非 JSON 体 ⇒ 全文 10000 字符、不含 `…`、不含 `HTTP 4`
- **E6** 200 SSE 流内 `error` 事件 ⇒ errorMessage 逐字事件文案（`Upstream failed`），**不被重成形**（对照）

接线前红集预测：**E1–E5 红**（现值 `HTTP 4xx`/`HTTP 599`），**E6 绿**。

### 6.2 单测（util 落地后绿）

`ProviderErrorsTest` 追加 T1–T10 真值表十例（§4.1），另加 `reasonPhrase` 两例
（413 标准短语、599 空串）。新符号先编译红（按既有包惯例记录编译失败红），
util 提交后全绿。

### 6.3 可达性夹具

`pi-java-ai/src/test/java/com/pijava/ai/provider/ModelsJsonPiMessagesTest.java`
（照既有 models.json 测试走临时文件加载）：provider 级 `"api": "pi-messages"`
⇒ `createApi(ChatApi.class, opts)` **instanceof PiMessagesApi**；
再对 `com.sun.net.httpserver` 内联桩端到端 `send` 出 401，errorMessage 逐字 E1 文案。
补臂前：createApi 抛异常（红）。

### 6.4 变异探针（落地均 grep 复核、记录红集、恢复复绿）

| 探针 | 变异 | 预测红集 |
|---|---|---|
| M1 | 车道 catch 退回裸 `emitError("error", e)` | E1–E5 |
| M2 | util 删除 ` (code)` 后缀拼接 | T1 单测、E1 |
| M3 | `reasonPhrase` 恒返回 `""` | T1/T2/T5 等短语单测、E1–E3 |
| M4 | suffix 门改成「空 message 回落 body」（`!message.isEmpty()`） | T3 单测、E2 |
| M5 | 删 ModelsJsonProvider 的 PI_MESSAGES 臂 | 6.3 全红 |

已知**语义等价零牙口**（不设探针，记录在案）：把 §2.1 的 `error` 解析门放松
（接受数组/标量 error）对**文案**零影响 —— 非对象 error 上取不到字符串 message/code，
suffix 仍落裸 body；这道门的牙口在诊断 details 的 `error` vs `body` 二选一，随 **B225**。

## 7. 行数与提交序

| 文件 | 现状 | 预估 |
|---|---|---|
| `ProviderErrors.java` | 287 | ~362 |
| `PiMessagesApi.java` | 334 | ~344 |
| `ModelsJsonProvider.java` | 86 | ~90 |
| 新测试 ×2 + ProviderErrorsTest 追加 | — | ~450 行新增测试 |

全部 ≤500 行。

1. `docs(49): pi-messages error format design`（本提交）
2. `test(ai): red pi-messages error body + models.json reachability fixtures`（6.1+6.3，先跑红记录）
3. `feat(ai): formatPiMessagesError with RFC reason phrases`（5.1 + 6.2 单测）
4. `feat(ai): wire pi-messages HTTP error format in lane catch`（5.2；E1–E6 绿）
5. `feat(ai): route models.json pi-messages providers to PiMessagesApi`（5.3；6.3 绿）
6. M1–M5 探针：grep 复核落地 → 跑 → 记录 → 恢复复绿
7. `docs(04,41,49,map): close B224 text half, register B225/B226`

## 8. 验证门

1. 红集与 §6 预测一致（不一致先查夹具牙口）；探针全部恢复、复绿。
2. `JAVA_HOME='D:\soft\jdk\graalvm-jdk-25' /d/soft/apache-maven-3.9.9/bin/mvn -pl pi-java-ai -am test`
   全绿（计数取 Maven 模块汇总行，基线 1425）。
3. 下游反应堆：`-pl pi-java-evals -am`、`-pl pi-java-tui -am` SUCCESS。
4. `mvn checkstyle:check` 0；改动文件无 `System.out.println`。
5. 台账：B224 销号、新登 **B225**（诊断信封整体，触发点 bug-report 移植）与
   **B226**（Radius 内建 provider：OAuth 工厂 `OAuthProviders.radius` 已在、
   chat provider/catalog 无接线）；docs/41 §7、map/02 回填；§9 闭环记录补全。

## 9. 闭环记录

（闭环时回填：提交表、实际红集、探针实际红集与预测差异、偏差、验证计数。）
