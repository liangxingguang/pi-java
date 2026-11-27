package com.pijava.coding.agent.codemode;

import com.pijava.agent.harness.PiLoop;

/** Launcher-facing helper for scripting one VM result. */
final class ScriptedContext {
    private ScriptedContext() {
    }

    /** Terminal message a scripted launcher sends when it sees a tool call. */
    static PiLoop.ToolOutcome outcome(String id, String text, boolean isError) {
        var result = new com.pijava.agent.tool.ToolResult<>(
            java.util.List.of(new com.pijava.ai.message.ContentBlock.TextContent(text)),
            null, null, false, java.util.List.of(), isError, null);
        var message = new com.pijava.ai.message.Message.ToolResultMessage(
            id, "read", result.content(), isError);
        return new PiLoop.ToolOutcome(message, result, isError);
    }
}
