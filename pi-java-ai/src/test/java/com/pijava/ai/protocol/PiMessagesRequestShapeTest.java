package com.pijava.ai.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;

import org.junit.jupiter.api.Test;

/**
 * <b>包 A2 的 R2</b>（{@code docs/49 §4.2 F2}）：pi-messages 的 POST body 形状。
 *
 * <p>pi 自 {@code 9e05370b2}（2026-09-16）起发 {@code {model, context, options}}，其中
 * {@code context} **就是** TranscriptContext（只有 {@code messages}，系统消息在数组里，
 * {@code pi-messages.ts:374-390}）。本仓此前发的是重构**前**的
 * {@code {systemPrompt?, messages, tools?}}。</p>
 */
class PiMessagesRequestShapeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void sendsTheTranscriptAsContextWithoutLegacyKeys() throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new PiMessagesApi(
                new ApiOptions(server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, Map.of()),
                "PI_MESSAGES_API_KEY");
            var messages = List.<Message>of(prompt(), user("hi"));
            var request = new StreamRequest(ModelInfo.minimal(ModelId.of("pi-messages", "gw")),
                null, messages, List.of(), -1, -1, Map.of());
            try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
                while (iter.hasNext()) {
                    iter.next();
                }
            } catch (Exception ignored) {
                // 桩回 400；请求体已录到
            }

            var body = MAPPER.readTree(server.body());
            var context = body.path("context");

            // 旧形状的两个键必须**不在**（它们正是 F2 的漂移）
            assertThat(context.has("systemPrompt")).as(server.body()).isFalse();
            assertThat(context.has("tools")).as(server.body()).isFalse();

            var messagesNode = context.path("messages");
            assertThat(messagesNode).hasSize(2);
            var system = messagesNode.get(0);
            assertThat(system.path("role").asText()).isEqualTo("system");
            assertThat(system.path("content").get(0).path("text").asText()).isEqualTo("be brief");
            assertThat(system.path("timestamp").asLong()).isEqualTo(0L);
            // pi 的 ai 层 Tool 形状（types.ts:600-605）：三字段，字段名是 parameters
            var tool = system.path("toolsAdded").get(0);
            assertThat(tool.path("name").asText()).isEqualTo("lookup");
            assertThat(tool.path("description").asText()).isEqualTo("Look up a value");
            assertThat(tool.path("parameters").path("type").asText()).isEqualTo("object");
            assertThat(tool.has("inputSchema")).as(server.body()).isFalse();
            // 键集**恰是**三个：包 B87② 之后本车道与 SessionJson 的系统消息落线共用
            // Transcripts.toToolDeclaration，两处都要有这条牙（多一个键就说明哪里又自己
            // 拼了形状）。AssertJ 的 Iterator 断言会先消费迭代器，故先落成 List。
            var toolKeys = new java.util.ArrayList<String>();
            tool.fieldNames().forEachRemaining(toolKeys::add);
            assertThat(toolKeys).containsExactlyInAnyOrder("name", "description", "parameters");
            assertThat(messagesNode.get(1).path("role").asText()).isEqualTo("user");
        }
    }

    /** 空集合按 pi 的 `...(x ? {x} : {})` 省略（同 SessionJson 的 A7 规则）。 */
    @Test
    void omitsEmptyOptionalFields() throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new PiMessagesApi(
                new ApiOptions(server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, Map.of()),
                "PI_MESSAGES_API_KEY");
            var bare = new Message.SystemMessage("just text", Instant.ofEpochMilli(7),
                Map.of(), List.of(), List.of());
            var request = new StreamRequest(ModelInfo.minimal(ModelId.of("pi-messages", "gw")),
                null, List.<Message>of(bare, user("hi")), List.of(), -1, -1, Map.of());
            try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
                while (iter.hasNext()) {
                    iter.next();
                }
            } catch (Exception ignored) {
                // 桩回 400
            }

            var system = MAPPER.readTree(server.body()).path("context").path("messages").get(0);
            assertThat(system.has("sections")).isFalse();
            assertThat(system.has("toolsAdded")).isFalse();
            assertThat(system.has("toolsRemoved")).isFalse();
            assertThat(system.path("timestamp").asLong()).isEqualTo(7L);
        }
    }

    /**
     * ★ 包 A-10：本车道**不参与**请求侧选项解析 —— envelope 的 {@code options} 里
     * <b>不得</b>出现 {@code maxTokens}。
     *
     * <p>pi 的 pi-messages 车道**不过** {@code buildBaseOptions}
     * （{@code pi-messages.ts:431-443} 直接 {@code {...options, reasoning, toolChoice, debug}}）
     * ⇒ 它的 {@code maxTokens} 恒为 {@code undefined}（缺席）。而 java 的
     * {@code AbstractChatApi} 是**所有**车道的漏斗，默认会填上解析值 —— 故
     * {@code PiMessagesApi} 必须显式豁免（{@code docs/57 §6 R2}）。
     * 没有这条豁免，本车道会**凭空多发**一个字段。</p>
     *
     * <p>夹具刻意用 {@code ModelInfo.minimal}（目录未命中）：不豁免时漏斗会填
     * {@code NO_MODEL_CAP_FALLBACK} ⇒ 断言必红。配对的正向证据在
     * {@code AnthropicMaxTokensWireTest}／{@code CompletionsMaxTokensWireTest}
     * （同样缺席、同样经漏斗，那两条**该有**）。</p>
     */
    @Test
    void doesNotSendTheResolvedMaxTokens() throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new PiMessagesApi(
                new ApiOptions(server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, Map.of()),
                "PI_MESSAGES_API_KEY");
            var request = new StreamRequest(ModelInfo.minimal(ModelId.of("pi-messages", "gw")),
                null, List.<Message>of(prompt(), user("hi")), List.of(), -1, -1, Map.of());
            try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
                while (iter.hasNext()) {
                    iter.next();
                }
            } catch (Exception ignored) {
                // 桩回 400
            }

            var options = MAPPER.readTree(server.body()).path("options");
            assertThat(options.has("maxTokens")).as(server.body()).isFalse();
            assertThat(options.has("temperature")).as(server.body()).isFalse();
        }
    }

    private static Message.SystemMessage prompt() {
        return new Message.SystemMessage("be brief", Instant.EPOCH, Map.of(),
            List.of(new ToolDefinition("lookup", "Look up a value", Map.of("type", "object"))),
            List.of());
    }

    private static Message.UserMessage user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }
}
