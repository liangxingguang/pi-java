package com.pijava.evals;

import java.util.List;
import java.util.Map;

import com.pijava.evals.harness.HarnessRun;
import com.pijava.evals.harness.HarnessStep;
import com.pijava.evals.harness.HarnessUsage;
import com.pijava.evals.harness.ToolCallRecord;

/** 批 32：判分器与报告测试用的最小 {@link HarnessRun} 夹具。 */
public final class EvalTestRuns {

    private EvalTestRuns() {
    }

    /**
     * A run whose only meaningful field is the assistant response.
     *
     * @param response assistant text
     * @return run fixture
     */
    public static HarnessRun response(String response) {
        return response(response, List.of());
    }

    /**
     * A run with the given response and tool calls.
     *
     * @param response  assistant text
     * @param toolCalls observed tool calls
     * @return run fixture
     */
    public static HarnessRun response(String response, List<ToolCallRecord> toolCalls) {
        var usage = new HarnessUsage("faux", "faux", 0, 0, 0, 0, 0, toolCalls.size(), null);
        return new HarnessRun(List.of(HarnessStep.prompt("prompt")), "prompt", response,
            List.of(), toolCalls, usage, 1L, "system", "sha", null, null, "run", Map.of());
    }

    /**
     * A successful tool call.
     *
     * @param name      tool name
     * @param arguments arguments
     * @return call record
     */
    public static ToolCallRecord ok(String name, Map<String, Object> arguments) {
        return new ToolCallRecord(name + "-id", name, arguments, "ok");
    }
}
