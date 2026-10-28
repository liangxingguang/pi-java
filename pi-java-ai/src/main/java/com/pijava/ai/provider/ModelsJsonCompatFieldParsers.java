package com.pijava.ai.provider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pijava.ai.catalog.AllowedFallbackModel;
import com.pijava.ai.catalog.CacheControlFormat;
import com.pijava.ai.catalog.ChatTemplateKwargValue;
import com.pijava.ai.catalog.MaxTokensField;
import com.pijava.ai.catalog.ThinkingFormat;
import com.pijava.ai.catalog.ThinkingTokenBudgetField;
import com.pijava.ai.catalog.VercelGatewayRouting;
import com.pijava.ai.model.PricingInfo;

/**
 * models.json compat 的字段解析器簇（从 {@code ModelsJsonCompat} 拆出，
 * 结构债拆分——纯移动零行为变化）：raw 值 → 目录领域类型，未知闭集取值一律响亮抛错。
 */
final class ModelsJsonCompatFieldParsers {

    private ModelsJsonCompatFieldParsers() {}

    /** base ChatTemplateKwargValue map 上叠加 raw JSON 层（raw 键经 kwargValueOf 转换）。 */
    static Map<String, ChatTemplateKwargValue> mergeKwargMap(
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
     * B98：raw {@code vercelGatewayRouting} → {@link VercelGatewayRouting}。
     * 两列表原样保留（{@code null} ≙ 未写该子键）。
     */
    static VercelGatewayRouting vercelRoutingOf(String providerId, String modelId,
            ModelsJsonSchema.CompatDef.VercelGatewayRoutingDef def) {
        if (def == null) {
            return null;
        }
        return new VercelGatewayRouting(def.only(), def.order());
    }

    /**
     * B98：raw {@code allowedFallbackModels} → 领域类型；{@code cost} 按
     * {@link ModelsJsonConfig#pricingFrom} 同形四费率（缺 cache 取 -1 未知）。
     * ⚠️ provider/model 必填（pi schema {@code minLength:1}），缺任一一律响亮报错。
     */
    static List<AllowedFallbackModel> fallbacksOf(String providerId, String modelId,
            List<ModelsJsonSchema.CompatDef.FallbackModelDef> defs) {
        if (defs == null) {
            return null;
        }
        var out = new ArrayList<AllowedFallbackModel>(defs.size());
        for (var d : defs) {
            if (d.provider() == null || d.provider().isBlank()
                    || d.model() == null || d.model().isBlank()) {
                throw new IllegalStateException("models.json provider \"" + providerId
                    + "\", model \"" + modelId
                    + "\": every allowedFallbackModels entry needs provider and model");
            }
            out.add(new AllowedFallbackModel(d.provider(), d.model(), fallbackCost(d.cost())));
        }
        return List.copyOf(out);
    }

    private static PricingInfo fallbackCost(ModelsJsonSchema.Cost cost) {
        if (cost == null || cost.input() == null || cost.output() == null) {
            return PricingInfo.UNKNOWN;
        }
        return new PricingInfo(cost.input(), cost.output(),
            cost.cacheRead() != null ? cost.cacheRead() : -1,
            cost.cacheWrite() != null ? cost.cacheWrite() : -1,
            List.of());
    }

    /**
     * models.json 的 {@code cacheControlFormat} 串 ⇒ {@link CacheControlFormat}（包 A-02）。
     *
     * <p>⚠️ 未知取值**响亮抛错**，理由与 {@link #maxTokensFieldOf} 完全相同：它是单值闭集，
     * 写错会让 completions 线上的 {@code cache_control} 形状静默换掉（pi 的 TypeBox
     * {@code Type.Literal("anthropic")} 同样拒绝未知取值）。</p>
     */
    static CacheControlFormat cacheControlFormatOf(String providerId, String modelId,
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
    static ThinkingFormat thinkingFormatOf(String providerId, String modelId,
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
     * 空表归一在 {@link com.pijava.ai.catalog.ModelCompat} 的 compact 构造器（pi 的 {@code ?? {}}）。
     */
    static Map<String, ChatTemplateKwargValue> chatTemplateValuesOf(
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
    static ThinkingTokenBudgetField thinkingTokenBudgetFieldOf(
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
    static MaxTokensField maxTokensFieldOf(String providerId, String modelId,
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
