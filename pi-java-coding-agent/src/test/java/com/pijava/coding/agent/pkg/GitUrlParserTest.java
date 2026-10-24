package com.pijava.coding.agent.pkg;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 18 T2（docs/29 §3）：pi {@code parseGitUrl}（utils/git.ts:172-226）移植面
 * ——协议/scp-like/git: 简写三形态、{@code @ref} 拆分、{@code buildGitSource}
 * 安全校验（D105：hosted-git-info 知名宿主表已裁）。
 */
class GitUrlParserTest {

    @Test
    void httpsUrlParsesHostAndPath() {
        var git = GitUrlParser.parseGitUrl("https://github.com/u/r");

        assertThat(git.repo()).isEqualTo("https://github.com/u/r");
        assertThat(git.host()).isEqualTo("github.com");
        assertThat(git.path()).isEqualTo("u/r");
        assertThat(git.ref()).isNull();
        assertThat(git.pinned()).isFalse();
    }

    @Test
    void trailingGitSuffixStripped() {
        var git = GitUrlParser.parseGitUrl("https://github.com/u/r.git");

        assertThat(git.path()).isEqualTo("u/r");
    }

    @Test
    void urlRefSuffixSplitsAndPins() {
        var git = GitUrlParser.parseGitUrl("https://github.com/u/r@v1.2.3");

        assertThat(git.repo()).isEqualTo("https://github.com/u/r");
        assertThat(git.path()).isEqualTo("u/r");
        assertThat(git.ref()).isEqualTo("v1.2.3");
        assertThat(git.pinned()).isTrue();
    }

    @Test
    void scpLikeSyntaxParsesWithGitPrefix() {
        // pi 同：scp-like 仅在 git: 前缀下收（git-ssh-url.test.ts 对位）；
        // 裸 scp-like 被 isLocalPath 判本地、parseGitUrl 协议门拒。
        var git = GitUrlParser.parseGitUrl("git:git@github.com:u/r.git");

        assertThat(git.repo()).isEqualTo("git@github.com:u/r.git");
        assertThat(git.host()).isEqualTo("github.com");
        assertThat(git.path()).isEqualTo("u/r");
    }

    @Test
    void scpLikeWithoutGitPrefixRejected() {
        assertThat(GitUrlParser.parseGitUrl("git@github.com:u/r.git")).isNull();
    }

    @Test
    void scpLikeRefSuffixSplits() {
        var git = GitUrlParser.parseGitUrl("git:git@github.com:u/r@main");

        assertThat(git.repo()).isEqualTo("git@github.com:u/r");
        assertThat(git.ref()).isEqualTo("main");
        assertThat(git.pinned()).isTrue();
    }

    @Test
    void sshProtocolAccepted() {
        var git = GitUrlParser.parseGitUrl("ssh://git@github.com/u/r");

        assertThat(git.host()).isEqualTo("github.com");
        assertThat(git.path()).isEqualTo("u/r");
    }

    @Test
    void gitProtocolSwallowedByPrefixGate() {
        // pi 同形亦 null：startsWith("git:") 命中 ⇒ 前缀剥掉后 "//host/path"
        // 进简写臂 ⇒ 空 host 拒。钉死对齐（非 java 特有缺陷）。
        assertThat(GitUrlParser.parseGitUrl("git://example.com/u/r")).isNull();
    }

    @Test
    void gitPrefixShorthandAccepted() {
        var git = GitUrlParser.parseGitUrl("git:github.com/u/r");

        // 简写臂重建为 https 形（pi parseGenericGitUrl else 臂）。
        assertThat(git.repo()).isEqualTo("https://github.com/u/r");
        assertThat(git.host()).isEqualTo("github.com");
        assertThat(git.path()).isEqualTo("u/r");
    }

    @Test
    void gitPrefixShorthandWithRef() {
        var git = GitUrlParser.parseGitUrl("git:github.com/u/r@dev");

        assertThat(git.repo()).isEqualTo("https://github.com/u/r");
        assertThat(git.ref()).isEqualTo("dev");
        assertThat(git.pinned()).isTrue();
    }

    @Test
    void localhostShorthandWithoutDotAccepted() {
        var git = GitUrlParser.parseGitUrl("git:localhost/u/r");

        assertThat(git.host()).isEqualTo("localhost");
    }

    @Test
    void dotlessShorthandHostRejected() {
        assertThat(GitUrlParser.parseGitUrl("git:nodot/u/r")).isNull();
    }

    @Test
    void shorthandWithoutGitPrefixRejected() {
        // 无 git: 前缀时只收显式协议 URL。
        assertThat(GitUrlParser.parseGitUrl("github.com/u/r")).isNull();
    }

    @Test
    void singleSegmentPathRejected() {
        assertThat(GitUrlParser.parseGitUrl("https://github.com/onlyone")).isNull();
    }

    @Test
    void unsafePartsRejected() {
        // \0、\、前导 /、.. 段（pi hasUnsafeGitInstallPart 双检）。
        assertThat(GitUrlParser.parseGitUrl("https://github.com/u/../r")).isNull();
        assertThat(GitUrlParser.parseGitUrl("git:github.com/u\\evil/r")).isNull();
        assertThat(GitUrlParser.parseGitUrl("git:/github.com/u/r")).isNull();
    }

    @Test
    void percentEncodedDotDotRejectedAfterDecode() {
        // %2e%2e 解码复审（pi decodeForValidation 臂）。
        assertThat(GitUrlParser.parseGitUrl("https://github.com/u/%2e%2e/x")).isNull();
    }

    @Test
    void protocolHostLowercasedLikeJsUrl() {
        // JS URL.hostname 小写归一（评审 M3）；身份键与安装路径随之稳定。
        var git = GitUrlParser.parseGitUrl("https://GitHub.COM/u/r");

        assertThat(git.host()).isEqualTo("github.com");
    }

    @Test
    void deepPathsAccepted() {
        var git = GitUrlParser.parseGitUrl("https://gitlab.com/group/sub/repo");

        assertThat(git.path()).isEqualTo("group/sub/repo");
    }
}
