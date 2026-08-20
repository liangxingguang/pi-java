package com.pijava.ai.auth;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * File-based credential store backed by {@code ~/.pi-java/auth.json}.
 *
 * <p>Uses {@link FileChannel#lock()} for cross-process concurrency safety.
 * The file stores a flat JSON object: {@code {"provider": "api-key-value", ...}}.</p>
 */
public final class FileCredentialStore implements CredentialStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path STORE_PATH = Path.of(
            System.getProperty("user.home"), ".pi-java", "auth.json");

    private final Path path;

    /** Create a store backed by the default {@code ~/.pi-java/auth.json} file. */
    public FileCredentialStore() {
        this(STORE_PATH);
    }

    /**
     * Create a store backed by the given file.
     *
     * @param path the credential file to read from and write to
     */
    public FileCredentialStore(Path path) {
        this.path = path;
    }

    @Override
    public Optional<String> resolveApiKey(String provider) {
        var all = readAll();
        return Optional.ofNullable(all.get(provider))
                .filter(v -> !v.isBlank());
    }

    /** 按 profile 解析（key 形如 {@code provider::profile}，P6-18）。 */
    public Optional<String> resolveApiKey(String provider, String profile) {
        var all = readAll();
        return Optional.ofNullable(all.get(profileKey(provider, profile)))
                .filter(v -> !v.isBlank());
    }

    /** 存储指定 profile 的 API key。 */
    public void storeApiKey(String provider, String profile, String apiKey) {
        var all = readAll();
        all.put(profileKey(provider, profile), apiKey);
        writeAll(all);
    }

    /** 删除指定 profile 的 API key。 */
    public void deleteApiKey(String provider, String profile) {
        var all = readAll();
        all.remove(profileKey(provider, profile));
        writeAll(all);
    }

    private static String profileKey(String provider, String profile) {
        return provider + "::" + profile;
    }

    @Override
    public void storeApiKey(String provider, String apiKey) {
        var all = readAll();
        all.put(provider, apiKey);
        writeAll(all);
    }

    @Override
    public void deleteApiKey(String provider) {
        var all = readAll();
        all.remove(provider);
        writeAll(all);
    }

    // ── File I/O ──────────────────────────────────────────────

    private Map<String, String> readAll() {
        try {
            var parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (!Files.exists(path)) {
                return new HashMap<>();
            }
            return MAPPER.readValue(path.toFile(),
                    new TypeReference<HashMap<String, String>>() {});
        } catch (IOException e) {
            return new HashMap<>();
        }
    }

    private void writeAll(Map<String, String> data) {
        try {
            var parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (var channel = FileChannel.open(path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
                 FileLock ignored = channel.lock()) {
                var json = MAPPER.writeValueAsBytes(data);
                channel.write(ByteBuffer.wrap(json));
            }
        } catch (IOException e) {
            throw new UnsupportedOperationException(
                    "Failed to write auth file: " + path, e);
        }
    }
}
