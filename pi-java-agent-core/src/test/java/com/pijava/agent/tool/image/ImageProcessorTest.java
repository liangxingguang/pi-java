package com.pijava.agent.tool.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import com.pijava.agent.tool.ImagePolicy;

/**
 * 批 9/B85：processImage 对拍 pi {@code utils/image-process.ts} 与其测试夹具
 * （image-process.test / image-resize-callers / tool-result-images）。
 */
class ImageProcessorTest {

    private static byte[] write(String format, int width, int height) throws Exception {
        var img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.RED);
        g.fillRect(0, 0, 1, 1);
        g.dispose();
        var out = new ByteArrayOutputStream();
        assertThat(ImageIO.write(img, format, out)).isTrue();
        return out.toByteArray();
    }

    private static final ImagePolicy GATE_OPEN = new ImagePolicy(true, null, true);
    private static final ImagePolicy GATE_CLOSED = new ImagePolicy(false, null, true);

    @Test
    void convertsBmpToPngWhenResizing() throws Exception {
        byte[] bmp = write("bmp", 1, 1);
        var result = ImageProcessor.process(bmp, "image/bmp", GATE_OPEN);
        assertThat(result).isInstanceOf(ImageProcessor.Processed.Ok.class);
        var ok = (ImageProcessor.Processed.Ok) result;
        assertThat(ok.mimeType()).isEqualTo("image/png");
        assertThat(ok.hints()).containsExactly(
            "[Image converted from image/bmp to image/png.]");
        assertPngMagic(ok.data());
    }

    @Test
    void convertsBmpToPngEvenWhenResizeDisabled() throws Exception {
        // pi 夹具钉死：异类转换不受 autoResizeImages 门控。
        byte[] bmp = write("bmp", 1, 1);
        var result = ImageProcessor.process(bmp, "image/bmp", GATE_CLOSED);
        assertThat(result).isInstanceOf(ImageProcessor.Processed.Ok.class);
        var ok = (ImageProcessor.Processed.Ok) result;
        assertThat(ok.mimeType()).isEqualTo("image/png");
        assertThat(ok.hints()).containsExactly(
            "[Image converted from image/bmp to image/png.]");
        assertPngMagic(ok.data());
    }

    @Test
    void withinLimitPngPassesThroughWithNoHints() throws Exception {
        byte[] png = write("png", 2, 2);
        var result = ImageProcessor.process(png, "image/png", GATE_OPEN);
        assertThat(result).isInstanceOf(ImageProcessor.Processed.Ok.class);
        var ok = (ImageProcessor.Processed.Ok) result;
        assertThat(ok.data()).isEqualTo(Base64.getEncoder().encodeToString(png));
        assertThat(ok.mimeType()).isEqualTo("image/png");
        assertThat(ok.hints()).isEmpty();
    }

    @Test
    void jpgAliasCanonicalizesMimeWithoutConversionHint() throws Exception {
        byte[] jpeg = write("jpeg", 2, 2);
        var result = ImageProcessor.process(jpeg, "image/jpg", GATE_OPEN);
        var ok = (ImageProcessor.Processed.Ok) result;
        assertThat(ok.mimeType()).isEqualTo("image/jpeg");
        assertThat(ok.hints()).isEmpty();
    }

    @Test
    void stripsMimeParametersBeforeNormalizing() throws Exception {
        byte[] jpeg = write("jpeg", 2, 2);
        var result = ImageProcessor.process(jpeg, "image/jpeg; charset=binary", GATE_OPEN);
        var ok = (ImageProcessor.Processed.Ok) result;
        assertThat(ok.mimeType()).isEqualTo("image/jpeg");
    }

    @Test
    void undecodableSupportedMimeFailsResize() {
        var result = ImageProcessor.process(
            "not-an-image".getBytes(StandardCharsets.UTF_8), "image/png", GATE_OPEN);
        assertThat(result).isInstanceOf(ImageProcessor.Processed.Failure.class);
        assertThat(((ImageProcessor.Processed.Failure) result).message())
            .isEqualTo("[Image omitted: could not be resized below the inline image size limit.]");
    }

    @Test
    void undecodableUnsupportedMimeFailsConversion() {
        var result = ImageProcessor.process(
            "not-an-image".getBytes(StandardCharsets.UTF_8), "image/x-targa", GATE_OPEN);
        assertThat(result).isInstanceOf(ImageProcessor.Processed.Failure.class);
        assertThat(((ImageProcessor.Processed.Failure) result).message())
            .isEqualTo("[Image omitted: could not be converted to a supported inline image format.]");
    }

    @Test
    void gateClosedPassesBytesThroughUnchanged() throws Exception {
        byte[] png = write("png", 2, 2);
        var result = ImageProcessor.process(png, "image/png", GATE_CLOSED);
        var ok = (ImageProcessor.Processed.Ok) result;
        assertThat(ok.data()).isEqualTo(Base64.getEncoder().encodeToString(png));
        assertThat(ok.hints()).isEmpty();
    }

    private static void assertPngMagic(String base64) {
        byte[] bytes = Base64.getDecoder().decode(base64);
        assertThat(bytes[0] & 0xFF).isEqualTo(0x89);
        assertThat(bytes[1]).isEqualTo((byte) 0x50);
        assertThat(bytes[2]).isEqualTo((byte) 0x4E);
        assertThat(bytes[3]).isEqualTo((byte) 0x47);
    }
}
