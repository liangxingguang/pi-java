package com.pijava.ai.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
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
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevelMap;

/**
 * <b>B155</b>：Google 车道的思考配置（pi {@code google-generative-ai.ts:317-346} 的分支
 * ＋ {@code :401-409} 的写点 ＋ {@code google-shared.ts} 的六件套）。
 *
 * <p>观测面是**真出站请求体**：{@code generationConfig.thinkingConfig.{includeThoughts,
 * thinkingBudget,thinkingLevel}}。</p>
 */
class GoogleThinkingWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** `gemini-2.5-*` 走 **budget** 分支；pi 的 `2.5-flash` 表 high = 24576。 */
    @Test
    void budgetBranchUsesTheFlashTable() throws Exception {
        var body = capture("gemini-2.5-flash", Optional.of(new ThinkingLevel.High()));

        var tc = thinking(body);
        assertThat(tc.path("includeThoughts").asBoolean()).isTrue();
        assertThat(tc.path("thinkingBudget").asInt()).isEqualTo(24576);
        assertThat(tc.has("thinkingLevel")).isFalse();
    }

    /** 级别先经 `clampThinkingLevel`：空级别表不支持 max ⇒ 夹到 high。 */
    @Test
    void levelIsClampedBeforeTheTableLookup() throws Exception {
        var body = capture("gemini-2.5-pro", Optional.of(new ThinkingLevel.Max()));

        // pi 的 `2.5-pro` 表 high = 32768。
        assertThat(thinking(body).path("thinkingBudget").asInt()).isEqualTo(32768);
    }

    /** `minimal` 在两张表里不同（pro 128 / flash 128）—— 逐字取表，不发明。 */
    @Test
    void minimalUsesTheTableValue() throws Exception {
        var body = capture("gemini-2.5-flash", Optional.of(new ThinkingLevel.Minimal()));

        assertThat(thinking(body).path("thinkingBudget").asInt()).isEqualTo(128);
    }

    /** 关卡型（`gemini-3-*`）走 **level** 分支：发 `thinkingLevel`，不发 budget。 */
    @Test
    void levelBranchSendsThinkingLevel() throws Exception {
        var body = capture("gemini-3-pro", Optional.of(new ThinkingLevel.Low()));

        var tc = thinking(body);
        assertThat(tc.path("thinkingLevel").asText()).isEqualTo("LOW");
        assertThat(tc.path("includeThoughts").asBoolean()).isTrue();
        assertThat(tc.has("thinkingBudget")).isFalse();
    }

    /** 没有思考级别 ⇒ 关思考配置：非关卡型 ⇒ `{thinkingBudget: 0}`（**不带** includeThoughts）。 */
    @Test
    void disabledConfigUsesZeroBudgetForBudgetModels() throws Exception {
        var body = capture("gemini-2.5-flash", Optional.empty());

        assertThat(body.path("generationConfig").has("thinkingConfig"))
            .as("关思考支**要发** thinkingConfig（不是缺席）—— 否则本条是空绿").isTrue();
        var tc = thinking(body);
        assertThat(tc.path("thinkingBudget").asInt()).isZero();
        assertThat(tc.has("includeThoughts"))
            .as("pi 的 getDisabledGoogleThinkingConfig 里没有 includeThoughts 键").isFalse();
    }

    /** 非 reasoning 模型 ⇒ 整块不发（pi `:401` 的 `model.reasoning` 门）。 */
    @Test
    void nonReasoningModelSendsNoThinkingConfig() throws Exception {
        var body = capture("gemini-2.5-flash", Optional.of(new ThinkingLevel.High()), false);

        assertThat(body.path("generationConfig").has("thinkingConfig")).isFalse();
    }

    /** 目录映射值不合四级 ⇒ **抛**（pi `google-shared.ts:61-65` 的 `default: throw`）。 */
    @Test
    void unsupportedDirectoryMappingThrows() {
        var map = ThinkingLevelMap.of(new java.util.LinkedHashMap<>(Map.of(
            com.pijava.ai.thinking.ModelThinkingLevel.of(new ThinkingLevel.High()),
            Optional.of("bogus"))));

        assertThatThrownBy(() -> GoogleThinking.resolve(
            modelWithLevels("gemini-2.5-flash", map), new ThinkingLevel.High()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Unsupported Google thinking level mapping")
            .hasMessageContaining("gemini-2.5-flash");
    }

    private static ModelInfo modelWithLevels(String modelName, ThinkingLevelMap levels) {
        return new ModelInfo(ModelId.of("google", modelName), modelName,
            Set.of(ModelCapability.TEXT, ModelCapability.THINKING, ModelCapability.STREAMING),
            1_000_000, 65_536, false, PricingInfo.UNKNOWN, levels,
            Map.of(), Map.of(), ModelCompat.NONE);
    }

    // ── 夹具 ───────────────────────────────────────────────────────

    private static JsonNode thinking(JsonNode body) {
        return body.path("generationConfig").path("thinkingConfig");
    }

    private static JsonNode capture(String modelName, Optional<ThinkingLevel> reasoning)
            throws Exception {
        return capture(modelName, reasoning, true);
    }

    private static JsonNode capture(String modelName, Optional<ThinkingLevel> reasoning,
                                    boolean reasoningModel) throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new GoogleGenerativeAiApi(new ApiOptions(
                server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, Map.of()));
            var request = new StreamRequest(model(modelName, reasoningModel), "sys",
                List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")))),
                List.of(), -1, -1, Map.of(), reasoning);
            try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
                var events = new ArrayList<StreamEvent>();
                while (iter.hasNext() && events.size() < 100) {
                    events.add(iter.next());
                }
            } catch (Exception ignored) {
                // 桩回 400：请求体已录到
            }
            assertThat(server.body()).as("请求必须真的发出去").isNotEmpty();
            return MAPPER.readTree(server.body());
        }
    }

    private static ModelInfo model(String modelName, boolean reasoningModel) {
        Set<ModelCapability> caps = reasoningModel
            ? Set.of(ModelCapability.TEXT, ModelCapability.THINKING, ModelCapability.STREAMING)
            : Set.of(ModelCapability.TEXT, ModelCapability.STREAMING);
        return new ModelInfo(ModelId.of("google", modelName), modelName, caps,
            1_000_000, 65_536, false, PricingInfo.UNKNOWN, ThinkingLevelMap.empty(),
            Map.of(), Map.of(), ModelCompat.NONE);
    }
}
