package com.pijava.tui.util;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * inline（raw-scrollback）模式的指示器唤醒源 —— pi 指示器的定时重绘等价物。
 *
 * <p>两种模式的节奏本来就不同（原 docs/31 §8.38.4 裁决点 B）：fullscreen 由
 * {@code ToolkitRunner.tickRate(33ms)} 持续整帧重绘，倒计时/spinner 按帧从时刻
 * 重算即可、<b>不需要</b>定时器；inline 只在 {@code dirty} 时渲染
 * （{@link InlineTuiShell#markDirty()}）⇒ 必须有东西周期性把它弄脏。</p>
 *
 * <p>L7 W1（docs/22 §4.4）：周期 1s → 80ms（pi {@code Loader} 的
 * {@code DEFAULT_INTERVAL_MS}）——pi 四种状态指示器全是 {@code Loader} 子类，
 * 均带 80ms spinner ⇒ inline 期间 12.5Hz 重绘本就是 pi 行为；retry 倒计时（1s
 * 分辨率）按帧重算取值不变。只在指示器存活期间唤醒（{@code active} 为假时不做
 * 任何事），空闲时不会与终端的原生 scrollback 抢重绘。</p>
 */
public final class CountdownWake implements AutoCloseable {

    private static final Duration PERIOD = Duration.ofMillis(80);

    private final ScheduledExecutorService scheduler;

    private CountdownWake(ScheduledExecutorService scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * 启动周期唤醒（80ms，pi Loader interval 对位）。
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
