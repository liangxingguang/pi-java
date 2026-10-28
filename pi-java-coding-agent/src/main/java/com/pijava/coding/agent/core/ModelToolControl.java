package com.pijava.coding.agent.core;

import java.util.List;

import com.pijava.agent.tool.AgentTool;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.thinking.ModelThinkingLevel;

/**
 * 批 29（结构债拆分）：{@link AgentSession} 的模型/工具管理簇 ——
 * setThinkingLevel／cycleModelForward／setModel／激活门三件套／exposure 四门，
 * 以及 scopedModels／modelCatalog／toolSelection 三字段。
 * 自 {@code AgentSession} 纯移动（行为零变化）；javadoc/注释逐字随迁。
 */
final class ModelToolControl {

    private final AgentSession owner;

    // B137：--models/settings patterns 解析出的 scope（Ctrl+P / cycle_model 在其间轮转）。
    private List<com.pijava.ai.model.ScopedModel> scopedModels = List.of();
    // scope 解析与轮转都对全目录（assemble 构造的同一实例）。
    private com.pijava.ai.catalog.ModelCatalog modelCatalog =
        com.pijava.ai.catalog.ModelCatalog.empty();
    /** T5a：工具选择策略（reload 的 defaultTools diff／装配后激活臂的操作数）。 */
    private ToolSelection.Resolved toolSelection;

    ModelToolControl(AgentSession owner) {
        this.owner = owner;
    }

    /**
     * 设置思考级别，**变化时**广播 {@link AgentSessionEvent.ThinkingLevelChanged}
     * （pi {@code agent-session.ts:1934-1962} 的 {@code setThinkingLevel}）。
     *
     * <p>⚠️ 包H5 步8：此前这个事件<b>零生产者</b> —— 声明了、被 {@code JsonEventMapper}
     * 消费了，但没人发。生产调用点（RPC 的 {@code set_thinking_level} ／
     * {@code cycle_thinking_level}、Web 的 {@code SetThinkingLevel}、{@code processPrompt}）
     * 原先都直呼 {@code harness().setThinkingLevel(...)}，绕过了会话事件流。</p>
     *
     * <p>pi 的 {@code isChanging} 门：级别没变就<b>不发</b>（也不写 transcript）。</p>
     *
     * <p>⚠️ pi 还会在这里把级别<b>夹到模型可用集</b>（{@code clampThinkingLevel}）并落
     * transcript。夹取在 pi-java 由车道侧的 {@code AnthropicThinking.resolve} 按模型能力
     * 处理（{@code 原 docs/46 §3-D2} 的范围裁决）；transcript 落盘属另一条线，本包不做。</p>
     */
    ModelThinkingLevel setThinkingLevel(ModelThinkingLevel level) {
        var previous = owner.harness().getThinkingLevel();
        owner.harness().setThinkingLevel(level);
        if (!java.util.Objects.equals(previous, level)) {
            owner.emitSessionEvent(new AgentSessionEvent.ThinkingLevelChanged(level));
        }
        return level;
    }

    /** 装配期写入 scope（B137）；不可变拷贝。 */
    void setScopedModels(List<com.pijava.ai.model.ScopedModel> models) {
        this.scopedModels = List.copyOf(models);
    }

    /** 批 13（T11/T12）：装配目录只读面（miss 价目查询与 /session Cache Re-billed）。 */
    com.pijava.ai.catalog.ModelCatalog modelCatalog() {
        return modelCatalog;
    }

    /** 装配目录写面（wireSession 装配期回填）。 */
    void modelCatalog(com.pijava.ai.catalog.ModelCatalog catalog) {
        this.modelCatalog = catalog;
    }

    /** 当前 scope（测试/RPC 只读面）。 */
    List<com.pijava.ai.model.ScopedModel> scopedModels() {
        return scopedModels;
    }

    /** 工具选择策略写面（wireSession 装配期回填）。 */
    void toolSelection(ToolSelection.Resolved selection) {
        this.toolSelection = selection;
    }

    /** 工具选择策略读面（{@link SessionReload}／{@link ExtensionLoading}）。 */
    ToolSelection.Resolved toolSelection() {
        return toolSelection;
    }

    /**
     * B85（批 9，docs/18 §4.5）：工具执行期的图片入模策略，经
     * {@link com.pijava.agent.tool.ToolContext#withImagePolicy} 晚绑定。
     *
     * <ul>
     *   <li>{@code autoResize}：{@code settings.images.autoResize ?? true}（pi settings-manager
     *       :1298-1300），每次现读，设置文件改动下次工具执行即生效；</li>
     *   <li>{@code resize}：当前车道模型的 {@code inputLimits.images.resize}，缺席 null
     *       （引擎走 2000/2000/4.5MiB/80 默认档，等同 pi 无模型档位时）；</li>
     *   <li>{@code supportsImages}：命中目录读 {@link com.pijava.ai.catalog.ModelInfo#supportsImageInput()}
     *       （未知按支持的既有裁决），未编目模型 ⇒ true。</li>
     * </ul>
     */
    com.pijava.agent.tool.ImagePolicy currentImagePolicy() {
        return ImagePolicies.resolve(
            owner.services().settings().effective(), modelCatalog, owner.harness().getModel());
    }

    /**
     * Ctrl+P / {@code cycle_model}（pi agent-session.ts:2519-2560）：scope 非空在其间
     * 正向轮转，空 scope 在全目录轮转；≤1/无变化不动。思考档走
     * {@code thinkingLevelForModelSwitch}（批 11 T2）：scope 显式级别 &gt;
     * per-model 覆盖 &gt; 全局默认 &gt; 继承当前（pi :2548 的 explicit 臂＋
     * :2669-2681 三臂）。
     */
    void cycleModelForward() {
        var current = owner.harness().getModel();
        var next = ScopedModelCycler.next(current, scopedModels, modelCatalog);
        if (next.equals(current)) {
            return;
        }
        // 批 11 T2（pi :2548）：显式级别＝scope 条目的级别；解析不出 ⇒ 不动
        // （继承当前档，pi undefined 语义）。
        owner.harness().setModel(next);
        var level = owner.thinkingLevelForModelSwitch(next,
            ScopedModelCycler.levelFor(next, scopedModels).orElse(null));
        if (level != null) {
            setThinkingLevel(com.pijava.coding.agent.cli.ThinkingLevels.parse(level));
        }
        // 批 16（T6b）：pi cycleModel 的 model_select 发射点（:2565/:2597）——
        // 直呼 harness.setModel 绕过会话层，故在此处补发射（不改道 setModel：
        // cycleModelForward 的思考档语义与 setModel 不同）。
        if (owner.extensionFirer() != null) {
            owner.extensionFirer().modelSelect(next, current, "cycle");
        }
    }

    /**
     * pi {@code setModel}（agent-session.ts:2477-2496）：换模型并解析新思考档——
     * per-model 覆盖 &gt; 全局默认 &gt; 继承当前（pi 的 {@code ?? this.thinkingLevel
     * ?? DEFAULT} 两臂：java harness 恒有当前档，解析不出就不动，语义等价；
     * 「全无配置时写 thinking-level entry」的 pi 臂不移植，窄差 D54，裁决⑦）。
     *
     * <p>pi {@code setModel} 的 persist（写默认模型）与 auth 预检面 java 本无
     * （既有登记，docs/16 裁决①）。</p>
     */
    void setModel(ModelId<?> model) {
        var previous = owner.harness().getModel();
        var level = owner.thinkingLevelForModelSwitch(model, null);
        owner.harness().setModel(model);
        if (level != null) {
            setThinkingLevel(com.pijava.coding.agent.cli.ThinkingLevels.parse(level));
        }
        // 批 16（T6b）：pi setModel 尾的 _emitModelSelect（:2496，在思考档变更
        // 之后）；同模型不发射（pi modelsAreEqual 门逐字）。
        if (owner.extensionFirer() != null && !java.util.Objects.equals(previous, model)) {
            owner.extensionFirer().modelSelect(model, previous, "set");
        }
    }

    // ── 批 16（docs/27）：激活门三件套（pi agent-session.ts:1483-1531 对位）──

    /**
     * pi {@code getActiveToolNames}（agent-session.ts:1483-1485）——声明给模型的
     * 工具名。java 侧以<b>注册表序</b>为稳定源（{@code activeTools} 是 Set，
     * 其迭代序不可作输出——ContextAssembler 同口径）。
     */
    java.util.List<String> getActiveToolNames() {
        return owner.services().tools().activeOf(
            owner.harness().getActiveTools().stream().map(AgentTool::name).toList())
            .stream().map(AgentTool::name).toList();
    }

    /**
     * pi {@code setActiveToolsByName}（agent-session.ts:1518-1525）——注册表查无的
     * 名静默忽略（pi「unknown 忽略」逐字）。系统提示不手动重建：java 的提示每 run
     * 起点由 ContextAssembler 重装配＋promptLoadout diff 补丁（既有机制），与 pi
     * {@code _rebuildSystemPrompt} 净效果同。批 24（docs/35 D4 销号）：pending 两臂
     * 落地——减员 ⇒ 清 pending（pi :1524）；已注册名出 pending（pi :1529）。
     */
    void setActiveToolsByName(java.util.List<String> names) {
        var previous = getActiveToolNames();
        var deduped = new java.util.LinkedHashSet<>(names);
        var tools = new java.util.LinkedHashSet<AgentTool<?, ?>>();
        for (var name : deduped) {
            var tool = owner.services().tools().get(name);
            // 批 27（docs/38 T6）：pi _applyToolLoadout 的过滤臂（:1578-1580
            // 逐字）——hidden 与不可激活（allowlist 保留 MCP 且无 tool_search）
            // 不进活跃集。
            if (tool != null
                    && toolExposureOf(name)
                        != com.pijava.coding.agent.extension.ToolExposure.HIDDEN
                    && isActivatableTool(name)) {
                tools.add(tool);
            }
        }
        owner.harness().setActiveTools(tools);
        var active = new java.util.HashSet<>(getActiveToolNames());
        if (previous.stream().anyMatch(name -> !active.contains(name))) {
            owner.harness().pendingToolNames().clear();
        }
        owner.harness().pendingToolNames()
            .removeIf(name -> owner.services().tools().get(name) != null);
    }

    /**
     * 批 24（docs/35 D4/T6）：运行时注册的工具激活缝——pi {@code refreshTools}
     * 重建合并（agent-session.ts:3570-3592）的 java 对位：{@code names}（调用方
     * 判好 exposure 的 direct 件）过注册面门（{@code --tools mcp__} 保留臂）激活；
     * 再消费 pending 晚注册臂（:3590＋:1529——pending 中现已注册的激活并出列，
     * pi 在此不复查 exposure/allowlist 逐字）。
     */
    void activateRuntimeTools(java.util.Collection<String> names) {
        var selection = toolSelection;
        var union = new java.util.LinkedHashSet<String>();
        for (var name : names) {
            if (selection == null || selection.isAllowed(name)) {
                union.add(name);
            }
        }
        var pending = owner.harness().pendingToolNames();
        for (var name : java.util.List.copyOf(pending)) {
            if (owner.services().tools().get(name) != null) {
                union.add(name);
                pending.remove(name);
            }
        }
        if (!union.isEmpty()) {
            owner.harness().activateTools(union);
        }
    }

    /**
     * 批 24（docs/35 T6）：运行时撤离激活集（hidden 重注/disable/资源工具 direct
     撤离的对位）——<b>不</b>清 pending（pi 的 pending 清理只在
     * {@code setActiveToolsByName} 减员臂）。
     */
    void deactivateRuntimeTools(java.util.Collection<String> names) {
        var current = new java.util.LinkedHashSet<>(getActiveToolNames());
        if (current.removeAll(names)) {
            owner.harness().setActiveTools(new java.util.LinkedHashSet<>(
                owner.services().tools().activeOf(current)));
        }
    }

    /**
     * pi {@code getAllTools}（agent-session.ts:1495-1506）的投影。批 27（B162
     * 裁决④）：{@code exposure}/{@code parameters}/{@code namespace} 三字段落地
     * （D91 收窄；余 annotations/sourceInfo 无对位）。
     */
    java.util.List<com.pijava.coding.agent.extension.ExtensionToolInfo> getAllTools() {
        var definitions = toolDefinitionsOrNull();
        return owner.services().tools().all().stream()
            .map(t -> {
                var entry = definitions == null ? null : definitions.get(t.name());
                return new com.pijava.coding.agent.extension.ExtensionToolInfo(
                    t.name(), t.description(),
                    t.promptGuidelines() == null ? java.util.List.of()
                        : java.util.List.copyOf(t.promptGuidelines()),
                    entry == null
                        ? com.pijava.coding.agent.extension.ToolExposure.DIRECT
                        : entry.exposure(),
                    t.inputSchema() == null ? java.util.Map.of() : t.inputSchema(),
                    entry == null ? null : entry.namespace());
            })
            .toList();
    }

    // ── 批 27（docs/38 T6）：exposure 四门（pi :1543-1567/:3595-3604 对位）──

    /** 定义表（无扩展装配 ⇒ {@code null}，四门全走 pi 默认臂）。 */
    private ToolDefinitions toolDefinitionsOrNull() {
        var context = owner.extensionContext();
        return context == null ? null : context.toolDefinitions();
    }

    /** pi {@code _getToolExposure}（agent-session.ts:1554-1556）：查无 ⇒ {@code direct}。 */
    com.pijava.coding.agent.extension.ToolExposure toolExposureOf(String name) {
        var definitions = toolDefinitionsOrNull();
        return definitions == null
            ? com.pijava.coding.agent.extension.ToolExposure.DIRECT
            : definitions.exposureOf(name);
    }

    /** pi {@code _isDeclarable}（:3595-3599）：激活即声明给模型。 */
    boolean isDeclarableTool(String name) {
        var exposure = toolExposureOf(name);
        return exposure == com.pijava.coding.agent.extension.ToolExposure.DIRECT
            || exposure == com.pijava.coding.agent.extension.ToolExposure.MODEL_ONLY;
    }

    /**
     * pi {@code _isActivatedOnRegistration}（:3601-3604，types.ts:612-617 的默认
     * 规则）：declarable 且 defaultActive ≠ false。查无条目 ⇒ pi 默认 true。
     */
    boolean isActivatedOnRegistration(String name) {
        if (!isDeclarableTool(name)) {
            return false;
        }
        var definitions = toolDefinitionsOrNull();
        var entry = definitions == null ? null : definitions.get(name);
        return entry == null || entry.defaultActive();
    }

    /**
     * pi {@code _isActivatable}（:1543-1552 逐字）：allowlist 保留的 MCP 工具只供
     * codemode/tool_search——仅当「非 direct 且 tool_search 已注册」才可被声明。
     */
    boolean isActivatableTool(String name) {
        var selection = toolSelection;
        if (selection == null || !selection.hasAllowlist() || selection.isRawAllowed(name)
                || !ToolNameMatcher.isMcpToolName(name)) {
            return true;
        }
        return toolExposureOf(name) != com.pijava.coding.agent.extension.ToolExposure.DIRECT
            && owner.services().tools().get(
                com.pijava.coding.agent.extension.toolsearch.ToolSearchTool.NAME) != null;
    }

    /**
     * pi {@code _getCallableTools}（:1558-1567 逐字）：{@code ctx.executeTool()} 的
     * 可调用集——活跃的 direct 工具＋全部已注册 codemode/deferred 工具。
     */
    java.util.List<AgentTool<?, ?>> callableTools() {
        var active = new java.util.HashSet<>(getActiveToolNames());
        return owner.services().tools().all().stream()
            .filter(tool -> {
                var exposure = toolExposureOf(tool.name());
                return exposure == com.pijava.coding.agent.extension.ToolExposure.CODEMODE
                    || exposure == com.pijava.coding.agent.extension.ToolExposure.DEFERRED
                    || (exposure == com.pijava.coding.agent.extension.ToolExposure.DIRECT
                        && active.contains(tool.name()));
            })
            .toList();
    }
}
