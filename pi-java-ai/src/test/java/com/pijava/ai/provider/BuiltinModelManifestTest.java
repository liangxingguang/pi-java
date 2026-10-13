package com.pijava.ai.provider;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.ai.provider.builtin.ModelData;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 3a T2：携带分片的信任锚 —— 逐字节复制自 npm {@code pi-ai@1.1.0}，
 * 每个文件的 sha256（UTF-8 字节）必须等于 {@code .manifest.json#files} 中上游钉死的值；
 * {@code generatedAt} 是远程目录守卫（{@code RemoteCatalogProvider}）的比较基准。
 */
class BuiltinModelManifestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode manifest() throws IOException {
        try (var in = BuiltinModelManifestTest.class.getResourceAsStream("/catalog/.manifest.json")) {
            assertThat(in).as("manifest must be on the classpath").isNotNull();
            return MAPPER.readTree(in);
        }
    }

    @Test
    void everyCarriedShardMatchesItsManifestSha256() throws Exception {
        var files = manifest().path("files");
        for (var shard : BuiltinModelCatalogs.shardIds()) {
            var name = shard + ".json";
            var expected = files.path(name).asText("");
            assertThat(expected).as("manifest lists %s", name).isNotBlank();
            assertThat(sha256("/catalog/" + name)).isEqualTo(expected);
        }
    }

    @Test
    void manifestPinsSchemaVersionAndGenerationTimestamp() throws Exception {
        var manifest = manifest();
        assertThat(manifest.path("schemaVersion").asInt()).isEqualTo(6);
        var pinned = Instant.parse("2026-10-07T22:01:28.515Z");
        assertThat(BuiltinModelCatalogs.generatedAt()).isEqualTo(pinned);
        // ModelData 委托同一来源：远程 overlay 守卫比较的就是这个瞬间。
        assertThat(ModelData.generatedAt()).isEqualTo(pinned);
    }

    @Test
    void nonCarriedShardsAreAbsentFromTheClasspath() {
        // manifest 全量记录 41 文件，本仓只携 17；其余 25 家（w0/R5）必须缺席。
        for (var absent : List.of("amazon-bedrock.json", "azure.json", "vercel-ai-gateway.json",
                "google-vertex.json", "openai-codex.json", "radius.json")) {
            assertThat(BuiltinModelManifestTest.class.getResourceAsStream("/catalog/" + absent))
                .as("non-mainstream shard must not be bundled: %s", absent).isNull();
        }
        // 义务与锚点随包携带。
        assertThat(BuiltinModelManifestTest.class.getResourceAsStream("/catalog/ATTRIBUTION.txt"))
            .isNotNull();
    }

    private static String sha256(String resource) throws IOException, NoSuchAlgorithmException {
        var digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = BuiltinModelManifestTest.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            in.transferTo(new java.io.OutputStream() {
                @Override
                public void write(int b) {
                    digest.update((byte) b);
                }

                @Override
                public void write(byte[] b, int off, int len) {
                    digest.update(b, off, len);
                }
            });
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
