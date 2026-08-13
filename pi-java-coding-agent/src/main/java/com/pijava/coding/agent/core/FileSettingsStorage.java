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

    public FileSettingsStorage() {
        this(defaultAgentDir(), Path.of(System.getProperty("user.dir")));
    }

    public FileSettingsStorage(Path agentDir, Path projectDir) {
        this.globalPath = agentDir.resolve("settings.json");
        this.projectPath = projectDir.resolve(".pi-java").resolve("settings.json");
    }

    static Path defaultAgentDir() {
        var envDir = System.getenv("PI_AGENT_DIR");
        if (envDir != null && !envDir.isBlank()) {
            return Path.of(envDir);
        }
        return Path.of(System.getProperty("user.home"), ".pi-java", "agent");
    }

    /** The global settings file path (for diagnostics). */
    public Path globalPath() {
        return globalPath;
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
                    Files.writeString(path, updated,
                        StandardOpenOption.TRUNCATE_EXISTING);
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
