package com.pijava.evals.cli;

import java.nio.file.Path;
import java.util.Map;

import com.pijava.evals.api.EvalSuite;
import com.pijava.evals.harness.DocumentationVariants;
import com.pijava.evals.harness.HarnessCase;
import com.pijava.evals.harness.HarnessRun;
import com.pijava.evals.harness.PiCodingAgentHarness;
import com.pijava.evals.plan.DocumentationVariant;
import com.pijava.evals.report.CaseStatus;
import com.pijava.evals.report.EvalObservation;

/**
 * 批 32（docs/44 §6）：生产环境的单臂执行器。
 *
 * <p>把 suite 的 {@code harnessOptions} 与 CLI 的 provider/model 合并后跑真
 * {@link PiCodingAgentHarness}，再用 suite 的 judges 与 threshold 判定。任一 judge
 * 失败即整体取最小分；threshold 为 {@code null} 时只记录分数、不判失败。</p>
 */
public final class HarnessArmRunner {

    private HarnessArmRunner() {
    }

    /**
     * Run one harness arm.
     *
     * @param suite       suite owning the case
     * @param evalCase    case definition
     * @param variant     docs variant
     * @param run         1-based run number
     * @param artifactDir artifact root
     * @param provider    provider name
     * @param model       model id
     * @return observation plus a durable session snapshot path (nullable)
     * @throws Exception when the harness cannot complete
     */
    public static EvalCli.ArmResult run(EvalSuite suite, HarnessCase evalCase,
                                        DocumentationVariant variant, int run,
                                        Path artifactDir, String provider, String model)
            throws Exception {
        var armId = armId(evalCase, variant, run);
        var sessionDir = artifactDir.resolve("tasks").resolve(armId);
        var base = suite.harnessOptions(variant, sessionDir);
        var builder = base.toBuilder().provider(provider).model(model)
            .sessionArtifactDir(sessionDir);
        if (variant == DocumentationVariant.WITHOUT_DOCS) {
            if (base.transformSystemPrompt() == null) {
                builder.transformSystemPrompt(
                    DocumentationVariants::excludePiDocumentationIfPresent);
            }
            if (base.expectedPiDocumentation() == null) {
                builder.expectedPiDocumentation(Boolean.FALSE);
            }
        } else if (base.expectedPiDocumentation() == null) {
            builder.expectedPiDocumentation(Boolean.TRUE);
        }
        var harnessRun = new PiCodingAgentHarness(builder.build()).run(evalCase.prompt());
        var judged = judge(suite, evalCase, harnessRun);
        var observation = new EvalObservation(
            evalCase.descriptor().evalSet(), evalCase.descriptor().caseName(), variant, run,
            judged.status(), judged.score(), judged.reason(), harnessRun.usage(),
            harnessRun.durationMs(), null, judged.metrics());
        return new EvalCli.ArmResult(observation,
            harnessRun.artifacts().get(PiCodingAgentHarness.PI_SESSION_SNAPSHOT_ARTIFACT));
    }

    /**
     * Arm identifier matching {@code TaskPlan.Arm#id()}.
     *
     * @param evalCase case definition
     * @param variant  docs variant
     * @param run      1-based run
     * @return arm id
     */
    public static String armId(HarnessCase evalCase, DocumentationVariant variant, int run) {
        return evalCase.descriptor().id() + "#" + variant.wireName() + "#" + run;
    }

    private static Judgement judge(EvalSuite suite, HarnessCase evalCase, HarnessRun run) {
        double score = 1.0;
        String reason = "";
        Map<String, Object> metrics = Map.of();
        for (var judge : suite.judges()) {
            var result = judge.evaluate(run, evalCase.expected());
            if (result.score() < score) {
                score = result.score();
                reason = result.rationale();
                metrics = result.metadata();
            }
        }
        var threshold = suite.judgeThreshold();
        var status = threshold == null || score >= threshold
            ? CaseStatus.PASSED : CaseStatus.FAILED;
        if (status == CaseStatus.FAILED && reason.isEmpty()) {
            reason = "score " + score + " is below the judge threshold " + threshold;
        }
        return new Judgement(status, score, reason, metrics);
    }

    private record Judgement(CaseStatus status, double score, String reason,
                             Map<String, Object> metrics) {
    }
}
