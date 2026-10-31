package com.pijava.evals.judge;

import java.util.Locale;
import java.util.Map;

import com.pijava.evals.harness.HarnessRun;

/**
 * 批 32（docs/44 §4）：文本相似度判分器 —— 归一化后的字符编辑距离
 * {@code 1 - distance / max(length)}。
 *
 * <p>用于 TUI 文档问答等「自然语言答案」场景：分数连续，阈值由用例的
 * {@code judgeThreshold}（例如 0.75）决定。空串对空串记 1.0（视为相同）。
 * 归一化＝小写、去首尾空白、连续空白折叠为单空格，其余字符（含标点）保留。</p>
 */
public final class LevenshteinJudge implements Judge {

    @Override
    public JudgeResult evaluate(HarnessRun run, Object expected) {
        var expectedText = normalize(expected == null ? "" : String.valueOf(expected));
        var actualText = normalize(run == null ? "" : run.response());
        var distance = distance(expectedText, actualText);
        var maxLength = Math.max(expectedText.length(), actualText.length());
        var score = maxLength == 0 ? 1.0 : 1.0 - (double) distance / maxLength;
        return new JudgeResult(score,
            "Levenshtein distance " + distance + " over " + maxLength + " characters",
            Map.of("distance", distance, "maxLength", maxLength));
    }

    /** Lower-case, trim, and collapse whitespace to single spaces. */
    static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }

    /** Standard two-row Levenshtein edit distance. */
    static int distance(String left, String right) {
        if (left.equals(right)) {
            return 0;
        }
        var previous = new int[right.length() + 1];
        var current = new int[right.length() + 1];
        for (int column = 0; column <= right.length(); column++) {
            previous[column] = column;
        }
        for (int row = 1; row <= left.length(); row++) {
            current[0] = row;
            for (int column = 1; column <= right.length(); column++) {
                var cost = left.charAt(row - 1) == right.charAt(column - 1) ? 0 : 1;
                current[column] = Math.min(
                    Math.min(previous[column] + 1, current[column - 1] + 1),
                    previous[column - 1] + cost);
            }
            var swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }
}
