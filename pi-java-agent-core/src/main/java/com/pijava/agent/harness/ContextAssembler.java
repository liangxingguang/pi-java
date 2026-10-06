package com.pijava.agent.harness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.hook.TurnUpdate;
import com.pijava.agent.prompt.SystemPromptOptions;
import com.pijava.agent.prompt.SystemPrompts;
import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ToolRegistry;
import com.pijava.ai.api.Transcripts;
import com.pijava.ai.message.Message;
import com.pijava.ai.thinking.ModelThinkingLevel;

/**
 * 车道上下文的**投影**：系统提示、工作副本重建、配置变更落盘、{@code transform_context}。
 *
 * <p>本类由旧的装配器收窄而来（{@code 原 docs/31 §4.2}）。它此前在**每次请求**上做三件事
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
     * 当前系统提示的**选项**（pi 的 {@code BuildSystemPromptOptions}）；什么都没配 ⇒ {@code null}。
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
     * （{@code 原 docs/52 §12}）。</p>
     */
    SystemPromptOptions promptOptions(LaneState lane) {
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
        var contextFiles = ctx.contextFiles().get();
        if (!isPresent(customPrompt) && tools.isEmpty() && promptGuidelines.isEmpty()
                && skills.isEmpty() && !isPresent(appendSystemPrompt)
                && contextFiles.isEmpty()) {
            // ⚠️ 什么都没配 ⇒ **没有提示**，而不是「默认提示」。这是 pi
            // `createInitialSystemMessage`（`utils/transcript.ts:12-22`）的空判据在 harness 层的
            // 对应物：pi 的 `createMutableAgentState` 见「提示与工具都空」就**不建**前导消息
            // （`agent.ts:84-85`），而 pi 生产恒由 coding-agent 供提示。agent-core 不该凭空造一份
            // —— 那会让 `AgentHarness` 的 SDK 用户（测试、evals、TUI）在毫无配置时凭白多出一条
            // 系统消息。`cwd` 不进这个判据：它恒有值，算进去就恒非空。
            return null;
        }
        return SystemPromptOptions.builder()
            .customPrompt(customPrompt)
            .selectedTools(tools.stream().map(AgentTool::name).toList())
            .toolSnippets(snippets)
            .toolGuidelines(guidelines)
            .promptGuidelines(promptGuidelines)
            .appendSystemPrompt(appendSystemPrompt)
            .skills(skills)
            .contextFiles(contextFiles)
            .cwd(System.getProperty("user.dir", ""))
            .build();
    }

    /**
     * 渲染出的整份提示文本 —— **派生值**，不进任何行为判据。
     *
     * <p>两个用途：{@code Context.systemPrompt}（pi 生产路径上那个字段是死的，见
     * {@code 原 docs/52 §2 P12}；java 保留它是为了让请求录制／诊断看得见模型实际收到的提示）与
     * {@link PiLaneSink} 的记账。pi 的等价物是
     * {@code getSystemMessageText(state)}（{@code system-prompt.ts:196}）。</p>
     */
    String renderPrompt(LaneState lane) {
        var options = promptOptions(lane);
        return options == null ? "" : SystemPrompts.build(options);
    }

    /**
     * pi {@code _preparePromptAndToolLoadout} 的**差分那一半**（{@code agent-session.ts:1158-1167}）：
     * 把「模型当前拥有的段」（从转录重放）与「想要的段」比，产出补丁消息或 {@code null}。
     *
     * <p>产出的消息是 {@code {content: "", sections: 补丁, timestamp: now}} —— 与 pi 逐字同形。
     * 它的 {@code toolsAdded} **不由这里写**：调用方把它放进 pending 之后，
     * {@link ToolChangeDeclaration} 会把工具增删**合并进同一条消息**（pi 的
     * {@code declareToolChanges} 锚点 = pending 里最后一条系统消息）。</p>
     *
     * <p>{@code messages} 是**差分基准**的来源，与 pi 同参数位（pi 默认
     * {@code agent.state.messages}；压缩后传重建过的列表）。</p>
     */
    Message.SystemMessage promptLoadout(LaneState lane, List<Message> messages) {
        var options = promptOptions(lane);
        if (options == null) {
            return null;
        }
        var current = Transcripts.getCurrentSystemMessage(messages);
        var previous = current == null ? Map.<String, String>of() : current.sections();
        var patch = SystemPrompts.diff(previous, SystemPrompts.buildSections(options));
        if (patch == null) {
            return null;
        }
        return new Message.SystemMessage("", Instant.now(), patch, List.of(), List.of());
    }

    /** 生效工具本体，**注册表序**（{@code activeTools} 是 Set，其迭代序不可作提示的输入）。 */
    private List<AgentTool<?, ?>> activeToolsInRegistryOrder() {
        if (ctx.toolRegistry() == null) {
            return List.of();
        }
        var names = ctx.activeTools().get().stream().map(AgentTool::name).toList();
        return ctx.toolRegistry().activeOf(names);
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isEmpty();
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
                // Applied mid-run by a prepare_next_turn hook ⇒ deferred.
                lane.appendDeferredEntry((seq, parentId) -> new Entry.ModelChange(
                    UUID.randomUUID().toString(), seq, parentId, Instant.now(),
                    upd.model().provider(), upd.model().modelName()));
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
                // Applied mid-run by a prepare_next_turn hook ⇒ deferred.
                lane.appendDeferredEntry((seq, parentId) -> new Entry.ThinkingLevelChange(
                    UUID.randomUUID().toString(), seq, parentId, Instant.now(),
                    upd.thinkingLevel()));
                lane.recordedThinking = upd.thinkingLevel();
            }
        }
    }
}
