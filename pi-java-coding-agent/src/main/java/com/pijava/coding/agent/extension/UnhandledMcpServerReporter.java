package com.pijava.coding.agent.extension;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.pijava.coding.agent.extension.event.ExtensionEventSpec;
import com.pijava.mcp.config.RegisteredMcpServer;

/**
 * 批 23（docs/34 T2）：pi {@code reportUnhandledMcpServers}（runner.ts:749-765 逐字）——
 * 注册了 MCP 服务器但没有任何扩展订阅 {@code mcp_servers_change}（＝没人负责连接它们，
 * 例如内置 MCP 支持被别的扩展替换）时，按服务器名各报一次扩展错误。
 *
 * <p>去重集随会话存活（runner 的 {@code reportedMcpServers} 字段对位）。</p>
 */
public final class UnhandledMcpServerReporter {

    /** pi runner.ts:759-763 的错文逐字。 */
    static final String MESSAGE_INFIX =
        " is registered, but no loaded extension connects MCP servers;"
            + " another extension may have replaced the built-in MCP support";

    private final ExtensionEventBus bus;
    /** pi {@code reportedMcpServers}——每名只报一次。 */
    private final Set<String> reported = new HashSet<>();

    public UnhandledMcpServerReporter(ExtensionEventBus bus) {
        this.bus = bus;
    }

    /** pi runner.ts:753-764 逐字：有处理器 ⇒ 静默；否则逐服务器报一次。 */
    public void report(List<RegisteredMcpServer> servers) {
        if (bus.hasHandlers(ExtensionEventSpec.MCP_SERVERS_CHANGE)) {
            return;
        }
        for (var server : servers) {
            if (!reported.add(server.name())) {
                continue;
            }
            bus.emitError(new ExtensionError(server.extensionPath(), "register_mcp_server",
                "MCP server \"" + server.name() + "\"" + MESSAGE_INFIX, null));
        }
    }
}
