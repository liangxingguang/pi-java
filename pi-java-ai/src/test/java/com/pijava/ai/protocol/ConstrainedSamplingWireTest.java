package com.pijava.ai.protocol;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.JsonSchemaSampling;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.StrictMode;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.catalog.CatalogCompatRules;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 原 docs/66：五条车道把 strict-prefer 工具的参数转换为 strict schema 并下发各自的
 * strict 标志（pi {@code constrained-sampling.ts} 五车道落点）。观测面为真出站体。
 */
class ConstrainedSamplingWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Map<String, Object> SCHEMA = Map.of(
        "type", "object",
        "properties", Map.of("command", Map.of("type", "string")));

    private static final ToolDefinition STRICT_TOOL = new ToolDefinition(
        "run", "Run a command", SCHEMA, "run", null, List.of(), "default",
        new JsonSchemaSampling(StrictMode.PREFER));

    @Test
    void completionsLaneConvertsSchemaAndSendsStrict() throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new OpenAICompletionsApi(options(server));
            drain(api, request(ModelInfo.minimal(ModelId.of("openai", "gpt-5"))));

            var fn = MAPPER.readTree(server.body()).path("tools").get(0).path("function");
            assertThat(fn.path("strict").asBoolean()).isTrue();
            assertStrictSchema(fn.path("parameters"));
        }
    }

    @Test
    void completionsLaneSuppressesStrictForMoonshot() throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new OpenAICompletionsApi(options(server));
            drain(api, request(ModelInfo.minimal(ModelId.of("moonshotai", "kimi-k2.5"))));

            var fn = MAPPER.readTree(server.body()).path("tools").get(0).path("function");
            assertThat(fn.path("strict").isMissingNode())
                .as("pi detectCompat：moonshot 缺省不支持 strict ⇒ 键不发").isTrue();
            assertThat(fn.path("parameters").path("additionalProperties").isMissingNode())
                .as("不支持时 schema 不转换").isTrue();
        }
    }

    @Test
    void responsesLaneConvertsSchemaAndSendsStrict() throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new OpenAIResponsesApi(options(server), "OPENAI_API_KEY");
            var model = new ModelInfo(ModelId.of("openai", "gpt-5"), "GPT-5",
                ModelInfo.minimal(ModelId.of("openai", "gpt-5")).capabilities(),
                128_000, 16_384, false,
                com.pijava.ai.model.PricingInfo.UNKNOWN,
                com.pijava.ai.thinking.ThinkingLevelMap.empty(), Map.of(), Map.of(),
                CatalogCompatRules.openaiResponses("openai", "gpt-5"));
            drain(api, request(model));

            var tool = MAPPER.readTree(server.body()).path("tools").get(0);
            assertThat(tool.path("strict").asBoolean()).isTrue();
            assertStrictSchema(tool.path("parameters"));
        }
    }

    @Test
    void azureResponsesLaneConvertsSchemaAndSendsStrict() throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new AzureOpenAIResponsesApi(options(server), "AZURE_OPENAI_API_KEY");
            drain(api, request(ModelInfo.minimal(ModelId.of("azure-openai-responses", "gpt-5"))));

            var tool = MAPPER.readTree(server.body()).path("tools").get(0);
            assertThat(tool.path("strict").asBoolean()).isTrue();
            assertStrictSchema(tool.path("parameters"));
        }
    }

    @Test
    void anthropicLaneConvertsSchemaAndSendsStrict() throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new AnthropicMessagesApi(options(server));
            var model = new ModelInfo(ModelId.of("anthropic", "claude-fable-5"),
                "Claude Fable 5",
                ModelInfo.minimal(ModelId.of("anthropic", "claude-fable-5")).capabilities(),
                200_000, 16_384, false,
                com.pijava.ai.model.PricingInfo.UNKNOWN,
                com.pijava.ai.thinking.ThinkingLevelMap.empty(), Map.of(), Map.of(),
                CatalogCompatRules.anthropic("anthropic", "claude-fable-5"));
            drain(api, request(model));

            var tool = MAPPER.readTree(server.body()).path("tools").get(0);
            assertThat(tool.path("strict").asBoolean()).isTrue();
            assertStrictSchema(tool.path("input_schema"));
        }
    }

    @Test
    void googleLaneValidatesAndConvertsSchemaForGemini3() throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new GoogleGenerativeAiApi(options(server));
            drain(api, request(ModelInfo.minimal(ModelId.of("google", "gemini-3-pro"))));

            var body = MAPPER.readTree(server.body());
            assertThat(body.path("toolConfig").path("functionCallingConfig")
                .path("mode").asText()).isEqualTo("VALIDATED");
            var declaration = body.path("tools").get(0)
                .path("functionDeclarations").get(0);
            assertStrictSchema(declaration.path("parametersJsonSchema"));
        }
    }

    @Test
    void googleLaneDoesNotValidateForGemini25() throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new GoogleGenerativeAiApi(options(server));
            drain(api, request(ModelInfo.minimal(ModelId.of("google", "gemini-2.5-pro"))));

            var body = MAPPER.readTree(server.body());
            assertThat(body.path("toolConfig").isMissingNode())
                .as("gemini-2.5 ⇒ pi 谓词为 false，无 VALIDATED 模式").isTrue();
            var declaration = body.path("tools").get(0)
                .path("functionDeclarations").get(0);
            assertThat(declaration.path("parametersJsonSchema")
                .path("additionalProperties").isMissingNode())
                .as("谓词 false ⇒ schema 不转换").isTrue();
        }
    }

    @Test
    void mistralLaneConvertsSchemaAndSendsStrict() throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new MistralConversationsApi(options(server));
            drain(api, request(ModelInfo.minimal(ModelId.of("mistral", "mistral-large"))));

            var fn = MAPPER.readTree(server.body()).path("tools").get(0).path("function");
            assertThat(fn.path("strict").asBoolean()).isTrue();
            assertStrictSchema(fn.path("parameters"));
        }
    }

    // ── 夹具 ─────────────────────────────────────────────────────────

    private static void assertStrictSchema(JsonNode parameters) {
        assertThat(parameters.path("additionalProperties").asBoolean())
            .as("strict schema ⇒ additionalProperties:false").isFalse();
        assertThat(parameters.path("required").size())
            .as("strict schema ⇒ required 全量").isEqualTo(1);
        assertThat(parameters.path("required").get(0).asText()).isEqualTo("command");
    }

    private static StreamRequest request(ModelInfo model) {
        return new StreamRequest(model, null,
            List.of(systemWithTool(), new Message.UserMessage(
                List.of(new ContentBlock.TextContent("hi")))),
            List.of(), -1, -1, Map.of());
    }

    private static Message.SystemMessage systemWithTool() {
        return new Message.SystemMessage("be brief", Instant.EPOCH, Map.of(),
            List.of(STRICT_TOOL), List.of());
    }

    private static ApiOptions options(RecordingHttpServer server) {
        return new ApiOptions(server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, Map.of());
    }

    private static void drain(AbstractChatApi api, StreamRequest request) {
        try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
            var events = new ArrayList<StreamEvent>();
            while (iter.hasNext() && events.size() < 100) {
                events.add(iter.next());
            }
        } catch (Exception ignored) {
            // 桩回 400：请求体已录到，流怎么结束与断言无关
        }
    }
}
