package com.pijava.ai.stream;

import java.util.Map;

/**
 * A streaming event emitted during an LLM response.
 *
 * <p>Consumers pattern-match on the permitted subtypes to handle
 * each kind of event. This is the core streaming contract used by
 * {@link com.pijava.ai.api.StreamApi}.</p>
 */
public sealed interface StreamEvent {

    /** A chunk of text in the assistant's response. */
    record TextDelta(String text, String type) implements StreamEvent {
        /** Plain conversational text. */
        public static final String TEXT = "text";
        /** Extended thinking / reasoning content. */
        public static final String THINKING = "thinking";

        public TextDelta {
            if (type == null || type.isBlank()) {
                type = TEXT;
            }
        }

        /** Convenience constructor for plain text (backward-compatible). */
        public static TextDelta of(String text) {
            return new TextDelta(text, TEXT);
        }
    }

    /** The assistant is about to call a tool. */
    record ToolCallStart(String id, String name) implements StreamEvent {}

    /** A chunk of JSON arguments for an in-progress tool call. */
    record ToolCallDelta(String id, String jsonDelta) implements StreamEvent {}

    /** A tool call is complete (all arguments received). */
    record ToolCallEnd(String id, String name, Map<String, Object> arguments) implements StreamEvent {
        public ToolCallEnd {
            arguments = Map.copyOf(arguments);
        }
    }

    /** Token usage statistics for the current request. */
    record UsageInfo(long inputTokens, long outputTokens) implements StreamEvent {}

    /** The stream finished normally. */
    record StreamDone(String stopReason, UsageInfo usage) implements StreamEvent {}

    /** An error occurred during streaming. */
    record StreamError(Throwable error) implements StreamEvent {}
}
