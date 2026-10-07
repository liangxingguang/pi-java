package com.pijava.coding.agent.extension.mcp;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.pijava.agent.tool.TruncationUtils;
import com.pijava.ai.message.ContentBlock;
import com.pijava.mcp.protocol.content.CallToolResult;
import com.pijava.mcp.protocol.content.LlmContent;
import com.pijava.mcp.protocol.content.McpContentBlock;
import com.pijava.mcp.protocol.content.McpContents;
import com.pijava.mcp.protocol.content.ResourceContents;

/**
 * Model-facing content of an MCP result (pi {@code tools.ts:99-211}).
 *
 * <p>Text over {@link #MCP_OUTPUT_MAX_BYTES} keeps its start and end with the middle cut out,
 * like Codex does, and the full text is saved to a file the model can read. Binary resources
 * other than images are saved to files too, and resource links name the
 * {@link #READ_MCP_RESOURCE_TOOL} tool.</p>
 */
public final class McpResultContent {

    /** Model-facing text of an MCP result beyond this is cut in the middle ({@code tools.ts:52}). */
    public static final int MCP_OUTPUT_MAX_BYTES = 20 * 1024;

    /** Tool that reads the resources named by resource links ({@code tools.ts:56}). */
    public static final String READ_MCP_RESOURCE_TOOL = "read_mcp_resource";

    /** {@code /\.[A-Za-z0-9]{1,8}$/} — the extension a URI ends in ({@code tools.ts:161}). */
    private static final Pattern EXTENSION = Pattern.compile("\\.[A-Za-z0-9]{1,8}$");

    private McpResultContent() {
    }

    /**
     * How a result is converted (pi {@code ConvertMcpResultOptions}, {@code tools.ts:151-156}).
     *
     * @param saveOutput saves truncated text and binary resources; a temp file when {@code null}
     * @param readableResources whether the server's resources can be read with
     *                          {@link #READ_MCP_RESOURCE_TOOL}, which resource links then name
     */
    public record Options(@Nullable McpOutputSaver saveOutput, boolean readableResources) {

        /** The defaults: temp files, and resource links that do not name a tool. */
        public static Options defaults() {
            return new Options(null, false);
        }

        /** The saver to use. */
        McpOutputSaver saver() {
            return saveOutput == null ? McpOutputSaver.tempFiles() : saveOutput;
        }
    }

    /**
     * Model-facing content after the output limit ({@code tools.ts:128-131}).
     *
     * @param content the blocks to show the model
     * @param fullOutputPath the file holding the full text, when it was truncated
     */
    public record Limited(List<ContentBlock> content, @Nullable String fullOutputPath) {
    }

    /**
     * The text blocks of a content list, joined by newlines ({@code tools.ts:99-104}).
     *
     * <p>Images are not part of this: they do not count against the output limit.</p>
     *
     * @param content the blocks
     * @return the joined text
     */
    public static String textOf(List<ContentBlock> content) {
        var out = new StringBuilder();
        for (var block : content) {
            if (block instanceof ContentBlock.TextContent text) {
                if (!out.isEmpty()) {
                    out.append('\n');
                }
                out.append(text.text());
            }
        }
        return out.toString();
    }

    /**
     * Keep model-facing text within {@link #MCP_OUTPUT_MAX_BYTES} ({@code tools.ts:124-149}).
     *
     * <p>Longer text becomes one text block in Codex's truncation format, followed by the path of
     * the file with the full text; images follow it, in their original order.</p>
     *
     * @param content the blocks to limit
     * @param options how to save the full text
     * @return the limited content
     */
    public static Limited limit(List<ContentBlock> content, Options options) {
        var combined = textOf(content);
        var truncation = TruncationUtils.truncateMiddle(combined, MCP_OUTPUT_MAX_BYTES);
        if (!truncation.truncated()) {
            return new Limited(content, null);
        }
        String fullOutputPath = null;
        String where;
        try {
            fullOutputPath = options.saver().saveText(combined, ".txt");
            where = "[Full output: " + fullOutputPath + " (read it with offset/limit)]";
        } catch (IOException | RuntimeException error) {
            where = "[Could not save the full output: " + errorMessage(error) + "]";
        }
        var tokens = (truncation.totalBytes() + 3) / 4;
        var text = "Warning: truncated output (original token count: " + tokens + ")\n"
                + "Total output lines: " + truncation.totalLines() + "\n\n"
                + truncation.content() + "\n\n" + where;
        var limited = new ArrayList<ContentBlock>();
        limited.add(new ContentBlock.TextContent(text));
        for (var block : content) {
            if (block instanceof ContentBlock.ImageContent) {
                limited.add(block);
            }
        }
        return new Limited(List.copyOf(limited), fullOutputPath);
    }

    /**
     * Model-facing content of {@code server}'s content blocks, before the output limit
     * ({@code tools.ts:204-211}).
     *
     * @param server the MCP server the blocks came from
     * @param blocks the server's content blocks
     * @param options how to save what is too big to show
     * @return the blocks to show the model
     */
    public static List<ContentBlock> toModelContent(String server, List<McpContentBlock> blocks,
                                                    Options options) {
        var out = new ArrayList<ContentBlock>();
        for (var block : blocks) {
            out.addAll(blockToContent(server, block, options));
        }
        return List.copyOf(out);
    }

    /** Model-facing content of one block of {@code server}'s result ({@code tools.ts:172-202}). */
    private static List<ContentBlock> blockToContent(String server, McpContentBlock block,
                                                     Options options) {
        if (block instanceof McpContentBlock.ResourceLink link) {
            var details = new ArrayList<String>();
            if (link.mimeType() != null && !link.mimeType().isEmpty()) {
                details.add(link.mimeType());
            }
            if (link.size() != null) {
                details.add(TruncationUtils.formatSize(link.size().longValue()));
            }
            var read = options.readableResources()
                    ? ". Read it with " + READ_MCP_RESOURCE_TOOL + " (server \"" + server + "\")" : "";
            var description = link.description() == null ? "" : ": " + link.description();
            var title = link.title() != null ? link.title() : link.name();
            return List.of(new ContentBlock.TextContent(
                    "[Resource " + link.uri() + " \"" + title + "\""
                            + (details.isEmpty() ? "" : " (" + String.join(", ", details) + ")")
                            + description + read + "]"));
        }
        if (block instanceof McpContentBlock.Embedded embedded
                && embedded.resource() instanceof ResourceContents.Blob blob
                && !(blob.mimeType() != null && blob.mimeType().startsWith("image/"))) {
            var data = Base64.getDecoder().decode(blob.blob());
            if (isTextMimeType(blob.mimeType())) {
                return List.of(new ContentBlock.TextContent(new String(data, StandardCharsets.UTF_8)));
            }
            var kind = (blob.mimeType() == null ? "unknown type" : blob.mimeType())
                    + ", " + TruncationUtils.formatSize(data.length);
            try {
                var path = options.saver().save(data, extensionOf(blob.uri()));
                return List.of(new ContentBlock.TextContent(
                        "[Binary resource " + blob.uri() + " (" + kind + ") saved to " + path + "]"));
            } catch (IOException | RuntimeException error) {
                return List.of(new ContentBlock.TextContent("[Binary resource " + blob.uri() + " ("
                        + kind + ") could not be saved: " + errorMessage(error) + "]"));
            }
        }
        var single = new CallToolResult(List.of(block), null, null, null);
        return McpContents.toLlmContent(single).stream().map(McpResultContent::toBlock).toList();
    }

    /** pi-mcp's content shape to the model message's ({@code content.ts:76}). */
    static ContentBlock toBlock(LlmContent content) {
        return switch (content) {
            case LlmContent.Text text -> new ContentBlock.TextContent(text.text());
            case LlmContent.Image image -> new ContentBlock.ImageContent(image.mimeType(), image.data());
        };
    }

    /** File extension for a saved binary resource: the one its URI ends in, else {@code .bin}. */
    static String extensionOf(String uri) {
        var path = uri;
        try {
            var parsed = URI.create(uri);
            if (parsed.isAbsolute() && parsed.getPath() != null) {
                path = parsed.getPath();
            }
        } catch (IllegalArgumentException expected) {
            // Not a URL; the whole string is the path, as in pi.
        }
        var matcher = EXTENSION.matcher(path);
        return matcher.find() ? matcher.group() : ".bin";
    }

    /** Blobs of these types are shown as text ({@code tools.ts:165-169}). */
    static boolean isTextMimeType(@Nullable String mimeType) {
        if (mimeType == null || mimeType.isEmpty()) {
            return false;
        }
        var type = mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return type.startsWith("text/") || type.equals("application/json")
                || type.endsWith("+json") || type.endsWith("+xml");
    }

    /** A failure's message, or its string form when it has none. */
    static String errorMessage(Throwable error) {
        var message = error.getMessage();
        return message == null ? error.toString() : message;
    }
}
