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
 * per-extension 委托：除 {@code on()} 与 MCP 注册面外全部透传基础上下文
 * （结构债拆分：自 {@link DefaultExtensionContext} 的内类顶出为顶层类，
 * 行为零变化——原 {@code DefaultExtensionContext.this.x} 委托改 {@code base.x}）。
 */
final class PerExtensionContext implements ExtensionContext {
    private final DefaultExtensionContext base;
    private final String extensionName;
    /** 批 23（T1）：pi {@code extension.path} 对位（MCP 所有权门/错文归属用）。 */
    private final String extensionPath;
    /** 批 23（T4）：本扩展的 sourceInfo 章（pi {@code extension.sourceInfo}）。 */
    private final com.pijava.coding.agent.core.source.SourceInfo sourceInfo;

    PerExtensionContext(DefaultExtensionContext base, String extensionName, String extensionPath,
                        com.pijava.coding.agent.core.source.SourceInfo sourceInfo) {
        this.base = base;
        this.extensionName = extensionName;
        this.extensionPath = extensionPath;
        this.sourceInfo = sourceInfo;
    }

    /** 派生口（{@link DefaultExtensionContext#forExtension} 转入）：补算 sourceInfo 章。 */
    static PerExtensionContext of(DefaultExtensionContext base, String extensionName,
                                  String extensionPath) {
        return new PerExtensionContext(base, extensionName, extensionPath,
            computeSourceInfo(base, extensionPath));
    }

    /**
     * 批 23（T4，E5-34）：扩展的 sourceInfo——pi {@code applyExtensionSourceInfo}
     * （resource-loader.ts:918-922）对位。目录根从 settings 存储面取（仅
     * {@code FileSettingsStorage} 携带）；取不到（内存存储/测试道）⇒
     * 合成路径规则仍生效，文件路径落 temporary 兜底。
     */
    private static com.pijava.coding.agent.core.source.SourceInfo computeSourceInfo(
            DefaultExtensionContext base, String extensionPath) {
        var services = base.services();
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

    @Override
    public <E extends ExtensionEvent, R> AutoCloseable on(
            ExtensionEventSpec<E, R> spec, ExtensionEventHandler<E, R> handler) {
        if (base.eventBus() == null) {
            return base.on(spec, handler);
        }
        return base.eventBus().on(extensionName, spec, handler);
    }

    @Override
    public ToolRegistry tools() {
        return base.tools();
    }

    @Override
    public CommandRegistry slashCommands() {
        // 批 23（T4）：注册即盖本扩展的 sourceInfo 章（pi loader.ts:317 对位）。
        return base.services().slashCommands()
            .extensionView(extensionPath, sourceInfo);
    }

    @Override
    public ProviderRegistry providers() {
        return base.providers();
    }

    @Override
    public SkillManager skills() {
        return base.skills();
    }

    @Override
    public SettingsManager settings() {
        return base.settings();
    }

    @Override
    public ExtensionUI ui() {
        return base.ui();
    }

    @Override
    public boolean hasUi() {
        return base.hasUi();
    }

    @Override
    public String mode() {
        return base.mode();
    }

    @Override
    public SessionServices services() {
        return base.services();
    }

    @Override
    public void registerFlag(String name, ExtensionFlagOptions options) {
        base.surface().registerFlag(extensionName, name, options);
    }

    @Override
    public Object getFlag(String name) {
        return base.surface().getFlag(extensionName, name);
    }

    @Override
    public java.util.List<String> getActiveTools() {
        return base.getActiveTools();
    }

    @Override
    public java.util.List<ExtensionToolInfo> getAllTools() {
        return base.getAllTools();
    }

    @Override
    public void setActiveTools(java.util.List<String> toolNames) {
        base.setActiveTools(toolNames);
    }

    @Override
    public com.pijava.coding.agent.core.ToolDefinitions toolDefinitions() {
        return base.toolDefinitions();
    }

    @Override
    public String cwd() {
        return base.cwd();
    }

    @Override
    public ExtensionSessionView sessionManager() {
        return base.sessionManager();
    }

    @Override
    public com.pijava.agent.harness.PiLoop.ToolOutcome executeTool(
            String callerToolCallId, com.pijava.ai.AbortSignal signal, String name,
            Map<String, Object> args, java.util.function.Consumer<Object> onUpdate) {
        return base.executeTool(callerToolCallId, signal, name,
            args, onUpdate);
    }

    @Override
    public java.util.List<com.pijava.agent.tool.AgentTool<?, ?>> callableTools() {
        return base.callableTools();
    }

    @Override
    public void registerVirtualModel(ExtensionVirtualModel model) {
        // 路由上下文是<b>本委托</b>（pi createContext() 的 per-extension 形）
        base.surface().registerVirtualModel(model, this);
    }

    @Override
    public void unregisterVirtualModel(String provider, String id) {
        base.surface().unregisterVirtualModel(provider, id);
    }

    @Override
    public void registerToolRenderer(ToolRendererResolver resolver) {
        base.registerToolRenderer(resolver);
    }

    @Override
    public String sendMessage(String customType, CustomMessageContent content,
                              boolean display, Map<String, Object> details,
                              SendMessageOptions options) {
        return base.sendMessage(
            customType, content, display, details, options);
    }

    @Override
    public void sendUserMessage(CustomMessageContent content, SendUserMessageOptions options) {
        base.sendUserMessage(content, options);
    }

    // ── 批 26（docs/37 T4/T5）：会话元数据与模型面（透传本体） ──

    @Override
    public void appendEntry(String customType, Map<String, Object> data) {
        base.appendEntry(customType, data);
    }

    @Override
    public void setSessionName(String name) {
        base.setSessionName(name);
    }

    @Override
    public String getSessionName() {
        return base.getSessionName();
    }

    @Override
    public void setLabel(String entryId, String label) {
        base.setLabel(entryId, label);
    }

    @Override
    public boolean setModel(com.pijava.ai.model.ModelId<?> model) {
        return base.setModel(model);
    }

    @Override
    public com.pijava.ai.thinking.ModelThinkingLevel getThinkingLevel() {
        return base.getThinkingLevel();
    }

    @Override
    public void setThinkingLevel(com.pijava.ai.thinking.ModelThinkingLevel level) {
        base.setThinkingLevel(level);
    }

    @Override
    public ExtensionExec.ExecResult exec(String command, java.util.List<String> args,
                                         ExtensionExec.ExecOptions options) {
        return base.exec(command, args, options);
    }

    @Override
    public void unregisterProvider(String name) {
        base.unregisterProvider(name);
    }

    @Override
    public ExtensionChannelBus events() {
        return base.events();
    }

    @Override
    public void registerShortcut(String keyId, String description,
                                 ExtensionShortcut.ShortcutHandler handler) {
        base.surface().registerShortcut(extensionPath, keyId, description,
            handler);
    }

    @Override
    public void registerMessageRenderer(String customType, ExtensionMessageRenderer renderer) {
        base.surface().registerMessageRenderer(extensionPath, customType,
            renderer);
    }

    @Override
    public void registerEntryRenderer(String customType, ExtensionEntryRenderer renderer) {
        base.surface().registerEntryRenderer(extensionPath, customType,
            renderer);
    }

    @Override
    public void registerMarkdownTransformer(MarkdownTransformer transformer) {
        base.surface().registerMarkdownTransformer(extensionPath, transformer);
    }

    // ── 批 23（docs/34 T1）：MCP 注册面（带归属转入本体） ──

    @Override
    public void registerMcpServer(String name,
                                  com.pijava.mcp.config.McpServerConfig config) {
        base.surface().registerMcpServer(extensionPath, name, config);
    }

    @Override
    public void unregisterMcpServer(String name) {
        base.surface().unregisterMcpServer(extensionPath, name);
    }

    @Override
    public java.util.List<com.pijava.mcp.config.RegisteredMcpServer> getMcpServers() {
        return base.getMcpServers();
    }

    @Override
    public java.util.List<com.pijava.coding.agent.core.slash.SlashCommandInfo>
            getCommands() {
        return base.getCommands();
    }
}
