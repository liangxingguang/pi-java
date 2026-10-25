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
 * coding-agent 单进程，方法 synchronized 即足够（原 docs/70 R6）。读面对
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
     * Default agent directory: {@code $PI_JAVA_CODING_AGENT_DIR} or
     * {@code ~/.pi-java/agent} —— 与
     * {@link com.pijava.ai.provider.ModelsJsonConfig#defaultPath()} 同一约定。
     */
    public static Path defaultDir() {
        var envDir = System.getenv("PI_JAVA_CODING_AGENT_DIR");
        return envDir != null && !envDir.isBlank()
            ? Path.of(envDir)
            : Path.of(System.getProperty("user.home"), ".pi-java", "agent");
    }

    /** Default file: {@code <agent dir>/models-store.json}（pi 也把它放在 agent 目录）。 */
    public static Path defaultFile() {
        return defaultDir().resolve(FILE_NAME);
    }

    /** Store at the default agent-directory location. */
    public static FileModelsStore defaultStore() {
        return new FileModelsStore(defaultDir());
    }

    @Override
    public synchronized Optional<ModelsStoreEntry> read(String providerId) {
        // 批 21（T2 同族）：读也过锁，杜绝撕裂读（pi FileModelsStore 的
        // FileAuthStorageBackend 对位）。
        return Optional.ofNullable(withLockedFile(channel -> loadFrom(channel).get(providerId)))
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
        withLockedFile(channel -> {
            var map = loadFrom(channel);
            var wires = new ArrayList<RemoteModelWire>();
            for (ModelInfo model : entry.models()) {
                wires.add(RemoteModelWire.fromModelInfo(model));
            }
            map.put(providerId, new StoredEntry(
                List.copyOf(wires),
                entry.lastModified() != null ? entry.lastModified().toEpochMilli() : null,
                entry.checkedAt() != null ? entry.checkedAt().toEpochMilli() : null,
                entry.etag()));
            writeTo(channel, map);
            return null;
        });
    }

    @Override
    public synchronized void delete(String providerId) {
        withLockedFile(channel -> {
            var map = loadFrom(channel);
            if (map.remove(providerId) != null) {
                writeTo(channel, map);
            }
            return null;
        });
    }

    /** 允许 IOException 的通道 I/O body。 */
    @FunctionalInterface
    private interface ChannelIo<T> {
        T apply(java.nio.channels.FileChannel channel) throws IOException;
    }

    /**
     * 批 21（T2 同族，E1-4）：打开 CREATE/READ/WRITE 通道并持有跨进程文件锁
     * 运行 body（pi FileAuthStorageBackend 对位；同 JVM 线程由方法级
     * synchronized 串行）。
     */
    private <T> T withLockedFile(ChannelIo<T> body) {
        try {
            var parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (var channel = java.nio.channels.FileChannel.open(file,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.READ,
                    java.nio.file.StandardOpenOption.WRITE);
                 java.nio.channels.FileLock lock = channel.lock()) {
                return body.apply(channel);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 在已加锁的通道上从头部读全量 map（空文件/坏 JSON ⇒ 空表——旧容忍语义保留）。 */
    private Map<String, StoredEntry> loadFrom(java.nio.channels.FileChannel channel)
            throws IOException {
        if (channel.size() == 0) {
            return new LinkedHashMap<>();
        }
        channel.position(0);
        var bytes = new byte[(int) channel.size()];
        var buffer = java.nio.ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                break;
            }
        }
        try {
            var type = JSON.getTypeFactory()
                .constructMapType(LinkedHashMap.class, String.class, StoredEntry.class);
            Map<String, StoredEntry> map = JSON.readValue(bytes, type);
            return map != null ? map : new LinkedHashMap<>();
        } catch (IOException | RuntimeException e) {
            // Corrupt cache: treat as empty; next write recreates the file.
            return new LinkedHashMap<>();
        }
    }

    /** 在已加锁的通道上截断并全量覆写。 */
    private static void writeTo(java.nio.channels.FileChannel channel,
                                Map<String, StoredEntry> map) throws IOException {
        channel.truncate(0);
        channel.position(0);
        channel.write(java.nio.ByteBuffer.wrap(JSON.writeValueAsBytes(map)));
        channel.force(true);
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
