package com.pijava.agent.hook;

import java.util.Map;

/**
 * Result returned by {@code before_tool} hook.
 * Allows hooks to allow, deny, or modify tool call arguments.
 *
 * @param allowed   whether the tool call is allowed
 * @param arguments modified arguments (null = use original)
 */
public record BeforeToolResult(boolean allowed, Map<String, Object> arguments) {
    /** Defensively copies {@code arguments} when non-null. */
    public BeforeToolResult {
        if (arguments != null) {
            arguments = Map.copyOf(arguments);
        }
    }

    /** Allow the tool call as-is (spec-compliant name). */
    public static BeforeToolResult allow() {
        return new BeforeToolResult(true, null);
    }

    /**
     * Allow the tool call as-is.
     * @deprecated Use {@link #allow()} for spec compliance.
     */
    @Deprecated
    public static BeforeToolResult proceed() {
        return new BeforeToolResult(true, null);
    }

    /** Deny the tool call with a reason (stored in arguments map). */
    public static BeforeToolResult deny(String reason) {
        return new BeforeToolResult(false, Map.of("reason", reason));
    }

    /** Allow the tool call with modified arguments. */
    public static BeforeToolResult modify(Map<String, Object> newArgs) {
        return new BeforeToolResult(true, newArgs);
    }
}
