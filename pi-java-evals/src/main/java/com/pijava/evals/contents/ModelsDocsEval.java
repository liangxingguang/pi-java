package com.pijava.evals.contents;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.pijava.evals.api.EvalCase;
import com.pijava.evals.api.EvalSuite;
import com.pijava.evals.fixtures.EvalFixtures;
import com.pijava.evals.harness.HarnessCase;
import com.pijava.evals.harness.HarnessOptions;
import com.pijava.evals.harness.PiCodingAgentHarness;
import com.pijava.evals.judge.Judge;
import com.pijava.evals.judge.StructuredOutputJudge;
import com.pijava.evals.judge.ToolCallJudge;
import com.pijava.evals.plan.DocumentationVariant;
import com.pijava.evals.plan.EvalCaseDescriptor;

/**
 * 批 32（docs/44 §8.3）：模型作者 eval —— pi {@code models.eval.ts} 对位。
 *
 * <p>模型读文档后调用 {@code inspect_added_model}，在全局 {@code models.json}
 * 增加 {@code openai/fixture-chat}（名称、reasoning、text、0 成本、32768/4096），
 * 且不替换既有模型。判定同时要求工具参数正确与最终回复逐字等于观测 JSON。</p>
 */
public final class ModelsDocsEval implements EvalSuite {

    /** Suite name. */
    public static final String SUITE_NAME = "models-docs";

    /** Case name. */
    public static final String CASE_NAME = "adds-fixture-chat";

    private static final String PROMPT = """
        Add a new model to the global models.json configuration.

        Read the project documentation about models.json, then call the
        inspect_added_model tool exactly once with:
        - provider: openai
        - model: fixture-chat

        The model must be named "Fixture Chat", declare reasoning support, accept
        text input, cost zero, use a 32768 context window and 4096 max output tokens.
        Existing models must be preserved. Reply with exactly the JSON object returned
        by the tool and nothing else.
        """;

    @Override
    public String name() {
        return SUITE_NAME;
    }

    @Override
    public List<EvalCase> cases() {
        return List.of();
    }

    @Override
    public List<Judge> judges() {
        return List.of(new StructuredOutputJudge(), new ToolCallJudge());
    }

    @Override
    public Double judgeThreshold() {
        return 1.0;
    }

    @Override
    public List<HarnessCase> harnessCases() {
        return List.of(new HarnessCase(
            new EvalCaseDescriptor(SUITE_NAME, CASE_NAME), PROMPT, expected()));
    }

    @Override
    public HarnessOptions harnessOptions(DocumentationVariant variant,
                                         Path sessionArtifactDir) {
        return HarnessOptions.builder()
            .name(SUITE_NAME)
            .tools(PiCodingAgentHarness.DOCUMENTATION_EVAL_TOOLS)
            .customTools(List.of(EvalFixtures.inspectAddedModelTool()))
            .noExtensions(true)
            .sessionArtifactDir(sessionArtifactDir)
            .build();
    }

    private static Map<String, Object> expected() {
        return Map.of(
            "expectedOutput", EvalFixtures.addedModelExpectation(),
            "expectedTools", List.of(Map.of(
                "name", EvalFixtures.INSPECT_ADDED_MODEL_TOOL,
                "arguments", Map.of(
                    "provider", EvalFixtures.ADDED_MODEL_PROVIDER,
                    "model", EvalFixtures.ADDED_MODEL_ID))));
    }
}
