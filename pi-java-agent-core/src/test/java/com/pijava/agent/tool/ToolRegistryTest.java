package com.pijava.agent.tool;
import com.pijava.ai.AbortSignal;

import java.util.Map;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolRegistryTest {

    private static AgentTool<Map<String, Object>, Void> dummyTool(String name) {
        return new AgentTool<>() {
            @Override public String name() { return name; }
            @Override public String label() { return name; }
            @Override public String description() { return "A dummy tool"; }
            @Override public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
            @Override public ExecutionMode executionMode() { return new ExecutionMode.Parallel(); }
            @Override
            public ToolResult<Void> execute(String toolCallId, Map<String, Object> params,
                    AbortSignal signal, ToolUpdateCallback<Void> onUpdate, ToolContext context) {
                return ToolResult.success("done");
            }
        };
    }

    @Test
    void registerAndLookup() {
        var registry = new ToolRegistry(null);
        var tool = dummyTool("test-tool");
        registry.register(tool);
        assertThat(registry.get("test-tool")).isSameAs(tool);
    }

    @Test
    void toolNamesReturnsRegistered() {
        var registry = new ToolRegistry(null);
        registry.register(dummyTool("a"));
        registry.register(dummyTool("b"));
        assertThat(registry.toolNames()).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void getUnknownReturnsNull() {
        var registry = new ToolRegistry(null);
        assertThat(registry.get("nonexistent")).isNull();
    }

    @Test
    void executeUnknownThrows() {
        var registry = new ToolRegistry(null);
        assertThatThrownBy(() -> registry.execute("unknown", "id", Map.of(),
            null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Tool not found");
    }

    @Test
    void executeApprovedTool() throws Exception {
        var registry = new ToolRegistry(null);
        registry.register(dummyTool("ok"));
        var result = registry.execute("ok", "id1", Map.of(), null, null, null);
        assertThat(result).isNotNull();
        assertThat(result.content()).isNotEmpty();
    }

    @Test
    void executeDeniedThrows() {
        var registry = new ToolRegistry((name, args) -> false);
        registry.register(dummyTool("blocked"));
        assertThatThrownBy(() -> registry.execute("blocked", "id", Map.of(),
            null, null, null))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("not approved");
    }

    @Test
    void toToolDefinitions() {
        var registry = new ToolRegistry(null);
        registry.register(dummyTool("a"));
        var defs = registry.toToolDefinitions();
        assertThat(defs).hasSize(1);
        assertThat(defs.get(0).name()).isEqualTo("a");
    }

    @Test
    void toSystemPromptFragment() {
        var registry = new ToolRegistry(null);
        registry.register(dummyTool("test"));
        var fragment = registry.toSystemPromptFragment();
        assertThat(fragment).contains("**test**");
        assertThat(fragment).contains("A dummy tool");
    }
}
