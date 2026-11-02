package com.pijava.ai.provider.builtin;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.pijava.ai.AbortSignal;
import com.pijava.ai.api.AuthKind;
import com.pijava.ai.auth.RecordedCredential;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * pi {@code personalizeOpenRouterCatalog}（{@code openrouter.ts:14-88}）：
 * 按 Key 收窄，401/403/404 原样返回，其他错误响亮。
 */
class OpenRouterPersonalizationTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String start(int status, String body) throws IOException {
        return start(status, body, new AtomicInteger());
    }

    private String start(int status, String body, AtomicInteger calls) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/models/user", exchange -> {
            calls.incrementAndGet();
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1";
    }

    private static ModelInfo chat(String id) {
        return new ModelInfo(ModelId.of("openrouter", id), id,
            Set.of(ModelCapability.TEXT, ModelCapability.TOOL_USE), 1_000, 100, false,
            new PricingInfo(9, 9, 9, 9, List.of()));
    }

    private static Optional<RecordedCredential> key() {
        return Optional.of(new RecordedCredential(AuthKind.API_KEY, "sk-or", "env:T"));
    }

    @Test
    void noKeyMeansNoRequestAndTheSameList() throws Exception {
        var calls = new AtomicInteger();
        var baseUrl = start(200, "{\"data\":[]}", calls);
        var catalog = List.of(chat("a"));

        var result = OpenRouterPersonalization.personalize(
            catalog, Optional.empty(), baseUrl, AbortSignal.create(), HttpClient.newHttpClient());

        assertThat(result).isSameAs(catalog);
        assertThat(calls).hasValue(0);
    }

    @Test
    void unauthorizedAndMissingEndpointsKeepTheCatalog() throws Exception {
        for (var status : List.of(401, 403, 404)) {
            var baseUrl = start(status, "{\"error\":\"no\"}");
            var catalog = List.of(chat("a"));

            var result = OpenRouterPersonalization.personalize(
                catalog, key(), baseUrl, AbortSignal.create(), HttpClient.newHttpClient());

            assertThat(result).as("status %d", status).isSameAs(catalog);
            stop();
        }
    }

    @Test
    void otherErrorsFailLoudly() throws Exception {
        var baseUrl = start(500, "{}");

        assertThatThrownBy(() -> OpenRouterPersonalization.personalize(
            List.of(chat("a")), key(), baseUrl, AbortSignal.create(), HttpClient.newHttpClient()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("OpenRouter model list request failed: 500");
    }

    @Test
    void invalidBodiesFailLoudly() throws Exception {
        for (var body : List.of("[]", "{}", "{\"data\":{}}")) {
            var baseUrl = start(200, body);

            assertThatThrownBy(() -> OpenRouterPersonalization.personalize(
                List.of(chat("a")), key(), baseUrl, AbortSignal.create(), HttpClient.newHttpClient()))
                .as("body %s", body)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid OpenRouter model list response");
            stop();
        }
    }

    @Test
    void narrowsToTheKeysModelsAndOverridesLimitsAndPricing() throws Exception {
        var baseUrl = start(200, """
            {"data":[{"id":"a","context_length":1000,
              "top_provider":{"context_length":456,"max_completion_tokens":789},
              "pricing":{"prompt":"0.000001","completion":"0.000002"}}]}
            """);
        var catalog = List.of(chat("a"), chat("b"));

        var result = OpenRouterPersonalization.personalize(
            catalog, key(), baseUrl, AbortSignal.create(), HttpClient.newHttpClient());

        assertThat(result).extracting(m -> m.id().modelName()).containsExactly("a");
        var a = result.getFirst();
        assertThat(a.maxInputTokens()).isEqualTo(456);
        assertThat(a.maxOutputTokens()).isEqualTo(789);
        assertThat(a.pricing().inputPrice()).isEqualTo(1);
        assertThat(a.pricing().outputPrice()).isEqualTo(2);
    }

    @Test
    void zeroOrMissingLimitsKeepTheCatalogValues() throws Exception {
        var baseUrl = start(200, """
            {"data":[{"id":"a","context_length":0,
              "top_provider":{"context_length":0,"max_completion_tokens":0}}]}
            """);
        var catalog = List.of(chat("a"));

        var result = OpenRouterPersonalization.personalize(
            catalog, key(), baseUrl, AbortSignal.create(), HttpClient.newHttpClient());

        assertThat(result.getFirst().maxInputTokens()).isEqualTo(1_000);
        assertThat(result.getFirst().maxOutputTokens()).isEqualTo(100);
        assertThat(result.getFirst().pricing().inputPrice()).isEqualTo(9);
    }

    @Test
    void negativePricingBecomesZero() throws Exception {
        var baseUrl = start(200, """
            {"data":[{"id":"a","pricing":{"prompt":"-1","completion":"-1"}}]}
            """);

        var result = OpenRouterPersonalization.personalize(
            List.of(chat("a")), key(), baseUrl, AbortSignal.create(), HttpClient.newHttpClient());

        assertThat(result.getFirst().pricing().inputPrice()).isZero();
        assertThat(result.getFirst().pricing().outputPrice()).isZero();
    }

    @Test
    void nonChatEntriesAreCarriedUnchanged() throws Exception {
        var baseUrl = start(200, "{\"data\":[]}");
        var image = new ModelInfo(ModelId.of("openrouter", "img"), "img",
            Set.of(ModelCapability.IMAGE_OUTPUT), 0, 0, false, PricingInfo.UNKNOWN);

        var result = OpenRouterPersonalization.personalize(
            List.of(image), key(), baseUrl, AbortSignal.create(), HttpClient.newHttpClient());

        assertThat(result).containsExactly(image);
    }

    @Test
    void anAbortedSignalSkipsTheRequest() throws Exception {
        var calls = new AtomicInteger();
        var baseUrl = start(200, "{\"data\":[]}", calls);
        var signal = AbortSignal.create();
        signal.abort();
        var catalog = List.of(chat("a"));

        var result = OpenRouterPersonalization.personalize(
            catalog, key(), baseUrl, signal, HttpClient.newHttpClient());

        assertThat(result).isSameAs(catalog);
        assertThat(calls).hasValue(0);
    }
}
