package com.pijava.coding.agent.core;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.util.function.Function;

/**
 * File-based settings storage (Phase 3 design §12.3).
 *
 * <p>Global path: {@code ~/.pi-java/agent/settings.json} (overridable with
 * the {@code PI_AGENT_DIR} environment variable). Project path:
 * {@code <cwd>/.pi-java/settings.json}. Writes are atomic (temp file +
 * move). Cross-process {@link java.nio.channels.FileLock} → Phase 4.</p>
 */
public final class FileSettingsStorage implements SettingsStorage {

    private final Path globalPath;
    private final Path projectPath;
    private final Path projectDir;

    /**
     * Create storage using the default agent directory and the current working
     * directory as the project directory.
     */
    public FileSettingsStorage() {
        this(defaultAgentDir(), Path.of(System.getProperty("user.dir")));
    }

    /**
     * Create storage rooted at the given agent and project directories.
     *
     * @param agentDir   directory holding the global {@code settings.json}
     * @param projectDir project directory holding {@code .pi-java/settings.json}
     */
    public FileSettingsStorage(Path agentDir, Path projectDir) {
        this.projectDir = projectDir;
        this.globalPath = agentDir.resolve("settings.json");
        this.projectPath = projectDir.resolve(".pi-java").resolve("settings.json");
    }

    /**
     * agent 目录的可注入属性（L6 W4，docs/26 §5.2 裁决 (b)）。
     *
     * <p>评测 harness 需要把 agent 目录指到隔离临时根，但 {@code System.getenv} 不可写，
     * 而改环境变量会把整机进程拖进串行。故在环境变量之后加一层属性覆盖：属性存在
     * 即优先，缺省行为不变。pi 用 {@code PI_CODING_AGENT_DIR} 环境变量，
     * 这是 Java 独有的第三条来源（登记 D231）。</p>
     */
    public static final String AGENT_DIR_PROPERTY = "pi.java.agentDir";

    /** 全局 agent 配置根（skills/extensions/sessions 均按此定位）。 */
    public static Path defaultAgentDir() {
        // 属性覆盖优先（仅评测/in-proc 夹具设置；生产不设）。
        var propertyDir = System.getProperty(AGENT_DIR_PROPERTY);
        if (propertyDir != null && !propertyDir.isBlank()) {
            return Path.of(propertyDir);
        }
        // 对齐 pi 的构造规则 ${APP_NAME.toUpperCase()}_CODING_AGENT_DIR（config.ts:495）。
        var envDir = System.getenv("PI_JAVA_CODING_AGENT_DIR");
        if (envDir != null && !envDir.isBlank()) {
            return Path.of(envDir);
        }
        return Path.of(System.getProperty("user.home"), ".pi-java", "agent");
    }

    /** The global settings file path (for diagnostics). */
    public Path globalPath() {
        return globalPath;
    }

    /** 批 24（docs/35）：项目目录（MCP 配置与信任判定的消费同源——批 23 同根纪律）。 */
    public Path projectDir() {
        return projectDir;
    }

    /** The project settings file path (for diagnostics). */
    public Path projectPath() {
        return projectPath;
    }

    @Override
    public Settings readGlobal() {
        return read(globalPath);
    }

    @Override
    public Settings readProject() {
        return read(projectPath);
    }

    @Override
    public void writeGlobal(Settings settings) {
        write(globalPath, settings);
    }

    @Override
    public void writeProject(Settings settings) {
        write(projectPath, settings);
    }

    @Override
    public void withLock(SettingsScope scope, Function<String, String> fn) {
        var path = scope instanceof SettingsScope.Global
            ? globalPath : projectPath;
        withFileLock(path, () -> {
            try {
                String current = Files.exists(path) ? Files.readString(path) : "{}";
                var updated = fn.apply(current);
                if (updated != null) {
                    // pi writeFileSync 对位：文件不存在时创建（批 19 写回链首写场景）。
                    Files.writeString(path, updated,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                }
            } catch (IOException e) {
                throw new SettingsStorageException(
                    "Cannot read-modify-write settings: " + path, e);
            }
        });
    }

    private Settings read(Path path) {
        if (!Files.exists(path)) {
            return new Settings();
        }
        try {
            return Json.mapper().readValue(path.toFile(), Settings.class);
        } catch (IOException e) {
            throw new SettingsStorageException(
                "Cannot read settings: " + path, e);
        }
    }

    private void write(Path path, Settings settings) {
        withFileLock(path, () -> writeUnlocked(path, settings));
    }

    private void writeUnlocked(Path path, Settings settings) {
        try {
            var parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            var temp = path.resolveSibling(path.getFileName() + ".tmp");
            Json.mapper().writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), settings);
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new SettingsStorageException(
                "Cannot write settings: " + path, e);
        }
    }

    /**
     * Run an action under an exclusive lock (retry 10×20ms). A sidecar
     * {@code .lock} file is used so the settings file itself is never held
     * open while an atomic rename happens (Windows-friendly).
     */
    private static void withFileLock(Path target, Runnable action) {
        var lockPath = target.resolveSibling(target.getFileName() + ".lock");
        try {
            var parent = lockPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new SettingsStorageException(
                "Cannot create settings directory: " + target, e);
        }
        for (int attempt = 0; ; attempt++) {
            try (var channel = FileChannel.open(
                    lockPath, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                action.run();
                return;
            } catch (OverlappingFileLockException e) {
                if (attempt >= 9) {
                    throw new SettingsStorageException(
                        "Could not acquire settings lock: " + target, e);
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new SettingsStorageException(
                        "Interrupted while waiting for settings lock: " + target, ie);
                }
            } catch (IOException e) {
                throw new SettingsStorageException(
                    "Could not lock settings: " + target, e);
            }
        }
    }
}
