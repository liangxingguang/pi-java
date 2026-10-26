package com.pijava.coding.agent.extension.mcp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.extension.DefaultExtensionContext;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEvent.BeforeAgentStartEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.McpServersChangeEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.SessionShutdownEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.SessionStartEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.ToolCallEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.TurnStartEvent;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;
import com.pijava.mcp.config.McpConfig;
import com.pijava.mcp.config.McpScope;
import com.pijava.mcp.config.McpServerConfigs;
import com.pijava.mcp.runtime.ConfigValueResolver;

/**
 * 批 24（docs/35）：内置 MCP 扩展本体（pi {@code createMcpExtension()} 默认导出对位，
 * {@code extensions/mcp/index.ts}）——{@code builtin:mcp} 身份经 ServiceLoader 自动
 * 发现（ExtensionManager 既有合成），{@code --no-mcp} 按名摘除（pi
 * {@code disabledBuiltinExtensions} 对位）。
 *
 * <p>六个 {@code on()} 事件的订阅者全在此（map/07 G 组）：session_start 读两源配置
 * ＋注册表并后台连接；before_agent_start 等 direct 服务器（有界 10s）＋写
 * {@code mcp_servers} 提示段；tool_call 就绪等待；turn_start 拾取进程外登录；
 * mcp_servers_change 热插拔；session_shutdown 收敛。</p>
 *
 * <p>R5 面外（不做）：codemode/tool_search 两臂（tool_call 等待的
 * scriptNeedsServer 臂、ensureDiscoveryActive 本体——#93 权重 0；不可达一次性
 * 警告由 {@link McpLifecycle#warnUnreachableOnce} 替身，裁决⑥）。</p>
 */
public final class McpExtension implements PiExtension {

    /** 内置名（pi {@code extensions/index.ts} 的 {@code name: "mcp"}；--no-mcp 过滤键）。 */
    public static final String NAME = "mcp";

    /** 资源三工具名集（pi {@code RESOURCE_TOOL_NAMES}，index.ts:99-103）。 */
    static final java.util.Set<String> RESOURCE_TOOL_NAMES = java.util.Set.of(
        McpResourceTools.LIST_MCP_RESOURCES_TOOL,
        McpResourceTools.LIST_MCP_RESOURCE_TEMPLATES_TOOL,
        McpResourceTools.READ_MCP_RESOURCE_TOOL);

    private final ObjectMapper json = new ObjectMapper();
    private final McpExtensionState state = new McpExtensionState();
    private final Executor executor = McpLifecycle.virtualThreads();

    /** 会话作用域（pi 的 session AbortController；初始 abort 态逐字）。 */
    private volatile McpSessionScope scope = McpSessionScope.abortedScope();

    private ExtensionContext ctx;       // register 期捕获的 per-extension 上下文
    private McpLifecycle lifecycle;
    private McpToolRegistrar registrar;
    private McpManagerActions actions;
    /** 测试覆写（pi {@code McpExtensionOptions} 的窄化：传输工厂/agentDir/启动等待）。 */
    private final @Nullable Overrides overrides;

    /** ServiceLoader 入口（无参）。 */
    public McpExtension() {
        this(null);
    }

    McpExtension(@Nullable Overrides overrides) {
        this.overrides = overrides;
    }

    /** 测试覆写件（同包）。 */
    record Overrides(
            com.pijava.mcp.runtime.@Nullable McpTransportFactory transportFactory,
            @Nullable Path agentDir, long startupWaitMs) {
    }

    com.pijava.mcp.runtime.@Nullable McpTransportFactory transportFactory() {
        return overrides != null ? overrides.transportFactory() : null;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Built-in MCP support: connects the servers from mcp.json and"
            + " extension-registered servers into the session.";
    }

    @Override
    public void register(ExtensionContext context) {
        this.ctx = context;
        this.lifecycle = new McpLifecycle(state, this, executor,
            new ConfigValueResolver(ShellPathCommandRunner.twoTier(
                context.services().settings().accessors())));
        this.registrar = new McpToolRegistrar(state, context);
        this.actions = new McpManagerActions(state, context, lifecycle, registrar, executor,
            this::scope);
        try {
            context.on(ExtensionEventSpec.SESSION_START, this::onSessionStart);
            context.on(ExtensionEventSpec.BEFORE_AGENT_START, this::onBeforeAgentStart);
            context.on(ExtensionEventSpec.TOOL_CALL, this::onToolCall);
            context.on(ExtensionEventSpec.TURN_START, this::onTurnStart);
            context.on(ExtensionEventSpec.MCP_SERVERS_CHANGE, this::onMcpServersChange);
            context.on(ExtensionEventSpec.SESSION_SHUTDOWN, this::onSessionShutdown);
        } catch (IllegalStateException noEventBus) {
            // 无事件总线的退化宿主（--help 轻量道/无 bus 的旧测试夹具）——
            // 订阅面不可用 ⇒ 只注册命令面（pi 的 assertActive 同形守卫，不升级为装配失败）
        }
        context.slashCommands().register(new McpSlashCommand(actions));
    }

    // ── 内部访问（同包） ──

    McpSessionScope scope() {
        return scope;
    }

    /** 测试缝（同包）：会话状态（pending 等待等断言用）。 */
    McpExtensionState state() {
        return state;
    }

    /** 测试缝（同包）：/mcp 动作面（脚本化 McpManagerUi 直驱管理循环）。 */
    McpManagerActions actions() {
        return actions;
    }

    McpToolRegistrar registrar() {
        return registrar;
    }

    ObjectMapper json() {
        return json;
    }

    /** pi 处理器参数 {@code ctx} 的宿主会话（fires 传基础上下文；晚绑 UI 两路同效）。 */
    static @Nullable AgentSession hostSession(ExtensionContext fired) {
        return fired instanceof DefaultExtensionContext base ? base.hostSessionOrNull() : null;
    }

    // ── session_start（pi index.ts:1111-1151 逐字） ──

    private Void onSessionStart(SessionStartEvent event, ExtensionContext fired) {
        var host = hostSession(fired);
        if (host != null) {
            state.sessionCwd = Path.of(host.cwd());
        } else if (fired.services().settings().storage()
                instanceof com.pijava.coding.agent.core.FileSettingsStorage files) {
            // 无宿主会话的装配（测试直驱等）：与 settings 存储面同源（批 23 同根纪律）
            // ——绝不能落 process.cwd()（会误读调用方工作区，测试污染源仓的教训）
            state.sessionCwd = files.projectDir();
        }
        // agentDir 从 settings 存储面解析（批 23 forSettings 同手法；测试覆写优先）
        if (overrides != null && overrides.agentDir() != null) {
            state.agentDir = overrides.agentDir();
        } else if (fired.services().settings().storage()
                instanceof com.pijava.coding.agent.core.FileSettingsStorage files
            && files.globalPath() != null && files.globalPath().getParent() != null) {
            state.agentDir = files.globalPath().getParent();
        } else {
            state.agentDir =
                com.pijava.coding.agent.core.FileSettingsStorage.defaultAgentDir();
        }
        if (overrides != null) {
            state.startupWaitMs = overrides.startupWaitMs();
        }
        var loaded = McpConfig.load(state.agentDir, state.sessionCwd,
            fired.services().settings().isProjectTrusted());
        state.configErrors = loaded.errors();
        state.projectConfig = loaded.projectConfig();
        state.warnedUnreachable = false;
        state.waitedForStartup = false;
        scope = McpSessionScope.active();
        var currentScope = scope;
        state.configuredEntries = loaded.servers();
        var registered = registeredServers();
        state.overridden = registered.overridden;
        state.servers.clear();
        for (var entry : loaded.servers()) {
            state.servers.add(new McpServerHandle(entry));
        }
        state.servers.addAll(registered.servers);
        state.emitChange();
        // 裁决⑥：pi ensureDiscoveryActive 的 java 替身（codemode/tool_search R5 不做）
        lifecycle.warnUnreachableOnce(fired);
        var enabled = state.servers.stream().filter(McpExtensionState::isEnabled).toList();
        if (enabled.isEmpty()) {
            lifecycle.reportProblems(fired);
            return null;
        }
        // 裁决⑦：java 无模块加载开销，不模拟 setImmediate——仅异步推迟首连
        var tick = CompletableFuture.runAsync(() -> { }, executor);
        var futures = enabled.stream()
            .map(server -> lifecycle.startConnection(server, tick))
            .toArray(CompletableFuture[]::new);
        state.pending = CompletableFuture.allOf(futures)
            .thenRun(() -> {
                if (!currentScope.isAborted()) {
                    lifecycle.reportProblems(fired);
                }
            })
            .exceptionally(error -> {
                try {
                    fired.ui().notify("MCP failed to load: " + errorMessage(error),
                        NotifyType.ERROR);
                } catch (RuntimeException stale) {
                    // 会话已 dispose，ctx 陈旧（pi try/catch {} 对位）
                }
                return null;
            });
        return null;
    }

    /**
     * 扩展注册的服务器（pi {@code registeredServers} :352-373 逐字）——
     * mcp.json 同名（命名空间归一后）压注册表。
     */
    private RegisteredServers registeredServers() {
        var servers = new ArrayList<McpServerHandle>();
        var overridden = new ArrayList<String>();
        for (var registered : ctx.getMcpServers()) {
            var configured = state.configuredEntries.stream()
                .filter(entry -> McpServerConfigs.namespace(entry.name())
                    .equals(McpServerConfigs.namespace(registered.name())))
                .findFirst();
            if (configured.isPresent()) {
                overridden.add("\"" + registered.name() + "\" registered by "
                    + registered.extensionPath() + " is overridden by \""
                    + configured.get().name() + "\" in " + configured.get().source());
                continue;
            }
            var handle = new McpServerHandle(McpExtensionState.extensionEntry(
                registered.name(), registered.config(), registered.extensionPath()));
            handle.extensionPath = registered.extensionPath();
            try {
                handle.registeredConfig = json.writeValueAsString(registered.config());
            } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
                handle.registeredConfig = String.valueOf(registered.config());
            }
            servers.add(handle);
        }
        return new RegisteredServers(servers, overridden);
    }

    private record RegisteredServers(List<McpServerHandle> servers, List<String> overridden) {
    }

    // ── before_agent_start（pi :1175-1185 逐字；sections 经共享可变图原地改写） ──

    private com.pijava.coding.agent.extension.event.ExtensionResults.BeforeAgentStartEventResult
            onBeforeAgentStart(BeforeAgentStartEvent event, ExtensionContext fired) {
        lifecycle.waitForDirectServers(fired);
        var listings = new ArrayList<McpServerListing>(state.servers.size());
        for (var server : state.servers) {
            listings.add(new McpServerListing(server.entry,
                server.connection != null ? server.connection.instructions() : null));
        }
        var section = McpServersSection.render(listings);
        var sections = event.sections();
        if (section != null) {
            sections.put(McpServersSection.SECTION, section);
        } else {
            sections.remove(McpServersSection.SECTION);
        }
        return null;
    }

    // ── tool_call（pi :1187-1207；codemode/tool_search 两臂 R5 不移植） ──

    private com.pijava.coding.agent.extension.event.ExtensionResults.ToolCallEventResult
            onToolCall(ToolCallEvent event, ExtensionContext fired) {
        // pi 用 pi.getAllTools().find(name)（注册表存在性）——java 直查注册表
        //（同效；getAllTools 的会话缝在 --help 轻量道等无宿主路径会抛）
        if (ctx.tools().get(event.toolName()) == null) {
            return null;
        }
        var readyServers = state.servers.stream()
            .filter(server -> McpExtensionState.isEnabled(server) && server.ready != null)
            .toList();
        if (readyServers.isEmpty()) {
            return null;
        }
        List<McpServerHandle> waiting;
        if (RESOURCE_TOOL_NAMES.contains(event.toolName())) {
            waiting = readyServers;
        } else {
            var owner = registrar.ownerServerOf(event.toolName());
            waiting = readyServers.stream()
                .filter(server -> server.entry.name().equals(owner))
                .toList();
        }
        lifecycle.waitForServers(waiting);
        return null;
    }

    // ── turn_start（pi :1209-1212 逐字） ──

    private Void onTurnStart(TurnStartEvent event, ExtensionContext fired) {
        if (lifecycle.hasPendingSignIns()) {
            lifecycle.reconnectSignedIn(fired);
        }
        return null;
    }

    // ── mcp_servers_change（pi :1214-1247 逐字） ──

    private Void onMcpServersChange(McpServersChangeEvent event, ExtensionContext fired) {
        var currentScope = scope;
        if (currentScope.isAborted()) {
            return null;
        }
        var registered = registeredServers();
        state.overridden = registered.overridden;
        var next = new LinkedHashMap<String, McpServerHandle>();
        for (var server : registered.servers) {
            next.put(server.entry.name(), server);
        }
        // 注销的与换配置再注册的先拆（后者下面以新配置回来）
        var removed = state.servers.stream()
            .filter(server -> server.entry.scope() == McpScope.EXTENSION)
            .filter(server -> {
                var incoming = next.get(server.entry.name());
                return incoming == null
                    || !java.util.Objects.equals(incoming.registeredConfig,
                        server.registeredConfig);
            })
            .toList();
        state.servers.removeAll(removed);
        for (var server : removed) {
            registrar.hideTools(server.entry.name());
        }
        var added = registered.servers.stream()
            .filter(server -> state.findServer(server.entry.name()) == null)
            .toList();
        state.servers.addAll(added);
        state.emitChange();
        lifecycle.warnUnreachableOnce(fired);
        for (var server : removed) {
            if (server.connection != null) {
                server.connection.close().join();
            }
        }
        var connecting = added.stream().filter(McpExtensionState::isEnabled).toList();
        if (currentScope.isAborted() || connecting.isEmpty()) {
            return null;
        }
        CompletableFuture.allOf(connecting.stream()
            .map(server -> lifecycle.startConnection(server, null))
            .toArray(CompletableFuture[]::new)).join();
        if (currentScope.isAborted()) {
            for (var server : connecting) {
                if (server.connection != null) {
                    server.connection.close().join();
                }
            }
            return null;
        }
        lifecycle.reportProblems(fired, connecting);
        return null;
    }

    // ── session_shutdown（pi :1249-1255 逐字） ──

    private Void onSessionShutdown(SessionShutdownEvent event, ExtensionContext fired) {
        var currentScope = scope;
        currentScope.abort();
        var closing = state.connections();
        state.servers.clear();
        state.emitChange();
        for (var connection : closing) {
            try {
                connection.close().join();
            } catch (RuntimeException ignored) {
                // 关闭失败不挡其余（pi Promise.all 的并发对位）
            }
        }
        currentScope.abortAndAwait();
        return null;
    }

    static String errorMessage(Throwable error) {
        var cause = error instanceof java.util.concurrent.CompletionException
            && error.getCause() != null ? error.getCause() : error;
        return cause.getMessage() != null ? cause.getMessage() : String.valueOf(cause);
    }
}
