package com.pijava.agent.tool.builtin;
import com.pijava.ai.AbortSignal;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ImagePolicy;
import com.pijava.agent.tool.PathUtils;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.agent.tool.TruncationUtils;
import com.pijava.agent.tool.image.ImageProcessor;
import com.pijava.ai.api.JsonSchemaSampling;
import com.pijava.ai.api.StrictMode;
import com.pijava.ai.message.ContentBlock;

/**
 * File reading tool. Supports text files and images (jpg/png/gif/webp/bmp).
 * Aligned with pi's {@code createReadTool}.
 *
 * <p>Schema: { path: String, offset?: Number, limit?: Number }
 * Text output truncated to 2000 lines or 100KB.
 * Images returned as base64 ContentBlock.ImageContent.</p>
 */
public final class ReadTool {
    private ReadTool() {}

    public record ReadInput(String path, Optional<Integer> offset, Optional<Integer> limit) {}
    public record ReadDetails(TruncationUtils.TruncationResult truncation) {}

    /** Create the read tool. */
    public static AgentTool<ReadInput, ReadDetails> create() {
        return new AgentTool<>() {
            @Override public String name() { return "read"; }
            @Override public String label() { return "read"; }
            @Override public String description() {
                return "Read the contents of a file. Supports text files and images (jpg, png, gif, webp, bmp). "
                    + "Images are sent as attachments. "
                    + "For text files, output is truncated to " + TruncationUtils.DEFAULT_MAX_LINES
                    + " lines or " + (TruncationUtils.DEFAULT_MAX_BYTES / 1024)
                    + "KB (whichever is hit first). Use offset/limit for large files.";
            }
            @Override
            public Map<String, Object> inputSchema() {
                return Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "path", Map.of("type", "string",
                            "description", "Path to the file to read (relative or absolute)"),
                        "offset", Map.of("type", "number",
                            "description", "Line number to start reading from (1-indexed)"),
                        "limit", Map.of("type", "number",
                            "description", "Maximum number of lines to read")
                    ),
                    "required", List.of("path")
                );
            }
            @Override public ExecutionMode executionMode() { return new ExecutionMode.Parallel(); }

            // 原 docs/66：pi 的 read 默认 strict-prefer（CHANGELOG）。
            @Override public JsonSchemaSampling constrainedSampling() {
                return new JsonSchemaSampling(StrictMode.PREFER);
            }

            @Override
            public ReadInput prepareArguments(Map<String, Object> raw) {
                String path = (String) raw.get("path");
                Optional<Integer> offset = Optional.empty();
                Optional<Integer> limit = Optional.empty();
                Object offsetObj = raw.get("offset");
                if (offsetObj instanceof Number n) offset = Optional.of(n.intValue());
                Object limitObj = raw.get("limit");
                if (limitObj instanceof Number n) limit = Optional.of(n.intValue());
                return new ReadInput(path, offset, limit);
            }

            @Override
            public ToolResult<ReadDetails> execute(String toolCallId, ReadInput params,
                    AbortSignal signal, ToolUpdateCallback<ReadDetails> onUpdate,
                    ToolContext context) throws Exception {
                String absolutePath = PathUtils.resolveToolPath(context, params.path());
                byte[] bytes = context.fs().readBinary(absolutePath);

                // Check if it's an image
                var mimeType = PathUtils.detectImageMimeType(bytes);
                if (mimeType.isPresent()) {
                    return readImage(bytes, mimeType.get(), context);
                }

                // Text file
                int offset = params.offset().orElse(1);
                int startLine = Math.max(0, offset - 1);
                String text = new String(bytes, StandardCharsets.UTF_8);
                var allLines = List.of(text.split("\n", -1));

                if (startLine >= allLines.size()) {
                    throw new IllegalArgumentException("Offset " + offset + " is beyond end of file ("
                        + allLines.size() + " lines total)");
                }

                int endLine = params.limit().isPresent()
                    ? Math.min(startLine + params.limit().get(), allLines.size())
                    : allLines.size();
                String selectedContent = String.join("\n", allLines.subList(startLine, endLine));

                var truncation = TruncationUtils.truncateHead(selectedContent);
                String outputText;
                ReadDetails details = null;

                if (truncation.firstLineExceedsLimit()) {
                    outputText = "[Line " + (startLine + 1) + " exceeds "
                        + TruncationUtils.formatSize(TruncationUtils.DEFAULT_MAX_BYTES)
                        + " limit. Use bash: sed -n '" + (startLine + 1) + "p' "
                        + params.path() + " | head -c " + TruncationUtils.DEFAULT_MAX_BYTES + "]";
                    details = new ReadDetails(truncation);
                } else if (truncation.truncated()) {
                    int startDisplay = startLine + 1;
                    int endDisplay = startDisplay + truncation.outputLines() - 1;
                    int nextOffset = endDisplay + 1;
                    outputText = truncation.content()
                        + "\n\n[Showing lines " + startDisplay + "-" + endDisplay
                        + " of " + allLines.size()
                        + " (" + TruncationUtils.formatSize(TruncationUtils.DEFAULT_MAX_BYTES)
                        + " limit). Use offset=" + nextOffset + " to continue.]";
                    details = new ReadDetails(truncation);
                } else if (endLine < allLines.size()) {
                    int remaining = allLines.size() - endLine;
                    outputText = truncation.content()
                        + "\n\n[" + remaining + " more lines in file. Use offset="
                        + (endLine + 1) + " to continue.]";
                } else {
                    outputText = truncation.content();
                }

                return new ToolResult<>(
                    List.of(new ContentBlock.TextContent(outputText)),
                    details, null, false, List.of());
            }
        };
    }

    /**
     * 图片读取（pi {@code tools/read.ts:128-153}，批 9/B85）：归一/转换/缩放后以
     * {@code [text, image]} 两块返回；处理失败只回一块省略文本。非视觉模型在文本尾部
     * 追加注记（图片块仍带上，真正省略由 ai 层 transform 降级闸做）。
     */
    private static ToolResult<ReadDetails> readImage(byte[] bytes, String mimeType,
                                                      ToolContext context) {
        ImagePolicy policy = context.imagePolicy();
        String note = policy.supportsImages() ? null
            : "[Current model does not support images. The image will be omitted from this request.]";

        var processed = ImageProcessor.process(bytes, mimeType, policy);
        if (processed instanceof ImageProcessor.Processed.Ok ok) {
            String text = "Read image file [" + ok.mimeType() + "]";
            if (!ok.hints().isEmpty()) {
                text += "\n" + String.join("\n", ok.hints());
            }
            if (note != null) {
                text += "\n" + note;
            }
            return new ToolResult<>(
                List.of(
                    new ContentBlock.TextContent(text),
                    new ContentBlock.ImageContent(ok.mimeType(), ok.data())),
                null, null, false, List.of());
        }
        var failure = (ImageProcessor.Processed.Failure) processed;
        String text = "Read image file [" + mimeType + "]\n" + failure.message();
        if (note != null) {
            text += "\n" + note;
        }
        return new ToolResult<>(
            List.of(new ContentBlock.TextContent(text)),
            null, null, false, List.of());
    }
}
