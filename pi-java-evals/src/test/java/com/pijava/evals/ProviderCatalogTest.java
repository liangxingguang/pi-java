package com.pijava.evals;

import com.pijava.ai.provider.FauxProvider;
import com.pijava.evals.api.DefaultEvalContext;
import com.pijava.evals.conformance.ProviderCatalogConformance;
import com.pijava.evals.runner.EvalRunner;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs catalog conformance against the 17 built-in providers.
 */
class ProviderCatalogTest {

    @Test
    void catalogConformancePasses() {
        var ctx = DefaultEvalContext.of(FauxProvider.text("unused"));
        var results = new EvalRunner().run(new ProviderCatalogConformance(), ctx);
        var failures = results.stream().filter(r -> !r.passed()).toList();
        assertThat(failures)
            .as(failures.stream().map(r -> r.caseName() + ": " + r.detail()).toList().toString())
            .isEmpty();
        assertThat(results).hasSize(3);
    }
}
