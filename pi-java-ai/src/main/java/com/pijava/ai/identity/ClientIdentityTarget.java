package com.pijava.ai.identity;

/**
 * The provider and endpoint a request goes to.
 *
 * <p>pi {@code ClientIdentityTarget}（{@code packages/ai/src/utils/identity.ts:39-43}）：
 * 归因头按 provider 名与 baseUrl 判定目标。</p>
 *
 * @param provider provider 标识（Java 的 provider 名）
 * @param baseUrl  请求实际打到的端点（可以为空串，不得为 {@code null}）
 */
public record ClientIdentityTarget(String provider, String baseUrl) {
    public ClientIdentityTarget {
        provider = provider == null ? "" : provider;
        baseUrl = baseUrl == null ? "" : baseUrl;
    }
}
