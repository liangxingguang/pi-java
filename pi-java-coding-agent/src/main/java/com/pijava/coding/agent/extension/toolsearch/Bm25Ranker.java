package com.pijava.coding.agent.extension.toolsearch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * pi {@code Bm25Ranker}（tool.ts:118-157 逐字）：Okapi BM25，k1=1.2、b=0.75；
 * score>0 才入选；<b>同分保持文档顺序</b>（:118 注释 "Ties keep document
 * order."——java 的 {@code List.sort} 是稳定排序，与 V8 同语义）。
 */
public final class Bm25Ranker {

    /** pi {@code ToolSearchMatch}（:27-29）。 */
    public record Match(String name, double score) {}

    private final double k1;
    private final double b;

    public Bm25Ranker() {
        this(1.2, 0.75);
    }

    public Bm25Ranker(double k1, double b) {
        this.k1 = k1;
        this.b = b;
    }

    /** pi {@code rank}（:127-156 逐字）。 */
    public List<Match> rank(String query, List<ToolSearchDocument> documents, int limit) {
        var queryTerms = new ArrayList<>(new java.util.LinkedHashSet<>(
            ToolSearchTokenizer.tokenize(query)));
        if (queryTerms.isEmpty() || documents.isEmpty() || limit <= 0) {
            return List.of();
        }
        var termCounts = new ArrayList<Map<String, Integer>>(documents.size());
        for (var document : documents) {
            var counts = new HashMap<String, Integer>();
            for (var term : ToolSearchTokenizer.tokenize(document.text())) {
                counts.merge(term, 1, Integer::sum);
            }
            termCounts.add(counts);
        }
        var lengths = new double[documents.size()];
        for (int i = 0; i < documents.size(); i++) {
            lengths[i] = termCounts.get(i).values().stream().mapToInt(Integer::intValue).sum();
        }
        double sum = 0;
        for (var length : lengths) {
            sum += length;
        }
        double averageLength = sum / documents.size();
        if (averageLength == 0) {
            averageLength = 1; // pi 的 `|| 1`
        }
        var idf = new LinkedHashMap<String, Double>();
        for (var term : queryTerms) {
            double frequency = termCounts.stream().filter(counts -> counts.containsKey(term))
                .count();
            idf.put(term,
                Math.log(1 + (documents.size() - frequency + 0.5) / (frequency + 0.5)));
        }
        var matches = new ArrayList<Match>();
        for (int index = 0; index < documents.size(); index++) {
            double score = 0;
            for (var term : queryTerms) {
                var count = termCounts.get(index).get(term);
                if (count == null) {
                    continue;
                }
                double norm = k1 * (1 - b + b * lengths[index] / averageLength);
                score += idf.get(term) * (count * (k1 + 1) / (count + norm));
            }
            if (score > 0) {
                matches.add(new Match(documents.get(index).name(), score));
            }
        }
        matches.sort((x, y) -> Double.compare(y.score(), x.score()));
        return matches.subList(0, Math.min(limit, matches.size()));
    }
}
