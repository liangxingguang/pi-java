package com.pijava.ai.provider;

import java.util.LinkedHashMap;
import java.util.Map;

import com.pijava.ai.catalog.CacheControlFormat;
import com.pijava.ai.catalog.MaxTokensField;
import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.SessionAffinityFormat;
import com.pijava.ai.catalog.ChatTemplateKwargValue;
import com.pijava.ai.catalog.ThinkingFormat;
import com.pijava.ai.catalog.ThinkingTokenBudgetField;
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
            maxTokensFieldOf(providerId, model.id(), def.maxTokensField()),
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
            thinkingTokenBudgetFieldOf(providerId, model.id(), def.thinkingTokenBudgetField()),
            def.supportsThinkingTokenBudget(),
            // 包 A-10 第 6 步：`supportsMaxOutputTokens` 的缺省（`true`）由解析层按车道补
            // （CompatResolver.forResponses）⇒ 这里同样不归一 —— 「用户没写」必须能与
            // 「用户写了 true」区分开，因为只有前者会吃车道缺省。
            def.supportsMaxOutputTokens(),
            // 包 A-09：思考开关字段。`thinkingFormat`/`supportsReasoningEffort` 原样
            // 透传可空值（缺省由 CompatResolver.forCompletions 的探测补）；两个模板 map
            // 在这里就完成校验（$var 闭集、Literal 只收标量），null 由 ModelCompat 的
            // compact 构造器归一成空表。
            thinkingFormatOf(providerId, model.id(), def.thinkingFormat()),
            chatTemplateValuesOf(providerId, model.id(), "chatTemplateKwargs",
                def.chatTemplateKwargs()),
            chatTemplateValuesOf(providerId, model.id(), "chatTemplateArgs",
                def.chatTemplateArgs()),
            def.supportsReasoningEffort(),
            // 包 A-02：cacheControlFormat 闭集响亮抛（与 maxTokensField 同口径）；
            // openRouterRouting 纯透传（null 保持 null，不归一——原 docs/59 R6）。
            cacheControlFormatOf(providerId, model.id(), def.cacheControlFormat()),
            def.openRouterRouting(),
            // 包 B103：两个亲和字段原样透传可空值（缺省由车道构造期按 openrouter 探测补）。
            def.sendSessionAffinityHeaders(),
            SessionAffinityFormat.parse(def.sessionAffinityFormat())
                .orElse(null),
            def.supportsStrictTools() != null && def.supportsStrictTools(),
            def.supportsOpenAIGrammarTools());
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
                ? maxTokensFieldOf(providerId, modelId, raw.maxTokensField())
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
                ? thinkingTokenBudgetFieldOf(providerId, modelId, raw.thinkingTokenBudgetField())
                : base.thinkingTokenBudgetField(),
            raw.supportsThinkingTokenBudget() != null
                ? raw.supportsThinkingTokenBudget()
                : base.supportsThinkingTokenBudget(),
            raw.supportsMaxOutputTokens() != null
                ? raw.supportsMaxOutputTokens() : base.supportsMaxOutputTokens(),
            raw.thinkingFormat() != null
                ? thinkingFormatOf(providerId, modelId, raw.thinkingFormat())
                : base.thinkingFormat(),
            mergeKwargMap(providerId, modelId, "chatTemplateKwargs",
                base.chatTemplateKwargs(), raw.chatTemplateKwargs()),
            mergeKwargMap(providerId, modelId, "chatTemplateArgs",
                base.chatTemplateArgs(), raw.chatTemplateArgs()),
            raw.supportsReasoningEffort() != null
                ? raw.supportsReasoningEffort() : base.supportsReasoningEffort(),
            raw.cacheControlFormat() != null
                ? cacheControlFormatOf(providerId, modelId, raw.cacheControlFormat())
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
                : base.supportsOpenAIGrammarTools());
    }

    /** base ChatTemplateKwargValue map 上叠加 raw JSON 层（raw 键经 kwargValueOf 转换）。 */
    private static Map<String, ChatTemplateKwargValue> mergeKwargMap(
            String providerId, String modelId, String keyName,
            Map<String, ChatTemplateKwargValue> base, Map<String, Object> raw) {
        if (raw == null) {
            return base;
        }
        var merged = new LinkedHashMap<>(base);
        raw.forEach((key, value) ->
            merged.put(key, kwargValueOf(providerId, modelId, keyName, key, value)));
        return merged;
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
                : base.supportsOpenAIGrammarTools());
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

    /**
     * models.json 的 {@code cacheControlFormat} 串 ⇒ {@link CacheControlFormat}（包 A-02）。
     *
     * <p>⚠️ 未知取值**响亮抛错**，理由与 {@link #maxTokensFieldOf} 完全相同：它是单值闭集，
     * 写错会让 completions 线上的 {@code cache_control} 形状静默换掉（pi 的 TypeBox
     * {@code Type.Literal("anthropic")} 同样拒绝未知取值）。</p>
     */
    private static CacheControlFormat cacheControlFormatOf(String providerId, String modelId,
                                                           String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return CacheControlFormat.parse(value).orElseThrow(() -> new IllegalStateException(
            "models.json provider \"" + providerId + "\", model \"" + modelId
                + "\": unknown compat.cacheControlFormat \"" + value + "\" (expected \"anthropic\")"));
    }

    /**
     * models.json 的 {@code thinkingFormat} 串 ⇒ {@link ThinkingFormat}（包 A-09）。
     *
     * <p>⚠️ 未知取值**响亮抛错**，理由与 {@link #maxTokensFieldOf} 完全相同：它是十一值
     * 闭集，写错一个字母会让思考开关的形状静默换掉（甚至整个不发），而那种偏离没有任何
     * 其它症状（pi 的 zod 联合同样拒绝未知取值）。</p>
     */
    private static ThinkingFormat thinkingFormatOf(String providerId, String modelId,
                                                   String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return ThinkingFormat.parse(value).orElseThrow(() -> new IllegalStateException(
            "models.json provider \"" + providerId + "\", model \"" + modelId
                + "\": unknown compat.thinkingFormat \"" + value + "\" (expected one of: "
                + "openai, openrouter, deepseek, together, baseten, zai, qwen, "
                + "chat-template, qwen-chat-template, string-thinking, ant-ling)"));
    }

    /**
     * models.json 的 {@code chatTemplateKwargs}/{@code chatTemplateArgs} 对象 ⇒
     * {@link ChatTemplateKwargValue} 表（包 A-09）。缺席（{@code null}）原样返回 ——
     * 空表归一在 {@link ModelCompat} 的 compact 构造器（pi 的 {@code ?? {}}）。
     */
    private static Map<String, ChatTemplateKwargValue> chatTemplateValuesOf(
            String providerId, String modelId, String keyName, Map<String, Object> raw) {
        if (raw == null) {
            return null;
        }
        var out = new LinkedHashMap<String, ChatTemplateKwargValue>();
        raw.forEach((key, value) ->
            out.put(key, kwargValueOf(providerId, modelId, keyName, key, value)));
        return out;
    }

    /**
     * 一个声明值的分类（pi {@code ChatTemplateKwargValue}，{@code types.ts:87-95}）：
     * 标量（含 {@code null}）⇒ {@link ChatTemplateKwargValue.Literal}；
     * {@code {$var, omitWhenOff?}} ⇒ {@link ChatTemplateKwargValue.Var}。
     *
     * <p>⚠️ 其余形状（数组、缺 {@code $var} 的对象、{@code $var} 不在三值闭集、
     * {@code omitWhenOff} 不是布尔）**响亮抛错** —— 与本文件 {@code maxTokensField}
     * 同口径：静默降级会静默改变线格（pi 的 zod 同样拒绝）。</p>
     *
     * <p>⚠️ {@code Literal(null)} 是**合法值**（「写这个键，值是 null」，pi
     * {@code resolveChatTemplateKwargValue:1050} 的早返回）—— 别把 JSON null 当缺席吞掉
     * （{@code 原 docs/58} R11）。</p>
     */
    private static ChatTemplateKwargValue kwargValueOf(String providerId, String modelId,
                                                       String keyName, String key, Object raw) {
        var where = "models.json provider \"" + providerId + "\", model \"" + modelId
            + "\": compat." + keyName + "." + key;
        if (raw instanceof Map<?, ?> obj) {
            if (!(obj.get("$var") instanceof String varName)) {
                throw new IllegalStateException(where
                    + " is an object without a string \"$var\" (expected a scalar or "
                    + "{\"$var\": \"thinking.enabled\"|\"thinking.effort\"|\"thinking.budget\"})");
            }
            var var = ChatTemplateKwargValue.ThinkingVar.parse(varName).orElseThrow(
                () -> new IllegalStateException(where + " has an unknown $var \"" + varName
                    + "\" (expected \"thinking.enabled\", \"thinking.effort\" or "
                    + "\"thinking.budget\")"));
            var omit = obj.get("omitWhenOff");
            if (omit != null && !(omit instanceof Boolean)) {
                throw new IllegalStateException(where + ".omitWhenOff must be a boolean");
            }
            return new ChatTemplateKwargValue.Var(var, Boolean.TRUE.equals(omit));
        }
        if (raw == null || raw instanceof String || raw instanceof Number
                || raw instanceof Boolean) {
            return new ChatTemplateKwargValue.Literal(raw);
        }
        throw new IllegalStateException(where + " must be a string, number, boolean, null "
            + "or a {$var} object");
    }

    /**
     * models.json 的 {@code thinkingTokenBudgetField} 串 ⇒ {@link ThinkingTokenBudgetField}
     * （包 A-10）。
     *
     * <p>⚠️ 未知取值**响亮抛错**，理由与 {@link #maxTokensFieldOf} 完全相同：它是三值闭集，
     * 写错一个字母会让顶层预算字段的名字静默换掉，而那种偏离没有任何其它症状
     * （pi 的 zod 联合 {@code ThinkingTokenBudgetField} 同样拒绝未知取值）。</p>
     */
    private static ThinkingTokenBudgetField thinkingTokenBudgetFieldOf(
            String providerId, String modelId, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value) {
            case "thinking_token_budget" -> ThinkingTokenBudgetField.THINKING_TOKEN_BUDGET;
            case "thinking_budget" -> ThinkingTokenBudgetField.THINKING_BUDGET;
            case "thinking_budget_tokens" -> ThinkingTokenBudgetField.THINKING_BUDGET_TOKENS;
            default -> throw new IllegalStateException("models.json provider \"" + providerId
                + "\", model \"" + modelId + "\": unknown compat.thinkingTokenBudgetField \""
                + value + "\" (expected \"thinking_token_budget\", \"thinking_budget\" or "
                + "\"thinking_budget_tokens\")");
        };
    }

    /**
     * models.json 的 {@code maxTokensField} 串 ⇒ {@link MaxTokensField}
     * （{@code 原 docs/53 §4.3}）。
     *
     * <p>⚠️ 未知取值**响亮抛错**，而不是像同文件其它未知键那样被忽略：它是二值闭集，
     * 写错一个字母会让线格上的**字段名**静默换掉（{@code max_completion_tokens} ↔
     * {@code max_tokens}），而那种偏离没有任何其它症状。pi 的 zod 联合同样会拒绝未知取值
     * （{@code model-config.ts} 的 {@code ProviderCompatSchema}）。</p>
     */
    private static MaxTokensField maxTokensFieldOf(String providerId, String modelId,
                                                   String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value) {
            case "max_tokens" -> MaxTokensField.MAX_TOKENS;
            case "max_completion_tokens" -> MaxTokensField.MAX_COMPLETION_TOKENS;
            default -> throw new IllegalStateException("models.json provider \"" + providerId
                + "\", model \"" + modelId + "\": unknown compat.maxTokensField \"" + value
                + "\" (expected \"max_tokens\" or \"max_completion_tokens\")");
        };
    }
}
