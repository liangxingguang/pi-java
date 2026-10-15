package com.pijava.ai.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic（Claude Pro/Max）订阅 OAuth（pi {@code auth/oauth/anthropic.ts}）。
 *
 * <p>登录先 {@code select} 二选一：</p>
 * <ul>
 *   <li><b>browser（默认）</b>：loopback 回调，优先固定端口 53692（容器/SSH 可转发），
 *       占用则回落空闲端口、再失败不起服务器只走手工粘贴；redirectUri 主机名恒为
 *       {@code localhost}，state＝PKCE verifier；</li>
 *   <li><b>copy_code（headless）</b>：不起服务器，redirect_uri 固定为 Anthropic 平台的
 *       code-callback 页，用户把 {@code code#state} 贴回来。</li>
 * </ul>
 *
 * <p>token 端点（platform.claude.com）恒用 <b>JSON</b> 请求体（授权交换与刷新皆然），
 * 凭证到期时间提前 5 分钟；刷新 POST 带 15s 硬超时（B231）。</p>
 */
public final class AnthropicOAuthFlow {

    private static final String CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e";
    private static final String AUTHORIZE_URL = "https://claude.ai/oauth/authorize";
    private static final String TOKEN_URL = "https://platform.claude.com/v1/oauth/token";
    private static final int CALLBACK_PORT = 53692;
    private static final String CALLBACK_PATH = "/callback";
    private static final String STATIC_REDIRECT_URI = "http://localhost:" + CALLBACK_PORT + CALLBACK_PATH;
    private static final String COPY_CODE_REDIRECT_URI = "https://platform.claude.com/oauth/code/callback";
    private static final String BROWSER_METHOD = "browser";
    private static final String COPY_CODE_METHOD = "copy_code";
    private static final String SCOPES =
        "org:create_api_key user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload";

    /** pi：expires = now + expires_in − 5min。 */
    private static final long EXPIRY_MARGIN_SECONDS = 5 * 60;
    /** pi postJson：AbortSignal.timeout(30_000)。 */
    private static final Duration TOKEN_EXCHANGE_TIMEOUT = Duration.ofSeconds(30);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final boolean autoOpenBrowser;
    private final Duration refreshTimeout;
    private final String callbackHost;
    private final String tokenUrl;
    private final HttpClient http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL).build();

    public AnthropicOAuthFlow() {
        this(true, Duration.ofSeconds(15), defaultCallbackHost(), TOKEN_URL);
    }

    /** 测试注入：不自动开浏览器／自定义刷新超时／回调主机／token 端点。 */
    AnthropicOAuthFlow(boolean autoOpenBrowser, Duration refreshTimeout, String callbackHost,
                       String tokenUrl) {
        this.autoOpenBrowser = autoOpenBrowser;
        this.refreshTimeout = refreshTimeout;
        this.callbackHost = callbackHost;
        this.tokenUrl = tokenUrl;
    }

    private static String defaultCallbackHost() {
        var env = System.getenv("PI_OAUTH_CALLBACK_HOST");
        return env != null && !env.isBlank() ? env : "127.0.0.1";
    }

    // ── 登录 ──────────────────────────────────────────────────

    public OAuthCredential login(OAuthInteraction interaction) throws IOException {
        var method = interaction.select("Select Anthropic login method:", List.of(
            new OAuthInteraction.LoginOption(BROWSER_METHOD, "Browser login (default)"),
            new OAuthInteraction.LoginOption(COPY_CODE_METHOD, "Copy code login (headless)")));
        if (COPY_CODE_METHOD.equals(method)) {
            return loginCopyCode(interaction);
        }
        if (!BROWSER_METHOD.equals(method)) {
            throw new IOException("Unknown Anthropic login method: " + method);
        }
        return loginBrowser(interaction);
    }

    private OAuthCredential loginBrowser(OAuthInteraction interaction) throws IOException {
        var pkce = Pkce.generate();
        // pi：53692 占用 → 空闲端口 → 无服务器（手工粘贴兜底）。state＝PKCE verifier。
        LoopbackCallbackServer<String> server = startQuietly(CALLBACK_PORT, pkce.verifier());
        if (server == null) {
            server = startQuietly(0, pkce.verifier());
        }
        var redirectUri = server != null ? server.redirectUri() : STATIC_REDIRECT_URI;
        try {
            var authorizeUrl = buildAuthorizeUrl(redirectUri, pkce);
            presentAuthorizeUrl(interaction, authorizeUrl,
                "Complete login in your browser. If the browser is on another machine, "
                    + "paste the final redirect URL here.");

            var outcome = LoopbackCallbackServer.awaitCallbackOrManual(interaction, server,
                "Complete login in your browser, or paste the authorization code / redirect URL here:");
            String code;
            String state = pkce.verifier();
            if (outcome instanceof LoopbackCallbackServer.Outcome.Callback<?> cb) {
                code = (String) cb.value(); // 服务器已按 verifier 校验 state
            } else {
                var parsed = parseAuthorizationInput(
                    ((LoopbackCallbackServer.Outcome.Manual<?>) outcome).input());
                if (parsed.state() != null && !parsed.state().equals(pkce.verifier())) {
                    throw new IOException("OAuth state mismatch");
                }
                code = parsed.code();
                if (parsed.state() != null) {
                    state = parsed.state();
                }
            }
            if (code == null) {
                throw new IOException("Missing authorization code");
            }
            interaction.notify("Exchanging authorization code for tokens...");
            return exchangeAuthorizationCode(code, state, pkce.verifier(), redirectUri);
        } finally {
            if (server != null) {
                server.close();
            }
        }
    }

    private OAuthCredential loginCopyCode(OAuthInteraction interaction) throws IOException {
        var pkce = Pkce.generate();
        var authorizeUrl = buildAuthorizeUrl(COPY_CODE_REDIRECT_URI, pkce);
        presentAuthorizeUrl(interaction, authorizeUrl,
            "Complete login in your browser, then copy the code Anthropic shows and paste it here.");
        var input = interaction.prompt(
            "Paste the code Anthropic shows after you sign in:");
        var parsed = parseAuthorizationInput(input);
        if (parsed.state() != null && !parsed.state().equals(pkce.verifier())) {
            throw new IOException("OAuth state mismatch");
        }
        if (parsed.code() == null) {
            throw new IOException("Missing authorization code");
        }
        interaction.notify("Exchanging authorization code for tokens...");
        return exchangeAuthorizationCode(parsed.code(),
            parsed.state() != null ? parsed.state() : pkce.verifier(),
            pkce.verifier(), COPY_CODE_REDIRECT_URI);
    }

    private LoopbackCallbackServer<String> startQuietly(int port, String state) {
        try {
            return LoopbackCallbackServer.start(new LoopbackCallbackServer.Options<>(
                "Anthropic", callbackHost, port, CALLBACK_PATH, "localhost", state, null,
                (code, query) -> code));
        } catch (IOException e) {
            // pi：.catch 回落（BindException 是其子类）；两次都失败则无服务器，继续走手工粘贴。
            return null;
        }
    }

    // ── 交换 / 刷新 ───────────────────────────────────────────

    private OAuthCredential exchangeAuthorizationCode(String code, String state, String verifier,
                                                       String redirectUri) throws IOException {
        var body = new LinkedHashMap<String, String>();
        body.put("grant_type", "authorization_code");
        body.put("client_id", CLIENT_ID);
        body.put("code", code);
        body.put("state", state);
        body.put("redirect_uri", redirectUri);
        body.put("code_verifier", verifier);
        return parseTokenResponse(postJson(body, TOKEN_EXCHANGE_TIMEOUT),
            "Token exchange request failed. url=" + tokenUrl
                + "; redirect_uri=" + redirectUri + "; response_type=authorization_code");
    }

    public OAuthCredential refresh(OAuthCredential credential) throws IOException {
        if (credential.refreshToken().isBlank()) {
            throw new IOException("No refresh token available for Anthropic");
        }
        var body = new LinkedHashMap<String, String>();
        body.put("grant_type", "refresh_token");
        body.put("client_id", CLIENT_ID);
        body.put("refresh_token", credential.refreshToken());
        return parseTokenResponse(postJson(body, refreshTimeout),
            "Anthropic token refresh request failed. url=" + tokenUrl);
    }

    private HttpResponse<String> postJson(Map<String, String> fields, Duration timeout)
            throws IOException {
        String body;
        try {
            body = JSON.writeValueAsString(fields);
        } catch (IOException e) {
            throw new IOException("Failed to encode OAuth token request", e);
        }
        var request = HttpRequest.newBuilder(URI.create(tokenUrl))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .timeout(timeout)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new IOException("Anthropic OAuth token request timed out", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Token request interrupted", e);
        }
    }

    private OAuthCredential parseTokenResponse(HttpResponse<String> response, String errorPrefix)
            throws IOException {
        if (response.statusCode() / 100 != 2) {
            throw new IOException(errorPrefix + "; HTTP " + response.statusCode()
                + "; body=" + response.body());
        }
        JsonNode node;
        try {
            node = JSON.readTree(response.body());
        } catch (IOException e) {
            throw new IOException(errorPrefix + "; invalid JSON: " + response.body(), e);
        }
        var access = text(node, "access_token");
        var refresh = text(node, "refresh_token");
        var expiresIn = node.has("expires_in") ? node.get("expires_in").asLong() : -1;
        if (access == null || access.isBlank() || refresh == null || refresh.isBlank()
            || expiresIn <= 0) {
            throw new IOException(errorPrefix + "; malformed token response: " + response.body());
        }
        var expiresAt = Instant.now().getEpochSecond() + expiresIn - EXPIRY_MARGIN_SECONDS;
        return new OAuthCredential(access, refresh, expiresAt, null);
    }

    // ── authorize URL / 手工解析 ──────────────────────────────

    private String buildAuthorizeUrl(String redirectUri, Pkce.PkcePair pkce) {
        var params = new LinkedHashMap<String, String>();
        params.put("code", "true");
        params.put("client_id", CLIENT_ID);
        params.put("response_type", "code");
        params.put("redirect_uri", redirectUri);
        params.put("scope", SCOPES);
        params.put("code_challenge", pkce.challenge());
        params.put("code_challenge_method", "S256");
        params.put("state", pkce.verifier());
        return AUTHORIZE_URL + "?" + OAuthFlow.formEncode(params);
    }

    private void presentAuthorizeUrl(OAuthInteraction interaction, String url, String instructions) {
        interaction.notify("Open this URL in your browser:\n  " + url);
        if (!autoOpenBrowser || !OAuthFlow.openBrowser(url)) {
            interaction.notify("Could not open a browser automatically. " + instructions);
        } else {
            interaction.notify(instructions);
        }
    }

    /**
     * pi {@code parseAuthorizationInput}：完整 URL 取 code/state 参 →
     * {@code code#state} → 裸 query（code=）→ 裸 code。
     */
    static ParsedInput parseAuthorizationInput(String input) {
        var value = input == null ? "" : input.trim();
        if (value.isEmpty()) {
            return new ParsedInput(null, null);
        }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            var uri = URI.create(value);
            return new ParsedInput(param(uri.getRawQuery(), "code"),
                param(uri.getRawQuery(), "state"));
        }
        if (value.contains("#")) {
            var parts = value.split("#", 2);
            return new ParsedInput(emptyToNull(parts[0]),
                parts.length > 1 ? emptyToNull(parts[1]) : null);
        }
        if (value.contains("code=")) {
            var query = value.indexOf('?') >= 0 ? value.substring(value.indexOf('?') + 1) : value;
            return new ParsedInput(param(query, "code"), param(query, "state"));
        }
        return new ParsedInput(value, null);
    }

    private static String param(String query, String key) {
        if (query == null) {
            return null;
        }
        for (var pair : query.split("&")) {
            var parts = pair.split("=", 2);
            if (parts.length == 2 && parts[0].equals(key)) {
                return emptyToNull(java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
        }
        return null;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    /** 手工输入解析结果（code/state 均可空）。 */
    record ParsedInput(String code, String state) {}
}
