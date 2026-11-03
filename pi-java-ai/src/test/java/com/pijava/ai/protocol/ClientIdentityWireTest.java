package com.pijava.ai.protocol;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.AuthKind;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.identity.AttributionSetting;
import com.pijava.ai.identity.ClientAttribution;
import com.pijava.ai.identity.ClientIdentity;
import com.pijava.ai.identity.ClientIdentityTarget;
import com.pijava.ai.identity.IdentityHeaders;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.thinking.ThinkingLevelMap;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/14 B266：车道头表的 identity 层与覆盖序。
 *
 * <p>pi 对位：{@code identity.test.ts:159-252}（identity 低于 model/request 头、
 * {@code null} 删除、OpenCode session 头）。</p>
 */
class ClientIdentityWireTest {

    private static final ClientIdentity PI_IDENTITY = new ClientIdentity(
        null, AttributionSetting.of(ClientAttribution.PI), null);

    private static Message user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }

    private static ModelInfo model(String provider, String id, Map<String, String> headers) {
        return new ModelInfo(ModelId.of(provider, id), id, Set.of(ModelCapability.TEXT),
            128_000, 8_192, false, PricingInfo.UNKNOWN, ThinkingLevelMap.empty(),
            headers, Map.of(), ModelCompat.NONE);
    }

    private static ApiOptions options(String baseUrl, ClientIdentity identity,
                                      Map<String, Object> extra) {
        var enriched = new LinkedHashMap<String, Object>(extra);
        enriched.put(IdentityHeaders.EXTRA_KEY, IdentityHeaders.identityHeaders(
            new ClientIdentityTarget("openrouter", baseUrl), identity));
        return new ApiOptions(baseUrl, "test-key", Duration.ofSeconds(5), 0,
            enriched, AuthKind.API_KEY, null, identity);
    }

    private static void drain(com.pijava.ai.api.StreamApi api, ModelInfo model,
                              ApiOptions options) {
        var request = new StreamRequest(model, "sys", List.of(user("hi")), List.of(), -1, -1);
        try (var iterator = api.streamBlocking(request, options)) {
            while (iterator.hasNext()) {
                iterator.next();
            }
        }
    }

    @Test
    void mistralHeaderOrderAndNullDelete() throws IOException {
        try (var server = new RecordingHttpServer()) {
            var extra = new LinkedHashMap<String, Object>();
            var headers = new LinkedHashMap<String, Object>();
            headers.put("HTTP-Referer", null);
            headers.put("X-Request", "yes");
            extra.put("headers", headers);
            var opts = options(server.baseUrl(), PI_IDENTITY, extra);
            var mdl = model("openrouter", "mistral-test",
                Map.of("User-Agent", "model-agent"));
            drain(new MistralConversationsApi(opts, Duration.ofSeconds(5)), mdl, opts);

            assertThat(server.headers())
                .containsEntry("user-agent", "model-agent")
                .containsEntry("x-request", "yes")
                .containsEntry("x-openrouter-title", "pi")
                .doesNotContainKey("http-referer");
        }
    }

    @Test
    void piMessagesAppliesIdentityBelowOptionHeaders() throws IOException {
        try (var server = new RecordingHttpServer()) {
            var extra = new LinkedHashMap<String, Object>();
            extra.put("headers", Map.of("user-agent", "request-agent"));
            var opts = options(server.baseUrl(), PI_IDENTITY, extra);
            var mdl = model("openrouter", "pi-messages-test", Map.of());
            drain(new PiMessagesApi(opts, "PI_MESSAGES_API_KEY"), mdl, opts);

            assertThat(server.headers())
                .containsEntry("user-agent", "request-agent")
                .containsEntry("x-openrouter-title", "pi");
        }
    }

    @Test
    void openAiCompletionsAppliesIdentityAndExtraWins() throws IOException {
        try (var server = new RecordingHttpServer()) {
            var extra = new LinkedHashMap<String, Object>();
            extra.put("headers", Map.of("X-OpenRouter-Title", "model-title"));
            var opts = options(server.baseUrl(), PI_IDENTITY, extra);
            var mdl = model("openrouter", "completions-test", Map.of());
            drain(new OpenAICompletionsApi(opts), mdl, opts);

            assertThat(server.headers())
                .containsEntry("x-openrouter-title", "model-title")
                .containsEntry("http-referer", "https://pi.dev")
                .hasEntrySatisfying("user-agent",
                    value -> assertThat(value).startsWith("pi-java/"));
        }
    }

    @Test
    void anthropicOAuthKeepsClaudeIdentity() throws IOException {
        try (var server = new RecordingHttpServer()) {
            var extra = new LinkedHashMap<String, Object>();
            var opts = options(server.baseUrl(), PI_IDENTITY, extra);
            var oauth = new ApiOptions(opts.baseUrl(), "sk-ant-oat-test", opts.timeout(),
                0, opts.extra(), AuthKind.OAUTH, null, PI_IDENTITY);
            var mdl = model("anthropic", "claude-test", Map.of());
            drain(new AnthropicMessagesApi(oauth), mdl, oauth);

            assertThat(server.headers())
                .containsEntry("user-agent", "claude-cli/"
                    + ClaudeCodeNames.CLAUDE_CODE_VERSION)
                .doesNotContainKey("http-referer");
        }
    }
}
