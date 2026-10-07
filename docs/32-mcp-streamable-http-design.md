# 包 32：MCP 对齐（B160）——第 5 包：streamable-http transport

> **状态：📝 待审核（2026-10-07，R-A）。**
> 总包路线见 `docs/28` §3；本文是第 5 包（pi
> `transports/streamable-http.ts`，500 行）详细设计。审核通过后才许写代码。

## 1. 文件清单（`com.pijava.mcp.transport.http`，主源）

| 新文件 | 对齐 |
|---|---|
| `StreamableHttpTransportOptions.java` | :114-123 |
| `StreamableHttpTransport.java` | :126-406（生命周期/POST） |
| `HttpRequests.java` | :242-279（POST/GET 构造） |
| `SseStreamConsumer.java` | :20-104（SSE 消费器） |

## 2. Options（:114-123）

```java
public record StreamableHttpTransportOptions(
        String url,
        @Nullable Map<String,String> headers,
        @Nullable McpTransportFetch fetch,     // 注入式 HTTP 执行（默认 JDK）
        @Nullable AuthProvider authProvider,   // 401 处理（第 6/7 包接线；本包先留接口）
        long reconnectTimeoutMs,               // GET 流重连；<=0 默认 30s
        long requestTimeoutMs) {              // 单次 POST；<=0 默认 30s
}
```

`Fetch`/`AuthProvider`：本包内定义最小 shape 接口（`Fetch` 用 JDK HttpClient
适配为默认）；真正 OAuth 实现在包⑥⑦，经接口注入，避免本包提前依赖。

## 3. HTTP 执行地基

用 JDK `java.net.http.HttpClient`（模块内单例，重定向 NORMAL）。不复用
`PiHttpClient`——那是「String body＋整 SSE 缓冲」模型，MCP 要**响应体流式
SSE 逐事件**与 GET 流，形状不同。

```java
// McpHttpFetch.java（接口）
interface McpHttpFetch {
    /** Execute one request; response body is streamed to the consumer. */
    McpHttpResponse execute(McpHttpRequest request,
                           SseEventConsumer eventConsumer) throws Exception;
}
```

`McpHttpRequest`：method、url、headers（Map）、@Nullable body bytes；
`McpHttpResponse`：status、headers、`@Nullable String contentType`。

默认实现 `JdkMcpHttpFetch`：
- POST：`BodyPublishers.ofByteArray`；`Accept: application/json, text/event-stream`；
- 请求头叠加 options.headers；session id（§5）；
- response：`BodyHandlersors.ofInputStream`——先读 status/headers/contentType：
  - 200 + `text/event-stream`：体流交给 `SseStreamConsumer.consume`；
  - 200 + json：体读完即单条 JSON 消息（emitMessage 一次）；
  - 202：无 body，无消息；
  - 401/403：交 auth 链（§6）；
  - 其余非 2xx：`McpHttpError`（status＋body 片段）。

## 4. SSE 消费器（:20-104）——`SseStreamConsumer`

pi 的 `consumeSseStream` 是手写行解析（不假设块边界；event 名/data 多行
`\n` join；空行才派发；`id:`/`event:`/`data:`/`retry:`；冒号注释行忽略；
行内可有无空格）。

```java
static void consume(InputStream body, SseEventHandler handler) throws Exception
```

- handler：`onEvent(SseEvent event)`（id/event/data）、可选 `onRetry(long ms)`、
  `onError(Throwable)`、`onId(String id)`（GET 流重连用）；
- `data` 多行按 `\n` 连接（:53）；
- 派发默认 event 名 `"message"`；
- 结束（EOF）正常返回——**GET 流**由调用方决定重连，**响应流** EOF 非答案
  即请求失败（§5.2）。

## 5. `StreamableHttpTransport`

### 5.1 字段/状态（:126-145）

```java
public final class StreamableHttpTransport extends AbstractMcpTransport {
    private final StreamableHttpTransportOptions options;
    private final Object lock = new Object();
    private volatile boolean initialized;
    private volatile boolean closed;
    private @Nullable String sessionId;
    private @Nullable String getStreamId;      // GET 流标识（重连匹配）
```

### 5.2 send（:223-353）

POST 一条 wire 消息（:224-230）：
- headers：`Content-Type: application/json`、Accept；options.headers 合并；
- 已建会话时 `Mcp-Session-Id`（:241）；
- 体＝消息 JSON bytes；
- 响应处理（:281-336）：
  - **SSE 响应**：consume 期间 `event.event=="message"` ⇒ parse `event.data`
    → emitMessage；一个 POST 可回多条；
  - **JSON 响应**：单条 emit；
  - **202**：无消息返回；
  - 服务器 200 但既非 json 也非 sse ⇒ McpHttpError；
  - 请求被 202/无响应（:280）⇒ McpError("server accepts without response")。

### 5.3 initialize 后的 GET 流（:338-361）

`notifications/initialized` 之后，若 initialize 结果未在 POST 流里携带
（判断：POST 响应是否已含 SSE 流——pi 用 initialize 是否 SSE 回答区分）：
- 开 GET 请求（Accept: text/event-stream；session id）；
- 成功后 `Last-Event-ID` 仅在**断点续传**时发，首次不发（:385-389）；
- GET 流上 message 事件正常 emit；`id:` 更新 lastEventId；
- EOF/网络断：按 `reconnectTimeoutMs` 重连（指数？pi 用固定限期＋循环），
  重连带 Last-Event-ID；重连仅在**未 close**时进行。

### 5.4 响应流恢复（:362-389）

POST 的 SSE 响应若在**答案帧之前**被服务器关闭：重新 POST（同一请求体、
同一请求语义），直到拿到答案帧（`id` 匹配该请求）；已观察到的事件不重复
（按 event id 去重——pi 靠 lastEventId）。答案帧之后流结束＝正常完成。

### 5.5 close（:392-406）

closed=true；中止在飞 GET 重连循环（AbortSignal）；无持久连接需关（HTTP 请求
短生命周期）；emitClose 一次。

## 6. 测试计划（L5）

移植 pi `test/streamable-http.test.ts` 11 条款（406 行）为
`StreamableHttpTransportTest`；夹具＝**脚本化 loopback HTTP server**（JDK
com.sun.net.httpserver.HttpServer，同包③思路：可按脚本应答 JSON/SSE/202/401、
可控关闭响应体）：

1. JSON 与 SSE 响应＋session/protocol 头（:124）
2. 401 归类（:143）
3. 只失败 SSE 中断的那个请求（:158）
4. initialize 后开 GET 流、Last-Event-ID 仅恢复时发（:188）
5. 响应流提前关闭则恢复（:208）
6. 无答案帧的响应流结束＝失败（:236）
7. GET 流断开重连（:252）
8. 202/无响应 ⇒ 错误（:280）
9. HTTP 错误含响应体（:294）
10. 401＋insufficient-scope 403 交 auth（:306）
11. fetch 不以调用者 receiver 执行（:346）

`SseStreamConsumer` 直接单测 ×2（:92,106）：CRLF 事件/注释/id/多行 data；
无空行长 data 超限报错。

## 7. 变异探针

| # | 变异 | 预期红 |
|---|---|---|
| M1 | GET 流首次也发 Last-Event-ID | 条款 4 红 |
| M2 | 响应流 EOF 不恢复 | 条款 5 红 |
| M3 | 202 当成功（不报错） | 条款 8 红 |
| M4 | SSE 多行 data 用最后行而非 `\n` join | consumer 多行用例红 |
| M5 | session id 不回传（POST 二跳无头） | 条款 1 session 头红 |
| M6 | GET 重连在 closed 后仍循环 | close 条款红（closed 后无线程存活） |

## 8. 台账影响

- B160 不销号；B177 维持。闭环回填 docs/28 banner 与本文。
