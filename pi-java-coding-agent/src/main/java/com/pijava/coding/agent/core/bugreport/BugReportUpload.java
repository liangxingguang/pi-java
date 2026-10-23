package com.pijava.coding.agent.core.bugreport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * bug report 上传（pi {@code core/bug-report-upload.ts} 逐段对位）：multipart
 * form-data POST 到 Radius 网关 {@code /v1/bug-reports}，匿名或带 Radius token。
 *
 * <p>裁决⑩：java 无 Radius provider，生产恒匿名（{@code token == null}）；
 * token 参预留（{@code Authorization: Bearer} 臂有测试），Radius 登录面落地时
 * 接线（窄差 D85）。网关地址<b>每调现读</b>环境（批 14 静态冻结教训）。</p>
 */
public final class BugReportUpload {

    /** pi-ai {@code radius-config.ts:4} 的 {@code DEFAULT_RADIUS_GATEWAY}。 */
    static final String DEFAULT_GATEWAY = "https://radius.pi.dev";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final HttpClient http;
    private final String gateway;

    /** 默认 HttpClient 与 {@link #gatewayUrl()} 网关。 */
    public BugReportUpload() {
        this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(),
            gatewayUrl());
    }

    /** 测试注入：HttpClient 与网关 URL（SessionShare 先例）。 */
    BugReportUpload(HttpClient http, String gateway) {
        this.http = http;
        this.gateway = gateway;
    }

    /**
     * pi {@code getRadiusGatewayUrl()}（{@code core/radius.ts:12-14}）：
     * {@code PI_RADIUS_GATEWAY} 环境变量 ?? 默认网关，normalize 去尾斜杠。
     */
    public static String gatewayUrl() {
        var env = System.getenv("PI_RADIUS_GATEWAY");
        return normalize(env != null && !env.isBlank() ? env : DEFAULT_GATEWAY);
    }

    /** pi 上传响应。 */
    public record Result(String id) {}

    /**
     * pi {@code uploadBugReport}：multipart 全文件 POST；成功＝2xx &&
     * {@code json.ok == true} && {@code bug_report.id} 是串；否则抛
     * {@code Bug report upload failed: <detail||statusText||status>}【用户可见逐字】。
     */
    public Result upload(BugReport.Bundle bundle, String token) throws IOException {
        var boundary = boundary();
        var body = multipartBody(boundary, bundle);
        var builder = HttpRequest.newBuilder(URI.create(gateway + "/v1/bug-reports"))
            .header("content-type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (token != null && !token.isBlank()) {
            builder.header("authorization", "Bearer " + token);
        }
        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Bug report upload interrupted", e);
        }
        com.fasterxml.jackson.databind.JsonNode json = null;
        try {
            json = BugReport.MAPPER.readTree(response.body());
        } catch (Exception ignored) {
            // pi response.json().catch(() => null)：非 JSON 体 ⇒ json == null。
        }
        boolean ok = response.statusCode() / 100 == 2
            && json != null && json.path("ok").isBoolean() && json.path("ok").booleanValue()
            && json.path("bug_report").path("id").isTextual();
        if (ok) {
            return new Result(json.path("bug_report").path("id").textValue());
        }
        // pi: const detail = json && !json.ok ? json.description || json.error : undefined
        String detail = null;
        if (json != null && !json.path("ok").booleanValue()) {
            detail = json.path("description").isTextual() ? json.path("description").textValue()
                : json.path("error").isTextual() ? json.path("error").textValue() : null;
        }
        // java HttpResponse 无 statusText ⇒ 回落即 status（pi 链的末位）。
        throw new IOException("Bug report upload failed: "
            + (detail != null ? detail : String.valueOf(response.statusCode())));
    }

    private static String normalize(String url) {
        var out = url;
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private static String boundary() {
        var bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        var sb = new StringBuilder("----pi-java-form-");
        for (var b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static byte[] multipartBody(String boundary, BugReport.Bundle bundle)
            throws IOException {
        var out = new ByteArrayOutputStream();
        for (var file : BugReport.files(bundle)) {
            out.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + file.name()
                + "\"; filename=\"" + file.name() + "\"\r\n"
                + "Content-Type: " + file.contentType() + "\r\n"
                + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(file.data().getBytes(StandardCharsets.UTF_8));
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }
}
