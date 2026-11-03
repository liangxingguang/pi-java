package com.pijava.ai.protocol;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.CompatResolver;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.provider.BuiltinModelCatalogs;
import com.pijava.ai.thinking.ThinkingLevel;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #108（docs/17 §4）：Responses 的 mid-conversation effort。
 *
 * <p>启用条件（pi {@code openai-responses.ts:103-121}）：compat 有
 * {@code supportsMidConvoEffort}、请求带级别、且不是 ChatGPT 订阅登录。启用后顶层
 * {@code reasoning.effort} 固定 {@code "medium"}，真实级别由输入项
 * {@code configuration_update.reasoning.effort} 携带：同模型历史 assistant 之前插一条，
 * 转录末尾再补一条当前级别。</p>
 */
class ResponsesConfigurationUpdateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ModelInfo model(String id) {
        return BuiltinModelCatalogs.catalog("openai")
            .find(ModelId.of("openai", id)).orElseThrow();
    }

    @Test
    void midConversationModelSendsMediumTopLevelAndUpdatesAroundHistory() throws Exception {
        var model = model("gpt-6-sol");
        var transcript = List.<Message>of(
            historyAssistant("openai", "openai-responses", "gpt-6-sol", "low"),
            user("next"));

        var body = body(model, transcript, Optional.of(new ThinkingLevel.High()), false);

        assertThat(body.path("reasoning").path("effort").asText()).isEqualTo("medium");
        var input = types(body);
        // 历史同模型 assistant 之前：configuration_update(low)，随后是 assistant 输出项。
        assertThat(input).containsExactly("configuration_update", "message", "", "configuration_update");
        assertThat(body.path("input").get(0).path("reasoning").path("effort").asText())
            .isEqualTo("low");
        // 转录末尾补当前级别（high）。
        var tail = body.path("input").get(body.path("input").size() - 1);
        assertThat(tail.path("type").asText()).isEqualTo("configuration_update");
        assertThat(tail.path("reasoning").path("effort").asText()).isEqualTo("high");
    }

    @Test
    void crossProviderOrCrossApiHistoryGetsNoUpdateButTheTailStillDoes() throws Exception {
        var model = model("gpt-6-sol");
        for (var history : List.of(
                historyAssistant("openrouter", "openai-responses", "gpt-6-sol", "low"),
                historyAssistant("openai", "openai-completions", "gpt-6-sol", "low"))) {
            var transcript = List.<Message>of(history, user("next"));
            var body = body(model, transcript, Optional.of(new ThinkingLevel.High()), false);

            assertThat(body.path("input").get(0).path("type").asText())
                .as("跨 provider/api 历史前不插 update").isNotEqualTo("configuration_update");
            assertThat(types(body)).containsExactly("message", "", "configuration_update");
        }
    }

    @Test
    void unstampedHistoryGetsNoUpdate() throws Exception {
        var model = model("gpt-6-sol");
        var transcript = List.<Message>of(
            historyAssistant("openai", "openai-responses", "gpt-6-sol", null),
            user("next"));

        var body = body(model, transcript, Optional.of(new ThinkingLevel.High()), false);

        assertThat(types(body)).containsExactly("message", "", "configuration_update");
    }

    @Test
    void chatGptSignInDisablesUpdatesAndKeepsTheRequestedEffort() throws Exception {
        var model = model("gpt-6-sol");
        var transcript = List.<Message>of(
            historyAssistant("openai", "openai-responses", "gpt-6-sol", "low"),
            user("next"));

        var body = body(model, transcript, Optional.of(new ThinkingLevel.High()), true);

        assertThat(body.path("reasoning").path("effort").asText()).isEqualTo("high");
        assertThat(types(body)).containsExactly("message", "");
    }

    @Test
    void modelsWithoutTheCompatFlagKeepTheRequestedEffort() throws Exception {
        var model = model("gpt-5.4");
        var body = body(model, List.of(user("hi")),
            Optional.of(new ThinkingLevel.High()), false);

        assertThat(model.compat().supportsMidConvoEffort()).isNotEqualTo(Boolean.TRUE);
        assertThat(body.path("reasoning").path("effort").asText()).isEqualTo("high");
        assertThat(types(body)).containsExactly("");
    }

    @Test
    void providerThinkingLevelIsTheWireMappedUpdateEffort() {
        var model = model("gpt-6-sol");
        var request = new StreamRequest(model, null, List.of(user("hi")), List.of(), -1, -1);
        var api = new OpenAIResponsesApi(new ApiOptions("http://localhost:1", "test-key",
            Duration.ofSeconds(1), 0, extraLevel()), "OPENAI_API_KEY");

        assertThat(api.providerThinkingLevel(request)).isEqualTo("high");
    }

    @Test
    void chatGptSignInProducesNoProviderThinkingLevel() {
        var model = model("gpt-6-sol");
        var request = new StreamRequest(model, null, List.of(user("hi")), List.of(), -1, -1);
        // 官方 baseUrl ＋ 非 sk- 前缀 ⇒ Sign in with ChatGPT 分类为真。
        var api = new OpenAIResponsesApi(new ApiOptions("https://api.openai.com/v1", "not-a-key",
            Duration.ofSeconds(1), 0, extraLevel()), "OPENAI_API_KEY");

        assertThat(api.providerThinkingLevel(request)).isNull();
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static Map<String, Object> extraLevel() {
        return Map.of("reasoningEffort", new ThinkingLevel.High());
    }

    private static JsonNode body(ModelInfo model, List<Message> transcript,
                                 Optional<ThinkingLevel> level, boolean chatGptSignIn)
            throws Exception {
        var compat = CompatResolver.forResponses(model, false);
        var ropts = new ResponsesOptions(level.orElse(null), null, null,
            ResponsesOptions.CacheRetention.SHORT, null, Map.of());
        var request = new StreamRequest(model, null, transcript, List.of(), -1, -1);
        var params = ResponsesMessageConverter.buildParams(request, ropts,
            model.id().modelName(), "openai-responses", compat,
            Map.of(), chatGptSignIn, Map.of());
        return MAPPER.readTree(
            com.openai.core.ObjectMappers.jsonMapper().writeValueAsString(params._body()));
    }

    /** input 项的 {@code type}（消息项在 4.96 序列化为 {@code "message"}）。 */
    private static List<String> types(JsonNode body) {
        var types = new ArrayList<String>();
        for (var item : body.path("input")) {
            types.add(item.path("type").asText());
        }
        return types;
    }

    private static Message.AssistantMessage historyAssistant(String provider, String api,
                                                             String model, String level) {
        return new Message.AssistantMessage(
            List.of(new ContentBlock.TextContent("hello")), StopReason.STOP, null,
            api, provider, model, level, null, null, null, null);
    }

    private static Message.UserMessage user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }
}
