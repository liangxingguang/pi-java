package com.pijava.agent.harness;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.record.QueueKind;
import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolRegistry;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 21 step 2: every queue lifecycle transition emits its record
 * (docs/21 §3.2, D10). Covers the three enqueue queues, explicit
 * cancellation, drain consumption, and the mid-run steer injection path that
 * bypasses {@code ConsumeQueueItem}.
 */
class QueueRecordEmissionTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "test-model");

    private static final AssistantMessage DONE = AssistantMessage.empty()
        .withContent(List.of(new ContentBlock.TextContent("done")))
        .withStopReason("stop");

    private static StreamFn simpleStreamFn() {
        return (model, context, options) -> StreamIterator.from(List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextEnd(0, "done", DONE),
            new StreamEvent.StreamDone("stop", null, DONE)));
    }

    /** First LLM call asks for a tool, later calls stop — one tool round. */
    private static StreamFn toolUseThenStopStreamFn(String toolName) {
        var toolUse = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.ToolUseContent(
                "call-1", toolName, Map.of("text", "hello"))))
            .withStopReason("tool_use");
        var calls = new AtomicInteger();
        return (model, context, options) -> {
            var partial = calls.incrementAndGet() == 1 ? toolUse : DONE;
            return StreamIterator.from(List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.TextEnd(0, "x", partial),
                new StreamEvent.StreamDone(partial.stopReason(), null, partial)));
        };
    }

    private static AgentTool<String, Void> echoTool() {
        return new AgentTool<>() {
            @Override public String name() { return "echo"; }
            @Override public String label() { return "echo"; }
            @Override public String description() { return "Echo input"; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public ExecutionMode executionMode() { return new ExecutionMode.Sequential(); }
            @Override public String prepareArguments(Map<String, Object> raw) {
                return String.valueOf(raw.get("text"));
            }
            @Override public ToolResult<Void> execute(String id, String params, AbortSignal signal,
                    ToolUpdateCallback<Void> onUpdate, ToolContext ctx) {
                return ToolResult.success(params);
            }
        };
    }

    private static AgentHarness harness(StreamFn sf, ToolRegistry registry) {
        return AgentHarness.create(new HarnessConfig(
            sf, MODEL, ModelThinkingLevel.off(), "",
            Set.of(), 200_000, registry, null, null,
            DriveMode.MANUAL, null, Map.of(),
            com.pijava.ai.http.RetryPolicy.defaultPolicy(),
            com.pijava.telemetry.NoopTelemetryContext.INSTANCE,
            com.pijava.ai.thinking.ThinkingLevelMap.empty(),
            QueueMode.defaultMode(), QueueMode.defaultMode(), ToolExecution.defaultMode(),
            event -> { }));
    }

    private static AgentHarness harness() {
        return harness(simpleStreamFn(), null);
    }

    private static List<LaneRecord> ofType(AgentHarness h, String lane, Class<?> type) {
        return h.snapshot(lane).records().stream().filter(type::isInstance).toList();
    }

    private static void drive(AgentHarness h, String lane) {
        var action = h.peekAction(lane);
        while (action != null) {
            action = h.executeAction(lane, action);
        }
    }

    private static List<String> userText(AgentHarness h, String lane) {
        return h.snapshot(lane).transcript().stream()
            .filter(e -> e instanceof Entry.Message m && "user".equals(m.message().role()))
            .map(e -> (Entry.Message) e)
            .flatMap(e -> e.message().content().stream())
            .filter(ContentBlock.TextContent.class::isInstance)
            .map(b -> ((ContentBlock.TextContent) b).text())
            .toList();
    }

    @Test
    void steerEnqueueEmitsQueueEnqueued() {
        var h = harness();
        h.steer("default", "steer me");

        var enqueued = ofType(h, "default", LaneRecord.QueueEnqueued.class);
        assertThat(enqueued).hasSize(1);
        var record = (LaneRecord.QueueEnqueued) enqueued.get(0);
        assertThat(record.queue()).isEqualTo(QueueKind.STEER);
        assertThat(record.lane()).isEqualTo("default");
        // The provisioned target id is the item's queue sequence number.
        assertThat(record.target().entry().id()).isEqualTo("0");
    }

    @Test
    void followUpAndNextRunEnqueueEmitTheirOwnQueueKind() {
        var h = harness();
        h.followUp("default", "a");
        h.nextRun("default", "b");

        var kinds = ofType(h, "default", LaneRecord.QueueEnqueued.class).stream()
            .map(r -> ((LaneRecord.QueueEnqueued) r).queue())
            .toList();
        assertThat(kinds).containsExactly(QueueKind.FOLLOW_UP, QueueKind.NEXT_RUN);
    }

    @Test
    void cancelQueuedEmitsQueueCancelledForEachItem() {
        var h = harness();
        h.followUp("default", "first");
        h.followUp("default", "second");
        h.cancelQueued("default", "followUp");

        var cancelled = ofType(h, "default", LaneRecord.QueueCancelled.class);
        assertThat(cancelled).hasSize(2);
        // Each cancellation references the enqueued item by its queue seq.
        assertThat(cancelled.stream().map(r -> ((LaneRecord.QueueCancelled) r).entryId()))
            .containsExactly("0", "1");
    }

    @Test
    void drainingFollowUpEmitsQueueConsumed() {
        var h = harness();
        h.followUp("default", "queued");

        drive(h, "default");

        assertThat(userText(h, "default")).contains("queued");
        var consumed = ofType(h, "default", LaneRecord.QueueConsumed.class);
        assertThat(consumed).hasSize(1);
        var record = (LaneRecord.QueueConsumed) consumed.get(0);
        assertThat(record.queue()).isEqualTo(QueueKind.FOLLOW_UP);
        assertThat(record.targets()).hasSize(1);
        assertThat(record.runId()).isNotEmpty();
    }

    @Test
    void drainingNextRunEmitsQueueConsumedWithNextRunKind() {
        var h = harness();
        h.nextRun("default", "queued");

        drive(h, "default");

        // Regression: the nextRun drain used to be labelled "followUp".
        var consumed = ofType(h, "default", LaneRecord.QueueConsumed.class);
        assertThat(consumed).hasSize(1);
        assertThat(((LaneRecord.QueueConsumed) consumed.get(0)).queue())
            .isEqualTo(QueueKind.NEXT_RUN);
    }

    @Test
    void midRunSteerInjectionEmitsQueueConsumed() {
        var registry = new ToolRegistry(null);
        registry.register(echoTool());
        var h = harness(toolUseThenStopStreamFn("echo"), registry);

        // Drive until the tool call is the pending action: the next peek runs
        // in the assistant phase with no pending tool calls, which is where
        // queued steering is injected (bypassing ConsumeQueueItem).
        var action = h.run("default", "first prompt");
        while (action != null && !(action instanceof Action.ExecuteTool)) {
            action = h.executeAction("default", action);
        }
        assertThat(action).isInstanceOf(Action.ExecuteTool.class);

        h.steer("default", "steer mid-run");
        var next = action;
        while (next != null) {
            next = h.executeAction("default", next);
        }

        assertThat(userText(h, "default")).contains("steer mid-run");
        var consumed = ofType(h, "default", LaneRecord.QueueConsumed.class);
        assertThat(consumed).hasSize(1);
        assertThat(((LaneRecord.QueueConsumed) consumed.get(0)).queue())
            .isEqualTo(QueueKind.STEER);
    }

    @Test
    void consumedRecordsSurviveAcrossRuns() {
        var h = harness();
        h.followUp("default", "one");
        drive(h, "default");
        h.followUp("default", "two");
        drive(h, "default");

        // Append-only log (docs/21 D6): both runs' queue lifecycle stays visible.
        assertThat(ofType(h, "default", LaneRecord.QueueEnqueued.class)).hasSize(2);
        assertThat(ofType(h, "default", LaneRecord.QueueConsumed.class)).hasSize(2);
    }
}
