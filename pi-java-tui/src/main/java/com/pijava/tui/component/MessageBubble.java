package com.pijava.tui.component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import com.pijava.ai.message.ContentBlock;
import com.pijava.tui.util.TamboUIAdapter;

import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.element.Size;
import dev.tamboui.toolkit.element.StyledElement;

/**
 * Renders a {@link ChatMessage} into a TamboUI widget tree
 * (Phase 3 design §4.2).
 *
 * <p>{@link ChatPanel} is a {@code ListElement}, which sizes every item from
 * its preferred height. Long content is therefore pre-wrapped to the chat
 * content width here so the measured height always matches the rendered
 * height — otherwise auto-wrapped rows would be clipped inside a list cell
 * (TamboUI chat-pane rule: pre-wrap long lines at insert time).</p>
 *
 * <p>The actual content width is only known once the list lays out for a
 * frame, so {@link #of(ChatMessage)} returns a width-aware wrapper that
 * pre-wraps to the item's real width at render time (reserving the column
 * taken by the always-visible scrollbar).</p>
 */
public final class MessageBubble {

    private MessageBubble() {}

    /**
     * Build the widget for a chat message. Long text is pre-wrapped to the
     * actual content width of the list cell when the frame renders.
     */
    public static Element of(ChatMessage msg) {
        return new WidthWrappedElement(width -> build(msg, width));
    }

    private static Element build(ChatMessage msg, int contentWidth) {
        return switch (msg) {
            case ChatMessage.User(var text) ->
                // Codex-CLI style: plain text, no bubble/background.
                TamboUIAdapter.markupText(wrap(text, contentWidth));
            case ChatMessage.Assistant(var blocks) ->
                TamboUIAdapter.column(renderBlocks(blocks, contentWidth))
                    .addClass("MessageBubble", "assistant");
            case ChatMessage.ToolCall(var name, var arguments) ->
                new ToolCallCard(name, arguments, "running").render();
            case ChatMessage.ToolResult(var output) ->
                TamboUIAdapter.markupText(wrap(truncate(output, 500), contentWidth));
            case ChatMessage.Error(var message) ->
                TamboUIAdapter.markupText(wrap("[red]" + message + "[/]", contentWidth));
            case ChatMessage.System(var text) ->
                // markupText keeps multi-line output (slash command help,
                // changelogs, hotkeys) as separate lines; plain text() renders
                // a single line and flattens every newline.
                TamboUIAdapter.markupText(wrap(text, contentWidth)).dim();
        };
    }

    private static List<Element> renderBlocks(
            List<ContentBlock> blocks, int contentWidth) {
        var elements = new ArrayList<Element>();
        for (var block : blocks) {
            elements.add(switch (block) {
                case ContentBlock.TextContent(var text) ->
                    TamboUIAdapter.markupText(wrap(text, contentWidth));
                case ContentBlock.ToolUseContent(var id, var name, var arguments) ->
                    new ToolCallCard(name, String.valueOf(arguments), "running").render();
                case ContentBlock.ToolResultContent(
                        var toolUseId, var toolName, var content, var isError) ->
                    new ToolCallCard(
                        toolName,
                        truncate(joinText(content), 500),
                        isError ? "error" : "done").render();
                case ContentBlock.ImageContent(var mediaType, var data) ->
                    TamboUIAdapter.text("[image: " + mediaType + "]").dim();
            });
        }
        return elements;
    }

    /**
     * Pre-wraps text to the exact width of the list cell, so the measured
     * height (explicit newline count) matches the rendered height.
     */
    private static final class WidthWrappedElement
            extends StyledElement<WidthWrappedElement> {

        private final Function<Integer, Element> factory;
        private Element cached;
        private int cachedWidth = -1;

        WidthWrappedElement(Function<Integer, Element> factory) {
            this.factory = factory;
        }

        private Element child(int width) {
            if (cached == null || cachedWidth != width) {
                cached = factory.apply(width);
                cachedWidth = width;
            }
            return cached;
        }

        @Override
        public Size preferredSize(int availableWidth, int availableHeight,
                                  RenderContext context) {
            // The always-visible scrollbar reserves the last column.
            int contentWidth = Math.max(1, availableWidth - 1);
            return child(contentWidth).preferredSize(contentWidth, availableHeight, context);
        }

        @Override
        protected void renderContent(Frame frame, Rect area,
                                     RenderContext context) {
            child(Math.max(1, area.width())).render(frame, area, context);
        }
    }

    /**
     * Greedy word-wrap with a hard-break fallback, keeping multi-line input
     * intact. Lines longer than {@code width} are broken at word boundaries
     * when possible, otherwise split by display width (wide chars count 2).
     */
    static String wrap(String text, int width) {
        if (text == null || text.isEmpty() || width <= 0) {
            return text;
        }
        var out = new StringBuilder();
        var lines = text.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            wrapLine(lines[i], width, out);
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    private static void wrapLine(String line, int width, StringBuilder out) {
        var words = line.split(" ");
        var current = new StringBuilder();
        int currentWidth = 0;
        for (var word : words) {
            int wordWidth = displayWidth(word);
            if (currentWidth + wordWidth + (current.isEmpty() ? 0 : 1) > width) {
                if (current.isEmpty()) {
                    // Word alone is wider than the line: hard-break it.
                    out.append(hardBreak(word, width));
                    continue;
                }
                out.append(current).append('\n');
                current.setLength(0);
                currentWidth = 0;
            }
            if (!current.isEmpty()) {
                current.append(' ');
                currentWidth += 1;
            }
            current.append(word);
            currentWidth += wordWidth;
        }
        out.append(current);
    }

    private static String hardBreak(String word, int width) {
        var out = new StringBuilder();
        var chunk = new StringBuilder();
        int chunkWidth = 0;
        for (int i = 0; i < word.length(); ) {
            int cp = word.codePointAt(i);
            int w = displayWidth(Character.toString(cp));
            if (chunkWidth + w > width && chunkWidth > 0) {
                out.append(chunk).append('\n');
                chunk.setLength(0);
                chunkWidth = 0;
            }
            chunk.appendCodePoint(cp);
            chunkWidth += w;
            i += Character.charCount(cp);
        }
        out.append(chunk);
        return out.toString();
    }

    private static int displayWidth(String s) {
        int width = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            width += isWide(cp) ? 2 : 1;
            i += Character.charCount(cp);
        }
        return width;
    }

    private static boolean isWide(int cp) {
        return cp >= 0x1100 && (cp <= 0x115F
                || cp == 0x2329 || cp == 0x232A
                || (cp >= 0x2E80 && cp <= 0xA4CF && cp != 0x303F)
                || (cp >= 0xAC00 && cp <= 0xD7A3)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0xFE10 && cp <= 0xFE19)
                || (cp >= 0xFE30 && cp <= 0xFE6F)
                || (cp >= 0xFF00 && cp <= 0xFF60)
                || (cp >= 0xFFE0 && cp <= 0xFFE6)
                || (cp >= 0x1F300 && cp <= 0x1F64F)
                || (cp >= 0x1F900 && cp <= 0x1F9FF)
                || (cp >= 0x20000 && cp <= 0x2FFFD)
                || (cp >= 0x30000 && cp <= 0x3FFFD));
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
