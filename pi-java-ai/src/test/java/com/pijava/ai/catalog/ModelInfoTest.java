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

    // ------------------------------------------------ 包 H2（docs/44 D2）：能力位读取

    /** 目录/models.json 声明了图片输入 ⇒ 真。 */
    @Test
    void imageCapabilityMeansSupported() {
        var model = new ModelInfo(
            ModelId.of("anthropic", "claude-sonnet-5"), "Claude Sonnet 5",
            Set.of(ModelCapability.TEXT, ModelCapability.IMAGE_INPUT),
            200_000, 8_192, false, PricingInfo.UNKNOWN);

        assertThat(model.supportsImageInput()).isTrue();
    }

    /** 能力位非空但**不含**图片输入 ⇒ 假（这是「已知不支持」，与下一条不同）。 */
    @Test
    void declaredTextOnlyModelMeansUnsupported() {
        var model = new ModelInfo(
            ModelId.of("deepseek", "deepseek-v4"), "DeepSeek V4",
            Set.of(ModelCapability.TEXT, ModelCapability.TOOL_USE),
            128_000, 8_192, false, PricingInfo.UNKNOWN);

        assertThat(model.supportsImageInput()).isFalse();
    }

    /**
     * ⚠️ {@code docs/44 D2} 的刻意偏离：目录未命中（{@code capabilities} 为空）⇒
     * **未知按支持**处理。反方向会把「用户读的图被静默换成占位文本」这个正在修的 bug
     * 在目录未命中时原样重演。pi 没有这个状态（它的 {@code getModel} 查不到就抛）。
     */
    @Test
    void unknownCapabilitiesMeanSupported() {
        assertThat(ModelInfo.minimal(ModelId.of("x", "unknown-model")).supportsImageInput())
            .isTrue();
    }
}
