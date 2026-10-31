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
 * 批 32（docs/44 §8.4）：自定义 provider 集成 eval —— pi
 * {@code custom-provider.eval.ts} 对位。
 *
 * <p>模型读文档后调用 {@code inspect_provider}，把自定义流式 provider 配进
 * {@code models.json} 并跑一次 32 token 探针。判定同时要求工具参数正确
 * （provider/api/model）与最终回复逐字等于探针 JSON。</p>
 */
public final class CustomProviderDocsEval implements EvalSuite {

    /** Suite name. */
    public static final String SUITE_NAME = "custom-provider-docs";

    /** Case name. */
    public static final String CASE_NAME = "configures-acme-provider";

    private static final String PROMPT = """
        Configure a local custom streaming provider so the runtime can talk to it.

        Read the project documentation about custom providers, then call the
        inspect_provider tool exactly once with:
        - provider: acme
        - api: the streaming protocol name documented for custom providers
        - model: fixture-chat

        Reply with exactly the JSON object returned by the tool and nothing else.
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
            .customTools(List.of(EvalFixtures.inspectProviderTool()))
            .noExtensions(true)
            .sessionArtifactDir(sessionArtifactDir)
            .build();
    }

    private static Map<String, Object> expected() {
        return Map.of(
            "expectedOutput", EvalFixtures.probeExpectation(),
            "expectedTools", List.of(Map.of(
                "name", EvalFixtures.INSPECT_PROVIDER_TOOL,
                "arguments", Map.of(
                    "provider", EvalFixtures.CUSTOM_PROVIDER,
                    "api", "pi-messages",
                    "model", EvalFixtures.MODEL_ID))));
    }
}
