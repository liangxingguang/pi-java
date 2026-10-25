package com.pijava.coding.agent.theme;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ThemeLoader} 测试（pi resource-loader.ts loadThemes/dedupeThemes 对位，
 * 批 21/docs/32 §7）。
 */
class ThemeLoaderTest {

    @TempDir
    Path dir;

    private Path writeTheme(String fileName, String name) throws Exception {
        var colors = new StringBuilder();
        for (var token : ThemeToken.values()) {
            if (token.fallback() == null) {
                if (colors.length() > 0) {
                    colors.append(',');
                }
                colors.append('"').append(token.piName()).append("\":\"\"");
            }
        }
        var path = dir.resolve(fileName);
        Files.writeString(path, "{\"name\":\"" + name + "\",\"colors\":{" + colors + "}}");
        return path;
    }

    @Test
    void warnsOnMissingPathAndNonJsonFile() throws Exception {
        var notJson = dir.resolve("theme.txt");
        Files.writeString(notJson, "{}");
        var result = ThemeLoader.loadThemes(
            List.of(dir.resolve("absent").toString(), notJson.toString()),
            TerminalColorMode.TRUECOLOR);
        assertThat(result.themes()).isEmpty();
        assertThat(result.diagnostics()).hasSize(2);
        assertThat(result.diagnostics().get(0).message()).isEqualTo("theme path does not exist");
        assertThat(result.diagnostics().get(1).message())
            .isEqualTo("theme path is not a json file");
        assertThat(result.diagnostics().get(0).level()).isEqualTo("warning");
    }

    @Test
    void scansDirectoryForJsonFilesOnly() throws Exception {
        writeTheme("nord.json", "nord");
        Files.writeString(dir.resolve("notes.md"), "not a theme");
        var subdir = dir.resolve("nested");
        Files.createDirectory(subdir);  // 目录被跳过（pi isFile 门）
        var result = ThemeLoader.loadThemes(List.of(dir.toString()),
            TerminalColorMode.TRUECOLOR);
        assertThat(result.themes()).hasSize(1);
        assertThat(result.themes().get(0).name()).isEqualTo("nord");
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void invalidThemeFileYieldsWarningNotAbort() throws Exception {
        Files.writeString(dir.resolve("broken.json"), "{\"name\":\"broken\"}");
        var ok = writeTheme("ok.json", "ok");
        var result = ThemeLoader.loadThemes(
            List.of(dir.resolve("broken.json").toString(), ok.toString()),
            TerminalColorMode.TRUECOLOR);
        assertThat(result.themes()).hasSize(1);
        assertThat(result.diagnostics()).hasSize(1);
        assertThat(result.diagnostics().get(0).message()).contains("Invalid theme \"");
    }

    @Test
    void dedupeFirstWinsWithCollisionDiagnostic() throws Exception {
        var first = writeTheme("a.json", "dup");
        var second = writeTheme("b.json", "dup");
        var result = ThemeLoader.loadThemes(
            List.of(first.toString(), second.toString()), TerminalColorMode.TRUECOLOR);
        assertThat(result.themes()).hasSize(1);
        assertThat(result.themes().get(0).sourcePath()).isEqualTo(first.toString());
        assertThat(result.diagnostics()).hasSize(1);
        var collision = result.diagnostics().get(0);
        assertThat(collision.level()).isEqualTo("collision");
        assertThat(collision.message()).isEqualTo("name \"dup\" collision");
        assertThat(collision.collision().resourceType()).isEqualTo("theme");
        assertThat(collision.collision().winnerPath()).isEqualTo(first.toString());
        assertThat(collision.collision().loserPath()).isEqualTo(second.toString());
    }
}
