package com.pijava.ai.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ThinkingLevelMap;

/**
 * #114 余项：Anthropic **非 strict** 工具的根 schema 归一化。
 *
 * <p>pi {@code anthropic-messages.ts:1914-1929} 的 {@code legacyInputSchema} 只发
 * {@code type/properties/required} 与可选的 {@code additionalProperties/$defs/definitions}：
 * 根 {@code title}/{@code $schema}/{@code anyOf} 等一律不发（Anthropic 对根 union 直接 400），
 * {@code properties}/{@code required} 缺席时补 {@code {}}／{@code []}。观测面是真出站请求体。</p>
 */
class AnthropicLegacyInputSchemaWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 根含额外键、且缺 {@code required}：归一化后只余白名单六键，{@code required} 补空数组。 */
    @Test
    void nonStrictSchemaDropsOtherRootKeysAndDefaultsRequired() throws Exception {
        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("title", "Thing");
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("anyOf", List.of(Map.of("type", "object"), Map.of("type", "null")));
        schema.put("properties", Map.of("path", Map.of("type", "string")));
        schema.put("additionalProperties", false);
        schema.put("$defs", Map.of("defsOnly", Map.of("type", "string")));
        schema.put("definitions", Map.of("legacyDefs", Map.of("type", "number")));

        var input = inputSchema(capture(tool("legacy", schema)));

        assertThat(fieldNames(input)).containsExactly(
            "type", "properties", "required", "additionalProperties", "$defs", "definitions");
        assertThat(input.path("type").asText()).isEqualTo("object");
        assertThat(input.path("required").isArray()).isTrue();
        assertThat(input.path("required")).isEmpty();
        assertThat(input.path("properties").path("path").path("type").asText()).isEqualTo("string");
        assertThat(input.has("title")).isFalse();
        assertThat(input.has("$schema")).isFalse();
        assertThat(input.has("anyOf")).isFalse();
    }

    /** 缺 {@code properties} 补 {@code {}}；没有 {@code additionalProperties} 就不发该键。 */
    @Test
    void nonStrictSchemaDefaultsPropertiesAndOmitsAbsentOptionalKeys() throws Exception {
        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");

        var input = inputSchema(capture(tool("bare", schema)));

        assertThat(fieldNames(input)).containsExactly("type", "properties", "required");
        assertThat(input.path("properties").isObject()).isTrue();
        assertThat(input.path("properties").isEmpty()).isTrue();
        assertThat(input.path("required")).isEmpty();
    }

    // ── 夹具 ─────────────────────────────────────────────────────────

    private static ToolDefinition tool(String name, Map<String, Object> schema) {
        return new ToolDefinition(name, name + " tool", schema);
    }

    private static JsonNode inputSchema(String rawBody) throws Exception {
        var body = MAPPER.readTree(rawBody);
        assertThat(body.path("tools")).hasSize(1);
        return body.path("tools").get(0).path("input_schema");
    }

    private static List<String> fieldNames(JsonNode node) {
        var names = new ArrayList<String>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static String capture(ToolDefinition tool) throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new AnthropicMessagesApi(
                new ApiOptions(server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, Map.of()),
                "ANTHROPIC_API_KEY");
            var messages = List.<Message>of(
                new Message.SystemMessage("base prompt", Instant.EPOCH, Map.of(),
                    List.of(tool), List.of()),
                new Message.UserMessage(List.of(new ContentBlock.TextContent("hi"))));
            var request = new StreamRequest(model(), null, messages, List.of(), -1, -1);
            try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
                var events = new ArrayList<StreamEvent>();
                while (iter.hasNext() && events.size() < 100) {
                    events.add(iter.next());
                }
            } catch (Exception ignored) {
                // 桩回 400：请求体已录到，流怎么结束与断言无关。
            }
            assertThat(server.body()).as("请求必须真的发出去").isNotEmpty();
            return server.body();
        }
    }

    private static ModelInfo model() {
        return new ModelInfo(ModelId.of("anthropic", "claude-opus-5"), "Claude Opus 5", Set.of(),
            100_000, 1000, false, PricingInfo.UNKNOWN, ThinkingLevelMap.empty(),
            Map.of(), Map.of(), ModelCompat.NONE);
    }
}
