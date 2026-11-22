package com.pijava.agent.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * fd/minimatch 方言的 glob 翻译（`find.ts:209-252`、`grep.ts:167`）：
 * {@code **&#47;} 零或多段、裸 {@code **} 跨段、smart case、字符类/支选。
 */
class GlobPatternTest {

    @Test
    void doubleStarSlashMatchesZeroOrMoreSegments() {
        var p = GlobPattern.compile("**/*.ts", false);
        assertThat(p.matches("a.ts")).isTrue();
        assertThat(p.matches("src/a.ts")).isTrue();
        assertThat(p.matches("src/deep/b.ts")).isTrue();
        assertThat(p.matches("src/a.md")).isFalse();
    }

    @Test
    void bareDoubleStarCrossesSegments() {
        var p = GlobPattern.compile("src/**", false);
        assertThat(p.matches("src/a.ts")).isTrue();
        assertThat(p.matches("src/a/b.ts")).isTrue();
        // fd 的 'src/**' 不吃 'src' 本身（minimatch 会，pi 在 Windows 上特判改写成 '/**/*'）。
        assertThat(p.matches("src")).isFalse();
        assertThat(p.matches("pkg/src/a.ts")).isFalse();
    }

    @Test
    void starStaysWithinOneSegmentAndMatchesDotfiles() {
        var p = GlobPattern.compile("*.ts", false);
        assertThat(p.matches("a.ts")).isTrue();
        assertThat(p.matches(".hidden.ts")).isTrue();
        assertThat(p.matches("src/a.ts")).isFalse();
    }

    @Test
    void relativePathPatternIsRootAnchored() {
        var anchored = GlobPattern.compile("src/*.ts", false);
        assertThat(anchored.matches("src/a.ts")).isTrue();
        assertThat(anchored.matches("pkg/src/a.ts")).isFalse();

        var anyDepth = GlobPattern.compile("**/src/*.ts", false);
        assertThat(anyDepth.matches("src/a.ts")).isTrue();
        assertThat(anyDepth.matches("pkg/src/b.ts")).isTrue();
    }

    @Test
    void questionMarkMatchesOneNonSeparatorChar() {
        var p = GlobPattern.compile("a?c", false);
        assertThat(p.matches("abc")).isTrue();
        assertThat(p.matches("a/c")).isFalse();
    }

    @Test
    void characterClassAndNegation() {
        var p = GlobPattern.compile("file[0-9].txt", false);
        assertThat(p.matches("file7.txt")).isTrue();
        assertThat(p.matches("filex.txt")).isFalse();

        var negated = GlobPattern.compile("file[!0-9].txt", false);
        assertThat(negated.matches("filex.txt")).isTrue();
        assertThat(negated.matches("file7.txt")).isFalse();
    }

    @Test
    void braceExpansion() {
        var p = GlobPattern.compile("*.{ts,js}", false);
        assertThat(p.matches("a.ts")).isTrue();
        assertThat(p.matches("a.js")).isTrue();
        assertThat(p.matches("a.md")).isFalse();
    }

    @Test
    void ignoreCaseFollowsCallerFlag() {
        var insensitive = GlobPattern.compile("*.txt", true);
        assertThat(insensitive.matches("A.TXT")).isTrue();

        var sensitive = GlobPattern.compile("*.TXT", false);
        assertThat(sensitive.matches("A.TXT")).isTrue();
        assertThat(sensitive.matches("a.txt")).isFalse();
    }
}
