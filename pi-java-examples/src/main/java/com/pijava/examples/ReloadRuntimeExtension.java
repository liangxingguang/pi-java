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
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

/**
 * pi {@code examples/extensions/reload-runtime.ts:1-44} 移植（docs/51 §2.3）——
 * 命令 {@code /reload-runtime} 直接调 {@code ctx.reload()}；LLM 可调工具
 * {@code reload_runtime} 只记请求并以 {@code terminate} 结束本轮，
 * {@code agent_settled} 后以 {@code expandPromptTemplates=true} 派发同名命令
 * （工具内不能 reload、运行中也不能 reload，故用这条绕行）。
 */
public final class ReloadRuntimeExtension implements PiExtension {

    /** 工具名（pi {@code registerTool({name: "reload_runtime"})}）。 */
    static final String TOOL_NAME = "reload_runtime";

    /** 工具返回文案（逐字）。 */
    static final String TOOL_TEXT = "Reload requested. Pi reloads after this response.";

    /** pi 的 {@code let reloadRequested = false}。 */
    private volatile boolean reloadRequested;

    @Override
    public String name() {
        return "reload-runtime";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.slashCommands().register(ExampleCommands.command("reload-runtime",
            "Reload extensions, skills, prompts, themes, and context files",
            args -> ctx.reload()));

        ctx.tools().register(new ReloadRuntimeTool(this));

        ctx.on(ExtensionEventSpec.AGENT_SETTLED, (event, c) -> {
            if (!reloadRequested) {
                return null;
            }
            reloadRequested = false;
            // Sent from agent_settled the command runs after every settled handler finishes,
            // while the agent is idle; expandPromptTemplates dispatches it as a command.
            c.sendUserMessage("/reload-runtime",
                new ExtensionContext.SendUserMessageOptions(null, Boolean.TRUE));
            return null;
        });
    }

    /** 工具调用的记录形（pi {@code execute} 只置标志）。 */
    void requestReload() {
        reloadRequested = true;
    }

    /** {@code reload_runtime} 工具本体。 */
    static final class ReloadRuntimeTool implements AgentTool<Map<String, Object>, Map<String, Object>> {

        private final ReloadRuntimeExtension extension;

        ReloadRuntimeTool(ReloadRuntimeExtension extension) {
            this.extension = extension;
        }

        @Override
        public String name() {
            return TOOL_NAME;
        }

        @Override
        public String label() {
            return "Reload Runtime";
        }

        @Override
        public String description() {
            return "Reload extensions, skills, prompts, themes, and context files";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return JsonSchemas.obj("type", "object", "properties", Map.of());
        }

        @Override
        public ExecutionMode executionMode() {
            return new ExecutionMode.Parallel();
        }

        @Override
        public ToolResult<Map<String, Object>> execute(String toolCallId,
                Map<String, Object> params, AbortSignal signal,
                ToolUpdateCallback<Map<String, Object>> onUpdate, ToolContext context) {
            extension.requestReload();
            return new ToolResult<>(
                List.of(new ContentBlock.TextContent(TOOL_TEXT)),
                Map.of(), null, true, List.of());
        }
    }
}
