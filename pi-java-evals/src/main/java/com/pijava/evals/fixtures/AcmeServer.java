package com.pijava.evals.fixtures;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 批 32（docs/44 §8.4）：本地假 provider 服务 —— pi {@code acme-server.ts} 对位。
 *
 * <p>两条车道：</p>
 * <ul>
 *   <li>{@code /stream/messages}：pi-messages 自定义 SSE（{@code start} →
 *       {@code text_start} → {@code text_delta} → {@code text_end} → {@code done}），
 *       由 {@code PiMessagesApi} 消费；</li>
 *   <li>{@code /openai/v1/chat/completions}：OpenAI 兼容 SSE（chunk ＋
 *       {@code [DONE]}），由 {@code OpenAICompletionsApi} 消费。</li>
 * </ul>
 *
 * <p>Java 的协议闭集没有 pi 的 {@code acme-stream}，自定义车道改用仓内已有的
 * {@code pi-messages} wire 协议（窄差登记于 T13）。服务记录每次请求的路径与 body，
 * 供“服务端收到合法请求”的断言使用。</p>
 */
public final class AcmeServer implements AutoCloseable {

    /** 固定回复文本（两条车道一致）。 */
    public static final String REPLY_TEXT = "pong";

    /** 固定输入 token 数。 */
    public static final int INPUT_TOKENS = 12;

    /** 固定输出 token 数。 */
    public static final int OUTPUT_TOKENS = 3;

    /** pi-messages 车道路由。 */
    public static final String MESSAGES_PATH = "/stream/messages";

    /** OpenAI 兼容车道路由。 */
    public static final String OPENAI_PATH = "/openai/v1/chat/completions";

    private static final String SSE_CONTENT_TYPE = "text/event-stream; charset=utf-8";

    /** 一次被记录的服务端请求。 */
    public record RecordedRequest(String path, String body) {
    }

    private final HttpServer server;
    private final ExecutorService executor;
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();

    private AcmeServer(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    /**
     * Start the server on an ephemeral loopback port.
     *
     * @return running server
     * @throws IOException if the loopback listener cannot be created
     */
    public static AcmeServer start() throws IOException {
        var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var server = new AcmeServer(http, executor);
        http.createContext(MESSAGES_PATH, server::handleMessages);
        http.createContext(OPENAI_PATH, server::handleOpenAi);
        http.setExecutor(executor);
        http.start();
        return server;
    }

    /** @return bound port */
    public int port() {
        return server.getAddress().getPort();
    }

    /** @return base URL for the pi-messages lane ({@code /stream}) */
    public String customBaseUrl() {
        return "http://127.0.0.1:" + port() + "/stream";
    }

    /** @return base URL for the OpenAI lane ({@code /openai/v1}) */
    public String openAiBaseUrl() {
        return "http://127.0.0.1:" + port() + "/openai/v1";
    }

    /** @return recorded requests, in arrival order */
    public List<RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    /** Clear recorded requests without touching the listener. */
    public void reset() {
        requests.clear();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.close();
    }

    private void handleMessages(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "method not allowed");
                return;
            }
            record(exchange);
            writeSse(exchange, List.of(
                "{\"type\":\"start\"}",
                "{\"type\":\"text_start\",\"contentIndex\":0}",
                "{\"type\":\"text_delta\",\"contentIndex\":0,\"delta\":\"" + REPLY_TEXT + "\"}",
                "{\"type\":\"text_end\",\"contentIndex\":0,\"content\":\"" + REPLY_TEXT
                    + "\",\"contentSignature\":null}",
                doneFrame()));
        } finally {
            exchange.close();
        }
    }

    private void handleOpenAi(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "method not allowed");
                return;
            }
            record(exchange);
            writeSse(exchange, List.of(
                "{\"id\":\"chatcmpl-acme\",\"object\":\"chat.completion.chunk\","
                    + "\"created\":1,\"model\":\"fixture-chat\",\"choices\":[{\"index\":0,"
                    + "\"delta\":{\"role\":\"assistant\",\"content\":\"" + REPLY_TEXT
                    + "\"},\"finish_reason\":null}]}",
                "{\"id\":\"chatcmpl-acme\",\"object\":\"chat.completion.chunk\","
                    + "\"created\":1,\"model\":\"fixture-chat\",\"choices\":[{\"index\":0,"
                    + "\"delta\":{},\"finish_reason\":\"stop\"}]}",
                "{\"id\":\"chatcmpl-acme\",\"object\":\"chat.completion.chunk\","
                    + "\"created\":1,\"model\":\"fixture-chat\",\"choices\":[],"
                    + "\"usage\":{\"prompt_tokens\":" + INPUT_TOKENS
                    + ",\"completion_tokens\":" + OUTPUT_TOKENS
                    + ",\"total_tokens\":" + (INPUT_TOKENS + OUTPUT_TOKENS) + "}}",
                "[DONE]"));
        } finally {
            exchange.close();
        }
    }

    private static String doneFrame() {
        return "{\"type\":\"done\",\"reason\":\"stop\",\"usage\":{\"input\":" + INPUT_TOKENS
            + ",\"output\":" + OUTPUT_TOKENS
            + ",\"cacheRead\":0,\"cacheWrite\":0,\"totalTokens\":"
            + (INPUT_TOKENS + OUTPUT_TOKENS)
            + ",\"cost\":{\"input\":0,\"output\":0,\"cacheRead\":0,\"cacheWrite\":0,\"total\":0}},"
            + "\"responseId\":\"acme-1\"}";
    }

    private void record(HttpExchange exchange) throws IOException {
        var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new RecordedRequest(exchange.getRequestURI().getPath(), body));
    }

    private static void writeSse(HttpExchange exchange, List<String> frames) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", SSE_CONTENT_TYPE);
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            for (var frame : frames) {
                out.write(("data: " + frame + "\n\n").getBytes(StandardCharsets.UTF_8));
            }
            out.flush();
        }
    }

    private static void respond(HttpExchange exchange, int status, String text)
            throws IOException {
        var bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
