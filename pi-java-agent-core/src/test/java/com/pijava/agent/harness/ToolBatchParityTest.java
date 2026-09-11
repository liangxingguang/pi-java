package com.pijava.agent.harness;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

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
 * pi alignment for multi-tool turns (pi {@code agent-loop.ts}):
 * <ul>
 *   <li>{@code hasSequentialToolCall} (:419-424) — one tool declaring
 *       {@link ExecutionMode#Sequential} demotes the whole batch to sequential
 *       execution; the batch is never split.</li>
 *   <li>{@code shouldTerminateToolBatch} (:582) — a batch ends the run only when
 *       <em>every</em> call asks to terminate, not when any one does.</li>
 * </ul>
 */
class ToolBatchParityTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "test-model");

    /** A no-op tool with a declared execution mode and a terminate hint. */
    private static AgentTool<Map<String, Object>, Void> tool(
            String name, ExecutionMode mode, boolean terminate) {
        return new AgentTool<>() {
            @Override public String name() { return name; }
            @Override public String label() { return name; }
            @Override public String description() { return "test tool " + name; }
            @Override public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
            @Override public ExecutionMode executionMode() { return mode; }
            @Override
            public ToolResult<Void> execute(String toolCallId, Map<String, Object> params,
                                           AbortSignal signal,
                                           ToolUpdateCallback<Void> onUpdate,
                                           ToolContext context) {
                return new ToolResult<>(
                    List.of(new ContentBlock.TextContent(name + " ok")),
                    null, null, terminate, List.of());
            }
        };
    }

    /**
     * First round answers with two tool calls for {@code first}/{@code second};
     * every later round answers with plain text so the run can finish.
     */
    private static StreamFn twoToolCallsThenText(String first, String second) {
        var round = new AtomicInteger();
        return (messages, model, options) -> {
            if (round.getAndIncrement() == 0) {
                var partial = AssistantMessage.empty()
                    .withContent(List.of(
                        new ContentBlock.ToolUseContent("c1", first, Map.of()),
                        new ContentBlock.ToolUseContent("c2", second, Map.of())))
                    .withStopReason("tool_use");
                return StreamIterator.from(List.of(
                    new StreamEvent.Start(AssistantMessage.empty()),
                    new StreamEvent.ToolCallEnd(0, "c1", first, Map.of(), partial),
                    new StreamEvent.ToolCallEnd(1, "c2", second, Map.of(), partial),
                    new StreamEvent.StreamDone("tool_use", null, partial)));
            }
            var done = AssistantMessage.empty()
                .withContent(List.of(new ContentBlock.TextContent("done")))
                .withStopReason("stop");
            return StreamIterator.from(List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.TextEnd(0, "done", done),
                new StreamEvent.StreamDone("stop", null, done)));
        };
    }

    private static AgentHarness harness(StreamFn fn, List<AgentTool<?, ?>> tools) {
        var registry = new ToolRegistry(null);
        tools.forEach(registry::register);
        return AgentHarness.create(HarnessConfig.builder()
            .streamFn(fn)
            .model(MODEL)
            .thinkingLevel(ModelThinkingLevel.off())
            .activeTools(Set.copyOf(tools))
            .toolRegistry(registry)
            .toolExecution(ToolExecution.defaultMode())
            .build());
    }

    /** Drive until the first tool action surfaces (or give up after 10 steps). */
    private static Action firstToolAction(AgentHarness h) {
        Action action = h.run("default", "hi");
        for (int i = 0; i < 10 && action != null; i++) {
            if (action instanceof Action.ExecuteTool
                    || action instanceof Action.ExecuteToolBatch) {
                return action;
            }
            action = h.executeAction("default", action);
        }
        return action;
    }

    @Test
    void parallelToolsFormOneBatch() {
        var h = harness(twoToolCallsThenText("read", "glob"), List.<AgentTool<?, ?>>of(
            tool("read", new ExecutionMode.Parallel(), false),
            tool("glob", new ExecutionMode.Parallel(), false)));

        var action = firstToolAction(h);
        assertThat(action).isInstanceOf(Action.ExecuteToolBatch.class);
        assertThat(((Action.ExecuteToolBatch) action).calls()).hasSize(2);
    }

    @Test
    void oneSequentialToolDemotesTheWholeBatch() {
        var h = harness(twoToolCallsThenText("bash", "read"), List.<AgentTool<?, ?>>of(
            tool("bash", new ExecutionMode.Sequential(), false),
            tool("read", new ExecutionMode.Parallel(), false)));

        // pi: hasSequentialToolCall → executeToolCallsSequential, never a split batch
        assertThat(firstToolAction(h)).isInstanceOf(Action.ExecuteTool.class);
    }

    @Test
    void singleTerminatingCallDoesNotEndTheRun() {
        var h = harness(twoToolCallsThenText("read", "glob"), List.<AgentTool<?, ?>>of(
            tool("read", new ExecutionMode.Parallel(), true),
            tool("glob", new ExecutionMode.Parallel(), false)));

        var action = firstToolAction(h);
        assertThat(action).isInstanceOf(Action.ExecuteToolBatch.class);
        assertThat(h.executeAction("default", action)).isNotNull();
    }

    @Test
    void everyCallTerminatingEndsTheRun() {
        var h = harness(twoToolCallsThenText("read", "glob"), List.<AgentTool<?, ?>>of(
            tool("read", new ExecutionMode.Parallel(), true),
            tool("glob", new ExecutionMode.Parallel(), true)));

        var action = firstToolAction(h);
        assertThat(action).isInstanceOf(Action.ExecuteToolBatch.class);
        // L3: the operation terminal is single-sourced in FinishOperation. A
        // fully-terminating batch yields a stop-finish action whose execution
        // ends the drive (returns null) after writing operation_finished.
        var finish = h.executeAction("default", action);
        assertThat(finish).isEqualTo(new Action.FinishOperation("completed", true));
        assertThat(h.executeAction("default", finish)).isNull();
    }
}
