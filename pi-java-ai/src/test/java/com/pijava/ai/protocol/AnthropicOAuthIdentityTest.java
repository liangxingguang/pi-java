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
 * 包 A0 步8（{@code docs/43 D7}）：OAuth 形态的**身份头**与 {@code sk-ant-oat} 识别 ——
 * pi {@code api/anthropic-messages.ts:906-908} ＋ {@code :946-972}。
 *
 * <pre>
 * function isOAuthToken(apiKey) { return apiKey.includes("sk-ant-oat"); }   // :906-908
 *
 * if (apiKey &amp;&amp; isOAuthToken(apiKey)) {                                      // :946
 *   new Anthropic({ apiKey: null, authToken: apiKey,                        // Bearer
 *     defaultHeaders: { accept, "anthropic-dangerous-direct-browser-access",
 *                       "user-agent": `claude-cli/${claudeCodeVersion}`,   // "2.1.251"（:87）
 *                       "x-app": "cli" } });
 * }
 * </pre>
 *
 * <p>⚠️ pi 的判据是**值**（{@code includes("sk-ant-oat")}），不是「变量来自哪」——
 * 所以 CLI 直给 / 文件凭证里的 oat 值同样走 OAuth 形态。pi-java 的 {@link AuthKind}
 * 是显式载体，两者都要认（{@link #oatMarkerInPlainApiKeyIsTreatedAsOauth} 钉住后者）。</p>
 *
 * <p>⚠️ pi 另外两枚头（{@code accept}、{@code anthropic-dangerous-direct-browser-access}）
 * 是**浏览器场景**的产物（pi 恒传 {@code dangerouslyAllowBrowser: true}），java 无该场景
 * ⇒ 不移植，登记在 {@code docs/43 §6}。</p>
 *
 * <p>⚠️ pi 的 OAuth 分支还**改系统提示**（{@code :1078-1090} 前置 "You are Claude Code…"）
 * 与工具名（{@code toClaudeCodeName}）—— 那两个面不在 D7 范围，登记。</p>
 */
class AnthropicOAuthIdentityTest {

    private static final ModelId<?> TARGET = ModelId.of("anthropic", "claude-sonnet-5");

    private static Map<String, String> headersFor(AuthKind kind, String value) throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new AnthropicMessagesApi(
                new ApiOptions(server.baseUrl(), value, Duration.ofSeconds(5), 0, Map.of(), kind),
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

    @Test
    void oauthKindSendsIdentityHeaders() throws Exception {
        var headers = headersFor(AuthKind.OAUTH, "oat-tok");

        assertThat(headers).containsEntry("user-agent", "claude-cli/2.1.251");
        assertThat(headers).containsEntry("x-app", "cli");
        assertThat(headers).containsEntry("authorization", "Bearer oat-tok");
        assertThat(headers).doesNotContainKey("x-api-key");
    }

    @Test
    void oatMarkerInPlainApiKeyIsTreatedAsOauth() throws Exception {
        // pi 的判据是值：CLI 直给 / 文件凭证里的 oat 值同样走 OAuth 形态。
        var headers = headersFor(AuthKind.API_KEY, "sk-ant-oat01-abc");

        assertThat(headers).containsEntry("authorization", "Bearer sk-ant-oat01-abc");
        assertThat(headers).containsEntry("user-agent", "claude-cli/2.1.251");
        assertThat(headers).containsEntry("x-app", "cli");
        assertThat(headers).doesNotContainKey("x-api-key");
    }

    @Test
    void bearerKindHasNoIdentityHeaders() throws Exception {
        // ANTHROPIC_AUTH_TOKEN 走的是 pi 的「header-owned」路径（provider 层给
        // Authorization 头），身份头只属于 **OAuth** 分支 ⇒ 非 oat 的值不带这两枚头。
        var headers = headersFor(AuthKind.BEARER, "plain-bearer-tok");

        assertThat(headers).containsEntry("authorization", "Bearer plain-bearer-tok");
        // ⚠️ SDK 自带一枚 user-agent（{@code AnthropicClientImpl/Java …}）⇒ 判据是
        // 「**不是** claude-cli 那枚」，不是「头不存在」。
        assertThat(headers).doesNotContainEntry("user-agent", "claude-cli/2.1.251");
        assertThat(headers).doesNotContainKey("x-app");
    }
}