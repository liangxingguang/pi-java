package com.pijava.mcp;

import java.util.List;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import com.pijava.mcp.protocol.ClientCapabilities;
import com.pijava.mcp.protocol.Root;

/**
 * Options of an {@link McpClient} ({@code client.ts:47-52}).
 *
 * @param name             client name
 * @param version          client version
 * @param title            optional title
 * @param capabilities     client capabilities
 * @param protocolVersion  protocol version to request; latest when {@code null}
 * @param requestTimeoutMs per-request timeout in ms; {@code <=0} uses the default
 * @param roots            roots offered to the server
 */
public record McpClientOptions(
        String name,
        String version,
        @Nullable String title,
        @Nullable ClientCapabilities capabilities,
        @Nullable String protocolVersion,
        long requestTimeoutMs,
        @Nullable Roots roots) {

    /** Minimal options (no capabilities, roots, or timeouts). */
    public McpClientOptions(String name, String version) {
        this(name, version, null, null, null, 0, null);
    }

    /** Roots offered at request time: a fixed list or a supplier. */
    public sealed interface Roots permits Roots.Fixed, Roots.Supplied {

        /** Fixed roots. */
        record Fixed(List<Root> roots) implements Roots {
        }

        /** Roots supplied per {@code roots/list}. */
        record Supplied(Supplier<List<Root>> supplier) implements Roots {
        }
    }
}
