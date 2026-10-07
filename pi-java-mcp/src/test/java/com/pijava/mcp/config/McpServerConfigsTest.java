package com.pijava.mcp.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.pijava.mcp.McpJson;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单条服务器配置的校验与 exposure 语义（{@code mcp-servers.ts:207-278}）。
 */
class McpServerConfigsTest {

    private static McpConfigValidation validate(String name, String json) throws Exception {
        return McpServerConfigs.validate(name, McpJson.mapper().readTree(json));
    }

    private static String errorOf(String name, String json) throws Exception {
        assertThat(validate(name, json)).isInstanceOf(McpConfigValidation.Invalid.class);
        return ((McpConfigValidation.Invalid) validate(name, json)).message();
    }

    private static McpServerConfig configOf(String name, String json) throws Exception {
        var validation = validate(name, json);
        assertThat(validation).isInstanceOf(McpConfigValidation.Valid.class);
        return ((McpConfigValidation.Valid) validation).config();
    }

    @Test
    void rejectsInvalidNamesAndShapes() throws Exception {
        assertThat(errorOf("bad name", "{\"command\":\"x\"}"))
                .isEqualTo("invalid server name \"bad name\" (use letters, digits, \"_\" and \"-\")");
        assertThat(errorOf("ok", "5")).isEqualTo("server \"ok\" must be an object");
        assertThat(errorOf("ok", "[]")).isEqualTo("server \"ok\" must be an object");
    }

    @Test
    void requiresCommandOrUrl() throws Exception {
        assertThat(errorOf("bad", "{\"args\":[\"no command\"]}"))
                .isEqualTo("server \"bad\" needs either \"command\" (stdio) or \"url\" (streamable HTTP)");
        assertThat(errorOf("legacy", "{\"type\":\"sse\",\"url\":\"https://example.com/sse\"}"))
                .isEqualTo("server \"legacy\": legacy SSE transport is not supported;"
                        + " use the streamable HTTP URL");
        assertThat(errorOf("badUrl", "{\"url\":\"example.com/mcp\"}"))
                .isEqualTo("server \"badUrl\": url must be an http or https URL");
    }

    @Test
    void validatesExposureAndAliases() throws Exception {
        assertThat(errorOf("wrong", "{\"command\":\"x\",\"exposure\":\"model-only\"}"))
                .isEqualTo("server \"wrong\": exposure must be one of"
                        + " \"codemode\", \"deferred\", \"direct\", \"hidden\"");
        assertThat(errorOf("bad", "{\"command\":\"x\",\"toolExposure\":{\"a\":\"visible\"}}"))
                .isEqualTo("server \"bad\": toolExposure \"a\" must be one of"
                        + " \"codemode\", \"deferred\", \"direct\", \"hidden\"");
        assertThat(errorOf("bad", "{\"command\":\"x\",\"toolExposure\":5}"))
                .isEqualTo("server \"bad\": toolExposure must map tool names to exposures");
        assertThat(errorOf("bad", "{\"command\":\"x\",\"enabled\":\"yes\"}"))
                .isEqualTo("server \"bad\": enabled must be a boolean");
        assertThat(errorOf("bad", "{\"command\":\"x\",\"description\":1}"))
                .isEqualTo("server \"bad\": description must be a string");
        assertThat(errorOf("bad", "{\"command\":\"x\",\"timeout\":0}"))
                .isEqualTo("server \"bad\": timeout must be a positive number of seconds");

        var stdio = configOf("scripts", "{\"command\":\"x\",\"exposure\":\"codemode-deferred\","
                + "\"toolExposure\":{\"a\":\"codemode-deferred\"}}");
        assertThat(stdio.exposure()).isEqualTo(McpExposure.CODEMODE);
        assertThat(stdio.toolExposure()).containsExactly(Map.entry("a", McpExposure.CODEMODE));
    }

    @Test
    void validatesStdioFields() throws Exception {
        assertThat(errorOf("s", "{\"command\":\"x\",\"args\":\"y\"}"))
                .isEqualTo("server \"s\": args must be an array of strings");
        assertThat(errorOf("s", "{\"command\":\"x\",\"args\":[1]}"))
                .isEqualTo("server \"s\": args must be an array of strings");
        assertThat(errorOf("s", "{\"command\":\"x\",\"env\":{\"A\":1}}"))
                .isEqualTo("server \"s\": env must map names to strings");
        assertThat(errorOf("s", "{\"command\":\"x\",\"cwd\":1}"))
                .isEqualTo("server \"s\": cwd must be a string");

        var stdio = (McpServerConfig.Stdio) configOf("s", "{\"command\":\"npx\",\"args\":[\"-y\"],"
                + "\"env\":{\"A\":\"1\"},\"cwd\":\".\"}");
        assertThat(stdio.command()).isEqualTo("npx");
        assertThat(stdio.args()).containsExactly("-y");
        assertThat(stdio.env()).containsExactly(Map.entry("A", "1"));
        assertThat(stdio.cwd()).isEqualTo(".");
    }

    @Test
    void validatesHttpFields() throws Exception {
        assertThat(errorOf("s", "{\"url\":\"https://a.example/mcp\",\"headers\":{\"A\":1}}"))
                .isEqualTo("server \"s\": headers must map names to strings");

        var http = (McpServerConfig.Http) configOf("remote",
                "{\"url\":\"https://example.com/mcp\",\"headers\":{\"Authorization\":\"Bearer ${TOKEN}\"}}");
        assertThat(http.url()).isEqualTo("https://example.com/mcp");
        assertThat(http.headers()).containsExactly(Map.entry("Authorization", "Bearer ${TOKEN}"));
    }

    @Test
    void validatesOAuthCallbackUrlScopeAndClientName() throws Exception {
        var prefix = "{\"url\":\"https://a.example/mcp\",\"oauth\":";
        assertThat(errorOf("remote", prefix + "{\"callbackUrl\":\"https://example.com/callback\"}}"))
                .isEqualTo("server \"remote\": oauth.callbackUrl must be an http URI on localhost,"
                        + " 127.0.0.1, or [::1] without query or fragment");
        assertThat(errorOf("query", prefix + "{\"callbackUrl\":\"http://localhost/cb?x=1\"}}"))
                .isEqualTo("server \"query\": oauth.callbackUrl must be an http URI on localhost,"
                        + " 127.0.0.1, or [::1] without query or fragment");
        assertThat(errorOf("both", prefix + "{\"callbackUrl\":\"http://127.0.0.1:1/cb\",\"callbackPort\":2}}"))
                .isEqualTo("server \"both\": oauth.callbackUrl and oauth.callbackPort name different ports");
        assertThat(errorOf("port", prefix + "{\"callbackPort\":0}}"))
                .isEqualTo("server \"port\": oauth.callbackPort must be a port number");
        assertThat(errorOf("port", prefix + "{\"callbackPort\":1.5}}"))
                .isEqualTo("server \"port\": oauth.callbackPort must be a port number");
        assertThat(errorOf("scope", prefix + "{\"scope\":[\"a\"]}}"))
                .isEqualTo("server \"scope\": oauth.scope must be a string");
        assertThat(errorOf("unnamed", prefix + "{\"clientName\":\" \"}}"))
                .isEqualTo("server \"unnamed\": oauth.clientName must be a non-empty string");
        assertThat(errorOf("plain", prefix + "{\"authServerMetadataUrl\":\"http://idp.example/m\"}}"))
                .isEqualTo("server \"plain\": oauth.authServerMetadataUrl must be an https URL,"
                        + " or http on localhost, 127.0.0.1, or [::1]");
        assertThat(errorOf("bad", prefix + "{\"clientRegistration\":\"auto\"}}"))
                .isEqualTo("server \"bad\": oauth.clientRegistration must be \"dcr\" or \"cimd\"");
        assertThat(errorOf("cimdClient", prefix + "{\"clientRegistration\":\"cimd\",\"clientId\":\"x\"}}"))
                .isEqualTo("server \"cimdClient\": oauth.clientRegistration \"cimd\" cannot be combined"
                        + " with oauth.clientId or oauth.clientName");
        assertThat(errorOf("cimdPath",
                prefix + "{\"clientRegistration\":\"cimd\",\"callbackUrl\":\"http://127.0.0.1/cb\"}}"))
                .isEqualTo("server \"cimdPath\": oauth.clientRegistration \"cimd\" requires"
                        + " oauth.callbackUrl on localhost or 127.0.0.1 with path /callback");
        assertThat(errorOf("notobj", prefix + "5}")).isEqualTo("server \"notobj\": oauth must be an object");
    }

    @Test
    void acceptsTheLoopbackAndCimdBoundaries() throws Exception {
        for (var json : List.of(
                "{\"url\":\"https://a.example/mcp\",\"oauth\":{\"callbackUrl\":\"http://localhost:8080/callback\",\"scope\":\"a b\"}}",
                "{\"url\":\"https://a.example/mcp\",\"oauth\":{\"callbackUrl\":\"http://[::1]/cb\",\"callbackPort\":9000}}",
                "{\"url\":\"https://a.example/mcp\",\"oauth\":{\"callbackUrl\":\"http://127.0.0.1:2/cb\",\"callbackPort\":2}}",
                "{\"url\":\"https://a.example/mcp\",\"oauth\":{\"clientName\":\"Claude Code\"}}",
                "{\"url\":\"https://a.example/mcp\",\"oauth\":{\"authServerMetadataUrl\":\"https://idp.example/m\"}}",
                "{\"url\":\"https://a.example/mcp\",\"oauth\":{\"clientRegistration\":\"cimd\",\"callbackUrl\":\"http://localhost/callback\"}}")) {
            assertThat(validate("ok", json)).isInstanceOf(McpConfigValidation.Valid.class);
        }
    }

    @Test
    void validatesProviderAuthAndLoopbackHostsCaseInsensitively() throws Exception {
        var http = (McpServerConfig.Http) configOf("radius",
                "{\"url\":\"https://radius.example/mcp\",\"auth\":{\"provider\":\"radius\"}}");
        assertThat(http.auth()).isEqualTo(new McpServerConfig.Auth("radius"));
        assertThat(validate("local", "{\"url\":\"http://localhost:8788/mcp\",\"auth\":{\"provider\":\"r-dev\"}}"))
                .isInstanceOf(McpConfigValidation.Valid.class);
        // JS 的 `new URL(...).hostname` 小写化 ⇒ pi 放行 http://LOCALHOST。
        assertThat(validate("upper", "{\"url\":\"http://LOCALHOST:8788/mcp\",\"auth\":{\"provider\":\"r\"}}"))
                .isInstanceOf(McpConfigValidation.Valid.class);
        assertThat(errorOf("plain", "{\"url\":\"http://radius.example/mcp\",\"auth\":{\"provider\":\"radius\"}}"))
                .isEqualTo("server \"plain\": auth requires an https URL, or http on localhost,"
                        + " 127.0.0.1, or [::1]");
        assertThat(errorOf("empty", "{\"url\":\"https://radius.example/mcp\",\"auth\":{\"provider\":\"\"}}"))
                .isEqualTo("server \"empty\": auth.provider must be a provider name");
    }

    @Test
    void namespacesAndToolExposure() {
        assertThat(McpServerConfigs.namespace("work-files")).isEqualTo("mcp__work_files");
        assertThat(McpServerConfigs.namespace("docs")).isEqualTo("mcp__docs");

        var overrides = new LinkedHashMap<String, McpExposure>();
        overrides.put("get_*", McpExposure.CODEMODE);
        overrides.put("get_me", McpExposure.DIRECT);
        overrides.put("*delete*", McpExposure.HIDDEN);
        overrides.put("get_file.*", McpExposure.DIRECT);
        var config = new McpServerConfig.Stdio(null, McpExposure.DEFERRED, null, overrides, null, null,
                "x", null, null, null);

        assertThat(McpServerConfigs.getMcpToolExposure(config, "get_me")).isEqualTo(McpExposure.DIRECT);
        assertThat(McpServerConfigs.getMcpToolExposure(config, "get_issue")).isEqualTo(McpExposure.CODEMODE);
        assertThat(McpServerConfigs.getMcpToolExposure(config, "get_delete_hint")).isEqualTo(McpExposure.CODEMODE);
        assertThat(McpServerConfigs.getMcpToolExposure(config, "delete_repo")).isEqualTo(McpExposure.HIDDEN);
        assertThat(McpServerConfigs.getMcpToolExposure(config, "list_issues")).isEqualTo(McpExposure.DEFERRED);

        // 只有 `*` 特殊：`get_file.*` 不匹配 `get_file_x`（mcp-extension.test.ts:234）。
        var dotted = Map.of("get_file.*", McpExposure.DIRECT);
        var onlyDots = new McpServerConfig.Stdio(null, null, null, dotted, null, null, "x", null, null, null);
        assertThat(McpServerConfigs.getMcpToolExposure(onlyDots, "get_file_x")).isEqualTo(McpExposure.CODEMODE);
        assertThat(McpServerConfigs.getMcpToolExposure(onlyDots, "get_file.a")).isEqualTo(McpExposure.DIRECT);

        // 默认 exposure 是 codemode。
        var plain = new McpServerConfig.Stdio(null, null, null, null, null, null, "x", null, null, null);
        assertThat(McpServerConfigs.getMcpToolExposure(plain, "any")).isEqualTo(McpExposure.CODEMODE);
    }
}
