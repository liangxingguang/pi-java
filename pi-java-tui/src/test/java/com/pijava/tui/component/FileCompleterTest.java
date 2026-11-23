package com.pijava.tui.component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * K25（pi autocomplete.ts）：{@code @文件} 前缀提取、目录列举、模糊打分、
 * 引号形、CJK 标点分隔、Tab 强制与 slash 上下文抑制。
 */
class FileCompleterTest {

    @TempDir
    Path root;

    private FileCompleter completer;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(root.resolve("src/main"));
        Files.writeString(root.resolve("src/main/Main.java"), "class Main {}");
        Files.writeString(root.resolve("src/Makefile"), "all:");
        Files.writeString(root.resolve("README.md"), "# readme");
        completer = new FileCompleter(root, () -> 5);
    }

    @Test
    void atFileScopedQueryListsDirectory() {
        assertThat(completer.update("@src/ma", false)).isTrue();

        var values = completer.matches().stream().map(FileCompleter.Suggestion::value).toList();
        assertThat(values.get(0)).isEqualTo("@src/main/");
        assertThat(values).contains("@src/Makefile");
    }

    @Test
    void directorySelectionKeepsTrailingSlash() {
        assertThat(completer.update("@src/ma", false)).isTrue();

        assertThat(completer.selectedValue()).isEqualTo("@src/main/");
        assertThat(completer.selectedDirectory()).isTrue();
        assertThat(completer.replaceGraphemes()).isEqualTo("@src/ma".length());
    }

    @Test
    void fileSelectionIsNotADirectory() {
        assertThat(completer.update("@src/ma", false)).isTrue();
        completer.onKeyEvent(dev.tamboui.tui.event.KeyEvent.ofKey(
            dev.tamboui.tui.event.KeyCode.DOWN));

        assertThat(completer.selectedValue()).isEqualTo("@src/Makefile");
        assertThat(completer.selectedDirectory()).isFalse();
    }

    @Test
    void bareAtShowsRootEntries() {
        assertThat(completer.update("@", false)).isTrue();
        assertThat(completer.matches()).isNotEmpty();
    }

    @Test
    void slashCommandNameContextSuppressesFileCompletion() {
        assertThat(completer.update("/mo", false)).isFalse();
    }

    @Test
    void tabForcesPlainPathCompletion() {
        assertThat(completer.update("src/ma", true)).isTrue();
        assertThat(completer.matches().stream().map(FileCompleter.Suggestion::value).toList())
            .contains("src/main/");
    }

    @Test
    void cjkPunctuationSeparatesTokens() {
        assertThat(completer.update("看这个：@src/ma", false)).isTrue();
    }

    @Test
    void quotedAtPrefixKeepsQuotes() {
        assertThat(completer.update("@\"src/ma", false)).isTrue();

        String value = completer.selectedValue();
        assertThat(value).startsWith("@\"src/main");
        assertThat(value).endsWith("\"");
    }

    @Test
    void scoreEntryRanksExactPrefixSubstringAndPath() {
        assertThat(FileCompleter.scoreEntry("src/a", "a", false)).isEqualTo(100);
        assertThat(FileCompleter.scoreEntry("src/abc", "a", false)).isEqualTo(80);
        assertThat(FileCompleter.scoreEntry("src/ba", "a", false)).isEqualTo(50);
        assertThat(FileCompleter.scoreEntry("a/file", "a", false)).isEqualTo(30);
        assertThat(FileCompleter.scoreEntry("src/a", "a", true)).isEqualTo(110);
    }

    @Test
    void completionValueQuotesPathsWithSpaces() {
        assertThat(FileCompleter.buildCompletionValue("my file.txt", true, false))
            .isEqualTo("@\"my file.txt\"");
        assertThat(FileCompleter.buildCompletionValue("plain.txt", false, false))
            .isEqualTo("plain.txt");
    }

    @Test
    void extractAtPrefixRequiresTokenStart() {
        assertThat(FileCompleter.extractAtPrefix("hello @src/ma")).isEqualTo("@src/ma");
        assertThat(FileCompleter.extractAtPrefix("(@src/ma")).isEqualTo("@src/ma");
        assertThat(FileCompleter.extractAtPrefix("mail a@b")).isNull();
    }

    @Test
    void extractPathPrefixHonoursForce() {
        assertThat(FileCompleter.extractPathPrefix("see ./src", false)).isEqualTo("./src");
        assertThat(FileCompleter.extractPathPrefix("hello", false)).isNull();
        assertThat(FileCompleter.extractPathPrefix("hello", true)).isEqualTo("hello");
    }
}
