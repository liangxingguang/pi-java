package com.pijava.agent.harness;

import com.pijava.agent.tool.ExecutionMode;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

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
     *   <li>并行模式：start 与准备按**源序**交替推进，准备相即失败的调用当场收尾，
     *       其余 end 随各自完成；结果消息在批次收束后**严格按源序**补发
     *       （{@code :497-554}）</li>
     *   <li>{@code terminate} 取**全部** —— {@code shouldTerminateToolBatch} 是
     *       {@code every(terminate === true)}，非 any（{@code :589-591}）</li>
     * </ul>
     *
     * @param truncated {@code true} 表示助手消息以 {@code length} 收尾：参数可能被截断，
     *                  全部调用直接失败且**不执行**（{@code :379-404}）
     */
    static Batch run(List<ContentBlock.ToolUseContent> calls, Context context,
                     PiLoop.Config config, PiLoop.Sink emit, boolean truncated) {
        if (truncated) {
            return failTruncated(calls, emit);
        }
        return useSequentialPath(calls, context, config)
            ? executeSequential(calls, context, config, emit)
            : executeParallel(calls, context, config, emit);
    }

    /**
     * pi {@code agent-loop.ts:417-421}：**批次里只要有一个 {@code sequential} 工具，
     * 整批就走顺序路径** —— 哪怕配置说的是 parallel。
     *
     * <p>此前这个判据在 pi-java 里**没有对应物**：{@code AgentTool.executionMode()} 生产零读者，
     * 于是一个含 bash 的批次被当成并行批次处理。pi 的规则是「一个慢/独占的工具会拖住整批」
     * —— 顺序路径会把 start/end/结果消息逐个成组发出，事件形状与并行路径**不同**。</p>
     */
    private static boolean useSequentialPath(List<ContentBlock.ToolUseContent> calls,
                                             Context context, PiLoop.Config config) {
        if (config.toolExecution() instanceof ToolExecution.Sequential) {
            return true;
        }
        for (var call : calls) {
            var tool = context.toolNamed(call.name());
            if (tool != null && tool.executionMode() instanceof ExecutionMode.Sequential) {
                return true;
            }
        }
        return false;
    }

    /** pi {@code executeToolCallsSequential}（{@code :431-485}）：start → 准备 → 执行 → end → 结果消息，逐个成组。 */
    private static Batch executeSequential(List<ContentBlock.ToolUseContent> calls,
                                           Context context, PiLoop.Config config,
                                           PiLoop.Sink emit) {
        var messages = new ArrayList<Message.ToolResultMessage>();
        var outcomes = new ArrayList<PiLoop.ToolOutcome>();
        for (var call : calls) {
            emit.emit(new PiLoop.Event.ToolExecutionStart(
                call.id(), call.name(), call.arguments()));
            // pi :451-468：immediate 与已执行的分岔只影响结果从哪来，收尾时序相同
            var outcome = switch (config.toolRunner().prepare(toolCall(call))) {
                case PiLoop.ImmediateOutcome immediate -> immediate.outcome();
                case PiLoop.Prepared prepared -> config.toolRunner().execute(prepared);
            };
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

    /** 内容块 → 端口请求。截断分支在 {@link #run} 顶部整体拦下，这里恒为 {@code false}。 */
    private static PiLoop.ToolCall toolCall(ContentBlock.ToolUseContent call) {
        return new PiLoop.ToolCall(call.id(), call.name(), call.arguments(), false);
    }

    /**
     * pi {@code executeToolCallsParallel}（{@code :487-560}）：**两相结构**。
     *
     * <p>发射顺序分三段，都是语义的一部分：</p>
     * <ol>
     *   <li>准备循环按**源序**交替发 start 与准备；准备相就失败的调用
     *       （拒绝 / 未找到 / 参数非法 / 已中止）**当场在准备循环内**收尾 end
     *       （{@code :506-517}）—— 它的 end 因此排在所有真正执行过的调用**之前**；</li>
     *   <li>拿到执行票的调用打包成延迟任务，end 由任务自己在完成时发
     *       （{@code :520-541}）；中止检查也发生在任务执行时，不是入队时
     *       （{@code :521-524}）；入队后同样查中止并 break（{@code :542-544}）；</li>
     *   <li>批次收束（pi 的 {@code Promise.all}，{@code :547}）之后，结果消息按**源序**
     *       补发（{@code :549-554}）。</li>
     * </ol>
     *
     * <p>注意该模式**不**保证「所有 start 早于任何 end」：一个准备相即失败的调用，
     * 其 end 会插在批次后续的 start 之前。此前 Java 侧所有 start 先批量发出、
     * 再逐个执行收尾，被拒绝调用的 end 排错了位置 —— L5 的 S4 只能靠
     * {@code PARALLEL_TOOL_END_ORDER} 放宽规则勉强对上，本方法按 pi 重排后该规则已删。</p>
     *
     * <p>延迟任务当前在 Java 里**串行**执行（确定性工具下与 pi 的完成序一致）；
     * 真正的并行执行是另一条独立缺口（B），与本方法的发射顺序无关。</p>
     */
    private static Batch executeParallel(List<ContentBlock.ToolUseContent> calls,
                                         Context context, PiLoop.Config config,
                                         PiLoop.Sink emit) {
        // pi 的 FinalizedToolCallEntry[]：immediate 已定局（end 已在准备循环里发过），
        // prepared 是待跑任务（自己在完成时发 end）。List 位置 = 源序。
        var entries = new ArrayList<Supplier<PiLoop.ToolOutcome>>();
        for (var call : calls) {
            emit.emit(new PiLoop.Event.ToolExecutionStart(
                call.id(), call.name(), call.arguments()));
            var preparation = config.toolRunner().prepare(toolCall(call));
            if (preparation instanceof PiLoop.ImmediateOutcome immediate) {
                emit.emit(new PiLoop.Event.ToolExecutionEnd(
                    call.id(), call.name(),
                    immediate.outcome().result(), immediate.outcome().isError()));
                entries.add(immediate::outcome);
                if (aborted(config)) {
                    break;
                }
                continue;
            }
            var prepared = (PiLoop.Prepared) preparation;
            entries.add(() -> {
                var outcome = aborted(config)
                    ? abortedOutcome(call)
                    : config.toolRunner().execute(prepared);
                emit.emit(new PiLoop.Event.ToolExecutionEnd(
                    call.id(), call.name(), outcome.result(), outcome.isError()));
                return outcome;
            });
            if (aborted(config)) {
                break;
            }
        }
        var outcomes = new ArrayList<PiLoop.ToolOutcome>();
        for (var entry : entries) {
            outcomes.add(entry.get());
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
