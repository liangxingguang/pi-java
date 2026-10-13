package com.pijava.ai.protocol;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.provider.BuiltinModelCatalogs;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B129：xiaomi 内置目录必须标注
 * {@code requiresReasoningContentOnAssistantMessages=true}（新锚点
 * {@code providers/data/xiaomi*.json} 实测全 true；探测给不出 ——
 * {@code isDeepSeek} 不认 xiaomi）。
 *
 * <p>夹具走**内置目录真实条目**（npm 1.1.0 分片 {@code xiaomi.json}）：
 * 标注补上之前，{@code OpenAICompletionsMessageConverter:413-417} 的空串补全不点火 ⇒
 * 请求体里没有 {@code reasoning_content}，本用例红。</p>
 */
class XiaomiReasoningContentWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void xiaomiAssistantHistoryCarriesAnEmptyReasoningContent() throws Exception {
        var model = BuiltinModelCatalogs.catalog("xiaomi")
            .find(ModelId.of("xiaomi", "mimo-v2.5-pro")).orElseThrow();
        var body = requestBody(model, "xiaomi", "mimo-v2.5-pro");

        JsonNode assistant = null;
        for (var message : body.path("messages")) {
            if ("assistant".equals(message.path("role").asText())) {
                assistant = message;
            }
        }
        assertThat(assistant).as("助手历史必须随请求发出").isNotNull();
        assertThat(assistant.path("content").asText()).isEqualTo("ok");
        assertThat(assistant.has("reasoning_content"))
            .as("xiaomi 位为 true ⇒ 补空串 reasoning_content（deepseek 族 relay 缺了会 4xx）")
            .isTrue();
        assertThat(assistant.path("reasoning_content").asText()).isEqualTo("");
    }

    /** 对照：同样走内置目录的 zai/glm-4.7（pi 目录无此位）⇒ 不凭空补推理字段。 */
    @Test
    void otherBuiltInProvidersDoNotGetTheBackfill() throws Exception {
        var model = BuiltinModelCatalogs.catalog("zai")
            .find(ModelId.of("zai", "glm-4.7")).orElseThrow();
        var body = requestBody(model, "zai", "glm-4.7");

        JsonNode assistant = null;
        for (var message : body.path("messages")) {
            if ("assistant".equals(message.path("role").asText())) {
                assistant = message;
            }
        }
        assertThat(assistant).isNotNull();
        assertThat(assistant.has("reasoning_content")).isFalse();
    }

    private static JsonNode requestBody(ModelInfo model, String provider, String modelId)
            throws Exception {
        var assistant = new Message.AssistantMessage(
            List.of(new ContentBlock.TextContent("ok")), StopReason.STOP, null,
            "openai-completions", provider, modelId, null, null, null, null);
        var user = new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")));

        try (var server = new RecordingHttpServer()) {
            var api = new OpenAICompletionsApi(
                new ApiOptions(server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, Map.of()));
            var request = new StreamRequest(model, "be brief", List.of(assistant, user),
                List.of(), -1, -1, Optional.empty());
            try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
                while (iter.hasNext()) {
                    iter.next();
                }
            } catch (Exception ignored) {
                // 桩恒回 400：请求体已录到。
            }
            assertThat(server.body()).as("请求必须真的发出去").isNotEmpty();
            return MAPPER.readTree(server.body());
        }
    }
}
