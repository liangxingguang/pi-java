package com.pijava.coding.agent.core;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.stream.StreamEvent;

/**
 * Live result of one {@code AgentSession.processPrompt} call (Phase 3 §10).
 *
 * <p>Unlike a plain record, this is a live object: {@link #stream()} drains
 * incremental {@link StreamEvent} values as the run produces them (driven on
 * a virtual thread), while {@link #entries()} and {@link #status()} block
 * until the run finishes.</p>
 *
 * <p>A5（{@code 原 docs/25}）：{@link #disposition} 对齐 pi 预检结果
 * （{@code started} = 起了 run；{@code queued} = 仅排队，无驱动线程）。</p>
 */
public final class SessionResult {

    private final Stream<StreamEvent> stream;
    private final CompletableFuture<List<Entry>> entries;
    private final CompletableFuture<RunStatus> status;
    private final String disposition;
    private final CompletableFuture<Void> commandCompletion;

    SessionResult(
            Stream<StreamEvent> stream,
            CompletableFuture<List<Entry>> entries,
            CompletableFuture<RunStatus> status,
            String disposition) {
        this(stream, entries, status, disposition, null);
    }

    SessionResult(
            Stream<StreamEvent> stream,
            CompletableFuture<List<Entry>> entries,
            CompletableFuture<RunStatus> status,
            String disposition,
            CompletableFuture<Void> commandCompletion) {
        this.stream = stream;
        this.entries = entries;
        this.status = status;
        this.disposition = disposition;
        this.commandCompletion = commandCompletion;
    }

    /** Incremental events (single consumption, like any Java Stream). */
    public Stream<StreamEvent> stream() {
        return stream;
    }

    /** Full transcript entries once the run finishes (blocking). */
    public List<Entry> entries() {
        return entries.join();
    }

    /** Terminal run status (blocking). */
    public RunStatus status() {
        return status.join();
    }

    /** Completion signal for the run status (non-blocking). */
    public CompletableFuture<RunStatus> statusFuture() {
        return status;
    }

    /** Preflight disposition: {@code "started"} or {@code "queued"} (A5). */
    public String disposition() {
        return disposition;
    }

    /**
     * A queued prompt: no run was started — empty stream, pre-settled status
     * {@code (0, "queued")}, no entries (A5，{@code 原 docs/25}；pi 的
     * {@code preflightResult("queued")}，{@code agent-session.ts:1977}）。
     */
    public static SessionResult queued() {
        return new SessionResult(
            Stream.empty(),
            CompletableFuture.completedFuture(List.of()),
            CompletableFuture.completedFuture(new RunStatus(0, "queued")),
            "queued");
    }

    /**
     * pi {@code preflightResult("handled")}（agent-session.ts:1999-2002，docs/15）：
     * 扩展 {@code input} 事件已处置该输入 —— 无展开、无排队、无驱动。
     */
    public static SessionResult handled() {
        return new SessionResult(
            Stream.empty(),
            CompletableFuture.completedFuture(List.of()),
            CompletableFuture.completedFuture(new RunStatus(0, "handled")),
            "handled");
    }

    /**
     * 批 20 评审回补（I-1）：扩展命令臂的 handled——pi {@code prompt()} 是
     * {@code void} 异步语义（rpc-mode.ts:398 不 await 读线程），命令体在<b>独立
     * 虚拟线程</b>执行：同步 join 会让 RPC 读线程死等「只有自己能读」的
     * {@code extension_ui_response}（ctx.ui 对话 5 分钟超时），TUI 渲染线程亦被
     * 慢命令冻住。{@link #commandCompletion()} 在命令体结束时正常完成
     * （错误已路由 extension_error，不向外抛）——RPC 层借此把响应推迟到
     * 命令完成之后（pi preflightResult 时序对位）。
     */
    public static SessionResult handledBy(Runnable commandBody) {
        var completion = new CompletableFuture<Void>();
        Thread.startVirtualThread(() -> {
            try {
                commandBody.run();
                completion.complete(null);
            } catch (Throwable t) {
                completion.completeExceptionally(t);
            }
        });
        return new SessionResult(
            Stream.empty(),
            CompletableFuture.completedFuture(List.of()),
            CompletableFuture.completedFuture(new RunStatus(0, "handled")),
            "handled", completion);
    }

    /**
     * 扩展命令执行体的完成信号（仅 {@link #handledBy} 产物非 null）——
     * pi {@code preflightResult} 在命令完成后才发射的时序对位。
     */
    public CompletableFuture<Void> commandCompletion() {
        return commandCompletion;
    }
}
