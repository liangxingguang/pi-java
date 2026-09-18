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
        @JsonProperty("compat") CompatDef compat
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
     *                            {@code true} ({@code detectCompat:1638}), so an absent key means
     *                            "strict", and only an explicit {@code false} relaxes it
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CompatDef(
        @JsonProperty("allowEmptySignature") Boolean allowEmptySignature,
        @JsonProperty("requiresReasoningContentOnAssistantMessages")
        Boolean requiresReasoningContentOnAssistantMessages,
        @JsonProperty("supportsFinishReason") Boolean supportsFinishReason
    ) {}

    /** Per-million-token pricing. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Cost(
        @JsonProperty("input") Double input,
        @JsonProperty("output") Double output
    ) {}
}
