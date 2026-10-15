package com.pijava.ai.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 通用 OAuth2 授权码 + PKCE 流程（pi {@code auth/oauth/openrouter.ts} 形状）——
 * 主流 7 家里现仅 OpenRouter 使用；Anthropic 有专用 {@link AnthropicOAuthFlow}。
 *
 * <p>回调走共享 {@link LoopbackCallbackServer}：端口 0（空闲端口）、随机
 * {@code /oauth/callback/<uuid>} 路径（不发 state，随机路径挡野请求）、5 分钟
 * 整体超时；token 交换在回调 {@code complete} 内完成（交换失败浏览器可见 502）；
 * 回调与手工粘贴竞速（无控制台/SSH 场景粘贴完整回调 URL）。token 请求支持表单
 * 与 JSON 两种编码（{@link OAuthConfig.TokenRequestStyle}）。</p>
 */
public final class OAuthFlow {

    private static final long LOGIN_TIMEOUT_SECONDS = 5 * 60;
    private static final Duration TOKEN_EXCHANGE_TIMEOUT = Duration.ofSeconds(30);

    /**
     * B231（pi {@code auth/resolve.ts:105-106}）：刷新请求的硬超时 15s。调用方可取消
     * 等待，<b>已开始的刷新只受这个超时约束</b>；登录交换走 {@link #TOKEN_EXCHANGE_TIMEOUT}。
     */
    private static final Duration REFRESH_TIMEOUT = Duration.ofSeconds(15);

    /** 品牌页/错误文案里的 provider 显示名（注册表键 → pi 页面名）。 */
    private static final Map<String, String> DISPLAY_NAMES = Map.of("openrouter", "OpenRouter");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final OAuthConfig config;
    private final boolean autoOpenBrowser;
    private final Duration refreshTimeout;
    private final HttpClient http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL).build();

    /** @param config 目标 provider 的 OAuth 配置 */
    public OAuthFlow(OAuthConfig config) {
        this(config, true, REFRESH_TIMEOUT);
    }

    /** @param autoOpenBrowser false 时仅提示手动打开（测试/无桌面环境用） */
    OAuthFlow(OAuthConfig config, boolean autoOpenBrowser) {
        this(config, autoOpenBrowser, REFRESH_TIMEOUT);
    }

    /** 测试注入：自定义刷新超时（夹具用短值，不真等 15s）。 */
    OAuthFlow(OAuthConfig config, boolean autoOpenBrowser, Duration refreshTimeout) {
        this.config = config;
        this.autoOpenBrowser = autoOpenBrowser;
        this.refreshTimeout = refreshTimeout;
    }

    /**
     * 执行完整登录流程，返回凭证。
     *
     * @throws IOException 用户取消、超时、授权被拒或 token 换取失败
     */
    public OAuthCredential login(OAuthInteraction interaction) throws IOException {
        var pkce = Pkce.generate();
        var callbackPath = "/oauth/callback/" + UUID.randomUUID();
        // redirectUri 的实际端口要 start 后才知道；complete 在回调时才执行，用 holder 桥接。
        var redirectUriHolder = new String[1];
        var server = LoopbackCallbackServer.<OAuthCredential>start(
            new LoopbackCallbackServer.Options<>(
                displayName(), callbackHost(), 0, callbackPath, null, null,
                Duration.ofSeconds(LOGIN_TIMEOUT_SECONDS),
                (code, query) -> exchangeToken(code, pkce.verifier(), redirectUriHolder[0])));
        redirectUriHolder[0] = server.redirectUri();
        try {
            var authorizeUrl = buildAuthorizeUrl(redirectUriHolder[0], pkce.challenge());
            interaction.notify("Listening for " + displayName() + " OAuth callback on "
                + redirectUriHolder[0]);
            if (!autoOpenBrowser || !openBrowser(authorizeUrl)) {
                interaction.notify("Could not open a browser automatically.\nOpen this URL manually:\n  "
                    + authorizeUrl);
            }
            var outcome = LoopbackCallbackServer.awaitCallbackOrManual(interaction, server,
                "Complete sign-in in your browser, or paste the authorization code / redirect URL here:");
            if (outcome instanceof LoopbackCallbackServer.Outcome.Callback<?> cb) {
                return (OAuthCredential) cb.value(); // 交换已在 complete 内完成（失败页面 502）。
            }
            var input = ((LoopbackCallbackServer.Outcome.Manual<?>) outcome).input();
            var code = parseAuthorizationInput(input);
            if (code == null) {
                throw new IOException("Missing authorization code");
            }
            interaction.notify("Exchanging authorization code for an API key...");
            return exchangeToken(code, pkce.verifier(), redirectUriHolder[0]);
        } finally {
            server.close();
        }
    }

    // ── Authorize URL / browser ──────────────────────────────

    private String buildAuthorizeUrl(String redirectUri, String challenge) {
        return config.authorizeTemplate()
            .replace("{redirect_uri}", encode(redirectUri))
            .replace("{code_challenge}", encode(challenge))
            .replace("{code_challenge_method}", "S256")
            .replace("{client_id}", encode(config.clientId()))
            .replace("{scope}", encode(config.scope()));
    }

    private static boolean openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return true;
            }
        } catch (Exception ignored) {
            // 无桌面环境时回落为手动粘贴
        }
        return false;
    }

    /**
     * pi {@code parseAuthorizationInput}：完整 URL 取 {@code code} 参 →
     * 含 {@code code=} 的 query 串 → 裸 code。
     */
    private static String parseAuthorizationInput(String input) {
        var value = input.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            var uri = URI.create(value);
            var code = param(uri.getRawQuery(), "code");
            if (code != null) {
                return code;
            }
        }
        if (value.contains("code=")) {
            var query = value.indexOf('?') >= 0 ? value.substring(value.indexOf('?') + 1) : value;
            return param(query, "code");
        }
        return value;
    }

    private String displayName() {
        return DISPLAY_NAMES.getOrDefault(config.name(), config.name());
    }

    private String callbackHost() {
        var env = System.getenv("PI_OAUTH_CALLBACK_HOST");
        return env != null && !env.isBlank() ? env : "127.0.0.1";
    }

    /** 用 refresh token 换取新凭证（凭证无 refresh token 时抛出）。 */
    public OAuthCredential refresh(OAuthCredential credential) throws IOException {
        if (credential.refreshToken().isBlank()) {
            throw new IOException("No refresh token available for " + config.name());
        }
        var params = new LinkedHashMap<String, String>();
        params.put("grant_type", "refresh_token");
        params.put("refresh_token", credential.refreshToken());
        params.put("client_id", config.clientId());
        var response = postForm(config.tokenUrl(), params);
        return parseTokenResponse(response);
    }

    // ── Token exchange ───────────────────────────────────────

    OAuthCredential exchangeToken(String code, String verifier, String redirectUri) throws IOException {
        Map<String, String> params;
        String body;
        if (config.tokenStyle() == OAuthConfig.TokenRequestStyle.JSON) {
            params = new LinkedHashMap<>();
            params.put("code", code);
            params.put("code_verifier", verifier);
            params.putAll(config.tokenExtra());
            body = JSON.writeValueAsString(params);
        } else {
            params = new LinkedHashMap<>();
            params.put("grant_type", "authorization_code");
            params.put("code", code);
            params.put("redirect_uri", redirectUri);
            params.put("code_verifier", verifier);
            if (!config.clientId().isBlank()) {
                params.put("client_id", config.clientId());
            }
            params.putAll(config.tokenExtra());
            body = formEncode(params);
        }
        var request = HttpRequest.newBuilder(URI.create(config.tokenUrl()))
            .header("accept", "application/json")
            .header("content-type", config.tokenStyle() == OAuthConfig.TokenRequestStyle.JSON
                ? "application/json" : "application/x-www-form-urlencoded")
            .timeout(TOKEN_EXCHANGE_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return parseTokenResponse(send(request));
    }

    private OAuthCredential parseTokenResponse(HttpResponse<String> response) throws IOException {
        if (response.statusCode() / 100 != 2) {
            throw new IOException("OAuth token request failed (HTTP " + response.statusCode()
                + "): " + response.body());
        }
        JsonNode node;
        try {
            node = JSON.readTree(response.body());
        } catch (IOException e) {
            throw new IOException("OAuth token response is not valid JSON", e);
        }
        var access = text(node, "access_token");
        if (access == null) {
            access = text(node, "key");
        }
        if (access == null) {
            throw new IOException("OAuth token response carries no access token");
        }
        var refresh = text(node, "refresh_token");
        var expiresIn = node.has("expires_in") ? node.get("expires_in").asLong() : -1;
        long expiresAt = expiresIn > 0 ? Instant.now().getEpochSecond() + expiresIn : Long.MAX_VALUE;
        return new OAuthCredential(access, refresh == null ? "" : refresh, expiresAt,
            text(node, "base_url"));
    }

    private HttpResponse<String> postForm(String url, Map<String, String> params) throws IOException {
        var request = HttpRequest.newBuilder(URI.create(url))
            .header("accept", "application/json")
            .header("content-type", "application/x-www-form-urlencoded")
            .timeout(refreshTimeout) // B231：刷新硬超时 15s（pi AbortSignal.timeout(15_000)）
            .POST(HttpRequest.BodyPublishers.ofString(formEncode(params)))
            .build();
        return send(request);
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new IOException("OpenRouter OAuth token exchange timed out", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Token request interrupted", e);
        }
    }

    private static String param(String query, String key) {
        if (query == null) {
            return null;
        }
        for (var pair : query.split("&")) {
            var parts = pair.split("=", 2);
            if (parts.length == 2 && parts[0].equals(key)) {
                return java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    static String formEncode(Map<String, String> params) {
        var sb = new StringBuilder();
        for (var entry : params.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('&');
            }
            sb.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
        }
        return sb.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }
}
