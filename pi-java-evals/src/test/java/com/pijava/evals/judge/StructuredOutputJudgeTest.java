package com.pijava.evals.judge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pijava.evals.EvalTestRuns;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 批 32（docs/44 §4）：{@link StructuredOutputJudge} 的严格匹配与多余键语义。 */
class StructuredOutputJudgeTest {

    private final StructuredOutputJudge strict = new StructuredOutputJudge();

    @Test
    void matchesNestedObject() {
        var expected = Map.of("verdict", "match", "nested", Map.of("ok", true));
        var result = strict.evaluate(EvalTestRuns.response(
            "{\"verdict\":\"match\",\"nested\":{\"ok\":true}}"), expected);
        assertThat(result.score()).isEqualTo(1.0);
    }

    @Test
    void rejectsExtraKeysByDefault() {
        var expected = Map.of("verdict", "match");
        var result = strict.evaluate(EvalTestRuns.response(
            "{\"verdict\":\"match\",\"extra\":1}"), expected);
        assertThat(result.score()).isZero();
        assertThat(result.rationale()).contains("$.extra").contains("(missing)");
    }

    @Test
    void allowsExtraKeysWhenConfigured() {
        var expected = Map.of("verdict", "match");
        var result = new StructuredOutputJudge(true).evaluate(EvalTestRuns.response(
            "{\"verdict\":\"match\",\"extra\":1}"), expected);
        assertThat(result.score()).isEqualTo(1.0);
    }

    @Test
    void reportsFirstDifferencePath() {
        var expected = Map.of("verdict", "match", "nested", Map.of("ok", true));
        var result = strict.evaluate(EvalTestRuns.response(
            "{\"verdict\":\"match\",\"nested\":{\"ok\":false}}"), expected);
        assertThat(result.score()).isZero();
        assertThat(result.rationale()).contains("$.nested.ok");
    }

    @Test
    void comparesNumbersWithTolerance() {
        var expected = new LinkedHashMap<String, Object>();
        expected.put("value", 1.0);
        var result = strict.evaluate(EvalTestRuns.response("{\"value\":1.0000000001}"), expected);
        assertThat(result.score()).isEqualTo(1.0);
    }

    @Test
    void comparesTextVerbatimWhenExpectedIsString() {
        assertThat(strict.evaluate(EvalTestRuns.response("Hello, Bob!"), "Hello, Bob!").score())
            .isEqualTo(1.0);
        var mismatch = strict.evaluate(EvalTestRuns.response("Hello, Bob!"), "hello bob");
        assertThat(mismatch.score()).isZero();
        assertThat(mismatch.rationale()).contains("text mismatch");
    }

    @Test
    void unwrapsExpectedOutputFromCombinedExpectation() {
        var expected = Map.of("expectedOutput", Map.of("verdict", "match"),
            "expectedTools", List.of("submit_documentation_audit"));
        var result = strict.evaluate(EvalTestRuns.response("{\"verdict\":\"match\"}"), expected);
        assertThat(result.score()).isEqualTo(1.0);
    }

    @Test
    void reportsArrayLengthMismatch() {
        var result = strict.evaluate(EvalTestRuns.response("[1,2,3]"), List.of(1, 2));
        assertThat(result.score()).isZero();
        assertThat(result.rationale()).contains("$.length");
    }
}
