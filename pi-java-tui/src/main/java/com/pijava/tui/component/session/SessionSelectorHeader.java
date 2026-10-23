package com.pijava.tui.component.session;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.tui.component.KeybindingHints;
import com.pijava.tui.component.session.SessionListRenderer.Span;
import com.pijava.tui.component.session.SessionSelectorSearch.NameFilter;
import com.pijava.tui.component.session.SessionSelectorSearch.SortMode;
import com.pijava.tui.theme.SemanticColors;
import com.pijava.tui.util.TextLayout;

/**
 * pi {@code SessionSelectorHeader}（session-selector.ts:56-187）逐字 port
 * （批 14，docs/25 T10）：标题＋三门状态（scope/sort/name）＋两行 hint＋状态消息。
 *
 * <p>状态消息的自动隐＝deadline 制（pi {@code setTimeout} 对位）：宿主 33ms tick
 * 调 {@link #tick()} 清过期，无额外线程。</p>
 */
public final class SessionSelectorHeader {

    /** pi {@code SessionScope}（:24）。 */
    public enum Scope { CURRENT, ALL }

    /** pi statusMessage（:65）；{@code error} 决定 error/accent 色。 */
    public record StatusMessage(boolean error, String message) {}

    /** pi loadProgress（:62）。 */
    private record Progress(int loaded, int total) {}

    private Scope scope;
    private SortMode sortMode;
    private NameFilter nameFilter;
    private boolean loading;
    private Progress loadProgress;
    private boolean showPath;
    private String confirmingDeletePath;
    private StatusMessage statusMessage;
    private long statusHideAtMs = -1;
    /** picker 恒 false（session-picker.ts:98 showRenameHint: false）。 */
    private final boolean showRenameHint;
    private final KeybindingsManager keybindings;
    private final LongSupplier clock;

    public SessionSelectorHeader(Scope scope, SortMode sortMode, NameFilter nameFilter,
                                 boolean showRenameHint, KeybindingsManager keybindings,
                                 LongSupplier clock) {
        this.scope = scope;
        this.sortMode = sortMode;
        this.nameFilter = nameFilter;
        this.showRenameHint = showRenameHint;
        this.keybindings = keybindings;
        this.clock = clock;
    }

    public void setScope(Scope scope) {
        this.scope = scope;
    }

    public void setSortMode(SortMode sortMode) {
        this.sortMode = sortMode;
    }

    public void setNameFilter(NameFilter nameFilter) {
        this.nameFilter = nameFilter;
    }

    /** pi :88-92：进度随 loading 状态设置一起清。 */
    public void setLoading(boolean loading) {
        this.loading = loading;
        this.loadProgress = null;
    }

    public void setProgress(int loaded, int total) {
        this.loadProgress = new Progress(loaded, total);
    }

    public void setShowPath(boolean showPath) {
        this.showPath = showPath;
    }

    public void setConfirmingDeletePath(String path) {
        this.confirmingDeletePath = path;
    }

    /** pi :116-126：新消息顶替旧 deadline；{@code autoHideMs} &lt;= 0 ⇒ 不自动隐。 */
    public void setStatusMessage(StatusMessage message, long autoHideMs) {
        this.statusMessage = message;
        this.statusHideAtMs = message == null || autoHideMs <= 0
            ? -1 : clock.getAsLong() + autoHideMs;
    }

    /** pi setTimeout 回调对位：清过期状态消息。 */
    public void tick() {
        if (statusMessage != null && statusHideAtMs >= 0 && clock.getAsLong() >= statusHideAtMs) {
            statusMessage = null;
            statusHideAtMs = -1;
        }
    }

    /** 只读探针（测试面）。 */
    StatusMessage statusMessage() {
        return statusMessage;
    }

    /** pi {@code render(width)}（:130-186）逐字：三行（标题行＋hint 两行）。 */
    public List<List<Span>> layout(int width) {
        var title = scope == Scope.CURRENT
            ? "Resume Session (Current Folder)" : "Resume Session (All)";

        // 右段（pi :134-148）：{scopeText}  {nameText}  {sortText}（两个空格分隔）。
        var right = new ArrayList<Span>();
        right.addAll(scopeSpans());
        right.add(new Span("  ", SemanticColors.PLAIN));
        right.add(new Span("Name: ", SemanticColors.MUTED));
        right.add(new Span(nameFilter == NameFilter.ALL ? "All" : "Named", SemanticColors.ACCENT));
        right.add(new Span("  ", SemanticColors.PLAIN));
        right.add(new Span("Sort: ", SemanticColors.MUTED));
        right.add(new Span(sortLabel(), SemanticColors.ACCENT));
        right = new ArrayList<>(SessionText.truncateSpans(right, width));

        int rightWidth = SessionText.widthOf(right);
        int availableLeft = Math.max(0, width - rightWidth - 1);
        var left = SessionText.truncateToWidth(title, availableLeft, "");
        int spacing = Math.max(0, width - TextLayout.displayWidth(left) - rightWidth);

        var row1 = new ArrayList<Span>();
        row1.add(new Span(left, SemanticColors.PLAIN.bold()));
        row1.add(new Span(" ".repeat(spacing), SemanticColors.PLAIN));
        row1.addAll(right);

        var hintRows = hintRows(width);
        return List.of(List.copyOf(row1), hintRows.get(0), hintRows.get(1));
    }

    /** pi :140-148 scopeText 三态逐字。 */
    private List<Span> scopeSpans() {
        if (loading) {
            var progressText = loadProgress != null
                ? loadProgress.loaded + "/" + loadProgress.total : "...";
            return List.of(
                new Span("○ Current Folder | ", SemanticColors.MUTED),
                new Span("Loading " + progressText, SemanticColors.ACCENT));
        }
        if (scope == Scope.CURRENT) {
            return List.of(
                new Span("◉ Current Folder", SemanticColors.ACCENT),
                new Span(" | ○ All", SemanticColors.MUTED));
        }
        return List.of(
            new Span("○ Current Folder | ", SemanticColors.MUTED),
            new Span("◉ All", SemanticColors.ACCENT));
    }

    /** pi :134：relevance 显示为 Fuzzy。 */
    private String sortLabel() {
        return switch (sortMode) {
            case THREADED -> "Threaded";
            case RECENT -> "Recent";
            case RELEVANCE -> "Fuzzy";
        };
    }

    /** pi :156-183 hint 区三态逐字（恒两行，非常态第二行为空）。 */
    private List<List<Span>> hintRows(int width) {
        if (confirmingDeletePath != null) {
            // pi :159-161：删除确认单行（error 色为底、键名 dim/描述 muted）。
            var row = new ArrayList<Span>();
            row.add(new Span("Delete session? ", SemanticColors.ERROR));
            row.add(new Span("enter", SemanticColors.DIM));
            row.add(new Span(" confirm", SemanticColors.MUTED));
            row.add(new Span(" · ", SemanticColors.ERROR));
            row.add(new Span("esc", SemanticColors.DIM));
            row.add(new Span(" cancel", SemanticColors.MUTED));
            return List.of(SessionText.truncateSpans(row, width), List.of());
        }
        if (statusMessage != null) {
            // pi :162-165：状态消息单行（error/accent 色）。
            var style = statusMessage.error() ? SemanticColors.ERROR : SemanticColors.ACCENT;
            return List.of(List.of(new Span(
                SessionText.truncateToWidth(statusMessage.message(), width, "…"), style)),
                List.of());
        }
        // pi :167-182 常态两行。
        var row1 = new ArrayList<Span>();
        row1.addAll(keyHintSpans("tab", "scope"));   // tui.input.tab 不入键位表（裁决⑥）
        row1.add(new Span(" · ", SemanticColors.MUTED));
        row1.add(new Span("re:<pattern> regex · \"phrase\" exact", SemanticColors.MUTED));

        var row2 = new ArrayList<Span>();
        row2.addAll(keyHint(KeybindingsManager.SESSION_TOGGLE_SORT, "sort"));
        row2.add(new Span(" · ", SemanticColors.MUTED));
        row2.addAll(keyHint(KeybindingsManager.SESSION_TOGGLE_NAMED_FILTER, "named"));
        row2.add(new Span(" · ", SemanticColors.MUTED));
        row2.addAll(keyHint(KeybindingsManager.SESSION_DELETE, "delete"));
        row2.add(new Span(" · ", SemanticColors.MUTED));
        row2.addAll(keyHint(KeybindingsManager.SESSION_TOGGLE_PATH,
            "path " + (showPath ? "(on)" : "(off)")));
        if (showRenameHint) {
            row2.add(new Span(" · ", SemanticColors.MUTED));
            row2.addAll(keyHint(KeybindingsManager.SESSION_RENAME, "rename"));
        }
        return List.of(SessionText.truncateSpans(row1, width),
            SessionText.truncateSpans(row2, width));
    }

    /** pi {@code keyHint}（keybinding-hints.ts:42-44）：dim 键名＋muted 描述。 */
    private List<Span> keyHint(String actionId, String description) {
        return keyHintSpans(KeybindingHints.keyText(keybindings.strokeFor(actionId)), description);
    }

    private List<Span> keyHintSpans(String keyText, String description) {
        return List.of(
            new Span(keyText, SemanticColors.DIM),
            new Span(" " + description, SemanticColors.MUTED));
    }
}
