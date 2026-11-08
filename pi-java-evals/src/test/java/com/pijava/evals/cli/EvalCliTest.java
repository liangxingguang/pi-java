package com.pijava.evals.cli;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.pijava.evals.api.EvalCase;
import com.pijava.evals.api.EvalSuite;
import com.pijava.evals.harness.HarnessCase;
import com.pijava.evals.plan.DocumentationVariant;
import com.pijava.evals.plan.EvalCaseDescriptor;
import com.pijava.evals.report.CaseStatus;
import com.pijava.evals.report.EvalObservation;
import com.pijava.evals.report.ObservationJson;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 批 32（docs/44 §6）：CLI 编排、cohort、退出码与 artifact 落盘。 */
class EvalCliTest {

    @TempDir
    Path artifactBase;

    @Test
    void docsLiftRunWritesArtifactsAndReturnsZero() throws Exception {
        var layout = ArtifactLayout.create(artifactBase);
        var cli = new EvalCli(List.of(suite("set", "case")), passing(), layout, sink());
        var code = cli.execute(options(List.of(), null, false));
        assertThat(code).isZero();
        assertThat(ObservationJson.read(layout.observations())).hasSize(2);
        assertThat(layout.protocol()).exists();
        assertThat(layout.expectedRuns()).exists();
        assertThat(layout.reportJson()).exists();
        assertThat(layout.reportText()).exists();
        assertThat(Files.readString(layout.reportText(), StandardCharsets.UTF_8))
            .contains("Documentation Eval Comparisons")
            .contains("Pairs  1/1 eligible")
            .contains("Pass rate  +0.0 pp")
            .contains("Operational totals");
        assertThat(Files.readString(layout.expectedRuns(), StandardCharsets.UTF_8))
            .contains("with_docs").contains("without_docs");
    }

    @Test
    void erroredTreatmentArmBlocksAndReturnsOne() throws Exception {
        var layout = ArtifactLayout.create(artifactBase);
        var runner = (EvalCli.ArmRunner) (suite, evalCase, variant, run, dir) -> {
            if (variant == DocumentationVariant.WITH_DOCS) {
                throw new IllegalStateException("boom");
            }
            return new EvalCli.ArmResult(
                observation(evalCase.descriptor(), variant, run, CaseStatus.PASSED, 1.0), null);
        };
        var code = new EvalCli(List.of(suite("set", "case")), runner, layout, sink())
            .execute(options(List.of(), null, false));
        assertThat(code).isEqualTo(1);
        assertThat(ObservationJson.read(layout.observations())).hasSize(2);
        assertThat(Files.readString(layout.reportText(), StandardCharsets.UTF_8))
            .contains("Pass rate  withheld because pairs are blocked")
            .contains("Blocked pairs")
            .contains("with_docs errored");
    }

    @Test
    void hostModeFailsWhenAnyCaseFails() throws Exception {
        var layout = ArtifactLayout.create(artifactBase);
        var runner = (EvalCli.ArmRunner) (suite, evalCase, variant, run, dir) ->
            new EvalCli.ArmResult(
                observation(evalCase.descriptor(), variant, run, CaseStatus.FAILED, 0.0), null);
        var code = new EvalCli(List.of(suite("set", "case")), runner, layout, sink())
            .execute(options(List.of(), null, true));
        assertThat(code).isEqualTo(1);
        assertThat(ObservationJson.read(layout.observations())).hasSize(1);
        assertThat(Files.readString(layout.reportText(), StandardCharsets.UTF_8))
            .contains("failed-or-errored=1");
    }

    @Test
    void cohortMismatchThrows() throws Exception {
        var layout = ArtifactLayout.create(artifactBase);
        var mismatch = new FakeSuite("set", cases("case"), true);
        assertThatThrownBy(() -> new EvalCli(List.of(mismatch), passing(), layout, sink())
            .execute(options(List.of(), null, false)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cohort mismatch");
    }

    @Test
    void testNamePatternAndCaseSelectorFilterCases() throws Exception {
        var layout = ArtifactLayout.create(artifactBase);
        var cli = new EvalCli(List.of(new FakeSuite("set", cases("alpha", "beta"), false)),
            passing(), layout, sink());
        cli.execute(options(List.of(), "alpha", false));
        assertThat(ObservationJson.read(layout.observations())).hasSize(2);

        var second = ArtifactLayout.create(artifactBase);
        cli = new EvalCli(List.of(new FakeSuite("set", cases("alpha", "beta"), false)),
            passing(), second, sink());
        cli.execute(options(List.of("set/beta"), null, false));
        assertThat(ObservationJson.read(second.observations())).hasSize(2);
        assertThat(ObservationJson.read(second.observations()))
            .allMatch(observation -> observation.caseName().equals("beta"));
    }

    @Test
    void requiresModelSelectionWhenCasesExist() throws Exception {
        var layout = ArtifactLayout.create(artifactBase);
        var cli = new EvalCli(List.of(suite("set", "case")), passing(), layout, sink());
        assertThatThrownBy(() -> cli.execute(new EvalCliOptions(List.of(), null, null, null,
            1, false)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("provider");
    }

    private static EvalCliOptions options(List<String> selectors, String pattern,
                                          boolean host) {
        return new EvalCliOptions(selectors, pattern, "openai", "gpt-4o", 1, host);
    }

    private static EvalCli.ArmRunner passing() {
        return (suite, evalCase, variant, run, dir) -> new EvalCli.ArmResult(
            observation(evalCase.descriptor(), variant, run, CaseStatus.PASSED, 1.0), null);
    }

    private static EvalObservation observation(EvalCaseDescriptor descriptor,
                                               DocumentationVariant variant, int run,
                                               CaseStatus status, Double score) {
        return new EvalObservation(descriptor.evalSet(), descriptor.caseName(), variant, run,
            status, score, "", null, 1L, null, Map.of());
    }

    private static EvalSuite suite(String evalSet, String caseName) {
        return new FakeSuite(evalSet, cases(caseName), false);
    }

    private static List<HarnessCase> cases(String... names) {
        return java.util.Arrays.stream(names)
            .map(name -> new HarnessCase(new EvalCaseDescriptor("set", name), "prompt " + name,
                name))
            .toList();
    }

    private static PrintStream sink() {
        return new PrintStream(java.io.OutputStream.nullOutputStream());
    }

    private static final class FakeSuite implements EvalSuite {

        private final String name;
        private final List<HarnessCase> cases;
        private final boolean cohortMismatch;

        FakeSuite(String name, List<HarnessCase> cases, boolean cohortMismatch) {
            this.name = name;
            this.cases = List.copyOf(cases);
            this.cohortMismatch = cohortMismatch;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public List<EvalCase> cases() {
            return List.of();
        }

        @Override
        public List<HarnessCase> harnessCases() {
            return cases;
        }

        @Override
        public List<EvalCaseDescriptor> discoverCases(DocumentationVariant variant) {
            var descriptors = cases.stream().map(HarnessCase::descriptor).toList();
            if (cohortMismatch && variant == DocumentationVariant.WITHOUT_DOCS) {
                return descriptors.stream().skip(1).toList();
            }
            return descriptors;
        }
    }
}
