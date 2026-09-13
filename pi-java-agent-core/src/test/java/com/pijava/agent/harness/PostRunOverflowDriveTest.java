package com.pijava.agent.harness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.pijava.agent.compaction.CompactionObserver;
import com.pijava.agent.compaction.CompactionResult;
import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.agent.entry.Entry;
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
 * 3c 的驱动侧端到端（pi {@code _runAgentPrompt:1109-1113} 的
 * {@code while(await _handlePostAgentRun()) await agent.continue()}）：
 * 一次 {@code prompt()} 在溢出错误后**自动续跑**，溢出恢复预算恰好用一次，
 * 成功响应把闩复位。跑在真实 {@link AgentHarness} + 剧本 {@link StreamFn} 上。
 */
class PostRunOverflowDriveTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "e2e-model");
    private static final CompactionSettings SETTINGS = new CompactionSettings(true, 10, 20_000);

    /** 第 N 次请求吃第 N 个剧本（越界即失败 —— 顺带钉住「不多跑一轮」）。 */
    private static StreamFn scripted(List<List<StreamEvent>> scripts) {
        var index = new AtomicInteger();
        return (model, context, options) -> {
            var script = scripts.get(index.getAndIncrement());
            return new StreamIterator() {
                private int i;

                @Override public boolean hasNext() { return i < script.size(); }
                @Override public StreamEvent next() { return script.get(i++); }
                @Override public void close() { }
            };
        };
    }

    private static List<StreamEvent> errorOverflowTurn() {
        var errPartial = AssistantMessage.empty()
            .withIdentity("faux-api", "faux", "e2e-model", Instant.now())
            .withStopReason("error")
            .withErrorMessage("prompt is too long: 210000 tokens > 200000 maximum");
        return List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.StreamError("error",
                new IllegalStateException("prompt is too long"), errPartial));
    }

    private static List<StreamEvent> textTurn(String text) {
        var partial = AssistantMessage.empty();
        var done = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent(text)))
            .withStopReason("stop");
        return List.of(
            new StreamEvent.Start(partial),
            new StreamEvent.TextDelta(0, text, done),
            new StreamEvent.StreamDone("stop", null, done));
    }

    private static final class Recorder implements CompactionObserver {
        final List<String> starts = new ArrayList<>();
        final List<String> ends = new ArrayList<>();

        @Override
        public void onStart(String reason) {
            starts.add(reason);
        }

        @Override
        public void onEnd(String reason, CompactionResult result, boolean aborted,
                          boolean willRetry, String errorMessage) {
            ends.add(reason + "|" + aborted + "|" + willRetry);
        }
    }

    private static AgentHarness harness(StreamFn sf, CompactionObserver obs) {
        return AgentHarness.create(HarnessConfig.builder()
            .streamFn(sf)
            .model(MODEL)
            .thinkingLevel(ModelThinkingLevel.off())
            .systemPrompt("sys")
            .activeTools(Set.of())
            .maxInputTokens(200_000)
            .contextWindow(id -> 200)
            .maxOutputTokens(id -> 8_192)
            .compactionSettings(SETTINGS)
            .compactionObserver(obs)
            .build());
    }

    @Test
    void overflowErrorContinuesTheSameDriveOnce() {
        var obs = new Recorder();
        var harness = harness(
            scripted(List.of(errorOverflowTurn(), textTurn("recovered"), textTurn("second"))),
            obs);

        var outcome = harness.prompt("hi");

        // 一次 prompt = 两个 pass（pi：continue 不另起驱动），id 各一。
        assertThat(outcome.passRunIds()).hasSize(2);
        assertThat(outcome.passRunIds().get(0)).isEqualTo(outcome.runId());
        // 溢出 ⇒ compact-and-retry：start/end 各一，end 挂着 willRetry。
        assertThat(obs.starts).containsExactly("overflow");
        assertThat(obs.ends).containsExactly("overflow|false|true");
        // 日志里压过了，且恢复轮的助手消息落了盘。
        assertThat(outcome.transcript()).anyMatch(Entry.Compaction.class::isInstance);
        var last = outcome.transcript().get(outcome.transcript().size() - 1);
        assertThat(((Entry.Message) last).message()).isInstanceOf(Message.AssistantMessage.class);
        var assistant = (Message.AssistantMessage) ((Entry.Message) last).message();
        assertThat(assistant.content().toString()).contains("recovered");
        // 成功收尾（stop）⇒ 闩复位（pi :694-696）：下一次 prompt 单 pass 跑完，
        // 不借上一轮的溢出预算、也不多烧一次请求。
        var second = harness.prompt("again");
        assertThat(second.passRunIds()).hasSize(1);
        assertThat(obs.starts).containsExactly("overflow"); // 没有第二次压缩
    }

    @Test
    void successfulRunStaysSinglePass() {
        var obs = new Recorder();
        var harness = harness(scripted(List.of(textTurn("fine"))), obs);
        var outcome = harness.prompt("hi");
        assertThat(outcome.passRunIds()).hasSize(1);
        assertThat(obs.starts).isEmpty();
    }
}
