package com.pijava.ai.protocol;

import java.util.List;
import java.util.concurrent.SubmissionPublisher;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.stream.StreamPartialBuilder;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B234：响应 {@code durationMs} 的身份缝加盖（pi
 * {@code AssistantMessageEventStream}，event-stream.ts:91-129）。
 *
 * <p>对位 pi {@code event-stream.test.ts:121-170} 的四件事：done/error 终局
 * 盖单调钟毫秒；只盖一次；已有值保留（转发流保留内层测量）；非终局帧不盖。
 * Java 侧时钟起点＝{@code stream()} 调用（缝建在 AbstractChatApi 上）。</p>
 */
class ResponseTimingTest {

    private static StreamRequest request() {
        return StreamRequest.of(ModelId.of("test", "timed-model"),
            List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")))));
    }

    /** 发 start → sleep → 终局事件的零 I/O 车道。 */
    private static final class TimedApi extends AbstractChatApi {
        private final boolean error;
        private final long sleepMs;
        private final Long presetDuration;

        TimedApi(boolean error, long sleepMs, Long presetDuration) {
            this.error = error;
            this.sleepMs = sleepMs;
            this.presetDuration = presetDuration;
        }

        @Override
        public String apiName() {
            return "openai-responses";
        }

        @Override
        protected void streamInternal(StreamRequest request,
                                      SubmissionPublisher<StreamEvent> publisher) {
            var builder = new StreamPartialBuilder();
            publisher.submit(builder.emitStart());
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (presetDuration != null) {
                builder.emitTextStart();
            }
            if (error) {
                publisher.submit(builder.emitError(StopReason.ERROR,
                    new RuntimeException("boom")));
            } else {
                publisher.submit(builder.emitDone(StopReason.STOP));
            }
        }
    }

    /** 终局（done/error）携带耗时；start 帧不带。 */
    @Test
    void stampsDurationOnTerminalOnly() {
        var api = new TimedApi(false, 30, null);
        AssistantMessage start = null;
        StreamEvent.StreamDone done = null;
        try (var iter = api.streamBlocking(request(), ApiOptions.defaults())) {
            while (iter.hasNext()) {
                var e = iter.next();
                if (e instanceof StreamEvent.Start s) {
                    start = s.partial();
                }
                if (e instanceof StreamEvent.StreamDone d) {
                    done = d;
                }
            }
        }
        assertThat(start).isNotNull();
        assertThat(start.durationMs()).as("流中帧不盖耗时").isNull();
        assertThat(done).isNotNull();
        assertThat(done.partial().durationMs())
            .as("30ms 的流至少量到 10ms（CI 宽限）")
            .isGreaterThanOrEqualTo(10L);
    }

    /** error 终局同法（pi 夹具：error.durationMs >= 0）。 */
    @Test
    void stampsDurationOnErrorTerminal() {
        var api = new TimedApi(true, 10, null);
        StreamEvent.StreamError terminal = null;
        try (var iter = api.streamBlocking(request(), ApiOptions.defaults())) {
            while (iter.hasNext()) {
                var e = iter.next();
                if (e instanceof StreamEvent.StreamError se) {
                    terminal = se;
                }
            }
        }
        assertThat(terminal).isNotNull();
        assertThat(terminal.partial().durationMs()).isNotNull().isGreaterThanOrEqualTo(0L);
    }

    /** send() 的终局消息（会话记录投影）携带耗时。 */
    @Test
    void sendProjectsDuration() {
        var api = new TimedApi(false, 10, null);
        var message = api.send(request(), ApiOptions.defaults());
        assertThat(message).isInstanceOf(Message.AssistantMessage.class);
        assertThat(((Message.AssistantMessage) message).durationMs())
            .isNotNull().isGreaterThanOrEqualTo(0L);
    }

    /** 已有值保留（pi：转发流保留内层测量，outer stream 不覆盖）。 */
    @Test
    void keepsExistingDuration() {
        var api = new AbstractChatApi() {
            @Override public String apiName() {
                return "openai-responses";
            }
            @Override protected void streamInternal(StreamRequest request,
                                                     SubmissionPublisher<StreamEvent> publisher) {
                // 生产者自行挂身份 ⇒ IdentitySubscriber 早返回，事件原样过（缝不再盖）。
                var partial = AssistantMessage.empty()
                    .withIdentity("openai-responses", "test", "timed-model",
                        java.time.Instant.now())
                    .withStopReason(StopReason.STOP)
                    .withDurationMs(1234L);
                publisher.submit(new StreamEvent.StreamDone(StopReason.STOP, null, partial));
            }
        };
        StreamEvent.StreamDone done = null;
        try (var iter = api.streamBlocking(request(), ApiOptions.defaults())) {
            while (iter.hasNext()) {
                var e = iter.next();
                if (e instanceof StreamEvent.StreamDone d) {
                    done = d;
                }
            }
        }
        assertThat(done).isNotNull();
        assertThat(done.partial().durationMs())
            .as("终局消息自带的耗时不被外层覆盖")
            .isEqualTo(1234L);
    }
}
