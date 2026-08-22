package com.pijava.web;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Git 服务（Phase 7 Stage B）：status / diff / history（JGit）。
 */
class GitServiceTest {

    @TempDir
    Path tempDir;

    /** 建一个 git 仓库：commit a.txt，改 b.txt（未暂存），新增 c.txt（未跟踪）。 */
    private Path initRepo() throws Exception {
        var repo = Git.init().setDirectory(tempDir.toFile()).call();
        try {
            Files.writeString(tempDir.resolve("a.txt"), "line1\n");
            Files.writeString(tempDir.resolve("b.txt"), "old\n");
            repo.add().addFilepattern(".").call();
            repo.commit().setMessage("init").setAuthor("t", "t@t")
                .setCommitter("t", "t@t").call();

            Files.writeString(tempDir.resolve("b.txt"), "new\n");
            Files.writeString(tempDir.resolve("c.txt"), "untracked\n");
        } finally {
            repo.close();
        }
        return tempDir;
    }

    private final GitService service = new GitService();

    @Test
    void statusListsModifiedAndUntracked() throws Exception {
        initRepo();

        var status = service.status(tempDir);

        var b = status.entries().stream().filter(e -> e.path().equals("b.txt")).findFirst();
        assertThat(b).isPresent();
        assertThat(b.get().workTreeStatus()).isEqualTo("M");

        var c = status.entries().stream().filter(e -> e.path().equals("c.txt")).findFirst();
        assertThat(c).isPresent();
        assertThat(c.get().workTreeStatus()).isEqualTo("U");
    }

    @Test
    void diffContainsAddedAndRemovedLines() throws Exception {
        initRepo();

        var diff = service.diff(tempDir, null, false);

        assertThat(diff.text()).contains("old").contains("new");
    }

    @Test
    void historyReturnsCommits() throws Exception {
        initRepo();

        var history = service.history(tempDir, null, 10);

        assertThat(history.commits()).isNotEmpty();
        assertThat(history.commits().get(0).message()).isEqualTo("init");
        assertThat(history.commits().get(0).id()).hasSize(8);
    }

    @Test
    void nonGitDirIsRejected() {
        assertThatThrownBy(() -> service.status(tempDir))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Not a git repository");
    }
}
