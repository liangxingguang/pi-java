package com.pijava.coding.agent.pkg;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 18 T5（docs/29 §3/§7）：pi {@code readPiManifest}（pi-manifest.ts 全 35 行
 * 对位）——package.json 的 {@code pi} 字段、四资源键、非对象/解析失败 ⇒ null。
 */
class PiManifestTest {

    @TempDir
    Path dir;

    private Path write(String json) throws Exception {
        var path = dir.resolve("package.json");
        Files.writeString(path, json);
        return path;
    }

    @Test
    void readsAllFourResourceKeys() throws Exception {
        var manifest = PiManifest.read(write(
            "{\"name\":\"x\",\"pi\":{\"extensions\":[\"a.jar\"],\"skills\":[\"s/\"],"
                + "\"prompts\":[\"p.md\"],\"themes\":[\"t.json\"]}}"));

        assertThat(manifest.extensions()).containsExactly("a.jar");
        assertThat(manifest.skills()).containsExactly("s/");
        assertThat(manifest.prompts()).containsExactly("p.md");
        assertThat(manifest.themes()).containsExactly("t.json");
    }

    @Test
    void missingPiFieldYieldsNull() throws Exception {
        assertThat(PiManifest.read(write("{\"name\":\"x\"}"))).isNull();
    }

    @Test
    void nonObjectPiYieldsNull() throws Exception {
        assertThat(PiManifest.read(write("{\"pi\":\"nope\"}"))).isNull();
        assertThat(PiManifest.read(write("{\"pi\":[\"a\"]}"))).isNull();
    }

    @Test
    void invalidJsonYieldsNull() throws Exception {
        assertThat(PiManifest.read(write("not json{{{"))).isNull();
        assertThat(PiManifest.read(dir.resolve("absent.json"))).isNull();
    }

    @Test
    void nonStringEntriesDropThatKeyOnly() throws Exception {
        var manifest = PiManifest.read(write(
            "{\"pi\":{\"extensions\":[\"a.jar\",42],\"skills\":[\"ok.md\"]}}"));

        assertThat(manifest).isNotNull();
        assertThat(manifest.extensions()).isNull();
        assertThat(manifest.skills()).containsExactly("ok.md");
        assertThat(manifest.prompts()).isNull();
    }

    @Test
    void bomTolerated() throws Exception {
        assertThat(PiManifest.read(write("﻿{\"pi\":{\"skills\":[\"s.md\"]}}")))
            .isNotNull()
            .extracting(m -> m.skills())
            .isEqualTo(java.util.List.of("s.md"));
    }
}
