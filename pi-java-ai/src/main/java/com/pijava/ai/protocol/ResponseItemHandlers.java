package com.pijava.ai.protocol;

/**
 * responses 输出项生命周期处理：output_item.added/done 的四种类型分支
 * （pi {@code openai-responses-shared.ts:463-527/700-740}），从
 * {@code ResponsesStreamProcessor} 抽出，共享 {@link ResponseEventContext}。
 */
final class ResponseItemHandlers {

    private ResponseItemHandlers() {}

    /**
     * pi {@code openai-responses-shared.ts:700} 的内容合并：每个 part 非
     * {@code output_text} 就取 {@code refusal}。
     *
     * <p>SDK 里两者是**独立变体**（{@code ResponseOutputText} 没有 {@code refusal()}）⇒
     * pi 的三元式在这里落成两次 {@code Optional} 取值。</p>
     */
    private static String joinedText(
            java.util.List<com.openai.models.responses.ResponseOutputMessage.Content> content) {
        var out = new StringBuilder();
        for (var part : content) {
            out.append(part.outputText()
                .map(com.openai.models.responses.ResponseOutputText::text)
                .orElseGet(() -> part.refusal()
                    .map(com.openai.models.responses.ResponseOutputRefusal::refusal)
                    .orElse("")));
        }
        return out.toString();
    }

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
            var fc = item.functionCall().get();
            // D3（docs/62）：id 复合 `call_id|item.id`（pi shared :485-489）。
            var compositeId = ResponsesStreamProcessor.compositeId(
                fc.callId(), fc.id().orElse(""));
            // docs/32 B152：端点偶发把**同一个 item** 投两次（`call_id`/`item.id`/参数逐字
            // 相同，而 `fc_` 是端点分配的 ⇒ 同一个 item）。pi 照单全收，回放时于是发出两条
            // 同 `call_id` 的 function_call，被中转（TeamoRouter→DeepSeek）以 400 拒
            // （二分实证：只把重复那个的 id 改成唯一即恢复）。判据是复合 id 逐字相等 ——
            // 合法的重复调用会拿到**不同**的 call_id，故不误伤。
            if (alreadySeen(ctx, compositeId)) {
                return;
            }
            ctx.slotTypes.put(outputIndex, ResponsesStreamProcessor.TOOLCALL);
            ctx.toolCalls.put(outputIndex,
                new ResponsesStreamProcessor.FunctionCallState(
                    compositeId, fc.name(), initialArguments(fc)));
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

    /**
     * pi {@code openai-responses-shared.ts:485-490} 的 {@code item.arguments || ""}：
     * {@code response.output_item.added} 里的 function_call **可能没有** {@code arguments} 键
     * —— OpenAI 自己发 {@code ""}，但兼容端点会整个省掉（2026-10-03 实测 TeamoRouter 的
     * {@code /responses} 就是）。
     *
     * <p>⚠️ 这里**必须**走 {@code JsonField} 的容错读法：SDK 的必填访问器
     * {@code ResponseFunctionToolCall.arguments()} 在缺键时抛
     * {@code OpenAIInvalidDataException: `arguments` is not set} ⇒ 整轮 0 token 断流
     * （夹具 {@code ResponsesFunctionCallMissingArgumentsTest} 钉住）。</p>
     */
    private static String initialArguments(
            com.openai.models.responses.ResponseFunctionToolCall fc) {
        var field = fc._arguments();
        return field.isMissing() || field.isNull() ? "" : fc.arguments();
    }

    /**
     * pi {@code openai-responses-shared.ts:710} 的
     * {@code item.arguments || slot.block.partialJson || "{}"}：收尾项里的参数**优先**，
     * 其次才是流式累积的草稿，都没有才给 {@code "{}"}。
     *
     * <p>注意 {@code ||} 的语义：收尾项给的是**空串**时同样落回草稿（与 {@code ??} 不同）。</p>
     */
    private static String authoritativeArguments(
            com.openai.models.responses.ResponseFunctionToolCall fc, String accumulated) {
        var fromItem = initialArguments(fc);
        if (!fromItem.isEmpty()) {
            return fromItem;
        }
        return accumulated == null || accumulated.isEmpty() ? "{}" : accumulated;
    }

    /** 本轮的 function_call 槽里是否已有同一个复合 id（docs/32 B152 的去重判据）。 */
    private static boolean alreadySeen(ResponseEventContext ctx, String compositeId) {
        for (var state : ctx.toolCalls.values()) {
            if (state.callId.equals(compositeId)) {
                return true;
            }
        }
        return false;
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
            var message = item.message().get();
            // docs/71 G3：收尾的 content 是**权威值**（pi :700）—— output_text 取 text、
            // 其余（refusal）取 refusal。流式 delta 只是草稿，可能比它短甚至为空。
            var text = joinedText(message.content());
            // R4 的刻意偏差：pi 无条件覆盖；本仓 content 为空时**不动**已流出的文本，
            // 否则「收尾没带 content 的 provider」会把正文清空（夹具钉两支）。
            if (!text.isEmpty()) {
                ctx.builder.replaceText(text);
            }
            var phase = message.phase().map(value -> value.asString()).orElse(null);
            ctx.builder.retainTextSignature(TextSignatureV1.encode(message.id(), phase));
            // docs/71 G4：phase=final_answer ⇒ 就地落 stop（pi :442-446，按字符串比较）。
            if ("final_answer".equals(phase)) {
                ctx.builder.forceStopReason("stop");
            }
            ctx.publisher.submit(ctx.builder.emitTextEnd());
        } else if (item.functionCall().isPresent()
                && ResponsesStreamProcessor.isSlot(
                    ctx.slotTypes, outputIndex, ResponsesStreamProcessor.TOOLCALL)) {
            var state = ctx.toolCalls.remove(outputIndex);
            if (state != null) {
                // pi :710 的 `item.arguments || partialJson || "{}"`：**收尾项里的参数是权威值**，
                // 流式 delta 只是草稿（可能更短，也可能一个都没有）。
                ctx.builder.replaceToolArguments(authoritativeArguments(
                    item.functionCall().get(), state.args));
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
