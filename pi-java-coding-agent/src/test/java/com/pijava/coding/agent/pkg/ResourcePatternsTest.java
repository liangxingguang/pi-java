package com.pijava.coding.agent.pkg;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 18 T5（docs/29 §3/§7）：pi 过滤代数真值表——四符（普通/!/＋/－）、
 * 精确 vs pattern、SKILL.md 父目录形态、autoload=false 映射语义。
 */
class ResourcePatternsTest {

    @TempDir
    Path root;

    private String rel(String path) {
        return root.resolve(path).toString();
    }

    @Test
    void noIncludesDefaultsToAll() {
        var all = List.of(rel("skills/a.md"), rel("skills/b.md"));

        assertThat(ResourcePatterns.applyPatterns(all, List.of(), root.toString()))
            .containsExactlyInAnyOrderElementsOf(all);
    }

    @Test
    void includePatternsFilter() {
        var all = List.of(rel("skills/a.md"), rel("skills/b.md"), rel("prompts/p.md"));

        assertThat(ResourcePatterns.applyPatterns(all, List.of("skills/**"), root.toString()))
            .containsExactlyInAnyOrder(rel("skills/a.md"), rel("skills/b.md"));
        // 文件名形态也可命中。
        assertThat(ResourcePatterns.applyPatterns(all, List.of("a.md"), root.toString()))
            .containsExactly(rel("skills/a.md"));
    }

    @Test
    void bangExcludes() {
        var all = List.of(rel("skills/a.md"), rel("skills/b.test.md"));

        assertThat(ResourcePatterns.applyPatterns(all,
            List.of("skills/**", "!*.test.md"), root.toString()))
            .containsExactly(rel("skills/a.md"));
    }

    @Test
    void plusForceIncludesOverExclusion() {
        var all = List.of(rel("skills/a.md"), rel("skills/b.test.md"));

        // 无 include ⇒ 默认全收；! 排除后 + 精确回补（pi 四步序）。
        assertThat(ResourcePatterns.applyPatterns(all,
            List.of("!*.test.md", "+skills/b.test.md"), root.toString()))
            .containsExactlyInAnyOrder(rel("skills/a.md"), rel("skills/b.test.md"));
    }

    @Test
    void minusForceExcludesOverForceInclude() {
        var all = List.of(rel("skills/a.md"), rel("skills/b.test.md"));

        assertThat(ResourcePatterns.applyPatterns(all,
            List.of("+skills/b.test.md", "-skills/b.test.md"), root.toString()))
            .containsExactly(rel("skills/a.md"));
    }

    @Test
    void plusIsExactOnly() {
        // + 是精确形：通配写法不命中（pi matchesAnyExactPattern 字符串等值）。
        var all = List.of(rel("skills/a.md"), rel("skills/b.md"));

        assertThat(ResourcePatterns.applyPatterns(all,
            List.of("!**", "+skills/*"), root.toString()))
            .isEmpty();
    }

    @Test
    void skillMdParentDirForms() {
        var skillFile = rel("skills/foo/SKILL.md");

        // pattern 命中父目录相对路径。
        assertThat(ResourcePatterns.matchesAnyPattern(
            skillFile, List.of("skills/foo"), root.toString())).isTrue();
        // 父目录名形态。
        assertThat(ResourcePatterns.matchesAnyPattern(
            skillFile, List.of("foo"), root.toString())).isTrue();
        // 精确形也吃父目录。
        assertThat(ResourcePatterns.matchesAnyExactPattern(
            skillFile, List.of("skills/foo"), root.toString())).isTrue();
        // 普通文件无父目录形态。
        assertThat(ResourcePatterns.matchesAnyPattern(
            rel("skills/foo/a.md"), List.of("foo"), root.toString())).isFalse();
    }

    @Test
    void exactPatternStripsDotSlashPrefix() {
        assertThat(ResourcePatterns.matchesAnyExactPattern(
            rel("skills/a.md"), List.of("./skills/a.md"), root.toString())).isTrue();
    }

    @Test
    void autoloadDisabledPatternsProduceEnabledMap() {
        var all = List.of(rel("skills/a.md"), rel("skills/b.md"));

        var map = ResourcePatterns.applyAutoloadDisabledPatterns(all,
            List.of("skills/a.md", "!b.md"), root.toString());

        // +/普通＝true、!＝false；未点名条目不出现（delta 语义）。
        assertThat(map).containsEntry(rel("skills/a.md"), true)
            .containsEntry(rel("skills/b.md"), false)
            .hasSize(2);
    }

    @Test
    void isEnabledByOverridesOrder() {
        var path = rel("extensions/x.jar");

        assertThat(ResourcePatterns.isEnabledByOverrides(path, List.of(), root.toString()))
            .isTrue();
        assertThat(ResourcePatterns.isEnabledByOverrides(
            path, List.of("!x.jar"), root.toString())).isFalse();
        assertThat(ResourcePatterns.isEnabledByOverrides(
            path, List.of("!x.jar", "+extensions/x.jar"), root.toString())).isTrue();
        assertThat(ResourcePatterns.isEnabledByOverrides(
            path, List.of("+extensions/x.jar", "-extensions/x.jar"), root.toString())).isFalse();
    }

    @Test
    void patternClassification() {
        assertThat(ResourcePatterns.isPattern("plain.md")).isFalse();
        assertThat(ResourcePatterns.isPattern("!x")).isTrue();
        assertThat(ResourcePatterns.isPattern("+x")).isTrue();
        assertThat(ResourcePatterns.isPattern("-x")).isTrue();
        assertThat(ResourcePatterns.isPattern("a*b")).isTrue();
        assertThat(ResourcePatterns.isPattern("a?b")).isTrue();
        assertThat(ResourcePatterns.isOverridePattern("a*b")).isFalse();
        assertThat(ResourcePatterns.hasGlobPattern("a*b")).isTrue();
        assertThat(ResourcePatterns.hasGlobPattern("plain")).isFalse();

        var split = ResourcePatterns.splitPatterns(List.of("a.md", "!b.md", "c*d"));
        assertThat(split.plain()).containsExactly("a.md");
        assertThat(split.patterns()).containsExactly("!b.md", "c*d");
    }
}
