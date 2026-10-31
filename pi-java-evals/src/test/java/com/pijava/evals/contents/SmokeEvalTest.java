package com.pijava.evals.contents;

import com.pijava.evals.EvalTestRuns;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 32（docs/44 §10）：smoke eval 的用例与大小写不敏感判定。
 */
class SmokeEvalTest {

    @Test
    void exposesSingleToolFreeCase() {
        var suite = new SmokeEval();
        assertThat(suite.harnessCases()).singleElement().satisfies(evalCase -> {
            assertThat(evalCase.prompt()).isEqualTo(SmokeEval.PROMPT);
            assertThat(evalCase.expected()).isEqualTo(SmokeEval.EXPECTED);
        });
        var options = suite.harnessOptions(
            com.pijava.evals.plan.DocumentationVariant.WITH_DOCS, null);
        assertThat(options.noTools()).isTrue();
        assertThat(options.tools()).isNull();
    }

    @Test
    void judgeAcceptsParisCaseInsensitively() {
        var judge = new SmokeEval().judges().get(0);
        var run = EvalTestRuns.response("The capital of France is PARIS.");
        assertThat(judge.evaluate(run, SmokeEval.EXPECTED).score()).isEqualTo(1.0);
        assertThat(judge.evaluate(EvalTestRuns.response("London."), SmokeEval.EXPECTED).score())
            .isZero();
        assertThat(judge.evaluate(EvalTestRuns.response(""), SmokeEval.EXPECTED).score())
            .isZero();
    }
}
