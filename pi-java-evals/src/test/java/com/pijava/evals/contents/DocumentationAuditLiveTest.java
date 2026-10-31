package com.pijava.evals.contents;

import com.pijava.evals.harness.PiCodingAgentHarness;
import com.pijava.evals.plan.DocumentationVariant;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 32（docs/44 §10）：文档审计的活模型用例（默认跳过）。
 */
@Tag("EVAL")
class DocumentationAuditLiveTest {

    @Test
    void auditsFirstDocumentationPage() {
        Assumptions.assumeTrue(
            Boolean.parseBoolean(System.getProperty("pi.eval.run", "false")),
            "set -Dpi.eval.run=true to run host evals");
        var provider = System.getenv("PI_PROVIDER");
        var model = System.getenv("PI_MODEL");
        Assumptions.assumeTrue(
            provider != null && !provider.isBlank() && model != null && !model.isBlank(),
            "set PI_PROVIDER and PI_MODEL");
        var suite = new DocumentationAuditEval();
        var evalCase = suite.harnessCases().get(0);
        var options = suite.harnessOptions(DocumentationVariant.WITH_DOCS, null)
            .toBuilder().provider(provider).model(model).build();
        var run = new PiCodingAgentHarness(options).run(evalCase.prompt());
        var judgement = suite.judges().get(0).evaluate(run, evalCase.expected());
        assertThat(judgement.score()).as(judgement.rationale()).isEqualTo(1.0);
    }
}
