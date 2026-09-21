package com.pijava.ai.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 录制**最后一次请求体**的桩服务器（package A0，{@code docs/43}）：根上下文接所有路径，
 * 一律回 400 让车道尽快收场 —— 请求体已经录到了，流怎么结束与断言无关。
 *
 * <p>与 {@code LaneTransformMessagesWiringTest} 内嵌的私有实现同形；抽出来是因为
 * 包 A0 要按车道分别取证（Anthropic ／ OpenAI-completions ／ OpenAI-responses），
 * 三条车道各一份内嵌副本会变成三份漂移源。</p>
 */
final class RecordingHttpServer implements AutoCloseable {

    private final HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>("");

    RecordingHttpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            // ⚠️ 解码口径：孤对代理若被序列化器写成 JSON 转义（\uD83D），此处看到的是
            // 6 个 ASCII 字符；若写成裸代理字节，UTF-8 解码会得到 U+FFFD。两种形态
            // 断言都要拒（见 docs/43 §10 的实测记录）。
            body.set(out.toString(StandardCharsets.UTF_8));
        }
        byte[] payload = "{\"error\":{\"message\":\"recorded\",\"type\":\"invalid_request_error\"}}"
            .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(400, payload.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(payload);
        }
    }

    String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort() + "/v1";
    }

    String body() {
        return body.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}