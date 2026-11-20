package com.pijava.tui.component.session;

import java.nio.file.Path;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/63（L7 W3，pi {@code getSessionPathKey}）：realpath（失败回落）+ resolve +
 * Windows 折小写。
 */
class SessionPathKeyTest {

    @Test
    void relativeAndAbsoluteFormsAreTheSameSession() {
        var absolute = Path.of("tmp", "x", "s.jsonl").toAbsolutePath().toString();

        assertThat(SessionPathKey.same(absolute, Path.of("tmp", "x", "s.jsonl").toString())).isTrue();
        assertThat(SessionPathKey.same(absolute, absolute + java.io.File.separator)).isTrue();
    }

    @Test
    void differentPathsAreNotTheSameSession() {
        assertThat(SessionPathKey.same("tmp/x/a.jsonl", "tmp/x/b.jsonl")).isFalse();
        assertThat(SessionPathKey.same(null, "tmp/x/a.jsonl")).isFalse();
        assertThat(SessionPathKey.same(null, null)).isTrue();
    }

    @Test
    void windowsFoldsCase() {
        Assumptions.assumeTrue(
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows"),
            "Windows 专有语义");

        assertThat(SessionPathKey.same("C:\\Temp\\A.jsonl", "c:/temp/a.jsonl")).isTrue();
    }
}
