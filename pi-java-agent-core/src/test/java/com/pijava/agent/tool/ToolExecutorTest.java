package com.pijava.agent.tool;

import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.harness.Action;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.message.ContentBlock;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolExecutorTest {

    private static AgentTool<String, Void> echoTool() {
        return new AgentTool<>() {
            @Override public String name() { return "echo"; }
            @Override public String label() { return "echo"; }
            @Override public String description() { return "Echo input"; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public ExecutionMode executionMode() { return new ExecutionMode.Sequential(); }
            @Override public String prepareArguments(Map<String, Object> raw) {
                return String.valueOf(raw.get("text"));
            }
            @Override public ToolResult<Void> execute(String id, String params, AbortSignal signal,
                    ToolUpdateCallback<Void> onUpdate, ToolContext ctx) {
                return ToolResult.success(params);
            }
        };
    }

    @Test
    void executeSequentialExecutesBatch() {
        var registry = new ToolRegistry(null);
        registry.register(echoTool());
        var executor = new ToolExecutor(registry, null);

        var results = executor.executeSequential(
                List.of(new Action.ExecuteTool("c1", "echo", Map.of("text", "hello"))),
                null);

        assertThat(results).hasSize(1);
        var entry = results.get(0);
        assertThat(entry.role()).isEqualTo("tool");
        var block = (ContentBlock.ToolResultContent) entry.blocks().get(0);
        assertThat(block.toolName()).isEqualTo("echo");
    }

    @Test
    void executeSequentialWrapsErrors() {
        var registry = new ToolRegistry(null);
        registry.register(new AgentTool<String, Void>() {
            @Override public String name() { return "boom"; }
            @Override public String label() { return "boom"; }
            @Override public String description() { return "Always fails"; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public ExecutionMode executionMode() { return new ExecutionMode.Sequential(); }
            @Override public String prepareArguments(Map<String, Object> raw) { return ""; }
            @Override public ToolResult<Void> execute(String id, String params, AbortSignal signal,
                    ToolUpdateCallback<Void> onUpdate, ToolContext ctx) {
                throw new IllegalStateException("tool failed");
            }
        });
        var executor = new ToolExecutor(registry, null);

        var results = executor.executeSequential(
                List.of(new Action.ExecuteTool("c1", "boom", Map.of())), null);

        assertThat(results).hasSize(1);
        var block = (ContentBlock.ToolResultContent) results.get(0).blocks().get(0);
        assertThat(block.isError()).isTrue();
    }

    @Test
    void executeParallelThrowsPhase3() {
        var executor = new ToolExecutor(new ToolRegistry(null), null);
        assertThatThrownBy(() -> executor.executeParallel(List.of(), null))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
