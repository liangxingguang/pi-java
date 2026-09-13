package com.pijava.agent.harness;

import java.util.List;
import java.util.Set;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 queue scheduling: steer / followUp / nextRun / cancelQueued
 * enqueueing, cancellation, and mode-aware consumption.
 */
class QueueSchedulingTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "test-model");

    private static AgentHarness harness() {
        var partial = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent("done")))
            .withStopReason("stop");
        StreamFn sf = (model, context, options) -> StreamIterator.from(List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextEnd(0, "done", partial),
            new StreamEvent.StreamDone("stop", null, partial)));
        return AgentHarness.create(new HarnessConfig(
            sf, MODEL, ModelThinkingLevel.off(), "",
            Set.of(), 200_000, null, null, null,
            null, java.util.Map.of(),
            com.pijava.ai.http.RetryPolicy.defaultPolicy(),
            com.pijava.telemetry.NoopTelemetryContext.INSTANCE,
            com.pijava.ai.thinking.ThinkingLevelMap.empty(),
            QueueMode.defaultMode(), QueueMode.defaultMode(), ToolExecution.defaultMode(),
            event -> { }));
    }

    private static List<String> userMessages(AgentHarness h, String lane) {
        return h.snapshot(lane).transcript().stream()
            .filter(e -> e instanceof Entry.Message m && "user".equals(m.message().role()))
            .map(e -> ((Entry.Message) e).message().content())
            .map(blocks -> blocks.isEmpty() ? "" : ((ContentBlock.TextContent) blocks.get(0)).text())
            .toList();
    }

    @Test
    void cancelQueuedRejectsUnknownType() {
        var h = harness();
        h.nextRun("default", "x");
        try {
            h.cancelQueued("default", "bogus");
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    void steerQueuedBeforeRunIsInjectedAsUserMessage() {
        var h = harness();
        h.steer("default", "steering message");

        // 队列只在运行内被 drain：起手的 prompt 先落盘，随后轮询到的 steer 被注入。
        h.prompt("go");

        assertThat(userMessages(h, "default"))
            .containsExactly("go", "steering message");
    }

    @Test
    void followUpQueuedDuringRunStartsNextRun() throws Exception {
        var h = harness();
        // 在第一次助手回复仍在途时入队（流事件监听器跑在运行线程上）。
        var queued = new java.util.concurrent.atomic.AtomicBoolean();
        var registration = h.onStreamEvent(e -> {
            if (e instanceof StreamEvent.TextEnd && queued.compareAndSet(false, true)) {
                h.followUp("default", "follow-up prompt");
            }
        });
        try {
            h.prompt("first prompt");
        } finally {
            registration.close();
        }

        // 运行内追加：内层循环耗尽后 drain follow-up，同一轮运行续上第二轮。
        assertThat(userMessages(h, "default"))
            .containsExactly("first prompt", "follow-up prompt");
        assertThat(h.lastAssistantMessage()).isNotNull();
    }

    @Test
    void oneAtATimeLeavesRemainingFollowUpsQueued() {
        var h = harness();
        h.followUp("default", "first");
        h.followUp("default", "second");
        h.followUp("default", "third");

        h.prompt("go");

        // One-at-a-time: each inner-loop exhaustion drains exactly one message;
        // the turns chain inside the run until the queue is empty. Runs append,
        // so the final transcript holds every processed prompt in order.
        assertThat(userMessages(h, "default"))
            .containsExactly("go", "first", "second", "third");
        assertThat(h.snapshot("default").queues().followUp()).isEmpty();
    }

    @Test
    void oneAtATimeStopsWhenRunFinishesWithoutQueue() {
        var h = harness();
        h.followUp("default", "only");

        h.prompt("go");

        assertThat(userMessages(h, "default")).containsExactly("go", "only");
        assertThat(h.snapshot("default").operation()).isNull();
        assertThat(h.snapshot("default").queues().followUp()).isEmpty();
    }

    @Test
    void allModeDrainsEntireQueue() {
        var h = harness();
        h.followUpMode(new QueueMode.All());
        h.followUp("default", "first");
        h.followUp("default", "second");

        h.prompt("go");

        // All 模式一次取走整条队列，但**一项一条**：pi 的 PendingMessageQueue.drain()
        // 在 "all" 下原样返回全部消息（agent.ts:143-148），循环再逐条 push 并各自发
        // message_start/message_end（agent-loop.ts:200-208）—— 不合并。旧的
        // ActionExecutor 把整批拼成一条 "first\n\nsecond"，是 pi-java 自己的构造。
        assertThat(userMessages(h, "default"))
            .containsExactly("go", "first", "second");
        assertThat(h.snapshot("default").queues().followUp()).isEmpty();
    }

    @Test
    void streamListenerReceivesEvents() throws Exception {
        var h = harness();
        var received = new java.util.ArrayList<StreamEvent>();
        try (var registration = h.onStreamEvent(received::add)) {
            h.prompt("default", "hello", List.of());
        }

        assertThat(received).isNotEmpty();
        assertThat(received).anyMatch(e -> e instanceof StreamEvent.TextEnd);
    }
}
