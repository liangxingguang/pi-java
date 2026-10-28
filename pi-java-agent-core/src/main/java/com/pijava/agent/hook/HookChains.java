package com.pijava.agent.hook;

import java.util.List;
import java.util.Map;

import com.pijava.agent.harness.PiLoop;

/**
 * {@link HookSystem} 的**链式合并簇**抽离（结构债拆分：行为零变化纯移动，
 * javadoc/注释逐字随代码搬）—— {@code before_request}／{@code before_payload}／
 * {@code transform_context}／{@code prepare_next_turn} 四个「后钩看到前钩结果」的
 * 合并体。{@link HookSystem} 留一行委托；钩子失败记账回呼
 * {@link HookSystem#recordHookError}（包私有）。
 */
final class HookChains {

    private final HookSystem.HookRegistry registry;
    private final HookSystem owner;

    HookChains(HookSystem.HookRegistry registry, HookSystem owner) {
        this.registry = registry;
        this.owner = owner;
    }

    /**
     * Fire {@code before_request} hooks（pi 的 {@code prepareRequest}，
     * {@code agent-loop.ts:219-239}），链式合并：按注册序逐钩调用，<b>后钩看到前钩的
     * 替换结果</b>；任一钩抛异常记账并弃权（与 {@link HookSystem#fireFinishTurn} 同纪律）；
     * 全弃权 ⇒ {@code null}。
     *
     * @return 合并后的 update；{@code null} 表示没有钩子发言
     */
    PiLoop.RequestUpdate firePrepareRequest(String laneName,
                                            PiLoop.PrepareRequestContext request) {
        PiLoop.RequestUpdate accumulated = null;
        for (var hook : registry.get(laneName, "before_request")) {
            try {
                // 后钩看到前钩的替换结果：已积累的组件覆盖入参快照。
                var visible = accumulated == null ? request
                    : new PiLoop.PrepareRequestContext(
                        accumulated.context() != null ? accumulated.context() : request.context(),
                        accumulated.model() != null ? accumulated.model() : request.model(),
                        accumulated.thinking() != null ? accumulated.thinking() : request.thinking(),
                        request.failedResponse());
                var u = ((PrepareRequestHook) hook).prepareRequest(visible);
                if (u != null) {
                    accumulated = new PiLoop.RequestUpdate(
                        u.context() != null ? u.context()
                            : accumulated != null ? accumulated.context() : null,
                        u.model() != null ? u.model()
                            : accumulated != null ? accumulated.model() : null,
                        u.thinking() != null ? u.thinking()
                            : accumulated != null ? accumulated.thinking() : null);
                }
            } catch (Exception e) {
                owner.recordHookError(laneName, "before_request", e);
            }
        }
        // 批 27（B162）：路由级钩在沙箱链<b>之后</b>跑（pi 的 projection 先调
        // previousPrepareRequest 再路由，agent-session.ts:800-810 的序）；异常
        // 不隔离（pi 路由失败 reject ⇒ run 错误收场）。
        var routed = registry.get(laneName, "before_request_routed");
        if (!routed.isEmpty()) {
            var visible = accumulated == null ? request
                : new PiLoop.PrepareRequestContext(
                    accumulated.context() != null ? accumulated.context() : request.context(),
                    accumulated.model() != null ? accumulated.model() : request.model(),
                    accumulated.thinking() != null ? accumulated.thinking() : request.thinking(),
                    request.failedResponse());
            for (var hook : routed) {
                var u = ((PrepareRequestHook) hook).prepareRequest(visible);
                if (u != null) {
                    visible = new PiLoop.PrepareRequestContext(
                        u.context() != null ? u.context() : visible.context(),
                        u.model() != null ? u.model() : visible.model(),
                        u.thinking() != null ? u.thinking() : visible.thinking(),
                        visible.failedResponse());
                    accumulated = new PiLoop.RequestUpdate(
                        visible.context() != request.context() ? visible.context()
                            : accumulated != null ? accumulated.context() : null,
                        visible.model(), visible.thinking());
                }
            }
        }
        return accumulated;
    }

    /**
     * Fire {@code before_payload} hooks, chaining modifications.
     * Each hook receives the payload from the previous hook (or the original).
     * @return the final (possibly modified) payload
     */
    Map<String, Object> fireBeforePayload(String laneName, Map<String, Object> payload) {
        Map<String, Object> result = payload;
        for (var hook : registry.get(laneName, "before_payload")) {
            try {
                var modified = ((BeforePayloadHook) hook).beforePayload(result);
                if (modified != null) result = modified;
            } catch (Exception e) {
                owner.recordHookError(laneName, "before_payload", e);
            }
        }
        return result;
    }

    /**
     * Fire {@code transform_context} hooks, chaining message transformations.
     * @return the final (possibly modified) message list
     */
    List<com.pijava.ai.message.Message> fireTransformContext(
            String laneName, List<com.pijava.ai.message.Message> messages) {
        List<com.pijava.ai.message.Message> result = messages;
        for (var hook : registry.get(laneName, "transform_context")) {
            try {
                var transformed = ((TransformContextHook) hook).transformContext(
                    List.copyOf(result));
                if (transformed != null) result = transformed;
            } catch (Exception e) {
                owner.recordHookError(laneName, "transform_context", e);
            }
        }
        return result;
    }

    /**
     * Fire {@code prepare_next_turn} hooks, chaining updates. Each non-null
     * return merges over the accumulated result; a throwing hook is recorded
     * and abstains.
     *
     * @return the merged update, or null when no hook produced one
     */
    TurnUpdate firePrepareNextTurn(String laneName, PrepareNextTurnContext ctx) {
        TurnUpdate result = null;
        for (var hook : registry.get(laneName, "prepare_next_turn")) {
            try {
                var u = ((PrepareNextTurnHook) hook).prepareNextTurn(ctx);
                if (u != null) {
                    result = new TurnUpdate(
                        u.model() != null ? u.model()
                            : result != null ? result.model() : null,
                        u.thinkingLevel() != null ? u.thinkingLevel()
                            : result != null ? result.thinkingLevel() : null);
                }
            } catch (Exception e) {
                owner.recordHookError(laneName, "prepare_next_turn", e);
            }
        }
        return result;
    }
}
