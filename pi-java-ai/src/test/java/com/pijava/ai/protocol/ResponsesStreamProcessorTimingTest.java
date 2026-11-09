package com.pijava.ai.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import com.openai.core.JsonValue;
import com.openai.core.http.StreamResponse;
import com.openai.models.responses.ResponseOutputItemAddedEvent;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.ResponseTextDeltaEvent;

import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Responses 车道的<b>增量发射</b>语义（pi 对位：TS 侧逐 chunk 异步迭代，事件随
 * SSE 到达即发布——{@code openai-responses-shared.ts} 的 {@code for await}）。
 *
 * <p>回归背景：车道曾把 {@code stream.stream().toList()} 当循环源——{@code toList}
 * 先<b>排空</b>整条 SSE 流再迭代 ⇒ 所有 {@code publisher.submit} 堆到流结束时一次性
 * 爆发，web/TUI 全程无增量、回合结束才蹦全文（web 探针实测：11 条 message_update
 * 全部落在同一毫秒）。</p>
 *
 * <p>断言是<b>确定性的</b>、不依赖时钟：伪造流的拉取钩在「第 2 个事件被拉取」时
 * 快照已提交的 TextDelta 计数。惰性迭代 ⇒ 第 1 个事件已提交（=1）；先排空再迭代
 * ⇒ 快照时为 0。</p>
 */
class ResponsesStreamProcessorTimingTest {

    private static ResponseStreamEvent textDelta(String delta) {
        return ResponseStreamEvent.ofOutputTextDelta(
            ResponseTextDeltaEvent.builder()
                .itemId("msg_1")
                .outputIndex(0)
                .contentIndex(0)
                .delta(delta)
                .logprobs(List.of())
                .sequenceNumber(1)
                .build());
    }

    /** 声明 outputIndex 0 是 message（TEXT）槽——TextDelta 的提交门。 */
    private static ResponseStreamEvent itemAdded() {
        return ResponseStreamEvent.ofOutputItemAdded(
            ResponseOutputItemAddedEvent.builder()
                .sequenceNumber(0)
                .outputIndex(0)
                .item(ResponseOutputMessage.builder()
                    .id("msg_1")
                    .role(JsonValue.from("assistant"))
                    .content(List.of())
                    .status(ResponseOutputMessage.Status.IN_PROGRESS)
                    .build())
                .build());
    }

    /** 记录式 publisher：无需订阅者，submit 即记账（排除车道的 Start 帧）。 */
    private static final class RecordingPublisher extends SubmissionPublisher<StreamEvent> {
        final List<StreamEvent> submitted = Collections.synchronizedList(new ArrayList<>());

        @Override
        public int submit(StreamEvent item) {
            submitted.add(item);
            return super.submit(item);
        }

        long textDeltas() {
            synchronized (submitted) {
                return submitted.stream().filter(StreamEvent.TextDelta.class::isInstance).count();
            }
        }
    }

    /** 伪造流：每次 tryAdvance 先触发 onPull，再吐下一个事件。 */
    private static StreamResponse<ResponseStreamEvent> fakeStream(
            List<ResponseStreamEvent> events, Runnable onPull) {
        return new StreamResponse<>() {
            @Override
            public Stream<ResponseStreamEvent> stream() {
                Iterator<ResponseStreamEvent> iter = events.iterator();
                var spliter = new Spliterators.AbstractSpliterator<ResponseStreamEvent>(
                    Long.MAX_VALUE, Spliterator.ORDERED) {
                    @Override
                    public boolean tryAdvance(
                            java.util.function.Consumer<? super ResponseStreamEvent> action) {
                        onPull.run();
                        if (iter.hasNext()) {
                            action.accept(iter.next());
                            return true;
                        }
                        return false;
                    }
                };
                return StreamSupport.stream(spliter, false);
            }

            @Override
            public void close() {
                // no-op
            }
        };
    }

    private static StreamRequest request() {
        return StreamRequest.of(ModelId.of("openai", "timing-test"),
            List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")))));
    }

    @Test
    void emitsTextDeltasIncrementallyWhilePulling() {
        var publisher = new RecordingPublisher();
        var pulls = new AtomicInteger();
        // 快照＝第 3 次拉取（第 2 个 delta 事件被拉取）那一刻已提交的 TextDelta 计数。
        // 事件序：① itemAdded（声明 TEXT 槽）② delta "a" ③ delta "b"。
        // 惰性迭代 ⇒ ②已处理并提交 ⇒ 快照 1；先排空再迭代 ⇒ 快照 0。
        var textDeltasAtSecondPull = new AtomicInteger(-1);
        var events = List.of(itemAdded(), textDelta("a"), textDelta("b"));
        Supplier<StreamResponse<ResponseStreamEvent>> supplier = () ->
            fakeStream(events, () -> {
                if (pulls.incrementAndGet() == 3) {
                    textDeltasAtSecondPull.set((int) publisher.textDeltas());
                }
            });

        ResponsesStreamProcessor.process(supplier, publisher,
            ModelInfo.minimal(ModelId.of("openai", "timing-test")),
            Map.of(), Throwable::getMessage, null);

        assertThat(pulls.get()).as("三个事件都被拉取（＋一次耗尽探测）").isEqualTo(4);
        // 惰性迭代：第 1 个 TextDelta 在第 2 个 delta 被拉取之前就已提交。
        // （.toList() 先排空整条流 ⇒ 快照时为 0。）
        assertThat(textDeltasAtSecondPull.get())
            .as("拉取第 2 个 delta 时第 1 个 TextDelta 必须已提交（增量发射）")
            .isEqualTo(1);
        // 收口语义不受影响：两个 delta 都进了 publisher。
        assertThat(publisher.textDeltas()).isEqualTo(2);
    }
}
