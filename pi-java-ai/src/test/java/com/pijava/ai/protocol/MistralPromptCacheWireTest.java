package com.pijava.ai.protocol;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
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
 * B233：Mistral 缓存门 —— 对应 pi
 * {@code test/mistral-reasoning-mode.test.ts:145-160}（体键门）与
 * {@code test/mistral-http-transport.test.ts:113-129}（头＋体）、
 * {@code :421-445}（覆盖与显式压制）。
 *
 * <p>pi {@code mistral-conversations.ts:551-553}：{@code cacheRetention !== "none" && sessionId}
 * ⇒ 体发 {@code prompt_cache_key}（<b>原样不夹 64 码点</b>）、头发 {@code x-affinity}。
 * 走真实 HTTP，{@link RecordingHttpServer} 录最后一次请求的头（键已小写）与体。</p>
 */
class MistralPromptCacheWireTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SID = "0192ab63-mistral-session-affinity";

    private record Captured(Map<String, String> headers, JsonNode body) {}

    private static ModelInfo model(Map<String, String> modelHeaders) {
        return new ModelInfo(ModelId.of("mistral", "mistral-large-latest"),
            "Mistral Large", Set.of(ModelCapability.TEXT), 128_000, 8_192, false,
            PricingInfo.UNKNOWN, ThinkingLevelMap.empty(), modelHeaders, Map.of(),
            ModelCompat.NONE);
    }

    private static Message user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }

    /** extra 的便利构造：sessionId/cacheRetention/headers 按需放。 */
    private static Map<String, Object> extra(String sessionId, String cacheRetention,
                                             Map<String, Object> headers) {
        var extra = new LinkedHashMap<String, Object>();
        if (sessionId != null) {
            extra.put("sessionId", sessionId);
        }
        if (cacheRetention != null) {
            extra.put("cacheRetention", cacheRetention);
        }
        if (headers != null) {
            extra.put("headers", headers);
        }
        return extra;
    }

    private static Captured capture(Map<String, String> modelHeaders,
                                    Map<String, Object> extra) throws IOException {
        try (var server = new RecordingHttpServer()) {
            var opts = new ApiOptions(server.baseUrl(), "test-key",
                Duration.ofSeconds(5), 0, extra);
            var api = new MistralConversationsApi(opts);
            var request = new StreamRequest(model(modelHeaders), "sys",
                List.of(user("hi")), List.of(), -1, -1);
            try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
                while (iter.hasNext()) {
                    iter.next();
                }
            } catch (Exception ignored) {
                // 桩 400：头/体已录，流如何结束无关（同 SessionAffinityWireTest）。
            }
            return new Captured(server.headers(), JSON.readTree(server.body()));
        }
    }

    // ── T6：门 ───────────────────────────────────────────────────────────

    /** retention 缺省（⇒ short）＋sessionId ⇒ 头与体都发，值原样（pi :113/:129）。 */
    @Test
    void sessionEnablesAffinityHeaderAndCacheKey() throws Exception {
        var captured = capture(Map.of(), extra(SID, null, null));

        assertThat(captured.headers()).containsEntry("x-affinity", SID);
        assertThat(captured.body().get("prompt_cache_key").asText()).isEqualTo(SID);
    }

    /** cacheRetention:"none" ⇒ 门关，两头皆无（pi mistral-reasoning-mode.test.ts:154-160）。 */
    @Test
    void cacheRetentionNoneDisablesBoth() throws Exception {
        var captured = capture(Map.of(), extra(SID, "none", null));

        assertThat(captured.headers()).doesNotContainKey("x-affinity");
        assertThat(captured.body().has("prompt_cache_key")).isFalse();
    }

    /** 无 sessionId（retention 缺省）⇒ 不发（pi 的 {@code !!options?.sessionId} 假值支）。 */
    @Test
    void noSessionSendsNeither() throws Exception {
        var captured = capture(Map.of(), extra(null, null, null));

        assertThat(captured.headers()).doesNotContainKey("x-affinity");
        assertThat(captured.body().has("prompt_cache_key")).isFalse();
    }

    /**
     * Mistral 不做 OpenAI 系的 64 码点 clamp（{@code clampOpenAIPromptCacheKey} 是
     * completions/responses 的事）：超长键头与体都原样。
     */
    @Test
    void longSessionIdIsNotClamped() throws Exception {
        var longSid = "s".repeat(120);

        var captured = capture(Map.of(), extra(longSid, null, null));

        assertThat(captured.headers()).containsEntry("x-affinity", longSid);
        assertThat(captured.body().get("prompt_cache_key").asText()).isEqualTo(longSid);
    }

    // ── T7：model/options 两层覆盖与显式压制（pi :421-445）────────────────

    /** model.headers 显式 X-Affinity ⇒ 用 model 的值、不自动补；体键照发。 */
    @Test
    void modelAffinityHeaderWinsOverAutomatic() throws Exception {
        var captured = capture(Map.of("X-Affinity", "model-affinity"),
            extra(SID, null, null));

        assertThat(captured.headers()).containsEntry("x-affinity", "model-affinity");
        assertThat(captured.body().get("prompt_cache_key").asText()).isEqualTo(SID);
    }

    /** options {@code x-affinity:null} 叠加在 model 的 X-Affinity 上 ⇒ 头被删净（pi :438-443）。 */
    @Test
    void optionNullAffinitySuppressesModelHeader() throws Exception {
        var optionHeaders = new LinkedHashMap<String, Object>();
        optionHeaders.put("x-affinity", null);

        var captured = capture(Map.of("X-Affinity", "model-affinity"),
            extra(SID, null, optionHeaders));

        assertThat(captured.headers()).doesNotContainKey("x-affinity");
        // 显式压制只盖头；体键的门不看覆盖（pi shouldUsePromptCaching 与覆盖无关）。
        assertThat(captured.body().get("prompt_cache_key").asText()).isEqualTo(SID);
    }

    /** options 覆盖：authorization:null 删认证头；User-Agent 大小写不敏感替换（pi :439-444）。 */
    @Test
    void optionHeaderOverridesDeleteAndReplace() throws Exception {
        var optionHeaders = new LinkedHashMap<String, Object>();
        optionHeaders.put("authorization", null);
        optionHeaders.put("User-Agent", "custom-agent");

        var captured = capture(Map.of(), extra(SID, null, optionHeaders));

        assertThat(captured.headers()).doesNotContainKey("authorization");
        assertThat(captured.headers()).containsEntry("user-agent", "custom-agent");
        // 自动亲和不受其它头覆盖影响。
        assertThat(captured.headers()).containsEntry("x-affinity", SID);
    }
}
