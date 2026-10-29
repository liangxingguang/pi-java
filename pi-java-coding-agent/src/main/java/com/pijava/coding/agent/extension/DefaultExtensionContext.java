package com.pijava.coding.agent.extension;

import java.util.Map;
import java.util.function.Supplier;

import com.pijava.agent.entry.CustomMessageContent;
import com.pijava.agent.session.SessionTree;
import com.pijava.agent.skill.SkillManager;
import com.pijava.agent.tool.ToolRegistry;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.coding.agent.core.SessionServices;
import com.pijava.coding.agent.core.SettingsManager;
import com.pijava.coding.agent.core.slash.CommandRegistry;
import com.pijava.coding.agent.extension.event.ExtensionEvent;
import com.pijava.coding.agent.extension.event.ExtensionEventHandler;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

/**
 * {@link ExtensionContext} 默认实现 —— 包装 {@link SessionServices} + SkillManager，
 * 另经 {@link #bindSession} 惰性绑定会话树以支持 {@link #sendMessage} 落盘。
 *
 * <p>docs/14：本实例是**全扩展共享的基础上下文**（桥传给处理器的 {@code ctx} 也是它）。
 * 扩展注册走的是 {@link #forExtension} 派生的委托实例 —— 其 {@code on()} 把扩展名
 * 带给 {@link ExtensionEventBus}，错误归属与 pi 的 {@code extensionPath} 对齐。
 * 委托实例可随时 {@code on()}（pi 的扩展同样能在 register 后经持有的 API 在运行期订阅；
 * pi 的 {@code assertActive} 只对**加载失败**的扩展抛）。基础上下文自己的 {@code on()}
 * 抛 {@link IllegalStateException} —— 不是 pi 的时限语义，而是「无扩展名可归属」。</p>
 *
 * <p>结构债拆分（行为零变化纯移动）：per-extension 委托顶出为 {@link PerExtensionContext}；
 * 注册表五簇＋宿主会话晚读面迁 {@link ExtensionSurfaceRegistries}（{@link #surface()} 触达）。</p>
 */
public final class DefaultExtensionContext implements ExtensionContext {

    private final SessionServices services;
    private final SkillManager skills;
    /** 批 17（T1，裁决②）：UI 晚绑定——pi runner.ts:876-884「Context values are
     *  resolved at call time」逐字；烘死字段会让 RPC 后注入的生产者到不了扩展
     * （E5-32 存疑的实质）。 */
    private final Supplier<ExtensionUI> uiSupplier;
    /** pi {@code ExtensionContext.mode} 的晚绑定源（同上）。 */
    private final Supplier<String> modeSupplier;
    private final ExtensionEventBus bus;
    /** 批 17（T2）：ui_prompt 包装器缓存（按 delegate 恒等；depth 状态随包装器）。 */
    private final java.util.Map<ExtensionUI, ExtensionUI> uiWrappers =
        new java.util.IdentityHashMap<>();
    /** 批 17（T2）：ui_prompt 事件发射器（loadExtensions 尾部回填；null ⇒ 不发射）。 */
    private volatile SessionEventFirer uiPromptFirer;
    private volatile Supplier<SessionTree> sessionSupplier;
    /** 批 16（T2c）：激活门三件套的会话晚绑定（loadExtensions 回填）。 */
    private volatile Supplier<com.pijava.coding.agent.core.AgentSession> toolControl;
    /** 按扩展名分桶的注册表集合＋宿主会话晚读面（结构债拆分，本体全在其中）。 */
    private final ExtensionSurfaceRegistries surface = new ExtensionSurfaceRegistries(this);
    /** 批 16（T4b）：工具渲染器责任链（pi extension.toolRenderers 拍平对位）。 */
    private final ToolRendererRegistry toolRenderers = new ToolRendererRegistry();
    /**
     * 批 28（docs/40 T2，裁决③）：pi {@code ReadonlySessionManager} 对位视图——
     * 惰性委托宿主会话（无宿主 ⇒ requireToolControl 同文抛错；--no-session 走
     * harness 转录兜底，F1 取证）。
     */
    private final ExtensionSessionView sessionView =
        () -> java.util.List.copyOf(requireToolControl().accumulatedEntries());

    /** @param services 会话服务（工具/命令/Provider/settings）
     *  @param skills   技能注册表（harness 的 skillManager）
     *  @param ui       扩展 UI 服务（RPC 模式注入；缺省 noop）
     *  @param bus      扩展事件总线（{@code on()} 的登记目标；null ⇒ 订阅面不可用） */
    public DefaultExtensionContext(SessionServices services, SkillManager skills,
                                   ExtensionUI ui, ExtensionEventBus bus) {
        this(services, skills,
            ui == null ? ExtensionUI::noop : () -> ui, () -> "print", bus);
    }

    /**
     * 批 17（T1，裁决②）：晚绑定构造——{@code ui}/{@code mode} 都是调用时现取的
     * 供给器（pi {@code createContext()} 的 getter 对位）。
     *
     * @param uiSupplier   UI 供给器（返回 null ≙ noop）
     * @param modeSupplier 模式供给器（pi {@code ExtensionMode} 四值）
     */
    public DefaultExtensionContext(SessionServices services, SkillManager skills,
                                   Supplier<ExtensionUI> uiSupplier,
                                   Supplier<String> modeSupplier, ExtensionEventBus bus) {
        this.services = services;
        this.skills = skills;
        this.uiSupplier = uiSupplier;
        this.modeSupplier = modeSupplier;
        this.bus = bus;
    }

    /** docs/14 之前的三参形状：无总线（{@code on()} 不可用）。 */
    public DefaultExtensionContext(SessionServices services, SkillManager skills,
                                   ExtensionUI ui) {
        this(services, skills, ui, null);
    }

    /** 便捷构造：noop UI。 */
    public DefaultExtensionContext(SessionServices services, SkillManager skills) {
        this(services, skills, ExtensionUI.noop());
    }

    /**
     * 惰性绑定会话树供给器（session 在扩展加载后才 resolve，故用 supplier）。
     * 重复调用覆盖旧值。
     */
    public void bindSession(Supplier<SessionTree> sessionSupplier) {
        this.sessionSupplier = sessionSupplier;
    }

    @Override
    public ToolRegistry tools() {
        return services.tools();
    }

    @Override
    public CommandRegistry slashCommands() {
        // 批 20（B237）：扩展注册走打标视图（register 即 EXTENSION）——
        // pi 的扩展命令表与内置件是两个注册面，java 同池 ⇒ 标签区分执行面。
        return services.slashCommands().extensionView();
    }

    @Override
    public ProviderRegistry providers() {
        return services.providers();
    }

    @Override
    public SkillManager skills() {
        return skills;
    }

    @Override
    public SettingsManager settings() {
        return services.settings();
    }

    /**
     * 批 17（T1/T2）：晚绑定现取＋ui_prompt 包装——pi {@code setUIContext} 的
     * {@code uiContext ? wrapUIPromptContext(uiContext) : noOpUIContext}
     * （runner.ts:581-584）对位：非 NOOP 才包；包装器按 delegate 恒等缓存
     * （depth 计数随包装器存活，runner.ts:598 的 runner 字段对位）。
     */
    @Override
    public ExtensionUI ui() {
        var delegate = uiSupplier.get();
        if (delegate == null || delegate == ExtensionUI.NOOP) {
            return ExtensionUI.NOOP;
        }
        synchronized (uiWrappers) {
            return uiWrappers.computeIfAbsent(delegate,
                d -> new UiPromptEventExtensionUI(d, () -> uiPromptFirer));
        }
    }

    /** pi {@code hasUI}（runner.ts:622 恒等比较对位——读<b>未包装</b>的 delegate）。 */
    @Override
    public boolean hasUi() {
        var delegate = uiSupplier.get();
        return delegate != null && delegate != ExtensionUI.NOOP;
    }

    /** pi {@code ExtensionContext.mode}（现读）。 */
    @Override
    public String mode() {
        return modeSupplier.get();
    }

    /** 批 17（T2）：loadExtensions 尾部回填 ui_prompt 发射器（firer 晚于 context 诞生）。 */
    public void bindUiPromptFirer(SessionEventFirer firer) {
        this.uiPromptFirer = firer;
    }

    @Override
    public SessionServices services() {
        return services;
    }

    /** 共享总线（桥装配用；null ⇒ 订阅面未装）。 */
    public ExtensionEventBus eventBus() {
        return bus;
    }

    /** per-extension 委托（{@link PerExtensionContext}）触达注册表本体的口。 */
    ExtensionSurfaceRegistries surface() {
        return surface;
    }

    /** sendMessage 轻量夹具回落臂用（{@link ExtensionSurfaceRegistries} 读）。 */
    Supplier<SessionTree> sessionSupplier() {
        return sessionSupplier;
    }

    /** 批 16（T2c）：激活门三件套的会话晚绑定（loadExtensions 回填；重复调用覆盖）。 */
    public void bindToolControl(
            Supplier<com.pijava.coding.agent.core.AgentSession> sessionSupplier) {
        this.toolControl = sessionSupplier;
    }

    public java.util.Map<String, Object> flagValues() {
        return surface.flagValues();
    }

    public java.util.List<ExtensionFlag> allFlags() {
        return surface.allFlags();
    }

    public ExtensionFlag findFlag(String name) {
        return surface.findFlag(name);
    }

    /** 工具渲染器责任链（T4b；装配点注册内置 MCP resolver，扩展经 registerToolRenderer 追加）。 */
    public ToolRendererRegistry toolRenderers() {
        return toolRenderers;
    }

    @Override
    public void registerFlag(String name, ExtensionFlagOptions options) {
        throw new IllegalStateException(
            "registerFlag() 只能在扩展 register 期间经 per-extension 上下文调用（pi 的 assertActive）");
    }

    @Override
    public Object getFlag(String name) {
        throw new IllegalStateException(
            "getFlag() 只能在扩展 register 期间经 per-extension 上下文调用（pi 的 assertActive）");
    }

    @Override
    public java.util.List<String> getActiveTools() {
        return requireToolControl().getActiveToolNames();
    }

    @Override
    public java.util.List<ExtensionToolInfo> getAllTools() {
        return requireToolControl().getAllTools();
    }

    @Override
    public void setActiveTools(java.util.List<String> toolNames) {
        requireToolControl().setActiveToolsByName(toolNames);
    }

    /** 批 28（docs/40 T2）：pi {@code ExtensionContext.cwd}——宿主会话 cwd。 */
    @Override
    public String cwd() {
        return requireToolControl().cwd();
    }

    /** 批 28（docs/40 T2）：pi {@code ExtensionContext.sessionManager}——惰性只读视图。 */
    @Override
    public ExtensionSessionView sessionManager() {
        return sessionView;
    }

    /**
     * 批 27（B162/T4）：嵌套调用——路由到宿主会话（pi 的 executeToolFn 对位）；
     * 无会话 ⇒ 接口默认臂（不可用错文）。
     */
    @Override
    public com.pijava.agent.harness.PiLoop.ToolOutcome executeTool(
            String callerToolCallId, com.pijava.ai.AbortSignal signal, String name,
            Map<String, Object> args, java.util.function.Consumer<Object> onUpdate) {
        var supplier = toolControl;
        var session = supplier == null ? null : supplier.get();
        if (session == null) {
            return ExtensionContext.super.executeTool(callerToolCallId, signal, name, args,
                onUpdate);
        }
        return session.executeNestedToolCall(callerToolCallId, name, args,
            new com.pijava.coding.agent.core.NestedToolCallRunner.Options(
                signal, onUpdate));
    }

    @Override
    public java.util.List<com.pijava.agent.tool.AgentTool<?, ?>> callableTools() {
        var supplier = toolControl;
        var session = supplier == null ? null : supplier.get();
        return session == null ? java.util.List.of() : session.callableTools();
    }

    /** 基础上下文直调（无扩展名归属）时路由上下文即本上下文。 */
    @Override
    public void registerVirtualModel(ExtensionVirtualModel model) {
        surface.registerVirtualModel(model, this);
    }

    @Override
    public void unregisterVirtualModel(String provider, String id) {
        surface.unregisterVirtualModel(provider, id);
    }

    @Override
    public void registerToolRenderer(ToolRendererResolver resolver) {
        toolRenderers.register(resolver);
    }

    /**
     * 批 24（docs/35 T1）：内置 MCP 扩展的宿主会话缝——pi {@code ExtensionContext}
     * 的 {@code cwd}/{@code isProjectTrusted} 等会话成员的 java 内部通道
     * （不公开进 {@link ExtensionContext} 接口；仅供 {@code extension.mcp} 包）。
     *
     * @return 绑定的会话；未绑（--help 轻量道等）⇒ {@code null}
     */
    public com.pijava.coding.agent.core.AgentSession hostSessionOrNull() {
        var supplier = toolControl;
        return supplier == null ? null : supplier.get();
    }

    com.pijava.coding.agent.core.AgentSession requireToolControl() {
        var supplier = toolControl;
        var session = supplier == null ? null : supplier.get();
        if (session == null) {
            throw new IllegalStateException(
                "tool control requires a bound session; none is available yet");
        }
        return session;
    }

    @Override
    public <E extends ExtensionEvent, R> AutoCloseable on(ExtensionEventSpec<E, R> spec,
                                                          ExtensionEventHandler<E, R> handler) {
        throw new IllegalStateException(
            "on() 只能在扩展 register 期间经 per-extension 上下文调用（pi 的 assertActive）");
    }

    // ── 批 23（docs/34 T1）：MCP 注册面 ──

    /** MCP 注册表（装配接线用——{@code AgentSession.loadExtensions} 尾部接 changeListener）。 */
    public com.pijava.mcp.config.McpServerRegistry mcpServers() {
        return surface.mcpServers();
    }

    @Override
    public void registerMcpServer(String name, com.pijava.mcp.config.McpServerConfig config) {
        throw new IllegalStateException(
            "registerMcpServer() 需经 per-extension 上下文调用（无扩展归属可记）");
    }

    @Override
    public void unregisterMcpServer(String name) {
        throw new IllegalStateException(
            "unregisterMcpServer() 需经 per-extension 上下文调用（无扩展归属可记）");
    }

    @Override
    public java.util.List<com.pijava.mcp.config.RegisteredMcpServer> getMcpServers() {
        return surface.getMcpServers();
    }

    /**
     * 派生 per-extension 委托上下文：全部服务方法透传，唯 {@code on()} 以扩展名
     * 登记进总线（docs/14 §3.3：错误归属对齐 pi 的 {@code extensionPath}）。
     * 无总线时同样派生（其 {@code on()} 抛与基础上下文一致的异常）。
     */
    @Override
    public ExtensionContext forExtension(String extensionName) {
        return PerExtensionContext.of(this, extensionName, extensionName);
    }

    /** 批 23（T1）：带路径身份的派生（pi {@code extension.path} 对位）。 */
    @Override
    public ExtensionContext forExtension(String extensionName, String extensionPath) {
        return PerExtensionContext.of(this, extensionName, extensionPath);
    }

    @Override
    public java.util.List<com.pijava.coding.agent.core.slash.SlashCommandInfo> getCommands() {
        return surface.getCommands();
    }

    @Override
    public String sendMessage(String customType, CustomMessageContent content, boolean display,
                              Map<String, Object> details, SendMessageOptions options) {
        return surface.sendMessage(customType, content, display, details, options);
    }

    @Override
    public void sendUserMessage(CustomMessageContent content, SendUserMessageOptions options) {
        surface.sendUserMessage(content, options);
    }

    @Override
    public void appendEntry(String customType, Map<String, Object> data) {
        surface.appendEntry(customType, data);
    }

    @Override
    public void setSessionName(String name) {
        surface.setSessionName(name);
    }

    @Override
    public String getSessionName() {
        return surface.getSessionName();
    }

    @Override
    public void setLabel(String entryId, String label) {
        surface.setLabel(entryId, label);
    }

    @Override
    public boolean setModel(com.pijava.ai.model.ModelId<?> model) {
        return surface.setModel(model);
    }

    @Override
    public com.pijava.ai.thinking.ModelThinkingLevel getThinkingLevel() {
        return surface.getThinkingLevel();
    }

    @Override
    public void setThinkingLevel(com.pijava.ai.thinking.ModelThinkingLevel level) {
        surface.setThinkingLevel(level);
    }

    @Override
    public ExtensionExec.ExecResult exec(String command, java.util.List<String> args,
                                         ExtensionExec.ExecOptions options) {
        return surface.exec(command, args, options);
    }

    /**
     * 批 26（docs/37 T8，E5-5）：pi {@code unregisterProvider}——删注册
     * （裁决⑥：内置模型恢复面不做；未注册静默，pi 同）。
     */
    @Override
    public void unregisterProvider(String name) {
        providers().remove(name);
    }

    @Override
    public ExtensionChannelBus events() {
        return surface.events();
    }

    /** 无归属的共享基础上下文调用 ⇒ 抛（registerMcpServer 同纪律，批 23 先例）。 */
    @Override
    public void registerShortcut(String keyId, String description,
                                 ExtensionShortcut.ShortcutHandler handler) {
        throw new IllegalStateException(
            "registerShortcut() 需经 per-extension 上下文调用（无扩展归属可记）");
    }

    /** pi {@code getMessageRenderer}（runner.ts:775-782）：扩展加载序 first-wins。 */
    public ExtensionMessageRenderer resolveMessageRenderer(String customType) {
        return surface.resolveMessageRenderer(customType);
    }

    /** pi {@code getEntryRenderer}（runner.ts:798-805）：扩展加载序 first-wins。 */
    public ExtensionEntryRenderer resolveEntryRenderer(String customType) {
        return surface.resolveEntryRenderer(customType);
    }

    /** 无归属共享面 ⇒ 抛（同 registerShortcut 纪律）。 */
    @Override
    public void registerMessageRenderer(String customType, ExtensionMessageRenderer renderer) {
        throw new IllegalStateException(
            "registerMessageRenderer() 需经 per-extension 上下文调用（无扩展归属可记）");
    }

    /** 无归属共享面 ⇒ 抛（同 registerShortcut 纪律）。 */
    @Override
    public void registerEntryRenderer(String customType, ExtensionEntryRenderer renderer) {
        throw new IllegalStateException(
            "registerEntryRenderer() 需经 per-extension 上下文调用（无扩展归属可记）");
    }

    public java.util.List<MarkdownTransformer> markdownTransformers() {
        return surface.markdownTransformers();
    }

    /** 无归属共享面 ⇒ 抛（同 registerShortcut 纪律）。 */
    @Override
    public void registerMarkdownTransformer(MarkdownTransformer transformer) {
        throw new IllegalStateException(
            "registerMarkdownTransformer() 需经 per-extension 上下文调用（无扩展归属可记）");
    }

    /** pi {@code runner.getShortcuts}（runner.ts:673-716）——冲突三规则解决（本体在
     *  {@link ExtensionSurfaceRegistries}，签名不动）。 */
    public ExtensionShortcuts.Resolution resolvedShortcuts(
            com.pijava.coding.agent.core.KeybindingsManager keys, boolean hasUi) {
        return surface.resolvedShortcuts(keys, hasUi);
    }

    /** pi {@code runtime.invalidate} 的 eventBus 半（loader.ts:193-196；本体在
     *  {@link ExtensionSurfaceRegistries}）。 */
    public void invalidateChannels() {
        surface.invalidateChannels();
    }
}
