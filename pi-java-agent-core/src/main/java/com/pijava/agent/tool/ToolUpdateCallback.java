package com.pijava.agent.tool;

/**
 * Progress callback for streaming tool execution updates.
 * Scoped to the current {@code execute()} invocation; calls made after
 * the execute() promise settles are ignored.
 */
@FunctionalInterface
public interface ToolUpdateCallback<TDetails> {
    void onUpdate(ToolResult<TDetails> partialResult);
}
