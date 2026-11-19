package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** docs/54 §2.7：truncated-tool 验收——匹配计数、截断通知、无匹配/失败、工具注册。 */
class TruncatedToolExtensionTest {

    @Test
    void matchCount() {
        assertThat(TruncatedToolExtension.matchCount("a.java:1:x\nb.java:2:y")).isEqualTo(2);
        assertThat(TruncatedToolExtension.matchCount("")).isZero();
        assertThat(TruncatedToolExtension.matchCount(null)).isZero();
    }

    @Test
    void noMatchesAndFailure() {
        var ext = new TruncatedToolExtension();
        var noMatch = ext.run((p, path, glob, cwd) -> "", "foo", null, null, null);
        assertThat(ExampleFixtures.textOf(noMatch.content())).isEqualTo("No matches found");

        var failure = ext.run((p, path, glob, cwd) -> {
            throw new IllegalStateException("connection refused");
        }, "foo", null, null, null);
        assertThat(ExampleFixtures.textOf(failure.content()))
            .isEqualTo("ripgrep failed: connection refused");
        assertThat(failure.isError()).isTrue();
    }

    @Test
    void matchResultAndTruncationNotice() {
        var ext = new TruncatedToolExtension();
        var result = ext.run((p, path, glob, cwd) -> "a.java:1:foo\nb.java:2:foo",
            "foo", null, null, null);
        assertThat(ExampleFixtures.textOf(result.content())).contains("a.java:1:foo");
        assertThat(result.details()).containsEntry("matchCount", 2);

        var big = "x".repeat(6000);
        var trunc = com.pijava.agent.tool.TruncationUtils.truncateHead(big);
        var notice = TruncatedToolExtension.truncationNotice(trunc);
        assertThat(notice).contains("Output truncated: showing")
            .contains("lines").contains("omitted").contains("Full output saved to:");
    }

    @Test
    void toolRegistered() {
        var w = new W3Context();
        new TruncatedToolExtension().register(w.ctx);
        assertThat(w.tools.toolNames()).contains("rg");
    }
}
