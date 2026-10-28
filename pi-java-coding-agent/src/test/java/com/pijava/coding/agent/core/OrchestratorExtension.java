package com.pijava.coding.agent.core;

import java.util.List;
import java.util.Map;

import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.message.ContentBlock;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.ToolExposure;

/**
 * 批 27（B162/T3+T4 测试夹具）：pi 的 orchestratorExtension
 * （{@code agent-session-tool-orchestration.test.ts:14-53} 对位）——注册
 * {@code echo}（direct）、{@code helper}（codemode）、{@code run_tools}
 * （model-only；执行中经 {@code ctx.executeTool()} 依次调 helper/echo/自身）。
 *
 * <p>顶层 public 类是 ServiceLoader 的硬要求（嵌套类实例化会被
 * IllegalAccess 静默吞）。</p>
 */
public final class OrchestratorExtension implements PiExtension {

    @Override
    public String name() {
        return "orchestrator";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.tools().register(plain("echo"));
        ctx.toolDefinitions().register("echo",
            ToolDefinitions.Entry.of(ToolExposure.DIRECT, true));
        ctx.tools().register(plain("helper"));
        ctx.toolDefinitions().register("helper",
            ToolDefinitions.Entry.of(ToolExposure.CODEMODE, true));
        ctx.tools().register(orchestrator(ctx));
        ctx.toolDefinitions().register("run_tools",
            ToolDefinitions.Entry.of(ToolExposure.MODEL_ONLY, true));
    }

    private static AgentTool<Map<String, Object>, Object> plain(String name) {
        return new AgentTool<>() {
            @Override public String name() {
                return name;
            }

            @Override public String label() {
                return name;
            }

            @Override public String description() {
                return name;
            }

            @Override public Map<String, Object> inputSchema() {
                return Map.of("type", "object");
            }

            @Override public ExecutionMode executionMode() {
                return new ExecutionMode.Parallel();
            }

            @Override public ToolResult<Object> execute(String toolCallId,
                    Map<String, Object> params, AbortSignal signal,
                    ToolUpdateCallback<Object> onUpdate, ToolContext context) {
                return ToolResult.success(name + " ok");
            }
        };
    }

    /** pi 的 run_tools（:36-50 对位）：脚本式依次调 helper/echo/自身。 */
    private static AgentTool<Map<String, Object>, Object> orchestrator(ExtensionContext ctx) {
        return new AgentTool<>() {
            @Override public String name() {
                return "run_tools";
            }

            @Override public String label() {
                return "run_tools";
            }

            @Override public String description() {
                return "runs tools";
            }

            @Override public Map<String, Object> inputSchema() {
                return Map.of("type", "object");
            }

            @Override public ExecutionMode executionMode() {
                return new ExecutionMode.Parallel();
            }

            @Override public ToolResult<Object> execute(String toolCallId,
                    Map<String, Object> params, AbortSignal signal,
                    ToolUpdateCallback<Object> onUpdate, ToolContext context) {
                var lines = new StringBuilder();
                for (var target : List.of("helper", "echo", "run_tools")) {
                    var outcome = ctx.executeTool(toolCallId, signal, target, Map.of(), null);
                    lines.append(target).append(" → ").append(textOf(outcome)).append('\n');
                }
                return ToolResult.success(lines.toString().stripTrailing());
            }

            private static String textOf(
                    com.pijava.agent.harness.PiLoop.ToolOutcome outcome) {
                var text = new StringBuilder();
                for (var block : outcome.result().content()) {
                    if (block instanceof ContentBlock.TextContent t) {
                        text.append(t.text());
                    }
                }
                return text.toString();
            }
        };
    }
}
