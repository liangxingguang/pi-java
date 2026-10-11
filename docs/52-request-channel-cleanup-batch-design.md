# docs/52 设计稿：请求通道收尾包（B103 ＋ B107 ＋ B113 ＋ B229）

> 状态：**待审核**（设计稿落地 → 用户审核 → 才写代码）
> 锚点：`ea448f454`（v1.1.0-22，2026-10-11 换锚）。pi 证据全部取自 `git show ea448f454:<path>`；B229 另引 `6b07b4e57` 的 diff。
> 上一包：docs/51（B98 compat 八字段，已闭环）。

## 1. 范围

四条同在「ai 请求管线」一面的窄角，合成一包：

| # | 内容 | 性质 |
|---|---|---|
| **B103** | 会话亲和头补齐 anthropic / completions 两车道（responses 已落）；顺带修掉一个 B156 型事故：显式 compat 值被 `CompatResolver.resolved()` 静默丢成 null | feat + fix |
| **B107** | `StreamRequest.extra` 死通道删除（生产恒 `Map.of()`、零消费者；per-request 选项全部走 `ApiOptions.extra`，适配器每请求新建） | refactor（机械改签 ~103 处 / 75 文件） |
| **B113** | 删 `AbstractChatApi.send()` 不可达的 `catch → PiHttpException("Streaming failed")` | fix（死代码） |
| **B229** | Mistral 60s（生产 300s）超时改为**只盖等响应头**；body 停顿不再被固定截止时间切断 | feat（SSE 传输改造） |

**翻案一条旧结论**：B140（「官方 Java SDK streaming transport 不透传任意头」）—— javap 实证两个 SDK 的 params builder 都有
`putAdditionalHeader(String,String)`（openai-java 4.42.0 `ChatCompletionCreateParams$Builder`、
anthropic-java 2.52.0 `MessageCreateParams$Builder`），且 responses 车道早已经此路径上线
（`ResponsesMessageConverter.java:193` `affinityHeaders.forEach(builder::putAdditionalHeader)`）。
B140 在 docs/41:223 的「1/3 车道」结论随本包作废，相关注释四处一并更正。

不在本包：B125 的 samplingParams per-request 半（通道结论在 §4.2 更正，但不造生产者）、
`ApiOptions.timeout()` 全车道零消费（预先存在，B229 只做 Mistral 传输，见 §5 偏差 4）、B148。

## 2. pi 证据（逐字）

### 2.1 B103：三车道三种形状，两个 cacheSessionId 门

**completions**（`packages/ai/src/api/openai-completions.ts:348` 门，`:776-786` 头）：

```ts
const cacheSessionId = cacheRetention === "none" ? undefined : options?.sessionId;   // :348
// createClient(... cacheSessionId) :349-357
if (sessionId && compat.sendSessionAffinityHeaders) {                                  // :776
    if (compat.sessionAffinityFormat === "openrouter") {
        headers["x-session-id"] = sessionId;                                           // :778
    } else {
        if (compat.sessionAffinityFormat === "openai") {
            headers.session_id = sessionId;                                           // :781
        }
        headers["x-client-request-id"] = sessionId;                                   // :783
        headers["x-session-affinity"] = sessionId;                                     // :784
    }
}
```

探测缺省（`:1680-1681`，resolved 于 `:1729-1730` 为 `model.compat.X ?? detected.X`）：

```ts
sendSessionAffinityHeaders: isOpenRouter,
sessionAffinityFormat: isOpenRouter ? "openrouter" : "openai",
```

**responses**（`openai-responses.ts:159` 门，`:277-286` 头）——**无 send 开关**、**不发** `x-session-affinity`：

```ts
const cacheSessionId = cacheRetention === "none" ? undefined : options?.sessionId;   // :159
if (sessionId) {                                                                      // :277
    if (compat.sessionAffinityFormat === "openrouter") {
        headers["x-session-id"] = sessionId;                                          // :279
    } else {
        if (compat.sessionAffinityFormat === "openai") {
            headers.session_id = sessionId;                                           // :282
        }
        headers["x-client-request-id"] = sessionId;                                   // :284
    }
}
```

**anthropic**（`anthropic-messages.ts:625` 门，`:1037-1050` 头）——单头，format 只有 openrouter/默认之别：

```ts
const cacheSessionId = cacheRetention === "none" ? undefined : options?.sessionId;   // :625
const sessionAffinityHeaders: ProviderHeaders = {};
if (sessionId && compat.sendSessionAffinityHeaders) {                                 // :1039
    const header = compat.sessionAffinityFormat === "openrouter" ? "x-session-id" : "x-session-affinity";
    sessionAffinityHeaders[header] = sessionId;
}
const defaultHeaders = mergeClientHeaders(
    { accept: "application/json", "anthropic-dangerous-direct-browser-access": "true" },
    sessionAffinityHeaders,                                                           // 仅默认分支
    model.headers, optionsHeaders,
);
```

缺省（`:215-221`）：`sendSessionAffinityHeaders ?? isOpenRouter`；
`sessionAffinityFormat ?? (isOpenRouter ? "openrouter" : undefined)`。

**OAuth/copilot 例外**（`:992-1031`）：两个 Bearer 分支的 `mergeClientHeaders(...)` 参数表里
**没有** `sessionAffinityHeaders` —— 亲和头只在 API key/自有头/联邦分支发出。

### 2.2 B229：Mistral 超时只盖响应头（commit `6b07b4e57`，fixes #10609）

```ts
// The timeout covers only the wait for response headers. Long streams (e.g. extended thinking)
// must not be cut off by a fixed deadline; body stalls are left to the HTTP client idle timeout.
const timeoutMs = options?.timeoutMs ?? 60_000;
const headerTimeoutSignal = AbortSignal.timeout(timeoutMs);
const combinedSignal = combineAbortSignals([options?.signal, headerTimeoutSignal]);
try {
    response = await (options?.fetch ?? globalThis.fetch)(url, { ..., signal: combinedSignal.signal });
} catch (error) {
    if (headerTimeoutSignal.aborted && !options?.signal?.aborted) {
        throw new Error(`Mistral response headers timed out after ${timeoutMs}ms`);
    }
    throw error;
} finally { combinedSignal.cleanup(); }
return readMistralEvents(response.body, options?.signal);   // body 只剩调用方 signal
```

生产超时值链（`coding-agent/src/core/sdk.ts:350`）：
`options.timeoutMs ?? providerRetrySettings.timeoutMs ?? effectiveTimeoutMs`，
`effectiveTimeoutMs = httpIdleTimeoutMs===0 ? MAX : httpIdleTimeoutMs`；
默认 `DEFAULT_HTTP_IDLE_TIMEOUT_MS = 300_000`（`core/http-dispatcher.ts:4`）。
⇒ `bin pi` 生产恒为 300s；ai 包 standalone 的 60s 默认 R5 不可达。

### 2.3 B113 / B107

- pi 的错误**全部落流**（`AssistantMessageEventStream` 对 done/error 都 resolve），`complete()` 不把流内错误包成异常。
- pi 公开入口只有**一个** per-call 选项对象（`stream(model, context, options)`）；java 把它劈成
  `ApiOptions`（适配器构造期，生产每请求新建）与 `StreamRequest`（呼叫数据）两条 extra 通道，后者无生产者无消费者。

## 3. Java 现状

| 事实 | 位置 |
|---|---|
| responses 头发射已落（params 级） | `OpenAIResponsesApi.java:82-83`、`ResponsesMessageConverter.java:193` |
| completions 有 sessionId、有 `prompt_cache_key`，头**故意不发**（旧 B140 结论） | `OpenAICompletionsApi.java:123-125,194`、`OpenAICompletionsMessageConverter.java:298-308` |
| anthropic 完全不读 sessionId；`buildParams(request, cacheSpec)` 两参 | `AnthropicMessagesApi.java:103,165,449-455`、`AnthropicRequestBuilder.java:58-62` |
| **显式亲和 compat 被两处硬编码 null 丢掉** | `CompatResolver.java:406`（resolved：`null, null`）、`:195`（withCompletions：`null, null`）；models.json 解析/合并本身正确（`ModelsJsonCompat.java:115-117,205-209`） |
| anthropic 解析无 baseUrl（无法探测 openrouter） | `CompatResolver.forAnthropic(ModelInfo)` `:217` |
| 反向断言「头不出现」的旧夹具 | `SessionAffinityWireTest.java:91-117`（用户旧裁决方案 2，本包翻案） |
| StreamRequest.extra 恒空生产点 | `DefaultProviders.java:281` `java.util.Map.of()`；全树消费点 **0**（grep `request.extra()` 仅 `SimpleOptionsTest.java:167` 一条拷贝断言） |
| send() 死 catch | `AbstractChatApi.java:163-181`；`QueueStreamIterator.hasNext/next` 吞 InterruptedException（:40-43/:55-58），不抛；`StreamIterator.close()` 无 checked 异常 |
| Mistral 走 PiHttpClient，300s 盖头+体；响应整体缓冲后才回放 | `PiHttpClient.java:87` `.timeout(300s)`、`:132` `BodyHandlers.ofString()`；`MistralConversationsApi.java:110-111` |
| postSse 另一消费者 pi-messages（保持现状） | `PiMessagesApi.java:87` |

**JDK 25 spike 实证**（GraalVM 25 实跑，2026-10-11）：`HttpClient.sendAsync(req, BodyHandlers.ofInputStream())`
返回的 future 在 400ms 后 `cancel(true)`：① future 411ms 以 `ExecutionException(CancellationException: Request cancelled)`
完成；② 服务端 `readAllBytes()` 立即收到 EOF —— **取消会真的中止底层 exchange**。
且 `ofInputStream` 的 future 在**响应头到达时**即完成（body 尚未读），正是 fetch-headers 边界。方案成立。

## 4. 设计

### 4.1 B103：两车道接线 + resolver 丢字段修复

#### 4.1.1 `CompatResolver`

1. `resolved()` 末段（`:405-406`）`null, null` 改为
   `c.sendSessionAffinityHeaders(), c.sessionAffinityFormat()` —— 显式值（内置目录/models.json）
   在四条车道都不再被丢。
2. `withCompletions(...)` 增两个形参 `Boolean detectedSend, SessionAffinityFormat detectedFormat`，
   重建处（`:195`）改 `pick(c.sendSessionAffinityHeaders(), detectedSend)` 与
   `c.sessionAffinityFormat() != null ? c.sessionAffinityFormat() : detectedFormat`；
   `forCompletions` 调用点传 `isOpenRouter`（局部变量已有，`:68`）与
   `isOpenRouter ? SessionAffinityFormat.OPENROUTER : SessionAffinityFormat.OPENAI`（pi `:1680-1681`）。
3. 新增 `forAnthropic(ModelInfo model, String baseUrl)`；旧一参重载委托 `(model, null)`
   （`anthropicCacheControl` 等不读这两个组件的调用点零改签）。方法体内：
   ```java
   boolean isOpenRouter = SessionAffinityHeaders.isOpenRouter(provider(model), baseUrl);
   // 重建的两个亲和位：
   pick(partial.sendSessionAffinityHeaders(), isOpenRouter),
   partial.sessionAffinityFormat() != null ? partial.sessionAffinityFormat()
       : (isOpenRouter ? SessionAffinityFormat.OPENROUTER : null);
   ```
   （pi `:220-221`。）
4. 类 javadoc「java 都不携带 ⇒ 本方法只做…」（`:209-215`）与 `:405` 行内注释更正。

#### 4.1.2 `SessionAffinityHeaders`（纯函数）

新增两个静态方法（`responses` 已在，不动；新增常量 `X_SESSION_AFFINITY = "x-session-affinity"`）：

```java
/** completions 车道（有 send 门；openai 形三头，openai-nosession 两头，openrouter 一头）。 */
static Map<String,String> completions(String sessionId, boolean send,
                                      SessionAffinityFormat format, String provider, String baseUrl) {
    if (!send || sessionId == null || sessionId.isBlank()) return Map.of();
    SessionAffinityFormat eff = format != null
        ? format : (isOpenRouter(provider, baseUrl) ? OPENROUTER : OPENAI);
    var h = new LinkedHashMap<String,String>();
    switch (eff) {
        case OPENROUTER -> h.put(X_SESSION_ID, sessionId);
        case OPENAI -> { h.put(SESSION_ID, sessionId);
                         h.put(X_CLIENT_REQUEST_ID, sessionId);
                         h.put(X_SESSION_AFFINITY, sessionId); }
        case OPENAI_NOSESSION -> { h.put(X_CLIENT_REQUEST_ID, sessionId);
                                   h.put(X_SESSION_AFFINITY, sessionId); }
    }
    return h;
}

/** anthropic 车道（单头；非 openrouter 一律 x-session-affinity，含显式 openai 形）。 */
static Map<String,String> anthropic(String sessionId, boolean send,
                                    SessionAffinityFormat format, String provider, String baseUrl) {
    if (!send || sessionId == null || sessionId.isBlank()) return Map.of();
    boolean openRouter = format == SessionAffinityFormat.OPENROUTER
        || (format == null && isOpenRouter(provider, baseUrl));
    return Map.of(openRouter ? X_SESSION_ID : X_SESSION_AFFINITY, sessionId);
}
```

`send` 由调用方按解析后 compat 给（`Boolean.TRUE.equals(...)`）；入参 sessionId 已是
**过了 cacheRetention none 门**的值。

#### 4.1.3 completions 车道

`OpenAICompletionsMessageConverter.buildParams(..., resolvedRetention, rawSessionId)` 内
（`:300-308` 块重写）：

```java
String cacheSessionId = cacheRetention == CacheRetention.NONE ? null : rawSessionId;  // pi :348
if (cacheSessionId != null && !cacheSessionId.isBlank()) {
    // 头（B103）：params 级 additionalHeaders 经 streaming 透传（javap + responses 实证，B140 作废）。
    SessionAffinityHeaders.completions(cacheSessionId,
            Boolean.TRUE.equals(compat.sendSessionAffinityHeaders()),
            compat.sessionAffinityFormat(), request.modelId().provider(), baseUrl)
        .forEach(builder::putAdditionalHeader);
    // prompt_cache_key 门不变（officialOpenAi / LONG 两支，:302-307）
}
```

`OpenAICompletionsApi.java:123-124` 注释更正（不再提 B140 不可达）。

#### 4.1.4 anthropic 车道

- `AnthropicMessagesApi` 构造期增存：`private final String baseUrl`（effective，同 completions）、
  `private final String sessionId`（`sessionIdOf(options)`，helper 照抄 completions 那份）、
  `private final boolean bearer`（现有局部提升为字段 —— OAuth/copilot 例外用）。
- `streamInternal` 调 `buildParams` 处改为四参：
  ```java
  var resolvedRetention = CompatResolver.resolveCacheRetention(cacheRetention,
      System.getenv(CACHE_RETENTION_ENV));                       // 与 anthropicCacheControl 同源
  var cacheSessionId = resolvedRetention == CacheRetention.NONE ? null : sessionId;  // pi :625
  AnthropicRequestBuilder.buildParams(request, cacheSpec,
      bearer ? null : cacheSessionId, baseUrl);                  // pi :992-1031 Bearer 不挂
  ```
  （`buildParams` 的 cacheSpec 仍由 `CompatResolver.anthropicCacheControl` 现算；多解析一次
  retention 与 completions 车道同形。）
- `AnthropicRequestBuilder`：`buildParams(request, cacheSpec)` 保留（委托 `..., null, null`，
  九处直调夹具零改签）；新四参把内部 `CompatResolver.forAnthropic(request.model())` 改传 baseUrl，
  `return` 前：
  ```java
  if (sessionId != null && !sessionId.isBlank()) {
      SessionAffinityHeaders.anthropic(sessionId,
              Boolean.TRUE.equals(compat.sendSessionAffinityHeaders()),
              compat.sessionAffinityFormat(),
              request.modelId().provider(), baseUrl)
          .forEach(builder::putAdditionalHeader);
  }
  ```

#### 4.1.5 responses 车道补 none 门（现有潜在偏差）

pi `:159` 的 cacheSessionId 门 Java 没有（`OpenAIResponsesApi.java:82` 现在 retention=none 仍发头）：

```java
String sid = responsesOptions.cacheRetention() == ResponsesOptions.CacheRetention.NONE
    ? null : responsesOptions.sessionId();
SessionAffinityHeaders.responses(sid, compat.sessionAffinityFormat(),
    request.modelId().provider(), baseUrl)
```

### 4.2 B107：删 `StreamRequest.extra`

按「休眠通道删除」先例（包 H5 删 `extra["thinking.budgetTokens"]`）：

1. `StreamRequest` 删 `extra` 组件：canonical ctor、3 个便捷 ctor、compact ctor 的
   `Map.copyOf(extra)`、`withMaxTokens` 同步。
2. 全树 ~103 个构造点 / 75 文件去参（ai 测试为主；生产点 `DefaultProviders.java:281`；
   下游 `ChatApiConformanceSuite`（evals）1、`CrossLayerLengthGateTest`（agent-core）1）。
3. `SimpleOptions.resolveRequest` 不再透传 extra；删 `SimpleOptionsTest.java:167` 拷贝断言；
   `SimpleOptions.java:24,36-37,143-145` 三处 javadoc 改述：per-request 选项的唯一通道是
   **`ApiOptions.extra`**（适配器在 `DefaultProviders.streamBlocking` 每请求新建）。
4. `DefaultProviders.java:307-308` 的 B107 注释删除。
5. 台账 B125 行更正：`ModelInfo.headers` 现有消费者（`withPerModelOverrides:178`）；
   samplingParams per-request 半仍无生产者，通道结论从「StreamRequest.extra」改为「如需，走
   ApiOptions.extra，与 sessionId/cacheRetention 同形」——本包不造生产者。

零行为变化（无生产者无消费者）；验证口径＝全 reactor 绿。

### 4.3 B113：删 send() 死 catch

`AbstractChatApi.send()`（`:163-181`）：去掉 `catch (Exception e) → PiHttpException(0,"Streaming failed")`
与外层 try（保留 try-with-resources）。管道自身故障时循环自然走完 → 返回 identityBase 基底
（现有尾返回保留）；`streamBlocking`/迭代器自身抛未检查异常时**原样传播**（保留类型身份，
与 pi result() 对意外 reject 同形）。`StreamIterator.close()` 不声明 checked 异常，方法签名不变。
`:159-160` javadoc 的 B113 注记更新。

### 4.4 B229：PiHttpClient 头超时 + 增量 SSE

新增（http 包）：

```java
/** 等响应头超时（pi 6b07b4e57）：只可能在头阶段抛出；body 阶段不再有固定截止时间。 */
public final class HeaderTimeoutException extends java.io.IOException {
    private final Duration timeout;
    HeaderTimeoutException(Duration timeout) {
        super("Response headers timed out after " + timeout.toMillis() + "ms");
        this.timeout = timeout;
    }
    Duration timeout() { return timeout; }
}
```

`PiHttpClient`：

- 静态守护调度器：`ScheduledExecutorService`（daemon，`Thread.ofVirtual` 工厂），
  close() 不强制关（进程级单例，与 HttpClient 同生命周期）。
- 新 6 参 `postSse(url, body, headers, signal, headerTimeout)`：
  - request **不设** `.timeout(...)`；`sendAsync(request, BodyHandlers.ofInputStream())`；
  - 守护：`scheduler.schedule(() -> cf.cancel(true), ms)`；cf 完成（成功/失败）立即取消守护；
  - join：`CancellationException`/超时 ⇒ `throw new HeaderTimeoutException(headerTimeout)`
    （spike 实测取消会中止连接，服务端收 EOF）；
  - 2xx：`new SseIterator(new BufferedReader(new InputStreamReader(in, UTF_8)).lines().iterator())`
    —— **逐行增量**（现状是整 body 缓冲后回放，本改顺带对齐增量流式；读 body 的
    `UncheckedIOException` 从迭代器逸出，Mistral 车道 catch 已能接）；
  - 非 2xx/重试：先把 InputStream 收干成 String，走与现有 `executeWithRetrySse` 相同的
    status/retry/providerErrorMessage 逻辑（抽私有 helper 共用，重试递归带上 headerTimeout）。
- 3/4 参旧签名保留（legacy：`.timeout(300s)` 盖全程 + ofString 缓冲），唯一消费者
  **pi-messages** 与 `PiHttpClientRetryLogTest` 零改签。文件预计 324 → ~440 行，<500；
  若超则抽 `SseTransport`。

`MistralConversationsApi`：

```java
private static final Duration HEADER_TIMEOUT = Duration.ofSeconds(300);  // pi DEFAULT_HTTP_IDLE_TIMEOUT_MS
// streamInternal:
http.postSse(baseUrl + "/chat/completions", jsonBody, headers, null, HEADER_TIMEOUT)
// catch 链（在现有 httpError 判定之前）：
if (e instanceof HeaderTimeoutException hte) {
    publisher.submit(builder.emitError("error", new IllegalStateException(
        "Mistral response headers timed out after " + hte.timeout().toMillis() + "ms", e)));
    return;
}
```

文案逐字 pi `:323`。HeaderTimeoutException 是 IOException ⇒ 经 RetryPolicy 既有 IO 重试面
（默认 maxRetries=0，与 pi mistral 无重试包裹一致）。

## 5. 偏差与残留

1. **body 阶段无 idle 超时**：JDK HttpClient 没有 undici 式 body idle timeout；头之后流可无限挂
   （安全网是宿主 `markAborted`/线程中断；现状 300s 全程截止被移除是本包目的）。B229 闭环时在台账
   留一行残留说明，不新建自造看门狗（无 pi 对应物，R5 不造）。
2. **超时值取 300s 不取 ai 包默认 60s**：生产链（sdk.ts:350 + http-dispatcher.ts:4）恒给 300_000；
   60s 是 standalone 默认，`bin pi` 不可达。
3. **Mistral 顺带变增量流式**（整缓冲→InputStream 逐行）：可观察差异是 TTFB 与 abort 及时性，
   方向就是对齐 pi；事件序列/解析零变化（共用 SseIterator）。
4. **`ApiOptions.timeout()` 仍全车道零消费**（SDK 车道同样不读）：预先存在的缺口，不在四条台账
   任何一条的文本内，本包不扩面（B229 只要求「头后释放」）。
5. **OAuth 分支不发亲和头**照 pi 复刻（bearer 时传 null sessionId）；pi 的 copilot 动态头 Java
   整支未移植，不影响该门。
6. B107 后 B125 的 per-request samplingParams 若未来要做，通道走 `ApiOptions.extra`。

## 6. 红灯清单（先红，预计 ~20 条）

**B103**
1. `SessionAffinityHeadersTest`（新纯函数夹具）：completions 三形 3、门（send false/blank）2、
   anthropic 默认/openrouter/null-format 探测 3、responses 既有形状回归 2 ≈ 10
2. `SessionAffinityWireTest` 翻案改写：anthropic 发 `x-session-affinity`（旧反向断言变红）、
   anthropic bearer 不发、completions openai 三头/nosession 两头/openrouter 一头、
   completions none 全不发、responses none 不发头（新抓偏差）≈ 6
3. `CompatResolverTest`：completions 探测（openrouter provider/url ⇒ send=true、format）、
   anthropic baseUrl 探测、显式 format 经 resolved() 存活（responses 显式 format 当前被丢）≈ 4

**B113**
4. 新 `AbstractChatApiSendTest`：匿名子类覆写 `streamBlocking` 直接抛 ISE ⇒ `send()` 原样抛 ISE
   （当前被包成 PiHttpException，红）

**B229**
5. `PiHttpClientHeaderTimeoutTest`（http 包，自带 stall HttpServer）：
  头永不到 + 300ms ⇒ HeaderTimeoutException 且 ~时序内返回（当前挂死/300s，红）；
  头即到、body 事件延迟 1.5s ⇒ 事件仍取到（legacy 300s 不会红 ⇒ 需脚手架：6 参先委托 legacy，
  该条在真实现后才是回归保护，红灯主要是第①条）
6. Mistral 车道：包私有测试构造 `(ApiOptions, Duration)` 接缝 ⇒  stall 时 StreamError 文案
   `Mistral response headers timed out after 300ms`（红：接缝/异常不存在）

**B107** 无行为红灯（删除型）；编译红即过程，分模块转绿。

## 7. 变异探针（实现后逐条 grep 确认落点、记录红数、还原复绿）

| 探针 | 变异 | 预计命中 |
|---|---|---|
| M1 | completions 亲和门恒关（`send` 改 false） | wire 3 + pure 1 |
| M2 | resolver resolved() 重新硬编码 null,null | 显式存活夹具 + wire 显式 format ≈ 2 |
| M3 | anthropic builder 删 putAdditionalHeader | anthropic wire 正断言 1 |
| M4 | anthropic 去掉 bearer 门 | OAuth 不发夹具 1 |
| M5 | responses none 门短路 | responses none 夹具 1 |
| M6 | B229 头后不清守护（全程截止复活） | slow-body 夹具 1 |
| M7 | B229 不排定 cancel | stall 夹具（超时失败）1 |
| M8 | B113 恢复包装 catch | send 原样传播夹具 1 |
| M9 | completions 三头漏发 x-session-affinity | wire 三头断言 1 |

## 8. 提交序列（每步独立可编译）

1. `docs(ai): docs/52 request channel cleanup batch design`（本稿）
2. `test(ai): red fixtures for B103/B113/B229`（B229 含惰性脚手架：6 参委托 legacy、
   HeaderTimeoutException、车道接缝构造——参照 docs/51 的 inert scaffold 做法）
3. `fix(ai): carry explicit affinity compat and detect lane defaults`（resolver + 纯函数 + 三车道接线，B103）
4. `refactor(ai): drop dead StreamRequest.extra channel`（ai 模块内 B107）
5. `refactor(agent-core,evals): follow StreamRequest extra removal`（下游两测试；若 grep 发现
   coding-agent/tui 构造点则同批）
6. `fix(ai): remove unreachable catch in AbstractChatApi.send`（B113）
7. `feat(ai): apply mistral timeout to response headers only`（B229）
8. `docs(04,41,52,map): close request channel cleanup batch`

行数预算：生产 ~450（PiHttpClient ~+120、resolver/接线 ~120、StreamRequest 删除净 −40、
其余注释/台账），测试 ~450（含 ~75 文件机械改签，但多数单行）。

## 9. 验证口径

- `JAVA_HOME='D:\soft\jdk\graalvm-jdk-25' mvn clean test -pl pi-java-ai`（计数只取模块汇总行）
- 下游 `-am`：agent-core、coding-agent、tui、evals 四 reactor（B107 机械改签必须带）
- `mvn checkstyle:check`；触碰文件零新增 WARN；无 `System.out.println`；文件 ≤500 行
- 旧 `SessionAffinityWireTest` 两条反向断言翻案后，全树 grep 确认无「B140/不可达」残留注释
  （`SessionAffinityHeaders` javadoc、`OpenAICompletionsApi:123`、converter:301、
  `OpenRouterModels.java:42-44`、`CompatResolver:209-215`）
- 台账：B103/B107/B113 销号；B229 销号并留 body-idle 残留注记；B125 行更正；B140 在 docs/41
  翻案；§1 计数与 docs/41 §4/§7、map/02 同步
