package com.pijava.coding.agent.extension.bridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.pijava.agent.harness.LaneState;
import com.pijava.agent.harness.PiLoop;
import com.pijava.agent.harness.PiToolRunner;
import com.pijava.agent.hook.HookSystem;
import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolRegistry;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.message.ContentBlock;
import com.pijava.coding.agent.extension.ExtensionEventBus;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B264 评审回归：before_tool 的 {@code event.input} 必须可原地改写嵌套容器。
 *
 * <p>pi 的契约是「to modify arguments, mutate {@code event.input} in place」
 * （types.ts:1474-1483）。B264 把 {@code ToolUseContent.arguments()} 改为深不可变快照后，
 * 只重包顶层的 {@link EngineHookBridge} 会让扩展对嵌套数组/对象的改写抛
 * {@code UnsupportedOperationException}，再被转成工具错误、调用不执行。</p>
 */
class EngineHookBridgeNestedArgumentsTest {

    private static final ToolContext CTX = new ToolContext(
        System.getProperty("java.io.tmpdir"), Map.of(),
        new DefaultShellExecutor(), new DefaultFileSystem());
    private static final PiLoop.Sink NO_EMIT = event -> {};

    /** 记录 prepareArguments 入参与执行标记的工具。 */
    private static final class CapturingTool implements AgentTool<String, Void> {
        private final AtomicReference<Map<String, Object>> rawArgs;
        private final AtomicBoolean executed;

        CapturingTool(AtomicReference<Map<String, Object>> rawArgs, AtomicBoolean executed) {
            this.rawArgs = rawArgs;
            this.executed = executed;
        }

        @Override public String name() { return "cap"; }
        @Override public String label() { return "cap"; }
        @Override public String description() { return "capturing tool"; }
        @Override public Map<String, Object> inputSchema() { return Map.of(); }
        @Override public ExecutionMode executionMode() { return new ExecutionMode.Sequential(); }
        @Override public String prepareArguments(Map<String, Object> raw) {
            rawArgs.set(raw);
            return "prepared";
        }
        @Override public ToolResult<Void> execute(String id, String params, AbortSignal signal,
                ToolUpdateCallback<Void> onUpdate, ToolContext ctx) {
            executed.set(true);
            return ToolResult.success("done");
        }
    }

    @Test
    void handlersCanMutateNestedArgumentsInPlace() {
        // 与 ToolUseContent.arguments() 完全同形：顶层与嵌套容器都被封死。
        var sealed = new ContentBlock.ToolUseContent("tc1", "cap",
            Map.of("edits", new ArrayList<>(List.of("first"))), null).arguments();

        var bus = new ExtensionEventBus();
        bus.on("ext", ExtensionEventSpec.TOOL_CALL, (event, ctx) -> {
            @SuppressWarnings("unchecked") // JSON 数组在参数表里就是 List<Object>。
            var edits = (List<Object>) event.input().get("edits");
            edits.add("second");
            return null;
        });
        var hooks = new HookSystem(new LaneState());
        EngineHookBridge.install(hooks, "default", bus, null);

        var captured = new AtomicReference<Map<String, Object>>();
        var executed = new AtomicBoolean();
        var registry = new ToolRegistry(null);
        registry.register(new CapturingTool(captured, executed));
        var runner = new PiToolRunner("default", registry, hooks, CTX, null, null);

        var preparation = runner.prepare(new PiLoop.ToolCall("tc1", "cap", sealed, false));
        if (preparation instanceof PiLoop.Prepared prepared) {
            runner.execute(prepared, NO_EMIT);
        }

        assertThat(executed).isTrue();
        assertThat(captured.get()).isNotNull();
        var edits = (List<?>) captured.get().get("edits");
        assertThat(edits).hasSize(2);
        assertThat(edits.get(0)).isEqualTo("first");
        assertThat(edits.get(1)).isEqualTo("second");
    }
}
