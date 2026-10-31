package com.pijava.evals.contents;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pijava.evals.api.EvalCase;
import com.pijava.evals.api.EvalSuite;
import com.pijava.evals.harness.HarnessCase;
import com.pijava.evals.harness.HarnessOptions;
import com.pijava.evals.judge.Judge;
import com.pijava.evals.judge.LevenshteinJudge;
import com.pijava.evals.plan.DocumentationVariant;
import com.pijava.evals.plan.EvalCaseDescriptor;

/**
 * 批 32（docs/44 §8.7/D183）：TUI 文档审计 eval —— pi {@code tui.eval.ts} 的
 * 等价形态。
 *
 * <p>Java TUI 没有 pi 的内置 context 百分比状态行，因此用 Java TUI 文档的问答
 * 审计：{@code with_docs} 变体种入 TUI 文档页，{@code without_docs} 不种，
 * 由 {@link LevenshteinJudge}（阈值 0.75）判分并测 lift。行 20 记存疑。</p>
 */
public final class TuiDocsEval implements EvalSuite {

    /** Suite name. */
    public static final String SUITE_NAME = "tui-docs";

    /** Judge threshold. */
    public static final double THRESHOLD = 0.75;

    /** Documentation pages seeded for the with-docs variant. */
    public static final List<String> DOC_PAGES = List.of(
        "docs/map/05-tui-web.md", "docs/03-detailed-design.md");

    private static final List<String> TOOL_NAMES = List.of("read", "grep", "find", "ls");

    /** One documentation question with its short expected answer. */
    public record Question(String caseName, String question, String answer) {
    }

    /** Fixed question set (short answers keep Levenshtein scoring exact). */
    public static final List<Question> QUESTIONS = List.of(
        new Question("extension-slots-class",
            "Which class in pi-java implements the extension widget, footer, header"
                + " and status slots? Reply with just the class name.",
            "ChatScreen"),
        new Question("extension-ui-methods-class",
            "Which class exposes the four extension UI methods (selector, input,"
                + " editor, confirm)? Reply with just the class name.",
            "TuiExtensionUI"),
        new Question("max-widget-lines",
            "What is the maximum number of extension widget lines before truncation?"
                + " Reply with the number only.",
            "10"),
        new Question("status-sanitize",
            "How are extension status entries sanitized before being joined?"
                + " Reply in one short sentence.",
            "Newlines and tabs become spaces, repeated spaces collapse, then trim."));

    private final Path repoRoot;
    private final Map<String, String> docs;

    /** Use the repository containing the current working directory. */
    public TuiDocsEval() {
        this(defaultRepoRoot());
    }

    /**
     * Use the given repository root.
     *
     * @param repoRoot repository root containing {@code docs/}
     */
    public TuiDocsEval(Path repoRoot) {
        this.repoRoot = repoRoot;
        this.docs = loadDocs(repoRoot);
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
        return List.of(new LevenshteinJudge());
    }

    @Override
    public Double judgeThreshold() {
        return THRESHOLD;
    }

    @Override
    public List<HarnessCase> harnessCases() {
        return QUESTIONS.stream()
            .map(question -> new HarnessCase(
                new EvalCaseDescriptor(SUITE_NAME, question.caseName()),
                promptFor(question), question.answer()))
            .toList();
    }

    @Override
    public HarnessOptions harnessOptions(DocumentationVariant variant,
                                         Path sessionArtifactDir) {
        var builder = HarnessOptions.builder()
            .name(SUITE_NAME)
            .tools(TOOL_NAMES)
            .noExtensions(true)
            .noSession(true)
            .sessionArtifactDir(sessionArtifactDir);
        if (variant == DocumentationVariant.WITH_DOCS) {
            builder.workspaceFiles(docs);
        }
        return builder.build();
    }

    /** @return repository root used for discovery */
    public Path repoRoot() {
        return repoRoot;
    }

    /**
     * Prompt for one question.
     *
     * @param question question definition
     * @return prompt text
     */
    public static String promptFor(Question question) {
        return "Answer from the Java TUI documentation. " + question.question();
    }

    private static Map<String, String> loadDocs(Path repoRoot) {
        var pages = new LinkedHashMap<String, String>();
        for (var page : DOC_PAGES) {
            var path = repoRoot.resolve(page);
            if (!Files.isRegularFile(path)) {
                continue;
            }
            try {
                pages.put(page, Files.readString(path, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read " + page, e);
            }
        }
        return Collections.unmodifiableMap(pages);
    }

    private static Path defaultRepoRoot() {
        var cwd = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        if (Files.isDirectory(cwd.resolve("docs"))) {
            return cwd;
        }
        var parent = cwd.getParent();
        return parent != null && Files.isDirectory(parent.resolve("docs")) ? parent : cwd;
    }
}
