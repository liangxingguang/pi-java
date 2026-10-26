package com.pijava.coding.agent.extension.mcp;

import java.util.ArrayList;
import java.util.List;


import com.pijava.coding.agent.extension.mcp.McpManagerUi.McpMenu;
import com.pijava.coding.agent.extension.mcp.McpManagerUi.McpMenuItem;
import com.pijava.mcp.config.McpExposure;
import com.pijava.mcp.config.McpScope;
import com.pijava.mcp.runtime.McpServerState;

/**
 * 批 24（docs/35 T10）：/mcp 管理器的菜单模型构建（pi {@code index.ts}
 * serversMenu/serverMenu/showTools/chooseExposure :781-935 逐字文案）。
 */
final class McpManagerMenus {

    private McpManagerMenus() {
    }

    private static String wire(McpExposure exposure) {
        return exposure.wire();
    }

    private static String scopeWire(McpScope scope) {
        return scope.name().toLowerCase(java.util.Locale.ROOT);
    }

    // ── serversMenu（pi :787-799） ──

    static McpMenu serversMenu(McpExtensionState state) {
        var notices = new ArrayList<String>();
        for (var error : state.configErrors) {
            notices.add("config: " + error);
        }
        notices.addAll(state.overridden);
        var servers = new ArrayList<>(state.servers);
        servers.sort((a, b) -> {
            var rank = Integer.compare(McpExtensionState.attentionRank(a),
                McpExtensionState.attentionRank(b));
            return rank != 0 ? rank : a.entry.name().compareTo(b.entry.name());
        });
        var items = new ArrayList<McpMenuItem>();
        for (var server : servers) {
            items.add(new McpMenuItem(server.entry.name(), server.entry.name(),
                McpExtensionState.describeState(server) + " · "
                    + wire(McpExtensionState.exposureOf(server.entry)) + " · "
                    + (server.entry.override() != null ? "global, project override"
                        : scopeWire(server.entry.scope()))));
        }
        return new McpMenu("MCP servers",
            null, notices.isEmpty() ? null : String.join("\n", notices),
            items,
            "No MCP servers configured. Add them to "
                + state.agentDirOrDefault().resolve("mcp.json") + " or .pi-java/mcp.json.",
            null, "manage", "close");
    }

    // ── serverMenu（pi :801-860） ──

    static McpMenu serverMenu(McpExtensionState state, String name) {
        var server = state.findServer(name);
        if (server == null) {
            return new McpMenu(name, null, null, List.of(),
                "This server is no longer configured.", null, "", "back");
        }
        var entry = server.entry;
        var connection = server.connection;
        String saved;
        if (entry.scope() == McpScope.EXTENSION) {
            saved = "for this session";
        } else if (entry.override() != null) {
            saved = "saved to the project mcp.json";
        } else {
            saved = "saved to the " + scopeWire(entry.scope()) + " mcp.json";
        }
        var inProjectSaved = "saved to the project mcp.json";
        // 全局无 override 的服务器可只对受信项目开关
        var inProject = entry.scope() == McpScope.GLOBAL && entry.override() == null
            && state.projectConfig != null;
        var items = new ArrayList<McpMenuItem>();
        if (!McpExtensionState.isEnabled(server)) {
            items.add(new McpMenuItem("enable", "Enable", saved));
            if (inProject) {
                items.add(new McpMenuItem("enable-project", "Enable in this project",
                    inProjectSaved));
            }
        } else {
            var connectionState = connection != null ? connection.state() : null;
            if (connectionState == McpServerState.NEEDS_AUTH) {
                items.add(new McpMenuItem("signin", "Sign in", "opens the browser"));
            }
            if (connectionState == McpServerState.CONNECTED && connection != null) {
                items.add(new McpMenuItem("tools", "Tools",
                    connection.tools().size() + " offered"));
            }
            if (connectionState == McpServerState.FAILED
                || connectionState == McpServerState.DISCONNECTED
                || connectionState == McpServerState.CONNECTED
                || connectionState == McpServerState.NEEDS_AUTH) {
                items.add(new McpMenuItem("reconnect", "Reconnect", null));
            }
            if (connectionState == McpServerState.CONNECTED && connection != null
                && connection.oauthUrl() != null) {
                items.add(new McpMenuItem("signout", "Sign out",
                    "deletes the stored credentials"));
            }
            items.add(new McpMenuItem("exposure", "Exposure",
                wire(McpExtensionState.exposureOf(entry))));
            items.add(new McpMenuItem("disable", "Disable", saved));
            if (inProject) {
                items.add(new McpMenuItem("disable-project", "Disable in this project",
                    inProjectSaved));
            }
        }
        var sourceText = server.extensionPath != null
            ? server.extensionPath : String.valueOf(entry.source());
        var details = McpExtensionState.describeTransport(entry) + "\n"
            + scopeWire(entry.scope()) + ": " + sourceText
            + (entry.override() != null ? "\nproject override: " + entry.override() : "")
            + "\nState: " + McpExtensionState.describeState(server, false);
        var errorLines = new ArrayList<String>();
        if (server.message != null) {
            errorLines.add(server.message);
        }
        if (connection != null && connection.state() != McpServerState.CONNECTED
            && connection.error() != null) {
            errorLines.add(connection.error());
        }
        return new McpMenu("MCP server " + name, details,
            errorLines.isEmpty() ? null : String.join("\n", errorLines),
            items, null, items.isEmpty() ? null : items.get(0).value(), "select", "back");
    }

    // ── showTools（pi :862-880） ──

    static McpMenu toolsMenu(McpServerHandle server) {
        var exposure = McpExtensionState.exposureOf(server.entry);
        var overridden = server.entry.config().toolExposure() != null
            && !server.entry.config().toolExposure().isEmpty();
        var items = new ArrayList<McpMenuItem>();
        var connection = server.connection;
        if (connection != null) {
            for (var tool : connection.tools()) {
                var toolExposure = com.pijava.mcp.config.McpServerConfigs
                    .getMcpToolExposure(server.entry.config(), tool.name());
                var description = McpExtensionState.firstLine(
                    tool.description() != null ? tool.description() : "");
                items.add(new McpMenuItem(tool.name(), tool.name(),
                    toolExposure == exposure ? description
                        : "[" + wire(toolExposure) + "] " + description));
            }
        }
        return new McpMenu("Tools of " + server.entry.name(),
            "Exposure " + wire(exposure) + ": " + (exposure == McpExposure.HIDDEN
                ? "unreachable" : McpExtensionState.EXPOSURE_DESCRIPTIONS.get(exposure))
                + (overridden ? "\nSome tools override it with toolExposure." : ""),
            null, items, "The server offers no tools.", null, "back", "back");
    }

    // ── chooseExposure（pi :882-905） ──

    static McpMenu exposureMenu(McpServerHandle server) {
        var current = McpExtensionState.exposureOf(server.entry);
        // pi Object.keys(EXPOSURE_DESCRIPTIONS) 序＝声明序（codemode/deferred/direct）
        var items = new ArrayList<McpMenuItem>();
        for (var exposure : List.of(McpExposure.CODEMODE, McpExposure.DEFERRED,
                McpExposure.DIRECT)) {
            items.add(new McpMenuItem(wire(exposure),
                (exposure == current ? "✓ " : "  ") + wire(exposure),
                McpExtensionState.EXPOSURE_DESCRIPTIONS.get(exposure)));
        }
        return new McpMenu("Exposure of " + server.entry.name(),
            server.entry.scope() == McpScope.EXTENSION
                ? "Applies to this session; the server is registered by "
                    + (server.extensionPath != null ? server.extensionPath
                        : String.valueOf(server.entry.source())) + "."
                : "Saved to " + (server.entry.override() != null
                    ? String.valueOf(server.entry.override())
                    : String.valueOf(server.entry.source())) + ".",
            null, items, null, wire(current), "save", "back");
    }
}
