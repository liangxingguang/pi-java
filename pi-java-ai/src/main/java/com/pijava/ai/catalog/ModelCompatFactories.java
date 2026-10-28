package com.pijava.ai.catalog;

import java.util.Map;

/**
 * {@link ModelCompat} 的便捷工厂集合（批 29＝docs/41 结构债清空批）。
 *
 * <p>来历：ModelCompat 原携带 12 档历史「零改签」便捷构造器（共 372 行），把 record 推过
 * 500 行规则。record 的构造器必须与 record 同文件，唯一纯移动路径是「构造器 → 静态工厂」
 * 机械改写——各档元数互不歧义（3/4/5/9/14/16/18/19/23/25/27/29），统一命名 {@code of}
 * 重载。每档的缺席语义注记随方法逐字保留（原 {@code ModelCompat.java:388-759}）。</p>
 *
 * <p>行为零变化：每条工厂都是「缺省值补齐 → {@code new ModelCompat(39 参)}」的同值委托。</p>
 */
public final class ModelCompatFactories {

    private ModelCompatFactories() {
    }

    /**
     * 二十九参便捷工厂（包 09 加 {@code supportsEagerToolInputStreaming} /
     * {@code supportsExplicitPromptCacheMode} 之前的**规范**形态）：两个新组件缺席
     * （{@code null} ≙ 车道缺省 —— 前者 {@code true}、后者 {@code false}）。
     */
    public static ModelCompat of(boolean allowEmptySignature,
                                 Boolean requiresReasoningContentOnAssistantMessages,
                                 boolean supportsFinishReason,
                                 boolean forceAdaptiveThinking,
                                 Boolean supportsMidConvoSystemMessages,
                                 Boolean supportsMidConvoToolAdditions,
                                 Boolean supportsMidConvoToolChanges,
                                 Boolean supportsAdditionalTools,
                                 Boolean supportsToolSearch,
                                 boolean supportsTemperature,
                                 MaxTokensField maxTokensField,
                                 Boolean supportsStore,
                                 Boolean supportsDeveloperRole,
                                 Boolean supportsStrictMode,
                                 Boolean supportsLongCacheRetention,
                                 Boolean supportsCacheControlOnTools,
                                 ThinkingTokenBudgetField thinkingTokenBudgetField,
                                 Boolean supportsThinkingTokenBudget,
                                 Boolean supportsMaxOutputTokens,
                                 ThinkingFormat thinkingFormat,
                                 Map<String, ChatTemplateKwargValue> chatTemplateKwargs,
                                 Map<String, ChatTemplateKwargValue> chatTemplateArgs,
                                 Boolean supportsReasoningEffort,
                                 CacheControlFormat cacheControlFormat,
                                 Map<String, Object> openRouterRouting,
                                 Boolean sendSessionAffinityHeaders,
                                 SessionAffinityFormat sessionAffinityFormat,
                                 boolean supportsStrictTools,
                                 Boolean supportsOpenAIGrammarTools) {
        return new ModelCompat(allowEmptySignature, requiresReasoningContentOnAssistantMessages,
             supportsFinishReason, forceAdaptiveThinking, supportsMidConvoSystemMessages,
             supportsMidConvoToolAdditions, supportsMidConvoToolChanges,
             supportsAdditionalTools, supportsToolSearch, supportsTemperature,
             maxTokensField, supportsStore, supportsDeveloperRole, supportsStrictMode,
             supportsLongCacheRetention, supportsCacheControlOnTools,
             thinkingTokenBudgetField, supportsThinkingTokenBudget, supportsMaxOutputTokens,
             thinkingFormat, chatTemplateKwargs, chatTemplateArgs, supportsReasoningEffort,
             cacheControlFormat, openRouterRouting, sendSessionAffinityHeaders,
             sessionAffinityFormat, supportsStrictTools, supportsOpenAIGrammarTools,
             // 包 09 两字段缺席；B98 八字段在本档全部缺席（null ≙ 探测/省略）。
             null, null, null, null, null, null, null, null, null, null,
             // B99：supportsMidConvoEffort 缺席（null ≙ false）。
             null);
    }

    /**
     * 二十五参便捷工厂（包 B103 之前的**规范**形态 —— 那时组件就这二十五个）：包 B103 新增的
     * 两个缺席（{@code null} ≙「按车道探测」）。
     */
    public static ModelCompat of(boolean allowEmptySignature,
                                 Boolean requiresReasoningContentOnAssistantMessages,
                                 boolean supportsFinishReason,
                                 boolean forceAdaptiveThinking,
                                 Boolean supportsMidConvoSystemMessages,
                                 Boolean supportsMidConvoToolAdditions,
                                 Boolean supportsMidConvoToolChanges,
                                 Boolean supportsAdditionalTools,
                                 Boolean supportsToolSearch,
                                 boolean supportsTemperature,
                                 MaxTokensField maxTokensField,
                                 Boolean supportsStore,
                                 Boolean supportsDeveloperRole,
                                 Boolean supportsStrictMode,
                                 Boolean supportsLongCacheRetention,
                                 Boolean supportsCacheControlOnTools,
                                 ThinkingTokenBudgetField thinkingTokenBudgetField,
                                 Boolean supportsThinkingTokenBudget,
                                 Boolean supportsMaxOutputTokens,
                                 ThinkingFormat thinkingFormat,
                                 Map<String, ChatTemplateKwargValue> chatTemplateKwargs,
                                 Map<String, ChatTemplateKwargValue> chatTemplateArgs,
                                 Boolean supportsReasoningEffort,
                                 CacheControlFormat cacheControlFormat,
                                 Map<String, Object> openRouterRouting) {
        return of(allowEmptySignature, requiresReasoningContentOnAssistantMessages,
             supportsFinishReason, forceAdaptiveThinking, supportsMidConvoSystemMessages,
             supportsMidConvoToolAdditions, supportsMidConvoToolChanges,
             supportsAdditionalTools, supportsToolSearch,
             supportsTemperature, maxTokensField, supportsStore, supportsDeveloperRole,
             supportsStrictMode, supportsLongCacheRetention, supportsCacheControlOnTools,
             thinkingTokenBudgetField, supportsThinkingTokenBudget, supportsMaxOutputTokens,
             thinkingFormat, chatTemplateKwargs, chatTemplateArgs, supportsReasoningEffort,
             cacheControlFormat, openRouterRouting, null, null, false, null);
    }

    /**
     * 二十七参便捷工厂（原 docs/66 加 {@code supportsStrictTools} 之前的**规范**形态）：
     * 新组件缺席（{@code false} ≙ pi 的 {@code ?? false}）。
     */
    public static ModelCompat of(boolean allowEmptySignature,
                                 Boolean requiresReasoningContentOnAssistantMessages,
                                 boolean supportsFinishReason,
                                 boolean forceAdaptiveThinking,
                                 Boolean supportsMidConvoSystemMessages,
                                 Boolean supportsMidConvoToolAdditions,
                                 Boolean supportsMidConvoToolChanges,
                                 Boolean supportsAdditionalTools,
                                 Boolean supportsToolSearch,
                                 boolean supportsTemperature,
                                 MaxTokensField maxTokensField,
                                 Boolean supportsStore,
                                 Boolean supportsDeveloperRole,
                                 Boolean supportsStrictMode,
                                 Boolean supportsLongCacheRetention,
                                 Boolean supportsCacheControlOnTools,
                                 ThinkingTokenBudgetField thinkingTokenBudgetField,
                                 Boolean supportsThinkingTokenBudget,
                                 Boolean supportsMaxOutputTokens,
                                 ThinkingFormat thinkingFormat,
                                 Map<String, ChatTemplateKwargValue> chatTemplateKwargs,
                                 Map<String, ChatTemplateKwargValue> chatTemplateArgs,
                                 Boolean supportsReasoningEffort,
                                 CacheControlFormat cacheControlFormat,
                                 Map<String, Object> openRouterRouting,
                                 Boolean sendSessionAffinityHeaders,
                                 SessionAffinityFormat sessionAffinityFormat) {
        return of(allowEmptySignature, requiresReasoningContentOnAssistantMessages,
             supportsFinishReason, forceAdaptiveThinking, supportsMidConvoSystemMessages,
             supportsMidConvoToolAdditions, supportsMidConvoToolChanges,
             supportsAdditionalTools, supportsToolSearch,
             supportsTemperature, maxTokensField, supportsStore, supportsDeveloperRole,
             supportsStrictMode, supportsLongCacheRetention, supportsCacheControlOnTools,
             thinkingTokenBudgetField, supportsThinkingTokenBudget, supportsMaxOutputTokens,
             thinkingFormat, chatTemplateKwargs, chatTemplateArgs, supportsReasoningEffort,
             cacheControlFormat, openRouterRouting,
             sendSessionAffinityHeaders, sessionAffinityFormat, false, null);
    }

    /**
     * 二十三参便捷工厂（包 A-02 之前的**规范**形态 —— 那时组件就这二十三个）：包 A-02 新增的
     * 两个缺席（{@code cacheControlFormat} 传 {@code null} ≙「按车道探测」，
     * {@code openRouterRouting} 传 {@code null} ≙「无路由表」）。
     */
    public static ModelCompat of(boolean allowEmptySignature,
                                 Boolean requiresReasoningContentOnAssistantMessages,
                                 boolean supportsFinishReason,
                                 boolean forceAdaptiveThinking,
                                 Boolean supportsMidConvoSystemMessages,
                                 Boolean supportsMidConvoToolAdditions,
                                 Boolean supportsMidConvoToolChanges,
                                 Boolean supportsAdditionalTools,
                                 Boolean supportsToolSearch,
                                 boolean supportsTemperature,
                                 MaxTokensField maxTokensField,
                                 Boolean supportsStore,
                                 Boolean supportsDeveloperRole,
                                 Boolean supportsStrictMode,
                                 Boolean supportsLongCacheRetention,
                                 Boolean supportsCacheControlOnTools,
                                 ThinkingTokenBudgetField thinkingTokenBudgetField,
                                 Boolean supportsThinkingTokenBudget,
                                 Boolean supportsMaxOutputTokens,
                                 ThinkingFormat thinkingFormat,
                                 Map<String, ChatTemplateKwargValue> chatTemplateKwargs,
                                 Map<String, ChatTemplateKwargValue> chatTemplateArgs,
                                 Boolean supportsReasoningEffort) {
        return of(allowEmptySignature, requiresReasoningContentOnAssistantMessages,
             supportsFinishReason, forceAdaptiveThinking, supportsMidConvoSystemMessages,
             supportsMidConvoToolAdditions, supportsMidConvoToolChanges,
             supportsAdditionalTools, supportsToolSearch,
             supportsTemperature, maxTokensField, supportsStore, supportsDeveloperRole,
             supportsStrictMode, supportsLongCacheRetention, supportsCacheControlOnTools,
             thinkingTokenBudgetField, supportsThinkingTokenBudget, supportsMaxOutputTokens,
             thinkingFormat, chatTemplateKwargs, chatTemplateArgs, supportsReasoningEffort,
             null, null);
    }

    /**
     * 十九参便捷工厂（包 A-10 之后的**规范**形态 —— 那时组件就这十九个）：包 A-09 新增的
     * 四个缺席（{@code thinkingFormat}/{@code supportsReasoningEffort} 传 {@code null}
     * ≙「按车道探测」，两个模板 map 归一成空表）。
     */
    public static ModelCompat of(boolean allowEmptySignature,
                                 Boolean requiresReasoningContentOnAssistantMessages,
                                 boolean supportsFinishReason,
                                 boolean forceAdaptiveThinking,
                                 Boolean supportsMidConvoSystemMessages,
                                 Boolean supportsMidConvoToolAdditions,
                                 Boolean supportsMidConvoToolChanges,
                                 Boolean supportsAdditionalTools,
                                 Boolean supportsToolSearch,
                                 boolean supportsTemperature,
                                 MaxTokensField maxTokensField,
                                 Boolean supportsStore,
                                 Boolean supportsDeveloperRole,
                                 Boolean supportsStrictMode,
                                 Boolean supportsLongCacheRetention,
                                 Boolean supportsCacheControlOnTools,
                                 ThinkingTokenBudgetField thinkingTokenBudgetField,
                                 Boolean supportsThinkingTokenBudget,
                                 Boolean supportsMaxOutputTokens) {
        return of(allowEmptySignature, requiresReasoningContentOnAssistantMessages,
             supportsFinishReason, forceAdaptiveThinking, supportsMidConvoSystemMessages,
             supportsMidConvoToolAdditions, supportsMidConvoToolChanges,
             supportsAdditionalTools, supportsToolSearch,
             supportsTemperature, maxTokensField, supportsStore, supportsDeveloperRole,
             supportsStrictMode, supportsLongCacheRetention, supportsCacheControlOnTools,
             thinkingTokenBudgetField, supportsThinkingTokenBudget, supportsMaxOutputTokens,
             null, null, null, null);
    }

    /**
     * 十八参便捷工厂（包 A-10 之前的**规范**形态 —— 那时组件就这十八个）：
     * 包 A-10 的第 6 步新增的 {@code supportsMaxOutputTokens} 缺席（{@code null}
     * ≙ 「按车道缺省」）。
     *
     * <p>保留该形态的理由：包 A-10 的第 5 步把组件数从 16 推到 18 时留的是**十六参**那一档，
     * 而 {@code CompatResolver} 与第 5 步的夹具（{@code ThinkingTokenBudgetWireTest}）用的是
     * 十八参那一档。</p>
     */
    public static ModelCompat of(boolean allowEmptySignature,
                                 Boolean requiresReasoningContentOnAssistantMessages,
                                 boolean supportsFinishReason,
                                 boolean forceAdaptiveThinking,
                                 Boolean supportsMidConvoSystemMessages,
                                 Boolean supportsMidConvoToolAdditions,
                                 Boolean supportsMidConvoToolChanges,
                                 Boolean supportsAdditionalTools,
                                 Boolean supportsToolSearch,
                                 boolean supportsTemperature,
                                 MaxTokensField maxTokensField,
                                 Boolean supportsStore,
                                 Boolean supportsDeveloperRole,
                                 Boolean supportsStrictMode,
                                 Boolean supportsLongCacheRetention,
                                 Boolean supportsCacheControlOnTools,
                                 ThinkingTokenBudgetField thinkingTokenBudgetField,
                                 Boolean supportsThinkingTokenBudget) {
        return of(allowEmptySignature, requiresReasoningContentOnAssistantMessages,
             supportsFinishReason, forceAdaptiveThinking, supportsMidConvoSystemMessages,
             supportsMidConvoToolAdditions, supportsMidConvoToolChanges,
             supportsAdditionalTools, supportsToolSearch,
             supportsTemperature, maxTokensField, supportsStore, supportsDeveloperRole,
             supportsStrictMode, supportsLongCacheRetention, supportsCacheControlOnTools,
             thinkingTokenBudgetField, supportsThinkingTokenBudget, null);
    }

    /**
     * 十六参便捷工厂（包 A-10 之前的**规范**形态 —— 那时组件就这十六个）：
     * 包 A-10 新增的两个缺席（{@code null} ≙ pi 的 {@code undefined} ≙「探测值」
     * —— {@code detectCompat:1662-1663} 恰好就是 {@code null}／{@code false}）。
     *
     * <p>保留该形态的**实证理由**：四个既有夹具（{@code AnthropicCacheControlTest:118}／
     * {@code :168}、{@code AnthropicCacheControlWireTest:204}／{@code :227}）用的正是它。</p>
     */
    public static ModelCompat of(boolean allowEmptySignature,
                                 Boolean requiresReasoningContentOnAssistantMessages,
                                 boolean supportsFinishReason,
                                 boolean forceAdaptiveThinking,
                                 Boolean supportsMidConvoSystemMessages,
                                 Boolean supportsMidConvoToolAdditions,
                                 Boolean supportsMidConvoToolChanges,
                                 Boolean supportsAdditionalTools,
                                 Boolean supportsToolSearch,
                                 boolean supportsTemperature,
                                 MaxTokensField maxTokensField,
                                 Boolean supportsStore,
                                 Boolean supportsDeveloperRole,
                                 Boolean supportsStrictMode,
                                 Boolean supportsLongCacheRetention,
                                 Boolean supportsCacheControlOnTools) {
        return of(allowEmptySignature, requiresReasoningContentOnAssistantMessages,
             supportsFinishReason, forceAdaptiveThinking, supportsMidConvoSystemMessages,
             supportsMidConvoToolAdditions, supportsMidConvoToolChanges,
             supportsAdditionalTools, supportsToolSearch,
             supportsTemperature, maxTokensField, supportsStore, supportsDeveloperRole,
             supportsStrictMode, supportsLongCacheRetention, supportsCacheControlOnTools,
             null, null, null);
    }

    /**
     * 十四参便捷工厂（包 A-01 之前的**规范**形态 —— 那时组件就这十四个）：
     * 包 A-01 新增的两个缺席 —— 两者 pi 的缺省都是 {@code true}，但这里保持 {@code null}
     * （≙ pi 的 {@code undefined}）而**不是** {@code true}，因为缺席会被
     * {@link CompatResolver} 补成车道缺省；在解析层之外直接读 {@code null} 的代码会
     * NPE —— 这正是不许在车道里直接读 compat 的理由之一。包 A-10 的两个新组件同样缺席
     * （{@code null}）。
     */
    public static ModelCompat of(boolean allowEmptySignature,
                                 Boolean requiresReasoningContentOnAssistantMessages,
                                 boolean supportsFinishReason,
                                 boolean forceAdaptiveThinking,
                                 Boolean supportsMidConvoSystemMessages,
                                 Boolean supportsMidConvoToolAdditions,
                                 Boolean supportsMidConvoToolChanges,
                                 Boolean supportsAdditionalTools,
                                 Boolean supportsToolSearch,
                                 boolean supportsTemperature,
                                 MaxTokensField maxTokensField,
                                 Boolean supportsStore,
                                 Boolean supportsDeveloperRole,
                                 Boolean supportsStrictMode) {
        return of(allowEmptySignature, requiresReasoningContentOnAssistantMessages,
             supportsFinishReason, forceAdaptiveThinking, supportsMidConvoSystemMessages,
             supportsMidConvoToolAdditions, supportsMidConvoToolChanges,
             supportsAdditionalTools, supportsToolSearch,
             supportsTemperature, maxTokensField, supportsStore, supportsDeveloperRole,
             supportsStrictMode, null, null, null, null);
    }

    /**
     * 九参便捷工厂（包 A7 之前的**规范**形态 —— 那时组件就这九个）：
     * 包 A7 新增的五个缺席 —— {@code supportsTemperature} 缺席 ≙ {@code true}
     * （pi 的探测值是常量 true）、其余四个 ≙ pi 的 {@code undefined}（走探测或车道缺省）。
     */
    public static ModelCompat of(boolean allowEmptySignature,
                                 Boolean requiresReasoningContentOnAssistantMessages,
                                 boolean supportsFinishReason,
                                 boolean forceAdaptiveThinking,
                                 Boolean supportsMidConvoSystemMessages,
                                 Boolean supportsMidConvoToolAdditions,
                                 Boolean supportsMidConvoToolChanges,
                                 Boolean supportsAdditionalTools,
                                 Boolean supportsToolSearch) {
        return of(allowEmptySignature, requiresReasoningContentOnAssistantMessages,
             supportsFinishReason, forceAdaptiveThinking, supportsMidConvoSystemMessages,
             supportsMidConvoToolAdditions, supportsMidConvoToolChanges,
             supportsAdditionalTools, supportsToolSearch,
             true, null, null, null, null);
    }

    /**
     * 五参便捷工厂（包A3 之前的形状）—— 工具增删那四个标志缺席 ≙ pi 的 {@code undefined}
     * ≙ {@code false}（四个读点全是真值判断或 {@code === true}）⇒ 单态塌缩是安全的。
     */
    public static ModelCompat of(boolean allowEmptySignature,
                                 Boolean requiresReasoningContentOnAssistantMessages,
                                 boolean supportsFinishReason,
                                 boolean forceAdaptiveThinking,
                                 Boolean supportsMidConvoSystemMessages) {
        return of(allowEmptySignature, requiresReasoningContentOnAssistantMessages,
             supportsFinishReason, forceAdaptiveThinking, supportsMidConvoSystemMessages,
             null, null, null, null);
    }

    /**
     * 四参便捷工厂（包A2 之前的形状）—— {@code supportsMidConvoSystemMessages} 缺席 ≙ pi 的
     * {@code undefined} ≙ 折叠支。
     */
    public static ModelCompat of(boolean allowEmptySignature,
                                 Boolean requiresReasoningContentOnAssistantMessages,
                                 boolean supportsFinishReason,
                                 boolean forceAdaptiveThinking) {
        return of(allowEmptySignature, requiresReasoningContentOnAssistantMessages,
             supportsFinishReason, forceAdaptiveThinking, null);
    }

    /**
     * 三参便捷工厂（包H5 之前的老形状）—— {@code forceAdaptiveThinking} 缺席 ≙ pi 的
     * {@code undefined}，而 pi 的判据是 {@code === true} ⇒ 与 {@code false} 同义。
     */
    public static ModelCompat of(boolean allowEmptySignature,
                                 Boolean requiresReasoningContentOnAssistantMessages,
                                 boolean supportsFinishReason) {
        return of(allowEmptySignature, requiresReasoningContentOnAssistantMessages,
             supportsFinishReason, false, null);
    }
}
