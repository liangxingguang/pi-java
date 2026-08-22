package com.pijava.agent.tool;
import com.pijava.ai.AbortSignal;

import java.util.List;
import java.util.Map;

/**
 * A tool definition executed by the agent runtime.
 *
 * @param <TParams>  validated arguments type (Jackson-deserialized from LLM JSON)
 * @param <TDetails> structured detail type for UI rendering
 *
 * <p>Aligned with pi's {@code AgentTool}. Key Java-izations:
 * <ul>
 *   <li>5-param {@code execute()} — adds explicit {@code ToolContext} (pi injects via closure)</li>
 *   <li>Exception-driven errors — tools throw on failure, harness catches and
 *       wraps in error content (pi identical approach)</li>
 *   <li>{@code @FunctionalInterface} for callbacks instead of inline lambdas</li>
 * </ul></p>
 */
public interface AgentTool<TParams, TDetails> {

    /** Unique tool name (e.g. "bash", "read", "write"). */
    String name();

    /** Human-readable label for UI display. */
    String label();

    /**
     * One-line snippet for the system prompt's Available-tools section
     * (pi {@code ToolDefinition.promptSnippet}). Empty means fall back to
     * {@link #description()}.
     */
    default String promptSnippet() {
        return "";
    }

    /** Guideline bullets appended to the system prompt when this tool is active. */
    default List<String> promptGuidelines() {
        return List.of();
    }

    /** Description shown to the LLM in the system prompt / tool definition. */
    String description();

    /** JSON Schema describing the tool's input parameters. */
    Map<String, Object> inputSchema();

    /**
     * Execution mode hint.
     * {@link ExecutionMode.Sequential} — cannot run concurrently with other tools (e.g. bash).
     * {@link ExecutionMode.Parallel} — can run concurrently with other parallel tools (e.g. read, grep, ls, glob).
     */
    ExecutionMode executionMode();

    /**
     * Optional compatibility shim for raw tool-call arguments before
     * schema validation. Must return an object matching TParams.
     */
    @SuppressWarnings("unchecked")  // safe: callers supply TParams via type inference at the call site
    default TParams prepareArguments(Map<String, Object> raw) {
        return (TParams) raw;
    }

    /**
     * Execute the tool call. <b>Throw on failure</b> — the harness catches
     * exceptions and wraps them in error results. This aligns with pi's
     * exception-driven error model.
     *
     * @param toolCallId unique identifier from the LLM
     * @param params     validated arguments
     * @param signal     abort signal (may be null)
     * @param onUpdate   progress callback (may be null; scoped to this invocation)
     * @param context    execution environment (cwd, shell, filesystem) —
     *                   explicit because Java lacks TypeScript's closure-based DI
     * @return the tool result
     * @throws Exception on failure (harness wraps in error result)
     */
    ToolResult<TDetails> execute(
        String toolCallId,
        TParams params,
        AbortSignal signal,
        ToolUpdateCallback<TDetails> onUpdate,
        ToolContext context
    ) throws Exception;
}
