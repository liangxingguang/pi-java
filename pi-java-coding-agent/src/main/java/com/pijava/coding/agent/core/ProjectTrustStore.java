package com.pijava.coding.agent.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 批 23（docs/34 T3，裁决②）：pi {@code ProjectTrustStore}（trust-manager.ts:210-246）
 * 对位——信任决策存 {@code <agentDir>/trust.json}（替换旧 {@code TrustManager}
 * 标记文件，pi 现锚点唯一形态）。
 *
 * <p>语义逐字：</p>
 * <ul>
 *   <li>键＝规范化绝对路径（绝对化＋{@code normalize}）；</li>
 *   <li>读＝<b>最近祖先继承</b>（从 cwd 逐级向父目录找第一个 true/false 键，
 *       {@code findNearestTrustEntry}，trust-manager.ts:45-59）；</li>
 *   <li>写＝{@code setMany}，{@code decision == null} 语义是<b>删除该键</b>；</li>
 *   <li>线格式：键排序＋2 空格缩进＋尾换行（writeTrustFile，:126-136）；</li>
 *   <li>读校验：非对象/值非 true-false-null ⇒ 抛（{@code Invalid trust store ...}）；</li>
 *   <li>RMW 全在 {@code trust.json.lock} 文件锁内（pi proper-lockfile 对位；
 *       JVM 内互斥由静态监视器兜底——{@link OverlappingFileLockException} 防护）。</li>
 * </ul>
 */
public final class ProjectTrustStore {

    /** 一条写入（pi {@code ProjectTrustUpdate}；{@code decision == null} ⇒ 删键）。 */
    public record Update(String path, Boolean decision) {}

    /** 一条命中（pi {@code ProjectTrustStoreEntry}——携带命中的是哪一级路径）。 */
    public record TrustEntry(String path, boolean decision) {}

    /** JVM 内互斥（FileLock 只跨进程；同 JVM 重入会撞 OverlappingFileLockException）。 */
    private static final Object JVM_LOCK = new Object();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path trustDir;
    private final Path trustPath;

    /** @param agentDir 全局 agent 目录（{@code trust.json} 落其下，pi :214）。 */
    public ProjectTrustStore(Path agentDir) {
        this.trustDir = agentDir.toAbsolutePath().normalize();
        this.trustPath = trustDir.resolve("trust.json");
    }

    /** 从 settings 存储面取 agentDir 建仓（仅 {@link FileSettingsStorage} 携带目录）。 */
    public static ProjectTrustStore forSettings(SettingsManager settings) {
        if (settings.storage() instanceof FileSettingsStorage files) {
            var globalPath = files.globalPath();
            var parent = globalPath == null ? null : globalPath.getParent();
            if (parent != null) {
                return new ProjectTrustStore(parent);
            }
        }
        return new ProjectTrustStore(FileSettingsStorage.defaultAgentDir());
    }

    /** 测试/诊断用：存储文件路径。 */
    public Path trustPath() {
        return trustPath;
    }

    /** pi {@code get(cwd)}：最近祖先的决策；无条目 ⇒ {@code null}。 */
    public Boolean get(Path cwd) {
        var entry = getEntry(cwd);
        return entry == null ? null : entry.decision();
    }

    /** pi {@code getEntry(cwd)}（:220-225）：锁内读＋最近祖先匹配。 */
    public TrustEntry getEntry(Path cwd) {
        synchronized (JVM_LOCK) {
            return locked(() -> {
                var data = readTrustFile();
                return findNearestTrustEntry(data, cwd);
            });
        }
    }

    /** pi {@code set(cwd, decision)}（:227-229）。 */
    public void set(Path cwd, Boolean decision) {
        setMany(List.of(new Update(normalizeCwd(cwd), decision)));
    }

    /** pi {@code setMany}（:231-245）：锁内 RMW；null 删键；排序写。 */
    public void setMany(List<Update> decisions) {
        synchronized (JVM_LOCK) {
            locked(() -> {
                var data = readTrustFile();
                for (var update : decisions) {
                    var key = normalizeCwd(Path.of(update.path()));
                    if (update.decision() == null) {
                        data.remove(key);
                    } else {
                        data.put(key, update.decision());
                    }
                }
                writeTrustFile(data);
                return null;
            });
        }
    }

    /** pi {@code normalizeCwd}（:41-43）：绝对化＋规范化（canonicalizePath 对位）。 */
    static String normalizeCwd(Path cwd) {
        return cwd.toAbsolutePath().normalize().toString();
    }

    /** pi {@code findNearestTrustEntry}（:45-59 逐字）：逐级向父，命中 true/false 即返。 */
    private static TrustEntry findNearestTrustEntry(Map<String, Boolean> data, Path cwd) {
        var current = normalizeCwd(cwd);
        while (true) {
            var value = data.get(current);
            if (value != null) {
                return new TrustEntry(current, value);
            }
            var parent = Path.of(current).getParent();
            if (parent == null) {
                return null;
            }
            current = parent.toString();
        }
    }

    /** pi {@code readTrustFile}（:99-124）：缺席 ⇒ 空；BOM 剥；逐值校验。 */
    private Map<String, Boolean> readTrustFile() {
        if (!Files.exists(trustPath)) {
            return new LinkedHashMap<>();
        }
        final JsonNode parsed;
        try {
            var text = Files.readString(trustPath, StandardCharsets.UTF_8);
            if (!text.isEmpty() && text.charAt(0) == '﻿') {
                text = text.substring(1);
            }
            parsed = MAPPER.readTree(text);
        } catch (IOException e) {
            throw new IllegalStateException(
                "Failed to read trust store " + trustPath + ": " + e.getMessage(), e);
        }
        if (parsed == null || !parsed.isObject()) {
            throw new IllegalStateException(
                "Invalid trust store " + trustPath + ": expected an object");
        }
        var data = new LinkedHashMap<String, Boolean>();
        parsed.properties().forEach(entry -> {
            var value = entry.getValue();
            if (!value.isBoolean() && !value.isNull()) {
                throw new IllegalStateException(
                    "Invalid trust store " + trustPath + ": value for "
                        + MAPPER.valueToTree(entry.getKey()) + " must be true, false, or null");
            }
            data.put(entry.getKey(), value.isBoolean() ? value.booleanValue() : null);
        });
        return data;
    }

    /** pi {@code writeTrustFile}（:126-136）：键排序＋2 空格缩进＋尾换行；null 键不写。
     *  逐字节对位 {@code JSON.stringify(sorted, null, 2) + "\n"}——手写输出器
     * （Jackson 默认 pretty-printer 的冒号间距是 {@code " : "}，与 pi 的
     *  {@code ": "} 不同）。 */
    private void writeTrustFile(Map<String, Boolean> data) {
        var sorted = new TreeMap<>(data);
        sorted.values().removeIf(java.util.Objects::isNull);
        try {
            var json = new StringBuilder("{");
            int index = 0;
            for (var entry : sorted.entrySet()) {
                json.append(index++ == 0 ? "\n" : ",\n").append("  ")
                    .append(MAPPER.writeValueAsString(entry.getKey()))
                    .append(": ").append(entry.getValue());
            }
            json.append(sorted.isEmpty() ? "}" : "\n}").append("\n");
            Files.createDirectories(trustDir);
            Files.writeString(trustPath, json.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** pi {@code withTrustFileLock}（:173-177）：整段读写跑在 trust.json.lock 文件锁内。 */
    private <T> T locked(java.util.function.Supplier<T> body) {
        try {
            Files.createDirectories(trustDir);
            try (var channel = FileChannel.open(lockPath(),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    var lock = lock(channel)) {
                return body.get();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private java.nio.channels.FileLock lock(FileChannel channel) throws IOException {
        try {
            return channel.lock(); // 跨进程阻塞等锁（pi ELOCKED 忙等重试对位）
        } catch (OverlappingFileLockException e) {
            // 同 JVM 并发：JVM_LOCK 已串行化，理论上不可达；防御性重试一次。
            return channel.lock();
        }
    }

    private Path lockPath() {
        return trustPath.resolveSibling(trustPath.getFileName() + ".lock");
    }

    /** 读全表（测试/诊断用；锁外快照语义不保证——仅夹具用）。 */
    Map<String, Boolean> snapshotForTests() {
        return new LinkedHashMap<>(readTrustFile());
    }
}
