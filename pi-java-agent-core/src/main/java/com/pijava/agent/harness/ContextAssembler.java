package com.pijava.agent.harness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.hook.TurnUpdate;
import com.pijava.agent.prompt.SystemPromptBuilder;
import com.pijava.ai.message.Message;
import com.pijava.ai.thinking.ModelThinkingLevel;

/**
 * 车道上下文的**投影**：系统提示、工作副本重建、配置变更落盘、{@code transform_context}。
 *
 * <p>本类由旧的装配器收窄而来（{@code docs/31 §4.2}）。它此前在**每次请求**上做三件事
 * ——建系统提示、从 entry 日志重走 {@code pathToLeaf} 重建消息、fire
 * {@code transform_context}。对齐 pi 后只剩最后一件留在请求路径上：</p>
 *
 * <ul>
 *   <li>系统提示改由 run 起点装进 {@code Context}（pi 的 {@code AgentState.systemPrompt}
 *       是字段，{@link #buildSystemPrompt} 只在起手调用一次）；</li>
 *   <li>消息改由 {@link LaneState#messages} 工作副本承载，只在日志整体替换时
 *       {@link #rebuildMessages 重建}；</li>
 *   <li>{@code transform_context} 留在循环里，与 pi 的
 *       {@code AgentLoopConfig.transformContext} 同处（{@link #transformContext}）。</li>
 * </ul>
 */
final class ContextAssembler {

    private final ExecutionContext ctx;

    ContextAssembler(ExecutionContext ctx) {
        this.ctx = ctx;
    }

    // ═══════════════════════════════════════════════════════════
    // 系统提示
    // ═══════════════════════════════════════════════════════════

    /** Build the system prompt (base + tools + skills) for the run. */
    String buildSystemPrompt(LaneState lane) {
        var effectivePrompt = lane.systemPrompt != null
            ? lane.systemPrompt : ctx.systemPrompt().get();
        var effectiveTools = lane.activeTools != null
            ? lane.activeTools : ctx.activeTools().get();
        return new SystemPromptBuilder()
            .base(effectivePrompt)
            .tools(effectiveTools)
            .skills(ctx.skillManager().all())
            .build();
    }

    // ═══════════════════════════════════════════════════════════
    // 请求路径
    // ═══════════════════════════════════════════════════════════

    /**
     * pi {@code AgentLoopConfig.transformContext}：转成 provider 消息之前的最后一处改写。
     *
     * <p>与 pi 的钩子签名一致，只看得到消息（系统提示与工具定义不经此处，
     * 它们在 {@link Context} 上）。</p>
     */
    List<Message> transformContext(String laneName, List<Message> messages) {
        var transformed = ctx.hookSystem().fireTransformContext(laneName, messages);
        return transformed == null ? messages : new ArrayList<>(transformed);
    }

    // ═══════════════════════════════════════════════════════════
    // 配置变更（pi 的 prepare_next_turn 钩子内的 append* + setModel）
    // ═══════════════════════════════════════════════════════════

    /**
     * 落成 {@code prepare_next_turn} 钩子要求的配置变更：字段赋值 + 写 entry，同处发生。
     *
     * <p>此前这个变更被暂存到 {@link LaneState#pendingTurnUpdate}、留到**下一次请求前**
     * 才应用 —— 那是把「钩子改了配置」当成装配的一部分。pi 里
     * {@code prepareNextTurnWithContext} 自己 {@code appendModelChange} /
     * {@code setModel}（{@code agent-session.ts:1687}），返回给循环的只是
     * {@code {model, reasoning}}。这里照该形状，在钩子返回点就地应用。</p>
     *
     * <p>变更判定只作用于 **entry**（模型无条件写、思考等级变了才写），与 §4.1 一致；
     * {@link ExecutionContext#turnConfigApplier()} 无论是否变更都要调 —— 等级从 null
     * 解析成具体值这类「字面不同但语义是设置」的情形由它兜底。</p>
     */
    void applyTurnUpdate(String laneName, LaneState lane, TurnUpdate upd) {
        if (upd.model() != null) {
            var current = ctx.model().get();
            boolean changed = current == null
                || !current.modelName().equals(upd.model().modelName())
                || !current.provider().equals(upd.model().provider());
            ctx.turnConfigApplier().accept(upd.model(), null);
            if (changed) {
                var e = new Entry.ModelChange(UUID.randomUUID().toString(), lane.nextSeq(),
                    HarnessUtils.lastEntryId(lane), Instant.now(),
                    upd.model().provider(), upd.model().modelName());
                lane.transcript.add(e);
                // Applied mid-run by a prepare_next_turn hook ⇒ deferred.
                HarnessUtils.recordDeferredWrite(lane, e);
            }
        }
        if (upd.thinkingLevel() != null) {
            var cur = ctx.thinkingLevel().get();
            boolean changed;
            if ("off".equals(upd.thinkingLevel())) {
                changed = !(cur instanceof ModelThinkingLevel.Off);
            } else {
                changed = !(cur instanceof ModelThinkingLevel.Enabled en
                    && en.level().label().equals(upd.thinkingLevel()));
            }
            ctx.turnConfigApplier().accept(null, upd.thinkingLevel());
            if (changed) {
                var e = new Entry.ThinkingLevelChange(UUID.randomUUID().toString(), lane.nextSeq(),
                    HarnessUtils.lastEntryId(lane), Instant.now(), upd.thinkingLevel());
                lane.transcript.add(e);
                lane.recordedThinking = upd.thinkingLevel();
                // Applied mid-run by a prepare_next_turn hook ⇒ deferred.
                HarnessUtils.recordDeferredWrite(lane, e);
            }
        }
    }
}
