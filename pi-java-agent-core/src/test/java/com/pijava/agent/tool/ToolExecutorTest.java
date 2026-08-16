package com.pijava.agent.tool;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.harness.Action;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

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
        assertThat(entry.message().role()).isEqualTo("tool");
        var toolMessage = (Message.ToolResultMessage) entry.message();
        assertThat(toolMessage.toolName()).isEqualTo("echo");
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
        var toolMessage = (Message.ToolResultMessage) results.get(0).message();
        assertThat(toolMessage.isError()).isTrue();
    }

    @Test
    void executeParallelPreservesDeclarationOrder() {
        var registry = new ToolRegistry(null);
        registry.register(textTool("alpha", "a-result"));
        registry.register(textTool("beta", "b-result"));
        var executor = new ToolExecutor(registry, null);

        var results = executor.executeParallel(List.of(
                new Action.ExecuteTool("c1", "alpha", Map.of("text", "a")),
                new Action.ExecuteTool("c2", "beta", Map.of("text", "b"))), null);

        assertThat(results).hasSize(2);
        assertThat(toolText(results.get(0))).isEqualTo("a-result");
        assertThat(toolText(results.get(1))).isEqualTo("b-result");
    }

    @Test
    void executeParallelRunsToolsConcurrently() throws Exception {
        var registry = new ToolRegistry(null);
        var started = new AtomicInteger();
        var gate = new CountDownLatch(2);
        registry.register(new AgentTool<String, Void>() {
            @Override public String name() { return "blocking"; }
            @Override public String label() { return "blocking"; }
            @Override public String description() { return "Blocks until both start"; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public ExecutionMode executionMode() { return new ExecutionMode.Parallel(); }
            @Override public String prepareArguments(Map<String, Object> raw) {
                return String.valueOf(raw.get("text"));
            }
            @Override public ToolResult<Void> execute(String id, String params, AbortSignal signal,
                    ToolUpdateCallback<Void> onUpdate, ToolContext ctx) throws InterruptedException {
                started.incrementAndGet();
                gate.countDown();
                gate.await(5, TimeUnit.SECONDS);
                return ToolResult.success(params);
            }
        });
        var executor = new ToolExecutor(registry, null);

        var future = CompletableFuture.runAsync(() -> executor.executeParallel(
                List.of(new Action.ExecuteTool("c1", "blocking", Map.of("text", "1")),
                        new Action.ExecuteTool("c2", "blocking", Map.of("text", "2"))),
                null));

        // Both tool calls must start before either completes (parallel).
        assertThat(gate.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(started.get()).isEqualTo(2);
        future.get(5, TimeUnit.SECONDS);
    }

    private static AgentTool<String, Void> textTool(String name, String reply) {
        return new AgentTool<>() {
            @Override public String name() { return name; }
            @Override public String label() { return name; }
            @Override public String description() { return "Return a fixed text"; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public ExecutionMode executionMode() { return new ExecutionMode.Parallel(); }
            @Override public String prepareArguments(Map<String, Object> raw) {
                return String.valueOf(raw.get("text"));
            }
            @Override public ToolResult<Void> execute(String id, String params, AbortSignal signal,
                    ToolUpdateCallback<Void> onUpdate, ToolContext ctx) {
                return ToolResult.success(reply);
            }
        };
    }

    private static String toolText(Entry.Message entry) {
        var toolMessage = (Message.ToolResultMessage) entry.message();
        return ((ContentBlock.TextContent) toolMessage.content().get(0)).text();
    }
}
