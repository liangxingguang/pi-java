package com.pijava.examples;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.message.ContentBlock;
import com.pijava.coding.agent.core.slash.SlashCommand;
import com.pijava.coding.agent.core.slash.SlashContext;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

/**
 * pi {@code examples/extensions/dynamic-tools.ts:13-74} 移植（docs/40 P16）——
 * 运行时动态注册工具：session_start 注册 {@code echo_session}；
 * {@code /add-echo-tool <name>} 会话中追加。
 *
 * <p>裁决⑤：命令处理器内经 register 期捕获的 per-extension 上下文调
 * {@code ui()}/{@code tools()}（pi 闭包捕获 {@code pi} 对位）。</p>
 */
public final class DynamicToolsExtension implements PiExtension {

    private static final Pattern VALID_NAME = Pattern.compile("^[a-z0-9_]+$");

    /** pi 的 {@code registeredToolNames}（Set 对位；并发批次下注册安全）。 */
    private final Set<String> registeredToolNames = ConcurrentHashMap.newKeySet();
    /** 裁决⑤：register 期捕获的 per-extension 上下文（pi 闭包的 {@code pi}）。 */
    private volatile ExtensionContext extensionContext;

    @Override
    public String name() {
        return "dynamic-tools";
    }

    @Override
    public void register(ExtensionContext ctx) {
        this.extensionContext = ctx;
        ctx.on(ExtensionEventSpec.SESSION_START, (event, c) -> {
            registerEchoTool("echo_session", "Echo Session", "[session] ");
            ctx.ui().notify("Registered dynamic tool: echo_session", NotifyType.INFO);
            return null;
        });
        ctx.slashCommands().register(new AddEchoToolCommand());
    }

    /** pi {@code normalizeToolName}（trim＋小写＋白名单正则；非法 ⇒ null ≙ undefined）。 */
    static String normalizeToolName(String input) {
        var trimmed = input.strip().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty() || !VALID_NAME.matcher(trimmed).matches()) {
            return null;
        }
        return trimmed;
    }

    /** pi {@code registerEchoTool}：同名去重（false ⇒ 已注册）。 */
    private boolean registerEchoTool(String toolName, String label, String prefix) {
        if (!registeredToolNames.add(toolName)) {
            return false;
        }
        extensionContext.tools().register(new EchoTool(toolName, label, prefix));
        return true;
    }

    /** pi {@code /add-echo-tool} 命令。 */
    private final class AddEchoToolCommand implements SlashCommand {
        @Override
        public String name() {
            return "add-echo-tool";
        }

        @Override
        public String description() {
            return "Register a new echo tool dynamically: /add-echo-tool <tool_name>";
        }

        @Override
        public String argumentHint() {
            return "";
        }

        @Override
        public CompletionStage<String> execute(String args, SlashContext context) {
            var toolName = normalizeToolName(args);
            if (toolName == null) {
                extensionContext.ui().notify(
                    "Usage: /add-echo-tool <tool_name> (lowercase, numbers, underscores)",
                    NotifyType.WARNING);
                return CompletableFuture.completedFuture("");
            }
            var created = registerEchoTool(toolName, "Echo " + toolName, "[" + toolName + "] ");
            if (!created) {
                extensionContext.ui().notify("Tool already registered: " + toolName,
                    NotifyType.WARNING);
                return CompletableFuture.completedFuture("");
            }
            extensionContext.ui().notify("Registered dynamic tool: " + toolName, NotifyType.INFO);
            return CompletableFuture.completedFuture("");
        }
    }

    /** pi 的 echo 工具工厂产物（{@code [prefix]message}＋details {@code {tool, prefix}}）。 */
    static final class EchoTool implements AgentTool<Map<String, Object>, Map<String, Object>> {

        private final String toolName;
        private final String label;
        private final String prefix;

        EchoTool(String toolName, String label, String prefix) {
            this.toolName = toolName;
            this.label = label;
            this.prefix = prefix;
        }

        @Override
        public String name() {
            return toolName;
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public String promptSnippet() {
            return "Echo back user-provided text with " + prefix.trim() + " prefix";
        }

        @Override
        public List<String> promptGuidelines() {
            return List.of("Use echo_session when the user asks for exact echo output.");
        }

        @Override
        public String description() {
            return "Echo a message with prefix: " + prefix;
        }

        @Override
        public Map<String, Object> inputSchema() {
            return JsonSchemas.obj("type", "object",
                "required", List.of("message"),
                "properties", JsonSchemas.obj(
                    "message", JsonSchemas.obj("type", "string",
                        "description", "Message to echo")));
        }

        @Override
        public ExecutionMode executionMode() {
            return new ExecutionMode.Parallel();
        }

        @Override
        public ToolResult<Map<String, Object>> execute(String toolCallId,
                Map<String, Object> params, AbortSignal signal,
                ToolUpdateCallback<Map<String, Object>> onUpdate, ToolContext context) {
            var message = String.valueOf(params.get("message"));
            return new ToolResult<>(
                List.of(new ContentBlock.TextContent(prefix + message)),
                Map.of("tool", toolName, "prefix", prefix), null, false, List.of());
        }
    }
}
