package com.pijava.ai.protocol;

import java.util.Map;
import java.util.function.Consumer;

import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.stream.StreamPartialBuilder;

/**
 * docs/69：responses 入站 custom_tool_call 的逐槽状态与事件处理
 * （pi {@code openai-responses-shared.ts:410-423/504-527/670-680/726-740}），
 * 从 {@link ResponsesStreamProcessor} 抽出以保持车道文件不超限。
 */
final class ResponsesCustomCalls {

    /** Slot type marker for custom tool calls. */
    static final String CUSTOM = "custom";

    /**
     * Per-output-index state (pi {@code StreamingToolCall} custom 形态)。
     *
     * @param compositeId {@code call_id|item.id}，与 function 槽同形（D3）
     * @param name        工具名
     * @param buffer      输入重组状态机
     */
    static final class CustomCallState {
        final String compositeId;
        final String name;
        final GrammarInputBuffer buffer;

        CustomCallState(String compositeId, String name, String property) {
            this.compositeId = compositeId;
            this.name = name;
            this.buffer = new GrammarInputBuffer(property);
        }
    }

    /** Build state for a custom_tool_call item added event (pi :504-527)。 */
    static CustomCallState state(
            com.openai.models.responses.ResponseCustomToolCall item,
            Map<String, String> grammarProperties) {
        var itemId = item.id().orElse("");
        var compositeId = ResponsesStreamProcessor.compositeId(item.callId(), itemId);
        var property = grammarProperties.get(item.name());
        return new CustomCallState(compositeId, item.name(),
            property != null ? property : "input");
    }

    /** custom_tool_call_input.delta event（pi :670-676）。 */
    static void inputDelta(
            com.openai.models.responses.ResponseCustomToolCallInputDeltaEvent event,
            ResponseEventContext ctx) {
        var state = ctx.customCalls.get(event.outputIndex());
        if (state != null) {
            inputDelta(state, event.delta(), ctx.publisher::submit, ctx.builder);
        }
    }

    /** custom_tool_call_input.done event（pi :677-680）。 */
    static void inputDone(
            com.openai.models.responses.ResponseCustomToolCallInputDoneEvent event,
            ResponseEventContext ctx) {
        var state = ctx.customCalls.get(event.outputIndex());
        if (state != null) {
            inputDone(state, event.input(), ctx.publisher::submit, ctx.builder);
        }
    }

    static void inputDelta(CustomCallState state, String delta,
                            Consumer<StreamEvent> emit, StreamPartialBuilder builder) {
        var nextInput = state.buffer.input() + delta;
        var fragment = state.buffer.append(nextInput, false);
        if (fragment != null) {
            emit.accept(builder.emitToolCallDelta(state.compositeId, fragment));
        }
    }

    static void inputDone(CustomCallState state, String input,
                           Consumer<StreamEvent> emit, StreamPartialBuilder builder) {
        var fragment = state.buffer.append(input, true);
        if (fragment != null) {
            emit.accept(builder.emitToolCallDelta(state.compositeId, fragment));
        }
    }

    /**
     * output_item.done for custom（pi :726-732）：以 item.input（缺省取已累积串）
     * 关闭 —— 若 input.done 已关且同值则幂等无片段；变值则由缓冲抛 after-closed 错。
     */
    static void itemDone(CustomCallState state, String itemInput,
                          Consumer<StreamEvent> emit, StreamPartialBuilder builder) {
        var nextInput = itemInput != null ? itemInput : state.buffer.input();
        var fragment = state.buffer.append(nextInput, true);
        if (fragment != null) {
            emit.accept(builder.emitToolCallDelta(state.compositeId, fragment));
        }
    }

    private ResponsesCustomCalls() {}
}
