package com.pijava.tui.theme;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 3 §3.2 + P6-21: built-in TCSS themes load and activate by name;
 * a custom theme file path loads as the active stylesheet.
 */
class PiThemeTest {

    @Test
    void darkThemeLoadsAndActivates() {
        assertThat(PiTheme.engineFor("dark").getActiveStylesheet())
            .contains("dark");
    }

    @Test
    void lightThemeLoadsAndActivates() {
        assertThat(PiTheme.engineFor("light").getActiveStylesheet())
            .contains("light");
    }

    @Test
    void unknownThemeFallsBackToDark() {
        assertThat(PiTheme.engineFor("bogus").getActiveStylesheet())
            .contains("dark");
    }

    @Test
    void customThemeFileLoadsAndActivates(@TempDir Path dir) throws IOException {
        Path theme = dir.resolve("nord.tcss");
        Files.writeString(theme, ".Screen { background: #2e3440; }\n");

        assertThat(PiTheme.engineFor(theme.toString()).getActiveStylesheet())
            .contains("custom");
    }

    @Test
    void missingCustomThemeFileThrows(@TempDir Path dir) {
        Path missing = dir.resolve("missing.tcss");
        assertThatThrownBy(() -> PiTheme.engineFor(missing.toString()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not found");
    }

    // ── resolveTheme（resources_discover 主题候选优先级） ────────────────

    @Test
    void resolveThemePrecedenceCliWins() {
        assertThat(PiTheme.resolveTheme("light", "dark", List.of(Path.of("x"))))
            .isEqualTo("light");
    }

    @Test
    void resolveThemeSettingsWhenNoCli() {
        assertThat(PiTheme.resolveTheme(null, "light", List.of(Path.of("x"))))
            .isEqualTo("light");
    }

    @Test
    void resolveThemeFirstCandidateWhenNoExplicit() {
        assertThat(PiTheme.resolveTheme(null, null,
            List.of(Path.of("cand1"), Path.of("cand2"))))
            .isEqualTo("cand1");
    }

    @Test
    void resolveThemeSkipsNullCandidates() {
        assertThat(PiTheme.resolveTheme(null, null, Arrays.asList(null, Path.of("cand2"))))
            .isEqualTo("cand2");
    }

    @Test
    void resolveThemeDefaultsToDark() {
        assertThat(PiTheme.resolveTheme(null, null, null)).isEqualTo("dark");
        assertThat(PiTheme.resolveTheme(null, null, List.of())).isEqualTo("dark");
    }
}
