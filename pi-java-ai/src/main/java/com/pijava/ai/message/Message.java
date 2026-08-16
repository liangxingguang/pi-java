package com.pijava.ai.message;

import java.util.List;

/**
 * A message in a conversation with an LLM.
 *
 * <p>This sealed interface has four permitted subtypes: the three standard
 * roles ({@link SystemMessage}, {@link UserMessage}, {@link AssistantMessage})
 * plus a {@link ToolResultMessage}.</p>
 */
public sealed interface Message {

    /** The role of the message author. */
    String role();

    /** The content blocks that make up this message. */
    List<ContentBlock> content();

    /** A system-level instruction message. */
    record SystemMessage(List<ContentBlock> content) implements Message {
        /** Compact constructor that defensively copies the content blocks. */
        public SystemMessage {
            content = List.copyOf(content);
        }

        @Override
        public String role() {
            return "system";
        }
    }

    /** A message from the end user. */
    record UserMessage(List<ContentBlock> content) implements Message {
        /** Compact constructor that defensively copies the content blocks. */
        public UserMessage {
            content = List.copyOf(content);
        }

        @Override
        public String role() {
            return "user";
        }
    }

    /** A message from the assistant (LLM). */
    record AssistantMessage(List<ContentBlock> content) implements Message {
        /** Compact constructor that defensively copies the content blocks. */
        public AssistantMessage {
            content = List.copyOf(content);
        }

        @Override
        public String role() {
            return "assistant";
        }
    }

    /**
     * A tool execution result message.
     * Carries the tool call ID, tool name, result content, and error flag
     * so that protocol adapters can format tool-result blocks correctly
     * for each LLM provider.
     */
    record ToolResultMessage(String toolUseId, String toolName,
                             List<ContentBlock> content, boolean isError) implements Message {
        /** Compact constructor that defensively copies the content blocks. */
        public ToolResultMessage {
            content = List.copyOf(content);
        }

        @Override
        public String role() {
            return "tool";
        }
    }
}
