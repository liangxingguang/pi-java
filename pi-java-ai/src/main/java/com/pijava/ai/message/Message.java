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
     * A tool execution result message (pi {@code ToolResultMessage}，
     * {@code packages/ai/src/types.ts:452-468}；由 {@code createToolResultMessage}
     * 从结果对象派生，{@code agent-loop.ts:784-797})。
     *
     * <p>{@code details} / {@code usage} 是结果树上同名对象的<b>原样转发</b>
     * （pi 的 {@code finalized.result.details}/{@code .usage}）—— pi 的
     * {@code usage} 是 ai 共享的 {@code Usage} 类型，而 pi-java 的工具 usage 长在
     * {@code ToolResult.UsageInfo}（agent 模块），ai 不能反向依赖 agent，故两者
     * 都取 {@code Object}（{@code null} ≙ pi 的 undefined，线上/库里键缺席）。
     * {@code addedToolNames} 对齐 pi 的「仅有内容才带上」：这里统一存非 null 列表，
     * 空表在序列化时省略。</p>
     *
     * <p><b>provider 投影不读这三项</b> —— pi 的各协议适配器只从 {@code content}
     * 构造工具结果块（{@code details} 是给 UI/日志的结构化载荷，不进模型上下文）。</p>
     *
     * <p>{@code role()} 的 {@code "tool"} 是 pi-java 持久化方言（JSONL/SQLite 里
     * 写作 {@code "tool"}/{@code toolUseId}）；对外的 WS wire 由
     * {@code WebWireJson} 转回 pi 的 {@code "toolResult"}/{@code toolCallId} 拼写。
     * 别把 {@code role()} 改成 {@code "toolResult"} —— 那会连带改破既有会话文件。</p>
     */
    record ToolResultMessage(String toolUseId, String toolName,
                             List<ContentBlock> content,
                             Object details, Object usage, List<String> addedToolNames,
                             boolean isError) implements Message {
        /** Compact constructor: defensive copies; empty {@code addedToolNames} ≙ pi 的省略。 */
        public ToolResultMessage {
            content = List.copyOf(content);
            addedToolNames = addedToolNames == null ? List.of() : List.copyOf(addedToolNames);
        }

        /**
         * Compatibility constructor for messages without a structured payload
         * (pi 的对象字面量里这些字段本就可选)。生产路径一律走全参构造，从结果对象
         * 转发 —— 见 {@code PiToolRunner.toOutcome}。
         */
        public ToolResultMessage(String toolUseId, String toolName,
                                 List<ContentBlock> content, boolean isError) {
            this(toolUseId, toolName, content, null, null, List.of(), isError);
        }

        @Override
        public String role() {
            return "tool";
        }
    }
}
