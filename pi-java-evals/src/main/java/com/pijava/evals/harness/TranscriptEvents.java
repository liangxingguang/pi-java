package com.pijava.evals.harness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

/**
 * 批 32（docs/44 §3.3）：{@link Message} → {@link TranscriptEvent} 映射 —— pi
 * {@code toTranscriptEvents}（{@code harness.ts:190-223}）对位。
 *
 * <p>工具调用状态由同 id 的 {@code tool_result} 反推：有结果且
 * {@code isError=false} ⇒ {@code ok}；有结果且 error ⇒ {@code error}；
 * 无结果 ⇒ {@code pending}。</p>
 */
public final class TranscriptEvents {

    private TranscriptEvents() {
    }

    /**
     * Convert messages into transcript events.
     *
     * @param messages transcript messages
     * @return events in transcript order
     */
    public static List<TranscriptEvent> from(List<Message> messages) {
        var events = new ArrayList<TranscriptEvent>();
        for (var message : messages) {
            switch (message) {
                case Message.UserMessage user -> events.add(
                    TranscriptEvent.message("user", text(user.content())));
                case Message.AssistantMessage assistant -> {
                    var text = text(assistant.content());
                    if (!text.isEmpty()) {
                        events.add(TranscriptEvent.message("assistant", text));
                    }
                    for (var block : assistant.content()) {
                        if (block instanceof ContentBlock.ToolUseContent call) {
                            events.add(TranscriptEvent.toolCall(
                                call.id(), call.name(), call.arguments()));
                        }
                    }
                }
                case Message.ToolResultMessage result -> events.add(
                    TranscriptEvent.toolResult(result.toolUseId(), result.toolName(),
                        text(result.content()), result.isError()));
                default -> {
                    // system messages are not part of pi's transcript events.
                }
            }
        }
        return events;
    }

    /**
     * Derive tool calls with status from messages.
     *
     * @param messages transcript messages
     * @return tool calls in assistant order
     */
    public static List<ToolCallRecord> toolCalls(List<Message> messages) {
        var results = new LinkedHashMap<String, Boolean>();
        for (var message : messages) {
            if (message instanceof Message.ToolResultMessage result) {
                results.put(result.toolUseId(), result.isError());
            }
        }
        var calls = new ArrayList<ToolCallRecord>();
        for (var message : messages) {
            if (!(message instanceof Message.AssistantMessage assistant)) {
                continue;
            }
            for (var block : assistant.content()) {
                if (!(block instanceof ContentBlock.ToolUseContent call)) {
                    continue;
                }
                var error = results.get(call.id());
                var status = error == null ? "pending" : error ? "error" : "ok";
                calls.add(new ToolCallRecord(call.id(), call.name(), call.arguments(),
                    status));
            }
        }
        return calls;
    }

    private static String text(List<ContentBlock> content) {
        var parts = new ArrayList<String>();
        for (var block : content) {
            if (block instanceof ContentBlock.TextContent text) {
                parts.add(text.text() == null ? "" : text.text());
            }
        }
        return String.join("\n", parts);
    }
}
