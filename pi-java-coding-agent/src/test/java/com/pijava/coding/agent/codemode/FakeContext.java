package com.pijava.coding.agent.codemode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.harness.PiLoop;
import com.pijava.agent.skill.SkillManager;
import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ToolRegistry;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.coding.agent.core.SessionServices;
import com.pijava.coding.agent.core.SettingsManager;
import com.pijava.coding.agent.core.slash.CommandRegistry;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.ExtensionShortcut;
import com.pijava.coding.agent.extension.ExtensionSessionView;
import com.pijava.coding.agent.extension.ExtensionToolInfo;
import com.pijava.coding.agent.extension.ToolCallRenderer;
import com.pijava.coding.agent.extension.ToolRendererResolver;

/** Minimal fake {@link ExtensionContext} for codemode tests. */
final class FakeContext implements ExtensionContext {
    private final SettingsManager manager;
    private final List<AgentTool<?, ?>> callable = new ArrayList<>();
    private final Map<String, PiLoop.ToolOutcome> outcomes = new LinkedHashMap<>();
    private final List<Map<String, Object>> appended = new ArrayList<>();
    private final List<Entry> branch = new ArrayList<>();
    private final List<ToolRendererResolver> renderers = new ArrayList<>();
    private int nestedCount;

    FakeContext() {
        this(SettingsManager.load(null));
    }

    FakeContext(SettingsManager manager) {
        this.manager = manager;
    }

    FakeContext callable(AgentTool<?, ?> tool) {
        callable.add(tool);
        return this;
    }

    FakeContext outcome(String name, PiLoop.ToolOutcome outcome) {
        outcomes.put(name, outcome);
        return this;
    }

    List<Map<String, Object>> appended() {
        return appended;
    }

    List<ToolRendererResolver> renderers() {
        return renderers;
    }

    @Override public ToolRegistry tools() {
        return new ToolRegistry(null);
    }

    @Override public CommandRegistry slashCommands() {
        return CommandRegistry.withBuiltins();
    }

    @Override public ProviderRegistry providers() {
        return ProviderRegistry.create();
    }

    @Override public SkillManager skills() {
        return new SkillManager();
    }

    @Override public SettingsManager settings() {
        return manager;
    }

    @Override public SessionServices services() {
        return null;
    }

    @Override public <E extends com.pijava.coding.agent.extension.event.ExtensionEvent, R> AutoCloseable on(
            com.pijava.coding.agent.extension.event.ExtensionEventSpec<E, R> spec,
            com.pijava.coding.agent.extension.event.ExtensionEventHandler<E, R> handler) {
        return () -> { };
    }

    @Override public void registerFlag(String name,
                                       com.pijava.coding.agent.extension.ExtensionFlagOptions options) {
    }

    @Override public Object getFlag(String name) {
        return null;
    }

    @Override public List<String> getActiveTools() {
        return List.of();
    }

    @Override public List<ExtensionToolInfo> getAllTools() {
        return List.of();
    }

    @Override public void setActiveTools(List<String> toolNames) {
    }

    @Override public void registerToolRenderer(ToolRendererResolver resolver) {
        renderers.add(resolver);
    }

    @Override public String sendMessage(String customType, com.pijava.agent.entry.CustomMessageContent content,
                                       boolean display, Map<String, Object> details,
                                       ExtensionContext.SendMessageOptions options) {
        return null;
    }

    @Override public void sendUserMessage(com.pijava.agent.entry.CustomMessageContent content,
                                          SendUserMessageOptions options) {
    }

    @Override public void registerMcpServer(String name, com.pijava.mcp.config.McpServerConfig config) {
    }

    @Override public void unregisterMcpServer(String name) {
    }

    @Override public List<com.pijava.mcp.config.RegisteredMcpServer> getMcpServers() {
        return List.of();
    }

    @Override public void appendEntry(String customType, Map<String, Object> data) {
        Map<String, Object> entry = new LinkedHashMap<>(data);
        entry.put("__customType", customType);
        appended.add(entry);
    }

    @Override public void setSessionName(String name) {
    }

    @Override public String getSessionName() {
        return null;
    }

    @Override public void setLabel(String entryId, String label) {
    }

    @Override public boolean setModel(com.pijava.ai.model.ModelId<?> model) {
        return false;
    }

    @Override public com.pijava.ai.thinking.ModelThinkingLevel getThinkingLevel() {
        return null;
    }

    @Override public void setThinkingLevel(com.pijava.ai.thinking.ModelThinkingLevel level) {
    }

    @Override public List<com.pijava.coding.agent.core.slash.SlashCommandInfo> getCommands() {
        return List.of();
    }

    @Override public com.pijava.coding.agent.extension.ExtensionExec.ExecResult exec(String command,
            List<String> args, com.pijava.coding.agent.extension.ExtensionExec.ExecOptions options) {
        return null;
    }

    @Override public void unregisterProvider(String name) {
    }

    @Override public com.pijava.coding.agent.extension.ExtensionChannelBus events() {
        return null;
    }

    @Override public void registerShortcut(String keyId, String description,
                                           ExtensionShortcut.ShortcutHandler handler) {
    }

    @Override public void registerMessageRenderer(String customType,
                                                  com.pijava.coding.agent.extension.ExtensionMessageRenderer renderer) {
    }

    @Override public void registerEntryRenderer(String customType,
                                                com.pijava.coding.agent.extension.ExtensionEntryRenderer renderer) {
    }

    @Override public void registerMarkdownTransformer(
            com.pijava.coding.agent.extension.MarkdownTransformer transformer) {
    }

    @Override public void registerVirtualModel(com.pijava.coding.agent.extension.ExtensionVirtualModel model) {
    }

    @Override public void unregisterVirtualModel(String provider, String id) {
    }

    @Override public List<AgentTool<?, ?>> callableTools() {
        return callable;
    }

    @Override public ExtensionSessionView sessionManager() {
        return new ExtensionSessionView() {
            @Override public List<Entry> getEntries() { return branch; }
            @Override public List<Entry> getBranch() { return branch; }
            @Override public Entry getEntry(String id) { return null; }
            @Override public String getLabel(String targetId) { return null; }
            @Override public Entry getLeafEntry() { return null; }
            @Override public String getSessionFile() { return null; }
            @Override public String getCwd() { return ""; }
            @Override public String getSessionId() { return ""; }
            @Override public String getLeafId() { return null; }
            @Override public List<com.pijava.coding.agent.core.SessionTreeNode> getTree() { return List.of(); }
        };
    }

    @Override public PiLoop.ToolOutcome executeTool(String callerToolCallId, AbortSignal signal, String name,
                                                    Map<String, Object> args,
                                                    java.util.function.Consumer<Object> onUpdate) {
        nestedCount++;
        PiLoop.ToolOutcome outcome = outcomes.get(name);
        if (outcome != null) {
            return outcome;
        }
        var result = new com.pijava.agent.tool.ToolResult<>(
            List.of(new ContentBlock.TextContent("ok")), null, null, false, List.of());
        Message.ToolResultMessage message = new Message.ToolResultMessage(
            callerToolCallId + "/" + nestedCount, name, result.content(), result.isError());
        return new PiLoop.ToolOutcome(message, result, result.isError());
    }

    ToolRendererRenderer resolverRenderer(ToolRendererResolver resolver) {
        return new ToolRendererRenderer(resolver);
    }

    /** Wraps a resolver's renderer for direct assertions. */
    static final class ToolRendererRenderer {
        private final ToolCallRenderer delegate;

        ToolRendererRenderer(ToolRendererResolver resolver) {
            delegate = resolver.resolve("codemode", () -> null);
        }

        List<String> call(Map<String, Object> args, boolean expanded) {
            return delegate.renderCall("codemode", args, expanded);
        }

        List<String> result(String text, boolean expanded) {
            return delegate.renderResult("codemode", text, false, expanded);
        }
    }
}
