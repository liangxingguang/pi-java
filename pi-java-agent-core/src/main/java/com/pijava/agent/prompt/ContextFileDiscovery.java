package com.pijava.agent.prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import com.pijava.agent.prompt.SystemPromptOptions.ContextFile;

/**
 * Discovery of project instruction files (AGENTS.md / CLAUDE.md) — pi
 * {@code loadProjectContextFiles}（{@code coding-agent/src/core/resource-loader.ts}）。
 *
 * <p>Order of the returned list: <b>global (agentDir) → farthest ancestor →
 * cwd (nearest)</b>. Each directory contributes at most one file, the first
 * candidate in {@link #CANDIDATES} that is a regular file. Canonical paths
 * de-duplicate files seen at multiple walk positions. Unreadable files are
 * skipped (warning only), matching pi.</p>
 */
public final class ContextFileDiscovery {

    /**
     * pi {@code loadContextFileFromDir} candidates（{@code resource-loader.ts:185}），
     * 顺序即优先级。
     */
    public static final List<String> CANDIDATES = List.of(
        "AGENTS.override.md", "AGENTS.md", "AGENTS.MD",
        "CLAUDE.md", "CLAUDE.MD");

    /** BOM stripped by pi's {@code stripBom}. */
    private static final char BOM = '﻿';

    private ContextFileDiscovery() {}

    /**
     * pi {@code loadProjectContextFiles}。
     *
     * @param cwd      walk start (resolved like pi's resolvedCwd)
     * @param agentDir global scope; {@code null} ⇒ no global file
     */
    public static List<ContextFile> discover(Path cwd, Path agentDir) {
        var files = new ArrayList<ContextFile>();
        var seen = new LinkedHashSet<Path>();

        if (agentDir != null) {
            var global = loadFromDir(agentDir).orElse(null);
            if (global != null && seen.add(canonical(Path.of(global.path())))) {
                files.add(global);
            }
        }

        var ancestors = new ArrayList<ContextFile>();
        var current = cwd;
        while (true) {
            var file = loadFromDir(current).orElse(null);
            if (file != null && seen.add(canonical(Path.of(file.path())))) {
                // unshift: nearest dir goes to the end ⇒ farthest ancestor first
                ancestors.add(0, file);
            }
            var parent = current.getParent();
            if (parent == null) {
                break;
            }
            current = parent;
        }
        files.addAll(ancestors);
        return List.copyOf(files);
    }

    /**
     * pi {@code loadContextFileFromDir}：按 {@link #CANDIDATES} 顺序取第一个
     * 存在的普通文件。{@code path} 是命中文件的完整路径。
     */
    static Optional<ContextFile> loadFromDir(Path dir) {
        for (var name : CANDIDATES) {
            var file = dir.resolve(name);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                var content = stripBom(Files.readString(file, StandardCharsets.UTF_8));
                return Optional.of(new ContextFile(file.toString(), content));
            } catch (IOException e) {
                // pi: console warning, treat as miss
                System.getLogger(ContextFileDiscovery.class.getName())
                    .log(System.Logger.Level.WARNING,
                        "Could not read " + file + ": " + e.getMessage());
            }
        }
        return Optional.empty();
    }

    /** De-duplication key: real path, falling back to absolute path. */
    private static Path canonical(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    static String stripBom(String text) {
        return !text.isEmpty() && text.charAt(0) == BOM ? text.substring(1) : text;
    }
}
