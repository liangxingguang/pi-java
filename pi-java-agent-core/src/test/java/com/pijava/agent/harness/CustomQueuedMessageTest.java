package com.pijava.agent.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.pijava.agent.entry.CustomMessageContent;
import com.pijava.agent.entry.Entry;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 26（docs/37 T1，裁决⑧）：custom 队列项——steer/followUp 携扩展 custom 消息，
 * 排空时以 user 投影进 LLM 上下文（pi {@code convertToLlm} custom case），落盘为
 * {@link Entry.CustomMessage}（pi agent-session.ts:1147-1155 的 message_end 臂对位）。
 */
class CustomQueuedMessageTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "test-model");

    /** 捕获每次请求的上下文消息。 */
    private static final class Capturing {
        final List<List<Message>> requests = new ArrayList<>();

        AgentHarness harness() {
            StreamFn sf = (model, context, options) -> {
                requests.add(List.copyOf(context.messages()));
                var partial = AssistantMessage.empty()
                    .withContent(List.of(new ContentBlock.TextContent("done")))
                    .withStopReason(StopReason.STOP);
                return StreamIterator.from(List.of(
                    new StreamEvent.Start(AssistantMessage.empty()),
                    new StreamEvent.TextEnd(0, "done", partial),
                    new StreamEvent.StreamDone(StopReason.STOP, null, partial)));
            };
            return AgentHarness.create(new HarnessConfig(
                sf, MODEL, ModelThinkingLevel.off(), "",
                Set.of(), 200_000, null, null, null,
                null, java.util.Map.of(),
                com.pijava.telemetry.NoopTelemetryContext.INSTANCE,
                com.pijava.ai.thinking.ThinkingLevelMap.empty(),
                QueueMode.defaultMode(), QueueMode.defaultMode(), ToolExecution.defaultMode(),
                event -> { }));
        }
    }

    private static List<Entry.CustomMessage> customEntries(AgentHarness h, String lane) {
        return h.snapshot(lane).transcript().stream()
            .filter(e -> e instanceof Entry.CustomMessage)
            .map(e -> (Entry.CustomMessage) e)
            .toList();
    }

    @Test
    void steeredCustomMessageRunsAsUserProjectionAndPersistsAsCustomEntry() {
        var cap = new Capturing();
        var h = cap.harness();
        h.steerCustom("default", "jira", CustomMessageContent.of("ticket ABC-7"), true,
            Map.of("k", "v"));

        h.prompt("go");

        // LLM 上下文：custom 内容以 user 投影出现（一项一条，不合并）。
        var secondRequest = cap.requests.get(cap.requests.size() - 1);
        assertThat(secondRequest.stream()
            .filter(m -> m instanceof Message.UserMessage)
            .map(m -> ((ContentBlock.TextContent) m.content().get(0)).text()))
            .contains("ticket ABC-7");

        // 落盘：custom_message 条目携载荷，不是 user 消息条目。
        var customs = customEntries(h, "default");
        assertThat(customs).hasSize(1);
        assertThat(customs.get(0).customType()).isEqualTo("jira");
        assertThat(customs.get(0).display()).isTrue();
        assertThat(customs.get(0).details()).containsEntry("k", "v");
        assertThat(customs.get(0).timestamp()).isNotNull();

        // user 条目只有起手 prompt 一条（custom 不落成 user entry）。
        var userEntries = h.snapshot("default").transcript().stream()
            .filter(e -> e instanceof Entry.Message m && "user".equals(m.message().role()))
            .toList();
        assertThat(userEntries).hasSize(1);
    }

    @Test
    void customItemContentNullNormalizesToEmptyBlocks() {
        var cap = new Capturing();
        var h = cap.harness();
        h.steerCustom("default", "bare", null, false, null);

        h.prompt("go");

        var customs = customEntries(h, "default");
        assertThat(customs).hasSize(1);
        assertThat(((CustomMessageContent.Blocks) customs.get(0).content()).blocks()).isEmpty();
        // user 投影同样进上下文（空内容块表）。
        assertThat(cap.requests.get(cap.requests.size() - 1).stream()
            .filter(m -> m instanceof Message.UserMessage)
            .map(m -> (Message.UserMessage) m)
            .anyMatch(m -> m.content().isEmpty()))
            .isTrue();
    }

    @Test
    void followUpCustomStartsNextRun() throws Exception {
        var cap = new Capturing();
        var h = cap.harness();
        // 在第一次助手回复仍在途时入队（流事件监听器跑在运行线程上，
        // QueueSchedulingTest.followUpQueuedDuringRunStartsNextRun 同款机制）。
        var queued = new java.util.concurrent.atomic.AtomicBoolean();
        var registration = h.onStreamEvent(e -> {
            if (e instanceof StreamEvent.TextEnd && queued.compareAndSet(false, true)) {
                h.followUpCustom("default", "note", CustomMessageContent.of("later"), false, null);
            }
        });
        try {
            h.prompt("go");
        } finally {
            registration.close();
        }

        assertThat(queued).isTrue();
        var customs = customEntries(h, "default");
        assertThat(customs).hasSize(1);
        assertThat(customs.get(0).customType()).isEqualTo("note");
        // 第二轮确被驱动（两次 provider 请求）。
        assertThat(cap.requests.size()).isGreaterThanOrEqualTo(2);
    }
}
