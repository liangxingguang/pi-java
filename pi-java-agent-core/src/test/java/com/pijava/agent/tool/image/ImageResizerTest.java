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

import com.pijava.ai.catalog.InputLimits;

/**
 * 批 9/B85：缩放核心对齐 pi {@code image-resize-core.ts} 的算法形状
 * （默认档、限内直通、等比收边、PNG 先/JPEG 阶梯、1×1 终局、尺寸注记）。
 * 像素不逐字同（photon Lanczos3 vs BICUBIC，裁决 D2），只断言宽高/体积/文案。
 */
class ImageResizerTest {

    private static byte[] png(int width, int height) throws Exception {
        // 加噪点避免纯色图被压得过小、同时让缩放真的发生。
        var img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                g.setColor(new Color((x * 37 + y * 91) % 256, (x * 17) % 256, (y * 23) % 256));
                g.fillRect(x, y, 1, 1);
            }
        }
        g.dispose();
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static byte[] jpeg(int width, int height) throws Exception {
        var img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, width, height);
        g.dispose();
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpeg", out);
        return out.toByteArray();
    }

    private static byte[] webpVp8x(int width, int height) {
        var out = new ByteArrayOutputStream();
        out.writeBytes("RIFF".getBytes(StandardCharsets.US_ASCII));
        byte[] payload = new byte[18];
        int wm1 = width - 1;
        int hm1 = height - 1;
        payload[7] = (byte) wm1; payload[8] = (byte) (wm1 >>> 8); payload[9] = (byte) (wm1 >>> 16);
        payload[10] = (byte) hm1; payload[11] = (byte) (hm1 >>> 8); payload[12] = (byte) (hm1 >>> 16);
        out.writeBytes(new byte[4]);
        out.writeBytes("WEBP".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes("VP8X".getBytes(StandardCharsets.US_ASCII));
        int size = payload.length;
        out.write(size & 0xFF); out.write((size >>> 8) & 0xFF);
        out.write((size >>> 16) & 0xFF); out.write((size >>> 24) & 0xFF);
        out.writeBytes(payload);
        return out.toByteArray();
    }

    @Test
    void defaultsMatchPi() {
        assertThat(ImageResizer.DEFAULT_MAX_WIDTH).isEqualTo(2000);
        assertThat(ImageResizer.DEFAULT_MAX_HEIGHT).isEqualTo(2000);
        assertThat(ImageResizer.DEFAULT_JPEG_QUALITY).isEqualTo(80);
        assertThat(ImageResizer.DEFAULT_MAX_BYTES).isEqualTo((int) (4.5 * 1024 * 1024));
    }

    @Test
    void withinLimitsReturnsOriginalBytes() throws Exception {
        byte[] bytes = jpeg(2, 2);
        var result = ImageResizer.resize(bytes, "image/jpeg", null);
        assertThat(result).isNotNull();
        assertThat(result.wasResized()).isFalse();
        assertThat(result.data()).isEqualTo(Base64.getEncoder().encodeToString(bytes));
        assertThat(result.width()).isEqualTo(2);
        assertThat(result.height()).isEqualTo(2);
        assertThat(result.originalWidth()).isEqualTo(2);
        assertThat(ImageResizer.dimensionNote(result)).isNull();
    }

    @Test
    void resizesOversizedImageToFitBothDimensions() throws Exception {
        byte[] bytes = png(2400, 4800);
        var result = ImageResizer.resize(bytes, "image/png", null);
        assertThat(result).isNotNull();
        assertThat(result.wasResized()).isTrue();
        assertThat(result.originalWidth()).isEqualTo(2400);
        assertThat(result.originalHeight()).isEqualTo(4800);
        // 先收宽 2400→2000（h=4000），再收高 4000→2000（w=1000）。
        assertThat(result.width()).isEqualTo(1000);
        assertThat(result.height()).isEqualTo(2000);
        assertThat(result.mimeType()).isIn("image/png", "image/jpeg");
        String note = ImageResizer.dimensionNote(result);
        assertThat(note).contains("original 2400x4800");
        assertThat(note).contains("displayed at 1000x2000");
        assertThat(note).contains("2.40");
    }

    @Test
    void returnsNullWhenNothingFitsMaxBytes() throws Exception {
        byte[] bytes = png(200, 200);
        var impossible = new InputLimits.ResizeOptions(2000, 2000, 1, 80);
        assertThat(ImageResizer.resize(bytes, "image/png", impossible)).isNull();
    }

    @Test
    void undecodableBytesReturnNull() {
        assertThat(ImageResizer.resize("not-an-image".getBytes(StandardCharsets.UTF_8),
            "image/png", null)).isNull();
    }

    @Test
    void withinLimitWebpPassesThroughUsingHeaderDimensions() {
        byte[] bytes = webpVp8x(100, 50);
        var result = ImageResizer.resize(bytes, "image/webp", null);
        assertThat(result).isNotNull();
        assertThat(result.wasResized()).isFalse();
        assertThat(result.data()).isEqualTo(Base64.getEncoder().encodeToString(bytes));
        assertThat(result.mimeType()).isEqualTo("image/webp");
        assertThat(result.width()).isEqualTo(100);
        assertThat(result.height()).isEqualTo(50);
    }

    @Test
    void oversizedWebpCannotBeReencodedAndReturnsNull() {
        // 裁决 D1：JDK 无 WebP 编码器，超限等同 photon 不可用 ⇒ null（上层省略）。
        byte[] bytes = webpVp8x(3000, 3000);
        assertThat(ImageResizer.resize(bytes, "image/webp", null)).isNull();
    }

    @Test
    void modelProfileOverridesDefaultsPerField() throws Exception {
        byte[] bytes = png(240, 240);
        // maxBytes 极小 + 放宽边限：逼到 1×1 仍失败 ⇒ null，证明档位字段被读取。
        var tiny = new InputLimits.ResizeOptions(null, null, 1, null);
        assertThat(ImageResizer.resize(bytes, "image/png", tiny)).isNull();
    }
}
