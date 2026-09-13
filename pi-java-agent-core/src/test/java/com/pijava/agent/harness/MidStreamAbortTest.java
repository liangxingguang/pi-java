package com.pijava.agent.harness;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.record.OperationOutcome;
import com.pijava.agent.tool.ToolRegistry;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流中途 abort 的终态（docs/23 §4.4 的待确认项 A8）。
 *
 * <p>场景：provider 已经吐出部分内容（partial 的 {@code stopReason} 仍为 {@code null}，
 * 这是真实的中途快照），此时驱动循环在下一轮迭代开头检测到 abort 信号并
 * {@code iter.close(); break;}。此后没有任何代码把 stopReason 改写成
 * {@code "aborted"}，于是：</p>
 *
 * <ul>
 *   <li>{@code determineOutcome} 落到 {@code "completed"}（{@code HarnessUtils:126}）</li>
 *   <li>助手 entry 以 {@code stopReason == null} 落库，而
 *       {@code ContextEntries.NON_PROJECTED_STOP_REASONS} 只含 {@code deferred/error/aborted}
 *       ⇒ 这段被打断的响应会被**当成正常回答投影进后续请求的上下文**</li>
 * </ul>
 *
 * <p>本测试断言的是**应对齐 pi 的行为**：abort 必须记成 {@code aborted}。
 * 若它失败，即证明 A8 成立（docs/23 §4.4 已给出修法：break 后补
 * {@code lane.partial = lane.partial.withStopReason("aborted")}）。</p>
 */
class MidStreamAbortTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "test-model");

    /** 流中途 abort：第 3 次取事件之前置起 abort 信号，此后的事件不得被消费。 */
    private static StreamFn midStreamAbortFn(AtomicReference<AgentHarness> holder) {
        var partial = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent("partial answer")));
        return (model, context, options) -> new StreamIterator() {
            private int i;

            @Override
            public boolean hasNext() {
                if (i == 2) {
                    holder.get().abort("default");   // 已产出 2 个事件 ⇒ 真·流中途
                }
                return i < 3;
            }

            @Override
            public StreamEvent next() {
                return switch (++i) {
                    case 1 -> new StreamEvent.Start(AssistantMessage.empty());
                    case 2 -> new StreamEvent.TextEnd(0, "partial answer", partial);
                    // 这一帧**不应**被消费（循环已在检测到 abort 后 break）：
                    default -> new StreamEvent.StreamDone("stop", null,
                        partial.withStopReason("stop"));
                };
            }

            @Override
            public void close() { }
        };
    }

    @Test
    void abortDuringStreamingFinalizesAsAborted() {
        var holder = new AtomicReference<AgentHarness>();
        var h = harness(midStreamAbortFn(holder));
        holder.set(h);

        // abort 由流自身在拉取途中设置（见 midStreamAbortFn），驱动是阻塞的整轮运行。
        h.prompt("default", "go", List.of());

        // 终态必须是 aborted（pi：中断的轮次不是 completed）。
        assertThat(operationOutcome(h)).isEqualTo(OperationOutcome.ABORTED);
        // 最新助手消息的 stopReason 必须是 aborted（否则会投影进后续请求）。
        assertThat(h.lastAssistantMessage().stopReason()).isEqualTo("aborted");
        // 落库的助手 entry 同样（entry 是 stopReason 的唯一真相，docs/22 D1）。
        assertThat(lastAssistantEntryStopReason(h)).isEqualTo("aborted");
    }

    private static OperationOutcome operationOutcome(AgentHarness h) {
        return h.snapshot("default").records().stream()
            .filter(LaneRecord.OperationFinished.class::isInstance)
            .map(r -> ((LaneRecord.OperationFinished) r).outcome())
            .reduce((first, second) -> second)
            .orElseThrow(() -> new AssertionError("no OperationFinished record"));
    }

    private static String lastAssistantEntryStopReason(AgentHarness h) {
        return h.snapshot("default").transcript().stream()
            .filter(Entry.Message.class::isInstance)
            .map(e -> ((Entry.Message) e).message())
            .filter(Message.AssistantMessage.class::isInstance)
            .map(m -> ((Message.AssistantMessage) m).stopReason())
            .reduce((first, second) -> second)
            .orElseThrow(() -> new AssertionError("no assistant entry"));
    }

    private static AgentHarness harness(StreamFn streamFn) {
        return AgentHarness.create(new HarnessConfig(
            streamFn, MODEL, ModelThinkingLevel.off(), "",
            Set.of(), 200_000, new ToolRegistry(null), null, null,
            null, Map.of(),
            com.pijava.ai.http.RetryPolicy.defaultPolicy(),
            com.pijava.telemetry.NoopTelemetryContext.INSTANCE,
            com.pijava.ai.thinking.ThinkingLevelMap.empty(),
            QueueMode.defaultMode(), QueueMode.defaultMode(), ToolExecution.defaultMode(),
            event -> { }));
    }
}
