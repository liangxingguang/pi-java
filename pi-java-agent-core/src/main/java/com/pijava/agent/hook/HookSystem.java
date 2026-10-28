package com.pijava.agent.hook;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.pijava.agent.harness.LaneState;
import com.pijava.agent.harness.PiLoop;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.record.UsageCause;
import com.pijava.agent.tool.ToolResult;

/**
 * Manages lifecycle hook registration and firing for {@code AgentHarness}.
 *
 * <p>Hooks are stored per-lane and per-hook-name, allowing independent
 * hook chains for each lane. Hook execution errors are recorded as
 * {@link LaneRecord.HookError} and never propagate — hooks are non-fatal.
 * <b>两个工具钩子除外</b>：pi 把 {@code beforeToolCall} 的异常转成 immediate 错误结果
 * （{@code agent-loop.ts:668-673}）、{@code afterToolCall} 的异常转成错误结果
 * （{@code :754-757}）——所以这两个先记录、再**向上重抛**，由 {@code PiToolRunner}
 * 的 catch 完成转换；其余钩子族维持「非致命」。</p>
 *
 * <p>车道名仍是登记键（一个 harness 只有一条车道，{@code 原 docs/31 §4.3}）——
 * 扩展注册时给出会话的车道名，触发时按同一名字取回。</p>
 */
public final class HookSystem {

    private final HookRegistry registry = new HookRegistry();
    private final LaneState lane;
    /** 链式合并簇（结构债拆分，纯移动）—— 本体在 {@link HookChains}。 */
    private final HookChains chains;

    /** Create a hook system bound to the harness's lane. */
    public HookSystem(LaneState lane) {
        this.lane = lane;
        this.chains = new HookChains(registry, this);
    }

    // ═══════════════════════════════════════════════════════════
    // Registration（13 个 on* 方法；before_navigation 死钩已随 docs/18 裁决① 删除）
    // ═══════════════════════════════════════════════════════════

    /** Register a {@code before_run} hook for the lane. */
    public AutoCloseable onBeforeRun(String laneName, BeforeRunHook hook) {
        return registry.register(laneName, "before_run", hook);
    }

    /** Register a {@code before_resume} hook for the lane. */
    public AutoCloseable onBeforeResume(String laneName, BeforeResumeHook hook) {
        return registry.register(laneName, "before_resume", hook);
    }

    /** Register a {@code transform_context} hook for the lane. */
    public AutoCloseable onTransformContext(String laneName, TransformContextHook hook) {
        return registry.register(laneName, "transform_context", hook);
    }

    /** Register a {@code before_request} hook for the lane（pi 的 {@code prepareRequest}；注册键保留 B221 族先例）。 */
    public AutoCloseable onPrepareRequest(String laneName, PrepareRequestHook hook) {
        return registry.register(laneName, "before_request", hook);
    }

    /**
     * 批 27（B162）：路由级 prepareRequest——pi {@code _installAgentRequestProjection}
     * （agent-session.ts:787-844）的对位。与 {@link #onPrepareRequest} 的差别：
     * 异常<b>不被隔离</b>——pi 的路由失败 reject 会以错误响应结束本次 run
     * （:808-809 注释），java 让异常传出循环、由 {@code PiLaneEngine} 的
     * {@code catch (Throwable)}（runWithLifecycle 对位）收成运行失败。
     * 发射序：沙箱扩展钩先跑（pi 的 previousPrepareRequest），路由钩最后看
     * 到累积结果（pi :800-810 的序）。
     */
    public AutoCloseable onPrepareRequestRouted(String laneName, PrepareRequestHook hook) {
        return registry.register(laneName, "before_request_routed", hook);
    }

    /** Register a {@code before_payload} hook for the lane. */
    public AutoCloseable onBeforePayload(String laneName, BeforePayloadHook hook) {
        return registry.register(laneName, "before_payload", hook);
    }

    /** Register an {@code after_response} hook for the lane. */
    public AutoCloseable onAfterResponse(String laneName, AfterResponseHook hook) {
        return registry.register(laneName, "after_response", hook);
    }

    /** Register a {@code before_tool} hook for the lane. */
    public AutoCloseable onBeforeTool(String laneName, BeforeToolHook hook) {
        return registry.register(laneName, "before_tool", hook);
    }

    /** Register an {@code after_tool} hook for the lane. */
    public AutoCloseable onAfterTool(String laneName, AfterToolHook hook) {
        return registry.register(laneName, "after_tool", hook);
    }

    /** Register a {@code before_compaction} hook for the lane. */
    public AutoCloseable onBeforeCompaction(String laneName, BeforeCompactionHook hook) {
        return registry.register(laneName, "before_compaction", hook);
    }

    /** Register a {@code before_run_end} hook for the lane. */
    public AutoCloseable onBeforeRunEnd(String laneName, BeforeRunEndHook hook) {
        return registry.register(laneName, "before_run_end", hook);
    }

    /** Register a {@code should_stop_after_turn} hook for the lane（pi 的 {@code finishTurn}）。 */
    public AutoCloseable onFinishTurn(String laneName, ShouldStopAfterTurnHook hook) {
        return registry.register(laneName, "should_stop_after_turn", hook);
    }

    /** Register a {@code prepare_next_turn} hook for the lane. */
    public AutoCloseable onPrepareNextTurn(String laneName, PrepareNextTurnHook hook) {
        return registry.register(laneName, "prepare_next_turn", hook);
    }

    /** Register a {@code message_end} hook for the lane（docs/15 裁决①：pi {@code emitMessageEnd} 的引擎侧落点）。 */
    public AutoCloseable onMessageEnd(String laneName, MessageEndHook hook) {
        return registry.register(laneName, "message_end", hook);
    }

    /** Register a {@code before_settle} hook for the lane（docs/15 裁决②：pi {@code _runBeforeSettleBoundary} 的引擎侧落点）。 */
    public AutoCloseable onBeforeSettle(String laneName, BeforeSettleHook hook) {
        return registry.register(laneName, "before_settle", hook);
    }

    // ═══════════════════════════════════════════════════════════
    // Firing（13 个 fire* 方法）
    // ═══════════════════════════════════════════════════════════

    /** Fire all {@code before_run} hooks for the lane. */
    public void fireBeforeRun(String laneName, RunContext ctx) {
        fireVoid(laneName, "before_run",
            hook -> ((BeforeRunHook) hook).beforeRun(ctx));
    }

    /** Fire all {@code before_resume} hooks for the lane. */
    public void fireBeforeResume(String laneName, ResumeContext ctx) {
        fireVoid(laneName, "before_resume",
            hook -> ((BeforeResumeHook) hook).beforeResume(ctx));
    }

    /**
     * Fire {@code before_request} hooks（pi 的 {@code prepareRequest}）—— 链式合并体在
     * {@link HookChains}（结构债拆分，纯移动）。
     *
     * @return 合并后的 update；{@code null} 表示没有钩子发言
     */
    public PiLoop.RequestUpdate firePrepareRequest(String laneName,
                                                   PiLoop.PrepareRequestContext request) {
        return chains.firePrepareRequest(laneName, request);
    }

    /**
     * Fire {@code before_payload} hooks, chaining modifications —— 链式合并体在
     * {@link HookChains}（结构债拆分，纯移动）。
     * @return the final (possibly modified) payload
     */
    public Map<String, Object> fireBeforePayload(String laneName, Map<String, Object> payload) {
        return chains.fireBeforePayload(laneName, payload);
    }

    /** Fire all {@code after_response} hooks for the lane. */
    public void fireAfterResponse(String laneName, ResponseContext ctx) {
        fireVoid(laneName, "after_response",
            hook -> ((AfterResponseHook) hook).afterResponse(ctx));
    }

    /**
     * Fire {@code before_tool} hooks.
     * Short-circuits on first denial; accumulates argument modifications.
     * @return the effective {@code BeforeToolResult}, or a default proceed
     */
    public BeforeToolResult fireBeforeTool(String laneName, ToolCallContext ctx) {
        BeforeToolResult result = BeforeToolResult.allow();
        for (var hook : registry.get(laneName, "before_tool")) {
            try {
                var r = ((BeforeToolHook) hook).beforeTool(ctx);
                if (r != null && !r.allowed()) return r;
                if (r != null && r.arguments() != null) result = r;
            } catch (Exception e) {
                // pi 的 prepareToolCall 把钩子异常 catch 成 immediate 错误结果
                // （agent-loop.ts:668-673）—— 这里记账后向上重抛，转换点在 PiToolRunner.prepare。
                recordHookError(laneName, "before_tool", e);
                throw e;
            }
        }
        return result;
    }

    /**
     * Fire {@code after_tool} hooks, merging each patch **field-by-field** into the
     * result — pi's {@code finalizeExecutedToolCall}（{@code agent-loop.ts:744-752}）:
     * 未设的字段保留原值，{@code isError} 由补丁翻转（初值取执行相的判定 —— pi 的
     * {@code finalizeExecutedToolCall} 直接继承 {@code executed.isError}，{@code :729}）。
     * 钩子异常先记账再**向上重抛**（pi 的转换点 {@code :754-757}，在 Java 侧对应
     * {@code PiToolRunner.execute} 的 catch → 错误结果）。
     *
     * @return the merged result plus the error flag, as pi's finalized outcome
     */
    public AfterToolOutcome fireAfterTool(String laneName, ToolResultContext ctx) {
        var result = ctx.result();
        var isError = ctx.isError();
        for (var hook : registry.get(laneName, "after_tool")) {
            try {
                var patch = ((AfterToolHook) hook).afterTool(
                    new ToolResultContext(ctx.lane(), ctx.toolCallId(), ctx.toolName(),
                        result, isError, ctx.arguments()));
                if (patch != null) {
                    result = applyAfterToolPatch(result, patch);
                    if (patch.isError() != null) {
                        isError = patch.isError();
                    }
                }
            } catch (Exception e) {
                recordHookError(laneName, "after_tool", e);
                throw e;
            }
        }
        return new AfterToolOutcome(result, isError);
    }

    /**
     * pi 的 {@code ??} 合并：补丁字段为 null = 保留原值（pi 的 null/undefined 同样落右操作数，清不成 null）。
     *
     * <p>docs/14 修真：旧实现走 5 参构造器重建，任何补丁都会把 {@code isError} 洗成
     * {@code false}、{@code structuredContent} 洗成 {@code null} —— pi 的合并是逐字段的，
     * 未设的字段必须原样活着。structuredContent 三支：drop 优先 ⇒ null；补丁给值 ⇒ 替换；
     * 否则保留（pi {@code emitToolResult} 的 delete/独立合并/保留三态，runner.ts:1194-1214）。</p>
     */
    private static ToolResult<Object> applyAfterToolPatch(ToolResult<?> cur, AfterToolPatch p) {
        return new ToolResult<>(
            p.content() != null ? p.content() : cur.content(),
            p.details() != null ? p.details() : cur.details(),
            p.usage() != null ? p.usage() : cur.usage(),
            p.terminate() != null ? p.terminate() : cur.terminate(),
            cur.addedToolNames(),
            cur.isError(),
            p.dropStructuredContent() ? null
                : p.structuredContent() != null ? p.structuredContent()
                : cur.structuredContent());
    }

    /**
     * Fire {@code before_compaction} hooks.
     * @return the first non-null {@code CompactionPlan} from any hook, or null
     */
    public CompactionPlan fireBeforeCompaction(String laneName, CompactionContext ctx) {
        for (var hook : registry.get(laneName, "before_compaction")) {
            try {
                var plan = ((BeforeCompactionHook) hook).beforeCompaction(ctx);
                if (plan != null) return plan;
            } catch (Exception e) {
                recordHookError(laneName, "before_compaction", e);
            }
        }
        return null;
    }

    /** Fire all {@code before_run_end} hooks for the lane. */
    public void fireBeforeRunEnd(String laneName, RunEndContext ctx) {
        fireVoid(laneName, "before_run_end",
            hook -> ((BeforeRunEndHook) hook).beforeRunEnd(ctx));
    }

    /**
     * Fire {@code transform_context} hooks, chaining message transformations ——
     * 链式合并体在 {@link HookChains}（结构债拆分，纯移动）。
     * @return the final (possibly modified) message list
     */
    public List<com.pijava.ai.message.Message> fireTransformContext(
            String laneName, List<com.pijava.ai.message.Message> messages) {
        return chains.fireTransformContext(laneName, messages);
    }

    /**
     * Fire {@code should_stop_after_turn} hooks，合并语义照 pi 的会话层链接
     * （{@code agent-session.ts:891-895}）：<b>任一 END 短路即赢</b>；无 END 有
     * CONTINUE ⇒ CONTINUE；全弃权 ⇒ {@code null}（≙ pi 的 undefined，照常调度）。
     * 抛错的钩子记账并弃权。
     *
     * @return 合并裁决；{@code null} 表示没有钩子发言
     */
    public PiLoop.TurnDecision fireFinishTurn(String laneName, ShouldStopAfterTurnContext ctx) {
        boolean sawContinue = false;
        for (var hook : registry.get(laneName, "should_stop_after_turn")) {
            try {
                var r = ((ShouldStopAfterTurnHook) hook).shouldStopAfterTurn(ctx);
                if (r == PiLoop.TurnDecision.END) {
                    return PiLoop.TurnDecision.END;
                }
                if (r == PiLoop.TurnDecision.CONTINUE) {
                    sawContinue = true;
                }
            } catch (Exception e) {
                recordHookError(laneName, "should_stop_after_turn", e);
            }
        }
        return sawContinue ? PiLoop.TurnDecision.CONTINUE : null;
    }

    /**
     * Fire {@code prepare_next_turn} hooks, chaining updates —— 链式合并体在
     * {@link HookChains}（结构债拆分，纯移动）。
     *
     * @return the merged update, or null when no hook produced one
     */
    public TurnUpdate firePrepareNextTurn(String laneName, PrepareNextTurnContext ctx) {
        return chains.firePrepareNextTurn(laneName, ctx);
    }

    /**
     * Fire {@code message_end} hooks（docs/15 裁决①，pi {@code runner.ts:1144-1181} 的
     * 链式语义）：按注册序逐钩调用，<b>后钩看到前钩的替换结果</b>；任一钩抛异常记账并
     * 弃权（非致命族）；全程无替换 ⇒ {@code null}。
     *
     * @return 链终替换消息；{@code null} 表示没有钩子发言（调用方用原消息）
     */
    public com.pijava.ai.message.Message fireMessageEnd(String laneName, MessageEndContext ctx) {
        com.pijava.ai.message.Message current = ctx.message();
        boolean modified = false;
        for (var hook : registry.get(laneName, "message_end")) {
            try {
                var r = ((MessageEndHook) hook).messageEnd(
                    new MessageEndContext(ctx.lane(), ctx.runId(), current));
                if (r != null) {
                    current = r;
                    modified = true;
                }
            } catch (Exception e) {
                recordHookError(laneName, "message_end", e);
            }
        }
        return modified ? current : null;
    }

    /**
     * Fire {@code before_settle} hooks（docs/15 裁决②）：任一 {@code TRUE} 短路即赢；
     * 无 TRUE 有 FALSE ⇒ FALSE；全弃权 ⇒ {@code null}（调用方按无桥行为收口——
     * pi 此刻队列必空，弃权与 {@code hasQueuedMessages()} 同值）。抛错的钩子记账并弃权。
     */
    public Boolean fireBeforeSettle(String laneName, BeforeSettleContext ctx) {
        Boolean decision = null;
        for (var hook : registry.get(laneName, "before_settle")) {
            try {
                var r = ((BeforeSettleHook) hook).beforeSettle(ctx);
                if (Boolean.TRUE.equals(r)) {
                    return Boolean.TRUE;
                }
                if (r != null) {
                    decision = r;
                }
            } catch (Exception e) {
                recordHookError(laneName, "before_settle", e);
            }
        }
        return decision;
    }

    // ═══════════════════════════════════════════════════════════
    // Internal helpers
    // ═══════════════════════════════════════════════════════════

    /** Functional interface for void-returning hook invocations. */
    @FunctionalInterface
    private interface HookRunner {
        void run(Object hook) throws Exception;
    }

    /** Generic void-hook dispatcher: iterate hooks, catch and record errors. */
    private void fireVoid(String laneName, String hookName, HookRunner runner) {
        for (var hook : registry.get(laneName, hookName)) {
            try {
                runner.run(hook);
            } catch (Exception e) {
                recordHookError(laneName, hookName, e);
            }
        }
    }

    /**
     * 钩子失败非致命：失败本身照旧 {@code throw} 冒泡（调用点记完就抛），这里只补审计。
     *
     * <p>⚠️ <b>它不是 pi 的行为</b>。旧注释写「pi records them via usage records with
     * cause "hook"」，但换锚后 pi 侧 {@code HookError}/{@code hookError} **零命中**，
     * 且 {@code appendUsage} 全仓只有一个调用者（缓存预热的 {@code cache-warmer.ts:342}）
     * —— 那条引用出自旧锚，已失效。本仓保留这个标记，并让它穿上 pi 的行形状
     * （{@link com.pijava.agent.entry.Entry.Usage}，{@code kind:"hook"}、
     * provider/model 空串、零用量）。</p>
     *
     * <p>⚠️ 它是**一条 entry**，所以会推进车道叶 —— 而这里跑在**工具线程**上
     * （{@code PiToolRunner} 的 worker）。追加必须走
     * {@link com.pijava.agent.harness.LaneState#appendDeferredEntry}：
     * 与宿主线程共享车道监视器，否则两边会读到同一个叶、生成两条同父的 entry。</p>
     */
    void recordHookError(String laneName, String hookName, Exception e) {
        if (lane.laneName().equals(laneName)) {
            lane.appendDeferredEntry((seq, parentId) -> new com.pijava.agent.entry.Entry.Usage(
                java.util.UUID.randomUUID().toString(), seq, parentId, java.time.Instant.now(),
                UsageCause.HOOK.value(), "", "",
                com.pijava.ai.Usage.of(0, 0), null,
                "", null, null, null, null));
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Hook registry (internal)
    // ═══════════════════════════════════════════════════════════

    /**
     * Thread-safe registry mapping {@code laneName/hookName → List<hook>}.
     *
     * <p>Hooks are stored as {@code Object} because the registry is shared across
     * all hook types, each with a different functional interface. Callers are
     * responsible for casting to the correct type based on the hook name key —
     * the registration path guarantees type safety through the string key convention.</p>
     */
    static final class HookRegistry {
        // key = laneName + "/" + hookName
        private final ConcurrentMap<String, List<Object>> store = new ConcurrentHashMap<>();

        AutoCloseable register(String lane, String hookName, Object hook) {
            var key = lane + "/" + hookName;
            store.computeIfAbsent(key, k -> new ArrayList<>()).add(hook);
            return () -> {
                var list = store.get(key);
                if (list != null) list.remove(hook);
            };
        }

        /**
         * Returns a snapshot of registered hooks for the given lane and hook name.
         *
         * @return immutable snapshot of the hook list, or empty list if none registered
         */
        List<Object> get(String lane, String hookName) {
            var list = store.get(lane + "/" + hookName);
            return list != null ? List.copyOf(list) : List.of();
        }
    }
}
