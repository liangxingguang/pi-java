package com.pijava.coding.agent.core;

import java.util.Optional;
import java.util.function.Supplier;

import com.pijava.ai.auth.RecordedCredential;
import com.pijava.ai.model.ModelId;

/**
 * 批 12 T13（docs/23 §3）：Anthropic 订阅凭证超额用量警告，pi
 * {@code maybeWarnAboutAnthropicSubscriptionAuth}（interactive-mode.ts:5261-5286）
 * ＋ {@code ANTHROPIC_SUBSCRIPTION_AUTH_WARNING}（:321-323 逐字）＋
 * {@code isAnthropicSubscriptionAuthKey}（:325-327）对位。
 *
 * <p>判据差异注记：pi 的 {@code checkAuth("anthropic")} 是「种类 oauth <b>或</b>
 * key 以 {@code sk-ant-oat} 开头」双判据之一即中。java 侧 ai 层把 Anthropic 的
 * stored OAuth 也映射为 {@code AuthKind.API_KEY}（{@code Credentials#oauthKind}，
 * 走 {@code x-api-key}）——种类不可达，退化为 <b>source 标记
 * （{@code stored-oauth:anthropic}）或值前缀</b>双判据（设计的「种类不可达则
 * 退化」臂；订阅 token 本身也带 {@code sk-ant-oat} 前缀，两判据同中）。</p>
 */
public final class AnthropicExtraUsageWarning {

    /** pi :321-323 逐字【模型可见】。 */
    public static final String TEXT =
        "Anthropic subscription auth is active. Third-party harness usage draws from "
        + "extra usage and is billed per token, not your Claude plan limits. Manage extra "
        + "usage at https://claude.ai/settings/usage. Disable this warning in /settings.";

    /** pi {@code OAUTH_TOKEN_MARKER}（AnthropicMessagesApi:146）同源。 */
    static final String OAUTH_TOKEN_PREFIX = "sk-ant-oat";

    private final Supplier<Settings.Warnings> warnings;
    /** pi {@code anthropicSubscriptionWarningShown}：每会话一次（实例随会话重建）。 */
    private boolean shown;

    /**
     * @param warnings warnings 设置现读源（{@code uiPrefs().getWarnings()}——
     *                 {@code anthropicExtraUsage ?? true}，写后下次判定即生效）
     */
    public AnthropicExtraUsageWarning(Supplier<Settings.Warnings> warnings) {
        this.warnings = warnings;
    }

    /**
     * pi {@code maybeWarnAboutAnthropicSubscriptionAuth}（:5261-5286）：
     * {@code warnings.anthropicExtraUsage == false} ⇒ 静默；非 anthropic ⇒ 静默；
     * 订阅凭证（stored OAuth 或 key 以 {@code sk-ant-oat} 开头）⇒ 每会话一次警告。
     * 凭证解析异常静默吞掉（pi 同，:5284-5286）。
     *
     * @param currentModel        当前模型（可 null ⇒ 静默）
     * @param anthropicCredential anthropic 凭证解析闭包（{@code Credentials
     *                            #resolveCredential("anthropic")} 对位）
     * @return 命中且未警告过 ⇒ 警告文案；否则 empty
     */
    public Optional<String> maybeWarn(ModelId<?> currentModel,
            Supplier<Optional<RecordedCredential>> anthropicCredential) {
        if (shown) {
            return Optional.empty();
        }
        // pi :5262-5264 门控：显式 false 才关（?? true）。
        if (Boolean.FALSE.equals(warnings.get().anthropicExtraUsage())) {
            return Optional.empty();
        }
        if (currentModel == null || !"anthropic".equals(currentModel.provider())) {
            return Optional.empty();
        }
        Optional<RecordedCredential> credential;
        try {
            credential = anthropicCredential.get();
        } catch (Exception e) {
            return Optional.empty();      // pi：异常静默吞掉
        }
        if (credential.isEmpty() || !isSubscriptionAuth(credential.get())) {
            return Optional.empty();
        }
        shown = true;
        return Optional.of(TEXT);
    }

    /**
     * pi {@code checkAuth} 双判据的 java 落形：stored OAuth（source 标记）或
     * {@code sk-ant-oat} 前缀（pi {@code isAnthropicSubscriptionAuthKey} :325-327）。
     */
    static boolean isSubscriptionAuth(RecordedCredential credential) {
        return credential.source().startsWith("stored-oauth:")
            || credential.value().startsWith(OAUTH_TOKEN_PREFIX);
    }
}
