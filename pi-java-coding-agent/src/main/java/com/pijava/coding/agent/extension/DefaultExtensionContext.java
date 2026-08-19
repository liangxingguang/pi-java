package com.pijava.coding.agent.extension;

import com.pijava.agent.skill.SkillManager;
import com.pijava.agent.tool.ToolRegistry;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.coding.agent.core.SessionServices;
import com.pijava.coding.agent.core.SettingsManager;
import com.pijava.coding.agent.core.slash.CommandRegistry;

/**
 * {@link ExtensionContext} 默认实现 —— 包装 {@link SessionServices} + SkillManager。
 */
public final class DefaultExtensionContext implements ExtensionContext {

    private final SessionServices services;
    private final SkillManager skills;
    private final ExtensionUI ui;

    /** @param services 会话服务（工具/命令/Provider/settings）
     *  @param skills   技能注册表（harness 的 skillManager）
     *  @param ui       扩展 UI 服务（RPC 模式注入；缺省 noop） */
    public DefaultExtensionContext(SessionServices services, SkillManager skills,
                                   ExtensionUI ui) {
        this.services = services;
        this.skills = skills;
        this.ui = ui == null ? ExtensionUI.noop() : ui;
    }

    /** 便捷构造：noop UI。 */
    public DefaultExtensionContext(SessionServices services, SkillManager skills) {
        this(services, skills, ExtensionUI.noop());
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
}
