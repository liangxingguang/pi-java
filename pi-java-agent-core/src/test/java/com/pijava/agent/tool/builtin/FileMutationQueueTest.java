package com.pijava.agent.tool.builtin;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-canonical-path FIFO serialization (pi {@code file-mutation-queue.ts}).
 */
class FileMutationQueueTest {

    @Test
    void serializesConcurrentMutationsToSamePath() throws Exception {
        var queue = new FileMutationQueue();
        var result = new StringBuilder();
        var futures = new ArrayList<CompletableFuture<Void>>();
        for (int i = 0; i < 10; i++) {
            final int n = i;
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    queue.withQueue("same.txt", () -> {
                        result.append('[');
                        Thread.sleep(5);
                        result.append(n);
                        Thread.sleep(5);
                        result.append(']');
                        return null;
                    });
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        // Each "[n]" block must be contiguous — mutations never interleave.
        assertThat(result.toString()).matches("(\\[\\d\\]){10}");
    }

    @Test
    void differentPathsRunInParallel() throws Exception {
        var queue = new FileMutationQueue();
        var a = new CompletableFuture<String>();
        var b = new CompletableFuture<String>();
        var t1 = new Thread(() -> run(queue, "a.txt", a));
        var t2 = new Thread(() -> run(queue, "b.txt", b));
        t1.start();
        t2.start();
        assertThat(a.get(200, TimeUnit.MILLISECONDS)).isEqualTo("a");
        assertThat(b.get(200, TimeUnit.MILLISECONDS)).isEqualTo("b");
        t1.join();
        t2.join();
    }

    private static void run(FileMutationQueue queue, String path, CompletableFuture<String> out) {
        try {
            String value = queue.withQueue(path, () -> {
                Thread.sleep(50);
                return path.startsWith("a") ? "a" : "b";
            });
            out.complete(value);
        } catch (Exception e) {
            out.completeExceptionally(e);
        }
    }
}
