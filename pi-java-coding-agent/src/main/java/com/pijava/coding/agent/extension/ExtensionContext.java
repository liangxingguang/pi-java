package com.pijava.coding.agent.extension;

import java.util.Map;

import com.pijava.agent.entry.CustomMessageContent;
import com.pijava.agent.skill.SkillManager;
import com.pijava.agent.tool.ToolRegistry;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.coding.agent.core.CustomMessageDelivery;
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
        return sendMessage(customType, content, display, details,
            new SendMessageOptions(Boolean.FALSE, null));
    }

    /**
     * 批 26（docs/37 T3，E5-12 收口）：pi {@code sendMessage} 全行为
     * （agent-session.ts:2293-2329 五臂逐字——nextTurn 缓冲／流式 steer·followUp
     * 队列〔默认 steer〕／triggerTurn 起轮〔settled 发射窗内延迟〕／流式延迟落盘／
     * 空闲立即落盘）。
     *
     * @return 立即落盘臂的条目 id；其余臂（缓冲/排队/起轮）无即时条目 ⇒ {@code null}
     */
    String sendMessage(String customType, CustomMessageContent content, boolean display,
                       Map<String, Object> details, SendMessageOptions options);

    /**
     * 批 26（docs/37 T3，E5-13）：pi {@code sendUserMessage}
     * （agent-session.ts:2365-2393 逐字）——发用户消息，<b>恒触发回合</b>；流式中
     * 按 {@code deliverAs} 排队。content 数组形：文本块 {@code "\n"} 拼接＋图片另表。
     * {@code expandPromptTemplates} 默认 <b>false</b>（pi :2390——不派发扩展命令、
     * 不展开 skill/模板）。
     */
    default void sendUserMessage(String content, SendUserMessageOptions options) {
        sendUserMessage(CustomMessageContent.of(content), options);
    }

    /** {@link #sendUserMessage(String, SendUserMessageOptions)} 的数组形（text/image 块）。 */
    void sendUserMessage(CustomMessageContent content, SendUserMessageOptions options);

    /** {@link #sendUserMessage(String, SendUserMessageOptions)} 无选项便捷形。 */
    default void sendUserMessage(String content) {
        sendUserMessage(content, null);
    }

    /**
     * sendMessage 投递选项（对齐 pi {@code SendMessageOptions}，批 26 扩 deliverAs）。
     *
     * @param triggerTurn 是否触发一次 LLM 回合——{@code null} ≙ pi 的 undefined
     *                    （流式中视同 true 进队列臂，空闲视同 false 直接落盘）
     * @param deliverAs   投递语义（{@code null} ≙ 未给）
     */
    record SendMessageOptions(Boolean triggerTurn, CustomMessageDelivery deliverAs) {
        /** 兼容构造器（批 26 前的单参形）。 */
        public SendMessageOptions(boolean triggerTurn) {
            this(triggerTurn, null);
        }
    }

    /**
     * sendUserMessage 选项（pi {@code {deliverAs?: "steer"|"followUp",
     * expandPromptTemplates?: boolean}}）。
     *
     * @param deliverAs 流式中的排队行为（null ≙ 未给——流式中未给则按 prompt 路由门语义）
     * @param expandPromptTemplates null ≙ pi undefined ⇒ <b>false</b>
     *        （sendUserMessage 私有默认，pi :2390）
     */
    record SendUserMessageOptions(com.pijava.coding.agent.core.StreamingBehavior deliverAs,
                                  Boolean expandPromptTemplates) {}

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

    /**
     * 批 23（docs/34 T4，E5-20）：pi {@code getCommands}（types.ts:1753；
     * agent-session.ts:3361-3381 三源序逐字）——当前会话可调用的斜杠命令：
     * 扩展注册命令（撞名编号后的 invocationName）→ prompt 模板 → 技能
     * （{@code skill:} 前缀）；无内置命令（pi 语义：内置不可经 prompt 调用）。
     */
    java.util.List<com.pijava.coding.agent.core.slash.SlashCommandInfo> getCommands();

    // ── 批 26（docs/37 T4/T5）：会话元数据与模型面 ──

    /**
     * pi {@code appendEntry}（types.ts:1719；loader.ts:391-394）——追加 custom 条目
     * （状态持久化用，<b>不进 LLM 上下文</b>）；落盘后广播 {@code entry_appended}。
     */
    void appendEntry(String customType, Map<String, Object> data);

    /**
     * pi {@code setSessionName}（agent-session.ts:3942-3947）——换行消毒＋trim＋空名
     * 清除（规范化在会话层），落 session_info 并双发 {@code session_info_changed}。
     */
    void setSessionName(String name);

    /**
     * pi {@code getSessionName}（session-manager.ts:1318-1327）——最新 session_info
     * 的名（stored 名，非显示名）；未命名 ⇒ {@code null} ≙ pi undefined（裁决⑪）。
     */
    String getSessionName();

    /**
     * pi {@code setLabel}（loader.ts:406-409 → session-manager.ts:1441-1462）——
     * 设置/清除条目标签（null ≙ undefined ⇒ 清除）；目标条目不存在抛
     * {@code Entry <id> not found}（文案逐字，裁决⑦已核两存储经 validateTarget 抛）。
     */
    void setLabel(String entryId, String label);

    /**
     * pi 扩展面 {@code setModel}（agent-session.ts:3429-3432 逐字）：auth 未配置 ⇒
     * <b>返回 false 不抛</b>；配置 ⇒ 会话内换模（不写全局默认——pi 扩展面
     * 恒不 persist）＋ {@code model_select}（源 "set"）。
     */
    boolean setModel(com.pijava.ai.model.ModelId<?> model);

    /** pi {@code getThinkingLevel}（types.ts:1766）——当前思考级别。 */
    com.pijava.ai.thinking.ModelThinkingLevel getThinkingLevel();

    /**
     * pi {@code setThinkingLevel}（types.ts:1772 → agent-session.ts:2612-2635）——
     * 会话内设级（不写全局默认）；级别真变才发 {@code thinking_level_changed}＋
     * 扩展事件 {@code thinking_level_select}（桥已在 SessionEventBridge:118-120）。
     */
    void setThinkingLevel(com.pijava.ai.thinking.ModelThinkingLevel level);

    /**
     * 批 26（docs/37 T6，E5-17）：pi {@code exec}（types.ts:1735 → core/exec.ts
     * 逐字语义）——无壳 spawn、timeout/signal 两臂、出错 code=1。java 侧为
     * <b>阻塞</b>调用（pi 返回 Promise；调用方要异步请自行包虚拟线程）。
     * cwd 默认扩展加载 cwd（loader.ts:413 对位）。
     */
    ExtensionExec.ExecResult exec(String command, java.util.List<String> args,
                                  ExtensionExec.ExecOptions options);

    /**
     * 批 26（docs/37 T8，E5-5）：pi {@code unregisterProvider}（types.ts:1846；
     * loader.ts:466-468 → modelRegistry.unregisterProvider）——移除该 provider 的
     * 注册；未注册静默无效果（pi 同）。[裁决⑥：内置模型恢复面不做]
     */
    void unregisterProvider(String name);

    /**
     * 批 26（docs/37 T7，E5-28）：pi {@code ExtensionAPI.events}（types.ts:1889-1890；
     * core/event-bus.ts:1-33）——扩展间共享通道总线。经本面 {@code on} 的订阅被
     * 跟踪（pi {@code trackEventBusSubscription}），会话失效（替换/reload）时统一
     * 退订。
     */
    ExtensionChannelBus events();

    /**
     * 批 26（docs/37 T9，E5-6）：pi {@code registerShortcut}（types.ts:1655；
     * loader.ts:320-323）——注册键盘快捷键。keyId 非法即在注册点抛
     * {@link IllegalArgumentException}（裁决⑫）。与内置保留键/其他扩展的冲突在
     * 解决期按 pi 三规则处理（保留键跳过／非保留内置扩展赢／互撞后者赢，均记诊断）。
     * 需经 per-extension 上下文调用（归属进诊断与 /hotkeys 段）。
     */
    void registerShortcut(String keyId, String description,
                          ExtensionShortcut.ShortcutHandler handler);

    /**
     * 批 26（docs/37 T10，E5-8）：pi {@code registerMessageRenderer}
     * （types.ts:1687-1688）——为 customType 的 custom 消息注册 TUI 渲染器；
     * 解决期按扩展加载序 first-wins（runner.ts:775-782）。需经 per-extension
     * 上下文调用。
     */
    void registerMessageRenderer(String customType, ExtensionMessageRenderer renderer);

    /**
     * 批 26（docs/37 T10，E5-10）：pi {@code registerEntryRenderer}
     * （types.ts:1693-1694）——为 customType 的 custom 条目注册 TUI 渲染器；
     * 无渲染器的 custom 条目<b>不渲染</b>（pi interactive-mode.ts:3848-3850）。
     * 需经 per-extension 上下文调用。
     */
    void registerEntryRenderer(String customType, ExtensionEntryRenderer renderer);

    /**
     * 批 26（docs/37 T11，E5-9）：pi {@code registerMarkdownTransformer}
     * （types.ts:1690-1691）——注册 markdown 变换器（渲染前应用于 assistant/
     * thinking 消息）。<b>单槽</b>：同扩展重复注册后者覆盖（loader.ts:361 的
     * 赋值语义）；跨扩展按加载序链式串行。需经 per-extension 上下文调用。
     */
    void registerMarkdownTransformer(MarkdownTransformer transformer);

    /**
     * 批 27（B162 裁决④）：工具定义元数据表（pi {@code _toolDefinitions} 对位）。
     * <b>内部面</b>——生产方是内置扩展（tool-search／MCP registrar）；一般扩展经
     * {@link #getAllTools()} 读投影即可，无需碰本表。
     */
    default com.pijava.coding.agent.core.ToolDefinitions toolDefinitions() {
        throw new UnsupportedOperationException("toolDefinitions not available in this context");
    }
}
