package com.pijava.ai.provider;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** HTTP client for Radius /v1/config. */
public class RadiusGatewayClient {

    private final String gateway;
    private final HttpClient http;

    /** Create a client with normalized gateway URL. */
    public RadiusGatewayClient(String gateway, HttpClient http) {
        this.gateway = normalizeGateway(gateway);
        this.http = http;
    }

    /** Load and validate the current account catalog. */
    public RadiusGatewayConfig load(String bearer) throws IOException {
        var builder = HttpRequest.newBuilder(URI.create(gateway + "/v1/config"))
            .header("accept", "application/json").GET();
        if (bearer != null && !bearer.isBlank()) {
            builder.header("authorization", "Bearer " + bearer);
        }
        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Radius config request interrupted", e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Could not load Radius config from " + gateway + ": "
                + response.statusCode() + ": " + truncate(response.body()));
        }
        try {
            return RadiusGatewayConfig.parse("radius", response.body());
        } catch (IOException e) {
            throw new IOException("Invalid Radius config from " + gateway, e);
        }
    }

    /** pi normalizeRadiusGatewayUrl: add https when absent and strip trailing slashes. */
    public static String normalizeGateway(String value) {
        var withScheme = value.matches("(?i)^https?://.*") ? value : "https://" + value;
        var result = withScheme;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String truncate(String body) {
        var trimmed = body == null ? "" : body.trim();
        return trimmed.length() > 512 ? trimmed.substring(0, 512) + "…" : trimmed;
    }
}
