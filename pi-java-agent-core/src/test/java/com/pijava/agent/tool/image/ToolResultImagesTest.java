package com.pijava.agent.tool.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import com.pijava.agent.tool.ImagePolicy;
import com.pijava.ai.message.ContentBlock;

/**
 * 批 9/B85：工具结果图片收口对拍 pi {@code test/tool-result-images.test.ts}
 * （同引用、失败留原图、BMP 关闸仍转换、顺序保持、hints 追文本）。
 */
class ToolResultImagesTest {

    private static final ImagePolicy OPEN = new ImagePolicy(true, null, true);
    private static final ImagePolicy CLOSED = new ImagePolicy(false, null, true);

    private static String pngBase64(int width, int height, boolean noisy) throws Exception {
        var img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        if (noisy) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    g.setColor(new Color((x * 37 + y * 91) % 256, (x * 17) % 256, (y * 23) % 256));
                    g.fillRect(x, y, 1, 1);
                }
            }
        }
        g.dispose();
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private static String bmpBase64() throws Exception {
        var img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "bmp", out);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private static ContentBlock.ImageContent image(String mime, String data) {
        return new ContentBlock.ImageContent(mime, data);
    }

    @Test
    void noImageBlocksReturnsSameList() {
        var content = List.<ContentBlock>of(new ContentBlock.TextContent("no images here"));
        assertThat(ToolResultImages.normalize(content, OPEN)).isSameAs(content);
    }

    @Test
    void withinLimitImageReturnsSameList() throws Exception {
        var content = List.<ContentBlock>of(
            new ContentBlock.TextContent("shot"),
            image("image/png", pngBase64(2, 2, false)));
        assertThat(ToolResultImages.normalize(content, OPEN)).isSameAs(content);
    }

    @Test
    void resizesOversizedImageAndAppendsHint() throws Exception {
        var content = List.<ContentBlock>of(image("image/png", pngBase64(2400, 4800, true)));
        var normalized = ToolResultImages.normalize(content, OPEN);
        assertThat(normalized).isNotSameAs(content).hasSize(2);
        assertThat(normalized.get(0)).isInstanceOf(ContentBlock.ImageContent.class);
        assertThat(normalized.get(1)).isInstanceOf(ContentBlock.TextContent.class);
        assertThat(((ContentBlock.TextContent) normalized.get(1)).text())
            .contains("original 2400x4800");
    }

    @Test
    void gateClosedLeavesOversizedImageAlone() throws Exception {
        var content = List.<ContentBlock>of(image("image/png", pngBase64(2400, 4800, true)));
        assertThat(ToolResultImages.normalize(content, CLOSED)).isSameAs(content);
    }

    @Test
    void convertsBmpEvenWhenGateClosed() throws Exception {
        var content = List.<ContentBlock>of(image("image/bmp", bmpBase64()));
        var normalized = ToolResultImages.normalize(content, CLOSED);
        assertThat(normalized).isNotSameAs(content).hasSize(2);
        assertThat(((ContentBlock.ImageContent) normalized.get(0)).mediaType())
            .isEqualTo("image/png");
        assertThat(((ContentBlock.TextContent) normalized.get(1)).text())
            .isEqualTo("[Image converted from image/bmp to image/png.]");
    }

    @Test
    void undecodableImageIsKeptInsteadOfDropped() {
        // pi：normalize 失败保留原块（与 read 的省略相反），"not-an-image" base64。
        String data = Base64.getEncoder()
            .encodeToString("not-an-image".getBytes(StandardCharsets.UTF_8));
        var content = List.<ContentBlock>of(image("image/png", data));
        assertThat(ToolResultImages.normalize(content, OPEN)).isSameAs(content);
    }

    @Test
    void surroundingTextOrderIsPreserved() throws Exception {
        var content = List.<ContentBlock>of(
            new ContentBlock.TextContent("before"),
            image("image/png", pngBase64(2400, 100, true)),
            new ContentBlock.TextContent("after"));
        var normalized = ToolResultImages.normalize(content, OPEN);
        assertThat(normalized).hasSize(4);
        assertThat(((ContentBlock.TextContent) normalized.get(0)).text()).isEqualTo("before");
        assertThat(normalized.get(1)).isInstanceOf(ContentBlock.ImageContent.class);
        assertThat(normalized.get(2)).isInstanceOf(ContentBlock.TextContent.class);
        assertThat(((ContentBlock.TextContent) normalized.get(3)).text()).isEqualTo("after");
    }
}
