# 包 29：MCP 对齐（B160）——第 2 包：`McpClient`

> **状态：✅ 已闭环（2026-10-07，R-A）。**
> 总包路线见 `docs/28` §3；本文是第 2 包（pi `packages/mcp/src/client.ts`，615 行）
> 设计。实施记录见 §8。

## 1. 移植文件清单（`com.pijava.mcp`）

| 新文件 | 对齐 |
|---|---|
| `McpClientOptions.java` | client.ts:47-52 |
| `McpRequestOptions.java` | client.ts:54-58 |
| `McpClient.java` | client.ts:153-615（主体） |
| `McpClientValidators.java` | client.ts:72-151（四个校验器） |

## 2. 选项 records

```java
// McpClientOptions.java —— client.ts:47-52
public record McpClientOptions(
        String name,
        String version,
        @Nullable String title,
        @Nullable ClientCapabilities capabilities,
        @Nullable String protocolVersion,
        long requestTimeoutMs,                        // <=0 表示未设置
        @Nullable Roots roots) {                      // 静态列表或供给器
    /** Roots offered at request time: fixed list or supplier. */
    public sealed interface Roots permits Roots.Fixed, Roots.Supplied {
        record Fixed(List<Root> roots) implements Roots {}
        record Supplied(Supplier<List<Root>> supplier) implements Roots {}
    }
}
```

```java
// McpRequestOptions.java —— client.ts:54-58
public record McpRequestOptions(
        @Nullable AbortSignal signal,
        long timeoutMs,                               // <=0 表示未设置
        @Nullable Consumer<ProgressNotification> onProgress) {
}
```

## 3. 两处地基改动

### 3.1 `AbortSignal` 加监听支持（pi-java-ai）

pi 的 signal 有 `addEventListener("abort")`（client.ts:435 等）。Java 侧
`AbortSignal` 目前只有 volatile flag。加一个加法 API（不改既有行为；其余使用方
轮询不受影响）：

```java
private final List<Runnable> abortListeners = new CopyOnWriteArrayList<>();

/** Run {@code listener} once when aborted, immediately when already aborted. */
public void onAbort(Runnable listener) {
    if (aborted) {
        listener.run();
    } else {
        abortListeners.add(listener);
    }
}

public void abort() {
    aborted = true;
    for (var listener : abortListeners) listener.run();
}
```

### 3.2 超时调度器

`setTimeout`/`clearTimeout` 用一个模块级 daemon 调度器（虚拟线程工厂）：

```java
// McpClientSchedules.java
static final ScheduledExecutorService SCHEDULER =
        Executors.newScheduledThreadPool(1, Thread.ofVirtual().factory());
/** Schedule {@code task}; returns a handle used to cancel it. */
static ScheduledFuture<?> timeout(long ms, Runnable task) {
    return SCHEDULER.schedule(task, ms, TimeUnit.MILLISECONDS);
}
```

## 4. `McpClient`

### 4.1 线程模型

pi 是单事件循环；Java transport 从任意线程投递消息。语义对齐做法：**所有内部状态
操作在 `this` 锁内串行**（消息处理、pending 登记/收口、监听者注册）。监听者回调
（onProgress/notification/error/close）在投递线程持锁内执行——与 pi 的「回调内联
调用」顺序一致（参考 B 包 update 内联裁决）。监听者不得回调 client 重入：文档
注释声明。

### 4.2 wire 发送

消息一律 `Map<String,Object>`（§28 §4.2 裁决：id 放原始 int，不发 `1.0`）：

```java
private static Map<String, Object> requestWire(long id, String method, @Nullable Object params) {
    var m = new LinkedHashMap<String, Object>();
    m.put("jsonrpc", "2.0");
    m.put("id", id);
    m.put("method", method);
    if (params != null) m.put("params", params);
    return m;
}
```

### 4.3 状态与字段（client.ts:153-180）

```java
public final class McpClient {
    private static final long DEFAULT_TIMEOUT_MS = 30_000;
    private static final int MAX_LIST_PAGES = 1_000;

    private final McpClientOptions options;
    private final Object lock = new Object();
    private State state = State.IDLE;                  // IDLE/CONNECTING/CONNECTED/CLOSED
    private @Nullable McpTransport transport;
    private long nextRequestId = 1;
    private @Nullable InitializeResult initializeResult;     // 拆 serverInfo/caps/instructions/protocolVersion
    private final Map<Long, Pending> pending = new HashMap<>();
    private final Map<Long, Long> progressRequests = new HashMap<>();   // token -> id
    private final Map<Long, AbortController> incoming = new HashMap<>();
    private final Map<String, RequestHandler> requestHandlers = new HashMap<>();
    private final Map<String, List<Consumer<Object>>> notificationListeners = new HashMap<>();
    private final List<Consumer<Error>> errorListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> closeListeners = new CopyOnWriteArrayList<>();
```

`Pending` record（client.ts:60-70）：`CompletableFuture<Object> future`、
`long timeoutMs`、`@Nullable ScheduledFuture<?> timer`、`@Nullable AbortSignal signal`、
`Runnable onAbort`、`boolean cancellable`、`@Nullable Consumer<ProgressNotification> onProgress`、
`@Nullable Long progressToken`。

`RequestHandler`：`Object handle(Object params, AbortSignal signal) throws Exception`
（返回值或 `CompletableFuture`，由内部统一 `toFuture` 适配）。

默认 handlers：`ping -> Map.of()`；配置了 roots 时 `roots/list -> {roots: [...]}`
（roots 供给器此时调用；Fixed/Supplied 两形）。

### 4.4 方法（签名按 pi，全部包锁语义）

| 方法 | client.ts |
|---|---|
| `connectionState()` | :182-184 |
| `serverInfo() / serverCapabilities() / instructions() / protocolVersion()` | :186-200 |
| `McpClient connect(McpTransport)` | :202-248 |
| `CompletableFuture<Object> request(String, Object params, McpRequestOptions)` | :250-256 |
| `notify(String, Object)` | :258-260 |
| `setRequestHandler`（返回注销 Runnable） | :262-267 |
| `onNotification/onError/onClose`（返回注销） | :269-288 |
| `ping` | :290-292 |
| `listTools() → List<McpTool>` | :294-296 |
| `listResources() / listResourcesPage(cursor)` | :298-318 |
| `listResourceTemplates() / listResourceTemplatesPage(cursor)` | :309-333 |
| `readResource(uri)` | :335-337 |
| `callTool(name, args)` | :376-384 |
| `close()` | :386-392 |

要点逐字移植：

- `connect`：注册 transport 三回调 → `transport.start()` → initialize（capabilities
  展开；roots 存在时补 `capabilities.roots={}`，:215-216）→ protocolVersion 必须在
  `McpVersion.SUPPORTED`（:233-235）→ `setProtocolVersion` 回调 transport →
  `notifications/initialized` → CONNECTED；失败路径 `close()` 后 rethrow（:244-247）。
- 分页：`listAll` 循 nextCursor，`MAX_LIST_PAGES` 上限，重复 cursor 报错
  （:354-374）；null/"" cursor 归一为缺省（valid 内处理）。
- `requestInternal`（:394-439）：门控（connected/connecting）→ signal 已 abort 即抛
  `McpAbortError` → onProgress 时 `_meta.progressToken=id` → 登记 pending、挂超时、
  signal.onAbort → `transport.send` 异常即收口（false）。
- `handleRequest`（:487-517）：无 handler 回 METHOD_NOT_FOUND；handler 结果
  `result ?? {}`；异常时 `McpError` 透传 code/data，其余 INTERNAL_ERROR。
- progress 通知：校验后**重置该请求超时**（:536）再回调。
- `markClosed`（:590-605）：幂等；reject 全部 pending（`McpConnectionClosedError`）、
  abort 所有 incoming handler、closeListeners 回调一次。
- initialize 的 `cancellable=false`：abort/timeout 都不发 `notifications/cancelled`
  （:422-429）。

结果类型转换：`listTools` 等用 `McpJson.mapper().convertValue(raw, TypeReference)`，
由校验器先过形状。

### 4.5 `McpClientValidators`

四函数逐字移植 client.ts:72-151：

- `validateInitializeResult`（:72-85）：protocolVersion String、capabilities/
  serverInfo 是 object、name/version String、instructions 若有须 String；失败
  `McpError(INVALID_REQUEST)`。
- `validateListPage(method,key,raw,isItem)`（:91-107）：`key` 数组逐项校验；
  nextCursor null/"" 归一缺省，若非 String 报错。
- `validateReadResourceResult`（:128-140）：contents 数组，每项 uri String 且
  text/blob 至少一个 String。
- `validateCallToolResult`（:142-151）：content 若有须数组；structuredContent
  若有须 object；缺 content 时补空数组（SDK 同默认）。

isItem 三谓词：`isTool`（name String＋inputSchema object）、`isResource`（uri
String，name 若有须 String）、`isResourceTemplate`（uriTemplate String 同理）；
Resource/Template 缺 name 时补 uri/uriTemplate（:116-122）。

## 5. 测试计划（L5，RED-first）

移植 pi `test/client.test.ts` 12 条款（329 行）为 `McpClientTest`：

1. 连接前不暴露 server 信息；connect 后可取（:71-92）
2. tools 分页、protocol 定义保留（:94-126）
3. resources list/read（:128-160）
4. structured content 透传＋JSON-RPC 错误上浮（:162-183）
5. progress 续超时（:185-208）
6. abort/timeout 取消（:210-226）
7. transport error 不杀 pending（:228-249）
8. 旧协议版本服务器可接（:251-272）
9. tools/call content 缺省补空（:274-281）
10. initialize 超时不发 cancelled（:283-292）
11. transport drop 时 close 监听者只回调一次（:294-306）
12. roots/list 应答＋notification 分发（:308-329）

夹具：RecordingTransport（手工实现 `McpTransport`，队列化收到的 wire Map，
可脚本化投递任意 JSON）。多线程用例的线程交接一律闩锁。

## 6. 变异探针

| # | 变异 | 预期红 |
|---|---|---|
| M1 | protocolVersion 不在 SUPPORTED 时不报错 | 条款 8 红 |
| M2 | progress 通知不重置超时 | 条款 5 红（缩短超时，中途发 progress） |
| M3 | initialize 也发 notifications/cancelled | 条款 10 红 |
| M4 | 分页重复 cursor 不检测 | 条款 2 红（脚本构造重复 cursor） |
| M5 | content 缺省时不补空数组 | 条款 9 红 |
| M6 | 去掉 `synchronized`（改 ConcurrentHashMap 裸奔） | roots/list 并发应答用例红（racing 读到半登记 pending） |

每次变异 grep 复核落地。

## 7. 台账影响

- B160 不销号；B177 维持。闭环回填 docs/28 banner 与本文。

## 8. 实施记录（2026-10-07）

**落地（1 feat＋1 docs）**：

| 内容 | 文件 |
|---|---|
| McpClient 状态机 | 拆 7 文件：`McpClient`（496 行）、`McpClientOptions`、`McpClientValidators`、`McpClientInbox`、`McpClientPages`、`McpClientRegistrations`、`McpClientShortcuts`、`McpClientSchedules` |
| 地基 | `AbortSignal.onAbort`（返回注销句柄）；虚拟线程超时调度器 |

**测试 27/27 绿**：`McpClientTest` ×12（client.test.ts 12 条款逐字翻译）＋包 1 的 15；
checkstyle mcp 0。

**变异探针 6/6 红**：M1 去版本支持报错 ⇒ 古服务器条款红；M2 去 progress 重排
（超时同步缩到 150）⇒ McpTimeoutError；M3 initialize cancellable 改 true ⇒ 超时发
cancelled 条款红；M4 去重复 cursor 检测＋脚本循环 cursor ⇒ exceeded 1000 pages；
M5 去 content 缺省补空 ⇒ structured 条款红；M6 sed 剥离全部 21 个 client 锁＋
500 并发 roots burst ⇒ 虚拟线程并发 NPE（default uncaught handler 捕获）。

**两处设计外发现**：

1. checkstyle 在 validate 阶段拦截时，模块测试根本不执行（surefire 报告是上轮
   残留）——排错先看 validate 输出。
2. 未跟踪新文件无 git 副本可 checkout；sed 探针变异前留好反向脚本。
