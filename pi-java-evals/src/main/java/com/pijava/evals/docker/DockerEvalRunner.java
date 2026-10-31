package com.pijava.evals.docker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pijava.evals.plan.DocumentationVariant;
import com.pijava.evals.plan.EvalCaseDescriptor;
import com.pijava.evals.plan.TaskPlan;
import com.pijava.evals.report.CaseStatus;
import com.pijava.evals.report.EvalObservation;

/**
 * 批 32（docs/44 §7.2）：Docker 编排 —— 构建两镜像、发现 cohort、执行单臂并读
 * JUnit XML。
 *
 * <p>D182：CI 只钉命令契约、target 名、cohort 校验与 XML 读取；真实构建留给有
 * Docker 与网络的环境。进程经 {@link DockerProcess} 注入，测试用假实现。</p>
 */
public final class DockerEvalRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DockerContext context;
    private final DockerProcess process;
    private final DockerCommandBuilder builder;

    /**
     * Create a runner with the default command builder.
     *
     * @param context docker context
     * @param process docker process (real or fake)
     */
    public DockerEvalRunner(DockerContext context, DockerProcess process) {
        this(context, process, new DockerCommandBuilder());
    }

    /**
     * Create a runner.
     *
     * @param context docker context
     * @param process docker process (real or fake)
     * @param builder command builder
     */
    public DockerEvalRunner(DockerContext context, DockerProcess process,
                            DockerCommandBuilder builder) {
        this.context = Objects.requireNonNull(context, "context");
        this.process = Objects.requireNonNull(process, "process");
        this.builder = Objects.requireNonNull(builder, "builder");
    }

    /**
     * Image specs in build order.
     *
     * @return {@code without-docs} then {@code with-docs}
     */
    public List<DockerImageSpec> images() {
        return DockerImageSpec.both(context.imagePrefix());
    }

    /**
     * Image spec matching a documentation variant.
     *
     * @param variant docs variant
     * @return image spec
     */
    public DockerImageSpec imageFor(DocumentationVariant variant) {
        return DockerImageSpec.forVariant(context.imagePrefix(), variant);
    }

    /**
     * Build both images; any failure aborts the run.
     *
     * @throws IllegalStateException when a build fails
     */
    public void buildImages() {
        for (var image : images()) {
            var result = process.run(builder.buildCommand(context, image));
            if (result.exitCode() != 0) {
                throw new IllegalStateException("docker build failed for " + image.image()
                    + ": " + describe(result));
            }
        }
    }

    /**
     * Run {@code --list-cases} in one image and parse the JSON lines.
     *
     * @param image image spec
     * @return discovered cases
     * @throws IllegalStateException when the container fails
     */
    public List<EvalCaseDescriptor> discoverCases(DockerImageSpec image) {
        var result = process.run(builder.discoverCommand(image));
        if (result.exitCode() != 0) {
            throw new IllegalStateException("docker --list-cases failed for " + image.image()
                + ": " + describe(result));
        }
        return parseCases(result.stdout());
    }

    /**
     * Discover both images and require the same cohort.
     *
     * @return discovered cases
     * @throws IllegalStateException when the two cohorts differ
     */
    public List<EvalCaseDescriptor> verifyCohort() {
        var without = discoverCases(imageFor(DocumentationVariant.WITHOUT_DOCS));
        var with = discoverCases(imageFor(DocumentationVariant.WITH_DOCS));
        if (!new HashSet<>(without).equals(new HashSet<>(with))) {
            throw new IllegalStateException(
                "Eval cohort mismatch: without_docs=" + without + " with_docs=" + with);
        }
        return with;
    }

    /**
     * Execute one arm and classify the JUnit XML it produced.
     *
     * @param arm         arm to execute
     * @param artifactDir host artifact directory mounted at {@code /artifacts}
     * @param provider    provider name
     * @param model       model id
     * @return observation (not yet persisted)
     * @throws IOException when the XML exists but cannot be read
     */
    public EvalObservation runTask(TaskPlan.Arm arm, Path artifactDir, String provider,
                                   String model) throws IOException {
        Objects.requireNonNull(arm, "arm");
        Objects.requireNonNull(artifactDir, "artifactDir");
        var image = imageFor(arm.variant());
        var command = builder.runCommand(context, image, artifactDir, provider, model,
            arm.evalCase(), arm.run());
        var started = System.nanoTime();
        var result = process.run(command);
        var duration = Duration.ofNanos(System.nanoTime() - started).toMillis();
        var xml = artifactDir.resolve("tasks").resolve(arm.id()).resolve("junit.xml");
        if (!Files.isRegularFile(xml)) {
            return observation(arm, CaseStatus.MISSING, null,
                "JUnit XML not found: " + xml + " (" + describe(result) + ")",
                duration, Map.of("exitCode", result.exitCode()));
        }
        JUnitXmlReader.JUnitResult parsed;
        try {
            parsed = JUnitXmlReader.read(xml);
        } catch (IOException | RuntimeException e) {
            return observation(arm, CaseStatus.ERRORED, null,
                "Invalid JUnit XML: " + message(e), duration,
                metrics(result.exitCode(), null));
        }
        return classify(arm, parsed, duration, result.exitCode());
    }

    /**
     * Parse {@code --list-cases} JSON lines.
     *
     * @param stdout container output
     * @return discovered cases
     * @throws IllegalArgumentException when a line is not a case object
     */
    static List<EvalCaseDescriptor> parseCases(String stdout) {
        var cases = new ArrayList<EvalCaseDescriptor>();
        for (var line : stdout.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            try {
                var node = MAPPER.readTree(line);
                var evalSet = node == null ? null : node.get("evalSet");
                var caseName = node == null ? null : node.get("case");
                if (evalSet == null || !evalSet.isTextual()
                        || caseName == null || !caseName.isTextual()) {
                    throw new IllegalArgumentException("Invalid eval case line: " + line);
                }
                cases.add(new EvalCaseDescriptor(evalSet.asText(), caseName.asText()));
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("Invalid eval case line: " + line, e);
            }
        }
        return List.copyOf(cases);
    }

    private EvalObservation classify(TaskPlan.Arm arm, JUnitXmlReader.JUnitResult parsed,
                                     long duration, int exitCode) {
        var metrics = metrics(exitCode, parsed);
        if (parsed.tests() == 0) {
            return observation(arm, CaseStatus.UNSCORED, null,
                "JUnit XML reported no tests", duration, metrics);
        }
        if (parsed.skipped() > 0) {
            return observation(arm, CaseStatus.SKIPPED, null,
                parsed.message().isEmpty() ? "test skipped" : parsed.message(), duration,
                metrics);
        }
        if (parsed.errors() > 0) {
            return observation(arm, CaseStatus.ERRORED, null,
                parsed.message().isEmpty() ? "JUnit XML reported errors" : parsed.message(),
                duration, metrics);
        }
        if (parsed.failures() > 0) {
            return observation(arm, CaseStatus.FAILED, 0.0,
                parsed.message().isEmpty() ? "JUnit XML reported failures" : parsed.message(),
                duration, metrics);
        }
        return observation(arm, CaseStatus.PASSED, 1.0, "", duration, metrics);
    }

    private static Map<String, Object> metrics(int exitCode,
                                               JUnitXmlReader.JUnitResult parsed) {
        var metrics = new LinkedHashMap<String, Object>();
        metrics.put("exitCode", exitCode);
        if (parsed != null) {
            metrics.put("tests", parsed.tests());
            metrics.put("failures", parsed.failures());
            metrics.put("errors", parsed.errors());
            metrics.put("skipped", parsed.skipped());
        }
        return metrics;
    }

    private static EvalObservation observation(TaskPlan.Arm arm, CaseStatus status,
                                               Double score, String reason, long duration,
                                               Map<String, Object> metrics) {
        return new EvalObservation(arm.evalCase().evalSet(), arm.evalCase().caseName(),
            arm.variant(), arm.run(), status, score, reason, null, duration, null, metrics);
    }

    private static String describe(DockerProcess.Result result) {
        var stderr = result.stderr();
        if (!stderr.isBlank()) {
            return "exit " + result.exitCode() + ": " + stderr.trim();
        }
        return "exit " + result.exitCode() + ": " + result.stdout().trim();
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
