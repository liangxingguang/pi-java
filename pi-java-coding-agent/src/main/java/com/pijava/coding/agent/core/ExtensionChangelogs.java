package com.pijava.coding.agent.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import com.pijava.coding.agent.core.source.SourceInfo;

/**
 * L5 W4（B277b）：扩展包 changelog 启动展示（pi interactive-mode.ts:1384-1432、
 * source-info.ts:57/77、settings-manager.ts:690-697）。
 *
 * <ul>
 *   <li>只看 {@code origin=package} 且带 {@code baseDir}/{@code changelogPath} 的扩展；</li>
 *   <li>按 {@code getSourcePackageKey({source,scope})} 去重；</li>
 *   <li>读包 {@code package.json} 的 {@code name/version}；无 lastVersion ⇒ 只记
 *       版本不展示（全新安装）；</li>
 *   <li>{@code getNewEntries} 有内容 ⇒ 记版本并展示；每条 {@code ## x.y.z} 标题前
 *       插包名。</li>
 * </ul>
 *
 * <p>折叠文案与 “Extension Updates” 头的 UI 装配在 TUI 侧（本类只产数据）。</p>
 */
public final class ExtensionChangelogs {

    private ExtensionChangelogs() {}

    /** 一条待展示更新（pi {@code ExtensionChangelogUpdate}）。 */
    public record Update(String name, String version, String markdown) {}

    /** 包 identity 键（pi {@code getSourcePackageKey}：{@code JSON.stringify({source,scope})}）。 */
    public static String packageKey(SourceInfo info) {
        // 固定键序，保证跨进程稳定。
        return "{\"source\":\"" + jsonString(info.source())
            + "\",\"scope\":\"" + jsonString(info.scope().wire()) + "\"}";
    }

    /**
     * 计算启动展示集（已按包去重、按扩展表序）。
     *
     * @param extensions          扩展的 sourceInfo 列表
     * @param settings            settings 访问器（版本键读写）
     * @param sessionHasMessages  resumed/continued 会话已有消息 ⇒ 不展示
     */
    public static List<Update> compute(List<SourceInfo> extensions,
                                       SettingsAccessors settings,
                                       boolean sessionHasMessages) {
        if (sessionHasMessages) {
            return List.of();
        }
        var seen = new LinkedHashSet<String>();
        var updates = new ArrayList<Update>();
        for (var info : extensions) {
            if (info.origin() != SourceInfo.Origin.PACKAGE || info.baseDir() == null
                    || info.changelogPath() == null) {
                continue;
            }
            var key = packageKey(info);
            if (!seen.add(key)) {
                continue;
            }
            var pkg = readPackageJson(Path.of(info.baseDir()));
            if (pkg == null || pkg.version() == null) {
                continue;
            }
            var lastVersion = settings.getExtensionChangelogVersion(key);
            if (lastVersion == null || lastVersion.isBlank()) {
                settings.setExtensionChangelogVersion(key, pkg.version());
                continue;
            }
            var entries = ChangelogSupport.getNewEntries(
                ChangelogSupport.parse(readFile(info.changelogPath())), lastVersion);
            if (entries.isEmpty()) {
                continue;
            }
            settings.setExtensionChangelogVersion(key, pkg.version());
            var name = pkg.name() != null ? pkg.name() : info.source();
            // pi：条目标题形如 "## [x.y.z]"；Java 的 parse 只保留正文 ⇒ 这里重建
            // 标题并在 "## " 后插入包名（渲染结果同 pi 的 "## <name> [x.y.z]"）。
            var markdown = entries.stream()
                .map(entry -> "## " + name + " [" + entry.major() + "." + entry.minor()
                    + "." + entry.patch() + "]\n\n" + entry.content())
                .collect(java.util.stream.Collectors.joining("\n\n"))
                .trim();
            updates.add(new Update(name, pkg.version(), markdown));
        }
        return List.copyOf(updates);
    }

    /** 折叠臂逐字文案（pi interactive-mode.ts:1418-1423）：每包一行。 */
    public static String condensedText(List<Update> updates) {
        return updates.stream()
            .map(update -> "Updated " + update.name() + " to v" + update.version() + ".")
            .collect(java.util.stream.Collectors.joining("\n"));
    }

    private record PackageIdentity(String name, String version) {}

    private static PackageIdentity readPackageJson(Path packageRoot) {
        try {
            var content = Files.readString(packageRoot.resolve("package.json"));
            if (content.startsWith("﻿")) {
                content = content.substring(1);
            }
            JsonNode pkg = Json.mapper().readTree(content);
            if (pkg == null || !pkg.isObject()) {
                return null;
            }
            var name = pkg.get("name");
            var version = pkg.get("version");
            return new PackageIdentity(
                name != null && name.isTextual() ? name.asText() : null,
                version != null && version.isTextual() ? version.asText() : null);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String readFile(String path) {
        try {
            return Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    private static String jsonString(String value) {
        return new String(com.fasterxml.jackson.core.io.JsonStringEncoder.getInstance()
            .quoteAsString(value == null ? "" : value));
    }
}
