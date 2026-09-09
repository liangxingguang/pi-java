package com.pijava.coding.agent.extension;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ResourcePaths — 会话开始资源路径合并/去重/空判断。
 */
class ResourcePathsTest {

    private static Path p(String s) {
        return Path.of(s);
    }

    @Test
    void noneIsEmpty() {
        assertThat(ResourcePaths.none().isEmpty()).isTrue();
    }

    @Test
    void plusMergesPreservingOrder() {
        var a = new ResourcePaths(List.of(p("skillsA")), List.of(p("promptsA")),
            List.of(p("themesA")));
        var b = new ResourcePaths(List.of(p("skillsB")), List.of(p("promptsB")),
            List.of(p("themesB")));
        var merged = a.plus(b);
        assertThat(merged.skillPaths()).containsExactly(
            p("skillsA"), p("skillsB"));
        assertThat(merged.promptPaths()).containsExactly(
            p("promptsA"), p("promptsB"));
        assertThat(merged.themePaths()).containsExactly(
            p("themesA"), p("themesB"));
    }

    @Test
    void plusDedupesByPath() {
        var a = new ResourcePaths(List.of(p("x")), List.of(), List.of());
        var b = new ResourcePaths(List.of(p("x"), p("y")), List.of(), List.of());
        var merged = a.plus(b);
        assertThat(merged.skillPaths()).containsExactly(p("x"), p("y"));
    }

    @Test
    void plusNoneReturnsThis() {
        var a = new ResourcePaths(List.of(p("x")), List.of(), List.of());
        assertThat(a.plus(ResourcePaths.none())).isSameAs(a);
    }

    @Test
    void plusNullReturnsThis() {
        var a = new ResourcePaths(List.of(p("x")), List.of(), List.of());
        assertThat(a.plus(null)).isSameAs(a);
    }

    @Test
    void nullsBecomeEmptyLists() {
        var paths = new ResourcePaths(null, null, null);
        assertThat(paths.skillPaths()).isEmpty();
        assertThat(paths.promptPaths()).isEmpty();
        assertThat(paths.themePaths()).isEmpty();
        assertThat(paths.isEmpty()).isTrue();
    }
}
