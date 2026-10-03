package com.pijava.ai.provider.builtin;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.api.ProviderApi;
import com.pijava.ai.protocol.AnthropicMessagesApi;
import com.pijava.ai.protocol.OpenAICompletionsApi;
import com.pijava.ai.provider.ConfigurableProvider;
import com.pijava.ai.provider.Protocol;
import com.pijava.ai.provider.ProviderConfig;

import java.util.Set;

/**
 * OpenRouter chat provider（包 A-02，docs/59 §4.4）—— 镜像 pi
 * {@code providers/openrouter.ts}：一个 provider、两条车道
 * （{@code anthropic-messages} ＋ {@code openai-completions}），派发键是模型自带的
 * {@code api}（pi {@code compat.ts:262}；java 的落点是 {@code extra["protocol"]}，
 * 见 {@code DefaultProviders.streamBlocking}）。
 *
 * <p>与 {@link OpenRouterImagesProvider}（id {@code openrouter-images}）并存 ——
 * pi 同样是两个 provider。</p>
 *
 * <p><b>baseUrl</b>：pi 是 per-model（anthropic 车道 {@code https://openrouter.ai/api}、
 * completions 车道 {@code https://openrouter.ai/api/v1}）；java 的 {@link ProviderConfig}
 * 每 provider 一个默认值 ⇒ 落成 per-protocol：completions 用 config 默认，anthropic 车道
 * 在 baseUrl 缺席时由 {@link #pinnedOptions} pin（两条车道各一个值，对内置目录等价；
 * CLI {@code --base-url}/settings 覆盖仍然赢，见 {@code ConfigurableProvider.effectiveOptions}
 * 的判空）。⚠️ anthropic 那个值**不带** {@code /v1}：Anthropic SDK 自己拼
 * {@code /v1/messages}（pi 数据同形）。</p>
 */
public final class OpenRouterProvider extends ConfigurableProvider {

    /** anthropic 车道的默认 baseUrl（pi openrouter.json 该组每条模型的 baseUrl）。 */
    static final String ANTHROPIC_BASE_URL = "https://openrouter.ai/api";

    @Override
    protected ProviderConfig config() {
        return new ProviderConfig(
            "openrouter", "OpenRouter", "https://openrouter.ai/api/v1",
            "OPENROUTER_API_KEY", Protocol.OPENAI_COMPLETIONS,
            Set.of(Protocol.OPENAI_COMPLETIONS, Protocol.ANTHROPIC_MESSAGES),
            OpenRouterModels.catalog());
    }

    @Override
    public <T extends ProviderApi> T createApi(Class<T> apiType, ApiOptions options) {
        return super.createApi(apiType, pinnedOptions(resolveProtocol(options), options));
    }

    /**
     * baseUrl 缺席时按协议 pin（包可见是为了夹具——与 {@code DefaultProviders.cacheExtra}
     * 同形）。⚠️ 六参构造：{@code authKind} 必须保真（B138 的同族教训，别改回五参）。
     */
    static ApiOptions pinnedOptions(Protocol protocol, ApiOptions options) {
        if (protocol == Protocol.ANTHROPIC_MESSAGES
                && (options.baseUrl() == null || options.baseUrl().isBlank())) {
            return new ApiOptions(ANTHROPIC_BASE_URL, options.apiKey(), options.timeout(),
                options.maxRetries(), options.extra(), options.authKind());
        }
        return options;
    }

    @Override
    protected ChatApi createChatApi(Protocol protocol, ApiOptions options) {
        return switch (protocol) {
            case OPENAI_COMPLETIONS -> new OpenAICompletionsApi(options, config().apiKeyEnvVar());
            case ANTHROPIC_MESSAGES -> new AnthropicMessagesApi(options, config().apiKeyEnvVar());
            default -> throw new IllegalArgumentException(
                "OpenRouterProvider cannot serve protocol " + protocol);
        };
    }
}
