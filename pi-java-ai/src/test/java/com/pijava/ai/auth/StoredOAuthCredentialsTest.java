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

    /**
     * B231（pi commit {@code bde882c74} 两条 {@code persists an OAuth refresh that started
     * before …} 夹具的 java 版）：刷新已开始后调用方才被取消 ⇒ 调用方立刻收到失败，
     * 但刷新走完后<b>轮转后的凭证必须落盘</b>。
     */
    @Test
    void cancelledCallerStillPersistsRotatedCredential() throws Exception {
        store.store("anthropic", new OAuthCredential("old", "old-refresh", epochInSeconds(60), null));
        var rotated = new OAuthCredential("new", "new-refresh", epochInSeconds(3600), null);
        var refreshEntered = new CountDownLatch(1);
        var finishRefresh = new CountDownLatch(1);
        var callerRef = new Thread[1];
        OAuthTokenRefresher rotating = (provider, credential) -> {
            refreshEntered.countDown();
            // provider 在返回前已作废旧 refresh token —— 此刻调用方被取消。
            callerRef[0].interrupt();
            try {
                if (!finishRefresh.await(10, TimeUnit.SECONDS)) {
                    throw new java.io.IOException("timed out waiting for test gate");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException("interrupted while holding refresh gate", e);
            }
            return rotated;
        };
        var component = component(rotating);
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var interruptedFlag = new java.util.concurrent.atomic.AtomicBoolean();
        var caller = new Thread(() -> {
            try {
                component.resolveEffective("anthropic");
            } catch (OAuthRefreshException e) {
                failure.set(e);
                interruptedFlag.set(Thread.currentThread().isInterrupted());
            }
        });
        callerRef[0] = caller;

        caller.start();
        assertThat(refreshEntered.await(5, TimeUnit.SECONDS)).isTrue();
        caller.join(5000);
        assertThat(caller.isAlive()).isFalse();
        // 调用方以失败收场，中断位在抛出前重设。
        assertThat(failure.get()).isInstanceOf(OAuthRefreshException.class);
        assertThat(interruptedFlag).isTrue();
        // 注：不在此刻读盘断言旧凭证 —— worker 仍持有独占文件锁，Windows 下另一柄读取
        // 被锁拒（readAll 吞 IOException 回空 map，既有生产行为）；pi 夹具也只断言放行后。

        finishRefresh.countDown();
        // pi 的 vi.waitFor：轮询断言新凭证最终落盘。
        OAuthCredential persisted = null;
        for (int i = 0; i < 50; i++) {
            persisted = store.resolve("anthropic").orElse(null);
            if (persisted != null && "new-refresh".equals(persisted.refreshToken())) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(persisted).isEqualTo(rotated);
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
