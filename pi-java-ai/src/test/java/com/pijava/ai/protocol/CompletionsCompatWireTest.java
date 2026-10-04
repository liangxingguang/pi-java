package com.pijava.ai.protocol;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.openai.models.chat.completions.ChatCompletionCreateParams;

import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.BuiltinCatalog;
import com.pijava.ai.catalog.MaxTokensField;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包 A7c 的端到端钉子：内置目录的 compat ＋ 请求期探测真的落到了 completions 的线上体
 * （{@code 原 docs/53 §3 F3} 的 D3，以及 R5 的两个字段）。
 *
 * <p>每一条都是「旧代码必红」：包 A7 之前 {@code OpenAICompletionsMessageConverter} 恒发
 * {@code max_completion_tokens}、从不发 {@code store}、指令消息恒 {@code system} 角色。</p>
 */
class CompletionsCompatWireTest {

    private static ModelInfo builtIn(ModelCatalog catalog, String provider, String modelName) {
        return catalog.find(ModelId.of(provider, modelName)).orElseThrow();
    }

    /** 带前导系统提示的转录（为了让指令消息真的落线，developer 角色的钉子需要它）。 */
    private static StreamRequest request(ModelInfo model) {
        return new StreamRequest(
            model, "be brief",
            List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")))),
            List.of(), 512, -1, Map.of(), Optional.empty());
    }

    private static ChatCompletionCreateParams build(ModelInfo model, String baseUrl) {
        return OpenAICompletionsMessageConverter.buildParams(
            request(model), "openai-completions", baseUrl);
    }

    // ── D3：输出上限的字段名（maxTokensField）───────────────────

    @Test
    void theNativeDeepSeekModelUsesThePlainMaxTokensField() {
        var model = builtIn(BuiltinCatalog.deepseekModels(), "deepseek", "deepseek-v4-pro");

        var params = build(model, "https://api.deepseek.com");

        assertThat(params.maxTokens()).contains(512L);
        assertThat(params.maxCompletionTokens()).isEmpty();
    }

    @Test
    void openAiKeepstheCompletionTokensField() {
        // 配对：同一个 maxTokens 在标准端点上仍走 OpenAI 自己的字段名。
        var model = builtIn(BuiltinCatalog.openaiModels(), "openai", "gpt-5");

        var params = build(model, "https://api.openai.com/v1");

        assertThat(params.maxCompletionTokens()).contains(512L);
        assertThat(params.maxTokens()).isEmpty();
    }

    @Test
    void anExplicitMaxTokensFieldWinsOverTheDetection() {
        // `explicit ?? detected`：显式写的值压过 deepseek 的探测。
        var detected = builtIn(BuiltinCatalog.deepseekModels(), "deepseek", "deepseek-v4-pro");
        var explicit = new ModelInfo(detected.id(), detected.displayName(),
            detected.capabilities(), detected.maxInputTokens(), detected.maxOutputTokens(),
            detected.deprecated(), detected.pricing(), detected.thinkingLevelMap(),
            detected.headers(), detected.samplingParams(),
            new ModelCompat(false, null, true, false, null, null, null, null,
                null, true, MaxTokensField.MAX_COMPLETION_TOKENS, null, null, null));

        assertThat(build(detected, "https://api.deepseek.com").maxTokens()).contains(512L);
        assertThat(build(explicit, "https://api.deepseek.com").maxCompletionTokens())
            .contains(512L);
    }

    // ── R5：store 与 developer 角色 ─────────────────────────────

    @Test
    void storeIsOmittedForNonStandardEndpointsAndExplicitlyFalseForStandardOnes() {
        var deepseek = builtIn(BuiltinCatalog.deepseekModels(), "deepseek", "deepseek-v4-pro");
        var openai = builtIn(BuiltinCatalog.openaiModels(), "openai", "gpt-5");

        assertThat(build(deepseek, "https://api.deepseek.com").store()).isEmpty();
        // pi :832-834 —— 支持就发 `store: false`（显式退出服务端留存）。
        assertThat(build(openai, "https://api.openai.com/v1").store()).contains(false);
    }

    @Test
    void theInstructionRoleFollowsTheModelAndTheEndpoint() {
        var reasoning = builtIn(BuiltinCatalog.openaiModels(), "openai", "gpt-5");
        var chat = builtIn(BuiltinCatalog.deepseekModels(), "deepseek", "deepseek-v4-flash");

        // pi :1225 —— 推理模型 ＋ 标准端点 ⇒ developer 角色。
        assertThat(instructionIsDeveloper(build(reasoning, "https://api.openai.com/v1"))).isTrue();
        // 非推理模型 ⇒ 一律 system（即使端点支持 developer）。
        assertThat(instructionIsDeveloper(build(chat, "https://api.openai.com/v1"))).isFalse();
    }

    /** 第一条指令消息是不是 {@code developer} 角色（没有指令消息就直接炸，不静默通过）。 */
    private static boolean instructionIsDeveloper(ChatCompletionCreateParams params) {
        var instruction = params.messages().stream()
            .filter(m -> m.isSystem() || m.isDeveloper())
            .findFirst();
        assertThat(instruction).isPresent();
        return instruction.orElseThrow().isDeveloper();
    }
}
