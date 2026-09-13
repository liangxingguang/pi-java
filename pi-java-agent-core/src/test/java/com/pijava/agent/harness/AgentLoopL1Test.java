package com.pijava.agent.harness;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import com.pijava.agent.hook.BeforeToolResult;
import com.pijava.agent.record.LaneRecord;
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
 * Agent-loop L1 regression tests (docs/20 §3):
 * ③ length-stop truncation protection — truncated tool calls are failed back
 *    into the transcript and never executed;
 * ④ runtime schema validation — malformed arguments become error results fed
 *    back to the model instead of reaching the tool;
 * ⑤ before_tool deny-and-terminate channel — a denying hook can end the run.
 */
class AgentLoopL1Test {

    private static final ModelId<?> MODEL = ModelId.of("faux", "test-model");

    /** A tool that records how many times it actually executed. */
    private static AgentTool<String, Void> recordingTool(String name, Map<String, Object> schema,
                                                         AtomicInteger executed) {
        return new AgentTool<>() {
            @Override public String name() { return name; }
            @Override public String label() { return name; }
            @Override public String description() { return "Test tool " + name; }
            @Override public Map<String, Object> inputSchema() { return schema; }
            @Override public ExecutionMode executionMode() { return new ExecutionMode.Sequential(); }
            @Override public String prepareArguments(Map<String, Object> raw) {
                return String.valueOf(raw.get("text"));
            }
            @Override public ToolResult<Void> execute(String id, String params, AbortSignal signal,
                    ToolUpdateCallback<Void> onUpdate, ToolContext ctx) {
                executed.incrementAndGet();
                return ToolResult.success(params);
            }
        };
    }

    /**
     * StreamFn with a scripted list of partials: each call returns the next
     * one, so a test can drive length-stop → model retries → final stop.
     */
    private static StreamFn scriptedStreamFn(List<AssistantMessage> partials) {
        var index = new AtomicInteger();
        return (model, context, options) -> {
            var partial = partials.get(Math.min(index.incrementAndGet() - 1,
                partials.size() - 1));
            return StreamIterator.from(List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.TextEnd(0, "x", partial),
                new StreamEvent.StreamDone(partial.stopReason(), null, partial)));
        };
    }

    private static AgentHarness harness(ToolRegistry registry, StreamFn streamFn) {
        return AgentHarness.create(new HarnessConfig(
                streamFn, MODEL, ModelThinkingLevel.off(), "",
                Set.of(), 200_000, registry, null, null,
                DriveMode.MANUAL, null, Map.of(),
                com.pijava.ai.http.RetryPolicy.defaultPolicy(),
                com.pijava.telemetry.NoopTelemetryContext.INSTANCE,
                com.pijava.ai.thinking.ThinkingLevelMap.empty(),
                QueueMode.defaultMode(), QueueMode.defaultMode(), ToolExecution.defaultMode(),
                event -> { }));
    }

    private static void drive(AgentHarness h, String prompt) {
        h.run("default", prompt);
        var action = h.peekAction("default");
        while (action != null) {
            action = h.executeAction("default", action);
        }
    }

    private static AssistantMessage toolUsePartial(String stopReason, String toolName,
                                                   Map<String, Object> args) {
        return AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.ToolUseContent("call-1", toolName, args)))
            .withStopReason(stopReason);
    }

    // ── ③ length-stop truncation protection ─────────────────

    @Test
    void lengthStopFailsToolCallsBackWithoutExecutingThem() {
        var executed = new AtomicInteger();
        var registry = new ToolRegistry(null);
        registry.register(recordingTool("echo", Map.of(), executed));
        // First response: tool_use + length stop (truncated arguments). Second:
        // plain stop — the model "retried" after seeing the failure.
        var lengthStop = toolUsePartial("length", "echo", Map.of("text", "hello"));
        var finalStop = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent("done")))
            .withStopReason("stop");
        var h = harness(registry, scriptedStreamFn(List.of(lengthStop, finalStop)));

        drive(h, "truncated call");

        // The truncated tool call must never reach the tool.
        assertThat(executed.get()).isZero();

        // The run must continue (the model retried) rather than terminate.
        var lane = h.snapshot("default");
        var toolEntries = lane.transcript().stream()
            .filter(e -> e instanceof com.pijava.agent.entry.Entry.Message m
                && "tool".equals(m.message().role()))
            .map(e -> (com.pijava.agent.entry.Entry.Message) e)
            .toList();
        // One failed tool result fed back after the length stop.
        assertThat(toolEntries).hasSize(1);
        var msg = (com.pijava.ai.message.Message.ToolResultMessage) toolEntries.get(0).message();
        assertThat(msg.content()).hasSize(1);
        assertThat(((ContentBlock.TextContent) msg.content().get(0)).text())
            .contains("hit the output token limit");

        // No ToolFinished record was written (nothing executed).
        assertThat(lane.records().stream()
            .noneMatch(r -> r instanceof LaneRecord.ToolFinished)).isTrue();

        // Run ended normally on the retry.
        assertThat(h.lastAssistantMessage().stopReason()).isEqualTo("stop");
    }

    // ── ④ runtime schema validation ─────────────────────────

    @Test
    void invalidArgumentsAreFedBackAsErrorsInsteadOfExecuted() {
        var executed = new AtomicInteger();
        var schema = Map.<String, Object>of(
            "type", "object",
            "properties", Map.of("text", Map.of("type", "string")),
            "required", List.of("text"));
        var registry = new ToolRegistry(null);
        registry.register(recordingTool("echo", schema, executed));
        // First response: tool_use with a non-string "text" (violates schema).
        var badCall = toolUsePartial("tool_use", "echo", Map.of("text", 123));
        var finalStop = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent("done")))
            .withStopReason("stop");
        var h = harness(registry, scriptedStreamFn(List.of(badCall, finalStop)));

        drive(h, "bad args");

        // The invalid call must never reach the tool.
        assertThat(executed.get()).isZero();

        var lane = h.snapshot("default");
        var toolEntries = lane.transcript().stream()
            .filter(e -> e instanceof com.pijava.agent.entry.Entry.Message m
                && "tool".equals(m.message().role()))
            .map(e -> (com.pijava.agent.entry.Entry.Message) e)
            .toList();
        assertThat(toolEntries).hasSize(1);
        var msg = (com.pijava.ai.message.Message.ToolResultMessage) toolEntries.get(0).message();
        assertThat(msg.isError()).isTrue();
        assertThat(((ContentBlock.TextContent) msg.content().get(0)).text())
            .contains("Tool error");

        // Run continued and ended normally.
        assertThat(h.lastAssistantMessage().stopReason()).isEqualTo("stop");
    }

    @Test
    void validArgumentsStillExecute() {
        var executed = new AtomicInteger();
        var schema = Map.<String, Object>of(
            "type", "object",
            "properties", Map.of("text", Map.of("type", "string")),
            "required", List.of("text"));
        var registry = new ToolRegistry(null);
        registry.register(recordingTool("echo", schema, executed));
        var goodCall = toolUsePartial("tool_use", "echo", Map.of("text", "hello"));
        var finalStop = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent("done")))
            .withStopReason("stop");
        var h = harness(registry, scriptedStreamFn(List.of(goodCall, finalStop)));

        drive(h, "good args");

        assertThat(executed.get()).isEqualTo(1);
        assertThat(h.lastAssistantMessage().stopReason()).isEqualTo("stop");
    }

    // ── ⑤ before_tool deny-and-terminate ────────────────────

    @Test
    void denyAndTerminateEndsTheRun() {
        var registry = new ToolRegistry(null);
        registry.register(recordingTool("echo", Map.of(), new AtomicInteger()));
        var stop = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent("done")))
            .withStopReason("stop");
        // The tool_use is the ONLY response — the run must end on the deny.
        var h = harness(registry, scriptedStreamFn(List.of(
            toolUsePartial("tool_use", "echo", Map.of("text", "hello")), stop)));
        h.hookSystem().onBeforeTool("default", ctx -> BeforeToolResult.denyAndTerminate("not allowed"));

        drive(h, "denied call");

        // The run must terminate without looping on the same tool call.
        var lane = h.snapshot("default");
        var toolEntries = lane.transcript().stream()
            .filter(e -> e instanceof com.pijava.agent.entry.Entry.Message m
                && "tool".equals(m.message().role()))
            .map(e -> (com.pijava.agent.entry.Entry.Message) e)
            .toList();
        // The denied result is wrapped in a ToolResultContent carrying the
        // call id/name; its inner block is the text reason.
        assertThat(toolEntries).hasSize(1);
        var msg = (com.pijava.ai.message.Message.ToolResultMessage) toolEntries.get(0).message();
        assertThat(msg.content()).hasSize(1);
        var block = (ContentBlock.ToolResultContent) msg.content().get(0);
        assertThat(block.toolUseId()).isEqualTo("call-1");
        assertThat(block.toolName()).isEqualTo("echo");
        assertThat(((ContentBlock.TextContent) block.content().get(0)).text())
            .contains("denied");

        // ToolFinished records the terminate flag so the run ended.
        var finished = lane.records().stream()
            .filter(r -> r instanceof LaneRecord.ToolFinished)
            .map(r -> (LaneRecord.ToolFinished) r)
            .toList();
        assertThat(finished).hasSize(1);
        assertThat(finished.get(0).terminate()).isTrue();
        assertThat(finished.get(0).isError()).isTrue();
    }

    @Test
    void plainDenyDoesNotTerminate() {
        var executed = new AtomicInteger();
        var registry = new ToolRegistry(null);
        registry.register(recordingTool("echo", Map.of(), executed));
        // Deny once, then allow — the run continues to the second attempt.
        var denied = new AtomicBoolean();
        var stop = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent("done")))
            .withStopReason("stop");
        var h = harness(registry, scriptedStreamFn(List.of(
            toolUsePartial("tool_use", "echo", Map.of("text", "hello")),
            toolUsePartial("tool_use", "echo", Map.of("text", "hello")),
            stop)));
        h.hookSystem().onBeforeTool("default", ctx -> {
            if (denied.compareAndSet(false, true)) {
                return BeforeToolResult.deny("first time");
            }
            return BeforeToolResult.allow();
        });

        drive(h, "deny then allow");

        // Second attempt executed.
        assertThat(executed.get()).isEqualTo(1);
        assertThat(h.lastAssistantMessage().stopReason()).isEqualTo("stop");
    }
}
