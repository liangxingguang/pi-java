package com.pijava.agent.harness;

import java.util.List;
import java.util.Set;

import com.pijava.agent.tool.AgentTool;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.thinking.ModelThinkingLevel;

/**
 * {@link AgentHarness} 的**模型/思考级别/系统提示/工具门面簇**抽离（结构债拆分：
 * 行为零变化纯移动，javadoc/注释逐字随代码搬）—— 沿用 {@link HarnessBootstrap}
 * 的包私有中间基类先例（#94，docs/10 §8）。{@code AgentHarness} 经继承获得的成员
 * 与其原声明逐一同形，公共 API 面（签名与语义）不变。所触字段均在
 * {@link HarnessBootstrap}（同包可见）。
 */
abstract class HarnessPromptAndToolAccessors extends HarnessBootstrap {

    HarnessPromptAndToolAccessors(HarnessConfig config) {
        super(config);
    }

    // ═══════════════════════════════════════════════════════════
    // Model / Thinking / Tools
    // ═══════════════════════════════════════════════════════════

    public ModelId<?> getModel() { return lane.model; }
    /** Set the model used for subsequent LLM calls（同时把变更写进 transcript，见 §4.1）. */
    public void setModel(ModelId<?> model) {
        if (closed) throw new HarnessClosedException();
        lane.model = model;
        runLifecycle.recordModelChange(AgentHarness.DEFAULT_LANE, model);
    }
    public ModelThinkingLevel getThinkingLevel() { return lane.thinkingLevel; }
    /** Set the thinking level used for subsequent LLM calls（变更时才写 entry，见 §4.1）. */
    public void setThinkingLevel(ModelThinkingLevel level) {
        if (closed) throw new HarnessClosedException();
        lane.thinkingLevel = level;
        runLifecycle.recordConfigChanged(AgentHarness.DEFAULT_LANE);
    }

    /** Change the system prompt for subsequent runs. */
    public void setSystemPrompt(String prompt) {
        if (closed) throw new HarnessClosedException();
        lane.systemPrompt = prompt;
    }

    /** The current system prompt. */
    public String getSystemPrompt() {
        return lane.systemPrompt;
    }

    /**
     * 系统提示 {@code rules} 段的追加准则条目（包 A4b）。
     *
     * <p>pi 的同一件事是 {@code BuildSystemPromptOptions.promptGuidelines} —— 它是**内容**
     * （表达风格之类），因此由宿主给，而不是由本模块硬编码。</p>
     */
    public void setPromptGuidelines(List<String> guidelines) {
        if (closed) throw new HarnessClosedException();
        lane.promptGuidelines = guidelines == null ? List.of() : List.copyOf(guidelines);
    }

    /** 追加提示（包 A4b；pi 的 {@code appendSystemPrompt}，渲染成 {@code addendum} 段）。 */
    public void setAppendSystemPrompt(String text) {
        if (closed) throw new HarnessClosedException();
        lane.appendSystemPrompt = text == null ? "" : text;
    }

    /** 当前追加提示（包 A4b）。 */
    public String getAppendSystemPrompt() {
        return lane.appendSystemPrompt;
    }

    /** 当前系统提示 {@code rules} 段的追加准则条目（包 A4b）。 */
    public List<String> getPromptGuidelines() {
        return List.copyOf(lane.promptGuidelines);
    }

    public Set<AgentTool<?, ?>> getActiveTools() {
        return Set.copyOf(lane.activeTools);
    }

    /**
     * 替换激活工具集（pi {@code _applyToolLoadout} 只写 {@code agent.state.tools}
     * 的对位，agent-session.ts:1575-1579）。
     *
     * <p>批 16（T2a，裁决①）：<b>注册面与激活面分离</b>——本方法不再清/重填
     * {@code toolRegistry}（旧语义会把扩展后注册的工具从注册表抹掉）。</p>
     */
    public void setActiveTools(Set<AgentTool<?, ?>> tools) {
        if (closed) throw new HarnessClosedException();
        lane.activeTools = Set.copyOf(tools);
    }

    /**
     * 按名并入激活集（批 16 T2d：pi 的「注册即激活」臂，
     * agent-session.ts:3603 对位）——注册表查无的名静默忽略。
     */
    public void activateTools(java.util.Collection<String> names) {
        if (closed) throw new HarnessClosedException();
        if (toolRegistry == null) {
            return;
        }
        var next = new java.util.LinkedHashSet<>(lane.activeTools);
        for (var name : names) {
            var tool = toolRegistry.get(name);
            if (tool != null) {
                next.add(tool);
            }
        }
        lane.activeTools = Set.copyOf(next);
    }

    /**
     * 批 24（docs/35 D4）：pi {@code _pendingToolNames} 的宿主访问器
     * （lane 级集合；线程纪律同 {@code activeTools}——车道装配/事件线程）。
     */
    public java.util.Set<String> pendingToolNames() {
        return lane.pendingToolNames;
    }

    /**
     * 批 16（T5b）：pi {@code syncQueueModesFromSettings} 对位——/reload 后
     * steer/followUp 排空模式随设置（pi agent-session.ts:3679 同位）。
     */
    public void setQueueModes(QueueMode steering, QueueMode followUp) {
        if (closed) throw new HarnessClosedException();
        lane.steeringMode = steering;
        lane.followUpMode = followUp;
    }

    /**
     * 系统提示自定义段（批 16 T3a；pi {@code BuildSystemPromptOptions.sections}）。
     * 唯一生产者是扩展 {@code before_agent_start} 的写回。
     */
    public void setPromptSections(java.util.Map<String, String> sections) {
        if (closed) throw new HarnessClosedException();
        lane.promptSections = sections == null ? java.util.Map.of() : java.util.Map.copyOf(sections);
    }

    /** 当前系统提示自定义段（批 16 T3a）。 */
    public java.util.Map<String, String> getPromptSections() {
        return lane.promptSections;
    }
}
