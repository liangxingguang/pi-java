package com.pijava.coding.agent.extension.mcp;

import java.util.concurrent.CompletableFuture;

import org.jspecify.annotations.Nullable;

import com.pijava.mcp.config.McpConfig;
import com.pijava.mcp.config.McpConfigPatch;
import com.pijava.mcp.config.McpExposure;
import com.pijava.mcp.config.McpScope;
import com.pijava.mcp.config.McpServerConfig;
import com.pijava.mcp.config.McpServerEntry;
import com.pijava.mcp.runtime.McpServerState;

/**
 * 批 24（docs/35 T9）：/mcp 的连接与配置写回动作（pi {@code index.ts}
 * reconnect/setEnabled/setExposure/saveConfig :656-787 逐字对位）。
 * 从 {@link McpManagerActions} 拆出（文件 ≤500 行纪律）。
 */
final class McpServerOps {

    private final McpExtensionState state;
    private final McpLifecycle lifecycle;
    private final McpToolRegistrar registrar;

    McpServerOps(McpExtensionState state, McpLifecycle lifecycle, McpToolRegistrar registrar) {
        this.state = state;
        this.lifecycle = lifecycle;
        this.registrar = registrar;
    }

    /** pi {@code reconnect}（:715-731）：链在上一次 ready 之后；错误文案返回。 */
    @Nullable String reconnect(McpServerHandle server) {
        var connection = server.connection;
        if (connection == null) {
            return "MCP server \"" + server.entry.name() + "\" is disabled.";
        }
        var previous = server.ready != null ? server.ready
            : CompletableFuture.<Void>completedFuture(null);
        var ready = previous.thenCompose(ignored -> connection.reconnect());
        server.ready = ready.handle((ok, err) -> null);
        try {
            ready.join();
            return null;
        } catch (RuntimeException error) {
            return McpExtension.errorMessage(error);
        }
    }

    /** enable/disable（含项目臂）；返回错误文案或 null（连接错误落在状态里）。 */
    @Nullable String setEnabled(McpServerHandle server, boolean enabled, boolean inProject) {
        var failed = saveConfig(server, new McpConfigPatch(enabled, null), inProject);
        if (failed != null) {
            return failed;
        }
        if (!enabled) {
            var connection = server.connection;
            server.attempt = null;
            server.connection = null;
            if (connection != null) {
                // 进行中的 reconnect 可能已摘旧 client 还在等传输关闭——单关连接不等它
                var ready = server.ready != null ? server.ready
                    : CompletableFuture.<Void>completedFuture(null);
                server.closing = CompletableFuture.allOf(connection.close(), ready)
                    .handle((ok, err) -> null);
            }
            var closing = server.closing;
            registrar.hideTools(server.entry.name());
            state.emitChange();
            try {
                if (closing != null) {
                    closing.join();
                }
            } finally {
                if (server.closing == closing) {
                    server.closing = null;
                }
            }
            return null;
        }
        lifecycle.startConnection(server, server.closing).join();
        return null;
    }

    /** pi {@code setExposure}（:770-787）：写配置＋重注册＋间接工具撤离激活集。 */
    @Nullable String setExposure(McpServerHandle server, McpExposure exposure) {
        var failed = saveConfig(server, new McpConfigPatch(null, exposure), false);
        if (failed != null) {
            return failed;
        }
        if (server.connection != null
            && server.connection.state() == McpServerState.CONNECTED) {
            registrar.registerTools(server.connection);
        }
        registrar.syncResourceTools();
        // 不再直连的工具离开声明集（direct 工具在注册时激活）
        registrar.deactivateIndirectTools(server.entry.name());
        state.emitChange();
        return null;
    }

    /**
     * 写配置（pi {@code saveConfig} :658-676）；返回写不进文件的错误文案。
     * 注册服务器只及当前会话。{@code inProject} 加项目 override。
     */
    @Nullable String saveConfig(McpServerHandle server, McpConfigPatch patch,
                                boolean inProject) {
        var override = inProject ? state.projectConfig : server.entry.override();
        var entry = override != null
            ? new McpServerEntry(server.entry.name(), server.entry.config(),
                server.entry.source(), server.entry.scope(), override)
            : server.entry;
        if (entry.scope() != McpScope.EXTENSION) {
            var target = override != null ? override : entry.source();
            try {
                McpConfig.update(target, entry.name(), patch, override != null);
            } catch (RuntimeException error) {
                return "Could not update " + target + ": " + McpExtension.errorMessage(error);
            }
        }
        server.entry = new McpServerEntry(entry.name(), patched(entry.config(), patch),
            entry.source(), entry.scope(), entry.override());
        return null;
    }

    private static McpServerConfig patched(McpServerConfig config, McpConfigPatch patch) {
        var enabled = patch.enabled() != null ? patch.enabled() : config.enabled();
        var exposure = patch.exposure() != null ? patch.exposure() : config.exposure();
        if (config instanceof McpServerConfig.Stdio stdio) {
            return new McpServerConfig.Stdio(stdio.type(), exposure, stdio.description(),
                stdio.toolExposure(), enabled, stdio.timeout(), stdio.command(),
                stdio.args(), stdio.env(), stdio.cwd());
        }
        var http = (McpServerConfig.Http) config;
        return new McpServerConfig.Http(http.type(), exposure, http.description(),
            http.toolExposure(), enabled, http.timeout(), http.url(), http.headers(),
            http.oauth(), http.auth());
    }
}
