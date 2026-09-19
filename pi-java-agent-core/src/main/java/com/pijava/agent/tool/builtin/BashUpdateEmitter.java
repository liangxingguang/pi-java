package com.pijava.agent.tool.builtin;

import java.util.List;
import java.util.function.LongSupplier;

import com.pijava.agent.tool.ShellOutputSink;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.TruncationUtils;
import com.pijava.agent.tool.builtin.BashTool.BashDetails;
import com.pijava.ai.message.ContentBlock;

/**
 * 把 shell 的增量输出**节流**成 pi 的 {@code tool_execution_update} 载荷
 * （包⑧，{@code docs/35}）。逐位对齐 pi {@code bash.ts:255-314} 的三个状态：
 * {@code updateDirty} / {@code lastUpdateAt} / {@code updateTimer}。
 *
 * <h3>三条语义（都照 pi）</h3>
 * <ul>
 *   <li><b>起手先发一条空载荷</b>（{@code bash.ts:296-298}）：{@code content} 是
 *       <b>空数组</b>、{@code details} 为 null，在任何进程被拉起<b>之前</b>。
 *       ⚠️ 它**不**更新 {@code lastUpdateAt} —— 所以第一块真实输出总走前沿立即发。</li>
 *   <li><b>节流 100 ms，且是节流不是防抖</b>（{@code bash.ts:281-294}）：距上次 ≥100 ms
 *       就立即发（前沿）；否则把定时器挂上、到点发一次合并后的快照（尾沿）。
 *       两条边都可能发生。</li>
 *   <li><b>载荷是累积快照</b>（{@code bash.ts:264}）：官方文档原文
 *       {@code docs/rpc.md:1055}「accumulated output so far (not just the delta)」，
 *       消费者<b>整块替换</b>（{@code interactive-mode.ts:3348-3353}）。</li>
 * </ul>
 *
 * <p>快照的截断口径用 <b>pi 的值</b>（2000 行 / 51200 字节，{@code truncate.ts:11-12}），
 * 而不是 pi-java 的 {@code TruncationUtils.DEFAULT_MAX_BYTES}（100_000）—— 因为这是
 * <b>载荷快照</b>的口径；终局截断仍走 pi-java 现值（{@code docs/35 §6-2}）。</p>
 *
 * <p>时钟与调度器<b>可注入</b>：节流语义靠「等 100 ms 再看」是运气断言，夹具用假时钟
 * 与假调度器把每条边钉死（{@code docs/31 §8.23.8 ⑥} 的口径）。</p>
 */
final class BashUpdateEmitter implements ShellOutputSink {

    /** 挂尾沿定时器的能力；抽出来是为了让夹具确定性地驱动。 */
    @FunctionalInterface
    interface Scheduler {
        /**
         * 在 {@code delayMs} 毫秒后执行一次 {@code task}。
         *
         * @return 取消句柄（调用它即撤销）
         */
        Runnable schedule(Runnable task, long delayMs);
    }

    /** pi 的 {@code BASH_UPDATE_THROTTLE_MS}（{@code renderers/bash.ts:19}）。 */
    static final long THROTTLE_MS = 100L;

    /**
     * 生产用的调度器 —— pi 的 {@code setTimeout} 对应物。
     *
     * <p>一条**守护**单线程：任务只是「发一帧」，极短；共用一个定时线程即可，
     * 不必每次执行起一个。</p>
     *
     * <p>⚠️ 这把线程会调进 {@code onUpdate} ⇒ 最终进 {@code PiLaneSink.emit} 的
     * {@code synchronized}。与工具线程**只是互斥、不会死锁**：工具线程在
     * {@code future.get()} 上等子进程时不持有那把锁，谁都不在持锁时等对方
     * （{@code docs/35 §8.0 裁决 B} 的 ⚠️）。</p>
     */
    static Scheduler defaultScheduler() {
        return (task, delayMs) -> {
            var future = TIMER.schedule(task, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        };
    }

    private static final java.util.concurrent.ScheduledExecutorService TIMER =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r, "bash-update-timer");
            thread.setDaemon(true);
            return thread;
        });

    /** pi 的快照上限（{@code truncate.ts:11-12}）——**不是** pi-java 的默认值。 */
    private static final int SNAPSHOT_MAX_LINES = 2000;
    private static final long SNAPSHOT_MAX_BYTES = 51200L;

    private final com.pijava.agent.tool.ToolUpdateCallback<BashDetails> onUpdate;
    private final long throttleMs;
    private final LongSupplier nowNanos;
    private final Scheduler scheduler;

    private final StringBuilder accumulated = new StringBuilder();
    private boolean dirty;
    private long lastUpdateNanos;
    private Runnable cancelTrailing;

    BashUpdateEmitter(com.pijava.agent.tool.ToolUpdateCallback<BashDetails> onUpdate,
                      long throttleMs, LongSupplier nowNanos, Scheduler scheduler) {
        this.onUpdate = onUpdate;
        this.throttleMs = throttleMs;
        this.nowNanos = nowNanos;
        this.scheduler = scheduler;
    }

    /**
     * 起手那条空载荷（pi {@code bash.ts:296-298}）。
     * <b>刻意不更新 {@code lastUpdateNanos}</b> —— 与 pi 同（它的 {@code lastUpdateAt}
     * 初值 0，故第一块真实输出总走前沿）。
     */
    void start() {
        emit(List.of(), null);
    }

    @Override
    public void onOutput(String delta) {
        accumulated.append(delta);
        dirty = true;
        long elapsedMs = (nowNanos.getAsLong() - lastUpdateNanos) / 1_000_000L;
        long delay = throttleMs - elapsedMs;
        if (delay <= 0) {
            cancelTrailing();
            emitNow();
        } else if (cancelTrailing == null) {
            cancelTrailing = scheduler.schedule(this::fireTrailing, delay);
        }
    }

    /** 收尾补冲一次并撤掉尾沿定时器（pi 的 {@code finishOutput}，{@code bash.ts:306-314}）。 */
    void flush() {
        cancelTrailing();
        emitNow();
    }

    /** 只撤定时器、不发（pi {@code finally} 里的 {@code clearUpdateTimer()}，{@code bash.ts:367-369}）。 */
    void disarm() {
        cancelTrailing();
    }

    // ── 内部 ────────────────────────────────────────────────────────

    /** 尾沿：pi 的 {@code setTimeout(() => { updateTimer = undefined; emitOutputUpdate(); }) }。 */
    private void fireTrailing() {
        cancelTrailing = null;
        emitNow();
    }

    private void cancelTrailing() {
        if (cancelTrailing != null) {
            cancelTrailing.run();
            cancelTrailing = null;
        }
    }

    /** pi 的 {@code emitOutputUpdate}：{@code !onUpdate || !updateDirty} 时不发。 */
    private void emitNow() {
        if (!dirty) {
            return;
        }
        dirty = false;
        lastUpdateNanos = nowNanos.getAsLong();
        var truncation = TruncationUtils.truncateTail(
            accumulated.toString(), SNAPSHOT_MAX_LINES, SNAPSHOT_MAX_BYTES);
        // pi：truncation 未发生时被显式赋 undefined ⇒ 键在、值 undefined ⇒ 线上省略；
        // 这里用 null，包⑦ 的 toolPayload 投影同样省掉该键（同形）。
        var details = truncation.truncated()
            ? new BashDetails(truncation, null)
            : null;
        emit(List.of(new ContentBlock.TextContent(truncation.content())), details);
    }

    private void emit(List<ContentBlock> content, BashDetails details) {
        onUpdate.onUpdate(new ToolResult<>(content, details, null, false, List.of()));
    }
}
