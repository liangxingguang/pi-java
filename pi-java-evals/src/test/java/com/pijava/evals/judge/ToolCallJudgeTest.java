package com.pijava.evals.judge;

import java.util.List;
import java.util.Map;

import com.pijava.evals.EvalTestRuns;
import com.pijava.evals.harness.ToolCallRecord;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 批 32（docs/44 §4）：{@link ToolCallJudge} 的顺序、数量与参数匹配。 */
class ToolCallJudgeTest {

    private final ToolCallJudge judge = new ToolCallJudge();

    @Test
    void matchesOrderedCallsWithArguments() {
        var calls = List.of(EvalTestRuns.ok("hello", Map.of("name", "Bob")));
        var result = judge.evaluate(EvalTestRuns.response("Hello, Bob!", calls),
            List.of(Map.of("name", "hello", "arguments", Map.of("name", "Bob"))));
        assertThat(result.score()).isEqualTo(1.0);
    }

    @Test
    void acceptsNameOnlyExpectations() {
        var calls = List.of(EvalTestRuns.ok("hello", Map.of("name", "Bob")));
        var result = judge.evaluate(EvalTestRuns.response("Hello, Bob!", calls), List.of("hello"));
        assertThat(result.score()).isEqualTo(1.0);
    }

    @Test
    void rejectsMissingAndExtraCalls() {
        var none = judge.evaluate(EvalTestRuns.response("done"), List.of("hello"));
        assertThat(none.score()).isZero();
        assertThat(none.rationale()).contains("expected [hello]").contains("observed []");

        var extra = judge.evaluate(EvalTestRuns.response("done",
            List.of(EvalTestRuns.ok("hello", Map.of("name", "Bob")),
                EvalTestRuns.ok("read", Map.of("path", "x")))), List.of("hello"));
        assertThat(extra.score()).isZero();
        assertThat(extra.rationale()).contains("[hello]").contains("[hello, read]");
    }

    @Test
    void rejectsWrongOrder() {
        var calls = List.of(EvalTestRuns.ok("read", Map.of()),
            EvalTestRuns.ok("hello", Map.of("name", "Bob")));
        var result = judge.evaluate(EvalTestRuns.response("done", calls),
            List.of("hello", "read"));
        assertThat(result.score()).isZero();
        assertThat(result.rationale()).contains("call #0");
    }

    @Test
    void rejectsFailedCalls() {
        var calls = List.of(new ToolCallRecord("id", "hello", Map.of("name", "Bob"), "error"));
        var result = judge.evaluate(EvalTestRuns.response("failed", calls), List.of("hello"));
        assertThat(result.score()).isZero();
        assertThat(result.rationale()).contains("observed []");
    }

    @Test
    void rejectsArgumentMismatch() {
        var calls = List.of(EvalTestRuns.ok("hello", Map.of("name", "Alice")));
        var result = judge.evaluate(EvalTestRuns.response("Hi", calls),
            List.of(Map.of("name", "hello", "arguments", Map.of("name", "Bob"))));
        assertThat(result.score()).isZero();
        assertThat(result.rationale()).contains("$[0].arguments.name");
    }

    @Test
    void rejectsMalformedExpectation() {
        var result = judge.evaluate(EvalTestRuns.response("done"), "hello");
        assertThat(result.score()).isZero();
        assertThat(result.rationale()).contains("expected must be a list");
    }
}
