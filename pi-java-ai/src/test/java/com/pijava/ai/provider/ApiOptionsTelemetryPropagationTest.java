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
 * docs/13 §3.7：AI 侧的五个 {@code ApiOptions} 重建点必须把 {@code telemetry}
 * 原样带到下一层；漏传不会编译失败，只能靠逐点断言。
 */
class ApiOptionsTelemetryPropagationTest {

    private static final TelemetrySpan SENTINEL =
        new InMemoryTelemetryContext().openSpan(new SpanOptions("sentinel"));

    private static ApiOptions withTelemetry(String baseUrl, String apiKey) {
        return new ApiOptions(baseUrl, apiKey, Duration.ofSeconds(1), 0, Map.of(),
            AuthKind.API_KEY, SENTINEL);
    }

    @Test
    void configurableProviderFillsDefaultBaseUrlWithoutDroppingTelemetry() {
        var provider = new CapturingProvider();

        provider.createApi(ChatApi.class, withTelemetry("", "k"));

        assertThat(provider.seen.baseUrl()).isEqualTo("https://default");
        assertThat(provider.seen.telemetry()).isSameAs(SENTINEL);
    }

    @Test
    void configurableProviderKeepsExplicitBaseUrlAndTelemetry() {
        var provider = new CapturingProvider();

        provider.createApi(ChatApi.class, withTelemetry("https://explicit", "k"));

        assertThat(provider.seen.baseUrl()).isEqualTo("https://explicit");
        assertThat(provider.seen.telemetry()).isSameAs(SENTINEL);
    }

    @Test
    void openAiCompatiblePlaceholderKeyKeepsTelemetry() {
        var pinned = OpenAiCompatibleProvider.withPlaceholderKey(withTelemetry("", ""));

        assertThat(pinned.apiKey()).isEqualTo("ollama");
        assertThat(pinned.telemetry()).isSameAs(SENTINEL);
    }

    @Test
    void modelsJsonPinnedBaseUrlKeepsTelemetry() {
        var pinned = ModelsJsonProvider.pinnedOptions(withTelemetry("", "k"), "https://models-json");

        assertThat(pinned.baseUrl()).isEqualTo("https://models-json");
        assertThat(pinned.telemetry()).isSameAs(SENTINEL);
    }

    @Test
    void modelsJsonInlineKeyKeepsTelemetry() {
        var pinned = ModelsJsonProvider.withInlineKey(withTelemetry("", ""), "inline-key");

        assertThat(pinned.apiKey()).isEqualTo("inline-key");
        assertThat(pinned.telemetry()).isSameAs(SENTINEL);
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
