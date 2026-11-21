package com.pijava.ai.protocol;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.JsonSchemaSampling;
import com.pijava.ai.api.StrictMode;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.thinking.ThinkingLevelMap;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.6.0（pi #12148，{@code mistral-conversations.ts:531-534}）：Mistral 的 strict tool 是
 * <b>显式 opt-in</b> —— 门取 {@code model.compat?.supportsStrictMode ?? false}。
 *
 * <p>Mistral Large 4 带 {@code strict:true} 时会沉默数分钟才发工具调用；{@code strict:false}
 * 秒级返回。旧实现恒传 true（默认偏差）。观测面是真出站请求体
 * （{@link RecordingHttpServer}）。</p>
 */
class MistralToolStrictWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void strictIsOffByDefault() throws Exception {
        var body = body(ModelCompat.NONE);

        var tool = firstTool(body);
        assertThat(tool.path("function").path("strict").asBoolean()).isFalse();
        // strict:false ⇒ parameters 原样（不走 strict schema 转换）。
        assertThat(tool.path("function").path("parameters").has("additionalProperties")).isFalse();
    }

    @Test
    void supportsStrictModeTurnsTheKeyOn() throws Exception {
        // 规范构造：第 14 位是 supportsStrictMode，其余缺席（读点自己补缺省）。
        var compat = new ModelCompat(false, null, true, false, null, null, null, null, null,
            true, null, null, null, Boolean.TRUE, null, null, null, null, null, null,
            Map.of(), Map.of(), null, null, null, null, null, false, null, null, null,
            null, null, null, null, null, null, null, null, null,
            null, null, null);
        var body = body(compat);

        var tool = firstTool(body);
        assertThat(tool.path("function").path("strict").asBoolean()).isTrue();
        // strict:true ⇒ parameters 经 StrictJsonSchema.convert（补 additionalProperties:false）。
        assertThat(tool.path("function").path("parameters")
            .path("additionalProperties").asBoolean()).isFalse();
    }

    // ── 夹具 ────────────────────────────────────────────────────────────

    private static ModelInfo model(ModelCompat compat) {
        return new ModelInfo(ModelId.of("mistral", "mistral-large-latest"),
            "Mistral Large", Set.of(ModelCapability.TEXT), 128_000, 8_192, false,
            PricingInfo.UNKNOWN, ThinkingLevelMap.empty(), Map.of(), Map.of(), compat);
    }

    private static ToolDefinition tool() {
        return new ToolDefinition("lookup", "lookup tool",
            Map.of("type", "object",
                "properties", Map.of("path", Map.of("type", "string")),
                "required", List.of("path")),
            "lookup", null, List.of(), "default",
            new JsonSchemaSampling(StrictMode.PREFER));
    }

    private static JsonNode body(ModelCompat compat) throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new MistralConversationsApi(new ApiOptions(
                server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, Map.of()));
            var request = new StreamRequest(model(compat), "sys",
                List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")))),
                List.of(tool()), -1, -1);
            try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
                while (iter.hasNext()) {
                    iter.next();
                }
            } catch (Exception ignored) {
                // 桩回 400：请求体已录到。
            }
            assertThat(server.body()).as("请求必须真的发出去").isNotEmpty();
            return MAPPER.readTree(server.body());
        }
    }

    private static JsonNode firstTool(JsonNode body) {
        assertThat(body.has("tools")).as("工具表必须在场").isTrue();
        return body.path("tools").get(0);
    }
}
