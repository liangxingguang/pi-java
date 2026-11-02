package com.pijava.agent.harness;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ToolRegistry;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.message.ContentBlock;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** B264 execution gate and the exact legacy Bash raw-argument recovery boundary. */
class B264ToolArgumentsExecutionTest {

    @Test
    void malformedStreamErrorPrecedesSchemaAndNeverExecutes() {
        var tool = new CountingTool("counting", Map.of());
        var outcome = runBoth(tool, new PiLoop.ToolCall(
            "id", "counting", Map.of(), false, "bad-json"));

        assertThat(outcome.isError()).isTrue();
        assertThat(text(outcome)).isEqualTo("Invalid arguments for tool \"counting\": bad-json");
        assertThat(tool.executions).hasValue(0);
    }

    @Test
    void customToolRawPropertyIsOrdinaryLegalInput() {
        var tool = new CountingTool("custom", Map.of(
            "type", "object", "properties", Map.of("_raw", Map.of("type", "string")),
            "required", List.of("_raw")));
        var outcome = runBoth(tool, new PiLoop.ToolCall(
            "id", "custom", Map.of("_raw", "not json"), false));

        assertThat(outcome.isError()).isFalse();
        assertThat(tool.executions).hasValue(1);
        assertThat(tool.prepared.get()).containsEntry("_raw", "not json");
    }

    @Test
    void customRawPropertyDoesNotBypassRequiredSchema() {
        var tool = new CountingTool("custom", Map.of("required", List.of("command")));
        var outcome = runBoth(tool, new PiLoop.ToolCall(
            "id", "custom", Map.of("_raw", "{\"command\":\"echo hi\"}"), false));

        assertThat(outcome.isError()).isTrue();
        assertThat(text(outcome)).isEqualTo("missing required argument \"command\"");
        assertThat(tool.executions).hasValue(0);
    }

    @Test
    void legacyBashSingletonRawUsesTheStrictFinalParser() {
        var tool = new CountingTool("bash", Map.of("required", List.of("command")));
        var outcome = runBoth(tool, new PiLoop.ToolCall(
            "id", "bash", Map.of("_raw", "{\"command\":\"echo hi\"}"), false));

        assertThat(outcome.isError()).isFalse();
        assertThat(tool.executions).hasValue(1);
        assertThat(tool.prepared.get()).containsExactly(Map.entry("command", "echo hi"));
    }

    @Test
    void malformedLegacyBashSingletonRawFailsBeforeExecution() {
        var tool = new CountingTool("bash", Map.of("required", List.of("command")));
        var outcome = runBoth(tool, new PiLoop.ToolCall(
            "id", "bash", Map.of("_raw", "{\"command\":\"echo hi\"} broken"), false));

        assertThat(outcome.isError()).isTrue();
        assertThat(text(outcome)).startsWith("Invalid arguments for tool \"bash\": ")
            .contains("Received arguments:\n{\"command\":\"echo hi\"} broken");
        assertThat(tool.executions).hasValue(0);
    }

    private static PiLoop.ToolOutcome runBoth(CountingTool tool, PiLoop.ToolCall call) {
        var registry = new ToolRegistry(null);
        registry.register(tool);
        var runner = new PiToolRunner("default", registry, null, null, null, null);
        var preparation = runner.prepare(call);
        return preparation instanceof PiLoop.ImmediateOutcome immediate
            ? immediate.outcome()
            : runner.execute((PiLoop.Prepared) preparation, event -> { });
    }

    private static String text(PiLoop.ToolOutcome outcome) {
        return ((ContentBlock.TextContent) outcome.message().content().getFirst()).text();
    }

    private static final class CountingTool implements AgentTool<Map<String, Object>, Void> {
        private final String name;
        private final Map<String, Object> schema;
        private final AtomicInteger executions = new AtomicInteger();
        private final AtomicReference<Map<String, Object>> prepared = new AtomicReference<>();

        private CountingTool(String name, Map<String, Object> schema) {
            this.name = name;
            this.schema = schema;
        }

        @Override public String name() { return name; }
        @Override public String label() { return name; }
        @Override public String description() { return "counting tool"; }
        @Override public Map<String, Object> inputSchema() { return schema; }
        @Override public ExecutionMode executionMode() { return new ExecutionMode.Sequential(); }

        @Override
        public Map<String, Object> prepareArguments(Map<String, Object> raw) {
            prepared.set(raw);
            return raw;
        }

        @Override
        public ToolResult<Void> execute(String id, Map<String, Object> args, AbortSignal signal,
                ToolUpdateCallback<Void> onUpdate, com.pijava.agent.tool.ToolContext context) {
            executions.incrementAndGet();
            return ToolResult.success("ok");
        }
    }
}
