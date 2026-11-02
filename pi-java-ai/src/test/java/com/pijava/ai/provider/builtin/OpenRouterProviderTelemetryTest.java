package com.pijava.ai.provider.builtin;

import java.time.Duration;
import java.util.Map;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.AuthKind;
import com.pijava.ai.provider.Protocol;
import com.pijava.telemetry.InMemoryTelemetryContext;
import com.pijava.telemetry.SpanOptions;
import com.pijava.telemetry.TelemetrySpan;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** docs/13 §3.7：OpenRouter 的 anthropic 车道 baseUrl pin 不得丢掉 telemetry。 */
class OpenRouterProviderTelemetryTest {

    private static final TelemetrySpan SENTINEL =
        new InMemoryTelemetryContext().openSpan(new SpanOptions("sentinel"));

    private static ApiOptions withTelemetry(String baseUrl) {
        return new ApiOptions(baseUrl, "k", Duration.ofSeconds(1), 0, Map.of(),
            AuthKind.API_KEY, SENTINEL);
    }

    @Test
    void pinnedAnthropicBaseUrlKeepsTelemetry() {
        var pinned = OpenRouterProvider.pinnedOptions(
            Protocol.ANTHROPIC_MESSAGES, withTelemetry(""));

        assertThat(pinned.baseUrl()).isEqualTo(OpenRouterProvider.ANTHROPIC_BASE_URL);
        assertThat(pinned.telemetry()).isSameAs(SENTINEL);
    }

    @Test
    void otherProtocolReturnsSameInstance() {
        var options = withTelemetry("");

        assertThat(OpenRouterProvider.pinnedOptions(Protocol.OPENAI_COMPLETIONS, options))
            .isSameAs(options);
    }
}
