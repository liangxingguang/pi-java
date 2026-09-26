package com.pijava.agent.harness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.hook.TurnUpdate;
import com.pijava.agent.prompt.SystemPromptOptions;
import com.pijava.agent.prompt.SystemPrompts;
import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ToolRegistry;
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

    /**
     * 系统提示的**当前值**：走 pi 的结构化段（{@link SystemPrompts}），不再是拼字符串。
     *
     * <p>入参的对应关系（逐条对 pi {@code BuildSystemPromptOptions}）：</p>
     * <ul>
     *   <li>车道的 {@code systemPrompt} → pi 的 {@code customPrompt}（**替换**默认 preamble，
     *       并抑制 {@code tools}/{@code rules}/{@code docs} 三段 —— 这是 pi 的语义，
     *       {@code system-prompt.ts:139-141}）；空串 ⇒ 走默认 preamble；</li>
     *   <li>车道生效的工具（**注册表序**，不是 {@code Set} 的迭代序）→ {@code selectedTools}，
     *       并派生 {@code toolSnippets}/{@code toolGuidelines}；</li>
     *   <li>车道的 {@code promptGuidelines}/{@code appendSystemPrompt} → 同名入参；</li>
     *   <li>技能与 cwd 照传。</li>
     * </ul>
     *
     * <p>⚠️ 工具的**顺序**必须稳定：{@code tools}/{@code rules} 两段的正文按
     * {@code selectedTools} 的顺序生成，而 {@code activeTools} 是 {@code Set}
     * （{@code Set.copyOf} 的迭代序在不同 JVM run 间可变）⇒ 直接用它会造出**每次启动都不一样**
     * 的提示，进而在续跑时吐出无意义的段补丁。故这里走
     * {@link ToolRegistry#activeOf}（跟注册表序），与
     * {@code PiLaneEngine.activeTools} 同一稳定源。</p>
     *
     * <p>⚠️ <b>{@code toolSnippets} 用的是「有片段用片段、否则用描述」</b>，而 pi 的
     * {@code tools} 段只收**声明了 {@code promptSnippet} 的**工具（{@code system-prompt.ts:151}）。
     * pi 的 8 个内置工具都声明了片段，pi-java 的工具一个都没声明 ⇒ 照 pi 的稀疏规则会得到
     * {@code (none)}，把工具清单整段抹掉。故此处保留 pi-java 既有的回落，并把
     * 「pi-java 的工具没有 promptSnippet/promptGuidelines」登记为缺口
     * （{@code docs/52 §12}）。</p>
     */
    String buildSystemPrompt(LaneState lane) {
        var tools = activeToolsInRegistryOrder();
        var snippets = new LinkedHashMap<String, String>();
        var guidelines = new LinkedHashMap<String, List<String>>();
        for (var tool : tools) {
            var snippet = tool.promptSnippet();
            snippets.put(tool.name(), snippet == null || snippet.isBlank() ? tool.description() : snippet);
            var toolRules = tool.promptGuidelines();
            if (toolRules != null && !toolRules.isEmpty()) {
                guidelines.put(tool.name(), List.copyOf(toolRules));
            }
        }
        var skills = List.copyOf(ctx.skillManager().all());
        var customPrompt = ctx.systemPrompt().get();
        var promptGuidelines = ctx.promptGuidelines().get();
        var appendSystemPrompt = ctx.appendSystemPrompt().get();
        if (!isPresent(customPrompt) && tools.isEmpty() && promptGuidelines.isEmpty()
                && skills.isEmpty() && !isPresent(appendSystemPrompt)) {
            // ⚠️ 什么都没配 ⇒ **空提示**，不是「默认提示」。这是 pi
            // `createInitialSystemMessage`（`utils/transcript.ts:12-22`）的空判据在 harness 层的
            // 对应物：pi 的 `createMutableAgentState` 见「提示与工具都空」就**不建**前导消息
            // （`agent.ts:84-85`），而 pi 生产恒由 coding-agent 供提示。agent-core 不该凭空造一份
            // —— 那会让 `AgentHarness` 的 SDK 用户（测试、evals、TUI）在毫无配置时凭白多出一条
            // 系统消息。`cwd` 不进这个判据：它恒有值，算进去就恒非空。
            return "";
        }
        var options = SystemPromptOptions.builder()
            .customPrompt(customPrompt)
            .selectedTools(tools.stream().map(AgentTool::name).toList())
            .toolSnippets(snippets)
            .toolGuidelines(guidelines)
            .promptGuidelines(promptGuidelines)
            .appendSystemPrompt(appendSystemPrompt)
            .skills(skills)
            .cwd(System.getProperty("user.dir", ""))
            .build();
        return SystemPrompts.build(options);
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isEmpty();
    }

    /** 生效工具本体，**注册表序**（{@code activeTools} 是 Set，其迭代序不可作提示的输入）。 */
    private List<AgentTool<?, ?>> activeToolsInRegistryOrder() {
        if (ctx.toolRegistry() == null) {
            return List.of();
        }
        var names = ctx.activeTools().get().stream().map(AgentTool::name).toList();
        return ctx.toolRegistry().activeOf(names);
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
