package com.pijava.coding.agent.core;

import java.nio.file.Path;
import java.util.List;

import com.pijava.coding.agent.core.slash.CommandRegistry;
import com.pijava.coding.agent.core.slash.SlashContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 12 T7（docs/23 §3/§7）：changelog 流——pi {@code parseChangelog}（:111）
 * 分节、{@code compareVersions}（:173）逐段、{@code getNewEntries}（:182-192，
 * 非法段按 0）、{@code getChangelogForDisplay}（interactive-mode.ts:1332-1357）
 * 三臂；{@code /changelog}（:6774-6793）全量 reverse 且<b>不回写</b>。
 */
class ChangelogSupportTest {

    private static final String SAMPLE = """
        # Changelog

        ## [0.2.0]

        - Second thing
        - Another second

        ## [0.1.0]

        - First thing
        """;

    private static SettingsManager manager(Path dir) {
        return SettingsManager.withStorage(new FileSettingsStorage(dir, dir));
    }

    @Test
    void parseSplitsOnVersionHeaders() {
        var entries = ChangelogSupport.parse(SAMPLE);

        assertThat(entries).containsExactly(
            new ChangelogSupport.ChangelogEntry(0, 2, 0, "- Second thing\n- Another second"),
            new ChangelogSupport.ChangelogEntry(0, 1, 0, "- First thing"));
    }

    @Test
    void compareVersionsOrdersPerSegment() {
        var e = ChangelogSupport.parse(SAMPLE);
        assertThat(ChangelogSupport.compareVersions(e.get(0), e.get(1))).isPositive();
        assertThat(ChangelogSupport.compareVersions(e.get(1), e.get(0))).isNegative();
        assertThat(ChangelogSupport.compareVersions(e.get(0), e.get(0))).isZero();
    }

    @Test
    void getNewEntriesFiltersStrictlyGreaterWithLenientParse() {
        var entries = ChangelogSupport.parse(SAMPLE);

        assertThat(ChangelogSupport.getNewEntries(entries, "0.1.0"))
            .containsExactly(entries.get(0));
        // pi Number() NaN || 0：非法段按 0 ⇒ "0.2.x" 的 last 吃掉 0.2.0。
        assertThat(ChangelogSupport.getNewEntries(entries, "0.2.0-SNAPSHOT")).isEmpty();
        assertThat(ChangelogSupport.getNewEntries(entries, "garbage"))
            .containsExactlyElementsOf(entries);
    }

    @Test
    void bundledChangelogParsesToAtLeastOneEntry() {
        var entries = ChangelogSupport.parse(ChangelogSupport.loadBundledUnchecked());

        assertThat(entries).isNotEmpty();
        assertThat(entries.get(0).content()).isNotBlank();
    }

    // ── forDisplay 三臂（pi :1332-1357）────────────────────────

    @Test
    void forDisplaySkipsSessionsWithMessages(@TempDir Path dir) {
        var settings = manager(dir);

        assertThat(ChangelogSupport.forDisplay(settings, "9.9.9", true)).isEmpty();
        // 不写回（会话有消息臂在一切之前）。
        assertThat(settings.install().getLastChangelogVersion()).isNull();
    }

    @Test
    void forDisplayFreshInstallOnlyStampsVersion(@TempDir Path dir) {
        var settings = manager(dir);

        assertThat(ChangelogSupport.forDisplay(settings, "9.9.9", false)).isEmpty();
        assertThat(settings.install().getLastChangelogVersion()).isEqualTo("9.9.9");
    }

    @Test
    void forDisplayStampsAndReturnsNewEntries(@TempDir Path dir) {
        var settings = manager(dir);
        settings.install().setLastChangelogVersion("0.0.1");
        var bundled = ChangelogSupport.parse(ChangelogSupport.loadBundledUnchecked());

        var shown = ChangelogSupport.forDisplay(settings, "9.9.9", false);

        assertThat(shown).isPresent();
        // 内置条目全部 > 0.0.1 ⇒ 全量拼接返回。
        for (var entry : bundled) {
            assertThat(shown.get()).contains(entry.content());
        }
        assertThat(settings.install().getLastChangelogVersion()).isEqualTo("9.9.9");
        // 写回后不再重复显示（内置条目 ≤ 9.9.9）。
        assertThat(ChangelogSupport.forDisplay(settings, "9.9.9", false)).isEmpty();
    }

    @Test
    void forDisplayNoNewEntriesStaysQuiet(@TempDir Path dir) {
        var settings = manager(dir);
        settings.install().setLastChangelogVersion("99.0.0");

        assertThat(ChangelogSupport.forDisplay(settings, "99.0.1", false)).isEmpty();
        // 无新条目臂**不写回**（pi 同——回写只在全新安装与有更新两臂）。
        assertThat(settings.install().getLastChangelogVersion()).isEqualTo("99.0.0");
    }

    // ── /changelog（pi :6774-6793）：全量 reverse，不回写 ───────

    @Test
    void slashChangelogReturnsReversedEntriesWithoutWriteback(@TempDir Path dir) {
        var settings = manager(dir);
        settings.install().setLastChangelogVersion("0.0.1");
        var registry = CommandRegistry.withBuiltins();

        var result = registry.dispatch("/changelog", SlashContext.of(null))
            .toCompletableFuture().join();

        var bundled = ChangelogSupport.parse(ChangelogSupport.loadBundledUnchecked());
        assertThat(result).startsWith("What's New\n\n").contains(bundled.get(0).content());
        // /changelog 不回写 lastChangelogVersion（pi :6791-6793 注记）。
        assertThat(settings.install().getLastChangelogVersion()).isEqualTo("0.0.1");
    }

    @Test
    void latestVersionOfFindsFirstVersionShape() {
        assertThat(ChangelogSupport.latestVersionOf("notes for 0.2.0 release"))
            .contains("0.2.0");
        assertThat(ChangelogSupport.latestVersionOf("no versions here")).isEmpty();
    }

    @Test
    void collapseNoticeUsesVerbatimText() {
        // pi :843-872 折叠臂文案逐字【模型可见】。
        var version = ChangelogSupport.latestVersionOf("x 0.2.0 y").orElseThrow();
        var line = "Updated to v" + version + ". Use /changelog to view full changelog.";
        assertThat(line).isEqualTo(
            "Updated to v0.2.0. Use /changelog to view full changelog.");
    }

    @Test
    void entriesKeepFileOrderBeforeReverse() {
        // 文件序（旧→新）；/changelog 才 reverse（最新在前）。
        List<ChangelogSupport.ChangelogEntry> entries = ChangelogSupport.parse(SAMPLE);
        var reversed = new java.util.ArrayList<>(entries);
        java.util.Collections.reverse(reversed);
        assertThat(reversed.get(0).content()).isEqualTo("- First thing");
    }
}
