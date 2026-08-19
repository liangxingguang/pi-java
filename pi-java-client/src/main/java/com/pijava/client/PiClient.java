package com.pijava.client;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import com.pijava.protocol.ByteConnection;
import com.pijava.protocol.CborCodec;
import com.pijava.protocol.ClientMessage;
import com.pijava.protocol.Command;
import com.pijava.protocol.CommandResult;
import com.pijava.protocol.FrameCodec;
import com.pijava.protocol.FrameDecoder;
import com.pijava.protocol.ProtocolVersion;
import com.pijava.protocol.ServerEvent;
import com.pijava.protocol.ServerMessage;
import com.pijava.protocol.SessionSnapshot;

/**
 * 远程会话客户端 —— 连接、握手、请求/响应关联、事件分发（对齐 pi {@code PiClient}）。
 *
 * <p>后台读线程按 id 关联响应（{@link ServerMessage.ResponseEnvelope}）到待决请求，
 * 并把 {@link ServerMessage.EventEnvelope} 广播给事件监听器（会话快照/进度订阅）。</p>
 */
public final class PiClient implements AutoCloseable {

    private final PiClientOptions options;
    private final CborCodec cbor = new CborCodec();
    private final ConcurrentHashMap<String, CompletableFuture<CommandResult>> pending =
        new ConcurrentHashMap<>();
    private final List<Consumer<ServerEvent>> eventListeners = new CopyOnWriteArrayList<>();

    private ByteConnection conn;
    private InputStream in;
    private OutputStream out;
    private Thread readerThread;
    private volatile boolean closed;
    private volatile boolean connected;

    /** @param options 传输/帧上限/超时 */
    public PiClient(PiClientOptions options) {
        this.options = options;
    }

    /** 建立连接并完成 hello 握手。 */
    public void connect() {
        try {
            conn = options.transport().connect();
            in = new BufferedInputStream(conn.in());
            out = new BufferedOutputStream(conn.out());
            write(new ClientMessage.ClientHello(ProtocolVersion.PROTOCOL_VERSION));
            out.flush();

            ServerMessage hello = readFrame();
            if (hello instanceof ServerMessage.ServerHello sh) {
                connected = true;
                startReader();
            } else if (hello instanceof ServerMessage.ServerHelloError err) {
                throw new PiClientException(
                    "Handshake failed: " + err.error().code() + " " + err.error().message());
            } else {
                throw new PiClientException("Unexpected server message: " + hello);
            }
        } catch (IOException e) {
            throw new PiClientException("Connect failed", e);
        }
    }

    /** 发送命令并等待响应；服务端错误抛 {@link PiClientException}。 */
    public CommandResult send(Command command) {
        var id = UUID.randomUUID().toString();
        var future = new CompletableFuture<CommandResult>();
        pending.put(id, future);
        try {
            write(new ClientMessage.RequestEnvelope(id, command));
            out.flush();
            CommandResult result = future.get(
                options.connectTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (result == null) {
                throw new PiClientException("Server returned an error for " + command.type());
            }
            return result;
        } catch (IOException e) {
            throw new PiClientException("Request failed: " + command.type(), e);
        } catch (TimeoutException e) {
            throw new PiClientException("Request timed out: " + command.type(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PiClientException("Interrupted: " + command.type(), e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw new PiClientException(cause == null ? "Request failed" : cause.getMessage());
        } finally {
            pending.remove(id);
        }
    }

    /** 订阅服务端推送事件；返回句柄只摘除本监听器。 */
    public AutoCloseable subscribe(Consumer<ServerEvent> listener) {
        eventListeners.add(listener);
        return () -> eventListeners.remove(listener);
    }

    /** 获取会话租约（attach）。 */
    public SessionHandle acquire(String sessionId) {
        CommandResult result = send(new Command.Attach(sessionId));
        if (result instanceof CommandResult.AttachResult(SessionSnapshot session)) {
            return new SessionHandle(this, session);
        }
        throw new PiClientException("Attach failed for " + sessionId);
    }

    // ── 包级操作（SessionHandle 使用） ───────────────────────────────────

    SessionSnapshot prompt(String sessionId, String text) {
        CommandResult result = send(new Command.Prompt(sessionId, text));
        return snapshotOf(result, sessionId);
    }

    void abort(String sessionId) {
        send(new Command.Abort(sessionId));
    }

    void detach(String sessionId) {
        send(new Command.Detach(sessionId));
    }

    @Override
    public void close() {
        closed = true;
        if (conn != null) {
            conn.close();
        }
        if (readerThread != null) {
            readerThread.interrupt();
        }
    }

    // ── 内部 ─────────────────────────────────────────────────────────────

    private void startReader() {
        readerThread = Thread.ofVirtual().start(() -> {
            var decoder = new FrameDecoder(options.maxFrameLength());
            byte[] buf = new byte[8192];
            try {
                int n;
                while (!closed && (n = in.read(buf)) != -1) {
                    for (byte[] payload : decoder.push(Arrays.copyOf(buf, n))) {
                        Object msg = cbor.decode(payload, ServerMessage.class);
                        if (msg instanceof ServerMessage.ResponseEnvelope resp) {
                            var future = pending.remove(resp.id());
                            if (future != null) {
                                if (resp.error() != null) {
                                    future.completeExceptionally(new PiClientException(
                                        resp.error().message()));
                                } else {
                                    future.complete(resp.result());
                                }
                            }
                        } else if (msg instanceof ServerMessage.EventEnvelope ev) {
                            for (var listener : eventListeners) {
                                try {
                                    listener.accept(ev.event());
                                } catch (RuntimeException ignored) {
                                    // 单监听器异常被隔离
                                }
                            }
                        }
                    }
                }
            } catch (IOException e) {
                // 连接关闭
            }
        });
    }

    private ServerMessage readFrame() throws IOException {
        var decoder = new FrameDecoder(options.maxFrameLength());
        byte[] buf = new byte[8192];
        while (true) {
            int n = in.read(buf);
            if (n == -1) {
                throw new IOException("Connection closed");
            }
            var frames = decoder.push(Arrays.copyOf(buf, n));
            if (!frames.isEmpty()) {
                return cbor.decode(frames.get(0), ServerMessage.class);
            }
        }
    }

    private void write(ClientMessage message) throws IOException {
        out.write(FrameCodec.encode(cbor.encode(message)));
    }

    private static SessionSnapshot snapshotOf(CommandResult result, String sessionId) {
        if (result instanceof CommandResult.PromptResult pr) {
            return pr.session();
        }
        throw new PiClientException("Unexpected result for prompt on " + sessionId);
    }
}
