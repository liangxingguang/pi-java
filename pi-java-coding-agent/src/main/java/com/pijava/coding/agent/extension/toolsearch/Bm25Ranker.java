package com.pijava.coding.agent.extension.toolsearch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * pi {@code Bm25Ranker}（tool.ts:196-256 逐字）：描述上的 Okapi BM25，名称与
 * 命名空间作<b>独立字段</b>加权。
 *
 * <ul>
 *   <li>{@code NAME_WEIGHT = 3}——名称命中胜过描述里任意多次提及（描述项的上限
 *       是 {@code k1+1 = 2.2} 倍 IDF）；</li>
 *   <li>{@code NAMESPACE_WEIGHT = 0.5}——命名空间被同服务器所有工具共享，帮找
 *       服务器不帮找工具；</li>
 *   <li>IDF 覆盖三字段；长度归一<b>只用描述</b>（长名称/长命名空间说明不降分）；</li>
 *   <li>排序键依次 {@code exact}（整名查询）、{@code covered}（命中的查询词数）、
 *       {@code score}；同分保持文档序；</li>
 *   <li>本地名（最后一个 {@code __} 段）的每个词都被查询覆盖 ⇒ 加
 *       {@code fullNameBonus}（= 全部 IDF 之和，等价于再多命中一遍全部查询词）。</li>
 * </ul>
 */
public final class Bm25Ranker {

    /** pi {@code ToolSearchMatch}（:27-29）。 */
    public record Match(String name, double score) {}

    /** pi {@code NAME_WEIGHT}（:169-175）。 */
    private static final double NAME_WEIGHT = 3;

    /** pi {@code NAMESPACE_WEIGHT}（:177-182）。 */
    private static final double NAMESPACE_WEIGHT = 0.5;

    private final double k1;
    private final double b;

    public Bm25Ranker() {
        this(1.2, 0.75);
    }

    public Bm25Ranker(double k1, double b) {
        this.k1 = k1;
        this.b = b;
    }

    /** 一篇文章按排序器视角拆开的三字段（pi {@code IndexedDocument}，:148-166）。 */
    private record IndexedDocument(String name, Set<String> nameTerms,
                                   List<String> localNameTerms,
                                   Map<String, Integer> textCounts, int textLength,
                                   Set<String> namespaceTerms) {}

    /** pi {@code indexDocument}（:167-177）。 */
    private static IndexedDocument indexDocument(ToolSearchDocument document) {
        var textCounts = new HashMap<String, Integer>();
        for (var term : ToolSearchTokenizer.tokenize(document.text())) {
            textCounts.merge(term, 1, Integer::sum);
        }
        var segments = document.name().split("__");
        var localSegments = ToolSearchTokenizer.tokenize(segments[segments.length - 1]);
        var textLength = textCounts.values().stream().mapToInt(Integer::intValue).sum();
        return new IndexedDocument(
            document.name(),
            new LinkedHashSet<>(ToolSearchTokenizer.tokenize(document.name())),
            List.copyOf(new LinkedHashSet<>(localSegments)),
            textCounts, textLength,
            new LinkedHashSet<>(ToolSearchTokenizer.tokenize(document.namespaceText())));
    }

    /** pi {@code normalizeName}（:131-137 逐字）：小写，非脚本标识符字符换 {@code _}。 */
    private static String normalizeName(String name) {
        return name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_$]", "_");
    }

    /** pi {@code isExactNameQuery}（:143-146）：整名或 {@code __} 之后的末段。 */
    private static boolean isExactNameQuery(String normalizedQuery, String name) {
        var normalized = normalizeName(name);
        return normalized.equals(normalizedQuery) || normalized.endsWith("__" + normalizedQuery);
    }

    /** pi {@code rank}（:204-255 逐字）。 */
    public List<Match> rank(String query, List<ToolSearchDocument> documents, int limit) {
        var queryTerms = new LinkedHashSet<>(ToolSearchTokenizer.tokenize(query));
        if (queryTerms.isEmpty() || documents.isEmpty() || limit <= 0) {
            return List.of();
        }
        var indexed = documents.stream().map(Bm25Ranker::indexDocument).toList();
        double totalLength = indexed.stream().mapToInt(IndexedDocument::textLength).sum();
        double averageLength = totalLength / indexed.size();
        if (averageLength == 0) {
            averageLength = 1; // pi 的 `|| 1`
        }
        var idf = new LinkedHashMap<String, Double>();
        for (var term : queryTerms) {
            long frequency = indexed.stream().filter(document ->
                document.nameTerms().contains(term) || document.textCounts().containsKey(term)
                    || document.namespaceTerms().contains(term)).count();
            idf.put(term, Math.log(1 + (indexed.size() - frequency + 0.5) / (frequency + 0.5)));
        }
        double fullNameBonus = idf.values().stream().mapToDouble(Double::doubleValue).sum();
        var normalizedQuery = normalizeName(query);

        var ranked = new ArrayList<Entry>();
        for (var document : indexed) {
            double norm = k1 * (1 - b + b * document.textLength() / averageLength);
            double score = 0;
            int covered = 0;
            for (var term : queryTerms) {
                int count = document.textCounts().getOrDefault(term, 0);
                double weight = (document.nameTerms().contains(term) ? NAME_WEIGHT : 0)
                    + (document.namespaceTerms().contains(term) ? NAMESPACE_WEIGHT : 0)
                    + (count * (k1 + 1)) / (count + norm);
                if (weight > 0) {
                    covered++;
                }
                score += idf.get(term) * weight;
            }
            if (score == 0) {
                continue;
            }
            var local = document.localNameTerms();
            if (!local.isEmpty() && queryTerms.containsAll(local)) {
                score += fullNameBonus;
            }
            ranked.add(new Entry(document.name(), score, covered,
                isExactNameQuery(normalizedQuery, document.name())));
        }
        // pi :251-254：exact 先、covered 次、score 末；同分保持文档序（稳定排序）。
        ranked.sort((a, b) -> {
            int byExact = Boolean.compare(b.exact(), a.exact());
            if (byExact != 0) {
                return byExact;
            }
            int byCovered = Integer.compare(b.covered(), a.covered());
            return byCovered != 0 ? byCovered : Double.compare(b.score(), a.score());
        });
        return ranked.stream().limit(limit)
            .map(entry -> new Match(entry.name(), entry.score())).toList();
    }

    /** pi 排序前的中间形（{@code ToolSearchMatch & {covered, exact}}）。 */
    private record Entry(String name, double score, int covered, boolean exact) {}
}
