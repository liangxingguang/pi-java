package com.pijava.coding.agent.extension.mcp;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.jspecify.annotations.Nullable;

import com.pijava.agent.session.memory.MemorySessionRepository;
import com.pijava.agent.skill.SkillManager;
import com.pijava.agent.tool.ToolRegistry;
import com.pijava.ai.model.DefaultModelResolver;
import com.pijava.ai.provider.builtin.ProviderCatalog;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.coding.agent.core.ProjectTrustStore;
import com.pijava.coding.agent.core.SessionServices;
import com.pijava.coding.agent.core.SettingsManager;
import com.pijava.coding.agent.core.slash.CommandRegistry;
import com.pijava.coding.agent.extension.DefaultExtensionContext;
import com.pijava.coding.agent.extension.ExtensionCustomOptions;
import com.pijava.coding.agent.extension.ExtensionComponent;
import com.pijava.coding.agent.extension.ExtensionComponentFactory;
import com.pijava.coding.agent.extension.ExtensionEventBus;
import com.pijava.coding.agent.extension.ExtensionUI;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.SessionEventFirer;
import com.pijava.coding.agent.prompt.PromptTemplateRegistry;

/**
 * 批 24（docs/35 §6）：MCP 扩展的测试装备——真总线＋firer＋录制 UI＋
 * 内存/临时目录 settings；扩展直注册（不走 ServiceLoader），事件经 firer 发射。
 */
final class McpExtensionTestRig {

    /** 录制 UI（notify/select/input 落表；select/input 的答案可编排）。 */
    static final class RecordingUi implements ExtensionUI {
        final List<String[]> notifications = new CopyOnWriteArrayList<>();
        final List<String> selects = new CopyOnWriteArrayList<>();
        final List<String[]> inputs = new CopyOnWriteArrayList<>();
        /** select 的编排答案（按调用序消费；空 ⇒ null）。 */
        final List<String> selectAnswers = new CopyOnWriteArrayList<>();
        String inputAnswer;

        @Override
        public void notify(String message, @Nullable NotifyType type) {
            notifications.add(new String[] {message, type == null ? "info"
                : type.name().toLowerCase(java.util.Locale.ROOT)});
        }

        @Override
        public @Nullable String select(String title, List<String> options,
                                       com.pijava.coding.agent.extension.@Nullable ExtensionUiDialogOptions opts) {
            selects.add(title + "|" + String.join(",", options));
            return selectAnswers.isEmpty() ? null : selectAnswers.remove(0);
        }

        @Override
        public @Nullable String input(String title, @Nullable String placeholder,
                                      com.pijava.coding.agent.extension.@Nullable ExtensionUiDialogOptions opts) {
            inputs.add(new String[] {title, placeholder});
            return inputAnswer;
        }

        @Override
        public <T> @Nullable T custom(ExtensionComponentFactory<T> factory,
                                      @Nullable ExtensionCustomOptions options) {
            throw new UnsupportedOperationException("recording UI has no custom host");
        }

        boolean hasNotification(String fragment, String type) {
            return notifications.stream().anyMatch(
                n -> n[0].contains(fragment) && n[1].equals(type));
        }
    }

    final ExtensionEventBus bus = new ExtensionEventBus();
    final RecordingUi ui = new RecordingUi();
    /** 扩展错误（总线错误通道）——测试排障用。 */
    final List<String> extensionErrors = new CopyOnWriteArrayList<>();
    final ToolRegistry tools = new ToolRegistry(null);
    final CommandRegistry commands = new CommandRegistry();
    final SettingsManager settings;
    final DefaultExtensionContext base;
    final SessionEventFirer firer;
    final McpExtension ext;

    private McpExtensionTestRig(Path agentDir, Path cwd, boolean trusted,
                                @Nullable ScriptedMcpServer server, long startupWaitMs) {
        settings = SettingsManager.load(agentDir, cwd, trusted);
        var services = new SessionServices(
            settings,
            ProjectTrustStore.forSettings(settings),
            ProviderRegistry.create(),
            new DefaultModelResolver(ProviderCatalog.allModels()),
            tools, commands, new MemorySessionRepository(), new PromptTemplateRegistry());
        base = new DefaultExtensionContext(services, new SkillManager(), ui, bus);
        bus.onError(error -> extensionErrors.add(String.valueOf(error)));
        firer = new SessionEventFirer(bus, base);
        ext = new McpExtension(new McpExtension.Overrides(
            server != null ? server.factory() : null, agentDir, startupWaitMs));
        ext.register(base.forExtension("mcp", "builtin:mcp"));
        // 装配尾序对位：changeListener 在扩展装载后接（AgentSession:3010-3014）
        base.mcpServers().setChangeListener(() ->
            firer.mcpServersChange(base.mcpServers().list()));
    }

    static McpExtensionTestRig create(Path agentDir, Path cwd, boolean trusted,
                                      @Nullable ScriptedMcpServer server) {
        return new McpExtensionTestRig(agentDir, cwd, trusted, server, 10_000L);
    }

    static McpExtensionTestRig create(Path agentDir, Path cwd, boolean trusted,
                                      @Nullable ScriptedMcpServer server, long startupWaitMs) {
        return new McpExtensionTestRig(agentDir, cwd, trusted, server, startupWaitMs);
    }

    void startSession() {
        firer.sessionStart("startup", null);
    }

    void shutdownSession() {
        firer.sessionShutdown("quit", null);
    }

    /** before_agent_start 发射＋终态 sections（批 16 T3c 的 firer 链）。 */
    java.util.Map<String, String> fireBeforeAgentStart() {
        var outcome = firer.beforeAgentStart("hello", () -> "sys", java.util.Map.of());
        return outcome.sections();
    }

    /** tool_call 直发（ EngineHookBridge 的净效果）。 */
    void fireToolCall(String toolName) throws Exception {
        for (var handler : bus.snapshot(
                com.pijava.coding.agent.extension.event.ExtensionEventSpec.TOOL_CALL)) {
            handler.handle(new com.pijava.coding.agent.extension.event.ExtensionEvent.ToolCallEvent(
                "call-1", toolName, java.util.Map.of()), base);
        }
    }

    void fireTurnStart() throws Exception {
        for (var handler : bus.snapshot(
                com.pijava.coding.agent.extension.event.ExtensionEventSpec.TURN_START)) {
            handler.handle(new com.pijava.coding.agent.extension.event.ExtensionEvent.TurnStartEvent(
                1, System.currentTimeMillis()), base);
        }
    }
}
