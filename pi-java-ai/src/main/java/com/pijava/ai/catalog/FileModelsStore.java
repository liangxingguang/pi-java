package com.pijava.ai.catalog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@link ModelsStore} 的文件实现：单文件 {@code models-store.json}，
 * 形状为 {@code Map<providerId, Entry>}（对齐 pi
 * {@code coding-agent/src/core/models-store.ts}），取代旧的「每 provider
 * 一文件」实现（旧实现无生产调用者）。
 *
 * <p>持久化统一走 pi wire（{@link RemoteModelWire}）；时间戳存 epoch milli。
 * coding-agent 单进程，方法 synchronized 即足够（docs/70 R6）。读面对
 * 缺失/损坏文件一律返回 empty（与旧实现一致），写失败抛出。</p>
 */
public final class FileModelsStore implements ModelsStore {

    /** File name inside the agent directory. */
    public static final String FILE_NAME = "models-store.json";

    private static final ObjectMapper JSON = new ObjectMapper()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** One persisted provider entry (pi wire). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record StoredEntry(
        List<RemoteModelWire> models,
        Long lastModified,
        Long checkedAt,
        String etag) {
    }

    private final Path file;

    /** Store backed by {@code directory/models-store.json}. */
    public FileModelsStore(Path directory) {
        this.file = directory.resolve(FILE_NAME);
    }

    /**
     * Default file: {@code $PI_JAVA_CODING_AGENT_DIR/models-store.json} or
     * {@code ~/.pi-java/agent/models-store.json} —— 与
     * {@link com.pijava.ai.provider.ModelsJsonConfig#defaultPath()} 同一 agent
     * 目录约定（pi 把 models-store 也放在 agent 目录）。
     */
    public static Path defaultFile() {
        var envDir = System.getenv("PI_JAVA_CODING_AGENT_DIR");
        var agentDir = envDir != null && !envDir.isBlank()
            ? Path.of(envDir)
            : Path.of(System.getProperty("user.home"), ".pi-java", "agent");
        return agentDir.resolve(FILE_NAME);
    }

    /** Store at the default agent-directory location ({@link #defaultFile()}). */
    public static FileModelsStore defaultStore() {
        return new FileModelsStore(defaultFile().getParent());
    }

    @Override
    public synchronized Optional<ModelsStoreEntry> read(String providerId) {
        return Optional.ofNullable(loadMap().get(providerId))
            .map(stored -> new ModelsStoreEntry(
                toModelInfos(providerId, stored.models()),
                stored.lastModified() != null
                    ? java.time.Instant.ofEpochMilli(stored.lastModified()) : null,
                stored.checkedAt() != null
                    ? java.time.Instant.ofEpochMilli(stored.checkedAt()) : null,
                stored.etag()));
    }

    @Override
    public synchronized void write(String providerId, ModelsStoreEntry entry) {
        var map = loadMap();
        var wires = new ArrayList<RemoteModelWire>();
        for (ModelInfo model : entry.models()) {
            wires.add(RemoteModelWire.fromModelInfo(model));
        }
        map.put(providerId, new StoredEntry(
            List.copyOf(wires),
            entry.lastModified() != null ? entry.lastModified().toEpochMilli() : null,
            entry.checkedAt() != null ? entry.checkedAt().toEpochMilli() : null,
            entry.etag()));
        save(map);
    }

    @Override
    public synchronized void delete(String providerId) {
        var map = loadMap();
        if (map.remove(providerId) != null) {
            save(map);
        }
    }

    private Map<String, StoredEntry> loadMap() {
        if (!Files.isRegularFile(file)) {
            return new LinkedHashMap<>();
        }
        try {
            var type = JSON.getTypeFactory()
                .constructMapType(LinkedHashMap.class, String.class, StoredEntry.class);
            Map<String, StoredEntry> map = JSON.readValue(file.toFile(), type);
            return map != null ? map : new LinkedHashMap<>();
        } catch (IOException e) {
            // Corrupt cache: treat as empty; next write recreates the file.
            return new LinkedHashMap<>();
        }
    }

    private void save(Map<String, StoredEntry> map) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            JSON.writeValue(file.toFile(), map);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<ModelInfo> toModelInfos(
            String providerId, List<RemoteModelWire> models) {
        var result = new ArrayList<ModelInfo>();
        if (models != null) {
            for (var wire : models) {
                result.add(wire.toModelInfo(providerId));
            }
        }
        return List.copyOf(result);
    }
}
