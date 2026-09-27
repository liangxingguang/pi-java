package com.pijava.ai.protocol;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
import com.pijava.ai.thinking.ThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevelMap;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包 A-10：顶层思考预算字段（pi {@code openai-completions.ts:870-871}／{@code :972-978}）。
 *
 * <p>为什么它存在（pi {@code types.ts:718-724} 的注释）：这些端点（vLLM／Qwen-DashScope／
 * llama.cpp）上**推理与答案共用 {@code max_tokens}** ⇒ 不设预算时，推理重的回合可以把整个
 * 响应吃光、一个答案与工具调用都不留。</p>
 *
 * <p>⚠️ 它与 {@code thinkingFormat}（A-09）**无关**：同一台服务器可能同时服务
 * zai／qwen／chat-template 三种形态的模型（pi {@code :972-975} 的注释）⇒ 本字段的落点在
 * 形态链条之外，「openai」形态也有。故它随本包落地，A-09 不重复做。</p>
 *
 * <p>可达性如实说：pi 的 {@code detectCompat:1662-1663} 把这两个字段恒置为
 * {@code supportsThinkingTokenBudget: false} / {@code thinkingTokenBudgetField: undefined}
 * （注释：{@code not set on the generated catalog}）⇒ **只有 models.json 显式写才可达**，
 * 内置目录一个都不标。本文件的夹具因此都用**显式 compat**（等价于用户写了 models.json）。</p>
 */
class ThinkingTokenBudgetWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** ★ RED-7：显式字段名 ⇒ body 里出现该键，值是「级别预算夹到天花板」。 */
    @Test
    void explicitFieldNameLandsWithTheClampedBudget() throws Exception {
        var body = body(compat(ThinkingTokenBudgetField.THINKING_BUDGET, null),
            Optional.of(new ThinkingLevel.Medium()), 16_384);

        // medium 的默认预算 8192；天花板 16384 − 1024 ⇒ 夹取惰性 ⇒ 8192
        assertThat(body.path("thinking_budget").asInt()).isEqualTo(8192);
    }

    /** ★ 配对：没有思考级别 ⇒ **整条不发**（pi 的 {@code !options.reasoningEffort} 提前返回）。 */
    @Test
    void withoutAReasoningLevelTheFieldIsAbsent() throws Exception {
        var body = body(compat(ThinkingTokenBudgetField.THINKING_BUDGET, null),
            Optional.empty(), 16_384);

        assertThat(body.has("thinking_budget")).isFalse();
    }

    /** 布尔别名 ≙ {@code "thinking_token_budget"}（vLLM 拼写，pi {@code :1004-1010}）。 */
    @Test
    void theBooleanAliasMeansTheVllmSpelling() throws Exception {
        var body = body(compat(null, Boolean.TRUE),
            Optional.of(new ThinkingLevel.Low()), 16_384);

        assertThat(body.path("thinking_token_budget").asInt()).isEqualTo(2048);
    }

    /** 显式字段名**优先于**别名（pi 的先判顺序）—— 两个都写时用显式的那个。 */
    @Test
    void theExplicitFieldNameWinsOverTheAlias() throws Exception {
        var body = body(compat(ThinkingTokenBudgetField.THINKING_BUDGET_TOKENS, Boolean.TRUE),
            Optional.of(new ThinkingLevel.Low()), 16_384);

        assertThat(body.path("thinking_budget_tokens").asInt()).isEqualTo(2048);
        assertThat(body.has("thinking_token_budget")).isFalse();
    }

    /** 天花板真参与运算：模型上限 3000 ⇒ {@code min(8192, 3000 − 1024) = 1976}。 */
    @Test
    void theBudgetIsClampedToTheAnswerRoom() throws Exception {
        var body = body(compat(ThinkingTokenBudgetField.THINKING_BUDGET, null),
            Optional.of(new ThinkingLevel.Medium()), 3_000);

        assertThat(body.path("thinking_budget").asInt()).isEqualTo(1976);
    }

    /** pi 的第二道门 {@code !model.reasoning}：非推理模型即使写了 compat 也不发。 */
    @Test
    void aNonReasoningModelSendsNothing() throws Exception {
        var model = model(compat(ThinkingTokenBudgetField.THINKING_BUDGET, null),
            16_384, Set.of(ModelCapability.TEXT));
        var body = MAPPER.readTree(outbound(model, Optional.of(new ThinkingLevel.Medium())));

        assertThat(body.has("thinking_budget")).isFalse();
    }

    /** 对照组：compat 里两个字段都缺席（探测值）⇒ 一个键都不发。 */
    @Test
    void withoutTheCompatNothingIsSent() throws Exception {
        var body = body(compat(null, null), Optional.of(new ThinkingLevel.High()), 16_384);

        assertThat(body.has("thinking_budget")).isFalse();
        assertThat(body.has("thinking_token_budget")).isFalse();
        assertThat(body.has("thinking_budget_tokens")).isFalse();
    }

    // ── 夹具 ────────────────────────────────────────────────────────────

    private static JsonNode body(ModelCompat compat, Optional<ThinkingLevel> reasoning,
                                 int maxOutput) throws Exception {
        return MAPPER.readTree(outbound(model(compat, maxOutput, Set.of(
            ModelCapability.TEXT, ModelCapability.THINKING)), reasoning));
    }

    private static String outbound(ModelInfo model, Optional<ThinkingLevel> reasoning)
            throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new OpenAICompletionsApi(
                new ApiOptions(server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, Map.of()));
            var request = new StreamRequest(model, "be brief",
                List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")))),
                List.of(), -1, -1, Map.of(), reasoning);
            try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
                while (iter.hasNext()) {
                    iter.next();
                }
            } catch (Exception ignored) {
                // 桩恒回 400：请求体已录到
            }
            assertThat(server.body()).as("请求必须真的发出去").isNotEmpty();
            return server.body();
        }
    }

    /**
     * 显式 compat（≙ 用户在 models.json 里写 compat）—— 其余位与
     * {@code ModelCompat.NONE} 同值，避免夹具顺带变更别的行为。
     */
    private static ModelCompat compat(ThinkingTokenBudgetField field, Boolean alias) {
        return new ModelCompat(false, null, true, false, null, null, null, null, null,
            true, MaxTokensField.MAX_COMPLETION_TOKENS, null, null, null,
            null, null, field, alias);
    }

    private static ModelInfo model(ModelCompat compat, int maxOutput,
                                   Set<ModelCapability> capabilities) {
        return new ModelInfo(ModelId.of("test", "reasoner"), "reasoner",
            capabilities, 200_000, maxOutput, false, PricingInfo.UNKNOWN,
            ThinkingLevelMap.empty(), Map.of(), Map.of(), compat);
    }
}
