package com.pijava.ai.catalog;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevelMap;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包H5 步7：目录 wire DTO 的 {@code thinkingLevelMap} 往返
 * （{@code docs/46 §3-D5-2}）。
 *
 * <p>改动前 {@code CatalogModel} 在<b>两个方向都丢弃</b>这张表 ⇒ 远程目录与
 * {@code FileModelsStore} <b>结构上装不了</b>它 —— 光开 models.json 的键只覆盖了本地文件那一条路。</p>
 */
class CatalogModelThinkingMapTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ModelInfo modelWith(ThinkingLevelMap map) {
        return new ModelInfo(
            ModelId.of("p", "m"), "M",
            Set.of(ModelCapability.TEXT, ModelCapability.THINKING),
            128_000, 8192, false, PricingInfo.UNKNOWN, map);
    }

    private static ModelThinkingLevel lvl(ThinkingLevel level) {
        return ModelThinkingLevel.of(level);
    }

    /** {@link ModelInfo} → DTO → {@link ModelInfo}：三态逐个活下来。 */
    @Test
    void modelInfoRoundTripPreservesAllThreeStates() {
        var map = ThinkingLevelMap.of(Map.of(
            lvl(new ThinkingLevel.XHigh()), Optional.empty(),      // 显式不支持
            lvl(new ThinkingLevel.Max()), Optional.of("max"),      // 有映射
            ModelThinkingLevel.off(), Optional.of("none")));       // off 有映射
        // low 键缺席（第三态）

        var back = CatalogModel.fromModelInfo(modelWith(map)).toModelInfo();

        assertThat(back.thinkingLevelMap().explicitlyUnsupported(lvl(new ThinkingLevel.XHigh()))).isTrue();
        assertThat(back.thinkingLevelMap().mapped(lvl(new ThinkingLevel.Max()))).contains("max");
        assertThat(back.thinkingLevelMap().mapped(ModelThinkingLevel.off())).contains("none");
        assertThat(back.thinkingLevelMap().hasEntry(lvl(new ThinkingLevel.Low()))).isFalse();
    }

    /** 空表 ⇒ DTO 侧写 {@code null}（不发明一个空对象），回来仍是空表。 */
    @Test
    void emptyMapRoundTripsAsEmpty() {
        var back = CatalogModel.fromModelInfo(modelWith(ThinkingLevelMap.empty())).toModelInfo();

        assertThat(back.thinkingLevelMap()).isEqualTo(ThinkingLevelMap.empty());
    }

    /** ⚠️ JSON 往返：{@code {"xhigh": null}} 的键必须活下来（不能被写成「键缺席」）。 */
    @Test
    void jsonRoundTripKeepsTheExplicitNullKey() throws Exception {
        var map = ThinkingLevelMap.of(Map.of(
            lvl(new ThinkingLevel.XHigh()), Optional.empty()));

        var json = MAPPER.writeValueAsString(CatalogModel.fromModelInfo(modelWith(map)));
        var back = MAPPER.readValue(json, CatalogModel.class).toModelInfo();

        assertThat(json).contains("\"xhigh\":null");
        assertThat(back.thinkingLevelMap().hasEntry(lvl(new ThinkingLevel.XHigh()))).isTrue();
        assertThat(back.thinkingLevelMap().explicitlyUnsupported(lvl(new ThinkingLevel.XHigh()))).isTrue();
    }

    /** 九参便捷构造仍然可用（既有构造点零改签），且得到空表。 */
    @Test
    void nineArgConvenienceConstructorYieldsAnEmptyMap() {
        var dto = new CatalogModel("p", "m", "M", java.util.List.of("text"), 1, 2, false, 0, 0);

        assertThat(dto.toModelInfo().thinkingLevelMap()).isEqualTo(ThinkingLevelMap.empty());
    }
}
