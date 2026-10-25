package com.pijava.coding.agent.theme;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ThemeSettings} 测试（pi theme-detection.test.ts 向量移植，
 * 批 21/docs/32 §7）。
 */
class ThemeSettingsTest {

    // ── parseAutoThemeSetting 五臂 ──────────────────────────────

    @Test
    void parseAutoThemeSettingArms() {
        assertThat(ThemeSettings.parseAutoThemeSetting(null)).isNull();
        assertThat(ThemeSettings.parseAutoThemeSetting("dark")).isNull();
        assertThat(ThemeSettings.parseAutoThemeSetting("a/b/c")).isNull();
        assertThat(ThemeSettings.parseAutoThemeSetting("/dark")).isNull();
        assertThat(ThemeSettings.parseAutoThemeSetting("light/")).isNull();
        var pair = ThemeSettings.parseAutoThemeSetting(" light / dark ");
        assertThat(pair).isNotNull();
        assertThat(pair.lightTheme()).isEqualTo("light");
        assertThat(pair.darkTheme()).isEqualTo("dark");
    }

    @Test
    void resolveThemeSettingArms() {
        assertThat(ThemeSettings.resolveThemeSetting("light/dark", ThemeAppearance.LIGHT))
            .isEqualTo("light");
        assertThat(ThemeSettings.resolveThemeSetting("light/dark", ThemeAppearance.DARK))
            .isEqualTo("dark");
        // 含 / 的畸形值 ⇒ null（pi :603）。
        assertThat(ThemeSettings.resolveThemeSetting("a/b/c", ThemeAppearance.DARK)).isNull();
        assertThat(ThemeSettings.resolveThemeSetting("nord", ThemeAppearance.DARK))
            .isEqualTo("nord");
        assertThat(ThemeSettings.resolveThemeSetting(null, ThemeAppearance.DARK)).isNull();
    }

    // ── detectColorFgBgTheme（pi 测试逐字向量）───────────────────

    @Test
    void colorFgBgVectors() {
        assertThat(ThemeSettings.detectColorFgBgTheme("15;0")).isEqualTo(ThemeAppearance.DARK);
        assertThat(ThemeSettings.detectColorFgBgTheme("0;7;15")).isEqualTo(ThemeAppearance.LIGHT);
        // Solarized Dark 的背景是亮黑。
        assertThat(ThemeSettings.detectColorFgBgTheme("12;8")).isEqualTo(ThemeAppearance.DARK);
        // rxvt 在非调色板色时写 "default"。
        assertThat(ThemeSettings.detectColorFgBgTheme("15;default")).isNull();
        assertThat(ThemeSettings.detectColorFgBgTheme(null)).isNull();
        assertThat(ThemeSettings.detectColorFgBgTheme("0;16")).isNull();
    }

    // ── detectTerminalTheme（pi 测试逐字向量）────────────────────

    @Test
    void terminalThemePriority() {
        // 背景优先于上报 scheme。
        assertThat(ThemeSettings.detectTerminalTheme(
            new TerminalColors(null, new ThemeColor.Rgb(8, 8, 8), null), ThemeAppearance.LIGHT))
            .isEqualTo(ThemeAppearance.DARK);
        assertThat(ThemeSettings.detectTerminalTheme(TerminalColors.EMPTY, ThemeAppearance.DARK))
            .isEqualTo(ThemeAppearance.DARK);
        // 全无 ⇒ dark。
        assertThat(ThemeSettings.detectTerminalTheme(TerminalColors.EMPTY, null))
            .isEqualTo(ThemeAppearance.DARK);
    }

    @Test
    void terminalThemeFollowsReadableForeground() {
        var background = new ThemeColor.Rgb(118, 118, 118);
        // pi 向量：bg 118 灰 ⇒ light（黑字对比更高）。
        assertThat(SystemTheme.terminalAppearance(background, null))
            .isEqualTo(ThemeAppearance.LIGHT);
        // 前景白且可读 ⇒ 听前景 ⇒ dark。
        assertThat(SystemTheme.terminalAppearance(background, new ThemeColor.Rgb(255, 255, 255)))
            .isEqualTo(ThemeAppearance.DARK);
        // 中灰上白字达不到 4.5:1 ⇒ 不听前景。
        assertThat(SystemTheme.terminalAppearance(new ThemeColor.Rgb(128, 128, 128),
            new ThemeColor.Rgb(255, 255, 255))).isEqualTo(ThemeAppearance.LIGHT);
    }

    @Test
    void wcagContrastKnownPairs() {
        assertThat(SystemTheme.wcagContrast(
            new ThemeColor.Rgb(255, 255, 255), new ThemeColor.Rgb(0, 0, 0)))
            .isCloseTo(21.0, org.assertj.core.api.Assertions.within(0.01));
        assertThat(SystemTheme.wcagContrast(
            new ThemeColor.Rgb(255, 255, 255), new ThemeColor.Rgb(255, 255, 255)))
            .isCloseTo(1.0, org.assertj.core.api.Assertions.within(0.001));
    }
}
