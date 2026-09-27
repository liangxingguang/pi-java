package com.pijava.ai.protocol;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.SubmissionPublisher;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.stream.StreamPartialBuilder;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C 批次（{@code docs/55}）：{@link AbstractChatApi} 两条出口的终局落定。
 *
 * <ul>
 *   <li><b>缺终局 ⇒ 错误</b>：{@code onComplete} 安全网此前推一个
 *       {@code StreamDone("stop", null, empty)}（一次「空回答的成功轮次」），
 *       pi 的规矩反过来 —— 「干净 EOF 但没有 done/error」是错误
 *       （{@code agent/src/proxy.ts:236-247}）。</li>
 *   <li><b>非流式 {@code send} 返回落定消息</b>：pi 的 {@code complete()} 就是
 *       {@code stream(...).result()}，两种终局**都 resolve**（{@code compat.ts:269-276}）
 *       ⇒ 调用方读消息上的 {@code stopReason}/{@code errorMessage}，而不是吞掉错误。</li>
 * </ul>
 */
class ChatApiTerminalSettlementTest {

    private static StreamRequest request(String model) {
        return StreamRequest.of(ModelId.of("test", model), List.of());
    }

    /** 只发非终局事件后正常返回 ⇒ 命名管道关闭 ⇒ 触发 {@code onComplete} 安全网。 */
    private static final class NoTerminalApi extends AbstractChatApi {
        @Override public String apiName() { return "faux"; }

        @Override
        protected void streamInternal(StreamRequest request,
                                      SubmissionPublisher<StreamEvent> publisher) {
            publisher.submit(new StreamEvent.Start(AssistantMessage.empty()));
            publisher.submit(new StreamEvent.TextStart(0, AssistantMessage.empty()));
        }
    }

    /** 流到一半再报错的车道（内容必须留下）。 */
    private static final class MidStreamErrorApi extends AbstractChatApi {
        private final IOException cause;

        MidStreamErrorApi(IOException cause) { this.cause = cause; }

        @Override public String apiName() { return "faux"; }

        @Override
        protected void streamInternal(StreamRequest request,
                                      SubmissionPublisher<StreamEvent> publisher) {
            var builder = new StreamPartialBuilder("msg-1");
            publisher.submit(builder.emitStart());
            publisher.submit(builder.emitTextStart());
            publisher.submit(builder.emitTextDelta("alpha"));
            publisher.submit(builder.emitError("error", cause));
        }
    }

    private static List<StreamEvent> drain(AbstractChatApi api, StreamRequest request) {
        var events = new ArrayList<StreamEvent>();
        try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
            while (iter.hasNext()) {
                events.add(iter.next());
            }
        }
        return events;
    }

    @Test
    void missingTerminalIsAnErrorNotASuccess() {
        var events = drain(new NoTerminalApi(), request("no-terminal"));

        assertThat(events.getLast()).isInstanceOf(StreamEvent.StreamError.class);
        var err = (StreamEvent.StreamError) events.getLast();
        assertThat(err.reason()).isEqualTo("error");
        assertThat(err.partial().stopReason()).isEqualTo("error");
        assertThat(err.partial().errorMessage()).isNotBlank();
    }

    @Test
    void streamErrorExitsTheSeamSettled() {
        var events = drain(new MidStreamErrorApi(new IOException("kaboom")), request("mid-error"));

        var err = (StreamEvent.StreamError) events.getLast();
        assertThat(err.partial().stopReason()).isEqualTo("error");
        assertThat(err.partial().errorMessage()).isEqualTo("kaboom");
        assertThat(err.partial().content())
            .containsExactly(new ContentBlock.TextContent("alpha"));
        // 身份挂载照旧（3a）：终局快照仍带 api/provider/model/timestamp。
        assertThat(err.partial().api()).isEqualTo("faux");
        assertThat(err.partial().provider()).isEqualTo("test");
        assertThat(err.partial().timestamp()).isNotNull();
    }

    @Test
    void sendReturnsTheSettledError() {
        var message = new MidStreamErrorApi(new IOException("kaboom"))
            .send(request("send-error"), ApiOptions.defaults());

        // ChatApi.send 的静态返回类型是 Message 接口（pi 的 complete() 返回 AssistantMessage，
        // Java 侧同物分两个类）⇒ 断言先落型再读字段。
        assertThat(message).isInstanceOfSatisfying(
            com.pijava.ai.message.Message.AssistantMessage.class,
            settled -> {
                assertThat(settled.stopReason()).isEqualTo("error");
                assertThat(settled.errorMessage()).isEqualTo("kaboom");
                assertThat(settled.content())
                    .containsExactly(new ContentBlock.TextContent("alpha"));
                assertThat(settled.api()).isEqualTo("faux");
            });
    }
}
