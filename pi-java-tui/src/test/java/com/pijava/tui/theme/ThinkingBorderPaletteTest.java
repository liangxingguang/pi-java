package com.pijava.tui.theme;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.pijava.coding.agent.theme.ThemeToken;

/** L7 W6（C15）：思考等级 → 主题令牌映射。 */
class ThinkingBorderPaletteTest {

    @Test
    void mapsPiLabelsToThemeTokens() {
        assertThat(ThinkingBorderPalette.token("off")).isEqualTo(ThemeToken.THINKING_OFF);
        assertThat(ThinkingBorderPalette.token("minimal")).isEqualTo(ThemeToken.THINKING_MINIMAL);
        assertThat(ThinkingBorderPalette.token("low")).isEqualTo(ThemeToken.THINKING_LOW);
        assertThat(ThinkingBorderPalette.token("medium")).isEqualTo(ThemeToken.THINKING_MEDIUM);
        assertThat(ThinkingBorderPalette.token("high")).isEqualTo(ThemeToken.THINKING_HIGH);
        assertThat(ThinkingBorderPalette.token("xhigh")).isEqualTo(ThemeToken.THINKING_XHIGH);
        assertThat(ThinkingBorderPalette.token("max")).isEqualTo(ThemeToken.THINKING_MAX);
        assertThat(ThinkingBorderPalette.token("unknown")).isEqualTo(ThemeToken.THINKING_OFF);
    }
}
