package com.pijava.coding.agent.pkg;

import java.util.Locale;

/**
 * pi {@code isOfflineModeEnabled}（package-manager.ts:54-60）——{@code PI_OFFLINE}
 * ∈ {@code {1, true, yes}}（大小写不敏感）⇒ 跳过一切网络动作。core 的
 * {@code ModelCatalogRefresh.truthyEnvFlag} 同款判定（包管理器侧文件内 helper
 * 对位，不跨包复用）。
 */
public final class PackageEnvironment {

    private PackageEnvironment() {}

    public static boolean isOfflineModeEnabled() {
        return isOfflineModeEnabled(System.getenv("PI_OFFLINE"));
    }

    /** 可测形（值注入）。 */
    public static boolean isOfflineModeEnabled(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        if (value.equals("1")) {
            return true;
        }
        var lower = value.toLowerCase(Locale.ROOT);
        return lower.equals("true") || lower.equals("yes");
    }
}
