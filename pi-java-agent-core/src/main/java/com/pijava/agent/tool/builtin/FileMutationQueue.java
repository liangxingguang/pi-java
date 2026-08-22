package com.pijava.agent.tool.builtin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Serialize file-mutation operations targeting the same canonical path
 * (pi {@code harness/tools/file-mutation-queue.ts}).
 *
 * <p>Tool calls of one turn execute in parallel on a virtual-thread executor,
 * so two edits to the same file can otherwise interleave (read-modify-write
 * lost updates). Mutations to the same canonical path are chained FIFO;
 * mutations to different files still run in parallel.</p>
 */
final class FileMutationQueue {

    private final ConcurrentHashMap<String, CompletableFuture<Void>> queues = new ConcurrentHashMap<>();
    private volatile CompletableFuture<Void> registration = CompletableFuture.completedFuture(null);

    /** Serialize {@code fn} against prior mutations to the same file. */
    <T> T withQueue(String filePath, ThrowingSupplier<T> fn) throws Exception {
        CompletableFuture<KeyedQueue> reg = registration.thenApply(ignored -> {
            String key = canonicalKey(filePath);
            CompletableFuture<Void> currentQueue = queues.getOrDefault(key,
                CompletableFuture.completedFuture(null));
            CompletableFuture<Void> release = new CompletableFuture<>();
            CompletableFuture<Void> chained = currentQueue.thenCompose(v -> release);
            queues.put(key, chained);
            return new KeyedQueue(key, currentQueue, chained, release);
        });
        registration = reg.thenApply(v -> (Void) null).exceptionally(e -> null);

        KeyedQueue keyed = reg.join();
        keyed.currentQueue().join();
        try {
            return fn.get();
        } finally {
            keyed.release().complete(null);
            queues.remove(keyed.key(), keyed.chained());
        }
    }

    /** Canonical mutation key: realpath when resolvable, else the normalized path. */
    private static String canonicalKey(String filePath) {
        Path path = Path.of(filePath).toAbsolutePath().normalize();
        try {
            return path.toRealPath().toString();
        } catch (IOException e) {
            return path.toString();
        }
    }

    /** The queue chain resolved for one mutation. */
    private record KeyedQueue(String key, CompletableFuture<Void> currentQueue,
                              CompletableFuture<Void> chained, CompletableFuture<Void> release) {
    }

    /** {@link java.util.function.Supplier} that may throw. */
    interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
