package com.pijava.coding.agent.extension;

import java.util.Map;

import com.pijava.agent.entry.CustomMessageContent;
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

    /**
     * 扩展 UI 服务（批 17 全面化为 pi {@code ExtensionUIContext} 28 成员——
     * TUI 模式可弹对话/注入槽位，RPC 模式走九方法通道，无通道回落 {@link ExtensionUI#NOOP}）。
     *
     * <p>实现按 pi runner.ts:876-884 的<b>晚绑定</b>语义：调用时才解析当前 UI
     * （{@code bindUI}/{@code bindCore} 的改动即时反映——java 对位＝
     * {@code AgentSession.extensionUI(ui, mode)} 后注入）。</p>
     */
    default ExtensionUI ui() {
        return ExtensionUI.noop();
    }

    /**
     * 批 17（T1）：pi {@code ExtensionContext.mode}（types.ts:330）——
     * {@code "tui" | "rpc" | "json" | "print"}；无模式概念 ⇒ {@code "print"}。
     */
    default String mode() {
        return "print";
    }

    /**
     * 批 17（T1）：pi {@code ExtensionContext.hasUI}（types.ts:332：
     * 「true in TUI and RPC modes」）——对话级 UI 可用性。
     * 对位 pi runner.ts:622 的恒等比较（{@code uiContext !== noOpUIContext}）。
     */
    default boolean hasUi() {
        return false;
    }

    /** 便捷访问底层服务集合。 */
    SessionServices services();

    /**
     * 订阅扩展事件（pi {@code ExtensionAPI.on()}，loader.ts:272-288；裁决① 的
     * spec-token 单方法 —— pi 的 41 个重载只是类型约束，运行时全走一个 {@code on()}）。
     *
     * <p>注册序即触发序；返回的退订闭包按实例移除、空表删键（pi 逐字语义）。
     * 需经 per-extension 上下文（{@link #forExtension} 的委托）调用 —— 委托可
     * 在 register 期或其后任何时候使用（pi 的扩展也能运行期订阅）；共享基础
     * 上下文调用抛 {@link IllegalStateException}（无扩展名可归属，非时限门）。</p>
     *
     * @param spec    事件规格令牌（{@code ExtensionEventSpec.CONTEXT} 等）
     * @param handler 处理器；返回 {@code null ≙ pi 的 undefined}（不发言）
     * @param <E>     事件载荷类型
     * @param <R>     结果类型（观察型为 {@link Void}）
     * @return 退订闭包
     */
    <E extends ExtensionEvent, R> AutoCloseable on(ExtensionEventSpec<E, R> spec,
                                                   ExtensionEventHandler<E, R> handler);

    /**
     * 派生 per-extension 上下文（docs/14：错误按扩展名归属，pi 的 {@code extensionPath}
     * 对齐点）。默认返回自身 —— 无归属概念的实现用它保持 {@link ExtensionManager}
     * 装配路径不炸。
     */
    default ExtensionContext forExtension(String extensionName) {
        return this;
    }

    /**
     * 批 23（docs/34 T1）：带路径身份的 {@link #forExtension(String)}——
     * {@code extensionPath} 是 pi {@code extension.path} 对位（JAR 路径串；ServiceLoader
     * 发现 ⇒ 合成 {@code "builtin:<name>"}，裁决⑥），MCP 注册的所有权门与
     * 命令 sourceInfo 盖章用。默认退化单参（path 即名）。
     */
    default ExtensionContext forExtension(String extensionName, String extensionPath) {
        return forExtension(extensionName);
    }

    // ── 批 16（docs/27）：CLI flag 面 / 激活门 / 工具渲染器 ──

    /**
     * 批 16（T1b）：pi {@code registerFlag}（loader.ts:331-349）——声明一个 CLI flag。
     * default 类型不匹配即抛（pi 文案逐字：
     * {@code Invalid default for flag "<name>": expected <type>, got <实际>}）；
     * default 是 set-if-absent（CLI 值/先到 default 不被覆盖）。
     */
    void registerFlag(String name, ExtensionFlagOptions options);

    /**
     * 批 16（T1b）：pi {@code getFlag}（loader.ts:373-378）——<b>所有权门</b>：
     * 只能读本扩展注册的名，否则 {@code null}（≙ pi undefined）。
     *
     * @return {@link Boolean} | {@link String} | {@code null}
     */
    Object getFlag(String name);

    /**
     * 批 16（T2c）：pi {@code getActiveTools}（types.ts:1737-1738）——
     * 声明给模型的工具名（pi 读 {@code agent.state.tools}；java 读
     * {@code lane.activeTools}）。
     */
    java.util.List<String> getActiveTools();

    /**
     * 批 16（T2c）：pi {@code getAllTools}（types.ts:1740-1741）的窄化投影
     * （裁决⑥，窄差 D91）。
     */
    java.util.List<ExtensionToolInfo> getAllTools();

    /**
     * 批 16（T2c）：pi {@code setActiveTools}（types.ts:1743-1749）——
     * 未知名静默忽略；java 无 hidden/exposure 概念（窄差并入 D91）。
     */
    void setActiveTools(java.util.List<String> toolNames);

    /**
     * 批 16（T4b）：pi {@code registerToolRenderer}（types.ts:1695-1696）——
     * resolver 按注册序（＝扩展加载序）成责任链。
     */
    void registerToolRenderer(ToolRendererResolver resolver);

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

    // ── 批 23（docs/34 T1）：MCP 服务器注册面（pi types.ts:1857-1872） ──

    /**
     * pi {@code registerMcpServer}（loader.ts:471-489 三守卫逐字）：配置校验失败抛
     * （{@code Invalid MCP server registered by extension "<path>": <原因>}）；
     * <b>别的扩展</b>注册的同名抛错；{@code -}/{@code _} 归一后撞命名空间亦抛。
     * 同名再注册＝替换本扩展的旧注册。注册不持久化——每次加载要重注册（pi 文档注记）。
     *
     * <p>装载期注册静默落表（changeListener 装配尾才接，裁决⑧）；装配后注册即时
     * 触发 {@code mcp_servers_change} 广播。</p>
     *
     * @throws IllegalArgumentException 配置校验失败
     * @throws IllegalStateException    同名被他扩展占用 / 命名空间冲突 / 无扩展归属
     */
    void registerMcpServer(String name, com.pijava.mcp.config.McpServerConfig config);

    /**
     * pi {@code unregisterMcpServer}（loader.ts:491-494）——移除<b>本扩展</b>注册的
     * 服务器；别的扩展的同名注册静默不动（注册表所有权门，mcp-servers.ts:298-303）。
     */
    void unregisterMcpServer(String name);

    /**
     * pi {@code getMcpServers}（loader.ts:496-498）——全部扩展注册的服务器，
     * 注册序，配置深拷贝（注册表 list() 语义）。
     */
    java.util.List<com.pijava.mcp.config.RegisteredMcpServer> getMcpServers();
}
