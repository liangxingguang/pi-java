package com.pijava.coding.agent.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.pijava.agent.harness.Context;
import com.pijava.agent.harness.StreamOptions;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.provider.ModelsJsonConfig;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.coding.agent.cli.ArgsParser;

/**
 * D-P1（{@code docs/65}）：命中内置 provider 的 models.json 覆盖在真 HTTP 线上
 * 生效 —— provider 级 baseUrl、per-model baseUrl（B136）与 client-builder
 * default headers（R2）的端到端证据。
 */
class ModelsJsonOverrideWireTest {

    /** 返回 200 + 最小 SSE done；同时录下请求路径、头。 */
    private static final class StubServer implements AutoCloseable {
        private final HttpServer server;
        private final AtomicReference<String> path = new AtomicReference<>("");
        private final AtomicReference<Map<String, List<String>>> headers = new AtomicReference<>();

        StubServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        String baseUrl() {
            return "http://localhost:" + server.getAddress().getPort() + "/v1";
        }

        String lastPath() { return path.get(); }
        String header(String name) {
            var h = headers.get();
            if (h == null) {
                return null;
            }
            var v = h.get(name.toLowerCase());
            return v == null || v.isEmpty() ? null : v.get(0);
        }

        private void handle(HttpExchange exchange) throws IOException {
            path.set(exchange.getRequestURI().getPath());
            exchange.getRequestBody().readAllBytes();
            headers.set(exchange.getRequestHeaders());
            byte[] sse = String.join("\n",
                "event: response.completed",
                "data: {\"type\":\"response.completed\",\"response\":{"
                    + "\"id\":\"r\",\"created_at\":0,\"error\":null,"
                    + "\"incomplete_details\":null,\"instructions\":null,\"metadata\":{},"
                    + "\"parallel_tool_calls\":true,\"temperature\":1.0,"
                    + "\"tool_choice\":\"auto\",\"tools\":[],\"top_p\":1.0,"
                    + "\"status\":\"completed\",\"model\":\"gpt-5\",\"output\":[],"
                    + "\"usage\":{\"input_tokens\":1,\"input_tokens_details\":{},"
                    + "\"output_tokens\":1,\"output_tokens_details\":{},"
                    + "\"total_tokens\":2}}}",
                "", "").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, sse.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(sse);
            }
        }

        @Override public void close() { server.stop(0); }
    }

    private StubServer server;

    @BeforeEach
    void start() throws Exception {
        server = new StubServer();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private static ModelsJsonConfig writeConfig(String json, java.nio.file.Path dir)
            throws Exception {
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve("models.json"), json,
            StandardCharsets.UTF_8);
        return ModelsJsonConfig.load(dir.resolve("models.json"));
    }

    /** 排空迭代器，等整条流结束（请求在虚拟线程上，避免读结果竞态）。 */
    private static void drain(com.pijava.ai.api.StreamIterator iter) {
        while (iter.hasNext()) {
            iter.next();
        }
    }

    private StreamOptions streamOptions() {
        return new StreamOptions(java.util.OptionalInt.empty(),
            java.util.OptionalDouble.empty(), java.util.Optional.empty(),
            java.util.Optional.empty());
    }

    @Test
    void providerBaseUrlRedirectsTheBuiltinModel() throws Exception {
        var dir = java.nio.file.Files.createTempDirectory("mjson-p1");
        try {
            var config = writeConfig("""
                {"providers": {"openai": {"baseUrl": "%s"}}}
                """.formatted(server.baseUrl()), dir);
            var registry = ProviderRegistry.create();
            registry.loadBuiltinProviders();
            DefaultProviders.registerModelsJsonProviders(registry, config);

            var settings = new Settings();
            var streamFn = DefaultProviders.streamFnFor(ArgsParser.parse(new String[] {}),
                "openai", registry, settings);
            drain(streamFn.stream(ModelId.of("openai", "gpt-5"),
                Context.of(List.of()), streamOptions()));

            // 请求打到 provider 级 baseUrl（路径是 Responses 的 /responses）。
            assertThat(server.lastPath()).endsWith("/responses");
        } finally {
           
        }
    }

    @Test
    void mergedHeadersAreSentOnTheStreamingRequest() throws Exception {
        var dir = java.nio.file.Files.createTempDirectory("mjson-p2");
        try {
            var config = writeConfig("""
                {"providers": {"openai": {
                  "baseUrl": "%s",
                  "headers": {"X-Test": "yes"}
                }}}
                """.formatted(server.baseUrl()), dir);
            var registry = ProviderRegistry.create();
            registry.loadBuiltinProviders();
            DefaultProviders.registerModelsJsonProviders(registry, config);

            var streamFn = DefaultProviders.streamFnFor(ArgsParser.parse(new String[] {}),
                "openai", registry, new Settings());
            drain(streamFn.stream(ModelId.of("openai", "gpt-5"),
                Context.of(List.of()), streamOptions()));

            // R2：client-builder default headers 在 streaming 是否透传——真线取证。
            assertThat(server.header("X-Test")).isEqualTo("yes");
        } finally {
           
        }
    }

    @Test
    void replacedModelKeepsTheOtherBuiltinModels() throws Exception {
        var dir = java.nio.file.Files.createTempDirectory("mjson-p3");
        try {
            var config = writeConfig("""
                {"providers": {"openai": {
                  "baseUrl": "%s",
                  "models": [{"id": "gpt-5", "api": "openai-completions"}]
                }}}
                """.formatted(server.baseUrl()), dir);
            var registry = ProviderRegistry.create();
            registry.loadBuiltinProviders();
            DefaultProviders.registerModelsJsonProviders(registry, config);

            // 未被替换的内置模型仍在（不整条替换 provider）。
            var provider = registry.get("openai").orElseThrow();
            var ids = provider.builtinModels().listModels().stream()
                .map(ModelInfo::id).map(ModelId::modelName).toList();
            assertThat(ids).contains("gpt-5", "gpt-5-mini", "gpt-5-nano");
            // 被替换的走显式 completions。
            var replaced = provider.builtinModels()
                .find(ModelId.of("openai", "gpt-5")).orElseThrow();
            assertThat(replaced.api()).isEqualTo("openai-completions");
        } finally {
           
        }
    }
}
