package com.pijava.agent.harness;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.agent.context.ContextUsage;
import com.pijava.agent.entry.Entry;
import com.pijava.ai.Usage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * B293：{@link SnapshotService} 的 context usage 计算，对位 pi
 * {@code getContextUsage()}（{@code agent-session.ts:4416-4459}）。
 */
class SnapshotServiceContextUsageTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "context-model");
    private static final int WINDOW = 272_000;

    private LaneState lane;

    @BeforeEach
    void setUp() {
        lane = new LaneState();
        lane.laneName = "default";
        lane.model = MODEL;
    }

    private SnapshotService service(int window, CompactionSettings settings) {
        return new SnapshotService(lane, new HarnessEventBus(),
            new ExecutionContext.TokenCounter(),
            () -> MODEL.modelName(), Set::of,
            ignored -> window, () -> settings);
    }

    private SnapshotService service() {
        return service(WINDOW, CompactionSettings.defaults());
    }

    private SessionSnapshot snapshot(SnapshotService service) {
        try (var watch = service.watchSession()) {
            return watch.current();
        }
    }

    private static Entry user(String id, String parentId) {
        return new Entry.Message(id, 0, parentId, Instant.ofEpochMilli(1),
            new Message.UserMessage(List.of(new ContentBlock.TextContent("hello"))), false);
    }

    private static Entry assistant(String id, String parentId, double tokens) {
        var usage = new Usage(0, 0, 0, 0, null, null, tokens, Usage.Cost.zero());
        return new Entry.Message(id, 0, parentId, Instant.ofEpochMilli(1),
            new Message.AssistantMessage(
                List.of(new ContentBlock.TextContent("hi")), StopReason.STOP, null,
                null, null, null, usage, null, null, null), false);
    }

    private static Entry compaction(String id, String parentId, String firstKeptEntryId) {
        return new Entry.Compaction(id, 0, parentId, Instant.ofEpochMilli(1),
            "summary", firstKeptEntryId, List.of(), 0, Map.of(), null, false);
    }

    @Test
    void emptySessionReportsZeroUsageAndTheWindow() {
        ContextUsage usage = snapshot(service()).contextUsage();

        assertThat(usage).isNotNull();
        assertThat(usage.tokens()).isEqualTo(0.0);
        assertThat(usage.contextWindow()).isEqualTo(WINDOW);
        assertThat(usage.percent()).isEqualTo(0.0);
    }

    @Test
    void assistantUsageDrivesTokensAndPercent() {
        lane.transcript.add(user("e1", null));
        lane.transcript.add(assistant("e2", "e1", 1000));

        ContextUsage usage = snapshot(service()).contextUsage();

        assertThat(usage).isNotNull();
        assertThat(usage.tokens()).isEqualTo(1000.0);
        assertThat(usage.percent()).isCloseTo(1000.0 / WINDOW * 100, within(1e-9));
    }

    @Test
    void compactionWithoutPostCompactionAssistantUsageIsUnknown() {
        lane.transcript.add(user("e1", null));
        lane.transcript.add(assistant("e2", "e1", 1000));
        lane.transcript.add(compaction("c1", "e2", "e1"));

        ContextUsage usage = snapshot(service()).contextUsage();

        assertThat(usage).isNotNull();
        assertThat(usage.tokens()).isNull();
        assertThat(usage.percent()).isNull();
        assertThat(usage.contextWindow()).isEqualTo(WINDOW);
    }

    @Test
    void postCompactionAssistantUsageRestoresTheEstimate() {
        lane.transcript.add(user("e1", null));
        lane.transcript.add(compaction("c1", "e1", null));
        lane.transcript.add(assistant("e2", "c1", 500));

        ContextUsage usage = snapshot(service()).contextUsage();

        assertThat(usage).isNotNull();
        assertThat(usage.tokens()).isEqualTo(500.0);
        assertThat(usage.percent()).isCloseTo(500.0 / WINDOW * 100, within(1e-9));
    }

    @Test
    void zeroWindowMeansNoContextUsage() {
        assertThat(snapshot(service(0, CompactionSettings.defaults())).contextUsage()).isNull();
    }

    @Test
    void autoCompactReflectsTheEnabledSetting() {
        assertThat(snapshot(service()).autoCompact()).isTrue();
        assertThat(snapshot(service(WINDOW, null)).autoCompact()).isFalse();
    }
}
