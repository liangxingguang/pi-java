package com.pijava.agent.hook;

import com.pijava.agent.tool.ToolResult;

/** Context passed to {@code after_tool} hook. */
public record ToolResultContext(String lane, String toolCallId, String toolName,
                                 ToolResult<?> result) {}
