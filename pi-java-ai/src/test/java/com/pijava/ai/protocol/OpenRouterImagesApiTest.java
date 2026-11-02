package com.pijava.ai.protocol;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.openai.core.JsonValue;
import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ImageRequest;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.model.ModelId;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-28: OpenRouterImagesApi — 请求构建（chat + modalities）与响应图片解析。
 */
class OpenRouterImagesApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static OpenRouterImagesApi api() {
        return new OpenRouterImagesApi(
            new ApiOptions("", "test-key", Duration.ofSeconds(10), 0, Map.of()),
            "OPENROUTER_API_KEY");
    }

    @Test
    void buildParamsSetsModelModalitiesAndUserContent() {
        var params = api().buildParams(new ImageRequest(
            ModelId.of("openrouter-images", "black-forest-labs/flux.2-flex"),
            List.of(new ContentBlock.TextContent("a red panda"))));

        assertThat(params.model().toString())
            .isEqualTo("black-forest-labs/flux.2-flex");
        // pi: modalities: ["image", "text"]
        assertThat(params.modalities()).isPresent();
        var modalities = params.modalities().get().stream()
            .map(m -> m._value().asKnown().orElse("")).toList();
        assertThat(modalities).containsExactly("image", "text");
        // 一条 user 消息，含 text content part
        assertThat(params.messages()).hasSize(1);
        assertThat(params.messages().get(0).isUser()).isTrue();
    }

    @Test
    void parseImagesExtractsDataUris() throws Exception {
        var node = JSON.readTree(
            "[{\"image_url\":{\"url\":\"data:image/png;base64,AAAA\"}},"
            + "{\"image_url\":{\"url\":\"https://example.com/x.png\"}},"
            + "{\"image_url\":\"data:image/jpeg;base64,BBBB\"}]");
        var blocks = OpenRouterImagesApi.parseImages(JsonValue.fromJsonNode(node));
        assertThat(blocks).containsExactly(
            new ContentBlock.ImageContent("image/png", "AAAA"),
            new ContentBlock.ImageContent("image/jpeg", "BBBB"));
    }

    @Test
    void generateCarriesTheProviderResponseId() throws Exception {
        var server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/v1/chat/completions", exchange -> {
                var body = ("{\"id\":\"resp-image\",\"created\":1,\"model\":\"image-model\","
                    + "\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{"
                    + "\"role\":\"assistant\",\"content\":\"\"}}]}")
                    .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.start();
            var api = new OpenRouterImagesApi(new ApiOptions(
                "http://localhost:" + server.getAddress().getPort() + "/v1", "test-key",
                Duration.ofSeconds(5), 0, Map.of()), "OPENROUTER_API_KEY");
            var result = api.generate(new ImageRequest(
                ModelId.of("openrouter-images", "image-model"),
                List.of(new ContentBlock.TextContent("draw"))), ApiOptions.defaults());
            assertThat(result.responseId()).isEqualTo("resp-image");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void parseImagesSkipsNullAndMalformed() {
        assertThat(OpenRouterImagesApi.parseImages(null)).isEmpty();
        assertThat(OpenRouterImagesApi.parseImages(JsonValue.from("not-an-array")))
            .isEmpty();
    }
}
