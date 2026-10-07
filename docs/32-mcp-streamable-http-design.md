# 包 32：MCP 对齐（B160）——第 5 包：streamable-http transport

> **状态：✅ 已闭环（2026-10-07，R-A）。**
> 总包路线见 `docs/28` §3；本文是第 5 包（pi
> `transports/streamable-http.ts`，500 行）设计。实施记录见 §11。

## 1. 文件清单（`com.pijava.mcp.transport.http`，主源）

| 新文件 | 对齐 |
|---|---|
| `StreamableHttpTransportOptions.java` | :114-123 |
| `ReconnectOptions.java` | :105-113 |
| `McpHttpErrors.java`（3 异常） | :126-151 |
| `McpHttpFetch/Request/Response.java` | fetch shape（:158,169） |
| `JdkMcpHttpFetch.java` | 默认 JDK fetch |
| `SseStreamConsumer.java` | :30-98（真实 `consumeSseStream`） |
| `AuthProvider.java`（最小接口） | auth-provider.ts（包⑥⑦实现） |
| `StreamableHttpTransport.java` | :183-274（send/close/check/capture） |
| `StreamableHttpStreams.java` | :353-500（响应流恢复＋GET 流） |

## 2. Options（:105-123）

```java
public record ReconnectOptions(long initialDelayMs,   // 默认 1_000
                               long maxDelayMs,        // 默认 30_000
                               int maxRetries) {      // 默认 5
}

public record StreamableHttpTransportOptions(
        String url,
        @Nullable Map<String,String> headers,   // 构造时浅拷贝
        @Nullable McpHttpFetch fetch,          // 默认 JDK
        boolean openGetStream,                 // 默认 true
        int maxMessageBytes,                   // <=0 ⇒ 16 MiB
        @Nullable AuthProvider authProvider,
        @Nullable ReconnectOptions reconnect) {
}
```

`AuthProvider` 最小接口（本包定义，包⑥⑦实现）：

```java
interface AuthProvider {
    @Nullable String token() throws Exception;
    void onUnauthorized(AuthContext context) throws Exception;
    record AuthContext(int status, Map<String,String> headers, String body,
                       String serverUrl, @Nullable String token) { }
}
```

## 3. 异常（:126-151）

| 类 | 要点 |
|---|---|
| `McpHttpError extends RuntimeException` | `int status()`、`String body()`；message 由 `describeHttpFailure`（body 截 500 字符） |
| `McpAuthRequiredError extends McpHttpError` | status 401；`@Nullable String wwwAuthenticate()` |
| `McpSessionExpiredError extends McpHttpError` | status 404，message "MCP session expired" |

`needsAuthorization`（:160-165）：401 恒真；403 且 WWW-Authenticate 匹配
`error="insufficient_scope"`（带不带引号两种）真。
`isTransientStatus`（:167-170）：408/429/>=500。

## 4. fetch 契约与 JDK 实现

fetch 返回**未消费的响应体**，由 transport 决定如何读（状态分派先于读体）：

```java
interface McpHttpFetch {
    McpHttpResponse execute(McpHttpRequest request, AbortSignal signal) throws Exception;
}
record McpHttpResponse(int status, Map<String,String> headers,
                       @Nullable String contentType, InputStream body) { }
```

`JdkMcpHttpFetch`：`HttpClient.sendAsync(..., BodyHandlers.ofInputStream())`；
AbortSignal 触发时 `future.cancel(true)`（取消在飞请求）；contentType 去参数、
小写；headers 取每键首值、大小写不敏感。调用 fetch 不以显式 receiver（对齐
:177 注释）——lambda 形状自然满足。

## 5. SSE 消费器（对齐真实 `consumeSseStream` :30-98）

- **流式解码**：字节块以 `TextDecoder(stream:true)` 等价物处理——Java 用
  InputStreamReader（decoder 内部保欠流状态）；按 `\n` 切行，行尾 CR 剥除；
  缓冲在未遇换行时跨块保留。
- 字段：`event:`/`data:`/`id:`（含 NUL 忽略，且 **id 每见即回调 onId**，
  含无数据事件——resumption priming）/`retry:`（纯数字回调）；冒号起始注释
  忽略；值去一个前导空格。
- **dataBytes 计数**：多行 join 的 `\n` 计入，超 maxEventBytes 即抛错。
- 派发：blank line 派发；**EOF 时残余 data 也派发一次**（真实 :92-94）；
  无 data 的 blank 不派发但清状态。
- Handler：`onId/onRetry/onEvent(SseEvent)`；event 名缺省 "message"，
  data 以 `\n` join。

## 6. Transport 主体（:183-274）

`send` 真实分派：

1. 未 start/closed ⇒ McpConnectionClosedError。
2. `authorizedFetch("POST", accept + content-type, body=JSON)`；
   先 `checkResponse`（!ok：401→McpAuthRequired；有会话 404→
   McpSessionExpired；其余 McpHttpError），再 `captureSession`。
3. **非 request**（notification/response）：`discard(body)`；
   method==`notifications/initialized` ⇒ startGetStream（openGetStream≠false）；
   正常返回（202 无错误）。
4. **request**：202/204 ⇒ **McpHttpError**（"accepted … without a response"）；
   JSON：体可为**数组**，逐项 parseJsonRpcMessage→emitMessage；
   SSE：`void consumeResponseStream(body, id)`；
   其余 ⇒ McpHttpError（unsupported content type）。

`authorizedFetch`/`headers`（:277-312）：
- headers 合并：options.headers → 本次头 → `Mcp-Session-Id`（有会话）→
  `MCP-Protocol-Version`（协议降级时；本包先留通道）→
  auth.token() 有值则 `Authorization: Bearer`；
- 401/insufficient-scope 403：调 `authProvider.onUnauthorized` 一次，discard 后
  重试一次（attempt>0 不再触发）。

`close`（:257-274）：幂等；closed=true，abort 共享 signal；**有会话则
DELETE** url（1s 超时，失败/鉴权失败均吞）；emitClose 一次（基座保 once）。

## 7. 流恢复（:353-500）——`StreamableHttpStreams`

**consumeResponseStream**（:353-399）：
- cursor：lastEventId/retryMs/received；onMessage 回调里判定
  `isJsonRpcResponse && id==requestId ⇒ answered`；
- 流结束/失败：answered/closed ⇒ 收；失败不可重试或**无 lastEventId**或
  超过 maxRetries ⇒ 终止；
- 重连：GET openSseStream(lastEventId)；received ⇒ attempt 复位；等待延迟
  （closed 时 sleep 返 false 即收）；
- 终止失败：**emit 一帧合成 JSON-RPC error**：id=requestId，code
  INTERNAL_ERROR，message `"MCP response stream failed: <reason>"`
  （reason：无答案＝"stream ended without a response"）。

**runGetStream**（:407-431）：
- openSseStream：**405 ⇒ 服务器不提供 GET 流，静默停止**；非 SSE ⇒
  McpHttpError；
- 流健康复位：received 或存活超 maxDelay ⇒ attempt=0；
- 不可重试错误 ⇒ emitError 并停；连续 maxRetries ⇒
  emitError("MCP server-to-client stream dropped and could not be reopened")。

**isRetryable**（:481-487）：McpHttpError⇒transient status；网络 TypeError
（Java：IOException）⇒真；code 字符串 `E`/`UND_ERR` 前缀⇒真。

**退避**（:469-479）：`min(initial * 2^attempt, maxDelay)`；server 的
retryMs 优先。sleep 响应 abort（shared signal），等待不保活。

## 8. 测试计划（L5）

移植 pi `test/streamable-http.test.ts` 11 条款；夹具＝脚本化 loopback
`com.sun.net.httpserver.HttpServer`（可按脚本应答 JSON/SSE/202/401/405、可控
关闭流、统计请求方法/头）：

1. JSON 与 SSE 响应＋session 头（:124，含 JSON 数组逐项）
2. 401 归类＋www-authenticate（:143）
3. 只失败中断的那个请求（:158）
4. initialize 后 GET 流；Last-Event-ID 仅恢复时（:188）
5. 响应流答案帧前关闭→GET 恢复（:208）
6. 无答案帧结束→合成 error 帧（:236）
7. GET 流断开退避重连（:252）；405 静默
8. request 收 202/204 ⇒ 错误（:280）
9. HTTP 错误含 body（:294）
10. 401/insufficient-scope 403 → auth 重试（:306）
11. fetch 无 receiver（:346）

SSE 消费器直接单测：CRLF/注释/id/retry/多行 data、EOF 残余派发、dataBytes
超限、无 data 不派发。

## 9. 变异探针

| # | 变异 | 预期红 |
|---|---|---|
| M1 | GET 首次也发 Last-Event-ID | 条款 4 |
| M2 | 响应流 EOF 不恢复 | 条款 5 |
| M3 | request 202 当成功 | 条款 8 |
| M4 | data 多行取最后行/不 join | 消费器多行用例 |
| M5 | EOF 残余不派发 | EOF dispatch 用例 |
| M6 | 405 当错误（emitError） | GET 405 静默条款 |
| M7 | 无 lastEventId 仍重连 | 条款 6（无 id 服务器应直接失败） |

每次变异 grep 复核落地（CRLF 教训）。

## 10. 台账影响

- B160 不销号；B177 维持。闭环回填 docs/28 banner 与本文。

## 11. 实施记录（2026-10-07）

**落地（1 feat＋1 docs）**，10 个主/测文件：

| 类 | 职责 |
|---|---|
| `McpHttpFetch/Request/Response` | fetch 契约（未消费体） |
| `JdkMcpHttpFetch` | sendAsync＋AbortSignal 取消；大小写不敏感头 |
| `AuthProvider` | 凭据接口（包⑥⑦实现） |
| `SseStreamConsumer` | 行解析/dataBytes/EOF 派发 |
| `StreamableHttpTransport`（294 行） | send 全分派/authorizedFetch/headers/checkResponse/DELETE 关闭 |
| `StreamableHttpStreams`（~230 行） | 响应流恢复循环＋GET 流重连 |
| `ScriptedHttpServer`（test） | 脚本化 loopback HttpServer |

**测试 52/52 绿**（本模块全量）：transport ×11、SSE ×5，含响应流 GET 恢复、
无 id 不恢复、GET 断开重连带 Last-Event-ID、405 静默、202 request 报错、
JSON 数组批处理；全依赖 1377 绿；checkstyle mcp 0。

**变异探针 7/7 红**：M1 首次 GET 强发 Last-Event-ID；M2 响应流不恢复；
M3 request 202 当成功；M4 多行 data 只留最后行；M5 EOF 残余不派发；
M6 405 报错；M7 无 id 仍恢复。

**三条设计外发现**：

1. **`new TreeMap<>(sortedMap)` 不继承比较器**——参数静态类型为 Map 时走
   `TreeMap(Map)` 自然排序，复制即丢比较器；须显式传 String.CASE_INSENSITIVE_ORDER。
2. **JDK HttpClient 把头名规范化为小写**；HTTP 头断言一律大小写不敏感。
3. loopback 的 JDK HttpServer 不自动加 Content-Type，helper 须显式设置。

另一条过程教训：多探针连续变异时，**每次还原必须 grep/编译双重确认**（M4 残留
未还原导致后续排查浪费）。
