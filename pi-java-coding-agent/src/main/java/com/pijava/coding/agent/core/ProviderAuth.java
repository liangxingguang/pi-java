package com.pijava.coding.agent.core;

import com.pijava.ai.auth.Credentials;

/**
 * L5 W6b 拆件（docs/04 结构债惯例；pi {@code ModelRuntime.hasConfiguredAuth}
 * 对位）：一个 provider 是否已有可用凭据。
 *
 * <p>活凭证链（环境变量／{@code auth.json}／settings 的 provider key）非空即真。
 * 只用于「同 id 跨 provider 时谁赢」的破平判定（pi 的 {@code usable} 谓词），
 * 不决定请求能否发出（那是 {@code DefaultProviders.apiOptions} 的职责）。</p>
 *
 * <p>从 {@link DefaultProviders} 顶出以守住文件 ≤500 行闸门（行为零变化：
 * {@code DefaultProviders.hasConfiguredAuth} 保留同签名委托）。</p>
 */
public final class ProviderAuth {

    private ProviderAuth() {
    }

    /** 该 provider 是否已有配置凭据（{@code null}/空白 ⇒ false）。 */
    public static boolean configured(String provider) {
        if (provider == null || provider.isBlank()) {
            return false;
        }
        return Credentials.resolveCredential(provider).isPresent();
    }
}
