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
import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ToolResult;
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

    /** 两侧共用的基线模型 —— pi 侧 {@code createModel()} 就是 {@code openai/mock}。 */
    private static final ModelId<?> BASE_MODEL = ModelId.of("openai", "mock");

    private ConformanceRunner() {}

    /** 跑完一个剧本，返回逐帧归一化后的 JSON 行。 */
    static List<String> run(ConformanceScript script) {
        var normalizer = new FrameNormalizer();
        var frames = new ArrayList<String>();
        var driver = new Driver(script);
        var config = new PiLoop.Config(
            BASE_MODEL,
            ModelThinkingLevel.off(),
            ThinkingLevelMap.empty(),
            "sequential".equals(script.toolExecution())
                ? new ToolExecution.Sequential() : new ToolExecution.Parallel(),
            new PiLoop.ToolRunner() {
                @Override public PiLoop.Preparation prepare(PiLoop.ToolCall call) {
                    return driver.prepareTool(call);
                }
                @Override public PiLoop.ToolOutcome execute(PiLoop.Prepared prepared) {
                    return driver.executeTool(prepared);
                }
            },
            driver::stream,
            null,
            driver::steering,
            driver::followUp,
            null,
            driver::prepareNextTurn,
            null,
            null);

        var prompt = new Message.UserMessage(
            List.of(new ContentBlock.TextContent(script.prompt())));
        // 系统提示与工具走 Context（pi 的 AgentContext），不在消息列表里。
        var context = new Context(script.systemPrompt(), new ArrayList<>(), tools(script));
        PiLoop.run(List.of(prompt), context, config,
            event -> frames.add(normalizer.frame(event)));
        return List.copyOf(frames);
    }

    /**
     * 剧本 → 工具**本体**（pi 侧 {@code runScript} 的 {@code AgentTool[]} 对偶）。
     *
     * <p>执行不走这里的 {@code execute} —— 循环的工具端口是 {@code driver::executeTool}。
     * 本体只有两个用处：给 provider 投影出定义，以及让循环读到 {@code executionMode}
     * （决定整批走顺序还是并行，{@code agent-loop.ts:417-421}）。</p>
     */
    private static List<AgentTool<?, ?>> tools(ConformanceScript script) {
        var out = new ArrayList<AgentTool<?, ?>>();
        for (var tool : script.tools()) {
            out.add(new ScriptTool(tool.name(),
                "sequential".equals(tool.executionMode())
                    ? new ExecutionMode.Sequential() : new ExecutionMode.Parallel()));
        }
        return List.copyOf(out);
    }

    /** 剧本工具的骨架：名字 + 执行模式，参数与行为都不参与（执行由 driver 提供）。 */
    private record ScriptTool(String name, ExecutionMode mode) implements AgentTool<Void, Void> {
        @Override public String label() { return name; }
        @Override public String description() { return "scripted tool " + name; }
        @Override public Map<String, Object> inputSchema() { return Map.of(); }
        @Override public ExecutionMode executionMode() { return mode; }
        @Override public ToolResult<Void> execute(String toolCallId, Void params,
                com.pijava.ai.AbortSignal signal,
                com.pijava.agent.tool.ToolUpdateCallback<Void> onUpdate,
                com.pijava.agent.tool.ToolContext context) {
            return ToolResult.success("ok");
        }
    }

    /** 一次剧本运行的驱动状态：流脚本游标、工具语义、队列注入。 */
    private static final class Driver {

        private final ConformanceScript script;
        private final Set<String> rejected = new HashSet<>();
        private final Map<String, ConformanceScript.Tool> toolsByName = new HashMap<>();
        private final List<ConformanceScript.Injection> steering;
        private final List<ConformanceScript.Injection> followUp;
        private final List<AgentTool<?, ?>> tools;
        private final AtomicInteger callIds = new AtomicInteger();
        private int streamCalls;
        private boolean nextTurnFired;

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
            this.tools = ConformanceRunner.tools(script);
        }

        StreamIterator stream(ModelId<?> model, Context context, StreamOptions options) {
            var response = script.responses().get(streamCalls++);
            return new ListStream(ScriptedStreams.eventsFor(
                response.echoRequest() ? withEcho(response, context, model) : response, callIds));
        }

        /**
         * 把这次请求的形状编进首个文本块 —— 帧里只有 agent 事件，请求本身不可见（见
         * {@link ConformanceScript.Response#echoRequest}）。pi 侧 {@code runScript} 里
         * 有一份逐字相同的实现，**改动必须同步**，否则差分会把两侧的措辞差异当成行为差异。
         */
        private static ConformanceScript.Response withEcho(
                ConformanceScript.Response response, Context context, ModelId<?> model) {
            var echo = "[n=" + context.messages().size()
                + " model=" + model.provider() + "/" + model.modelName()
                + " sys=" + (context.systemPrompt() == null ? "-" : context.systemPrompt()) + "]";
            var content = new ArrayList<ConformanceScript.Content>();
            for (int i = 0; i < response.content().size(); i++) {
                var block = response.content().get(i);
                if (i == 0 && "text".equals(block.type())) {
                    var text = block.text() == null ? "" : block.text();
                    content.add(new ConformanceScript.Content(block.type(),
                        echo + " " + text, block.thinking(), block.name(),
                        block.arguments(), block.chunks()));
                } else {
                    content.add(block);
                }
            }
            return new ConformanceScript.Response(
                List.copyOf(content), response.stopReason(), true);
        }

        /**
         * pi 的 {@code prepareNextTurn}，在 {@code afterTurn} 指定的那一轮之后返回一次更新。
         *
         * <p>轮次从流请求计数派生（与 {@link #drain} 同源）：钩子在下一轮的开头被调用，
         * 此时已发出的流请求数正好是「刚完成的轮次 + 1」。</p>
         */
        PiLoop.NextTurnUpdate prepareNextTurn(PiLoop.NextTurnContext turn) {
            var spec = script.nextTurn();
            if (spec == null || nextTurnFired || spec.afterTurn() != streamCalls - 1) {
                return null;
            }
            nextTurnFired = true;
            var messages = new ArrayList<Message>();
            for (var text : spec.messages()) {
                messages.add(new Message.UserMessage(
                    List.of(new ContentBlock.TextContent(text))));
            }
            var model = spec.model() == null ? null : ModelId.of("openai", spec.model());
            return new PiLoop.NextTurnUpdate(model, null,
                new Context(spec.systemPrompt(), messages, tools));
        }

        /**
         * 准备相：{@code reject} 工具在这里被拦下、产出 **immediate** 结局（pi 的
         * {@code beforeToolCall}）。两相的区分正是 L5 要验证的形状 —— pi 并行分支的
         * end 在准备循环内就地发出（{@code agent-loop.ts:506-517}）。
         */
        PiLoop.Preparation prepareTool(PiLoop.ToolCall call) {
            if (rejected.contains(call.toolName())) {
                return new PiLoop.ImmediateOutcome(denied(call));
            }
            return new PiLoop.ToolRunner.CallPrepared(call);
        }

        PiLoop.ToolOutcome executeTool(PiLoop.Prepared prepared) {
            var call = prepared.call();
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
