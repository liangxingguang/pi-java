package com.pijava.mcp.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code FileAuthStorageBackend}（{@code auth-storage.ts:49-114}）。
 *
 * <p>⚠️ 本夹具是包⑪ 补的：包⑨ 的凭据 store 测试用的是内存后端，文件后端**从未被跑过**
 * —— 第一次真消费（{@code mcp list}）就撞上 Windows 的整文件字节锁拒绝跨句柄读。</p>
 */
class FileAuthJsonBackendTest {

    @TempDir
    Path root;

    @Test
    void createsTheFileWithAnEmptyObject() {
        var path = root.resolve("agent").resolve("mcp-auth.json");
        var current = new FileAuthJsonBackend(path)
                .withLock(content -> AuthJsonBackend.LockResult.of(content));
        assertThat(current).isEqualTo("{}");
        assertThat(Files.exists(path)).isTrue();
    }

    @Test
    void readsAndWritesThroughTheLockedChannel() throws IOException {
        var path = root.resolve("mcp-auth.json");
        var backend = new FileAuthJsonBackend(path);

        backend.withLock(current -> {
            assertThat(current).isEqualTo("{}");
            return AuthJsonBackend.LockResult.writing(null, "{\"a\": 1}");
        });
        assertThat(Files.readString(path, StandardCharsets.UTF_8)).isEqualTo("{\"a\": 1}");

        // A second pass must see what the first wrote: this is the read that used to fail with
        // a lock violation on Windows.
        var second = backend.withLock(content -> AuthJsonBackend.LockResult.of(content));
        assertThat(second).isEqualTo("{\"a\": 1}");
    }

    @Test
    void anEditThatLeavesTheFileAloneDoesNotTruncateIt() throws IOException {
        var path = root.resolve("mcp-auth.json");
        Files.writeString(path, "{\"keep\": true}", StandardCharsets.UTF_8);

        new FileAuthJsonBackend(path).withLock(current -> AuthJsonBackend.LockResult.of(null));

        assertThat(Files.readString(path, StandardCharsets.UTF_8)).isEqualTo("{\"keep\": true}");
    }

    @Test
    void aShorterWritesDoesNotLeaveTheOldTail() throws IOException {
        var path = root.resolve("mcp-auth.json");
        Files.writeString(path, "{\"a\": \"aaaaaaaaaa\"}", StandardCharsets.UTF_8);

        new FileAuthJsonBackend(path).withLock(current -> AuthJsonBackend.LockResult.writing(null, "{}"));

        assertThat(Files.readString(path, StandardCharsets.UTF_8)).isEqualTo("{}");
    }

    @Test
    void threadsTakeTurns() {
        var path = root.resolve("mcp-auth.json");
        var backend = new FileAuthJsonBackend(path);
        backend.withLock(current -> AuthJsonBackend.LockResult.writing(null, "{\"n\": 0}"));

        var seen = new java.util.ArrayList<Integer>();
        var threads = new java.util.ArrayList<Thread>();
        for (var index = 0; index < 8; index++) {
            var thread = Thread.ofVirtual().unstarted(() -> {
                for (var round = 0; round < 5; round++) {
                    backend.withLock(current -> {
                        var next = Integer.parseInt(current.replaceAll("[^0-9]", ""));
                        seen.add(next);
                        return AuthJsonBackend.LockResult.writing(null, "{\"n\": " + (next + 1) + "}");
                    });
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (var thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        assertThat(seen).hasSize(40);
        assertThat(seen).isSorted();
        assertThat(seen.get(seen.size() - 1)).isEqualTo(39);
    }

    @Test
    void aWholeCredentialStoreRoundTripsOnDisk() {
        // The shape the CLI actually uses: store -> server view -> load/save through the file
        // backend, which is what failed before the read moved onto the locked channel.
        var store = new McpOAuthCredentialStore(root.resolve("agent"));
        assertThat(store.forServer("files", "http://x/mcp").load()).isNull();
        store.forServer("files", "http://x/mcp").save(
                new com.pijava.mcp.oauth.McpOAuthState("http://x/mcp", null, null, 1L, null, null, null));
        assertThat(store.forServer("files", "http://x/mcp").load().tokensExpireAt()).isEqualTo(1L);
        assertThat(store.tokens("files", "http://x/mcp")).isNull();
        assertThat(store.remove("files", "http://x/mcp")).isTrue();
    }
}
