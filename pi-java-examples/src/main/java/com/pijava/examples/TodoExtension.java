package com.pijava.examples;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.message.ContentBlock;
import com.pijava.coding.agent.extension.ExtensionComponent;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.ToolCallRenderer;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

/**
 * pi {@code examples/extensions/todo.ts:1-304} 移植（docs/52 §2.2）——
 * {@code todo} 工具管理待办列表，状态随每次结果的 {@code details} 进入会话；
 * session_start/session_tree 从 getBranch 的 todo 工具结果重建。
 * renderResult default 臂把错误文本按文本级渲染（D92/主题窄差）。
 */
public final class TodoExtension implements PiExtension {

    @Override
    public String name() {
        return "todo";
    }

    @Override
    public void register(ExtensionContext ctx) {
        var state = new TodoState();
        ctx.tools().register(new TodoTool(state));
        ctx.registerToolRenderer((toolName, next) -> "todo".equals(toolName)
            ? new TextToolRenderer() : null);
        ctx.slashCommands().register(ExampleCommands.command("todos",
            "Show all todos on the current branch", args -> {
                if (!"tui".equals(ctx.mode())) {
                    ctx.ui().notify("/todos requires interactive mode", NotifyType.ERROR);
                    return;
                }
                rebuild(ctx, state);
                ctx.ui().custom((com.pijava.coding.agent.extension.ExtensionUiHostEnv env,
                                 com.pijava.coding.agent.core.KeybindingsManager kb,
                                 java.util.function.Consumer<String> done) -> {
                    done.accept(null);
                    return new TodoComponent(state);
                }, null);
            }));
        ctx.on(ExtensionEventSpec.SESSION_START, (event, c) -> {
            rebuild(c, state);
            return null;
        });
        ctx.on(ExtensionEventSpec.SESSION_TREE, (event, c) -> {
            rebuild(c, state);
            return null;
        });
    }

    /** 从分支的 todo 工具结果重建状态（pi L114-129）。 */
    static void rebuild(ExtensionContext ctx, TodoState state) {
        state.todos.clear();
        state.nextId = 1;
        for (var entry : ctx.sessionManager().getBranch()) {
            if (!(entry instanceof Entry.Message m)
                    || !(m.message() instanceof com.pijava.ai.message.Message.ToolResultMessage t)
                    || !"todo".equals(t.toolName())) {
                continue;
            }
            TodoState.applyDetails(state, t.details());
        }
    }

    /** 可变待办项（pi 用可变类，markCompletedSteps 需原地改）。 */
    static final class TodoItem {
        final int id;
        final String text;
        boolean done;

        TodoItem(int id, String text) {
            this.id = id;
            this.text = text;
        }
    }

    /** 待办状态机（纯逻辑）。 */
    static final class TodoState {
        final List<TodoItem> todos = new ArrayList<>();
        int nextId = 1;

        /** 执行一个 action，返回 (content 文本, details)。pi L150-216。 */
        Map<String, Object> execute(String action, String text, String id) {
            var details = new LinkedHashMap<String, Object>();
            String content;
            switch (action == null ? "" : action) {
                case "list" -> {
                    content = listText();
                    details.put("action", "list");
                    details.put("todos", snapshot());
                    details.put("nextId", nextId);
                }
                case "add" -> {
                    if (text == null || text.isBlank()) {
                        content = "Error: text required for add";
                        details.put("error", "text required");
                    } else {
                        var item = new TodoItem(nextId++, text);
                        todos.add(item);
                        content = "Added todo #" + item.id + ": " + item.text;
                    }
                    details.put("action", "add");
                    details.put("todos", snapshot());
                    details.put("nextId", nextId);
                }
                case "toggle" -> {
                    if (id == null || id.isBlank()) {
                        content = "Error: id required for toggle";
                        details.put("error", "id required");
                    } else {
                        var item = find(id);
                        if (item == null) {
                            content = "Todo #" + id + " not found";
                            details.put("error", "#" + id + " not found");
                        } else {
                            item.done = !item.done;
                            content = "Todo #" + item.id + " " + (item.done ? "completed" : "uncompleted");
                        }
                    }
                    details.put("action", "toggle");
                    details.put("todos", snapshot());
                    details.put("nextId", nextId);
                }
                case "clear" -> {
                    var count = todos.size();
                    todos.clear();
                    nextId = 1;
                    content = "Cleared " + count + " todos";
                    details.put("action", "clear");
                    details.put("todos", snapshot());
                    details.put("nextId", nextId);
                }
                default -> {
                    content = "Unknown action: " + action;
                    details.put("error", "unknown action: " + action);
                    details.put("action", action);
                }
            }
            details.put("content", content);
            return details;
        }

        private String listText() {
            if (todos.isEmpty()) {
                return "No todos";
            }
            var lines = new ArrayList<String>();
            for (var t : todos) {
                lines.add("[" + (t.done ? "x" : " ") + "] #" + t.id + ": " + t.text);
            }
            return String.join("\n", lines);
        }

        private TodoItem find(String id) {
            for (var t : todos) {
                if (String.valueOf(t.id).equals(id)) {
                    return t;
                }
            }
            return null;
        }

        private List<Map<String, Object>> snapshot() {
            var out = new ArrayList<Map<String, Object>>();
            for (var t : todos) {
                out.add(Map.of("step", t.id, "text", t.text, "completed", t.done));
            }
            return out;
        }

        /** 从 details 宽转恢复（pi reconstructState）。 */
        @SuppressWarnings("unchecked")
        static void applyDetails(TodoState state, Object details) {
            if (!(details instanceof Map<?, ?> m)) {
                return;
            }
            if (m.get("nextId") instanceof Number n) {
                state.nextId = n.intValue();
            }
            if (m.get("todos") instanceof List<?> list) {
                state.todos.clear();
                for (var item : list) {
                    if (item instanceof Map<?, ?> im
                            && im.get("text") instanceof String text) {
                        var todo = new TodoItem(
                            im.get("step") instanceof Number s ? s.intValue() : state.todos.size() + 1,
                            text);
                        todo.done = Boolean.TRUE.equals(im.get("completed"));
                        state.todos.add(todo);
                    }
                }
            }
        }
    }

    /** todo 工具本体。 */
    static final class TodoTool implements AgentTool<Map<String, Object>, Map<String, Object>> {
        private final TodoState state;

        TodoTool(TodoState state) {
            this.state = state;
        }

        @Override public String name() { return "todo"; }
        @Override public String label() { return "Todo"; }
        @Override public String description() {
            return "Manage a todo list. Actions: list, add (text), toggle (id), clear";
        }
        @Override public Map<String, Object> inputSchema() {
            return JsonSchemas.obj("type", "object", "properties", JsonSchemas.obj(
                "action", JsonSchemas.obj("type", "string", "enum",
                    List.of("list", "add", "toggle", "clear")),
                "text", JsonSchemas.obj("type", "string"),
                "id", JsonSchemas.obj("type", "string")));
        }
        @Override public ExecutionMode executionMode() { return new ExecutionMode.Parallel(); }

        @Override
        public ToolResult<Map<String, Object>> execute(String toolCallId,
                Map<String, Object> params, AbortSignal signal,
                ToolUpdateCallback<Map<String, Object>> onUpdate, ToolContext context) {
            var action = params.get("action") instanceof String s ? s : null;
            var text = params.get("text") instanceof String s ? s : null;
            var id = params.get("id") instanceof String s ? s : null;
            var details = state.execute(action, text, id);
            var content = String.valueOf(details.get("content"));
            return new ToolResult<>(List.of(new ContentBlock.TextContent(content)),
                details, null, false, List.of());
        }
    }

    /** 文本级渲染器（D92）：default 臂按 error 文本渲染，空内容空表。 */
    static final class TextToolRenderer implements ToolCallRenderer {
        @Override
        public List<String> renderCall(String toolName, Map<String, Object> args, boolean expanded) {
            return List.of("todo " + args);
        }

        @Override
        public List<String> renderResult(String toolName, String textOutput, boolean isError,
                                         boolean expanded) {
            if (textOutput == null || textOutput.isEmpty()) {
                return List.of();
            }
            return List.of((isError ? "Error: " : "✓ ") + textOutput);
        }
    }

    /** 占位组件（todos 命令）。 */
    static final class TodoComponent implements ExtensionComponent {
        private final TodoState state;

        TodoComponent(TodoState state) {
            this.state = state;
        }

        @Override
        public List<String> render(int width) {
            var lines = new ArrayList<String>();
            lines.add(" Todos ");
            if (state.todos.isEmpty()) {
                lines.add("No todos yet. Ask the agent to add some!");
            } else {
                for (var t : state.todos) {
                    lines.add((t.done ? "☑ " : "☐ ") + "#" + t.id + " " + t.text);
                }
            }
            return lines;
        }

        @Override
        public void handleInput(com.pijava.coding.agent.core.KeybindingsManager.KeyStroke key) {
        }
    }
}
