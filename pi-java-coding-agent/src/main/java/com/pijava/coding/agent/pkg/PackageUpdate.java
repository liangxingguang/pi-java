package com.pijava.coding.agent.pkg;

import java.util.Locale;

/**
 * pi {@code PackageUpdate}（package-manager.ts:100-105）——{@code checkForAvailableUpdates}
 * 的结果行（启动更新通知的操作数，批 19 T3/T7）。
 */
public record PackageUpdate(String source, String displayName, Type type, SourceScope scope) {

    /** pi {@code type} 字面量闭集（{@code "npm" | "git"}）⇒ enum。 */
    public enum Type {
        NPM, GIT;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
