package com.pijava.agent.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.pijava.ai.AbortSignal;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingConfig;
import com.pijava.ai.thinking.ThinkingLevelMap;

/**
 * pi 双循环的 1:1 移植（{@code packages/agent/src/agent-loop.ts} @ pi {@code v0.85.1}，803 行）。
 *
 * <p><b>为什么有这个类</b>：{@code docs/28} 判定 pi-java 自建的显式状态机
 * （{@code Action} / {@code RunPhase} / {@code peekAction} / {@code executeAction} /
 * record-log fold）在 pi 的产品层没有对应物，且它的三个真源产出了三个真实缺陷。
 * 本类按「等价性由结构保证」的原则一对一译出 pi 的循环，用 Java 的普通同步调用
 * 替代 {@code await}，用 {@link Sink} 替代 pi 的 {@code emit}。</p>
 *
 * <p><b>刻意不做的事</b>：不引入 {@code Action}、不引入 {@code RunPhase}、不写任何记录日志、
 * 不持有持久化状态。状态只有三样，与 pi 一致：消息列表、配置、{@code pendingMessages}。</p>
 *
 * <p><b>工具执行是端口</b>：pi 的 {@code executeToolCalls} 在 pi-java 里已有等价实现
 * （{@code ToolExecutionPipeline}），因此这里只定义 {@link ToolRunner} 端口，由调用方注入；
 * 发射时序（start 先于校验、结果消息源序补发、{@code terminate} 取全部）在
 * {@link PiLoopTools}。</p>
 */
public final class PiLoop {

    // ═══════════════════════════════════════════════════════════════
    // 事件：pi 的 AgentEvent（types.ts:431-446），恰好 10 个变体
    // ═══════════════════════════════════════════════════════════════

    /** pi {@code AgentEvent}。标签名与载荷逐字对齐，不增不减。 */
    public sealed interface Event {

        /** pi {@code { type: "agent_start" }} */
        record AgentStart() implements Event {}

        /** pi {@code { type: "agent_end"; messages }} */
        record AgentEnd(List<Message> messages) implements Event {}

        /** pi {@code { type: "turn_start" }} */
        record TurnStart() implements Event {}

        /** pi {@code { type: "turn_end"; message; toolResults }} */
        record TurnEnd(Message.AssistantMessage message,
                       List<Message.ToolResultMessage> toolResults) implements Event {}

        /** pi {@code { type: "message_start"; message }} */
        record MessageStart(Message message) implements Event {}

        /** pi {@code { type: "message_update"; message; assistantMessageEvent }} */
        record MessageUpdate(Message message, StreamEvent assistantMessageEvent) implements Event {}

        /** pi {@code { type: "message_end"; message }} */
        record MessageEnd(Message message) implements Event {}

        /** pi {@code { type: "tool_execution_start"; toolCallId; toolName; args }} */
        record ToolExecutionStart(String toolCallId, String toolName,
                                  Map<String, Object> args) implements Event {}

        /** pi {@code { type: "tool_execution_update"; ...; partialResult }} */
        record ToolExecutionUpdate(String toolCallId, String toolName,
                                   Map<String, Object> args, Object partialResult) implements Event {}

        /** pi {@code { type: "tool_execution_end"; ...; result; isError }} */
        record ToolExecutionEnd(String toolCallId, String toolName,
                                Object result, boolean isError) implements Event {}
    }

    /** pi 的 {@code AgentEventSink}。 */
    @FunctionalInterface
    public interface Sink {
        /** 同步接收一个事件（pi 的 {@code emit} 是 async，pi-java 侧全部同步）。 */
        void emit(Event event);
    }

    // ═══════════════════════════════════════════════════════════════
    // 端口：工具执行
    // ═══════════════════════════════════════════════════════════════

    /** 一次工具调用的执行请求。{@code truncatedByLength} 对应 pi 的 length 截断分支。 */
    public record ToolCall(String toolCallId, String toolName,
                           Map<String, Object> args, boolean truncatedByLength) {}

    /**
     * 一次工具调用的最终结果。
     *
     * @param message   pi 的 {@code ToolResultMessage}（会进上下文与 {@code newMessages}）
     * @param result    pi 的 {@code tool_execution_end.result}（wire 载荷，可为任意形状）
     * @param isError   是否错误结果
     * @param terminate pi 的 {@code terminate}：为真时本批次结束驱动
     */
    public record ToolOutcome(Message.ToolResultMessage message, Object result,
                              boolean isError, boolean terminate) {}

    /** 工具执行端口。pi-java 侧由 {@code ToolExecutionPipeline} 提供实现。 */
    @FunctionalInterface
    public interface ToolRunner {
        /**
         * 执行一次工具调用（pi 的 {@code prepareToolCall} + {@code executePreparedToolCall}
         * + {@code finalizeExecutedToolCall}）。被拒绝与未找到的调用**也要**返回结果而非抛异常
         * —— pi 对它们同样发出 start 与 end。
         */
        ToolOutcome run(ToolCall call);
    }

    // ═══════════════════════════════════════════════════════════════
    // 钩子（pi 的 AgentLoopConfig 可选回调）
    // ═══════════════════════════════════════════════════════════════

    /** pi {@code prepareNextTurn} 的入参。 */
    public record NextTurnContext(Message.AssistantMessage message,
                                  List<Message.ToolResultMessage> toolResults,
                                  List<Message> messages) {}

    /** pi {@code prepareNextTurn} 的返回值：只允许改 model 与 thinking。 */
    public record NextTurnUpdate(ModelId<?> model, ModelThinkingLevel thinking) {}

    /** pi {@code prepareNextTurn}。返回 {@code null} 表示不改。 */
    @FunctionalInterface
    public interface NextTurnHook {
        /** 在 {@code turn_end} 之后、{@code shouldStopAfterTurn} 之前调用；返回 {@code null} 表示不改。 */
        NextTurnUpdate apply(NextTurnContext context);
    }

    /** pi {@code shouldStopAfterTurn}。 */
    @FunctionalInterface
    public interface StopHook {
        /** 为真则发 {@code agent_end} 并结束整个驱动（不再检查 follow-up）。 */
        boolean apply(NextTurnContext context);
    }

    /** pi {@code transformContext}。返回 {@code null} 表示不改。 */
    @FunctionalInterface
    public interface ContextTransform {
        /** 在转成 provider 消息之前改写上下文；返回 {@code null} 表示不改。 */
        List<Message> apply(List<Message> messages);
    }

    // ═══════════════════════════════════════════════════════════════
    // 配置
    // ═══════════════════════════════════════════════════════════════

    /**
     * pi 的 {@code AgentLoopConfig} 在 pi-java 侧的对应物。所有可选字段用
     * {@code null} 表示「未配置」，与 pi 的 {@code ?.} 可选调用一致。
     *
     * <p>{@code streamListener} 是 pi-java 特有的**原始帧旁路**：pi 把用量等信息放在消息
     * 本身的 partial 里，pi-java 的 {@link StreamEvent.UsageInfo} 却是一个独立帧，且不属于
     * 生命周期事件（{@link PiLoop#isUpdateEvent} 不含它）。若不旁路，token 记账与停因推导会
     * 静默丢失 —— 该帧同时喂给 harness 的既有广播链（{@code AgentHarness.onStreamEvent}），
     * 使会话层无需为切换驱动改造记账代码。</p>
     */
    public record Config(
            ModelId<?> model,
            ModelThinkingLevel thinking,
            ThinkingLevelMap thinkingLevelMap,
            List<ToolDefinition> toolDefs,
            ToolExecution toolExecution,
            ToolRunner toolRunner,
            StreamFn streamFn,
            AbortSignal signal,
            Supplier<List<Message>> steeringMessages,
            Supplier<List<Message>> followUpMessages,
            ContextTransform transformContext,
            NextTurnHook prepareNextTurn,
            StopHook shouldStopAfterTurn,
            Consumer<StreamEvent> streamListener) {}

    private PiLoop() {}

    // ═══════════════════════════════════════════════════════════════
    // 入口：pi runAgentLoop / runAgentLoopContinue（agent-loop.ts:96-141）
    // ═══════════════════════════════════════════════════════════════

    /**
     * pi {@code runAgentLoop}：带一个新 prompt 启动循环。
     *
     * <p>prompt 会先进入上下文并逐条发 {@code message_start}/{@code message_end}。</p>
     *
     * @param prompts 新增的 prompt 消息
     * @param context 起始上下文（**会被就地扩展**，与 pi 一致）
     * @return 本次循环新增的消息（pi 的 {@code newMessages}）
     */
    public static List<Message> run(List<Message> prompts, List<Message> context,
                                    Config config, Sink emit) {
        var newMessages = new ArrayList<>(prompts);
        var currentMessages = new ArrayList<>(context);
        currentMessages.addAll(prompts);

        emit.emit(new Event.AgentStart());
        emit.emit(new Event.TurnStart());
        for (var prompt : prompts) {
            emit.emit(new Event.MessageStart(prompt));
            emit.emit(new Event.MessageEnd(prompt));
        }

        runLoop(currentMessages, newMessages, config, emit, true);
        context.clear();
        context.addAll(currentMessages);
        return newMessages;
    }

    /**
     * pi {@code runAgentLoopContinue}：不新增消息，从当前上下文续跑（重试用）。
     *
     * <p>前置条件与 pi 相同：上下文非空，且末条不是 assistant
     * —— 否则 provider 会拒绝请求（{@code agent-loop.ts:71-77}）。</p>
     */
    public static List<Message> continueRun(List<Message> context, Config config, Sink emit) {
        if (context.isEmpty()) {
            throw new IllegalStateException("Cannot continue: no messages in context");
        }
        if (context.get(context.size() - 1).role().equals("assistant")) {
            throw new IllegalStateException("Cannot continue from message role: assistant");
        }

        var newMessages = new ArrayList<Message>();
        emit.emit(new Event.AgentStart());
        emit.emit(new Event.TurnStart());

        runLoop(context, newMessages, config, emit, true);
        return newMessages;
    }

    // ═══════════════════════════════════════════════════════════════
    // 主循环：pi runLoop（agent-loop.ts:165-279）
    // ═══════════════════════════════════════════════════════════════

    /**
     * pi 的双循环。
     *
     * <p>外层 `while (true)`：follow-up 队列决定是否再来一轮。
     * 内层 `while (hasMoreToolCalls || pendingMessages.length > 0)`：处理 steer 与工具调用。</p>
     *
     * @param firstTurn 外层首轮不重复发 {@code turn_start}（调用方已发过一次）
     */
    private static void runLoop(List<Message> messages, List<Message> newMessages,
                                Config initialConfig, Sink emit, boolean firstTurn) {
        var config = initialConfig;
        var first = firstTurn;
        // pi: 起始即检查 steer（用户可能在等待期间已经输入）
        var pending = config.steeringMessages() == null
            ? new ArrayList<Message>() : new ArrayList<>(config.steeringMessages().get());

        while (true) {
            boolean hasMoreToolCalls = true;

            while (hasMoreToolCalls || !pending.isEmpty()) {
                if (!first) {
                    emit.emit(new Event.TurnStart());
                } else {
                    first = false;
                }

                // pi: 下一轮助手响应之前先注入 pending 消息
                if (!pending.isEmpty()) {
                    for (var message : pending) {
                        emit.emit(new Event.MessageStart(message));
                        emit.emit(new Event.MessageEnd(message));
                        messages.add(message);
                        newMessages.add(message);
                    }
                    pending.clear();
                }

                var message = streamAssistantResponse(messages, config, emit);
                newMessages.add(message);

                // pi: error / aborted 直接收口（agent-loop.ts:189-193）
                if ("error".equals(message.stopReason()) || "aborted".equals(message.stopReason())) {
                    emit.emit(new Event.TurnEnd(message, List.of()));
                    emit.emit(new Event.AgentEnd(List.copyOf(newMessages)));
                    return;
                }

                var toolCalls = PiLoopTools.callsOf(message);
                var toolResults = new ArrayList<Message.ToolResultMessage>();
                hasMoreToolCalls = false;
                if (!toolCalls.isEmpty()) {
                    // pi: length 截断 ⇒ 全部失败，不执行（:206-208 分派，:379-404 实现）
                    var batch = PiLoopTools.run(toolCalls, config, emit,
                        "length".equals(message.stopReason()));
                    toolResults.addAll(batch.messages());
                    hasMoreToolCalls = !batch.terminate();
                    for (var result : toolResults) {
                        messages.add(result);
                        newMessages.add(result);
                    }
                }

                emit.emit(new Event.TurnEnd(message, List.copyOf(toolResults)));

                var nextTurn = new NextTurnContext(message, List.copyOf(toolResults),
                    List.copyOf(messages));

                // pi: prepareNextTurn 在 turn_end 之后、shouldStopAfterTurn 之前
                if (config.prepareNextTurn() != null) {
                    var update = config.prepareNextTurn().apply(nextTurn);
                    if (update != null) {
                        config = new Config(
                            update.model() != null ? update.model() : config.model(),
                            update.thinking() != null ? update.thinking() : config.thinking(),
                            config.thinkingLevelMap(),
                            config.toolDefs(), config.toolExecution(), config.toolRunner(),
                            config.streamFn(), config.signal(), config.steeringMessages(),
                            config.followUpMessages(), config.transformContext(),
                            config.prepareNextTurn(), config.shouldStopAfterTurn(),
                            config.streamListener());
                    }
                }

                if (config.shouldStopAfterTurn() != null
                        && config.shouldStopAfterTurn().apply(nextTurn)) {
                    emit.emit(new Event.AgentEnd(List.copyOf(newMessages)));
                    return;
                }

                pending = config.steeringMessages() == null
                    ? new ArrayList<>() : new ArrayList<>(config.steeringMessages().get());
            }

            // pi: 内层结束 ⇒ 检查 follow-up，有则并入 pending 继续外层
            var followUp = config.followUpMessages() == null
                ? List.<Message>of() : config.followUpMessages().get();
            if (!followUp.isEmpty()) {
                pending = new ArrayList<>(followUp);
                continue;
            }
            break;
        }

        emit.emit(new Event.AgentEnd(List.copyOf(newMessages)));
    }

    // ═══════════════════════════════════════════════════════════════
    // 助手流：pi streamAssistantResponse（agent-loop.ts:283-375）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 发一次 provider 请求并把流式事件翻译成消息生命周期事件。
     *
     * <p>与 pi 的三条关键规则一致：① {@code message_start} 由流的 {@code start} 事件触发；
     * ② 流中每帧用 partial 覆盖上下文的最后一条；③ 若 provider 从未发 {@code start}，
     * 则在 {@code message_end} 之前补发一次 {@code message_start}。</p>
     */
    private static Message.AssistantMessage streamAssistantResponse(
            List<Message> messages, Config config, Sink emit) {

        var llmMessages = config.transformContext() != null
            ? config.transformContext().apply(List.copyOf(messages)) : List.copyOf(messages);

        var options = new StreamOptions(
            java.util.OptionalInt.empty(), java.util.OptionalDouble.empty(),
            thinkingConfig(config), config.toolDefs());

        var iter = config.streamFn().stream(llmMessages, config.model(), options);
        Message.AssistantMessage finalMessage = null;
        boolean addedPartial = false;
        try {
            while (iter.hasNext()) {
                var event = iter.next();
                if (config.streamListener() != null) {
                    config.streamListener().accept(event);
                }
                if (event instanceof StreamEvent.Start start) {
                    addedPartial = true;
                    finalMessage = fromPartial(start.partial());
                    messages.add(finalMessage);
                    emit.emit(new Event.MessageStart(finalMessage));
                } else if (isUpdateEvent(event)) {
                    if (finalMessage != null) {
                        finalMessage = fromPartial(event.partial());
                        messages.set(messages.size() - 1, finalMessage);
                        emit.emit(new Event.MessageUpdate(finalMessage, event));
                    }
                } else if (event instanceof StreamEvent.StreamDone done) {
                    finalMessage = fromPartial(done.partial());
                    break;
                } else if (event instanceof StreamEvent.StreamError err) {
                    finalMessage = fromPartial(err.partial());
                    break;
                }
            }
        } finally {
            iter.close();
        }

        if (finalMessage == null) {
            throw new IllegalStateException("stream produced no terminal message");
        }
        if (addedPartial) {
            messages.set(messages.size() - 1, finalMessage);
        } else {
            messages.add(finalMessage);
            emit.emit(new Event.MessageStart(finalMessage));
        }
        emit.emit(new Event.MessageEnd(finalMessage));
        return finalMessage;
    }

    /** pi: 除 start/done/error 之外的流事件都是 update。 */
    private static boolean isUpdateEvent(StreamEvent event) {
        return event instanceof StreamEvent.TextStart
            || event instanceof StreamEvent.TextDelta
            || event instanceof StreamEvent.TextEnd
            || event instanceof StreamEvent.ThinkingStart
            || event instanceof StreamEvent.ThinkingDelta
            || event instanceof StreamEvent.ThinkingEnd
            || event instanceof StreamEvent.ToolCallStart
            || event instanceof StreamEvent.ToolCallDelta
            || event instanceof StreamEvent.ToolCallEnd;
    }

    /** 流式 partial 类型 → 消息类型（pi-java 把两者拆成了两个类）。 */
    private static Message.AssistantMessage fromPartial(AssistantMessage partial) {
        return new Message.AssistantMessage(partial.content(), partial.stopReason(), null);
    }

    private static ThinkingConfig thinkingConfig(Config config) {
        return config.thinkingLevelMap().forLevel(config.thinking());
    }
}
