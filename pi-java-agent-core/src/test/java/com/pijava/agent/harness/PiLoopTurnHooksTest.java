package com.pijava.agent.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.pijava.ai.api.StreamIterator;
import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevelMap;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code prepareNextTurn} / {@code shouldStopAfterTurn} 的调用**时机与上下文通道**，
 * 逐条对齐 pi {@code packages/agent/src/agent-loop.ts} @ {@code v0.85.1}。
 *
 * <p>这个类是 {@code docs/31 §8.3-6} 的落地验证。四处此前不一致的地方：</p>
 *
 * <ol>
 *   <li>{@code AgentLoopTurnUpdate} 的 {@code context} 字段缺失 —— 压缩的落地通道
 *       （{@code agent-session.ts:557-577}）</li>
 *   <li>{@code NextTurnContext} 只带消息列表，不带 tools —— pi 传的是整个 {@code AgentContext}
 *       （{@code :246}）</li>
 *   <li><b>两者顺序相反</b>：pi 是 {@code shouldStopAfterTurn} 先（{@code :249-252}）、
 *       {@code prepareNextTurn} 后（{@code :176-183}，在下一轮的开头）</li>
 *   <li>{@code prepareNextTurn} 之后不重拉 steer —— pi 明写理由是「准备可能很慢，例如压缩」
 *       （{@code :184-189}）</li>
 * </ol>
 */
class PiLoopTurnHooksTest {

    // ── 剧本 ───────────────────────────────────────────────────────

    /** 记录每次请求收到的消息列表，便于断言「下一轮看到的是什么」。 */
    private final List<List<Message>> requests = new ArrayList<>();

    /** 流式脚本：第 N 次请求使用第 N 个脚本；同时记录每次请求的入参。 */
    private StreamFn scripted(List<List<StreamEvent>> scripts) {
        var index = new AtomicInteger();
        return (model, context, options) -> {
            requests.add(List.copyOf(context.messages()));
            var script = scripts.get(index.getAndIncrement());
            return new StreamIterator() {
                private int i;

                @Override public boolean hasNext() { return i < script.size(); }
                @Override public StreamEvent next() { return script.get(i++); }
                @Override public void close() { }
            };
        };
    }

    /** 一段纯文本助手响应。 */
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

    /** 一段带单个工具调用的助手响应。 */
    private static List<StreamEvent> toolTurn(String callId, String name) {
        var partial = AssistantMessage.empty();
        var done = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.ToolUseContent(callId, name, Map.of())))
            .withStopReason("tool_use");
        return List.of(
            new StreamEvent.Start(partial),
            new StreamEvent.ToolCallEnd(0, callId, name, Map.of(), done),
            new StreamEvent.StreamDone("tool_use", null, done));
    }

    private static Message user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }

    /** 无 immediate 分支的端口：所有调用直接进执行相（{@link PiLoop.ToolRunner#always}）。 */
    private static final PiLoop.ToolRunner OK_TOOLS = PiLoop.ToolRunner.always(call ->
        new PiLoop.ToolOutcome(
            new Message.ToolResultMessage(call.toolCallId(), call.toolName(),
                List.of(new ContentBlock.TextContent("ok")), false),
            "ok", false, false));

    /** 工具本体：只有 name / executionMode 有语义，其余给最小合法值。 */
    private static AgentTool<?, ?> toolDef(String name) {
        return new ScriptTool(name);
    }

    /** 剧本式工具骨架：不执行（本测试只关心上下文里带了什么）。 */
    private record ScriptTool(String name) implements AgentTool<Void, Void> {
         public String label() { return name; }
         public String description() { return "test tool"; }
         public Map<String, Object> inputSchema() { return Map.of(); }
         public ExecutionMode executionMode() { return new ExecutionMode.Parallel(); }
         public ToolResult<Void> execute(String id, Void params, com.pijava.ai.AbortSignal s,
                ToolUpdateCallback<Void> onUpdate, ToolContext c) {
            return ToolResult.success("ok");
        }
    }

    /** 运行上下文：工具走 {@link Context}（pi 的 {@code AgentContext}），不挂在配置上。 */
    private static Context context(List<AgentTool<?, ?>> tools) {
        return new Context(null, new ArrayList<>(), tools);
    }

    private static PiLoop.Config config(StreamFn streamFn,
                                        PiLoop.NextTurnHook nextTurn, PiLoop.StopHook stop,
                                        java.util.function.Supplier<List<Message>> steering) {
        return new PiLoop.Config(
            ModelId.of("faux", "test-model"),
            ModelThinkingLevel.off(),
            ThinkingLevelMap.empty(),
            ToolExecution.defaultMode(),
            OK_TOOLS,
            streamFn,
            null,
            steering,
            null,
            null,
            nextTurn,
            stop,
            null);
    }

    /** 两轮：先工具、后文本。第二轮开头即 {@code prepareNextTurn} 的调用点。 */
    private static List<List<StreamEvent>> toolThenText() {
        return List.of(
            toolTurn("tc1", "echo"),
            textTurn("done"));
    }

    /** 把事件压成帧标签，便于断言「这个事件出现在哪个位置」。 */
    private static final class Recorder implements PiLoop.Sink {
        private final List<String> frames = new ArrayList<>();

        @Override
        public void emit(PiLoop.Event event) {
            frames.add(switch (event) {
                case PiLoop.Event.AgentStart e -> "agent_start";
                case PiLoop.Event.AgentEnd e -> "agent_end";
                case PiLoop.Event.TurnStart e -> "turn_start";
                case PiLoop.Event.TurnEnd e -> "turn_end";
                case PiLoop.Event.MessageStart e -> "message_start:" + e.message().role();
                case PiLoop.Event.MessageEnd e -> "message_end:" + e.message().role();
                case PiLoop.Event.MessageUpdate e -> "message_update";
                case PiLoop.Event.ToolExecutionStart e -> "tool_execution_start";
                case PiLoop.Event.ToolExecutionUpdate e -> "tool_execution_update";
                case PiLoop.Event.ToolExecutionEnd e -> "tool_execution_end";
            });
        }
    }

    // ── 用例 ───────────────────────────────────────────────────────

    /**
     * {@code context} 通道：钩子可以**整体替换**上下文，下一轮请求就用替换后的。
     *
     * <p>这是压缩的落地通道 —— {@code agent-session.ts:557-577} 返回的
     * {@code {context: {...}} } 正是靠它生效。</p>
     */
    @Test
    void prepareNextTurnCanReplaceTheContext() {
        var replacement = List.of(user("COMPACTED"));
        var calls = new AtomicInteger();

        var config = config(scripted(toolThenText()),
            ctx -> {
                calls.incrementAndGet();
                return new PiLoop.NextTurnUpdate(null, null, Context.of(replacement));
            },
            null, null);

        PiLoop.run(List.of(user("go")), context(List.of(toolDef("echo"))), config, new Recorder());

        assertThat(calls.get()).as("两轮 ⇒ prepareNextTurn 在第一轮结束后调用一次").isEqualTo(1);
        assertThat(requests).as("两次请求：第一轮原始上下文，第二轮用替换后的").hasSize(2);
        assertThat(requests.get(1))
            .as("pi: currentContext = nextTurnSnapshot.context ?? currentContext（整体替换）")
            .isEqualTo(replacement);
    }

    /** 钩子返回 {@code null} 或 {@code context} 为 null ⇒ 上下文不变。 */
    @Test
    void prepareNextTurnWithoutContextKeepsTheMessages() {
        var config = config(scripted(toolThenText()),
            ctx -> new PiLoop.NextTurnUpdate(null, null, null),
            null, null);

        PiLoop.run(List.of(user("go")), context(List.of(toolDef("echo"))), config, new Recorder());

        assertThat(requests.get(1))
            .as("不改 context ⇒ 沿用累计的消息（用户 + 助手 + 工具结果）")
            .hasSizeGreaterThan(1)
            .anyMatch(m -> m instanceof Message.ToolResultMessage);
    }

    /**
     * {@code NextTurnContext} 携带**当时**的 {@code AgentContext}，含 tools
     * —— pi {@code agent-loop.ts:246} 传的是 {@code {message, toolResults, context, newMessages}}。
     */
    @Test
    void nextTurnContextExposesTheToolsOfTheRun() {
        var tools = List.of(toolDef("echo"), toolDef("read"));
        var seen = new ArrayList<List<AgentTool<?, ?>>>();

        var config = config(scripted(toolThenText()),
            ctx -> {
                seen.add(ctx.context().tools());
                return null;
            },
            null, null);

        PiLoop.run(List.of(user("go")), context(tools), config, new Recorder());

        assertThat(seen).singleElement().isEqualTo(tools);
    }

    /**
     * <b>顺序</b>：{@code shouldStopAfterTurn} 为真时，pi **根本不会调用**
     * {@code prepareNextTurn} —— 它在 {@code turn_end} 之后立即返回
     * （{@code agent-loop.ts:249-252}），而 {@code prepareNextTurn} 在下一轮的开头
     * （{@code :176-183}）。
     */
    @Test
    void shouldStopAfterTurnRunsBeforePrepareNextTurn() {
        var prepared = new AtomicInteger();

        var config = config(scripted(List.of(textTurn("bye"))),
            ctx -> {
                prepared.incrementAndGet();
                return null;
            },
            ctx -> true,   // 立刻停
            null);

        PiLoop.run(List.of(user("hi")), context(List.of()), config, new Recorder());

        assertThat(prepared.get())
            .as("停之后没有下一轮 ⇒ prepareNextTurn 一次都不该被调用")
            .isZero();
    }

    /**
     * <b>重拉 steer</b>：{@code prepareNextTurn} 之后、{@code turn_start} 之前，
     * 若此前没有待注入的 steer，则**再轮询一次** —— pi 的理由是「准备可能很慢（例如压缩）」
     * （{@code agent-loop.ts:184-189}）。
     */
    @Test
    void steeringQueuedDuringPrepareNextTurnIsPickedUpInTheSameTurn() {
        var queued = new ArrayList<Message>();
        var recorder = new Recorder();

        var config = config(scripted(List.of(
                toolTurn("tc1", "echo"),
                textTurn("done"))),
            ctx -> {
                // 模拟「压缩期间用户敲进来的 steer」
                queued.add(user("STEER"));
                return null;
            },
            null,
            // pi 的 getSteeringMessages 是 queue.drain() —— 取出即清空
            () -> {
                var drained = List.copyOf(queued);
                queued.clear();
                return drained;
            });

        PiLoop.run(List.of(user("go")), context(List.of(toolDef("echo"))), config, recorder);

        // 第二轮：turn_start 之后立刻是这条 steer 的 message_start，而不是被推迟到再下一轮。
        // 没有重拉时，steer 要等到第二轮结束后才被轮询到，于是会多跑一轮 ⇒ 3 个 turn_start。
        var turnStarts = new ArrayList<Integer>();
        for (int i = 0; i < recorder.frames.size(); i++) {
            if ("turn_start".equals(recorder.frames.get(i))) {
                turnStarts.add(i);
            }
        }
        assertThat(turnStarts)
            .as("两轮、两个 turn_start —— 重拉生效时 steer 不会撑出第三轮")
            .hasSize(2);
        assertThat(recorder.frames.get(turnStarts.get(1) + 1))
            .as("第二轮 turn_start 之后紧跟这条 steer")
            .isEqualTo("message_start:user");
        assertThat(requests.get(1)).as("第二轮请求里必须带上这条 steer")
            .anyMatch(m -> m instanceof Message.UserMessage u
                && u.content().toString().contains("STEER"));
    }
}
