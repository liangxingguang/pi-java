package com.pijava.agent.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.api.ToolDefinition;
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
 * {@link PiLoop} 与 pi 的事件序列逐帧对齐（{@code docs/28 §5} 第 1 步的验证）。
 *
 * <p>期望值全部取自 pi {@code packages/agent/src/agent-loop.ts} @ {@code v0.85.1} 的实读，
 * **不是**从 pi-java 现有实现反推的：</p>
 *
 * <ul>
 *   <li>起手 {@code agent_start} → {@code turn_start} → 各 prompt 的
 *       {@code message_start}/{@code message_end}（{@code :109-114}）</li>
 *   <li>助手 {@code message_start} 由流的 {@code start} 事件触发（{@code :321-325}），
 *       其余帧走 {@code message_update}，{@code done} 时发 {@code message_end}（{@code :341-344}）</li>
 *   <li>{@code tool_execution_start} 在**校验之前**发出（{@code :443-448}）</li>
 *   <li>工具结果作为普通消息发 {@code message_start}/{@code message_end}（{@code :793-796}）</li>
 *   <li>{@code turn_end} 携带 {@code toolResults}，紧随其后是下一轮 {@code turn_start}
 *       （{@code :224} 与 {@code :176}）</li>
 *   <li>{@code error}/{@code aborted} ⇒ {@code turn_end + agent_end} 后返回（{@code :189-193}）</li>
 * </ul>
 */
class PiLoopTest {

    // ── 剧本 ───────────────────────────────────────────────────────

    /** 流式脚本：第 N 次请求使用第 N 个脚本。 */
    private static StreamFn scripted(List<List<StreamEvent>> scripts) {
        var index = new AtomicInteger();
        return (messages, model, options) -> {
            var script = scripts.get(index.getAndIncrement());
            return new StreamIterator() {
                private int i;

                @Override
                public boolean hasNext() {
                    return i < script.size();
                }

                @Override
                public StreamEvent next() {
                    return script.get(i++);
                }

                @Override
                public void close() { }
            };
        };
    }

    /** 一段纯文本助手响应：start → text_start/delta/end → done。 */
    private static List<StreamEvent> textTurn(String text) {
        var partial = AssistantMessage.empty();
        var done = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent(text)))
            .withStopReason("stop");
        return List.of(
            new StreamEvent.Start(partial),
            new StreamEvent.TextStart(0, partial),
            new StreamEvent.TextDelta(0, text, done),
            new StreamEvent.TextEnd(0, text, done),
            new StreamEvent.StreamDone("stop", null, done));
    }

    /** 一段带单个工具调用的助手响应：stopReason = tool_use。 */
    private static List<StreamEvent> toolTurn(String callId, String name, Map<String, Object> args) {
        var partial = AssistantMessage.empty();
        var done = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.ToolUseContent(callId, name, args)))
            .withStopReason("tool_use");
        return List.of(
            new StreamEvent.Start(partial),
            new StreamEvent.ToolCallStart(0, partial),
            new StreamEvent.ToolCallEnd(0, callId, name, args, done),
            new StreamEvent.StreamDone("tool_use", null, done));
    }

    /** 一段以给定 stopReason 收尾的助手响应（无内容）。 */
    private static List<StreamEvent> bareTurn(String stopReason) {
        var partial = AssistantMessage.empty();
        var done = AssistantMessage.empty().withStopReason(stopReason);
        return List.of(
            new StreamEvent.Start(partial),
            new StreamEvent.StreamDone(stopReason, null, done));
    }

    // ── 事件记录 ───────────────────────────────────────────────────

    /** 把事件压成可读的帧标签，便于逐帧断言。 */
    private static final class Recorder implements PiLoop.Sink {
        private final List<String> frames = new ArrayList<>();

        @Override
        public void emit(PiLoop.Event event) {
            frames.add(switch (event) {
                case PiLoop.Event.AgentStart e -> "agent_start";
                case PiLoop.Event.AgentEnd e -> "agent_end";
                case PiLoop.Event.TurnStart e -> "turn_start";
                case PiLoop.Event.TurnEnd e -> "turn_end(" + e.toolResults().size() + ")";
                case PiLoop.Event.MessageStart e -> "message_start:" + e.message().role();
                case PiLoop.Event.MessageEnd e -> "message_end:" + e.message().role();
                case PiLoop.Event.MessageUpdate e -> "message_update";
                case PiLoop.Event.ToolExecutionStart e -> "tool_execution_start:" + e.toolName();
                case PiLoop.Event.ToolExecutionUpdate e -> "tool_execution_update:" + e.toolName();
                case PiLoop.Event.ToolExecutionEnd e -> "tool_execution_end:" + e.toolName();
            });
        }
    }

    /** 记录工具调用次数，返回固定成功结果。 */
    private static final class StubTools implements PiLoop.ToolRunner {
        private final List<String> invoked = new ArrayList<>();

        @Override
        public PiLoop.ToolOutcome run(PiLoop.ToolCall call) {
            invoked.add(call.toolName());
            var message = new Message.ToolResultMessage(call.toolCallId(), call.toolName(),
                List.of(new ContentBlock.TextContent("ok")), false);
            return new PiLoop.ToolOutcome(message, "ok", false, false);
        }
    }

    private static PiLoop.Config config(StreamFn streamFn, PiLoop.ToolRunner tools) {
        return config(streamFn, tools, null, null);
    }

    private static PiLoop.Config config(StreamFn streamFn, PiLoop.ToolRunner tools,
                                        java.util.function.Supplier<List<Message>> followUp,
                                        PiLoop.StopHook stopHook) {
        return new PiLoop.Config(
            ModelId.of("faux", "test-model"),
            ModelThinkingLevel.off(),
            ThinkingLevelMap.empty(),
            List.<ToolDefinition>of(),
            ToolExecution.defaultMode(),
            tools,
            streamFn,
            null,
            null,
            followUp,
            null,
            null,
            stopHook,
            null);
    }

    private static Message user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }

    // ── 用例 ───────────────────────────────────────────────────────

    @Test
    void singleTextTurnMatchesPiSequence() {
        var rec = new Recorder();
        var context = new ArrayList<Message>();

        PiLoop.run(List.of(user("hi")), context,
            config(scripted(List.of(textTurn("hello"))), null), rec);

        assertThat(rec.frames).containsExactly(
            "agent_start",
            "turn_start",
            "message_start:user",
            "message_end:user",
            "message_start:assistant",
            "message_update",           // text_start
            "message_update",           // text_delta
            "message_update",           // text_end
            "message_end:assistant",
            "turn_end(0)",
            "agent_end");
    }

    @Test
    void toolTurnEmitsToolFramesThenStartsSecondTurn() {
        var rec = new Recorder();
        var tools = new StubTools();
        var context = new ArrayList<Message>();
        var streamFn = scripted(List.of(
            toolTurn("tc1", "bash", Map.of("cmd", "ls")),
            textTurn("done")));

        PiLoop.run(List.of(user("run ls")), context, config(streamFn, tools), rec);

        assertThat(rec.frames).containsExactly(
            "agent_start",
            "turn_start",
            "message_start:user",
            "message_end:user",
            "message_start:assistant",
            "message_update",           // toolcall_start
            "message_update",           // toolcall_end
            "message_end:assistant",
            "tool_execution_start:bash",
            "tool_execution_end:bash",
            "message_start:tool",
            "message_end:tool",
            "turn_end(1)",
            "turn_start",               // 第二轮：内层继续，不再发 agent_start
            "message_start:assistant",
            "message_update",
            "message_update",
            "message_update",
            "message_end:assistant",
            "turn_end(0)",
            "agent_end");

        assertThat(tools.invoked).containsExactly("bash");
    }

    @Test
    void abortedStopReasonEndsRunWithoutFurtherTurns() {
        var rec = new Recorder();
        var context = new ArrayList<Message>();

        PiLoop.run(List.of(user("hi")), context,
            config(scripted(List.of(bareTurn("aborted"))), null), rec);

        assertThat(rec.frames).containsExactly(
            "agent_start",
            "turn_start",
            "message_start:user",
            "message_end:user",
            "message_start:assistant",
            "message_end:assistant",
            "turn_end(0)",              // pi: error/aborted 也发 turn_end，且 toolResults 为空
            "agent_end");
    }

    @Test
    void truncatedLengthFailsToolCallsWithoutExecutingThem() {
        var rec = new Recorder();
        var tools = new StubTools();
        var context = new ArrayList<Message>();
        var partial = AssistantMessage.empty();
        var truncated = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.ToolUseContent("tc2", "bash", Map.of())))
            .withStopReason("length");

        PiLoop.run(List.of(user("go")), context,
            config(scripted(List.of(
                // 第一轮：length 截断，带一个工具调用
                List.of(new StreamEvent.Start(partial),
                    new StreamEvent.StreamDone("length", null, truncated)),
                // 第二轮：模型重新发出完整参数后正常收尾
                textTurn("done"))),
                tools),
            rec);

        // pi: length 截断 ⇒ 每个调用都发 start/end 与结果消息，但**不执行**；
        // 且 failToolCallsFromTruncatedMessage 返回 terminate=false（:403）
        // ⇒ hasMoreToolCalls=true ⇒ 内层继续，**再请求一次**让模型重发完整参数。
        assertThat(rec.frames).containsExactly(
            "agent_start",
            "turn_start",
            "message_start:user",
            "message_end:user",
            "message_start:assistant",
            "message_end:assistant",
            "tool_execution_start:bash",
            "tool_execution_end:bash",
            "message_start:tool",
            "message_end:tool",
            "turn_end(1)",
            "turn_start",
            "message_start:assistant",
            "message_update",
            "message_update",
            "message_update",
            "message_end:assistant",
            "turn_end(0)",
            "agent_end");
        assertThat(tools.invoked).isEmpty();
    }

    @Test
    void followUpStartsANewTurnAfterTheInnerLoopDrains() {
        var rec = new Recorder();
        var context = new ArrayList<Message>();
        var followUps = new ArrayList<List<Message>>(List.of(List.of(user("and again"))));

        PiLoop.run(List.of(user("hi")), context,
            config(scripted(List.of(textTurn("one"), textTurn("two"))), null,
                () -> followUps.isEmpty() ? List.of() : followUps.remove(0),
                null),
            rec);

        // 第一轮结束 → 内层耗尽 → follow-up 并入 pending → 外层 continue
        // → 内层发新 turn_start（**不再发 agent_start**）→ 注入 follow-up 消息
        assertThat(rec.frames).containsExactly(
            "agent_start",
            "turn_start",
            "message_start:user",
            "message_end:user",
            "message_start:assistant",
            "message_update",
            "message_update",
            "message_update",
            "message_end:assistant",
            "turn_end(0)",
            "turn_start",
            "message_start:user",
            "message_end:user",
            "message_start:assistant",
            "message_update",
            "message_update",
            "message_update",
            "message_end:assistant",
            "turn_end(0)",
            "agent_end");
        assertThat(rec.frames.stream().filter("agent_start"::equals)).hasSize(1);
    }
}
