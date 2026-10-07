package com.pijava.mcp.transport.http;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpExchange;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StreamableHttpTransportTest {

    private static StreamableHttpTransport connect(ScriptedHttpServer server) throws Exception {
        var transport = new StreamableHttpTransport(
                new StreamableHttpTransportOptions(server.url()));
        transport.start();
        return transport;
    }

    private static Map<String, Object> request(long id, String method) {
        return Map.of("jsonrpc", "2.0", "id", id, "method", method);
    }

    private static Map<String, Object> notification(String method) {
        return Map.of("jsonrpc", "2.0", "method", method);
    }

    @Test
    void deliversJsonResponseAndCapturesSession() throws Exception {
        var gotMessage = new CountDownLatch(1);
        var server = ScriptedHttpServer.start(exchange -> ScriptedHttpServer.json(
                exchange, 200,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}",
                Map.of("Mcp-Session-Id", "sess-1")));
        var transport = connect(server);
        var messages = new CopyOnWriteArrayList<Object>();
        transport.onMessage(message -> {
            messages.add(message);
            gotMessage.countDown();
        });

        transport.send(request(1, "ping"));
        assertThat(gotMessage.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(messages.get(0)).isEqualTo(Map.of(
                "jsonrpc", "2.0", "id", 1,
                "result", Map.of("ok", true)));
        assertThat(transport.sessionId()).isEqualTo("sess-1");
        transport.close();
        server.stop();
    }

    @Test
    void deliversEachItemOfABatchArray() throws Exception {
        var gotTwo = new CountDownLatch(2);
        var server = ScriptedHttpServer.start(exchange -> ScriptedHttpServer.json(
                exchange, 200,
                "[{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}},"
                        + "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{}}]",
                Map.of()));
        var transport = connect(server);
        var messages = new CopyOnWriteArrayList<Object>();
        transport.onMessage(message -> {
            messages.add(message);
            gotTwo.countDown();
        });

        transport.send(request(1, "ping"));
        assertThat(gotTwo.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(messages).hasSize(2);
        transport.close();
        server.stop();
    }

    @Test
    void deliversMessageFromSseResponse() throws Exception {
        var gotMessage = new CountDownLatch(1);
        var server = ScriptedHttpServer.start(exchange -> {
            var out = ScriptedHttpServer.beginSse(exchange, Map.of());
            var frame = "event: message\n"
                    + "data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"value\":42}}\n\n";
            out.write(frame.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
            Thread.sleep(200);
            out.close();
        });
        var transport = connect(server);
        transport.onMessage(message -> gotMessage.countDown());

        transport.send(request(1, "tools/call"));
        assertThat(gotMessage.await(5, TimeUnit.SECONDS)).isTrue();
        transport.close();
        server.stop();
    }

    @Test
    void surfaces401WithChallenge() throws Exception {
        var server = ScriptedHttpServer.start(exchange -> ScriptedHttpServer.json(
                exchange, 401, "{}",
                Map.of("WWW-Authenticate", "Basic realm=\"mcp\"")));
        var transport = connect(server);

        assertThatThrownBy(() -> transport.send(request(1, "ping")))
                .isInstanceOf(McpAuthRequiredError.class)
                .satisfies(error -> assertThat(((McpAuthRequiredError) error).wwwAuthenticate())
                        .isEqualTo("Basic realm=\"mcp\""));
        transport.close();
        server.stop();
    }

    @Test
    void surfacesHttpErrorWithBody() throws Exception {
        var server = ScriptedHttpServer.start(exchange -> ScriptedHttpServer.json(
                exchange, 500, "boom", Map.of()));
        var transport = connect(server);

        assertThatThrownBy(() -> transport.send(request(1, "ping")))
                .isInstanceOf(McpHttpError.class)
                .hasMessageContaining("status 500")
                .hasMessageContaining("boom");
        transport.close();
        server.stop();
    }

    @Test
    void requestAnswered202IsAnError() throws Exception {
        var server = ScriptedHttpServer.start(exchange ->
                ScriptedHttpServer.acknowledgement(exchange, 202));
        var transport = connect(server);

        assertThatThrownBy(() -> transport.send(request(1, "tools/call")))
                .isInstanceOf(McpHttpError.class)
                .hasMessageContaining("without a response");
        transport.close();
        server.stop();
    }

    @Test
    void notificationOpensGetStreamWithoutLastEventId() throws Exception {
        var getArrived = new CountDownLatch(1);
        var eventDelivered = new CountDownLatch(1);
        var server = ScriptedHttpServer.start(exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                getArrived.countDown();
                var out = ScriptedHttpServer.beginSse(exchange, Map.of());
                out.write("data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/ping\"}\n\n"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(300);
                out.close();
                return;
            }
            // initialize result, then 202 for the initialized notification.
            if (exchange.getRequestURI().toString().endsWith("init")) {
                ScriptedHttpServer.json(exchange, 200,
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":"
                                + "{\"protocolVersion\":\"2025-11-25\","
                                + "\"capabilities\":{},\"serverInfo\":{\"name\":\"s\",\"version\":\"1\"}}}",
                        Map.of());
            } else {
                ScriptedHttpServer.acknowledgement(exchange, 202);
                // Keep the SSE exchange alive long enough for assertions.
                Thread.sleep(500);
            }
        });

        var options = new StreamableHttpTransportOptions(
                server.url() + "/init", null, null, true, 0, null, null);
        var transport = new StreamableHttpTransport(options);
        transport.onMessage(message -> eventDelivered.countDown());
        transport.start();
        transport.send(request(1, "initialize"));
        transport.send(notification("notifications/initialized"));

        assertThat(getArrived.await(5, TimeUnit.SECONDS)).isTrue();
        var getRequest = server.received().stream()
                .filter(received -> "GET".equals(received.method()))
                .findFirst().orElseThrow();
        assertThat(getRequest.headers().keySet())
                .noneMatch(name -> name.equalsIgnoreCase("Last-Event-ID"));
        assertThat(eventDelivered.await(5, TimeUnit.SECONDS)).isTrue();
        transport.close();
        server.stop();
    }

    /** Case-insensitive header lookup (HTTP header names are case-insensitive). */
    private static String header(Map<String, String> headers, String name) {
        for (var entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    @Test
    void resumesResponseStreamViaGetWithLastEventId() throws Exception {
        var gotAnswer = new CountDownLatch(1);
        var phase = new java.util.concurrent.atomic.AtomicInteger();
        var server = ScriptedHttpServer.start(exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                var out = ScriptedHttpServer.beginSse(exchange, Map.of());
                // Server assigns an event id, then drops the stream before answering.
                out.write("id: e1\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(150);
                out.close();
                return;
            }
            var out = ScriptedHttpServer.beginSse(exchange, Map.of());
            out.write(("data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}\n\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
            Thread.sleep(200);
            out.close();
        });
        var transport = connect(server);
        transport.onMessage(message -> {
            if (message instanceof Map<?, ?> map && map.containsKey("result")) {
                gotAnswer.countDown();
            }
        });

        transport.send(request(1, "tools/call"));
        assertThat(gotAnswer.await(5, TimeUnit.SECONDS)).isTrue();

        var get = server.received().stream()
                .filter(received -> "GET".equals(received.method()))
                .findFirst().orElseThrow();
        assertThat(header(get.headers(), "Last-Event-ID")).isEqualTo("e1");
        transport.close();
        server.stop();
    }

    @Test
    void doesNotResumeWhenServerAssignedNoEventId() throws Exception {
        var gotErrorFrame = new CountDownLatch(1);
        var server = ScriptedHttpServer.start(exchange -> {
            var out = ScriptedHttpServer.beginSse(exchange, Map.of());
            // A non-response message with no event id, then the stream drops.
            out.write("data: {\"jsonrpc\":\"2.0\",\"method\":\"update\"}\n\n"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
            Thread.sleep(100);
            out.close();
        });
        var transport = connect(server);
        transport.onMessage(message -> {
            if (message instanceof Map<?, ?> map && map.containsKey("error")) {
                gotErrorFrame.countDown();
            }
        });

        transport.send(request(1, "tools/call"));
        assertThat(gotErrorFrame.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(server.received())
                .noneMatch(received -> "GET".equals(received.method()));
        transport.close();
        server.stop();
    }

    @Test
    void reconnectsGetStreamWithLastEventId() throws Exception {
        var secondGet = new CountDownLatch(1);
        var server = ScriptedHttpServer.start(exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                var already = exchange.getRequestHeaders()
                        .getFirst("Last-Event-ID") != null;
                var out = ScriptedHttpServer.beginSse(exchange, Map.of());
                if (already) {
                    secondGet.countDown();
                    out.write("data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/ping\"}\n\n"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    out.flush();
                    Thread.sleep(200);
                    return;
                }
                out.write(("id: g1\n"
                        + "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/ping\"}\n\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(100);
                out.close();   // first stream drops; client must reconnect
                return;
            }
            ScriptedHttpServer.acknowledgement(exchange, 202);
            Thread.sleep(300);
        });
        var transport = connect(server);
        transport.send(notification("notifications/initialized"));

        assertThat(secondGet.await(5, TimeUnit.SECONDS)).isTrue();
        var getWithId = server.received().stream()
                .filter(received -> "GET".equals(received.method())
                        && "g1".equals(header(received.headers(), "Last-Event-ID")))
                .findFirst();
        assertThat(getWithId).isPresent();
        transport.close();
        server.stop();
    }

    @Test
    void getStreamAnswered405StaysSilent() throws Exception {
        var getArrived = new CountDownLatch(1);
        var server = ScriptedHttpServer.start(exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                getArrived.countDown();
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            ScriptedHttpServer.acknowledgement(exchange, 202);
            Thread.sleep(300);
        });
        var errors = new CopyOnWriteArrayList<Throwable>();
        var transport = new StreamableHttpTransport(
                new StreamableHttpTransportOptions(server.url()));
        transport.onError(errors::add);
        transport.start();
        transport.send(notification("notifications/initialized"));

        assertThat(getArrived.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(200);
        assertThat(errors).isEmpty();
        transport.close();
        server.stop();
    }
}
