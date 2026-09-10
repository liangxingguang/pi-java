package com.pijava.agent.hook;

import java.util.Map;

/**
 * Result returned by {@code before_tool} hook.
 * Allows hooks to allow, deny, or modify tool call arguments, and — when
 * denying — to ask the run to terminate (pi {@code agent-loop.ts:636-646}).
 *
 * @param allowed   whether the tool call is allowed
 * @param arguments modified arguments (null = use original)
 * @param terminate when {@code allowed} is false, whether the run should end
 *                  instead of continuing (deny + terminate channel)
 */
public record BeforeToolResult(boolean allowed, Map<String, Object> arguments, boolean terminate) {
    /**
     * Two-argument constructor for backward compatibility: a denied or allowed
     * result without a terminate intent.
     *
     * @param allowed   whether the tool call is allowed
     * @param arguments modified arguments (null = use original)
     */
    public BeforeToolResult(boolean allowed, Map<String, Object> arguments) {
        this(allowed, arguments, false);
    }

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

    /** Deny the tool call and terminate the run (agent-loop plan §3.3). */
    public static BeforeToolResult denyAndTerminate(String reason) {
        return new BeforeToolResult(false, Map.of("reason", reason), true);
    }

    /** Allow the tool call with modified arguments. */
    public static BeforeToolResult modify(Map<String, Object> newArgs) {
        return new BeforeToolResult(true, newArgs);
    }
}
