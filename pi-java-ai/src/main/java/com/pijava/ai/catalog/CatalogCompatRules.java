package com.pijava.ai.catalog;

/**
 * pi 的**生成期**目录 compat 规则（{@code scripts/generate-models.ts}）里，与内置目录
 * **代码推导**相关的那几条 —— 逐字照抄谓词，而不是把生成出来的 JSON 值硬编进目录。
 *
 * <p><b>为什么要照抄谓词</b>（包 A7 的裁决 {@code docs/53 §9 R3}）：pi 的目录数据
 * （{@code src/providers/data/*.json}）**不在仓库里**（gitignore，由生成器从 models.dev 抓取后
 * 算出），但它的 compat 里有相当一部分**不是远端数据、而是代码**：下面这四条全是
 * {@code (provider, modelId)} 的纯字符串函数。照抄谓词 ⇒ models.dev 漂移时 java 的行为
 * **只随 pi 的代码变**，与 pi 的变更点一一对应；硬编值则会静默陈旧（{@code deepseek-v4-flash}
 * 那次改名就是教训，{@code docs/53 §3 F5}）。</p>
 *
 * <p>⚠️ <b>大小写与匹配方式必须逐个照抄</b>，pi 三处谓词并不统一：
 * {@code supportsAnthropicMidConvoSystemMessages} 用**正则**且**不**小写化；
 * {@code isAnthropicAdaptiveThinkingModel} 用 {@code includes} 且**不**小写化；
 * {@code isAnthropicTemperatureUnsupportedModel} 用 {@code includes} 且**先小写化**。
 * 把三者统一成一种写法会**扩大或缩小**命中集（例如把正则改成 {@code includes} 会让
 * {@code claude-opus-4-80} 这类 id 误命中）。</p>
 *
 * <p>⚠️ 本类只承载 java 真正**携带**的标志（{@link ModelCompat} 的组件）；pi 生成器给这些模型
 * 写的其余键（{@code supportsStrictTools}、{@code allowedFallbackModels}…）按
 * {@code docs/53 §4.4} 的归属表留给各自的包。</p>
 */
public final class CatalogCompatRules {

    private CatalogCompatRules() {
    }

    /**
     * Anthropic 车道的目录标注 —— pi {@code getAnthropicMessagesCompat:1142-1170}
     * （只看与 java 携带字段有关的三个分支）。
     *
     * <p>⚠️ 中间两条都对 {@code api === "anthropic-messages"} 有门（{@code generate-models.ts}
     * 的调用点 {@code :1039}/{@code :1043}），而本方法<b>只被 anthropic 目录调用</b>
     * ⇒ 那道门由调用方承担。</p>
     *
     * @param provider pi 的 provider 名（中间那条要求它恰是 {@code "anthropic"}）
     * @param modelId  pi 的模型 id（**原样**，谓词自己决定要不要小写化）
     * @return 该模型应标注的 compat；无命中时返回 {@link ModelCompat#NONE}
     */
    public static ModelCompat anthropic(String provider, String modelId) {
        var id = modelId == null ? "" : modelId;
        var midConvo = "anthropic".equals(provider) && supportsMidConvoSystemMessages(id);
        return new ModelCompat(
            false,
            null,
            true,
            isAdaptiveThinkingModel(id),
            // :1152-1154 —— provider 是 anthropic 时**两个一起给**（tool changes 随 system messages）。
            midConvo ? Boolean.TRUE : null,
            null,
            midConvo ? Boolean.TRUE : null,
            null,
            null,
            !isTemperatureUnsupportedModel(id),
            null, null, null, null);
    }

    /**
     * completions 车道的目录标注 —— pi {@code applyOpenAICompletionsTranscriptMetadata:875-900}。
     *
     * <p>⚠️ 那条规则**逐 id 写死**：{@code provider === "deepseek" && model.id === "deepseek-v4-pro"}
     * 走 {@code isTextOnly}（只得 {@code supportsMidConvoSystemMessages}），而同族的
     * {@code deepseek-flash} **不在**名单里 —— pi 自己的测试把这一点钉成了断言
     * （{@code test/providers.test.ts} 的 {@code supported}/{@code unsupported} 两份列表，
     * 锚点 26/26 绿）。</p>
     *
     * <p>其余端点属性（{@code maxTokensField}／{@code supportsStore}／
     * {@code supportsDeveloperRole}／{@code requiresReasoningContentOnAssistantMessages}）
     * **不在这里标注**：它们在 pi 的目录里是**探测的差量**，而
     * {@link CompatResolver#forCompletions} 在请求期会算出同一个值。</p>
     */
    public static ModelCompat completions(String provider, String modelId) {
        var isNativeDeepSeekPro =
            "deepseek".equals(provider) && "deepseek-v4-pro".equals(modelId);
        return isNativeDeepSeekPro
            ? new ModelCompat(false, null, true, false, true, null, null, null, null)
            : ModelCompat.NONE;
    }

    // ── 照抄的谓词（generate-models.ts:578/585/604）──────────────

    /**
     * pi {@code supportsAnthropicMidConvoSystemMessages:578-583} —— 正则、**不**小写化。
     *
     * <pre>{@code /^claude-opus-(?:4[.-]8|5)(?:-\d{8})?$/  ||  /^claude-(?:fable|mythos)-5(?:[.-]1)?(?:-\d{8})?$/}</pre>
     */
    private static boolean supportsMidConvoSystemMessages(String modelId) {
        return modelId.matches("^claude-opus-(?:4[.-]8|5)(?:-\\d{8})?$")
            || modelId.matches("^claude-(?:fable|mythos)-5(?:[.-]1)?(?:-\\d{8})?$");
    }

    /**
     * pi {@code isAnthropicAdaptiveThinkingModel:585-602} —— {@code includes} 串、**不**小写化。
     */
    private static boolean isAdaptiveThinkingModel(String modelId) {
        return modelId.contains("opus-4-6") || modelId.contains("opus-4.6")
            || modelId.contains("opus-4-7") || modelId.contains("opus-4.7")
            || modelId.contains("opus-4-8") || modelId.contains("opus-4.8")
            || modelId.contains("opus-5") || modelId.contains("opus.5")
            || modelId.contains("sonnet-4-6") || modelId.contains("sonnet-4.6")
            || modelId.contains("sonnet-5") || modelId.contains("sonnet.5")
            || modelId.contains("fable-5") || modelId.contains("mythos-5");
    }

    /**
     * pi {@code isAnthropicTemperatureUnsupportedModel:604-614} —— {@code includes} 串、**先**小写化。
     */
    private static boolean isTemperatureUnsupportedModel(String modelId) {
        var id = modelId.toLowerCase(java.util.Locale.ROOT);
        return id.contains("opus-4-7") || id.contains("opus-4.7")
            || id.contains("opus-4-8") || id.contains("opus-4.8")
            || id.contains("opus-5") || id.contains("opus.5");
    }
}
