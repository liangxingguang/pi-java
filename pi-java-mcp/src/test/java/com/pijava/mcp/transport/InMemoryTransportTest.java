package com.pijava.mcp.transport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryTransportTest {

    @Test
    void deliversAsynchronouslyWithoutReEnteringOnSend() throws Exception {
        var pair = InMemoryTransports.create();
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
        var pair = InMemoryTransports.create();
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
    void closesBothSidesAndDropsQueuedDeliveries() throws Exception {
        var pair = InMemoryTransports.create();
        var serverClosed = new CountDownLatch(1);
        pair.server().onClose(serverClosed::countDown);
        var messages = new ArrayList<Object>();
        pair.server().onMessage(messages::add);

        pair.server().close();
        assertThat(serverClosed.await(5, TimeUnit.SECONDS)).isTrue();

        // The client side can still send; delivery to a closed peer is dropped.
        pair.client().send(Map.of());
        Thread.sleep(50);
        assertThat(messages).isEmpty();

        // Sending after one's own close is rejected.
        pair.client().close();
        assertThatThrownBy(() -> pair.client().send(Map.of()))
                .isInstanceOf(com.pijava.mcp.protocol.jsonrpc.McpConnectionClosedError.class);
    }

    @Test
    void transportsAreIndependentInBothDirections() throws Exception {
        var pair = InMemoryTransports.create();
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
