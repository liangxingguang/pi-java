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
 * 多 profile 认证：记录每个 provider 当前激活的 profile 名（P6-18）。
 *
 * <p>文件 {@code ~/.pi-java/auth-profiles.json}，结构 {@code {"provider": "profile"}}。
 * profile 具体凭证存于 {@link FileCredentialStore}（key 形如 {@code provider::profile}）
 * 或环境变量（形如 {@code ANTHROPIC_API_KEY_<PROFILE>}）。</p>
 */
public final class AuthProfileManager {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path STORE_PATH = Path.of(
            System.getProperty("user.home"), ".pi-java", "auth-profiles.json");

    private final Path path;

    /** 使用默认 {@code ~/.pi-java/auth-profiles.json}。 */
    public AuthProfileManager() {
        this(STORE_PATH);
    }

    /** 使用给定文件（测试注入）。 */
    public AuthProfileManager(Path path) {
        this.path = path;
    }

    /** provider 当前激活的 profile；未设置返回 empty。 */
    public Optional<String> activeProfile(String provider) {
        return Optional.ofNullable(readAll().get(provider))
            .filter(v -> !v.isBlank());
    }

    /** 设置 provider 的激活 profile。 */
    public void setActiveProfile(String provider, String profile) {
        var all = readAll();
        all.put(provider, profile);
        writeAll(all);
    }

    /** 清除 provider 的激活 profile（回到默认凭证）。 */
    public void clearActiveProfile(String provider) {
        var all = readAll();
        all.remove(provider);
        writeAll(all);
    }

    /** 所有 provider → profile 映射（不可变）。 */
    public Map<String, String> all() {
        return Map.copyOf(readAll());
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
                channel.write(ByteBuffer.wrap(MAPPER.writeValueAsBytes(data)));
            }
        } catch (IOException e) {
            throw new UnsupportedOperationException(
                    "Failed to write auth profiles file: " + path, e);
        }
    }
}
