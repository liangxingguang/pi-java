package com.pijava.tui.component;

import java.util.ArrayList;
import java.util.List;

import com.pijava.ai.message.ContentBlock;
import com.pijava.tui.util.TamboUIAdapter;

import dev.tamboui.toolkit.element.Element;

/**
 * Renders a {@link ChatMessage} into a TamboUI widget tree
 * (Phase 3 design §4.2).
 */
public final class MessageBubble {

    private MessageBubble() {}

    /** Build the widget for a chat message. */
    public static Element of(ChatMessage msg) {
        return switch (msg) {
            case ChatMessage.User(var text) ->
                TamboUIAdapter.panel(TamboUIAdapter.markupText(text))
                    .cyan().rounded().addClass("MessageBubble", "user");
            case ChatMessage.Assistant(var blocks) ->
                TamboUIAdapter.column(renderBlocks(blocks))
                    .addClass("MessageBubble", "assistant");
            case ChatMessage.ToolCall(var name, var arguments) ->
                new ToolCallCard(name, arguments, "running").render();
            case ChatMessage.ToolResult(var output) ->
                TamboUIAdapter.panel(TamboUIAdapter.markupText(truncate(output, 500)))
                    .green().rounded().addClass("MessageBubble", "tool");
            case ChatMessage.Error(var message) ->
                TamboUIAdapter.panel(TamboUIAdapter.markupText(
                    "[red]" + message + "[/]")).red().rounded();
            case ChatMessage.System(var text) ->
                TamboUIAdapter.text(text).dim();
        };
    }

    private static List<Element> renderBlocks(List<ContentBlock> blocks) {
        var elements = new ArrayList<Element>();
        for (var block : blocks) {
            elements.add(switch (block) {
                case ContentBlock.TextContent(var text) ->
                    TamboUIAdapter.markupText(text);
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
