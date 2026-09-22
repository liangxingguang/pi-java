package com.pijava.ai.protocol;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevelMap;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包H5 步4：Anthropic 车道的级别 → effort 翻译
 * （pi {@code anthropic-messages.ts:838-856}）。
 *
 * <p>两条判据：<b>目录映射优先</b>（值是字符串就直接用），<b>否则回退 switch</b>。
 * 回退表里最反直觉的是 {@code default → "high"} —— 它把 {@code xhigh}/{@code max}
 * 以及一切未列出的值都收到 {@code high}。</p>
 *
 * <p>⚠️ pi 的 {@code AnthropicEffort} 是 {@code "low"|"medium"|"high"|"xhigh"|"max"}
 * （{@code :177}）—— 映射值<b>不校验</b>，目录写什么就发什么。</p>
 */
class AnthropicThinkingEffortTest {

    private static ModelInfo model(ThinkingLevelMap map) {
        return new ModelInfo(
            ModelId.of("anthropic", "claude"), "Claude",
            Set.of(ModelCapability.TEXT, ModelCapability.THINKING),
            200_000, 8192, false, PricingInfo.UNKNOWN, map);
    }

    /** 目录给了字符串 ⇒ <b>原样</b>用它（不校验、不夹取）。 */
    @Test
    void mappedStringWinsVerbatim() {
        var map = ThinkingLevelMap.of(Map.of(
            ModelThinkingLevel.of(new ThinkingLevel.High()), Optional.of("MAX")));

        assertThat(AnthropicThinking.mapLevelToEffort(model(map), new ThinkingLevel.High()))
            .isEqualTo("MAX");
    }

    /** 目录映射**逐级**生效：只写了 {@code minimal} 时，其余级别仍走回退 switch。 */
    @Test
    void mappingIsPerLevel() {
        var map = ThinkingLevelMap.of(Map.of(
            ModelThinkingLevel.of(new ThinkingLevel.Minimal()), Optional.of("low")));

        assertThat(AnthropicThinking.mapLevelToEffort(model(map), new ThinkingLevel.Minimal()))
            .isEqualTo("low");
        assertThat(AnthropicThinking.mapLevelToEffort(model(map), new ThinkingLevel.Medium()))
            .isEqualTo("medium");
    }

    /** 空表 ⇒ 纯回退 switch：{@code minimal}/{@code low} 都收成 {@code low}。 */
    @Test
    void minimalAndLowBothFallBackToLow() {
        var m = model(ThinkingLevelMap.empty());

        assertThat(AnthropicThinking.mapLevelToEffort(m, new ThinkingLevel.Minimal())).isEqualTo("low");
        assertThat(AnthropicThinking.mapLevelToEffort(m, new ThinkingLevel.Low())).isEqualTo("low");
    }

    /** {@code medium} 与 {@code high} 各自对应同名 effort。 */
    @Test
    void mediumAndHighMapToThemselves() {
        var m = model(ThinkingLevelMap.empty());

        assertThat(AnthropicThinking.mapLevelToEffort(m, new ThinkingLevel.Medium())).isEqualTo("medium");
        assertThat(AnthropicThinking.mapLevelToEffort(m, new ThinkingLevel.High())).isEqualTo("high");
    }

    /**
     * ⚠️ {@code xhigh}/{@code max} <b>没有自己的回退值</b> —— pi 的 {@code default}
     * 把它们收到 {@code high}（只有目录显式映射才能把它们送出去，见第一条用例）。
     */
    @Test
    void xhighAndMaxFallBackToHigh() {
        var m = model(ThinkingLevelMap.empty());

        assertThat(AnthropicThinking.mapLevelToEffort(m, new ThinkingLevel.XHigh())).isEqualTo("high");
        assertThat(AnthropicThinking.mapLevelToEffort(m, new ThinkingLevel.Max())).isEqualTo("high");
    }

    /** 目录把某级标为**显式不支持**（{@code null}）⇒ 映射为空 ⇒ 仍走回退 switch。 */
    @Test
    void explicitlyUnsupportedLevelStillFallsBack() {
        var map = ThinkingLevelMap.of(Map.of(
            ModelThinkingLevel.of(new ThinkingLevel.High()), Optional.empty()));

        assertThat(AnthropicThinking.mapLevelToEffort(model(map), new ThinkingLevel.High()))
            .isEqualTo("high");
    }
}
