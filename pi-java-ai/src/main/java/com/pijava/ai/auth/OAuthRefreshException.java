package com.pijava.ai.auth;

/**
 * 请求路径上 stored OAuth 刷新失败（包 B1，{@code docs/63}）。
 *
 * <p>pi 的 {@code resolveStoredOAuth}（{@code auth/resolve.ts:127-179}）在临期刷新失败时
 * <b>不静默回落</b> ambient env（stored credential 拥有 provider），而是让请求以错误收场。
 * java 侧用此非受检异常把刷新失败带出解析链，交由宿主的 provider 错误投影处理。</p>
 */
public final class OAuthRefreshException extends RuntimeException {

    /**
     * @param provider 刷新失败的 provider 名
     * @param cause    底层刷新错误（无 refresh token／端点报错等）
     */
    public OAuthRefreshException(String provider, Throwable cause) {
        super("OAuth token refresh failed for " + provider + ": " + cause.getMessage(), cause);
    }
}
