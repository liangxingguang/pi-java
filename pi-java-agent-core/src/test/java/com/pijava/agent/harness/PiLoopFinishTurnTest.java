package com.pijava.agent.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.pijava.ai.api.StreamIterator;
import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevelMap;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B159：{@code finishTurn} 裁决形状，逐条镜像 pi {@code agent-loop.ts} @ {@code ea448f454}：
 *
 * <ul>
 *   <li>钩子在 emit {@code turn_end} <b>之前</b>调用（{@code :286-287}）</li>
 *   <li>END ⇒ turn_end 照发、agent_end 收口、不再查队列（{@code :289-292}）</li>
 *   <li>CONTINUE ⇒ 没有被满足时白送一轮 context-only 请求（{@code :310-314}）；
 *       被工具结果/steering/follow-up 满足则不额外发（{@code :294-298, :302-307}）</li>
 *   <li>错误/中止轮钩子照调、裁决忽略（{@code :245-257}）</li>
 *   <li>上下文带 {@code newMessages}（{@code types.ts:143}）</li>
 * </ul>
 */
class PiLoopFinishTurnTest {

    // ── 剧本（与 PiLoopTurnHooksTest 同装置） ────────────────────────

    private final List<List<Message>> requests = new ArrayList<>();

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

    private static List<StreamEvent> textTurn(String text) {
        var partial = AssistantMessage.empty();
        var done = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent(text)))
            .withStopReason(StopReason.STOP);
        return List.of(
            new StreamEvent.Start(partial),
            new StreamEvent.TextDelta(0, text, done),
            new StreamEvent.StreamDone(StopReason.STOP, null, done));
    }

    private static List<StreamEvent> toolTurn(String callId, String name) {
        var partial = AssistantMessage.empty();
        var done = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.ToolUseContent(callId, name, Map.of())))
            .withStopReason(StopReason.TOOL_USE);
        return List.of(
            new StreamEvent.Start(partial),
            new StreamEvent.ToolCallEnd(0, callId, name, Map.of(), done),
            new StreamEvent.StreamDone(StopReason.TOOL_USE, null, done));
    }

    /** 一段以 error 收尾的助手响应（pi 的硬出口路径）。 */
    private static List<StreamEvent> errorTurn() {
        var partial = AssistantMessage.empty();
        var done = AssistantMessage.empty().withStopReason(StopReason.ERROR);
        return List.of(
            new StreamEvent.Start(partial),
            new StreamEvent.StreamDone(StopReason.ERROR, null, done));
    }

    private static Message user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }

    private static final PiLoop.ToolRunner OK_TOOLS = PiLoop.ToolRunner.always(call ->
        new PiLoop.ToolOutcome(
            new Message.ToolResultMessage(call.toolCallId(), call.toolName(),
                List.of(new ContentBlock.TextContent("ok")), false),
            ToolResult.success("ok"), false));

    private static AgentTool<?, ?> toolDef(String name) {
        return new ScriptTool(name);
    }

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

    private static Context context(List<AgentTool<?, ?>> tools) {
        return new Context(null, new ArrayList<>(), tools);
    }

    private static PiLoop.Config config(StreamFn streamFn,
                                        PiLoop.NextTurnHook nextTurn,
                                        PiLoop.FinishTurnHook finishTurn,
                                        Supplier<List<Message>> steering,
                                        Supplier<List<Message>> followUp) {
        return new PiLoop.Config(
            ModelId.of("faux", "test-model"),
            ModelThinkingLevel.off(),
            ThinkingLevelMap.empty(),
            ToolExecution.defaultMode(),
            OK_TOOLS,
            streamFn,
            null,
            steering,
            followUp,
            null,
            null,
            nextTurn,
            finishTurn,
            null,
            null);
    }

    /** 帧标签记录器；钩子探针也写进同一列表 ⇒ 调用点与事件的相对序可断言。 */
    private static final class Recorder implements PiLoop.Sink {
        final List<String> frames = new CopyOnWriteArrayList<>();

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

        long count(String frame) {
            return frames.stream().filter(frame::equals).count();
        }
    }

    /** 只在第 {@code call} 次调用时给 {@code decision}，之后弃权（防 CONTINUE 死循环）。 */
    private static PiLoop.FinishTurnHook once(int call, PiLoop.TurnDecision decision,
                                              AtomicInteger calls) {
        return ctx -> calls.incrementAndGet() == call ? decision : null;
    }

    // ── ① END：turn_end 照发、agent_end 收口、不再发请求 ─────────────

    @Test
    void endDecisionClosesTheRunAfterTurnEnd() {
        var recorder = new Recorder();
        var config = config(scripted(List.of(textTurn("bye"))),
            null, ctx -> PiLoop.TurnDecision.END, null, null);

        PiLoop.run(List.of(user("hi")), context(List.of()), config, recorder);

        assertThat(requests).as("END ⇒ 只烧一次请求").hasSize(1);
        assertThat(recorder.count("turn_start")).as("没有第二轮").isEqualTo(1);
        var frames = recorder.frames;
        assertThat(frames.get(frames.size() - 2)).as("turn_end 照发").isEqualTo("turn_end");
        assertThat(frames.get(frames.size() - 1)).as("随后 agent_end 收口").isEqualTo("agent_end");
    }

    // ── ② CONTINUE 白送一轮：恰 2 次请求，第二轮走完整起手 ──────────

    @Test
    void continueGrantsExactlyOneContextOnlyRequest() {
        var recorder = new Recorder();
        var calls = new AtomicInteger();
        var prepared = new AtomicInteger();
        var config = config(scripted(List.of(textTurn("one"), textTurn("two"))),
            ctx -> {
                prepared.incrementAndGet();
                return null;
            },
            once(1, PiLoop.TurnDecision.CONTINUE, calls), null, null);

        PiLoop.run(List.of(user("hi")), context(List.of()), config, recorder);

        assertThat(requests)
            .as("无工具/steering/follow-up ⇒ CONTINUE 白送一轮 context-only 请求，恰 2 次")
            .hasSize(2);
        assertThat(calls.get()).as("两轮各调一次钩子").isEqualTo(2);
        assertThat(prepared.get()).as("白送的第二轮也走 prepareNextTurn").isEqualTo(1);
        assertThat(recorder.count("turn_start")).as("两个完整回合").isEqualTo(2);
        assertThat(requests.get(1))
            .as("context-only：第二轮请求只是「现有上下文」—— 用户消息＋首轮 assistant，无新增")
            .hasSize(2);
        assertThat(requests.get(1).get(1)).isInstanceOf(Message.AssistantMessage.class);
        assertThat(recorder.frames.get(recorder.frames.size() - 1)).isEqualTo("agent_end");
    }

    // ── ③ CONTINUE 被满足×3：不额外发请求 ────────────────────────────

    @Test
    void continueIsSatisfiedByToolResults() {
        var calls = new AtomicInteger();
        var config = config(scripted(List.of(toolTurn("tc1", "echo"), textTurn("done"))),
            null, once(1, PiLoop.TurnDecision.CONTINUE, calls), null, null);

        PiLoop.run(List.of(user("go")), context(List.of(toolDef("echo"))), config, new Recorder());

        assertThat(requests)
            .as("工具结果已经排定了下一次请求 ⇒ CONTINUE 被满足，不白送第三轮（:296-298）")
            .hasSize(2);
    }

    @Test
    void continueIsSatisfiedBySteering() {
        var queued = new ArrayList<Message>();
        var calls = new AtomicInteger();
        // steering 在第一轮结束后才出现（模拟用户在流式期间敲入）
        Supplier<List<Message>> steering = () -> {
            if (requests.size() == 1 && queued.isEmpty()) {
                queued.add(user("STEER"));
            }
            var drained = List.copyOf(queued);
            queued.clear();
            return drained;
        };
        var config = config(scripted(List.of(textTurn("one"), textTurn("two"))),
            null, once(1, PiLoop.TurnDecision.CONTINUE, calls), steering, null);

        PiLoop.run(List.of(user("hi")), context(List.of()), config, new Recorder());

        assertThat(requests)
            .as("steering 已排到 ⇒ CONTINUE 被满足，恰 2 次请求")
            .hasSize(2);
        assertThat(requests.get(1))
            .as("第二次请求由那条 steering 驱动")
            .anyMatch(m -> m instanceof Message.UserMessage u
                && u.content().toString().contains("STEER"));
    }

    @Test
    void continueIsSatisfiedByFollowUp() {
        var calls = new AtomicInteger();
        var followUps = new ArrayList<List<Message>>(List.of(List.of(user("FU"))));
        var config = config(scripted(List.of(textTurn("one"), textTurn("two"))),
            null, once(1, PiLoop.TurnDecision.CONTINUE, calls), null,
            () -> followUps.isEmpty() ? List.of() : followUps.remove(0));

        PiLoop.run(List.of(user("hi")), context(List.of()), config, new Recorder());

        assertThat(requests)
            .as("follow-up 已排到 ⇒ CONTINUE 被满足（:303-307），恰 2 次请求")
            .hasSize(2);
    }

    // ── ④ 错误路：钩子照调、裁决忽略 ─────────────────────────────────

    @Test
    void errorPathStillCallsFinishTurnButIgnoresTheDecision() {
        var recorder = new Recorder();
        var seen = new ArrayList<PiLoop.NextTurnContext>();
        var config = config(scripted(List.of(errorTurn())),
            null, ctx -> {
                seen.add(ctx);
                return PiLoop.TurnDecision.CONTINUE;   // CONTINUE 也救不回硬出口
            }, null, null);

        PiLoop.run(List.of(user("hi")), context(List.of()), config, recorder);

        assertThat(seen).as("错误轮 finishTurn 照调一次（:252）").hasSize(1);
        assertThat(seen.get(0).toolResults()).as("错误轮的 toolResults 为空").isEmpty();
        assertThat(requests).as("裁决被忽略：不再发请求").hasSize(1);
        var frames = recorder.frames;
        assertThat(frames.get(frames.size() - 2)).isEqualTo("turn_end");
        assertThat(frames.get(frames.size() - 1)).isEqualTo("agent_end");
    }

    // ── ⑤ 顺序钉：钩子在 turn_end 之前 ──────────────────────────────

    @Test
    void finishTurnRunsBeforeTurnEndIsEmitted() {
        var recorder = new Recorder();
        var config = config(scripted(List.of(textTurn("hi"))),
            null, ctx -> {
                recorder.frames.add("finish_turn");
                return null;
            }, null, null);

        PiLoop.run(List.of(user("go")), context(List.of()), config, recorder);

        var frames = recorder.frames;
        assertThat(frames.indexOf("finish_turn"))
            .as("pi :286-287 —— finishTurn 先于 turn_end")
            .isLessThan(frames.indexOf("turn_end"));
    }

    // ── ⑥ newMessages：第二轮钩子看到首轮的全部产物 ──────────────────

    @Test
    void secondRoundHookSeesTheFirstRoundsNewMessages() {
        var snapshots = new ArrayList<List<Message>>();
        var config = config(scripted(List.of(toolTurn("tc1", "echo"), textTurn("done"))),
            null, ctx -> {
                snapshots.add(ctx.newMessages());
                return null;
            }, null, null);

        PiLoop.run(List.of(user("go")), context(List.of(toolDef("echo"))), config, new Recorder());

        assertThat(snapshots).hasSize(2);
        assertThat(snapshots.get(0))
            .as("首轮末快照：prompt＋assistant＋工具结果都在（types.ts:143 的「此刻退出会返回的消息」）")
            .anyMatch(m -> m instanceof Message.UserMessage)
            .anyMatch(m -> m instanceof Message.AssistantMessage)
            .anyMatch(m -> m instanceof Message.ToolResultMessage);
        assertThat(snapshots.get(1).size())
            .as("次轮末快照 = 首轮产物 ＋ 次轮 assistant")
            .isEqualTo(snapshots.get(0).size() + 1);
    }
}
