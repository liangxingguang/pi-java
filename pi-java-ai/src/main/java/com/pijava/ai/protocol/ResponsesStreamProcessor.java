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
 *
 * <h3>B20：收尾语义更正 α/β/γ/δ/ε（docs/31 §8.35.14）</h3>
 *
 * <p>本车道曾是 §8.35.2 里**唯一**被判为「已对齐」的一条，逐行复核后查出五处差距。
 * 关键在**终局事件的性质**：pi 的收尾（{@code openai-responses.ts:181-192} + shared
 * {@code :758-760}）是「全部 throw、由外层 catch 落成**唯一**一个 error 事件」，只有
 * 一切正常才 push {@code done}；修复前则是「就地 emitError 后**继续迭代**」+「{@code done}
 * 照发」，于是出错轮次在通道上是「先 error 后 done」两条，且 {@code done("error")} 破了
 * 「done = 成功」这条协议不变量。</p>
 *
 * <ul>
 *   <li><b>α</b> {@code mapStopReason} 的 {@code default} 由 {@code "stop"} 改为 <b>throw</b>
 *       —— 未知 status 此前被当成正常结束。可被线格触发（实测 openai-java 4.42.0 的
 *       {@code ResponseStatus} 是 Enum 模式，未知值照常反序列化、{@code toString()} 给原值）。</li>
 *   <li><b>β</b> {@code incomplete} 且非 {@code max_output_tokens} ⇒ {@code error} +
 *       {@code "Response incomplete: X"}（无 reason 时 {@code "Response incomplete without a
 *       provider reason"}）—— 此前只回裸 {@code "error"}、无文案，转录取不到原因。</li>
 *   <li><b>γ</b> 终局事件类型：{@code done} 只在收尾判定全过之后发，**绝不**发
 *       {@code done("error")}。</li>
 *   <li><b>δ</b> {@code response.failed} 三条退化文案照 pi（见 {@link #failedMessage}）。</li>
 *   <li><b>ε</b> 出错即终止：{@code error}/{@code response.failed} 两支改为 throw，后面的
 *       帧一帧都不再处理。</li>
 * </ul>
 *
 * <p>abort 检查（pi {@code :181-183}）在车道层**结构上不可达** —— {@code StreamRequest} 没有
 * signal，中止由宿主 {@code PiLoopRunner.markAborted} 在流外处理（§8.35.14 第三节 ③）。</p>
 */
final class ResponsesStreamProcessor {

    private static final String TEXT = "text";
    private static final String THINKING = "thinking";
    private static final String TOOLCALL = "toolcall";

    /**
     * pi 累加器的 stop reason 初值（{@code openai-responses.ts:139}），非 pi 词汇表取值；
     * 收尾拿它当「一个 stop reason 都没观测到」的哨兵。
     */
    private static final String PENDING = "pending";

    private ResponsesStreamProcessor() {}

    /** 消费 Responses 流并发布 pi-java 事件。 */
    static void process(StreamResponse<ResponseStreamEvent> stream,
                        SubmissionPublisher<StreamEvent> publisher) {
        var builder = new StreamPartialBuilder();
        var slotTypes = new HashMap<Long, String>();
        var toolCalls = new HashMap<Long, FunctionCallState>();
        var stop = new StopState();
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
                    finalizeResponse(builder, publisher, event.completed().get().response(), stop);
                } else if (event.incomplete().isPresent()) {
                    sawTerminal = true;
                    finalizeResponse(builder, publisher, event.incomplete().get().response(), stop);
                } else if (event.failed().isPresent()) {
                    // pi 的 `response.failed` 分支（shared :745-755）：先记 sawTerminal 再 throw。
                    // throw 终止整条流（ε）—— 不是「发一条 error 继续读」。
                    sawTerminal = true;
                    throw new IllegalStateException(failedMessage(event.failed().get().response()));
                } else if (event.error().isPresent()) {
                    // pi :743-744 同样是 throw。⚠️ pi 的模板串 `${event.code}` / `${event.message}`
                    // 在字段缺席时渲染成 JS 的 `undefined`，本车道保留 pi-java 既有的 `unknown`
                    // 兜底（SDK 的 `message()` 缺席时会抛，见 §8.35.14 实施记录）。
                    var e = event.error().get();
                    throw new IllegalStateException("Error Code "
                        + e.code().orElse("unknown") + ": " + e.message());
                }
            }
            if (!sawTerminal) {
                // pi :758-760（在 shared 的循环**之外**）：整条流没有任何终局事件。
                throw new IllegalStateException(
                    "OpenAI Responses stream ended before a terminal response event");
            }
            // ⚠️ pi 的 pending 检查（openai-responses.ts:185-186）在本车道**结构上不可达**：
            // sawTerminal 只由 finalizeResponse / failed 两支写入，而前者必经 mapStopReason 写下
            // 一个非 pending 的取值、后者直接 throw ⇒ 走到这里时哨兵早已被覆盖。pi 侧同一对
            // 前置条件 ⇒ 这条在 pi 里同样不可达。留着是为了与 pi 的收尾四段同形，**不是**补缺口
            // （与 Google/Mistral 车道「局部量兜底成 stop」那种真缺口不同，见各车道 javadoc）。
            if (PENDING.equals(stop.reason)) {
                throw new IllegalStateException(
                    "OpenAI Responses stream ended without a stop reason");
            }
            // pi 此处还比了 `"aborted"`（:188），那个值只由 abort 检查写入 ⇒ 结构上不可达（见类 javadoc）。
            if ("error".equals(stop.reason)) {
                throw new IllegalStateException(stop.errorMessage != null
                    ? stop.errorMessage : "An unknown error occurred");
            }
            publisher.submit(builder.emitDone(stop.reason));
        } catch (Exception e) {
            // pi 的 catch（openai-responses.ts:194-205）只 push 一条 {type:"error"} 就 stream.end()
            // ⇒ 一条流**只有一个**终局事件；上面的 throw 全落在这里，emitDone 不会被发出去。
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

    /**
     * 终局事件：只**记状态**（usage 照发），不发 {@code done}。
     *
     * <p>对齐 pi {@code finalizeResponse}（shared :551-596）—— 它在终局事件上改写
     * {@code output.stopReason}/{@code errorMessage}，而 {@code done} 由车道收尾
     * （{@code openai-responses.ts:192}）在判定全过之后才 push。修复前本方法直接
     * {@code emitDone(reason)}，于是 {@code reason=="error"} 时把错误当成功发了出去（γ）。</p>
     */
    private static void finalizeResponse(StreamPartialBuilder builder,
                                         SubmissionPublisher<StreamEvent> publisher,
                                         Response response, StopState stop) {
        if (response.usage().isPresent()) {
            var u = response.usage().get();
            publisher.submit(builder.emitUsage(u.inputTokens(), u.outputTokens()));
        }
        var mapped = mapStopReason(response.status().orElse(null), incompleteReason(response));
        stop.reason = mapped.reason();
        stop.errorMessage = mapped.errorMessage();
        if (hasToolUse(builder) && "stop".equals(stop.reason)) {
            // pi :593-595 —— 内容里有工具块时 stop 补成 toolUse
            stop.reason = "tool_use";
        }
    }

    /** pi 的 {@code output.stopReason} + {@code output.errorMessage} 两件套。 */
    private static final class StopState {
        private String reason = PENDING;
        private String errorMessage;
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

    /**
     * 线格 status → pi 的 {@code StopReason} + 文案
     * （pi {@code openai-responses-shared.ts:763-796}）。
     *
     * <p>⚠️ {@code default} 是 **throw**（α），与 Anthropic / Google 同向、与
     * completions / Mistral 的「落 error 事件」反向 —— 四处都照 pi 写，别「统一」。</p>
     */
    private static MappedStopReason mapStopReason(ResponseStatus status, String incompleteReason) {
        if (status == null) {
            return new MappedStopReason("stop", null);
        }
        return switch (status.toString()) {
            case "completed" -> new MappedStopReason("stop", null);
            case "incomplete" -> {
                if ("max_output_tokens".equals(incompleteReason)) {
                    yield new MappedStopReason("length", null);
                }
                yield new MappedStopReason("error",
                    incompleteReason != null && !incompleteReason.isEmpty()
                        ? "Response incomplete: " + incompleteReason
                        : "Response incomplete without a provider reason");
            }
            case "failed", "cancelled" -> new MappedStopReason("error", null);
            // pi 注释标为 "wonky"：照抄行为 —— 这两个中间态被当成正常结束
            case "in_progress", "queued" -> new MappedStopReason("stop", null);
            default -> throw new IllegalStateException("Unhandled stop reason: " + status);
        };
    }

    /** 映射结果：pi 的 {@code { stopReason, errorMessage? }}。 */
    private record MappedStopReason(String reason, String errorMessage) {}

    /**
     * {@code response.failed} 的文案（pi {@code openai-responses-shared.ts:748-754}）：
     * {@code "code: message"} → 退化 {@code "incomplete: X"} → {@code "Unknown error
     * (no error details in response)"}。
     *
     * <p>⚠️ 读的是 {@code _code()}/{@code _message()} 的**存在性**，只有存在且非 null 才调那个
     * 访问器：实测 openai-java 4.42.0 的 {@code ResponseError.code()}/{@code message()} 在字段
     * 缺席或为 null 时抛 {@code OpenAIInvalidDataException}（{@code `message` is not set}），
     * 而 pi 的 {@code ||} 兜底恰恰要求这两个位置可缺 ⇒ 直接读会把 pi 的文案换成一条 SDK 异常
     * 文本（修复前 {"code":"server_error"} 就是这样退化成 {@code "`message` is not set"} 的）。</p>
     */
    private static String failedMessage(Response response) {
        if (response.error().isPresent()) {
            var e = response.error().get();
            String code = "unknown";
            if (!e._code().isMissing() && !e._code().isNull()) {
                code = e.code().toString();
            }
            String message = "no message";
            if (!e._message().isMissing() && !e._message().isNull() && !e.message().isEmpty()) {
                message = e.message();
            }
            return code + ": " + message;
        }
        var reason = response.incompleteDetails().flatMap(d -> d.reason());
        if (reason.isPresent()) {
            return "incomplete: " + reason.get();
        }
        return "Unknown error (no error details in response)";
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
