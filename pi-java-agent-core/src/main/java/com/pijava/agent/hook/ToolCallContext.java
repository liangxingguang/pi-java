package com.pijava.agent.hook;

import java.util.Map;

/** Context passed to {@code before_tool} hook. */
public record ToolCallContext(String lane, String toolCallId, String toolName,
                               Map<String, Object> arguments) {
    /** Defensively copies {@code arguments}. */
    public ToolCallContext {
        arguments = Map.copyOf(arguments);
    }
}
