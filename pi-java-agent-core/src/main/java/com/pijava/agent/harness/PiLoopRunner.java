package com.pijava.agent.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.pijava.agent.harness.PiLoop.Config;
import com.pijava.agent.harness.PiLoop.Event;
import com.pijava.agent.harness.PiLoop.NextTurnContext;
import com.pijava.agent.harness.PiLoop.NextTurnUpdate;
import com.pijava.agent.harness.PiLoop.Sink;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.Message;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ThinkingConfig;

/**
 * {@link PiLoop} 的内部实现：双循环、助手流翻译、以及驱动它们的小工具。
 *
 * <p>从 {@code PiLoop} 拆出，使两者都在 500 行以内（{@code CLAUDE.md} 编码规范）。
 * 公开 API 仍是 {@code PiLoop} —— 本类包内可见，不对外暴露。</p>
 *
 * <p>逐段对齐 pi {@code packages/agent/src/agent-loop.ts} @ {@code v0.85.1}：
 * {@link #runLoop} 对应 {@code :165-279}，{@link #streamAssistantResponse} 对应
 * {@code :283-375}。</p>
 */
final class PiLoopRunner {

    private PiLoopRunner() {}

    // ═══════════════════════════════════════════════════════════════
    // 主循环：pi runLoop（agent-loop.ts:165-279）
    // ═══════════════════════════════════════════════════════════════

    /**
     * pi 的双循环。
     *
     * <p>外层 `while (true)`：follow-up 队列决定是否再来一轮。
     * 内层 `while (hasMoreToolCalls || pendingMessages.length > 0)`：处理 steer 与工具调用。</p>
     *
     * @param newMessages 外层首轮不重复发 {@code turn_start}（调用方已发过一次）
     */
    static void runLoop(Context initialContext, List<Message> newMessages,
                        Config initialConfig, Sink emit) {
        var context = initialContext;
        var messages = context.messages();
        var config = initialConfig;
        // pi: 已完成的上一轮（agent-loop.ts:164）。非空即「下一轮的开头」。
        // 它同时承担「首轮不发 turn_start」的判据 —— 调用方在进入本方法前已发过一次。
        NextTurnContext lastCompletedTurn = null;
        // pi: 起始即检查 steer（用户可能在等待期间已经输入）
        var pending = poll(config.steeringMessages());

        while (true) {
            boolean hasMoreToolCalls = true;

            while (hasMoreToolCalls || !pending.isEmpty()) {
                if (lastCompletedTurn != null) {
                    // pi: prepareNextTurn 在**下一轮的开头**调用一次（agent-loop.ts:176-183），
                    // 此时 shouldStopAfterTurn 已经放行 —— 顺序与本类此前的实现相反。
                    if (config.prepareNextTurn() != null) {
                        var update = config.prepareNextTurn().apply(lastCompletedTurn);
                        if (update != null) {
                            // pi: currentContext = nextTurnSnapshot.context ?? currentContext
                            if (update.context() != null) {
                                context = copyOf(update.context());
                            }
                            config = withModelAndThinking(config, update);
                        }
                    }
                    // pi: 准备可能很慢（例如压缩）。拾取期间排队的 steer。
                    // 只在上一轮末尾那次轮询没拿到东西时才再轮询 —— 否则 one-at-a-time
                    // 模式会在一轮里塞两条（agent-loop.ts:184-189）。
                    if (pending.isEmpty()) {
                        pending = poll(config.steeringMessages());
                    }
                    emit.emit(new Event.TurnStart());
                }
                messages = context.messages();

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

                var message = streamAssistantResponse(context, config, emit);
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
                    var batch = PiLoopTools.run(toolCalls, context, config, emit,
                        "length".equals(message.stopReason()));
                    toolResults.addAll(batch.messages());
                    hasMoreToolCalls = !batch.terminate();
                    for (var result : toolResults) {
                        messages.add(result);
                        newMessages.add(result);
                    }
                }

                emit.emit(new Event.TurnEnd(message, List.copyOf(toolResults)));

                // pi: lastCompletedTurn 带的是**当时**的 context（agent-loop.ts:246）
                lastCompletedTurn = new NextTurnContext(message, List.copyOf(toolResults), context);

                // pi: shouldStopAfterTurn 在 turn_end 之后、prepareNextTurn **之前**
                // （agent-loop.ts:249-252）—— 它一旦为真，pi 就返回，**根本不会调用**
                // prepareNextTurn。本类此前把两者写反了，会多跑一次有副作用的钩子。
                if (config.shouldStopAfterTurn() != null
                        && config.shouldStopAfterTurn().apply(lastCompletedTurn)) {
                    emit.emit(new Event.AgentEnd(List.copyOf(newMessages)));
                    return;
                }

                pending = poll(config.steeringMessages());
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

    /**
     * 复制替换进来的上下文，使 {@code messages} 变成**可变**列表。
     *
     * <p>pi 的 {@code context.messages} 是数组，天然可变；pi 的压缩也正是交回
     * {@code this.agent.state.messages.slice()}（一个新数组，{@code agent-session.ts:563}）。
     * Java 侧没有这个语言保证 —— 钩子很容易返回一个 {@code List.of(...)}，
     * 而循环随后要往里追加助手消息与工具结果。这里统一复制，让契约与 pi 的**实际行为**
     * 一致（替换后循环写的是自己那份，不回头改调用方的列表，也与 {@code .slice()} 相同）。</p>
     */
    private static Context copyOf(Context context) {
        return new Context(context.systemPrompt(), new ArrayList<>(context.messages()),
            context.tools());
    }

    /** pi: {@code config = {...config, model, reasoning}} —— 只有这两个字段可被改写。 */
    private static Config withModelAndThinking(Config config, NextTurnUpdate update) {
        return new Config(
            update.model() != null ? update.model() : config.model(),
            update.thinking() != null ? update.thinking() : config.thinking(),
            config.thinkingLevelMap(),
            config.toolExecution(), config.toolRunner(),
            config.streamFn(), config.signal(), config.steeringMessages(),
            config.followUpMessages(), config.transformContext(),
            config.prepareNextTurn(), config.shouldStopAfterTurn(),
            config.streamListener());
    }

    private static ArrayList<Message> poll(Supplier<List<Message>> source) {
        return source == null ? new ArrayList<>() : new ArrayList<>(source.get());
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
            Context context, Config config, Sink emit) {

        var messages = context.messages();
        var llmMessages = config.transformContext() != null
            ? config.transformContext().apply(List.copyOf(messages)) : List.copyOf(messages);

        // pi: llmContext = {systemPrompt: context.systemPrompt, messages: llmMessages,
        //                   tools: context.tools}（agent-loop.ts:290-301）。
        // transformContext 只改消息（pi 的 transformContext 签名也不含 systemPrompt），
        // 系统提示与工具原样透传。
        var llmContext = new Context(context.systemPrompt(), llmMessages, context.tools());
        var options = new StreamOptions(
            java.util.OptionalInt.empty(), java.util.OptionalDouble.empty(),
            thinkingConfig(config));

        var signal = config.signal();
        // 进入本次拉取时信号是否**已经**中止。它区分两件事：
        //   · 已中止时进场 ⇒ 归工具阶段管（pi 的 prepareToolCall 逐调用回 immediate 错误，
        //     agent-loop.ts:655-661）；provider 若照样吐帧，那些帧就该被消费，
        //     否则工具阶段的「每个调用都补一个 end」根本没有调用可补。
        //   · 拉取**途中**才中止 ⇒ 循环层自己兜底，不再消费后续事件（docs/23 §4.4 的 A8）。
        // pi 把 signal 交给 streamFunction、由 provider 自己收尾（:307-311）；pi-java 的
        // StreamIterator 是同步拉取，不能假定 provider 照做，所以补上第二条。
        boolean abortedAtEntry = signal != null && signal.isAborted();
        var iter = config.streamFn().stream(config.model(), llmContext, options);
        Message.AssistantMessage finalMessage = null;
        boolean addedPartial = false;
        boolean consumed = false;
        boolean cutShort = false;
        try {
            while (iter.hasNext()) {
                if (signal != null && !abortedAtEntry && consumed && signal.isAborted()) {
                    cutShort = true;
                    break;
                }
                var event = iter.next();
                consumed = true;
                if (config.streamListener() != null) {
                    config.streamListener().accept(event);
                }
                if (event instanceof StreamEvent.Start(AssistantMessage partial)) {
                    addedPartial = true;
                    finalMessage = fromPartial(partial);
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
                    finalMessage = withErrorShape(fromPartial(err.partial()), err);
                    break;
                }
            }
        } finally {
            iter.close();
        }

        if (finalMessage == null) {
            if (signal == null || !signal.isAborted()) {
                // provider 什么都没发 ⇒ 真错误。
                throw new IllegalStateException("stream produced no terminal message");
            }
            // 信号在**第一帧之前**就响了（调用方 abort 后才进这一轮）：没有事件可消费，
            // 这一轮仍必须有终局消息，否则调用方拿到 null。补一个空的 aborted 助手消息
            // —— pi 侧等价物是 provider 自己以 aborted 收尾的那条消息（faux.ts:321-328：
            // provider/model 来自请求、errorMessage="Request was aborted"、timestamp=now；
            // api 在循环层不可见 —— pi-java 的 ModelId 没有协议维度，故留 null，键省略）。
            finalMessage = new Message.AssistantMessage(List.of(), "aborted", null,
                null, config.model().provider(), config.model().modelName(),
                null, java.time.Instant.now(), "Request was aborted", null);
        }
        finalMessage = markAborted(finalMessage, signal, cutShort);
        if (addedPartial) {
            messages.set(messages.size() - 1, finalMessage);
        } else {
            messages.add(finalMessage);
            emit.emit(new Event.MessageStart(finalMessage));
        }
        emit.emit(new Event.MessageEnd(finalMessage));
        return finalMessage;
    }

    /**
     * 被中断的一轮必须以 {@code aborted} 收尾，而不是徒留一个 {@code null}
     * {@code stopReason}（那样 {@code determineOutcome} 会落到 {@code completed}，
     * 而 {@code ContextEntries.NON_PROJECTED_STOP_REASONS} 又不含 {@code null}，
     * 于是这段残缺的响应会被当成正常回答投影进后续请求）。
     *
     * <p><b>只在流真的被切断时改写</b>（{@code cutShort}，或 provider 连终局事件都没发、
     * {@code stopReason} 为 {@code null}）。provider 报出了终局判定就必须采信它 ——
     * 否则一个「进场前就已中止、但 provider 照样吐完 tool_use」的轮次会被改写成
     * {@code aborted}，循环随即返回，pi 的 {@code prepareToolCall} 那条
     * 「每个调用补一个 immediate 错误 end」的路径就永远走不到了
     * （{@code agent-loop.ts:655-661}）。</p>
     */
    private static Message.AssistantMessage markAborted(
            Message.AssistantMessage message, com.pijava.ai.AbortSignal signal, boolean cutShort) {
        if (signal == null || !signal.isAborted()) {
            return message;
        }
        if ("error".equals(message.stopReason()) || "aborted".equals(message.stopReason())) {
            return message;
        }
        if (!cutShort && message.stopReason() != null) {
            return message;
        }
        return message.withStopReason("aborted");
    }

    /**
     * 错误轮的终局消息必须携带错误本身（pi：provider 层把 {@code stopReason:"error"}
     * 和 {@code errorMessage} 文本写在消息上，pi 的重试分类器只读消息）。pi-java 的
     * provider 方言把错误文本放在 {@code StreamError} 的 Throwable 上，partial 可能是
     * 连 stopReason 都没有的 identityBase 空快照（AbstractChatApi）或只带 stopReason
     * 的 builder 快照（StreamPartialBuilder.emitError）—— 缺哪个槽补哪个：
     * stopReason ← {@code err.reason()}（缺省 "error"），errorMessage ←
     * {@code err.error().getMessage()}。partial 自带的终局/文本优先，不覆写。
     *
     * <p>3d（docs/31 §8.22）：不补则 post-run 重试环 A 的白名单分类器（要求非空
     * errorMessage）在真实 provider 路径上恒 false —— 环 A 形同虚设。与
     * {@code LlmSummaryGenerator.terminal} 的补齐同形（先例即在那里）。</p>
     */
    private static Message.AssistantMessage withErrorShape(
            Message.AssistantMessage projected, StreamEvent.StreamError err) {
        String stopReason = projected.stopReason();
        String errorMessage = projected.errorMessage();
        boolean fixReason = stopReason == null || stopReason.isEmpty();
        boolean fixText = (errorMessage == null || errorMessage.isEmpty()) && err.error() != null;
        if (!fixReason && !fixText) {
            return projected;
        }
        String reason = err.reason() == null || err.reason().isEmpty() ? "error" : err.reason();
        return new Message.AssistantMessage(projected.content(),
            fixReason ? reason : stopReason,
            projected.deferred(), projected.api(), projected.provider(), projected.model(),
            projected.usage(), projected.timestamp(),
            fixText ? err.error().getMessage() : errorMessage,
            projected.rawStopReason());
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

    /**
     * 流式 partial 类型 → 消息类型（pi-java 把两者拆成了两个类）。
     *
     * <p>3a：投影必须全字段 —— pi 的 partial 与终局是同一形状（
     * {@code assistant-message-frame.ts:77-92}），api/provider/model/usage/
     * timestamp/errorMessage 都在 provider 构造点写死、由这里带进终局消息，
     * compaction 估算与溢出守卫（3b/3c）才读得到。此前只搬 content/stopReason，
     * 是一个静默丢点。</p>
     */
    private static Message.AssistantMessage fromPartial(AssistantMessage partial) {
        return Message.AssistantMessage.fromPartial(partial);
    }

    private static ThinkingConfig thinkingConfig(Config config) {
        return config.thinkingLevelMap().forLevel(config.thinking());
    }
}
