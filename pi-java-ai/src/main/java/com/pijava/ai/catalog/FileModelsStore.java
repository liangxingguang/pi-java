package com.pijava.ai.catalog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * {@link ModelsStore} 的文件系统实现（每 provider 一个 JSON 文件）。
 *
 * <p>持久化经 {@link CatalogModel} DTO（{@code ModelInfo} 不可直接 round-trip）；
 * ETag 原样存储（含引号）。默认目录 {@code ~/.pi-java/agent/catalogs/}。</p>
 */
public final class FileModelsStore implements ModelsStore {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path dir;

    /** @param dir 缓存文件目录 */
    public FileModelsStore(Path dir) {
        this.dir = dir;
    }

    /** 默认缓存目录。 */
    public static Path defaultDir() {
        return Path.of(System.getProperty("user.home"), ".pi-java", "agent", "catalogs");
    }

    @Override
    public Optional<ModelsStoreEntry> read(String providerId) {
        Path file = fileFor(providerId);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            JsonNode node = JSON.readTree(file.toFile());
            var models = new ArrayList<ModelInfo>();
            if (node.has("models")) {
                for (var m : node.get("models")) {
                    models.add(JSON.treeToValue(m, CatalogModel.class).toModelInfo());
                }
            }
            return Optional.of(new ModelsStoreEntry(
                List.copyOf(models),
                instant(node, "lastModified"),
                instant(node, "checkedAt"),
                node.hasNonNull("etag") ? node.get("etag").asText() : null));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public void write(String providerId, ModelsStoreEntry entry) {
        try {
            Files.createDirectories(dir);
            ObjectNode node = JSON.createObjectNode();
            var arr = node.putArray("models");
            for (var model : entry.models()) {
                arr.add(JSON.valueToTree(CatalogModel.fromModelInfo(model)));
            }
            putInstant(node, "lastModified", entry.lastModified());
            putInstant(node, "checkedAt", entry.checkedAt());
            if (entry.etag() != null) {
                node.put("etag", entry.etag());
            }
            JSON.writeValue(fileFor(providerId).toFile(), node);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void delete(String providerId) {
        try {
            Files.deleteIfExists(fileFor(providerId));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path fileFor(String providerId) {
        return dir.resolve(providerId.replaceAll("[^a-zA-Z0-9._-]", "_") + ".json");
    }

    private static Instant instant(JsonNode node, String field) {
        return node.hasNonNull(field) ? Instant.ofEpochMilli(node.get(field).asLong()) : null;
    }

    private static void putInstant(ObjectNode node, String field, Instant value) {
        if (value != null) {
            node.put(field, value.toEpochMilli());
        }
    }
}
