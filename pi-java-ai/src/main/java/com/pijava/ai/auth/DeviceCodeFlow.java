package com.pijava.ai.auth;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * OAuth2 RFC 8628 device-code 流程（pi {@code auth/oauth/device-code.ts}）。
 *
 * <p>请求设备授权 → 向用户展示 verification URI + user code → 轮询 token 端点
 * （pending/slow_down/expired/denied 处理）→ 返回凭证。支持标准 RFC 8628 与
 * OpenAI Codex 两段式（device 轮询返回 {@code authorization_code} 后再走 PKCE 交换）。</p>
 */
public final class DeviceCodeFlow {

    private static final long LOGIN_TIMEOUT_SECONDS = 15 * 60;
    private static final long MIN_INTERVAL_MS = 1_000;
    private static final long DEFAULT_POLL_INTERVAL_SECONDS = 5;
    private static final long SLOW_DOWN_INCREMENT_MS = 5_000;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DeviceCodeConfig config;
    private final boolean autoOpenBrowser;
    private final HttpClient http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL).build();

    /** @param config 目标 provider 的 device-code 配置 */
    public DeviceCodeFlow(DeviceCodeConfig config) {
        this(config, true);
    }

    /** @param autoOpenBrowser false 时仅提示手动打开（测试/无桌面环境用） */
    DeviceCodeFlow(DeviceCodeConfig config, boolean autoOpenBrowser) {
        this.config = config;
        this.autoOpenBrowser = autoOpenBrowser;
    }

    /**
     * 执行完整 device-code 登录流程，返回凭证。
     *
     * @throws IOException 用户取消、超时、授权被拒或 token 换取失败
     */
    public OAuthCredential login(OAuthInteraction interaction) throws IOException {
        var device = requestDeviceAuthorization(interaction);
        interaction.notify("Open this URL in your browser and enter the code:\n  "
            + device.verificationUri() + "\nUser code: " + device.userCode());
        if (autoOpenBrowser) {
            openBrowser(device.verificationUri());
        }
        return pollForTokens(device);
    }

    /** 用 refresh token 换取新凭证（无 refresh token 时抛出）。 */
    public OAuthCredential refresh(OAuthCredential credential) throws IOException {
        if (credential.refreshToken().isBlank()) {
            throw new IOException("No refresh token available for " + config.name());
        }
        String refreshUrl = config.exchangeUrl().isBlank() ? config.tokenUrl() : config.exchangeUrl();
        var params = new LinkedHashMap<String, String>();
        params.put("grant_type", "refresh_token");
        params.put("refresh_token", credential.refreshToken());
        params.put("client_id", config.clientId());
        var request = HttpRequest.newBuilder(URI.create(refreshUrl))
            .header("accept", "application/json")
            .header("content-type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(formEncode(params)))
            .build();
        return parseCredential(parseJson(send(request)));
    }

    // ── Device authorization ────────────────────────────────────────────

    private record Device(String code, String userCode, String verificationUri,
                          long intervalSeconds, long expiresInSeconds) {
    }

    private Device requestDeviceAuthorization(OAuthInteraction interaction) throws IOException {
        if (config.deviceStyle() == DeviceCodeConfig.DeviceAuthStyle.OPENAI_CODEX) {
            var body = "{\"client_id\":\"" + config.clientId() + "\"}";
            var request = HttpRequest.newBuilder(URI.create(config.deviceCodeUrl()))
                .header("accept", "application/json")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            var response = send(request);
            if (response.statusCode() / 100 != 2) {
                throw new IOException("Device authorization failed (HTTP " + response.statusCode()
                    + "): " + response.body());
            }
            var node = parseJson(response);
            var deviceAuthId = text(node, "device_auth_id");
            var userCode = text(node, "user_code");
            if (deviceAuthId == null || userCode == null) {
                throw new IOException("Invalid device authorization response: " + response.body());
            }
            long interval = node.has("interval") ? node.get("interval").asLong() : DEFAULT_POLL_INTERVAL_SECONDS;
            return new Device(deviceAuthId, userCode, config.verificationUri(),
                interval, LOGIN_TIMEOUT_SECONDS);
        }

        var params = new LinkedHashMap<String, String>();
        params.put("client_id", config.clientId());
        if (!config.scope().isBlank()) {
            params.put("scope", config.scope());
        }
        var request = HttpRequest.newBuilder(URI.create(config.deviceCodeUrl()))
            .header("accept", "application/json")
            .header("content-type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(formEncode(params)))
            .build();
        var response = send(request);
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Device authorization failed (HTTP " + response.statusCode()
                + "): " + response.body());
        }
        var node = parseJson(response);
        var code = text(node, "device_code");
        var userCode = text(node, "user_code");
        var verification = config.verificationUri().isBlank()
            ? text(node, "verification_uri") : config.verificationUri();
        if (code == null || userCode == null || verification == null) {
            throw new IOException("Device authorization response is missing required fields: "
                + response.body());
        }
        long interval = node.has("interval")
            ? Math.max(1, node.get("interval").asLong()) : DEFAULT_POLL_INTERVAL_SECONDS;
        long expiresIn = node.has("expires_in") ? node.get("expires_in").asLong() : LOGIN_TIMEOUT_SECONDS;
        return new Device(code, userCode, verification, interval, expiresIn);
    }

    // ── Token polling ───────────────────────────────────────────────────

    private OAuthCredential pollForTokens(Device device) throws IOException {
        long deadline = Instant.now().getEpochSecond() + device.expiresInSeconds();
        long intervalMs = Math.max(MIN_INTERVAL_MS, device.intervalSeconds() * 1_000);
        long slowDowns = 0;

        while (Instant.now().getEpochSecond() < deadline) {
            var response = pollToken(device);
            if (response.statusCode() / 100 == 2) {
                var node = parseJson(response);
                // OpenAI Codex: device poll returns an intermediate authorization_code.
                var authCode = text(node, "authorization_code");
                if (authCode != null) {
                    return exchangeAuthorizationCode(authCode, text(node, "code_verifier"));
                }
                return parseCredential(node);
            }

            var node = tryParseJson(response);
            String error = text(node, "error");
            if (error != null && error.indexOf(':') > 0) {
                // OpenAI Codex errors are objects like { error: { code } }.
                var codeNode = node.get("error");
                if (codeNode != null && codeNode.isObject()) {
                    error = text(codeNode, "code");
                }
            }
            if ("authorization_pending".equals(error)
                    || "deviceauth_authorization_pending".equals(error)
                    || (config.deviceStyle() == DeviceCodeConfig.DeviceAuthStyle.OPENAI_CODEX
                        && (response.statusCode() == 403 || response.statusCode() == 404))) {
                // still waiting for the user
            } else if ("slow_down".equals(error)) {
                slowDowns++;
                long serverInterval = node.has("interval") ? node.get("interval").asLong() : 0;
                intervalMs = serverInterval > 0
                    ? Math.max(MIN_INTERVAL_MS, serverInterval * 1_000)
                    : Math.max(MIN_INTERVAL_MS, intervalMs + SLOW_DOWN_INCREMENT_MS);
            } else if ("expired_token".equals(error)) {
                throw new IOException("Device code expired. Please restart login.");
            } else if ("access_denied".equals(error) || "denied".equals(error)) {
                throw new IOException("Device authorization was denied.");
            } else if (error != null) {
                throw new IOException("Device token request failed: " + error);
            } else {
                throw new IOException("Device token request failed (HTTP " + response.statusCode()
                    + "): " + response.body());
            }
            sleep(intervalMs);
        }
        throw new IOException(slowDowns > 0
            ? "Device flow timed out after one or more slow_down responses"
            : "Device flow timed out. Please restart login.");
    }

    private HttpResponse<String> pollToken(Device device) throws IOException {
        if (config.deviceStyle() == DeviceCodeConfig.DeviceAuthStyle.OPENAI_CODEX) {
            var body = "{\"device_auth_id\":\"" + device.code()
                + "\",\"user_code\":\"" + device.userCode() + "\"}";
            return send(HttpRequest.newBuilder(URI.create(config.tokenUrl()))
                .header("accept", "application/json")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
        }
        var params = new LinkedHashMap<String, String>();
        params.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
        params.put("client_id", config.clientId());
        params.put("device_code", device.code());
        return send(HttpRequest.newBuilder(URI.create(config.tokenUrl()))
            .header("accept", "application/json")
            .header("content-type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(formEncode(params)))
            .build());
    }

    /** OpenAI Codex 第二段：把 device 轮询拿到的授权码换成 token。 */
    private OAuthCredential exchangeAuthorizationCode(String code, String verifier) throws IOException {
        if (verifier == null || config.exchangeUrl().isBlank()) {
            throw new IOException("OpenAI Codex device poll returned no code_verifier for the exchange.");
        }
        var params = new LinkedHashMap<String, String>();
        params.put("grant_type", "authorization_code");
        params.put("client_id", config.clientId());
        params.put("code", code);
        params.put("code_verifier", verifier);
        params.put("redirect_uri", config.exchangeRedirectUri());
        return parseCredential(parseJson(send(HttpRequest.newBuilder(URI.create(config.exchangeUrl()))
            .header("accept", "application/json")
            .header("content-type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(formEncode(params)))
            .build())));
    }

    // ── Response parsing / HTTP helpers ─────────────────────────────────

    private OAuthCredential parseCredential(JsonNode node) throws IOException {
        var access = text(node, "access_token");
        if (access == null) {
            access = text(node, "key");
        }
        if (access == null) {
            throw new IOException("OAuth token response carries no access token");
        }
        var refresh = text(node, "refresh_token");
        long expiresIn = node.has("expires_in") ? node.get("expires_in").asLong() : -1;
        long expiresAt = expiresIn > 0
            ? Instant.now().getEpochSecond() + expiresIn : Long.MAX_VALUE;
        return new OAuthCredential(access, refresh == null ? "" : refresh, expiresAt,
            text(node, "base_url"));
    }

    private static JsonNode parseJson(HttpResponse<String> response) throws IOException {
        try {
            return JSON.readTree(response.body());
        } catch (IOException e) {
            throw new IOException("OAuth response is not valid JSON", e);
        }
    }

    private static JsonNode tryParseJson(HttpResponse<String> response) {
        try {
            return JSON.readTree(response.body());
        } catch (IOException e) {
            return JSON.getNodeFactory().objectNode();
        }
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
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

    private static boolean openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return true;
            }
        } catch (Exception ignored) {
            // 无桌面环境时用户手动打开
        }
        return false;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
