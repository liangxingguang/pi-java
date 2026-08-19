package com.pijava.ai.protocol;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.SubmissionPublisher;

import com.openai.core.http.StreamResponse;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseStatus;
import com.openai.models.responses.ResponseStreamEvent;

import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.stream.StreamPartialBuilder;

/**
 * OpenAI Responses 流事件 → pi-java {@link StreamEvent} 映射。
 *
 * <p>对齐 pi {@code openai-responses-shared.ts} 的 {@code processResponsesStream}。
 * 按 {@code output_index} 追踪当前槽位类型（text / thinking / toolcall），把
 * Responses SSE 事件映射为 {@link StreamPartialBuilder} 事件序列。供
 * {@code OpenAIResponsesApi} 与 {@code AzureOpenAIResponsesApi} 共享。</p>
 */
final class ResponsesStreamProcessor {

    private static final String TEXT = "text";
    private static final String THINKING = "thinking";
    private static final String TOOLCALL = "toolcall";

    private ResponsesStreamProcessor() {}

    /** 消费 Responses 流并发布 pi-java 事件。 */
    static void process(StreamResponse<ResponseStreamEvent> stream,
                        SubmissionPublisher<StreamEvent> publisher) {
        var builder = new StreamPartialBuilder();
        var slotTypes = new HashMap<Long, String>();
        var toolCalls = new HashMap<Long, FunctionCallState>();
        boolean sawTerminal = false;
        try {
            publisher.submit(builder.emitStart());
            for (var event : stream.stream().toList()) {
                if (event.outputItemAdded().isPresent()) {
                    handleOutputItemAdded(event.outputItemAdded().get().item(),
                        event.outputItemAdded().get().outputIndex(),
                        builder, publisher, slotTypes, toolCalls);
                } else if (event.reasoningSummaryTextDelta().isPresent()) {
                    var d = event.reasoningSummaryTextDelta().get();
                    if (isSlot(slotTypes, d.outputIndex(), THINKING)) {
                        publisher.submit(builder.emitThinkingDelta(d.delta()));
                    }
                } else if (event.reasoningTextDelta().isPresent()) {
                    var d = event.reasoningTextDelta().get();
                    if (isSlot(slotTypes, d.outputIndex(), THINKING)) {
                        publisher.submit(builder.emitThinkingDelta(d.delta()));
                    }
                } else if (event.reasoningSummaryPartDone().isPresent()) {
                    var d = event.reasoningSummaryPartDone().get();
                    if (isSlot(slotTypes, d.outputIndex(), THINKING)) {
                        publisher.submit(builder.emitThinkingDelta("\n\n"));
                    }
                } else if (event.outputTextDelta().isPresent()) {
                    var d = event.outputTextDelta().get();
                    if (isSlot(slotTypes, d.outputIndex(), TEXT)) {
                        publisher.submit(builder.emitTextDelta(d.delta()));
                    }
                } else if (event.refusalDelta().isPresent()) {
                    var d = event.refusalDelta().get();
                    if (isSlot(slotTypes, d.outputIndex(), TEXT)) {
                        publisher.submit(builder.emitTextDelta(d.delta()));
                    }
                } else if (event.functionCallArgumentsDelta().isPresent()) {
                    var d = event.functionCallArgumentsDelta().get();
                    var state = toolCalls.get(d.outputIndex());
                    if (state != null) {
                        state.args += d.delta();
                        publisher.submit(builder.emitToolCallDelta(state.callId, d.delta()));
                    }
                } else if (event.functionCallArgumentsDone().isPresent()) {
                    var d = event.functionCallArgumentsDone().get();
                    var state = toolCalls.get(d.outputIndex());
                    if (state != null) {
                        // 补齐尾部 delta，使 builder 缓冲与权威 arguments 一致
                        if (d.arguments().startsWith(state.args)) {
                            String tail = d.arguments().substring(state.args.length());
                            if (!tail.isEmpty()) {
                                state.args = d.arguments();
                                publisher.submit(builder.emitToolCallDelta(state.callId, tail));
                            }
                        }
                    }
                } else if (event.outputItemDone().isPresent()) {
                    handleOutputItemDone(event.outputItemDone().get().item(),
                        event.outputItemDone().get().outputIndex(),
                        builder, publisher, slotTypes, toolCalls);
                } else if (event.completed().isPresent()) {
                    sawTerminal = true;
                    finalizeResponse(builder, publisher, event.completed().get().response());
                } else if (event.incomplete().isPresent()) {
                    sawTerminal = true;
                    finalizeResponse(builder, publisher, event.incomplete().get().response());
                } else if (event.failed().isPresent()) {
                    sawTerminal = true;
                    var response = event.failed().get().response();
                    publisher.submit(builder.emitError("error",
                        new RuntimeException(errorMessage(response))));
                } else if (event.error().isPresent()) {
                    var e = event.error().get();
                    publisher.submit(builder.emitError("error", new RuntimeException(
                        "Error Code " + e.code().orElse("unknown") + ": " + e.message())));
                }
            }
            if (!sawTerminal) {
                publisher.submit(builder.emitError("error", new RuntimeException(
                    "OpenAI Responses stream ended before a terminal response event")));
            }
        } catch (Exception e) {
            publisher.submit(builder.emitError("error", e));
        }
    }

    // ── Item 生命周期 ──────────────────────────────────────────────────

    private static void handleOutputItemAdded(
            ResponseOutputItem item, long outputIndex,
            StreamPartialBuilder builder, SubmissionPublisher<StreamEvent> publisher,
            Map<Long, String> slotTypes, Map<Long, FunctionCallState> toolCalls) {
        if (item.reasoning().isPresent()) {
            slotTypes.put(outputIndex, THINKING);
            publisher.submit(builder.emitThinkingStart());
        } else if (item.message().isPresent()) {
            slotTypes.put(outputIndex, TEXT);
            publisher.submit(builder.emitTextStart());
        } else if (item.functionCall().isPresent()) {
            slotTypes.put(outputIndex, TOOLCALL);
            var fc = item.functionCall().get();
            toolCalls.put(outputIndex,
                new FunctionCallState(fc.callId(), fc.name(), fc.arguments()));
            publisher.submit(builder.emitToolCallStart());
        }
    }

    private static void handleOutputItemDone(
            ResponseOutputItem item, long outputIndex,
            StreamPartialBuilder builder, SubmissionPublisher<StreamEvent> publisher,
            Map<Long, String> slotTypes, Map<Long, FunctionCallState> toolCalls) {
        if (item.reasoning().isPresent() && isSlot(slotTypes, outputIndex, THINKING)) {
            publisher.submit(builder.emitThinkingEnd());
        } else if (item.message().isPresent() && isSlot(slotTypes, outputIndex, TEXT)) {
            publisher.submit(builder.emitTextEnd());
        } else if (item.functionCall().isPresent() && isSlot(slotTypes, outputIndex, TOOLCALL)) {
            var state = toolCalls.remove(outputIndex);
            if (state != null) {
                publisher.submit(builder.emitToolCallEnd(state.callId, state.name));
            }
        }
        slotTypes.remove(outputIndex);
    }

    // ── 终止事件 ────────────────────────────────────────────────────────

    private static void finalizeResponse(StreamPartialBuilder builder,
                                         SubmissionPublisher<StreamEvent> publisher,
                                         Response response) {
        if (response.usage().isPresent()) {
            var u = response.usage().get();
            publisher.submit(builder.emitUsage(u.inputTokens(), u.outputTokens()));
        }
        String reason = mapStopReason(response.status().orElse(null),
            incompleteReason(response));
        if (hasToolUse(builder) && "stop".equals(reason)) {
            reason = "tool_use";
        }
        publisher.submit(builder.emitDone(reason));
    }

    private static String incompleteReason(Response response) {
        return response.incompleteDetails()
            .flatMap(d -> d.reason())
            .map(r -> r.toString())
            .orElse(null);
    }

    private static boolean hasToolUse(StreamPartialBuilder builder) {
        return builder.snapshot().content().stream()
            .anyMatch(b -> b instanceof ContentBlock.ToolUseContent);
    }

    /** 对齐 pi mapStopReason。 */
    static String mapStopReason(ResponseStatus status, String incompleteReason) {
        if (status == null) {
            return "stop";
        }
        return switch (status.toString()) {
            case "completed" -> "stop";
            case "incomplete" ->
                "max_output_tokens".equals(incompleteReason) ? "length" : "error";
            case "failed", "cancelled" -> "error";
            // pi 注释标为 "wonky"：照抄行为
            case "in_progress", "queued" -> "stop";
            default -> "stop";
        };
    }

    private static String errorMessage(Response response) {
        if (response.error().isPresent()) {
            var e = response.error().get();
            return e.code().toString() + ": " + e.message();
        }
        return "Response failed without error details";
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static boolean isSlot(Map<Long, String> slots, long index, String type) {
        return type.equals(slots.get(index));
    }

    /** function_call 增量状态 —— arguments 累计缓冲。 */
    private static final class FunctionCallState {
        final String callId;
        final String name;
        String args;

        FunctionCallState(String callId, String name, String args) {
            this.callId = callId;
            this.name = name;
            this.args = args == null ? "" : args;
        }
    }
}
