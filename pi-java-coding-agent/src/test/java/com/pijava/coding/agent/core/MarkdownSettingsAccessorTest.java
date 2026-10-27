package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 25（docs/36 T6/T7，E2-43）：markdown 设置 accessor——
 * codeBlockIndent 逐字回落 {@code "  "}（pi settings-manager.ts:1403-1405）、
 * mermaid {@code off/final} 直传其余回落 {@code streaming}（:1407-1410，不消费）。
 */
class MarkdownSettingsAccessorTest {

    private static SettingsManager manager(Path dir) {
        return SettingsManager.withStorage(new FileSettingsStorage(dir, dir));
    }

    @Test
    void codeBlockIndentDefaultsToTwoSpaces(@TempDir Path dir) {
        assertThat(manager(dir).accessors().getMarkdownCodeBlockIndent()).isEqualTo("  ");
    }

    @Test
    void codeBlockIndentReadsConfiguredValue(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("settings.json"),
            "{ \"markdown\": { \"codeBlockIndent\": \"\\t\" } }");
        assertThat(manager(dir).accessors().getMarkdownCodeBlockIndent()).isEqualTo("\t");
    }

    @Test
    void mermaidDefaultsToStreamingAndPassesOffFinal(@TempDir Path dir) throws Exception {
        assertThat(manager(dir).accessors().getMarkdownMermaid()).isEqualTo("streaming");
        Files.writeString(dir.resolve("settings.json"),
            "{ \"markdown\": { \"mermaid\": \"off\" } }");
        assertThat(manager(dir).accessors().getMarkdownMermaid()).isEqualTo("off");
        Files.writeString(dir.resolve("settings.json"),
            "{ \"markdown\": { \"mermaid\": \"bogus\" } }");
        // pi 逐字：非 off/final ⇒ streaming
        assertThat(manager(dir).accessors().getMarkdownMermaid()).isEqualTo("streaming");
    }
}
