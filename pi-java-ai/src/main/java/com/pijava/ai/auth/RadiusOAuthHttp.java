package com.pijava.ai.auth;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Radius OAuth discovery/device/token HTTP details. */
final class RadiusOAuthHttp {

    record Device(String code, String userCode, String verificationUri,
                  long intervalSeconds, long expiresInSeconds) {}

    /** HTTP/OAuth error retaining the protocol error code for polling. */
    static final class OAuthResponseError extends IOException {
        private final String oauthError;

        private OAuthResponseError(String message, String oauthError) {
            super(message);
            this.oauthError = oauthError;
        }

        String oauthError() {
            return oauthError;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final RadiusOAuthConfig config;
    private final HttpClient http;

    RadiusOAuthHttp(RadiusOAuthConfig config, HttpClient http) {
        this.config = config;
        this.http = http;
    }

    String loadAuthorizationEndpoint() throws IOException {
        var response = send(HttpRequest.newBuilder(URI.create(config.gateway() + "/v1/oauth"))
            .header("accept", "application/json").GET().build());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Could not load Radius OAuth config from " + config.gateway()
                + ": " + response.statusCode() + " " + response.body());
        }
        var endpointNode = JSON.readTree(response.body()).path("authorizationEndpoint");
        var endpoint = TrustedHttpUrl.normalize(
            endpointNode.isTextual() ? endpointNode.textValue() : null);
        if (endpoint == null) {
            throw new IOException("Invalid Radius OAuth config from " + config.gateway());
        }
        return endpoint;
    }

    Device requestDevice() throws IOException {
        var response = post(config.gateway() + "/v1/oauth/device", Map.of(
            "client_id", "pi-gateway", "scope", "gateway offline_access"));
        if (response.statusCode() / 100 != 2) {
            throw error(response, "Radius OAuth device authorization failed");
        }
        var node = JSON.readTree(response.body());
        // pi radius.ts:215 的真值判据：空串与数字 0 都算缺失。
        if (!nonEmptyText(node.path("device_code")) || !nonEmptyText(node.path("user_code"))
                || !nonEmptyText(node.path("verification_uri")) || !nonZeroNumber(node.path("expires_in"))) {
            throw new IOException("Radius OAuth device authorization response is missing required fields");
        }
        var verificationUri = TrustedHttpUrl.normalize(node.path("verification_uri").textValue());
        if (verificationUri == null) {
            throw new IOException("Untrusted verification_uri in Radius OAuth device authorization response");
        }
        return new Device(node.path("device_code").textValue(), node.path("user_code").textValue(),
            verificationUri, node.path("interval").asLong(5), node.path("expires_in").asLong());
    }

    private static boolean nonEmptyText(com.fasterxml.jackson.databind.JsonNode node) {
        return node.isTextual() && !node.textValue().isEmpty();
    }

    private static boolean nonZeroNumber(com.fasterxml.jackson.databind.JsonNode node) {
        return node.isNumber() && node.asDouble() != 0;
    }

    OAuthCredential token(Map<String, String> params) throws IOException {
        var response = post(config.gateway() + "/v1/oauth/token", params);
        if (response.statusCode() / 100 != 2) {
            throw error(response, "Radius OAuth token request failed");
        }
        var node = JSON.readTree(response.body());
        long expiresIn = node.path("expires_in").asLong();
        long expiresAt = expiresIn > 0
            ? Instant.now().getEpochSecond() + expiresIn - 60 : Long.MAX_VALUE;
        var scopes = node.path("scope").isTextual()
            ? List.of(node.path("scope").textValue().split(" ")) : List.<String>of();
        return new OAuthCredential(node.path("access_token").textValue(),
            node.path("refresh_token").isTextual() ? node.path("refresh_token").textValue() : "",
            expiresAt, node.path("base_url").isTextual() ? node.path("base_url").textValue() : null,
            null, scopes);
    }

    private HttpResponse<String> post(String url, Map<String, String> params) throws IOException {
        var body = OAuthFlow.formEncode(new LinkedHashMap<>(params));
        return send(HttpRequest.newBuilder(URI.create(url))
            .header("accept", "application/json")
            .header("content-type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Login cancelled", e);
        }
    }

    private static OAuthResponseError error(HttpResponse<String> response, String message) {
        String oauthError = null;
        String description = null;
        try {
            var node = JSON.readTree(response.body());
            oauthError = node.path("error").isTextual() ? node.path("error").textValue() : null;
            description = node.path("error_description").isTextual()
                ? node.path("error_description").textValue() : null;
        } catch (Exception ignored) {
            description = response.body();
        }
        var detail = oauthError != null
            ? description != null ? oauthError + ": " + description : oauthError
            : description != null ? description : String.valueOf(response.statusCode());
        return new OAuthResponseError(message + ": " + detail, oauthError);
    }
}
