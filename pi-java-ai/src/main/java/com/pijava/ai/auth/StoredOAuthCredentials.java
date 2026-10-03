package com.pijava.ai.auth;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

/**
 * 解析「stored OAuth → 有效凭证」（包 B1，{@code docs/63}）—— 请求路径与 auth 命令共用。
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
     */
    public Optional<OAuthCredential> resolveEffective(String provider) {
        var effective = store.modify(provider, current -> {
            if (!nearExpiry(current)) {
                return current;
            }
            try {
                return refresher.refresh(provider, current);
            } catch (RuntimeException | IOException e) {
                throw new OAuthRefreshException(provider, e);
            }
        });
        return Optional.ofNullable(effective);
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
