package com.pijava.examples;

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

/**
 * pi {@code examples/extensions/shutdown-command.ts:1-64} 移植（docs/55 §2.5）——
 * /quit 命令与两个退出工具，均经 ctx.shutdown()（B284 已闭环，reason "quit"）。
 */
public final class ShutdownCommandExtension implements PiExtension {

    @Override
    public String name() {
        return "shutdown-command";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.slashCommands().register(ExampleCommands.command("quit", "Exit pi cleanly",
            args -> ctx.shutdown()));
        ctx.tools().register(new FinishTool(ctx));
        ctx.tools().register(new DeployTool(ctx));
    }

    static final class FinishTool implements AgentTool<Map<String, Object>, Map<String, Object>> {
        private final ExtensionContext ctx;

        FinishTool(ExtensionContext ctx) {
            this.ctx = ctx;
        }

        @Override public String name() { return "finish_and_exit"; }
        @Override public String label() { return "Finish and Exit"; }
        @Override public String description() { return "Complete a task and exit pi"; }
        @Override public Map<String, Object> inputSchema() {
            return JsonSchemas.obj("type", "object", "properties", Map.of());
        }
        @Override public ExecutionMode executionMode() { return new ExecutionMode.Parallel(); }

        @Override
        public ToolResult<Map<String, Object>> execute(String toolCallId,
                Map<String, Object> params, AbortSignal signal,
                ToolUpdateCallback<Map<String, Object>> onUpdate, ToolContext context) {
            ctx.shutdown();
            return new ToolResult<>(List.of(new ContentBlock.TextContent(
                "Shutdown requested. Exiting after this response.")),
                Map.of(), null, false, List.of());
        }
    }

    static final class DeployTool implements AgentTool<Map<String, Object>, Map<String, Object>> {
        private final ExtensionContext ctx;

        DeployTool(ExtensionContext ctx) {
            this.ctx = ctx;
        }

        @Override public String name() { return "deploy_and_exit"; }
        @Override public String label() { return "Deploy and Exit"; }
        @Override public String description() { return "Deploy the application and exit pi"; }
        @Override public Map<String, Object> inputSchema() {
            return JsonSchemas.obj("type", "object", "properties", JsonSchemas.obj(
                "environment", JsonSchemas.obj("type", "string",
                    "description", "Target environment (e.g., production, staging)")));
        }
        @Override public ExecutionMode executionMode() { return new ExecutionMode.Parallel(); }

        @Override
        @SuppressWarnings("unchecked")
        public ToolResult<Map<String, Object>> execute(String toolCallId,
                Map<String, Object> params, AbortSignal signal,
                ToolUpdateCallback<Map<String, Object>> onUpdate, ToolContext context) {
            var env = params.get("environment") instanceof String s ? s : "";
            if (onUpdate != null) {
                onUpdate.onUpdate(new ToolResult<>(List.of(new ContentBlock.TextContent(
                    "Deploying to " + env + "...")), Map.of(), null, false, List.of()));
            }
            // 部署逻辑为 pi 注释示例（pi.exec("npm",["run","deploy",env])），不执行
            if (onUpdate != null) {
                onUpdate.onUpdate(new ToolResult<>(List.of(new ContentBlock.TextContent(
                    "Deployment complete, exiting...")), Map.of(), null, false, List.of()));
            }
            ctx.shutdown();
            return new ToolResult<>(List.of(new ContentBlock.TextContent(
                "Done! Shutdown requested.")), Map.of("environment", env),
                null, false, List.of());
        }
    }
}
