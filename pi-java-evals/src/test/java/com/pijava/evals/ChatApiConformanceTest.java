package com.pijava.evals;

import com.pijava.evals.api.DefaultEvalContext;
import com.pijava.evals.api.EvalCase;
import com.pijava.evals.api.EvalContext;
import com.pijava.evals.conformance.ChatApiConformanceSuite;
import com.pijava.evals.conformance.ConformanceFixtures;
import com.pijava.evals.runner.EvalRunner;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

/**
 * Expands {@link ChatApiConformanceSuite} against offline Faux fixtures.
 */
class ChatApiConformanceTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void casePasses(String name, EvalCase evalCase, EvalContext ctx) {
        var result = evalCase.run(ctx);
        assertThat(result.passed()).as("%s: %s", name, result.detail()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void runnerRecordsPass(String name, EvalCase evalCase, EvalContext ctx) {
        var suite = new EvalSuiteAdapter(evalCase);
        var results = new EvalRunner().run(suite, ctx);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).passed()).as("%s: %s", name, results.get(0).detail()).isTrue();
    }

    static Stream<Arguments> cases() {
        var suite = new ChatApiConformanceSuite();
        return suite.cases().stream().map(evalCase -> Arguments.of(
            evalCase.name(),
            evalCase,
            DefaultEvalContext.of(ConformanceFixtures.forCase(evalCase.name()))));
    }

    private static final class EvalSuiteAdapter implements com.pijava.evals.api.EvalSuite {
        private final EvalCase evalCase;

        private EvalSuiteAdapter(EvalCase evalCase) {
            this.evalCase = evalCase;
        }

        @Override
        public String name() {
            return evalCase.name();
        }

        @Override
        public java.util.List<EvalCase> cases() {
            return java.util.List.of(evalCase);
        }
    }
}
