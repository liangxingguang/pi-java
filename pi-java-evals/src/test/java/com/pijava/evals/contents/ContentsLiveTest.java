package com.pijava.evals.contents;

import com.pijava.evals.api.EvalSuite;
import com.pijava.evals.harness.HarnessOptions;
import com.pijava.evals.harness.PiCodingAgentHarness;
import com.pijava.evals.plan.DocumentationVariant;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 32（docs/44 §10）：模型作者／扩展作者／smoke／TUI 四类 eval 的活模型用例；
 * 默认跳过。
 */
@Tag("EVAL")
class ContentsLiveTest {

    @Test
    void modelsDocsCaseScoresPerfectly() {
        runSingle(new ModelsDocsEval());
    }

    @Test
    void extensionsDocsCaseScoresPerfectly() {
        requireEnvironment();
        var suite = new ExtensionsDocsEval();
        var evalCase = suite.harnessCases().get(0);
        var run = new PiCodingAgentHarness(options(suite)).run(ExtensionsDocsEval.steps());
        for (var judge : suite.judges()) {
            var judgement = judge.evaluate(run, evalCase.expected());
            assertThat(judgement.score()).as(judgement.rationale()).isEqualTo(1.0);
        }
    }

    @Test
    void smokeCaseScoresPerfectly() {
        runSingle(new SmokeEval());
    }

    @Test
    void tuiDocsCaseMeetsThreshold() {
        requireEnvironment();
        var suite = new TuiDocsEval();
        var evalCase = suite.harnessCases().get(0);
        var run = new PiCodingAgentHarness(options(suite)).run(evalCase.prompt());
        var judgement = suite.judges().get(0).evaluate(run, evalCase.expected());
        assertThat(judgement.score()).as(judgement.rationale())
            .isGreaterThanOrEqualTo(TuiDocsEval.THRESHOLD);
    }

    private static void runSingle(EvalSuite suite) {
        requireEnvironment();
        var evalCase = suite.harnessCases().get(0);
        var run = new PiCodingAgentHarness(options(suite)).run(evalCase.prompt());
        for (var judge : suite.judges()) {
            var judgement = judge.evaluate(run, evalCase.expected());
            assertThat(judgement.score()).as(judgement.rationale()).isEqualTo(1.0);
        }
    }

    private static HarnessOptions options(EvalSuite suite) {
        var provider = System.getenv("PI_PROVIDER");
        var model = System.getenv("PI_MODEL");
        return suite.harnessOptions(DocumentationVariant.WITH_DOCS, null)
            .toBuilder().provider(provider).model(model).build();
    }

    private static void requireEnvironment() {
        Assumptions.assumeTrue(
            Boolean.parseBoolean(System.getProperty("pi.eval.run", "false")),
            "set -Dpi.eval.run=true to run host evals");
        var provider = System.getenv("PI_PROVIDER");
        var model = System.getenv("PI_MODEL");
        Assumptions.assumeTrue(
            provider != null && !provider.isBlank() && model != null && !model.isBlank(),
            "set PI_PROVIDER and PI_MODEL");
    }
}
