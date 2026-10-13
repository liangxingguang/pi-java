package com.pijava.ai.provider.builtin;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.protocol.OpenAIResponsesApi;
import com.pijava.ai.provider.BuiltinModelCatalogs;
import com.pijava.ai.provider.ConfigurableProvider;
import com.pijava.ai.provider.Protocol;
import com.pijava.ai.provider.ProviderConfig;

/**
 * xAI (Grok) provider —— 镜像 pi {@code providers/xai.ts}：一个 provider、一条车道
 * （{@code openai-responses}），baseUrl {@code https://api.x.ai/v1}，apiKey 取
 * {@code XAI_API_KEY}（pi {@code envApiKeyAuth("xAI API key", ["XAI_API_KEY"])}）。
 *
 * <p>OAuth（SuperGrok／X Premium 订阅，RFC 8628 设备码）已在
 * {@code com.pijava.ai.auth.OAuthProviders} 的 {@code "xai"} 条目落地 —— 本类补的是
 * <b>车道</b>：此前 compat 层（{@code CompatResolver} 的 {@code provider.equals("xai")}
 * 与 {@code isGrok} 分支）已认识 xai，却没有 provider 类可承载，是主流 7 家里唯一
 * 没上车的端点。</p>
 */
public final class XaiProvider extends ConfigurableProvider {

    @Override
    protected ProviderConfig config() {
        // pi 是单车道 provider ⇒ ProviderConfig.single（OpenAIProvider 是多车道，不同形）。
        return ProviderConfig.single(
            "xai", "xAI", "https://api.x.ai/v1",
            "XAI_API_KEY", Protocol.OPENAI_RESPONSES,
            BuiltinModelCatalogs.catalog("xai"));
    }

    @Override
    protected ChatApi createChatApi(Protocol protocol, ApiOptions options) {
        return switch (protocol) {
            case OPENAI_RESPONSES -> new OpenAIResponsesApi(options, config().apiKeyEnvVar());
            default -> throw new IllegalArgumentException(
                "XaiProvider cannot serve protocol " + protocol);
        };
    }
}
