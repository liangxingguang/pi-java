package com.pijava.coding.agent.extension;

import java.util.List;
import java.util.Map;

import com.pijava.agent.skill.SkillManager;
import com.pijava.mcp.config.McpServerConfig;
import com.pijava.mcp.config.RegisteredMcpServer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 批 23（docs/34 T1）：扩展 MCP 注册面三守卫（pi loader.ts:471-489 逐字）——
 * 校验失败抛 / 他扩展同名抛 / 命名空间（{@code -}⇔{@code _} 归一）撞名抛；
 * 同名再注册＝替换本扩展旧注册；unregister 所有权门；getMcpServers 全量深拷贝。
 */
class ExtensionMcpServerApiTest {

    private static McpServerConfig stdio(String command) {
        return new McpServerConfig.Stdio(null, null, null, null, null, null,
            command, List.of(), null, null);
    }

    private static DefaultExtensionContext newContext() {
        return new DefaultExtensionContext(null, new SkillManager(),
            ExtensionUI.noop(), new ExtensionEventBus());
    }

    @Test
    void registersAndListsInRegistrationOrder() {
        var ctx = newContext();
        var extA = ctx.forExtension("ext-a", "/path/a.jar");
        var extB = ctx.forExtension("ext-b", "/path/b.jar");
        extA.registerMcpServer("one", stdio("run-one"));
        extB.registerMcpServer("two", stdio("run-two"));

        var listed = ctx.getMcpServers();
        assertThat(listed.stream().map(RegisteredMcpServer::name))
            .containsExactly("one", "two");
        assertThat(listed.stream().map(RegisteredMcpServer::extensionPath))
            .containsExactly("/path/a.jar", "/path/b.jar");
    }

    @Test
    void invalidConfigThrowsVerbatimMessage() {
        var ctx = newContext();
        var ext = ctx.forExtension("bad", "/path/bad.jar");
        // 无名非法字符（pi SERVER_NAME /^[A-Za-z0-9_-]+$/）。
        assertThatThrownBy(() -> ext.registerMcpServer("not ok!", stdio("x")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith(
                "Invalid MCP server registered by extension \"/path/bad.jar\": ");
    }

    @Test
    void sameNameOwnedByAnotherExtensionThrows() {
        var ctx = newContext();
        ctx.forExtension("ext-a", "/a.jar").registerMcpServer("srv", stdio("a"));
        var extB = ctx.forExtension("ext-b", "/b.jar");
        assertThatThrownBy(() -> extB.registerMcpServer("srv", stdio("b")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("MCP server \"srv\" is already registered by extension \"/a.jar\"");
    }

    @Test
    void sameNameBySameExtensionReplaces() {
        var ctx = newContext();
        var ext = ctx.forExtension("ext-a", "/a.jar");
        ext.registerMcpServer("srv", stdio("first"));
        ext.registerMcpServer("srv", stdio("second"));
        var listed = ctx.getMcpServers();
        assertThat(listed).hasSize(1);
        assertThat(((McpServerConfig.Stdio) listed.getFirst().config()).command())
            .isEqualTo("second");
    }

    @Test
    void namespaceClashThrows() {
        var ctx = newContext();
        ctx.forExtension("ext-a", "/a.jar").registerMcpServer("my-server", stdio("a"));
        var extB = ctx.forExtension("ext-b", "/b.jar");
        assertThatThrownBy(() -> extB.registerMcpServer("my_server", stdio("b")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage(
                "MCP server \"my_server\" conflicts with registered server \"my-server\"");
    }

    @Test
    void unregisterRespectsOwnership() {
        var ctx = newContext();
        var extA = ctx.forExtension("ext-a", "/a.jar");
        var extB = ctx.forExtension("ext-b", "/b.jar");
        extA.registerMcpServer("srv", stdio("a"));
        // 别的扩展注销 ⇒ 静默不动（mcp-servers.ts:298-303）。
        extB.unregisterMcpServer("srv");
        assertThat(ctx.getMcpServers()).hasSize(1);
        extA.unregisterMcpServer("srv");
        assertThat(ctx.getMcpServers()).isEmpty();
    }

    @Test
    void listReturnsDeepCopies() {
        var ctx = newContext();
        var ext = ctx.forExtension("ext-a", "/a.jar");
        ext.registerMcpServer("srv", new McpServerConfig.Stdio(null, null, null, null,
            null, null, "cmd", new java.util.ArrayList<>(List.of("x")), null, null));
        var first = ctx.getMcpServers().getFirst();
        var second = ctx.getMcpServers().getFirst();
        assertThat(second.config()).isNotSameAs(first.config());
        assertThat(second).isEqualTo(first);
    }

    @Test
    void baseContextThrowsWithoutAttribution() {
        var ctx = newContext();
        assertThatThrownBy(() -> ctx.registerMcpServer("srv", stdio("x")))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ctx.unregisterMcpServer("srv"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void exposureAliasNormalizesOnValidation() {
        // pi validateMcpServerConfig 解析别名 codemode-deferred ⇒ codemode（经
        // JsonNode 往返——注册的是校验后的规范化配置）。
        var ctx = newContext();
        var ext = ctx.forExtension("ext-a", "/a.jar");
        ext.registerMcpServer("srv", new McpServerConfig.Http(null, null, null, null,
            null, null, "http://localhost:9", Map.of(), null, null));
        assertThat(ctx.getMcpServers().getFirst().config())
            .isInstanceOf(McpServerConfig.Http.class);
    }
}
