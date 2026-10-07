package com.pijava.mcp;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Request timeout scheduler: one daemon virtual-thread timer shared by all
 * clients (the JS {@code setTimeout}/{@code clearTimeout} equivalent).
 */
final class McpClientSchedules {

    static final ScheduledExecutorService SCHEDULER =
            Executors.newScheduledThreadPool(1, Thread.ofVirtual().factory());

    private McpClientSchedules() {
    }

    /** Run {@code task} after {@code ms} milliseconds. */
    static ScheduledFuture<?> schedule(long ms, Runnable task) {
        return SCHEDULER.schedule(task, ms, TimeUnit.MILLISECONDS);
    }
}
