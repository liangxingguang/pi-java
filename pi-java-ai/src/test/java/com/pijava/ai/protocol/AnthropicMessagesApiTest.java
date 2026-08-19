package com.pijava.ai.protocol;

import java.time.Duration;
import java.util.Map;

import com.pijava.ai.api.ApiOptions;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 6: Anthropic adapter accepts {@code apiKeyEnvVar} and {@code baseUrl}.
 */
class AnthropicMessagesApiTest {

    @Test
    void customEnvVarAppearsInMissingKeyError() {
        var options = new ApiOptions(
            "https://api.minimaxi.com/anthropic", "",
            Duration.ofSeconds(1), 0, Map.of());
        assertThatThrownBy(() -> new AnthropicMessagesApi(options, "MINIMAX_CN_API_KEY"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("MINIMAX_CN_API_KEY");
    }

    @Test
    void acceptsExplicitKeyAndBaseUrlOverride() {
        var options = new ApiOptions(
            "https://api.minimaxi.com/anthropic", "sk-test",
            Duration.ofSeconds(1), 0, Map.of());
        var api = new AnthropicMessagesApi(options, "MINIMAX_CN_API_KEY");
        assertThat(api).isNotNull();
    }
}
