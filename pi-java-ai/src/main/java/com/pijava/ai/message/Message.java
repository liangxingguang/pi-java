package com.pijava.ai.message;

import java.util.List;

/**
 * A message in a conversation with an LLM.
 *
 * <p><b>恰好三个变体</b>，与 pi 的 {@code Message} 联合类型一致
 * （{@code packages/ai/src/types.ts:470}：{@code UserMessage | AssistantMessage |
 * ToolResultMessage}）。<b>没有 system 角色</b> —— 系统提示不属于消息列表，
 * 它是 {@code Context.systemPrompt}，由各 provider 适配层映射到自己的 system 字段。</p>
 */
public sealed interface Message {

    /** The role of the message author. */
    String role();

    /** The content blocks that make up this message. */
    List<ContentBlock> content();

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

    /**
     * A message from the assistant (LLM).
     *
     * <p>{@code stopReason} is the reason the turn ended ({@code stop} /
     * {@code tool_use} / {@code length} / {@code error} / {@code aborted} /
     * {@code deferred}), or {@code null} for messages that never came from a
     * completed stream. It is the single source of truth for the reason
     * (docs/22 D1) — readers must not keep a parallel copy.
     * {@code deferred} is the provider handle carried only when
     * {@code stopReason} is {@code "deferred"}.</p>
     */
    record AssistantMessage(
        List<ContentBlock> content,
        String stopReason,
        DeferredHandle deferred
    ) implements Message {
        /** Compact constructor that defensively copies the content blocks. */
        public AssistantMessage {
            content = List.copyOf(content);
        }

        /**
         * Compatibility constructor: every pre-existing call site, and data
         * written before stop reasons were recorded, carries neither field.
         */
        public AssistantMessage(List<ContentBlock> content) {
            this(content, null, null);
        }

        /**
         * The same message with a different {@code stopReason}.
         *
         * <p>Used by the loop to record a turn that was cut short: an abort the
         * provider did not report itself still has to land as {@code aborted},
         * or the interrupted response would be projected into later requests as
         * an ordinary answer.</p>
         */
        public AssistantMessage withStopReason(String newStopReason) {
            return new AssistantMessage(content, newStopReason, deferred);
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
