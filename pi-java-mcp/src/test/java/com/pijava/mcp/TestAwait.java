package com.pijava.mcp;

import java.util.function.BooleanSupplier;

/** Tiny polling waiter for asynchronous test effects. */
public final class TestAwait {

    private TestAwait() {
    }

    /** Wait until {@code condition} is true, polling every 5 ms. */
    public static void waitFor(BooleanSupplier condition, String description) {
        waitFor(condition, description, 5_000);
    }

    /** Wait until true with an explicit timeout. */
    public static void waitFor(BooleanSupplier condition, String description, long timeoutMs) {
        var deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted waiting for: " + description);
            }
        }
        if (condition.getAsBoolean()) {
            return;
        }
        throw new AssertionError("timed out waiting for: " + description);
    }

    /** Wait for a latch, wrapping interruption. */
    public static void await(java.util.concurrent.CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(error);
        }
    }

    /** Timed latch wait, wrapping interruption. */
    public static boolean await(java.util.concurrent.CountDownLatch latch,
                         long timeout, java.util.concurrent.TimeUnit unit) {
        try {
            return latch.await(timeout, unit);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(error);
        }
    }

    /** Sleep {@code ms}, best effort. */
    public static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted");
        }
    }
}
