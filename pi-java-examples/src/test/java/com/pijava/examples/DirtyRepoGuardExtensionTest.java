package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.pijava.coding.agent.extension.NotifyType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * docs/40 P5：dirty-repo-guard——{@code git status --porcelain} 非零（非 git 仓库）⇒ 放行；
 * 干净 ⇒ 放行；脏＋无 UI ⇒ {@code {cancel:true}}；脏＋有 UI 选 No ⇒ cancel＋warning 通知。
 */
class DirtyRepoGuardExtensionTest {

    @TempDir Path tmp;

    /** 与 scopeExtensions 的 services 写入隔离：会话 cwd 用子目录。 */
    private Path sessionCwd() throws IOException {
        return Files.createDirectories(tmp.resolve("repo"));
    }

    private static void git(Path dir, String... args) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add("git");
        command.addAll(java.util.List.of(args));
        var process = new ProcessBuilder(command).directory(dir.toFile())
            .redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + output);
        }
    }

    @Test
    void nonGitRepoPassesThrough() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, DirtyRepoGuardExtension.class)) {
            try (var session = ExampleFixtures.newSession(sessionCwd(),
                    ExampleFixtures.textProvider("faux-dr", "done"))) {
                assertThat(session.fireBeforeSwitch("new", null)).isFalse();
                assertThat(session.fireBeforeSwitch("resume", "t")).isFalse();
            }
        }
    }

    @Test
    void cleanRepoPassesThrough() throws Exception {
        var cwd = sessionCwd();
        git(cwd, "init", "-q");
        try (var scope = ExampleFixtures.scopeExtensions(tmp, DirtyRepoGuardExtension.class)) {
            try (var session = ExampleFixtures.newSession(cwd,
                    ExampleFixtures.textProvider("faux-dr2", "done"))) {
                assertThat(session.fireBeforeSwitch("new", null)).isFalse();
            }
        }
    }

    @Test
    void dirtyRepoWithoutUiCancels() throws Exception {
        var cwd = sessionCwd();
        git(cwd, "init", "-q");
        Files.writeString(cwd.resolve("dirty.txt"), "uncommitted");
        try (var scope = ExampleFixtures.scopeExtensions(tmp, DirtyRepoGuardExtension.class)) {
            try (var session = ExampleFixtures.newSession(cwd,
                    ExampleFixtures.textProvider("faux-dr3", "done"))) {
                assertThat(session.fireBeforeSwitch("new", null)).isTrue();
                // fork 臂同构
                assertThat(session.forkCopy("branch")).isEmpty();
            }
        }
    }

    @Test
    void dirtyRepoUiNoCancelsWithWarning() throws Exception {
        var cwd = sessionCwd();
        git(cwd, "init", "-q");
        Files.writeString(cwd.resolve("dirty.txt"), "uncommitted");
        try (var scope = ExampleFixtures.scopeExtensions(tmp, DirtyRepoGuardExtension.class)) {
            try (var session = ExampleFixtures.newSession(cwd,
                    ExampleFixtures.textProvider("faux-dr4", "done"))) {
                var ui = new ExampleFixtures.ScriptedUi();
                session.extensionUI(ui, "tui");
                ui.answer("No, let me commit first");

                assertThat(session.fireBeforeSwitch("new", null)).isTrue();
                assertThat(ui.notices()).contains(
                    new ExampleFixtures.ScriptedUi.Notice(
                        "Commit your changes first", NotifyType.WARNING));
            }
        }
    }

    @Test
    void dirtyRepoUiYesProceeds() throws Exception {
        var cwd = sessionCwd();
        git(cwd, "init", "-q");
        Files.writeString(cwd.resolve("dirty.txt"), "uncommitted");
        try (var scope = ExampleFixtures.scopeExtensions(tmp, DirtyRepoGuardExtension.class)) {
            try (var session = ExampleFixtures.newSession(cwd,
                    ExampleFixtures.textProvider("faux-dr5", "done"))) {
                var ui = new ExampleFixtures.ScriptedUi();
                session.extensionUI(ui, "tui");
                ui.answer("Yes, proceed anyway");

                assertThat(session.fireBeforeSwitch("new", null)).isFalse();
                assertThat(ui.notices()).isEmpty();
            }
        }
    }
}
