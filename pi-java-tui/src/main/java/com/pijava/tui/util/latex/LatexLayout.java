package com.pijava.tui.util.latex;

import java.util.ArrayList;
import java.util.List;

/**
 * LaTeX 布局引擎（L7 W7，R8）：pi {@code latex.ts renderLayout/joinLayouts}
 * 的 java 对位——把解析器的占位标记展开为多行布局（分数堆叠、上下标、
 * 极限操作符、矩阵），按基线拼接后去掉公共缩进。
 */
final class LatexLayout {

    private record Layout(List<String> lines, int width, int baseline) {}

    private LatexLayout() {}

    /** 展开标记 → 输出行（不含公共缩进裁剪）。 */
    static List<String> render(String source, List<Object> nodes) {
        var rendered = new ArrayList<String>();
        for (String sourceLine : source.split("\n", -1)) {
            var pieces = new ArrayList<Layout>();
            int position = 0;
            int markerIndex;
            while ((markerIndex = sourceLine.indexOf(LatexRenderer.MARK_START, position)) >= 0) {
                int end = sourceLine.indexOf(LatexRenderer.MARK_END, markerIndex);
                if (end < 0) {
                    break;
                }
                if (markerIndex > position) {
                    pieces.add(text(sourceLine.substring(position, markerIndex)));
                }
                int index = Integer.parseInt(sourceLine.substring(markerIndex + 1, end));
                pieces.add(nodeLayout(nodes.get(index), nodes));
                position = end + 1;
            }
            if (position < sourceLine.length()) {
                pieces.add(text(sourceLine.substring(position)));
            }
            rendered.addAll(join(pieces).lines());
        }
        return rendered;
    }

    private static Layout text(String value) {
        String trimmed = value.stripTrailing();
        return new Layout(List.of(trimmed), displayWidth(trimmed), 0);
    }

    private static Layout nodeLayout(Object node, List<Object> nodes) {
        if (node instanceof LatexRenderer.Fraction fraction) {
            Layout numerator = renderSub(fraction.numerator(), nodes);
            Layout denominator = renderSub(fraction.denominator(), nodes);
            int contentWidth = Math.max(1, Math.max(numerator.width(), denominator.width()));
            var lines = new ArrayList<String>();
            numerator.lines().forEach(line -> lines.add(center(line, contentWidth)));
            lines.add(" " + "─".repeat(contentWidth) + " ");
            denominator.lines().forEach(line -> lines.add(center(line, contentWidth)));
            return new Layout(lines, contentWidth + 2, numerator.lines().size());
        }
        if (node instanceof LatexRenderer.Operator operator) {
            int width = Math.max(displayWidth(operator.operator()),
                Math.max(operator.upper() == null ? 0 : displayWidth(operator.upper()),
                    operator.lower() == null ? 0 : displayWidth(operator.lower())));
            var lines = new ArrayList<String>();
            if (operator.upper() != null) {
                lines.add(center(operator.upper(), width) + " ");
            }
            lines.add(center(operator.operator(), width) + " ");
            if (operator.lower() != null) {
                lines.add(center(operator.lower(), width) + " ");
            }
            return new Layout(lines, width + 1, operator.upper() == null ? 0 : 1);
        }
        if (node instanceof LatexRenderer.Script script) {
            Layout upper = script.upper() == null ? null : renderSub(script.upper(), nodes);
            Layout lower = script.lower() == null ? null : renderSub(script.lower(), nodes);
            int width = Math.max(upper == null ? 0 : upper.width(),
                lower == null ? 0 : lower.width());
            var lines = new ArrayList<String>();
            if (upper != null) {
                upper.lines().forEach(line -> lines.add(padRight(line, width)));
            }
            lines.add(" ".repeat(width));
            if (lower != null) {
                lower.lines().forEach(line -> lines.add(padRight(line, width)));
            }
            return new Layout(lines, width, upper == null ? 0 : upper.lines().size());
        }
        if (node instanceof LatexRenderer.Matrix matrix) {
            int width = matrix.lines().stream().mapToInt(LatexLayout::displayWidth).max().orElse(0);
            return new Layout(matrix.lines(), width, 0);
        }
        return new Layout(List.of(""), 0, 0);
    }

    private static Layout renderSub(String source, List<Object> nodes) {
        var lines = new ArrayList<>(render(source, nodes));
        int width = lines.stream().mapToInt(LatexLayout::displayWidth).max().orElse(0);
        return new Layout(lines, width, 0);
    }

    private static Layout join(List<Layout> pieces) {
        if (pieces.isEmpty()) {
            return new Layout(List.of(""), 0, 0);
        }
        int baseline = pieces.stream().mapToInt(Layout::baseline).max().orElse(0);
        int below = pieces.stream().mapToInt(p -> p.lines().size() - p.baseline() - 1)
            .max().orElse(0);
        var lines = new ArrayList<String>();
        for (int row = 0; row <= baseline + below; row++) {
            var line = new StringBuilder();
            for (Layout piece : pieces) {
                int sourceRow = row - baseline + piece.baseline();
                String content = sourceRow >= 0 && sourceRow < piece.lines().size()
                    ? piece.lines().get(sourceRow) : "";
                line.append(padRight(content, piece.width()));
            }
            lines.add(line.toString().stripTrailing());
        }
        int width = pieces.stream().mapToInt(Layout::width).sum();
        return new Layout(lines, width, baseline);
    }

    private static String center(String value, int width) {
        int padding = Math.max(0, width - displayWidth(value));
        int left = padding / 2;
        return " ".repeat(left) + value + " ".repeat(padding - left);
    }

    private static String padRight(String value, int width) {
        return value + " ".repeat(Math.max(0, width - displayWidth(value)));
    }

    static int displayWidth(String value) {
        int width = 0;
        for (int i = 0; i < value.length(); ) {
            int cp = value.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == 0xFE0F || cp == 0x200D || (cp >= 0x0300 && cp <= 0x036F)) {
                continue;
            }
            width += isWide(cp) ? 2 : 1;
        }
        return width;
    }

    private static boolean isWide(int cp) {
        return (cp >= 0x1100 && cp <= 0x115F) || (cp >= 0x2E80 && cp <= 0xA4CF)
            || (cp >= 0xAC00 && cp <= 0xD7A3) || (cp >= 0xF900 && cp <= 0xFAFF)
            || (cp >= 0xFE30 && cp <= 0xFE6F) || (cp >= 0xFF00 && cp <= 0xFF60)
            || (cp >= 0xFFE0 && cp <= 0xFFE6) || (cp >= 0x1F300 && cp <= 0x1FAFF);
    }
}
