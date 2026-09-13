package com.pijava.agent.harness;

import java.util.List;
import java.util.Map;

import com.pijava.agent.hook.HookSystem;
import com.pijava.agent.hook.ToolCallContext;
import com.pijava.agent.hook.ToolResultContext;
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
 * <p><b>错误映射</b>（对应 pi 的 {@code prepareToolCall} 三条路）：</p>
 * <ul>
 *   <li>钩子拒绝 ⇒ {@code allowed=false}，返回立即错误结果（pi 的 denied）</li>
 *   <li>{@code IllegalArgumentException} ⇒ 工具未找到或参数非法（pi 的 unavailable）</li>
 *   <li>{@code SecurityException} ⇒ 未获批准（{@code ToolRegistry} 的 approvalHandler）</li>
 *   <li>其余异常 ⇒ 工具自身失败</li>
 * </ul>
 * <p>四条路都返回**错误结果消息**而非抛出 —— pi 对它们同样发 {@code tool_execution_start}
 * 与 {@code tool_execution_end}，由 {@link PiLoopTools} 负责发射。</p>
 *
 * <p><b>已知缺口（A7，未在本步修）</b>：pi 的 {@code ToolResultMessage} 带 {@code details}，
 * 而 pi-java 的 {@link Message.ToolResultMessage} 只有
 * {@code (toolUseId, toolName, content, isError)} —— 因此 {@code details} 只能经事件的
 * {@code result} 传到 wire，**无法随 entry 落库**。这是 `docs/23` 的 A7 项。</p>
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
     * @param registry    工具注册表，负责查找、参数校验与执行
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

    @Override
    public PiLoop.ToolOutcome run(PiLoop.ToolCall call) {
        var decision = hooks == null ? null : hooks.fireBeforeTool(laneName,
            new ToolCallContext(laneName, call.toolCallId(), call.toolName(), call.args()));
        if (decision != null && !decision.allowed()) {
            notify(call.toolCallId(), false);
            return errorOutcome(call, denyReason(decision), decision.terminate());
        }
        notify(call.toolCallId(), true);
        var args = decision != null && decision.arguments() != null
            ? decision.arguments() : call.args();
        try {
            var result = registry.execute(call.toolName(), call.toolCallId(), args,
                signal, null, toolContext);
            var finalized = hooks == null ? result : hooks.fireAfterTool(laneName,
                new ToolResultContext(laneName, call.toolCallId(), call.toolName(), result));
            return toOutcome(call, finalized != null ? finalized : result, false);
        } catch (Exception e) {
            return errorOutcome(call, messageOf(e), false);
        }
    }

    private void notify(String toolCallId, boolean allowed) {
        if (observer != null) {
            observer.decided(toolCallId, allowed);
        }
    }

    /** pi 的 {@code createToolResultMessage}：内容块 + 错误标记进消息，details 只上事件。 */
    private static PiLoop.ToolOutcome toOutcome(PiLoop.ToolCall call,
                                                ToolResult<?> result, boolean isError) {
        return new PiLoop.ToolOutcome(
            new Message.ToolResultMessage(call.toolCallId(), call.toolName(),
                result.content(), isError),
            result.details(),
            isError,
            result.terminate());
    }

    /** 立即失败（denied / unavailable / 执行异常）：内容为单块文本，terminate 由调用方给。 */
    private static PiLoop.ToolOutcome errorOutcome(PiLoop.ToolCall call, String text,
                                                   boolean terminate) {
        var message = new Message.ToolResultMessage(call.toolCallId(), call.toolName(),
            List.of(new ContentBlock.TextContent(text == null ? "" : text)), true);
        return new PiLoop.ToolOutcome(message, text, true, terminate);
    }

    /** 钩子拒绝时的理由：{@code BeforeToolResult} 把 reason 放在 arguments 里。 */
    private static String denyReason(com.pijava.agent.hook.BeforeToolResult decision) {
        Map<String, Object> arguments = decision.arguments();
        if (arguments == null) {
            return "Tool call denied";
        }
        var reason = arguments.get("reason");
        return reason == null ? "Tool call denied" : String.valueOf(reason);
    }

    private static String messageOf(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
