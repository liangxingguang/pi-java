package com.pijava.ai.protocol;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.provider.BuiltinModelCatalogs;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/13 裁决① 的<b>探针固化夹具</b>：beta 协商走 {@code anthropic-beta}
 * <b>请求头</b>，不落请求体。
 *
 * <p>探针背景（2026-10-14，anthropic-java 2.66.0）：既有代码把 beta 列表经
 * {@code putAdditionalBodyProperty("betas", …)} 发出。loopback 抓包实测 ——
 * stable SDK 不认识这个额外体键：它不会被提升成请求头（请求头集合里没有
 * anthropic-beta），只作为未知键留在 JSON 体里，服务端收不到 beta 协商。
 * pi 的 TS SDK 是把 {@code params.betas} 编成逗号连接的 anthropic-beta 头。
 * 本夹具钉住「头在、体无」，防止通道再退回 body。</p>
 */
class AnthropicBetaHeaderProbeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void betasRideTheAnthropicBetaHeaderNotTheBody() throws Exception {
        var model = BuiltinModelCatalogs.catalog("anthropic")
            .find(ModelId.of("anthropic", "claude-fable-5")).orElseThrow();
        List<Message> messages = List.of(new Message.UserMessage(
            List.of(new ContentBlock.TextContent("hi"))));

        String betaHeader;
        JsonNode body;
        try (var server = new RecordingHttpServer()) {
            var api = new AnthropicMessagesApi(
                new ApiOptions(server.baseUrl(), "test-key",
                    java.time.Duration.ofSeconds(5), 0, Map.of()),
                "ANTHROPIC_API_KEY");
            var request = new StreamRequest(model, "be brief", messages, List.of(), -1, -1);
            try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
                while (iter.hasNext()) {
                    iter.next();
                }
            } catch (Exception ignored) {
                // 桩回 400：请求已录到。
            }
            assertThat(server.body()).as("请求必须真的发出去").isNotEmpty();
            betaHeader = server.headers().get("anthropic-beta");
            body = MAPPER.readTree(server.body());
        }

        // fable-5 目录上带 allowedFallbackModels ⇒ 服务端回退 beta 必在头里。
        assertThat(betaHeader)
            .as("beta 协商是 anthropic-beta 请求头（逗号连接），不是 body 的 betas 键")
            .contains("server-side-fallback-2026-07-01");
        assertThat(betaHeader).doesNotContain(",,");
        assertThat(body.has("betas"))
            .as("beta 列表不许再以未知键留在请求体里")
            .isFalse();
    }
}
