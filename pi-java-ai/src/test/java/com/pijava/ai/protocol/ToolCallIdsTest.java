package com.pijava.ai.protocol;

import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.utils.ShortHash;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包 B14 步2（docs/47 §5）：五车道 toolCall id 归一器，一组文件钉五个。
 *
 * <p>先写桩跑红，再写真实现跑绿（TDD）。所有期望值都从 pi 语义**现场推导**
 * （{@code ShortHash.of(...)} 现算），不硬编码 pi 之外的魔数。</p>
 */
class ToolCallIdsTest {

    private static final ModelId<?> TARGET_ANTHROPIC = ModelId.of("anthropic", "claude-sonnet-5");

    /**
     * 源助手消息桩：第十参构造器。source 的 api/provider 只被 Responses 车道读到
     * （{@code isForeignToolCall}），其余车道忽略。
     */
    private static final Message.AssistantMessage SOURCE = new Message.AssistantMessage(
            List.of(new ContentBlock.TextContent("x")),
            "stop", null,
            "openai-responses", "github-copilot", "gpt-5",
            null, null, null, null);

    // --------------------------------------------------------------- Anthropic (P17)

    @Test
    void anthropicReplacesPipeWithUnderscore() {
        var n = AnthropicToolCallIds.create();
        assertThat(n.normalize("call_123|fc_123", TARGET_ANTHROPIC, SOURCE))
                .isEqualTo("call_123_fc_123");
    }

    @Test
    void anthropicLongFcId() {
        var n = AnthropicToolCallIds.create();
        assertThat(n.normalize("call_123|fc_9d8e7f6a5b4c3d2e1f0a", TARGET_ANTHROPIC, SOURCE))
                .isEqualTo("call_123_fc_9d8e7f6a5b4c3d2e1f0a");
    }

    @Test
    void anthropicLengthBoundary() {
        var n = AnthropicToolCallIds.create();
        var legal63 = "a".repeat(63);
        assertThat(n.normalize(legal63, TARGET_ANTHROPIC, SOURCE)).isEqualTo(legal63);
        assertThat(n.normalize("a".repeat(65), TARGET_ANTHROPIC, SOURCE))
                .isEqualTo("a".repeat(64));
    }

    @Test
    void anthropicLegalIdUnchanged() {
        var n = AnthropicToolCallIds.create();
        assertThat(n.normalize("toolu_01ABCdefGHIjklMNOpqrSTUvwxYZ-__99", TARGET_ANTHROPIC, SOURCE))
                .isEqualTo("toolu_01ABCdefGHIjklMNOpqrSTUvwxYZ-__99");
    }

    @Test
    void anthropicEmptyString() {
        var n = AnthropicToolCallIds.create();
        assertThat(n.normalize("", TARGET_ANTHROPIC, SOURCE)).isEmpty();
    }

    @Test
    void anthropicNonAscii() {
        var n = AnthropicToolCallIds.create();
        // 每个非法 UTF-16 码元一个 '_'：'中'、'文' 各 1 char ⇒ 各 1 '_'
        assertThat(n.normalize("call中文1", TARGET_ANTHROPIC, SOURCE)).isEqualTo("call__1");
    }

    /**
     * 变异探针的宿主用例：空格在 pi 的字符类里**不合法** ⇒ 必须换成 '_'。
     * 把 {@code [^a-zA-Z0-9_-]} 变异成「空格合法」的形式时，本条**恰好红**
     * （{@code call 中文1} 会原样保留空格）；对不含空格的 id 两条正则等价。
     */
    @Test
    void anthropicSpaceIsIllegal() {
        var n = AnthropicToolCallIds.create();
        assertThat(n.normalize("call 123", TARGET_ANTHROPIC, SOURCE)).isEqualTo("call_123");
    }

    // --------------------------------------------------------------- Google (P19)

    @Test
    void googleGateOpenGemini3() {
        var n = GoogleToolCallIds.create();
        var target = ModelId.of("google", "gemini-3-pro");
        assertThat(n.normalize("call_123|fc_123", target, SOURCE))
                .isEqualTo("call_123_fc_123");
    }

    @Test
    void googleGateOpenClaudeOnGoogleLane() {
        var n = GoogleToolCallIds.create();
        var target = ModelId.of("google", "claude-sonnet-4-5");
        assertThat(n.normalize("call_123|fc_123", target, SOURCE))
                .isEqualTo("call_123_fc_123");
    }

    @Test
    void googleGateClosedGemini2() {
        var n = GoogleToolCallIds.create();
        var target = ModelId.of("google", "gemini-2.5-pro");
        assertThat(n.normalize("call_123|fc_123", target, SOURCE))
                .isEqualTo("call_123|fc_123");
    }

    @Test
    void googleGateClosedOtherModel() {
        var n = GoogleToolCallIds.create();
        var target = ModelId.of("google", "other-model");
        assertThat(n.normalize("call_123|fc_123", target, SOURCE))
                .isEqualTo("call_123|fc_123");
    }

    // --------------------------------------------------------------- Completions (P20)

    @Test
    void completionsOpenaiShortIdUnchanged() {
        var n = CompletionsToolCallIds.create();
        var target = ModelId.of("openai", "gpt-5");
        assertThat(n.normalize("call_123", target, SOURCE)).isEqualTo("call_123");
    }

    @Test
    void completionsOpenaiTruncatesAt40() {
        var n = CompletionsToolCallIds.create();
        var target = ModelId.of("openai", "gpt-5");
        assertThat(n.normalize("a".repeat(45), target, SOURCE)).isEqualTo("a".repeat(40));
    }

    @Test
    void completionsNonOpenaiNoPipeUnchanged() {
        var n = CompletionsToolCallIds.create();
        var target = ModelId.of("github-copilot", "gpt-5");
        assertThat(n.normalize("a".repeat(45), target, SOURCE)).isEqualTo("a".repeat(45));
    }

    @Test
    void completionsPipeCombinedShort() {
        var n = CompletionsToolCallIds.create();
        var target = ModelId.of("openai", "gpt-5");
        assertThat(n.normalize("call_123|fc_456", target, SOURCE))
                .isEqualTo("call_123_fc_456");
    }

    @Test
    void completionsPipeCombinedLongUsesHash() {
        var n = CompletionsToolCallIds.create();
        var target = ModelId.of("openai", "gpt-5");
        // callId 30 字符 + '_' + itemId 30 字符 = 61 > 40 ⇒ hash 路径
        var id = "c".repeat(30) + "|" + "f".repeat(30);
        var callId = "c".repeat(30);
        var hash = ShortHash.of(id).substring(0, 8);
        // JS slice(0, end) 对超界 end 截到串长 —— Java 需显式 clamp
        var prefix = callId.substring(0, Math.min(callId.length(),
                Math.max(1, 40 - hash.length() - 1)));
        assertThat(n.normalize(id, target, SOURCE)).isEqualTo(prefix + "_" + hash);
    }

    @Test
    void completionsPipeCallIdAtLeast32Uses31CharPrefix() {
        var n = CompletionsToolCallIds.create();
        var target = ModelId.of("openai", "gpt-5");
        var id = "c".repeat(35) + "|" + "f".repeat(10);
        var hash = ShortHash.of(id).substring(0, 8);
        var expected = "c".repeat(31) + "_" + hash;
        assertThat(n.normalize(id, target, SOURCE)).isEqualTo(expected);
        assertThat(expected).hasSize(40);
    }

    @Test
    void completionsMultiplePipesSliceNotSplit() {
        var n = CompletionsToolCallIds.create();
        var target = ModelId.of("openai", "gpt-5");
        // JS slice(separatorIndex+1) 是第一个 '|' 之后的全部（含后续 '|'），
        // 随后 replaceAll 把 '|' 换 '_' ⇒ a|b|c ⇒ a_b_c
        assertThat(n.normalize("a|b|c", target, SOURCE)).isEqualTo("a_b_c");
    }

    // --------------------------------------------------------------- Responses (P21)

    @Test
    void responsesOpenaiNoPipeUnchanged() {
        var n = ResponsesToolCallIds.create("openai-responses");
        var target = ModelId.of("openai", "gpt-5");
        assertThat(n.normalize("fc_9d8e7f6a5b4c3d2e1f0a", target, SOURCE))
                .isEqualTo("fc_9d8e7f6a5b4c3d2e1f0a");
    }

    @Test
    void responsesOpenaiTrailingUnderscoresStripped() {
        var n = ResponsesToolCallIds.create("openai-responses");
        var target = ModelId.of("openai", "gpt-5");
        assertThat(n.normalize("fc_abc__", target, SOURCE)).isEqualTo("fc_abc");
    }

    @Test
    void responsesOpenaiLongIdTruncatedThenStripped() {
        var n = ResponsesToolCallIds.create("openai-responses");
        var target = ModelId.of("openai", "gpt-5");
        // 70 个 'a'：截 64 后无尾下划线 ⇒ 原样 64 个 a
        assertThat(n.normalize("a".repeat(70), target, SOURCE)).isEqualTo("a".repeat(64));
    }

    @Test
    void responsesDisallowedProviderNormalizesWholeId() {
        var n = ResponsesToolCallIds.create("openai-responses");
        var target = ModelId.of("github-copilot", "gpt-5");
        // 第一分支 normalizeIdPart(id)：'|' 是非法字符 ⇒ '_'，不拆段
        assertThat(n.normalize("call_123|fc_123", target, SOURCE))
                .isEqualTo("call_123_fc_123");
    }

    @Test
    void responsesDisallowedProviderTruncatesAt64() {
        var n = ResponsesToolCallIds.create("openai-responses");
        var target = ModelId.of("github-copilot", "gpt-5");
        assertThat(n.normalize("a".repeat(65), target, SOURCE)).isEqualTo("a".repeat(64));
    }

    @Test
    void responsesOpenaiCodexForeignItemUsesHash() {
        var n = ResponsesToolCallIds.create("openai-responses");
        var target = ModelId.of("openai-codex", "gpt-5-codex");
        // source 是异源（provider=github-copilot ≠ target 的 openai-codex）⇒ fc_<hash>
        assertThat(n.normalize("call_123|abc", target, SOURCE))
                .isEqualTo("call_123" + "|" + "fc_" + ShortHash.of("abc"));
    }

    @Test
    void responsesOpenaiSameOriginItemGetsFcPrefix() {
        var n = ResponsesToolCallIds.create("openai-responses");
        var target = ModelId.of("openai", "gpt-5");
        // 同源：source.provider == target.provider 且 source.api == apiName
        var sameOriginSource = new Message.AssistantMessage(
                List.of(new ContentBlock.TextContent("x")),
                "stop", null,
                "openai-responses", "openai", "gpt-5",
                null, null, null, null);
        assertThat(n.normalize("call_123|abc", target, sameOriginSource))
                .isEqualTo("call_123|fc_abc");
    }

    // --------------------------------------------------------------- Mistral (P22)

    @Test
    void mistralNineCharAlnumUsedDirectly() {
        var n = MistralToolCallIds.create();
        var target = ModelId.of("mistral", "mistral-large");
        assertThat(n.normalize("abcdefgh1", target, SOURCE)).isEqualTo("abcdefgh1");
    }

    @Test
    void mistralTwelveCharGoesThroughHash() {
        var n = MistralToolCallIds.create();
        var target = ModelId.of("mistral", "mistral-large");
        var id = "abcdefgh1234";
        // normalized 12 位 ≠ 9 ⇒ shortHash(seed) 字母数字子串截 9（现场算，不硬编码）
        var expected = ShortHash.of(id).replaceAll("[^a-zA-Z0-9]", "").substring(0, 9);
        assertThat(n.normalize(id, target, SOURCE)).isEqualTo(expected);
    }

    @Test
    void mistralDeterministicWithinInstance() {
        var n = MistralToolCallIds.create();
        var target = ModelId.of("mistral", "mistral-large");
        var first = n.normalize("abcdefgh1234", target, SOURCE);
        var second = n.normalize("abcdefgh1234", target, SOURCE);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void mistralIndependentInstancesDoNotInterfere() {
        var target = ModelId.of("mistral", "mistral-large");
        var id = "abcdefgh1234";
        var expected = ShortHash.of(id).replaceAll("[^a-zA-Z0-9]", "").substring(0, 9);
        // 新实例重新从 attempt 0 开始 ⇒ 同一 id 得同一 candidate（hash 决定性）
        assertThat(MistralToolCallIds.create().normalize(id, target, SOURCE))
                .isEqualTo(expected);
        assertThat(MistralToolCallIds.create().normalize(id, target, SOURCE))
                .isEqualTo(expected);
    }

    @Test
    void mistralTwoDistinctIdsGetStableDistinctResults() {
        var n = MistralToolCallIds.create();
        var target = ModelId.of("mistral", "mistral-large");
        var a = n.normalize("abcdefgh1234", target, SOURCE);
        var b = n.normalize("wxyz12345678", target, SOURCE);
        // 各自稳定（同实例重复调用走 idMap 命中路径）；长度恒 9（derive 的出口）
        assertThat(a).hasSize(9);
        assertThat(b).hasSize(9);
        assertThat(a).isNotEqualTo(b);
        assertThat(n.normalize("abcdefgh1234", target, SOURCE)).isEqualTo(a);
        assertThat(n.normalize("wxyz12345678", target, SOURCE)).isEqualTo(b);
    }

    @Test
    void mistralEmptyId() {
        var n = MistralToolCallIds.create();
        var target = ModelId.of("mistral", "mistral-large");
        // normalized 空串 ⇒ seedBase 回落原 id "" ⇒ shortHash("") = k4n83c7h0j2b ⇒ 前 9 位
        assertThat(n.normalize("", target, SOURCE)).isEqualTo("k4n83c7h0");
    }
}
