package com.pijava.tui.util;

import java.util.ArrayList;
import java.util.List;

import com.pijava.ai.message.ContentBlock;
import com.pijava.tui.component.ChatMessage;
import com.pijava.tui.component.LogicalLine;
import com.pijava.tui.component.MetaKind;
import com.pijava.tui.component.MessageBubble;
import com.pijava.tui.component.RenderRow;
import com.pijava.tui.util.TextLayout;

import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.MarkupParser;
import dev.tamboui.text.Text;

/**
 * Prints the chat transcript into the terminal's native scrollback (Codex
 * raw-output mode). Committed messages append as styled blocks above the
 * inline bottom region; the streaming draft is not printed into the
 * scrollback (in-place rewrites corrupt Windows ConPTY output), so the
 * response lands once, at TextEnd.
 *
 * <p>Blocks are wrapped at the terminal width before printing, so the
 * terminal never wraps a printed line on its own.</p>
 */
public final class ScrollbackTranscript {

    /** Output target for one styled terminal line. */
    public interface Sink {

        /** Appends one styled line to the scrollback. */
        void println(Line line);

        /**
         * Rewrites the last {@code lineCount} printed lines in place (the
         * streaming draft). Must return {@code false} when rewriting is
         * unsafe (the block scrolled off the top), in which case the caller
         * stops updating and lets the final message append normally.
         */
        boolean replaceLastBlock(int lineCount, List<Line> block);
    }

    private final Sink sink;
    private int printedMessages;
    private boolean draftVisible = true;

    /**
     * Creates the scrollback printer writing through the given sink.
     *
     * @param sink the styled-line output target
     */
    public ScrollbackTranscript(Sink sink) {
        this.sink = sink;
    }

    /** Committed messages printed so far (test hook). */
    public int printedMessages() {
        return printedMessages;
    }

    /** Whether the streaming draft is still being rewritten in place. */
    public boolean draftVisible() {
        return draftVisible;
    }

    /**
     * Brings the scrollback in line with the current model. Call on the
     * render thread once per frame, before redrawing the bottom region.
     */
    public void sync(List<ChatMessage> messages, ChatMessage draft, int width) {
        int size = messages.size();
        while (printedMessages < size) {
            ChatMessage message = messages.get(printedMessages);
            printBlock(message, width);
            printedMessages++;
        }

        if (draft == null) {
            draftVisible = true;
            return;
        }
        // Raw-scrollback mode deliberately does NOT stream the draft into the
        // scrollback: in-place rewrites (cursor-up + erase + rewrite above the
        // bottom region) race with insert-line handling on Windows ConPTY and
        // scramble the response text. The committed assistant message prints
        // normally at TextEnd instead.
        draftVisible = true;
    }

    private void printBlock(ChatMessage message, int width) {
        for (Line line : blockLines(message, width)) {
            sink.println(line);
        }
    }

    /** Wraps a message at the terminal width and styles each row. */
    private static List<Line> blockLines(ChatMessage message, int width) {
        List<LogicalLine> logical = MessageBubble.lines(message);
        List<RenderRow> rows = TextLayout.wrap(logical, Math.max(1, width));
        var out = new ArrayList<Line>(rows.size());
        for (RenderRow row : rows) {
            Text parsed = MarkupParser.parse(row.text());
            Line line = parsed.lines().isEmpty() ? Line.empty() : parsed.lines().get(0);
            out.add(row.style().equals(Style.EMPTY) ? line : line.patchStyle(row.style()));
        }
        return out;
    }


    private static String joinText(ChatMessage message) {
        return switch (message) {
            case ChatMessage.User(String text) -> text;
            case ChatMessage.Assistant(var blocks) -> joinText(blocks);
            case ChatMessage.ToolCall(String name, String arguments) -> name + " " + arguments;
            case ChatMessage.ToolResult(String output, boolean isError) -> output;
            case ChatMessage.Error(String text) -> text;
            case ChatMessage.System(String text, MetaKind kind) -> text;
            case ChatMessage.TurnSeparator(String label) -> label;
        };
    }

    private static String joinText(List<ContentBlock> blocks) {
        var builder = new StringBuilder();
        for (var block : blocks) {
            if (block instanceof ContentBlock.TextContent(String text)) {
                builder.append(text);
            }
        }
        return builder.toString();
    }
}
