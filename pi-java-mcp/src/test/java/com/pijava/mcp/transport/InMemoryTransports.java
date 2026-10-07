package com.pijava.mcp.transport;

/**
 * Factory for connected in-memory transport pairs (in-memory.ts:45-51).
 */
final class InMemoryTransports {

    /** A connected pair: client and server side. */
    record Pair(InMemoryTransport client, InMemoryTransport server) {
    }

    private InMemoryTransports() {
    }

    /** Create and connect a pair. */
    static Pair create() {
        var client = new InMemoryTransport();
        var server = new InMemoryTransport();
        client.connect(server);
        return new Pair(client, server);
    }
}
