package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.pijava.agent.tool.TruncationUtils;
import com.pijava.ai.AbortSignal;
import com.pijava.coding.agent.extension.event.ExtensionEvent;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;
import com.pijava.coding.agent.extension.event.ExtensionResults.ToolResultEventResult;

/** docs/30：subagent 验收——状态机、证据、截断、模式判定、确认门、结果钩子。 */
class SubagentExtensionTest {

    private static Map<String, Object> step(String agent, String task) {
        return Map.of("agent", agent, "task", task);
    }

    private static SubagentAgents.AgentConfig agent(String name, String source) {
        return new SubagentAgents.AgentConfig(name, "d", List.of(), null, source,
            "/tmp/" + name + ".md", "");
    }

    private static SubagentExtension.SingleResult done(String agent, String task, String output) {
        return new SubagentExtension.SingleResult(agent, "user", task,
            SubagentExtension.ChildStatus.COMPLETED, 0, output, "", "stop", null, null, null,
            "m");
    }

    private static SubagentExtension.SingleResult failed(String agent, String task,
            String error, String stderr) {
        return new SubagentExtension.SingleResult(agent, "user", task,
            SubagentExtension.ChildStatus.FAILED, 1, null, stderr, "error", error, "SIGTERM",
            null, "m");
    }

    @Test
    void modeValidation() {
        assertThat(SubagentExtension.validateMode(false, false, false, "a (user), b (project)"))
            .isEqualTo("Invalid parameters. Provide exactly one mode.\n"
                + "Available agents: a (user), b (project)");
        assertThat(SubagentExtension.validateMode(true, true, false, null))
            .contains("Provide exactly one mode");
        assertThat(SubagentExtension.validateMode(true, false, false, null)).isNull();
        assertThat(SubagentExtension.validateMode(false, false, true, null)).isNull();
    }

    @Test
    void chainSubstitutesPreviousAndStopsOnFailure() {
        var ext = new SubagentExtension();
        ext.availableAgents(List.of(agent("a", "user"), agent("b", "user")));
        ext.runner((agent, task, cwd, signal) -> done(agent, task, "OUT:" + task));

        var result = ext.runChain(List.of(
            step("a", "first"), step("b", "use {previous} now")), "/tmp", null);

        assertThat(result.text()).isEqualTo("OUT:use OUT:first now");
    }

    @Test
    void chainFailureVerbatim() {
        var ext = new SubagentExtension();
        ext.availableAgents(List.of(agent("a", "user")));
        ext.runner((agent, task, cwd, signal) -> failed(agent, task, "boom", ""));

        assertThat(ext.runChain(List.of(step("a", "x")), "/tmp", null).text())
            .isEqualTo("Chain stopped at step 1 (a): Error: boom\n\nSignal: SIGTERM");
    }

    @Test
    void parallelTooMany() {
        var ext = new SubagentExtension();
        var tasks = java.util.stream.IntStream.range(0, 9)
            .<Map<String, Object>>mapToObj(i -> step("a", "t" + i)).toList();
        assertThat(ext.runParallel(tasks, null, null).text())
            .isEqualTo("Too many parallel tasks (9). Max is 8.");
    }

    @Test
    void parallelSummarizesSuccessAndFailure() {
        var ext = new SubagentExtension();
        ext.availableAgents(List.of(agent("a", "user"), agent("b", "user")));
        ext.runner((agent, task, cwd, signal) -> task.equals("bad")
            ? failed(agent, task, "boom", "")
            : done(agent, task, "ok-output-" + task));

        var result = ext.runParallel(List.of(step("a", "good"), step("b", "bad")), null, null);

        assertThat(result.text()).startsWith("Parallel: 1/2 succeeded");
        assertThat(result.text()).contains("### [a] completed", "ok-output-good")
            .contains("### [b] failed (error)").contains("\n\n---\n\n");
    }

    @Test
    void singleSuccessAndFailure() {
        var ext = new SubagentExtension();
        ext.availableAgents(List.of(agent("a", "user")));
        ext.runner((agent, task, cwd, signal) -> done(agent, task, "the answer"));
        assertThat(ext.runSingleMode("a", "q", "/tmp", null, null).text())
            .isEqualTo("the answer");

        ext.runner((agent, task, cwd, signal) -> failed(agent, task, "explode", ""));
        assertThat(ext.runSingleMode("a", "q", "/tmp", null, null).text())
            .isEqualTo("Agent error: Error: explode\n\nSignal: SIGTERM");
    }

    @Test
    void unknownAgentFailsBeforeRunner() {
        var ext = new SubagentExtension();
        ext.availableAgents(List.of(agent("a", "user")));
        ext.runner((agent, task, cwd, signal) -> {
            throw new AssertionError("runner must not run");
        });

        assertThat(ext.runSingleMode("zzz", "q", "/tmp", null, null).text())
            .isEqualTo("Agent failed: Error: Unknown agent: \"zzz\". "
                + "Available agents: \"a\".");
    }

    @Test
    void abortedBeforeStartDoesNotRun() {
        var ext = new SubagentExtension();
        ext.availableAgents(List.of(agent("a", "user")));
        ext.runner((agent, task, cwd, signal) -> {
            throw new AssertionError("runner must not run");
        });
        var signal = AbortSignal.create();
        signal.abort();

        assertThat(ext.runChain(List.of(step("a", "x")), "/tmp", signal).text())
            .isEqualTo("Chain stopped at step 1 (a): "
                + "Error: Subagent was aborted before starting");
    }

    @Test
    void failureEvidenceOrderAndDedupe() {
        var result = new SubagentExtension.SingleResult("a", "user", "t",
            SubagentExtension.ChildStatus.FAILED, 2, "assistant text", "stderr text",
            "error", "err text", "SIGKILL", null, "m");

        assertThat(SubagentExtension.failureEvidence(result)).isEqualTo(
            "Error: err text\n\nSignal: SIGKILL\n\nstderr: stderr text"
                + "\n\nAssistant output: assistant text");

        var duplicate = new SubagentExtension.SingleResult("a", "user", "t",
            SubagentExtension.ChildStatus.FAILED, 2, "same", "same", "error", "same", null,
            null, "m");
        assertThat(SubagentExtension.failureEvidence(duplicate)).isEqualTo("Error: same");
    }

    @Test
    void noOutputFallbackAndTruncation() {
        var ext = new SubagentExtension();
        ext.availableAgents(List.of(agent("a", "user")));
        ext.runner((agent, task, cwd, signal) -> done(agent, task, ""));
        assertThat(ext.runSingleMode("a", "q", "/tmp", null, null).text())
            .isEqualTo("(no output)");

        var parallel = SubagentExtension.truncateParallelOutput(
            "x".repeat(SubagentExtension.PER_TASK_OUTPUT_CAP + 10));
        assertThat(parallel).contains("10 bytes omitted")
            .endsWith(". Full output preserved in tool details.]");

        var model = SubagentExtension.truncateModelOutput(
            "line\n".repeat((int) TruncationUtils.DEFAULT_MAX_BYTES));
        assertThat(model).endsWith("bytes omitted. Full output preserved in tool details.]");
    }

    @Test
    void mappedConcurrencyPreservesOrder() {
        var result = SubagentExtension.mapWithConcurrencyLimit(List.of(1, 2, 3, 4, 5), 2, i -> i * 10);
        assertThat(result).containsExactly(10, 20, 30, 40, 50);
    }

    @Test
    void detailsCarryStatusAndErrorFlag() {
        var ok = done("a", "t", "out");
        var bad = failed("b", "t", "boom", "");
        var details = SubagentExtension.details("parallel", List.of(ok, bad), false);

        assertThat(details).containsEntry("mode", "parallel").containsEntry("isError", true);
        @SuppressWarnings("unchecked")
        var results = (List<Map<String, Object>>) details.get("results");
        assertThat(results).hasSize(2);
        assertThat(results.get(0)).containsEntry("status", "completed");
        assertThat(results.get(1)).containsEntry("status", "failed")
            .containsEntry("stderr", "").containsEntry("exitCode", 1);
    }

    @Test
    void toolRegisteredAndDefaultRunnerThrows() {
        var w = new W3Context();
        var ext = new SubagentExtension();
        ext.register(w.ctx);
        assertThat(w.tools.toolNames()).contains("subagent");

        ext.availableAgents(List.of(agent("a", "user")));
        assertThatThrownBy(() -> ext.runSingleMode("a", "q", "/tmp", null, null))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("requires a runner");
    }

    @Test
    void toolResultHookMarksError() {
        var w = new W3Context();
        var ext = new SubagentExtension();
        ext.register(w.ctx);

        var event = new ExtensionEvent.ToolResultEvent("c1", "subagent", Map.of(),
            List.of(), Map.of("isError", true), null, false, null);
        var results = w.fire(ExtensionEventSpec.TOOL_RESULT, event);

        assertThat(results).hasSize(1);
        assertThat(((ToolResultEventResult) results[0]).isError()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void projectAgentsRequireConfirmation() {
        var w = new W3Context();
        var ext = new SubagentExtension();
        ext.register(w.ctx);
        ext.availableAgents(List.of(agent("p", "project")));
        ext.runner((agent, task, cwd, signal) -> done(agent, task, "ok"));
        w.cwd = "/tmp";
        w.ui.confirmAnswer(false);

        var tool = (SubagentExtension.SubagentTool) w.tools.get("subagent");
        var result = tool.execute("c1",
            Map.of("agent", "p", "task", "t", "agentScope", "both"), null, null, null);

        assertThat(ExampleFixtures.textOf(result.content()))
            .isEqualTo("Canceled: project-local agents not approved.");
        assertThat(result.isError()).isFalse();
        assertThat(w.ui.dialogOptions()).hasSize(1);
    }
}
