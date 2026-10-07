package com.pijava.coding.agent.extension.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;


import org.junit.jupiter.api.Test;

import com.pijava.ai.message.ContentBlock;

import com.pijava.mcp.protocol.content.McpContentBlock;
import com.pijava.mcp.protocol.content.ResourceContents;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内容块转换与结果上限（{@code tools.ts:99-211}）。
 */
class McpResultContentTest {

    private static final McpResultContent.Options PLAIN = McpResultContent.Options.defaults();

    private static String textOf(List<ContentBlock> blocks) {
        return ((ContentBlock.TextContent) blocks.get(0)).text();
    }

    private static McpContentBlock.ResourceLink link(String uri, String name, String title,
                                                     String description, String mimeType, Double size) {
        return new McpContentBlock.ResourceLink(uri, name, title, description, mimeType, size,
                null, null);
    }

    private static McpContentBlock.Embedded embedded(ResourceContents resource) {
        return new McpContentBlock.Embedded(resource, null, null);
    }

    // ------------------------------------------------------------------- textOf

    @Test
    void textOfJoinsTextBlocksAndSkipsImages() {
        var blocks = List.<ContentBlock>of(
                new ContentBlock.TextContent("a"),
                new ContentBlock.ImageContent("image/png", "QUJD"),
                new ContentBlock.TextContent("b"));
        assertThat(McpResultContent.textOf(blocks)).isEqualTo("a\nb");
    }

    // -------------------------------------------------------------------- limit

    @Test
    void contentWithinTheLimitIsPassedThrough() {
        var blocks = List.<ContentBlock>of(new ContentBlock.TextContent("short"));
        var limited = McpResultContent.limit(blocks, PLAIN);
        assertThat(limited.content()).isSameAs(blocks);
        assertThat(limited.fullOutputPath()).isNull();
    }

    @Test
    void longerTextIsCutInTheMiddleAndTheFullTextSaved() {
        var veryLong = "x".repeat(McpResultContent.MCP_OUTPUT_MAX_BYTES + 100);
        var saved = new ArrayList<String>();
        var options = new McpResultContent.Options((data, extension) -> {
            saved.add(extension + ":" + data.length);
            return "C:/tmp/pi-mcp-abc.txt";
        }, false);

        var limited = McpResultContent.limit(
                List.of(new ContentBlock.TextContent(veryLong),
                        new ContentBlock.ImageContent("image/png", "QUJD")), options);

        assertThat(limited.fullOutputPath()).isEqualTo("C:/tmp/pi-mcp-abc.txt");
        assertThat(saved).containsExactly(".txt:" + veryLong.length());
        // One text block first, then the images in their original order.
        assertThat(limited.content()).hasSize(2);
        assertThat(limited.content().get(1)).isInstanceOf(ContentBlock.ImageContent.class);
        assertThat(textOf(limited.content()))
                .startsWith("Warning: truncated output (original token count: ")
                .contains("Total output lines: 1")
                .contains("chars truncated")
                .contains("[Full output: C:/tmp/pi-mcp-abc.txt (read it with offset/limit)]");
    }

    @Test
    void aSaveFailureStillReturnsTheTruncatedText() {
        var options = new McpResultContent.Options((data, extension) -> {
            throw new IOException("disk full");
        }, false);
        var limited = McpResultContent.limit(
                List.of(new ContentBlock.TextContent("x".repeat(McpResultContent.MCP_OUTPUT_MAX_BYTES + 1))),
                options);

        assertThat(limited.fullOutputPath()).isNull();
        assertThat(textOf(limited.content()))
                .contains("[Could not save the full output: disk full]");
    }

    // ---------------------------------------------------------- block conversion

    @Test
    void aResourceLinkNamesItsToolOnlyWhenResourcesAreReadable() {
        var block = link("file:///a.txt", "a", null, null, "text/plain", 2048.0);

        assertThat(textOf(McpResultContent.toModelContent("files", List.of(block), PLAIN)))
                .isEqualTo("[Resource file:///a.txt \"a\" (text/plain, 2.0KB)]");

        var readable = new McpResultContent.Options(null, true);
        assertThat(textOf(McpResultContent.toModelContent("files", List.of(block), readable)))
                .isEqualTo("[Resource file:///a.txt \"a\" (text/plain, 2.0KB)"
                        + ". Read it with read_mcp_resource (server \"files\")]");
    }

    @Test
    void aResourceLinkPrefersItsTitleAndKeepsItsDescription() {
        var block = link("file:///a.txt", "a", "A file", "the notes", null, null);
        assertThat(textOf(McpResultContent.toModelContent("files", List.of(block), PLAIN)))
                .isEqualTo("[Resource file:///a.txt \"A file\": the notes]");
    }

    @Test
    void aTextBlobBecomesText() {
        var block = embedded(new ResourceContents.Blob("file:///a.json",
                "application/json", Base64.getEncoder().encodeToString("{\"a\":1}".getBytes(StandardCharsets.UTF_8)),
                null));
        assertThat(textOf(McpResultContent.toModelContent("files", List.of(block), PLAIN)))
                .isEqualTo("{\"a\":1}");
    }

    @Test
    void aBinaryBlobIsSavedAndNamedWithItsExtension() {
        var saved = new ArrayList<String>();
        var options = new McpResultContent.Options((data, extension) -> {
            saved.add(extension);
            return "C:/tmp/blob" + extension;
        }, false);
        var block = embedded(new ResourceContents.Blob("file:///a.bin",
                "application/octet-stream", Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}), null));

        assertThat(textOf(McpResultContent.toModelContent("files", List.of(block), options)))
                .isEqualTo("[Binary resource file:///a.bin (application/octet-stream, 3B)"
                        + " saved to C:/tmp/blob.bin]");
        assertThat(saved).containsExactly(".bin");
    }

    @Test
    void aBinaryBlobThatCannotBeSavedSaysWhy() {
        var options = new McpResultContent.Options((data, extension) -> {
            throw new IOException("read-only");
        }, false);
        var block = embedded(new ResourceContents.Blob("file:///a.bin", null,
                Base64.getEncoder().encodeToString(new byte[] {1}), null));

        assertThat(textOf(McpResultContent.toModelContent("files", List.of(block), options)))
                .isEqualTo("[Binary resource file:///a.bin (unknown type, 1B)"
                        + " could not be saved: read-only]");
    }

    @Test
    void anImageBlobGoesToTheModelAsAnImage() {
        var block = embedded(new ResourceContents.Blob("file:///a.png", "image/png", "QUJD", null));
        var converted = McpResultContent.toModelContent("files", List.of(block), PLAIN);
        assertThat(converted).hasSize(1);
        assertThat(converted.get(0)).isInstanceOf(ContentBlock.ImageContent.class);
        // pi's block is {data, mimeType}; pi-java's is (mediaType, data).
        assertThat(((ContentBlock.ImageContent) converted.get(0)).mediaType()).isEqualTo("image/png");
        assertThat(((ContentBlock.ImageContent) converted.get(0)).data()).isEqualTo("QUJD");
    }

    // ------------------------------------------------------------------ helpers

    @Test
    void recognizesTextMimeTypes() {
        assertThat(McpResultContent.isTextMimeType(null)).isFalse();
        assertThat(McpResultContent.isTextMimeType("")).isFalse();
        assertThat(McpResultContent.isTextMimeType("text/plain")).isTrue();
        assertThat(McpResultContent.isTextMimeType("TEXT/Plain; charset=utf-8")).isTrue();
        assertThat(McpResultContent.isTextMimeType("application/json")).isTrue();
        assertThat(McpResultContent.isTextMimeType("application/ld+json")).isTrue();
        assertThat(McpResultContent.isTextMimeType("image/svg+xml")).isTrue();
        assertThat(McpResultContent.isTextMimeType("image/png")).isFalse();
    }

    @Test
    void picksTheExtensionTheUriEndsIn() {
        assertThat(McpResultContent.extensionOf("file:///a/b.txt")).isEqualTo(".txt");
        assertThat(McpResultContent.extensionOf("file:///a/b.PNG")).isEqualTo(".PNG");
        assertThat(McpResultContent.extensionOf("not a url/x.json")).isEqualTo(".json");
        assertThat(McpResultContent.extensionOf("file:///a/b")).isEqualTo(".bin");
        assertThat(McpResultContent.extensionOf("file:///a/b.verylongextension")).isEqualTo(".bin");
    }

    @Test
    void aResultWithNoBlocksConvertsToNothing() {
        // 空 content 的回落是 convertMcpResult 的事，这里只钉「不给块就返回空」。
        assertThat(McpResultContent.toModelContent("s", List.of(), PLAIN)).isEmpty();
    }
}
