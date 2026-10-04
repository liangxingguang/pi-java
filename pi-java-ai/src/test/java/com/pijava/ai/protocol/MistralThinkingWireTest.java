package com.pijava.ai.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
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
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevelMap;

/**
 * <b>B155</b>：Mistral 车道的思考配置（pi {@code mistral-conversations.ts:200-208} 的
 * streamSimple ＋ {@code :898-916} 的三件套 ＋ {@code :525-526} 的写点）。
 *
 * <p>两个键**互斥**：id 命中白名单 ⇒ {@code reasoningEffort}；否则（只要 reasoning）
 * ⇒ {@code promptMode:"reasoning"}。</p>
 */
class MistralThinkingWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** `mistral-large` 不在白名单 ⇒ 走 `promptMode`（本仓内置目录里它带 THINKING）。 */
    @Test
    void nonWhitelistedReasoningModelSendsPromptMode() throws Exception {
        var body = capture("mistral-large", Optional.of(new ThinkingLevel.High()),
            ThinkingLevelMap.empty());

        assertThat(body.path("promptMode").asText()).isEqualTo("reasoning");
        assertThat(body.has("reasoningEffort")).isFalse();
    }

    /** 白名单 id ⇒ 走 `reasoningEffort`（`mapReasoningEffort`：映射优先，缺省 "high"）。 */
    @Test
    void whitelistedModelSendsReasoningEffort() throws Exception {
        var body = capture("mistral-small-latest", Optional.of(new ThinkingLevel.High()),
            ThinkingLevelMap.empty());

        assertThat(body.path("reasoningEffort").asText()).isEqualTo("high");
        assertThat(body.has("promptMode")).isFalse();
    }

    /** 目录映射优先于缺省的 `"high"`（pi `:915` 的 `thinkingLevelMap?.[level] ?? "high"`）。 */
    @Test
    void whitelistedModelHonoursTheDirectoryMapping() throws Exception {
        var map = ThinkingLevelMap.of(new LinkedHashMap<>(Map.of(
            ModelThinkingLevel.of(new ThinkingLevel.High()), Optional.of("none"))));
        var body = capture("mistral-small-latest", Optional.of(new ThinkingLevel.High()), map);

        assertThat(body.path("reasoningEffort").asText()).isEqualTo("none");
    }

    /** 级别被夹成 off（空表不支持 xhigh ⇒ 夹到 high；这里用一个不支持 reasoning 的模型更直接）。 */
    @Test
    void noReasoningLevelSendsNeitherKey() throws Exception {
        var body = capture("mistral-large", Optional.empty(), ThinkingLevelMap.empty());

        assertThat(body.has("promptMode")).isFalse();
        assertThat(body.has("reasoningEffort")).isFalse();
    }

    /** 非 reasoning 模型 ⇒ 两个键都不发（pi 的 `model.reasoning` 门）。 */
    @Test
    void nonReasoningModelSendsNeitherKey() throws Exception {
        var body = capture("mistral-small", Optional.of(new ThinkingLevel.High()),
            ThinkingLevelMap.empty(), false);

        assertThat(body.has("promptMode")).isFalse();
        assertThat(body.has("reasoningEffort")).isFalse();
    }

    // ── 夹具 ───────────────────────────────────────────────────────

    private static JsonNode capture(String modelName, Optional<ThinkingLevel> reasoning,
                                    ThinkingLevelMap levels) throws Exception {
        return capture(modelName, reasoning, levels, true);
    }

    private static JsonNode capture(String modelName, Optional<ThinkingLevel> reasoning,
                                    ThinkingLevelMap levels, boolean reasoningModel)
            throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new MistralConversationsApi(new ApiOptions(
                server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, Map.of()));
            var request = new StreamRequest(model(modelName, levels, reasoningModel), "sys",
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

    private static ModelInfo model(String modelName, ThinkingLevelMap levels,
                                   boolean reasoningModel) {
        Set<ModelCapability> caps = reasoningModel
            ? Set.of(ModelCapability.TEXT, ModelCapability.THINKING, ModelCapability.STREAMING)
            : Set.of(ModelCapability.TEXT, ModelCapability.STREAMING);
        return new ModelInfo(ModelId.of("mistral", modelName), modelName, caps,
            128_000, 8_192, false, PricingInfo.UNKNOWN, levels,
            Map.of(), Map.of(), ModelCompat.NONE);
    }
}
