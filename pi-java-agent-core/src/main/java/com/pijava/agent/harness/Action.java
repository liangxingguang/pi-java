package com.pijava.agent.harness;

import java.util.Map;

/**
 * A pending action that the harness will execute (manual drive mode).
 *
 * <p>Phase 2a subtypes: {@link StreamAssistant}, {@link AppendEntry},
 * {@link TryFinishRun}. {@link ExecuteTool} is declared for Phase 2b
 * but never returned in Phase 2a.</p>
 *
 * <p>Aligned with pi's {@code ActionInfo} union type.</p>
 */
public sealed interface Action {

    /**
     * Call the LLM and consume the event stream.
     *
     * @param step    "assistant" (Phase 2a) | "compaction" | "branch_summary" (Phase 2c)
     * @param attempt retry attempt number (0-indexed)
     */
    record StreamAssistant(
        String step,
        int attempt
    ) implements Action {}

    /**
     * Persist a provisioned entry to storage.
     * The entry was already created in pendingWrites; this action
     * only identifies it by type + ID.
     *
     * @param entryType "message" | "thinking_level_change" | ...
     * @param entryId   the entry's unique ID
     */
    record AppendEntry(
        String entryType,
        String entryId
    ) implements Action {}

    /**
     * Try to end the current run.
     * May be rejected if the lane state is not ready.
     *
     * @param outcome "completed" | "tool_use" | "error"
     */
    record TryFinishRun(
        String outcome
    ) implements Action {}

    /**
     * Execute a tool call (Phase 2b).
     * Declared now because Java sealed types require all subtypes at compile time.
     */
    record ExecuteTool(
        String toolCallId,
        String toolName,
        Map<String, Object> arguments
    ) implements Action {
        public ExecuteTool {
            arguments = Map.copyOf(arguments);
        }
    }
}
