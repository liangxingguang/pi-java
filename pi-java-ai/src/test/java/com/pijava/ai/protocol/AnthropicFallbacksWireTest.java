package com.pijava.ai.protocol;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.provider.BuiltinModelCatalogs;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B98（docs/51）：{@code allowedFallbackModels} 的**请求半** ——
 * pi {@code anthropic-messages.ts:1116/:1281-1284}。
 *
 * <p>内置目录的 {@code claude-fable-5} 带两条许可回退（opus-4-8/opus-5）⇒ 请求发
 * beta {@code server-side-fallback-2026-07-01} 与 {@code fallbacks:[{model}]}；
 * 其它模型两者皆无（Anthropic 对无许可目标的模型拒绝该字段）。
 * 流内回退块/responseModel 成本归属随 B148。</p>
 */
class AnthropicFallbacksWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void fableFiveCarriesTheFallbackBetaAndParams() throws Exception {
        var model = BuiltinModelCatalogs.catalog("anthropic")
            .find(ModelId.of("anthropic", "claude-fable-5")).orElseThrow();

        var captured = capture(model);
        var body = captured.body();

        assertThat(captured.betaHeader())
            .as("beta 协商在 anthropic-beta 请求头（docs/13 裁决①：body 的 betas 键不上头）")
            .contains("server-side-fallback-2026-07-01");
        assertThat(body.has("betas"))
            .as("beta 列表不许再落进 body")
            .isFalse();
        var fallbacks = body.path("fallbacks");
        assertThat(fallbacks.isArray()).isTrue();
        assertThat(fallbacks).hasSize(2);
        assertThat(fallbacks.get(0).path("model").asText()).isEqualTo("claude-opus-4-8");
        assertThat(fallbacks.get(1).path("model").asText()).isEqualTo("claude-opus-5");
        // 请求只发 model：provider/cost 是本地元数据，不上线。
        assertThat(fallbacks.get(0).has("provider")).isFalse();
        assertThat(fallbacks.get(0).has("cost")).isFalse();
    }

    @Test
    void otherAnthropicModelsCarryNeitherBetaNorFallbacks() throws Exception {
        var model = BuiltinModelCatalogs.catalog("anthropic")
            .find(ModelId.of("anthropic", "claude-sonnet-4-6")).orElseThrow();

        var captured = capture(model);

        assertThat(captured.body().has("fallbacks")).isFalse();
        assertThat(captured.body().has("betas")).isFalse();
        assertThat(captured.betaHeader())
            .doesNotContain("server-side-fallback-2026-07-01");
    }

    /** 一次抓包：请求体 + anthropic-beta 头原文（无该头时为空串）。 */
    private record Captured(JsonNode body, String betaHeader) {}

    private static Captured capture(ModelInfo model) throws Exception {
        List<Message> messages = List.of(new Message.UserMessage(
            List.of(new ContentBlock.TextContent("hi"))));
        try (var server = new RecordingHttpServer()) {
            var api = new AnthropicMessagesApi(
                new ApiOptions(server.baseUrl(), "test-key",
                    java.time.Duration.ofSeconds(5), 0, Map.of()),
                "ANTHROPIC_API_KEY");
            var request = new StreamRequest(model, "be brief", messages, List.of(),
                -1, -1);
            try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
                while (iter.hasNext()) {
                    iter.next();
                }
            } catch (Exception ignored) {
                // 桩回 400：请求体已录到。
            }
            assertThat(server.body()).as("请求必须真的发出去").isNotEmpty();
            var header = server.headers().getOrDefault("anthropic-beta", "");
            return new Captured(MAPPER.readTree(server.body()), header);
        }
    }
}
