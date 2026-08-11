package com.pijava.agent.thinking;

import java.util.Set;

import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingConfig;
import com.pijava.ai.thinking.ThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevelMap;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ThinkingLevelTest {

    @Test
    void orderedShouldReturnFiveLevels() {
        assertThat(ThinkingLevel.ordered()).hasSize(5);
    }

    @Test
    void clampShouldReturnSameWhenSupported() {
        var result = ThinkingLevel.clamp(
                new ThinkingLevel.Medium(),
                Set.of(new ThinkingLevel.Low(), new ThinkingLevel.Medium(), new ThinkingLevel.High()));
        assertThat(result).isInstanceOf(ThinkingLevel.Medium.class);
    }

    @Test
    void clampShouldFallbackUpwardFirst() {
        var result = ThinkingLevel.clamp(
                new ThinkingLevel.Low(),
                Set.of(new ThinkingLevel.Medium(), new ThinkingLevel.High()));
        assertThat(result).isInstanceOf(ThinkingLevel.Medium.class);
    }

    @Test
    void clampShouldFallbackDownwardIfNoUpward() {
        var result = ThinkingLevel.clamp(
                new ThinkingLevel.High(),
                Set.of(new ThinkingLevel.Low(), new ThinkingLevel.Medium()));
        assertThat(result).isInstanceOf(ThinkingLevel.Medium.class);
    }

    @Test
    void clampShouldFallbackToMinimal() {
        var result = ThinkingLevel.clamp(
                new ThinkingLevel.XHigh(),
                Set.of());
        assertThat(result).isInstanceOf(ThinkingLevel.Minimal.class);
    }

    @Test
    void modelThinkingLevelOff() {
        var off = ModelThinkingLevel.off();
        assertThat(off).isInstanceOf(ModelThinkingLevel.Off.class);
    }

    @Test
    void modelThinkingLevelEnabled() {
        var enabled = ModelThinkingLevel.of(new ThinkingLevel.High());
        assertThat(enabled).isInstanceOf(ModelThinkingLevel.Enabled.class);
        var e = (ModelThinkingLevel.Enabled) enabled;
        assertThat(e.level()).isInstanceOf(ThinkingLevel.High.class);
    }

    @Test
    void thinkingConfigOff() {
        assertThat(ThinkingConfig.OFF.enabled()).isFalse();
    }

    @Test
    void thinkingConfigWithBudget() {
        var config = ThinkingConfig.withBudget(4096);
        assertThat(config.enabled()).isTrue();
        assertThat(config.budgetTokens()).hasValue(4096);
    }

    @Test
    void thinkingLevelMapForLevelOff() {
        var map = ThinkingLevelMap.empty();
        var config = map.forLevel(ModelThinkingLevel.off());
        assertThat(config.enabled()).isFalse();
    }

    @Test
    void thinkingLevelMapForLevelEnabled() {
        var map = ThinkingLevelMap.of(java.util.Map.of(
                new ThinkingLevel.Low(), ThinkingConfig.withBudget(2048),
                new ThinkingLevel.Medium(), ThinkingConfig.withBudget(8192)
        ));
        var config = map.forLevel(ModelThinkingLevel.of(new ThinkingLevel.Low()));
        assertThat(config.enabled()).isTrue();
        assertThat(config.budgetTokens()).hasValue(2048);
    }

    @Test
    void thinkingLevelMapClampsUnsupported() {
        var map = ThinkingLevelMap.of(java.util.Map.of(
                new ThinkingLevel.Low(), ThinkingConfig.withBudget(2048)
        ));
        // Request High but only Low supported → clamp to Low
        var config = map.forLevel(ModelThinkingLevel.of(new ThinkingLevel.High()));
        assertThat(config.enabled()).isTrue();
        assertThat(config.budgetTokens()).hasValue(2048);
    }
}
