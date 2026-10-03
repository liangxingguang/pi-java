package com.pijava.ai.catalog;

import org.junit.jupiter.api.Test;


import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/70：pi.dev Model wire DTO 的解析与映射（宽松忽略未知字段、
 * 模态/推理能力、thinkingLevelMap 三态）。
 */
class RemoteModelWireTest {

    @Test
    void parsesFullPiModelAndIgnoresUnknownFields() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
            .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        // pi Model 完整形状的超集：含 Java 不消费的 api/baseUrl/compat/tiers 等。
        var json = """
            {
              "id": "gpt-5",
              "name": "GPT-5",
              "api": "openai-responses",
              "provider": "ignored-field-overwritten",
              "baseUrl": "https://api.openai.com/v1",
              "reasoning": true,
              "input": ["text", "image"],
              "cost": {"input": 1.25, "output": 10.0,
                       "cacheRead": 0.1, "cacheWrite": 0.2,
                       "tiers": [{"input": 5, "output": 6,
                                  "cacheRead": 7, "cacheWrite": 8,
                                  "inputTokensAbove": 1000}]},
              "contextWindow": 400000,
              "maxTokens": 128000,
              "samplingParams": {"temperature": 0.7},
              "promptCache": {"short": 300},
              "compat": {"supportsStrictMode": true},
              "thinkingLevelMap": {"xhigh": null, "max": "max-2026"}
            }""";

        var wire = mapper.readValue(json, RemoteModelWire.class);
        var info = wire.toModelInfo("openai");

        assertThat(info.id().provider()).isEqualTo("openai");
        assertThat(info.id().modelName()).isEqualTo("gpt-5");
        assertThat(info.displayName()).isEqualTo("GPT-5");
        assertThat(info.maxInputTokens()).isEqualTo(400_000);
        assertThat(info.maxOutputTokens()).isEqualTo(128_000);
        assertThat(info.capabilities()).contains(
            ModelCapability.TEXT, ModelCapability.TOOL_USE, ModelCapability.STREAMING,
            ModelCapability.THINKING, ModelCapability.IMAGE_INPUT);
        assertThat(info.pricing().inputPrice()).isEqualTo(1.25);
        assertThat(info.pricing().outputPrice()).isEqualTo(10.0);
        var levels = info.thinkingLevelMap().entries();
        var max = com.pijava.ai.thinking.ModelThinkingLevel.parse("max").orElseThrow();
        var xhigh = com.pijava.ai.thinking.ModelThinkingLevel.parse("xhigh").orElseThrow();
        assertThat(levels).containsKey(max);
        assertThat(levels.get(max)).contains("max-2026");
        assertThat(levels.get(xhigh)).isEmpty();
    }

    @Test
    void absentOptionalFieldsDefaultSafely() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var wire = mapper.readValue("""
            {"id":"m","contextWindow":1000,"maxTokens":100}""",
            RemoteModelWire.class);

        var info = wire.toModelInfo("p");
        assertThat(info.displayName()).isEqualTo("m");
        assertThat(info.capabilities()).containsExactlyInAnyOrder(
            ModelCapability.TEXT, ModelCapability.TOOL_USE, ModelCapability.STREAMING);
        assertThat(info.pricing().inputPrice()).isZero();
        assertThat(info.thinkingLevelMap().entries()).isEmpty();
    }

    @Test
    void roundTripsModelInfoThroughWire() {
        var original = new ModelInfo(
            ModelId.of("openai", "gpt-5"), "GPT-5",
            new java.util.LinkedHashSet<>(java.util.List.of(
                ModelCapability.TEXT, ModelCapability.TOOL_USE,
                ModelCapability.STREAMING, ModelCapability.THINKING)),
            400_000, 128_000, false,
            new com.pijava.ai.model.PricingInfo(1.25, 10.0),
            com.pijava.ai.thinking.ThinkingLevelMap.empty());

        var restored = RemoteModelWire.fromModelInfo(original)
            .toModelInfo("openai");

        assertThat(restored.id()).isEqualTo(original.id());
        assertThat(restored.displayName()).isEqualTo(original.displayName());
        assertThat(restored.capabilities()).isEqualTo(original.capabilities());
        assertThat(restored.maxInputTokens()).isEqualTo(original.maxInputTokens());
        assertThat(restored.maxOutputTokens()).isEqualTo(original.maxOutputTokens());
        assertThat(restored.pricing().inputPrice()).isEqualTo(1.25);
        assertThat(restored.pricing().outputPrice()).isEqualTo(10.0);
    }
}
