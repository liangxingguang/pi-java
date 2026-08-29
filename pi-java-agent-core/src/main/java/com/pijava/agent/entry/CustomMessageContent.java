package com.pijava.agent.entry;

import java.util.List;

import com.pijava.ai.message.ContentBlock;

/**
 * Content of a {@link Entry.CustomMessage}: either a plain string or a list
 * of text/image content blocks (pi {@code CustomMessageEntry.content},
 * same shape as a user message). Only the text/image block types that pi
 * allows for custom messages are meaningful here; other block types pass
 * through untouched for LLM compatibility.
 *
 * <p>Serialized as a bare JSON string ({@link Text}) or a bare JSON array
 * ({@link Blocks}) so JSONL output matches pi's session format (see
 * {@link com.pijava.agent.session.SessionJson}).</p>
 */
public sealed interface CustomMessageContent {

    /** Plain-string form; serializes as a bare JSON string. */
    record Text(String text) implements CustomMessageContent {}

    /** Content-block form; serializes as a bare JSON array. */
    record Blocks(List<ContentBlock> blocks) implements CustomMessageContent {
        /** Defensively copies {@code blocks}. */
        public Blocks {
            blocks = List.copyOf(blocks);
        }
    }

    /** Convenience constructor for the string form. */
    static CustomMessageContent of(String text) {
        return new Text(text);
    }

    /** Convenience constructor for the block-list form. */
    static CustomMessageContent of(List<ContentBlock> blocks) {
        return new Blocks(blocks);
    }

    /**
     * LLM content blocks (pi {@code messages.ts} convertToLlm custom case):
     * string becomes a single text block, the block list passes through.
     */
    default List<ContentBlock> toBlocks() {
        return switch (this) {
            case Text t -> List.of(new ContentBlock.TextContent(t.text()));
            case Blocks b -> b.blocks();
        };
    }

    /**
     * Plain text for display (pi's default custom-message rendering): text
     * blocks joined, non-text blocks ignored.
     */
    default String plainText() {
        StringBuilder builder = new StringBuilder();
        for (ContentBlock block : toBlocks()) {
            if (block instanceof ContentBlock.TextContent text) {
                builder.append(text.text());
            }
        }
        return builder.toString();
    }
}
