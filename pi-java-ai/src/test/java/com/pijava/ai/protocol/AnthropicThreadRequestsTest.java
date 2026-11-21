package com.pijava.ai.protocol;

import java.util.ArrayList;
import java.util.List;

import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelId;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code docs/12} S7b：线程请求的**准备／记账**（pi {@code prepareThreadRequest :352-383}、
 * {@code recordThreadReply :445-475}）与开关推导（{@code getAnthropicCompat:219}）。
 *
 * <p>只测逻辑面：行指纹按「记账时算出的回复行」拼回下一轮的前缀，验证「能续就续、不能就
 * create」。真实转换的逐字对齐由 {@link AnthropicThreadsWireTest} 的两轮观测兜住。</p>
 */
class AnthropicThreadRequestsTest {

    private static final String SESSION = "s-threads-unit";
    private static final String BASE = "https://api.anthropic.com";

    @AfterEach
    void clearSessionState() {
        AnthropicThreadState.clear(null);
    }

    @Test
    void theAllowlistFollowsTheExtraOverrideThenTheEndpoint() {
        assertThat(AnthropicThreadRequests.supported(true, "openrouter", "http://localhost"))
            .as("extra 显式开启优先于端点").isTrue();
        assertThat(AnthropicThreadRequests.supported(false, "anthropic", BASE))
            .as("extra 显式关闭优先于端点").isFalse();
        assertThat(AnthropicThreadRequests.supported(null, "anthropic", null))
            .as("无覆盖且 provider 就是 anthropic ⇒ SDK 缺省端点即白名单").isTrue();
        assertThat(AnthropicThreadRequests.supported(null, "anthropic", BASE)).isTrue();
        assertThat(AnthropicThreadRequests.supported(null, "anthropic", "https://proxy.test"))
            .as("中转站默认不开：它会丢未知字段").isFalse();
        assertThat(AnthropicThreadRequests.supported(null, "minimax", BASE))
            .as("provider 不是 anthropic 但端点是 ⇒ 开门").isTrue();
    }

    @Test
    void aFreshSessionCreatesAThread() {
        var thread = AnthropicThreadRequests.prepare(SESSION, "anthropic", BASE, built(2, "s"));

        assertThat(thread).isNotNull();
        assertThat(thread.type()).isEqualTo(AnthropicThreadWire.ThreadRequest.Type.CREATE);
    }

    @Test
    void aSessionThatWasTurnedOffSendsNoThread() {
        AnthropicThreadState.off(SESSION);

        assertThat(AnthropicThreadRequests.prepare(SESSION, "anthropic", BASE, built(2, "s")))
            .isNull();
    }

    @Test
    void aRecoveredReplyContinuesOnceFromTheRecordedPrefix() {
        var request = request();
        var first = built(2, "s");
        AnthropicThreadRequests.record(SESSION, "anthropic", BASE, request, first, reply(),
            false, false);

        var state = AnthropicThreadState.get(SESSION).orElseThrow();
        var replyKeys = AnthropicThreadRequests.replyRowKeys(reply(), request, false, false);
        assertThat(state.prefixCount()).isEqualTo(2 + replyKeys.size());
        assertThat(state.replyId()).isEqualTo("msg_1");

        var next = builtWithKeys(List.of("row-0", "row-1", replyKeys.get(0), "row-3"), "s");
        var thread = AnthropicThreadRequests.prepare(SESSION, "anthropic", BASE, next);

        assertThat(thread.type()).isEqualTo(AnthropicThreadWire.ThreadRequest.Type.CONTINUE);
        assertThat(thread.prefixCount()).isEqualTo(3);
        assertThat(thread.replyId()).isEqualTo("msg_1");
        assertThat(AnthropicThreadState.get(SESSION)).as("单次可续：状态已被读走").isEmpty();
    }

    @Test
    void aChangedPrefixStartsANewThread() {
        var request = request();
        AnthropicThreadRequests.record(SESSION, "anthropic", BASE, request, built(2, "s"),
            reply(), false, false);

        // 设置变了（system／tools 的签名不同）⇒ 前缀哈希不同。
        var thread = AnthropicThreadRequests.prepare(SESSION, "anthropic", BASE,
            built(4, "other-settings"));

        assertThat(thread.type()).isEqualTo(AnthropicThreadWire.ThreadRequest.Type.CREATE);
    }

    @Test
    void aReplyThatAResponseModelProducedIsNotRecorded() {
        // pi :466-467 —— responseModel 有值 ⇒ 回退模型作答，服务端不存那些回复。
        var fallbackAnswered = Message.AssistantMessage.fromPartial(
            com.pijava.ai.message.AssistantMessage.empty()
                .withContent(List.of(new ContentBlock.TextContent("hi")))
                .withResponseModel("claude-opus-5"));

        AnthropicThreadRequests.record(SESSION, "anthropic", BASE, request(), built(2, "s"),
            fallbackAnswered, false, false);

        assertThat(AnthropicThreadState.get(SESSION)).isEmpty();
    }

    // ── Fixtures ────────────────────────────────────────────────────────

    private static AnthropicRequestBuilder.BuiltParams built(int count, String settings) {
        var keys = new ArrayList<String>();
        for (int i = 0; i < count; i++) {
            keys.add("row-" + i);
        }
        return builtWithKeys(keys, settings);
    }

    private static AnthropicRequestBuilder.BuiltParams builtWithKeys(List<String> keys,
                                                                     String settings) {
        return new AnthropicRequestBuilder.BuiltParams(null, false, keys.size(),
            List.copyOf(keys), settings);
    }

    private static StreamRequest request() {
        return new StreamRequest(ModelId.of("anthropic", "claude-sonnet-5"), "be brief",
            List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi"))),
                new Message.UserMessage(List.of(new ContentBlock.TextContent("next")))),
            List.of(), 100, 0.5);
    }

    private static Message.AssistantMessage reply() {
        return Message.AssistantMessage.fromPartial(
            new com.pijava.ai.message.AssistantMessage(
                "m1", List.of(new ContentBlock.TextContent("hi")), null, StopReason.STOP,
                "anthropic-messages", "anthropic", "claude-sonnet-5", null, null, null,
                null, null, null, null, "msg_1", null));
    }
}
