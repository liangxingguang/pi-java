package com.pijava.ai.provider.builtin;

import java.time.Duration;
import java.util.Map;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.AuthKind;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.protocol.AnthropicMessagesApi;
import com.pijava.ai.protocol.OpenAICompletionsApi;
import com.pijava.ai.provider.Protocol;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包 A-02（docs/59 §4.4）：OpenRouter chat provider 的双协议路由与 per-protocol
 * baseUrl pin（pi 是 per-model baseUrl，java 落 per-protocol——对内置目录等价）。
 */
class OpenRouterProviderTest {

    private static final OpenRouterProvider PROVIDER = new OpenRouterProvider();

    private static ApiOptions options(Map<String, Object> extra) {
        return new ApiOptions("", "sk-test", Duration.ofSeconds(5), 0, extra);
    }

    @Test
    void configMirrorsThePiProviderDefinition() {
        var config = PROVIDER.providerConfig();
        assertThat(config.name()).isEqualTo("openrouter");
        assertThat(config.displayName()).isEqualTo("OpenRouter");
        assertThat(config.defaultBaseUrl()).isEqualTo("https://openrouter.ai/api/v1");
        assertThat(config.apiKeyEnvVar()).isEqualTo("OPENROUTER_API_KEY");
        assertThat(config.defaultProtocol()).isEqualTo(Protocol.OPENAI_COMPLETIONS);
        assertThat(config.supportedProtocols())
            .containsExactlyInAnyOrder(Protocol.OPENAI_COMPLETIONS, Protocol.ANTHROPIC_MESSAGES);
    }

    @Test
    void defaultProtocolServesTheCompletionsLane() {
        var api = PROVIDER.createApi(ChatApi.class, options(Map.of()));
        assertThat(api).isInstanceOf(OpenAICompletionsApi.class);
    }

    @Test
    void anthropicProtocolServesTheAnthropicLane() {
        var api = PROVIDER.createApi(ChatApi.class,
            options(Map.of("protocol", "anthropic-messages")));
        assertThat(api).isInstanceOf(AnthropicMessagesApi.class);
    }

    @Test
    void pinAppliesOnlyToTheAnthropicLaneWithBlankBaseUrl() {
        var blank = new ApiOptions("", "sk-test", Duration.ofSeconds(5), 0, Map.of(),
            AuthKind.BEARER);

        var pinned = OpenRouterProvider.pinnedOptions(Protocol.ANTHROPIC_MESSAGES, blank);
        assertThat(pinned.baseUrl()).isEqualTo(OpenRouterProvider.ANTHROPIC_BASE_URL);
        // ⚠️ 六参构造：凭证形态必须活过 pin（B138 的同族教训）。
        assertThat(pinned.authKind()).isEqualTo(AuthKind.BEARER);

        // completions 车道不 pin（config 默认值由 effectiveOptions 补）。
        assertThat(OpenRouterProvider.pinnedOptions(Protocol.OPENAI_COMPLETIONS, blank))
            .isSameAs(blank);
    }

    @Test
    void explicitBaseUrlWinsOverThePin() {
        // CLI --base-url / settings 覆盖（中转站场景）：pin 只在缺席时生效。
        var explicit = new ApiOptions("https://relay.example.com", "sk-test",
            Duration.ofSeconds(5), 0, Map.of());

        assertThat(OpenRouterProvider.pinnedOptions(Protocol.ANTHROPIC_MESSAGES, explicit))
            .isSameAs(explicit);
    }

    @Test
    void unknownProtocolIsRejectedLoudly() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> PROVIDER.createApi(ChatApi.class,
                    options(Map.of("protocol", "google-generative-ai"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not support protocol");
    }

    @Test
    void imagesProviderRemainsSeparate() {
        // pi 同样是两个 provider：chat 面不吃 ImageApi，images 面不吃 ChatApi。
        assertThat(PROVIDER.supportedApis()).containsExactly(ChatApi.class);
        assertThat(new OpenRouterImagesProvider().name()).isEqualTo("openrouter-images");
    }
}
