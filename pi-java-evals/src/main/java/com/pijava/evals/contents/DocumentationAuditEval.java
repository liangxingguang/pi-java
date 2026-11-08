package com.pijava.evals.contents;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.ai.AbortSignal;
import com.pijava.evals.api.EvalCase;
import com.pijava.evals.api.EvalSuite;
import com.pijava.evals.harness.HarnessCase;
import com.pijava.evals.harness.HarnessOptions;
import com.pijava.evals.harness.HarnessRun;
import com.pijava.evals.harness.ToolCallRecord;
import com.pijava.evals.judge.Judge;
import com.pijava.evals.judge.JudgeResult;
import com.pijava.evals.plan.DocumentationVariant;
import com.pijava.evals.plan.EvalCaseDescriptor;

/**
 * 批 32（docs/44 §8.1）：文档审计 eval —— pi {@code documentation-audit.eval.ts} 对位。
 *
 * <p>用例集合是 {@code docs/**}{@code /*.md} 的递归发现（按路径字典序）；每个页面一个
 * 用例。工具集为 {@code read/grep/find/ls} 加内联 {@code submit_documentation_audit}。
 * 判定要求恰一次成功调用、{@code verdict=match}、{@code explanation} 非空且不超过
 * 2000 字符。</p>
 */
public final class DocumentationAuditEval implements EvalSuite {

    /** Suite name. */
    public static final String SUITE_NAME = "documentation-audit";

    /** Inline submit tool name. */
    public static final String SUBMIT_TOOL = "submit_documentation_audit";

    /** Maximum explanation length. */
    public static final int EXPLANATION_LIMIT = 2000;

    private static final List<String> TOOL_NAMES = List.of("read", "grep", "find", "ls");
    private static final List<String> VERDICTS = List.of("match", "mismatch", "inconclusive");

    private final Path repoRoot;
    private final Map<String, String> pages;
    private final AuditSourceIndex sourceIndex;

    /** Audit the repository at the current working directory (or its parent). */
    public DocumentationAuditEval() {
        this(defaultRepoRoot());
    }

    /**
     * Audit the given repository root.
     *
     * @param repoRoot repository root containing {@code docs/}
     */
    public DocumentationAuditEval(Path repoRoot) {
        this.repoRoot = repoRoot;
        this.pages = loadPages(repoRoot);
        this.sourceIndex = AuditSourceIndex.scan(repoRoot);
    }

    @Override
    public String name() {
        return SUITE_NAME;
    }

    @Override
    public List<EvalCase> cases() {
        return List.of();
    }

    @Override
    public List<Judge> judges() {
        return List.of(new AuditJudge(implementationPaths()));
    }

    @Override
    public Double judgeThreshold() {
        return 1.0;
    }

    @Override
    public List<HarnessCase> harnessCases() {
        return pages.keySet().stream()
            .map(page -> new HarnessCase(
                new EvalCaseDescriptor(SUITE_NAME, page), promptFor(page), page))
            .toList();
    }

    /**
     * 所有会被种入的实现源路径（判分器的取证白名单）。
     *
     * @return repo-relative source paths
     */
    public Set<String> implementationPaths() {
        var paths = new LinkedHashSet<String>();
        for (var entry : pages.entrySet()) {
            paths.addAll(sourceIndex.sourcesFor(entry.getKey(), entry.getValue()));
        }
        return Collections.unmodifiableSet(paths);
    }

    @Override
    public HarnessOptions harnessOptions(DocumentationVariant variant,
                                         Path sessionArtifactDir) {
        return HarnessOptions.builder()
            .name(SUITE_NAME)
            .tools(TOOL_NAMES)
            .customTools(List.of(submitTool()))
            .workspaceFiles(workspaceFiles())
            .noExtensions(true)
            .sessionArtifactDir(sessionArtifactDir)
            .build();
    }

    /**
     * L6 W3（docs/26 §4.4）：页面 ＋ 按页挑选的实现源，全部种进隔离 workspace。
     *
     * <p>pi 把整个仓库只读挂载给模型（{@code documentation-audit.eval.ts:32-34}）；
     * Java 的 read/grep/find/ls 受 {@code PathUtils} 约束只能在 workspace 内读，
     * 故此处按页面挑相关源一并种入（形状差 D230）。</p>
     *
     * @return workspace-relative path → content
     */
    public Map<String, String> workspaceFiles() {
        var files = new LinkedHashMap<>(pages);
        for (var entry : pages.entrySet()) {
            var sources = sourceIndex.sourcesFor(entry.getKey(), entry.getValue());
            files.putAll(AuditSourceIndex.read(repoRoot, sources));
        }
        return Collections.unmodifiableMap(files);
    }

    /** @return the source index backing {@link #workspaceFiles()} */
    public AuditSourceIndex sourceIndex() {
        return sourceIndex;
    }

    /** @return repository root used for discovery */
    public Path repoRoot() {
        return repoRoot;
    }

    /**
     * Discover {@code docs/**}{@code /*.md} in dictionary order.
     *
     * @param repoRoot repository root
     * @return repo-relative paths with {@code /} separators
     */
    public static List<String> discoverPages(Path repoRoot) {
        var docs = repoRoot.resolve("docs");
        if (!Files.isDirectory(docs)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(docs)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".md"))
                .map(path -> repoRoot.relativize(path).toString().replace('\\', '/'))
                .sorted()
                .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to discover documentation pages", e);
        }
    }

    /**
     * Prompt for one page.
     *
     * @param page repo-relative page path
     * @return prompt text
     */
    public static String promptFor(String page) {
        return """
            Audit whether the documentation page "%s" matches the current pi-java implementation.

            The repository checkout is the source of truth. Read the page, check the commands,
            APIs, file paths, configuration and behavior it describes against the code, then call
            the %s tool exactly once with:
            - verdict: "match" when the page is accurate, otherwise "mismatch" or "inconclusive"
            - explanation: a concise justification

            Do not edit any files.
            """.formatted(page, SUBMIT_TOOL);
    }

    /**
     * Inline tool that records the audit verdict.
     *
     * @return submit_documentation_audit tool
     */
    public static AgentTool<Map<String, Object>, Void> submitTool() {
        return new AgentTool<>() {
            @Override public String name() {
                return SUBMIT_TOOL;
            }

            @Override public String label() {
                return "submit documentation audit";
            }

            @Override public String description() {
                return "Submit the documentation audit verdict and a short explanation.";
            }

            @Override public Map<String, Object> inputSchema() {
                return Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "verdict", Map.of("type", "string", "enum", VERDICTS),
                        "explanation", Map.of("type", "string",
                            "minLength", 1, "maxLength", EXPLANATION_LIMIT)
                    ),
                    "required", List.of("verdict", "explanation"));
            }

            @Override public ExecutionMode executionMode() {
                return new ExecutionMode.Sequential();
            }

            @Override
            public ToolResult<Void> execute(String toolCallId, Map<String, Object> params,
                    AbortSignal signal, ToolUpdateCallback<Void> onUpdate,
                    ToolContext context) {
                var verdict = params == null ? null : params.get("verdict");
                if (!(verdict instanceof String text) || !VERDICTS.contains(text)) {
                    throw new IllegalArgumentException(
                        "verdict must be one of " + VERDICTS + " but was " + verdict);
                }
                var explanation = params.get("explanation");
                if (!(explanation instanceof String body) || body.isBlank()
                        || body.length() > EXPLANATION_LIMIT) {
                    throw new IllegalArgumentException("explanation must be 1.."
                        + EXPLANATION_LIMIT + " characters");
                }
                return ToolResult.success("documentation audit recorded: " + text);
            }
        };
    }

    private static Path defaultRepoRoot() {
        var cwd = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        if (Files.isDirectory(cwd.resolve("docs"))) {
            return cwd;
        }
        var parent = cwd.getParent();
        return parent != null && Files.isDirectory(parent.resolve("docs")) ? parent : cwd;
    }

    private static Map<String, String> loadPages(Path repoRoot) {
        var pages = new LinkedHashMap<String, String>();
        for (var page : discoverPages(repoRoot)) {
            try {
                pages.put(page, Files.readString(repoRoot.resolve(page), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read " + page, e);
            }
        }
        return Collections.unmodifiableMap(pages);
    }

    /**
     * 取证 ＋ 提交两侧的判定（L6 W3，docs/26 §4）。
     *
     * <p>pi {@code documentation-audit.eval.ts:60-78} 的顺序是：先断言「读过被审页面」
     * 与「读过至少一个实现源」，再校验提交调用。本类同序，理由编码进
     * {@code metadata.reason} 便于定位。</p>
     */
    private static final class AuditJudge implements Judge {

        private final Set<String> implementations;

        AuditJudge(Set<String> implementations) {
            this.implementations = Set.copyOf(implementations);
        }

        @Override
        public JudgeResult evaluate(HarnessRun run, Object expected) {
            if (run == null) {
                return new JudgeResult(0.0, "no harness run", Map.of("reason", "no-run"));
            }
            var page = String.valueOf(expected == null ? "" : expected);
            var readPaths = readPaths(run);
            if (!readPaths.contains(page)) {
                return new JudgeResult(0.0,
                    "the model never read the audited page " + page,
                    Map.of("reason", "page-not-read", "readPaths", readPaths.size()));
            }
            var readImplementation = readPaths.stream().anyMatch(implementations::contains);
            if (!readImplementation) {
                return new JudgeResult(0.0,
                    "the model read no implementation source for " + page,
                    Map.of("reason", "implementation-not-read", "readPaths", readPaths.size()));
            }
            return submit(run);
        }

        /** 成功读过的路径集合（{@code status=ok} 才算取证，pi 同）。 */
        private static Set<String> readPaths(HarnessRun run) {
            var paths = new LinkedHashSet<String>();
            for (var call : run.toolCalls()) {
                if (!"read".equals(call.name()) || !call.succeeded()) {
                    continue;
                }
                var arguments = call.arguments();
                var path = arguments == null ? null : arguments.get("path");
                if (path instanceof String text && !text.isBlank()) {
                    paths.add(text);
                }
            }
            return paths;
        }

        private static JudgeResult submit(HarnessRun run) {
            var calls = run.toolCalls().stream()
                .filter(call -> SUBMIT_TOOL.equals(call.name())).toList();
            if (calls.size() != 1) {
                return new JudgeResult(0.0,
                    "expected exactly one " + SUBMIT_TOOL + " call but observed " + calls.size(),
                    Map.of("reason", "submit-count", "calls", calls.size()));
            }
            var call = calls.get(0);
            if (!call.succeeded()) {
                return new JudgeResult(0.0, SUBMIT_TOOL + " call did not succeed",
                    Map.of("reason", "submit-status", "status", call.status()));
            }
            var arguments = call.arguments() == null ? Map.<String, Object>of() : call.arguments();
            var verdict = arguments.get("verdict");
            if (!"match".equals(verdict)) {
                return new JudgeResult(0.0, "verdict was " + verdict + " instead of match",
                    Map.of("reason", "verdict", "verdict", String.valueOf(verdict)));
            }
            var explanation = arguments.get("explanation");
            if (!(explanation instanceof String text) || text.isBlank()
                    || text.length() > EXPLANATION_LIMIT) {
                return new JudgeResult(0.0, "explanation must be 1.." + EXPLANATION_LIMIT
                    + " characters", Map.of("reason", "explanation"));
            }
            return new JudgeResult(1.0, "documentation audit matches", Map.of());
        }
    }
}
