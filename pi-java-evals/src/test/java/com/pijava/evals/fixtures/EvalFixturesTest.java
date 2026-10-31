package com.pijava.evals.fixtures;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pijava.ai.message.ContentBlock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 批 32（docs/44 §10）：{@code inspect_provider}/{@code inspect_added_model} 工具的
 * 两条车道、配置落盘与错误协议。
 */
class EvalFixturesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void probesPiMessagesLane(@TempDir Path tmp) throws Exception {
        try (var server = AcmeServer.start()) {
            var models = tmp.resolve("models.json");
            var tool = EvalFixtures.inspectProviderTool(server, () -> models);
            var result = tool.execute("call-1",
                Map.of("provider", "acme", "api", "pi-messages", "model", "fixture-chat"),
                null, null, null);
            var text = ((ContentBlock.TextContent) result.content().get(0)).text();
            assertThat(text).contains("\"text\":\"pong\"", "\"stopReason\":\"stop\"",
                "\"inputTokens\":12", "\"outputTokens\":3");
            assertThat(Files.readString(models, StandardCharsets.UTF_8))
                .contains("\"api\":\"pi-messages\"", server.customBaseUrl());
            assertThat(server.requests()).singleElement().satisfies(request -> {
                assertThat(request.path()).isEqualTo(AcmeServer.MESSAGES_PATH);
                assertThat(request.body()).contains("\"model\":\"fixture-chat\"");
            });
        }
    }

    @Test
    void probesOpenAiLane(@TempDir Path tmp) throws Exception {
        try (var server = AcmeServer.start()) {
            var models = tmp.resolve("models.json");
            var tool = EvalFixtures.inspectProviderTool(server, () -> models);
            var result = tool.execute("call-2",
                Map.of("provider", "fixture-openai", "api", "openai-completions",
                    "model", "fixture-chat"),
                null, null, null);
            var text = ((ContentBlock.TextContent) result.content().get(0)).text();
            assertThat(text).contains("\"text\":\"pong\"", "\"inputTokens\":12");
            assertThat(server.requests()).singleElement().satisfies(request -> {
                assertThat(request.path()).isEqualTo(AcmeServer.OPENAI_PATH);
                assertThat(request.body()).contains("\"stream\":true");
            });
        }
    }

    @Test
    void rejectsUnknownProtocol(@TempDir Path tmp) throws Exception {
        try (var server = AcmeServer.start()) {
            var models = tmp.resolve("models.json");
            var tool = EvalFixtures.inspectProviderTool(server, () -> models);
            assertThatThrownBy(() -> tool.execute("call-3",
                Map.of("provider", "acme", "api", "acme-stream", "model", "fixture-chat"),
                null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown api");
        }
    }

    @Test
    void writesAddedModelFields(@TempDir Path tmp) throws Exception {
        var models = tmp.resolve("models.json");
        var tool = EvalFixtures.inspectAddedModelTool(() -> models);
        var result = tool.execute("call-4",
            Map.of("provider", EvalFixtures.ADDED_MODEL_PROVIDER,
                "model", EvalFixtures.ADDED_MODEL_ID),
            null, null, null);
        var text = ((ContentBlock.TextContent) result.content().get(0)).text();
        var json = MAPPER.readTree(text);
        assertThat(json.path("providerId").asText()).isEqualTo(EvalFixtures.ADDED_MODEL_PROVIDER);
        assertThat(json.path("modelId").asText()).isEqualTo(EvalFixtures.ADDED_MODEL_ID);
        assertThat(json.path("name").asText()).isEqualTo(EvalFixtures.ADDED_MODEL_NAME);
        assertThat(json.path("reasoning").asBoolean()).isTrue();
        assertThat(json.path("text").asBoolean()).isTrue();
        assertThat(json.path("toolUse").asBoolean()).isTrue();
        assertThat(json.path("streaming").asBoolean()).isTrue();
        assertThat(json.path("inputCost").asDouble()).isZero();
        assertThat(json.path("outputCost").asDouble()).isZero();
        assertThat(json.path("contextWindow").asInt())
            .isEqualTo(EvalFixtures.ADDED_MODEL_CONTEXT_WINDOW);
        assertThat(json.path("maxTokens").asInt())
            .isEqualTo(EvalFixtures.ADDED_MODEL_MAX_TOKENS);
        assertThat(json.path("fileModels").asInt()).isEqualTo(1);
        assertThat(json.path("totalModels").asInt())
            .isEqualTo(json.path("builtinModels").asInt() + 1);
        assertThat(Files.readString(models, StandardCharsets.UTF_8))
            .contains("\"fixture-chat\"", "\"reasoning\" : true", "\"contextWindow\" : 32768");
    }

    @Test
    void preservesExistingModels(@TempDir Path tmp) throws Exception {
        var models = tmp.resolve("models.json");
        Files.writeString(models, "{\"providers\":{\"legacy\":{\"api\":\"pi-messages\","
            + "\"baseUrl\":\"http://127.0.0.1:1/stream\",\"models\":[{\"id\":\"old-model\","
            + "\"name\":\"Old\"}]}}}", StandardCharsets.UTF_8);
        var tool = EvalFixtures.inspectAddedModelTool(() -> models);
        var result = tool.execute("call-5",
            Map.of("provider", EvalFixtures.ADDED_MODEL_PROVIDER,
                "model", EvalFixtures.ADDED_MODEL_ID),
            null, null, null);
        var text = ((ContentBlock.TextContent) result.content().get(0)).text();
        assertThat(MAPPER.readTree(text).path("fileModels").asInt()).isEqualTo(2);
        var merged = Files.readString(models, StandardCharsets.UTF_8);
        assertThat(merged).contains("\"legacy\"", "\"old-model\"", "\"fixture-chat\"");
    }

    @Test
    void rejectsBlankAddedModelParameters(@TempDir Path tmp) {
        var tool = EvalFixtures.inspectAddedModelTool(() -> tmp.resolve("models.json"));
        assertThatThrownBy(() -> tool.execute("call-6", Map.of("provider", " ", "model", "x"),
            null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("provider");
    }
}
