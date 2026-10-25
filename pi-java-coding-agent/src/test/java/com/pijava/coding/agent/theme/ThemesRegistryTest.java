package com.pijava.coding.agent.theme;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link Themes} 注册表测试（pi theme.ts 全局面 :682-731 对位，
 * 批 21/docs/32 §7）。静态态由 {@link Themes#resetForTests()} 隔离。
 */
class ThemesRegistryTest {

    @AfterEach
    void reset() {
        Themes.resetForTests();
    }

    @Test
    void initWithUnknownNameFallsBackToSystemSilently() {
        // pi initTheme catch 臂：currentThemeName 也回落 system。
        Themes.initTheme("does-not-exist", false);
        assertThat(Themes.currentThemeName()).isEqualTo("system");
        assertThat(Themes.current().name()).isEqualTo("system");
    }

    @Test
    void initWithNullMeansSystem() {
        Themes.initTheme(null, false);
        assertThat(Themes.currentThemeName()).isEqualTo("system");
    }

    @Test
    void setThemeFailureReportsErrorAndFallsBack() {
        var result = Themes.setTheme("does-not-exist", false);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo("Theme not found: does-not-exist");
        assertThat(Themes.currentThemeName()).isEqualTo("system");
    }

    @Test
    void setThemeSuccessNotifiesCallback() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        Themes.onThemeChange(calls::incrementAndGet);
        var result = Themes.setTheme("light", false);
        assertThat(result.success()).isTrue();
        assertThat(Themes.currentThemeName()).isEqualTo("light");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void registeredThemesShadowBuiltinAndTakePriority() {
        // 与内建同名的注册主题遮蔽内建（pi loadTheme 序：registered 先于 builtin）。
        var custom = Themes.loadThemeFromPath(writeTempTheme("dark", "#010203"));
        Themes.setRegisteredThemes(java.util.List.of(custom));
        Themes.initTheme("dark", false);
        assertThat(ThemeColorMath.colorToHex(Themes.current().colors().get(ThemeToken.ACCENT)))
            .isEqualTo("#010203");
    }

    @Test
    void systemNameIsReserved() {
        // 注册同名主题也压不过 system（pi loadTheme 第一臂）。
        var custom = Themes.loadThemeFromPath(writeTempTheme("system", "#010203"));
        Themes.setRegisteredThemes(java.util.List.of(custom));
        Themes.initTheme("system", false);
        assertThat(Themes.current().name()).isEqualTo("system");
        // system 是生成的（tier-3：accent＝ANSI 索引 5），不是文件的 #010203。
        assertThat(Themes.current().colors().get(ThemeToken.ACCENT))
            .isEqualTo(new ThemeColor.Indexed(5));
    }

    @Test
    void availableThemesOrderSystemFirstThenAlphabetical() {
        var names = Themes.getAvailableThemes();
        assertThat(names.get(0)).isEqualTo("system");
        assertThat(names).contains("dark", "light");
        var rest = names.subList(1, names.size());
        assertThat(rest).isSorted();
    }

    @Test
    void currentLazyInitializesToDark() {
        // 裁决⑤（窄差 D126）：未初始化 ⇒ 懒 dark（pi 抛错的对位）。
        assertThat(Themes.current().name()).isEqualTo("dark");
    }

    @Test
    void exportColorsForBuiltinDark() {
        // dark.json 的 export 节：okhsl 值 ⇒ hex（CSS 不支持 okhsl()）。
        var export = Themes.getThemeExportColors("dark");
        assertThat(export.pageBg()).asString().startsWith("#");
        assertThat(export.cardBg()).asString().startsWith("#");
        // system 恒空（pi :851-852）。
        assertThat(Themes.getThemeExportColors("system").pageBg()).isNull();
    }

    @Test
    void resolvedThemeColorsAreHexForAllTokens() {
        var colors = Themes.getResolvedThemeColors("dark");
        assertThat(colors).hasSize(ThemeToken.values().length);
        assertThat(colors.values()).allSatisfy(v -> assertThat(v).matches("#[0-9a-f]{6}"));
    }

    private static java.nio.file.Path writeTempTheme(String name, String accent) {
        try {
            var dir = java.nio.file.Files.createTempDirectory("pi-theme-test");
            var colors = new StringBuilder("\"accent\":\"" + accent + "\"");
            for (var token : ThemeToken.values()) {
                if (token.fallback() == null && token != ThemeToken.ACCENT) {
                    colors.append(",\"").append(token.piName()).append("\":\"\"");
                }
            }
            var path = dir.resolve(name + ".json");
            java.nio.file.Files.writeString(path,
                "{\"name\":\"" + name + "\",\"colors\":{" + colors + "}}");
            return path;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
