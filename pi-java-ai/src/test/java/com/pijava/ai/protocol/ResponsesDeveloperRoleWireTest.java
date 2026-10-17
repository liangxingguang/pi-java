package com.pijava.ai.protocol;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ThinkingLevelMap;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B222（批 1，原 docs/09 ③）：Responses 车道前导/中途系统指令的角色。
 *
 * <p>pi {@code openai-responses-shared.ts:214-215}：
 * {@code model.reasoning && compat?.supportsDeveloperRole !== false ? "developer" : "system"}
 * ——缺省 <b>true</b>。注意 completions 车道是相反缺省（显式 true 才行，
 * {@code OpenAICompletionsMessageConverter} 已对齐，别抄反）。</p>
 */
class ResponsesDeveloperRoleWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void reasoningModelDefaultsToDeveloperRole() throws Exception {
        var body = body(model(Set.of(ModelCapability.TEXT, ModelCapability.THINKING), ModelCompat.NONE));

        assertThat(firstInstructionRole(body))
            .as("推理模型＋compat 缺省 ⇒ developer（缺省 true）")
            .isEqualTo("developer");
    }

    @Test
    void explicitFalseCompatFallsBackToSystem() throws Exception {
        var body = body(model(Set.of(ModelCapability.TEXT, ModelCapability.THINKING), compatDeveloper(false)));

        assertThat(firstInstructionRole(body))
            .as("supportsDeveloperRole:false ⇒ system（!== false 门）")
            .isEqualTo("system");
    }

    @Test
    void nonReasoningModelAlwaysGetsSystem() throws Exception {
        var body = body(model(Set.of(ModelCapability.TEXT), compatDeveloper(true)));

        assertThat(firstInstructionRole(body))
            .as("非推理模型即便 compat 显式 true 也走 system")
            .isEqualTo("system");
    }

    @Test
    void midConversationSystemUpdateUsesTheSameRole() throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new OpenAIResponsesApi(
                new ApiOptions(server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, Map.of()),
                "OPENAI_API_KEY");
            List<Message> messages = List.of(
                new Message.SystemMessage("lead", Instant.EPOCH, Map.of(), List.of(), List.of()),
                new Message.UserMessage(List.of(new ContentBlock.TextContent("first"))),
                new Message.SystemMessage("updated rules", Instant.EPOCH, Map.of(), List.of(), List.of()),
                new Message.UserMessage(List.of(new ContentBlock.TextContent("second"))));
            // supportsMidConvoSystemMessages 才保留中途系统项（否则 collapseSystemMessages
            // 会把它们折进首条；pi shared:resolveTranscript 同形门），developer 位同开。
            var request = new StreamRequest(
                model(Set.of(ModelCapability.TEXT, ModelCapability.THINKING), compatMidConvoAndDeveloper()),
                null, messages, List.of(), -1, -1);
            drain(api, request);

            var input = MAPPER.readTree(server.body()).path("input");
            var roles = new ArrayList<String>();
            for (JsonNode item : input) {
                var role = item.path("role").asText("");
                if ("developer".equals(role) || "system".equals(role)) {
                    roles.add(role);
                }
            }
            assertThat(roles).as("前导与中途更新同角色").containsExactly("developer", "developer");
        }
    }

    // ── 夹具 ────────────────────────────────────────────────────────────

    private static String firstInstructionRole(JsonNode body) {
        return body.path("input").get(0).path("role").asText();
    }

    private static JsonNode body(ModelInfo model) throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new OpenAIResponsesApi(
                new ApiOptions(server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, Map.of()),
                "OPENAI_API_KEY");
            var request = new StreamRequest(model, "be brief",
                List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")))),
                List.of(), -1, -1);
            drain(api, request);
            assertThat(server.body()).as("请求必须真的发出去").isNotEmpty();
            return MAPPER.readTree(server.body());
        }
    }

    private static void drain(OpenAIResponsesApi api, StreamRequest request) {
        try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
            var events = new ArrayList<StreamEvent>();
            while (iter.hasNext() && events.size() < 100) {
                events.add(iter.next());
            }
        } catch (Exception ignored) {
            // 桩恒回 400：请求体已录到，流怎么结束与断言无关
        }
    }

    private static ModelInfo model(Set<ModelCapability> caps, ModelCompat compat) {
        return new ModelInfo(ModelId.of("openai", "gpt-5"), "gpt-5", caps,
            200_000, 32_000, false, PricingInfo.UNKNOWN,
            ThinkingLevelMap.empty(), Map.of(), Map.of(), compat);
    }

    /** 只改 supportsDeveloperRole 一位（25 参便捷构造，其余同 {@link ModelCompat#NONE}）。 */
    private static ModelCompat compatDeveloper(Boolean flag) {
        return new ModelCompat(
            false, null, true, false,
            null, null, null, null, null,
            true,
            null, null, flag, null, null, null,
            null, null, null,
            null, null, null, null, null, null);
    }

    /** 开 supportsMidConvoSystemMessages（保留中途系统项）＋supportsDeveloperRole 缺省 true。 */
    private static ModelCompat compatMidConvoAndDeveloper() {
        return new ModelCompat(
            false, null, true, false,
            Boolean.TRUE, null, null, null, null,
            true,
            null, null, Boolean.TRUE, null, null, null,
            null, null, null,
            null, null, null, null, null, null);
    }
}
