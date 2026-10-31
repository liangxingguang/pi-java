package com.pijava.evals.harness;

import java.util.List;
import java.util.Map;

/**
 * 批 32（docs/44 §3.3）：一次 harness 运行的完整观测输出 —— pi
 * {@code SimpleHarnessResult} + {@code PiRunDiagnostics} 对位。
 *
 * @param steps             executed steps
 * @param prompt            last prompt text
 * @param response          last assistant text
 * @param events            transcript events
 * @param toolCalls         tool calls with derived status
 * @param usage             usage summary
 * @param durationMs        total wall time
 * @param systemPrompt      model-visible system prompt
 * @param systemPromptSha256 SHA-256 of {@code systemPrompt}
 * @param sessionFile       session file path (nullable)
 * @param sessionJsonl      session file content (nullable)
 * @param runId             session id
 * @param artifacts         artifact key → local path / value
 */
public record HarnessRun(
    List<HarnessStep> steps,
    String prompt,
    String response,
    List<TranscriptEvent> events,
    List<ToolCallRecord> toolCalls,
    HarnessUsage usage,
    long durationMs,
    String systemPrompt,
    String systemPromptSha256,
    String sessionFile,
    String sessionJsonl,
    String runId,
    Map<String, String> artifacts
) {

    /** Defensive copies. */
    public HarnessRun {
        steps = List.copyOf(steps);
        events = List.copyOf(events);
        toolCalls = List.copyOf(toolCalls);
        artifacts = Map.copyOf(artifacts);
    }

    /**
     * Programmatic output for judges (pi {@code result.output}).
     *
     * @return the assistant response
     */
    public Object output() {
        return response;
    }

    /**
     * Tool calls that completed successfully.
     *
     * @return successful calls in transcript order
     */
    public List<ToolCallRecord> successfulToolCalls() {
        return toolCalls.stream().filter(ToolCallRecord::succeeded).toList();
    }
}
