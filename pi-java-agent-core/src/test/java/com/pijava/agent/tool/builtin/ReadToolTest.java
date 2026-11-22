package com.pijava.agent.tool.builtin;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
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
import com.pijava.ai.AbortSignal;
import com.pijava.ai.message.ContentBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void descriptionPromisesPi50KBBudget() {
        // B158：pi read.ts 的描述由 DEFAULT_MAX_BYTES 现算 —— 50KB（旧值是 97KB）。
        assertThat(ReadTool.create().description()).contains("50KB").doesNotContain("97KB");
    }

    @Test
    void firstLineExceedsNoteQuotesThe50KBBudget(@TempDir Path tmp) throws Exception {
        // 单行 >51200 字节 ⇒ 首行超限注记（50.0KB + head -c 51200，旧值是 100000）。
        var file = tmp.resolve("long.txt");
        Files.writeString(file, "x".repeat(60_000));
        var tool = ReadTool.create();
        var result = tool.execute("id1", new ReadTool.ReadInput(
            file.toString(), Optional.empty(), Optional.empty()),
            null, null, TestContexts.at(tmp));
        var text = ((ContentBlock.TextContent) result.content().get(0)).text();
        assertThat(text).contains("exceeds 50.0KB limit").contains("head -c 51200");
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

    @Test
    void descriptionEndsWithContinuationHint() {
        // pi read.ts:239 末句：read 是整文件接力的入口。
        assertThat(ReadTool.create().description()).endsWith(
            "When you need the full file, continue with offset until complete.");
    }

    @Test
    void firstLineNoticeQuotesTheLineSizeAndThe50KBBudget(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("long.txt");
        Files.writeString(file, "x".repeat(60_000));
        var tool = ReadTool.create();
        var result = tool.execute("id1", new ReadTool.ReadInput(
            file.toString(), Optional.empty(), Optional.empty()),
            null, null, TestContexts.at(tmp));
        var text = ((ContentBlock.TextContent) result.content().get(0)).text();
        assertThat(text).startsWith("[Line 1 is 58.6KB, exceeds 50.0KB limit. Use bash: sed -n '1p'");
        assertThat(result.details().truncation().firstLineExceedsLimit()).isTrue();
    }

    @Test
    void lineCountTruncationNoticeOmitsTheByteLimit(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("many.txt");
        Files.writeString(file, String.join("\n", java.util.stream.IntStream.range(0, 2500)
            .mapToObj(i -> "l" + i).toList()));
        var tool = ReadTool.create();
        var result = tool.execute("id1", new ReadTool.ReadInput(
            file.toString(), Optional.empty(), Optional.empty()),
            null, null, TestContexts.at(tmp));
        var text = ((ContentBlock.TextContent) result.content().get(0)).text();
        assertThat(text).endsWith(
            "\n\n[Showing lines 1-2000 of 2500. Use offset=2001 to continue.]");
        assertThat(text).doesNotContain("(50.0KB limit)");
    }

    @Test
    void byteTruncationNoticeIncludesTheByteLimit(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("wide.txt");
        Files.writeString(file, String.join("\n",
            java.util.stream.IntStream.range(0, 200).mapToObj(i -> "y".repeat(400)).toList()));
        var tool = ReadTool.create();
        var result = tool.execute("id1", new ReadTool.ReadInput(
            file.toString(), Optional.empty(), Optional.empty()),
            null, null, TestContexts.at(tmp));
        var text = ((ContentBlock.TextContent) result.content().get(0)).text();
        assertThat(text).contains("of 200 (50.0KB limit). Use offset=");
    }

    @Test
    void streamingSelectionMatchesWholeFileSelection() throws Exception {
        String content = String.join("\n", java.util.stream.IntStream.range(0, 3000)
            .mapToObj(i -> i == 2 ? "x".repeat(60_000) : "line " + i).toList());
        assertSelectionMatches(content, 0, null);
        assertSelectionMatches(content, 2, null);
        assertSelectionMatches(content, 2, 5);
        assertSelectionMatches(content, 10, 2500);
        assertSelectionMatches("a\r\nb\r\nc", 0, 2);
        assertSelectionMatches("emoji 😀😀\nnext", 0, 1);
    }

    @Test
    void abortedStreamStopsWithOperationAborted() throws Exception {
        var signal = AbortSignal.create();
        signal.abort();
        var in = new java.io.ByteArrayInputStream("a\nb\n".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> StreamingLineSelector.select(in, 0, null, signal))
            .isInstanceOf(java.io.IOException.class)
            .hasMessage("Operation aborted");
    }

    private static void assertSelectionMatches(String content, int startLine, Integer limit)
            throws Exception {
        var streamed = StreamingLineSelector.select(
            new java.io.ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
            startLine, limit, null);
        var buffer = bufferSelection(content, startLine, limit);
        assertThat(streamed.totalFileLines()).as("totalFileLines").isEqualTo(buffer.totalFileLines());
        assertThat(streamed.firstLineBytes()).as("firstLineBytes").isEqualTo(buffer.firstLineBytes());
        var a = streamed.truncation();
        var b = buffer.truncation();
        assertThat(a.content()).as("content").isEqualTo(b.content());
        assertThat(a.truncated()).as("truncated").isEqualTo(b.truncated());
        assertThat(a.truncatedBy()).as("truncatedBy").isEqualTo(b.truncatedBy());
        assertThat(a.firstLineExceedsLimit()).as("firstLineExceeds")
            .isEqualTo(b.firstLineExceedsLimit());
        assertThat(a.outputLines()).as("outputLines").isEqualTo(b.outputLines());
        assertThat(a.totalLines()).as("totalLines").isEqualTo(b.totalLines());
        assertThat(a.totalBytes()).as("totalBytes").isEqualTo(b.totalBytes());
    }

    private static StreamingLineSelector.Selection bufferSelection(String content, int startLine,
            Integer limit) {
        String[] all = content.split("\n", -1);
        int end = limit == null ? all.length : Math.min(startLine + limit, all.length);
        String selected = String.join("\n", java.util.List.of(all).subList(startLine, end));
        var truncation = com.pijava.agent.tool.TruncationUtils.truncateHead(selected);
        // pi's streaming selector records the first selected line's size unconditionally;
        // the read tool only reads it when firstLineExceedsLimit is set.
        int firstLineBytes = all[startLine].getBytes(StandardCharsets.UTF_8).length;
        return new StreamingLineSelector.Selection(truncation, all.length, firstLineBytes);
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
