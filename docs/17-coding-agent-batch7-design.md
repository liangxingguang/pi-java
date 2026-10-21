# coding-agent 批 7 设计稿：provider 请求面扩展事件四件（B248）

> **状态：已闭环（2026-10-21，用户授权免审直接实施）**。T1 ai 缝（`ProviderHooks` 三键＋七车道 `onPayload`/`onProviderStreamEvent`＋SDK 四车道 `withRawResponse` onResponse）＋T2 自备 HTTP 面（`SseResponseObserver` 终态外露＋mistral/pi-messages onResponse＋Azure/PiMessages 头通道补）＋T3 扩展桥（ADT/spec 四件＋`ProviderEventBridge`＋createApi 前头变换缝＋`wireSession`/`loadExtensions` 晚绑定接线）。新夹具 9 件 37 用例全绿（ai 六件 18：Anthropic 6／OpenAi 3／Mistral 3／PiMessages 3／Google 2／Azure 1；coding-agent 三件 19：bridge 12／providers 4／session wiring 3）；ai 1,734＋coding-agent 789＋tui 221＋web 56 回归全绿；全量 clean verify 见 git 记录。**B248 销号**、**B253**（attribution 头层）新登记；D 域 66.0→71.5/83＝**86.1%**、模块 283.5→289.0/496≈**58.3%**、全仓 948.5→954.0/1381≈**69.1%**；窄差 +D31–D35；引擎钩子 15 不变。
> 基线：pi `ea448f454`（2026-10-11）；pi-java main（coding-agent 批 6 闭环后，`cfd696e6`）。
> 所有 pi 侧 `file:line` 均经 `git -C D:/workplaceForai/pi show ea448f454:<path>` 实读，未读 pi 工作树。
> 前批：`16-coding-agent-batch6-design.md`（已闭环，fork 扩展装配＋会话关闭治理；B251/B252 销号）。

| 项 | 内容 |
|---|---|
| 批次 | coding-agent 批 7（docs/17）＝台账 **B248**（provider 请求面扩展事件四件：`before_provider_request`／`before_provider_headers`／`after_provider_response`／`provider_stream_event`）；pi 锚点 `ea448f454`；判定权威＝map/04 逐行 |
| 重件 | **T1**（ai 缝）：`com.pijava.ai.api.ProviderHooks` 四函数接口＋`ApiOptions.extra` 三键（`onPayload`/`onResponse`/`onProviderStreamEvent`，键名逐字对位 pi `StreamOptions`）；七车道落 `onPayload`（载荷改写）与 `onProviderStreamEvent`（原始事件观察）；SDK 四车道 `onResponse` 走 `withRawResponse()`（§3.1–§3.5） |
| 中件 | **T2**（自备 HTTP 响应面＋头通道补）：`PiHttpClient`/`SseTransport` 外露终态响应 status/headers（mistral/pi-messages 两车道 `onResponse`）；Azure 补 `putExtraHeaders`、PiMessages 补 `extra["headers"]` 合并（pi azure :156-169／pi-messages :394-399 对位——不补则头事件对这两车道是空操作）（§3.6–§3.8）；**T3**（扩展桥＋接线）：事件 ADT＋spec 四件、`ProviderEventBridge`（bus/ctx 晚绑定，pi `extensionRunnerRef` 对位）、`DefaultProviders` 注入（transformHeaders 缝消费＋三钩入 extra）（§3.9–§3.12） |
| 关键事实 | **google 车道不发 `onResponse`＝对齐不是窄差**：pi 的 google-generative-ai.ts/google-vertex.ts 全文件零 `onResponse` 命中（genai SDK 成功流拿不到响应头，pi 也没做）；java 照缺。**pi-messages 车道在 pi 不豁免**：pi-messages.ts:387/:404/:415 三钩全有（与 `resolvesRequestOptions` 的 R2 豁免是两层事，勿混） |
| 不碰 | `session_before_tree`/`session_tree`（**B245**，批 8 候选）；reload 臂（**B246**）；MCP 接线面（**B221**）；D′ 扩展 UI 面；E6 包管理器；bedrock/codex/vertex 车道（java 整体缺席，既有登记）；分类器（**B164**）；attribution 头层（新登记 **B253**）；旧引擎钩 `before_payload`/`after_response` 维持不 firing（§2.3） |
| 一句话 | 批 4–6 把扩展事件的「引擎族＋宿主族」全接了，剩下最后一条 w3 事件线要穿过 provider 请求本身；本批按 pi 的同层构造（streamFn options 缝，不动引擎钩子）把四个事件点着——扩展自此能看/改每一次出站请求的载荷与头、看到响应状态与每一个原始流事件。 |

预期账面（对账表见 §8）：map/04 **D-17**（w3 存疑→对齐，+1.5）／**D-18**（w2 存疑→对齐，+1.0）／**D-19**（w2 存疑→对齐，+1.0）／**D-20**（w2 缺失→对齐，+2.0）四行翻转，Δ＝**+5.5**；D 域 66.0→**71.5/83＝86.1%**；模块 283.5→**289.0/496≈58.3%**；全仓 948.5→**954.0/1381≈69.1%**；w3 层 #5 翻对齐（D 域 w3 欠账清零——#6/#8 批 5 已翻）。销号 **B248**；新登记 **B253**（attribution 头层）；窄差 +D31–D35；引擎钩子数 **15 不变**（本批不走引擎钩，裁决②）。

---

## §1 pi 侧事实（取证，锚点 `ea448f454`，只走 `git show`）

### 1.1 四事件类型与订阅面（types.ts 逐字）

载荷（types.ts:885-914）：

```ts
/** Fired before a provider request is sent. Can replace the payload. */
export interface BeforeProviderRequestEvent {
	type: "before_provider_request";
	payload: unknown;
}
/**
 * Fired after request headers are assembled, before the provider HTTP call.
 * Handlers mutate `headers` in place (e.g. to inject tracing/session headers);
 * the return value is ignored. A `null` value deletes that header.
 */
export interface BeforeProviderHeadersEvent {
	type: "before_provider_headers";
	headers: ProviderHeaders;          // = Record<string, string | null>（ai types.ts:148）
}
/** Fired after a provider response is received and before the response stream is consumed. */
export interface AfterProviderResponseEvent {
	type: "after_provider_response";
	status: number;
	headers: Record<string, string>;
}
/** Fired for a parsed provider stream event before Pi normalizes it. */
export interface ProviderStreamEvent {
	type: "provider_stream_event";
	provider: ProviderId;
	api: Api;
	model: string;
	data: unknown;
}
```

订阅面（types.ts:1604-1609）与结果类型（:1422）：

```ts
on(event: "before_provider_request",
	handler: ExtensionHandler<BeforeProviderRequestEvent, BeforeProviderRequestEventResult>): () => void;
on(event: "before_provider_headers", handler: ExtensionHandler<BeforeProviderHeadersEvent>): () => void;
on(event: "after_provider_response", handler: ExtensionHandler<AfterProviderResponseEvent>): () => void;
on(event: "provider_stream_event", handler: ExtensionHandler<ProviderStreamEvent>): () => void;
// :1422
export type BeforeProviderRequestEventResult = unknown;
```

即：`before_provider_request` 是**结果型**（处理器返回非 undefined ⇒ 替换当前载荷）；其余三件是**观察型**（`ExtensionHandler<E, undefined>`，返回值被忽略——headers 事件靠**原位改写**生效）。

### 1.2 派发语义（runner.ts 逐字）

两件专用 emit（runner.ts:1360-1420）——链式替换＋逐处理器异常隔离：

```ts
async emitBeforeProviderRequest(payload: unknown): Promise<unknown> {
	const ctx = this.createContext();
	let currentPayload = payload;
	for (const { ext, handlers } of snapshotEventHandlers(this.extensions, "before_provider_request")) {
		for (const handler of handlers) {
			try {
				const event: BeforeProviderRequestEvent = { type: "before_provider_request", payload: currentPayload };
				const handlerResult = await handler(event, ctx);
				if (handlerResult !== undefined) { currentPayload = handlerResult; }
			} catch (err) {
				// … this.emitError({ extensionPath: ext.path, event: "before_provider_request", error: message, stack });
			}
		}
	}
	return currentPayload;
}

async emitBeforeProviderHeaders(headers: ProviderHeaders): Promise<ProviderHeaders> {
	const ctx = this.createContext();
	for (const { ext, handlers } of snapshotEventHandlers(this.extensions, "before_provider_headers")) {
		for (const handler of handlers) {
			try {
				// Handlers mutate `headers` in place; the return value is ignored.
				const event: BeforeProviderHeadersEvent = { type: "before_provider_headers", headers };
				await handler(event, ctx);
			} catch (err) { /* … emitError 同上，继续下一处理器 */ }
		}
	}
	return headers;
}
```

另两件走**通用 `emit`**（runner.ts:1089-1119）：逐处理器 try/catch ⇒ `emitError` 后继续；这两事件非 session_before 族 ⇒ 结果恒被忽略。

### 1.3 注入口装配（sdk.ts 逐字）——四闭包＋late-binding ref

```ts
// sdk.ts:333（extensionRunnerRef 是 { current?: ExtensionRunner }，扩展加载后回填）
// sdk.ts:340-363  buildRequestOptions：transformHeaders 闭包
transformHeaders: async (requestHeaders) => {
	const headers = mergeProviderAttributionHeaders(
		model, settingsManager, options.sessionId, requestHeaders);
	return headerRunner?.hasHandlers("before_provider_headers")
		? headerRunner.emitBeforeProviderHeaders(headers ?? {})
		: (headers ?? {});
},
// sdk.ts:382-410
const transformProviderPayload = async (payload: unknown) => {
	const runner = extensionRunnerRef.current;
	if (!runner?.hasHandlers("before_provider_request")) return payload;
	return runner.emitBeforeProviderRequest(payload);
};
const handleProviderResponse = async (response) => {
	const runner = extensionRunnerRef.current;
	if (!runner?.hasHandlers("after_provider_response")) return;
	await runner.emit({ type: "after_provider_response", status: response.status, headers: response.headers });
};
const handleProviderStreamEvent = async (data, model) => {
	const runner = extensionRunnerRef.current;
	if (!runner?.hasHandlers("provider_stream_event")) return;
	await runner.emit({ data, type: "provider_stream_event",
		provider: model.provider, api: model.api, model: model.id });
};
// sdk.ts:432-434  Agent 构造选项
onPayload: transformProviderPayload,
onResponse: handleProviderResponse,
onProviderStreamEvent: handleProviderStreamEvent,
```

三个闭包是 **Agent 构造选项**（agent.ts:120/198/238 收，`createLoopConfig` :473-475 装进每一次 loop 配置）⇒ **每一轮请求**（含压缩、摘要、重试——它们全走同一 streamFn）都过这三钩；`transformHeaders` 则挂在每请求重建的 `buildRequestOptions` 里。四处的 `hasHandlers` 门都是**调用时现查**（扩展后注册也生效；空表零开销快走）。

### 1.4 ai 层消费点（逐车道）

**`transformHeaders` 的唯一消费点在 Models 层**（models.ts:843-872 `applyAuth`）：

```ts
const apiKey = options?.apiKey ?? auth.apiKey;
let headers = mergeHeaders(auth.headers, options?.headers);
if (options?.transformHeaders) headers = await options.transformHeaders(headers ?? {});
// :870-871  变换后**剥离**该键再下车道：
const { transformHeaders: _transformHeaders, ...providerOptions } = options ?? {};
```

**四钩的车道 firing 点**（`onPayload` 替换臂＋`onResponse`＋`onProviderStreamEvent`；google 两车道**无 `onResponse`**）：

| 车道 | onPayload | onResponse | onProviderStreamEvent |
|---|---|---|---|
| anthropic-messages | :640（`params = { ...(nextParams as MessageCreateParamsStreaming), stream: true }`） | :657（成功获取后、`stream.push(start)` 前） | :664（`for await` 循环首行，归一化前） |
| azure-openai-responses | :77 | :94 | :98（透传进共享消费器） |
| bedrock-converse-stream | :282 | :295（Smithy 中间件） | :299 |
| google-generative-ai | :96（`params = nextParams as GenerateContentParameters`） | **无**（全文件零命中） | :107 |
| google-vertex | :105 | **无** | :116 |
| mistral-conversations | :148 | :330 | :611 |
| openai-codex-responses | :278 | :414 | :751 |
| openai-completions | :366 | :383 | :559 |
| openai-responses | :174 | （经 shared） | openai-responses-shared.ts:600 |
| pi-messages | :387 | :404（**在 `!response.ok` 检查之前**——错误状态也发） | :415 |
| classifier-shared / llama-cpp-classify | :71 / :231 | :102 / :268 | — |

anthropic 车道 firing 点逐字（:639-664）：

```ts
let params = buildParams(model, normalizedContext, isOAuth, options);
const nextParams = await options?.onPayload?.(params, model);
if (nextParams !== undefined) {
	params = { ...(nextParams as MessageCreateParamsStreaming), stream: true };
}
// … retryProviderRequest(() => client.beta.messages.create(params, requestOptions).asResponse(), …)
await options?.onResponse?.({ status: response.status, headers: headersToRecord(response.headers) }, model);
stream.push({ type: "start", partial: output });
// …
for await (const event of iterateAnthropicEvents(response, options?.signal)) {
	await options?.onProviderStreamEvent?.(event, model);
	// 归一化 …
```

pi-messages 车道的头合并与 onResponse 位置（:394-404）：

```ts
const response = await (options?.fetch ?? globalThis.fetch)(url, {
	method: "POST",
	headers: {
		authorization: `Bearer ${apiKey}`,
		accept: "text/event-stream",
		"content-type": "application/json",
		...providerHeadersToRecord(options?.headers),   // 小写规范化＋null 删除
	},
	body: JSON.stringify(payload),
	signal: options?.signal,
});
await options?.onResponse?.({ status: response.status, headers: headersToRecord(response.headers) }, model);
if (!response.ok) { /* 抛错 */ }
```

`headersToRecord`（ai utils/headers.ts）：fetch `Headers.entries()` 逐对摊平成 `Record<string,string>`（fetch 头名小写）。**响应头口径**：SDK 车道只在**成功获取**后发（重试耗尽在 `retryProviderRequest` 内抛 ⇒ 到不了 onResponse）；fetch 车道（mistral/pi-messages）收到终态响应即发（含 4xx/5xx）——pi 自身两族口径不一致，照抄即对齐。

### 1.5 行为规格（pi 测试逐字要点）

- `sdk-stream-options.test.ts:273-298`（"forwards provider stream events to extensions"）：`onProviderStreamEvent` 存在；扩展收到 `{data, type, provider, api, model}` 五键全对。
- `sdk-stream-options.test.ts:300-320`（"runs before_provider_headers on assembled headers **without forwarding the transform**"）：事件看到的头集＝provider 层＋model 层＋显式 options 层**三层合并**；处理器新增的头落在最终 `options.headers` 上；**下车道的 options 不再有 `transformHeaders` 键**。
- `extensions-runner.test.ts:1384-1436`（"before_provider_headers"）：原位改写生效且既有头保留；**抛异常的处理器被隔离**（`emitError` 一次），其余处理器照常生效。

---

## §2 java 侧现状与缺口（实读）

### 2.1 请求链全景（唯一生产路径）

```
AgentSession.wireSession（coding-agent，:422）
  └─ DefaultProviders.streamFnFor(args, provider, providers, settings, sessionId)   ← 唯一 StreamFn 生产者
       └─（每请求）streamBlocking(provider, model, context, options, apiOptions)
            ├─ modelInfo 解析（:266-269）
            ├─ withPerModelOverrides（:175-183：modelInfo.headers() → extra["headers"]）
            ├─ withModelProtocol（:234-244：modelInfo.api() → extra["protocol"]）
            ├─ provider.createApi(ChatApi.class, options)      ← 车道**每请求新建**
            └─ api.streamBlocking(request, effectiveOptions)
                 └─ AbstractChatApi.stream → streamInternal（车道实现，SDK/自备 HTTP）
```

`ApiOptions.extra`（`Map<String,Object>`）是 per-request 选项的**既有通道**：`cacheRetention`／`sessionId`／`headers`／`protocol`／`maxRetryDelayMs`／`federationEnv`／`samplingParams` 全走它（`SimpleOptions` javadoc 与 `DefaultProviders` 各处先例）。车道在**构造期**读 extra 存字段（车道每请求新建 ⇒ 构造期＝请求期，`AnthropicMessagesApi:70-136` 先例）。

### 2.2 四缝现状（全缺）

| 缝 | 现状 |
|---|---|
| `onPayload`（载荷改写） | **无**。七车道构建点：anthropic `AnthropicMessagesApi:206`（SDK `MessageCreateParams`）、openai-comp `:194`（SDK `ChatCompletionCreateParams`）、openai-resp `:118` / azure `:80`（SDK `ResponseCreateParams`，同 `ResponsesMessageConverter.buildParams`）、google `:136-149`（`contents`＋`GenerateContentConfig` 两件）、mistral `:134`（`buildRequestBody:396-436`＝`HashMap`→`:435` 序列化）、pi-messages `:85`（`buildBody:281-309`＝`ObjectNode`→`:305` 序列化） |
| `transformHeaders`（头变换） | **无**。extra["headers"] 的唯一公共读取点＝`AbstractChatApi.putExtraHeaders:347`（anthropic :121／openai-comp :114／openai-resp :65／google :92-99 在构造期应用）；mistral 走 `MistralHeaders.build:31-50`（构造期 `:111-112` 读 extra，含 **null＝删除**语义）；**Azure 与 PiMessages 完全不读**（§2.4） |
| `onResponse`（响应观察） | **无成功路径**：anthropic/openai SDK 的 `StreamResponse` 只有 `stream()`/`close()`；google `ResponseStream` 同；`PiHttpClient.postSse` 只暴露 `Iterator<ServerSentEvent>`（status/headers 在 `SseTransport` 内部 `ResponseInfo`，:108 自用后丢弃；legacy 路径 `PiHttpClient:156` 的 `HttpResponse<String>` 用完即弃）。失败路径的 status/headers（SDK 异常、`PiHttpException`）不是 pi 的 firing 语义 |
| `onProviderStreamEvent`（原始事件观察） | **无**。迭代点：anthropic `:215`（`RawMessageStreamEvent`）、openai-comp `:204`（`ChatCompletionChunk`）、openai-resp/azure `ResponsesStreamProcessor:95`（`ResponseStreamEvent`）、google `:152`（`GenerateContentResponse`）、mistral `:152-162`（SSE `data` String→`:287` 解析 Map）、pi-messages `:92-98`（`PiMessagesEvent` sealed） |

### 2.3 旧引擎钩为什么不是本批的缝

`HookSystem` 里确有 `before_payload`（`Map` 载荷，`fireBeforePayload` **零调用点**）与 `after_response`（`ResponseContext`＝lane.partial＋usage，`PiLaneRequestTelemetry:105` 在一轮**结束**时发）——两者都是 legacy harness 遗物，**形状与 pi 的四事件都对不上**（pi 载荷是车道原生 params／`{status, headers}`）。pi 侧这四件**不走 agent-loop 钩子**，走的是 Agent 构造选项→streamFn options→车道（§1.3/§1.4）。⇒ 本批桥放 **StreamFn 层**（与 pi 同层），旧钩维持现状不 firing、不删除（另族债，不在册）。引擎钩子数 15 不变。

### 2.4 头通道缺口（随本批同修，裁决⑦）

- **Azure**：`AzureOpenAIResponsesApi` 构造（:55-62）**无 `putExtraHeaders`**——extra["headers"]（models.json 三层合并值）从不应用；pi azure :156-169 是 `{User-Agent, ...model.headers, ...options.headers}` 全量进 `defaultHeaders`。
- **PiMessages**：`:86-88` 手写 `Map.of(Authorization, Accept)`，不读 extra["headers"]；pi :394-399 有 `...providerHeadersToRecord(options?.headers)`。
- 不补这两处，`before_provider_headers` 的改写对这两条车道是**空操作**（事件照发、线上不变）——与 B248「改写到达线上」的语义同根，随 T2 同修。

### 2.5 扩展装配面与 late-binding 缺口

`loadExtensions`（`AgentSession:1848-1896`）每会话/fork 建 **fresh `ExtensionEventBus`**＋`DefaultExtensionContext`，`EngineHookBridge`/`SessionEventBridge` 在此安装。StreamFn 在 `wireSession:422` **先于** `loadExtensions` 创建 ⇒ 桥闭包拿不到 bus——正是 pi `extensionRunnerRef: { current }`（sdk.ts:333）的形状：先建闭包、后回填。java 侧尚无这个 ref，本批新增（T3）。

---

## §3 设计

### T1（ai 缝）：`ProviderHooks` ＋ 七车道三钩

#### 3.1 `com.pijava.ai.api.ProviderHooks`（新文件）

四函数接口＋三 extra 键常量＋类型化读取器（集中 extra 读点，`AnthropicStreamSupport.sessionIdOf` 先例）：

```java
/** pi {@code StreamOptions} 的请求面四钩（types.ts:178-223）的 extra 通道对位。 */
public final class ProviderHooks {
    /** extra 键名——逐字对位 pi StreamOptions 键。transformHeaders 不设键（宿主缝消费，裁决①）。 */
    public static final String ON_PAYLOAD = "onPayload";
    public static final String ON_RESPONSE = "onResponse";
    public static final String ON_PROVIDER_STREAM_EVENT = "onProviderStreamEvent";

    /** pi {@code onPayload}（types.ts:178）：非 null 返回 ⇒ 替换当前载荷（车道原生对象）。 */
    @FunctionalInterface
    public interface PayloadHook {
        Object onPayload(Object payload, ModelInfo model);
    }

    /** pi {@code onResponse}（types.ts:217）：响应到达、流消费前；观察型。 */
    @FunctionalInterface
    public interface ResponseHook {
        void onResponse(int status, Map<String, String> headers, ModelInfo model);
    }

    /** pi {@code onProviderStreamEvent}（types.ts:223）：原始事件，归一化前；data 适配器所有、只读。 */
    @FunctionalInterface
    public interface StreamEventHook {
        void onEvent(Object data, ModelInfo model);
    }

    public static PayloadHook payloadHookOf(ApiOptions options) { /* extra 读取＋类型守卫 */ }
    public static ResponseHook responseHookOf(ApiOptions options) { /* 同 */ }
    public static StreamEventHook streamEventHookOf(ApiOptions options) { /* 同 */ }
}
```

车道构造期读钩存字段（`cacheRetention` 先例），`streamInternal` 内 firing。

#### 3.2 `onPayload` 七车道（构建后、HTTP 前）

```java
// SDK 车道（anthropic :206 后；openai-comp :194 后；openai-resp :118 后；azure :80 后）：
var params = buildParams(/* … */);
if (payloadHook != null) {
    var next = payloadHook.onPayload(params, request.model());
    if (next != null) {
        params = requireType(next, MessageCreateParams.class);   // 类型守卫，裁决⑤/D31
    }
}
```

- **google**：pi 载荷是单一 `GenerateContentParameters{model, contents, config}`；java 三分参调用 ⇒ 新载体 record（窄差 D32）：
  ```java
  public record GooglePayload(String model, List<Content> contents, GenerateContentConfig config) {}
  ```
  firing 于 :147 前；替换后拆包送回 `generateContentStream(g.model(), g.contents(), g.config())`。
- **mistral**：`buildRequestBody` 拆两截——`:396-433` 的 `HashMap` 构建抽出为 `buildRequestBodyMap`，firing 后 `:435` 才序列化（替换＝返回 `Map<String,Object>`）。
- **pi-messages**：`buildBody` 同拆——`ObjectNode` 构建（:281-305 前段）⇒ firing ⇒ 序列化（:305）。`ObjectNode` 可变，但替换语义走返回值（pi 逐字：看 `handlerResult !== undefined`，不看原位改）。

#### 3.3 `onProviderStreamEvent` 七车道（迭代首行，归一化前）

每车道在 §2.2 表的迭代点首行插一行（hook null 快走）：

```java
sr.stream().forEach(raw -> {
    if (streamEventHook != null) streamEventHook.onEvent(raw, request.model());
    StreamEvent se = mapEvent(raw, /* … */);
```

mistral 的「原始事件」＝SSE `data` String（解析 **之前**，pi :611 喂的是解析后 chunk——mistral 车道 pi 喂的是 `consumeChatStream` 收到的解析后 JSON chunk；java 对位为 `:287` 解析出的 `Map<String,Object>`，在逐字段映射前 firing）；pi-messages 喂 `PiMessagesEvent`（反序列化后、switch 前，pi :415 同位）。

#### 3.4 SDK 四车道 `onResponse`：`withRawResponse()` 改签

两 SDK 已核实支持（javap，anthropic-java-core 2.66.0／openai-java-core 4.42.0）：`client.withRawResponse().messages().createStreaming(params)` → `HttpResponseFor<StreamResponse<…>>`（`statusCode()`／`headers()`／`parse()`）。

```java
// anthropic :211-213 改签（openai-comp/openai-resp/azure 同形）：
var raw = ProviderRetry.retry(
        () -> client.withRawResponse().messages().createStreaming(params),
        ProviderRetry::ofAnthropic, providerRetry);
if (responseHook != null) {
    responseHook.onResponse(raw.statusCode(), flatten(raw.headers()), request.model());
}
StreamResponse<RawMessageStreamEvent> sr = raw.parse();
try (sr) { /* 原流式循环不动 */ }
```

- 位置语义＝pi :657：成功获取后、`emitStart` 消费前发；重试耗尽在 `ProviderRetry` 内抛 ⇒ 不发（pi SDK 车道同形，§1.4）。
- `flatten`：SDK `Headers`（`names()`×`values(name)`）摊平成 `Map<String,String>`，同名多值逗号连接（≈fetch `Headers.entries()` 行为；名大小写按 SDK 原样——窄差 D35）。
- `HttpResponseFor` 实现 `AutoCloseable`；`parse()` 产物的 close 归既有 `try (sr)`——实现时以既有 wire 夹具验证无泄漏/双关。

#### 3.5 google 车道不发 `onResponse`（对齐，非窄差）

pi 的 google-generative-ai.ts／google-vertex.ts 全文件零 `onResponse` 命中（§1.4 表）。genai SDK（1.72.0）成功流同样不暴露 status/headers。⇒ java google 车道**照缺**，设计说明钉死，不进窄差表。

### T2（自备 HTTP 响应面＋头通道补）

#### 3.6 `PiHttpClient`/`SseTransport` 外露终态响应

新观察者接口（http 包）＋两路径各一个 firing 点（**终态**响应——重试环退出后、2xx 判定前，pi fetch 车道「收到即发、含错误状态」对位）：

```java
/** 终态响应观察（pi fetch 车道 onResponse 的 firing 位：重试环后、!ok 判定前）。 */
@FunctionalInterface
public interface SseResponseObserver {
    void onResponse(int status, Map<String, String> headers);
}
```

- `postSse` 加带 observer 的重载（旧三重载零改签，委托新重载传 null）；
- `SseTransport.execute`：`:131`（不可再重试）后、`:132` 2xx 判定前 fire——`HttpHeaders` 摊平（同名多值逗号连接）；
- legacy 路径（`executeWithRetrySse`，pi-messages 用）：`:174` 抛错前 fire（`response.statusCode()`/`response.headers()` 在场）；
- 两车道把 `ProviderHooks.ResponseHook` 适配成 observer（补 model 参）。

#### 3.7 Azure 补 `putExtraHeaders`

pi azure :156-169：`{ "User-Agent": getPiUserAgent(), ...model.headers }`＋`Object.assign(headers, options.headers)` ⇒ `defaultHeaders`。java 对位：构造期 `putExtraHeaders(options, builder::putHeader)`（openai-java azure builder 与标准 builder 同族，有 `putHeader`）。pi 的 `User-Agent` attribution 不移植（B253）。

#### 3.8 PiMessages 补 `extra["headers"]` 合并

pi :394-399：`authorization/accept/content-type` 三手写头＋`...providerHeadersToRecord(options?.headers)`（小写规范化、null 删除、调用方值压默认）。java 对位：`:86-88` 的 `Map.of` 改拼装——手写三头打底，extra["headers"] 逐条覆盖（null 值＝删除该键；`PiHttpClient:112` 已是 `setHeader` 替换语义）。

### T3（扩展桥＋接线）

#### 3.9 事件 ADT＋spec 四件（`ExtensionEvent`／`ExtensionEventSpec`）

```java
/** pi {@code BeforeProviderRequestEvent}（types.ts:887-891）——payload 是车道原生对象
 *  （SDK params 类／Map／ObjectNode／GooglePayload；结果型：返回非 null ⇒ 替换）。 */
record BeforeProviderRequestEvent(Object payload) implements ExtensionEvent {}

/** pi {@code BeforeProviderHeadersEvent}（types.ts:893-902）——headers 是**可变** map
 *  （原位改写，返回值忽略；null 值＝删除该头）。 */
record BeforeProviderHeadersEvent(Map<String, String> headers) implements ExtensionEvent {}

/** pi {@code AfterProviderResponseEvent}（types.ts:904-908）。 */
record AfterProviderResponseEvent(int status, Map<String, String> headers) implements ExtensionEvent {}

/** pi {@code ProviderStreamEvent}（types.ts:910-914）——data 适配器所有、只读。 */
record ProviderStreamEvent(String provider, String api, String model, Object data)
        implements ExtensionEvent {}
```

spec：`BEFORE_PROVIDER_REQUEST`（R＝`Object`，pi :1422 `unknown` 对位）、`BEFORE_PROVIDER_HEADERS`／`AFTER_PROVIDER_RESPONSE`／`PROVIDER_STREAM_EVENT`（均 `Void`）。

#### 3.10 `ProviderEventBridge`（extension/bridge/ 新文件）

pi `extensionRunnerRef`＋四闭包（sdk.ts:333/340-410）的对位。持有 `AtomicReference<ExtensionEventBus>`＋ctx；`install(bus, ctx)` 由 `loadExtensions` 回填（未安装／`--no-extensions` ⇒ 全跳，pi 空表同效）。实现 `ProviderHooks` 三接口＋头缝方法：

```java
public final class ProviderEventBridge {
    private final AtomicReference<ExtensionEventBus> busRef = new AtomicReference<>();
    private volatile ExtensionContext ctx;

    void install(ExtensionEventBus bus, ExtensionContext ctx) { … }

    /** pi emitBeforeProviderRequest（runner.ts:1360-1392 逐字）：hasHandlers 门＋链式替换＋
     *  逐处理器 catch(Throwable)→emitError 继续。无门/无替换 ⇒ null（调用方保留原载荷）。 */
    Object onPayload(Object payload, ModelInfo model) { … }

    /** pi buildRequestOptions 的 transformHeaders 闭包（sdk.ts:354-363）：原位改写＋null 删除；
     *  无门 ⇒ 原样返回。 */
    Map<String, String> transformHeaders(Map<String, String> headers) { … }

    /** pi handleProviderResponse（sdk.ts:387-395）：通用 emit 对位（观察、异常隔离）。 */
    void onResponse(int status, Map<String, String> headers, ModelInfo model) { … }

    /** pi handleProviderStreamEvent（sdk.ts:396-410）：事件五键 {data,type,provider,api,model}。 */
    void onEvent(Object data, ModelInfo model) { … }
}
```

`provider/api/model` 三键取自 lane 传入的 `ModelInfo`（`id().provider()`／`api()`／`id().modelName()`；`api()` null〔目录未命中 minimal〕⇒ 键给 `null` ≙  pi 的必填字段在 java 数据面缺席，登记说明）。

#### 3.11 `DefaultProviders` 注入（transformHeaders 缝＋三钩入 extra）

`streamFnFor` 加带桥参数的重载（旧签名保持，桥 null＝不注入）：

```java
// streamBlocking 内，withPerModelOverrides 之后、createApi 之前：
var effectiveOptions = withPerModelOverrides(apiOptions, modelInfo);
if (bridge != null) {
    // pi models.ts:866-871 对位：合并头集过 transformHeaders，结果写回 extra["headers"]；
    // 钩子本体**不入** extra（pi :870 剥离键/"without forwarding the transform" 对位）。
    effectiveOptions = bridge.applyHeaderTransform(effectiveOptions);
    effectiveOptions = bridge.withRequestHooks(effectiveOptions);   // 三钩入 extra
}
var api = provider.createApi(ChatApi.class, withModelProtocol(effectiveOptions, modelInfo));
```

`applyHeaderTransform`：`extra["headers"]`（models.json 三层合并值；可能缺席 ⇒ 空 map 起步——pi 的 `headers ?? {}`）⇒ 可变 `LinkedHashMap` ⇒ `bridge.transformHeaders` ⇒ 写回（null 值条目在桥内已删）。`withRequestHooks`：`onPayload`/`onResponse`/`onProviderStreamEvent` 三键入 extra（值＝桥的三接口方法引用）。

#### 3.12 `AgentSession` 接线

- `wireSession`：`var providerBridge = new ProviderEventBridge();` → 传 `streamFnFor` 新重载 → 存 `owner` 字段（`extensionFirer` 先例）；
- `loadExtensions`：bus/context 建好后 `owner.providerBridge.install(bus, context)`（`--no-extensions` 早退 ⇒ 永不安装 ⇒ 全跳）；
- fork：wireSession 每 fork 新桥新 streamFn（pi createRuntime 新 ref 对位），`loadExtensions` 各自回填。

---

## §4 裁决条目

### 裁决① 缝的载体＝`ApiOptions.extra` 三键；`transformHeaders` 不设键

- 【场景】pi 的四钩是 `StreamOptions`/`ModelsSimpleStreamOptions` 的四个可选键；java 没有单一 options 对象，`ApiOptions.extra` 是 per-request 选项的既有唯一通道（`cacheRetention`/`sessionId`/`headers`/`protocol` 等七个先例）。
- 【决定】`onPayload`/`onResponse`/`onProviderStreamEvent` 三键入 extra（键名逐字），车道构造期读取；`transformHeaders` **不设 extra 键**——pi 的唯一消费点是 Models 层 `applyAuth`（models.ts:866-871，消费后剥离），java 无 Models 层，等价消费点是 `DefaultProviders.streamBlocking` 的 createApi 前缝（§3.11），钩本体不下车道（pi 测试 "without forwarding the transform" 对位）。
- 【风险/代价】无影响：extra 键对不读取的车道（embedding/图片/MCP）惰性；`SimpleOptions` javadoc 的「`onPayload` 不移植」表行随本批过期，T1 顺手改写。

### 裁决② 桥放 StreamFn 层，不动引擎 HookSystem

- 【场景】`HookSystem` 的 `before_payload`（Map 载荷、零 firing 点）/`after_response`（usage 载荷、轮末发）形状与 pi 四事件全不符；pi 这四件走 Agent 选项→streamFn→车道，不经 agent-loop 钩子（§2.3）。
- 【决定】桥闭包经 extra 注入车道，firing 点全在 ai 车道内；旧引擎钩维持现状（不 firing、不删除）。引擎钩子数 15 不变。
- 【风险/代价】[内部]：两套「provider 请求」概念并存（legacy 钩名 vs 新事件线）——javadoc 互相指引防误接。

### 裁决③ bus/ctx 晚绑定 ref（pi `extensionRunnerRef` 对位）

- 【场景】StreamFn 在 `wireSession:422` 创建，bus 在 `loadExtensions` 才建（fresh per 会话/fork）——pi 用 `{ current?: ExtensionRunner }` 回填解同一时序。
- 【决定】`ProviderEventBridge` 持 `AtomicReference<ExtensionEventBus>`，`loadExtensions` 回填；未安装（含 `--no-extensions`）⇒ 全钩空跳（pi 空 runner/空表同效）。`hasHandlers` 门**调用时现查**（pi 逐字），装配后运行期订阅同生效。
- 【风险/代价】无影响：初始请求必晚于 loadExtensions；fork 新桥新装（pi createRuntime 对位）。

### 裁决④ `onResponse` 覆盖：SDK 四车道 `withRawResponse`＋自备两车道外露；google 照缺

- 【场景】SDK `StreamResponse`/genai `ResponseStream` 成功流无 status/headers；自备 HTTP 的 status/headers 在传输内部。pi 的对应面：SDK 车道经 `.asResponse()`、fetch 车道天然有、**google 两车道 pi 也没发**。
- 【决定】anthropic/openai-comp/openai-resp/azure 改 `withRawResponse()…createStreaming`（javap 已核实 2.66.0/4.42.0 均有）；mistral/pi-messages 经 `SseResponseObserver` 在终态响应点 fire（含错误状态，pi fetch 车道 :404 同形）；google **不发**（pi 同缺＝对齐）。
- 【风险/代价】[内部]：SDK 改签碰请求获取主路径——既有 wire 夹具全量回归兜住；`HttpResponseFor.parse()` 的关闭语义以夹具验证。

### 裁决⑤ payload 载体＝车道原生对象；类型不符 ⇒ 请求失败

- 【场景】pi 的 `payload: unknown` 是车道原生 params（TS 结构类型，替换靠展开）；java 车道 params 是 SDK 类/Map/ObjectNode，替换必须同型。
- 【决定】事件载荷＝车道原生对象；google 新 `GooglePayload(model, contents, config)` record；车道对替换结果做单点 `instanceof` 守卫，**不符 ⇒ 抛 `IllegalStateException` 请求失败**。
- 【风险/代价】[内部，边缘]（D31）：pi 会把垃圾形状发给 provider 收 400——同属「扩展弄坏请求 ⇒ 请求失败」，java 失败点提前且信息更清楚。

### 裁决⑥ headers 事件＝可变 map 原位改＋null＝删除（合并集内）

- 【场景】pi 处理器原位改 `ProviderHeaders`（`Record<string,string|null>`），null 值删除该头（含压 provider 默认头）。
- 【决定】事件给可变 `LinkedHashMap`（插入序＝合并序）；派发后桥内删 null 值条目写回 `extra["headers"]`。删除语义只及**合并集内**（models.json 三层）；SDK 默认头（SDK 自带 User-Agent 等）不在集内、压不到（D34）。mistral 车道自带链本已实现 null＝删除（`MistralHeaders:42`），同义。
- 【风险/代价】[内部，边缘]：合并集形状差（D33：pi 另有 auth.headers/调用方显式头/attribution 三层——前两者 java 无生产者，后者新登记 B253）。

### 裁决⑦ Azure/PiMessages 补 `extra["headers"]` 应用（同批同修）

- 【场景】这两车道从不读 extra["headers"]（§2.4）——不补则头事件对它们是空操作，models.json 头也对它们无效（pi :156-169/:394-399 都应用）。
- 【决定】T2 内补挂（Azure 构造期 `putExtraHeaders`；PiMessages 手写头打底＋extra 覆盖/null 删除）。
- 【风险/代价】[用户可见，边缘]：这两条车道开始真的发送 models.json 配置的头——这正是 pi 既有行为，属修真不是偏差。

---

## §5 窄差表（本批新增，接续批 6 的 D24–D30）

| # | 内容 | 等级 |
|---|---|---|
| D31 | 替换 payload 类型不符 ⇒ 车道抛 `IllegalStateException`（请求失败）；pi 会把垃圾形状发给 provider 收 400——同属失败、失败点提前（裁决⑤） | [内部，边缘] |
| D32 | google 车道 payload 载体是 `GooglePayload(model, contents, config)` record（pi 是单一 `GenerateContentParameters` 对象；java SDK 三分参调用所致） | [内部] |
| D33 | `before_provider_headers` 事件头集＝models.json 三层（provider⨁model⨁override）合并值；pi 另有 `auth.headers` 与调用方显式 `options.headers` 两层（java 无生产者）及 attribution 层（**B253** 新登记） | [内部] |
| D34 | null 抑制 **SDK 默认头**不可达（通道只作用于 extra["headers"] 合并集；pi 的 null 还能压 provider 默认头如 User-Agent）；合并集内 null＝删除全落 | [内部，边缘] |
| D35 | 响应头摊平口径：名按 SDK/JDK 原样大小写（pi fetch `Headers.entries()` 小写化），同名多值逗号连接（≈fetch 行为） | [内部] |

**说明（非窄差）**：① google 车道不发 `onResponse`＝pi 同缺（§3.5）；② SDK 车道 `onResponse` 只在成功获取后发、fetch 车道含错误状态——pi 自身两族口径即如此，照抄（§1.4）。
**销号**：**B248**（D-17/18/19/20 四行翻转）。**新登记**：**B253**（attribution 头层：openrouter `HTTP-Referer`/`X-OpenRouter-*`、nvidia、cloudflare User-Agent、opencode 会话头——`provider-attribution.ts:33-99` 整体未移植，java 全仓零命中；归属待定点〔pi 在 coding-agent 包、java 落点在 ai provider〕）。

---

## §6 测试计划

### ai 模块（T1/T2，复用 `RecordingHttpServer` 既有 wire 观测面）

| 夹具 | 用例 |
|---|---|
| `AnthropicProviderHooksWireTest`（T1） | ① 注入 PayloadHook 替换 params（`toBuilder().maxTokens(7).build()`）⇒ 服务器收到的 body `max_tokens=7`；② 类型不符（返回 String）⇒ 请求以 `IllegalStateException` 失败；③ ResponseHook 收到 `200`＋响应头含桩服务器自定头；④ 4xx 耗尽重试 ⇒ ResponseHook **不发**；⑤ StreamEventHook 收到 `RawMessageStreamEvent` 序列且首条先于任何 `StreamEvent`（归一化前门）；⑥ 无钩 ⇒ 零 firing（现有夹具即回归） |
| `OpenAiProviderHooksWireTest`（T1，completions＋responses 两车道） | ① 两车道 payload 替换落线；② StreamEventHook 原始类型（`ChatCompletionChunk`／`ResponseStreamEvent`）；③ responses 车道 onResponse 状态/头 |
| `MistralProviderHooksWireTest`（T1＋T2） | ① PayloadHook 收到 `Map`、替换键落线（服务器验 body）；② ResponseHook 收到终态 status/headers；③ **错误状态臂**：桩回 400（重试关）⇒ ResponseHook 仍发（pi fetch 车道同形）；④ StreamEventHook 收到解析后 Map、先于归一化 |
| `PiMessagesProviderHooksWireTest`（T1＋T2） | ① `ObjectNode` 替换落线；② ResponseHook 终态发；③ `extra["headers"]` 合并落线（§3.8：自定义头到服务器、null 删除手写默认头） |
| `GoogleProviderHooksWireTest`（T1） | ① PayloadHook 收到 `GooglePayload`、替换（改 config）落线；② StreamEventHook 收到 `GenerateContentResponse`；③ **钉**：ResponseHook 注入也不发（pi 同缺回归钉） |
| `AzureProviderHooksWireTest`（T1＋T2） | ① `extra["headers"]` 应用落线（§3.7）；② payload 替换落线 |

### coding-agent 模块（T3，复用批 4/5 扩展注入缝）

| 夹具 | 用例 |
|---|---|
| `ProviderEventBridgeTest` | ① `hasHandlers` 门：无订阅 ⇒ 四方法全空走（payload 原样/headers 原样/零事件）；② payload 替换链：两处理器，后处理器见到前者的替换值（pi runner.ts:1365-1378 序）；③ 异常隔离：抛错处理器 `emitError` 一次（`event="before_provider_request"`）且后续处理器照常（pi :1380-1387）；④ headers 原位改＋null 删除：新增头保留、null 值条目派发后消失、返回值被忽略；⑤ `after_provider_response`/`provider_stream_event` 五键载荷逐字段；⑥ 未 install ⇒ 全跳 |
| `DefaultProvidersProviderHooksTest` | ① 桥在场 ⇒ `extra["headers"]` 合并集先过桥再下车道（扩展新头出现在 lane 收到的 options 里）；② **`transformHeaders` 键不转发**（pi 测试对位：createApi 收到的 extra 无该键——本设计本就不设键，钉防回潮）；③ 三钩键入 extra 且类型正确；④ 桥 null（旧签名）⇒ extra 零钩键（存量行为不变） |
| `ProviderHooksSessionWiringTest` | ① 测试扩展 `on(BEFORE_PROVIDER_REQUEST)` 订阅＋FauxProvider 车道消费钩 ⇒ 会话 prompt 后扩展看到事件（端到端）；② `--no-extensions` ⇒ 零 firing；③ fork 后新会话的扩展订阅独立生效（批 6 装配缝复用） |

回归：ai 全量＋coding-agent 全量（`-am` 连带 agent-core——批 4 教训）。

---

## §7 验证口径

1. `mvn test -pl pi-java-ai -am`（T1/T2 新夹具＋既有全绿——SDK 改签是主路径，全量跑）。
2. `mvn test -pl pi-java-coding-agent -am`（T3 新夹具＋既有 770 全绿）。
3. `mvn test -pl pi-java-tui,pi-java-web -am`（连带回归）。
4. 收口 **全量 `mvn clean verify`**（spotbugs 在 verify 阶段——agent-core 批 4 教训；`RichSessionListTest` 存量红按批 5/6 口径排除：`-Dtest='!RichSessionListTest'`＋`-Dsurefire.failIfNoSpecifiedTests=false`；verify 日志不放 target/）。
5. `mvn checkstyle:check`；无 `System.out.println` 残留；新文件 ≤500 行；`AgentSession.java` 现 1,925 ⇒ 本批 ≤ 1,950（E1 在册不扩大）；`PiHttpClient.java` 现 349 ⇒ ≤ 420（超限则抽 `SseResponseObserver` 独立文件）。
6. commit 粒度：T1（ai 缝＋七车道三钩＋SDK onResponse）→ T2（自备 HTTP 面＋头通道补）→ T3（扩展桥＋接线）→ docs 回填（本稿状态行＋map/04 D-17/18/19/20＋窄差表＋docs/04 销号/新登记＋docs/05 数字）。

---

## §8 对账表

| 项 | 基线 | 闭环后 | Δ |
|---|---|---|---|
| map/04 D-17 `before_provider_request`（w3） | 存疑（引擎钩子未桥接） | 对齐（批 7：ai 缝＋桥，docs/17） | +1.5 |
| map/04 D-18 `before_provider_headers`（w2） | 存疑（只读、未桥接） | 对齐（批 7：缝消费＋原位改＋null 删除） | +1.0 |
| map/04 D-19 `after_provider_response`（w2） | 存疑（未桥接） | 对齐（批 7：六车道〔google 同 pi 缺〕） | +1.0 |
| map/04 D-20 `provider_stream_event`（w2） | 缺失 | 对齐（批 7：七车道迭代点） | +2.0 |
| 台账 B248 | 开放 | **销号** | −1 开放项 |
| 台账 B253（attribution 头层） | — | **新登记** | +1 开放项 |
| D 域加权 | 66.0/83＝79.5% | 71.5/83＝**86.1%** | +5.5 |
| 模块加权 | 283.5/496≈57.2% | 289.0/496≈**58.3%** | +5.5 |
| 全仓加权 | 948.5/1381≈68.7% | 954.0/1381≈**69.1%** | +5.5 |
| w3 层（map/04 权重 3 表） | #5 存疑 | #5 对齐（D 域 w3 欠账清零） | +1.5 |
| 窄差 | D9–D30 | D9–D35（+D31–D35） | +5 |
| 引擎钩子数 | 15 | 15 | 0（本批不走引擎钩，裁决②） |
