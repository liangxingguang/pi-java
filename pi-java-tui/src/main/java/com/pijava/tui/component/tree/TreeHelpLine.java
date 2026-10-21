package com.pijava.tui.component.tree;

import java.util.ArrayList;
import java.util.List;

import com.pijava.tui.util.TextLayout;

/**
 * pi {@code TreeHelp}（tree-selector.ts:1186-1276）对位：语义帮助行，
 * {@code " · "} 分隔按宽换行（窄端 wrap 优先不截断）。键名静态取默认值
 * （pi 随 keybindings 覆写渲染——覆写未接线，窄差 D46）。
 */
public final class TreeHelpLine {

    private TreeHelpLine() {}

    /** pi TREE_HELP_ITEMS（:1225-1244）＋formatHelpKeys 静态默认展开。 */
    private static final List<String> ITEMS = List.of(
        "↑/↓ move",
        "←/→ page",
        "ctrl+left/right branch",
        "ctrl+x copy",
        "shift+l label",
        "shift+t label time",
        "filters ctrl+d/t/u/l/a",
        "cycle ctrl+o");

    /**
     * pi render（:1189-1222）逐字：indent "  "、separator " · "；候选项超宽 ⇒
     * 当前行先 wrap 落行，新项起新行。
     */
    public static List<String> render(int width) {
        int available = Math.max(1, width);
        String indent = "  ";
        String separator = " · ";
        var lines = new ArrayList<String>();
        String currentLine = "";

        for (var item : ITEMS) {
            String candidate = currentLine.isEmpty()
                ? fits(indent + item, available) ? indent + item : item
                : currentLine + separator + item;
            if (currentLine.isEmpty() || fits(candidate, available)) {
                currentLine = candidate;
                continue;
            }
            lines.addAll(wrapWords(currentLine.stripTrailing(), available));
            currentLine = fits(indent + item, available) ? indent + item : item;
        }
        if (!currentLine.isEmpty()) {
            lines.addAll(wrapWords(currentLine.stripTrailing(), available));
        }
        return lines;
    }

    private static boolean fits(String text, int width) {
        return TextLayout.displayWidth(text) <= width;
    }

    /** 词界换行（pi wrapTextWithAnsi 对位）；超长单词硬切，永不截断。 */
    private static List<String> wrapWords(String text, int width) {
        var lines = new ArrayList<String>();
        var current = new StringBuilder();
        for (var word : text.split(" ", -1)) {
            if (current.isEmpty()) {
                current.append(word);
                continue;
            }
            if (TextLayout.displayWidth(current + " " + word) <= width) {
                current.append(' ').append(word);
            } else {
                lines.add(current.toString());
                current = new StringBuilder(word);
            }
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        // 硬切仍超宽的行（单个长词）。
        var result = new ArrayList<String>();
        for (var line : lines) {
            result.addAll(hardWrap(line, width));
        }
        return result;
    }

    private static List<String> hardWrap(String text, int width) {
        if (TextLayout.displayWidth(text) <= width) {
            return List.of(text);
        }
        var lines = new ArrayList<String>();
        var current = new StringBuilder();
        int col = 0;
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            int w = TextLayout.displayWidth(new String(Character.toChars(cp)));
            if (col + w > width && col > 0) {
                lines.add(current.toString());
                current = new StringBuilder();
                col = 0;
            }
            current.appendCodePoint(cp);
            col += w;
            i += Character.charCount(cp);
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
    }
}
