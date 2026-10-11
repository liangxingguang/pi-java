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
import com.pijava.ai.catalog.SessionAffinityFormat;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.thinking.ThinkingLevelMap;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B103（docs/52）端到端钉子：会话亲和头三车道全落。
 *
 * <p>旧裁决（方案 2：B140 认定 SDK streaming 不透传 params 级头，反向断言头缺席）已翻案 ——
 * javap 实证 {@code putAdditionalHeader} 存在且 responses 车道早已透传。本类现在正向钉住
 * pi {@code anthropic-messages.ts:1038-1048}、{@code openai-completions.ts:776-786}、
 * {@code openai-responses.ts:277-286} 的头表，外加三车道共用的 cacheRetention=none 门。</p>
 */
class SessionAffinityWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SID = "0192ab63-session-affinity-key";

    private static Message user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }

    private static ModelCompat compat(Boolean send, SessionAffinityFormat format,
                                      Boolean longRetention) {
        return new ModelCompat(false, null, true, false, null, null, null, null, null,
            true, null, null, null, null, longRetention, null, null, null, null,
            null, Map.of(), Map.of(), null, null, null, send, format);
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
                Duration.ofSeconds(5), 0, options.extra(), options.authKind());
            var api = switch (model.id().provider()) {
                case "fireworks-anthropic" ->
                    new AnthropicMessagesApi(opts, "ANTHROPIC_API_KEY");
                case "fireworks-completions" ->
                    new OpenAICompletionsApi(opts, "OPENAI_API_KEY");
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

    private static ApiOptions options(String apiKey, Map<String, Object> extra) {
        return new ApiOptions("", apiKey, Duration.ofSeconds(5), 0, extra);
    }

    private static Map<String, Object> extra(String retention) {
        var e = new java.util.LinkedHashMap<String, Object>();
        e.put("sessionId", SID);
        if (retention != null) {
            e.put("cacheRetention", retention);
        }
        return e;
    }

    // ── Anthropic：单头，Bearer 分支不挂 ────────────────────────

    @Test
    void anthropicSendsSessionAffinityHeader() throws Exception {
        var captured = send(options(extra(null)),
            model("fireworks-anthropic", "claude", compat(Boolean.TRUE, null, null)));
        assertThat(captured.headers())
            .containsEntry("x-session-affinity", SID)
            .doesNotContainKey("x-session-id");
    }

    @Test
    void anthropicOpenrouterFormatSendsSessionIdHeader() throws Exception {
        var captured = send(options(extra(null)),
            model("fireworks-anthropic", "claude",
                compat(Boolean.TRUE, SessionAffinityFormat.OPENROUTER, null)));
        assertThat(captured.headers())
            .containsEntry("x-session-id", SID)
            .doesNotContainKey("x-session-affinity");
    }

    @Test
    void anthropicOauthSendsNoAffinityHeader() throws Exception {
        // pi :992-1031：OAuth（Bearer）分支的 mergeClientHeaders 不带 sessionAffinityHeaders。
        var captured = send(options("sk-ant-oat0000-affinity-fixture", extra(null)),
            model("fireworks-anthropic", "claude", compat(Boolean.TRUE, null, null)));
        assertThat(captured.headers())
            .doesNotContainKey("x-session-affinity")
            .doesNotContainKey("x-session-id");
    }

    @Test
    void anthropicGateOffSendsNoAffinityHeader() throws Exception {
        // 显式缺席 ⇒ 探测缺省（非 openrouter 端点）false。
        var captured = send(options(extra(null)),
            model("fireworks-anthropic", "claude", compat(null, null, null)));
        assertThat(captured.headers())
            .doesNotContainKey("x-session-affinity")
            .doesNotContainKey("x-session-id");
    }

    @Test
    void anthropicNoneRetentionSuppressesAffinityHeader() throws Exception {
        var captured = send(options(extra("none")),
            model("fireworks-anthropic", "claude", compat(Boolean.TRUE, null, null)));
        assertThat(captured.headers())
            .doesNotContainKey("x-session-affinity")
            .doesNotContainKey("x-session-id");
    }

    // ── Completions：三头 / 两头 / 一头 + none 门 ───────────────

    @Test
    void completionsOpenaiSendsThreeAffinityHeaders() throws Exception {
        var captured = send(options(extra(null)),
            model("fireworks-completions", "llama",
                compat(Boolean.TRUE, SessionAffinityFormat.OPENAI, Boolean.TRUE)));
        assertThat(captured.headers())
            .containsEntry("session_id", SID)
            .containsEntry("x-client-request-id", SID)
            .containsEntry("x-session-affinity", SID)
            .doesNotContainKey("x-session-id");
    }

    @Test
    void completionsOpenaiNosessionSendsTwoHeaders() throws Exception {
        var captured = send(options(extra(null)),
            model("fireworks-completions", "llama",
                compat(Boolean.TRUE, SessionAffinityFormat.OPENAI_NOSESSION, null)));
        assertThat(captured.headers())
            .containsEntry("x-client-request-id", SID)
            .containsEntry("x-session-affinity", SID)
            .doesNotContainKey("session_id")
            .doesNotContainKey("x-session-id");
    }

    @Test
    void completionsOpenrouterSendsSingleSessionIdHeader() throws Exception {
        var captured = send(options(extra(null)),
            model("fireworks-completions", "llama",
                compat(Boolean.TRUE, SessionAffinityFormat.OPENROUTER, null)));
        assertThat(captured.headers())
            .containsEntry("x-session-id", SID)
            .doesNotContainKey("x-client-request-id")
            .doesNotContainKey("x-session-affinity");
    }

    @Test
    void completionsGateOffSendsNoAffinityHeader() throws Exception {
        var captured = send(options(extra(null)),
            model("fireworks-completions", "llama", compat(null, null, null)));
        assertThat(captured.headers())
            .doesNotContainKey("x-session-affinity")
            .doesNotContainKey("x-session-id")
            .doesNotContainKey("x-client-request-id")
            .doesNotContainKey("session_id");
    }

    @Test
    void completionsLongSendsPromptCacheKeyAndAffinityHeaders() throws Exception {
        var captured = send(options(extra("long")),
            model("fireworks-completions", "llama",
                compat(Boolean.TRUE, SessionAffinityFormat.OPENAI, Boolean.TRUE)));
        assertThat(captured.body.path("prompt_cache_key").asText()).isEqualTo(SID);
        assertThat(captured.headers())
            .containsEntry("x-client-request-id", SID)
            .containsEntry("x-session-affinity", SID);
    }

    @Test
    void completionsNoneSendsNeither() throws Exception {
        var captured = send(options(extra("none")),
            model("fireworks-completions", "llama",
                compat(Boolean.TRUE, SessionAffinityFormat.OPENAI, Boolean.TRUE)));
        assertThat(captured.body.has("prompt_cache_key")).isFalse();
        assertThat(captured.headers())
            .doesNotContainKey("x-session-affinity")
            .doesNotContainKey("x-client-request-id")
            .doesNotContainKey("session_id");
    }

    // ── Responses：头 SDK 透传 + none 门（pi :159）──────────────

    @Test
    void responsesSendsClientRequestIdAndPromptCacheKey() throws Exception {
        var captured = send(options(extra(null)),
            model("fireworks-responses", "gpt", compat(null, null, null)));
        assertThat(captured.headers()).containsEntry("x-client-request-id", SID);
        assertThat(captured.headers()).doesNotContainKey("x-session-affinity");
        assertThat(captured.body.path("prompt_cache_key").asText()).isEqualTo(SID);
    }

    @Test
    void responsesNoneSuppressesPromptCacheKeyAndAffinityHeader() throws Exception {
        var captured = send(options(extra("none")),
            model("fireworks-responses", "gpt", compat(null, null, null)));
        assertThat(captured.body.has("prompt_cache_key")).isFalse();
        assertThat(captured.headers()).doesNotContainKey("x-client-request-id");
    }
}
