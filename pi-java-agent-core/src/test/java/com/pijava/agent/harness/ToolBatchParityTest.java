package com.pijava.agent.harness;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.pijava.agent.entry.Entry;
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
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * pi alignment for multi-tool turns (pi {@code agent-loop.ts}):
 * <ul>
 *   <li>{@code shouldTerminateToolBatch} (:582) — a batch ends the run only when
 *       <em>every</em> call asks to terminate, not when any one does.</li>
 * </ul>
 *
 * <p>批次的形态（并行一批 vs. 一个 Sequential 调用把整批降级）由 {@code PiToolRunner}
 * 决定，不再经由 {@code Action} 暴露给调用方，因此这里只断言可观察到的运行终局。</p>
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
        return (model, context, options) -> {
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

    /** 助手文本块，按转录顺序（工具轮的助手消息只有 tool_use，不带文本）。 */
    private static List<String> assistantTexts(AgentHarness h) {
        return h.snapshot("default").transcript().stream()
            .filter(Entry.Message.class::isInstance)
            .map(e -> (Entry.Message) e)
            .filter(e -> "assistant".equals(e.message().role()))
            .flatMap(e -> e.message().content().stream())
            .filter(ContentBlock.TextContent.class::isInstance)
            .map(b -> ((ContentBlock.TextContent) b).text())
            .toList();
    }

    /** 转录末条 Entry。 */
    private static Entry lastEntry(AgentHarness h) {
        var transcript = h.snapshot("default").transcript();
        return transcript.get(transcript.size() - 1);
    }

    @Test
    void singleTerminatingCallDoesNotEndTheRun() {
        var h = harness(twoToolCallsThenText("read", "glob"), List.<AgentTool<?, ?>>of(
            tool("read", new ExecutionMode.Parallel(), true),
            tool("glob", new ExecutionMode.Parallel(), false)));

        h.prompt("hi");

        // 只有部分调用要求终止 ⇒ 运行继续，模型还有第二轮文本回复。
        assertThat(assistantTexts(h)).contains("done");
    }

    @Test
    void everyCallTerminatingEndsTheRun() {
        var h = harness(twoToolCallsThenText("read", "glob"), List.<AgentTool<?, ?>>of(
            tool("read", new ExecutionMode.Parallel(), true),
            tool("glob", new ExecutionMode.Parallel(), true)));

        h.prompt("hi");

        // pi: 全部要求终止 ⇒ 批次结束即收口，第二轮文本回复不再发生。
        assertThat(assistantTexts(h)).isEmpty();
        assertThat(lastEntry(h)).isInstanceOf(Entry.Message.class);
        assertThat(((Entry.Message) lastEntry(h)).message())
            .isInstanceOf(Message.ToolResultMessage.class);
    }
}
