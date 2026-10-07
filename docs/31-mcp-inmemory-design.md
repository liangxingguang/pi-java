# 包 31：MCP 对齐（B160）——第 4 包：in-memory transport

> **状态：✅ 已闭环（2026-10-07，R-A）。**
> 总包路线见 `docs/28` §3；本文是第 4 包（pi `transports/in-memory.ts`，51 行）
> 设计。实施记录见 §8。

## 1. 落点裁决

pi 的 in-memory pair 位于 `src/testing/index.ts`——**仅测试消费**。Java 侧落地为
**test 源**（`pi-java-mcp/src/test/java/com/pijava/mcp/transport/InMemoryTransport.java`），
不进主 jar；后续 McpClientTest 可改用它（替代 RecordingTransport 的部分脚本需求）。

## 2. 文件

| 新文件（test） | 对齐 |
|---|---|
| `InMemoryTransport.java` | in-memory.ts:10-43（双向 transport） |
| `InMemoryTransports.java` | in-memory.ts:45-51（pair 工厂） |

## 3. `InMemoryTransport`

双向：各持对端引用；send 经对端的 listener 队列**异步投递**（pi 用
`queueMicrotask`；Java 用虚拟线程立即调度，保证不在 send 栈内重入——与 pi
微任务语义一致：send 返回后对端才收）。

```java
final class InMemoryTransport extends AbstractMcpTransport {
    private @Nullable InMemoryTransport peer;
    private volatile boolean closed;
    private final List<Runnable> pendingDeliveries = new CopyOnWriteArrayList<>();

    void connect(InMemoryTransport other) {
        if (peer != null) throw new IllegalStateException("already connected");
        peer = other; other.peer = this;
    }

    @Override public void start() { }   // pair 工厂外已立即可用

    @Override public void send(Map<String,Object> message) {
        var target = peer;
        if (closed || target == null) throw new McpConnectionClosedError();
        // Deep copy so later mutation cannot affect the delivered message.
        var copy = deepCopy(message);
        Thread.startVirtualThread(() -> target.deliver(copy));
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        emitClose();
    }

    private void deliver(Object message) {
        if (!closed) emitMessage(message);
    }
}
```

`deepCopy`：经 McpJson mapper 序列化再读回（或 convertValue 自身）。

## 4. Pair 工厂（in-memory.ts:45-51）

```java
final class InMemoryTransports {
    record Pair(InMemoryTransport client, InMemoryTransport server) { }
    static Pair create() {
        var client = new InMemoryTransport();
        var server = new InMemoryTransport();
        client.connect(server);
        return new Pair(client, server);
    }
}
```

## 5. 测试（L5，RED-first）

`InMemoryTransportTest`（NEW ×3，对齐 client.test.ts 对 pair 的使用方式）：

1. send 的消息被对端 onMessage 收到（map 内容一致）、且 send **不内联重入**
   （监听者内做标记，断言 send 返回时监听者尚未运行）。
2. close 后双方 onClose 触发；closed 后 send 抛 McpConnectionClosedError；
   close 后排队的投递被丢弃。
3. 双向独立：各自 send 互不干扰；深拷贝隔离（send 后 mutate 原 map 不影响
   对端收到的内容）。

## 6. 变异探针

| # | 变异 | 预期红 |
|---|---|---|
| M1 | send 改为同步内联 deliver（去虚拟线程） | 非重入时序条款红 |
| M2 | 去掉 deepCopy（直接传引用） | 深拷贝隔离条款红 |
| M3 | close 不检查 closed 标志（排队投递仍投递） | close 后丢弃条款红 |

## 7. 台账影响

- B160 不销号；B177 维持。闭环回填 docs/28 banner 与本文。

## 8. 实施记录（2026-10-07）

**落地（1 feat＋1 docs）**：`InMemoryTransport`（test 源；虚拟线程异步投递、
deepCopy 隔离）、`InMemoryTransports` pair 工厂。

**测试 36/36 绿**：`InMemoryTransportTest` ×4（非重入时序/深拷贝隔离/关闭
丢弃/双向独立）＋前 3 包 32；全依赖绿；checkstyle 0。

**变异探针 3/3 红**：M1 内联同步投递；M2 去 deepCopy；M3 deliver 去 closed
检查。

**一处设计外发现**：**对端关闭后本端 send 不抛**（pi 同：send 只查自己的
closed 标志），消息静默丢弃；只有自己 close 后 send 才抛
McpConnectionClosedError。初版断言写反，修正。
