package com.pijava.ai.identity;

/**
 * Runtime tokens used in the detailed {@code User-Agent}.
 *
 * <p>pi 的 detailed UA 用 {@code process.platform}／{@code node/<version>}／
 * {@code process.arch}；JVM 没有 node，改为 {@code os.name}／{@code java/<version>}／
 * {@code os.arch}（与既有 {@code BugReportCollect} 的 pi-java UA 形一致）。</p>
 *
 * @param platform 平台名
 * @param runtime  运行时 token（形如 {@code java/25}）
 * @param arch     架构名
 */
public record RuntimeInfo(String platform, String runtime, String arch) {

    /** Read the current JVM values. */
    public static RuntimeInfo current() {
        return new RuntimeInfo(
            System.getProperty("os.name", ""),
            "java/" + System.getProperty("java.version", ""),
            System.getProperty("os.arch", ""));
    }
}
