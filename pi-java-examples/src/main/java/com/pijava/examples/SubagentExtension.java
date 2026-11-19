package com.pijava.examples;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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
 * pi {@code examples/extensions/subagent/index.ts} 移植（docs/54 §2.2）——
 * single/parallel/chain 三模式编排。子进程用注入式 {@link SubagentRunner} 返回假结果
 * （D244：真 spawn 不移植），默认 runner 抛 UOE。
 */
public final class SubagentExtension implements PiExtension {

    static final int MAX_PARALLEL_TASKS = 8;
    static final int MAX_CONCURRENCY = 4;
    static final int COLLAPSED_ITEM_COUNT = 10;
    static final int PER_TASK_OUTPUT_CAP = 50 * 1024;

    record TaskResult(String output, String stopReason, String error) {
        boolean failed() { return error != null || (stopReason != null && !"STOP".equals(stopReason)); }
    }

    /** 注入式子代理执行器；默认抛 UOE（真子进程不移植，D244）。 */
    @FunctionalInterface
    interface SubagentRunner {
        TaskResult run(String agent, String task, String cwd);
    }

    private SubagentRunner runner = (agent, task, cwd) -> {
        throw new UnsupportedOperationException("subagent process execution requires a runner");
    };
    private List<SubagentAgents.AgentConfig> availableAgents = List.of();

    @Override
    public String name() {
        return "subagent";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.tools().register(new SubagentTool(this, ctx));
    }

    void runner(SubagentRunner runner) {
        this.runner = runner;
    }

    void availableAgents(List<SubagentAgents.AgentConfig> agents) {
        this.availableAgents = agents;
    }

    /** description 逐字（pi L39-42，join 空格）。 */
    static String description(String available) {
        var list = available == null || available.isEmpty() ? "none" : available;
        return String.join(" ",
            "Delegate tasks to specialized subagents with isolated context.",
            "Modes: single (agent + task), parallel (tasks array), chain (sequential with {previous} placeholder).",
            "Agents are defined in agent markdown files (name/description/tools in frontmatter).",
            "User agents are under " + "<agentDir>/agents (scope: user).".replace("<agentDir>", "${getAgentDir()}"),
            "Project agents are under " + "<config>/agents (scope: project or both).".replace("<config>", "${CONFIG_DIR_NAME}"));
    }

    /** 模式互斥与参数校验（pi L66-100）。 */
    static String validateMode(boolean single, boolean parallel, boolean chain, String available) {
        int count = (single ? 1 : 0) + (parallel ? 1 : 0) + (chain ? 1 : 0);
        if (count != 1) {
            return "Invalid parameters. Provide exactly one mode.\nAvailable agents: "
                + (available == null || available.isEmpty() ? "none" : available);
        }
        return null;
    }

    /** chain：逐步替换 `{previous}`；失败停止（pi L119-140）。 */
    String runChain(List<Map<String, Object>> steps, String defaultCwd) {
        var previous = "";
        for (int i = 0; i < steps.size(); i++) {
            var step = steps.get(i);
            var agent = str(step.get("agent"));
            var task = str(step.get("task"));
            var cwd = step.get("cwd") instanceof String s ? s : defaultCwd;
            var resolved = task.replace("{previous}", previous);
            var result = runner.run(agent, resolved, cwd);
            if (result.failed()) {
                var reason = result.error() != null ? result.error() : result.stopReason();
                return "Chain stopped at step " + (i + 1) + " (" + agent + "): " + reason;
            }
            previous = result.output() == null || result.output().isEmpty()
                ? "(no output)" : result.output();
        }
        return previous.isEmpty() ? "(no output)" : previous;
    }

    /** parallel：并发上限、状态流、汇总（pi L142-192）。 */
    String runParallel(List<Map<String, Object>> tasks, String defaultCwd) {
        if (tasks.size() > MAX_PARALLEL_TASKS) {
            return "Too many parallel tasks (" + tasks.size() + "). Max is " + MAX_PARALLEL_TASKS + ".";
        }
        var results = mapWithConcurrencyLimit(tasks, MAX_CONCURRENCY, step -> {
            var agent = str(step.get("agent"));
            var task = str(step.get("task"));
            var cwd = step.get("cwd") instanceof String s ? s : defaultCwd;
            return runner.run(agent, task, cwd);
        });
        int succeeded = 0;
        var summaries = new ArrayList<String>();
        for (int i = 0; i < tasks.size(); i++) {
            var agent = str(tasks.get(i).get("agent"));
            var r = results.get(i);
            if (r.failed()) {
                var status = r.error() != null ? "failed"
                    : "failed (" + r.stopReason() + ")";
                summaries.add("### [" + agent + "] " + status + "\n\n" + truncate(r.output()));
            } else {
                succeeded++;
                summaries.add("### [" + agent + "] completed\n\n" + truncate(r.output()));
            }
        }
        return "Parallel: " + succeeded + "/" + tasks.size() + " succeeded\n\n"
            + String.join("\n\n---\n\n", summaries);
    }

    /** single：成功/失败文案（pi L194-211）。 */
    String runSingle(String agent, String task, String cwd) {
        var r = runner.run(agent, task, cwd);
        if (r.failed()) {
            var reason = r.stopReason() != null ? r.stopReason() : "failed";
            return "Agent " + reason + ": " + (r.error() == null ? "" : r.error());
        }
        return r.output() == null || r.output().isEmpty() ? "(no output)" : r.output();
    }

    /** 简单有界并发执行（保序收集结果）。 */
    static <T, R> List<R> mapWithConcurrencyLimit(List<T> items, int limit,
            java.util.function.Function<T, R> fn) {
        var results = new java.util.ArrayList<R>(java.util.Collections.nCopies(items.size(), null));
        var index = new AtomicInteger();
        var threads = new ArrayList<Thread>();
        int n = Math.min(limit, items.size());
        for (int k = 0; k < n; k++) {
            var t = new Thread(() -> {
                int i;
                while ((i = index.getAndIncrement()) < items.size()) {
                    results.set(i, fn.apply(items.get(i)));
                }
            });
            threads.add(t);
            t.start();
        }
        for (var t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return results;
    }

    static String truncate(String output) {
        if (output == null) {
            return "";
        }
        return output.length() > PER_TASK_OUTPUT_CAP
            ? output.substring(0, PER_TASK_OUTPUT_CAP) + "\n...[truncated]" : output;
    }

    static String str(Object o) {
        return o instanceof String s ? s : "";
    }

    static final class SubagentTool implements AgentTool<Map<String, Object>, Map<String, Object>> {
        private final SubagentExtension ext;
        private final ExtensionContext ctx;

        SubagentTool(SubagentExtension ext, ExtensionContext ctx) {
            this.ext = ext;
            this.ctx = ctx;
        }

        @Override public String name() { return "subagent"; }
        @Override public String label() { return "Subagent"; }
        @Override public String description() {
            return SubagentExtension.description(SubagentAgents.formatAgentList(ext.availableAgents));
        }
        @Override public Map<String, Object> inputSchema() {
            return JsonSchemas.obj("type", "object", "properties", JsonSchemas.obj(
                "agent", JsonSchemas.obj("type", "string"),
                "task", JsonSchemas.obj("type", "string"),
                "tasks", JsonSchemas.obj("type", "array"),
                "steps", JsonSchemas.obj("type", "array")));
        }
        @Override public ExecutionMode executionMode() { return new ExecutionMode.Parallel(); }

        @Override
        public ToolResult<Map<String, Object>> execute(String toolCallId,
                Map<String, Object> params, AbortSignal signal,
                ToolUpdateCallback<Map<String, Object>> onUpdate, ToolContext context) {
            boolean single = params.containsKey("agent") && params.containsKey("task");
            boolean parallel = params.containsKey("tasks");
            boolean chain = params.containsKey("steps");
            var invalid = validateMode(single, parallel, chain,
                SubagentAgents.formatAgentList(ext.availableAgents));
            if (invalid != null) {
                return error(invalid);
            }
            var cwd = params.get("cwd") instanceof String s ? s : ctx.cwd();
            String output;
            if (chain) {
                output = ext.runChain(castList(params.get("steps")), cwd);
            } else if (parallel) {
                output = ext.runParallel(castList(params.get("tasks")), cwd);
            } else {
                output = ext.runSingle(str(params.get("agent")), str(params.get("task")), cwd);
            }
            return new ToolResult<>(List.of(new ContentBlock.TextContent(output)),
                Map.of("output", output), null, false, List.of());
        }

        private static ToolResult<Map<String, Object>> error(String msg) {
            return new ToolResult<>(List.of(new ContentBlock.TextContent(msg)),
                Map.of("error", true), null, false, List.of(), true, null);
        }

        @SuppressWarnings("unchecked")
        private static List<Map<String, Object>> castList(Object o) {
            return o instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        }
    }
}
