package com.pijava.tui.util;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TerminalImageTest {

    @Test
    void readsPngDimensions() {
        byte[] png = new byte[33];
        png[0] = (byte) 0x89;
        png[1] = 'P';
        png[2] = 'N';
        png[3] = 'G';
        writeInt(png, 16, 320);
        writeInt(png, 20, 200);

        var dims = TerminalImage.dimensions(Base64.getEncoder().encodeToString(png), "image/png");
        assertThat(dims).isEqualTo(new TerminalImage.Dimensions(320, 200));
    }

    @Test
    void readsGifDimensions() {
        byte[] gif = "GIF89a\u0010\u0000\u0008\u0000".getBytes(StandardCharsets.ISO_8859_1);
        var dims = TerminalImage.dimensions(Base64.getEncoder().encodeToString(gif), "image/gif");
        assertThat(dims).isEqualTo(new TerminalImage.Dimensions(16, 8));
    }

    @Test
    void unknownMimeHasNoDimensions() {
        assertThat(TerminalImage.dimensions("AAAA", "image/bmp")).isNull();
    }

    @Test
    void kittyEncodingChunksLongPayloads() {
        String base64 = "A".repeat(9000);
        String encoded = TerminalImage.encodeKitty(base64, 10, 5, 7, false);
        assertThat(encoded).contains("a=T,f=100,q=2").contains("C=1")
            .contains("c=10").contains("r=5").contains("i=7")
            .contains(",m=1;").contains("m=0;");
        assertThat(encoded.split("\u001b\\\\", -1).length).isGreaterThan(2);
    }

    @Test
    void kittyShortPayloadIsSingleSequence() {
        String encoded = TerminalImage.encodeKitty("QUJD", 4, 2, null, true);
        assertThat(encoded).startsWith("\u001b_G").endsWith("\u001b\\")
            .doesNotContain("C=1").doesNotContain("m=1");
    }

    @Test
    void iterm2EncodingCarriesInlineSizeAndWidth() {
        String encoded = TerminalImage.encodeITerm2("QUJD", 12, "a.png");
        assertThat(encoded).contains("inline=1").contains("size=")
            .contains("width=12").contains("height=auto").contains("name=");
    }

    @Test
    void cellSizeKeepsAspectRatioWithinWidthCap() {
        var size = TerminalImage.cellSize(new TerminalImage.Dimensions(800, 600),
            60, 0, 9, 18, false);
        assertThat(size.columns()).isEqualTo(60);
        assertThat(size.rows()).isBetween(22, 24);
    }

    @Test
    void cellSizeHonoursHeightCap() {
        var size = TerminalImage.cellSize(new TerminalImage.Dimensions(800, 600),
            80, 10, 9, 18, false);
        assertThat(size.rows()).isLessThanOrEqualTo(10);
        assertThat(size.columns()).isLessThan(80);
    }

    @Test
    void imageLineDetectionAndStripping() {
        String line = "before \u001b_Ga=T,f=100;QUJD\u001b\\ after";
        assertThat(TerminalImage.isImageLine(line)).isTrue();
        assertThat(TerminalImage.stripImageSequences(line)).isEqualTo("before  after");
        assertThat(TerminalImage.isImageLine("plain")).isFalse();
    }

    @Test
    void fallbackMatchesPiShape() {
        assertThat(TerminalImage.fallback("image/png", null, null))
            .isEqualTo("[Image: [image/png]]");
        assertThat(TerminalImage.fallback("image/png",
            new TerminalImage.Dimensions(10, 20), null))
            .isEqualTo("[Image: [image/png] 10x20]");
        assertThat(TerminalImage.fallback("image/png",
            new TerminalImage.Dimensions(10, 20), "/tmp/a.png"))
            .contains("/tmp/a.png").contains("[image/png] 10x20]");
    }

    private static void writeInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }
}
