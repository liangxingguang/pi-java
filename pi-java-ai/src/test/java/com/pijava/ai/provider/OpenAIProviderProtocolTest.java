package com.pijava.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.protocol.OpenAICompletionsApi;
import com.pijava.ai.protocol.OpenAIResponsesApi;

/**
 * D3（{@code 原 docs/62}）：官方 OpenAI provider 默认 Responses（pi 写死，
 * {@code providers/openai.ts:6-14}），显式 {@code extra.protocol} 可压住默认。
 */
class OpenAIProviderProtocolTest {

    private static ApiOptions options(Map<String, Object> extra) {
        return new ApiOptions(null, "test-key", Duration.ofSeconds(5), 0, extra);
    }

    @Test
    void defaultsToResponses() {
        ChatApi api = new OpenAIProvider()
            .createApi(ChatApi.class, options(Map.of()));

        assertThat(api).isInstanceOf(OpenAIResponsesApi.class);
    }

    @Test
    void explicitCompletionsProtocolOverridesTheDefault() {
        var extra = new LinkedHashMap<String, Object>();
        extra.put("protocol", "openai-completions");
        ChatApi api = new OpenAIProvider()
            .createApi(ChatApi.class, options(extra));

        assertThat(api).isInstanceOf(OpenAICompletionsApi.class);
    }
}
