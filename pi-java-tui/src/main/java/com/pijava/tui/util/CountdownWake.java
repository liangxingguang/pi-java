package com.pijava.tui.util;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * inline（raw-scrollback）模式的倒计时唤醒源 —— pi {@code CountdownTimer} 每秒
 * {@code requestRender}（{@code components/countdown-timer.ts:14-31}）的等价物。
 *
 * <p>两种模式的节奏本来就不同（docs/31 §8.38.4 裁决点 B）：fullscreen 由
 * {@code ToolkitRunner.tickRate(33ms)} 持续整帧重绘，倒计时按帧从截止时刻重算即可、
 * <b>不需要</b>定时器；inline 只在 {@code dirty} 时渲染
 * （{@link InlineTuiShell#markDirty()}）⇒ 必须有东西每秒把它弄脏。</p>
 *
 * <p>只在含倒计时的指示器存活期间唤醒（{@code active} 为假时不做任何事），
 * 因此空闲时不会与终端的原生 scrollback 抢重绘。</p>
 */
public final class CountdownWake implements AutoCloseable {

    private static final Duration PERIOD = Duration.ofSeconds(1);

    private final ScheduledExecutorService scheduler;

    private CountdownWake(ScheduledExecutorService scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * 启动每秒一次的唤醒。
     *
     * @param active 为真时才执行 {@code wake}
     * @param wake   唤醒动作（inline 模式即 {@code shell::markDirty}）
     * @return 可关闭的唤醒源
     */
    public static CountdownWake start(BooleanSupplier active, Runnable wake) {
        var scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "pi-tui-countdown");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleAtFixedRate(() -> {
            if (active.getAsBoolean()) {
                wake.run();
            }
        }, PERIOD.toMillis(), PERIOD.toMillis(), TimeUnit.MILLISECONDS);
        return new CountdownWake(scheduler);
    }

    /** 停止唤醒（幂等）。 */
    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
