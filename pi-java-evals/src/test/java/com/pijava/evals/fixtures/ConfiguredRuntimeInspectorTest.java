package com.pijava.evals.fixtures;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 批 32（docs/44 §10）：观测器的模型字段、既有模型保留与两条 provider 车道探针。
 */
class ConfiguredRuntimeInspectorTest {

    @Test
    void probesPiMessagesLane(@TempDir Path tmp) throws Exception {
        try (var server = AcmeServer.start()) {
            var modelsJson = write(tmp, customProvider(server.customBaseUrl()));
            var probe = ConfiguredRuntimeInspector.inspectProvider(
                modelsJson, "acme", "fixture-chat",
                ConfiguredRuntimeInspector.PROBE_MAX_TOKENS);
            assertThat(probe.text()).isEqualTo(AcmeServer.REPLY_TEXT);
            assertThat(probe.stopReason()).isEqualTo("stop");
            assertThat(probe.inputTokens()).isEqualTo(AcmeServer.INPUT_TOKENS);
            assertThat(probe.outputTokens()).isEqualTo(AcmeServer.OUTPUT_TOKENS);
            assertThat(server.requests()).singleElement().satisfies(request -> {
                assertThat(request.path()).isEqualTo(AcmeServer.MESSAGES_PATH);
                assertThat(request.body()).contains("\"model\":\"fixture-chat\"", "\"context\"");
            });
        }
    }

    @Test
    void probesOpenAiLane(@TempDir Path tmp) throws Exception {
        try (var server = AcmeServer.start()) {
            var modelsJson = write(tmp, openAiProvider(server.openAiBaseUrl()));
            var probe = ConfiguredRuntimeInspector.inspectProvider(
                modelsJson, "fixture-openai", "fixture-chat",
                ConfiguredRuntimeInspector.PROBE_MAX_TOKENS);
            assertThat(probe.text()).isEqualTo(AcmeServer.REPLY_TEXT);
            assertThat(probe.stopReason()).isEqualTo("stop");
            assertThat(probe.inputTokens()).isEqualTo(AcmeServer.INPUT_TOKENS);
            assertThat(probe.outputTokens()).isEqualTo(AcmeServer.OUTPUT_TOKENS);
            assertThat(server.requests()).singleElement().satisfies(request -> {
                assertThat(request.path()).isEqualTo(AcmeServer.OPENAI_PATH);
                assertThat(request.body()).contains("fixture-chat", "\"stream\":true");
            });
        }
    }

    @Test
    void readsAddedModelFields(@TempDir Path tmp) throws Exception {
        try (var server = AcmeServer.start()) {
            var modelsJson = write(tmp, openAiProvider(server.openAiBaseUrl()));
            var model = ConfiguredRuntimeInspector.inspectAddedModel(
                modelsJson, "fixture-openai", "fixture-chat");
            assertThat(model.name()).isEqualTo("Fixture Chat");
            assertThat(model.reasoning()).isTrue();
            assertThat(model.text()).isTrue();
            assertThat(model.toolUse()).isTrue();
            assertThat(model.streaming()).isTrue();
            assertThat(model.inputCost()).isZero();
            assertThat(model.outputCost()).isZero();
            assertThat(model.contextWindow()).isEqualTo(32768);
            assertThat(model.maxTokens()).isEqualTo(4096);
            assertThat(model.fileModels()).isEqualTo(1);
            assertThat(model.builtinModels()).isPositive();
            assertThat(model.totalModels()).isEqualTo(model.builtinModels() + 1);
        }
    }

    @Test
    void rejectsMissingModel(@TempDir Path tmp) throws Exception {
        var modelsJson = write(tmp, openAiProvider("http://127.0.0.1:1/openai/v1"));
        assertThatThrownBy(() -> ConfiguredRuntimeInspector.inspectAddedModel(
            modelsJson, "fixture-openai", "missing"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("model not found");
    }

    private static Path write(Path dir, String json) throws Exception {
        var path = dir.resolve("models.json");
        Files.writeString(path, json, StandardCharsets.UTF_8);
        return path;
    }

    private static String customProvider(String baseUrl) {
        return """
            {"providers":{"acme":{
              "api":"pi-messages",
              "baseUrl":"%s",
              "apiKey":"acme-fixture-key",
              "models":[{"id":"fixture-chat","name":"Fixture Chat","reasoning":true,
                "contextWindow":32768,"maxTokens":4096,
                "cost":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0}}]
            }}}
            """.formatted(baseUrl);
    }

    private static String openAiProvider(String baseUrl) {
        return """
            {"providers":{"fixture-openai":{
              "api":"openai-completions",
              "baseUrl":"%s",
              "apiKey":"acme-fixture-key",
              "models":[{"id":"fixture-chat","name":"Fixture Chat","reasoning":true,
                "contextWindow":32768,"maxTokens":4096,
                "cost":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0}}]
            }}}
            """.formatted(baseUrl);
    }
}
