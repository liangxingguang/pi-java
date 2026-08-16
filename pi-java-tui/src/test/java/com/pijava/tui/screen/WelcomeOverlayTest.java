package com.pijava.tui.screen;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Startup card text: rounded box, version, model line, directory line, tip.
 */
class WelcomeOverlayTest {

    @Test
    void cardContainsBannerVersionModelAndTip() {
        var text = new WelcomeOverlay().text();

        assertThat(text).startsWith("╭");
        assertThat(text).contains(">_ pi-java (v");
        assertThat(text).contains("model:");
        assertThat(text).contains("directory:");
        assertThat(text).contains("Tip:");
    }
}
