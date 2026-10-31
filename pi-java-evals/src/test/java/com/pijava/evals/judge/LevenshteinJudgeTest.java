package com.pijava.evals.judge;

import com.pijava.evals.EvalTestRuns;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** 批 32（docs/44 §4）：{@link LevenshteinJudge} 的归一化与距离边界。 */
class LevenshteinJudgeTest {

    private final LevenshteinJudge judge = new LevenshteinJudge();

    @Test
    void emptyStringsAreEqual() {
        assertThat(judge.evaluate(EvalTestRuns.response(""), "").score()).isEqualTo(1.0);
    }

    @Test
    void identicalTextScoresFull() {
        assertThat(judge.evaluate(EvalTestRuns.response("42.2%/272k (auto)"),
            "42.2%/272k (auto)").score()).isEqualTo(1.0);
    }

    @Test
    void normalizesCaseAndWhitespace() {
        assertThat(judge.evaluate(EvalTestRuns.response("  Hello   World  "), "hello world").score())
            .isEqualTo(1.0);
    }

    @Test
    void computesBoundaryDistance() {
        assertThat(LevenshteinJudge.distance("kitten", "sitting")).isEqualTo(3);
        assertThat(LevenshteinJudge.distance("", "abc")).isEqualTo(3);
        assertThat(LevenshteinJudge.distance("abc", "")).isEqualTo(3);
    }

    @Test
    void scoreIsNormalizedDistance() {
        var result = judge.evaluate(EvalTestRuns.response("kitten"), "sitting");
        assertThat(result.score()).isCloseTo(4.0 / 7.0, within(1e-9));
        assertThat(result.metadata()).containsEntry("distance", 3).containsEntry("maxLength", 7);
    }

    @Test
    void completelyDifferentTextScoresZero() {
        assertThat(judge.evaluate(EvalTestRuns.response("abcd"), "wxyz").score()).isZero();
    }
}
