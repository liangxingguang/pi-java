package com.pijava.ai.catalog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-8: FileModelsStore — 读写删 round-trip、ETag 含引号原样存储。
 */
class FileModelsStoreTest {

    @TempDir
    Path tmp;

    private static final ModelInfo MODEL = new ModelInfo(
        ModelId.of("anthropic", "claude-fable-5"),
        "Claude Fable 5",
        Set.of(ModelCapability.TEXT, ModelCapability.THINKING),
        200_000, 64_000, false,
        new PricingInfo(5.0, 15.0));

    @Test
    void writeReadRoundTripsModelInfo() {
        var store = new FileModelsStore(tmp);
        store.write("anthropic", new ModelsStoreEntry(
            List.of(MODEL), Instant.parse("2026-08-19T00:00:00Z"),
            Instant.parse("2026-08-19T01:00:00Z"), "\"abc123\""));

        var entry = store.read("anthropic").orElseThrow();
        assertThat(entry.models()).hasSize(1);
        var model = entry.models().get(0);
        assertThat(model.id().provider()).isEqualTo("anthropic");
        assertThat(model.id().modelName()).isEqualTo("claude-fable-5");
        assertThat(model.displayName()).isEqualTo("Claude Fable 5");
        assertThat(model.capabilities()).contains(ModelCapability.THINKING);
        assertThat(model.maxInputTokens()).isEqualTo(200_000);
        assertThat(model.pricing().inputPrice()).isEqualTo(5.0);
        // ETag 原样存储（含引号）
        assertThat(entry.etag()).isEqualTo("\"abc123\"");
        assertThat(entry.lastModified()).isEqualTo(Instant.parse("2026-08-19T00:00:00Z"));
    }

    @Test
    void missingFileReturnsEmpty() {
        assertThat(new FileModelsStore(tmp).read("nope")).isEmpty();
    }

    @Test
    void deleteRemovesEntry() {
        var store = new FileModelsStore(tmp);
        store.write("p", ModelsStoreEntry.of(List.of(MODEL)));
        assertThat(store.read("p")).isPresent();
        store.delete("p");
        assertThat(store.read("p")).isEmpty();
    }

    @Test
    void persistSurvivesNewInstance() {
        new FileModelsStore(tmp).write("q", ModelsStoreEntry.of(List.of(MODEL)));
        var fresh = new FileModelsStore(tmp).read("q").orElseThrow();
        assertThat(fresh.models()).hasSize(1);
    }
}
