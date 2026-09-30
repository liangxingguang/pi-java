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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

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
    private static final TypeReference<HashMap<String, OAuthCredential>> MAP_TYPE =
        new TypeReference<>() {};
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

    /**
     * 在<b>独占文件锁</b>内对 provider 的凭证「读当前 → 应用 mutation → 回写」，
     * 返回 mutation 的结果（provider 不存在时返回 {@code null}，不调用 mutation）。
     *
     * <p>对齐 pi {@code CredentialStore.modify}（包 B1 R2）：锁覆盖整个读-改-写，
     * 因而可在 mutation 内安全执行「复核临期 → 网络刷新」，保证跨并发请求/进程全局
     * 单次刷新。mutation 抛出的非受检异常（如 {@link OAuthRefreshException}）原样穿透，
     * 不会被包装。</p>
     */
    public OAuthCredential modify(String provider,
                                  Function<OAuthCredential, OAuthCredential> mutation) {
        return withLockedFile(channel -> {
            var all = readFrom(channel);
            var current = all.get(provider);
            if (current == null) {
                return null;
            }
            var updated = mutation.apply(current);
            // 同一引用 ⇒ 无变化（常见：凭证仍新鲜）⇒ 跳过覆写。
            if (updated == current) {
                return updated;
            }
            if (updated != null) {
                all.put(provider, updated);
            } else {
                all.remove(provider);
            }
            writeTo(channel, all);
            return updated;
        });
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
            return MAPPER.readValue(path.toFile(), MAP_TYPE);
        } catch (IOException e) {
            return new HashMap<>();
        }
    }

    /** 允许 IOException 的通道 I/O body。 */
    @FunctionalInterface
    private interface ChannelIo<T> {
        T apply(FileChannel channel) throws IOException;
    }

    /**
     * Per-path 的 JVM 内锁。{@link FileLock} 代表整个 JVM 持有，同 JVM 第二个线程再对
     * 同一区域加锁会抛 {@link java.nio.channels.OverlappingFileLockException}（而非阻塞），
     * 故先经此锁串行化同 JVM 线程；文件锁只负责跨进程串行。
     */
    private static final Map<Path, ReentrantLock> VM_LOCKS = new ConcurrentHashMap<>();

    /** 打开 CREATE/READ/WRITE 通道：先取 JVM 内锁，再持有跨进程文件锁运行 body。 */
    private <T> T withLockedFile(ChannelIo<T> body) {
        var vmLock = VM_LOCKS.computeIfAbsent(
            path.toAbsolutePath().normalize(), key -> new ReentrantLock());
        vmLock.lock();
        try {
            var parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (var channel = FileChannel.open(path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE);
                 FileLock lock = channel.lock()) {
                return body.apply(channel);
            }
        } catch (IOException e) {
            throw new UnsupportedOperationException(
                "Failed to update OAuth auth file: " + path, e);
        } finally {
            vmLock.unlock();
        }
    }

    /** 在已加锁的通道上从头部反序列化全量 map（空文件 ⇒ 空 map）。 */
    private Map<String, OAuthCredential> readFrom(FileChannel channel) throws IOException {
        if (channel.size() == 0) {
            return new HashMap<>();
        }
        channel.position(0);
        // 直接读字节：不可用 Channels.newInputStream —— 它在 Jackson 关闭流时会连带关闭通道。
        var bytes = new byte[(int) channel.size()];
        var buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                break;
            }
        }
        return MAPPER.readValue(bytes, MAP_TYPE);
    }

    /** 在已加锁的通道上截断并全量覆写。 */
    private void writeTo(FileChannel channel, Map<String, OAuthCredential> data)
            throws IOException {
        channel.truncate(0);
        channel.position(0);
        channel.write(ByteBuffer.wrap(MAPPER.writeValueAsBytes(data)));
        channel.force(true);
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
