package com.pijava.evals.fixtures;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 批 32（docs/44 §8.7/D183）：footer 行为 oracle —— pi TUI eval 的
 * {@code context-footer} oracle 的 Java 等价形态。
 *
 * <p>{@code pi-java-evals} 不依赖 {@code pi-java-tui}，因此这里复刻
 * {@code ExtensionInjectionLayer} 扩展状态段的**行为文本**（不是渲染元素）：</p>
 * <ul>
 *   <li>按键字典序排序；</li>
 *   <li>{@code [\r\n\t] → 空格}、连续空格折叠、trim（pi {@code sanitizeStatusText}）；</li>
 *   <li>单空格 join；</li>
 *   <li>超过终端宽度时按 codepoint 截断并加 {@code "..."}（pi {@code truncateToWidth}）。</li>
 * </ul>
 *
 * <p>未实现 TUI 的显示宽度（宽字符占两列）细节，按每 codepoint 一列近似；这是
 * 只用于期望问答文本的窄差（T13 登记 D189 族）。</p>
 */
public final class RecordingFooterOracle {

    private final Map<String, String> statuses = new LinkedHashMap<>();

    /**
     * Record one extension status (last write wins).
     *
     * @param key  extension key
     * @param text status text, {@code null} removes the entry
     */
    public void record(String key, String text) {
        if (text == null) {
            statuses.remove(key);
        } else {
            statuses.put(key, text);
        }
    }

    /** @return recorded statuses in insertion order */
    public Map<String, String> statuses() {
        return Map.copyOf(statuses);
    }

    /** Clear all records. */
    public void reset() {
        statuses.clear();
    }

    /**
     * Render the footer status line text.
     *
     * @param width terminal width in columns
     * @return rendered text, {@code null} when no status is recorded
     */
    public String footerText(int width) {
        if (statuses.isEmpty()) {
            return null;
        }
        var sorted = new TreeMap<>(statuses);
        var joined = String.join(" ", sorted.values().stream()
            .map(RecordingFooterOracle::sanitizeStatusText).toList());
        return truncateToWidth(joined, Math.max(1, width));
    }

    /**
     * pi {@code sanitizeStatusText} 的等价实现.
     *
     * @param text raw status text
     * @return sanitized text
     */
    public static String sanitizeStatusText(String text) {
        return text.replaceAll("[\\r\\n\\t]", " ").replaceAll(" +", " ").trim();
    }

    /**
     * pi {@code truncateToWidth} 的等价实现（省略号 {@code "..."}）.
     *
     * @param text     text
     * @param maxWidth max columns
     * @return truncated text
     */
    public static String truncateToWidth(String text, int maxWidth) {
        if (maxWidth <= 0) {
            return "";
        }
        if (text.length() <= maxWidth) {
            return text;
        }
        if (maxWidth <= 3) {
            return "...".substring(0, maxWidth);
        }
        return text.substring(0, maxWidth - 3) + "...";
    }
}
