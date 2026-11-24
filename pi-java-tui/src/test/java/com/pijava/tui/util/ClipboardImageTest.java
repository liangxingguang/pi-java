package com.pijava.tui.util;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClipboardImageTest {

    @Test
    void extensionFollowsSupportedWhitelist() {
        assertThat(ClipboardImage.extensionFor("image/png")).isEqualTo("png");
        assertThat(ClipboardImage.extensionFor("image/jpeg; charset=binary")).isEqualTo("jpg");
        assertThat(ClipboardImage.extensionFor("image/webp")).isEqualTo("webp");
        assertThat(ClipboardImage.extensionFor("image/gif")).isEqualTo("gif");
        assertThat(ClipboardImage.extensionFor("image/bmp")).isNull();
        assertThat(ClipboardImage.extensionFor(null)).isNull();
    }

    @Test
    void selectPreferredPrefersPngThenAnyImage() {
        assertThat(ClipboardImage.selectPreferred(List.of("text/plain", "image/jpeg", "image/png")))
            .isEqualTo("image/png");
        assertThat(ClipboardImage.selectPreferred(List.of("text/plain", "image/bmp")))
            .isEqualTo("image/bmp");
        assertThat(ClipboardImage.selectPreferred(List.of("text/plain")))
            .isNull();
    }
}
