package com.pijava.coding.agent.extension.toolsearch;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * pi {@code tool-search/tool.ts} 的分词层逐字（:39-80）：停用词表、朴素词干化、
 * camelCase 边界切分。
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

    /** pi :75 的非字母数字切分。 */
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");

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
        var spaced = ACRONYM_WORD.matcher(
            LOWER_UPPER.matcher(text).replaceAll("$1 $2")).replaceAll("$1 $2");
        var terms = new ArrayList<String>();
        for (var raw : NON_ALNUM.split(spaced.toLowerCase())) {
            if (!raw.isEmpty() && !STOP_WORDS.contains(raw)) {
                terms.add(stem(raw));
            }
        }
        return terms;
    }
}
