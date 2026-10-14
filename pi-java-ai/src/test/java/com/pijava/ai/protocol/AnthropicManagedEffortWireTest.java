package com.pijava.ai.protocol;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.provider.BuiltinModelCatalogs;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ThinkingLevel;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B99/B110（docs/13 §1.2/§3.5，pi {@code anthropic-mid-conversation-effort.test.ts}）：
 * managed effort 模型（目录 {@code supportsMidConvoEffort:true}）的请求体两 beta、
 * 无条件 {@code adaptive + block_binding} ＋ 恒 high 的顶层 output_config、temperature
 * 抑制，以及 start 帧起盖上的 providerThinkingLevel。
 */
class AnthropicManagedEffortWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 批 3a 的目录数据已经烘入 flag；解析链接通后 9 个 Claude 5 模型带值。 */
    @Test
    void catalogDataCarriesTheManagedFlag() {
        var model = managedModel();
        assertThat(model.compat().supportsMidConvoEffort()).isTrue();
    }

    /** 夹具 1：managed 请求恒发 adaptive＋block_binding＋output_config.high，无 reasoning 也发。 */
    @Test
    void managedModelAlwaysSendsAdaptiveBlockBindingAndHighEffort() throws Exception {
        var captured = capture(managedModel(), Optional.empty(), 0.5, Map.of());

        var body = captured.body();
        var thinking = body.path("thinking");
        assertThat(thinking.path("type").asText()).isEqualTo("adaptive");
        assertThat(thinking.path("display").asText()).isEqualTo("summarized");
        assertThat(thinking.path("block_binding").path("prefix_mismatch_behavior").asText())
            .isEqualTo("drop_block");
        // 顶层 output_config 恒 high（与用户当前级别无关）。
        assertThat(body.path("output_config").path("effort").asText()).isEqualTo("high");
        // managed 分支不吃 thinkingBudgets ⇒ 不抬 max_tokens。
        assertThat(body.has("max_tokens")).isTrue();
        // 两 beta 都在 anthropic-beta 头里。
        assertThat(captured.betaHeader())
            .contains("mid-conversation-output-config-2026-07-01")
            .contains("thinking-binding-controls-2026-08-01");
        // temperature 四重合取的第三项：即便请求带 0.5 也不发。
        assertThat(body.has("temperature")).isFalse();
    }

    /** 反直觉点：无 reasoning（thinkingEnabled=false）时 managed 体照样发（pi :1233 顶支）。 */
    @Test
    void managedBodyIsSentWithoutReasoningToo() throws Exception {
        var captured = capture(managedModel(), Optional.empty(), -1, Map.of());

        assertThat(captured.body().path("thinking").path("block_binding").isMissingNode())
            .isFalse();
        assertThat(captured.body().path("output_config").path("effort").asText())
            .isEqualTo("high");
    }

    /** 夹具 13：盖章在 start 帧即在；无 reasoning ⇒ high。 */
    @Test
    void startFrameIsStampedHighWithoutReasoning() throws Exception {
        var captured = capture(managedModel(), Optional.empty(), -1, Map.of());

        var start = captured.events().stream()
            .filter(e -> e instanceof StreamEvent.Start)
            .map(e -> (StreamEvent.Start) e).findFirst().orElseThrow();
        assertThat(start.partial().providerThinkingLevel()).isEqualTo("high");
    }

    /** 有级别 ⇒ 盖章＝mapLevelToEffort（fable-5-1 的 medium 映射，默认 medium）。 */
    @Test
    void stampFollowsTheEffortMappingWhenReasoningPresent() throws Exception {
        var captured = capture(managedModel(), Optional.of(new ThinkingLevel.Medium()), -1,
            Map.of());

        var start = captured.events().stream()
            .filter(e -> e instanceof StreamEvent.Start)
            .map(e -> (StreamEvent.Start) e).findFirst().orElseThrow();
        assertThat(start.partial().providerThinkingLevel()).isEqualTo("medium");
    }

    /** 非 managed 模型（即便 forceAdaptive）不盖章、不带 block_binding 与两 beta。 */
    @Test
    void nonManagedAdaptiveModelIsNotStampedAndHasNoBlockBinding() throws Exception {
        var model = BuiltinModelCatalogs.catalog("anthropic")
            .find(ModelId.of("anthropic", "claude-opus-4-8")).orElseThrow();
        var captured = capture(model, Optional.of(new ThinkingLevel.High()), -1, Map.of());

        assertThat(captured.body().path("thinking").path("type").asText())
            .isEqualTo("adaptive");
        assertThat(captured.body().has("block_binding")).isFalse();
        assertThat(captured.body().path("thinking").has("block_binding")).isFalse();
        assertThat(captured.betaHeader())
            .doesNotContain("mid-conversation-output-config-2026-07-01")
            .doesNotContain("thinking-binding-controls-2026-08-01");
        var start = captured.events().stream()
            .filter(e -> e instanceof StreamEvent.Start)
            .map(e -> (StreamEvent.Start) e).findFirst().orElseThrow();
        assertThat(start.partial().providerThinkingLevel()).isNull();
    }

    /**
     * 夹具 7（pi 夹具同式）：custom models.json 单设 flag、不设 forceAdaptive 且开了
     * 思考 ⇒ interleaved 与两个 managed beta 在<b>同一个</b>头里；managed 顶支仍
     * 走 raw adaptive（分支顺序在 forceAdaptive 判定之前）。
     */
    @Test
    void flagWithoutAdaptiveCoexistsWithTheInterleavedBeta() throws Exception {
        var compat = new com.pijava.ai.catalog.ModelCompat(false, null, true, false,
            null, null, null, null, null, true,
            null, null, null, null, null, null, null, null, null, null,
            Map.of(), Map.of(), null, null, null, null, null, true, null,
            null, null,
            null, null, null, null, null, null, null, null,
            Boolean.TRUE);
        var model = new com.pijava.ai.catalog.ModelInfo(
            ModelId.of("anthropic", "custom-managed"), "Custom Managed",
            java.util.Set.of(com.pijava.ai.model.ModelCapability.TEXT,
                com.pijava.ai.model.ModelCapability.THINKING),
            200_000, 64_000, false,
            com.pijava.ai.model.PricingInfo.UNKNOWN,
            com.pijava.ai.thinking.ThinkingLevelMap.empty(),
            Map.of(), Map.of(), compat);

        var captured = capture(model, Optional.of(new ThinkingLevel.High()), -1, Map.of());

        assertThat(captured.betaHeader())
            .contains("interleaved-thinking-2025-05-14")
            .contains("mid-conversation-output-config-2026-07-01")
            .contains("thinking-binding-controls-2026-08-01");
        assertThat(captured.body().path("thinking").path("block_binding").isMissingNode())
            .isFalse();
    }

    /** 夹具 8（裁决③）：显式 anthropic-beta 头 ⇒ 自动 beta 列表整组撤下。 */
    @Test
    void explicitAnthropicBetaHeaderSuppressesTheAutoBetaList() throws Exception {
        var captured = capture(managedModel(), Optional.empty(), -1,
            Map.of("anthropic-beta", "custom-feature-2025-01-01"));

        // SDK client 仍把显式头发出去；自动字面量一个都不许混入。
        assertThat(captured.betaHeader()).isEqualTo("custom-feature-2025-01-01");
    }

    // ── 夹具 ────────────────────────────────────────────────────────────

    private static ModelInfo managedModel() {
        return BuiltinModelCatalogs.catalog("anthropic")
            .find(ModelId.of("anthropic", "claude-fable-5-1")).orElseThrow();
    }

    private record Captured(JsonNode body, String betaHeader, List<StreamEvent> events) {}

    private static Captured capture(ModelInfo model, Optional<ThinkingLevel> reasoning,
                                     double temperature, Map<String, String> extraHeaders)
            throws Exception {
        var messages = List.<Message>of(new Message.UserMessage(
            List.of(new ContentBlock.TextContent("hi"))));
        var extra = new java.util.LinkedHashMap<String, Object>();
        if (extraHeaders != null && !extraHeaders.isEmpty()) {
            extra.put("headers", extraHeaders);
        }
        try (var server = new RecordingHttpServer()) {
            var api = new AnthropicMessagesApi(
                new ApiOptions(server.baseUrl(), "test-key",
                    java.time.Duration.ofSeconds(5), 0, extra),
                "ANTHROPIC_API_KEY");
            var request = new StreamRequest(model, "be brief", messages, List.of(),
                -1, temperature, reasoning);
            var events = new java.util.ArrayList<StreamEvent>();
            try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
                while (iter.hasNext()) {
                    events.add(iter.next());
                }
            } catch (Exception ignored) {
                // 桩恒回 400：请求体已录到。
            }
            return new Captured(MAPPER.readTree(server.body()),
                server.headers().getOrDefault("anthropic-beta", ""), events);
        }
    }
}
