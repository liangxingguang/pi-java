package com.pijava.coding.agent.extension;

import com.pijava.agent.skill.SkillManager;
import com.pijava.agent.tool.ToolRegistry;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.coding.agent.core.SessionServices;
import com.pijava.coding.agent.core.SettingsManager;
import com.pijava.coding.agent.core.slash.CommandRegistry;

/**
 * 扩展注册上下文 —— 包装 {@link SessionServices}，另补一个 {@link SkillManager}
 * （SessionServices 目前没有技能字段）。
 */
public interface ExtensionContext {

    /** 工具注册表（扩展可注册 AgentTool）。 */
    ToolRegistry tools();

    /** 斜杠命令注册表。 */
    CommandRegistry slashCommands();

    /** Provider 注册表。 */
    ProviderRegistry providers();

    /** 技能注册表。 */
    SkillManager skills();

    /** 设置管理器。 */
    SettingsManager settings();

    /** 扩展 UI 服务（RPC 模式可交互；无通道回落 noop）。 */
    default ExtensionUI ui() {
        return ExtensionUI.noop();
    }

    /** 便捷访问底层服务集合。 */
    SessionServices services();
}
