package com.pijava.ai.auth;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * 解析「stored OAuth → 有效凭证」（包 B1，{@code 原 docs/63}）—— 请求路径与 auth 命令共用。
 *
 * <p>对齐 pi {@code auth/resolve.ts:127-179} {@code resolveStoredOAuth}：</p>
 * <ul>
 *   <li>读取 {@link OAuthCredentialStore}；无则 empty（交由后续 ambient 层）；</li>
 *   <li><b>剩余有效期 ≤ 5 分钟即临期</b>（{@code DEFAULT_OAUTH_MINIMUM_VALIDITY_MS}），
 *       不必等到硬过期；</li>
 *   <li>读当前值与刷新都在 store 的文件锁（{@link OAuthCredentialStore#modify modify}）内
 *       串行 ⇒ 跨并发请求/进程全局只刷一次，且不会读到半截文件；</li>
 *   <li>刷新失败抛 {@link OAuthRefreshException}，<b>不静默回落 env</b>。</li>
 * </ul>
 */
public final class StoredOAuthCredentials {

    /** pi DEFAULT_OAUTH_MINIMUM_VALIDITY_MS＝5 分钟（秒）。 */
    static final long MINIMUM_VALIDITY_SECONDS = 5 * 60L;

    private final OAuthCredentialStore store;
    private final OAuthTokenRefresher refresher;

    /** 生产：默认文件 store ＋ {@link DefaultOAuthTokenRefresher}。 */
    public StoredOAuthCredentials() {
        this(new OAuthCredentialStore(), new DefaultOAuthTokenRefresher());
    }

    /** 测试注入：store（临时文件）＋脚本化 refresher。 */
    StoredOAuthCredentials(OAuthCredentialStore store, OAuthTokenRefresher refresher) {
        this.store = store;
        this.refresher = refresher;
    }

    /**
     * 取 provider 当前有效的 OAuth 凭证。全程在 store 文件锁内读当前值 ⇒ 不存在并发下
     * 读到半截/空文件的问题；凭证仍新鲜则原样返回（{@code modify} 跳过覆写），临期则
     * 锁内刷新并回存 —— 串行化天然保证跨并发请求/进程全局只刷一次。
     *
     * <p><b>B231（pi {@code auth/resolve.ts:119-157}，commit {@code bde882c74}）</b>：
     * 整段「锁内读当前→复核临期→刷新→回写」跑在<b>脱离调用方中断状态的新虚拟线程</b>上。
     * 调用方线程的中断只取消它自己的等待（{@code Future.get} 抛 {@link InterruptedException}
     * ⇒ 抛 {@link OAuthRefreshException}），<b>不取消已开始的刷新</b>：轮转后的 refresh
     * token 在 worker 上照常完成并落盘。否则 ChatGPT 那类「刷新即作废旧 refresh token」的
     * provider 会在调用方中止后只剩一枚已失效的旧 token。worker 进锁前看一眼调用方的
     * 中断位（对应 pi「信号只取消等锁」），一旦进入 {@code modify} 就只受刷新自身的
     * 15s 超时约束（见 {@code OAuthFlow}/{@code DeviceCodeFlow}）。</p>
     */
    public Optional<OAuthCredential> resolveEffective(String provider) {
        var done = new CompletableFuture<OAuthCredential>();
        var caller = Thread.currentThread();
        Thread.startVirtualThread(() -> {
            try {
                // pi：调用方信号只取消「等锁」。进锁前已取消就不发起这次刷新，
                // 凭证留给下一个请求；进锁后不再受调用方中断影响。
                if (caller.isInterrupted()) {
                    done.complete(null);
                    return;
                }
                var result = store.modify(provider, current -> {
                    if (!nearExpiry(current)) {
                        return current;
                    }
                    try {
                        return refresher.refresh(provider, current);
                    } catch (RuntimeException | IOException e) {
                        throw new OAuthRefreshException(provider, e);
                    }
                });
                done.complete(result);
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        try {
            return Optional.ofNullable(done.get());
        } catch (InterruptedException e) {
            // 调用方取消等待：立刻收场（pi 的 raced caller 收 AbortError）；worker 不受
            // 影响，刷新完成后照常经 store.modify 落盘轮转 token —— 本批的核心保证。
            Thread.currentThread().interrupt();
            throw new OAuthRefreshException(provider,
                new InterruptedIOException("OAuth refresh wait interrupted"));
        } catch (ExecutionException e) {
            var cause = e.getCause();
            if (cause instanceof OAuthRefreshException ore) {
                throw ore;
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new OAuthRefreshException(provider, cause == null ? e : cause);
        }
    }

    /**
     * 是否临期：{@code now + 5min >= expiresAt}。permanent（{@code Long.MAX_VALUE}）
     * 与未知（{@code expiresAt<=0}）均不判临期。
     */
    static boolean nearExpiry(OAuthCredential credential) {
        var expiresAt = credential.expiresAtEpochSec();
        return expiresAt != Long.MAX_VALUE
            && expiresAt > 0
            && Instant.now().getEpochSecond() + MINIMUM_VALIDITY_SECONDS >= expiresAt;
    }
}
