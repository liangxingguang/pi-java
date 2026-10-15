package com.pijava.ai.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.BindException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * OpenAI「Sign in with ChatGPT」订阅 OAuth（pi {@code auth/oauth/openai-chatgpt.ts}）。
 *
 * <p>公开客户端、无 client secret：每次登录以固定 {@code dynamic_agent_client} 发起，
 * 提供方把<b>签发的 client_id</b> 放在回调 query 里，后续交换/刷新都用它（动态客户端注册）。
 * 回调固定 {@code http://127.0.0.1:1455/auth/callback}，端口被占时<b>硬失败</b>（不回落：
 * 否则浏览器回调会落进别的挂起登录或 Codex CLI 手里）。agent host 是
 * {@code urn:uuid:<安装 UUID>}（CLI 每次登录随机，对齐 pi-ai CLI）。</p>
 *
 * <p>token 响应强校验：access/refresh/scope 非空、{@code expires_in} 为正、
 * scope 必须含 {@code chatgpt.tokens.use.direct}（直连 token 授权），交换时还要求
 * {@code id_token} 在场（不读内容）；到期提前 3 分钟。刷新表单带签发 clientId 与 resource。</p>
 */
public final class ChatGptOAuthFlow {

    private static final String DYNAMIC_CLIENT_ID = "dynamic_agent_client";
    private static final String DEFAULT_AGENT_NAME = "Pi";
    private static final Pattern UUID_PATTERN = Pattern.compile(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        Pattern.CASE_INSENSITIVE);
    private static final String AUTHORIZE_URL = "https://auth.openai.com/api/accounts/authorize";
    private static final String TOKEN_URL = "https://auth.openai.com/api/accounts/oauth/token";
    private static final String RESOURCE = "https://api.openai.com/v1";
    private static final int CALLBACK_PORT = 1455;
    private static final String CALLBACK_PATH = "/auth/callback";
    private static final String DIRECT_TOKEN_SCOPE = "chatgpt.tokens.use.direct";
    private static final String SCOPE =
        "openid profile email offline_access resource.invoke " + DIRECT_TOKEN_SCOPE;
    private static final long EXPIRY_MARGIN_SECONDS = 3 * 60;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Duration refreshTimeout;
    private final String callbackHost;
    private final String tokenUrl;
    /** 固定回调端口（生产 1455；测试注入空闲端口——JDK HttpServer.stop 在 Windows 上异步解绑）。 */
    private final int callbackPort;
    private final String redirectUri;
    private final HttpClient http = HttpClient.newHttpClient();

    public ChatGptOAuthFlow() {
        this(Duration.ofSeconds(15), defaultCallbackHost(), TOKEN_URL, CALLBACK_PORT);
    }

    /** 测试注入：刷新超时／回调主机／token 端点／回调端口。 */
    ChatGptOAuthFlow(Duration refreshTimeout, String callbackHost, String tokenUrl,
                     int callbackPort) {
        this.refreshTimeout = refreshTimeout;
        this.callbackHost = callbackHost;
        this.tokenUrl = tokenUrl;
        this.callbackPort = callbackPort;
        this.redirectUri = "http://127.0.0.1:" + callbackPort + CALLBACK_PATH;
    }

    /** 固定 redirectUri（测试按注入端口取期望值）。 */
    String redirectUri() {
        return redirectUri;
    }

    /** 固定回调端口（生产 1455）。 */
    int callbackPort() {
        return callbackPort;
    }

    private static String defaultCallbackHost() {
        var env = System.getenv("PI_OAUTH_CALLBACK_HOST");
        return env != null && !env.isBlank() ? env : "127.0.0.1";
    }

    /** 登录环境提示：设备（安装）ID 来源与 agent 名。 */
    public record LoginHints(Supplier<String> deviceId, String agentName) {

        /** pi-ai CLI 形态：不持久化安装 ID，每次登录随机 UUID，agent 名 Pi。 */
        public static LoginHints cli() {
            return new LoginHints(() -> UUID.randomUUID().toString(), DEFAULT_AGENT_NAME);
        }
    }

    // ── 登录 ──────────────────────────────────────────────────

    public OAuthCredential login(OAuthInteraction interaction, LoginHints hints) throws IOException {
        var hostId = agentHostId(hints.deviceId().get()); // 必须在绑定端口/授权开始之前
        var pkce = Pkce.generate();
        var state = Pkce.randomValue();
        var nonce = Pkce.randomValue();

        LoopbackCallbackServer<AuthorizationResult> callback;
        try {
            callback = LoopbackCallbackServer.start(new LoopbackCallbackServer.Options<>(
                "ChatGPT", callbackHost, callbackPort, CALLBACK_PATH, null, state, null,
                (code, query) -> authorizationResultFromCallback(code, state, query.get("client_id"))));
        } catch (BindException e) {
            // pi：固定端口被别的挂起登录/Codex CLI 占着时，宁可明确报错也不挑端口。
            throw new IOException("Port " + callbackPort + " is in use, probably by an unfinished login "
                + "in another pi session or by the Codex CLI. Cancel that login and try again.", e);
        }

        var authorizeUrl = buildAuthorizeUrl(hints, hostId, pkce.challenge(), state, nonce);
        try {
            interaction.notify("Open this URL in your browser:\n  " + authorizeUrl);
            interaction.notify("Complete sign-in in your browser. If the callback does not complete, "
                + "paste the final redirect URL here.");
            var outcome = LoopbackCallbackServer.<AuthorizationResult>awaitCallbackOrManual(
                interaction, callback,
                "Complete login in your browser, or paste the final redirect URL here:");
            AuthorizationResult result;
            if (outcome instanceof LoopbackCallbackServer.Outcome.Callback<?> cb) {
                result = (AuthorizationResult) cb.value();
            } else {
                var input = ((LoopbackCallbackServer.Outcome.Manual<?>) outcome).input();
                result = authorizationResultFromManualInput(input, state, redirectUri);
            }
            interaction.notify("Exchanging authorization code for tokens...");
            return exchangeAuthorizationCode(result, pkce.verifier());
        } finally {
            callback.close();
        }
    }

    private static String agentHostId(String deviceId) throws IOException {
        if (deviceId == null || !UUID_PATTERN.matcher(deviceId).matches()) {
            throw new IOException(
                "Sign in with ChatGPT requires a device ID (UUID) for this installation");
        }
        return "urn:uuid:" + deviceId.toLowerCase();
    }

    private String buildAuthorizeUrl(LoginHints hints, String hostId, String challenge,
                                     String state, String nonce) {
        var params = new LinkedHashMap<String, String>();
        params.put("client_id", DYNAMIC_CLIENT_ID);
        params.put("agent_name_hint", hints.agentName() == null || hints.agentName().isBlank()
            ? DEFAULT_AGENT_NAME : hints.agentName());
        params.put("ext_agent_host_id", hostId);
        params.put("response_type", "code");
        params.put("redirect_uri", redirectUri);
        params.put("resource", RESOURCE);
        params.put("scope", SCOPE);
        params.put("state", state);
        params.put("code_challenge", challenge);
        params.put("code_challenge_method", "S256");
        params.put("nonce", nonce);
        return AUTHORIZE_URL + "?" + formEncode(params);
    }

    private record AuthorizationResult(String code, String clientId) {}

    private static AuthorizationResult authorizationResultFromCallback(
            String code, String expectedState, String rawClientId) throws IOException {
        if (code == null || code.isBlank()) {
            throw new IOException("Missing authorization code");
        }
        var clientId = rawClientId == null ? null : rawClientId.trim();
        if (clientId == null || clientId.isEmpty()) {
            throw new IOException(
                "OpenAI OAuth registration callback did not contain an issued client ID");
        }
        return new AuthorizationResult(code, clientId);
    }

    /** 手工粘贴必须是完整回调 URL（origin+path 一致），error 参即失败。 */
    private static AuthorizationResult authorizationResultFromManualInput(
            String input, String expectedState, String expectedRedirectUri) throws IOException {
        URI uri;
        try {
            uri = URI.create(input.trim());
            if (uri.getScheme() == null) {
                throw new IllegalArgumentException("not a URL");
            }
        } catch (IllegalArgumentException e) {
            throw new IOException("Paste the full callback URL from the browser", e);
        }
        var expected = URI.create(expectedRedirectUri);
        var sameOrigin = uri.getPort() == expected.getPort()
            && expected.getHost().equalsIgnoreCase(uri.getHost())
            && ("http".equalsIgnoreCase(uri.getScheme()));
        if (!sameOrigin || !expected.getPath().equals(uri.getPath())) {
            throw new IOException("The pasted callback URL must start with " + expectedRedirectUri);
        }
        var query = parseQuery(uri.getRawQuery());
        var error = query.get("error");
        if (error != null) {
            throw new IOException("ChatGPT authorization failed: " + error);
        }
        var state = query.get("state");
        if (state == null || !state.equals(expectedState)) {
            throw new IOException("OAuth state mismatch");
        }
        return authorizationResultFromCallback(query.get("code"), expectedState,
            query.get("client_id"));
    }

    // ── 交换 / 刷新 ───────────────────────────────────────────

    private OAuthCredential exchangeAuthorizationCode(AuthorizationResult result, String verifier)
            throws IOException {
        var params = new LinkedHashMap<String, String>();
        params.put("grant_type", "authorization_code");
        params.put("client_id", result.clientId());
        params.put("code", result.code());
        params.put("code_verifier", verifier);
        params.put("redirect_uri", redirectUri);
        params.put("resource", RESOURCE);
        var token = requestToken(params, null); // pi：交换只受 signal 约束，不挂超时
        var idToken = text(token, "id_token");
        if (idToken == null || idToken.isBlank()) {
            throw new IOException("OpenAI OAuth token response did not contain an ID token");
        }
        return credentialFromTokenResponse(token, result.clientId());
    }

    public OAuthCredential refresh(OAuthCredential credential) throws IOException {
        var clientId = credential.clientId();
        if (clientId == null || clientId.isBlank()) {
            throw new IOException(
                "Stored OpenAI OAuth credential does not contain an issued client ID; reconnect ChatGPT");
        }
        var params = new LinkedHashMap<String, String>();
        params.put("grant_type", "refresh_token");
        params.put("client_id", clientId);
        params.put("refresh_token", credential.refreshToken());
        params.put("resource", RESOURCE);
        var token = requestToken(params, refreshTimeout);
        return credentialFromTokenResponse(token, clientId);
    }

    private JsonNode requestToken(Map<String, String> body, Duration timeout) throws IOException {
        var request = HttpRequest.newBuilder(URI.create(tokenUrl))
            .header("accept", "application/json")
            .header("content-type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(formEncode(body)));
        if (timeout != null) {
            request.timeout(timeout);
        }
        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Token request interrupted", e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException("OpenAI OAuth token request failed (" + response.statusCode()
                + "): " + response.body());
        }
        JsonNode node;
        try {
            node = JSON.readTree(response.body());
        } catch (IOException e) {
            throw new IOException("OpenAI OAuth token response must be an object", e);
        }
        if (!node.isObject()) {
            throw new IOException("OpenAI OAuth token response must be an object");
        }
        return node;
    }

    private static OAuthCredential credentialFromTokenResponse(JsonNode token, String clientId)
            throws IOException {
        var access = requireTokenString(token, "access_token");
        var refresh = requireTokenString(token, "refresh_token");
        var scope = requireTokenString(token, "scope");
        var expiresInNode = token.get("expires_in");
        if (expiresInNode == null || !expiresInNode.canConvertToLong()
            || expiresInNode.asLong() <= 0) {
            throw new IOException("OpenAI OAuth token response has invalid expires_in");
        }
        var scopes = splitScopes(scope);
        if (!scopes.contains(DIRECT_TOKEN_SCOPE)) {
            throw new IOException(
                "OpenAI OAuth grant did not include " + DIRECT_TOKEN_SCOPE);
        }
        var expiresAt = Instant.now().getEpochSecond()
            + expiresInNode.asLong() - EXPIRY_MARGIN_SECONDS;
        return new OAuthCredential(access, refresh, expiresAt, null, clientId, scopes);
    }

    private static String requireTokenString(JsonNode token, String field) throws IOException {
        var node = token.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new IOException("OpenAI OAuth token response has invalid " + field);
        }
        return node.asText();
    }

    private static List<String> splitScopes(String scope) {
        var scopes = new ArrayList<String>();
        for (var part : scope.trim().split("\\s+")) {
            if (!part.isEmpty()) {
                scopes.add(part);
            }
        }
        return scopes;
    }

    // ── 小工具 ────────────────────────────────────────────────

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        var params = new LinkedHashMap<String, String>();
        if (rawQuery == null) {
            return params;
        }
        for (var pair : rawQuery.split("&")) {
            var idx = pair.indexOf('=');
            var key = idx < 0 ? pair : pair.substring(0, idx);
            var value = idx < 0 ? "" : pair.substring(idx + 1);
            if (!key.isEmpty()) {
                params.put(java.net.URLDecoder.decode(key, StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(value, StandardCharsets.UTF_8));
            }
        }
        return params;
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
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
