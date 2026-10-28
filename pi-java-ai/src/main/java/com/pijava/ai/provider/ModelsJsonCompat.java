package com.pijava.ai.provider;

import java.util.LinkedHashMap;
import java.util.Map;

import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.SessionAffinityFormat;
import com.pijava.ai.provider.ModelsJsonSchema.CompatDef;
import com.pijava.ai.provider.ModelsJsonSchema.ModelDef;

/**
 * models.json 的 compat 层：raw {@code CompatDef} 的解析与合并（D-P1 从
 * {@code ModelsJsonConfig} 拆出，{@code 原 docs/65 §3 Step 5}）。
 *
 * <p>对应 pi {@code provider-composer.ts} 的 {@code mergeCompat}（:86-106）：
 * raw 字段先两层合并（provider 级为 base、model 级覆盖），再一次性归一为
 * {@link ModelCompat}。</p>
 */
final class ModelsJsonCompat {

    private ModelsJsonCompat() {}

    /**
     * Map a models.json {@code compat} block onto {@link ModelCompat}.
     *
     * <p>{@code allowEmptySignature} is normalized — an absent block, or an absent key inside it,
     * both mean {@code false} (pi {@code anthropic-messages.ts:215} normalizes with
     * {@code ?? false}), so there is no third state to preserve (原 docs/31 §8.34.4 决策 3).
     * {@code supportsTemperature} is normalized with the **opposite** default ({@code true},
     * exactly like {@code supportsFinishReason}): its detected value is the constant
     * {@code true}, so only an explicit {@code false} suppresses the field.</p>
     *
     * <p>⚠️ {@code requiresReasoningContentOnAssistantMessages} is **not** normalized the same way:
     * it stays three-state (absent ⇒ {@code null}), because its absent meaning is "detect from the
     * provider/baseUrl" and only an explicit value overrides that (pi's {@code getCompat} is
     * {@code explicit ?? detected}, {@code openai-completions.ts:1700}). Collapsing {@code null}
     * into {@code false} here would silently disable the deepseek replay path.</p>
     *
     * <p>⚠️ <b>包 A7 的新键一律保持三态</b>（原样传 {@code Boolean}，不在这里归一）：
     * {@code supportsMidConvoSystemMessages}／{@code supportsMidConvoToolAdditions}／
     * {@code supportsMidConvoToolChanges}／{@code supportsAdditionalTools}／
     * {@code supportsToolSearch}／{@code supportsStore}／{@code supportsDeveloperRole}／
     * {@code supportsStrictMode}。理由是「用户没写」与「用户写了缺省值」必须可区分 ——
     * 前者要**让目录值与探测值活下来**，后者要**压掉**它们。今天在
     * {@code models[]} 里重定义一个内置模型时两者行为相同（整条替换、
     * {@code 原 docs/53 §3 F7} 的 path C），但逐字段合并补上时立刻需要
     * （{@code 原 docs/53 §9 R6}）。</p>
     *
     * <p>⚠️ {@code supportsFinishReason} 的归一方向与 {@code allowEmptySignature} **相反**
     * —— 不是疏忽：pi 对该标志的探测值是常量 {@code true}（{@code detectCompat:1640}，
     * 函数体内无任何分支碰它），所以 {@code explicit ?? detected} 真的塌缩成「缺席即严格」，
     * 没有第三种状态可保。</p>
     */
    static ModelCompat compatOf(String providerId, ModelDef model,
                                 CompatDef modelCompat, CompatDef providerCompat) {
        // D-P1（pi mergeCompat，provider-composer.ts:86-106）：raw 层先合并
        // （provider 级为 base、model 级覆盖），再一次性归一为 ModelCompat。
        CompatDef def = mergeRawCompat(providerCompat, modelCompat);
        if (def == null) {
            return ModelCompat.NONE;
        }
        return new ModelCompat(
            def.allowEmptySignature() != null && def.allowEmptySignature(),
            def.requiresReasoningContentOnAssistantMessages(),
            def.supportsFinishReason() == null || def.supportsFinishReason(),
            def.forceAdaptiveThinking() != null && def.forceAdaptiveThinking(),
            def.supportsMidConvoSystemMessages(),
            def.supportsMidConvoToolAdditions(),
            def.supportsMidConvoToolChanges(),
            def.supportsAdditionalTools(),
            def.supportsToolSearch(),
            def.supportsTemperature() == null || def.supportsTemperature(),
            ModelsJsonCompatFieldParsers.maxTokensFieldOf(providerId, model.id(), def.maxTokensField()),
            def.supportsStore(),
            def.supportsDeveloperRole(),
            def.supportsStrictMode(),
            // 包 A-01：两个 cache 门**原样透传可空值**（不像 `supportsTemperature` 那样在这里
            // 就塌成 true）—— 它们的缺省由**解析层**按车道补（`forAnthropic` 的 `?? true`），
            // 与 supportsStore/supportsDeveloperRole/supportsStrictMode 同形。
            def.supportsLongCacheRetention(),
            def.supportsCacheControlOnTools(),
            // 包 A-10：两个预算字段同样**原样透传可空值** —— 缺省由解析层按车道补
            // （completions 的探测值是 `false`／`undefined`，见 CompatResolver.forCompletions）。
            ModelsJsonCompatFieldParsers.thinkingTokenBudgetFieldOf(providerId, model.id(),
                def.thinkingTokenBudgetField()),
            def.supportsThinkingTokenBudget(),
            // 包 A-10 第 6 步：`supportsMaxOutputTokens` 的缺省（`true`）由解析层按车道补
            // （CompatResolver.forResponses）⇒ 这里同样不归一 —— 「用户没写」必须能与
            // 「用户写了 true」区分开，因为只有前者会吃车道缺省。
            def.supportsMaxOutputTokens(),
            // 包 A-09：思考开关字段。`thinkingFormat`/`supportsReasoningEffort` 原样
            // 透传可空值（缺省由 CompatResolver.forCompletions 的探测补）；两个模板 map
            // 在这里就完成校验（$var 闭集、Literal 只收标量），null 由 ModelCompat 的
            // compact 构造器归一成空表。
            ModelsJsonCompatFieldParsers.thinkingFormatOf(providerId, model.id(), def.thinkingFormat()),
            ModelsJsonCompatFieldParsers.chatTemplateValuesOf(providerId, model.id(), "chatTemplateKwargs",
                def.chatTemplateKwargs()),
            ModelsJsonCompatFieldParsers.chatTemplateValuesOf(providerId, model.id(), "chatTemplateArgs",
                def.chatTemplateArgs()),
            def.supportsReasoningEffort(),
            // 包 A-02：cacheControlFormat 闭集响亮抛（与 maxTokensField 同口径）；
            // openRouterRouting 纯透传（null 保持 null，不归一——原 docs/59 R6）。
            ModelsJsonCompatFieldParsers.cacheControlFormatOf(providerId, model.id(), def.cacheControlFormat()),
            def.openRouterRouting(),
            // 包 B103：两个亲和字段原样透传可空值（缺省由车道构造期按 openrouter 探测补）。
            def.sendSessionAffinityHeaders(),
            SessionAffinityFormat.parse(def.sessionAffinityFormat())
                .orElse(null),
            def.supportsStrictTools() != null && def.supportsStrictTools(),
            def.supportsOpenAIGrammarTools(),
            // 包 09：两个可空新键原样透传（缺省由车道构造期补：true / false）。
            def.supportsEagerToolInputStreaming(),
            def.supportsExplicitPromptCacheMode(),
            // B98（docs/51）：八字段原样透传；嵌套两类经专用助手转换。
            def.requiresToolResultName(),
            def.requiresAssistantAfterToolResult(),
            def.requiresThinkingAsText(),
            def.supportsUsageInStreaming(),
            def.zaiToolStream(),
            def.vllmPriority(),
            ModelsJsonCompatFieldParsers.vercelRoutingOf(providerId, model.id(), def.vercelGatewayRouting()),
            ModelsJsonCompatFieldParsers.fallbacksOf(providerId, model.id(), def.allowedFallbackModels()),
            // B99：原样透传可空值（缺省 false 在车道读点以 === true 判定）。
            def.supportsMidConvoEffort());
    }

    /**
     * D-P1：在<b>已归一</b>的 {@link ModelCompat} 上叠加一层 raw {@code CompatDef}
     * （pi 对未替换模型的 {@code mergeCompat(model.compat, config.compat)}，
     * provider-composer.ts:212）。raw 字段缺席 ⇒ 保留 base 组件；在场 ⇒ 覆盖；
     * 嵌套 map 键级合并。raw 整体 null ⇒ base 原样。
     */
    static ModelCompat mergeNormalizedCompat(String providerId, String modelId,
                                              ModelCompat base, CompatDef raw) {
        if (raw == null) {
            return base;
        }
        return new ModelCompat(
            raw.allowEmptySignature() != null
                ? raw.allowEmptySignature() : base.allowEmptySignature(),
            raw.requiresReasoningContentOnAssistantMessages() != null
                ? raw.requiresReasoningContentOnAssistantMessages()
                : base.requiresReasoningContentOnAssistantMessages(),
            raw.supportsFinishReason() != null
                ? raw.supportsFinishReason() : base.supportsFinishReason(),
            raw.forceAdaptiveThinking() != null
                ? raw.forceAdaptiveThinking() : base.forceAdaptiveThinking(),
            raw.supportsMidConvoSystemMessages() != null
                ? raw.supportsMidConvoSystemMessages()
                : base.supportsMidConvoSystemMessages(),
            raw.supportsMidConvoToolAdditions() != null
                ? raw.supportsMidConvoToolAdditions()
                : base.supportsMidConvoToolAdditions(),
            raw.supportsMidConvoToolChanges() != null
                ? raw.supportsMidConvoToolChanges()
                : base.supportsMidConvoToolChanges(),
            raw.supportsAdditionalTools() != null
                ? raw.supportsAdditionalTools() : base.supportsAdditionalTools(),
            raw.supportsToolSearch() != null
                ? raw.supportsToolSearch() : base.supportsToolSearch(),
            raw.supportsTemperature() != null
                ? raw.supportsTemperature() : base.supportsTemperature(),
            raw.maxTokensField() != null
                ? ModelsJsonCompatFieldParsers.maxTokensFieldOf(providerId, modelId, raw.maxTokensField())
                : base.maxTokensField(),
            raw.supportsStore() != null
                ? raw.supportsStore() : base.supportsStore(),
            raw.supportsDeveloperRole() != null
                ? raw.supportsDeveloperRole() : base.supportsDeveloperRole(),
            raw.supportsStrictMode() != null
                ? raw.supportsStrictMode() : base.supportsStrictMode(),
            raw.supportsLongCacheRetention() != null
                ? raw.supportsLongCacheRetention() : base.supportsLongCacheRetention(),
            raw.supportsCacheControlOnTools() != null
                ? raw.supportsCacheControlOnTools()
                : base.supportsCacheControlOnTools(),
            raw.thinkingTokenBudgetField() != null
                ? ModelsJsonCompatFieldParsers.thinkingTokenBudgetFieldOf(providerId, modelId,
                    raw.thinkingTokenBudgetField())
                : base.thinkingTokenBudgetField(),
            raw.supportsThinkingTokenBudget() != null
                ? raw.supportsThinkingTokenBudget()
                : base.supportsThinkingTokenBudget(),
            raw.supportsMaxOutputTokens() != null
                ? raw.supportsMaxOutputTokens() : base.supportsMaxOutputTokens(),
            raw.thinkingFormat() != null
                ? ModelsJsonCompatFieldParsers.thinkingFormatOf(providerId, modelId, raw.thinkingFormat())
                : base.thinkingFormat(),
            ModelsJsonCompatFieldParsers.mergeKwargMap(providerId, modelId, "chatTemplateKwargs",
                base.chatTemplateKwargs(), raw.chatTemplateKwargs()),
            ModelsJsonCompatFieldParsers.mergeKwargMap(providerId, modelId, "chatTemplateArgs",
                base.chatTemplateArgs(), raw.chatTemplateArgs()),
            raw.supportsReasoningEffort() != null
                ? raw.supportsReasoningEffort() : base.supportsReasoningEffort(),
            raw.cacheControlFormat() != null
                ? ModelsJsonCompatFieldParsers.cacheControlFormatOf(providerId, modelId, raw.cacheControlFormat())
                : base.cacheControlFormat(),
            mergeJsonMap(base.openRouterRouting(), raw.openRouterRouting()),
            raw.sendSessionAffinityHeaders() != null
                ? raw.sendSessionAffinityHeaders() : base.sendSessionAffinityHeaders(),
            raw.sessionAffinityFormat() != null
                ? SessionAffinityFormat.parse(raw.sessionAffinityFormat()).orElse(null)
                : base.sessionAffinityFormat(),
            raw.supportsStrictTools() != null
                ? raw.supportsStrictTools() : base.supportsStrictTools(),
            raw.supportsOpenAIGrammarTools() != null
                ? raw.supportsOpenAIGrammarTools()
                : base.supportsOpenAIGrammarTools(),
            // 包 09：raw 缺席 ⇒ 保留 base（mergeCompat 的逐字段覆盖语义）。
            raw.supportsEagerToolInputStreaming() != null
                ? raw.supportsEagerToolInputStreaming()
                : base.supportsEagerToolInputStreaming(),
            raw.supportsExplicitPromptCacheMode() != null
                ? raw.supportsExplicitPromptCacheMode()
                : base.supportsExplicitPromptCacheMode(),
            // B98：标量 override ?? base；嵌套两类（vercel 路由 / fallback 列表）
            // 非 map ⇒ 在场即整值替换（与 pi 的浅合并对非 map 值的语义同形）。
            raw.requiresToolResultName() != null
                ? raw.requiresToolResultName() : base.requiresToolResultName(),
            raw.requiresAssistantAfterToolResult() != null
                ? raw.requiresAssistantAfterToolResult()
                : base.requiresAssistantAfterToolResult(),
            raw.requiresThinkingAsText() != null
                ? raw.requiresThinkingAsText() : base.requiresThinkingAsText(),
            raw.supportsUsageInStreaming() != null
                ? raw.supportsUsageInStreaming() : base.supportsUsageInStreaming(),
            raw.zaiToolStream() != null
                ? raw.zaiToolStream() : base.zaiToolStream(),
            raw.vllmPriority() != null
                ? raw.vllmPriority() : base.vllmPriority(),
            raw.vercelGatewayRouting() != null
                ? ModelsJsonCompatFieldParsers.vercelRoutingOf(providerId, modelId, raw.vercelGatewayRouting())
                : base.vercelGatewayRouting(),
            raw.allowedFallbackModels() != null
                ? ModelsJsonCompatFieldParsers.fallbacksOf(providerId, modelId, raw.allowedFallbackModels())
                : base.allowedFallbackModels(),
            // B99：标量 override ?? base。
            raw.supportsMidConvoEffort() != null
                ? raw.supportsMidConvoEffort() : base.supportsMidConvoEffort());
    }

    /**
     * D-P1：pi {@code mergeCompat} 在 raw 层的合并（{@code provider-composer.ts:86-106}）：
     * 标量字段 {@code override ?? base}；三个嵌套 map（openRouterRouting／
     * chatTemplateKwargs／chatTemplateArgs）键级深合并。两层皆 null ⇒ {@code null}。
     */
    static CompatDef mergeRawCompat(CompatDef base, CompatDef override) {
        if (base == null) {
            return override;
        }
        if (override == null) {
            return base;
        }
        return new CompatDef(
            override.allowEmptySignature() != null
                ? override.allowEmptySignature() : base.allowEmptySignature(),
            override.requiresReasoningContentOnAssistantMessages() != null
                ? override.requiresReasoningContentOnAssistantMessages()
                : base.requiresReasoningContentOnAssistantMessages(),
            override.supportsFinishReason() != null
                ? override.supportsFinishReason() : base.supportsFinishReason(),
            override.forceAdaptiveThinking() != null
                ? override.forceAdaptiveThinking() : base.forceAdaptiveThinking(),
            override.supportsMidConvoSystemMessages() != null
                ? override.supportsMidConvoSystemMessages()
                : base.supportsMidConvoSystemMessages(),
            override.supportsMidConvoToolAdditions() != null
                ? override.supportsMidConvoToolAdditions()
                : base.supportsMidConvoToolAdditions(),
            override.supportsMidConvoToolChanges() != null
                ? override.supportsMidConvoToolChanges()
                : base.supportsMidConvoToolChanges(),
            override.supportsAdditionalTools() != null
                ? override.supportsAdditionalTools() : base.supportsAdditionalTools(),
            override.supportsToolSearch() != null
                ? override.supportsToolSearch() : base.supportsToolSearch(),
            override.supportsTemperature() != null
                ? override.supportsTemperature() : base.supportsTemperature(),
            override.maxTokensField() != null
                ? override.maxTokensField() : base.maxTokensField(),
            override.supportsStore() != null
                ? override.supportsStore() : base.supportsStore(),
            override.supportsDeveloperRole() != null
                ? override.supportsDeveloperRole() : base.supportsDeveloperRole(),
            override.supportsStrictMode() != null
                ? override.supportsStrictMode() : base.supportsStrictMode(),
            override.supportsLongCacheRetention() != null
                ? override.supportsLongCacheRetention() : base.supportsLongCacheRetention(),
            override.supportsCacheControlOnTools() != null
                ? override.supportsCacheControlOnTools()
                : base.supportsCacheControlOnTools(),
            override.thinkingTokenBudgetField() != null
                ? override.thinkingTokenBudgetField() : base.thinkingTokenBudgetField(),
            override.supportsThinkingTokenBudget() != null
                ? override.supportsThinkingTokenBudget()
                : base.supportsThinkingTokenBudget(),
            override.supportsMaxOutputTokens() != null
                ? override.supportsMaxOutputTokens() : base.supportsMaxOutputTokens(),
            override.thinkingFormat() != null
                ? override.thinkingFormat() : base.thinkingFormat(),
            mergeJsonMap(base.chatTemplateKwargs(), override.chatTemplateKwargs()),
            mergeJsonMap(base.chatTemplateArgs(), override.chatTemplateArgs()),
            override.supportsReasoningEffort() != null
                ? override.supportsReasoningEffort() : base.supportsReasoningEffort(),
            override.cacheControlFormat() != null
                ? override.cacheControlFormat() : base.cacheControlFormat(),
            mergeJsonMap(base.openRouterRouting(), override.openRouterRouting()),
            override.sendSessionAffinityHeaders() != null
                ? override.sendSessionAffinityHeaders() : base.sendSessionAffinityHeaders(),
            override.sessionAffinityFormat() != null
                ? override.sessionAffinityFormat() : base.sessionAffinityFormat(),
            override.supportsStrictTools() != null
                ? override.supportsStrictTools() : base.supportsStrictTools(),
            override.supportsOpenAIGrammarTools() != null
                ? override.supportsOpenAIGrammarTools()
                : base.supportsOpenAIGrammarTools(),
            // 包 09：raw 层同样逐字段覆盖（标量语义）。
            override.supportsEagerToolInputStreaming() != null
                ? override.supportsEagerToolInputStreaming()
                : base.supportsEagerToolInputStreaming(),
            override.supportsExplicitPromptCacheMode() != null
                ? override.supportsExplicitPromptCacheMode()
                : base.supportsExplicitPromptCacheMode(),
            // B98：raw 层六标量逐字段覆盖，嵌套两类在场即整值替换。
            override.requiresToolResultName() != null
                ? override.requiresToolResultName() : base.requiresToolResultName(),
            override.requiresAssistantAfterToolResult() != null
                ? override.requiresAssistantAfterToolResult()
                : base.requiresAssistantAfterToolResult(),
            override.requiresThinkingAsText() != null
                ? override.requiresThinkingAsText() : base.requiresThinkingAsText(),
            override.supportsUsageInStreaming() != null
                ? override.supportsUsageInStreaming() : base.supportsUsageInStreaming(),
            override.zaiToolStream() != null
                ? override.zaiToolStream() : base.zaiToolStream(),
            override.vllmPriority() != null
                ? override.vllmPriority() : base.vllmPriority(),
            override.vercelGatewayRouting() != null
                ? override.vercelGatewayRouting() : base.vercelGatewayRouting(),
            override.allowedFallbackModels() != null
                ? override.allowedFallbackModels() : base.allowedFallbackModels(),
            override.supportsMidConvoEffort() != null
                ? override.supportsMidConvoEffort() : base.supportsMidConvoEffort());
    }

    /** 两个 raw JSON map 的键级合并（{@code override} 同键覆盖）；皆 null ⇒ null。 */
    private static Map<String, Object> mergeJsonMap(Map<String, Object> base,
                                                    Map<String, Object> override) {
        if (base == null) {
            return override;
        }
        if (override == null) {
            return base;
        }
        var merged = new LinkedHashMap<>(base);
        merged.putAll(override);
        return merged;
    }

}
