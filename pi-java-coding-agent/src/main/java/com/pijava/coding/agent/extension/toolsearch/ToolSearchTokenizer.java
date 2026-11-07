package com.pijava.coding.agent.extension.toolsearch;

import java.text.BreakIterator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * pi {@code tool-search/tool.ts:tokenize}（:71-96 逐字）：NFC 规范化 → camel/缩写
 * 边界 → 小写 → 非字母/标记/数字替换为空格 → ASCII 快路径直接切空格，否则用
 * {@link BreakIterator} 取词；停用词与英文单数化只作用于纯 ASCII 词。
 *
 * <p><b>D224</b>：{@code Intl.Segmenter} 的词典分词与 JDK 的 UAX#29 断词在连续
 * 汉字上不同（浏览器把「搜索仓库问题」切成三个词，JDK 给整段）；本实现按 JDK
 * 行为对齐，测试以词组与混合标识符为主。</p>
 */
public final class ToolSearchTokenizer {

    /** pi {@code STOP_WORDS}（:39-61，21 个英文停用词）。 */
    private static final Set<String> STOP_WORDS = Set.of(
        "a", "an", "and", "are", "as", "at", "be", "by", "for", "from",
        "in", "is", "it", "of", "on", "or", "that", "the", "this", "to", "with");

    /** pi :72 的 camel 边界（{@code ([a-z0-9])([A-Z])} ⇒ 插空格）。 */
    private static final Pattern LOWER_UPPER = Pattern.compile("([a-z0-9])([A-Z])");

    /** pi :73 的缩写边界（{@code ([A-Z]+)([A-Z][a-z])} ⇒ 插空格）。 */
    private static final Pattern ACRONYM_WORD = Pattern.compile("([A-Z]+)([A-Z][a-z])");

    /** pi :75 的非词字符（{@code [^\p{L}\p{M}\p{N}]+} ⇒ 空格）。 */
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{M}\\p{N}]+");

    /** pi :78 的 ASCII 快路径判定（{@code /^[a-z0-9 ]*$/}）。 */
    private static final Pattern ASCII_ONLY = Pattern.compile("^[a-z0-9 ]*$");

    /** pi :79 的「纯 ASCII 词才做停用词与单数化」判定。 */
    private static final Pattern PLAIN_ASCII = Pattern.compile("^[a-z0-9]+$");

    /** pi :74-78 的复数尾巴（ches/shes/sses/xes/zes）。 */
    private static final Pattern ES_SUFFIX = Pattern.compile("(ches|shes|sses|xes|zes)$");

    private ToolSearchTokenizer() {}

    /** pi {@code stem}（:63-69 逐字——朴素单数化）。 */
    static String stem(String term) {
        if (term.length() > 4 && term.endsWith("ies")) {
            return term.substring(0, term.length() - 3) + "y";
        }
        if (term.length() > 4 && ES_SUFFIX.matcher(term).find()) {
            return term.substring(0, term.length() - 2);
        }
        if (term.length() > 3 && term.endsWith("s") && !term.endsWith("ss")) {
            return term.substring(0, term.length() - 1);
        }
        return term;
    }

    /** pi {@code tokenize}（:71-80 逐字）。 */
    public static List<String> tokenize(String text) {
        if (text == null) {
            return List.of();
        }
        var spaced = ACRONYM_WORD.matcher(
            LOWER_UPPER.matcher(Normalizer.normalize(text, Normalizer.Form.NFC))
                .replaceAll("$1 $2")).replaceAll("$1 $2");
        var normalized = NON_WORD.matcher(spaced.toLowerCase(Locale.ROOT)).replaceAll(" ");

        List<String> words;
        if (ASCII_ONLY.matcher(normalized).matches()) {
            // The segmenter costs about 15 times a split.（pi :76-77）
            words = List.of(normalized.split(" "));
        } else {
            var segmenter = BreakIterator.getWordInstance(Locale.ROOT);
            segmenter.setText(normalized);
            var segmented = new ArrayList<String>();
            int start = segmenter.first();
            for (int end = segmenter.next(); end != BreakIterator.DONE;
                    start = end, end = segmenter.next()) {
                var segment = normalized.substring(start, end);
                segmented.add(isWordLike(segment) ? segment : "");
            }
            words = segmented;
        }

        var terms = new ArrayList<String>();
        for (var word : words) {
            if (word.isEmpty() || STOP_WORDS.contains(word)) {
                continue;
            }
            terms.add(PLAIN_ASCII.matcher(word).matches() ? stem(word) : word);
        }
        return terms;
    }

    /** pi 的 {@code segment.isWordLike}：段内至少一个字母或数字（标点/空白段丢弃）。 */
    private static boolean isWordLike(String segment) {
        return segment.codePoints().anyMatch(c -> Character.isLetter(c) || Character.isDigit(c));
    }
}
