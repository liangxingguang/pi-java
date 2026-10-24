package com.pijava.coding.agent.pkg;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 18 T5（docs/29 §3/§7）：忽略文件三件套＋拍平加前缀模型
 *（pi :225-281；窄差 D107——JGit IgnoreNode 承载）。
 */
class PackageIgnoreTest {

    @TempDir
    Path root;

    @Test
    void gitignoreAtRootApplies() throws Exception {
        Files.writeString(root.resolve(".gitignore"), "*.tmp\n");

        var ignore = new PackageIgnore();
        ignore.addRules(root, root);

        assertThat(ignore.ignores("a.tmp", false)).isTrue();
        assertThat(ignore.ignores("a.md", false)).isFalse();
    }

    @Test
    void allThreeFileNamesRead() throws Exception {
        Files.writeString(root.resolve(".ignore"), "a.md\n");
        Files.writeString(root.resolve(".fdignore"), "b.md\n");

        var ignore = new PackageIgnore();
        ignore.addRules(root, root);

        assertThat(ignore.ignores("a.md", false)).isTrue();
        assertThat(ignore.ignores("b.md", false)).isTrue();
    }

    @Test
    void subdirectoryRulesGetPrefixFlattened() throws Exception {
        // 拍平加前缀：sub/.gitignore 的 *.tmp 只罩 sub/ 下（pi :258-281）。
        var sub = Files.createDirectories(root.resolve("sub"));
        Files.writeString(sub.resolve(".gitignore"), "*.tmp\n");

        var ignore = new PackageIgnore();
        ignore.addRules(root, root);
        ignore.addRules(sub, root);

        assertThat(ignore.ignores("sub/x.tmp", false)).isTrue();
        assertThat(ignore.ignores("x.tmp", false)).isFalse();
    }

    @Test
    void negationAndCommentsAndEscapes() throws Exception {
        Files.writeString(root.resolve(".gitignore"),
            "*.md\n!keep.md\n# comment\n\n/anchored.tmp\n");

        var ignore = new PackageIgnore();
        ignore.addRules(root, root);

        assertThat(ignore.ignores("a.md", false)).isTrue();
        assertThat(ignore.ignores("keep.md", false)).isFalse();
        // 前导 / 剥掉后照拍平模型命中根相对。
        assertThat(ignore.ignores("anchored.tmp", false)).isTrue();
    }

    @Test
    void directoryEntriesCheckedAsDirectories() throws Exception {
        Files.writeString(root.resolve(".gitignore"), "build/\n");

        var ignore = new PackageIgnore();
        ignore.addRules(root, root);

        assertThat(ignore.ignores("build", true)).isTrue();
        // 目录限定规则不罩同名文件（gitignore 语义）。
        assertThat(ignore.ignores("build", false)).isFalse();
    }

    @Test
    void unreadableDirSilentlySkipped() {
        var ignore = new PackageIgnore();
        ignore.addRules(root.resolve("nonexistent"), root);

        assertThat(ignore.ignores("anything", false)).isFalse();
    }
}
