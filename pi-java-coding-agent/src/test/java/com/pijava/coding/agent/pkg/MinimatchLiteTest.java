package com.pijava.coding.agent.pkg;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 18 T5（docs/29 §3/§7）：MinimatchLite 子集——{@code *} 不跨段、
 * {@code **} 跨段、{@code ?}、{@code {a,b}} 展开、dot:false 默认。
 */
class MinimatchLiteTest {

    @Test
    void starDoesNotCrossSlash() {
        assertThat(MinimatchLite.match("*.md", "s.md")).isTrue();
        assertThat(MinimatchLite.match("*.md", "sub/s.md")).isFalse();
        assertThat(MinimatchLite.match("skills/*", "skills/foo")).isTrue();
        assertThat(MinimatchLite.match("skills/*", "skills/foo/bar")).isFalse();
    }

    @Test
    void globstarCrossesSegments() {
        assertThat(MinimatchLite.match("**/*.md", "sub/s.md")).isTrue();
        assertThat(MinimatchLite.match("**/*.md", "s.md")).isTrue();
        assertThat(MinimatchLite.match("**/*.md", "a/b/c.md")).isTrue();
        assertThat(MinimatchLite.match("skills/**", "skills/a/b")).isTrue();
        assertThat(MinimatchLite.match("a/**/b", "a/b")).isTrue();
        assertThat(MinimatchLite.match("a/**/b", "a/x/y/b")).isTrue();
        assertThat(MinimatchLite.match("a/**/b", "a/x/y/c")).isFalse();
    }

    @Test
    void questionMarkMatchesSingleNonSlash() {
        assertThat(MinimatchLite.match("?.md", "s.md")).isTrue();
        assertThat(MinimatchLite.match("?.md", "ab.md")).isFalse();
        assertThat(MinimatchLite.match("a?c", "a/c")).isFalse();
    }

    @Test
    void braceExpansion() {
        assertThat(MinimatchLite.match("*.{md,json}", "t.json")).isTrue();
        assertThat(MinimatchLite.match("*.{md,json}", "t.md")).isTrue();
        assertThat(MinimatchLite.match("*.{md,json}", "t.yaml")).isFalse();
        assertThat(MinimatchLite.match("{a,b}/s.md", "b/s.md")).isTrue();
    }

    @Test
    void globstarAtEndRequiresAtLeastOneSegment() {
        // pi minimatch 10.2.6 实测对拍（评审 M1）：「a/**」不中「a」自身。
        assertThat(MinimatchLite.match("a/**", "a")).isFalse();
        assertThat(MinimatchLite.match("a/**", "a/b")).isTrue();
        assertThat(MinimatchLite.match("a/**", "a/b/c")).isTrue();
    }

    @Test
    void globstarDoesNotCrossDotSegments() {
        // dot:false：通配（含 **）不匹配 . 开头段（pi minimatch 实测对拍）。
        assertThat(MinimatchLite.match("a/**", "a/.b/c")).isFalse();
        assertThat(MinimatchLite.match("**/*.md", ".h/x.md")).isFalse();
        assertThat(MinimatchLite.match("**/*.md", "a/.h/x.md")).isFalse();
        assertThat(MinimatchLite.match("**/*.md", "a/x.md")).isTrue();
        assertThat(MinimatchLite.match("**", "plain")).isTrue();
        assertThat(MinimatchLite.match("**", ".dot")).isFalse();
    }

    @Test
    void dotSegmentsNotMatchedByLeadingWildcard() {
        // minimatch dot:false 默认。
        assertThat(MinimatchLite.match("*.md", ".hidden.md")).isFalse();
        assertThat(MinimatchLite.match("?oo", ".oo")).isFalse();
        // 显式 . 开头可以匹配。
        assertThat(MinimatchLite.match(".hidden.md", ".hidden.md")).isTrue();
    }

    @Test
    void literalDotsAndSpecialCharsEscaped() {
        assertThat(MinimatchLite.match("s.md", "sXmd")).isFalse();
        assertThat(MinimatchLite.match("a+b.md", "a+b.md")).isTrue();
    }
}
