package com.pijava.evals.runner;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.pijava.evals.api.EvalCase;
import com.pijava.evals.api.EvalContext;
import com.pijava.evals.api.EvalReporter;
import com.pijava.evals.api.EvalResult;
import com.pijava.evals.api.EvalSuite;

/**
 * Executes {@link EvalSuite} instances and collects {@link EvalResult}s.
 */
public final class EvalRunner {

    /**
     * Run every case in {@code suite}.
     *
     * @param suite suite to run
     * @param ctx   shared context
     * @return results in suite order
     */
    public List<EvalResult> run(EvalSuite suite, EvalContext ctx) {
        return run(suite, ctx, result -> { });
    }

    /**
     * Run every case in {@code suite}, notifying {@code reporter}.
     *
     * @param suite    suite to run
     * @param ctx      shared context
     * @param reporter result listener
     * @return results in suite order
     */
    public List<EvalResult> run(EvalSuite suite, EvalContext ctx, EvalReporter reporter) {
        var results = new ArrayList<EvalResult>();
        for (EvalCase evalCase : suite.cases()) {
            var started = System.nanoTime();
            EvalResult result;
            try {
                result = evalCase.run(ctx);
            } catch (RuntimeException | Error e) {
                var elapsed = Duration.ofNanos(System.nanoTime() - started);
                var message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                result = EvalResult.failed(evalCase.name(), message, elapsed);
            }
            reporter.onResult(result);
            results.add(result);
        }
        var copy = List.copyOf(results);
        reporter.onSuite(suite.name(), copy);
        return copy;
    }

    /**
     * Run every suite, discarding per-suite lists.
     *
     * @param suites suites to run
     * @param ctx    shared context
     */
    public void runAll(List<EvalSuite> suites, EvalContext ctx) {
        for (EvalSuite suite : suites) {
            run(suite, ctx);
        }
    }
}
