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
    // ── 批 16（T1b）：flag 面——声明存本扩展（pi extension.flags）、值存共享表
    // （pi runtime.flagValues）。pi 的 pending/commit 两阶段不移植：java 加载与
    // 绑定同线程顺序执行（register 全在 loadAll 内、CLI 绑定在其后），
    // set-if-absent 一刀即同净效果（docs/27 T1b 注记）。
    private final java.util.Map<String, Object> flagValues =
        new java.util.concurrent.ConcurrentHashMap<>();
    /** 全局名 → 声明（pi registeredFlags；同名后到胜出）。 */
    private final java.util.Map<String, ExtensionFlag> flagsByName =
        new java.util.LinkedHashMap<>();
    /** 扩展名 → 该扩展的声明表（pi extension.flags；getFlag 所有权门的数据源）。 */
    private final java.util.Map<String, java.util.Map<String, ExtensionFlag>> flagDeclarations =
        new java.util.LinkedHashMap<>();
    /** 批 16（T4b）：工具渲染器责任链（pi extension.toolRenderers 拍平对位）。 */
    private final ToolRendererRegistry toolRenderers = new ToolRendererRegistry();
    /**
     * 批 23（docs/34 T1，裁决⑦）：MCP 注册表（pi {@code runtime.mcpServers} 对位，
     * 每会话一份随上下文生死）。装载期注册静默落表——changeListener 由
     * {@code AgentSession.loadExtensions} 尾部接线（裁决⑧：java 同步装载＋
     * listener 后装 ≡ pi 的 pendingRuntimeChanges/commit 两阶段净效果）。
     */
    private final com.pijava.mcp.config.McpServerRegistry mcpServers =
        new com.pijava.mcp.config.McpServerRegistry();
    /** 批 26（docs/37 T7）：pi {@code eventBus}（每会话一条，loader.ts:688 对位）。 */
    private final ExtensionChannelBus channelBus = new ExtensionChannelBus();
    /** pi {@code eventBusUnsubscribers}（loader.ts:161）——跟踪表，失效时统一退订。 */
    private final java.util.List<AutoCloseable> channelSubscriptions =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    /** pi 的 {@code api.events}（跟踪视图，loader.ts:517-522 对位）。 */
    private final ExtensionChannelBus eventsView =
        channelBus.trackedView(channelSubscriptions::add);
    /**
     * 批 26（docs/37 T9）：pi {@code extension.shortcuts} 对位——按扩展加载序的
     * 快捷键表（extensionPath →〔keyId 原串 → 注册〕；注册点已解析校验 keyId）。
     */
    private final java.util.LinkedHashMap<String,
        java.util.LinkedHashMap<String, ExtensionShortcut>> shortcutsByExtension =
            new java.util.LinkedHashMap<>();
    /**
     * 批 26（docs/37 T10）：pi {@code extension.messageRenderers}/{@code entryRenderers}
     * 对位——按扩展加载序（first-wins 解决序，runner.ts:775-805）。
     */
    private final java.util.LinkedHashMap<String,
        java.util.Map<String, ExtensionMessageRenderer>> messageRenderersByExtension =
            new java.util.LinkedHashMap<>();
    /** 见 {@link #messageRenderersByExtension}。 */
    private final java.util.LinkedHashMap<String,
        java.util.Map<String, ExtensionEntryRenderer>> entryRenderersByExtension =
            new java.util.LinkedHashMap<>();
    /**
     * 批 26（docs/37 T11）：pi {@code extension.markdownTransformer} 对位——
     * <b>单槽</b>（loader.ts:361 赋值语义：同扩展重复注册后者覆盖）；跨扩展按
     * 加载序链式（runner.ts:785-787 的 flatMap 对位）。
     */
    private final java.util.LinkedHashMap<String, MarkdownTransformer> markdownTransformers =
        new java.util.LinkedHashMap<>();
    /** 批 27（B162 裁决④）：pi {@code _toolDefinitions} 对位——每会话一份随上下文生死。 */
    private final com.pijava.coding.agent.core.ToolDefinitions toolDefinitions =
        new com.pijava.coding.agent.core.ToolDefinitions();

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

    // ── 批 16（T1b/T2c/T4b）：flag 面 / 激活门 / 渲染器注册表 ──

    /** 批 16（T2c）：激活门三件套的会话晚绑定（loadExtensions 回填；重复调用覆盖）。 */
    public void bindToolControl(
            Supplier<com.pijava.coding.agent.core.AgentSession> sessionSupplier) {
        this.toolControl = sessionSupplier;
    }

    /** 共享 flag 值表（pi {@code runtime.flagValues}；binder 写入、getFlag 读）。 */
    public java.util.Map<String, Object> flagValues() {
        return flagValues;
    }

    /** 全部已声明 flag（pi --help 面的拍平，注册序）。 */
    public java.util.List<ExtensionFlag> allFlags() {
        return java.util.List.copyOf(flagsByName.values());
    }

    /** 按名查声明（pi registeredFlags.get）；未注册 ⇒ {@code null}。 */
    public ExtensionFlag findFlag(String name) {
        return flagsByName.get(name);
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

    /** pi loader.ts:331-349 的本体（per-extension 委托转到这里）。 */
    void registerFlag(String extensionName, String name, ExtensionFlagOptions options) {
        var defaultValue = options.defaultValue();
        if (defaultValue != null && !typeMatches(options.type(), defaultValue)) {
            throw new IllegalArgumentException(
                "Invalid default for flag \"" + name + "\": expected "
                    + jsType(options.type()) + ", got " + jsTypeOf(defaultValue));
        }
        var flag = new ExtensionFlag(name, options.description(), options.type(),
            defaultValue, extensionName);
        flagDeclarations.computeIfAbsent(extensionName, k -> new java.util.LinkedHashMap<>())
            .put(name, flag);
        flagsByName.put(name, flag);
        if (defaultValue != null) {
            // pi loader.ts:342-348：default set-if-absent（CLI 值/先到 default 不被覆盖）。
            flagValues.putIfAbsent(name, defaultValue);
        }
    }

    /** pi loader.ts:373-378 的本体：所有权门（只能读本扩展注册的名）。 */
    Object getFlag(String extensionName, String name) {
        var declared = flagDeclarations.get(extensionName);
        if (declared == null || !declared.containsKey(name)) {
            return null;
        }
        return flagValues.get(name);
    }

    private static boolean typeMatches(ExtensionFlag.Type type, Object value) {
        return switch (type) {
            case BOOLEAN -> value instanceof Boolean;
            case STRING -> value instanceof String;
        };
    }

    private static String jsType(ExtensionFlag.Type type) {
        return switch (type) {
            case BOOLEAN -> "boolean";
            case STRING -> "string";
        };
    }

    /** pi {@code typeof default} 的 java 近似（文案逐字的第二操作数）。 */
    private static String jsTypeOf(Object value) {
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Number) {
            return "number";
        }
        return "object";
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

    @Override
    public com.pijava.coding.agent.core.ToolDefinitions toolDefinitions() {
        return toolDefinitions;
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

    private com.pijava.coding.agent.core.AgentSession requireToolControl() {
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
        return mcpServers;
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
        // pi loader.ts:496-498 无归属门槛——list 是谁都能读的全量视图。
        return mcpServers.list();
    }

    /** pi loader.ts:471-489 的本体（per-extension 委托转入；三守卫逐字）。 */
    void registerMcpServer(String extensionPath, String name,
                           com.pijava.mcp.config.McpServerConfig config) {
        var validated = com.pijava.mcp.config.McpServerConfigs.validate(
            name, com.pijava.mcp.McpJson.mapper().valueToTree(config));
        switch (validated) {
            case com.pijava.mcp.config.McpConfigValidation.Invalid invalid ->
                throw new IllegalArgumentException(
                    "Invalid MCP server registered by extension \"" + extensionPath
                        + "\": " + invalid.message());
            case com.pijava.mcp.config.McpConfigValidation.Valid valid -> {
                var owner = mcpServers.get(name);
                if (owner != null && !owner.extensionPath().equals(extensionPath)) {
                    throw new IllegalStateException(
                        "MCP server \"" + name + "\" is already registered by extension \""
                            + owner.extensionPath() + "\"");
                }
                // pi：`-`/`_` 归一后同命名空间即冲突（mcpNamespace 比较）。
                var namespace = com.pijava.mcp.config.McpServerConfigs.namespace(name);
                for (var server : mcpServers.list()) {
                    if (!server.name().equals(name)
                            && com.pijava.mcp.config.McpServerConfigs.namespace(server.name())
                                .equals(namespace)) {
                        throw new IllegalStateException(
                            "MCP server \"" + name + "\" conflicts with registered server \""
                                + server.name() + "\"");
                    }
                }
                mcpServers.register(
                    new com.pijava.mcp.config.RegisteredMcpServer(name, valid.config(),
                        extensionPath));
            }
        }
    }

    /**
     * 派生 per-extension 委托上下文：全部服务方法透传，唯 {@code on()} 以扩展名
     * 登记进总线（docs/14 §3.3：错误归属对齐 pi 的 {@code extensionPath}）。
     * 无总线时同样派生（其 {@code on()} 抛与基础上下文一致的异常）。
     */
    @Override
    public ExtensionContext forExtension(String extensionName) {
        return new PerExtensionContext(extensionName, extensionName,
            computeSourceInfo(extensionName));
    }

    /** 批 23（T1）：带路径身份的派生（pi {@code extension.path} 对位）。 */
    @Override
    public ExtensionContext forExtension(String extensionName, String extensionPath) {
        return new PerExtensionContext(extensionName, extensionPath,
            computeSourceInfo(extensionPath));
    }

    /**
     * 批 23（T4，E5-34）：扩展的 sourceInfo——pi {@code applyExtensionSourceInfo}
     * （resource-loader.ts:918-922）对位。目录根从 settings 存储面取（仅
     * {@code FileSettingsStorage} 携带）；取不到（内存存储/测试道）⇒
     * 合成路径规则仍生效，文件路径落 temporary 兜底。
     */
    private com.pijava.coding.agent.core.source.SourceInfo computeSourceInfo(
            String extensionPath) {
        java.nio.file.Path agentDir = null;
        java.nio.file.Path projectDir = null;
        if (services != null && services.settings() != null
                && services.settings().storage()
                    instanceof com.pijava.coding.agent.core.FileSettingsStorage files) {
            var globalPath = files.globalPath();
            var projectPath = files.projectPath();
            agentDir = globalPath == null ? null : globalPath.getParent();
            var projectConfigDir = projectPath == null ? null : projectPath.getParent();
            projectDir = projectConfigDir == null ? null : projectConfigDir.getParent();
        }
        return com.pijava.coding.agent.core.source.ExtensionSourceInfos
            .forExtensionPath(extensionPath, agentDir, projectDir);
    }

    /** per-extension 委托：除 {@code on()} 与 MCP 注册面外全部透传基础上下文。 */
    private final class PerExtensionContext implements ExtensionContext {
        private final String extensionName;
        /** 批 23（T1）：pi {@code extension.path} 对位（MCP 所有权门/错文归属用）。 */
        private final String extensionPath;
        /** 批 23（T4）：本扩展的 sourceInfo 章（pi {@code extension.sourceInfo}）。 */
        private final com.pijava.coding.agent.core.source.SourceInfo sourceInfo;

        PerExtensionContext(String extensionName, String extensionPath,
                            com.pijava.coding.agent.core.source.SourceInfo sourceInfo) {
            this.extensionName = extensionName;
            this.extensionPath = extensionPath;
            this.sourceInfo = sourceInfo;
        }

        @Override
        public <E extends ExtensionEvent, R> AutoCloseable on(
                ExtensionEventSpec<E, R> spec, ExtensionEventHandler<E, R> handler) {
            if (bus == null) {
                return DefaultExtensionContext.this.on(spec, handler);
            }
            return bus.on(extensionName, spec, handler);
        }

        @Override
        public ToolRegistry tools() {
            return DefaultExtensionContext.this.tools();
        }

        @Override
        public CommandRegistry slashCommands() {
            // 批 23（T4）：注册即盖本扩展的 sourceInfo 章（pi loader.ts:317 对位）。
            return DefaultExtensionContext.this.services().slashCommands()
                .extensionView(extensionPath, sourceInfo);
        }

        @Override
        public ProviderRegistry providers() {
            return DefaultExtensionContext.this.providers();
        }

        @Override
        public SkillManager skills() {
            return DefaultExtensionContext.this.skills();
        }

        @Override
        public SettingsManager settings() {
            return DefaultExtensionContext.this.settings();
        }

        @Override
        public ExtensionUI ui() {
            return DefaultExtensionContext.this.ui();
        }

        @Override
        public boolean hasUi() {
            return DefaultExtensionContext.this.hasUi();
        }

        @Override
        public String mode() {
            return DefaultExtensionContext.this.mode();
        }

        @Override
        public SessionServices services() {
            return DefaultExtensionContext.this.services();
        }

        @Override
        public void registerFlag(String name, ExtensionFlagOptions options) {
            DefaultExtensionContext.this.registerFlag(extensionName, name, options);
        }

        @Override
        public Object getFlag(String name) {
            return DefaultExtensionContext.this.getFlag(extensionName, name);
        }

        @Override
        public java.util.List<String> getActiveTools() {
            return DefaultExtensionContext.this.getActiveTools();
        }

        @Override
        public java.util.List<ExtensionToolInfo> getAllTools() {
            return DefaultExtensionContext.this.getAllTools();
        }

        @Override
        public void setActiveTools(java.util.List<String> toolNames) {
            DefaultExtensionContext.this.setActiveTools(toolNames);
        }

        @Override
        public com.pijava.coding.agent.core.ToolDefinitions toolDefinitions() {
            return DefaultExtensionContext.this.toolDefinitions();
        }

        @Override
        public com.pijava.agent.harness.PiLoop.ToolOutcome executeTool(
                String callerToolCallId, com.pijava.ai.AbortSignal signal, String name,
                Map<String, Object> args, java.util.function.Consumer<Object> onUpdate) {
            return DefaultExtensionContext.this.executeTool(callerToolCallId, signal, name,
                args, onUpdate);
        }

        @Override
        public java.util.List<com.pijava.agent.tool.AgentTool<?, ?>> callableTools() {
            return DefaultExtensionContext.this.callableTools();
        }

        @Override
        public void registerToolRenderer(ToolRendererResolver resolver) {
            DefaultExtensionContext.this.registerToolRenderer(resolver);
        }

        @Override
        public String sendMessage(String customType, CustomMessageContent content,
                                  boolean display, Map<String, Object> details,
                                  SendMessageOptions options) {
            return DefaultExtensionContext.this.sendMessage(
                customType, content, display, details, options);
        }

        @Override
        public void sendUserMessage(CustomMessageContent content, SendUserMessageOptions options) {
            DefaultExtensionContext.this.sendUserMessage(content, options);
        }

        // ── 批 26（docs/37 T4/T5）：会话元数据与模型面（透传本体） ──

        @Override
        public void appendEntry(String customType, Map<String, Object> data) {
            DefaultExtensionContext.this.appendEntry(customType, data);
        }

        @Override
        public void setSessionName(String name) {
            DefaultExtensionContext.this.setSessionName(name);
        }

        @Override
        public String getSessionName() {
            return DefaultExtensionContext.this.getSessionName();
        }

        @Override
        public void setLabel(String entryId, String label) {
            DefaultExtensionContext.this.setLabel(entryId, label);
        }

        @Override
        public boolean setModel(com.pijava.ai.model.ModelId<?> model) {
            return DefaultExtensionContext.this.setModel(model);
        }

        @Override
        public com.pijava.ai.thinking.ModelThinkingLevel getThinkingLevel() {
            return DefaultExtensionContext.this.getThinkingLevel();
        }

        @Override
        public void setThinkingLevel(com.pijava.ai.thinking.ModelThinkingLevel level) {
            DefaultExtensionContext.this.setThinkingLevel(level);
        }

        @Override
        public ExtensionExec.ExecResult exec(String command, java.util.List<String> args,
                                             ExtensionExec.ExecOptions options) {
            return DefaultExtensionContext.this.exec(command, args, options);
        }

        @Override
        public void unregisterProvider(String name) {
            DefaultExtensionContext.this.unregisterProvider(name);
        }

        @Override
        public ExtensionChannelBus events() {
            return DefaultExtensionContext.this.events();
        }

        @Override
        public void registerShortcut(String keyId, String description,
                                     ExtensionShortcut.ShortcutHandler handler) {
            DefaultExtensionContext.this.registerShortcut(extensionPath, keyId, description,
                handler);
        }

        @Override
        public void registerMessageRenderer(String customType, ExtensionMessageRenderer renderer) {
            DefaultExtensionContext.this.registerMessageRenderer(extensionPath, customType,
                renderer);
        }

        @Override
        public void registerEntryRenderer(String customType, ExtensionEntryRenderer renderer) {
            DefaultExtensionContext.this.registerEntryRenderer(extensionPath, customType,
                renderer);
        }

        @Override
        public void registerMarkdownTransformer(MarkdownTransformer transformer) {
            DefaultExtensionContext.this.registerMarkdownTransformer(extensionPath, transformer);
        }

        // ── 批 23（docs/34 T1）：MCP 注册面（带归属转入本体） ──

        @Override
        public void registerMcpServer(String name,
                                      com.pijava.mcp.config.McpServerConfig config) {
            DefaultExtensionContext.this.registerMcpServer(extensionPath, name, config);
        }

        @Override
        public void unregisterMcpServer(String name) {
            mcpServers.unregister(name, extensionPath);
        }

        @Override
        public java.util.List<com.pijava.mcp.config.RegisteredMcpServer> getMcpServers() {
            return DefaultExtensionContext.this.getMcpServers();
        }

        @Override
        public java.util.List<com.pijava.coding.agent.core.slash.SlashCommandInfo>
                getCommands() {
            return DefaultExtensionContext.this.getCommands();
        }
    }

    /**
     * 批 23（T4，E5-20）：pi {@code getCommands}（agent-session.ts:3361-3381
     * 三源序逐字）——扩展编号名 → 模板 → {@code skill:} 前缀技能。
     */
    @Override
    public java.util.List<com.pijava.coding.agent.core.slash.SlashCommandInfo> getCommands() {
        var out = new java.util.ArrayList<com.pijava.coding.agent.core.slash.SlashCommandInfo>();
        for (var cmd : services.slashCommands().resolvedExtensionCommands()) {
            out.add(new com.pijava.coding.agent.core.slash.SlashCommandInfo(
                cmd.invocationName(), cmd.command().description(), "extension",
                cmd.sourceInfo()));
        }
        for (var template : services.promptTemplates().all()) {
            out.add(new com.pijava.coding.agent.core.slash.SlashCommandInfo(
                template.name(), template.description(), "prompt", template.sourceInfo()));
        }
        for (var skill : skills.all()) {
            out.add(new com.pijava.coding.agent.core.slash.SlashCommandInfo(
                "skill:" + skill.name(), skill.description(), "skill",
                com.pijava.coding.agent.core.source.SourceInfos.fromSkill(skill)));
        }
        return java.util.List.copyOf(out);
    }

    @Override
    public String sendMessage(String customType, CustomMessageContent content, boolean display,
                              Map<String, Object> details, SendMessageOptions options) {
        // 批 26（docs/37 T3）：委托 AgentSession.sendCustomMessage 五臂
        //（pi agent-session.ts:2293-2329 逐字；UOE 占位退役）。
        var host = hostSessionOrNull();
        if (host != null) {
            var opts = options == null ? new SendMessageOptions(null, null) : options;
            return host.sendCustomMessage(customType, content, display, details,
                opts.triggerTurn(), opts.deliverAs());
        }
        // 轻量夹具回落（无宿主会话、仅绑会话树）：仅落盘臂（批 26 前全量行为）；
        // triggerTurn 无车道可驱动 ⇒ 维持 UOE（本批的 triggerTurn 落地在宿主路径）。
        if (options != null && Boolean.TRUE.equals(options.triggerTurn())) {
            throw new UnsupportedOperationException(
                "sendMessage(triggerTurn=true) requires a bound host session");
        }
        var supplier = sessionSupplier;
        var tree = supplier == null ? null : supplier.get();
        if (tree == null) {
            throw new IllegalStateException(
                "sendMessage requires a bound session; none is available yet");
        }
        return tree.appendCustomMessageEntry(customType, content, display, details);
    }

    /**
     * 批 26（docs/37 T3，E5-13）：pi {@code sendUserMessage}（agent-session.ts:2365-2393
     * 逐字）——归一后走 {@code processPrompt}（source "extension"、deliverAs 作
     * streamingBehavior、expandPromptTemplates 默认 false 经 PromptConfig 门）。
     */
    @Override
    public void sendUserMessage(CustomMessageContent content, SendUserMessageOptions options) {
        var host = hostSessionOrNull();
        if (host == null) {
            throw new IllegalStateException(
                "sendUserMessage requires a bound session; none is available yet");
        }
        var opts = options == null ? new SendUserMessageOptions(null, null) : options;
        String text;
        java.util.List<com.pijava.agent.harness.PromptImage> images = null;
        switch (content) {
            case CustomMessageContent.Text t -> text = t.text();
            case CustomMessageContent.Blocks b -> {
                var textParts = new java.util.ArrayList<String>();
                var imageParts = new java.util.ArrayList<com.pijava.agent.harness.PromptImage>();
                for (var block : b.blocks()) {
                    if (block instanceof com.pijava.ai.message.ContentBlock.TextContent text2) {
                        textParts.add(text2.text());
                    } else if (block instanceof com.pijava.ai.message.ContentBlock.ImageContent img) {
                        imageParts.add(new com.pijava.agent.harness.PromptImage(
                            img.mediaType(), img.data()));
                    }
                }
                text = String.join("\n", textParts);
                if (!imageParts.isEmpty()) {
                    images = imageParts;
                }
            }
        }
        host.processPrompt(text, com.pijava.coding.agent.core.PromptConfig.extension(
            opts.deliverAs(), images, opts.expandPromptTemplates()));
    }

    // ── 批 26（docs/37 T4）：会话元数据面（委托宿主会话，pi types.ts:1719-1732）──

    /** pi {@code appendEntry}——custom 条目＋entry_appended＋落盘（AgentSession 既有全链）。 */
    @Override
    public void appendEntry(String customType, Map<String, Object> data) {
        requireToolControl().appendCustomEntry(customType, data);
    }

    /** pi {@code setSessionName}——规范化＋落 session_info＋session_info_changed 双发。 */
    @Override
    public void setSessionName(String name) {
        requireToolControl().setSessionName(name);
    }

    /** pi {@code getSessionName}——stored 名（批 20 B32 面），未命名 ⇒ null。 */
    @Override
    public String getSessionName() {
        return requireToolControl().storedSessionName();
    }

    /** pi {@code setLabel}——目标不存在抛 {@code Entry <id> not found}（文案批 26 修真）。 */
    @Override
    public void setLabel(String entryId, String label) {
        requireToolControl().setLabel(entryId, label);
    }

    // ── 批 26（docs/37 T5）：模型与思考级别面（pi types.ts:1763-1772）──

    /**
     * pi 扩展面 {@code setModel}（agent-session.ts:3429-3432 逐字）：auth 未配置 ⇒
     * false 不抛。auth 判据对位 pi {@code hasConfiguredAuth}（配置快照）——java 读
     * 活凭证链（{@code Credentials.resolveCredential}：环境变量＋存储；比快照更准，
     * 同真值域）。
     */
    @Override
    public boolean setModel(com.pijava.ai.model.ModelId<?> model) {
        var host = requireToolControl();
        if (com.pijava.ai.auth.Credentials.resolveCredential(model.provider()).isEmpty()) {
            return false;
        }
        host.setModel(model);
        return true;
    }

    /** pi {@code getThinkingLevel}——harness 车道当前档。 */
    @Override
    public com.pijava.ai.thinking.ModelThinkingLevel getThinkingLevel() {
        return requireToolControl().harness().getThinkingLevel();
    }

    /** pi {@code setThinkingLevel}——会话内设级（变化才发双事件，桥已在）。 */
    @Override
    public void setThinkingLevel(com.pijava.ai.thinking.ModelThinkingLevel level) {
        requireToolControl().setThinkingLevel(level);
    }

    /**
     * 批 26（docs/37 T6，E5-17）：pi {@code exec}——cwd 回落宿主会话 cwd
     * （loader.ts:413 的 {@code options?.cwd ?? cwd} 对位）；无宿主 ⇒ 进程当前目录。
     */
    @Override
    public ExtensionExec.ExecResult exec(String command, java.util.List<String> args,
                                         ExtensionExec.ExecOptions options) {
        var host = hostSessionOrNull();
        return ExtensionExec.execCommand(command, args == null ? java.util.List.of() : args,
            host == null ? null : host.cwd(), options);
    }

    /**
     * 批 26（docs/37 T8，E5-5）：pi {@code unregisterProvider}——删注册
     * （裁决⑥：内置模型恢复面不做；未注册静默，pi 同）。
     */
    @Override
    public void unregisterProvider(String name) {
        providers().remove(name);
    }

    /** 批 26（docs/37 T7，E5-28）：pi {@code events}——跟踪视图（失效退订的载体）。 */
    @Override
    public ExtensionChannelBus events() {
        return eventsView;
    }

    // ── 批 26（docs/37 T9，E5-6）：快捷键注册面 ──

    /**
     * pi {@code registerShortcut}（loader.ts:320-323）——写本扩展的表
     * （keyId 在注册点解析校验，裁决⑫）；同名键再注册＝覆盖本扩展旧注册
     * （pi 的 Map.set 同形）。
     */
    void registerShortcut(String extensionPath, String keyId, String description,
                          ExtensionShortcut.ShortcutHandler handler) {
        com.pijava.coding.agent.core.KeyIdParser.parse(keyId); // 裁决⑫：注册点校验
        shortcutsByExtension
            .computeIfAbsent(extensionPath, k -> new java.util.LinkedHashMap<>())
            .put(keyId, new ExtensionShortcut(keyId, description, handler, extensionPath));
    }

    /** 无归属的共享基础上下文调用 ⇒ 抛（registerMcpServer 同纪律，批 23 先例）。 */
    @Override
    public void registerShortcut(String keyId, String description,
                                 ExtensionShortcut.ShortcutHandler handler) {
        throw new IllegalStateException(
            "registerShortcut() 需经 per-extension 上下文调用（无扩展归属可记）");
    }

    // ── 批 26（docs/37 T10）：message/entry 渲染器注册面 ──

    /** pi {@code extension.messageRenderers.set}（loader.ts:351-354）。 */
    void registerMessageRenderer(String extensionPath, String customType,
                                 ExtensionMessageRenderer renderer) {
        messageRenderersByExtension
            .computeIfAbsent(extensionPath, k -> new java.util.LinkedHashMap<>())
            .put(customType, renderer);
    }

    /** pi {@code extension.entryRenderers ??= new Map(); .set}（loader.ts:361-365）。 */
    void registerEntryRenderer(String extensionPath, String customType,
                               ExtensionEntryRenderer renderer) {
        entryRenderersByExtension
            .computeIfAbsent(extensionPath, k -> new java.util.LinkedHashMap<>())
            .put(customType, renderer);
    }

    /** pi {@code getMessageRenderer}（runner.ts:775-782）：扩展加载序 first-wins。 */
    public ExtensionMessageRenderer resolveMessageRenderer(String customType) {
        for (var renderers : messageRenderersByExtension.values()) {
            var renderer = renderers.get(customType);
            if (renderer != null) {
                return renderer;
            }
        }
        return null;
    }

    /** pi {@code getEntryRenderer}（runner.ts:798-805）：扩展加载序 first-wins。 */
    public ExtensionEntryRenderer resolveEntryRenderer(String customType) {
        for (var renderers : entryRenderersByExtension.values()) {
            var renderer = renderers.get(customType);
            if (renderer != null) {
                return renderer;
            }
        }
        return null;
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

    // ── 批 26（docs/37 T11）：markdown 变换器注册面 ──

    /** pi {@code extension.markdownTransformer = transformer}（loader.ts:361，单槽覆盖）。 */
    void registerMarkdownTransformer(String extensionPath, MarkdownTransformer transformer) {
        markdownTransformers.put(extensionPath, transformer);
    }

    /** pi {@code getMarkdownTransformers}（runner.ts:785-787）：扩展加载序的链。 */
    public java.util.List<MarkdownTransformer> markdownTransformers() {
        return java.util.List.copyOf(markdownTransformers.values());
    }

    /** 无归属共享面 ⇒ 抛（同 registerShortcut 纪律）。 */
    @Override
    public void registerMarkdownTransformer(MarkdownTransformer transformer) {
        throw new IllegalStateException(
            "registerMarkdownTransformer() 需经 per-extension 上下文调用（无扩展归属可记）");
    }

    /**
     * pi {@code runner.getShortcuts}（runner.ts:673-716）——冲突三规则解决。
     *
     * @param keys    内置键位表；{@code null} ⇒ 空表（无键位面的宿主：RPC/headless）
     * @param hasUi   pi 的「无 UI ⇒ console.warn」门：false ⇒ 诊断写 stderr
     */
    public ExtensionShortcuts.Resolution resolvedShortcuts(
            com.pijava.coding.agent.core.KeybindingsManager keys, boolean hasUi) {
        var resolution = ExtensionShortcuts.resolve(keys, isWindowsPlatform(), shortcutsByExtension);
        if (!hasUi) {
            for (var diagnostic : resolution.diagnostics()) {
                System.err.println(diagnostic); // pi console.warn 对位（runner.ts:683-686）
            }
        }
        return resolution;
    }

    /** pi {@code useWindowsKeybindings} 对位（keybindings.ts:15-18）。 */
    private static boolean isWindowsPlatform() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
            || System.getenv("WSL_DISTRO_NAME") != null
            || System.getenv("WSL_INTEROP") != null;
    }

    /**
     * pi {@code runtime.invalidate} 的 eventBus 半（loader.ts:193-196 逐字）：
     * 会话失效（替换/reload）时统一退订全部通道订阅。
     */
    public void invalidateChannels() {
        for (var subscription : channelSubscriptions) {
            try {
                subscription.close();
            } catch (Exception e) {
                // pi 的 unsubscribe 不抛；java 侧隔离（单个退订失败不挡其余）。
                java.util.logging.Logger.getLogger(DefaultExtensionContext.class.getName())
                    .warning("channel unsubscribe failed: " + e);
            }
        }
        channelSubscriptions.clear();
    }
}
