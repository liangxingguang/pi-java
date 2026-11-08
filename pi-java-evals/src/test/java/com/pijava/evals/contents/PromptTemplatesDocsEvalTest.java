package com.pijava.evals.contents;

import java.util.List;
import java.util.Map;

import com.pijava.evals.EvalTestRuns;
import com.pijava.evals.harness.HarnessRun;
import com.pijava.evals.harness.HarnessStep;
import com.pijava.evals.judge.Judge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * L6 W4（docs/26 §5.4）：prompt template eval 的步骤、落点与判分。
 */
class PromptTemplatesDocsEvalTest {

    @TempDir
    Path workspace;

    @TempDir
    Path agentDir;

    @Test
    void stepsAreRequestReloadThenInvoke() {
        for (var scope : PromptTemplatesDocsEval.Scope.values()) {
            var steps = PromptTemplatesDocsEval.steps(scope);
            assertThat(steps).hasSize(3);
            assertThat(steps.get(0).isReload()).isFalse();
            assertThat(steps.get(1).isReload()).isTrue();
            assertThat(steps.get(2).content()).isEqualTo(PromptTemplatesDocsEval.COMMAND);
        }
    }

    @Test
    void requestTextsMatchTheirScope() {
        assertThat(PromptTemplatesDocsEval.Scope.PROJECT.request())
            .contains("already trusted")
            .contains(PromptTemplatesDocsEval.COMMAND)
            .contains(PromptTemplatesDocsEval.TEMPLATE_PROMPT);
        assertThat(PromptTemplatesDocsEval.Scope.USER.request())
            .contains("available in all my sessions")
            .contains(PromptTemplatesDocsEval.TEMPLATE_PROMPT);
    }

    @Test
    void expectedPathsDifferPerScope() {
        var project = PromptTemplatesDocsEval.expectedPath(
            PromptTemplatesDocsEval.Scope.PROJECT, workspace, agentDir);
        var user = PromptTemplatesDocsEval.expectedPath(
            PromptTemplatesDocsEval.Scope.USER, workspace, agentDir);
        assertThat(project).isEqualTo(workspace.resolve(".pi-java").resolve("prompts")
            .resolve("current-time.md"));
        assertThat(user).isEqualTo(agentDir.resolve("prompts").resolve("current-time.md"));
        assertThat(project).isNotEqualTo(user);
    }

    @Test
    void judgeScoresTheExpectedObservationFullMarks() {
        var observation = new PromptTemplatesDocsEval.Observation("project", true, false,
            true, 1);
        var result = judge().evaluate(run(observation), PromptTemplatesDocsEval.Scope.PROJECT);
        assertThat(result.score()).isEqualTo(1.0);
    }

    @Test
    void judgeRejectsWrongScope() {
        var result = judge().evaluate(
            run(new PromptTemplatesDocsEval.Observation("user", true, false, true, 1)),
            PromptTemplatesDocsEval.Scope.PROJECT);
        assertThat(result.score()).isZero();
        assertThat(result.rationale()).contains("scope was user instead of project");
    }

    @Test
    void judgeRejectsWrongPathAndOtherScopeAndUnexpandedCommand() {
        assertThat(judge().evaluate(
            run(new PromptTemplatesDocsEval.Observation("project", false, false, true, 1)),
            PromptTemplatesDocsEval.Scope.PROJECT).rationale())
            .contains("not at the expected path");
        assertThat(judge().evaluate(
            run(new PromptTemplatesDocsEval.Observation("project", true, true, true, 1)),
            PromptTemplatesDocsEval.Scope.PROJECT).rationale())
            .contains("other scope also holds");
        assertThat(judge().evaluate(
            run(new PromptTemplatesDocsEval.Observation("project", true, false, false, 1)),
            PromptTemplatesDocsEval.Scope.PROJECT).rationale())
            .contains("did not expand");
    }

    @Test
    void judgeRejectsDuplicateTemplateNames() {
        var result = judge().evaluate(
            run(new PromptTemplatesDocsEval.Observation("project", true, false, true, 2)),
            PromptTemplatesDocsEval.Scope.PROJECT);
        assertThat(result.score()).isZero();
        assertThat(result.rationale()).contains("exactly one current-time template");
    }

    @Test
    void judgeRejectsARunWithoutObservation() {
        var run = EvalTestRuns.response("I created the template.");
        var result = judge().evaluate(run, PromptTemplatesDocsEval.Scope.PROJECT);
        assertThat(result.score()).isZero();
        assertThat(result.metadata()).containsEntry("reason", "output-shape");
    }

    @Test
    void templateFileCarriesFrontmatterAndBody() {
        assertThat(PromptTemplatesDocsEval.templateFile())
            .startsWith("---")
            .contains("description: Current time")
            .contains(PromptTemplatesDocsEval.TEMPLATE_PROMPT);
    }

    private Judge judge() {
        return new PromptTemplatesDocsEval().judges().get(0);
    }

    /** 让 output() 返回给定的观测结果（pi 的 options.output 产出面）。 */
    private static HarnessRun run(PromptTemplatesDocsEval.Observation observation) {
        var base = EvalTestRuns.response("done");
        return new HarnessRun(base.steps(), base.prompt(), base.response(), base.events(),
            base.toolCalls(), base.usage(), base.durationMs(), base.systemPrompt(),
            base.systemPromptSha256(), base.sessionFile(), base.sessionJsonl(),
            base.runId(), Map.of(), observation);
    }
}
