package com.pijava.coding.agent.skill;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-6: IgnoreFilter — JGit IgnoreNode 三态上溯、嵌套 .gitignore 优先级、
 * POSIX 路径、isDirectory 准确性。不重测 gitignore 通配语义本身（属 JGit 职责）。
 */
class IgnoreFilterTest {

    @TempDir
    Path tmp;

    @Test
    void nestedGitignoreDeepestRuleWins() throws Exception {
        Files.writeString(tmp.resolve(".gitignore"), "*.log\n!keep.log\n");
        Files.createDirectories(tmp.resolve("sub"));
        Files.writeString(tmp.resolve("sub/.gitignore"), "*.log\n");

        var filter = new IgnoreFilter(tmp);
        filter.push(tmp);
        filter.push(tmp.resolve("sub"));

        // 深层 *.log 覆盖浅层 !keep.log → 忽略
        assertThat(filter.isIgnored("sub/keep.log", false)).isTrue();
        // 无深层规则 → 浅层 !keep.log 生效 → 不忽略
        filter.pop();
        assertThat(filter.isIgnored("sub/keep.log", false)).isFalse();
        assertThat(filter.isIgnored("sub/a.log", false)).isTrue();
    }

    @Test
    void nullMeansNoRuleContinueUpward() throws Exception {
        Files.writeString(tmp.resolve(".gitignore"), "*.log\n");
        var filter = new IgnoreFilter(tmp);
        filter.push(tmp);
        // 无匹配 → null → 不忽略
        assertThat(filter.isIgnored("readme.md", false)).isFalse();
        assertThat(filter.isIgnored("a.log", false)).isTrue();
    }

    @Test
    void directoryRuleRequiresIsDirectory() throws Exception {
        Files.writeString(tmp.resolve(".gitignore"), "build/\n");
        var filter = new IgnoreFilter(tmp);
        filter.push(tmp);
        assertThat(filter.isIgnored("build", true)).isTrue();
        assertThat(filter.isIgnored("build", false)).isFalse();
    }

    @Test
    void windowsStylePathIsNormalizedToPosix() throws Exception {
        Files.writeString(tmp.resolve(".gitignore"), "**/temp\n");
        var filter = new IgnoreFilter(tmp);
        filter.push(tmp);
        // Windows Path 经 toPosix 转 / 分隔后匹配
        assertThat(filter.isIgnored("x/temp", false)).isTrue();
        assertThat(IgnoreFilter.toPosix(Path.of("a", "b", "c")))
            .isEqualTo("a/b/c");
    }

    @Test
    void ignoreAndFdignoreUseSameParser() throws Exception {
        Files.writeString(tmp.resolve(".ignore"), "*.tmp\n");
        Files.writeString(tmp.resolve(".fdignore"), "*.junk\n");
        var filter = new IgnoreFilter(tmp);
        filter.push(tmp);
        assertThat(filter.isIgnored("x.tmp", false)).isTrue();
        assertThat(filter.isIgnored("x.junk", false)).isTrue();
    }
}
