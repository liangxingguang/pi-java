package com.pijava.ai.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

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

/**
 * <b>包 09 / B104</b>：OpenAI Responses 车道的**三个 prompt cache 键**。
 *
 * <p>pi 的逐字码：{@code api/openai-responses.ts:83-99} 的两个工厂 ＋ {@code :315-317}
 * 的三个落点 ＋ {@code :73}/{@code :78} 的 compat 缺省。观测面是**真出站请求体**。</p>
 *
 * <p>⚠️ 三条与 pi 的对照：{@code prompt_cache_retention} 受
 * {@code supportsLongCacheRetention && !supportsExplicitPromptCacheMode} 两道门；
 * {@code prompt_cache_options} 只在 {@code supportsExplicitPromptCacheMode} 时出现，
 * 且按 retention 落 {@code mode}/{@code ttl}；{@code none} 时**连 key 都不发**。</p>
 */
class ResponsesPromptCacheWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SID = "0192ab63-responses-cache-key";

    /** 缺省（compat 未标）＋ long ⇒ pi 的 {@code ?? true} ⇒ 发 `24h`，不发 options。 */
    @Test
    void longSendsRetentionAndNoOptionsByDefault() throws Exception {
        var body = capture("long", compat(null, null));

        assertThat(body.path("prompt_cache_key").asText()).isEqualTo(SID);
        assertThat(body.path("prompt_cache_retention").asText()).isEqualTo("24h");
        assertThat(body.has("prompt_cache_options")).isFalse();
    }

    /** `supportsLongCacheRetention:false` ⇒ 不发 retention（key 照发）。 */
    @Test
    void longRetentionFalseDropsTheRetentionKey() throws Exception {
        var body = capture("long", compat(Boolean.FALSE, null));

        assertThat(body.path("prompt_cache_key").asText()).isEqualTo(SID);
        assertThat(body.has("prompt_cache_retention")).isFalse();
    }

    /** 显式模式 ＋ long ⇒ **不发** retention，改发 `options:{ttl:"30m"}`。 */
    @Test
    void explicitModeLongSendsOptionsInsteadOfRetention() throws Exception {
        var body = capture("long", compat(null, Boolean.TRUE));

        assertThat(body.has("prompt_cache_retention"))
            .as("pi :87 —— 显式模式下 retention 被排除").isFalse();
        assertThat(body.path("prompt_cache_options").path("ttl").asText()).isEqualTo("30m");
    }

    /** 显式模式 ＋ none ⇒ 不发 key，发 `options:{mode:"explicit"}`。 */
    @Test
    void explicitModeNoneSendsModeExplicitAndNoKey() throws Exception {
        var body = capture("none", compat(null, Boolean.TRUE));

        assertThat(body.has("prompt_cache_key")).isFalse();
        assertThat(body.path("prompt_cache_options").path("mode").asText()).isEqualTo("explicit");
    }

    /** 显式模式 ＋ short ⇒ **两个 option 支都不命中** ⇒ 整个键缺席（pi 返回 undefined）。 */
    @Test
    void explicitModeShortSendsNoOptionsAtAll() throws Exception {
        var body = capture("short", compat(null, Boolean.TRUE));

        assertThat(body.path("prompt_cache_key").asText()).isEqualTo(SID);
        assertThat(body.has("prompt_cache_options")).isFalse();
    }

    // ── 夹具 ───────────────────────────────────────────────────────

    private static JsonNode capture(String retention, ModelCompat compat) throws Exception {
        var extra = new LinkedHashMap<String, Object>();
        extra.put("sessionId", SID);
        extra.put("cacheRetention", retention);
        try (var server = new RecordingHttpServer()) {
            var opts = new ApiOptions(server.baseUrl(), "test-key",
                Duration.ofSeconds(5), 0, extra);
            var api = new OpenAIResponsesApi(opts, "OPENAI_API_KEY");
            var request = new StreamRequest(model(compat), "sys",
                List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")))),
                List.of(), -1, -1, Map.of());
            try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
                while (iter.hasNext()) {
                    iter.next();
                }
            } catch (Exception ignored) {
                // 桩回 400：请求体已录到
            }
            assertThat(server.body()).as("请求必须真的发出去").isNotEmpty();
            return MAPPER.readTree(server.body());
        }
    }

    private static ModelInfo model(ModelCompat compat) {
        return new ModelInfo(ModelId.of("openai", "gpt-5"), "gpt-5",
            Set.of(ModelCapability.TEXT), 200_000, 32_000, false,
            PricingInfo.UNKNOWN, ThinkingLevelMap.empty(), Map.of(), Map.of(), compat);
    }

    /** 三十一参规范构造：只关心第 15 位（longCacheRetention）与第 31 位（explicitPromptCacheMode）。 */
    private static ModelCompat compat(Boolean longRetention, Boolean explicitMode) {
        return new ModelCompat(false, null, true, false, null, null, null, null, null,
            true, null, null, null, null, longRetention, null, null, null, null,
            null, Map.of(), Map.of(), null, null, null, null, null, false, null,
            null, explicitMode);
    }
}
