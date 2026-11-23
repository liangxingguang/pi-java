package com.pijava.coding.agent.core;

/**
 * One-shot cleanup on process termination (pi print/rpc SIGTERM handling,
 * {@code print-mode.ts:56-70} / {@code rpc-mode.ts:380-402}, L5 W5).
 *
 * <p>Java has no public signal API ({@code sun.misc.Signal} is outside the
 * {@code --release} export surface), so a JVM shutdown hook performs the first
 * signal's cleanup. Repeating a signal to force-exit is a registered narrow
 * difference (D, F2).</p>
 */
public final class SessionShutdownHook {

    private SessionShutdownHook() {}

    /**
     * Install a hook that closes {@code session} once.
     *
     * @return a handle that removes the hook when the mode already closed the session
     */
    public static AutoCloseable install(AgentSession session) {
        var hook = new Thread(() -> {
            try {
                session.close();
            } catch (RuntimeException ignored) {
                // Termination must not fail the shutdown sequence.
            }
        }, "pi-java-session-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        return () -> {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException ignored) {
                // The JVM is already shutting down; the hook will run.
            }
        };
    }
}
