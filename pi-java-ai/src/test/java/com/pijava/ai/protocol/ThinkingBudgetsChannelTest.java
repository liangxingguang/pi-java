package com.pijava.ai.protocol;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import com.anthropic.models.messages.MessageCreateParams;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.MaxTokensField;
import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.catalog.ThinkingTokenBudgetField;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.thinking.ThinkingBudgets;
import com.pijava.ai.thinking.ThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevelMap;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B128（docs/13 §3.1/§3.4，pi {@code simple-options.ts:67-108}）：自定义
 * {@code thinkingBudgets} 从 {@link StreamRequest} 进三个消费点 ——
 * anthropic budget 分支的 adjust、openai-completions 顶层预算字段、google 的
 * custom 首支。{@code null} ≙ pi 的 {@code undefined}（默认表浅合并）。
 */
class ThinkingBudgetsChannelTest {

    // ── anthropic budget 支 ─────────────────────────────────────────────

    private static final ModelId<?> ANTHROPIC_ID = ModelId.of("anthropic", "claude-sonnet-5");

    /** custom medium=4096 逐字落进 budget_tokens（默认表是 8192）。 */
    @Test
    void anthropicUsesTheCustomMediumBudget() throws Exception {
        var model = thinkingModel(64_000, ModelCompat.NONE);
        var params = anthropicParams(model, Optional.of(new ThinkingLevel.Medium()),
            new ThinkingBudgets(OptionalInt.empty(), OptionalInt.empty(),
                OptionalInt.of(4096), OptionalInt.empty()));

        assertThat(params.thinking().orElseThrow().asEnabled().budgetTokens())
            .isEqualTo(4096L);
    }

    /** 浅合并：只给 medium ⇒ low 仍取默认 2048。 */
    @Test
    void anthropicMergesUnsetLevelsWithDefaults() throws Exception {
        var model = thinkingModel(64_000, ModelCompat.NONE);
        var params = anthropicParams(model, Optional.of(new ThinkingLevel.Low()),
            new ThinkingBudgets(OptionalInt.empty(), OptionalInt.empty(),
                OptionalInt.of(4096), OptionalInt.empty()));

        assertThat(params.thinking().orElseThrow().asEnabled().budgetTokens())
            .isEqualTo(2048L);
    }

    /** xhigh/max 没有自己的槽 ⇒ 查 custom high 档（pi clampReasoning 夹 high）。 */
    @Test
    void anthropicXhighReadsTheCustomHighBucket() throws Exception {
        var model = thinkingModel(64_000, ModelCompat.NONE);
        var budgets = new ThinkingBudgets(OptionalInt.empty(), OptionalInt.empty(),
            OptionalInt.empty(), OptionalInt.of(7000));

        var xhigh = anthropicParams(model, Optional.of(new ThinkingLevel.XHigh()), budgets);
        var max = anthropicParams(model, Optional.of(new ThinkingLevel.Max()), budgets);

        assertThat(xhigh.thinking().orElseThrow().asEnabled().budgetTokens()).isEqualTo(7000L);
        assertThat(max.thinking().orElseThrow().asEnabled().budgetTokens()).isEqualTo(7000L);
    }

    /** null 通道值 ⇒ 默认表（medium 8192）。 */
    @Test
    void anthropicNullBudgetsMeanDefaults() throws Exception {
        var model = thinkingModel(64_000, ModelCompat.NONE);
        var params = anthropicParams(model, Optional.of(new ThinkingLevel.Medium()), null);

        assertThat(params.thinking().orElseThrow().asEnabled().budgetTokens())
            .isEqualTo(8192L);
    }

    // ── openai-completions 顶层预算字段 ─────────────────────────────────

    /** completions 支同源：custom medium=4096 落进显式字段（默认表是 8192）。 */
    @Test
    void completionsUsesTheCustomBudgetInTheTopLevelField() throws Exception {
        var body = completionsBody(Optional.of(new ThinkingLevel.Medium()),
            new ThinkingBudgets(OptionalInt.empty(), OptionalInt.empty(),
                OptionalInt.of(4096), OptionalInt.empty()), 16_384);

        assertThat(body.path("thinking_budget").asInt()).isEqualTo(4096);
    }

    /** custom 值被夹成 0 ⇒ 整键省略（pi {@code budget > 0 ? budget : undefined}）。 */
    @Test
    void completionsOmitsTheFieldWhenCustomBudgetClampsToZero() throws Exception {
        // 模型输出上限 512 ≤ MIN_ANSWER_TOKENS(1024) ⇒ answer-room 夹取恒 0。
        var body = completionsBody(Optional.of(new ThinkingLevel.High()),
            ThinkingBudgets.of(1, 1, 1, 1), 512);

        assertThat(body.has("thinking_budget")).isFalse();
    }

    // ── google custom 首支（pi getGoogleBudget :431-435）────────────────

    private static ModelInfo googleModel(String id) {
        return new ModelInfo(ModelId.of("google", id), id,
            Set.of(ModelCapability.TEXT, ModelCapability.THINKING),
            1_000_000, 64_000, false, PricingInfo.UNKNOWN,
            ThinkingLevelMap.empty(), Map.of(), Map.of(), ModelCompat.NONE);
    }

    /** custom 原样返回：2.5-pro 的 high 硬编码表是 32768，custom 7000 赢。 */
    @Test
    void googleCustomBudgetWinsOverTheHardcodedTable() {
        var model = googleModel("gemini-2.5-pro");
        var budgets = new ThinkingBudgets(OptionalInt.empty(), OptionalInt.empty(),
            OptionalInt.empty(), OptionalInt.of(7000));

        assertThat(GoogleThinking.budget(model, GoogleThinking.Level.HIGH, budgets))
            .isEqualTo(7000);
    }

    /** custom 不做任何钳制：{@code 0} 与哨兵 {@code -1} 都原样上线。 */
    @Test
    void googleCustomBudgetPassesZeroAndSentinelThroughRaw() {
        var model = googleModel("gemini-2.5-pro");

        var zero = new ThinkingBudgets(OptionalInt.empty(), OptionalInt.empty(),
            OptionalInt.empty(), OptionalInt.of(0));
        var sentinel = new ThinkingBudgets(OptionalInt.empty(), OptionalInt.empty(),
            OptionalInt.empty(), OptionalInt.of(-1));

        assertThat(GoogleThinking.budget(model, GoogleThinking.Level.HIGH, zero)).isZero();
        assertThat(GoogleThinking.budget(model, GoogleThinking.Level.HIGH, sentinel))
            .isEqualTo(-1);
    }

    /** custom 缺该档 ⇒ 回落硬编码表；整个 custom 为 null 同。 */
    @Test
    void googleFallsBackToTheHardcodedTablesWhenLevelUnsetOrNull() {
        var model = googleModel("gemini-2.5-pro");
        var partial = new ThinkingBudgets(OptionalInt.of(1), OptionalInt.empty(),
            OptionalInt.empty(), OptionalInt.empty());

        assertThat(GoogleThinking.budget(model, GoogleThinking.Level.HIGH, partial))
            .isEqualTo(32768);
        assertThat(GoogleThinking.budget(model, GoogleThinking.Level.HIGH, null))
            .isEqualTo(32768);
        assertThat(GoogleThinking.budget(model, GoogleThinking.Level.MEDIUM, null))
            .isEqualTo(8192);
    }

    // ── 夹具 ────────────────────────────────────────────────────────────

    private static ModelInfo thinkingModel(int maxOutput, ModelCompat compat) {
        return new ModelInfo(ANTHROPIC_ID, "Claude",
            Set.of(ModelCapability.TEXT, ModelCapability.THINKING),
            200_000, maxOutput, false, PricingInfo.UNKNOWN,
            ThinkingLevelMap.empty(), Map.of(), Map.of(), compat);
    }

    private static StreamRequest request(ModelInfo model, List<Message> messages,
                                          Optional<ThinkingLevel> reasoning,
                                          ThinkingBudgets budgets) {
        return new StreamRequest(model, null, messages, List.of(), -1, -1, reasoning, budgets);
    }

    private static MessageCreateParams anthropicParams(ModelInfo model,
                                                        Optional<ThinkingLevel> reasoning,
                                                        ThinkingBudgets budgets) throws Exception {
        var options = new ApiOptions(
            "https://api.teamorouter.cn", "sk-test", Duration.ofSeconds(10), 1, Map.of());
        var api = new AnthropicMessagesApi(options, "ANTHROPIC_API_KEY");
        Method method = AnthropicMessagesApi.class.getDeclaredMethod(
            "buildParams", StreamRequest.class);
        method.setAccessible(true);
        var request = request(model,
            List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")))),
            reasoning, budgets);
        return (MessageCreateParams) method.invoke(api, request);
    }

    private static JsonNode completionsBody(Optional<ThinkingLevel> reasoning,
                                             ThinkingBudgets budgets,
                                             int maxOutput) throws Exception {
        var compat = new ModelCompat(false, null, true, false, null, null, null, null, null,
            true, MaxTokensField.MAX_COMPLETION_TOKENS, null, null, null,
            null, null, ThinkingTokenBudgetField.THINKING_BUDGET, null);
        var model = new ModelInfo(ModelId.of("test", "reasoner"), "reasoner",
            Set.of(ModelCapability.TEXT, ModelCapability.THINKING),
            200_000, maxOutput, false, PricingInfo.UNKNOWN,
            ThinkingLevelMap.empty(), Map.of(), Map.of(), compat);
        var mapper = new ObjectMapper();
        try (var server = new RecordingHttpServer()) {
            var api = new OpenAICompletionsApi(
                new ApiOptions(server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, Map.of()));
            try (var iter = api.streamBlocking(request(model,
                    List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")))),
                    reasoning, budgets), ApiOptions.defaults())) {
                while (iter.hasNext()) {
                    iter.next();
                }
            } catch (Exception ignored) {
                // 桩恒回 400：请求体已录到。
            }
            return mapper.readTree(server.body());
        }
    }
}
