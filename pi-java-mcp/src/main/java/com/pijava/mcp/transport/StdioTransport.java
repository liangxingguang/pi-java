package com.pijava.mcp.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.pijava.mcp.protocol.jsonrpc.McpConnectionClosedError;

/**
 * MCP transport over a child process's stdio (stdio.ts:46-207).
 */
public final class StdioTransport extends AbstractMcpTransport {

    private static final long DEFAULT_CLOSE_TIMEOUT_MS = 2_000;

    /** pi {@code STDIN_CLOSE_GRACE_MS}：关 stdin 之后、SIGTERM 之前给服务器自己退的时间。 */
    private static final long STDIN_CLOSE_GRACE_MS = 500;

    private final StdioTransportOptions options;
    private final Object lock = new Object();
    private @Nullable Process process;
    private @Nullable OutputStream stdin;
    private volatile boolean started;
    private volatile boolean closed;
    private boolean closing;
    private final StderrTail stderrTail = new StderrTail();

    /** Create a transport. */
    public StdioTransport(StdioTransportOptions options) {
        this.options = options;
    }

    /** stderr tail, captured when stderr is piped. */
    public String stderr() {
        return stderrTail.text();
    }

    /** Child pid, or {@code -1} when not running. */
    public long pid() {
        synchronized (lock) {
            return process == null ? -1 : process.pid();
        }
    }

    @Override
    public void start() throws IOException {
        synchronized (lock) {
            if (started) {
                throw new IOException("MCP stdio transport already started");
            }
            var launched = StdioProcess.start(options);
            process = launched.process();
            stdin = launched.stdin();
            started = true;
            // pi 的 liveProcessGroups：宿主意外退出时兜底清理还活着的服务器树。
            StdioProcess.register(java.util.List.of(launched.process().toHandle()));
        }
        Thread.startVirtualThread(this::stdoutPump);
        Thread.startVirtualThread(this::stderrPump);
        Thread.startVirtualThread(this::exitPump);
    }

    @Override
    public void send(Map<String, Object> message) throws Exception {
        OutputStream out;
        synchronized (lock) {
            if (!started || closed || stdin == null) {
                throw new McpConnectionClosedError();
            }
            out = stdin;
        }
        var bytes = com.pijava.mcp.McpJson.mapper().writeValueAsBytes(message);
        synchronized (out) {
            out.write(bytes);
            out.write('\n');
            out.flush();
        }
    }

    @Override
    public void close() {
        Process child;
        java.util.List<ProcessHandle> tree;
        synchronized (lock) {
            if (closing || closed) {
                return;
            }
            closing = true;
            child = process;
            // ⚠️ 快照必须在关 stdin **之前**抓：服务器一退，它的后代就被重新挂到别的父下，
            // descendants() 再也找不到（pi 用进程组解决的正是这件事）。
            tree = child == null ? java.util.List.of() : StdioProcess.snapshotTree(child);
            if (stdin != null) {
                try {
                    stdin.close();
                } catch (IOException ignored) {
                }
            }
        }
        if (child != null) {
            long timeout = options.closeTimeoutMs() > 0
                    ? options.closeTimeoutMs() : DEFAULT_CLOSE_TIMEOUT_MS;
            closeTree(child, tree, timeout);
        }
        waitUntilClosed();
    }

    /**
     * pi {@code stdio.ts:172-205} 的三段式关闭：关 stdin 后先给
     * {@code min(500ms, closeTimeout)} 宽限让合作型服务器自己退，超时才 SIGTERM 等价杀树，
     * 再超时才 SIGKILL。旧实现是「白等满 closeTimeout 再杀」，且父进程一旦退出就忘了后代。
     */
    private void closeTree(Process child, java.util.List<ProcessHandle> tree, long closeTimeout) {
        long grace = Math.min(STDIN_CLOSE_GRACE_MS, closeTimeout);
        try {
            if (child.waitFor(grace, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                // 父进程自己退了：后代可能还握着管道（pi 在 close 事件里仍要补一刀 SIGTERM）。
                StdioProcess.terminate(tree, 0);
                return;
            }
            if (StdioProcess.terminate(tree, closeTimeout)) {
                return;
            }
            StdioProcess.kill(tree, closeTimeout);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private void waitUntilClosed() {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            synchronized (lock) {
                if (process == null) {
                    closed = true;
                    return;
                }
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        closed = true;
    }

    private void stdoutPump() {
        Process child;
        synchronized (lock) {
            child = process;
        }
        if (child == null) {
            return;
        }
        int maxBytes = options.maxMessageBytes() > 0
                ? options.maxMessageBytes() : DEFAULT_MAX_MESSAGE_BYTES;
        StdioLineFrames.pump(child.getInputStream(), maxBytes, line -> {
            try {
                var value = com.pijava.mcp.McpJson.mapper().readValue(line, Object.class);
                emitMessage(value);
            } catch (IOException error) {
                emitError(error);
            }
        }, this::emitError);
    }

    private void stderrPump() {
        if (options.stderr() != StdioTransportOptions.Stderr.PIPE) {
            return;
        }
        Process child;
        synchronized (lock) {
            child = process;
        }
        if (child == null) {
            return;
        }
        InputStream stderr = child.getErrorStream();
        var chunk = new byte[4 * 1024];
        try {
            int read;
            while ((read = stderr.read(chunk)) != -1) {
                stderrTail.append(java.util.Arrays.copyOf(chunk, read));
            }
        } catch (IOException ignored) {
        }
    }

    private void exitPump() {
        Process child;
        synchronized (lock) {
            child = process;
        }
        if (child == null) {
            return;
        }
        int exitCode;
        try {
            exitCode = child.waitFor();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return;
        }
        synchronized (lock) {
            process = null;
        }
        StdioProcess.unregister(child.toHandle());
        if (exitCode != 0) {
            var tail = stderrTail.text();
            var firstLine = tail.isBlank() ? "" : tail.lines().findFirst().orElse("");
            var message = "MCP server exited with code " + exitCode
                    + (firstLine.isBlank() ? "" : ": " + firstLine.trim());
            emitError(new RuntimeException(message));
        }
        emitClose();
    }
}
