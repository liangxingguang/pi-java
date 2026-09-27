package com.pijava.ai.provider;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;

/**
 * Raw models.json provider/model definitions at the JSON boundary (Jackson).
 *
 * <p>Field names follow pi's models.json schema (model-config.ts). Unknown
 * fields are ignored so pi configs round-trip. Provider identity comes from
 * the map key in the {@code providers} object, not from a field.</p>
 */
public final class ModelsJsonSchema {

    private ModelsJsonSchema() {}

    /** Root: {@code {"providers": {"<id>": {...}}}}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Root(@JsonProperty("providers") Map<String, ProviderDef> providers) {}

    /** One provider entry. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProviderDef(
        @JsonProperty("name") String name,
        @JsonProperty("baseUrl") String baseUrl,
        @JsonProperty("apiKey") String apiKey,
        @JsonProperty("api") String api,
        @JsonProperty("models") List<ModelDef> models
    ) {}

    /** One model definition. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ModelDef(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("api") String api,
        @JsonProperty("baseUrl") String baseUrl,
        @JsonProperty("reasoning") Boolean reasoning,
        @JsonProperty("input") List<String> input,
        @JsonProperty("cost") Cost cost,
        @JsonProperty("contextWindow") Integer contextWindow,
        @JsonProperty("maxTokens") Integer maxTokens,
        @JsonProperty("headers") Map<String, String> headers,
        @JsonProperty("samplingParams") Map<String, Object> samplingParams,
        @JsonProperty("compat") CompatDef compat,
        @JsonProperty("thinkingLevelMap") Map<String, String> thinkingLevelMap
    ) {}

    /**
     * Per-model provider compatibility flags (pi {@code Model.compat}).
     *
     * <p>Listed explicitly so the key is a **known** one: while these records ignore unknown
     * properties, an unlisted {@code compat} would be swallowed silently and the flag would
     * appear to do nothing (docs/31 §8.34.2-6, 决策 2). Unknown properties *inside* {@code compat}
     * are still ignored — that is deliberate: it lets a pi models.json round-trip, and it is
     * safer than turning {@code ignoreUnknown} off globally (which would make any typo in a
     * user's models.json a hard error).</p>
     *
     * @param allowEmptySignature whether a thinking block may be replayed with an empty signature
     *                            (Anthropic lane). Absent ⇒ {@code false}
     * @param requiresReasoningContentOnAssistantMessages whether assistant history must always
     *                            carry {@code reasoning_content}, filled with {@code ""} when
     *                            there is no reasoning to send (pi
     *                            {@code openai-completions.ts:1356-1362}). ⚠️ **Three-state**:
     *                            absent ⇒ auto-detect from provider/baseUrl, and only an explicit
     *                            value overrides the detection (pi's {@code getCompat} is
     *                            {@code explicit ?? detected})
     * @param supportsFinishReason whether a stream ending without any {@code finish_reason} is an
     *                            error (pi {@code openai-completions.ts:685-692}). ⚠️ **Two-state
     *                            with default {@code true}** — the opposite direction to
     *                            {@code allowEmptySignature}: pi's detected value is the constant
     *                            {@code true} ({@code detectCompat:1640}), so an absent key means
     *                            "strict", and only an explicit {@code false} relaxes it
     * @param forceAdaptiveThinking whether the Anthropic lane uses adaptive thinking
     *                            ({@code {type:"adaptive"}} ＋ {@code output_config.effort}) instead
     *                            of budget-based ({@code {type:"enabled", budget_tokens}})
     *                            (pi {@code anthropic-messages.ts:878, 1165}). ⚠️ **Two-state with
     *                            default {@code false}** — pi's test is {@code === true}, so an
     *                            absent key and {@code false} are indistinguishable
     * @param supportsMidConvoSystemMessages whether mid-conversation system messages are kept
     *                            instead of folded into the leading one (pi {@code types.ts:731}).
     *                            ⚠️ **Three-state**: the generated catalogue turns it on for
     *                            capable models, so an absent key is not the same as an explicit
     *                            {@code false} (writing {@code false} wins over the catalogue)
     * @param supportsMidConvoToolAdditions whether a mid-conversation system message may carry its
     *                            own {@code tools} (completions lane, Kimi shape). Absent ⇒
     *                            {@code false} — pi's read points test {@code === true}
     * @param supportsMidConvoToolChanges whether the Anthropic lane may emit mid-conversation
     *                            {@code tool_addition}/{@code tool_removal} blocks (pi
     *                            {@code types.ts:832}). Absent ⇒ {@code false}
     * @param supportsAdditionalTools Responses lanes: message-anchored {@code additional_tools}
     *                            items. Absent ⇒ {@code false}
     * @param supportsToolSearch Responses lanes: the client-executed {@code tool_search} pair used
     *                            when {@code additional_tools} is unavailable. Absent ⇒ {@code false}
     * @param supportsTemperature whether the Anthropic lane may send {@code temperature} (pi
     *                            {@code anthropic-messages.ts:1105-1110}). ⚠️ **Two-state with
     *                            default {@code true}** — same shape as {@code supportsFinishReason}:
     *                            the value pi detects is the constant {@code true}, and the
     *                            generated catalogue writes {@code false} for the Opus 4.7+
     *                            generation, so only an explicit {@code false} suppresses it
     * @param maxTokensField which request field carries the output cap —
     *                            {@code "max_tokens"} or {@code "max_completion_tokens"} (pi
     *                            {@code openai-completions.ts:836-841}). ⚠️ **Three-state**: absent
     *                            ⇒ detected from the provider/baseUrl. An unknown string is a hard
     *                            error (see {@code ModelsJsonConfig.compatOf}) — a typo here would
     *                            silently change the wire
     * @param supportsStore whether the completions lane explicitly opts out of server-side
     *                            retention by sending {@code store:false} (pi
     *                            {@code openai-completions.ts:832-834}). Absent ⇒ detected
     * @param supportsDeveloperRole whether the leading instruction message uses
     *                            {@code role:"developer"} for reasoning models (pi
     *                            {@code openai-completions.ts:1225}). Absent ⇒ detected
     * @param supportsStrictMode Responses lanes: whether function tools carry {@code strict}
     *                            (pi {@code openai-responses.ts:74} {@code ?? false},
     *                            {@code azure-openai-responses.ts:296} {@code ?? true}). ⚠️ Absent
     *                            ⇒ **the lane's** default — the two responses lanes disagree, so
     *                            this key stays three-state
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CompatDef(
        @JsonProperty("allowEmptySignature") Boolean allowEmptySignature,
        @JsonProperty("requiresReasoningContentOnAssistantMessages")
        Boolean requiresReasoningContentOnAssistantMessages,
        @JsonProperty("supportsFinishReason") Boolean supportsFinishReason,
        @JsonProperty("forceAdaptiveThinking") Boolean forceAdaptiveThinking,
        @JsonProperty("supportsMidConvoSystemMessages") Boolean supportsMidConvoSystemMessages,
        @JsonProperty("supportsMidConvoToolAdditions") Boolean supportsMidConvoToolAdditions,
        @JsonProperty("supportsMidConvoToolChanges") Boolean supportsMidConvoToolChanges,
        @JsonProperty("supportsAdditionalTools") Boolean supportsAdditionalTools,
        @JsonProperty("supportsToolSearch") Boolean supportsToolSearch,
        @JsonProperty("supportsTemperature") Boolean supportsTemperature,
        @JsonProperty("maxTokensField") String maxTokensField,
        @JsonProperty("supportsStore") Boolean supportsStore,
        @JsonProperty("supportsDeveloperRole") Boolean supportsDeveloperRole,
        @JsonProperty("supportsStrictMode") Boolean supportsStrictMode,
        /** 包 A-01：{@code cacheRetention:"long"} 是否落成 Anthropic 的 {@code ttl:"1h"}；缺省 {@code true}。 */
        @JsonProperty("supportsLongCacheRetention") Boolean supportsLongCacheRetention,
        /** 包 A-01：是否把缓存断点挂到工具表末项；缺省 {@code true}。置 {@code false} 只撤工具那一处。 */
        @JsonProperty("supportsCacheControlOnTools") Boolean supportsCacheControlOnTools,
        /**
         * 包 A-10（pi {@code types.ts:725}）：顶层思考预算字段名，三值闭集
         * {@code "thinking_token_budget" | "thinking_budget" | "thinking_budget_tokens"}
         * （vLLM／Qwen／llama.cpp）。未知取值**响亮抛错**（同 {@code maxTokensField}）。
         */
        @JsonProperty("thinkingTokenBudgetField") String thinkingTokenBudgetField,
        /** 包 A-10（pi {@code types.ts:727}）：上一键的布尔别名，≙ {@code "thinking_token_budget"}。 */
        @JsonProperty("supportsThinkingTokenBudget") Boolean supportsThinkingTokenBudget
    ) {}

    /**
     * Per-million-token pricing (pi {@code ModelCostSchema},
     * {@code model-config.ts:125-139}: four rates + optional {@code tiers}).
     *
     * <p>包 H1 步 6（J10，{@code docs/42}）：此前只声明两键，而本记录是
     * {@code ignoreUnknown=true} ⇒ 用户在 models.json 里写的
     * {@code cacheRead}/{@code cacheWrite}/{@code tiers} 被<b>静默吞掉</b>。
     * 字段类型保持宽松的 {@code Double}（pi 的 zod 对存在的 cost 强制四费率齐全）——
     * 残缺要能活着走到 {@link ModelsJsonConfig} 的裁决 F 兜底（半价 ⇒ 未知，
     * 不是免费），而不是在 Jackson 层炸掉整个文件。</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Cost(
        @JsonProperty("input") Double input,
        @JsonProperty("output") Double output,
        @JsonProperty("cacheRead") Double cacheRead,
        @JsonProperty("cacheWrite") Double cacheWrite,
        @JsonProperty("tiers") List<CostTierDef> tiers
    ) {
        /**
         * One request-wide pricing tier (pi {@code ModelCostTierSchema}：
         * 五个字段全部必填，缺任一 ⇒ 拒载，见 {@code ModelsJsonConfig#pricingFrom})。
         */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record CostTierDef(
            @JsonProperty("inputTokensAbove") Double inputTokensAbove,
            @JsonProperty("input") Double input,
            @JsonProperty("output") Double output,
            @JsonProperty("cacheRead") Double cacheRead,
            @JsonProperty("cacheWrite") Double cacheWrite
        ) {}
    }
}
