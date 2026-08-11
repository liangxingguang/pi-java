package com.pijava.agent.tool;

import java.util.Map;

/**
 * Approval callback for tool execution.
 * Phase 2b: functional interface; Phase 3 CLI/TUI provides interactive approval.
 */
@FunctionalInterface
public interface ApprovalHandler {
    /**
     * @return true if the tool call is approved for execution
     */
    boolean approve(String toolName, Map<String, Object> arguments);
}
