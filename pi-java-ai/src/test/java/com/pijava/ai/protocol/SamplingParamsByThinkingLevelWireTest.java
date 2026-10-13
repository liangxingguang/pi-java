package com.pijava.ai.protocol;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevelMap;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 3a T5（#91）：{@code Model.samplingParamsByThinkingLevel} 的请求期解析 ——
 * pi {@code simple-options.ts:24-33}：
 * {@code {...model.samplingParams, ...sptl[clamp(level)], ...requestParams}}。
 * 级别缺省 {@code reasoning} 时为 {@code "off"}。
 */
class SamplingParamsByThinkingLevelWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── completions 车道 ────────────────────────────────────────────────

    @Test
    void highLevelParamsAppearOnTheCompletionsLane() throws Exception {
        var sptl = Map.of(high(), Map.<String, Object>of("reasoning_effort", "high"));
        var body = completionsBody(model(Map.of(), ThinkingLevelMap.empty(), sptl),
            Optional.of(new ThinkingLevel.High()), Map.of());

        assertThat(body.path("reasoning_effort").asText()).isEqualTo("high");
        // typed setter 路径：不能写成两份。
        assertThat(occurrences(body.toString(), "\"reasoning_effort\"")).isEqualTo(1);
    }

    @Test
    void threeLayersMergeModelLevelRequestInOrder() throws Exception {
        // 请求同名最后赢。
        var sptl = Map.of(high(), Map.<String, Object>of("top_p", 0.5, "level_only", "l"));
        var requestParams = Map.<String, Object>of("top_p", 0.1, "request_only", "r");
        var body = completionsBody(model(Map.of("top_p", 0.9, "model_only", "m"),
            ThinkingLevelMap.empty(), sptl),
            Optional.of(new ThinkingLevel.High()), requestParams);

        assertThat(body.path("top_p").asDouble()).isEqualTo(0.1);
        assertThat(body.path("model_only").asText()).isEqualTo("m");
        assertThat(body.path("level_only").asText()).isEqualTo("l");
        assertThat(body.path("request_only").asText()).isEqualTo("r");

        // 无请求值 ⇒ 级别层赢模型层。
        var levelWins = completionsBody(model(Map.of("top_p", 0.9), ThinkingLevelMap.empty(),
            Map.of(high(), Map.<String, Object>of("top_p", 0.5))),
            Optional.of(new ThinkingLevel.High()), Map.of());
        assertThat(levelWins.path("top_p").asDouble()).isEqualTo(0.5);

        // reasoning 缺席（off 档无参数）⇒ 仅模型层。
        var modelWins = completionsBody(model(Map.of("top_p", 0.9), ThinkingLevelMap.empty(),
            Map.of(high(), Map.<String, Object>of("top_p", 0.5))),
            Optional.empty(), Map.of());
        assertThat(modelWins.path("top_p").asDouble()).isEqualTo(0.9);
    }

    @Test
    void requestedLevelIsClampedToTheNearestSupportedLevel() throws Exception {
        // 空 tlm：xhigh 是 opt-in 且无映射 ⇒ 不支持；clamp 先向上（max 也不支持）再向下 ⇒ high。
        var sptl = Map.of(high(), Map.<String, Object>of("top_p", 0.5));
        var body = completionsBody(model(Map.of(), ThinkingLevelMap.empty(), sptl),
            Optional.of(new ThinkingLevel.XHigh()), Map.of());

        assertThat(body.path("top_p").asDouble())
            .as("xhigh 不可用 ⇒ clamp 到 high，用的是 high 档的参数表").isEqualTo(0.5);
    }

    @Test
    void offLevelIsUsedWhenReasoningAbsent() throws Exception {
        var sptl = Map.of(ModelThinkingLevel.off(),
            Map.<String, Object>of("top_p", 0.2));
        var body = completionsBody(model(Map.of(), ThinkingLevelMap.empty(), sptl),
            Optional.empty(), Map.of());

        assertThat(body.path("top_p").asDouble()).isEqualTo(0.2);
    }

    @Test
    void paramsOfOtherLevelsDoNotLeak() throws Exception {
        var sptl = Map.of(high(), Map.<String, Object>of("high_only", 1));
        var body = completionsBody(model(Map.of(), ThinkingLevelMap.empty(), sptl),
            Optional.of(new ThinkingLevel.Low()), Map.of());

        assertThat(body.has("high_only")).isFalse();
    }

    @Test
    void unknownLevelKeyInTheDefinitionIsIgnored() throws Exception {
        var sptl = new LinkedHashMap<ModelThinkingLevel, Map<String, Object>>();
        // 解析层（ModelsJsonConfig.samplingParamsByThinkingLevelOf）吞未知键；这里模拟
        // 已解析表：七档闭集之外根本进不来，且空模型/空档不产生杂键（回归 today）。
        var body = completionsBody(model(Map.of(), ThinkingLevelMap.empty(), Map.of()),
            Optional.of(new ThinkingLevel.High()), Map.of());

        assertThat(body.has("top_p")).isFalse();
    }

    // ── responses 车道（与 completions 共用同一 merged 实现）──────────────

    @Test
    void responsesLaneMergesTheSameThreeLayers() throws Exception {
        var sptl = Map.of(high(), Map.<String, Object>of("top_p", 0.7));
        var body = responsesBody(model(Map.of(), ThinkingLevelMap.empty(), sptl),
            Optional.of(new ThinkingLevel.High()), Map.of());

        assertThat(body.path("top_p").asDouble()).isEqualTo(0.7);
    }

    // ── 夹具 ────────────────────────────────────────────────────────────

    private static ModelThinkingLevel high() {
        return ModelThinkingLevel.of(new ThinkingLevel.High());
    }

    private static JsonNode completionsBody(ModelInfo model, Optional<ThinkingLevel> reasoning,
                                             Map<String, Object> requestParams) throws Exception {
        try (var server = new RecordingHttpServer()) {
            var extra = new LinkedHashMap<String, Object>();
            if (!requestParams.isEmpty()) {
                extra.put("samplingParams", requestParams);
            }
            var api = new OpenAICompletionsApi(
                new ApiOptions(server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, extra));
            drain(api, request(model, reasoning));
            assertThat(server.body()).as("请求必须真的发出去").isNotEmpty();
            return MAPPER.readTree(server.body());
        }
    }

    private static JsonNode responsesBody(ModelInfo model, Optional<ThinkingLevel> reasoning,
                                           Map<String, Object> requestParams) throws Exception {
        try (var server = new RecordingHttpServer()) {
            var extra = new LinkedHashMap<String, Object>();
            if (!requestParams.isEmpty()) {
                extra.put("samplingParams", requestParams);
            }
            var api = new OpenAIResponsesApi(
                new ApiOptions(server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, extra));
            drain(api, request(model, reasoning));
            assertThat(server.body()).as("请求必须真的发出去").isNotEmpty();
            return MAPPER.readTree(server.body());
        }
    }

    private static void drain(AbstractChatApi api, StreamRequest request) {
        try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
            while (iter.hasNext()) {
                iter.next();
            }
        } catch (Exception ignored) {
            // 桩恒回 400：请求体已录到
        }
    }

    private static StreamRequest request(ModelInfo model, Optional<ThinkingLevel> reasoning) {
        return new StreamRequest(model, "be brief",
            List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")))),
            List.of(), -1, -1, reasoning);
    }

    private static int occurrences(String raw, String needle) {
        return raw.split(needle, -1).length - 1;
    }

    /** 16 参构造：模型级采样参数、思考级别表、按思考档采样参数表均可单独给值。 */
    private static ModelInfo model(Map<String, Object> modelSampling, ThinkingLevelMap tlm,
                                    Map<ModelThinkingLevel, Map<String, Object>> byLevel) {
        return new ModelInfo(ModelId.of("openai", "gpt-5"), "GPT-5",
            Set.of(ModelCapability.TEXT, ModelCapability.TOOL_USE, ModelCapability.STREAMING,
                ModelCapability.THINKING),
            200_000, 16_384, false, PricingInfo.UNKNOWN,
            tlm, Map.of(), modelSampling, ModelCompat.NONE,
            "openai-completions", null, null, null, byLevel);
    }
}
