package com.pijava.coding.agent.core;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pijava.ai.provider.builtin.RadiusProvider;

/** Uploads the current branch as a Radius organization artifact. */
public final class RadiusArtifactUploader {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final String gateway;
    private final HttpClient http;

    /** Production uploader using the built-in Radius gateway. */
    public RadiusArtifactUploader() {
        this(RadiusProvider.DEFAULT_GATEWAY,
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    RadiusArtifactUploader(String gateway, HttpClient http) {
        this.gateway = gateway.replaceAll("/+$", "");
        this.http = http;
    }

    /** Upload raw NDJSON; every Radius outcome is final and never triggers gist fallback. */
    public ShareOutcome upload(Path sessionJsonl, String token, ShareCancellation cancellation) {
        try {
            var body = Files.readAllBytes(sessionJsonl);
            if (cancellation.isCancelled()) {
                return cancelled();
            }
            var request = HttpRequest.newBuilder(URI.create(gateway
                    + "/v1/artifacts?visibility=organization&title=Pi+session"))
                .header("authorization", "Bearer " + token)
                .header("content-type", "application/x-ndjson")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (cancellation.isCancelled()) {
                return cancelled();
            }
            JsonNode json = null;
            try {
                json = JSON.readTree(response.body());
            } catch (IOException ignored) {
                // pi response.json().catch(() => null)
            }
            var artifact = json == null ? null : json.path("artifact");
            var canonical = artifact == null || !artifact.path("canonical_url").isTextual()
                ? null : artifact.path("canonical_url").textValue();
            if (response.statusCode() / 100 != 2 || canonical == null) {
                var error = json != null && json.path("error").isTextual()
                    && !json.path("error").textValue().isBlank()
                    ? json.path("error").textValue() : String.valueOf(response.statusCode());
                return new ShareOutcome(ShareOutcome.Kind.ERROR,
                    "Failed to upload Radius artifact: " + error);
            }
            return new ShareOutcome(ShareOutcome.Kind.SUCCESS, "Share URL: " + canonical);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return cancelled();
        } catch (IOException e) {
            return new ShareOutcome(ShareOutcome.Kind.ERROR,
                "Failed to upload Radius artifact: " + messageOf(e));
        }
    }

    private static ShareOutcome cancelled() {
        return new ShareOutcome(ShareOutcome.Kind.CANCELLED, "Share cancelled");
    }

    private static String messageOf(Exception error) {
        return error.getMessage() == null ? "Unknown error" : error.getMessage();
    }
}
