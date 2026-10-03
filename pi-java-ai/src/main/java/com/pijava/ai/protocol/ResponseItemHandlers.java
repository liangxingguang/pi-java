package com.pijava.ai.protocol;

/**
 * responses 输出项生命周期处理：output_item.added/done 的四种类型分支
 * （pi {@code openai-responses-shared.ts:463-527/700-740}），从
 * {@code ResponsesStreamProcessor} 抽出，共享 {@link ResponseEventContext}。
 */
final class ResponseItemHandlers {

    private ResponseItemHandlers() {}

    /** output_item.added：按 item 类型建槽并发布 start。 */
    static void added(com.openai.models.responses.ResponseOutputItem item, long outputIndex,
                       ResponseEventContext ctx) {
        if (item.reasoning().isPresent()) {
            ctx.slotTypes.put(outputIndex, ResponsesStreamProcessor.THINKING);
            ctx.publisher.submit(ctx.builder.emitThinkingStart());
        } else if (item.message().isPresent()) {
            ctx.slotTypes.put(outputIndex, ResponsesStreamProcessor.TEXT);
            ctx.publisher.submit(ctx.builder.emitTextStart());
        } else if (item.functionCall().isPresent()) {
            ctx.slotTypes.put(outputIndex, ResponsesStreamProcessor.TOOLCALL);
            var fc = item.functionCall().get();
            // D3（docs/62）：id 复合 `call_id|item.id`（pi shared :485-489）。
            var compositeId = ResponsesStreamProcessor.compositeId(
                fc.callId(), fc.id().orElse(""));
            ctx.toolCalls.put(outputIndex,
                new ResponsesStreamProcessor.FunctionCallState(
                    compositeId, fc.name(), fc.arguments()));
            // 包⑥：起点即带身份。
            ctx.publisher.submit(ctx.builder.emitToolCallStart(compositeId, fc.name()));
        } else if (item.customToolCall().isPresent()) {
            // docs/69（pi shared :504-527）。
            ctx.slotTypes.put(outputIndex, ResponsesCustomCalls.CUSTOM);
            var custom = item.customToolCall().get();
            var state = ResponsesCustomCalls.state(
                custom, ctx.grammarProperties);
            ctx.customCalls.put(outputIndex, state);
            ctx.publisher.submit(ctx.builder.emitToolCallStart(
                state.compositeId, state.name));
        }
    }

    /** output_item.done：发布 end 并清理槽位。 */
    static void done(com.openai.models.responses.ResponseOutputItem item, long outputIndex,
                      ResponseEventContext ctx) {
        if (item.reasoning().isPresent()
                && ResponsesStreamProcessor.isSlot(
                    ctx.slotTypes, outputIndex, ResponsesStreamProcessor.THINKING)) {
            ctx.reasoningCapture.capture(ctx.builder, item.reasoning().get());
            ctx.publisher.submit(ctx.builder.emitThinkingEnd());
        } else if (item.message().isPresent()
                && ResponsesStreamProcessor.isSlot(
                    ctx.slotTypes, outputIndex, ResponsesStreamProcessor.TEXT)) {
            ctx.publisher.submit(ctx.builder.emitTextEnd());
        } else if (item.functionCall().isPresent()
                && ResponsesStreamProcessor.isSlot(
                    ctx.slotTypes, outputIndex, ResponsesStreamProcessor.TOOLCALL)) {
            var state = ctx.toolCalls.remove(outputIndex);
            if (state != null) {
                ctx.publisher.submit(ctx.builder.emitToolCallEnd(
                    state.callId, state.name));
            }
        } else if (item.customToolCall().isPresent()
                && ResponsesStreamProcessor.isSlot(
                    ctx.slotTypes, outputIndex, ResponsesCustomCalls.CUSTOM)) {
            var state = ctx.customCalls.remove(outputIndex);
            if (state != null) {
                ResponsesCustomCalls.itemDone(state,
                    item.customToolCall().get().input(),
                    ctx.publisher::submit, ctx.builder);
                ctx.publisher.submit(ctx.builder.emitToolCallEnd(
                    state.compositeId, state.name));
            }
        }
        ctx.slotTypes.remove(outputIndex);
    }
}
