package com.pijava.coding.agent.codemode;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import com.pijava.ai.AbortSignal;

/**
 * Process-wide codemode worker slots, shared by every session.
 *
 * <p>Mirrors {@code execute.ts:57-111}: at most {@link #maxWorkers()} scripts run at once,
 * waiters are granted slots in arrival order, and a waiter gives up when cancelled or when its
 * wait budget expires. A slot released while a cancelled VM is still exiting allows the next
 * script to start, matching pi's "one replacement" behavior.</p>
 */
final class CodemodeWorkers {
    static final int DEFAULT_MAX_WORKERS = 2;
    /** How long a script waits for a free slot before failing. */
    static final long WAIT_MS = 60_000L;

    /** Why a reservation attempt ended. */
    enum Admission {
        RESERVED,
        ABORTED,
        TIMEOUT
    }

    private static final ReentrantLock LOCK = new ReentrantLock(true);
    private static final Condition FREE = LOCK.newCondition();
    private static int active;

    private CodemodeWorkers() {
    }

    /** Returns {@code PI_CODEMODE_MAX_WORKERS} when it is a positive integer, else the default. */
    static int maxWorkers() {
        String value = System.getenv("PI_CODEMODE_MAX_WORKERS");
        if (value != null) {
            try {
                int parsed = Integer.parseInt(value.trim());
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // fall through to the default
            }
        }
        return DEFAULT_MAX_WORKERS;
    }

    /**
     * Reserves one worker slot, waiting in arrival order while all are taken.
     *
     * @param signal cancellation signal, may be {@code null}
     * @param waitMs how long to wait before giving up
     * @return why the reservation ended
     */
    static Admission reserve(AbortSignal signal, long waitMs) {
        if (signal != null && signal.isAborted()) {
            return Admission.ABORTED;
        }
        Runnable unsubscribe = signal == null
                ? () -> { }
                : signal.onAbort(CodemodeWorkers::wakeWaiters);
        long remaining = TimeUnit.MILLISECONDS.toNanos(Math.max(0, waitMs));
        LOCK.lock();
        try {
            while (true) {
                if (signal != null && signal.isAborted()) {
                    return Admission.ABORTED;
                }
                if (active < maxWorkers()) {
                    active++;
                    return Admission.RESERVED;
                }
                if (remaining <= 0) {
                    return Admission.TIMEOUT;
                }
                remaining = FREE.awaitNanos(remaining);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Admission.ABORTED;
        } finally {
            LOCK.unlock();
            unsubscribe.run();
        }
    }

    /** Releases one previously reserved slot and wakes waiters in arrival order. */
    static void release() {
        LOCK.lock();
        try {
            if (active > 0) {
                active--;
            }
            FREE.signalAll();
        } finally {
            LOCK.unlock();
        }
    }

    private static void wakeWaiters() {
        LOCK.lock();
        try {
            FREE.signalAll();
        } finally {
            LOCK.unlock();
        }
    }
}
