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
 * @param judgedOutput      programmatic output for judges, {@code null} ⇒ fall back to {@code response}
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
    Map<String, String> artifacts,
    Object judgedOutput
) {

    /** Defensive copies. */
    public HarnessRun {
        steps = List.copyOf(steps);
        events = List.copyOf(events);
        toolCalls = List.copyOf(toolCalls);
        artifacts = Map.copyOf(artifacts);
    }

    /**
     * Run without a programmatic output (pi: {@code "output" in options} is false).
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
    public HarnessRun(List<HarnessStep> steps, String prompt, String response,
                      List<TranscriptEvent> events, List<ToolCallRecord> toolCalls,
                      HarnessUsage usage, long durationMs, String systemPrompt,
                      String systemPromptSha256, String sessionFile, String sessionJsonl,
                      String runId, Map<String, String> artifacts) {
        this(steps, prompt, response, events, toolCalls, usage, durationMs, systemPrompt,
            systemPromptSha256, sessionFile, sessionJsonl, runId, artifacts, null);
    }

    /**
     * Programmatic output for judges (pi {@code result.output}).
     *
     * <p>L6 W4：带 {@code output} 回调的套件（如 prompt-template eval）观测到的是
     * 结构化事实，不是模型回复文本；无回调时退回响应文本（pi
     * {@code "output" in options ? ... : response} 的同一分支）。</p>
     *
     * @return judged output, falling back to the assistant response
     */
    public Object output() {
        return judgedOutput == null ? response : judgedOutput;
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
