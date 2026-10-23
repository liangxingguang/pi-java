package com.pijava.coding.agent.core.bugreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

/** pi {@code bug-report-upload.ts} 对拍：成功/缺 id/500/非 JSON/multipart 形状/token 头。 */
class BugReportUploadTest {

    private static final BugReport.Bundle BUNDLE = new BugReport.Bundle(
        BugReport.MAPPER.createObjectNode().put("schemaVersion", 1),
        BugReport.MAPPER.createObjectNode().put("schemaVersion", 1),
        "{\"type\":\"session\"}\n",
        "# Summary");

    @Test
    void successfulUploadReturnsIdAndSendsMultipartWithToken() throws Exception {
        var capturedBody = new AtomicReference<String>();
        var capturedAuth = new AtomicReference<String>();
        var capturedContentType = new AtomicReference<String>();
        var server = server(200, "{\"ok\":true,\"bug_report\":{\"id\":\"br-123\"}}",
            capturedBody, capturedAuth, capturedContentType);
        try {
            var upload = new BugReportUpload(HttpClient.newHttpClient(), url(server));

            var result = upload.upload(BUNDLE, "token-abc");

            assertThat(result.id()).isEqualTo("br-123");
            assertThat(capturedAuth.get()).isEqualTo("Bearer token-abc");
            assertThat(capturedContentType.get())
                .startsWith("multipart/form-data; boundary=");
            // multipart 体形状：四名四件各一段（filename＋Content-Type）。
            var body = capturedBody.get();
            for (var name : new String[] {
                    "report.json", "diagnostics.json", "session.jsonl", "summary.md"}) {
                assertThat(body).contains(
                    "Content-Disposition: form-data; name=\"" + name
                        + "\"; filename=\"" + name + "\"");
            }
            assertThat(body).contains("Content-Type: application/x-ndjson");
            assertThat(body).contains("# Summary");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anonymousUploadSendsNoAuthorizationHeader() throws Exception {
        var capturedAuth = new AtomicReference<String>();
        var server = server(200, "{\"ok\":true,\"bug_report\":{\"id\":\"br-1\"}}",
            new AtomicReference<>(), capturedAuth, new AtomicReference<>());
        try {
            var upload = new BugReportUpload(HttpClient.newHttpClient(), url(server));
            var result = upload.upload(BUNDLE, null);
            assertThat(result.id()).isEqualTo("br-1");
            assertThat(capturedAuth.get()).isNull();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void okWithoutIdThrows() throws Exception {
        var server = server(200, "{\"ok\":true}",
            new AtomicReference<>(), new AtomicReference<>(), new AtomicReference<>());
        try {
            var upload = new BugReportUpload(HttpClient.newHttpClient(), url(server));
            var thrown = catchThrowable(() -> upload.upload(BUNDLE, null));
            // json.ok 为真 ⇒ detail 缺席 ⇒ 回落 status（pi 链末位）。
            assertThat(thrown).hasMessage("Bug report upload failed: 200");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void serverErrorUsesDescription() throws Exception {
        var server = server(500, "{\"ok\":false,\"description\":\"quota exceeded\"}",
            new AtomicReference<>(), new AtomicReference<>(), new AtomicReference<>());
        try {
            var upload = new BugReportUpload(HttpClient.newHttpClient(), url(server));
            var thrown = catchThrowable(() -> upload.upload(BUNDLE, null));
            assertThat(thrown).hasMessage("Bug report upload failed: quota exceeded");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void nonJsonBodyFallsBackToStatus() throws Exception {
        var server = server(502, "<html>bad gateway</html>",
            new AtomicReference<>(), new AtomicReference<>(), new AtomicReference<>());
        try {
            var upload = new BugReportUpload(HttpClient.newHttpClient(), url(server));
            var thrown = catchThrowable(() -> upload.upload(BUNDLE, null));
            assertThat(thrown).hasMessage("Bug report upload failed: 502");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void gatewayUrlDefaultsAndNormalizesTrailingSlash() {
        // 无 PI_RADIUS_GATEWAY 时（测试环境假定缺席）＝默认网关。
        if (System.getenv("PI_RADIUS_GATEWAY") == null) {
            assertThat(BugReportUpload.gatewayUrl())
                .isEqualTo(BugReportUpload.DEFAULT_GATEWAY);
        }
    }

    private static String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static HttpServer server(int status, String body,
            AtomicReference<String> capturedBody, AtomicReference<String> capturedAuth,
            AtomicReference<String> capturedContentType) throws Exception {
        var server = HttpServer.create(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/bug-reports", exchange -> {
            capturedAuth.set(exchange.getRequestHeaders().getFirst("authorization"));
            capturedContentType.set(exchange.getRequestHeaders().getFirst("content-type"));
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8));
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }
}
