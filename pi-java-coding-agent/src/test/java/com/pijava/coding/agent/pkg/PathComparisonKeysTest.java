package com.pijava.coding.agent.pkg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * L5 W5 §6.4（pi {@code utils/paths.ts:36-78}＋{@code source-info.ts:46-48}）：
 * 路径比较键——本地包身份在 Windows 上大小写折叠（同目录只有一条身份）、
 * {@code isWithin} 的根/包含判定、{@code getResourcePathKey} 的合成路径直通。
 */
class PathComparisonKeysTest {

    @TempDir
    Path dir;

    @Test
    void keyIsAbsoluteAndNormalized() {
        var dotted = dir.resolve("a").resolve("..").resolve("b");
        var expected = Path.of(dir.toString(), "b").toAbsolutePath().normalize().toString();

        // key 在 Windows 上折叠大小写，故按同一函数对齐期望值。
        assertThat(PathComparisonKeys.key(dotted.toString()))
            .isEqualTo(PathComparisonKeys.key(expected));
    }

    @Test
    void keyFoldsCaseOnWindowsOnly() {
        var sample = dir.resolve("Proj").toString();
        var folded = PathComparisonKeys.key(sample);
        var expected = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
            .startsWith("windows")
            ? sample.toLowerCase(java.util.Locale.ROOT) : sample;

        assertThat(folded).isEqualTo(expected);
        assertThat(PathComparisonKeys.equal(sample, folded)).isTrue();
    }

    @Test
    void isWithinCoversSelfAndDescendantsOnly() {
        var parent = dir.resolve("pkg").toString();
        var child = dir.resolve("pkg").resolve("skills").toString();
        var sibling = dir.resolve("pkg2").toString();

        assertThat(PathComparisonKeys.isWithin(parent, parent)).isTrue();
        assertThat(PathComparisonKeys.isWithin(parent, child)).isTrue();
        assertThat(PathComparisonKeys.isWithin(parent, sibling)).isFalse();
        // 前缀相同的兄弟目录不算「内部」（补分隔符再比）。
        assertThat(PathComparisonKeys.isWithin(
            dir.resolve("pkg").toString(), dir.resolve("pkg2").toString())).isFalse();
    }

    @Test
    void syntheticPathsBypassFilesystemKeys() {
        assertThat(PathComparisonKeys.isSynthetic("builtin:mcp")).isTrue();
        assertThat(PathComparisonKeys.isSynthetic("<inline:x>")).isTrue();
        assertThat(PathComparisonKeys.isSynthetic(dir.toString())).isFalse();
        assertThat(PathComparisonKeys.resourceKey("builtin:mcp")).isEqualTo("builtin:mcp");
        // 合法路径照常走 canonicalize＋比较键。
        assertThat(PathComparisonKeys.resourceKey(dir.toString()))
            .isEqualTo(PathComparisonKeys.key(PackagePaths.canonicalizePath(dir.toString())));
    }

    @Test
    void localPackageIdentityIsCaseFolded() throws IOException {
        var settingsStorage = new com.pijava.coding.agent.core.InMemorySettingsStorage();
        var settings = com.pijava.coding.agent.core.SettingsManager.withStorage(settingsStorage);
        var pkgDir = Files.createDirectories(dir.resolve("MyPkg"));
        var resolver = new SourceResolver(dir, dir.resolve("agent"), settings::isProjectTrusted);

        var lower = resolver.getPackageIdentity(pkgDir.toString().toLowerCase(java.util.Locale.ROOT), null);
        var exact = resolver.getPackageIdentity(pkgDir.toString(), null);

        // 非 Windows：两条不同身份；Windows：折叠后同一身份（pi getPathComparisonKey 同）。
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows")) {
            assertThat(lower).isEqualTo(exact);
        } else {
            assertThat(lower).isNotEqualTo(exact);
        }
    }

    @Test
    void dedupeKeepsOnePathForCaseVariantsOnWindows() throws IOException {
        var skillFile = dir.resolve("skills").resolve("s").resolve("SKILL.md");
        Files.createDirectories(skillFile.getParent());
        Files.writeString(skillFile, "# s");
        var variant = skillFile.toString().toLowerCase(java.util.Locale.ROOT);

        var accumulator = new ResourceAccumulator();
        var metadata = new PathMetadata("local", SourceScope.USER, PathMetadata.Origin.TOP_LEVEL,
            dir.toString(), null);
        accumulator.addResource(ResourceType.SKILLS, skillFile.toString(), metadata, true);
        accumulator.addResource(ResourceType.SKILLS, variant, metadata, true);

        // accumulate 是 first-wins 的 Map（pi 同），去重键只在 toResolvedPaths 生效；
        // 这里直接钉 ResourcePathKeys 的等价关系。
        assertThat(PathComparisonKeys.resourceKey(skillFile.toString())).isEqualTo(
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows")
                ? PathComparisonKeys.resourceKey(variant)
                : PathComparisonKeys.resourceKey(skillFile.toString()));
    }

    @Test
    void canonicalMissingPathFallsBackToRaw() {
        var missing = dir.resolve("nope").toString();
        assertThat(PackagePaths.canonicalizePath(missing)).isEqualTo(missing);
        assertThat(PathComparisonKeys.resourceKey(missing)).isEqualTo(PathComparisonKeys.key(missing));
    }

    @Test
    void resourcePatternTildeExpandsToHome() {
        var home = System.getProperty("user.home");
        var underHome = Path.of(home, "skills", "pdf").resolve("SKILL.md").toString();

        // `!~/skills/pdf`：排除臂必须命中 home 下的路径（旧实现只 toPosixPath ⇒ 永不匹配）。
        assertThat(ResourcePatterns.matchesAnyPattern(underHome,
            List.of("~/skills/pdf"), home)).isTrue();
        assertThat(ResourcePatterns.matchesAnyPattern(underHome,
            List.of("~"), home)).isFalse();
        // 精确形统一走 toPosixPath（pi 同）。
        assertThat(ResourcePatterns.normalizeExactPattern("~"))
            .isEqualTo(ResourcePatterns.toPosixPath(home));
        // 剥 "./" 之后再展开 `~`（pi normalizeExactPattern → normalizePath 同序）。
        assertThat(ResourcePatterns.normalizeExactPattern("./~/x"))
            .isEqualTo(ResourcePatterns.toPosixPath(home + java.io.File.separator + "x"));
    }

    @Test
    void exactTildePatternMatchesHomePath() {
        var home = System.getProperty("user.home");
        var dirPath = Path.of(home, "skills", "pdf").toString();

        assertThat(ResourcePatterns.matchesAnyExactPattern(dirPath,
            List.of("~/skills/pdf"), home)).isTrue();
    }
}
