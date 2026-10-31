package com.pijava.evals.contents;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.pijava.evals.api.EvalCase;
import com.pijava.evals.api.EvalSuite;
import com.pijava.evals.harness.HarnessCase;
import com.pijava.evals.harness.HarnessOptions;
import com.pijava.evals.harness.HarnessRun;
import com.pijava.evals.judge.Judge;
import com.pijava.evals.judge.JudgeResult;
import com.pijava.evals.plan.DocumentationVariant;
import com.pijava.evals.plan.EvalCaseDescriptor;

/**
 * 批 32（docs/44 §8.5）：最小 smoke eval —— pi {@code smoke.eval.ts} 对位。
 *
 * <p>单用例、空工具集；回复含 {@code Paris}（大小写不敏感）即通过。该 eval
 * 只走 host 模式（{@code EvalCli --host}），不参与 docs-lift 的配对报告。</p>
 */
public final class SmokeEval implements EvalSuite {

    /** Suite name. */
    public static final String SUITE_NAME = "smoke";

    /** Case name. */
    public static final String CASE_NAME = "capital-of-france";

    /** Prompt text. */
    public static final String PROMPT = "What is the capital of France?";

    /** Case-insensitive substring the reply must contain. */
    public static final String EXPECTED = "Paris";

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
        return List.of(new ContainsJudge());
    }

    @Override
    public Double judgeThreshold() {
        return 1.0;
    }

    @Override
    public List<HarnessCase> harnessCases() {
        return List.of(new HarnessCase(
            new EvalCaseDescriptor(SUITE_NAME, CASE_NAME), PROMPT, EXPECTED));
    }

    @Override
    public HarnessOptions harnessOptions(DocumentationVariant variant,
                                         Path sessionArtifactDir) {
        return HarnessOptions.builder()
            .name(SUITE_NAME)
            .noTools(true)
            .noExtensions(true)
            .noSession(true)
            .sessionArtifactDir(sessionArtifactDir)
            .build();
    }

    /** Case-insensitive substring match. */
    private static final class ContainsJudge implements Judge {

        @Override
        public JudgeResult evaluate(HarnessRun run, Object expected) {
            var needle = String.valueOf(expected == null ? EXPECTED : expected)
                .toLowerCase(Locale.ROOT);
            var response = run == null || run.response() == null ? "" : run.response();
            var found = response.toLowerCase(Locale.ROOT).contains(needle);
            return new JudgeResult(found ? 1.0 : 0.0,
                found ? "response contains " + needle : "response does not contain " + needle,
                Map.of("contains", found));
        }
    }
}
