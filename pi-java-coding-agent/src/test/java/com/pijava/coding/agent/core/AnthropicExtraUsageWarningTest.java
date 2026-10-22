package com.pijava.coding.agent.core;

import java.util.Map;
import java.util.Optional;

import com.pijava.ai.api.AuthKind;
import com.pijava.ai.auth.RecordedCredential;
import com.pijava.ai.model.ModelId;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 12 T13（docs/23 §3/§7）：pi {@code maybeWarnAboutAnthropicSubscriptionAuth}
 * （interactive-mode.ts:5261-5286）对位——warnings=false ⇒ 静默；非 anthropic ⇒
 * 静默；普通 key ⇒ 静默；{@code sk-ant-oat*} ⇒ 文案逐字且每会话一次；stored
 * OAuth（种类判据）⇒ 文案。
 */
class AnthropicExtraUsageWarningTest {

    private static final ModelId<?> CLAUDE = ModelId.of("anthropic", "claude-fable-5");
    private static final ModelId<?> GPT = ModelId.of("openai", "gpt-6");

    private static RecordedCredential credential(String kindSource, String value) {
        return new RecordedCredential(AuthKind.API_KEY, value, kindSource, Map.of());
    }

    private static AnthropicExtraUsageWarning guard(Boolean anthropicExtraUsage) {
        var warnings = new Settings.Warnings(anthropicExtraUsage);
        return new AnthropicExtraUsageWarning(() -> warnings);
    }

    @Test
    void disabledWarningStaysSilent() {
        var guard = guard(false);

        assertThat(guard.maybeWarn(CLAUDE,
            () -> Optional.of(credential("stored-oauth:anthropic", "sk-ant-oat-1"))))
            .isEmpty();
    }

    @Test
    void nonAnthropicModelStaysSilent() {
        var guard = guard(null);

        assertThat(guard.maybeWarn(GPT,
            () -> Optional.of(credential("stored-oauth:anthropic", "sk-ant-oat-1"))))
            .isEmpty();
        assertThat(guard.maybeWarn(null,
            () -> Optional.of(credential("stored-oauth:anthropic", "sk-ant-oat-1"))))
            .isEmpty();
    }

    @Test
    void plainApiKeyStaysSilent() {
        var guard = guard(null);

        assertThat(guard.maybeWarn(CLAUDE,
            () -> Optional.of(credential("env:ANTHROPIC_API_KEY", "sk-ant-api03-abc"))))
            .isEmpty();
        assertThat(guard.maybeWarn(CLAUDE, Optional::empty)).isEmpty();
    }

    @Test
    void subscriptionKeyWarnsOncePerSession() {
        var guard = guard(null);

        var first = guard.maybeWarn(CLAUDE,
            () -> Optional.of(credential("env:ANTHROPIC_API_KEY", "sk-ant-oat01-abc")));
        assertThat(first).contains(AnthropicExtraUsageWarning.TEXT);
        // 文案逐字钉死（pi :321-323【模型可见】）。
        assertThat(first.get()).isEqualTo(
            "Anthropic subscription auth is active. Third-party harness usage draws from "
            + "extra usage and is billed per token, not your Claude plan limits. Manage extra "
            + "usage at https://claude.ai/settings/usage. Disable this warning in /settings.");
        // 每会话一次（pi anthropicSubscriptionWarningShown）。
        assertThat(guard.maybeWarn(CLAUDE,
            () -> Optional.of(credential("env:ANTHROPIC_API_KEY", "sk-ant-oat01-abc"))))
            .isEmpty();
    }

    @Test
    void storedOAuthCountsAsSubscription() {
        var guard = guard(true);

        // pi checkAuth 的种类判据：stored OAuth ⇒ 订阅（值无前缀也中）。
        assertThat(guard.maybeWarn(CLAUDE,
            () -> Optional.of(credential("stored-oauth:anthropic", "opaque-token"))))
            .contains(AnthropicExtraUsageWarning.TEXT);
    }

    @Test
    void credentialResolutionErrorsAreSwallowed() {
        var guard = guard(null);

        assertThat(guard.maybeWarn(CLAUDE, () -> {
            throw new IllegalStateException("store locked");
        })).isEmpty();
    }
}
