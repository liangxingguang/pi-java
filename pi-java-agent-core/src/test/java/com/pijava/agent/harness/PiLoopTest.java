package com.pijava.agent.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.pijava.ai.AbortSignal;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevelMap;
import com.pijava.agent.tool.ToolResult;

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
        return (model, context, options) -> {
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
        // COW，不是 ArrayList：工具帧由 **worker 线程**发出（并行任务体，见 PiLoopTools
        // 的 executeParallel），而引擎线程也在往同一张表里记帧。普通 ArrayList 会丢帧 ——
        // 300 轮实测坏 5 轮，这正是曾经那次 PiLoopTest:338 flake 的根因（docs/31 §8.27.7）。
        private final List<String> frames = new CopyOnWriteArrayList<>();

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
        // 同样的理由：execute 跑在 worker 线程上（docs/31 §8.27.7）。
        private final List<String> invoked = new CopyOnWriteArrayList<>();

        @Override
        public PiLoop.Preparation prepare(PiLoop.ToolCall call) {
            return new PiLoop.ToolRunner.CallPrepared(call);
        }

        @Override
        public PiLoop.ToolOutcome execute(PiLoop.Prepared prepared, PiLoop.Sink emit) {
            var call = prepared.call();
            // invoked 记录在执行相：拿到执行票≠执行过（中止的闭包不会走到这里）。
            invoked.add(call.toolName());
            var message = new Message.ToolResultMessage(call.toolCallId(), call.toolName(),
                List.of(new ContentBlock.TextContent("ok")), false);
            return new PiLoop.ToolOutcome(message, ToolResult.success("ok"), false);
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
        var context = Context.of(new ArrayList<>());

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
        var context = Context.of(new ArrayList<>());
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
        var context = Context.of(new ArrayList<>());

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
        var context = Context.of(new ArrayList<>());
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
    void parallelBatchEmitsEveryStartBeforeAnyEnd() {
        // 准备循环按源序交替发 start 与准备（agent-loop.ts:498-545），延迟任务在完成时才发
        // end（:519-541）。当且仅当**没有调用在准备相当场失败、也没有中止**时，start 循环
        // 先于任何 closure 跑完，「所有 start 早于任何 end」成立 —— 这是本桩（StubTools 恒
        // 给执行票）的形状，不是 pi 的结构保证：一个 immediate 调用的 end 会插进批次
        // 后续的 start 之前（S4 剧本正是如此，见 docs/29 §4）。
        //
        // 断言到此为止：**end 的相对次序不是不变量**。package B（docs/31 §8.23）起延迟任务
        // 真并发，三个等延迟的桩谁先抢到串行化锁是任意的；pi 那边的「源序」是 JS 微任务队列
        // 的副产品（工具体在源序里同步进入），不是语义承诺。这里只钉住「本桩形状下 start
        // 全在前、且每个调用恰好一 start 一 end」。
        //
        // ⚠️ 本断言成立靠**两条**，缺一不可（docs/31 §8.27.7）：
        //   ① 结构：`PiLoopTools.executeParallel:190-194` 是**准备循环跑完才 submit**
        //      （Java 侧的执行票只是 thunk）⇒ 全 start 天然早于任何 end；
        //   ② 桩的线程安全：帧由 worker 线程发出，收帧的表必须是并发容器。
        // 曾经那次非复现 flake 出在 ② —— 旧口径误诊为「准备循环内交票 ⇒ 真竞态」（① 才是
        // 事实），实测根因是 Recorder 用普通 ArrayList 丢帧：300 轮坏 5 轮。
        var rec = new Recorder();
        var context = Context.of(new ArrayList<>());
        var partial = AssistantMessage.empty();
        var done = AssistantMessage.empty()
            .withContent(List.of(
                new ContentBlock.ToolUseContent("c1", "bash", Map.of()),
                new ContentBlock.ToolUseContent("c2", "read", Map.of()),
                new ContentBlock.ToolUseContent("c3", "grep", Map.of())))
            .withStopReason("tool_use");

        PiLoop.run(List.of(user("go")), context,
            config(scripted(List.of(
                List.of(new StreamEvent.Start(partial),
                    new StreamEvent.StreamDone("tool_use", null, done)),
                textTurn("done"))),
                new StubTools()),
            rec);

        var frames = rec.frames.stream().filter(f -> f.startsWith("tool_execution_")).toList();
        var starts = frames.stream().filter(f -> f.startsWith("tool_execution_start:")).toList();
        var ends = frames.stream().filter(f -> f.startsWith("tool_execution_end:")).toList();
        assertThat(starts).containsExactly(
            "tool_execution_start:bash",
            "tool_execution_start:read",
            "tool_execution_start:grep");
        assertThat(ends).containsExactlyInAnyOrder(
            "tool_execution_end:bash",
            "tool_execution_end:read",
            "tool_execution_end:grep");
        assertThat(frames.indexOf(ends.getFirst())).isGreaterThan(frames.indexOf(starts.getLast()));
    }

    @Test
    void abortedParallelBatchFramesOnlyTheFirstCallLikePi() {
        // pi executeToolCallsParallel：批次开始前信号已中止时，准备循环给**第一个**调用
        // 发 start、在其执行票闭包里收尾 end（"Operation aborted"，:521-524 的中止检查），
        // 随后 break（:542-544）—— 后续调用**一帧都没有**，结果消息也只补发一条。
        // 本测试旧断言（「每个已 start 的调用都收到 end」）钉住的是 pi-java 自创形状，
        // 随端口两相拆分一并改为 pi 的真实形状；docs/29 §4 同步修正。
        var signal = AbortSignal.create();
        signal.abort();
        var rec = new Recorder();
        var tools = new StubTools();
        var context = Context.of(new ArrayList<>());
        var partial = AssistantMessage.empty();
        var done = AssistantMessage.empty()
            .withContent(List.of(
                new ContentBlock.ToolUseContent("c1", "bash", Map.of()),
                new ContentBlock.ToolUseContent("c2", "read", Map.of())))
            .withStopReason("tool_use");

        PiLoop.run(List.of(user("go")), context,
            new PiLoop.Config(
                ModelId.of("faux", "test-model"),
                ModelThinkingLevel.off(),
                ThinkingLevelMap.empty(),
                ToolExecution.defaultMode(),
                tools,
                scripted(List.of(
                    List.of(new StreamEvent.Start(partial),
                        new StreamEvent.StreamDone("tool_use", null, done)),
                    textTurn("stopped"))),
                signal,
                null, null, null, null, null, null),
            rec);

        assertThat(rec.frames.stream().filter(f -> f.startsWith("tool_execution_")).toList())
            .containsExactly(
                "tool_execution_start:bash",
                "tool_execution_end:bash");
        assertThat(rec.frames.stream().filter(f -> f.equals("message_end:tool")).count())
            .as("break 之后 c2 没有任何结果消息（pi 的 finalizedCalls 里只有 c1）")
            .isEqualTo(1);
        assertThat(tools.invoked).isEmpty();
    }

    @Test
    void followUpStartsANewTurnAfterTheInnerLoopDrains() {
        var rec = new Recorder();
        var context = Context.of(new ArrayList<>());
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
