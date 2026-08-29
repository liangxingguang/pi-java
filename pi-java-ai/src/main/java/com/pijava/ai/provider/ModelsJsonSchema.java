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
        @JsonProperty("samplingParams") Map<String, Object> samplingParams
    ) {}

    /** Per-million-token pricing. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Cost(
        @JsonProperty("input") Double input,
        @JsonProperty("output") Double output
    ) {}
}
