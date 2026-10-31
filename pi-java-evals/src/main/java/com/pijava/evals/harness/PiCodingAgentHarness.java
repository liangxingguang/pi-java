package com.pijava.evals.harness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.coding.agent.cli.Args;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.DefaultProviders;
import com.pijava.coding.agent.core.SessionStats;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.PiExtension;

/**
 * 批 32（docs/44 §3）：真 coding-agent harness —— pi
 * {@code createPiCodingAgentHarness}/{@code runPiCodingAgent}
 * （{@code harness.ts:272-482}）对位。
 *
 * <p>每个 arm 在 {@link IsolatedEnvironment} 里装配真 {@link AgentSession}，
 * 收集 transcript/usage/timing/session artifact。属性隔离是进程级状态，
 * 因此 arm 串行执行（锁在 {@link IsolatedEnvironment} 内）。</p>
 */
public final class PiCodingAgentHarness {

    /** pi {@code DOCUMENTATION_EVAL_TOOLS}（{@code harness.ts:485}）。 */
    public static final List<String> DOCUMENTATION_EVAL_TOOLS =
        List.of("read", "write", "edit", "grep", "find", "ls");

    /** pi session snapshot artifact key（{@code report.ts:9}）。 */
    public static final String PI_SESSION_SNAPSHOT_ARTIFACT = "piSessionJsonl";

    private final HarnessOptions options;

    /**
     * Create a harness.
     *
     * @param options harness options
     */
    public PiCodingAgentHarness(HarnessOptions options) {
        this.options = options;
    }

    /**
     * Run one prompt.
     *
     * @param prompt prompt text
     * @return run observation
     */
    public HarnessRun run(String prompt) {
        return run(List.of(HarnessStep.prompt(prompt)));
    }

    /**
     * Run a sequence of prompt/reload steps.
     *
     * @param steps steps in order (at least one prompt)
     * @return run observation
     * @throws IllegalStateException if the input has no prompt or the run ends unexpectedly
     */
    public HarnessRun run(List<HarnessStep> steps) {
        if (steps == null || steps.isEmpty()
                || steps.stream().noneMatch(step -> !step.isReload())) {
            throw new IllegalArgumentException("Pi eval input must include at least one prompt step.");
        }
        var selection = resolveModelSelection(options.provider(), options.model());
        var started = System.nanoTime();
        try (var environment = IsolatedEnvironment.open()) {
            return execute(steps, selection, environment, started);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to prepare the isolated eval environment", e);
        }
    }

    private HarnessRun execute(List<HarnessStep> steps, ModelSelection selection,
                               IsolatedEnvironment environment, long started) {
        try {
            WorkspaceSeeder.seed(environment.workspace(), options.workspaceFiles());
            var args = buildArgs(selection, environment);
            var registry = options.providers() != null
                ? options.providers() : DefaultProviders.defaultProviders();
            var toolContext = new ToolContext(environment.workspace().toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem());
            try (var session = openSession(args, registry, toolContext)) {
                installCustomTools(session);
                if (options.sessionCustomizer() != null) {
                    options.sessionCustomizer().accept(session);
                }
                applyPromptTransform(session);
                var response = runSteps(session, steps);
                return collect(session, steps, selection, environment, started, response);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Pi eval harness I/O failed", e);
        }
    }

    /**
     * 需要 artifact 时走持久（JSONL）装配缝；{@code noSession} 保持内存臂。
     * 属性隔离由 {@link IsolatedEnvironment} 提供的临时 sessionDir 保证。
     */
    private AgentSession openSession(Args args, ProviderRegistry registry,
                                     ToolContext toolContext) {
        return options.noSession()
            ? AgentSession.create(args, registry, toolContext)
            : DefaultProviders.createPersistentSession(args, registry, toolContext);
    }

    private String runSteps(AgentSession session, List<HarnessStep> steps) {
        String response = null;
        for (var step : steps) {
            if (step.isReload()) {
                session.reload();
                continue;
            }
            var before = session.accumulatedMessages().size();
            session.processPrompt(step.content()).status();
            response = validateRun(session, before);
        }
        return response;
    }

    private static String validateRun(AgentSession session, int fromIndex) {
        var messages = session.accumulatedMessages();
        Message.AssistantMessage assistant = null;
        for (var i = messages.size() - 1; i >= fromIndex; i--) {
            if (messages.get(i) instanceof Message.AssistantMessage message) {
                assistant = message;
                break;
            }
        }
        if (assistant == null) {
            throw new IllegalStateException("Agent run completed without an assistant message.");
        }
        if (assistant.stopReason() != StopReason.STOP
                && assistant.stopReason() != StopReason.TOOL_USE) {
            var detail = assistant.errorMessage() != null
                ? assistant.errorMessage()
                : "Agent run ended with unexpected stop reason: "
                    + assistant.stopReason() + ".";
            throw new IllegalStateException(detail);
        }
        var output = session.lastAssistantText();
        if ((output == null || output.isEmpty()) && assistant.stopReason() == StopReason.STOP) {
            throw new IllegalStateException("Agent run produced no assistant text.");
        }
        return output == null ? "" : output;
    }

    private void applyPromptTransform(AgentSession session) {
        var rendered = session.harness().currentSystemPrompt();
        if (options.transformSystemPrompt() != null) {
            session.harness().setSystemPrompt(options.transformSystemPrompt().apply(rendered));
        }
        DocumentationVariants.verifySystemPrompt(session.harness().currentSystemPrompt(),
            options.name(), options.expectedPiDocumentation());
    }

    private void installCustomTools(AgentSession session) {
        if (options.customTools().isEmpty()) {
            return;
        }
        var tools = options.customTools();
        session.addInlineExtension(new PiExtension() {
            @Override
            public String name() {
                return "eval-custom-tools";
            }

            @Override
            public void register(ExtensionContext ctx) {
                for (var tool : tools) {
                    ctx.tools().register(tool);
                }
            }
        });
        session.activateRuntimeTools(tools.stream().map(AgentTool::name).toList());
    }

    private HarnessRun collect(AgentSession session, List<HarnessStep> steps,
                               ModelSelection selection, IsolatedEnvironment environment,
                               long started, String response) throws IOException {
        var messages = session.accumulatedMessages();
        var systemPrompt = session.harness().currentSystemPrompt();
        var artifacts = new LinkedHashMap<String, String>();
        artifacts.put("runId", session.sessionId());
        var sessionFile = session.sessionFile();
        String sessionJsonl = null;
        Path sessionPath = sessionFile == null || sessionFile.isBlank()
            ? null : Path.of(sessionFile);
        if (sessionPath != null && Files.isRegularFile(sessionPath)) {
            sessionJsonl = Files.readString(sessionPath, StandardCharsets.UTF_8);
        }
        if (sessionJsonl != null && options.sessionArtifactDir() != null) {
            var target = Files.createDirectories(options.sessionArtifactDir());
            var artifact = target.resolve("session.jsonl");
            Files.writeString(artifact, sessionJsonl, StandardCharsets.UTF_8);
            artifacts.put(PI_SESSION_SNAPSHOT_ARTIFACT, artifact.toString());
        }
        var stats = SessionStats.of(session);
        var model = session.harness().getModel();
        var providerName = model == null ? selection.provider() : model.provider();
        var modelName = model == null ? selection.model() : model.modelName();
        var cost = stats.cost() > 0 ? stats.cost() : null;
        var lastPrompt = steps.stream()
            .filter(step -> !step.isReload()).map(HarnessStep::content)
            .reduce((first, second) -> second).orElse(null);
        return new HarnessRun(steps, lastPrompt, response,
            TranscriptEvents.from(messages), TranscriptEvents.toolCalls(messages),
            HarnessUsage.from(stats, providerName, modelName, cost),
            Duration.ofNanos(System.nanoTime() - started).toMillis(),
            systemPrompt, sha256(systemPrompt), sessionFile, sessionJsonl,
            session.sessionId(), artifacts);
    }

    private Args buildArgs(ModelSelection selection, IsolatedEnvironment environment) {
        var args = new ArrayList<String>();
        args.add("--provider");
        args.add(selection.provider());
        args.add("--model");
        args.add(selection.model());
        args.add("--thinking");
        args.add(options.thinking());
        args.add("--session-dir");
        args.add(environment.sessionDir().toString());
        args.add("--no-mcp");
        if (options.noSession()) {
            args.add("--no-session");
        }
        if (options.noExtensions()) {
            args.add("--no-extensions");
        }
        if (options.noTools()) {
            args.add("--no-tools");
        } else if (options.tools() != null) {
            var names = new ArrayList<>(options.tools());
            for (var tool : options.customTools()) {
                if (!names.contains(tool.name())) {
                    names.add(tool.name());
                }
            }
            args.add("--tools");
            args.add(String.join(",", names));
        }
        return ArgsParser.parse(args.toArray(String[]::new));
    }

    private static ModelSelection resolveModelSelection(String provider, String model) {
        var selectedProvider = provider == null || provider.isBlank()
            ? System.getenv("PI_PROVIDER") : provider;
        var selectedModel = model == null || model.isBlank()
            ? System.getenv("PI_MODEL") : model;
        selectedProvider = selectedProvider == null ? null : selectedProvider.trim();
        selectedModel = selectedModel == null ? null : selectedModel.trim();
        if (selectedProvider == null || selectedProvider.isEmpty()
                || selectedModel == null || selectedModel.isEmpty()) {
            throw new IllegalStateException(
                "Select a harness model explicitly or set both PI_PROVIDER and PI_MODEL as defaults.");
        }
        return new ModelSelection(selectedProvider, selectedModel);
    }

    private static String sha256(String text) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** Resolved provider/model pair. */
    record ModelSelection(String provider, String model) {
    }
}
