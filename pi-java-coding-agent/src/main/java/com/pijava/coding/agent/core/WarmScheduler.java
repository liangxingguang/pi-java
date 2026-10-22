package com.pijava.coding.agent.core;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 批 13（docs/24 T13，裁决④）：{@link CacheWarmer} 的调度缝——pi 的
 * {@code setTimeout(refresh, delay)}（cache-warmer.ts:296）在 java 的注入形。
 *
 * <p>生产实现 {@link #virtualThreadDaemon()}：单线程守护
 * {@code ScheduledExecutorService} 计时（≙ pi 的 {@code .unref()}——不吊住进程），
 * 触发后任务本体在<b>虚拟线程</b>上跑（暖重放的 decide/stream 是阻塞调用，
 * 不占地调度线程）。全 JVM 共享一个调度线程（每会话一个会随 fork 漏线程）。</p>
 *
 * <p>测试实现＝手动队列＋手动时钟（翻译 pi {@code vi.useFakeTimers} 的前提，
 * 见 {@code ManualWarmScheduler}）。</p>
 */
public interface WarmScheduler {

    /** 已排任务的取消句柄（pi {@code clearTimeout} 对位）；幂等。 */
    interface Cancellable {
        /** 取消；已触发/已取消后再调是 no-op。 */
        void cancel();
    }

    /**
     * 在 {@code delayMs} 毫秒后触发 {@code task}（触发时刻允许迟到——
     * {@link CacheWarmer} 的 refresh deadline 机制按 pi 逐字兜住迟到刷新）。
     *
     * @return 取消句柄
     */
    Cancellable schedule(Runnable task, long delayMs);

    /** 生产实现：守护调度线程＋虚拟线程执行体（全 JVM 共享懒单例）。 */
    static WarmScheduler virtualThreadDaemon() {
        return Shared.INSTANCE;
    }

    /** 共享单例 holder（类加载懒初始化）。 */
    final class Shared {
        private static final WarmScheduler INSTANCE = create();

        private Shared() {}

        private static WarmScheduler create() {
            var executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                var thread = new Thread(runnable, "cache-warmer-scheduler");
                thread.setDaemon(true);
                return thread;
            });
            return (task, delayMs) -> {
                var future = executor.schedule(
                    () -> Thread.ofVirtual().name("cache-warmer-refresh").start(task),
                    Math.max(0, delayMs), TimeUnit.MILLISECONDS);
                return () -> future.cancel(false);
            };
        }
    }
}
