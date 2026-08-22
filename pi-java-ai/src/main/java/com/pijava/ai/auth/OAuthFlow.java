package com.pijava.ai.auth;

import java.awt.Desktop;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * OAuth2 授权码 + PKCE 流程（P6-17）。
 *
 * <p>启动 loopback 回调服务器（随机端口 + 随机路径），生成 PKCE verifier/challenge，
 * 打开浏览器跳转授权页，收到 {@code code} 后换取 token。无控制台/远程场景下
 * {@link Interaction#prompt} 提供手动粘贴授权码或回调 URL 的兜底。token 请求
 * 支持表单与 JSON 两种编码（对齐 provider 差异）。</p>
 */
public final class OAuthFlow {

    private static final long LOGIN_TIMEOUT_SECONDS = 5 * 60;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OAuthConfig config;
    private final boolean autoOpenBrowser;
    private final HttpClient http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL).build();

    /** @param config 目标 provider 的 OAuth 配置 */
    public OAuthFlow(OAuthConfig config) {
        this(config, true);
    }

    /** @param autoOpenBrowser false 时仅提示手动打开（测试/无桌面环境用） */
    OAuthFlow(OAuthConfig config, boolean autoOpenBrowser) {
        this.config = config;
        this.autoOpenBrowser = autoOpenBrowser;
    }

    /**
     * 执行完整登录流程，返回凭证。
     *
     * @throws IOException 用户取消、超时、授权被拒或 token 换取失败
     */
    public OAuthCredential login(OAuthInteraction interaction) throws IOException {
        var verifier = generateVerifier();
        var challenge = generateChallenge(verifier);
        var callbackPath = "/oauth/callback/" + Long.toHexString(RANDOM.nextLong());
        var server = HttpServer.create(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        var codeFuture = new CompletableFuture<CodeResult>();
        server.createContext(callbackPath, exchange -> handleCallback(exchange, codeFuture));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        try {
            var port = server.getAddress().getPort();
            var redirectUri = "http://127.0.0.1:" + port + callbackPath;
            var authorizeUrl = buildAuthorizeUrl(redirectUri, challenge);
            interaction.notify("Listening for OAuth callback on " + redirectUri);
            if (!autoOpenBrowser || !openBrowser(authorizeUrl)) {
                interaction.notify("Could not open a browser automatically.\nOpen this URL manually:\n  " + authorizeUrl);
            }
            var manual = interaction.prompt(
                "Complete sign-in in your browser, or paste the authorization code / redirect URL here:");
            var result = (manual != null && !manual.isBlank())
                ? new CodeResult(parseCode(manual), null)
                : awaitCode(codeFuture);
            if (result.error() != null) {
                throw new IOException(result.error());
            }
            if (result.code() == null) {
                throw new IOException("Missing authorization code");
            }
            interaction.notify("Exchanging authorization code for a token...");
            return exchangeToken(result.code(), verifier, redirectUri);
        } finally {
            server.stop(0);
        }
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

    // ── PKCE ─────────────────────────────────────────────────

    private static String generateVerifier() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return base64Url(bytes);
    }

    private static String generateChallenge(String verifier) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return base64Url(digest.digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // ── Callback server ──────────────────────────────────────

    private record CodeResult(String code, String error) {}

    private static void handleCallback(HttpExchange exchange, CompletableFuture<CodeResult> future) {
        try {
            var query = exchange.getRequestURI().getQuery();
            var code = param(query, "code");
            var error = param(query, "error");
            var description = param(query, "error_description");
            if (error != null) {
                sendHtml(exchange, 400, "<h1>Authorization failed</h1><p>"
                    + escapeHtml(description == null ? error : description) + "</p>");
                future.complete(new CodeResult(null, error + (description == null ? "" : ": " + description)));
            } else if (code != null) {
                sendHtml(exchange, 200, "<h1>Signed in</h1><p>You may now close this page.</p>");
                future.complete(new CodeResult(code, null));
            } else {
                sendHtml(exchange, 400, "<h1>No authorization code</h1>");
                future.complete(new CodeResult(null, "No authorization code in callback"));
            }
        } catch (IOException e) {
            future.complete(new CodeResult(null, e.getMessage()));
        } finally {
            exchange.close();
        }
    }

    private static void sendHtml(HttpExchange exchange, int status, String html) throws IOException {
        var body = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
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

    private static CodeResult awaitCode(CompletableFuture<CodeResult> future) throws IOException {
        try {
            return future.get(LOGIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IOException("OAuth login timed out after " + LOGIN_TIMEOUT_SECONDS + "s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("OAuth login interrupted", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IOException("OAuth callback failed", e.getCause());
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

    private static String parseCode(String input) {
        var value = input.trim();
        if (value.contains("code=")) {
            var query = value.substring(value.indexOf("code=") + 5);
            int amp = query.indexOf('&');
            return amp >= 0 ? query.substring(0, amp) : query;
        }
        try {
            var uri = URI.create(value);
            var code = param(uri.getQuery(), "code");
            if (code != null) {
                return code;
            }
        } catch (IllegalArgumentException ignored) {
            // 非 URL
        }
        return value;
    }

    // ── Token exchange ───────────────────────────────────────

    private OAuthCredential exchangeToken(String code, String verifier, String redirectUri)
            throws IOException {
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
            .POST(HttpRequest.BodyPublishers.ofString(formEncode(params)))
            .build();
        return send(request);
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Token request interrupted", e);
        }
    }

    private static String formEncode(Map<String, String> params) {
        var sb = new StringBuilder();
        for (var entry : params.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
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

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;");
    }
}
