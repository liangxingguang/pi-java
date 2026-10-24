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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * File-based credential store backed by {@code ~/.pi-java/auth.json}.
 *
 * <p>Uses {@link FileChannel#lock()} for cross-process concurrency safety.
 * The file stores a flat JSON object: {@code {"provider": "api-key-value", ...}}.</p>
 *
 * <p>批 21（docs/32 T2，E1-3）：读-改-写全部收进同一把文件锁（pi
 * {@code auth-storage.ts} 的 {@code withLock(fn)} 对位——读当前 → fn → 同锁写回）。
 * 修真前 {@code storeApiKey} 是先无锁读、再锁内写，跨进程并发会丢更新。pi 的
 * revision 读缓存/stale 锁检测/共享读态不移植（java 无进程内读缓存，每次盘读
 * 即新鲜——窄差 D131）。</p>
 */
public final class FileCredentialStore implements CredentialStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path path;

    /** Create a store backed by the default {@code ~/.pi-java/auth.json} file. */
    public FileCredentialStore() {
        this(defaultStorePath());
    }

    /**
     * 默认存储路径——构造时现读 {@code user.home}（批 14 闭环修真：原
     * {@code static final STORE_PATH} 在类初始化时冻结 home，测试套件里任一
     * 临时 home 夹具先触发类初始化就会把全 JVM 的凭证路径钉死到临时目录，
     * {@code ModelsJsonOverrideWireTest} 的套件性红〔批 13 误记为凭据内容问题〕
     * 真因即此；生产 user.home 不变，行为无差）。
     */
    private static Path defaultStorePath() {
        return Path.of(System.getProperty("user.home"), ".pi-java", "auth.json");
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
        return Optional.ofNullable(readAll().get(provider))
                .filter(v -> !v.isBlank());
    }

    /** 按 profile 解析（key 形如 {@code provider::profile}，P6-18）。 */
    public Optional<String> resolveApiKey(String provider, String profile) {
        return Optional.ofNullable(readAll().get(profileKey(provider, profile)))
                .filter(v -> !v.isBlank());
    }

    /** 存储指定 profile 的 API key。 */
    public void storeApiKey(String provider, String profile, String apiKey) {
        mutate(all -> all.put(profileKey(provider, profile), apiKey));
    }

    /** 删除指定 profile 的 API key。 */
    public void deleteApiKey(String provider, String profile) {
        mutate(all -> all.remove(profileKey(provider, profile)));
    }

    private static String profileKey(String provider, String profile) {
        return provider + "::" + profile;
    }

    @Override
    public void storeApiKey(String provider, String apiKey) {
        mutate(all -> all.put(provider, apiKey));
    }

    @Override
    public void deleteApiKey(String provider) {
        mutate(all -> all.remove(provider));
    }

    // ── File I/O ──────────────────────────────────────────────

    /** 锁内读全量（pi {@code withLock} 的读臂——读也过锁，杜绝撕裂读）。 */
    private Map<String, String> readAll() {
        try {
            return withLockedFile(FileCredentialStore::readFrom);
        } catch (UnsupportedOperationException e) {
            // 旧 readAll 语义：IO 失败 ⇒ 空表（pi parseStorageData 容忍同）。
            return new HashMap<>();
        }
    }

    /** 锁内 RMW（pi {@code withLock(fn)}：读当前 → 改 → 同锁写回）。 */
    private void mutate(java.util.function.Consumer<Map<String, String>> mutation) {
        withLockedFile(channel -> {
            var all = readFrom(channel);
            mutation.accept(all);
            writeTo(channel, all);
            return null;
        });
    }

    /**
     * Per-path 的 JVM 内锁（{@link OAuthCredentialStore} 同构先例）：
     * {@link FileLock} 代表整个 JVM 持有，同 JVM 第二个线程对同一区域加锁会抛
     * {@link java.nio.channels.OverlappingFileLockException}（而非阻塞），故先经
     * 此锁串行化同 JVM 线程；文件锁只负责跨进程串行。
     */
    private static final Map<Path, ReentrantLock> VM_LOCKS = new ConcurrentHashMap<>();

    /** 允许 IOException 的通道 I/O body。 */
    @FunctionalInterface
    private interface ChannelIo<T> {
        T apply(FileChannel channel) throws IOException;
    }

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
            // pi parseStorageData/readFileSync 失败 ⇒ 空数据容忍（读臂语义同旧
            // readAll 的 IOException ⇒ 空表）；写失败仍硬抛（writeAll 旧语义）。
            throw new UnsupportedOperationException(
                "Failed to update auth file: " + path, e);
        } finally {
            vmLock.unlock();
        }
    }

    /** 在已加锁的通道上从头部反序列化全量 map（空文件/坏 JSON ⇒ 空 map，pi 容忍同）。 */
    private static Map<String, String> readFrom(FileChannel channel) throws IOException {
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
        try {
            return MAPPER.readValue(bytes, new TypeReference<HashMap<String, String>>() {});
        } catch (IOException | RuntimeException e) {
            return new HashMap<>();
        }
    }

    /** 在已加锁的通道上截断并全量覆写。 */
    private static void writeTo(FileChannel channel, Map<String, String> data)
            throws IOException {
        channel.truncate(0);
        channel.position(0);
        channel.write(ByteBuffer.wrap(MAPPER.writeValueAsBytes(data)));
        channel.force(true);
    }
}
