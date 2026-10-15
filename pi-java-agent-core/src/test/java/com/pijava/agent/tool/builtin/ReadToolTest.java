package com.pijava.agent.tool.builtin;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;

import javax.imageio.ImageIO;

import com.pijava.agent.tool.ImagePolicy;
import com.pijava.agent.tool.TestContexts;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.ai.message.ContentBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

class ReadToolTest {

    @Test
    void readsTextFile(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("hello.txt");
        Files.writeString(file, "Hello, World!");
        var tool = ReadTool.create();
        var result = tool.execute("id1", new ReadTool.ReadInput(
            file.toString(), Optional.empty(), Optional.empty()),
            null, null, TestContexts.at(tmp));
        assertThat(result.content()).isNotEmpty();
        var text = ((ContentBlock.TextContent) result.content().get(0)).text();
        assertThat(text).contains("Hello, World!");
    }

    @Test
    void readsWithOffsetLimit(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("lines.txt");
        Files.writeString(file, "a\nb\nc\nd\ne");
        var tool = ReadTool.create();
        var result = tool.execute("id1", new ReadTool.ReadInput(
            file.toString(), Optional.of(2), Optional.of(2)),
            null, null, TestContexts.at(tmp));
        var text = ((ContentBlock.TextContent) result.content().get(0)).text();
        assertThat(text).contains("b", "c");
        assertThat(text).doesNotContain("a", "d");
    }

    @Test
    void readsImageFile(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("img.png");
        Files.write(file, png(2, 2));
        var tool = ReadTool.create();
        var result = tool.execute("id1", new ReadTool.ReadInput(
            file.toString(), Optional.empty(), Optional.empty()),
            null, null, TestContexts.at(tmp));
        assertThat(result.content()).hasSize(2);
        var text = ((ContentBlock.TextContent) result.content().get(0)).text();
        assertThat(text).isEqualTo("Read image file [image/png]");
        var image = (ContentBlock.ImageContent) result.content().get(1);
        assertThat(image.mediaType()).isEqualTo("image/png");
        assertThat(image.data()).isEqualTo(Base64.getEncoder().encodeToString(Files.readAllBytes(file)));
    }

    @Test
    void undecodableImageBecomesTextOnlyOmission(@TempDir Path tmp) throws Exception {
        // 合法 PNG 签但内容不可解码 ⇒ 缩放失败 ⇒ read 省略（pi image-resize-callers 夹具）。
        var file = tmp.resolve("broken.png");
        byte[] broken = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0, 0, 0, 13, 'I', 'H', 'D', 'R', 0, 0, 0, 1, 0, 0, 0, 1,
            8, 0, 0, 0, 0, 0, 0, 0};
        Files.write(file, broken);
        var tool = ReadTool.create();
        var result = tool.execute("id1", new ReadTool.ReadInput(
            file.toString(), Optional.empty(), Optional.empty()),
            null, null, TestContexts.at(tmp));
        assertThat(result.content()).hasSize(1);
        var text = ((ContentBlock.TextContent) result.content().get(0)).text();
        assertThat(text).startsWith("Read image file [image/png]");
        assertThat(text).contains("Image omitted");
    }

    @Test
    void nonVisionModelAddsAnnotationButKeepsImage(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("img.png");
        Files.write(file, png(2, 2));
        var context = new ToolContext(tmp.toString(), Map.of(),
            new DefaultShellExecutor(), new DefaultFileSystem(),
            () -> new ImagePolicy(true, null, false));
        var tool = ReadTool.create();
        var result = tool.execute("id1", new ReadTool.ReadInput(
            file.toString(), Optional.empty(), Optional.empty()),
            null, null, context);
        assertThat(result.content()).hasSize(2);
        var text = ((ContentBlock.TextContent) result.content().get(0)).text();
        assertThat(text).contains(
            "[Current model does not support images. The image will be omitted from this request.]");
        assertThat(result.content().get(1)).isInstanceOf(ContentBlock.ImageContent.class);
    }

    @Test
    void gateClosedReturnsOriginalBytes(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("img.png");
        Files.write(file, png(2, 2));
        var context = new ToolContext(tmp.toString(), Map.of(),
            new DefaultShellExecutor(), new DefaultFileSystem(),
            () -> new ImagePolicy(false, null, true));
        var tool = ReadTool.create();
        var result = tool.execute("id1", new ReadTool.ReadInput(
            file.toString(), Optional.empty(), Optional.empty()),
            null, null, context);
        var image = (ContentBlock.ImageContent) result.content().get(1);
        assertThat(image.data()).isEqualTo(Base64.getEncoder().encodeToString(Files.readAllBytes(file)));
    }

    @Test
    void nonImageBinaryFallsBackToText(@TempDir Path tmp) throws Exception {
        // 只有 PNG 签、IHDR 全零的桩过不了加严嗅探，按文本处理（pi 同）。
        var file = tmp.resolve("fake.png");
        byte[] stub = new byte[20];
        stub[0] = (byte) 0x89;
        Files.write(file, stub);
        var tool = ReadTool.create();
        var result = tool.execute("id1", new ReadTool.ReadInput(
            file.toString(), Optional.empty(), Optional.empty()),
            null, null, TestContexts.at(tmp));
        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0)).isInstanceOf(ContentBlock.TextContent.class);
    }

    private static byte[] png(int width, int height) throws Exception {
        var img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.RED);
        g.fillRect(0, 0, 1, 1);
        g.dispose();
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
