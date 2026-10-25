package com.pijava.coding.agent.export;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pijava.coding.agent.theme.Themes;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTML 导出主题变量注入（批 21/docs/32 T12；pi export-html
 * {@code generateThemeVars}/{@code deriveExportColors} 对位）。
 */
class HtmlExporterThemeTest {

    @TempDir
    Path dir;

    @AfterEach
    void reset() {
        Themes.resetForTests();
    }

    @Test
    void darkThemeGoldenVars() throws Exception {
        var out = exportWithTheme("dark");
        // dark.json golden（pi 锚点 tsx 实跑）：accent=#a798d7、text=#dee0e1。
        assertThat(out).contains("--accent: #a798d7");
        assertThat(out).contains("--text: #dee0e1");
        // dark.json 有显式 export 节 ⇒ page/card 用显式值（okhsl ⇒ hex）。
        assertThat(out).containsPattern("--page: #[0-9a-f]{6}");
        assertThat(out).containsPattern("--card: #[0-9a-f]{6}");
    }

    @Test
    void explicitExportSectionWins() throws Exception {
        // 自定义主题带显式 export.pageBg ⇒ 优先于派生链。
        var themeFile = dir.resolve("my-theme.json");
        Files.writeString(themeFile, themeJson("#112233"));
        Themes.setRegisteredThemes(java.util.List.of(Themes.loadThemeFromPath(themeFile)));
        var out = exportWithTheme("my-theme");
        assertThat(out).contains("--page: #112233");
    }

    @Test
    void deriveArmsFollowPi() {
        // 深底（luminance ≤ 0.5）：0.7×/0.85×；浅底：0.96×/原值。
        var dark = ExportThemeVars.deriveExportColors("#343541");
        assertThat(dark.pageBg()).isEqualTo("rgb(36, 37, 46)");   // ×0.7 逐通道取整
        assertThat(dark.cardBg()).isEqualTo("rgb(44, 45, 55)");   // ×0.85 逐通道取整
        var light = ExportThemeVars.deriveExportColors("#fdf6e3");
        assertThat(light.cardBg()).isEqualTo("#fdf6e3");
        // 不可解析 ⇒ 固定回落（pi :85-90）。
        assertThat(ExportThemeVars.deriveExportColors("banana").pageBg())
            .isEqualTo("rgb(24, 24, 30)");
    }

    private String exportWithTheme(String themeName) throws Exception {
        var session = dir.resolve("session.jsonl");
        Files.writeString(session, """
            {"kind":"header","id":"t","timestamp":"2026-10-25T00:00:00Z","cwd":"/tmp"}
            """);
        var out = dir.resolve("out.html");
        new HtmlExporter().export(session, out, themeName);
        return Files.readString(out);
    }

    private static String themeJson(String pageBg) {
        var colors = new StringBuilder();
        for (var token : com.pijava.coding.agent.theme.ThemeToken.values()) {
            if (token.fallback() == null) {
                if (colors.length() > 0) {
                    colors.append(',');
                }
                colors.append('"').append(token.piName()).append("\":\"\"");
            }
        }
        return "{\"name\":\"my-theme\",\"colors\":{" + colors
            + "},\"export\":{\"pageBg\":\"" + pageBg + "\"}}";
    }
}
