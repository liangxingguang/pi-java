package com.pijava.ai.catalog;

import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6 对齐：ModelInfo headers/samplingParams 字段建模 + 序列化。
 * （整记录反序列化受 ThinkingLevelMap 的 ThinkingLevel Map-key 限制，既有，
 *  不在本任务范围；此处验证新字段建模与 JSON 输出。）
 */
class ModelInfoTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void headersAndSamplingParamsAreModeled() throws Exception {
        var model = new ModelInfo(
            ModelId.of("openai", "gpt-5"), "GPT-5",
            Set.of(ModelCapability.TEXT), 128_000, 16_384, false,
            PricingInfo.UNKNOWN,
            Map.of("X-Custom", "v1"),
            Map.of("top_p", 0.9));

        assertThat(model.headers()).isEqualTo(Map.of("X-Custom", "v1"));
        assertThat(model.samplingParams()).isEqualTo(Map.of("top_p", 0.9));

        String json = JSON.writeValueAsString(model);
        assertThat(json).contains("\"headers\"")
            .contains("X-Custom")
            .contains("\"samplingParams\"")
            .contains("top_p");
    }

    @Test
    void defaultsToEmptyMaps() {
        var model = new ModelInfo(
            ModelId.of("openai", "gpt-5-mini"), "GPT-5 Mini",
            Set.of(ModelCapability.TEXT), 128_000, 8_192, false, PricingInfo.UNKNOWN);
        assertThat(model.headers()).isEmpty();
        assertThat(model.samplingParams()).isEmpty();
    }
}
