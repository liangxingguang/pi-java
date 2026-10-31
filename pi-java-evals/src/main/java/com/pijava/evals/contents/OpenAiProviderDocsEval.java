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
 * 批 32（docs/44 §8.4）：OpenAI 兼容 provider 集成 eval —— pi
 * {@code openai-provider.eval.ts} 对位。
 *
 * <p>与 {@link CustomProviderDocsEval} 同形，只是车道用 {@code openai-completions}，
 * 服务端路由为 {@code /openai/v1/chat/completions}。</p>
 */
public final class OpenAiProviderDocsEval implements EvalSuite {

    /** Suite name. */
    public static final String SUITE_NAME = "openai-provider-docs";

    /** Case name. */
    public static final String CASE_NAME = "configures-openai-provider";

    private static final String PROMPT = """
        Configure a local OpenAI-compatible provider so the runtime can talk to it.

        Read the project documentation about custom providers, then call the
        inspect_provider tool exactly once with:
        - provider: fixture-openai
        - api: the OpenAI-compatible protocol name documented for custom providers
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
                    "provider", EvalFixtures.OPENAI_PROVIDER,
                    "api", "openai-completions",
                    "model", EvalFixtures.MODEL_ID))));
    }
}
