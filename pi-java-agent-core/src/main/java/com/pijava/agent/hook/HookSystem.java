package com.pijava.agent.hook;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.pijava.agent.harness.LaneState;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.record.UsageCause;
import com.pijava.agent.record.UsageCause;
import com.pijava.agent.tool.ToolResult;

/**
 * Manages lifecycle hook registration and firing for {@code AgentHarness}.
 *
 * <p>Hooks are stored per-lane and per-hook-name, allowing independent
 * hook chains for each lane. Hook execution errors are recorded as
 * {@link LaneRecord.HookError} and never propagate — hooks are non-fatal.</p>
 */
public final class HookSystem {

    private final HookRegistry registry = new HookRegistry();
    private final ConcurrentMap<String, LaneState> lanes;

    /** Create a hook system bound to the given lane map. */
    public HookSystem(ConcurrentMap<String, LaneState> lanes) {
        this.lanes = lanes;
    }

    // ═══════════════════════════════════════════════════════════
    // Registration (11 on* methods)
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

    /** Register a {@code before_request} hook for the lane. */
    public AutoCloseable onBeforeRequest(String laneName, BeforeRequestHook hook) {
        return registry.register(laneName, "before_request", hook);
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

    /** Register a {@code before_navigation} hook for the lane. */
    public AutoCloseable onBeforeNavigation(String laneName, BeforeNavigationHook hook) {
        return registry.register(laneName, "before_navigation", hook);
    }

    /** Register a {@code before_run_end} hook for the lane. */
    public AutoCloseable onBeforeRunEnd(String laneName, BeforeRunEndHook hook) {
        return registry.register(laneName, "before_run_end", hook);
    }

    // ═══════════════════════════════════════════════════════════
    // Firing (10 fire* methods)
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

    /** Fire all {@code before_request} hooks for the lane. */
    public void fireBeforeRequest(String laneName, RequestContext ctx) {
        fireVoid(laneName, "before_request",
            hook -> ((BeforeRequestHook) hook).beforeRequest(ctx));
    }

    /**
     * Fire {@code before_payload} hooks, chaining modifications.
     * Each hook receives the payload from the previous hook (or the original).
     * @return the final (possibly modified) payload
     */
    public Map<String, Object> fireBeforePayload(String laneName, Map<String, Object> payload) {
        Map<String, Object> result = payload;
        for (var hook : registry.get(laneName, "before_payload")) {
            try {
                var modified = ((BeforePayloadHook) hook).beforePayload(result);
                if (modified != null) result = modified;
            } catch (Exception e) {
                recordHookError(laneName, "before_payload", e);
            }
        }
        return result;
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
                recordHookError(laneName, "before_tool", e);
            }
        }
        return result;
    }

    /**
     * Fire {@code after_tool} hooks, chaining result modifications.
     * @return the final (possibly modified) tool result
     */
    public ToolResult<?> fireAfterTool(String laneName, ToolResultContext ctx) {
        ToolResult<?> result = ctx.result();
        for (var hook : registry.get(laneName, "after_tool")) {
            try {
                var r = ((AfterToolHook) hook).afterTool(
                    new ToolResultContext(ctx.lane(), ctx.toolCallId(), ctx.toolName(), result));
                if (r != null) result = r;
            } catch (Exception e) {
                recordHookError(laneName, "after_tool", e);
            }
        }
        return result;
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

    /** Fire all {@code before_navigation} hooks for the lane. */
    public void fireBeforeNavigation(String laneName, NavigationContext ctx) {
        fireVoid(laneName, "before_navigation",
            hook -> ((BeforeNavigationHook) hook).beforeNavigation(ctx));
    }

    /** Fire all {@code before_run_end} hooks for the lane. */
    public void fireBeforeRunEnd(String laneName, RunEndContext ctx) {
        fireVoid(laneName, "before_run_end",
            hook -> ((BeforeRunEndHook) hook).beforeRunEnd(ctx));
    }

    /**
     * Fire {@code transform_context} hooks, chaining message transformations.
     * @return the final (possibly modified) message list
     */
    public List<com.pijava.ai.message.Message> fireTransformContext(
            String laneName, List<com.pijava.ai.message.Message> messages) {
        List<com.pijava.ai.message.Message> result = messages;
        for (var hook : registry.get(laneName, "transform_context")) {
            try {
                var transformed = ((TransformContextHook) hook).transformContext(
                    List.copyOf(result));
                if (transformed != null) result = transformed;
            } catch (Exception e) {
                recordHookError(laneName, "transform_context", e);
            }
        }
        return result;
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

    private void recordHookError(String laneName, String hookName, Exception e) {
        // Hook failures are non-fatal. pi records them via usage records with
        // cause "hook"; the harness does not produce usage for hooks yet, so a
        // zero-usage record marks the event (Phase 4 §3.2).
        var lane = lanes.get(laneName);
        if (lane != null) {
            lane.records.add(new LaneRecord.UsageRecord(
                java.util.UUID.randomUUID().toString(), 0, laneName, null,
                com.pijava.ai.Usage.of(0, 0), UsageCause.HOOK,
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
    private static final class HookRegistry {
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
