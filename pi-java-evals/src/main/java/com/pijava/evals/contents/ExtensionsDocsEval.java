package com.pijava.evals.contents;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicReference;

import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.ai.AbortSignal;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.evals.api.EvalCase;
import com.pijava.evals.api.EvalSuite;
import com.pijava.evals.harness.HarnessCase;
import com.pijava.evals.harness.HarnessOptions;
import com.pijava.evals.harness.HarnessRun;
import com.pijava.evals.harness.HarnessStep;
import com.pijava.evals.harness.TranscriptEvent;
import com.pijava.evals.judge.Judge;
import com.pijava.evals.judge.JudgeResult;
import com.pijava.evals.plan.DocumentationVariant;
import com.pijava.evals.plan.EvalCaseDescriptor;

/**
 * 批 32（docs/44 §8.2/D184）：扩展作者 eval —— pi {@code extensions.eval.ts} 对位。
 *
 * <p>工具集在 pi 的六件之上额外给 {@code bash}（Java 扩展需 {@code javac}＋
 * {@code jar}，D184）。模型在用户扩展目录创建 hello 工具，编译成 JAR 后调用内联
 * {@code install_extension}；该工具经 {@code sessionCustomizer} 拿到会话，
 * 用 {@link URLClassLoader}＋{@link ServiceLoader} 装载 JAR 并
 * {@code addInlineExtension}／{@code activateRuntimeTools}（{@code /reload}
 * 不重建扩展运行时，D90 裁决③）。随后 reload，再让模型调用 hello。</p>
 */
public final class ExtensionsDocsEval implements EvalSuite {

    /** Suite name. */
    public static final String SUITE_NAME = "extensions-docs";

    /** Case name. */
    public static final String CASE_NAME = "installs-hello-extension";

    /** Inline JAR loader tool name. */
    public static final String INSTALL_TOOL = "install_extension";

    /** Greeting tool name provided by the authored extension. */
    public static final String HELLO_TOOL = "hello";

    /** Expected greeting. */
    public static final String EXPECTED_GREETING = "Hello, Bob!";

    private static final List<String> TOOL_NAMES = List.of(
        "read", "write", "edit", "grep", "find", "ls", "bash", HELLO_TOOL);

    private static final String BUILD_PROMPT = """
        You are extending the pi-java coding agent with a Java extension.

        Read the project documentation about writing extensions. Create a new extension
        inside the user's extension directory (~/.pi-java/agent/extensions) that registers
        a tool named "hello". The tool takes one required string parameter "name" and
        returns "Hello, <name>!". Follow the documented Java extension shape; do not
        modify the repository source.

        You may use the bash tool together with javac and jar to build the JAR. The
        runtime classpath is listed in classpath.txt in the workspace.

        After the JAR is built, call the install_extension tool exactly once with the
        absolute JAR path.
        """;

    private static final String GREET_PROMPT = """
        Use the hello tool to greet Bob and reply exactly: Hello, Bob!
        """;

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
        return List.of(new ExtensionJudge());
    }

    @Override
    public Double judgeThreshold() {
        return 1.0;
    }

    @Override
    public List<HarnessCase> harnessCases() {
        return List.of(new HarnessCase(
            new EvalCaseDescriptor(SUITE_NAME, CASE_NAME),
            BUILD_PROMPT + "\n\n" + GREET_PROMPT, Map.of()));
    }

    /**
     * Two prompts separated by a reload (pi {@code {prompt} → reload → {prompt}}).
     *
     * @param variant          docs variant
     * @param sessionArtifactDir artifact destination
     * @return harness options
     */
    public HarnessOptions harnessOptions(DocumentationVariant variant,
                                         Path sessionArtifactDir) {
        var session = new AtomicReference<AgentSession>();
        return HarnessOptions.builder()
            .name(SUITE_NAME)
            .tools(TOOL_NAMES)
            .customTools(List.of(installTool(session)))
            .sessionCustomizer(session::set)
            .workspaceFiles(Map.of("classpath.txt",
                Objects.toString(System.getProperty("java.class.path"), "")))
            .sessionArtifactDir(sessionArtifactDir)
            .build();
    }

    /**
     * Ordered steps for this eval: build prompt, reload, greet prompt.
     *
     * @return harness steps
     */
    public static List<HarnessStep> steps() {
        return List.of(HarnessStep.prompt(BUILD_PROMPT), HarnessStep.reload(),
            HarnessStep.prompt(GREET_PROMPT));
    }

    /**
     * Inline tool that loads the authored JAR into the assembled session.
     *
     * @param session holder populated by {@link HarnessOptions#sessionCustomizer()}
     * @return install_extension tool
     */
    public static AgentTool<Map<String, Object>, Void> installTool(
            AtomicReference<AgentSession> session) {
        Objects.requireNonNull(session, "session");
        var loaders = new java.util.ArrayList<URLClassLoader>();
        return new AgentTool<>() {
            @Override public String name() {
                return INSTALL_TOOL;
            }

            @Override public String label() {
                return "install extension";
            }

            @Override public String description() {
                return "Load a compiled pi-java extension JAR into the current session.";
            }

            @Override public Map<String, Object> inputSchema() {
                return Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "jar", Map.of("type", "string",
                            "description", "absolute path to the extension JAR")),
                    "required", List.of("jar"));
            }

            @Override public ExecutionMode executionMode() {
                return new ExecutionMode.Sequential();
            }

            @Override
            public ToolResult<Void> execute(String toolCallId, Map<String, Object> params,
                    AbortSignal signal, ToolUpdateCallback<Void> onUpdate,
                    ToolContext context) throws Exception {
                var jar = resolvePath(params == null ? null : params.get("jar"));
                var target = session.get();
                if (target == null) {
                    throw new IllegalStateException(
                        "install_extension requires an assembled session");
                }
                var loader = new URLClassLoader(new URL[] {jar.toUri().toURL()},
                    ExtensionsDocsEval.class.getClassLoader());
                loaders.add(loader);
                var names = new LinkedHashSet<String>();
                for (var extension : ServiceLoader.load(PiExtension.class, loader)) {
                    target.addInlineExtension(extension);
                    names.add(extension.name());
                }
                if (names.isEmpty()) {
                    throw new IllegalStateException(
                        "No PiExtension service found in " + jar);
                }
                target.activateRuntimeTools(names);
                return ToolResult.success("loaded extensions: " + String.join(", ", names));
            }
        };
    }

    private static Path resolvePath(Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(
                "install_extension requires a non-blank 'jar'");
        }
        var expanded = text.startsWith("~/")
            ? System.getProperty("user.home") + text.substring(1) : text;
        var path = Path.of(expanded).toAbsolutePath().normalize();
        if (!java.nio.file.Files.isRegularFile(path)) {
            throw new IllegalArgumentException("extension JAR not found: " + path);
        }
        return path;
    }

    /** Exactly one install, one successful hello, the exact greeting, and no tool errors. */
    private static final class ExtensionJudge implements Judge {

        @Override
        public JudgeResult evaluate(HarnessRun run, Object expected) {
            if (run == null) {
                return new JudgeResult(0.0, "no harness run", Map.of());
            }
            var calls = run.toolCalls();
            var installs = calls.stream()
                .filter(call -> INSTALL_TOOL.equals(call.name())).toList();
            var greetings = calls.stream()
                .filter(call -> HELLO_TOOL.equals(call.name())).toList();
            if (installs.size() != 1 || !installs.get(0).succeeded()) {
                return new JudgeResult(0.0,
                    "expected exactly one successful install_extension call but observed "
                        + installs.size(), Map.of("installCalls", installs.size()));
            }
            if (greetings.size() != 1 || !greetings.get(0).succeeded()) {
                return new JudgeResult(0.0,
                    "expected exactly one successful hello call but observed "
                        + greetings.size(), Map.of("helloCalls", greetings.size()));
            }
            var toolError = run.events().stream()
                .filter(event -> "tool_result".equals(event.type()) && event.error())
                .findFirst();
            if (toolError.isPresent()) {
                return new JudgeResult(0.0,
                    "tool error: " + toolError.get().content(), Map.of());
            }
            var helloResult = run.events().stream()
                .filter(event -> "tool_result".equals(event.type())
                    && HELLO_TOOL.equals(event.toolName()))
                .map(TranscriptEvent::content).reduce((first, second) -> second).orElse("");
            if (!EXPECTED_GREETING.equals(helloResult)) {
                return new JudgeResult(0.0,
                    "hello result was '" + helloResult + "' instead of '"
                        + EXPECTED_GREETING + "'", Map.of());
            }
            var response = run.response() == null ? "" : run.response().trim();
            if (!EXPECTED_GREETING.equals(response)) {
                return new JudgeResult(0.0,
                    "final response was '" + response + "' instead of '"
                        + EXPECTED_GREETING + "'", Map.of());
            }
            return new JudgeResult(1.0, "hello extension loaded and greeted Bob", Map.of());
        }
    }
}
