package com.pijava.coding.agent.extension.mcp;

import com.pijava.mcp.config.McpExposure;

/**
 * Where a tool is exposed (pi's {@code ToolExposure}, {@code core/extensions/types.ts:509}).
 *
 * <p>{@code direct} and {@code model-only} tools are activated when they are registered; the
 * others are not. ⚠️ Nothing consumes this yet: the activation paths are codemode (B161) and
 * tool-search (B162), so today only {@code direct} MCP tools should reach the model. The type
 * is pi's; it lives here until the extension system grows its own.</p>
 */
public enum McpToolExposure {

    /** Activated when registered, and listed to the model. */
    DIRECT("direct"),

    /** Activated when registered, but not listed. */
    MODEL_ONLY("model-only"),

    /** Reachable from codemode scripts only. */
    CODEMODE("codemode"),

    /** Loaded on demand. */
    DEFERRED("deferred"),

    /** Registered but neither activated nor listed. */
    HIDDEN("hidden");

    private final String wire;

    McpToolExposure(String wire) {
        this.wire = wire;
    }

    /** The literal pi writes. */
    public String wire() {
        return wire;
    }

    /**
     * Tool exposure of an MCP exposure ({@code tools.ts:45-47}).
     *
     * <p>{@code codemode} and {@code deferred} both leave tools out of the codemode description;
     * they differ only in which tool the MCP extension activates to reach them.</p>
     *
     * @param exposure the configuration's exposure
     * @return the tool exposure
     */
    public static McpToolExposure of(McpExposure exposure) {
        return switch (exposure) {
            case CODEMODE -> DEFERRED;
            case DEFERRED -> DEFERRED;
            case DIRECT -> DIRECT;
            case HIDDEN -> HIDDEN;
        };
    }
}
