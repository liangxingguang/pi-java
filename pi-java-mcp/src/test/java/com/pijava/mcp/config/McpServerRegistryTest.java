package com.pijava.mcp.config;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code McpServerRegistry}（{@code mcp-servers.ts:288-319}）。
 */
class McpServerRegistryTest {

    private static McpServerConfig.Stdio stdio(String command) {
        return new McpServerConfig.Stdio(null, null, null, null, null, null, command,
                null, null, null);
    }

    private static RegisteredMcpServer server(String name, String command, String extensionPath) {
        return new RegisteredMcpServer(name, stdio(command), extensionPath);
    }

    @Test
    void registersAndReplaces() {
        var registry = new McpServerRegistry();
        registry.register(server("files", "one", "ext/a"));
        registry.register(server("files", "two", "ext/b"));

        assertThat(registry.get("files").config()).isEqualTo(stdio("two"));
        assertThat(registry.get("files").extensionPath()).isEqualTo("ext/b");
        assertThat(registry.list()).hasSize(1);
    }

    @Test
    void onlyTheOwningExtensionCanUnregister() {
        var registry = new McpServerRegistry();
        registry.register(server("files", "one", "ext/a"));

        registry.unregister("files", "ext/b");
        assertThat(registry.get("files")).isNotNull();

        registry.unregister("files", "ext/a");
        assertThat(registry.get("files")).isNull();
    }

    @Test
    void unregisteringSomethingThatIsNotThereIsSilent() {
        var registry = new McpServerRegistry();
        registry.unregister("nope", "ext/a");
        assertThat(registry.list()).isEmpty();
    }

    @Test
    void listKeepsRegistrationOrder() {
        var registry = new McpServerRegistry();
        registry.register(server("zebra", "z", "ext/a"));
        registry.register(server("alpha", "a", "ext/a"));
        // Replacing keeps the position the name first took.
        registry.register(server("zebra", "z2", "ext/b"));

        assertThat(registry.list()).extracting(RegisteredMcpServer::name)
                .containsExactly("zebra", "alpha");
    }

    @Test
    void listHandsBackCopies() {
        var registry = new McpServerRegistry();
        registry.register(server("files", "one", "ext/a"));

        var listed = new ArrayList<>(registry.list());
        listed.clear();

        assertThat(registry.list()).hasSize(1);
        // The configuration is a copy too: a record, rebuilt from the stored one.
        assertThat(registry.list().get(0).config()).isEqualTo(stdio("one"));
        assertThat(registry.list().get(0).config()).isNotSameAs(registry.get("files").config());
    }

    @Test
    void theChangeListenerRunsOnEveryChange() {
        var registry = new McpServerRegistry();
        var changes = new ArrayList<String>();
        registry.setChangeListener(() -> changes.add("changed"));

        registry.register(server("files", "one", "ext/a"));
        registry.unregister("files", "ext/b");
        registry.unregister("files", "ext/a");

        // Two: the register and the successful unregister. The rejected one notified nobody.
        assertThat(changes).hasSize(2);
    }

    @Test
    void theListenerCanBeDetached() {
        var registry = new McpServerRegistry();
        var changes = new ArrayList<String>();
        registry.setChangeListener(() -> changes.add("changed"));
        registry.register(server("one", "a", "ext/a"));
        registry.setChangeListener(null);
        registry.register(server("two", "b", "ext/a"));

        assertThat(changes).hasSize(1);
    }

    @Test
    void theScopeKnowsExtensions() {
        assertThat(McpScope.values()).containsExactly(McpScope.GLOBAL, McpScope.PROJECT,
                McpScope.EXTENSION);
        assertThat(List.of(McpScope.EXTENSION)).hasSize(1);
    }
}
