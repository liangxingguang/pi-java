package com.pijava.ai.protocol;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ImageRequest;
import com.pijava.ai.api.ImageStopReason;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.model.ModelId;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** OpenAI Images API: request body, response parsing and error text. */
class OpenAIImagesApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ApiOptions options(String baseUrl) {
        return new ApiOptions(baseUrl, "test-key", Duration.ofSeconds(5), 0, Map.of());
    }

    private static OpenAIImagesApi api(String baseUrl) {
        return new OpenAIImagesApi(options(baseUrl), "OPENAI_API_KEY");
    }

    @Test
    void buildBodyUsesPromptSizeAndModelSpecificResponseFormat() {
        var request = new ImageRequest(ModelId.of("openai-images", "gpt-image-1"),
            List.of(new ContentBlock.TextContent("a red panda")));
        var body = api("").buildBody(request, options(""));
        assertThat(body).containsEntry("model", "gpt-image-1")
            .containsEntry("prompt", "a red panda")
            .containsEntry("n", 1)
            .containsEntry("size", "1024x1024")
            .doesNotContainKey("response_format");

        var dallE = new ImageRequest(ModelId.of("openai-images", "dall-e-3"),
            List.of(new ContentBlock.TextContent("one"), new ContentBlock.TextContent("two")));
        assertThat(api("").buildBody(dallE, options("")))
            .containsEntry("prompt", "one\ntwo")
            .containsEntry("response_format", "b64_json");

        // 孤对代理按 pi sanitizeSurrogates 语义删除，避免 provider JSON 序列化失败。
        var lone = new ImageRequest(ModelId.of("openai-images", "gpt-image-1"),
            List.of(new ContentBlock.TextContent("a\uD800b")));
        assertThat(api("").buildBody(lone, options(""))).containsEntry("prompt", "ab");
    }

    @Test
    void parseImagesReadsB64JsonAndUrls() throws Exception {
        var root = JSON.readTree("{\"output_format\":\"png\",\"data\":["
            + "{\"b64_json\":\"AAAA\"},{\"url\":\"https://example.com/x.png\"}]}");
        assertThat(OpenAIImagesApi.parseImages(root)).containsExactly(
            new ContentBlock.ImageContent("image/png", "AAAA"),
            new ContentBlock.UrlImageContent("https://example.com/x.png"));
        assertThat(OpenAIImagesApi.parseImages(JSON.readTree("{}"))).isEmpty();
    }

    @Test
    void generatePostsToImagesEndpointAndReturnsImageContent() throws Exception {
        var seenPath = new AtomicReference<String>();
        var seenAuth = new AtomicReference<String>();
        var seenBody = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/images/generations", exchange -> {
            seenPath.set(exchange.getRequestURI().getPath());
            seenAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            seenBody.set(new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8));
            var body = ("{\"created\":1,\"data\":[{\"b64_json\":\"BBBB\"}]}")
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            var api = api("http://localhost:" + server.getAddress().getPort() + "/v1");
            var result = api.generate(new ImageRequest(ModelId.of("openai-images", "gpt-image-1"),
                List.of(new ContentBlock.TextContent("draw"))), options(""));
            assertThat(result.stopReason()).isEqualTo(ImageStopReason.STOP);
            assertThat(result.output()).containsExactly(
                new ContentBlock.ImageContent("image/png", "BBBB"));
            assertThat(seenPath.get()).isEqualTo("/v1/images/generations");
            assertThat(seenAuth.get()).isEqualTo("Bearer test-key");
            assertThat(JSON.readTree(seenBody.get()).path("prompt").asText()).isEqualTo("draw");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void generateFormatsProviderErrorText() throws Exception {
        var server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/images/generations", exchange -> {
            var body = "{\"error\":{\"message\":\"forbidden\"}}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(403, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            var api = api("http://localhost:" + server.getAddress().getPort() + "/v1");
            var result = api.generate(new ImageRequest(ModelId.of("openai-images", "gpt-image-1"),
                List.of(new ContentBlock.TextContent("draw"))), options(""));
            assertThat(result.stopReason()).isEqualTo(ImageStopReason.ERROR);
            assertThat(result.errorMessage())
                .isEqualTo("403: {\"message\":\"forbidden\"}");
        } finally {
            server.stop(0);
        }
    }
}
