package com.pijava.tui.util;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * OSC 9;4 终端进度指示（E2-32）：pi {@code tui/src/terminal.ts:15-17,618-633}
 * {@code setProgress} 的 java 对位。
 *
 * <p>active 时写 {@code ESC ] 9;4;3 BEL} 并每 1 s 重发一次保活；停止时清定时器
 * 并写 {@code ESC ] 9;4;0 BEL}。是否启用由 {@link BooleanSupplier} 现读
 * settings {@code terminal.showTerminalProgress}（pi 每个事件点现查）。</p>
 */
public final class TerminalProgressReporter implements AutoCloseable {

    /** 不确定进度序列（pi {@code TERMINAL_PROGRESS_ACTIVE_SEQUENCE}）。 */
    public static final String ACTIVE_SEQUENCE = "\u001b]9;4;3\u0007";
    /** 清除进度序列（pi {@code TERMINAL_PROGRESS_CLEAR_SEQUENCE}）。 */
    public static final String CLEAR_SEQUENCE = "\u001b]9;4;0\u0007";
    /** 保活间隔（pi {@code TERMINAL_PROGRESS_KEEPALIVE_MS}）。 */
    static final long KEEPALIVE_MS = 1000;

    private final Consumer<String> sink;
    private final BooleanSupplier enabled;
    private final long keepaliveMs;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private ScheduledFuture<?> keepalive;
    private volatile boolean active;

    /** 生产构造：写 stdout 并刷出；启用位现读设置。 */
    public static TerminalProgressReporter stdout(BooleanSupplier enabled) {
        return new TerminalProgressReporter(sequence -> {
            System.out.print(sequence);
            System.out.flush();
        }, enabled);
    }

    /** 默认 1 s 保活、自有守护调度器。 */
    public TerminalProgressReporter(Consumer<String> sink, BooleanSupplier enabled) {
        this(sink, enabled, KEEPALIVE_MS, Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "pi-terminal-progress");
            thread.setDaemon(true);
            return thread;
        }), true);
    }

    /** 测试缝：注入保活间隔与调度器（不拥有调度器）。 */
    TerminalProgressReporter(Consumer<String> sink, BooleanSupplier enabled,
                             long keepaliveMs, ScheduledExecutorService scheduler) {
        this(sink, enabled, keepaliveMs, scheduler, false);
    }

    private TerminalProgressReporter(Consumer<String> sink, BooleanSupplier enabled,
                                     long keepaliveMs, ScheduledExecutorService scheduler,
                                     boolean ownsScheduler) {
        this.sink = sink;
        this.enabled = enabled;
        this.keepaliveMs = keepaliveMs;
        this.scheduler = scheduler;
        this.ownsScheduler = ownsScheduler;
    }

    /** 开始进度；设置关闭或已在运行时 no-op。 */
    public synchronized void start() {
        if (active || !enabled.getAsBoolean()) {
            return;
        }
        active = true;
        sink.accept(ACTIVE_SEQUENCE);
        keepalive = scheduler.scheduleAtFixedRate(() -> {
            if (active) {
                sink.accept(ACTIVE_SEQUENCE);
            }
        }, keepaliveMs, keepaliveMs, TimeUnit.MILLISECONDS);
    }

    /** 停止进度：清定时器并写清除序列；未在运行时 no-op。 */
    public synchronized void stop() {
        if (!active) {
            return;
        }
        active = false;
        if (keepalive != null) {
            keepalive.cancel(false);
            keepalive = null;
        }
        sink.accept(CLEAR_SEQUENCE);
    }

    /** 当前是否在报进度（测试观察点）。 */
    public boolean active() {
        return active;
    }

    @Override
    public void close() {
        stop();
        if (ownsScheduler) {
            scheduler.shutdownNow();
        }
    }
}
