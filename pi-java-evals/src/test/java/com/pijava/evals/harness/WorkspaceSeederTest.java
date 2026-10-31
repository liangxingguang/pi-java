package com.pijava.evals.harness;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 批 32（docs/44 §3.1）：{@link WorkspaceSeeder} 的落盘与路径守卫。
 */
class WorkspaceSeederTest {

    @Test
    void writesRelativeFilesAndCreatesParents(@TempDir Path workspace) throws Exception {
        WorkspaceSeeder.seed(workspace, Map.of(
            "a.txt", "one",
            "nested/deep/b.txt", "two"));
        assertThat(workspace.resolve("a.txt")).hasContent("one");
        assertThat(workspace.resolve("nested/deep/b.txt")).hasContent("two");
    }

    @Test
    void emptyFixtureIsNoop(@TempDir Path workspace) throws Exception {
        WorkspaceSeeder.seed(workspace, Map.of());
        assertThat(workspace).isEmptyDirectory();
        WorkspaceSeeder.seed(workspace, null);
        assertThat(workspace).isEmptyDirectory();
    }

    @Test
    void rejectsAbsolutePath(@TempDir Path workspace) {
        var absolute = workspace.toAbsolutePath().resolve("outside.txt").toString();
        assertThatThrownBy(() -> WorkspaceSeeder.seed(workspace, Map.of(absolute, "x")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid workspace fixture path");
    }

    @Test
    void rejectsEscapingPath(@TempDir Path workspace) {
        assertThatThrownBy(() -> WorkspaceSeeder.seed(workspace, Map.of("../escape.txt", "x")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("escapes the workspace");
    }
}
