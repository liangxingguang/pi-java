package com.pijava.ai.auth;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 包 B1（{@code 原 docs/63}）：{@link StoredOAuthCredentials} —— 5 分钟临期主动刷新、
 * 锁内 double-checked 单次刷新、失败不静默回落。
 */
class StoredOAuthCredentialsTest {

    @TempDir
    Path tmp;

    private OAuthCredentialStore store;
    private final AtomicInteger refreshCount = new AtomicInteger();

    @BeforeEach
    void setUp() {
        store = new OAuthCredentialStore(tmp.resolve("auth-oauth.json"));
    }

    private static long epochInSeconds(long secondsFromNow) {
        return Instant.now().getEpochSecond() + secondsFromNow;
    }

    private StoredOAuthCredentials component(OAuthTokenRefresher refresher) {
        return new StoredOAuthCredentials(store, refresher);
    }

    private OAuthTokenRefresher returning(OAuthCredential result) {
        return (provider, credential) -> {
            refreshCount.incrementAndGet();
            return result;
        };
    }

    @Test
    void returnsFreshCredentialWithoutRefresh() {
        var credential = new OAuthCredential("access", "r", epochInSeconds(3600), null);
        store.store("anthropic", credential);

        var resolved = component(returning(new OAuthCredential("x", "r", 0, null)))
            .resolveEffective("anthropic");

        assertThat(resolved).hasValue(credential);
        assertThat(refreshCount).hasValue(0);
    }

    @Test
    void proactivelyRefreshesWhenLessThanFiveMinutesLeft() {
        // 3 分钟后过期 ⇒ 临期（不必硬过期）⇒ 主动刷新。
        store.store("anthropic", new OAuthCredential("old", "r", epochInSeconds(180), null));
        var rotated = new OAuthCredential("new", "r2", epochInSeconds(3600), null);

        var resolved = component(returning(rotated)).resolveEffective("anthropic");

        assertThat(resolved).hasValue(rotated);
        assertThat(store.resolve("anthropic")).hasValue(rotated);
        assertThat(refreshCount).hasValue(1);
    }

    @Test
    void refreshesWhenHardExpired() {
        store.store("anthropic", new OAuthCredential("old", "r", epochInSeconds(-60), null));
        var rotated = new OAuthCredential("new", "r2", epochInSeconds(3600), null);

        assertThat(component(returning(rotated)).resolveEffective("anthropic")).hasValue(rotated);
        assertThat(refreshCount).hasValue(1);
    }

    @Test
    void permanentCredentialIsNeverRefreshed() {
        store.store("openrouter", OAuthCredential.permanent("pk-123"));

        var resolved = component(returning(new OAuthCredential("x", "", 0, null)))
            .resolveEffective("openrouter");

        assertThat(resolved).hasValue(OAuthCredential.permanent("pk-123"));
        assertThat(refreshCount).hasValue(0);
    }

    @Test
    void refreshFailureThrowsAndDoesNotFallback() {
        store.store("anthropic", new OAuthCredential("old", "r", epochInSeconds(60), null));
        OAuthTokenRefresher failing = (provider, credential) -> {
            refreshCount.incrementAndGet();
            throw new java.io.IOException("refresh endpoint 500");
        };

        assertThatThrownBy(() -> component(failing).resolveEffective("anthropic"))
            .isInstanceOf(OAuthRefreshException.class)
            .hasMessageContaining("anthropic");
        // store 仍是原凭证（没被静默改写；回落由上层一律不做）。
        assertThat(store.resolve("anthropic").orElseThrow().accessToken()).isEqualTo("old");
    }

    @Test
    void concurrentRequestsTriggerASingleRefresh() throws Exception {
        store.store("anthropic", new OAuthCredential("old", "r", epochInSeconds(60), null));
        var rotated = new OAuthCredential("new", "r2", epochInSeconds(3600), null);
        var refreshEntered = new CountDownLatch(1);
        var gate = new CountDownLatch(1);
        OAuthTokenRefresher gated = (provider, credential) -> {
            refreshCount.incrementAndGet();
            refreshEntered.countDown();
            try {
                if (!gate.await(5, TimeUnit.SECONDS)) {
                    throw new java.io.IOException("timed out waiting for test gate");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException("interrupted while holding refresh gate", e);
            }
            return rotated;
        };
        var component = component(gated);
        var start = new CountDownLatch(1);
        var results = new ArrayBlockingQueue<OAuthCredential>(2);
        Runnable task = () -> {
            try {
                start.await();
                results.add(component.resolveEffective("anthropic").orElseThrow());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        var first = new Thread(task);
        var second = new Thread(task);
        first.start();
        second.start();
        start.countDown();

        // 第一请求已持锁进入刷新；等第二请求确实堵在锁上再放行。
        assertThat(refreshEntered.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(300);
        gate.countDown();
        first.join(5000);
        second.join(5000);

        assertThat(refreshCount).hasValue(1);
        assertThat(results).containsExactly(rotated, rotated);
        assertThat(store.resolve("anthropic")).hasValue(rotated);
    }

    @Test
    void nearExpiryBoundary() {
        assertThat(StoredOAuthCredentials.nearExpiry(
            new OAuthCredential("a", "r", epochInSeconds(300), null))).isTrue();
        assertThat(StoredOAuthCredentials.nearExpiry(
            new OAuthCredential("a", "r", epochInSeconds(301), null))).isFalse();
        assertThat(StoredOAuthCredentials.nearExpiry(OAuthCredential.permanent("a"))).isFalse();
    }
}
