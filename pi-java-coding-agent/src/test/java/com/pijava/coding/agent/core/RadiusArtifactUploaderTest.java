package com.pijava.coding.agent.core;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class RadiusArtifactUploaderTest {

    @TempDir
    Path tmp;

    @Test
    void uploadsRawNdjsonWithOrganizationQueryAndBearer() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var request = new RecordingRequest();
        server.createContext("/v1/artifacts", exchange -> {
            request.query = exchange.getRequestURI().getRawQuery();
            request.authorization = exchange.getRequestHeaders().getFirst("authorization");
            request.contentType = exchange.getRequestHeaders().getFirst("content-type");
            request.contentLength = exchange.getRequestHeaders().getFirst("content-length");
            request.body = exchange.getRequestBody().readAllBytes();
            var response = "{\"artifact\":{\"canonical_url\":\"https://radius.example/a/1\"}}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var body = "{\"type\":\"session\"}\n{\"text\":\"雪\"}\n";
            var file = tmp.resolve("session.jsonl");
            Files.writeString(file, body, StandardCharsets.UTF_8);
            var uploader = new RadiusArtifactUploader(
                "http://127.0.0.1:" + server.getAddress().getPort(), HttpClient.newHttpClient());

            var result = uploader.upload(file, "token", ShareCancellation.never());

            assertThat(result.kind()).isEqualTo(ShareOutcome.Kind.SUCCESS);
            assertThat(result.message()).isEqualTo("Share URL: https://radius.example/a/1");
            assertThat(request.query).isEqualTo("visibility=organization&title=Pi+session");
            assertThat(request.authorization).isEqualTo("Bearer token");
            assertThat(request.contentType).isEqualTo("application/x-ndjson");
            assertThat(request.body).isEqualTo(body.getBytes(StandardCharsets.UTF_8));
            assertThat(Integer.parseInt(request.contentLength)).isEqualTo(request.body.length);
        } finally {
            server.stop(0);
        }
    }

    private static final class RecordingRequest {
        private String query;
        private String authorization;
        private String contentType;
        private String contentLength;
        private byte[] body;
    }
}
