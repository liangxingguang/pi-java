package com.pijava.tui.util;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Cross-thread event dispatch (Phase 3 design §11.1 thread model).
 *
 * <p>Stream events and entries arrive on the interactive-mode virtual thread;
 * the TUI render loop runs on the main thread. Producers enqueue tasks here
 * and {@link #drain()} runs them on the render thread each frame, so widget
 * state is only ever mutated on the render thread.</p>
 *
 * <p>An optional wake callback ({@link #setWake(Runnable)}) lets the regular
 * (raw-scrollback) mode render on demand: every enqueued task marks the shell
 * dirty, so streaming updates wake the idle loop without forcing continuous
 * redraws that would fight the terminal's native scrollback.</p>
 */
public final class TuiEventDispatcher {

    private final BlockingQueue<Runnable> pending = new LinkedBlockingQueue<>();
    private volatile Runnable wake;

    /** Enqueue a task to run on the render thread (thread-safe). */
    public void dispatch(Runnable task) {
        pending.add(task);
        var wake = this.wake;
        if (wake != null) {
            wake.run();
        }
    }

    /** Register a callback invoked whenever a task is enqueued (or null). */
    public void setWake(Runnable wake) {
        this.wake = wake;
    }

    /** Run all queued tasks on the calling (render) thread. */
    public void drain() {
        Runnable task;
        while ((task = pending.poll()) != null) {
            task.run();
        }
    }
}
