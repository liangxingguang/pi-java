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
 * OAuth 凭证存储，文件 {@code ~/.pi-java/auth-oauth.json}（P6-17）。
 *
 * <p>结构为 {@code {"provider": {"accessToken":..., "refreshToken":..., "expiresAtEpochSec":..., "baseUrl":...}}}，
 * 与 {@link FileCredentialStore}（API key 平铺）并列，互不影响。文件锁保证跨进程安全。</p>
 */
public final class OAuthCredentialStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
            false);
    private static final Path STORE_PATH = Path.of(
            System.getProperty("user.home"), ".pi-java", "auth-oauth.json");

    private final Path path;

    /** 使用默认 {@code ~/.pi-java/auth-oauth.json}。 */
    public OAuthCredentialStore() {
        this(STORE_PATH);
    }

    /** 使用给定文件（测试注入）。 */
    public OAuthCredentialStore(Path path) {
        this.path = path;
    }

    /** 读取 provider 的 OAuth 凭证；无则返回 empty。 */
    public Optional<OAuthCredential> resolve(String provider) {
        return Optional.ofNullable(readAll().get(provider));
    }

    /** 保存 provider 的 OAuth 凭证。 */
    public void store(String provider, OAuthCredential credential) {
        var all = readAll();
        all.put(provider, credential);
        writeAll(all);
    }

    /** 删除 provider 的 OAuth 凭证。 */
    public void delete(String provider) {
        var all = readAll();
        all.remove(provider);
        writeAll(all);
    }

    // ── File I/O ──────────────────────────────────────────────

    private Map<String, OAuthCredential> readAll() {
        try {
            var parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (!Files.exists(path)) {
                return new HashMap<>();
            }
            return MAPPER.readValue(path.toFile(),
                    new TypeReference<HashMap<String, OAuthCredential>>() {});
        } catch (IOException e) {
            return new HashMap<>();
        }
    }

    private void writeAll(Map<String, OAuthCredential> data) {
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
                    "Failed to write OAuth auth file: " + path, e);
        }
    }
}
