package com.pijava.coding.agent.extension.mcp;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.jspecify.annotations.Nullable;

import com.pijava.mcp.config.McpExposure;
import com.pijava.mcp.config.McpScope;
import com.pijava.mcp.config.McpServerConfig;
import com.pijava.mcp.config.McpServerEntry;
import com.pijava.mcp.runtime.McpServerConnection;
import com.pijava.mcp.runtime.McpServerState;

/**
 * 批 24（docs/35）：内置 MCP 扩展的会话状态与纯谓词群（pi {@code index.ts:283-300}
 * 的闭包变量＋{@code :132-154,231-278} 的纯件逐字对位）。
 */
final class McpExtensionState {

    /** pi {@code EXPOSURE_DESCRIPTIONS}（:132-136 逐字）；hidden 不在表。 */
    static final java.util.Map<McpExposure, String> EXPOSURE_DESCRIPTIONS = java.util.Map.of(
        McpExposure.CODEMODE, "called from codemode scripts, which find them with searchTools()",
        McpExposure.DEFERRED,
            "not declared until tool_search loads them, then called directly; no codemode needed",
        McpExposure.DIRECT, "declared to the model like built-in tools");

    final List<McpServerHandle> servers = new ArrayList<>();
    /** {@code mcp.json} 两源的条目（同名压注册表）。 */
    List<McpServerEntry> configuredEntries = List.of();
    List<String> configErrors = List.of();
    /** 受信项目的 mcp.json（/mcp 存项目 override 的落点）；未受信 ⇒ null。 */
    @Nullable Path projectConfig;
    /** 被 mcp.json 压掉的注册服务器说明行（/mcp 显示）。 */
    List<String> overridden = List.of();
    /** 首个提示是否已等过启动连接（pi {@code waitedForStartup}）。 */
    boolean waitedForStartup;
    /** 不可达警告本会话是否已报（pi {@code warnedUnreachable}）。 */
    boolean warnedUnreachable;
    /** 首提示等待 direct 服务器的上限（pi {@code startupWaitMs}，默认 10s；测试可覆写）。 */
    long startupWaitMs = 10_000L;
    /** 会话工作目录（stdio 服务器相对路径的基）。 */
    Path sessionCwd = Path.of(System.getProperty("user.dir"));
    /** agent 目录（mcp-auth.json/mcp.log 的家）；session_start 从 settings 存储面解析
     * （批 23 forSettings 同手法——测试夹具不读真 home）。 */
    @Nullable Path agentDir;

    /** agentDir 已解析值；未装配（--help 轻量道等）⇒ 默认目录。 */
    Path agentDirOrDefault() {
        return agentDir != null ? agentDir
            : com.pijava.coding.agent.core.FileSettingsStorage.defaultAgentDir();
    }
    /** 启动连接全落地即完成（/mcp 子命令 {@code await pending} 对位）；null = 未启动。 */
    volatile @Nullable CompletableFuture<Void> pending;

    // ── 订阅面（pi listeners/emitChange/subscribe，:327-336）——/mcp 菜单重建用 ──

    private final Set<Runnable> listeners = new LinkedHashSet<>();

    synchronized void emitChange() {
        for (var listener : List.copyOf(listeners)) {
            listener.run();
        }
    }

    /** 订阅变化；返回退订闭包。 */
    synchronized Runnable subscribe(Runnable listener) {
        listeners.add(listener);
        return () -> {
            synchronized (this) {
                listeners.remove(listener);
            }
        };
    }

    // ── 谓词群（pi :132-154 逐字） ──

    static boolean isEnabled(McpServerHandle server) {
        return server.entry.config().enabled() != Boolean.FALSE;
    }

    static McpExposure exposureOf(McpServerEntry entry) {
        var exposure = entry.config().exposure();
        return exposure != null ? exposure : McpExposure.CODEMODE;
    }

    /** 配置可知的 exposure 集（连接前即可判定）。 */
    static Set<McpExposure> configuredExposures(McpServerEntry entry) {
        var result = new LinkedHashSet<McpExposure>();
        result.add(exposureOf(entry));
        var toolExposure = entry.config().toolExposure();
        if (toolExposure != null) {
            result.addAll(toolExposure.values());
        }
        return result;
    }

    /** 有工具声明给模型 ⇒ 首提示要等它。 */
    static boolean hasDirectTools(McpServerEntry entry) {
        return configuredExposures(entry).contains(McpExposure.DIRECT);
    }

    /** 有工具经 codemode/tool_search 到达（java：只进提示段，永不可达——裁决⑥）。 */
    static boolean hasIndirectTools(McpServerEntry entry) {
        var exposures = configuredExposures(entry);
        return exposures.contains(McpExposure.CODEMODE) || exposures.contains(McpExposure.DEFERRED);
    }

    @Nullable McpServerHandle findServer(String name) {
        for (var server : servers) {
            if (server.entry.name().equals(name)) {
                return server;
            }
        }
        return null;
    }

    List<McpServerConnection> connections() {
        var result = new ArrayList<McpServerConnection>();
        for (var server : servers) {
            if (server.connection != null) {
                result.add(server.connection);
            }
        }
        return result;
    }

    // ── 展示纯件（pi :231-278 逐字） ──

    static String firstLine(String text) {
        var lines = text.split("\n", 2);
        return lines.length == 0 ? "" : lines[0];
    }

    /** 列表与启动报告的短状态；{@code withError} 拼失败首行。 */
    static String describeState(McpServerHandle server) {
        return describeState(server, true);
    }

    static String describeState(McpServerHandle server, boolean withError) {
        if (!isEnabled(server)) {
            return "disabled";
        }
        var connection = server.connection;
        if (connection == null) {
            return "starting";
        }
        return switch (connection.state()) {
            case NEEDS_AUTH -> "needs sign-in";
            case FAILED -> withError
                ? "failed: " + firstLine(connection.error() != null ? connection.error()
                        : "unknown error")
                : "failed";
            case CONNECTED -> {
                var tools = connection.tools().size();
                var resources = connection.resources().size();
                var resourceCount = resources > 0
                    ? " · " + resources + " resource" + (resources == 1 ? "" : "s") : "";
                yield "connected · " + tools + " tool" + (tools == 1 ? "" : "s") + resourceCount;
            }
            case CONNECTING -> "connecting…";
            default -> connection.state().wire();
        };
    }

    /** 需要先照顾的服务器排前（pi {@code attentionRank} :259-272 逐字）。 */
    static int attentionRank(McpServerHandle server) {
        if (!isEnabled(server)) {
            return 5;
        }
        var connection = server.connection;
        if (connection == null) {
            return 3;
        }
        return switch (connection.state()) {
            case NEEDS_AUTH -> 0;
            case FAILED -> 1;
            case DISCONNECTED -> 2;
            case CONNECTED -> 4;
            default -> 3;
        };
    }

    static String describeTransport(McpServerEntry entry) {
        McpServerConfig config = entry.config();
        if (config instanceof McpServerConfig.Http http) {
            return http.url();
        }
        var stdio = (McpServerConfig.Stdio) config;
        return String.join(" ",
            stdio.args() == null ? List.of(stdio.command())
                : concat(stdio.command(), stdio.args()));
    }

    private static List<String> concat(String head, List<String> rest) {
        var result = new ArrayList<String>(rest.size() + 1);
        result.add(head);
        result.addAll(rest);
        return result;
    }

    /**
     * 扩展注册服务器的 entry 构造（pi {@code registeredServers()} :352-373 对位）：
     * {@code source} 槽塞 extensionPath——Windows 冒号路径（{@code builtin:<name>}）
     * 不是合法 {@link Path}，解析失败退化为下划线形（显示走 {@code handle.extensionPath}
     * 原文，此槽不参与展示）。
     */
    static McpServerEntry extensionEntry(String name, McpServerConfig config, String extensionPath) {
        return new McpServerEntry(name, config, lenientPath(extensionPath), McpScope.EXTENSION, null);
    }

    static Path lenientPath(String text) {
        try {
            return Path.of(text);
        } catch (InvalidPathException notAPath) {
            return Path.of(text.replace(':', '_'));
        }
    }
}
