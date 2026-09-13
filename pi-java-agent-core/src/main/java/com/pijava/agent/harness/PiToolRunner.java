package com.pijava.agent.harness;

import java.util.List;
import java.util.Map;

import com.pijava.agent.hook.AfterToolOutcome;
import com.pijava.agent.hook.BeforeToolResult;
import com.pijava.agent.hook.HookSystem;
import com.pijava.agent.hook.ToolCallContext;
import com.pijava.agent.hook.ToolResultContext;
import com.pijava.agent.tool.ToolArgumentsValidator;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolRegistry;
import com.pijava.agent.tool.ToolResult;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

/**
 * {@link PiLoop.ToolRunner} 的实现：用 pi-java 现有的 {@link ToolRegistry} 与钩子执行
 * 一次工具调用，并把结果转成 pi 的 {@code ToolResultMessage}。
 *
 * <p><b>为什么是新类而不是复用 {@code ToolExecutionPipeline}</b>：后者与旧状态机在
 * 9 处耦合（{@code LaneState} × 7、{@code Action.ExecuteTool} × 9），无法供
 * {@link PiLoop} 的端口使用。</p>
 *
 * <p><b>两相拆分</b>（对应 pi {@code agent-loop.ts} 的三个函数：{@code prepareToolCall}
 * {@code :607-675}、{@code executePreparedToolCall} {@code :677-718}、
 * {@code finalizeExecutedToolCall} {@code :720-764}）：{@link #prepare} 查找工具、校验参数、
 * 跑 {@code before_tool} 钩子 —— 未找到 / 参数非法 / 钩子拒绝 / 已中止都返回
 * {@link PiLoop.ImmediateOutcome}（pi 的 {@code kind:"immediate"}）；{@link #execute} 才真正
 * 运行工具并跑 {@code after_tool} 钩子。这个区分是发射时序的前提：pi 在准备循环内就给
 * immediate 调用收尾。</p>
 *
 * <p><b>错误映射</b>：</p>
 * <ul>
 *   <li>钩子拒绝 ⇒ {@code allowed=false}，立即错误结果（pi 的 denied）</li>
 *   <li>工具未找到 / {@code ToolArgumentsValidator} 不过 ⇒ immediate（pi 的 unavailable / 校验）</li>
 *   <li>{@code SecurityException}（未获批准）与其余异常 ⇒ 已执行但失败（pi 在
 *       {@code executePreparedToolCall} 的 catch 里）</li>
 * </ul>
 * <p>各路都返回**错误结果消息**而非抛出 —— pi 对它们同样发 {@code tool_execution_start}
 * 与 {@code tool_execution_end}，由 {@link PiLoopTools} 负责发射。</p>
 *
 * <p><b>消息载荷（A7 已闭环）</b>：pi 的 {@code createToolResultMessage}
 * （{@code agent-loop.ts:784-797}）把结果树的 {@code details}/{@code usage}/
 * {@code addedToolNames} 原样转发到 {@link Message.ToolResultMessage} 上，随 entry
 * 落库；{@link #toOutcome} 是本 runner 内该转发的唯一合成点（错误路径经
 * {@link #errorOutcome} 汇到同一处）。序列化侧的省略规则（null/空 ⇒ 键缺席）在
 * {@code SessionJson.messageNode} 与 {@code WebWireJson}，L5 消息帧与 L3 四路
 * 往返各自钉住。</p>
 */
public final class PiToolRunner implements PiLoop.ToolRunner {

    /**
     * 工具执行期间的观测点（**pi-java 可观测性层，pi 无对应物**）。
     *
     * <p>它存在是因为「被 {@code before_tool} 拒绝」与「工具自己抛错」在 pi 的
     * {@code ToolResultMessage} 上都只是 {@code isError = true}，
     * {@code tool.execute} 跨度却要区分二者。放在这里而不是
     * {@link PiLoop.ToolOutcome} 上，是为了让 pi 的端口类型保持 1:1。</p>
     */
    @FunctionalInterface
    public interface ToolObserver {
        /** {@code allowed=false} 表示该校验/拒绝分支没有执行工具。 */
        void decided(String toolCallId, boolean allowed);
    }

    private final String laneName;
    private final ToolRegistry registry;
    private final HookSystem hooks;
    private final ToolContext toolContext;
    private final AbortSignal signal;
    private final ToolObserver observer;

    /**
     * @param laneName    车道名（传给钩子；pi 的钩子事件带 {@code lane}）
     * @param registry    工具注册表，负责查找、参数校验与执行；可为 {@code null}
     *                    （pi 的 {@code currentContext.tools?.find}：按「未找到」处理）
     * @param hooks       钩子系统，可为 {@code null}（无钩子时跳过 before/after_tool）
     * @param toolContext 工具上下文（工作目录、shell、文件系统）
     * @param signal      中止信号，可为 {@code null}
     * @param observer    观测点，可为 {@code null}
     */
    public PiToolRunner(String laneName, ToolRegistry registry, HookSystem hooks,
                        ToolContext toolContext, AbortSignal signal, ToolObserver observer) {
        this.laneName = laneName;
        this.registry = registry;
        this.hooks = hooks;
        this.toolContext = toolContext;
        this.signal = signal;
        this.observer = observer;
    }

    /**
     * pi 的 {@code prepareToolCall}（{@code agent-loop.ts:607-675}），顺序与 pi 一致：
     * 查找 → 校验 → {@code before_tool} → 中止检查 → 拒绝检查。pi 校验的是 {@code prepareArguments}
     * 之后的参数；Java 侧 {@code prepareArguments} 长在 {@code ToolRegistry.execute} 里，
     * 这里校验原始参数 —— 两道校验都不过才拿不到执行票，语义相同。
     */
    @Override
    public PiLoop.Preparation prepare(PiLoop.ToolCall call) {
        // pi {@code :613}：{@code tools?.find} —— 无注册表与查不到同一张面孔。
        var tool = registry == null ? null : registry.get(call.toolName());
        if (tool == null) {
            return immediate(call, true, "Tool " + call.toolName() + " not found", false);
        }
        BeforeToolResult decision;
        try {
            ToolArgumentsValidator.validate(tool.inputSchema(), call.args());
            decision = hooks == null ? null : hooks.fireBeforeTool(laneName,
                new ToolCallContext(laneName, call.toolCallId(), call.toolName(), call.args()));
        } catch (Exception e) {
            return immediate(call, true, messageOf(e), false);
        }
        // pi 查两次中止（钩子返回后 :636-641、交付 prepared 前 :655-661），且都排在
        // block 检查之前；中止是粘滞的、两次检查间无 await，一次即可 —— 中止压过拒绝。
        if (signal != null && signal.isAborted()) {
            return immediate(call, true, "Operation aborted", false);
        }
        if (decision != null && !decision.allowed()) {
            return immediate(call, false, denyReason(decision), decision.terminate());
        }
        notify(call.toolCallId(), true);
        var args = decision != null && decision.arguments() != null
            ? decision.arguments() : call.args();
        return new PreparedCall(call, args);
    }

    /**
     * pi 的 {@code executePreparedToolCall} + {@code finalizeExecutedToolCall}。
     *
     * <p><b>两段各有自己的 catch</b>（与 pi 一致，{@code :685-714} / {@code :731-757}）：
     * 工具抛的异常先在执行段转成错误结果并带着 {@code isError=true} 进入收尾段 ——
     * <b>{@code after_tool} 钩子对失败的执行同样会跑</b>（钩子能改写错误文本、也能把
     * {@code isError} 翻回去）；只有收尾段自己抛的异常才在这里转错误结果。</p>
     *
     * <p><b>流式更新</b>（pi {@code :680-704}）：工具经 update 回调流出的每个部分结果都
     * 直接发成 {@code tool_execution_update}，载荷 {@code args} 用<b>原始</b>调用的参数
     * （pi 的 {@code prepared.toolCall.arguments} —— 改写后的参数在 {@code prepared.args}
     * 字段里，不进事件）。执行返回后闩落下 {@code acceptingUpdates} —— 泄漏线程之后的
     * 更新被丢弃，与 pi 同。</p>
     */
    @Override
    public PiLoop.ToolOutcome execute(PiLoop.Prepared prepared, PiLoop.Sink emit) {
        if (!(prepared instanceof PreparedCall state)) {
            throw new IllegalArgumentException("执行票不是本 runner 签发的：" + prepared);
        }
        var call = state.call();
        var acceptingUpdates = new java.util.concurrent.atomic.AtomicBoolean(true);
        ToolResult<?> executed;
        boolean isError;
        try {
            executed = registry.execute(call.toolName(), call.toolCallId(), state.args(),
                signal, partial -> {
                    // pi :688：!acceptingUpdates 时静默丢弃
                    if (acceptingUpdates.get()) {
                        emit.emit(new PiLoop.Event.ToolExecutionUpdate(call.toolCallId(),
                            call.toolName(), call.args(), partial));
                    }
                }, toolContext);
            isError = false;
        } catch (Exception e) {
            // pi :711-714：createErrorToolResult(error.message) —— 内容换掉、标记为错，继续收尾
            executed = PiLoopTools.createErrorToolResult(messageOf(e));
            isError = true;
        } finally {
            acceptingUpdates.set(false);
        }
        try {
            var finalized = hooks == null ? new AfterToolOutcome(executed, isError)
                : hooks.fireAfterTool(laneName, new ToolResultContext(
                    laneName, call.toolCallId(), call.toolName(), executed, isError));
            return toOutcome(call, finalized.result(), finalized.isError());
        } catch (Exception e) {
            return errorOutcome(call, messageOf(e), false);
        }
    }

    /** 执行票：调用本身 + 钩子改写后的最终参数（pi 的 {@code PreparedToolCall.args}）。 */
    private record PreparedCall(PiLoop.ToolCall call, Map<String, Object> args)
            implements PiLoop.Prepared {}

    /** 准备相当场失败：通知观测点后打包成 immediate 结果。 */
    private PiLoop.Preparation immediate(PiLoop.ToolCall call, boolean allowed,
                                         String text, boolean terminate) {
        notify(call.toolCallId(), allowed);
        return new PiLoop.ImmediateOutcome(errorOutcome(call, text, terminate));
    }

    private void notify(String toolCallId, boolean allowed) {
        if (observer != null) {
            observer.decided(toolCallId, allowed);
        }
    }

    /** pi 的 {@code createToolResultMessage}（{@code :784-797}）：消息由结果对象派生。 */
    private static PiLoop.ToolOutcome toOutcome(PiLoop.ToolCall call,
                                                ToolResult<?> result, boolean isError) {
        // pi :791：content ?? [] —— 无类型工具可能返回无内容的结果，null 不进历史
        var content = result.content() != null ? result.content() : List.<ContentBlock>of();
        // pi :792-794：details/usage/addedToolNames 原样转发到消息上（A7 的闭环点）
        return new PiLoop.ToolOutcome(
            new Message.ToolResultMessage(call.toolCallId(), call.toolName(), content,
                result.details(), result.usage(), result.addedToolNames(), isError),
            result, isError);
    }

    /**
     * 立即失败（denied / unavailable / 执行异常 / 收尾异常）：pi 的
     * {@code createErrorToolResult} 形状（{@code content=[text]}、{@code details={}}），
     * 拒绝带 terminate 时补在结果对象上（pi {@code :645-647}）。
     * 消息与结果同源构造 —— pi 的 immediate 分支同样只经
     * {@code createToolResultMessage} 一条路（{@code :784-797}）。
     */
    private static PiLoop.ToolOutcome errorOutcome(PiLoop.ToolCall call, String text,
                                                   boolean terminate) {
        var base = PiLoopTools.createErrorToolResult(text);
        var result = terminate
            ? new ToolResult<>(base.content(), base.details(), null, true, List.of())
            : base;
        return toOutcome(call, result, true);
    }

    /** 钩子拒绝时的理由：{@code BeforeToolResult} 把 reason 放在 arguments 里；兜底文案对齐 pi 的 {@code reason || "Tool execution was blocked"}（{@code :643}）。 */
    private static String denyReason(BeforeToolResult decision) {
        Map<String, Object> arguments = decision.arguments();
        var reason = arguments == null ? null : arguments.get("reason");
        var text = reason == null ? null : String.valueOf(reason);
        return text == null || text.isEmpty() ? "Tool execution was blocked" : text;
    }

    private static String messageOf(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
