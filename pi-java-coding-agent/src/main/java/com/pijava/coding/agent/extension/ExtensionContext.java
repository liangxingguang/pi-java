package com.pijava.coding.agent.extension;

import java.util.Map;

import com.pijava.agent.entry.CustomMessageContent;
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

    /**
     * 落盘一条 custom_message（对齐 pi {@code pi.sendMessage} 的仅落盘路径）：
     * content 参与后续 LLM 上下文，{@code display} 仅控制 TUI 渲染，
     * {@code details} 为扩展私有元数据、不进 LLM。返回条目 id。
     * 不触发 LLM 回合。
     */
    default String sendMessage(String customType, CustomMessageContent content,
                               boolean display, Map<String, Object> details) {
        return sendMessage(customType, content, display, details, new SendMessageOptions(false));
    }

    /** 带选项的 {@link #sendMessage} 重载；{@code options.triggerTurn()} 本轮未实现。 */
    String sendMessage(String customType, CustomMessageContent content, boolean display,
                       Map<String, Object> details, SendMessageOptions options);

    /**
     * sendMessage 投递选项（对齐 pi {@code SendMessageOptions}）。
     *
     * @param triggerTurn 是否触发一次 LLM 回合——当前版本未实现，{@code true} 时抛
     *                    {@link UnsupportedOperationException}；steer/followUp/nextTurn
     *                    投递语义为后续任务
     */
    record SendMessageOptions(boolean triggerTurn) {}
}
