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
import com.pijava.ai.provider.BuiltinModelCatalogs;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelId;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B99（docs/13 §1.2.4/§3.3，pi {@code insertThinkingLevelMessages}，
 * anthropic-mid-conversation-effort.test.ts）：managed 模型请求里的历史/队尾
 * effort marker 重建。
 */
class AnthropicEffortMarkerWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ModelInfo managedModel() {
        return BuiltinModelCatalogs.catalog("anthropic")
            .find(ModelId.of("anthropic", "claude-fable-5-1")).orElseThrow();
    }

    private static Message.AssistantMessage historyAssistant(String provider,
                                                              String api,
                                                              String stampedEffort,
                                                              List<ContentBlock> content) {
        return new Message.AssistantMessage(
            content, StopReason.STOP, null,
            api, provider, "claude-fable-5-1", stampedEffort,
            null, null, null, null);
    }

    private static ContentBlock.ThinkingContent thinking() {
        return new ContentBlock.ThinkingContent("ponder", "sig-1");
    }

    private static Message.UserMessage user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }

    /** 夹具 3：合格历史 assistant 前插 marker；队尾 active marker 无条件在最后；同 effort 也插。 */
    @Test
    void insertsMarkersBeforeHistoryAndAppendsTheActiveMarker() throws Exception {
        var transcript = List.<Message>of(
            historyAssistant("anthropic", "anthropic-messages", "high",
                List.of(thinking())),
            user("next question"),
            historyAssistant("anthropic", "anthropic-messages", "high",
                List.of(thinking())),
            user("after second"));

        var body = body(managedModel(), transcript, Optional.empty());
        var messages = body.get("messages");

        // 历史 marker、assistant、用户……最后是 active marker。
        assertThat(roleNames(messages)).containsExactly(
            "system", "assistant", "user",
            "system", "assistant", "user", "system");
        var historicalMarker = messages.get(0);
        assertThat(historicalMarker.get("content").isArray()).isTrue();
        assertThat(historicalMarker.get("content")).isEmpty();
        assertThat(historicalMarker.path("output_config").path("effort").asText())
            .isEqualTo("high");
        var activeMarker = messages.get(messages.size() - 1);
        assertThat(activeMarker.path("output_config").path("effort").asText())
            .isEqualTo("high");
        // 无 reasoning ⇒ active effort 恒 high（顶层 output_config 同值）。
        assertThat(body.path("output_config").path("effort").asText()).isEqualTo("high");
    }

    /** active effort 跟随当前级别（fable-5-1 目录无映射 ⇒ low 原样）。 */
    @Test
    void activeMarkerUsesTheCurrentEffort() throws Exception {
        var transcript = List.<Message>of(user("hi"));

        var body = body(managedModel(), transcript, Optional.of(
            new com.pijava.ai.thinking.ThinkingLevel.Low()));

        var activeMarker = body.get("messages").get(body.get("messages").size() - 1);
        assertThat(activeMarker.path("output_config").path("effort").asText())
            .isEqualTo("low");
    }

    /** 夹具 4：不同 provider／不同 api／非法 effort／空 assistant ⇒ 不插历史 marker。 */
    @Test
    void skipsMarkersForWrongProviderWrongApiBadEffortAndEmptyBlocks() throws Exception {
        var transcript = List.<Message>of(
            // provider 不同（openrouter 历史不在 anthropic 端点的前缀里）
            historyAssistant("openrouter", "anthropic-messages", "high",
                List.of(thinking())),
            // api 不同
            historyAssistant("anthropic", "openai-completions", "high",
                List.of(thinking())),
            // 盖章值不在五值白名单
            historyAssistant("anthropic", "anthropic-messages", "medium-well",
                List.of(thinking())),
            // 空块 assistant（即便盖了章也不索引）
            historyAssistant("anthropic", "anthropic-messages", "high", List.of()),
            user("next"));

        var body = body(managedModel(), transcript, Optional.empty());
        var messages = body.get("messages");

        // 只有队尾 active marker；四条历史 assistant 前都没有。
        assertThat(roleNames(messages)).containsExactly(
            "assistant", "assistant", "assistant", "user", "system");
    }

    /** 夹具 5：active marker 不吃缓存断点 —— 断点在它前面的末条 user 上。 */
    @Test
    void cacheBreakpointLandsBeforeTheActiveMarker() throws Exception {
        var transcript = List.<Message>of(user("hi"));

        var body = body(managedModel(), transcript, Optional.empty());
        var messages = body.get("messages");
        var activeMarker = messages.get(messages.size() - 1);
        assertThat(activeMarker.path("output_config").path("effort").asText())
            .isEqualTo("high");
        assertThat(activeMarker.path("cache_control").isMissingNode()).isTrue();
        // 末条 user（marker 前一条）末块吃断点。
        var lastUser = messages.get(messages.size() - 2);
        var lastBlock = lastUser.get("content").get(lastUser.get("content").size() - 1);
        assertThat(lastBlock.path("cache_control").path("type").asText())
            .isEqualTo("ephemeral");
    }

    /** 夹具 6：非 managed 模型（即便 forceAdaptive）一条 marker 都没有。 */
    @Test
    void nonManagedModelSendsNoMarkers() throws Exception {
        var model = BuiltinModelCatalogs.catalog("anthropic")
            .find(ModelId.of("anthropic", "claude-opus-4-8")).orElseThrow();
        var transcript = List.<Message>of(
            historyAssistant("anthropic", "anthropic-messages", "high",
                List.of(thinking())),
            user("next"));

        var body = body(model, transcript,
            Optional.of(new com.pijava.ai.thinking.ThinkingLevel.High()));

        for (var message : body.get("messages")) {
            assertThat(message.has("output_config"))
                .as("output_config 是消息级 marker；顶层那份属于 adaptive 请求体，不在这里")
                .isFalse();
            assertThat(message.path("content").isArray() && message.get("content").isEmpty())
                .isFalse();
        }
    }

    // ── 夹具 ────────────────────────────────────────────────────────────

    private static List<String> roleNames(JsonNode messages) {
        var names = new ArrayList<String>();
        for (var m : messages) {
            names.add(m.path("role").asText());
        }
        return names;
    }

    private static JsonNode body(ModelInfo model, List<Message> transcript,
                                  Optional<com.pijava.ai.thinking.ThinkingLevel> reasoning)
            throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new AnthropicMessagesApi(
                new ApiOptions(server.baseUrl(), "test-key", Duration.ofSeconds(5), 0,
                    Map.of()),
                "ANTHROPIC_API_KEY");
            var request = new StreamRequest(model, null, transcript, List.of(),
                -1, -1, reasoning);
            try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
                while (iter.hasNext()) {
                    iter.next();
                }
            } catch (Exception ignored) {
                // 桩恒回 400：请求体已录到。
            }
            JsonNode node = MAPPER.readTree(server.body());
            // 自检：请求确实发出且是消息数组。
            assertThat(node.get("messages").isArray()).isTrue();
            return node;
        }
    }
}
