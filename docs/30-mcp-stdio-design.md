# 包 30：MCP 对齐（B160）——第 3 包：stdio transport

> **状态：✅ 已闭环（2026-10-07，R-A）。**
> 总包路线见 `docs/28` §3；本文是第 3 包（pi `transports/transport.ts` 55 行＋
> `transports/stdio.ts` 216 行）设计。实施记录见 §10。

## 1. 文件清单（`pi-java-mcp`，包 `com.pijava.mcp.transport`）

| 新文件 | 对齐 |
|---|---|
| `AbstractMcpTransport.java` | transport.ts:20-54（监听器基座） |
| `StdioTransportOptions.java` | stdio.ts:31-44 |
| `StdioTransport.java` | stdio.ts:46-207（生命周期） |
| `StdioProcess.java` | stdio.ts:96-141（启动/env/杀树） |
| `StdioLineFrames.java` | stdio.ts:160-178（字节行帧解析） |

## 2. `AbstractMcpTransport`

`McpTransport` 接口已在包②落地。抽象类提供 listener 簿记（transport.ts:32-51）：

```java
public abstract class AbstractMcpTransport implements McpTransport {
    static final int DEFAULT_MAX_MESSAGE_BYTES = 16 * 1024 * 1024;
    private final List<Consumer<Object>> messageListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Throwable>> errorListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> closeListeners = new CopyOnWriteArrayList<>();

    protected void emitMessage(Object message) ...
    protected void emitError(Throwable error) ...
    protected void emitClose() ...
    @Override public Runnable onMessage/onError/onClose ...   // 返回注销句柄
}
```

## 3. Options（stdio.ts:31-44）

```java
public record StdioTransportOptions(
        String command,
        List<String> args,                 // 默认 List.of()
        @Nullable Map<String,String> env,  // 覆盖项
        boolean inheritEnv,                // 默认 true
        Stderr stderr,                     // enum：PIPE/INHERIT/IGNORE，默认 PIPE
        long closeTimeoutMs,               // 默认 2_000；<=0 用默认
        int maxMessageBytes) {            // 默认 16 MiB；<=0 用默认
    /** stderr 路由（stdio.ts:69-71）。 */
    public enum Stderr { PIPE, INHERIT, IGNORE }
}
```

加 2 个便捷构造器（command；command＋args）。

## 4. `StdioTransport`

### 4.1 状态字段（stdio.ts:53-73）

```java
public final class StdioTransport extends AbstractMcpTransport {
    private final StdioTransportOptions options;
    private final Object lock = new Object();
    private @Nullable Process process;
    private @Nullable OutputStream stdin;
    private volatile boolean started;
    private volatile boolean closed;
    private boolean closing;
    /** stderr PIPE 时的 64 KiB 尾部缓冲（:63-67,143-157）。 */
    private final StderrTail stderrTail = new StderrTail();
```

`StderrTail`（内部小类）：synchronized append(byte[])，保留最后 64 KiB；
`text()` UTF-8 解码尾部。

### 4.2 start（:75-94）

```java
@Override public void start() throws IOException {
    synchronized (lock) {
        if (started) throw new IOException("MCP stdio transport already started");
        var launched = StdioProcess.start(options);          // §5
        process = launched.process(); stdin = launched.stdin(); started = true;
    }
    Thread.startVirtualThread(this::readPump);               // stdout 行帧 + stderr 泵
    Thread.startVirtualThread(this::exitPump);               // waitFor 收口
}
```

exitPump（:181-189）：`process.waitFor()`；取 exit code：
- code ≠ 0：`emitError`（消息 `MCP server exited with code <code>`＋stderr 首行，
  若有）；
- `synchronized` 置 process=null；无论成败 `emitClose`。

### 4.3 send（:96-106）

```java
@Override public void send(Map<String,Object> message) throws Exception {
    OutputStream out;
    synchronized (lock) {
        if (!started || closed || stdin == null) throw new McpConnectionClosedError();
        out = stdin;
    }
    var bytes = McpJson.mapper().writeValueAsBytes(message);
    synchronized (out) { out.write(bytes); out.write('\n'); out.flush(); }
}
```

### 4.4 close —— 三段关闭（:152-179）

```java
@Override public void close() {
    Process child;
    synchronized (lock) {
        if (closing || closed) return;
        closing = true; child = process;
        // stdin end（:160-163）
        if (stdin != null) { try { stdin.close(); } catch (IOException ignored) {} }
    }
    long timeout = options.closeTimeoutMs() > 0 ? options.closeTimeoutMs() : 2_000;
    if (child != null) {
        StdioProcess.awaitGraceful(child, timeout);     // 等自行退出
        StdioProcess.terminateTree(child, timeout);     // SIGTERM 等价＋再等
        StdioProcess.killTree(child);                   // SIGKILL/taskkill /T /F
    }
    // emitClose 由 exitPump 在读到 waitFor 返回时发；此处兜底等 closed
}
```

幂等：closing/closed 双标志。close 返回后进程必已死（waitFor 全部带超时）。

## 5. `StdioProcess`（ProcessBuilder 封装）

```java
record Launched(Process process, OutputStream stdin) {}

static Launched start(StdioTransportOptions options) throws IOException {
    var pb = new ProcessBuilder();
    pb.command(join(command, args));
    configureEnvironment(pb.environment(), options);   // inheritEnv: clear or retain；env 覆盖
    pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
    pb.redirectInput(ProcessBuilder.Redirect.PIPE);
    pb.redirectError(stderrRedirect(options.stderr())); // INHERIT/ DISCARD/PIPE
    var process = pb.start();
    return new Launched(process, process.getOutputStream());
}
```

进程树操作（对齐 killProcessTree，stdio.ts:97-141）——用 `ProcessHandle` 走子进程：

```java
static void terminateTree(Process child, long timeoutMs) {
    var handle = child.toHandle();
    handle.descendants().forEach(ProcessHandle::destroy);
    handle.destroy();
    child.waitFor(timeout, MILLISECONDS);
}
static void killTree(Process child) {
    var handle = child.toHandle();
    handle.descendants().forEach(ProcessHandle::destroyForcibly);
    handle.destroyForcibly();
    child.waitFor();
}
```

与 pi 的差异裁决：pi 维护 group Set（server respawn 子进程）；Java 用
`descendants()` 即时枚举，结果等价（杀尽子树）。Windows 上 Java destroy() 即
TerminateProcess（强于 SIGTERM）——无 SIGTERM 语义可移植，接受（行为结果
对齐：限期不死即杀）。

## 6. `StdioLineFrames`（stdout 字节帧）

pi 用自定义 readline（注释明确不用 BufferedReader，防止多字节字符在 8 KiB
块边界切断）。逐字节读、按 0x0A 切帧：

```java
static void pump(InputStream stdout, int maxMessageBytes,
                 Consumer<byte[]> onLine, Consumer<Throwable> onError)
```

- 缓冲自上次 `\n` 起的字节；遇 `\n`：去掉行尾可选 `\r`，onLine(bytes)；
- 缓冲 > maxMessageBytes ⇒ onError("MCP message exceeds <n> bytes") 并停泵；
- 流结束但缓冲非空 ⇒ onError("MCP server closed with an incomplete JSON-RPC message")。

StdioTransport readPump：
- stdout：`StdioLineFrames.pump(..., line -> {
      Object value = McpJson.mapper().readValue(line, Object.class); emitMessage(value);
  }, this::emitError)`；
- stderr PIPE 时：虚拟线程逐 chunk append stderrTail（不 emit，:143-157）。

## 7. 测试计划（L5）

pi 两条款用 Node fixture。Java 用 **JVM 子进程 fixture**（同模块 test 源，
`-cp` 用 `System.getProperty("java.class.path")`）：

| Fixture main | 行为 |
|---|---|
| `StdioFixtureServer` | stderr 打 `stdio fixture ready`；stdin 行帧循环：initialize（latest＋echo tool）、tools/list（echo）、tools/call echo→`{content:[{text}]}`；stdin EOF 退出 |
| `StubbornFixtureServer` | 注册永不结束的 shutdown hook（模拟忽略 SIGTERM）；spawn 一个 grandchild JVM（IdleProcess 常驻），stderr 打 `grandchild <pid>`；stdin 忽略 |
| `IdleProcess` | 常驻直到被杀 |

`McpStdioTest` ×2：

1. **connect newline-delimited server＋capture stderr**（对齐 stdio.test:9-28）：
   listTools=`[echo]`、callTool echo→content hello、pid 为正、等待后
   `transport.stderrTail` 含 ready；close 后 state closed。
2. **kill stubborn server incl. children**（对齐 :30-63，跨平台都跑——Java
   descendants 走 ProcessHandle，不 skip Windows）：解析 grandchild pid；close
   总耗时 < 5s（closeTimeout 100ms）；断言 grandchild 进程已死
   （`ProcessHandle.of(pid).isPresent()==false`）。

## 8. 变异探针

| # | 变异 | 预期红 |
|---|---|---|
| M1 | 帧解析改用 `BufferedReader.readLine` | 多字节切断夹具红（临时 fixture：单条消息分 chunk 在字符边界处切 1 字节/读） |
| M2 | maxMessageBytes 检查删除 | 超长帧用例红 |
| M3 | killTree 只杀主进程、不枚举 descendants | stubborn 条款：grandchild 仍活 ⇒ 红 |
| M4 | close 不做三段强杀（只关 stdin） | stubborn 条款：close 超时/挂死 ⇒ 红 |
| M5 | exit code≠0 不 emitError | 临时夹具：fixture 退出码 1 ⇒ onError 条款红 |

每次变异 grep 复核落地。

## 9. 台账影响

- B160 不销号；B177 维持。闭环回填 docs/28 banner 与本文。

## 10. 实施记录（2026-10-07）

**落地（1 feat＋1 docs）**：

| 内容 | 文件 |
|---|---|
| 监听基座/Options | `AbstractMcpTransport`、`StdioTransportOptions`（stderr 三路由 enum） |
| 生命周期/进程/帧 | `StdioTransport`（196 行）、`StdioProcess`、`StdioLineFrames`、`StderrTail` |
| JVM 子进程 fixture | `StdioFixtureServer`（echo）、`StubbornFixtureServer`（grandchild＋shutdown hook）、`IdleProcess` |

**测试 32/32 绿**：`McpStdioTest` ×2、`StdioLineFramesTest` ×3（新增直接字节
单测：CR 剥离/多字节 emoji 逐字节送达/超长与不完整帧）＋包①② 的 27；全依赖
1377 绿；checkstyle mcp 0。

**变异探针 5/5 红**：M1 去 CR 剥离；M2 去 maxMessageBytes；M3 terminateTree
不枚举 descendants ⇒ grandchild 存活；M4 close 只关 stdin ⇒ stubborn 不退出；
M5（临时 ExitCode fixture）非零退出不报错。

**两处设计外发现**：

1. **fixture 必须忽略无 id 的通知**：`notifications/initialized` 是通知不是请求，
   当未知方法抛错会让 fixture 退 1——pi 的 stdio-server fixture 同样忽略通知。
2. **stubborn fixture 必须先应答 initialize 再挂**（否则 client connect 超时）；
   探针 M3 的正确落点是 `terminateTree`（唯一杀点），打在 killTree 会被上游
   SIGTERM 已杀 grandchild 而掩盖——又一条「探针落点」教训。
