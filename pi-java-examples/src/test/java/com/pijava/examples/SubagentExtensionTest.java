package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** docs/54 §2.2：subagent 验收——三模式互斥、chain/parallel/single 编排、假 runner。 */
class SubagentExtensionTest {

    private static Map<String, Object> step(String agent, String task) {
        return Map.of("agent", agent, "task", task);
    }

    @Test
    void modeValidation() {
        assertThat(SubagentExtension.validateMode(false, false, false, "a, b"))
            .isEqualTo("Invalid parameters. Provide exactly one mode.\nAvailable agents: a, b");
        assertThat(SubagentExtension.validateMode(true, true, false, null))
            .contains("Provide exactly one mode");
        assertThat(SubagentExtension.validateMode(true, false, false, null)).isNull();
        assertThat(SubagentExtension.validateMode(false, false, true, null)).isNull();
    }

    @Test
    void chainSubstitutesPreviousAndStopsOnFailure() {
        var ext = new SubagentExtension();
        ext.runner((agent, task, cwd) -> new SubagentExtension.TaskResult("OUT:" + task, "STOP", null));

        var result = ext.runChain(List.of(
            step("a", "first"), step("b", "use {previous} now")), "/tmp");

        assertThat(result).isEqualTo("OUT:use OUT:first now");
    }

    @Test
    void chainFailureVerbatim() {
        var ext = new SubagentExtension();
        ext.runner((agent, task, cwd) -> new SubagentExtension.TaskResult(null, null, "boom"));

        assertThat(ext.runChain(List.of(step("a", "x")), null))
            .isEqualTo("Chain stopped at step 1 (a): boom");
    }

    @Test
    void parallelTooMany() {
        var ext = new SubagentExtension();
        var tasks = java.util.stream.IntStream.range(0, 9)
            .<Map<String, Object>>mapToObj(i -> step("a", "t" + i)).toList();
        assertThat(ext.runParallel(tasks, null))
            .isEqualTo("Too many parallel tasks (9). Max is 8.");
    }

    @Test
    void parallelSummarizesSuccessAndFailure() {
        var ext = new SubagentExtension();
        ext.runner((agent, task, cwd) -> task.equals("bad")
            ? new SubagentExtension.TaskResult("detail", "ABORT", null)
            : new SubagentExtension.TaskResult("ok-output-" + task, "STOP", null));

        var result = ext.runParallel(List.of(step("a", "good"), step("b", "bad")), null);

        assertThat(result).startsWith("Parallel: 1/2 succeeded");
        assertThat(result).contains("### [a] completed", "ok-output-good")
            .contains("### [b] failed (ABORT)").contains("\n\n---\n\n");
    }

    @Test
    void singleSuccessAndFailure() {
        var ext = new SubagentExtension();
        ext.runner((agent, task, cwd) -> new SubagentExtension.TaskResult("the answer", "STOP", null));
        assertThat(ext.runSingle("a", "q", null)).isEqualTo("the answer");

        ext.runner((agent, task, cwd) -> new SubagentExtension.TaskResult(null, "ERROR", "explode"));
        assertThat(ext.runSingle("a", "q", null)).isEqualTo("Agent ERROR: explode");
    }

    @Test
    void noOutputFallbackAndTruncation() {
        var ext = new SubagentExtension();
        ext.runner((agent, task, cwd) -> new SubagentExtension.TaskResult("", "STOP", null));
        assertThat(ext.runSingle("a", "q", null)).isEqualTo("(no output)");
        assertThat(SubagentExtension.truncate("x".repeat(SubagentExtension.PER_TASK_OUTPUT_CAP + 10)))
            .endsWith("...[truncated]");
    }

    @Test
    void mappedConcurrencyPreservesOrder() {
        var result = SubagentExtension.mapWithConcurrencyLimit(List.of(1, 2, 3, 4, 5), 2, i -> i * 10);
        assertThat(result).containsExactly(10, 20, 30, 40, 50);
    }

    @Test
    void toolRegisteredAndDefaultRunnerThrows() {
        var w = new W3Context();
        var ext = new SubagentExtension();
        ext.register(w.ctx);
        assertThat(w.tools.toolNames()).contains("subagent");
        assertThatThrownByDefaultRunner(ext);
    }

    private static void assertThatThrownByDefaultRunner(SubagentExtension ext) {
        try {
            ext.runSingle("a", "q", null);
            throw new AssertionError("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            assertThat(expected).hasMessageContaining("requires a runner");
        }
    }
}
