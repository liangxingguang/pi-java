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
 * pi {@code examples/extensions/hello.ts:1-26} 移植（docs/40 P1）——
 * 最小工具注册示例：{@code hello} 工具回 {@code Hello, <name>!}＋details {@code {greeted}}。
 */
public final class HelloExtension implements PiExtension {

    @Override
    public String name() {
        return "hello";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.tools().register(new HelloTool());
    }

    /** hello 工具本体（pi 的 defineTool 字面量对位）。 */
    static final class HelloTool implements AgentTool<Map<String, Object>, Map<String, Object>> {

        @Override
        public String name() {
            return "hello";
        }

        @Override
        public String label() {
            return "Hello";
        }

        @Override
        public String description() {
            return "A simple greeting tool";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return JsonSchemas.obj("type", "object",
                "required", List.of("name"),
                "properties", JsonSchemas.obj(
                    "name", JsonSchemas.obj("type", "string",
                        "description", "Name to greet")));
        }

        @Override
        public ExecutionMode executionMode() {
            return new ExecutionMode.Parallel();
        }

        @Override
        public ToolResult<Map<String, Object>> execute(String toolCallId,
                Map<String, Object> params, AbortSignal signal,
                ToolUpdateCallback<Map<String, Object>> onUpdate, ToolContext context) {
            var raw = params.get("name");
            // JS `${undefined}` ⇒ "undefined"（schema 外调用的逐字臂）
            var name = raw == null ? "undefined" : String.valueOf(raw);
            return new ToolResult<>(
                List.of(new ContentBlock.TextContent("Hello, " + name + "!")),
                Map.of("greeted", name), null, false, List.of());
        }
    }
}
