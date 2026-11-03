package com.pijava.ai.provider;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.AuthKind;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.telemetry.InMemoryTelemetryContext;
import com.pijava.telemetry.SpanOptions;
import com.pijava.telemetry.TelemetrySpan;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/13 §3.7＋docs/14 §3.7：AI 侧的 {@code ApiOptions} 重建点必须把
 * {@code telemetry} 与 {@code identity} 原样带到下一层；漏传不会编译失败，
 * 只能靠逐点断言。
 */
class ApiOptionsTelemetryPropagationTest {

    private static final TelemetrySpan SENTINEL =
        new InMemoryTelemetryContext().openSpan(new SpanOptions("sentinel"));

    private static final com.pijava.ai.identity.ClientIdentity IDENTITY =
        new com.pijava.ai.identity.ClientIdentity(
            com.pijava.ai.identity.UserAgentMode.SHORT,
            com.pijava.ai.identity.AttributionSetting.of(
                com.pijava.ai.identity.ClientAttribution.PI),
            "1.0.0");

    private static ApiOptions withTelemetry(String baseUrl, String apiKey) {
        return new ApiOptions(baseUrl, apiKey, Duration.ofSeconds(1), 0, Map.of(),
            AuthKind.API_KEY, SENTINEL, IDENTITY);
    }

    @Test
    void configurableProviderFillsDefaultBaseUrlWithoutDroppingTelemetry() {
        var provider = new CapturingProvider();

        provider.createApi(ChatApi.class, withTelemetry("", "k"));

        assertThat(provider.seen.baseUrl()).isEqualTo("https://default");
        assertThat(provider.seen.telemetry()).isSameAs(SENTINEL);
        assertThat(provider.seen.identity()).isSameAs(IDENTITY);
        assertThat(provider.seen.extra())
            .containsKey(com.pijava.ai.identity.IdentityHeaders.EXTRA_KEY);
    }

    @Test
    void configurableProviderKeepsExplicitBaseUrlAndTelemetry() {
        var provider = new CapturingProvider();

        provider.createApi(ChatApi.class, withTelemetry("https://explicit", "k"));

        assertThat(provider.seen.baseUrl()).isEqualTo("https://explicit");
        assertThat(provider.seen.telemetry()).isSameAs(SENTINEL);
        assertThat(provider.seen.identity()).isSameAs(IDENTITY);
    }

    @Test
    void openAiCompatiblePlaceholderKeyKeepsTelemetry() {
        var pinned = OpenAiCompatibleProvider.withPlaceholderKey(withTelemetry("", ""));

        assertThat(pinned.apiKey()).isEqualTo("ollama");
        assertThat(pinned.telemetry()).isSameAs(SENTINEL);
        assertThat(pinned.identity()).isSameAs(IDENTITY);
    }

    @Test
    void modelsJsonPinnedBaseUrlKeepsTelemetry() {
        var pinned = ModelsJsonProvider.pinnedOptions(withTelemetry("", "k"), "https://models-json");

        assertThat(pinned.baseUrl()).isEqualTo("https://models-json");
        assertThat(pinned.telemetry()).isSameAs(SENTINEL);
        assertThat(pinned.identity()).isSameAs(IDENTITY);
    }

    @Test
    void modelsJsonInlineKeyKeepsTelemetry() {
        var pinned = ModelsJsonProvider.withInlineKey(withTelemetry("", ""), "inline-key");

        assertThat(pinned.apiKey()).isEqualTo("inline-key");
        assertThat(pinned.telemetry()).isSameAs(SENTINEL);
        assertThat(pinned.identity()).isSameAs(IDENTITY);
    }

    @Test
    void configurableProviderAddsAttributionHeadersForOpenRouterTarget() {
        var provider = new CapturingProvider();

        provider.createApi(ChatApi.class, withTelemetry("https://openrouter.ai/api/v1", "k"));

        var headers = com.pijava.ai.protocol.AbstractChatApi.identityHeaders(provider.seen);
        assertThat(headers)
            .containsEntry("User-Agent", "pi")
            .containsEntry("HTTP-Referer", "https://pi.dev")
            .containsEntry("X-OpenRouter-Title", "pi")
            .containsEntry("X-OpenRouter-Categories", "cli-agent");
    }

    /** Minimal configurable provider that records the options reaching the lane. */
    private static final class CapturingProvider extends ConfigurableProvider {

        private ApiOptions seen;

        @Override
        protected ProviderConfig config() {
            return new ProviderConfig(
                "capturing", "Capturing", "https://default", null,
                Protocol.OPENAI_COMPLETIONS, Set.of(Protocol.OPENAI_COMPLETIONS),
                ModelCatalog.empty());
        }

        @Override
        protected ChatApi createChatApi(Protocol protocol, ApiOptions options) {
            seen = options;
            return null;
        }
    }
}
