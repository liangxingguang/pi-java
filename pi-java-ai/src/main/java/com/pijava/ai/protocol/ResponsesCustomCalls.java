package com.pijava.ai.protocol;

import java.util.Map;
import java.util.function.Consumer;

import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.stream.StreamPartialBuilder;
import com.pijava.ai.stream.ToolCallSlotKey;

/** Responses custom-tool grammar state, isolated by output index. */
final class ResponsesCustomCalls {

    static final String CUSTOM = "custom";

    /** Per-output-index custom call, including the not-yet-published initial input fragment. */
    static final class CustomCallState {
        final String compositeId;
        final String name;
        final String property;
        final GrammarInputBuffer buffer;
        private final String initialInput;
        private String unsent;

        CustomCallState(String compositeId, String name, String property, String initialInput) {
            this.compositeId = compositeId;
            this.name = name;
            this.property = property;
            this.initialInput = initialInput;
            this.buffer = new GrammarInputBuffer(property);
            this.unsent = buffer.appendDelta(initialInput);
            if (unsent == null) {
                unsent = "";
            }
        }

        Map<String, Object> initialArguments() {
            return Map.of(property, initialInput);
        }
    }

    /** Build state for a custom_tool_call item added event. */
    static CustomCallState state(
            com.openai.models.responses.ResponseCustomToolCall item,
            Map<String, String> grammarProperties) {
        var itemId = item.id().orElse("");
        var compositeId = ResponsesStreamProcessor.compositeId(item.callId(), itemId);
        var property = grammarProperties.get(item.name());
        return new CustomCallState(compositeId, item.name(),
            property != null ? property : "input", item.input());
    }

    static void inputDelta(
            com.openai.models.responses.ResponseCustomToolCallInputDeltaEvent event,
            ResponseEventContext ctx) {
        var state = ctx.customCalls.get(event.outputIndex());
        if (state != null) {
            inputDelta(event.outputIndex(), state, event.delta(), ctx.publisher::submit, ctx.builder);
        }
    }

    static void inputDone(
            com.openai.models.responses.ResponseCustomToolCallInputDoneEvent event,
            ResponseEventContext ctx) {
        var state = ctx.customCalls.get(event.outputIndex());
        if (state != null) {
            inputDone(event.outputIndex(), state, event.input(), ctx.publisher::submit, ctx.builder);
        }
    }

    static void inputDelta(long outputIndex, CustomCallState state, String delta,
                            Consumer<StreamEvent> emit, StreamPartialBuilder builder) {
        send(outputIndex, state, state.buffer.appendDelta(delta), emit, builder);
    }

    static void inputDone(long outputIndex, CustomCallState state, String input,
                           Consumer<StreamEvent> emit, StreamPartialBuilder builder) {
        send(outputIndex, state, state.buffer.close(input), emit, builder);
    }

    /** Close from output_item.done; repeated identical close remains delta-free. */
    static void itemDone(long outputIndex, CustomCallState state, String itemInput,
                          Consumer<StreamEvent> emit, StreamPartialBuilder builder) {
        send(outputIndex, state, state.buffer.close(itemInput), emit, builder);
    }

    private static void send(long outputIndex, CustomCallState state, String fragment,
                             Consumer<StreamEvent> emit, StreamPartialBuilder builder) {
        if (state.unsent.isEmpty() && fragment == null) {
            return;
        }
        String delta = state.unsent + (fragment == null ? "" : fragment);
        state.unsent = "";
        emit.accept(builder.emitToolCallDelta(ToolCallSlotKey.indexed(outputIndex),
            state.compositeId, delta));
    }

    private ResponsesCustomCalls() {}
}
