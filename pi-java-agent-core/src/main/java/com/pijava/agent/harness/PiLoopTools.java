package com.pijava.agent.harness;

import java.util.ArrayList;
import java.util.List;

import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

/**
 * 工具调用的**发射时序**（pi {@code agent-loop.ts:379-591}）。
 *
 * <p>从 {@link PiLoop} 拆出以保持文件 ≤ 500 行；职责边界是「一轮工具调用如何映射成事件」，
 * 与循环控制流无关。实际执行委托给 {@link PiLoop.ToolRunner} 端口。</p>
 */
final class PiLoopTools {

    private PiLoopTools() {}

    /** pi 的 {@code ExecutedToolCallBatch}。 */
    record Batch(List<Message.ToolResultMessage> messages, boolean terminate) {}

    /** 提取助手消息里的 toolCall 块（pi: {@code content.filter(c => c.type === "toolCall")}）。 */
    static List<ContentBlock.ToolUseContent> callsOf(Message.AssistantMessage message) {
        var calls = new ArrayList<ContentBlock.ToolUseContent>();
        for (var block : message.content()) {
            if (block instanceof ContentBlock.ToolUseContent call) {
                calls.add(call);
            }
        }
        return calls;
    }

    /**
     * 执行本轮的每个工具调用（pi {@code executeToolCalls}，{@code :409-424}）。
     *
     * <p>关键对齐点：</p>
     * <ul>
     *   <li>{@code tool_execution_start} 在**校验之前**发出 —— 被拒绝（denied）与未找到
     *       （unavailable）的调用**同样**收到 start 与 end（{@code :443-448} / {@code :498-503}）</li>
     *   <li>顺序模式：每个调用的 start / end / 结果消息**成组**发出（{@code :442-479}）</li>
     *   <li>并行模式：所有 start 先按**源序**发出，end 随各自完成；结果消息在所有调用
     *       结束后**严格按源序**补发（{@code :547-555}）</li>
     *   <li>{@code terminate} 取**全部** —— {@code shouldTerminateToolBatch} 是
     *       {@code every(terminate === true)}，非 any（{@code :589-591}）</li>
     * </ul>
     *
     * @param truncated {@code true} 表示助手消息以 {@code length} 收尾：参数可能被截断，
     *                  全部调用直接失败且**不执行**（{@code :379-404}）
     */
    static Batch run(List<ContentBlock.ToolUseContent> calls, PiLoop.Config config,
                     PiLoop.Sink emit, boolean truncated) {
        if (truncated) {
            return failTruncated(calls, emit);
        }
        return config.toolExecution() instanceof ToolExecution.Sequential
            ? executeSequential(calls, config, emit)
            : executeParallel(calls, config, emit);
    }

    /** pi {@code executeToolCallsSequential}：start → run → end → 结果消息，逐个成组。 */
    private static Batch executeSequential(List<ContentBlock.ToolUseContent> calls,
                                           PiLoop.Config config, PiLoop.Sink emit) {
        var messages = new ArrayList<Message.ToolResultMessage>();
        var outcomes = new ArrayList<PiLoop.ToolOutcome>();
        for (var call : calls) {
            emit.emit(new PiLoop.Event.ToolExecutionStart(
                call.id(), call.name(), call.arguments()));
            var outcome = config.toolRunner().run(
                new PiLoop.ToolCall(call.id(), call.name(), call.arguments(), false));
            outcomes.add(outcome);
            emit.emit(new PiLoop.Event.ToolExecutionEnd(
                call.id(), call.name(), outcome.result(), outcome.isError()));
            emit.emit(new PiLoop.Event.MessageStart(outcome.message()));
            emit.emit(new PiLoop.Event.MessageEnd(outcome.message()));
            messages.add(outcome.message());
            if (aborted(config)) {
                break;
            }
        }
        return new Batch(List.copyOf(messages), allTerminate(outcomes));
    }

    /**
     * pi {@code executeToolCallsParallel}：start 全部源序 → end 随完成 → 结果消息源序补发。
     *
     * <p><b>两相结构是语义的一部分</b>：pi 先在准备循环里把**全部**调用以源序发出
     * {@code tool_execution_start}，随后才在 {@code Promise.all} 里按各自完成序发
     * {@code tool_execution_end}（{@code :547} 与 {@code :550-553}）。因此「所有 start 都
     * 早于任何 end」是该模式的保证，消费者可据此判断批次何时真正开始收尾。</p>
     */
    private static Batch executeParallel(List<ContentBlock.ToolUseContent> calls,
                                         PiLoop.Config config, PiLoop.Sink emit) {
        for (var call : calls) {
            emit.emit(new PiLoop.Event.ToolExecutionStart(
                call.id(), call.name(), call.arguments()));
        }

        var outcomes = new ArrayList<PiLoop.ToolOutcome>();
        for (var call : calls) {
            // pi: 已中止时 prepareToolCall 直接返回 immediate 错误结果，**不执行**（:655-661）
            var outcome = aborted(config)
                ? abortedOutcome(call)
                : config.toolRunner().run(
                    new PiLoop.ToolCall(call.id(), call.name(), call.arguments(), false));
            outcomes.add(outcome);
            emit.emit(new PiLoop.Event.ToolExecutionEnd(
                call.id(), call.name(), outcome.result(), outcome.isError()));
        }
        var messages = new ArrayList<Message.ToolResultMessage>();
        for (var outcome : outcomes) {
            emit.emit(new PiLoop.Event.MessageStart(outcome.message()));
            emit.emit(new PiLoop.Event.MessageEnd(outcome.message()));
            messages.add(outcome.message());
        }
        return new Batch(List.copyOf(messages), allTerminate(outcomes));
    }

    /**
     * pi {@code failToolCallsFromTruncatedMessage}（{@code :379-404}）：输出被 token 上限
     * 截断时，每个调用都以错误结束且**不执行**（参数可能是被截断的残片）。
     *
     * <p>返回的 {@code terminate} 恒为 {@code false} —— 因此 {@code hasMoreToolCalls}
     * 为真，内层循环会**再请求一次**，让模型重新发出完整参数。</p>
     */
    private static Batch failTruncated(List<ContentBlock.ToolUseContent> calls, PiLoop.Sink emit) {
        var messages = new ArrayList<Message.ToolResultMessage>();
        for (var call : calls) {
            emit.emit(new PiLoop.Event.ToolExecutionStart(
                call.id(), call.name(), call.arguments()));
            var text = "Tool call \"" + call.name() + "\" was not executed: the response hit the "
                + "output token limit, so its arguments may be truncated. "
                + "Re-issue the tool call with complete arguments.";
            var message = new Message.ToolResultMessage(call.id(), call.name(),
                List.of(new ContentBlock.TextContent(text)), true);
            emit.emit(new PiLoop.Event.ToolExecutionEnd(call.id(), call.name(), text, true));
            emit.emit(new PiLoop.Event.MessageStart(message));
            emit.emit(new PiLoop.Event.MessageEnd(message));
            messages.add(message);
        }
        return new Batch(List.copyOf(messages), false);
    }

    /**
     * pi {@code createErrorToolResult("Operation aborted")}（{@code :637-641} / {@code :656-660}）：
     * 信号已中止时该调用**不执行**，但照样收到一个错误结果。
     */
    private static PiLoop.ToolOutcome abortedOutcome(ContentBlock.ToolUseContent call) {
        var text = "Operation aborted";
        return new PiLoop.ToolOutcome(
            new Message.ToolResultMessage(call.id(), call.name(),
                List.of(new ContentBlock.TextContent(text)), true),
            text, true, false);
    }

    /** pi {@code shouldTerminateToolBatch}：非空且**每一项**都 terminate。 */
    private static boolean allTerminate(List<PiLoop.ToolOutcome> outcomes) {
        if (outcomes.isEmpty()) {
            return false;
        }
        for (var outcome : outcomes) {
            if (!outcome.terminate()) {
                return false;
            }
        }
        return true;
    }

    private static boolean aborted(PiLoop.Config config) {
        return config.signal() != null && config.signal().isAborted();
    }
}
