package com.pijava.ai.auth;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 21（docs/32 T2，E1-3）：读-改-写同锁的并发夹具——两实例交错写不丢更新
 *（pi {@code auth-storage.ts} {@code withLock(fn)} 的原子 RMW 对位）。
 */
class CredentialStoreConcurrencyTest {

    @TempDir
    Path dir;

    @Test
    void interleavedStoresLoseNoUpdates() throws Exception {
        var path = dir.resolve("auth.json");
        var storeA = new FileCredentialStore(path);
        var storeB = new FileCredentialStore(path);
        int rounds = 50;

        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<Future<?>>();
            futures.add(executor.submit(() -> {
                for (int i = 0; i < rounds; i++) {
                    storeA.storeApiKey("provider-a-" + i, "key-" + i);
                }
            }));
            futures.add(executor.submit(() -> {
                for (int i = 0; i < rounds; i++) {
                    storeB.storeApiKey("provider-b-" + i, "key-" + i);
                }
            }));
            for (var future : futures) {
                future.get();
            }
        }

        for (int i = 0; i < rounds; i++) {
            assertThat(storeA.resolveApiKey("provider-a-" + i)).contains("key-" + i);
            assertThat(storeA.resolveApiKey("provider-b-" + i)).contains("key-" + i);
        }
    }

    @Test
    void interleavedDeleteSurvives() throws Exception {
        var path = dir.resolve("auth.json");
        var storeA = new FileCredentialStore(path);
        var storeB = new FileCredentialStore(path);
        storeA.storeApiKey("keep", "v1");
        storeA.storeApiKey("drop", "v2");
        storeB.deleteApiKey("drop");
        assertThat(storeA.resolveApiKey("keep")).contains("v1");
        assertThat(storeA.resolveApiKey("drop")).isEmpty();
    }

    @Test
    void oauthStoreRmwIsAtomic() throws Exception {
        var path = dir.resolve("auth-oauth.json");
        var storeA = new OAuthCredentialStore(path);
        var storeB = new OAuthCredentialStore(path);
        int rounds = 30;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                for (int i = 0; i < rounds; i++) {
                    storeA.store("p-a-" + i, credential("ta" + i));
                }
            });
            var second = executor.submit(() -> {
                for (int i = 0; i < rounds; i++) {
                    storeB.store("p-b-" + i, credential("tb" + i));
                }
            });
            first.get();
            second.get();
        }
        for (int i = 0; i < rounds; i++) {
            int index = i;
            assertThat(storeA.resolve("p-a-" + index))
                .hasValueSatisfying(c -> assertThat(c.accessToken()).isEqualTo("ta" + index));
            assertThat(storeA.resolve("p-b-" + index))
                .hasValueSatisfying(c -> assertThat(c.accessToken()).isEqualTo("tb" + index));
        }
    }

    private static OAuthCredential credential(String token) {
        return new OAuthCredential(token, "refresh", 4_000_000_000L, null);
    }
}
