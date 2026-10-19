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
    private final ExtensionUI ui;
    private final ExtensionEventBus bus;
    private volatile Supplier<SessionTree> sessionSupplier;

    /** @param services 会话服务（工具/命令/Provider/settings）
     *  @param skills   技能注册表（harness 的 skillManager）
     *  @param ui       扩展 UI 服务（RPC 模式注入；缺省 noop）
     *  @param bus      扩展事件总线（{@code on()} 的登记目标；null ⇒ 订阅面不可用） */
    public DefaultExtensionContext(SessionServices services, SkillManager skills,
                                   ExtensionUI ui, ExtensionEventBus bus) {
        this.services = services;
        this.skills = skills;
        this.ui = ui == null ? ExtensionUI.noop() : ui;
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
        return services.slashCommands();
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

    @Override
    public ExtensionUI ui() {
        return ui;
    }

    @Override
    public SessionServices services() {
        return services;
    }

    /** 共享总线（桥装配用；null ⇒ 订阅面未装）。 */
    public ExtensionEventBus eventBus() {
        return bus;
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
        public SessionServices services() {
            return DefaultExtensionContext.this.services();
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
