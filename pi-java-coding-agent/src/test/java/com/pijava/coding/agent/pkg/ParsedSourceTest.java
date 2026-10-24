package com.pijava.coding.agent.pkg;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

import com.pijava.coding.agent.pkg.ParsedSource.GitSource;
import com.pijava.coding.agent.pkg.ParsedSource.LocalSource;
import com.pijava.coding.agent.pkg.ParsedSource.NpmSource;

/**
 * 批 18 T2（docs/29 §3）：pi {@code parseSource} 分派表
 *（package-manager.ts:1480-1505）＋ {@code parseNpmSpec} 正则（:1765-1773）。
 */
class ParsedSourceTest {

    @Test
    void npmPrefixedSourceParsesNameAndVersion() {
        var parsed = (NpmSource) SourceParser.parseSource("npm:foo@1.2.3");

        assertThat(parsed.spec()).isEqualTo("foo@1.2.3");
        assertThat(parsed.name()).isEqualTo("foo");
        assertThat(parsed.version()).isEqualTo("1.2.3");
        assertThat(parsed.pinned()).isTrue();
        assertThat(parsed.range()).isEqualTo("1.2.3");
    }

    @Test
    void npmScopedNameKeepsLeadingAt() {
        var parsed = (NpmSource) SourceParser.parseSource("npm:@org/pkg@^1.2");

        assertThat(parsed.name()).isEqualTo("@org/pkg");
        assertThat(parsed.version()).isEqualTo("^1.2");
        assertThat(parsed.pinned()).isFalse();
        assertThat(parsed.range()).isEqualTo("^1.2");
    }

    @Test
    void npmWithoutVersionIsUnpinnedAndRangefree() {
        var parsed = (NpmSource) SourceParser.parseSource("npm:foo");

        assertThat(parsed.name()).isEqualTo("foo");
        assertThat(parsed.version()).isNull();
        assertThat(parsed.range()).isNull();
        assertThat(parsed.pinned()).isFalse();
    }

    @Test
    void npmGarbageVersionIsNotPinnedAndRangeless() {
        var parsed = (NpmSource) SourceParser.parseSource("npm:foo@not a version!!!");

        assertThat(parsed.name()).isEqualTo("foo");
        assertThat(parsed.version()).isEqualTo("not a version!!!");
        assertThat(parsed.pinned()).isFalse();
        assertThat(parsed.range()).isNull();
    }

    @Test
    void npmSpecTrimmedAfterPrefix() {
        var parsed = (NpmSource) SourceParser.parseSource("npm:  foo@2.0.0  ");

        assertThat(parsed.spec()).isEqualTo("foo@2.0.0");
        assertThat(parsed.name()).isEqualTo("foo");
        assertThat(parsed.version()).isEqualTo("2.0.0");
    }

    @Test
    void explicitGitProtocolUrlsParseAsGit() {
        var parsed = (GitSource) SourceParser.parseSource("https://github.com/u/r");

        assertThat(parsed.host()).isEqualTo("github.com");
        assertThat(parsed.path()).isEqualTo("u/r");
        assertThat(parsed.pinned()).isFalse();
    }

    @Test
    void bareRelativeAndAbsolutePathsAreLocal() {
        assertThat(SourceParser.parseSource("./skills")).isInstanceOf(LocalSource.class);
        assertThat(SourceParser.parseSource("../x")).isInstanceOf(LocalSource.class);
        assertThat(SourceParser.parseSource("plainname")).isInstanceOf(LocalSource.class);
        assertThat(SourceParser.parseSource("file:///tmp/pkg")).isInstanceOf(LocalSource.class);
    }

    @Test
    void builtinPrefixFallsThroughToLocalVerbatim() {
        // pi：isLocalPath 排除 builtin: 后 parseGitUrl 不收 ⇒ 恒定回落 local
        //（内置扩展由 resolve 的内置臂单独处理，不走 parseSource）。
        var parsed = SourceParser.parseSource("builtin:reviewer");

        assertThat(parsed).isInstanceOf(LocalSource.class);
        assertThat(((LocalSource) parsed).path()).isEqualTo("builtin:reviewer");
    }

    @Test
    void gitParseFailureFallsBackToLocal() {
        // git: 前缀但无路径段 ⇒ parseGitUrl null ⇒ 恒定回落 local（不报错）。
        var parsed = SourceParser.parseSource("git:foo");

        assertThat(parsed).isInstanceOf(LocalSource.class);
        assertThat(((LocalSource) parsed).path()).isEqualTo("git:foo");
    }

    @Test
    void explicitProtocolWithDotlessHostIsStillGit() {
        // 协议臂不做点/localhost 校验（该校验只在无前缀简写臂）。
        var parsed = SourceParser.parseSource("https://nodot/u/r");

        assertThat(parsed).isInstanceOf(GitSource.class);
        assertThat(((GitSource) parsed).host()).isEqualTo("nodot");
    }
}
