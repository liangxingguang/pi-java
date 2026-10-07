package com.pijava.mcp.transport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryTransportTest {

    @Test
    void deliversAsynchronouslyWithoutReEnteringOnSend() throws Exception {
        var pair = InMemoryTransports.create();
        var received = new CountDownLatch(1);
        var delivered = new CopyOnWriteArrayList<Object>();
        pair.server().onMessage(message -> {
            delivered.add(message);
            received.countDown();
        });

        var ranInline = new boolean[1];
        pair.client().send(Map.of("key", "value"));
        ranInline[0] = delivered.isEmpty() == false;
        // send returned before the peer listener ran (microtask semantics).
        assertThat(ranInline[0]).isFalse();
        assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
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
