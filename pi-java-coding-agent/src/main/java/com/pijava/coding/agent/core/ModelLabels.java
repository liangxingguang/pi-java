package com.pijava.coding.agent.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * docs/62（L7 W2）：模型选择器行的"去重式别名"——pi
 * {@code modes/interactive/model-search.ts:19-34} {@code getDistinctModelName} 逐字。
 *
 * <p>语义：模型展示名（{@code name}）按 {@code \p{L}\p{N}} 分词后，<b>每个词</b>都被
 * id 的词表或紧凑 id 覆盖 ⇒ 名字没有新增信息，返回 {@code null}（不显示）；
 * 只要有一个词是 id 里没有的（重命名、区域后缀等）⇒ 返回 trim 后的名字。</p>
 *
 * <p>单字符词必须整词命中 id 词表（{@code "4"} 不被 {@code "mistral-small-2603"} 覆盖）。
 * 分词用 {@link Character#isLetterOrDigit(int)} 逐码点切，等价于 pi 的
 * {@code \p{L}\p{N}}（正则字符类在 Java 侧对 CJK/重音名的判定一致）。</p>
 */
public final class ModelLabels {

    private ModelLabels() {}

    /**
     * pi {@code getDistinctModelName(item)}。
     *
     * @param id   模型 id（如 {@code claude-sonnet-4-5}）
     * @param name 展示名（如 {@code Claude Sonnet 4.5}），可为 null
     * @return 需要额外显示的别名；{@code null} ⇒ 与 id 重复，不显示
     */
    public static String distinctName(String id, String name) {
        if (name == null) {
            return null;
        }
        var trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        var idWords = splitLabel(id);
        var idTokens = new HashSet<>(idWords);
        var compactId = String.join("", idWords);
        for (var word : splitLabel(trimmed)) {
            var covered = idTokens.contains(word)
                || (word.length() > 1 && compactId.contains(word));
            if (!covered) {
                return trimmed;
            }
        }
        return null;
    }

    /** pi {@code splitModelLabel}：小写后按非字母数字切分，丢空段。 */
    static List<String> splitLabel(String value) {
        if (value == null || value.isEmpty()) {
            return List.of();
        }
        var lower = value.toLowerCase(Locale.ROOT);
        var words = new ArrayList<String>();
        var current = new StringBuilder();
        for (int i = 0; i < lower.length();) {
            int codePoint = lower.codePointAt(i);
            if (Character.isLetterOrDigit(codePoint)) {
                current.appendCodePoint(codePoint);
            } else if (!current.isEmpty()) {
                words.add(current.toString());
                current.setLength(0);
            }
            i += Character.charCount(codePoint);
        }
        if (!current.isEmpty()) {
            words.add(current.toString());
        }
        return List.copyOf(words);
    }
}
