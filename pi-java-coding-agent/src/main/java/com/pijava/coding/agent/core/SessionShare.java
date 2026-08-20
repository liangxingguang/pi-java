package com.pijava.coding.agent.core;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 会话分享（P6-13）：把会话 JSONL 发布为 GitHub gist。
 *
 * <p>需要 {@code GITHUB_TOKEN} 环境变量（gist 需要 token；不含 gist scope 的
 * token 会被 GitHub 拒绝）。私有 gist，返回 {@code html_url} 供分享。</p>
 */
public final class SessionShare {

    private static final String GISTS_URL = "https://api.github.com/gists";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;
    private final String gistsUrl;

    /** 默认 HttpClient 与官方 gist 端点。 */
    public SessionShare() {
        this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(),
            GISTS_URL);
    }

    /** 测试注入：HttpClient 与 gist 端点 URL。 */
    SessionShare(HttpClient http, String gistsUrl) {
        this.http = http;
        this.gistsUrl = gistsUrl;
    }

    /**
     * 将会话 JSONL 发布为私有 gist。
     *
     * @param sessionJsonl 会话 JSONL 文本
     * @return gist 分享 URL
     * @throws IOException 无 token 或上传失败
     */
    public String share(String sessionJsonl) throws IOException {
        return share(sessionJsonl, System.getenv("GITHUB_TOKEN"));
    }

    /** 带显式 token 的分享（测试注入）。 */
    String share(String sessionJsonl, String token) throws IOException {
        if (token == null || token.isBlank()) {
            throw new IOException("GITHUB_TOKEN is not set (gist upload needs a token)");
        }
        var payload = JSON.createObjectNode();
        payload.put("description", "pi-java session export");
        payload.put("public", false);
        var files = payload.putObject("files");
        files.putObject("session.jsonl").put("content", sessionJsonl);

        var request = HttpRequest.newBuilder(URI.create(gistsUrl))
            .header("authorization", "Bearer " + token)
            .header("accept", "application/vnd.github+json")
            .header("content-type", "application/json")
            .header("user-agent", "pi-java")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
            .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Gist upload interrupted", e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Gist upload failed (HTTP " + response.statusCode()
                + "): " + response.body());
        }
        JsonNode node;
        try {
            node = JSON.readTree(response.body());
        } catch (IOException e) {
            throw new IOException("Gist response is not valid JSON", e);
        }
        var url = node.hasNonNull("html_url") ? node.get("html_url").asText() : null;
        if (url == null) {
            throw new IOException("Gist response carries no html_url");
        }
        return url;
    }
}
