package com.pijava.coding.agent.core;

import java.util.PriorityQueue;
import java.util.function.LongSupplier;

/**
 * 批 13 测试基建（docs/24 T13，裁决④）：{@link WarmScheduler} 的手动形——
 * {@link #advance(long)} 推进内部时钟并<b>同步</b>触发到期任务（pi
 * {@code vi.useFakeTimers} 的对位）。{@link #clock()} 供被测体当 {@code nowMs} 用，
 * 调度时间与时钟同一根轴，测试无需两边对齐。
 *
 * <p>任务在触发线程上同步执行：暖重放的 decide/stream 都是阻塞调用，同步形让
 * 断言点确定（advance 返回即 refresh 落定）；需要 in-flight 态的用例（替换 abort）
 * 在另一个线程里调 advance。</p>
 */
final class ManualWarmScheduler implements WarmScheduler {

    private final PriorityQueue<Task> queue = new PriorityQueue<>();
    private long now;
    private long seq;

    /** 内部时钟视图（被测体的 {@code nowMs}）。 */
    LongSupplier clock() {
        return () -> now;
    }

    @Override
    public synchronized Cancellable schedule(Runnable task, long delayMs) {
        var entry = new Task(now + Math.max(0, delayMs), seq++, task);
        queue.add(entry);
        return () -> {
            synchronized (this) {
                queue.remove(entry);
            }
        };
    }

    /** 推进 {@code ms} 毫秒；到期任务按到期序同步跑完（跑中新排的任务到点也跑）。 */
    public void advance(long ms) {
        now += ms;
        while (true) {
            Task due;
            synchronized (this) {
                due = queue.peek();
                if (due == null || due.dueAt > now) {
                    return;
                }
                queue.poll();
            }
            due.runnable.run();
        }
    }

    private static final class Task implements Comparable<Task> {
        final long dueAt;
        final long order;
        final Runnable runnable;

        Task(long dueAt, long order, Runnable runnable) {
            this.dueAt = dueAt;
            this.order = order;
            this.runnable = runnable;
        }

        @Override
        public int compareTo(Task other) {
            int byDue = Long.compare(dueAt, other.dueAt);
            return byDue != 0 ? byDue : Long.compare(order, other.order);
        }
    }
}
