package com.pijava.mcp.transport;

import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryTransportTest {

    /** A pair whose both sides were started, as the client does before sending. */
    private static InMemoryTransports.Pair startedPair() {
        var pair = InMemoryTransports.create();
        pair.client().start();
        pair.server().start();
        return pair;
    }

    @Test
    void deliversAsynchronouslyWithoutReEnteringOnSend() throws Exception {
        var pair = startedPair();
        var received = new CountDownLatch(1);
        var delivered = new CopyOnWriteArrayList<Object>();
        var listenerThread = new AtomicReference<Thread>();
        pair.server().onMessage(message -> {
            listenerThread.set(Thread.currentThread());
            delivered.add(message);
            received.countDown();
        });

        var senderThread = Thread.currentThread();
        pair.client().send(Map.of("key", "value"));
        assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
        // send never re-enters the peer's listeners on the calling stack (microtask
        // semantics). Pinning "not delivered yet" here would race the worker thread:
        // the worker may finish before the sending thread runs its next bytecode.
        assertThat(listenerThread.get()).isNotSameAs(senderThread);
        assertThat(delivered).containsExactly(Map.of("key", "value"));
    }

    @Test
    void isolatesDeliveredMessagesFromLaterMutation() throws Exception {
        var pair = startedPair();
        var received = new CountDownLatch(1);
        var delivered = new CopyOnWriteArrayList<Object>();
        pair.server().onMessage(message -> {
            delivered.add(message);
            received.countDown();
        });

        var original = new java.util.LinkedHashMap<String, Object>();
        original.put("nested", new java.util.LinkedHashMap<>(Map.of("a", 1)));
        pair.client().send(original);
        original.put("mutated", true);
        ((Map<String, Object>) original.get("nested")).put("b", 2);

        assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(delivered.get(0)).isEqualTo(Map.of(
                "nested", Map.of("a", 1)));
    }

    @Test
    void closingOneSideClosesItsPeer() throws Exception {
        var pair = startedPair();
        var serverClosed = new CountDownLatch(1);
        var clientClosed = new CountDownLatch(1);
        pair.server().onClose(serverClosed::countDown);
        pair.client().onClose(clientClosed::countDown);

        pair.server().close();
        assertThat(serverClosed.await(5, TimeUnit.SECONDS)).isTrue();
        // pi's close cascades to the peer, so sending from either side is rejected.
        assertThat(clientClosed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> pair.client().send(Map.of()))
                .isInstanceOf(com.pijava.mcp.protocol.jsonrpc.McpConnectionClosedError.class);
    }

    @Test
    void sendingBeforeStartIsRejected() throws Exception {
        var pair = InMemoryTransports.create();
        assertThatThrownBy(() -> pair.client().send(Map.of()))
                .isInstanceOf(com.pijava.mcp.protocol.jsonrpc.McpConnectionClosedError.class);
        pair.client().start();
        pair.server().start();
        pair.client().send(Map.of());
        pair.client().close();
    }

    @Test
    void sendingToAnUnstartedPeerIsRejected() throws Exception {
        var pair = InMemoryTransports.create();
        pair.client().start();
        assertThatThrownBy(() -> pair.client().send(Map.of()))
                .isInstanceOf(com.pijava.mcp.protocol.jsonrpc.McpConnectionClosedError.class)
                .hasMessage("In-memory MCP peer is not connected");
        pair.client().close();
    }

    @Test
    void closingCascadesAndIsIdempotent() {
        var pair = startedPair();
        var clientClosed = new java.util.concurrent.CountDownLatch(1);
        pair.client().onClose(clientClosed::countDown);
        pair.client().close();
        // The pair's other side closes with it, with or without a listener.
        assertThat(pair.server().isClosed()).isTrue();
        pair.client().close();
        pair.server().close();
        assertThat(clientClosed.getCount()).isZero();
    }

    @Test
    void errorsCanBeEmittedFromTests() throws Exception {
        var pair = InMemoryTransports.create();
        var errors = new CopyOnWriteArrayList<Throwable>();
        pair.client().onError(errors::add);
        pair.client().emitError(new IllegalStateException("boom"));
        assertThat(errors).hasSize(1);
        pair.client().close();
    }

    @Test
    void transportsAreIndependentInBothDirections() throws Exception {
        var pair = startedPair();
        var clientGot = new CountDownLatch(1);
        var serverGot = new CountDownLatch(1);
        pair.client().onMessage(message -> clientGot.countDown());
        pair.server().onMessage(message -> serverGot.countDown());

        pair.server().send(Map.of("to", "client"));
        pair.client().send(Map.of("to", "server"));
        assertThat(clientGot.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(serverGot.await(5, TimeUnit.SECONDS)).isTrue();
    }
}
