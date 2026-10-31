package com.pijava.evals.docker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pijava.evals.plan.DocumentationVariant;
import com.pijava.evals.plan.EvalCaseDescriptor;
import com.pijava.evals.plan.TaskPlan;
import com.pijava.evals.report.CaseStatus;
import com.pijava.evals.report.EvalObservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 批 32（docs/44 §7.2）：{@link DockerEvalRunner} 的两镜像构建、cohort 校验与
 * JUnit XML 六态分类（假 {@link DockerProcess}）。
 */
class DockerEvalRunnerTest {

    private static final String PREFIX = "pi-java-evals:abc123def456";
    private static final EvalCaseDescriptor SMOKE =
        new EvalCaseDescriptor("contents", "smoke");

    @TempDir
    Path temp;

    private DockerContext context;
    private Path artifacts;
    private RecordingProcess process;
    private DockerEvalRunner runner;

    @BeforeEach
    void setUp() throws IOException {
        var repo = Files.createDirectories(temp.resolve("repo"));
        Files.createDirectories(repo.resolve("pi-java-evals").resolve("docker"));
        var auth = Files.writeString(temp.resolve("auth.json"), "{}");
        context = new DockerContext(repo, auth, PREFIX);
        artifacts = Files.createDirectories(temp.resolve("artifacts"));
        process = new RecordingProcess();
        runner = new DockerEvalRunner(context, process);
    }

    @Test
    void buildsBothTargets() {
        process.responder = command -> new DockerProcess.Result(0, "", "");
        runner.buildImages();
        assertThat(process.commands).hasSize(2);
        assertThat(process.commands.get(0)).contains("--target", "without-docs");
        assertThat(process.commands.get(1)).contains("--target", "with-docs");
    }

    @Test
    void buildFailureAborts() {
        process.responder = command -> new DockerProcess.Result(1, "", "boom");
        assertThatThrownBy(() -> runner.buildImages())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("docker build failed")
            .hasMessageContaining("boom");
        assertThat(process.commands).hasSize(1);
    }

    @Test
    void parsesListCasesJsonLines() {
        process.responder = command -> new DockerProcess.Result(0,
            "{\"evalSet\":\"contents\",\"case\":\"one\"}\n"
                + "{\"evalSet\":\"contents\",\"case\":\"two\"}\n", "");
        var cases = runner.discoverCases(
            new DockerImageSpec(PREFIX, DockerImageSpec.WITH_DOCS_TARGET));
        assertThat(cases).containsExactly(
            new EvalCaseDescriptor("contents", "one"),
            new EvalCaseDescriptor("contents", "two"));
    }

    @Test
    void rejectsBadDiscoveryLine() {
        assertThatThrownBy(() -> DockerEvalRunner.parseCases("not json\n"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid eval case line");
    }

    @Test
    void discoveryFailureAborts() {
        process.responder = command -> new DockerProcess.Result(3, "", "no image");
        assertThatThrownBy(() -> runner.discoverCases(
                new DockerImageSpec(PREFIX, DockerImageSpec.WITHOUT_DOCS_TARGET)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("docker --list-cases failed");
    }

    @Test
    void cohortMustMatch() {
        process.responder = command -> new DockerProcess.Result(0,
            command.get(command.size() - 2).endsWith("-without-docs")
                ? "{\"evalSet\":\"contents\",\"case\":\"one\"}\n"
                : "{\"evalSet\":\"contents\",\"case\":\"two\"}\n", "");
        assertThatThrownBy(() -> runner.verifyCohort())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Eval cohort mismatch");
    }

    @Test
    void cohortMatches() {
        process.responder = command -> new DockerProcess.Result(0,
            "{\"evalSet\":\"contents\",\"case\":\"smoke\"}\n", "");
        assertThat(runner.verifyCohort())
            .containsExactly(new EvalCaseDescriptor("contents", "smoke"));
    }

    @Test
    void missingXmlIsMissing() throws IOException {
        process.responder = command -> new DockerProcess.Result(1, "", "arm failed");
        var observation = runner.runTask(arm(), artifacts, "acme", "fixture-chat");
        assertThat(observation.status()).isEqualTo(CaseStatus.MISSING);
        assertThat(observation.score()).isNull();
        assertThat(observation.reason()).contains("JUnit XML not found");
    }

    @Test
    void badXmlIsErrored() throws IOException {
        process.responder = writeXml("<not-a-testsuite/>", 0);
        var observation = runner.runTask(arm(), artifacts, "acme", "fixture-chat");
        assertThat(observation.status()).isEqualTo(CaseStatus.ERRORED);
        assertThat(observation.reason()).contains("Invalid JUnit XML");
    }

    @Test
    void passingXmlScoresOne() throws IOException {
        process.responder = writeXml(
            "<testsuite tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\">"
                + "<testcase name=\"smoke\"/></testsuite>", 0);
        var observation = runner.runTask(arm(), artifacts, "acme", "fixture-chat");
        assertThat(observation.status()).isEqualTo(CaseStatus.PASSED);
        assertThat(observation.score()).isEqualTo(1.0);
        assertThat(observation.metrics()).containsEntry("exitCode", 0);
    }

    @Test
    void failingXmlScoresZero() throws IOException {
        process.responder = writeXml(
            "<testsuite tests=\"1\" failures=\"1\" errors=\"0\" skipped=\"0\">"
                + "<testcase name=\"smoke\"><failure message=\"expected Paris\"/>"
                + "</testcase></testsuite>", 0);
        var observation = runner.runTask(arm(), artifacts, "acme", "fixture-chat");
        assertThat(observation.status()).isEqualTo(CaseStatus.FAILED);
        assertThat(observation.score()).isEqualTo(0.0);
        assertThat(observation.reason()).isEqualTo("expected Paris");
    }

    @Test
    void skippedXmlIsSkipped() throws IOException {
        process.responder = writeXml(
            "<testsuite tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"1\">"
                + "<testcase name=\"smoke\"><skipped/></testcase></testsuite>", 0);
        var observation = runner.runTask(arm(), artifacts, "acme", "fixture-chat");
        assertThat(observation.status()).isEqualTo(CaseStatus.SKIPPED);
        assertThat(observation.score()).isNull();
    }

    @Test
    void errorXmlIsErrored() throws IOException {
        process.responder = writeXml(
            "<testsuite tests=\"1\" failures=\"0\" errors=\"1\" skipped=\"0\">"
                + "<testcase name=\"smoke\"><error message=\"boom\"/></testcase>"
                + "</testsuite>", 0);
        var observation = runner.runTask(arm(), artifacts, "acme", "fixture-chat");
        assertThat(observation.status()).isEqualTo(CaseStatus.ERRORED);
        assertThat(observation.reason()).isEqualTo("boom");
    }

    @Test
    void zeroTestsIsUnscored() throws IOException {
        process.responder = writeXml(
            "<testsuite tests=\"0\" failures=\"0\" errors=\"0\" skipped=\"0\"/>", 0);
        var observation = runner.runTask(arm(), artifacts, "acme", "fixture-chat");
        assertThat(observation.status()).isEqualTo(CaseStatus.UNSCORED);
        assertThat(observation.score()).isNull();
    }

    @Test
    void runTaskIssuesArmCommandWithRunNumber() throws IOException {
        process.responder = writeXml(
            "<testsuite tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\"/>", 0);
        runner.runTask(arm(), artifacts, "acme", "fixture-chat");
        assertThat(process.commands).hasSize(1);
        assertThat(process.commands.get(0))
            .contains("PI_EVAL_VARIANT=with_docs", "PI_EVAL_RUN=1", "--case", "smoke",
                "--testNamePattern", "^smoke$");
    }

    private TaskPlan.Arm arm() {
        return new TaskPlan.Arm(SMOKE, DocumentationVariant.WITH_DOCS, 1);
    }

    private Function<List<String>, DockerProcess.Result> writeXml(String xml, int exitCode) {
        return command -> {
            try {
                var dir = Files.createDirectories(
                    artifacts.resolve("tasks").resolve(arm().id()));
                Files.writeString(dir.resolve("junit.xml"), xml, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return new DockerProcess.Result(exitCode, "", "");
        };
    }

    private static final class RecordingProcess implements DockerProcess {

        private final List<List<String>> commands = new ArrayList<>();
        private Function<List<String>, DockerProcess.Result> responder =
            command -> new DockerProcess.Result(0, "", "");

        @Override
        public DockerProcess.Result run(List<String> command) {
            commands.add(List.copyOf(command));
            return responder.apply(command);
        }
    }
}
