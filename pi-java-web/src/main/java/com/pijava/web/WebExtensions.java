package com.pijava.web;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.pijava.coding.agent.core.source.SourceInfo;
import com.pijava.coding.agent.extension.InstalledExtension;

import com.pijava.web.WebProtocol.ExtensionInfo;

/**
 * 扩展面板数据源（web 独有面）——把会话的两份<b>既有公开视图</b>合成一张清单：
 *
 * <ul>
 *   <li>{@code AgentSession.extensionSourceInfos()}：本次会话<b>已加载</b>的扩展
 *       及其 {@link SourceInfo}（path/source/scope/origin）。身份有两种形态：
 *       ServiceLoader 自动发现的 {@code builtin:<name>}，与 JAR 文件路径。</li>
 *   <li>{@code AgentSession.extensionResourcePaths()}：包里 resolve 出的扩展资源
 *       路径，经 {@link InstalledExtension#fromResolvedPaths} 读 JAR 内
 *       {@code META-INF/pi-extension.json}，得名字/版本/描述与四类资源名表。</li>
 * </ul>
 *
 * <p>两侧按<b>规范路径</b>对齐（Windows 折叠大小写，同 {@code ExtensionSourceInfos}
 * 的比较键约定）：命中的行同时携带清单元数据；只出现在 resolve 表的行 =
 * 「已安装但本次会话没加载」（被 filter 裁剪或加载失败），前端灰显。</p>
 *
 * <p>本类不改任何其他模块的 API：两条数据源都是 {@code AgentSession} 既有 public
 * 方法，元数据读取是 {@code InstalledExtension} 既有 public 静态面。</p>
 */
final class WebExtensions {

    /** 合成身份前缀（pi {@code BUILTIN_PATH_PREFIX}，source-info.ts:15）。 */
    private static final String BUILTIN_PREFIX =
        com.pijava.coding.agent.pkg.PackageManager.BUILTIN_PATH_PREFIX;

    /** 进程内注册身份的合成前缀（{@code ExtensionManager.registerInline} 约定）。 */
    private static final String INLINE_PREFIX = "inline:";

    /** 展示序：已加载在前，各自按名忽略大小写升序。 */
    private static final Comparator<ExtensionInfo> ORDER =
        Comparator.comparing(ExtensionInfo::loaded, Comparator.reverseOrder())
            .thenComparing(ExtensionInfo::name, String.CASE_INSENSITIVE_ORDER);

    private WebExtensions() {
    }

    /**
     * 合成扩展清单。
     *
     * @param loaded        会话已加载扩展的 sourceInfo（{@code null} 视作空表）
     * @param resolvedPaths resolve 出的扩展资源路径（{@code null} 视作空表）
     * @return 已排序的清单行（不可变）
     */
    static List<ExtensionInfo> list(List<SourceInfo> loaded, List<String> resolvedPaths) {
        var metadata = metadataByPath(resolvedPaths);
        var seen = new HashSet<String>();
        var rows = new ArrayList<ExtensionInfo>();
        for (var info : loaded == null ? List.<SourceInfo>of() : loaded) {
            var path = info.path() == null ? "" : info.path();
            seen.add(key(path));
            rows.add(loadedRow(path, info, metadata.get(key(path))));
        }
        for (var installed : metadata.values()) {
            var path = installed.path() == null ? "" : installed.path().toString();
            if (seen.contains(key(path))) {
                continue;
            }
            rows.add(notLoadedRow(path, installed));
        }
        rows.sort(ORDER);
        return List.copyOf(rows);
    }

    /** 已加载行：sourceInfo 给来源三档，清单给内容四表（无清单则留空）。 */
    private static ExtensionInfo loadedRow(String path, SourceInfo info,
                                           InstalledExtension installed) {
        var builtin = path.startsWith(BUILTIN_PREFIX);
        var name = installed == null || installed.name() == null || installed.name().isBlank()
            ? displayName(path) : installed.name();
        return new ExtensionInfo(
            name,
            path,
            info.source(),
            info.scope() == null ? null : info.scope().wire(),
            info.origin() == null ? null : info.origin().wire(),
            builtin,
            true,
            text(installed == null ? null : installed.version()),
            text(installed == null ? null : installed.description()),
            names(installed == null ? null : installed.tools()),
            names(installed == null ? null : installed.commands()),
            names(installed == null ? null : installed.providers()),
            names(installed == null ? null : installed.skills()));
    }

    /** 已安装但未加载行：来源三档缺席（无 sourceInfo），清单内容照显。 */
    private static ExtensionInfo notLoadedRow(String path, InstalledExtension installed) {
        var name = installed.name() == null || installed.name().isBlank()
            ? displayName(path) : installed.name();
        return new ExtensionInfo(
            name,
            path,
            null,
            null,
            null,
            false,
            false,
            text(installed.version()),
            text(installed.description()),
            names(installed.tools()),
            names(installed.commands()),
            names(installed.providers()),
            names(installed.skills()));
    }

    /** 清单元数据表：规范路径 → 展示记录（保持 resolve 序）。 */
    private static Map<String, InstalledExtension> metadataByPath(List<String> resolvedPaths) {
        var map = new LinkedHashMap<String, InstalledExtension>();
        if (resolvedPaths == null) {
            return map;
        }
        for (var installed : InstalledExtension.fromResolvedPaths(resolvedPaths)) {
            if (installed.path() == null) {
                continue;
            }
            map.putIfAbsent(key(installed.path().toString()), installed);
        }
        return map;
    }

    /**
     * 名字回落：{@code builtin:hello} → {@code hello}；{@code inline:hello} →
     * {@code hello}；JAR 路径 → 去后缀文件名。
     */
    private static String displayName(String path) {
        if (path.startsWith(BUILTIN_PREFIX)) {
            var name = path.substring(BUILTIN_PREFIX.length());
            return name.isEmpty() ? path : name;
        }
        if (path.startsWith(INLINE_PREFIX)) {
            var name = path.substring(INLINE_PREFIX.length());
            return name.isEmpty() ? path : name;
        }
        var normalized = path.replace('\\', '/');
        var slash = normalized.lastIndexOf('/');
        var file = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        if (file.isEmpty()) {
            return path;
        }
        return file.endsWith(".jar") ? file.substring(0, file.length() - 4) : file;
    }

    /** 路径比较键（{@code ExtensionSourceInfos} 同约定：resolve+normalize，Windows 折叠大小写）。 */
    private static String key(String path) {
        try {
            var resolved = Path.of(path).toAbsolutePath().normalize().toString();
            return windows() ? resolved.toLowerCase(Locale.ROOT) : resolved;
        } catch (InvalidPathException e) {
            return path;
        }
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static List<String> names(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
