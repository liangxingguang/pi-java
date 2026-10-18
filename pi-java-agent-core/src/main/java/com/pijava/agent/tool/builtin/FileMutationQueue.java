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

    /**
     * pi 的模块级单例（{@code file-mutation-queue.ts} 的 {@code fileMutationQueues} 是
     * 模块顶层 Map）—— edit 与 write 共享**同一条**队列：同一规范路径上的 edit↔write
     * 组合也互斥。此前两个工具各持一条静态队列，edit/write 交错在彼此射程之外
     * （#81 审计挖出，docs/10 §10.1）。
     */
    private static final FileMutationQueue SHARED = new FileMutationQueue();

    /** pi {@code withFileMutationQueue} 背后的模块级单例。 */
    static FileMutationQueue shared() {
        return SHARED;
    }

    private final ConcurrentHashMap<String, CompletableFuture<Void>> queues = new ConcurrentHashMap<>();

    /** Serialize {@code fn} against prior mutations to the same file. */
    <T> T withQueue(String filePath, ThrowingSupplier<T> fn) throws Exception {
        String key = canonicalKey(filePath);
        KeyedQueue keyed = register(key);
        keyed.currentQueue().join();
        try {
            return fn.get();
        } finally {
            keyed.release().complete(null);
            queues.remove(keyed.key(), keyed.chained());
        }
    }

    /**
     * Chain this mutation onto the tail of the same-key queue. Synchronized so
     * two threads registering for the same key cannot both capture the same
     * head and run concurrently (pi's JS version is single-threaded and does
     * not need the guard).
     */
    private synchronized KeyedQueue register(String key) {
        CompletableFuture<Void> currentQueue = queues.getOrDefault(key,
            CompletableFuture.completedFuture(null));
        CompletableFuture<Void> release = new CompletableFuture<>();
        CompletableFuture<Void> chained = currentQueue.thenCompose(v -> release);
        queues.put(key, chained);
        return new KeyedQueue(key, currentQueue, chained, release);
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
