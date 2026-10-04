package com.pijava.ai.protocol;

import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.AuthKind;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.provider.ModelsJsonConfig;
import com.pijava.ai.stream.StreamEvent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包 A-02（原 docs/59 §4.5，登记 B138）：models.json provider 的两处 ApiOptions 重建
 * （{@code createApi} 的 baseUrl pin 与 {@code withInlineKey}）必须保真 authKind。
 *
 * <p>观测面＝**真出站请求头**（{@link RecordingHttpServer}，与 {@code AnthropicAuthKindTest}
 * 同一手法）：BEARER 凭证必须落成 {@code Authorization: Bearer}，改前被五参重建归一成
 * {@code API_KEY} ⇒ 线上是 {@code x-api-key}。</p>
 */
class ModelsJsonAuthKindWireTest {

    @Test
    void bearerCredentialSurvivesTheProviderPinning() throws Exception {
        try (var server = new RecordingHttpServer()) {
            var path = Files.createTempFile("models", ".json");
            Files.writeString(path, """
                {"providers": {"relay": {
                  "baseUrl": "%s",
                  "api": "anthropic-messages",
                  "models": [{"id": "claude-x"}]
                }}}
                """.formatted(server.baseUrl()));
            var provider = ModelsJsonConfig.load(path).buildProviders().get(0);

            var api = (ChatApi) provider.createApi(ChatApi.class, new ApiOptions(
                "", "bearer-tok", Duration.ofSeconds(5), 0, Map.of(), AuthKind.BEARER));
            var request = new StreamRequest(ModelId.of("relay", "claude-x"), null,
                List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")))),
                List.of(), 100, -1, Map.of());
            try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
                var events = new ArrayList<StreamEvent>();
                while (iter.hasNext() && events.size() < 100) {
                    events.add(iter.next());
                }
            } catch (Exception ignored) {
                // 桩回 400，请求头已录到
            }

            assertThat(server.headers())
                .as("BEARER 形态必须活到线上")
                .containsEntry("authorization", "Bearer bearer-tok");
            assertThat(server.headers()).doesNotContainKey("x-api-key");
        }
    }

    @Test
    void inlineKeyInjectionAlsoPreservesTheKind() throws Exception {
        try (var server = new RecordingHttpServer()) {
            var path = Files.createTempFile("models", ".json");
            Files.writeString(path, """
                {"providers": {"relay": {
                  "baseUrl": "%s",
                  "apiKey": "inline-tok",
                  "api": "anthropic-messages",
                  "models": [{"id": "claude-x"}]
                }}}
                """.formatted(server.baseUrl()));
            var provider = ModelsJsonConfig.load(path).buildProviders().get(0);

            // options 的 key 为空 ⇒ withInlineKey 注入内联 key（第二处五参重建点）。
            var api = (ChatApi) provider.createApi(ChatApi.class, new ApiOptions(
                "", "", Duration.ofSeconds(5), 0, Map.of(), AuthKind.BEARER));
            var request = new StreamRequest(ModelId.of("relay", "claude-x"), null,
                List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")))),
                List.of(), 100, -1, Map.of());
            try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
                var events = new ArrayList<StreamEvent>();
                while (iter.hasNext() && events.size() < 100) {
                    events.add(iter.next());
                }
            } catch (Exception ignored) {
                // 桩回 400，请求头已录到
            }

            assertThat(server.headers())
                .containsEntry("authorization", "Bearer inline-tok");
        }
    }
}
