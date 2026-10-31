package com.pijava.evals.contents;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pijava.evals.EvalTestRuns;
import com.pijava.evals.fixtures.EvalFixtures;
import com.pijava.evals.harness.PiCodingAgentHarness;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 32（docs/44 §10）：模型作者 eval 的用例、工具与判分（不跑模型）。
 */
class ModelsDocsEvalTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void exposesOneCaseWithTheModelTool() {
        var suite = new ModelsDocsEval();
        assertThat(suite.harnessCases()).singleElement().satisfies(evalCase -> {
            assertThat(evalCase.descriptor().caseName())
                .isEqualTo(ModelsDocsEval.CASE_NAME);
            assertThat(evalCase.prompt()).contains(EvalFixtures.INSPECT_ADDED_MODEL_TOOL);
        });
        var options = suite.harnessOptions(
            com.pijava.evals.plan.DocumentationVariant.WITH_DOCS, null);
        assertThat(options.tools()).isEqualTo(PiCodingAgentHarness.DOCUMENTATION_EVAL_TOOLS);
        assertThat(options.customTools()).hasSize(1);
        assertThat(options.customTools().get(0).name())
            .isEqualTo(EvalFixtures.INSPECT_ADDED_MODEL_TOOL);
    }

    @Test
    void judgesAcceptExpectedObservation() throws JsonProcessingException {
        var suite = new ModelsDocsEval();
        var run = EvalTestRuns.response(
            MAPPER.writeValueAsString(EvalFixtures.addedModelExpectation()),
            List.of(EvalTestRuns.ok(EvalFixtures.INSPECT_ADDED_MODEL_TOOL, Map.of(
                "provider", EvalFixtures.ADDED_MODEL_PROVIDER,
                "model", EvalFixtures.ADDED_MODEL_ID))));
        for (var judge : suite.judges()) {
            assertThat(judge.evaluate(run, suite.harnessCases().get(0).expected()).score())
                .as(judge.getClass().getSimpleName()).isEqualTo(1.0);
        }
    }

    @Test
    void judgesRejectWrongOrMissingCalls() {
        var suite = new ModelsDocsEval();
        var expected = suite.harnessCases().get(0).expected();
        var missing = EvalTestRuns.response("{}");
        var wrongArguments = EvalTestRuns.response("{}",
            List.of(EvalTestRuns.ok(EvalFixtures.INSPECT_ADDED_MODEL_TOOL, Map.of(
                "provider", "acme", "model", EvalFixtures.ADDED_MODEL_ID))));
        var toolJudge = suite.judges().get(1);
        assertThat(toolJudge.evaluate(missing, expected).score()).isZero();
        assertThat(toolJudge.evaluate(wrongArguments, expected).score()).isZero();
    }
}
