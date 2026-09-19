package com.pijava.agent.harness;

import java.util.ArrayList;
import java.util.List;

import com.pijava.ai.AbortSignal;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.stream.StreamPartialBuilder;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevelMap;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⑩（B26）的两条不变量：**中间帧的 partial 带 {@code "pending"}**，而
 * **落定的消息绝不能停在 {@code "pending"}**。
 *
 * <p>为什么需要它：{@code StreamPartialBuilder} 的初值从 {@code null} 改成 pi 的
 * {@code "pending"}（{@code anthropic-messages.ts:526} 等五处）之后，「provider 还没给终局
 * 判定」这个状态**换了取值**。宿主侧 {@code PiLoopRunner:237} 是拿每个 update 的 partial
 * 重建终局消息的 —— 所以一处按 {@code != null} 写的收尾检查会**恒真**，把
 * {@code "pending"} 当成「已落定」放行；而 {@code ContextEntries.NON_PROJECTED_STOP_REASONS}
 * 不含 {@code "pending"} ⇒ 被打断的响应会被投影进后续请求的上下文（docs/31 §8.35.15 八-8.3，
 * 即 {@code MidStreamAbortTest} 记的 A8 缺陷的复活形态）。</p>
 *
 * <p><b>本夹具与 {@code MidStreamAbortTest} 的差别</b>：那条走「拉取**途中**中止」
 * （{@code cutShort} 支，收尾不看 stopReason，故对 ⑩ 免疫），且 partial 用
 * {@code AssistantMessage.empty()} 直造、走不到 builder。这条走「**进场前**就已中止」
 * （{@code PiLoopRunner:213} 的 {@code abortedAtEntry}）＋ **真实 builder 造帧** ——
 * 这正是 ⑩ 唯一能伤到的那条路。</p>
 */
class PendingStopReasonSettlementTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "test-model");

    /**
     * 进场前已中止，provider 照样吐帧、但**不发终局事件**。
     *
     * <p>终局消息因此是「最后一个 update 的 partial 投影」⇒ 它的 stopReason 就是累加器的
     * 当前值。⑩ 之前是 {@code null}、之后是 {@code "pending"} —— 两种都表示「没有终局判定」，
     * 故都必须被改写成 {@code aborted}。</p>
     */
    @Test
    void pendingPartialIsNeverSettledAsPending() {
        var signal = AbortSignal.create();
        signal.abort();                                  // 进场**之前**就已中止

        var builder = new StreamPartialBuilder();
        var streamFn = (StreamFn) (model, context, options) -> new StreamIterator() {
            private int i;

            @Override
            public boolean hasNext() {
                return i < 3;                            // 三帧，没有 done/error
            }

            @Override
            public StreamEvent next() {
                return switch (++i) {
                    case 1 -> builder.emitStart();
                    case 2 -> builder.emitTextStart();
                    default -> builder.emitTextDelta("partial answer");
                };
            }

            @Override
            public void close() { }
        };

        var updates = new ArrayList<String>();
        var context = Context.of(new ArrayList<>());
        var newMessages = PiLoop.run(List.of(user("go")), context,
            config(streamFn, signal), event -> {
                if (event instanceof PiLoop.Event.MessageUpdate update
                        && update.message() instanceof Message.AssistantMessage assistant) {
                    updates.add(String.valueOf(assistant.stopReason()));
                }
            });

        // ① 中间帧：pi 的中间态取值，逐帧都是 "pending"（不是 null、不是 "stop"）。
        assertThat(updates).isNotEmpty().containsOnly("pending");

        // ② 落定：绝不留在 "pending" —— 中止的轮次以 "aborted" 收尾。
        assertThat(assistantMessages(newMessages).get(0).stopReason()).isEqualTo("aborted");
    }

    private static List<Message.AssistantMessage> assistantMessages(List<Message> messages) {
        return messages.stream()
            .filter(Message.AssistantMessage.class::isInstance)
            .map(Message.AssistantMessage.class::cast)
            .toList();
    }

    private static Message user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }

    private static PiLoop.Config config(StreamFn streamFn, AbortSignal signal) {
        return new PiLoop.Config(
            MODEL,
            ModelThinkingLevel.off(),
            ThinkingLevelMap.empty(),
            ToolExecution.defaultMode(),
            null,
            streamFn,
            signal,
            null,
            null,
            null,
            null,
            null,
            null);
    }
}
