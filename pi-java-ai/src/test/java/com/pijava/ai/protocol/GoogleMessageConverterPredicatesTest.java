package com.pijava.ai.protocol;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包B84 步3：Google 车道的两个<b>模型谓词</b>（pi {@code google-shared.ts:165-186}）。
 *
 * <p>它们决定两件事：工具调用的 {@code id} 该不该发（{@code requiresToolCallId}），
 * 以及工具结果的图片是<b>内嵌</b>进 {@code functionResponse.parts} 还是另起一条 user
 * 回合（{@code supportsMultimodalFunctionResponse}）。两者都是「只在 gemini 3+ 成立」，
 * 且第二者对<b>非 gemini 恒真</b> —— 这一条最反直觉，单独钉。</p>
 *
 * <p>⚠️ 内置 Google 目录只有 {@code gemini-2.5-pro}／{@code gemini-2.5-flash}
 * （两个谓词都取假），gemini 3+ 只能经 {@code models.json} 进来 ⇒ 没有本类，
 * 步4／步5 的分支在夹具里**跑不到**（只能用真模型 id 直调）。</p>
 */
class GoogleMessageConverterPredicatesTest {

    // ── requiresToolCallId（pi :165-172）─────────────────────────────────

    /** gemini 2.x ⇒ **不**要求 id（内置两个模型的取值）。 */
    @Test
    void gemini2DoesNotRequireToolCallId() {
        assertThat(GoogleMessageConverter.requiresToolCallId("gemini-2.5-flash")).isFalse();
        assertThat(GoogleMessageConverter.requiresToolCallId("gemini-2.5-pro")).isFalse();
    }

    /** gemini 3+ ⇒ 要求 id。 */
    @Test
    void gemini3RequiresToolCallId() {
        assertThat(GoogleMessageConverter.requiresToolCallId("gemini-3-pro-preview")).isTrue();
        assertThat(GoogleMessageConverter.requiresToolCallId("gemini-3-flash")).isTrue();
    }

    /**
     * {@code gemini-live-2.5-*} 也认（正则 {@code /^gemini(?:-live)?-(\d+)/}）⇒ 主版本 2 ⇒ 假。
     *
     * <p>钉住 {@code (?:-live)?} 那一段：少了它，{@code gemini-live-2.5-flash} 匹配不上
     * ⇒ 落 {@code undefined} ⇒ 这条会红。</p>
     */
    @Test
    void geminiLiveIdsAreRecognised() {
        assertThat(GoogleMessageConverter.requiresToolCallId("gemini-live-2.5-flash")).isFalse();
        assertThat(GoogleMessageConverter.requiresToolCallId("gemini-live-3-flash")).isTrue();
    }

    /** 非 gemini 的两个前缀（Cloud Code Assist 上的 claude／gpt-oss）⇒ 要求 id。 */
    @Test
    void claudeAndGptOssRequireToolCallId() {
        assertThat(GoogleMessageConverter.requiresToolCallId("claude-sonnet-4")).isTrue();
        assertThat(GoogleMessageConverter.requiresToolCallId("gpt-oss-120b")).isTrue();
    }

    /** 其余模型 ⇒ 假（主版本读不到、前缀也不匹配）。 */
    @Test
    void otherModelsDoNotRequireToolCallId() {
        assertThat(GoogleMessageConverter.requiresToolCallId("llama-3.1-70b")).isFalse();
        assertThat(GoogleMessageConverter.requiresToolCallId("gemma-3-27b")).isFalse();
    }

    /**
     * ⚠️ 正则前**先小写化**（pi {@code :175} {@code modelId.toLowerCase()}）⇒ 大写 id 照认。
     *
     * <p>实测：目录里的 id 大小写不保证（{@code models.json} 是用户输入）。少了
     * {@code toLowerCase} 这条立刻红。</p>
     */
    @Test
    void uppercaseIdsAreRecognised() {
        assertThat(GoogleMessageConverter.requiresToolCallId("GEMINI-3-PRO")).isTrue();
        assertThat(GoogleMessageConverter.supportsMultimodalFunctionResponse("GEMINI-3-PRO")).isTrue();
    }

    // ── supportsMultimodalFunctionResponse（pi :180-186）────────────────

    /** gemini 2.x ⇒ 假 ⇒ 图片另起一条 user 回合。 */
    @Test
    void gemini2DoesNotSupportMultimodalFunctionResponse() {
        assertThat(GoogleMessageConverter.supportsMultimodalFunctionResponse("gemini-2.5-flash")).isFalse();
    }

    /** gemini 3+ ⇒ 真 ⇒ 图片内嵌进 {@code functionResponse.parts}。 */
    @Test
    void gemini3SupportsMultimodalFunctionResponse() {
        assertThat(GoogleMessageConverter.supportsMultimodalFunctionResponse("gemini-3-pro-preview")).isTrue();
    }

    /**
     * ⚠️ <b>非 gemini 恒真</b>（pi {@code :185} {@code return true}）—— 与
     * {@code requiresToolCallId} 的「非 gemini 恒真」同形但**理由不同**：这里是
     * 「不是 Gemini ⇒ 不受 Gemini 版本限制」，走内嵌。
     *
     * <p>这一条最容易被「顺手统一」掉（把谓词写成 {@code major != null && major >= 3}）
     * ⇒ 非 gemini 会翻假、多出一条 user 图片回合。</p>
     */
    @Test
    void nonGeminiAlwaysSupportsMultimodalFunctionResponse() {
        assertThat(GoogleMessageConverter.supportsMultimodalFunctionResponse("claude-sonnet-4")).isTrue();
        assertThat(GoogleMessageConverter.supportsMultimodalFunctionResponse("gpt-oss-120b")).isTrue();
        assertThat(GoogleMessageConverter.supportsMultimodalFunctionResponse("llama-3.1-70b")).isTrue();
    }

    /**
     * ⚠️ 两个谓词对非 gemini 的取值**相反**（id 要发／内嵌也要走）—— 摆在一起钉住
     * 「它们不是同一个谓词的两个名字」。
     */
    @Test
    void theTwoPredicatesDisagreeOnNonGemini() {
        assertThat(GoogleMessageConverter.requiresToolCallId("claude-sonnet-4")).isTrue();
        assertThat(GoogleMessageConverter.supportsMultimodalFunctionResponse("claude-sonnet-4")).isTrue();
        assertThat(GoogleMessageConverter.requiresToolCallId("llama-3.1-70b")).isFalse();
        assertThat(GoogleMessageConverter.supportsMultimodalFunctionResponse("llama-3.1-70b")).isTrue();
    }
}
