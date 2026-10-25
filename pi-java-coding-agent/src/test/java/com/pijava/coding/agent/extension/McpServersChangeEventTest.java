package com.pijava.coding.agent.extension;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.pijava.agent.skill.SkillManager;
import com.pijava.mcp.config.McpServerConfig;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.MCP_SERVERS_CHANGE;

/**
 * 批 23（docs/34 T2）：{@code mcp_servers_change} 发射（pi runner.ts:457-461）＋
 * 无处理器臂（runner.ts:749-765 逐字——错文/按名去重/有处理器静默）。
 */
class McpServersChangeEventTest {

    private static McpServerConfig stdio() {
        return new McpServerConfig.Stdio(null, null, null, null, null, null,
            "cmd", List.of(), null, null);
    }

    /** 装配序对位：firer/reporter 接好后才装 changeListener（AgentSession 装载尾）。 */
    private record Rig(DefaultExtensionContext ctx, ExtensionEventBus bus,
                       SessionEventFirer firer, UnhandledMcpServerReporter reporter) {
        Rig wire() {
            ctx.mcpServers().setChangeListener(() -> {
                var registered = ctx.mcpServers().list();
                firer.mcpServersChange(registered);
                reporter.report(registered);
            });
            return this;
        }
    }

    private static Rig newRig() {
        var bus = new ExtensionEventBus();
        var ctx = new DefaultExtensionContext(null, new SkillManager(),
            ExtensionUI.noop(), bus);
        return new Rig(ctx, bus, new SessionEventFirer(bus, ctx),
            new UnhandledMcpServerReporter(bus));
    }

    @Test
    void registrationDuringLoadStaysSilentThenChangeBroadcastsFullList() {
        var rig = newRig();
        var seen = new CopyOnWriteArrayList<List<String>>();
        rig.ctx.forExtension("mcp-ext", "builtin:mcp")
            .on(MCP_SERVERS_CHANGE, (e, c) -> {
                seen.add(e.servers().stream().map(s -> s.name()).toList());
                return null;
            });
        // 装载期注册（listener 未装）⇒ 静默落表。
        rig.ctx.forExtension("ext-a", "/a.jar").registerMcpServer("early", stdio());
        assertThat(seen).isEmpty();
        rig.wire();
        // 装配后注册 ⇒ 广播，载荷为变更后全量列表。
        rig.ctx.forExtension("ext-a", "/a.jar").registerMcpServer("late", stdio());
        assertThat(seen).containsExactly(List.of("early", "late"));
        // 注销 ⇒ 再发。
        rig.ctx.forExtension("ext-a", "/a.jar").unregisterMcpServer("early");
        assertThat(seen).containsExactly(List.of("early", "late"), List.of("late"));
    }

    @Test
    void noHandlerReportsExtensionErrorOncePerName() {
        var rig = newRig();
        var errors = new CopyOnWriteArrayList<ExtensionError>();
        rig.bus.onError(errors::add);
        rig.wire();
        rig.ctx.forExtension("ext-a", "/a.jar").registerMcpServer("srv", stdio());
        rig.ctx.forExtension("ext-a", "/a.jar").registerMcpServer("srv", stdio()); // 重注册仍一次
        rig.ctx.forExtension("ext-b", "/b.jar").registerMcpServer("other", stdio());
        assertThat(errors).hasSize(2);
        assertThat(errors.get(0).extensionName()).isEqualTo("/a.jar");
        assertThat(errors.get(0).event()).isEqualTo("register_mcp_server");
        assertThat(errors.get(0).message()).isEqualTo(
            "MCP server \"srv\" is registered, but no loaded extension connects MCP servers;"
                + " another extension may have replaced the built-in MCP support");
        assertThat(errors.get(1).message()).startsWith("MCP server \"other\"");
    }

    @Test
    void handlerPresentSuppressesReporting() {
        var rig = newRig();
        var errors = new CopyOnWriteArrayList<ExtensionError>();
        rig.bus.onError(errors::add);
        rig.ctx.forExtension("mcp-ext", "builtin:mcp")
            .on(MCP_SERVERS_CHANGE, (e, c) -> null);
        rig.wire();
        rig.ctx.forExtension("ext-a", "/a.jar").registerMcpServer("srv", stdio());
        assertThat(errors).isEmpty();
    }

    @Test
    void handlerThrowingGoesToErrorChannelAndLaterHandlersStillRun() {
        var rig = newRig();
        var errors = new CopyOnWriteArrayList<ExtensionError>();
        var seen = new CopyOnWriteArrayList<String>();
        rig.bus.onError(errors::add);
        rig.ctx.forExtension("bad", "/bad.jar").on(MCP_SERVERS_CHANGE, (e, c) -> {
            throw new IllegalStateException("boom");
        });
        rig.ctx.forExtension("good", "/good.jar").on(MCP_SERVERS_CHANGE, (e, c) -> {
            seen.add("ran");
            return null;
        });
        rig.wire();
        rig.ctx.forExtension("ext-a", "/a.jar").registerMcpServer("srv", stdio());
        assertThat(seen).containsExactly("ran");
        // 事件处理器有归属 ⇒ 不再走无处理器臂（hasHandlers 为真）。
        // bus 按扩展名归属（docs/14）；reporter 按注册表 extensionPath 归属。
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst().extensionName()).isEqualTo("bad");
        assertThat(errors.getFirst().message()).isEqualTo("boom");
    }
}
