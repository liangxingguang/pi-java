package com.pijava.agent.harness;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

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
 * Phase 23 step 5: every in-flight entry write emits its {@code write_deferred}
 * record (docs/23 D3). A lane-view entry write while a run is in flight is
 * deferred; while the lane is idle it is a direct append, so the prompt that
 * starts a run is not recorded as deferred.
 */
class WriteDeferredEmissionTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "test-model");

    private static final AssistantMessage DONE = AssistantMessage.empty()
        .withContent(List.of(new ContentBlock.TextContent("done")))
        .withStopReason("stop");

    private static StreamFn simpleStreamFn() {
        return (messages, model, options) -> StreamIterator.from(List.of(
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
        return (messages, model, options) -> {
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

    private static List<LaneRecord> ofType(AgentHarness h, String lane, Class<?> type) {
        return h.snapshot(lane).records().stream().filter(type::isInstance).toList();
    }

    private static void drive(AgentHarness h, String lane) {
        var action = h.peekAction(lane);
        while (action != null) {
            action = h.executeAction(lane, action);
        }
    }

    @Test
    void runStartUserPromptIsNotRecordedAsDeferred() {
        var h = harness(simpleStreamFn(), null);
        h.run("default", "hello");
        drive(h, "default");

        // run 起始时 lane 仍为 IDLE，属直接 append（docs/23 D3）：启动 run 的
        // user prompt 条目绝不落 write_deferred。断言的是「不指向该条目」而非
        // 「零条记录」——同一次 drive 期间 run 自身产出的 assistant 回复必须是
        // 延迟写入（见下一个用例 hasSize(1)），两者不可兼得。
        String promptId = h.snapshot("default").transcript().get(0).id();

        assertThat(ofType(h, "default", LaneRecord.WriteDeferred.class))
            .extracting(record -> ((LaneRecord.WriteDeferred) record).target().entry().id())
            .doesNotContain(promptId);
    }

    @Test
    void assistantReplyProducedMidRunIsRecordedAsDeferred() {
        var h = harness(simpleStreamFn(), null);
        h.run("default", "hello");
        drive(h, "default");

        var deferred = ofType(h, "default", LaneRecord.WriteDeferred.class);
        assertThat(deferred).hasSize(1);
        assertThat(((LaneRecord.WriteDeferred) deferred.get(0)).runId()).isNotEmpty();
    }

    @Test
    void toolResultsAndMidRunSteerAreRecordedAsDeferred() {
        var registry = new ToolRegistry(null);
        registry.register(echoTool());
        var h = harness(toolUseThenStopStreamFn("echo"), registry);
        var action = h.run("default", "go");
        while (action != null && !(action instanceof Action.ExecuteTool)) {
            action = h.executeAction("default", action);
        }
        h.steer("default", "steer mid-run");
        var next = action;
        while (next != null) {
            next = h.executeAction("default", next);
        }

        // 工具结果条目 + 中途 steer 注入条目 + 第二轮 assistant 回复
        assertThat(ofType(h, "default", LaneRecord.WriteDeferred.class).size())
            .isGreaterThanOrEqualTo(3);
    }
}
