package com.pijava.coding.agent.extension.mcp;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 批 24（docs/35）：一次会话生命周期的作用域（pi 扩展内 {@code session =
 * new AbortController()} 的对位，{@code index.ts:307-313}）——一次性对象：
 * 初始实例为 abort 态（{@code session.abort()} 逐字），每次
 * {@code session_start} 由扩展换新实例（pi 换新 controller 对位）。
 * {@code session_shutdown} 时 abort；开始后启动的工作捕获当时的作用域，
 * abort 后丢弃晚到的结果；活过启动它的命令的工作登记进 {@link #track}，
 * shutdown 得以等其清理。
 */
final class McpSessionScope {

    private final AtomicBoolean aborted;
    private final Set<CompletableFuture<?>> backgroundActions =
        ConcurrentHashMap.newKeySet();
    /** abort 时完成——等待链经 {@code anyOf(all, abortWatch)} 解封（pi 的 signal 竞速对位）。 */
    private final CompletableFuture<Void> abortWatch = new CompletableFuture<>();

    private McpSessionScope(boolean aborted) {
        this.aborted = new AtomicBoolean(aborted);
    }

    /** pi 初始态：{@code new AbortController()} 随即 {@code abort()}。 */
    static McpSessionScope abortedScope() {
        return new McpSessionScope(true);
    }

    /** pi {@code session_start} 的新 controller（活跃态）。 */
    static McpSessionScope active() {
        return new McpSessionScope(false);
    }

    boolean isAborted() {
        return aborted.get();
    }

    /** abort 观察件（{@code Promise.race(work, abort)} 的 java 形）。 */
    CompletableFuture<Void> abortWatch() {
        return abortWatch;
    }

    /** 登记活过启动命令的工作（管理器动作的连接开/关、登录）；返回原任务。 */
    <T> CompletableFuture<T> track(CompletableFuture<T> work) {
        var task = work.handle((ok, err) -> null);
        backgroundActions.add(task);
        task.whenComplete((ok, err) -> backgroundActions.remove(task));
        return work;
    }

    /** abort（pi {@code session.abort()}）——解封所有等待链。 */
    void abort() {
        aborted.set(true);
        abortWatch.complete(null);
    }

    /** {@code session_shutdown}：abort＋等全部登记动作收尾（pi {@code Promise.all} 对位）。 */
    void abortAndAwait() {
        abort();
        for (var task : backgroundActions.toArray(CompletableFuture<?>[]::new)) {
            task.join();
        }
    }
}
