package com.pijava.coding.agent.extension.mcp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.mcp.config.McpExposure;
import com.pijava.mcp.config.McpServerConfigs;
import com.pijava.mcp.runtime.McpResourceServer;
import com.pijava.mcp.runtime.McpServerConnection;
import com.pijava.mcp.runtime.McpServerState;

/**
 * 批 24（docs/35 T6）：MCP 工具的运行时注册（pi {@code index.ts:376-491} 的
 * toolOwners/serverTools/definitions 三表＋registerTools/hideTools/
 * syncResourceTools 逐字对位）。
 *
 * <p>exposure 表达（裁决③）：{@link com.pijava.agent.tool.AgentTool} 无
 * {@code exposure} 字段（窄差 D89/D91 维持）——本类自持 exposure 表；
 * declarable 经激活门表达：direct ⇒ 注册即激活；codemode/deferred ⇒ 注册不激活；
 * hidden/撤销 ⇒ 重注＋移出激活集（pi 的「工具不能注销，重注 hidden」对位——
 * java {@code ToolRegistry.register} 同名替换）。</p>
 */
final class McpToolRegistrar {

    private final McpExtensionState state;
    private final ExtensionContext ctx;

    /** 工具名 → {@code <server>\0<tool>}（pi {@code toolOwners}——名归一与稳定）。 */
    private final Map<String, String> toolOwners = new HashMap<>();
    /** 服务器 → 当前提供的工具名集。 */
    private final Map<String, Set<String>> serverTools = new HashMap<>();
    /** 工具名 → 最近注册的定义（撤销重注 hidden 用）。 */
    private final Map<String, McpToolDefinition> definitions = new HashMap<>();
    /** 工具名 → 当前 exposure（裁决③外挂表；资源三工具同驻）。 */
    private final Map<String, McpExposure> exposures = new HashMap<>();

    /**
     * 批 27（B162 裁决④）：把 exposure/namespace 镜像进会话级定义表
     * （pi {@code _toolDefinitions} 单一事实源的对位——java 侧本表管 MCP 内部
     * 簿记，共享表管四门与 tool_search 候选）。
     */
    private void mirrorDefinition(String name, McpExposure exposure, String namespace,
                                  String description, String instructions) {
        try {
            ctx.toolDefinitions().register(name,
                new com.pijava.coding.agent.core.ToolDefinitions.Entry(
                    // 批 24 映射保留：MCP codemode 在工具层记 DEFERRED（java 无
                    // codemode 引擎；tool_search 对两档同等可搜，pi isSearchable 同）。
                    com.pijava.coding.agent.extension.ToolExposure.valueOf(
                        McpToolExposure.of(exposure).name()), true,
                    namespace == null ? null
                        : new com.pijava.coding.agent.core.ToolDefinitions.Namespace(
                            namespace, description, instructions)));
        } catch (UnsupportedOperationException e) {
            // 无会话的上下文（flag 绑定等）——表不可得则跳过（四门走默认臂）。
        }
    }
    /** 资源工具最近一次注册的 exposure；null = 尚无服务器有资源。 */
    private @Nullable McpExposure resourceToolsExposure;

    McpToolRegistrar(McpExtensionState state, ExtensionContext ctx) {
        this.state = state;
        this.ctx = ctx;
    }

    // ── registerTools（pi :388-449 逐字） ──

    void registerTools(McpServerConnection connection) {
        var server = connection.name();
        var found = state.findServer(server);
        var entry = found != null ? found.entry : connection.entry();
        var description = entry.config().description();
        var namespace = McpServerConfigs.namespace(server);
        var previous = serverTools.getOrDefault(server, Set.of());
        var current = new LinkedHashSet<String>();
        // Codex 序无关：名归一撞名的全部加哈希后缀
        var plain = connection.tools().stream().map(com.pijava.mcp.protocol.McpTool::name)
            .distinct().map(tool -> McpToolNames.create(server, tool)).toList();
        var toActivate = new ArrayList<String>();
        for (var tool : connection.tools()) {
            var owner = server + '\0' + tool.name();
            var name = McpToolNames.create(server, tool.name(), candidate -> {
                var existing = toolOwners.get(candidate);
                return (existing != null && !existing.equals(owner))
                    || current.contains(candidate)
                    || plain.indexOf(candidate) != plain.lastIndexOf(candidate);
            });
            toolOwners.put(name, owner);
            current.add(name);
            var exposure = McpServerConfigs.getMcpToolExposure(entry.config(), tool.name());
            var definition = McpToolDefinition.create(new McpToolDefinition.Options(
                server, tool, name, exposure, namespace, connection.timeoutMs(),
                callerSource(server, tool.name()),
                () -> resourceServers().stream().anyMatch(c -> c.name().equals(server))));
            definitions.put(name, definition);
            exposures.put(name, exposure);
            mirrorDefinition(name, exposure, namespace, description,
                connection.instructions());
            ctx.tools().register(definition.agent());
            if (exposure == McpExposure.DIRECT) {
                toActivate.add(name);
            }
        }
        serverTools.put(server, current);
        // 撤销的工具重注为 hidden（工具不能注销）；再提供时上面按配置 exposure 重注
        var withdrawn = new ArrayList<String>();
        for (var name : previous) {
            var definition = definitions.get(name);
            if (!current.contains(name) && definition != null) {
                ctx.tools().register(definition.agent());
                exposures.put(name, McpExposure.HIDDEN);
                mirrorDefinition(name, McpExposure.HIDDEN, null, null, null);
                withdrawn.add(name);
            }
        }
        applyActivation(toActivate, withdrawn);
        syncResourceTools();
    }

    /** 让 disabled 服务器的工具不可达（pi {@code hideTools} :452-460 逐字）。 */
    void hideTools(String server) {
        var names = serverTools.getOrDefault(server, Set.of());
        var withdrawn = new ArrayList<String>();
        for (var name : names) {
            var definition = definitions.get(name);
            if (definition != null) {
                ctx.tools().register(definition.agent());
                exposures.put(name, McpExposure.HIDDEN);
                mirrorDefinition(name, McpExposure.HIDDEN, null, null, null);
                withdrawn.add(name);
            }
        }
        serverTools.put(server, Set.of());
        applyActivation(List.of(), withdrawn);
        syncResourceTools();
    }

    /**
     * 激活门（裁决③＋pi {@code _isActivatedOnRegistration} 对位）：direct 新工具
     * 经注册面门（{@code --tools mcp__} 保留臂）激活；再消费 pending 晚注册臂
     * （docs/35 §2.7）；撤销/hidden 移出激活集（pi refreshTools 重建的对位，
     * <b>不</b>清 pending——pi 的 pending 清理只在 {@code setActiveToolsByName}）。
     */
    private void applyActivation(List<String> toActivate, List<String> toDeactivate) {
        var session = hostSession();
        if (session == null) {
            return;
        }
        session.activateRuntimeTools(toActivate);
        if (!toDeactivate.isEmpty()) {
            session.deactivateRuntimeTools(toDeactivate);
        }
    }

    private @Nullable AgentSession hostSession() {
        return ctx instanceof com.pijava.coding.agent.extension.DefaultExtensionContext base
            ? base.hostSessionOrNull() : null;
    }

    // ── 资源工具同步（pi :466-491 逐字） ──

    /** enabled ∧ 有资源 ∧ exposure≠hidden 的服务器（资源工具可达面）。 */
    private List<McpServerHandle> serversWithResources() {
        var result = new ArrayList<McpServerHandle>();
        for (var server : state.servers) {
            var connection = server.connection;
            if (connection != null && connection.hasResources()
                && McpExtensionState.isEnabled(server)
                && McpExtensionState.exposureOf(server.entry) != McpExposure.HIDDEN) {
                result.add(server);
            }
        }
        return result;
    }

    private List<McpResourceServer> resourceServers() {
        var result = new ArrayList<McpResourceServer>();
        for (var server : serversWithResources()) {
            if (server.connection != null) {
                result.add(server.connection);
            }
        }
        return result;
    }

    void syncResourceTools() {
        var seen = new LinkedHashSet<McpExposure>();
        for (var server : serversWithResources()) {
            seen.add(McpExtensionState.exposureOf(server.entry));
        }
        McpExposure next = null;
        for (var candidate : List.of(McpExposure.DIRECT, McpExposure.CODEMODE,
                McpExposure.DEFERRED)) {
            if (seen.contains(candidate)) {
                next = candidate;
                break;
            }
        }
        if (next == null) {
            next = McpExposure.HIDDEN;
        }
        if (next == resourceToolsExposure
            || (resourceToolsExposure == null && next == McpExposure.HIDDEN)) {
            return;
        }
        var wasDirect = resourceToolsExposure == McpExposure.DIRECT;
        resourceToolsExposure = next;
        var resourceDefinitions = McpResourceTools.create(
            new McpResourceTools.Options(next, this::resourceServers));
        var names = new ArrayList<String>();
        for (var definition : resourceDefinitions) {
            definitions.put(definition.name(), definition);
            exposures.put(definition.name(), next);
            mirrorDefinition(definition.name(), next, null, null, null);
            ctx.tools().register(definition.agent());
            names.add(definition.name());
        }
        // direct 撤离激活集；direct 进入经激活门（pi setExposure 尾段/:490-491 对位）
        if (wasDirect) {
            var session = hostSession();
            if (session != null) {
                session.deactivateRuntimeTools(names);
            }
        }
        if (next == McpExposure.DIRECT) {
            applyActivation(names, List.of());
        }
    }

    /** {@code setExposure} 尾段（pi :779-787）：该服务器间接工具移出激活集。 */
    void deactivateIndirectTools(String server) {
        var names = serverTools.getOrDefault(server, Set.of());
        var indirect = new ArrayList<String>();
        for (var name : names) {
            if (exposures.get(name) != McpExposure.DIRECT) {
                indirect.add(name);
            }
        }
        if (!indirect.isEmpty()) {
            var session = hostSession();
            if (session != null) {
                session.deactivateRuntimeTools(indirect);
            }
        }
    }

    /** pi 工具名 → 属主服务器（tool_call 就绪等待用，:1203）。 */
    @Nullable String ownerServerOf(String toolName) {
        var owner = toolOwners.get(toolName);
        return owner == null ? null : owner.split("\0", 2)[0];
    }

    // ── getClient 晚解析（pi :428-444 三错文逐字） ──

    private McpToolDefinition.CallerSource callerSource(String server, String toolName) {
        return () -> {
            var current = state.findServer(server);
            if (current == null || !McpExtensionState.isEnabled(current)) {
                throw new IllegalStateException(
                    "MCP server \"" + server + "\" is disabled.");
            }
            var client = current.connection;
            if (client == null) {
                throw new IllegalStateException(
                    "MCP server \"" + server + "\" is still starting.");
            }
            if (McpServerConfigs.getMcpToolExposure(current.entry.config(), toolName)
                    == McpExposure.HIDDEN
                || (client.state() == McpServerState.CONNECTED
                    && client.tools().stream().noneMatch(t -> t.name().equals(toolName)))) {
                throw new IllegalStateException(
                    "MCP tool \"" + server + "/" + toolName + "\" is no longer available.");
            }
            return client;
        };
    }
}
