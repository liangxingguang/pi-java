package com.pijava.ai.protocol;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.thinking.ThinkingLevelMap;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包 B103 端到端钉子。
 *
 * <p><b>范围（用户裁决方案 2）</b>：请求体侧 {@code prompt_cache_key} 照落；
 * Anthropic/completions 的非标准亲和头在官方 Java SDK streaming 不可达（B140）⇒
 * 反向断言它们不出现，防止后人误判为已对齐。responses 车道的头 SDK 透传 ⇒ 正向断言。</p>
 */
class SessionAffinityWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SID = "0192ab63-session-affinity-key";

    private static Message user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }

    private static ModelCompat compat(Boolean send, Boolean longRetention) {
        return new ModelCompat(false, null, true, false, null, null, null, null, null,
            true, null, null, null, null, longRetention, null, null, null, null,
            null, Map.of(), Map.of(), null, null, null, send, null);
    }

    private static ModelInfo model(String provider, String modelName, ModelCompat compat) {
        return new ModelInfo(ModelId.of(provider, modelName), modelName,
            Set.of(ModelCapability.TEXT), 200_000, 32_000, false,
            PricingInfo.UNKNOWN, ThinkingLevelMap.empty(), Map.of(), Map.of(), compat);
    }

    private record Captured(Map<String, String> headers, JsonNode body) {}

    private static Captured send(ApiOptions options, ModelInfo model) throws Exception {
        try (var server = new RecordingHttpServer()) {
            var opts = new ApiOptions(server.baseUrl(), options.apiKey(),
                Duration.ofSeconds(5), 0, options.extra());
            var api = switch (model.id().provider()) {
                case "fireworks-anthropic" ->
                    new AnthropicMessagesApi(opts, "ANTHROPIC_API_KEY");
                case "fireworks-completions" -> new OpenAICompletionsApi(opts, "OPENAI_API_KEY");
                default -> new OpenAIResponsesApi(opts, "OPENAI_API_KEY");
            };
            var request = new StreamRequest(model, "sys", List.of(user("hi")), List.of(),
                -1, -1, Map.of());
            try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
                while (iter.hasNext()) {
                    iter.next();
                }
            } catch (Exception ignored) {
                // 桩 400：头/体已录，流如何结束无关
            }
            return new Captured(server.headers(), MAPPER.readTree(server.body()));
        }
    }

    private static ApiOptions options(Map<String, Object> extra) {
        return new ApiOptions("", "test-key", Duration.ofSeconds(5), 0, extra);
    }

    private static Map<String, Object> extra(String retention) {
        var e = new java.util.LinkedHashMap<String, Object>();
        e.put("sessionId", SID);
        if (retention != null) {
            e.put("cacheRetention", retention);
        }
        return e;
    }

    // ── Anthropic：非标准头不可达（B140）────────────────────────

    @Test
    void anthropicSendsNoAffinityHeader() throws Exception {        var captured = send(options(extra(null)),
            model("fireworks-anthropic", "claude", compat(Boolean.TRUE, null)));
        assertThat(captured.headers())
            .doesNotContainKey("x-session-affinity")
            .doesNotContainKey("x-session-id");
    }

    // ── Completions：体侧落、头侧不可达 ─────────────────────────

    @Test
    void completionsLongSendsPromptCacheKeyButNoAffinityHeader() throws Exception {        var captured = send(options(extra("long")),
            model("fireworks-completions", "llama", compat(Boolean.TRUE, Boolean.TRUE)));
        assertThat(captured.body.path("prompt_cache_key").asText()).isEqualTo(SID);
        assertThat(captured.headers())
            .doesNotContainKey("x-session-affinity")
            .doesNotContainKey("x-session-id");
    }

    @Test
    void completionsNoneSendsNeither() throws Exception {        var captured = send(options(extra("none")),
            model("fireworks-completions", "llama", compat(Boolean.TRUE, Boolean.TRUE)));
        assertThat(captured.body.has("prompt_cache_key")).isFalse();
        assertThat(captured.headers()).doesNotContainKey("x-session-affinity");
    }

    // ── Responses：头 SDK 透传 + 体侧落 ─────────────────────────

    @Test
    void responsesSendsClientRequestIdAndPromptCacheKey() throws Exception {        var captured = send(options(extra(null)),
            model("fireworks-responses", "gpt", compat(null, null)));
        assertThat(captured.headers()).containsEntry("x-client-request-id", SID);
        assertThat(captured.headers()).doesNotContainKey("x-session-affinity");
        assertThat(captured.body.path("prompt_cache_key").asText()).isEqualTo(SID);
    }

    @Test
    void responsesNoneOmitsPromptCacheKey() throws Exception {        var captured = send(options(extra("none")),
            model("fireworks-responses", "gpt", compat(null, null)));
        assertThat(captured.body.has("prompt_cache_key")).isFalse();
    }
}
