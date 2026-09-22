package com.pijava.ai.protocol;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.AuthKind;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包 A0 步7（{@code docs/43 D5/D7}）：Anthropic 车道按**凭证种类**分派 ——
 * pi {@code api/anthropic-messages.ts:906-989} 的三分派在 pi-java 的对应物。
 *
 * <table>
 *   <caption>pi → java</caption>
 *   <tr><th>pi</th><th>条件</th><th>SDK 调用</th></tr>
 *   <tr><td>{@code :964-970}</td><td>github-copilot</td><td>Bearer（本车道不涉及，provider 分开）</td></tr>
 *   <tr><td>{@code :971-981}</td><td>{@code isOAuthToken}（值含 {@code sk-ant-oat}）</td><td>{@code authToken} ＋ 身份头</td></tr>
 *   <tr><td>{@code :982-988}</td><td>否则</td><td>{@code apiKey}（{@code x-api-key}）</td></tr>
 * </table>
 *
 * <p>pi-java 的载体是 {@link AuthKind}（{@code ApiOptions} 的组件，D5-A）——
 * 不是「值里含 sk-ant-oat」这种字符串嗅探：种类由 {@code Credentials} 在解析时定下，
 * 车道只按 kind 分派。观测面＝**真出站请求头**（{@link RecordingHttpServer}）。</p>
 */
class AnthropicAuthKindTest {

    private static final ModelId<?> TARGET = ModelId.of("anthropic", "claude-sonnet-5");

    private static Map<String, String> headersFor(ApiOptions options) throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new AnthropicMessagesApi(
                new ApiOptions(server.baseUrl(), options.apiKey(), options.timeout(),
                    options.maxRetries(), options.extra(), options.authKind()),
                "ANTHROPIC_API_KEY");
            var request = new StreamRequest(TARGET, null,
                List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")))),
                List.of(), 100, 0.5, Map.of());
            try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
                var events = new ArrayList<StreamEvent>();
                while (iter.hasNext() && events.size() < 100) events.add(iter.next());
            } catch (Exception ignored) {
                // 桩回 400，请求头已录到
            }
            return server.headers();
        }
    }

    private static ApiOptions with(AuthKind kind, String value) {
        return new ApiOptions("", value, Duration.ofSeconds(5), 0, Map.of(), kind);
    }

    @Test
    void bearerKindSendsAuthorizationHeaderAndNoApiKey() throws Exception {
        var headers = headersFor(with(AuthKind.BEARER, "bearer-tok"));

        assertThat(headers).as("Bearer 形态").containsEntry("authorization", "Bearer bearer-tok");
        assertThat(headers).as("不许同时带 x-api-key").doesNotContainKey("x-api-key");
    }

    @Test
    void oauthKindSendsBearerToo() throws Exception {
        var headers = headersFor(with(AuthKind.OAUTH, "oat-tok"));

        assertThat(headers).as("OAuth 也走 Bearer 头").containsEntry("authorization", "Bearer oat-tok");
        assertThat(headers).doesNotContainKey("x-api-key");
    }

    @Test
    void apiKeyKindSendsXApiKeyAndNoAuthorization() throws Exception {
        var headers = headersFor(with(AuthKind.API_KEY, "plain-key"));

        assertThat(headers).as("默认形态仍是 x-api-key").containsEntry("x-api-key", "plain-key");
        assertThat(headers).as("不许凭空带 Authorization").doesNotContainKey("authorization");
    }

    @Test
    void fiveArgConstructorDefaultsToApiKey() throws Exception {
        // 旧构造器（无 kind 组件）⇒ API_KEY，行为与修复前完全一致（存量调用点不受影响）。
        var headers = headersFor(new ApiOptions("", "plain-key", Duration.ofSeconds(5), 0, Map.of()));

        assertThat(headers).containsEntry("x-api-key", "plain-key");
        assertThat(headers).doesNotContainKey("authorization");
    }
}