package com.pijava.tui.component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import com.pijava.coding.agent.core.KeybindingsManager;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.toolkit.element.Element;

/**
 * L7 W6（S2a/K21，pi {@code tui-alt-screen.ts} 搜索状态对位）：转录搜索会话。
 *
 * <p>开关、查询重算、上一个/下一个环回、命中高亮与滚动；输入框复用
 * {@link SingleLineInput}（C4）。键位：{@code ctrl+shift+f} 开关（由
 * {@code GlobalKeyRouter} 分派）、Enter/ctrl+g 下一个、Shift+Enter/ctrl+shift+g
 * 上一个、Esc 关闭。锚点语义＝打开时的首个可见行，查询变化后选其后的首个命中。</p>
 */
public final class TranscriptSearchController {

    private final Supplier<List<RenderRow>> rowsSupplier;
    private final IntSupplier firstVisibleRow;
    private final Consumer<Integer> scroller;
    private final SingleLineInput input = new SingleLineInput(" ", "Find in transcript");
    private final TranscriptSearchIndex index = new TranscriptSearchIndex();
    private List<TranscriptSearchIndex.Match> matches = List.of();
    private int selected = -1;
    private boolean active;
    private int anchorRow;

    /**
     * Creates the search session.
     *
     * @param rowsSupplier    当前渲染行来源
     * @param firstVisibleRow 当前首个可见行（锚点）
     * @param scroller        滚到目标行
     */
    public TranscriptSearchController(Supplier<List<RenderRow>> rowsSupplier,
                                      IntSupplier firstVisibleRow,
                                      Consumer<Integer> scroller) {
        this.rowsSupplier = rowsSupplier;
        this.firstVisibleRow = firstVisibleRow;
        this.scroller = scroller;
    }

    /** 搜索是否打开。 */
    public boolean active() {
        return active;
    }

    /** 打开/关闭（打开时清空查询并取当前首可见行为锚点）。 */
    public void toggle() {
        if (active) {
            close();
            return;
        }
        active = true;
        anchorRow = Math.max(0, firstVisibleRow.getAsInt());
        input.clear();
        refresh(false);
    }

    /** 关闭并清高亮。 */
    public void close() {
        active = false;
        matches = List.of();
        selected = -1;
    }

    /** 查询变化（文本输入/退格后调用）。 */
    public void queryChanged() {
        refresh(false);
    }

    /** 下一个命中（环回）。 */
    public void next() {
        if (matches.isEmpty()) {
            return;
        }
        selected = (selected + 1 + matches.size()) % matches.size();
        scrollToSelected();
    }

    /** 上一个命中（环回）。 */
    public void previous() {
        if (matches.isEmpty()) {
            return;
        }
        selected = (selected - 1 + matches.size()) % matches.size();
        scrollToSelected();
    }

    /**
     * 搜索激活时的键盘分派。
     *
     * @param event 键事件
     * @param keys  键位表（保留参数，交付给输入状态复用）
     * @return 是否消费
     */
    public boolean handleKey(KeyEvent event, KeybindingsManager keys) {
        if (!active) {
            return false;
        }
        if (event.code() == KeyCode.ESCAPE) {
            close();
            return true;
        }
        boolean ctrlG = event.hasCtrl() && "g".equalsIgnoreCase(event.string());
        if (event.code() == KeyCode.ENTER || ctrlG) {
            if (event.hasShift()) {
                previous();
            } else {
                next();
            }
            return true;
        }
        if (input.handleKey(event, keys)) {
            refresh(false);
            return true;
        }
        return true;
    }

    /** 当前查询（测试/渲染读面）。 */
    public SingleLineInput input() {
        return input;
    }

    /** 结果文案（pi：{@code No matches} / {@code n/m}）。 */
    public String resultText() {
        if (input.value().isEmpty()) {
            return "";
        }
        return matches.isEmpty() ? "No matches" : (selected + 1) + "/" + matches.size();
    }

    /** 命中数（测试观察点）。 */
    public int matchCount() {
        return matches.size();
    }

    /** 当前命中索引（0 基；无 ⇒ -1）。 */
    public int selectedIndex() {
        return selected;
    }

    /** 当前命中首行（无 ⇒ -1）。 */
    public int currentRow() {
        if (selected < 0 || selected >= matches.size()) {
            return -1;
        }
        var segments = matches.get(selected).segments();
        return segments.isEmpty() ? -1 : segments.get(0).row();
    }

    /** 搜索高亮（非当前下划线、当前反白加粗）。 */
    public List<ChatViewportElement.Highlight> highlights() {
        var out = new ArrayList<ChatViewportElement.Highlight>();
        for (int i = 0; i < matches.size(); i++) {
            boolean current = i == selected;
            for (var segment : matches.get(i).segments()) {
                out.add(new ChatViewportElement.Highlight(
                    segment.row(), segment.startCol(), segment.endCol(), current));
            }
        }
        return out;
    }

    /** 搜索条元素（三行框线）。 */
    public Element bar() {
        return new TranscriptSearchBar(this);
    }

    private void refresh(boolean keepSelection) {
        var result = index.search(rowsSupplier.get(), input.value());
        matches = result.matches();
        if (matches.isEmpty()) {
            selected = -1;
            return;
        }
        if (keepSelection && selected >= 0 && selected < matches.size()) {
            scrollToSelected();
            return;
        }
        selected = 0;
        for (int i = 0; i < matches.size(); i++) {
            var row = matches.get(i).segments().get(0).row();
            if (row >= anchorRow) {
                selected = i;
                break;
            }
        }
        scrollToSelected();
    }

    private void scrollToSelected() {
        int row = currentRow();
        if (row >= 0) {
            scroller.accept(row);
        }
    }
}
