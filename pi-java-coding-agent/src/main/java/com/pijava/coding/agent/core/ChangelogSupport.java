package com.pijava.coding.agent.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 批 12 T7（docs/23 §3）：changelog 流，pi {@code utils/changelog.ts}
 * （{@code parseChangelog}:111／{@code compareVersions}:173／
 * {@code getNewEntries}:182-192）＋ {@code getChangelogForDisplay}
 * （interactive-mode.ts:1332-1357）对位。
 *
 * <p>数据为 java 自建内置资源 {@code /CHANGELOG.md}（裁决⑦：pi 同
 * {@code ## [x.y.z]} 头分节格式，内容各自维护——数据文件分叉非窄差）。
 * {@code normalizeChangelogLinks}（相对链接拼绝对）<b>不移植</b>：首发
 * changelog 无相对链接，有需求出现再补。install ping 不移植（裁决⑤，D55）。</p>
 */
public final class ChangelogSupport {

    private ChangelogSupport() {}

    /** pi {@code ChangelogEntry}：数字三段版本＋该节 markdown 正文。 */
    public record ChangelogEntry(int major, int minor, int patch, String content) {}

    private static final Pattern SECTION_HEADER =
        Pattern.compile("^## \\[(\\d+)\\.(\\d+)\\.(\\d+)\\]");

    /** pi {@code parseChangelog}（:111）：按 {@code ## [x.y.z]} 头切分，保持文件序。 */
    public static List<ChangelogEntry> parse(String markdown) {
        var entries = new ArrayList<ChangelogEntry>();
        String[] lines = markdown.split("\n", -1);
        int major = -1;
        int minor = 0;
        int patch = 0;
        var content = new StringBuilder();
        for (var line : lines) {
            var matcher = SECTION_HEADER.matcher(line);
            if (matcher.find()) {
                if (major >= 0) {
                    entries.add(new ChangelogEntry(major, minor, patch,
                        content.toString().trim()));
                }
                major = Integer.parseInt(matcher.group(1));
                minor = Integer.parseInt(matcher.group(2));
                patch = Integer.parseInt(matcher.group(3));
                content.setLength(0);
            } else if (major >= 0) {
                if (!content.isEmpty()) {
                    content.append('\n');
                }
                content.append(line.stripTrailing());
            }
        }
        if (major >= 0) {
            entries.add(new ChangelogEntry(major, minor, patch, content.toString().trim()));
        }
        return List.copyOf(entries);
    }

    /** pi {@code compareVersions}（:173）：major→minor→patch 逐段比。 */
    public static int compareVersions(ChangelogEntry a, ChangelogEntry b) {
        if (a.major() != b.major()) {
            return Integer.compare(a.major(), b.major());
        }
        if (a.minor() != b.minor()) {
            return Integer.compare(a.minor(), b.minor());
        }
        return Integer.compare(a.patch(), b.patch());
    }

    /**
     * pi {@code getNewEntries}（:182-192 逐字对位）：严格大于 {@code lastVersion}
     * 的条目；lastVersion 解析失败段按 0（pi {@code Number() NaN || 0}）。
     */
    public static List<ChangelogEntry> getNewEntries(List<ChangelogEntry> entries,
                                                     String lastVersion) {
        var parts = lastVersion.split("\\.");
        var last = new ChangelogEntry(num(parts, 0), num(parts, 1), num(parts, 2), "");
        return entries.stream().filter(e -> compareVersions(e, last) > 0).toList();
    }

    /** pi {@code parts.map(Number)} 的 {@code || 0} 臂：非数字/缺席段 ⇒ 0。 */
    private static int num(String[] parts, int index) {
        if (index >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[index].trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 读内置资源（pi {@code getChangelogPath} 对位：发行包根 CHANGELOG.md）。 */
    public static String loadBundled() throws IOException {
        try (var in = ChangelogSupport.class.getResourceAsStream("/CHANGELOG.md")) {
            if (in == null) {
                return "";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 不抛形态（TUI/slash 消费点；读失败 ⇒ 空串 ≙ 无条目）。 */
    public static String loadBundledUnchecked() {
        try {
            return loadBundled();
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * pi {@code getChangelogForDisplay}（interactive-mode.ts:1332-1357）三臂：
     * 会话已有消息 ⇒ empty（resume/continue 不显示）；无 lastVersion ⇒ 全新安装
     * 只写版本号不显示；有新条目 ⇒ 写回版本号＋返回新条目拼接。install ping 不
     * 移植（裁决⑤）。写回走 markModified＋会话关闭 flush（批 11 setter 惯例）。
     */
    public static Optional<String> forDisplay(SettingsManager settings, String version,
                                              boolean sessionHasMessages) {
        if (sessionHasMessages) {
            return Optional.empty();
        }
        var install = settings.install();
        var last = install.getLastChangelogVersion();
        var entries = parse(loadBundledUnchecked());
        if (last == null || last.isBlank()) {
            install.setLastChangelogVersion(version);
            return Optional.empty();
        }
        var newEntries = getNewEntries(entries, last);
        if (newEntries.isEmpty()) {
            return Optional.empty();
        }
        install.setLastChangelogVersion(version);
        return Optional.of(newEntries.stream()
            .map(ChangelogEntry::content).collect(Collectors.joining("\n\n")));
    }

    /**
     * 折叠臂文案用的版本号（pi 折叠行显示当前版本）：markdown 里首个
     * {@code x.y.z} 形，缺失 ⇒ 调用方给的当前版本。
     */
    public static Optional<String> latestVersionOf(String markdown) {
        var matcher = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)").matcher(markdown);
        return matcher.find() ? Optional.of(matcher.group()) : Optional.empty();
    }
}
