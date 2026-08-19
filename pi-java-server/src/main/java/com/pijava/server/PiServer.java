package com.pijava.server;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.pijava.protocol.CborCodec;
import com.pijava.protocol.ClientMessage;
import com.pijava.protocol.Command;
import com.pijava.protocol.CommandResult;
import com.pijava.protocol.FrameCodec;
import com.pijava.protocol.FrameDecoder;
import com.pijava.protocol.FrameException;
import com.pijava.protocol.ProtocolError;
import com.pijava.protocol.ProtocolErrorCode;
import com.pijava.protocol.ProtocolVersion;
import com.pijava.protocol.ServerMessage;
import com.pijava.protocol.ServerSnapshot;
import com.pijava.protocol.ByteConnection;

/**
 * 会话服务器 —— 接受连接、hello 握手、命令分发、快照/进度事件转发（对齐 pi
 * {@code PiServer}）。
 *
 * <p>每连接一个虚拟线程 + 一个 {@link FrameDecoder}。会话租约按连接跟踪：create/attach
 * 获取运行时并订阅事件，detach 释放；冲突操作经 {@link SessionLockedException} 映射为
 * {@code SESSION_LOCKED}。</p>
 */
public final class PiServer implements AutoCloseable {

    private final PiServerService service;
    private final PiServerOptions options;
    private final CborCodec cbor = new CborCodec();
    private final List<PiServerListener> startedListeners = new ArrayList<>();
    /** 全局租约：sessionId → 持有连接（跨连接独占，冲突回 SESSION_LOCKED）。 */
    private final java.util.concurrent.ConcurrentHashMap<String, String> globalLeases =
        new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean closed;

    /** @param service 会话服务实现
     *  @param options  监听器/帧上限/握手超时/服务端 id */
    public PiServer(PiServerService service, PiServerOptions options) {
        this.service = service;
        this.options = options;
    }

    /** 启动全部监听器。 */
    public void start() {
        for (var listener : options.listeners()) {
            listener.start(this::handleConnection);
            startedListeners.add(listener);
        }
    }

    @Override
    public void close() {
        closed = true;
        for (var listener : startedListeners) {
            listener.close();
        }
    }

    // ── 连接处理 ─────────────────────────────────────────────────────────

    private void handleConnection(ByteConnection conn) {
        Thread.ofVirtual().start(() -> runConnection(conn));
    }

    private void runConnection(ByteConnection conn) {
        var leases = new HashMap<String, Lease>();
        try (conn) {
            InputStream in = new BufferedInputStream(conn.in());
            OutputStream out = new BufferedOutputStream(conn.out());
            var decoder = new FrameDecoder(options.maxFrameLength());

            ClientMessage first = readFrame(in, decoder);
            if (!(first instanceof ClientMessage.ClientHello hello)
                    || hello.version() != ProtocolVersion.PROTOCOL_VERSION) {
                write(out, new ServerMessage.ServerHelloError(
                    ProtocolError.of(ProtocolErrorCode.VERSION,
                        "Expected hello with version " + ProtocolVersion.PROTOCOL_VERSION)));
                out.flush();
                return;
            }
            String connectionId = "conn-" + Long.toHexString(
                System.nanoTime() & 0xffffffffL);
            write(out, new ServerMessage.ServerHello(
                ProtocolVersion.PROTOCOL_VERSION, connectionId, buildServerSnapshot()));
            out.flush();

            byte[] buf = new byte[8192];
            int n;
            while (!closed && (n = in.read(buf)) != -1) {
                for (byte[] payload : decoder.push(Arrays.copyOf(buf, n))) {
                    Object msg = cbor.decode(payload, ClientMessage.class);
                    if (msg instanceof ClientMessage.RequestEnvelope req) {
                        handleRequest(req, out, leases, connectionId);
                        out.flush();
                    }
                }
            }
        } catch (IOException | FrameException e) {
            // 连接关闭/协议错误 → 结束该连接
        } finally {
            releaseAll(leases);
        }
    }

    private ClientMessage readFrame(InputStream in, FrameDecoder decoder)
            throws IOException {
        byte[] buf = new byte[8192];
        while (true) {
            int n = in.read(buf);
            if (n == -1) {
                throw new FrameException("Connection closed during handshake");
            }
            var frames = decoder.push(Arrays.copyOf(buf, n));
            if (!frames.isEmpty()) {
                return cbor.decode(frames.get(0), ClientMessage.class);
            }
        }
    }

    // ── 请求分发 ─────────────────────────────────────────────────────────

    private void handleRequest(ClientMessage.RequestEnvelope req,
                               OutputStream out, Map<String, Lease> leases,
                               String connectionId) throws IOException {
        CommandResult result = null;
        ProtocolError error = null;
        try {
            result = dispatch(req.request(), out, leases, connectionId);
        } catch (SessionLockedException e) {
            error = ProtocolError.of(ProtocolErrorCode.SESSION_LOCKED, e.getMessage());
        } catch (IllegalArgumentException e) {
            error = ProtocolError.of(ProtocolErrorCode.INVALID_REQUEST, e.getMessage());
        } catch (Exception e) {
            error = ProtocolError.of(ProtocolErrorCode.INTERNAL_ERROR,
                e.getMessage() == null ? e.toString() : e.getMessage());
        }
        write(out, new ServerMessage.ResponseEnvelope(req.id(), result, error));
    }

    private CommandResult dispatch(Command command, OutputStream out,
                                   Map<String, Lease> leases, String connectionId)
            throws IOException {
        return switch (command) {
            case Command.List() -> new CommandResult.ListResult(service.listSessions());
            case Command.Create c -> {
                // create 只建会话并返回快照；租约由后续 attach 建立（设计 §5.5 两步）
                var runtime = service.createSession(new CreateSessionOptions(
                    c.cwd(), c.name(), c.model(), c.thinkingLevel()));
                yield new CommandResult.CreateResult(runtime.snapshot());
            }
            case Command.Attach a -> {
                var runtime = service.openSession(a.sessionId());
                attachLease(runtime, out, leases, connectionId);
                yield new CommandResult.AttachResult(runtime.snapshot());
            }
            case Command.Detach d -> {
                var lease = leases.remove(d.sessionId());
                if (lease != null) {
                    lease.release();
                }
                globalLeases.remove(d.sessionId());
                yield new CommandResult.DetachResult(d.sessionId());
            }
            case Command.Prompt p -> {
                var lease = requireLease(p.sessionId(), leases);
                lease.runtime().prompt(new PromptInput(p.text()));
                yield new CommandResult.PromptResult(lease.runtime().snapshot());
            }
            case Command.Steer s -> {
                var lease = requireLease(s.sessionId(), leases);
                lease.runtime().steer(new SteerInput(s.text()));
                yield new CommandResult.SteerResult(lease.runtime().snapshot());
            }
            case Command.Abort a -> {
                var lease = requireLease(a.sessionId(), leases);
                lease.runtime().abort();
                yield new CommandResult.AbortResult(lease.runtime().snapshot());
            }
            case Command.SetModel m -> {
                var lease = requireLease(m.sessionId(), leases);
                lease.runtime().setModel(m.model());
                yield new CommandResult.SetModelResult(lease.runtime().snapshot());
            }
            case Command.SetThinking t -> {
                var lease = requireLease(t.sessionId(), leases);
                lease.runtime().setThinking(t.thinkingLevel());
                yield new CommandResult.SetThinkingResult(lease.runtime().snapshot());
            }
        };
    }

    private Lease requireLease(String sessionId, Map<String, Lease> leases) {
        var lease = leases.get(sessionId);
        if (lease == null) {
            throw new IllegalArgumentException("No lease for session " + sessionId);
        }
        return lease;
    }

    /** 获取租约并订阅运行时事件，转发给连接。 */
    private void attachLease(PiSessionRuntime runtime, OutputStream out,
                             Map<String, Lease> leases, String connectionId) {
        String sessionId = runtime.snapshot().id();
        // 跨连接独占租约：已被其他连接持有 → 拒绝
        String previous = globalLeases.putIfAbsent(sessionId, connectionId);
        if (previous != null) {
            runtime.close();
            throw new SessionLockedException(sessionId);
        }
        if (leases.containsKey(sessionId)) {
            globalLeases.remove(sessionId);
            runtime.close();
            throw new SessionLockedException(sessionId);
        }
        Runnable unsubscribe = runtime.subscribe(event -> {
            if (event.snapshot() != null) {
                writeOrSwallow(out,
                    new ServerMessage.EventEnvelope(
                        new com.pijava.protocol.ServerEvent.SessionSnapshotEvent(
                            event.snapshot())));
            } else if (event.progress() != null) {
                writeOrSwallow(out,
                    new ServerMessage.EventEnvelope(
                        new com.pijava.protocol.ServerEvent.SessionProgress(
                            sessionId, event.progress())));
            }
        });
        leases.put(sessionId, new Lease(runtime, unsubscribe));
    }

    private void releaseAll(Map<String, Lease> leases) {
        for (var lease : leases.values()) {
            lease.release();
        }
        for (var sessionId : leases.keySet()) {
            globalLeases.remove(sessionId);
        }
        leases.clear();
    }

    // ── 快照 ─────────────────────────────────────────────────────────────

    private ServerSnapshot buildServerSnapshot() {
        return new ServerSnapshot(
            options.serverId(),
            ProtocolVersion.PROTOCOL_VERSION,
            0L,
            service.listSessions(),
            service.listModels());
    }

    // ── IO 辅助 ──────────────────────────────────────────────────────────

    private void write(OutputStream out, ServerMessage message) throws IOException {
        out.write(FrameCodec.encode(cbor.encode(message)));
    }

    private void writeOrSwallow(OutputStream out, ServerMessage message) {
        try {
            out.write(FrameCodec.encode(cbor.encode(message)));
            out.flush();
        } catch (IOException ignored) {
            // 连接已关闭
        }
    }

    /** 会话租约 —— 运行时 + 退订句柄。 */
    private record Lease(PiSessionRuntime runtime, Runnable unsubscribe) {
        void release() {
            try {
                unsubscribe.run();
            } finally {
                runtime.close();
            }
        }
    }
}
