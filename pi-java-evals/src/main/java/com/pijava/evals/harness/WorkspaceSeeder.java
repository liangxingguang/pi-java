package com.pijava.evals.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 批 32（docs/44 §3.1）：把 {@link HarnessOptions#workspaceFiles()} 落到隔离
 * workspace —— pi {@code seedWorkspace}（{@code harness.ts:225-236}）对位。
 *
 * <p>拒绝绝对路径与逃逸路径（{@code ..}）；写入前建父目录。</p>
 */
public final class WorkspaceSeeder {

    private WorkspaceSeeder() {
    }

    /**
     * Seed workspace files.
     *
     * @param workspace workspace root
     * @param files     relative path → content (nullable ⇒ nothing to do)
     * @throws IOException if a file cannot be written
     * @throws IllegalArgumentException if a path is absolute or escapes the workspace
     */
    public static void seed(Path workspace, Map<String, String> files) throws IOException {
        if (files == null || files.isEmpty()) {
            return;
        }
        var normalizedRoot = workspace.toAbsolutePath().normalize();
        for (var entry : files.entrySet()) {
            var name = entry.getKey();
            if (name == null || name.isBlank() || Path.of(name).isAbsolute()) {
                throw new IllegalArgumentException("Invalid workspace fixture path: " + name);
            }
            var target = normalizedRoot.resolve(name).normalize();
            if (!target.startsWith(normalizedRoot)) {
                throw new IllegalArgumentException("Workspace fixture escapes the workspace: " + name);
            }
            var parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(target, entry.getValue() == null ? "" : entry.getValue());
        }
    }
}
