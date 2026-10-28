package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pijava.coding.agent.core.PromptConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * docs/40 P14：provider-payload——before/after 两臂落
 * {@code <cwd>/.pi-java/provider-payload.log}；请求臂 JSON 美化（2 空格）＋双换行；
 * 响应臂 {@code [status] {headers}} 紧凑形＋双换行。附 {@link JsJson} 的 F2 字节锚点单测。
 */
class ProviderPayloadExtensionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir Path tmp;

    @Test
    void requestAndResponseArmsAppendVerbatim() throws Exception {
        var cwd = Files.createDirectories(tmp.resolve("repo"));
        Files.createDirectories(cwd.resolve(".pi-java")); // pi appendFileSync 不建目录
        try (var scope = ExampleFixtures.scopeExtensions(tmp, ProviderPayloadExtension.class)) {
            try (var session = ExampleFixtures.newSession(cwd, new HookLaneProvider(),
                    "hook-model")) {
                var result = session.processPrompt("go", PromptConfig.defaults());
                assertThat(result.status().exitCode()).isZero();
            }
        }
        var log = Files.readString(cwd.resolve(".pi-java/provider-payload.log"));
        assertThat(log).isEqualTo("""
            {
              "model": "hook-model",
              "stream": true
            }

            [200] {"x-resp":"r"}

            """);
    }

    // ── JsJson 的 F2 字节锚点（node probe.js 实跑产出逐字对位）──

    @Test
    void jsPrettyByteAnchors() {
        // JSON.stringify({}, null, 2) === "{}"
        assertThat(JsJson.stringify(MAPPER.valueToTree(Map.of()), 2)).isEqualTo("{}");
        // {"a": [], "b": {}}（插入序——Map.of 不保单序，LinkedHashMap 直建）
        var emptyContainers = new LinkedHashMap<String, Object>();
        emptyContainers.put("a", List.of());
        emptyContainers.put("b", Map.of());
        assertThat(JsJson.stringify(MAPPER.valueToTree(emptyContainers), 2))
            .isEqualTo("{\n  \"a\": [],\n  \"b\": {}\n}");
        // 嵌套＋null/boolean/1.5/1.0
        var nested = new LinkedHashMap<String, Object>();
        nested.put("a", Map.of("b", List.of(1, Map.of("c", "x"))));
        nested.put("d", null);
        nested.put("e", true);
        nested.put("f", 1.5);
        nested.put("g", 1.0);
        assertThat(JsJson.stringify(MAPPER.valueToTree(nested), 2)).isEqualTo("""
            {
              "a": {
                "b": [
                  1,
                  {
                    "c": "x"
                  }
                ]
              },
              "d": null,
              "e": true,
              "f": 1.5,
              "g": 1
            }""");
    }

    @Test
    void jsIntegerKeysSortFirst() {
        var map = new LinkedHashMap<String, Object>();
        map.put("b", 1);
        map.put("10", "x");
        map.put("2", "y");
        map.put("a", 2);
        assertThat(JsJson.stringify(MAPPER.valueToTree(map), 2))
            .isEqualTo("{\n  \"2\": \"y\",\n  \"10\": \"x\",\n  \"b\": 1,\n  \"a\": 2\n}");
    }

    @Test
    void jsStringEscapes() {
        assertThat(JsJson.stringify(MAPPER.valueToTree(Map.of("s", "q\"bs\\\né/")), 2))
            .isEqualTo("{\n  \"s\": \"q\\\"bs\\\\\\né/\"\n}");
        assertThat(JsJson.stringify(MAPPER.valueToTree(""), 2))
            .isEqualTo("\"\\u0001\\u001f\"");
    }

    @Test
    void jsNumberForms() {
        var numbers = new LinkedHashMap<String, Object>();
        numbers.put("n", 1e21);
        numbers.put("m", 1e-7);
        assertThat(JsJson.stringify(MAPPER.valueToTree(numbers), 2))
            .isEqualTo("{\n  \"n\": 1e+21,\n  \"m\": 1e-7\n}");
        assertThat(JsJson.stringify(MAPPER.valueToTree(0.000001), 2)).isEqualTo("0.000001");
        assertThat(JsJson.stringify(MAPPER.valueToTree(new BigDecimal("100.0")), 2))
            .isEqualTo("100");
    }

    @Test
    void jsCompactForm() {
        var map = new LinkedHashMap<String, Object>();
        map.put("a", 1);
        map.put("b", java.util.Arrays.asList("x", true, null));
        assertThat(JsJson.stringify(MAPPER.valueToTree(map)))
            .isEqualTo("{\"a\":1,\"b\":[\"x\",true,null]}");
    }
}
