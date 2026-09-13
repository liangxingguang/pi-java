package com.pijava.agent.harness.conformance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.pijava.agent.harness.Context;
import com.pijava.agent.harness.PiLoop;
import com.pijava.agent.harness.StreamFn;
import com.pijava.agent.harness.StreamOptions;
import com.pijava.agent.harness.ToolExecution;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevelMap;

/**
 * 用剧本驱动 {@link PiLoop} 并产出归一化帧序列 —— pi 侧 {@code runScript} 的对偶。
 *
 * <p>对标物是 pi 的 {@code agentLoop}，因此这里**直连** {@link PiLoop}，不经过
 * {@code PiLaneEngine}：后者带车道、记录日志与持久化，属于 pi 的 harness 层，
 * 不在 L5 差分的研究范围内（{@code docs/28 §5}）。</p>
 */
final class ConformanceRunner {

    private ConformanceRunner() {}

    /** 跑完一个剧本，返回逐帧归一化后的 JSON 行。 */
    static List<String> run(ConformanceScript script) {
        var normalizer = new FrameNormalizer();
        var frames = new ArrayList<String>();
        var driver = new Driver(script);
        var config = new PiLoop.Config(
            ModelId.of("faux", "conformance"),
            ModelThinkingLevel.off(),
            ThinkingLevelMap.empty(),
            "sequential".equals(script.toolExecution())
                ? new ToolExecution.Sequential() : new ToolExecution.Parallel(),
            driver::executeTool,
            driver::stream,
            null,
            driver::steering,
            driver::followUp,
            null,
            null,
            null,
            null);

        var prompt = new Message.UserMessage(
            List.of(new ContentBlock.TextContent(script.prompt())));
        // 系统提示与工具走 Context（pi 的 AgentContext），不在消息列表里。
        var context = new Context(script.systemPrompt(), new ArrayList<>(), toolDefs(script));
        PiLoop.run(List.of(prompt), context, config,
            event -> frames.add(normalizer.frame(event)));
        return List.copyOf(frames);
    }

    private static List<ToolDefinition> toolDefs(ConformanceScript script) {
        var defs = new ArrayList<ToolDefinition>();
        for (var tool : script.tools()) {
            defs.add(new ToolDefinition(tool.name(), "scripted tool " + tool.name(), Map.of()));
        }
        return List.copyOf(defs);
    }

    /** 一次剧本运行的驱动状态：流脚本游标、工具语义、队列注入。 */
    private static final class Driver {

        private final ConformanceScript script;
        private final Set<String> rejected = new HashSet<>();
        private final Map<String, ConformanceScript.Tool> toolsByName = new HashMap<>();
        private final List<ConformanceScript.Injection> steering;
        private final List<ConformanceScript.Injection> followUp;
        private final AtomicInteger callIds = new AtomicInteger();
        private int streamCalls;

        Driver(ConformanceScript script) {
            this.script = script;
            for (var tool : script.tools()) {
                toolsByName.put(tool.name(), tool);
                if (tool.reject()) {
                    rejected.add(tool.name());
                }
            }
            this.steering = new ArrayList<>(script.steer());
            this.followUp = new ArrayList<>(script.followUp());
        }

        StreamIterator stream(ModelId<?> model, Context context, StreamOptions options) {
            return new ListStream(ScriptedStreams.eventsFor(
                script.responses().get(streamCalls++), callIds));
        }

        PiLoop.ToolOutcome executeTool(PiLoop.ToolCall call) {
            if (rejected.contains(call.toolName())) {
                return denied(call);
            }
            var tool = toolsByName.get(call.toolName());
            var terminate = tool != null && tool.terminate();
            return executed(call, tool != null && tool.isError(), terminate);
        }

        /** pi 的 {@code beforeToolCall} 拦截分支：结果文本是 reason，结果消息标记为错误。 */
        private static PiLoop.ToolOutcome denied(PiLoop.ToolCall call) {
            var text = "denied by policy";
            return new PiLoop.ToolOutcome(
                new Message.ToolResultMessage(call.toolCallId(), call.toolName(),
                    List.of(new ContentBlock.TextContent(text)), true),
                CanonicalJson.obj("terminate", false), true, false);
        }

        /**
         * 正常执行的分支。注意 {@code script.tool().isError()} 只选结果文本 ——
         * pi 侧脚本的同名字段就是这个语义，执行成功的调用结果消息标记恒为 {@code false}。
         */
        private static PiLoop.ToolOutcome executed(PiLoop.ToolCall call, boolean failedText,
                                                   boolean terminate) {
            var text = failedText ? "failed" : "ok";
            return new PiLoop.ToolOutcome(
                new Message.ToolResultMessage(call.toolCallId(), call.toolName(),
                    List.of(new ContentBlock.TextContent(text)), false),
                CanonicalJson.obj("content", text, "details", Map.of(),
                    "terminate", terminate),
                false, terminate);
        }

        List<Message> steering() {
            return drain(steering);
        }

        List<Message> followUp() {
            return drain(followUp);
        }

        /**
         * 取走「在第 {@code streamCalls - 1} 轮之后」到期的注入消息。
         *
         * <p>轮次从**流请求计数**派生而非消费 {@code turn_end} 计数：pi 与 pi-java 都在发完
         * {@code turn_end} 之后立刻轮询队列，而事件消费者是否已排空该帧并不确定。以流请求数
         * 为准，队列语义就与消费速度无关。</p>
         */
        private List<Message> drain(List<ConformanceScript.Injection> queue) {
            var turn = streamCalls - 1;
            var due = new ArrayList<Message>();
            var iterator = queue.iterator();
            while (iterator.hasNext()) {
                var injection = iterator.next();
                if (injection.afterTurn() == turn) {
                    due.add(new Message.UserMessage(
                        List.of(new ContentBlock.TextContent(injection.text()))));
                    iterator.remove();
                }
            }
            return due;
        }
    }

    /** 把已物化的事件列表伪装成 {@link StreamIterator}（剧本是一次性的）。 */
    private static final class ListStream implements StreamIterator {

        private final List<StreamEvent> events;
        private int index;

        ListStream(List<StreamEvent> events) {
            this.events = events;
        }

        @Override
        public boolean hasNext() {
            return index < events.size();
        }

        @Override
        public StreamEvent next() {
            return events.get(index++);
        }

        @Override
        public void close() {
            // 剧本已全部在内存里，无需释放
        }
    }
}
