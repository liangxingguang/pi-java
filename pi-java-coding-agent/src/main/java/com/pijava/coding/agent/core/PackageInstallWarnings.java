package com.pijava.coding.agent.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pijava.coding.agent.pkg.PackageInstallFailure;
import com.pijava.coding.agent.pkg.ResolvedPaths;
import com.pijava.coding.agent.pkg.SourceScope;

/**
 * L5 W5 §6.1（pi {@code resource-loader.ts:105-133}）：装不上的配置包的启动警告
 * ——文案逐字（品牌 {@code pi-java}），按 source 去重保序。
 *
 * <p>三档提示：user/project ⇒ 教用户 {@code remove}（project 带 {@code --local}）；
 * temporary（{@code -e} 显式源）⇒ 没有 settings 条目可删，改为「检查源后重试」。</p>
 */
public final class PackageInstallWarnings {

    /** pi {@code APP_NAME} 的 java 品牌。 */
    private static final String APP_NAME = "pi-java";

    private PackageInstallWarnings() {}

    /** pi {@code formatPackageInstallWarning}：一行启动警告。 */
    public static String format(PackageInstallFailure failure) {
        var hint = failure.scope() == SourceScope.TEMPORARY
            ? "Check the source and try again."
            : "Run \"" + APP_NAME + " remove " + failure.source()
                + (failure.scope() == SourceScope.PROJECT ? " --local" : "")
                + "\" to remove it from settings.json.";
        return "Extension package \"" + failure.source() + "\": failed to install ("
            + failure.message() + "). " + hint;
    }

    /**
     * pi {@code collectPackageInstallWarnings}＋{@code mergeExtensionWarnings} 的去重
     * 语义：主 resolve 与 CLI 显式源的失败合流，<b>同 source 后写覆盖先写</b>
     * （pi {@code new Map([...list].map(([path, value]) => [path, value]))}），保插入序。
     */
    public static List<String> collect(ResolvedPaths... resolvedTables) {
        var bySource = new LinkedHashMap<String, PackageInstallFailure>();
        for (var table : resolvedTables) {
            if (table == null) {
                continue;
            }
            for (var failure : table.installFailures()) {
                bySource.put(failure.source(), failure);
            }
        }
        return bySource.values().stream().map(PackageInstallWarnings::format).toList();
    }

    /** 按失败表分组（诊断路径用：{@code path}=source、{@code warning}=正文）。 */
    public static Map<String, String> bySource(ResolvedPaths... resolvedTables) {
        var out = new LinkedHashMap<String, String>();
        for (var table : resolvedTables) {
            if (table == null) {
                continue;
            }
            for (var failure : table.installFailures()) {
                out.put(failure.source(), format(failure));
            }
        }
        return out;
    }
}
