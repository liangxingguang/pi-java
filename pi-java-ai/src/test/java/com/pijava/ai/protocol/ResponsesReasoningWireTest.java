package com.pijava.ai.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevelMap;

/**
 * <b>包 09 / B142</b>：OpenAI Responses 车道的 **reasoning 块**。
 *
 * <p>pi 的逐字码 {@code api/openai-responses.ts:343-362}：`model.reasoning` 门 ⇒
 * effort／summary 支（级别走 `thinkingLevelMap?.[level] ?? level`，只给 summary 时
 * effort 回落 `"medium"`）⇒ 否则 off 支（`thinkingLevelMap?.off ?? "none"`）。
 * 级别本身来自 {@code :230} 的 {@code clampThinkingLevel}。</p>
 *
 * <p>⚠️ 本仓改前：`effortString(extra["reasoningEffort"])` —— 那个键**没有生产者**，
 * 且 `effortString` 从不读 `thinkingLevelMap`、把 `xhigh`/`max` 塌成 `"high"`。</p>
 */
class ResponsesReasoningWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 门：模型不是 reasoning 模型 ⇒ 整块不发（pi `:343` 的 `if (model.reasoning)`）。 */
    @Test
    void nonReasoningModelGetsNoReasoningBlock() throws Exception {
        var body = capture(model(Set.of(ModelCapability.TEXT), ThinkingLevelMap.empty()),
            Optional.of(new ThinkingLevel.High()), null);

        assertThat(body.has("reasoning")).isFalse();
    }

    /** 级别经 `clampThinkingLevel` 后上线：空级别表的模型不支持 max ⇒ 夹到 high。 */
    @Test
    void levelIsClampedFromTheStreamRequest() throws Exception {
        var body = capture(model(reasoningCaps(), ThinkingLevelMap.empty()),
            Optional.of(new ThinkingLevel.Max()), null);

        assertThat(body.path("reasoning").path("effort").asText())
            .as("pi :230 的 clampThinkingLevel —— max 不被支持时向上/向下找")
            .isEqualTo("high");
    }

    /** 目录映射值**优先于**级别字面量（pi `:346-347` 的 `thinkingLevelMap?.[level] ?? level`）。 */
    @Test
    void thinkingLevelMapValueWinsOverTheLiteral() throws Exception {
        var map = ThinkingLevelMap.of(new LinkedHashMap<>(Map.of(
            ModelThinkingLevel.of(new ThinkingLevel.XHigh()), Optional.of("minimal"))));
        var body = capture(model(reasoningCaps(), map),
            Optional.of(new ThinkingLevel.XHigh()), null);

        assertThat(body.path("reasoning").path("effort").asText())
            .as("xhigh 在目录里映射成 minimal ⇒ 线上发 minimal")
            .isEqualTo("minimal");
    }

    /** 只给 summary（无 reasoning 级别）⇒ effort 回落 `"medium"`（pi `:348`）。 */
    @Test
    void summaryOnlyFallsBackToMediumEffort() throws Exception {
        var body = capture(model(reasoningCaps(), ThinkingLevelMap.empty()),
            Optional.empty(), "concise");

        assertThat(body.path("reasoning").path("effort").asText()).isEqualTo("medium");
        assertThat(body.path("reasoning").path("summary").asText()).isEqualTo("concise");
    }

    /** 两支都不命中 ⇒ off 支：`effort = thinkingLevelMap.off ?? "none"`（pi `:355-359`）。 */
    @Test
    void noEffortMeansTheOffBranch() throws Exception {
        var body = capture(model(reasoningCaps(), ThinkingLevelMap.empty()),
            Optional.empty(), null);

        assertThat(body.path("reasoning").path("effort").asText())
            .as("空级别表 ⇒ off 未被显式禁用 ⇒ 发 none")
            .isEqualTo("none");
    }

    /** off 被显式禁用（值为 null）⇒ off 支不发（pi 的 `thinkingLevelMap?.off !== null`）。 */
    @Test
    void explicitlyDisabledOffSkipsTheOffBranch() throws Exception {
        var map = ThinkingLevelMap.of(new LinkedHashMap<>(Map.of(
            ModelThinkingLevel.off(), Optional.empty())));
        var body = capture(model(reasoningCaps(), map), Optional.empty(), null);

        assertThat(body.has("reasoning")).isFalse();
    }

    // ── 夹具 ───────────────────────────────────────────────────────

    private static Set<ModelCapability> reasoningCaps() {
        return Set.of(ModelCapability.TEXT, ModelCapability.THINKING);
    }

    private static JsonNode capture(ModelInfo model, Optional<ThinkingLevel> reasoning,
                                    String summary) throws Exception {
        var extra = new LinkedHashMap<String, Object>();
        if (summary != null) {
            extra.put("reasoningSummary", summary);
        }
        try (var server = new RecordingHttpServer()) {
            var opts = new ApiOptions(server.baseUrl(), "test-key",
                Duration.ofSeconds(5), 0, extra);
            var api = new OpenAIResponsesApi(opts, "OPENAI_API_KEY");
            var request = new StreamRequest(model, "sys",
                List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")))),
                List.of(), -1, -1, Map.of(), reasoning);
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

    private static ModelInfo model(Set<ModelCapability> caps, ThinkingLevelMap levels) {
        return new ModelInfo(ModelId.of("openai", "gpt-5"), "gpt-5", caps,
            200_000, 32_000, false, PricingInfo.UNKNOWN, levels, Map.of(), Map.of(),
            ModelCompat.NONE);
    }
}
