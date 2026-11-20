package com.pijava.coding.agent.core.render;

/**
 * docs/60（L7 W1b）：工具行的路径显示——pi {@code shortenHomePath}
 * （{@code utils/paths.ts:72-78}）与 {@code renderToolPath}
 * （{@code core/tools/render-utils.ts:170-180}）的文本级对位。
 *
 * <p>分隔符统一折成 {@code /}（与 examples 的 {@code MinimalModeExtension.shortenPath}
 * 同一方言）；home 前缀折成 {@code ~}，相对路径原样。</p>
 */
public final class DisplayPath {

    private DisplayPath() {}

    /** pi {@code shortenHomePath(path)}：以当前用户 home 收缩。 */
    public static String shorten(String path) {
        return shorten(path, System.getProperty("user.home", ""));
    }

    /**
     * 以显式 {@code home} 收缩（测试缝）。
     *
     * @param path 原始路径（可为 null/相对路径）
     * @param home home 目录（空 ⇒ 不收缩）
     * @return {@code ~}／{@code ~/x}／原路径
     */
    public static String shorten(String path, String home) {
        if (path == null || path.isEmpty() || home == null || home.isEmpty()) {
            return path == null ? "" : path;
        }
        var p = path.replace('\\', '/');
        var h = home.replace('\\', '/');
        while (h.length() > 1 && h.endsWith("/")) {
            h = h.substring(0, h.length() - 1);
        }
        if (!p.regionMatches(true, 0, h, 0, h.length())) {
            return path;
        }
        if (p.length() > h.length() && p.charAt(h.length()) != '/') {
            return path;
        }
        var rest = p.substring(h.length());
        if (rest.startsWith("/")) {
            rest = rest.substring(1);
        }
        return rest.isEmpty() ? "~" : "~/" + rest;
    }

    /**
     * pi {@code renderToolPath}：非字符串 ⇒ {@code [invalid arg]}；
     * 空串 ⇒ {@code emptyFallback}；否则收缩后的路径。
     *
     * @param rawPath       {@code str()} 的结果（{@code null} ≙ 非字符串）
     * @param emptyFallback 空串时的占位（read/write 为 {@code ...}，ls 为 {@code .}）
     */
    static String display(String rawPath, String emptyFallback) {
        if (rawPath == null) {
            return "[invalid arg]";
        }
        return rawPath.isEmpty() ? emptyFallback : shorten(rawPath);
    }
}
