package com.pijava.coding.agent.core;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import com.pijava.agent.harness.PiLoop;
import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ToolResult;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.NestedToolCalls;

/**
 * Runs nested tool calls — pi {@code NestedToolCallRunner}
 * （{@code core/nested-tool-calls.ts:161-263}）。
 *
 * <p>要点对位：</p>
 * <ul>
 *   <li>嵌套 id＝{@code <callerId>/<n>}（:187-192），每 caller 一个 scope 计数器</li>
 *   <li>子 scope 与父<b>共享 recorder</b>（:214-218）——任意深度的嵌套调用都归并到
 *       模型发起调用的记录上</li>
 *   <li>独占队列（:202-213）：宿主全局串行或工具 {@code executionMode=sequential} 时
 *       排队；已持队列的调用（{@code holdsQueue}）其嵌套调用不再排</li>
 *   <li>三事件带 {@code parentToolCallId}；onUpdate 先转发调用方回调再发射事件
 *       （:227-231）</li>
 *   <li><b>永不因工具失败抛异常</b>（:249 注释）——失败以 {@code isError=true} 的结果返回</li>
 *   <li>嵌套结果不持久化，usage 只经 recorder 汇总（:238-239 注释）</li>
 * </ul>
 *
 * <p><b>并发方言</b>（窄差 D161）：pi 的 Promise 链串行队列 ⇒ java 用一把
 * {@link ReentrantLock}；调用发生在工具线程（虚拟线程）上，阻塞即 pi 的 await。</p>
 */
public final class NestedToolCallRunner {

    /**
     * 宿主抽象（pi {@code NestedToolCallHost}，:131-144 逐字四方法）。
     * 由 {@code AgentSession} 实现（T3）。
     */
    public interface Host {

        /** pi {@code getTools}：可调用工具集（active direct＋全部 codemode/deferred）。 */
        List<AgentTool<?, ?>> getTools();

        /** pi {@code isSequential}：宿主全局串行档（{@code agent.toolExecution}）。 */
        boolean isSequential();

        /**
         * pi {@code runToolCall}（:136-141）：走与模型调用完全相同的校验/钩子/权限
         * 管线；工具失败以 {@code isError} 结果返回，不抛。
         */
        PiLoop.ToolOutcome runToolCall(PiLoop.ToolCall toolCall, String parentToolCallId,
                                       AbortSignal signal, Consumer<Object> onUpdate);

        /** pi {@code emit}（:142）：先扩展 runner，再 session 订阅者（宿主侧保证顺序）。 */
        void emit(AgentSessionEvent event);
    }

    /** pi {@code NestedToolCallOptions}（:103-108）。 */
    public record Options(AbortSignal signal, Consumer<Object> onUpdate) {

        /** 无选项。 */
        public static Options none() {
            return new Options(null, null);
        }
    }

    /** pi {@code NestedCallSummary}（:36-41）：{@code takeRecord} 的出参。 */
    public record Summary(NestedToolCalls calls, ToolResult.UsageInfo usage) {}

    /** pi {@code CallScope}（:147-152）。 */
    private record CallScope(NestedCallRecorder recorder, AtomicInteger nextId,
                             boolean holdsQueue) {}

    private final Host host;
    private final Map<String, CallScope> scopes = new ConcurrentHashMap<>();
    /** pi 的 {@code queueTail} Promise 链（:165）⇒ 一把锁（串行化语义同，结构异）。 */
    private final ReentrantLock queueLock = new ReentrantLock();

    public NestedToolCallRunner(Host host) {
        this.host = host;
    }

    /**
     * pi {@code execute}（:176-250）。
     *
     * @param callerId 调用方工具调用的 id（模型发起的那次）
     * @param name     嵌套调用的工具名
     * @param args     参数
     * @param options  signal/onUpdate（signal 缺省继承调用方——宿主在更上层已处理）
     */
    public PiLoop.ToolOutcome execute(String callerId, String name,
                                      Map<String, Object> args, Options options) {
        // :182-186：取/建 caller 的 scope
        var scope = scopes.computeIfAbsent(callerId,
            k -> new CallScope(new NestedCallRecorder(), new AtomicInteger(1), false));
        // :187-192：嵌套 id
        var toolCall = new PiLoop.ToolCall(
            callerId + "/" + scope.nextId().getAndIncrement(), name, args, false);
        // :193
        var record = scope.recorder().start(toolCall);
        // :194-200
        host.emit(new AgentSessionEvent.ToolExecutionStart(
            toolCall.toolCallId(), name, args, callerId));

        // :202-205：独占判定
        var tool = findTool(name);
        boolean exclusive = !scope.holdsQueue()
            && (host.isSequential()
                || (tool != null && tool.executionMode() instanceof ExecutionMode.Sequential));
        // spotbugs UL 钉：条件 lock＋finally 条件 unlock 的形会被钉——独占臂走
        // 独立的 lock→try→finally（无条件 unlock）路径，两路径共享 executeHeld。
        if (exclusive) {
            queueLock.lock();
            try {
                return executeHeld(toolCall, scope, true, callerId, name, args, options,
                    record);
            } finally {
                queueLock.unlock();
            }
        }
        return executeHeld(toolCall, scope, scope.holdsQueue(), callerId, name, args,
            options, record);
    }

    /**
     * pi :214-249 的持锁段：子 scope 登记（共享 recorder；holdsQueue 逐字
     * {@code scope.holdsQueue || exclusive}）→ runToolCall → finally 摘子 scope →
     * finish/usage/end 事件。
     */
    private PiLoop.ToolOutcome executeHeld(PiLoop.ToolCall toolCall, CallScope scope,
            boolean holdsQueue, String callerId, String name, Map<String, Object> args,
            Options options, com.pijava.ai.message.NestedToolCallRecord record) {
        scopes.put(toolCall.toolCallId(), new CallScope(scope.recorder(),
            new AtomicInteger(1), holdsQueue));
        PiLoop.ToolOutcome outcome;
        try {
            // :221-231
            outcome = host.runToolCall(toolCall, callerId,
                options != null ? options.signal() : null, partial -> {
                    if (options != null && options.onUpdate() != null) {
                        options.onUpdate().accept(partial);
                    }
                    host.emit(new AgentSessionEvent.ToolExecutionUpdate(
                        toolCall.toolCallId(), name, args, partial, callerId));
                });
        } finally {
            // :232-235
            scopes.remove(toolCall.toolCallId());
        }
        // :237
        scope.recorder().finish(record, outcome.isError(), textOf(outcome.result()));
        // :238-239：usage 只经 recorder 计入
        if (outcome.result() != null && outcome.result().usage() != null) {
            scope.recorder().addUsage(outcome.result().usage());
        }
        // :240-248
        host.emit(new AgentSessionEvent.ToolExecutionEnd(toolCall.toolCallId(), name,
            outcome.result(), outcome.isError(),
            outcome.message() == null ? null : outcome.message().durationMs(), callerId));
        return outcome;
    }

    /**
     * pi {@code takeRecord}（:253-258）：取出并删除 scope（<b>只能取一次</b>），
     * 无 scope 返回 {@code null}。
     */
    public Summary takeRecord(String toolCallId) {
        var scope = scopes.remove(toolCallId);
        if (scope == null) {
            return null;
        }
        return new Summary(scope.recorder().snapshot(), scope.recorder().totalUsage());
    }

    /** pi {@code clear}（:260-262）：agent_end 时防泄漏。 */
    public void clear() {
        scopes.clear();
    }

    /** pi :203 的工具查找（host.getTools() 按名）。 */
    private AgentTool<?, ?> findTool(String name) {
        for (var candidate : host.getTools()) {
            if (candidate.name().equals(name)) {
                return candidate;
            }
        }
        return null;
    }

    /** pi {@code textOf}（:154-159）：拼接结果的所有 text 块。 */
    private static String textOf(ToolResult<?> result) {
        if (result == null || result.content() == null) {
            return "";
        }
        var text = new StringBuilder();
        for (var block : result.content()) {
            if (block instanceof ContentBlock.TextContent t) {
                text.append(t.text());
            }
        }
        return text.toString();
    }
}
