package com.pijava.ai.auth;

import java.io.IOException;

/**
 * 用 refresh token 换发新凭证（包 B1 Step 1 下沉，{@code docs/63}）。
 *
 * <p>pi 侧这是 provider 的 {@code oauth.refresh(credential)}（{@code auth/helpers.ts}
 * lazyOAuth 暴露）。java 把原本只在 {@code AuthCommand} 里的 PKCE/device 分派下沉到
 * {@code pi-java-ai}，使<b>请求路径</b>与 auth 命令复用同一刷新逻辑。函数式接口便于
 * 夹具脚本化（不必打真实网络）。</p>
 */
@FunctionalInterface
public interface OAuthTokenRefresher {

    /**
     * @param provider   provider 名（决定走 PKCE 还是 device-code 流程）
     * @param credential 临期/已过期的既有凭证
     * @return 已旋转的新凭证（调用方负责持久化）
     * @throws IOException 无 refresh token、未注册流程或刷新端点报错
     */
    OAuthCredential refresh(String provider, OAuthCredential credential) throws IOException;
}
