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
    public void registerToolRenderer(ToolRendererResolver resolver) {
        toolRenderers.register(resolver);
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

    /**
     * 派生 per-extension 委托上下文：全部服务方法透传，唯 {@code on()} 以扩展名
     * 登记进总线（docs/14 §3.3：错误归属对齐 pi 的 {@code extensionPath}）。
     * 无总线时同样派生（其 {@code on()} 抛与基础上下文一致的异常）。
     */
    @Override
    public ExtensionContext forExtension(String extensionName) {
        return new PerExtensionContext(extensionName);
    }

    /** per-extension 委托：除 {@code on()} 外全部透传基础上下文。 */
    private final class PerExtensionContext implements ExtensionContext {
        private final String extensionName;

        PerExtensionContext(String extensionName) {
            this.extensionName = extensionName;
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
            return DefaultExtensionContext.this.slashCommands();
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
    }

    @Override
    public String sendMessage(String customType, CustomMessageContent content, boolean display,
                              Map<String, Object> details, SendMessageOptions options) {
        if (options != null && options.triggerTurn()) {
            throw new UnsupportedOperationException(
                "sendMessage(triggerTurn=true) is not implemented yet");
        }
        var supplier = sessionSupplier;
        var tree = supplier == null ? null : supplier.get();
        if (tree == null) {
            throw new IllegalStateException(
                "sendMessage requires a bound session; none is available yet");
        }
        return tree.appendCustomMessageEntry(customType, content, display, details);
    }
}
