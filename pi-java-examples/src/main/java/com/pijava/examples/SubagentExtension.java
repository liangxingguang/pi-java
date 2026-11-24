package com.pijava.examples;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.agent.tool.TruncationUtils;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.message.ContentBlock;
import com.pijava.coding.agent.core.FileSettingsStorage;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;
import com.pijava.coding.agent.extension.event.ExtensionResults.ToolResultEventResult;
import com.pijava.coding.agent.pkg.SourceResolver;

/**
 * pi {@code examples/extensions/subagent/index.ts} 移植（docs/54 §2.2，docs/30）——
 * single/parallel/chain 三模式编排、状态机、启动前中止、失败证据与两种截断。
 *
 * <p>窄差 D244：子进程 spawn／临时 prompt／SIGTERM→SIGKILL／JSON 流由注入式
 * {@link SubagentRunner} 替代，默认 runner 抛 UOE；进程字段由 runner 结果提供。
 * TUI {@code renderCall}/{@code renderResult} 不移植（D235 同族）。</p>
 */
public final class SubagentExtension implements PiExtension {

    static final int MAX_PARALLEL_TASKS = 8;
    static final int MAX_CONCURRENCY = 4;
    static final int PER_TASK_OUTPUT_CAP = 50 * 1024;
    static final int TRUNCATION_NOTICE_RESERVE = 256;

    /** pi {@code ChildStatus}。 */
    enum ChildStatus {
        PENDING, RUNNING, COMPLETED, FAILED, ABORTED;

        String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** pi {@code SingleResult} 的窄化投影（messages 收敛为最终 output）。 */
    record SingleResult(String agent, String agentSource, String task, ChildStatus status,
                        Integer exitCode, String output, String stderr, String stopReason,
                        String errorMessage, String terminationSignal, Integer step,
                        String model) {

        boolean isFailed() {
            return status == ChildStatus.FAILED || status == ChildStatus.ABORTED;
        }
    }

    /** 注入式子代理执行器；默认抛 UOE（真子进程不移植，D244）。 */
    @FunctionalInterface
    interface SubagentRunner {
        SingleResult run(String agent, String task, String cwd, AbortSignal signal);
    }

    /** 一次模式执行的模型可见文本、逐条结果与强制错误位。 */
    record ModeOutcome(String text, List<SingleResult> results, boolean forceError) {}

    private record ResolvedAgent(SubagentAgents.AgentConfig agent,
                                 List<SubagentAgents.AgentConfig> available, String cwd) {}

    private SubagentRunner runner = (agent, task, cwd, signal) -> {
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
        // 工具 execute 结果无法直接置 runtime error，经 result 钩子补 isError
        ctx.on(ExtensionEventSpec.TOOL_RESULT, (event, c) -> {
            if (!"subagent".equals(event.toolName())) {
                return null;
            }
            if (event.details() instanceof Map<?, ?> details
                    && Boolean.TRUE.equals(details.get("isError"))) {
                return new ToolResultEventResult(null, null, null, Boolean.TRUE, null);
            }
            return null;
        });
    }

    void runner(SubagentRunner runner) {
        this.runner = runner;
    }

    void availableAgents(List<SubagentAgents.AgentConfig> agents) {
        this.availableAgents = agents;
    }

    /** description 逐字（pi 四句 join 空格）。 */
    static String toolDescription() {
        var userAgents = Path.of(FileSettingsStorage.defaultAgentDir().toString(), "agents");
        return String.join(" ",
            "Delegate tasks to specialized subagents with isolated context.",
            "Modes: single (agent + task), parallel (tasks array), chain (sequential with {previous} placeholder).",
            "Default agent scope is \"user\" (from " + userAgents + ").",
            "To enable project-local agents in " + SourceResolver.CONFIG_DIR_NAME
                + "/agents, set agentScope: \"both\" (or \"project\").");
    }

    /** 模式互斥校验（pi 的 modeCount 判定）。 */
    static String validateMode(boolean single, boolean parallel, boolean chain, String available) {
        int count = (single ? 1 : 0) + (parallel ? 1 : 0) + (chain ? 1 : 0);
        if (count != 1) {
            return "Invalid parameters. Provide exactly one mode.\nAvailable agents: "
                + (available == null || available.isEmpty() ? "none" : available);
        }
        return null;
    }

    // ── 编排 ────────────────────────────────────────────────────────────

    /** chain：逐步替换 {@code {previous}}；失败即停（pi L706-765）。 */
    ModeOutcome runChain(List<Map<String, Object>> steps, String defaultCwd, AbortSignal signal) {
        var results = new ArrayList<SingleResult>();
        var previous = "";
        for (int i = 0; i < steps.size(); i++) {
            var step = steps.get(i);
            var agentName = str(step.get("agent"));
            var task = str(step.get("task")).replace("{previous}", previous);
            var resolved = resolveAgent(defaultCwd, agentName, step.get("cwd"));
            var result = runSingleAgent(resolved, agentName, task, i + 1, signal);
            results.add(result);
            if (result.isFailed()) {
                return new ModeOutcome(truncateModelOutput("Chain stopped at step " + (i + 1)
                    + " (" + agentName + "): " + resultOutput(result)), results, false);
            }
            previous = result.output() == null ? "" : result.output();
        }
        var last = results.isEmpty() ? "" : results.get(results.size() - 1).output();
        return new ModeOutcome(truncateModelOutput(orNoOutput(last)), results, false);
    }

    /** parallel：上限 8、并发 4、逐任务 50 KiB 预算（pi L767-853）。 */
    ModeOutcome runParallel(List<Map<String, Object>> tasks, String defaultCwd, AbortSignal signal) {
        if (tasks.size() > MAX_PARALLEL_TASKS) {
            return new ModeOutcome("Too many parallel tasks (" + tasks.size() + "). Max is "
                + MAX_PARALLEL_TASKS + ".", List.of(), true);
        }
        var resolved = new ArrayList<ResolvedAgent>(tasks.size());
        for (var task : tasks) {
            resolved.add(resolveAgent(defaultCwd, str(task.get("agent")), task.get("cwd")));
        }
        var indices = java.util.stream.IntStream.range(0, tasks.size()).boxed().toList();
        var results = mapWithConcurrencyLimit(indices, MAX_CONCURRENCY, index -> {
            var task = tasks.get(index);
            return runSingleAgent(resolved.get(index), str(task.get("agent")),
                str(task.get("task")), null, signal);
        });
        int succeeded = 0;
        var summaries = new ArrayList<String>();
        for (var result : results) {
            if (!result.isFailed()) {
                succeeded++;
            }
            var label = result.isFailed() && result.stopReason() != null
                && !"end".equals(result.stopReason())
                ? result.status().label() + " (" + result.stopReason() + ")"
                : result.status().label();
            summaries.add("### [" + result.agent() + "] " + label + "\n\n"
                + truncateParallelOutput(resultOutput(result)));
        }
        return new ModeOutcome("Parallel: " + succeeded + "/" + results.size()
            + " succeeded\n\n" + String.join("\n\n---\n\n", summaries), results, false);
    }

    /** single：成功/失败文案（pi L855-888）。 */
    ModeOutcome runSingleMode(String agent, String task, String defaultCwd, String cwd,
            AbortSignal signal) {
        var resolved = resolveAgent(defaultCwd, agent, cwd);
        var result = runSingleAgent(resolved, agent, task, null, signal);
        if (result.isFailed()) {
            var label = result.stopReason() != null ? result.stopReason() : result.status().label();
            return new ModeOutcome(truncateModelOutput("Agent " + label + ": "
                + resultOutput(result)), List.of(result), false);
        }
        return new ModeOutcome(truncateModelOutput(orNoOutput(result.output())),
            List.of(result), false);
    }

    private SingleResult runSingleAgent(ResolvedAgent resolved, String agentName, String task,
            Integer step, AbortSignal signal) {
        if (signal != null && signal.isAborted()) {
            return unstarted(agentName, task, step, ChildStatus.ABORTED,
                "Subagent was aborted before starting");
        }
        if (resolved.agent() == null) {
            var available = resolved.available().isEmpty() ? "none"
                : resolved.available().stream().map(a -> "\"" + a.name() + "\"")
                    .collect(Collectors.joining(", "));
            return unstarted(agentName, task, step, ChildStatus.FAILED,
                "Unknown agent: \"" + agentName + "\". Available agents: " + available + ".");
        }
        var agent = resolved.agent();
        var ran = runner.run(agent.name(), task, resolved.cwd(), signal);
        return new SingleResult(agentName, agent.source(), task, ran.status(), ran.exitCode(),
            ran.output(), ran.stderr(), ran.stopReason(), ran.errorMessage(),
            ran.terminationSignal(), step,
            ran.model() != null ? ran.model() : agent.model());
    }

    private static SingleResult unstarted(String agent, String task, Integer step,
            ChildStatus status, String errorMessage) {
        return new SingleResult(agent, "unknown", task, status, null, null, "", null,
            errorMessage, null, step, null);
    }

    private ResolvedAgent resolveAgent(String defaultCwd, String name, Object cwd) {
        var base = defaultCwd == null ? "." : defaultCwd;
        var effective = Path.of(base).resolve(cwd instanceof String s ? s : ".").normalize();
        var found = availableAgents.stream().filter(a -> a.name().equals(name)).findFirst()
            .orElse(null);
        return new ResolvedAgent(found, availableAgents, effective.toString());
    }

    // ── 失败证据与截断 ──────────────────────────────────────────────────

    /** pi {@code getFailureEvidence}：Error／Signal／stderr／Assistant output 去空去重。 */
    static String failureEvidence(SingleResult result) {
        var evidence = new ArrayList<String>();
        var seen = new LinkedHashSet<String>();
        addEvidence(evidence, seen, "Error", result.errorMessage());
        addEvidence(evidence, seen, "Signal", result.terminationSignal());
        addEvidence(evidence, seen, "stderr", result.stderr());
        addEvidence(evidence, seen, "Assistant output", result.output());
        return evidence.isEmpty() ? "(no output)" : String.join("\n\n", evidence);
    }

    private static void addEvidence(List<String> evidence, java.util.Set<String> seen,
            String label, String value) {
        if (value == null || value.strip().isEmpty() || !seen.add(value.strip())) {
            return;
        }
        evidence.add(label + ": " + value.strip());
    }

    /** pi {@code getResultOutput}。 */
    static String resultOutput(SingleResult result) {
        return result.isFailed() ? failureEvidence(result) : orNoOutput(result.output());
    }

    /** pi {@code truncateParallelOutput}：逐任务 50 KiB 字节预算。 */
    static String truncateParallelOutput(String output) {
        int totalBytes = output.getBytes(StandardCharsets.UTF_8).length;
        if (totalBytes <= PER_TASK_OUTPUT_CAP) {
            return output;
        }
        var truncated = TruncationUtils.sliceHead(output, PER_TASK_OUTPUT_CAP);
        while (truncated.getBytes(StandardCharsets.UTF_8).length > PER_TASK_OUTPUT_CAP) {
            truncated = truncated.substring(0, truncated.length() - 1);
        }
        int omitted = totalBytes - truncated.getBytes(StandardCharsets.UTF_8).length;
        return truncated + "\n\n[Output truncated: " + omitted
            + " bytes omitted. Full output preserved in tool details.]";
    }

    /** pi {@code truncateModelOutput}：truncateHead 双限＋notice 预留。 */
    static String truncateModelOutput(String output) {
        var initial = TruncationUtils.truncateHead(output);
        if (!initial.truncated()) {
            return output;
        }
        var body = TruncationUtils.truncateHead(output,
            TruncationUtils.DEFAULT_MAX_LINES - 2,
            TruncationUtils.DEFAULT_MAX_BYTES - TRUNCATION_NOTICE_RESERVE);
        int omitted = initial.totalBytes() - body.outputBytes();
        var notice = "[Output truncated: " + omitted
            + " bytes omitted. Full output preserved in tool details.]";
        return body.content().isEmpty() ? notice : body.content() + "\n\n" + notice;
    }

    // ── 支撑 ────────────────────────────────────────────────────────────

    static String availableNames(List<SubagentAgents.AgentConfig> agents) {
        if (agents.isEmpty()) {
            return "none";
        }
        return agents.stream().map(a -> a.name() + " (" + a.source() + ")")
            .collect(Collectors.joining(", "));
    }

    static Map<String, Object> details(String mode, List<SingleResult> results,
            boolean forceError) {
        var list = new ArrayList<Map<String, Object>>(results.size());
        for (var result : results) {
            var one = new LinkedHashMap<String, Object>();
            one.put("agent", result.agent());
            one.put("agentSource", result.agentSource());
            one.put("task", result.task());
            one.put("status", result.status().label());
            one.put("exitCode", result.exitCode());
            one.put("output", result.output());
            one.put("stderr", result.stderr());
            one.put("stopReason", result.stopReason());
            one.put("errorMessage", result.errorMessage());
            one.put("terminationSignal", result.terminationSignal());
            one.put("step", result.step());
            one.put("model", result.model());
            list.add(one);
        }
        boolean isError = forceError || results.stream().anyMatch(SingleResult::isFailed);
        return JsonSchemas.obj("mode", mode, "results", list, "isError", isError);
    }

    /** 简单有界并发执行（保序收集结果）。 */
    static <T, R> List<R> mapWithConcurrencyLimit(List<T> items, int limit,
            java.util.function.Function<T, R> fn) {
        var results = new java.util.ArrayList<R>(Collections.nCopies(items.size(), null));
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

    static String str(Object o) {
        return o instanceof String s ? s : "";
    }

    private static String orNoOutput(String output) {
        return output == null || output.isEmpty() ? "(no output)" : output;
    }

    static Map<String, Object> inputSchema() {
        return JsonSchemas.obj("type", "object", "properties", JsonSchemas.obj(
            "agent", JsonSchemas.obj("type", "string",
                "description", "Name of the agent to invoke (for single mode)"),
            "task", JsonSchemas.obj("type", "string",
                "description", "Task to delegate (for single mode)"),
            "tasks", JsonSchemas.obj("type", "array", "items", itemSchema(),
                "description", "Array of {agent, task} for parallel execution"),
            "chain", JsonSchemas.obj("type", "array", "items", itemSchema(),
                "description", "Array of {agent, task} for sequential execution"),
            "agentScope", JsonSchemas.obj("type", "string",
                "enum", List.of("user", "project", "both"),
                "description", "Which agent directories to use. Default: \"user\". "
                    + "Use \"both\" to include project-local agents.",
                "default", "user"),
            "confirmProjectAgents", JsonSchemas.obj("type", "boolean",
                "description", "Prompt before running project-local agents. Default: true.",
                "default", Boolean.TRUE),
            "cwd", JsonSchemas.obj("type", "string",
                "description", "Working directory for the agent process (single mode)")));
    }

    private static Map<String, Object> itemSchema() {
        return JsonSchemas.obj("type", "object", "required", List.of("agent", "task"),
            "properties", JsonSchemas.obj(
                "agent", JsonSchemas.obj("type", "string",
                    "description", "Name of the agent to invoke"),
                "task", JsonSchemas.obj("type", "string",
                    "description", "Task to delegate to the agent"),
                "cwd", JsonSchemas.obj("type", "string",
                    "description", "Working directory for the agent process")));
    }

    // ── 工具 ────────────────────────────────────────────────────────────

    static final class SubagentTool implements AgentTool<Map<String, Object>, Map<String, Object>> {
        private final SubagentExtension ext;
        private final ExtensionContext ctx;

        SubagentTool(SubagentExtension ext, ExtensionContext ctx) {
            this.ext = ext;
            this.ctx = ctx;
        }

        @Override public String name() { return "subagent"; }
        @Override public String label() { return "Subagent"; }
        @Override public String description() { return SubagentExtension.toolDescription(); }
        @Override public Map<String, Object> inputSchema() { return SubagentExtension.inputSchema(); }
        @Override public ExecutionMode executionMode() { return new ExecutionMode.Parallel(); }

        @Override
        public ToolResult<Map<String, Object>> execute(String toolCallId,
                Map<String, Object> params, AbortSignal signal,
                ToolUpdateCallback<Map<String, Object>> onUpdate, ToolContext context) {
            var chain = castList(params.get("chain"));
            var tasks = castList(params.get("tasks"));
            boolean hasChain = !chain.isEmpty();
            boolean hasTasks = !tasks.isEmpty();
            boolean hasSingle = params.get("agent") instanceof String a && !a.isEmpty()
                && params.get("task") instanceof String t && !t.isEmpty();
            var mode = hasChain ? "chain" : hasTasks ? "parallel" : "single";
            var invalid = validateMode(hasSingle, hasTasks, hasChain,
                availableNames(ext.availableAgents));
            if (invalid != null) {
                return text(invalid, details("single", List.of(), true));
            }

            var defaultCwd = ctx.cwd();
            var scope = params.get("agentScope") instanceof String s ? s : "user";
            boolean confirm = !(params.get("confirmProjectAgents") instanceof Boolean b) || b;
            if (("project".equals(scope) || "both".equals(scope)) && confirm && ctx.hasUi()
                    && !confirmProjectAgents(chain, tasks, params, defaultCwd, hasChain, hasTasks)) {
                return text("Canceled: project-local agents not approved.",
                    details(mode, List.of(), false));
            }

            var outcome = hasChain ? ext.runChain(chain, defaultCwd, signal)
                : hasTasks ? ext.runParallel(tasks, defaultCwd, signal)
                : ext.runSingleMode(str(params.get("agent")), str(params.get("task")),
                    defaultCwd, params.get("cwd") instanceof String c ? c : null, signal);
            return text(outcome.text(), details(mode, outcome.results(), outcome.forceError()));
        }

        /** pi L671-704：项目级 agent 需确认（受信任仓不拦）。 */
        private boolean confirmProjectAgents(List<Map<String, Object>> chain,
                List<Map<String, Object>> tasks, Map<String, Object> params, String defaultCwd,
                boolean hasChain, boolean hasTasks) {
            var selected = new ArrayList<ResolvedAgent>();
            if (hasChain) {
                for (var step : chain) {
                    selected.add(ext.resolveAgent(defaultCwd, str(step.get("agent")),
                        step.get("cwd")));
                }
            } else if (hasTasks) {
                for (var task : tasks) {
                    selected.add(ext.resolveAgent(defaultCwd, str(task.get("agent")),
                        task.get("cwd")));
                }
            } else {
                selected.add(ext.resolveAgent(defaultCwd, str(params.get("agent")),
                    params.get("cwd")));
            }
            var project = selected.stream()
                .filter(r -> r.agent() != null && "project".equals(r.agent().source()))
                .toList();
            if (project.isEmpty() || ctx.isProjectTrusted()) {
                return true;
            }
            var sources = project.stream().map(r -> "- " + r.agent().name() + ": "
                + r.agent().filePath()).collect(Collectors.joining("\n"));
            return ctx.ui().confirm("Run project-local agents?",
                "Sources:\n" + sources
                    + "\n\nProject agents are repo-controlled. Only continue for trusted repositories.",
                null);
        }

        private static ToolResult<Map<String, Object>> text(String output,
                Map<String, Object> details) {
            return new ToolResult<>(List.of(new ContentBlock.TextContent(output)),
                details, null, false, List.of());
        }

        @SuppressWarnings("unchecked")
        private static List<Map<String, Object>> castList(Object o) {
            return o instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        }
    }
}
