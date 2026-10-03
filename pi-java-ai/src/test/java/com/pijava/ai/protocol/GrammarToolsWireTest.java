package com.pijava.ai.protocol;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.GrammarSampling;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.catalog.CatalogCompatRules;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ThinkingLevelMap;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/69：openai-completions 车道的 grammar custom tool 出站声明、构建期错误
 * 与历史回放（pi {@code openai-completions.ts:1472-1506/1351-1363}）。
 * 观测面为真出站体（RecordingHttpServer）。
 */
class GrammarToolsWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── 出站：门开 ⇒ custom tool ────────────────────────────────────

    @Test
    void emitsCustomToolForLarkWhenGateOpen() throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new OpenAICompletionsApi(options(server));
            drain(api, request(gateOpenModel(),
                grammarTool("pick", variants("openai_lark", "root ::= \"x\""),
                    grammarSchema("query"))));

            var tool = MAPPER.readTree(server.body()).path("tools").get(0);
            assertThat(tool.path("type").asText()).isEqualTo("custom");
            var custom = tool.path("custom");
            assertThat(custom.path("name").asText()).isEqualTo("pick");
            assertThat(custom.path("description").asText()).isEqualTo("Pick one");
            var format = custom.path("format");
            assertThat(format.path("type").asText()).isEqualTo("grammar");
            var grammar = format.path("grammar");
            assertThat(grammar.path("syntax").asText()).isEqualTo("lark");
            assertThat(grammar.path("definition").asText()).isEqualTo("root ::= \"x\"");
        }
    }

    @Test
    void emitsRegexWhenOnlyRegexVariantIsPresent() throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new OpenAICompletionsApi(options(server));
            drain(api, request(gateOpenModel(),
                grammarTool("pick", variants("openai_regex", "x+"), grammarSchema("query"))));

            var grammar = MAPPER.readTree(server.body()).path("tools").get(0)
                .path("custom").path("format").path("grammar");
            assertThat(grammar.path("syntax").asText()).isEqualTo("regex");
            assertThat(grammar.path("definition").asText()).isEqualTo("x+");
        }
    }

    @Test
    void larkWinsWhenBothVariantsArePresent() throws Exception {
        var variants = new LinkedHashMap<String, String>();
        variants.put("openai_lark", "root ::= \"lark\"");
        variants.put("openai_regex", "regex");
        try (var server = new RecordingHttpServer()) {
            var api = new OpenAICompletionsApi(options(server));
            drain(api, request(gateOpenModel(),
                grammarTool("pick", variants, grammarSchema("query"))));

            var grammar = MAPPER.readTree(server.body()).path("tools").get(0)
                .path("custom").path("format").path("grammar");
            assertThat(grammar.path("syntax").asText())
                .as("pi：两变体都在 ⇒ lark 优先").isEqualTo("lark");
            assertThat(grammar.path("definition").asText())
                .isEqualTo("root ::= \"lark\"");
        }
    }

    @Test
    void fallsBackToFunctionWhenGateClosed() throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new OpenAICompletionsApi(options(server));
            drain(api, request(gateClosedModel(),
                grammarTool("pick", variants("openai_lark", "root ::= \"x\""),
                    grammarSchema("query"))));

            var tool = MAPPER.readTree(server.body()).path("tools").get(0);
            assertThat(tool.path("type").asText())
                .as("门关 ⇒ 静默回落 function").isEqualTo("function");
            assertThat(tool.path("function").path("name").asText()).isEqualTo("pick");
        }
    }

    // ── 构建期错误：门关不抛、门开抛（文案逐字）──────────────────────

    @Test
    void gateOpenButNoVariantIsALoudBuildError() {
        var events = drain(new OpenAICompletionsApi(optionsUnstarted()),
            request(gateOpenModel(), grammarTool("pick", Map.of(), grammarSchema("query"))));

        assertThat(errorText(events)).contains(
            "Tool \"pick\" cannot use grammar constrained sampling: "
                + "no supported grammar variant was provided.");
    }

    @Test
    void blankVariantsCountAsNoVariant() {
        var events = drain(new OpenAICompletionsApi(optionsUnstarted()),
            request(gateOpenModel(),
                grammarTool("pick", variants("openai_lark", "   "), grammarSchema("query"))));

        assertThat(errorText(events)).contains("no supported grammar variant was provided.");
    }

    @Test
    void schemaMistakesAreLoudAndWrapped() {
        // 非 object
        var nonObject = drain(new OpenAICompletionsApi(optionsUnstarted()),
            request(gateOpenModel(), grammarTool("pick", lark(), Map.of("type", "string"))));
        assertThat(errorText(nonObject)).contains(
            "Tool \"pick\" cannot use grammar constrained sampling: "
                + "grammar constrained sampling requires an object parameter schema.");

        // required 非恰一个 string
        var wrongRequired = drain(new OpenAICompletionsApi(optionsUnstarted()),
            request(gateOpenModel(), grammarTool("pick", lark(),
                schemaWith("query", true, List.of("query", "extra")))));
        assertThat(errorText(wrongRequired)).contains(
            "exactly one required string property.");

        // properties 缺 required 项
        var missingProperty = drain(new OpenAICompletionsApi(optionsUnstarted()),
            request(gateOpenModel(), grammarTool("pick", lark(),
                schemaMissing("query"))));
        assertThat(errorText(missingProperty)).contains(
            "requires a properties entry for query.");

        // 属性类型非 string
        var nonString = drain(new OpenAICompletionsApi(optionsUnstarted()),
            request(gateOpenModel(), grammarTool("pick", lark(),
                schemaWith("query", false, List.of("query")))));
        assertThat(errorText(nonString)).contains(
            "property query must have type string.");
    }

    @Test
    void gateClosedKeepsBuildErrorsSilent() {
        // 门关：三种坏配置都不许产生构建错误（无 server ⇒ 除构建错误外没有别的失败源）。
        var events = drain(new OpenAICompletionsApi(optionsUnstarted()),
            request(gateClosedModel(), grammarTool("pick", Map.of(), Map.of("type", "string"))));

        assertThat(errorText(events))
            .as("门关 ⇒ 空变体静默回落，不抛构建错误")
            .doesNotContain("cannot use grammar constrained sampling");
    }

    // ── 历史回放：custom tool_calls，input 已净化 ───────────────────

    @Test
    void replaysGrammarToolCallsAsCustomWithSanitizedInput() throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new OpenAICompletionsApi(options(server));
            var transcript = List.<Message>of(
                new Message.SystemMessage("base", java.time.Instant.EPOCH, Map.of(),
                    List.of(grammarTool("pick", lark(), grammarSchema("query"))), List.of()),
                new Message.AssistantMessage(
                    List.of(new ContentBlock.ToolUseContent("call-1", "pick",
                        Map.of("query", "ab" + (char) 0xD800 + "cd"))),
                    "toolUse", null),
                new Message.UserMessage(List.of(new ContentBlock.TextContent("again"))));
            drain(api, new StreamRequest(gateOpenModel(), null, transcript,
                List.of(), -1, -1, Map.of()));

            var toolCall = firstField(MAPPER.readTree(server.body()).path("messages"), "tool_calls");
            var custom = toolCall.path("tool_calls").get(0);
            assertThat(custom.path("type").asText()).isEqualTo("custom");
            assertThat(custom.path("custom").path("name").asText()).isEqualTo("pick");
            assertThat(custom.path("custom").path("input").asText())
                .as("回放输入必须走 SanitizeUnicode（孤对被删）").isEqualTo("abcd");
        }
    }

    // ── 夹具 ─────────────────────────────────────────────────────────

    private static Map<String, String> lark() {
        return variants("openai_lark", "root ::= \"x\"");
    }

    private static Map<String, String> variants(String key, String value) {
        var map = new LinkedHashMap<String, String>();
        map.put(key, value);
        return map;
    }

    private static ToolDefinition grammarTool(String name, Map<String, String> variants,
                                               Map<String, Object> schema) {
        return new ToolDefinition(name, "Pick one", schema, name, null, List.of(),
            "default", new GrammarSampling(variants));
    }

    private static Map<String, Object> grammarSchema(String property) {
        return schemaWith(property, true, List.of(property));
    }

    /** Schema with property typed string (or int), required as given. */
    private static Map<String, Object> schemaWith(String property, boolean stringType,
                                                   List<String> required) {
        var propType = new LinkedHashMap<String, Object>();
        propType.put("type", stringType ? "string" : "integer");
        var props = new LinkedHashMap<String, Object>();
        props.put(property, propType);
        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", required);
        return schema;
    }

    /** Object schema whose required property is absent from properties. */
    private static Map<String, Object> schemaMissing(String property) {
        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", Map.of("other", Map.of("type", "string")));
        schema.put("required", List.of(property));
        return schema;
    }

    private static ModelInfo gateOpenModel() {
        return new ModelInfo(ModelId.of("openai", "gpt-5"), "GPT-5",
            java.util.Set.of(com.pijava.ai.model.ModelCapability.TEXT),
            128_000, 16_384, false, PricingInfo.UNKNOWN, ThinkingLevelMap.empty(),
            Map.of(), Map.of(),
            CatalogCompatRules.openaiResponses("openai", "gpt-5"));
    }

    private static ModelInfo gateClosedModel() {
        return ModelInfo.minimal(ModelId.of("openai", "gpt-4.1"));
    }

    private static StreamRequest request(ModelInfo model, ToolDefinition tool) {
        var system = new Message.SystemMessage("base", java.time.Instant.EPOCH, Map.of(),
            List.of(tool), List.of());
        var user = new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")));
        return new StreamRequest(model, null, List.of(system, user),
            List.of(), -1, -1, Map.of());
    }

    private static ApiOptions options(RecordingHttpServer server) {
        return new ApiOptions(server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, Map.of());
    }

    private static ApiOptions optionsUnstarted() {
        // 构建期错误 ⇒ 没有请求出站，baseUrl 任意。
        return new ApiOptions("http://127.0.0.1:1/v1", "test-key",
            Duration.ofSeconds(5), 0, Map.of());
    }

    private static List<StreamEvent> drain(AbstractChatApi api, StreamRequest request) {
        var events = new ArrayList<StreamEvent>();
        try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
            while (iter.hasNext() && events.size() < 100) {
                events.add(iter.next());
            }
        } catch (Exception ignored) {
            // 桩回 400：请求体已录到，流怎么结束与断言无关
        }
        return events;
    }

    private static String errorText(List<StreamEvent> events) {
        return events.stream().filter(StreamEvent.StreamError.class::isInstance)
            .map(e -> ((StreamEvent.StreamError) e).error().getMessage())
            .findFirst().orElse("<no error event>");
    }

    private static JsonNode firstField(JsonNode array, String fieldName) {
        for (var node : array) {
            if (node.has(fieldName)) {
                return node;
            }
        }
        throw new AssertionError("no message with " + fieldName);
    }
}
