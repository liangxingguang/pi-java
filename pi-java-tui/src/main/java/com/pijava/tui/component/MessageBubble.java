package com.pijava.tui.component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.pijava.ai.message.ContentBlock;
import com.pijava.tui.util.TextLayout;

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;

/**
 * Projects a {@link ChatMessage} into width-agnostic {@link LogicalLine}s
 * (Phase 3 alignment design §5.3). Wrapping happens at render time inside the
 * {@link ChatViewportElement}, so message content never caches a stale width.
 */
public final class MessageBubble {

    /** Continuation indent for user/assistant message bodies. */
    private static final int MESSAGE_INDENT = 2;
    /** Indent for tool arguments and tool results (Codex-CLI hierarchy). */
    private static final int TOOL_INDENT = 4;

    private MessageBubble() {}

    /**
     * Builds the logical lines for a chat message.
     *
     * @param msg the chat message
     * @return logical lines (empty for an empty message)
     */
    public static List<LogicalLine> lines(ChatMessage msg) {
        return switch (msg) {
            case ChatMessage.User(var text) -> prefix(
                TextLayout.split(TextLayout.escapeMarkup(text), false),
                "› ", MESSAGE_INDENT);
            case ChatMessage.Assistant(var blocks) -> renderBlocks(blocks);
            case ChatMessage.ToolCall(var name, var arguments) -> toolCallCard(name, arguments);
            case ChatMessage.ToolResult(var output, var isError) ->
                List.of(new LogicalLine(
                    (isError ? "! " : "") + truncate(TextLayout.escapeMarkup(output), 500),
                    TOOL_INDENT, TOOL_INDENT, true,
                    isError ? Style.EMPTY.red() : Style.EMPTY.dim()));
            case ChatMessage.Error(var message) ->
                TextLayout.split("[red]" + TextLayout.escapeMarkup(message) + "[/]", false);
            case ChatMessage.System(var text, var kind) -> system(text, kind);
            case ChatMessage.TurnSeparator(var label) ->
                List.of(new LogicalLine(separator(label), 0, 0, false, Style.EMPTY.dim()));
        };
    }

    /** Applies the first-line prefix and the continuation indent to a block. */
    private static List<LogicalLine> prefix(
            List<LogicalLine> lines, String prefix, int indent) {
        var out = new ArrayList<LogicalLine>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            var line = lines.get(i);
            out.add(i == 0
                ? new LogicalLine(prefix + line.markup(), 0, indent,
                    line.preformatted(), line.style())
                : new LogicalLine(line.markup(), indent, indent,
                    line.preformatted(), line.style()));
        }
        return out;
    }

    /** A dim rule spanning the separator label, or a plain rule without one. */
    private static String separator(String label) {
        String safe = label == null ? "" : TextLayout.escapeMarkup(label);
        return safe.isBlank()
            ? "─".repeat(40)
            : "─".repeat(18) + " " + safe + " " + "─".repeat(18);
    }

    private static List<LogicalLine> renderBlocks(List<ContentBlock> blocks) {
        var lines = new ArrayList<LogicalLine>();
        boolean prefixed = false;
        for (var block : blocks) {
            var blockLines = switch (block) {
                case ContentBlock.TextContent(var text) -> {
                    var split = TextLayout.split(TextLayout.escapeMarkup(text), false);
                    if (!prefixed) {
                        // The bullet belongs to the assistant prose, not to a
                        // leading tool card (which has its own status prefix).
                        split = prefix(split, "• ", MESSAGE_INDENT);
                        prefixed = true;
                    }
                    yield split;
                }
                case ContentBlock.ThinkingContent(var text) -> dim(
                    TextLayout.split(TextLayout.escapeMarkup(text), false));
                case ContentBlock.ToolUseContent(var id, var name, var arguments) ->
                    new ToolCallCard(toolName(name), toolArgs(arguments), "running").lines();
                case ContentBlock.ToolResultContent(
                        var toolUseId, var toolName, var content, var isError) ->
                    new ToolCallCard(
                        toolName(toolName),
                        truncate(TextLayout.escapeMarkup(joinText(content)), 500),
                        isError ? "error" : "done").lines();
                case ContentBlock.ImageContent(var mediaType, var data) ->
                    List.of(new LogicalLine(
                        "[image: " + mediaType + "]", 0, 0, false, Style.EMPTY.dim()));
                case ContentBlock.UrlImageContent(var url) ->
                    List.of(new LogicalLine(
                        "[image: " + url + "]", 0, 0, false, Style.EMPTY.dim()));
            };
            lines.addAll(blockLines);
        }
        return lines;
    }

    private static List<LogicalLine> toolCallCard(String name, String arguments) {
        return new ToolCallCard(toolName(name),
            TextLayout.escapeMarkup(arguments), "running").lines();
    }
    /** Tool card label: fall back to "tool" when the stream omits the name. */
    private static String toolName(String name) {
        return name == null || name.isBlank() ? "tool" : name;
    }

    /** Tool arguments for display: unwrap {"_raw": "..."} back to the raw text. */
    private static String toolArgs(Map<String, Object> arguments) {
        if (arguments.size() == 1 && arguments.get("_raw") instanceof String raw) {
            return raw;
        }
        return String.valueOf(arguments);
    }

    private static List<LogicalLine> dim(List<LogicalLine> lines) {
        var out = new ArrayList<LogicalLine>(lines.size());
        for (var line : lines) {
            out.add(new LogicalLine(line.markup(), line.initialIndent(),
                line.subsequentIndent(), line.preformatted(),
                Style.EMPTY.dim().patch(line.style())));
        }
        return out;
    }

    /**
     * Renders a metadata/system bubble (P6-25): the kind's icon prefixes the
     * first line, and the whole block takes the kind's color — except GENERIC,
     * which stays the legacy dim line with no icon.
     */
    private static List<LogicalLine> system(String text, MetaKind kind) {
        String escaped = TextLayout.escapeMarkup(text);
        String label = kind.icon() == null ? escaped : kind.icon() + " " + escaped;
        var lines = TextLayout.split(label, false);
        return kind == MetaKind.GENERIC ? dim(lines) : colorize(lines, kind.color());
    }

    /** Tints every line with a foreground color, preserving line styles. */
    private static List<LogicalLine> colorize(List<LogicalLine> lines, Color color) {
        var out = new ArrayList<LogicalLine>(lines.size());
        for (var line : lines) {
            out.add(new LogicalLine(line.markup(), line.initialIndent(),
                line.subsequentIndent(), line.preformatted(),
                Style.EMPTY.fg(color).patch(line.style())));
        }
        return out;
    }

    private static String joinText(List<ContentBlock> blocks) {
        var builder = new StringBuilder();
        for (var block : blocks) {
            if (block instanceof ContentBlock.TextContent text) {
                builder.append(text.text());
            }
        }
        return builder.toString();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
