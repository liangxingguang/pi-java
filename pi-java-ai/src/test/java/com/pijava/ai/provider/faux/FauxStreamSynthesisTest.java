package com.pijava.ai.provider.faux;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.stream.StreamEvent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * docs/20 §5：{@code streamWithDeltas}（pi {@code providers/faux.ts:358-457}）的
 * 事件合成顺序。固定块大小（min=max=1 ⇒ 每块 4 字符）消除随机边界。
 */
class FauxStreamSynthesisTest {

    private static final Instant NOW = Instant.ofEpochMilli(1_700_000_000_000L);
    private static final FauxChunking FIXED = new FauxChunking(1, 1, null);

    private static AssistantMessage message(List<ContentBlock> content, StopReason reason) {
        return new AssistantMessage(
            "m", content, null, reason, "faux", "faux", "faux-1", null, null, null,
            NOW, null, null, null);
    }

    private static List<StreamEvent> drain(AssistantMessage resolved, FauxChunking chunking) {
        var events = new java.util.concurrent.CopyOnWriteArrayList<StreamEvent>();
        var publisher = new SubmissionPublisher<StreamEvent>();
        publisher.subscribe(new Flow.Subscriber<StreamEvent>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(StreamEvent item) {
                events.add(item);
            }

            @Override
            public void onError(Throwable throwable) {
                throw new AssertionError(throwable);
            }

            @Override
            public void onComplete() {
            }
        });
        try {
            new FauxStreamSynthesis().emit(resolved, publisher, chunking);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        // SubmissionPublisher 异步交付：等终局（FIFO 保证之前的帧都到了）再返回。
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (!events.isEmpty()
                    && (events.get(events.size() - 1) instanceof StreamEvent.StreamDone
                        || events.get(events.size() - 1) instanceof StreamEvent.StreamError)) {
                break;
            }
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
        }
        return new ArrayList<>(events);
    }

    private static List<String> types(List<StreamEvent> events) {
        return events.stream().map(event -> event.getClass().getSimpleName()).toList();
    }

    @Test
    void textEmitsStartChunksEndAndDone() {
        var events = drain(message(List.of(new ContentBlock.TextContent("abcdefgh")),
            StopReason.STOP), FIXED);

        assertThat(types(events)).containsExactly(
            "Start", "TextStart", "TextDelta", "TextDelta", "TextEnd", "StreamDone");
        assertThat(((StreamEvent.TextDelta) events.get(2)).delta()).isEqualTo("abcd");
        assertThat(((StreamEvent.TextDelta) events.get(3)).delta()).isEqualTo("efgh");
        assertThat(((StreamEvent.TextEnd) events.get(4)).text()).isEqualTo("abcdefgh");
        assertThat(events.get(3).partial().content().get(0)).isEqualTo(
            new ContentBlock.TextContent("abcdefgh"));
    }

    @Test
    void theStartSnapshotIsPendingAndEmptyButKeepsIdentity() {
        var events = drain(message(List.of(new ContentBlock.TextContent("x")), StopReason.STOP),
            FIXED);

        var start = (StreamEvent.Start) events.get(0);
        assertThat(start.partial().stopReason()).isEqualTo(StopReason.PENDING);
        assertThat(start.partial().content()).isEmpty();
        assertThat(start.partial().api()).isEqualTo("faux");
        assertThat(start.partial().provider()).isEqualTo("faux");
        assertThat(start.partial().model()).isEqualTo("faux-1");
        assertThat(start.partial().timestamp()).isEqualTo(NOW);
    }

    @Test
    void thinkingThenTextEmitTheirOwnBlockFrames() {
        var events = drain(message(List.of(
            new ContentBlock.ThinkingContent("think"),
            new ContentBlock.TextContent("text")), StopReason.STOP), FIXED);

        assertThat(types(events)).containsExactly(
            "Start",
            "ThinkingStart", "ThinkingDelta", "ThinkingDelta", "ThinkingEnd",
            "TextStart", "TextDelta", "TextEnd",
            "StreamDone");
        assertThat(((StreamEvent.ThinkingEnd) events.get(4)).thinking()).isEqualTo("think");
        assertThat(((StreamEvent.TextEnd) events.get(7)).text()).isEqualTo("text");
    }

    @Test
    void toolArgumentsGrowThroughTheSharedStreamingParser() {
        var arguments = Map.<String, Object>of("path", "/x");
        var events = drain(message(List.of(
            new ContentBlock.ToolUseContent("call-1", "read", arguments)), StopReason.TOOL_USE),
            FIXED);

        assertThat(types(events).get(0)).isEqualTo("Start");
        assertThat(types(events).get(1)).isEqualTo("ToolCallStart");
        assertThat(types(events).get(types(events).size() - 1)).isEqualTo("StreamDone");
        assertThat(types(events)).contains("ToolCallEnd");

        var deltas = events.stream()
            .filter(StreamEvent.ToolCallDelta.class::isInstance)
            .map(StreamEvent.ToolCallDelta.class::cast)
            .toList();
        assertThat(String.join("", deltas.stream().map(StreamEvent.ToolCallDelta::jsonDelta).toList()))
            .isEqualTo("{\"path\":\"/x\"}");
        assertThat(deltas.get(deltas.size() - 1).partial().content().get(0))
            .isInstanceOf(ContentBlock.ToolUseContent.class);

        var end = (StreamEvent.ToolCallEnd) events.get(events.size() - 2);
        assertThat(end.arguments()).containsEntry("path", "/x");
        assertThat(end.partial().content().get(0)).isEqualTo(
            new ContentBlock.ToolUseContent("call-1", "read", arguments));
    }

    @Test
    void severalToolCallsKeepTheirOwnIndices() {
        var events = drain(message(List.of(
            new ContentBlock.ToolUseContent("call-1", "read", Map.of("a", "1")),
            new ContentBlock.ToolUseContent("call-2", "write", Map.of("b", "2"))),
            StopReason.TOOL_USE), FIXED);

        var ends = events.stream()
            .filter(StreamEvent.ToolCallEnd.class::isInstance)
            .map(StreamEvent.ToolCallEnd.class::cast)
            .toList();
        assertThat(ends).hasSize(2);
        assertThat(ends.get(0).contentIndex()).isZero();
        assertThat(ends.get(0).name()).isEqualTo("read");
        assertThat(ends.get(1).contentIndex()).isEqualTo(1);
        assertThat(ends.get(1).name()).isEqualTo("write");
    }

    @Test
    void anEmptyTextStillProducesOneEmptyDelta() {
        var events = drain(message(List.of(new ContentBlock.TextContent("")), StopReason.STOP),
            FIXED);

        assertThat(types(events)).containsExactly(
            "Start", "TextStart", "TextDelta", "TextEnd", "StreamDone");
        assertThat(((StreamEvent.TextDelta) events.get(2)).delta()).isEmpty();
    }

    @Test
    void errorStopReasonBecomesAStreamErrorCarryingTheMessageText() {
        var resolved = message(List.of(new ContentBlock.TextContent("partial")),
            StopReason.ERROR).withErrorMessage("boom");

        var events = drain(resolved, FIXED);

        assertThat(events.get(events.size() - 1)).isInstanceOf(StreamEvent.StreamError.class);
        var error = (StreamEvent.StreamError) events.get(events.size() - 1);
        assertThat(error.reason()).isEqualTo(StopReason.ERROR);
        assertThat(error.partial().errorMessage()).isEqualTo("boom");
        assertThat(error.partial().stopReason()).isEqualTo(StopReason.ERROR);
    }

    @Test
    void abortedStopReasonBecomesAnAbortedStreamError() {
        var events = drain(message(List.of(), StopReason.ABORTED), FIXED);

        assertThat(types(events)).containsExactly("Start", "StreamError");
        assertThat(((StreamEvent.StreamError) events.get(1)).reason())
            .isEqualTo(StopReason.ABORTED);
    }

    @Test
    void aPendingStopReasonIsAnErrorRatherThanASilentSuccess() {
        assertThatThrownBy(() -> drain(message(List.of(), StopReason.PENDING), FIXED))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Faux response ended without a stop reason");
    }
}
