package com.pijava.tui.screen;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FirstTimeSetupScreenTest {

    @Test
    void themeStepPreviewsAndAdvancesToAnalytics() {
        var screen = new FirstTimeSetupScreen();
        var previews = new ArrayList<String>();

        screen.move(1, previews::add);
        assertThat(previews).containsExactly("dark");
        assertThat(screen.lines(80)).contains("Pick a theme.", "→ Dark", "  Light");

        assertThat(screen.confirm()).isFalse();
        assertThat(screen.lines(80)).anyMatch(line ->
            line.contains("Opt-in to anonymous usage data sharing?"));
        assertThat(screen.hint()).contains("finish");
    }

    @Test
    void defaultResultIsSystemThemeAndShareEnabled() {
        var screen = new FirstTimeSetupScreen();
        screen.confirm();
        assertThat(screen.confirm()).isTrue();
        assertThat(screen.result()).isEqualTo(
            new FirstTimeSetupScreen.Result("system", true));
    }

    @Test
    void secondAnalyticsOptionIsDontShare() {
        var screen = new FirstTimeSetupScreen();
        screen.confirm();
        screen.move(1, theme -> { });
        assertThat(screen.confirm()).isTrue();
        assertThat(screen.result().shareAnalytics()).isFalse();
    }

    @Test
    void cancelSkipsWithoutResultUse() {
        var screen = new FirstTimeSetupScreen();
        screen.cancel();
        assertThat(screen.cancelled()).isTrue();
        assertThat(screen.lines(80)).isInstanceOf(List.class);
    }
}
