package com.pijava.coding.agent.cli;

import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevel;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 §16: {@code --thinking} mapping (7 inputs → 6 levels + fallback).
 */
class ThinkingLevelsTest {

    @Test
    void mapsAllValidLevels() {
        assertThat(ThinkingLevels.parse("off")).isInstanceOf(ModelThinkingLevel.Off.class);
        assertThat(ThinkingLevels.parse("minimal"))
            .isEqualTo(ModelThinkingLevel.of(new ThinkingLevel.Minimal()));
        assertThat(ThinkingLevels.parse("low"))
            .isEqualTo(ModelThinkingLevel.of(new ThinkingLevel.Low()));
        assertThat(ThinkingLevels.parse("medium"))
            .isEqualTo(ModelThinkingLevel.of(new ThinkingLevel.Medium()));
        assertThat(ThinkingLevels.parse("high"))
            .isEqualTo(ModelThinkingLevel.of(new ThinkingLevel.High()));
        assertThat(ThinkingLevels.parse("xhigh"))
            .isEqualTo(ModelThinkingLevel.of(new ThinkingLevel.XHigh()));
        // ⚠️ 包H5 步8：pi 的 ThinkingLevel 是 6 级（types.ts:84），"max" 有自己的级别
        // —— 此前 pi-java 把它并进 XHigh（5 级方言）。
        assertThat(ThinkingLevels.parse("max"))
            .isEqualTo(ModelThinkingLevel.of(new ThinkingLevel.Max()));
    }

    @Test
    void unknownFallsBackToOff() {
        assertThat(ThinkingLevels.parse("bogus"))
            .isInstanceOf(ModelThinkingLevel.Off.class);
        assertThat(ThinkingLevels.parse(null))
            .isInstanceOf(ModelThinkingLevel.Off.class);
    }

    @Test
    void isCaseInsensitive() {
        assertThat(ThinkingLevels.parse("HIGH"))
            .isEqualTo(ModelThinkingLevel.of(new ThinkingLevel.High()));
    }

    @Test
    void extractsSuffixFromModelPattern() {
        assertThat(ThinkingLevels.parseFromModelPattern(
            "anthropic/claude-sonnet:high"))
            .isEqualTo(ModelThinkingLevel.of(new ThinkingLevel.High()));
        assertThat(ThinkingLevels.parseFromModelPattern("gpt-5")).isNull();
        assertThat(ThinkingLevels.parseFromModelPattern(
            "anthropic/claude-sonnet:bogus")).isNull();
    }
}
