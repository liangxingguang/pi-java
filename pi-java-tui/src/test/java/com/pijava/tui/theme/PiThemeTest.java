package com.pijava.tui.theme;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 §3.2: built-in TCSS themes load and activate by name.
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
}
