package com.pijava.mcp.config;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Where a server's tools are exposed ({@code mcp-servers.ts:17-22}).
 *
 * <ul>
 *   <li>{@code codemode}: callable from codemode scripts only, not declared to the model.</li>
 *   <li>{@code deferred}: declared to the model once {@code tool_search} loads them.</li>
 *   <li>{@code direct}: declared to the model like any other tool.</li>
 *   <li>{@code hidden}: registered but unreachable.</li>
 * </ul>
 *
 * <p>The declaration order is the order of the error messages that list the values
 * ({@code mcp-servers.ts:226}); do not reorder.</p>
 */
public enum McpExposure {
    CODEMODE("codemode"),
    DEFERRED("deferred"),
    DIRECT("direct"),
    HIDDEN("hidden");

    private final String wire;

    McpExposure(String wire) {
        this.wire = wire;
    }

    /** The wire value ({@code "codemode"}, …). */
    @JsonValue
    public String wire() {
        return wire;
    }

    /**
     * Resolve a current wire value; older names and unknown values give {@code null}
     * ({@code mcp-servers.ts:176-178}).
     *
     * <p>Older names are replaced before validation ({@code McpServerConfigs}), so that a
     * value reaching here is one of the four current names or invalid.</p>
     */
    public static @Nullable McpExposure fromWire(String value) {
        for (var exposure : values()) {
            if (exposure.wire.equals(value)) {
                return exposure;
            }
        }
        return null;
    }
}
