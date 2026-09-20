package com.pijava.ai.provider;

import java.util.ArrayList;
import java.util.List;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包⑪（docs/38，台账 A16）：**夹具与生产同路** —— faux 的消息也要带身份。
 *
 * <p><b>关键取证</b>：pi 的 faux <b>挂身份比 pi-java 还真</b> ——
 * {@code cloneMessage(message, api, provider, modelId)} 写
 * {@code api}/{@code provider}/{@code model}/{@code timestamp}/{@code usage} 五项
 * （{@code ai/src/providers/faux.ts:281-291}），deferred/error/aborted 三个构造器
 * 同样写全（{@code :293-319}）；默认 {@code api="faux"}、{@code provider="faux"}、
 * {@code model="faux-1"}（{@code :23-25}）。⇒ <b>pi-java 的 faux 才是异类</b>。</p>
 *
 * <p>为什么这件事重要：身份挂载点在 {@code AbstractChatApi}，而 pi-java 的
 * {@code FauxChatApi} <b>直接实现 {@code ChatApi}、绕过它</b> ⇒ 一切经 faux 驱动的
 * 夹具都在**另一个形状**上跑。包⑩ 的 B48（RPC 线在带 timestamp 的消息上**抛**）
 * 就是这么藏住的。</p>
 */
class FauxProviderIdentityTest {

    private static final ApiOptions OPTIONS = ApiOptions.defaults();
    private static final StreamRequest REQUEST = new StreamRequest(
        ModelId.of("faux", "test-model"),
        null,
        List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hello")))),
        List.of(), -1, -1, java.util.Map.of());

    @Test
    void streamedPartialsCarryTheFiveIdentityFields() {
        ChatApi api = FauxProvider.text("Hi").createApi(ChatApi.class, OPTIONS);

        var events = new ArrayList<StreamEvent>();
        try (var iter = api.streamBlocking(REQUEST, OPTIONS)) {
            while (iter.hasNext()) {
                events.add(iter.next());
            }
        }

        assertThat(events).isNotEmpty();
        assertThat(events).allSatisfy(e -> {
            var partial = e.partial();
            if (partial == null) {
                return;   // UsageInfo 允许不带快照
            }
            assertThat(partial.api()).as("api 字面量：pi 的 faux 是 \"faux\"").isEqualTo("faux");
            assertThat(partial.provider()).isEqualTo("faux");
            assertThat(partial.model()).isEqualTo("test-model");
            assertThat(partial.timestamp()).as("pi 的 faux 给每条消息盖 timestamp").isNotNull();
        });
    }

    @Test
    void oneStreamKeepsASingleTimestamp() {
        ChatApi api = FauxProvider.text("Hi").createApi(ChatApi.class, OPTIONS);

        var stamps = new ArrayList<java.time.Instant>();
        try (var iter = api.streamBlocking(REQUEST, OPTIONS)) {
            while (iter.hasNext()) {
                var partial = iter.next().partial();
                if (partial != null && partial.timestamp() != null) {
                    stamps.add(partial.timestamp());
                }
            }
        }

        assertThat(stamps).isNotEmpty();
        assertThat(stamps).as("一次流固定一个 timestamp（AbstractChatApi 的既有语义）")
            .allMatch(stamps.get(0)::equals);
    }

    @Test
    void terminalMessageCarriesUsageToo() {
        ChatApi api = FauxProvider.text("Hi").createApi(ChatApi.class, OPTIONS);

        try (var iter = api.streamBlocking(REQUEST, OPTIONS)) {
            while (iter.hasNext()) {
                if (iter.next() instanceof StreamEvent.StreamDone done) {
                    assertThat(done.partial().api()).isEqualTo("faux");
                    assertThat(done.partial().usage())
                        .as("终局消息带计量（兜零也算带）").isNotNull();
                    return;
                }
            }
        }
        throw new AssertionError("没有见到 StreamDone");
    }

    @Test
    void sendReturnsAFullyIdentifiedMessage() {
        ChatApi api = FauxProvider.text("Hi").createApi(ChatApi.class, OPTIONS);

        var sent = api.send(REQUEST, OPTIONS);

        assertThat(sent).isInstanceOf(Message.AssistantMessage.class);
        var assistant = (Message.AssistantMessage) sent;
        assertThat(assistant.api()).isEqualTo("faux");
        assertThat(assistant.provider()).isEqualTo("faux");
        assertThat(assistant.model()).isEqualTo("test-model");
        assertThat(assistant.timestamp()).isNotNull();
        assertThat(assistant.usage()).isNotNull();
    }
}
