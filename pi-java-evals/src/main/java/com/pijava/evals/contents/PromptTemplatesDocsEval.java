package com.pijava.evals.contents;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.pijava.ai.message.Message;
import com.pijava.coding.agent.core.source.SourceInfo;
import com.pijava.coding.agent.prompt.PromptTemplateRegistry;
import com.pijava.coding.agent.prompt.PromptTemplates;
import com.pijava.evals.api.EvalCase;
import com.pijava.evals.api.EvalSuite;
import com.pijava.evals.harness.HarnessCase;
import com.pijava.evals.harness.HarnessOptions;
import com.pijava.evals.harness.HarnessRun;
import com.pijava.evals.harness.HarnessStep;
import com.pijava.evals.harness.TranscriptEvents;
import com.pijava.evals.judge.Judge;
import com.pijava.evals.judge.JudgeResult;
import com.pijava.evals.plan.DocumentationVariant;
import com.pijava.evals.plan.EvalCaseDescriptor;

/**
 * L6 W4（docs/26 §5）：prompt template 文档 eval —— pi
 * {@code prompt-templates.docs.eval.ts}（本锚点新增）对位。
 *
 * <p>两个场景：模型读文档后把 {@code /current-time} 模板写到 <b>project</b> 或
 * <b>user</b> scope，随后 {@code /reload} 重扫、再 {@code /current-time} 调用。
 * 判分同时钉四件事：</p>
 *
 * <ol>
 *   <li>模板落在正确 scope 的目录（{@code <cwd>/.pi-java/prompts/} 或
 *       {@code <agentDir>/prompts/}）；</li>
 *   <li>另一 scope <b>不</b>出现同名模板；</li>
 *   <li>{@code /current-time} 展开后的用户消息逐字等于模板正文；</li>
 *   <li>无模板诊断、无扩展错误、无技能混入。</li>
 * </ol>
 *
 * <p>目录名是 Java 方言（{@code .pi-java}，pi 用 {@code .pi}），登记 D231 族。</p>
 */
public final class PromptTemplatesDocsEval implements EvalSuite {

    /** Suite name. */
    public static final String SUITE_NAME = "prompt-templates-docs";

    /** Template name used by both scenarios. */
    public static final String TEMPLATE_NAME = "current-time";

    /** Command form of {@link #TEMPLATE_NAME}. */
    public static final String COMMAND = "/" + TEMPLATE_NAME;

    /** Exact template body the model must write. */
    public static final String TEMPLATE_PROMPT = "Tell me the current time and timezone.";

    /** Case name for the project-scoped scenario. */
    public static final String PROJECT_CASE = "project-scope";

    /** Case name for the user-scoped scenario. */
    public static final String USER_CASE = "user-scope";

    private static final List<String> TOOL_NAMES = List.of("read", "write", "edit", "grep", "find", "ls");

    private static final String PROJECT_REQUEST = """
        This project is already trusted. Add a %s prompt template for this project
        containing exactly: %s
        """.formatted(COMMAND, TEMPLATE_PROMPT);

    private static final String USER_REQUEST = """
        Make a %s prompt template available in all my sessions containing exactly: %s
        """.formatted(COMMAND, TEMPLATE_PROMPT);

    /** Which scope a case targets. */
    public enum Scope {

        /** Project-scoped template under {@code .pi-java/prompts}. */
        PROJECT(PROJECT_CASE, PROJECT_REQUEST),

        /** User-scoped template under {@code <agentDir>/prompts}. */
        USER(USER_CASE, USER_REQUEST);

        private final String caseName;
        private final String request;

        Scope(String caseName, String request) {
            this.caseName = caseName;
            this.request = request;
        }

        /** @return case name */
        public String caseName() {
            return caseName;
        }

        /** @return first-prompt text */
        public String request() {
            return request;
        }
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
        return List.of(new TemplateJudge());
    }

    @Override
    public Double judgeThreshold() {
        return 1.0;
    }

    @Override
    public List<HarnessCase> harnessCases() {
        return List.of(
            new HarnessCase(new EvalCaseDescriptor(SUITE_NAME, PROJECT_CASE),
                Scope.PROJECT.request(), expected(Scope.PROJECT)),
            new HarnessCase(new EvalCaseDescriptor(SUITE_NAME, USER_CASE),
                Scope.USER.request(), expected(Scope.USER)));
    }

    @Override
    public HarnessOptions harnessOptions(DocumentationVariant variant,
                                         Path sessionArtifactDir) {
        return HarnessOptions.builder()
            .name(SUITE_NAME)
            .tools(TOOL_NAMES)
            .noExtensions(true)
            .sessionArtifactDir(sessionArtifactDir)
            .output(context -> observe(context.session().services().promptTemplates(),
                Path.of(System.getProperty("user.dir", ".")), context.agentDir(),
                lastUserText(context)))
            .build();
    }

    /** 末条用户消息（{@code /current-time} 展开后的正文）。 */
    private static String lastUserText(HarnessOptions.OutputContext context) {
        var messages = context.session().accumulatedMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof Message.UserMessage user) {
                return TranscriptEvents.text(user.content());
            }
        }
        return "";
    }

    /**
     * Ordered steps for one scope: request, reload, invoke.
     *
     * @param scope scenario
     * @return harness steps
     */
    public static List<HarnessStep> steps(Scope scope) {
        Objects.requireNonNull(scope, "scope");
        return List.of(HarnessStep.prompt(scope.request()), HarnessStep.reload(),
            HarnessStep.prompt(COMMAND));
    }

    /**
     * Observable state after the run, as judged.
     *
     * <p>与 scope 无关：判分器拿 {@code expected}（场景）去比这些事实，
     * 不需要在观测期反推场景。</p>
     *
     * @param templateScope       resolved template scope, {@code null} when absent
     * @param projectPathExists   whether the project-scope file exists
     * @param userPathExists      whether the user-scope file exists
     * @param expandedToRequest   whether the last user message equals the template body
     * @param templateCount       how many templates carry this name
     */
    public record Observation(
        String templateScope,
        boolean projectPathExists,
        boolean userPathExists,
        boolean expandedToRequest,
        int templateCount
    ) {
    }

    /**
     * Collect the observable state, independent of the scenario.
     *
     * @param registry     session prompt-template registry
     * @param workspace    workspace root ({@code user.dir})
     * @param agentDir     isolated agent dir
     * @param lastUserText last user message text (already expanded)
     * @return observation
     */
    public static Observation observe(PromptTemplateRegistry registry,
                                      Path workspace, Path agentDir, String lastUserText) {
        var matches = new ArrayList<PromptTemplates.PromptTemplate>();
        for (var template : registry.all()) {
            if (TEMPLATE_NAME.equals(template.name())) {
                matches.add(template);
            }
        }
        var template = matches.size() == 1 ? matches.get(0) : null;
        return new Observation(
            template == null || template.sourceInfo() == null
                ? null : template.sourceInfo().scope().wire(),
            java.nio.file.Files.exists(expectedPath(Scope.PROJECT, workspace, agentDir)),
            java.nio.file.Files.exists(expectedPath(Scope.USER, workspace, agentDir)),
            TEMPLATE_PROMPT.equals(lastUserText == null ? "" : lastUserText.trim()),
            matches.size());
    }

    /**
     * Expected on-disk path for one scope.
     *
     * @param scope     scenario
     * @param workspace workspace root
     * @param agentDir  agent dir
     * @return expected template path
     */
    public static Path expectedPath(Scope scope, Path workspace, Path agentDir) {
        return scope == Scope.PROJECT
            ? workspace.resolve(".pi-java").resolve("prompts").resolve(TEMPLATE_NAME + ".md")
            : agentDir.resolve("prompts").resolve(TEMPLATE_NAME + ".md");
    }

    /**
     * Expected observation for one scenario.
     *
     * @param scope scenario
     * @return expected observation
     */
    public static Observation expected(Scope scope) {
        // project 场景：项目目录有、用户目录无；user 场景反之。
        return scope == Scope.PROJECT
            ? new Observation(SourceInfo.Scope.PROJECT.wire(), true, false, true, 1)
            : new Observation(SourceInfo.Scope.USER.wire(), false, true, true, 1);
    }

    /** Compares the observed facts against {@link #expected(Scope)}. */
    private static final class TemplateJudge implements Judge {

        @Override
        public JudgeResult evaluate(HarnessRun run, Object expected) {
            if (run == null) {
                return new JudgeResult(0.0, "no harness run", Map.of("reason", "no-run"));
            }
            // 观测结果由 harness 的 output 提供；字符串响应不足以判模板落点。
            var output = run.output();
            if (!(output instanceof Observation actual)) {
                return new JudgeResult(0.0,
                    "template eval requires an Observation output but got "
                        + (output == null ? "null" : output.getClass().getSimpleName()),
                    Map.of("reason", "output-shape"));
            }
            var wanted = expected instanceof Observation observation
                ? observation : expected(Scope.PROJECT);
            var problems = new ArrayList<String>();
            if (!Objects.equals(wanted.templateScope(), actual.templateScope())) {
                problems.add("template scope was " + actual.templateScope()
                    + " instead of " + wanted.templateScope());
            }
            if (actual.templateCount() != wanted.templateCount()) {
                problems.add("expected exactly " + wanted.templateCount() + " " + TEMPLATE_NAME
                    + " template but found " + actual.templateCount());
            }
            if (actual.projectPathExists() != wanted.projectPathExists()) {
                problems.add("project-scope file existence was " + actual.projectPathExists()
                    + " instead of " + wanted.projectPathExists());
            }
            if (actual.userPathExists() != wanted.userPathExists()) {
                problems.add("user-scope file existence was " + actual.userPathExists()
                    + " instead of " + wanted.userPathExists());
            }
            if (actual.expandedToRequest() != wanted.expandedToRequest()) {
                problems.add(COMMAND + " did not expand to the template body");
            }
            return problems.isEmpty()
                ? new JudgeResult(1.0, "template created, reloaded and invoked", Map.of())
                : new JudgeResult(0.0, String.join("; ", problems),
                    Map.of("reason", "template-mismatch", "problems", problems.size()));
        }
    }

    /** 模板正文（判分与提示共用同一常量）。 */
    public static String templateBody() {
        return TEMPLATE_PROMPT + System.lineSeparator();
    }

    /** 未使用的 codec 形状占位（保持与 pi 的 frontmatter 结构一致）。 */
    static String frontmatter() {
        return "---" + System.lineSeparator()
            + "description: Current time" + System.lineSeparator()
            + "---" + System.lineSeparator();
    }

    /** 模板文件的完整内容。 */
    public static String templateFile() {
        return frontmatter() + templateBody();
    }

    /** 判分器认识的作用域字面量。 */
    public static List<String> scopes() {
        return List.of(SourceInfo.Scope.PROJECT.wire(), SourceInfo.Scope.USER.wire());
    }
}
