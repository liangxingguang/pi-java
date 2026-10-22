package com.pijava.tui.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

import com.pijava.coding.agent.core.AgentSession;
import com.pijava.tui.theme.SemanticColors;
import com.pijava.tui.util.TamboUIAdapter;
import com.pijava.tui.util.TextLayout;

import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyEvent;

/**
 * pi {@code UserMessageSelectorComponent}（user-message-selector.ts:112-155）＋
 * {@code UserMessageList}（:14-107）的 java 落形（docs/21 §T2，B254）：
 * {@code /fork} 的 user-message 选择器。无过滤、环绕导航、每消息两行
 * （单行化文本＋序位行），可视窗 10 条以选中项为中心滑动。
 *
 * <p>tui.select.* 层沿 SelectList 纪律直配 {@link KeyEvent} helper
 * （isUp/isDown/isConfirm/isCancel——pi {@code kb.matches(keyData, "tui.select.*")}
 * 分派序，:81-106）。结局：Enter ⇒ confirmed（{@code apply} 交 entryId 回调，
 * 不动会话）；Esc ⇒ cancelled。空表渲染同形防御（pi :39-42），宿主预检后不可达
 * （pi 组件内 100ms 自动取消不移植——裁决⑥）。</p>
 */
public final class UserMessageSelectorScreen implements ScreenOverlay {

    /** 一行渲染文本的一个样式段（测试断言面；同批 9 TreeListRenderer.Span 手法）。 */
    record Span(String text, Style style) {}

    /** pi {@code maxVisible}（user-message-selector.ts:19）。 */
    private static final int MAX_VISIBLE = 10;
    /** pi 副标（:124-126 逐字【模型可见】）。 */
    private static final String SUBTITLE =
        "Select a user message to copy the active path up to that point into a new session";

    private final List<AgentSession.ForkMessage> messages;   // 时间序（旧→新）
    private final IntSupplier terminalCols;
    private final Consumer<String> onSelect;                 // entryId
    private final Runnable onCancel;
    private int selectedIndex;
    private boolean confirmed;
    private boolean cancelled;

    /**
     * @param messages          可选中的 user 消息（{@code AgentSession.getUserMessagesForForking()}）
     * @param initialSelectedId 初值选中（未命中 ⇒ 最后一条，pi :26-28）
     * @param terminalCols      终端列数源（可视宽；批 9 裁决⑧的 ResizeEvent 跟踪源）
     * @param onSelect          定案回调（entryId；apply 时触发）
     * @param onCancel          取消回调（apply 时触发）
     */
    public UserMessageSelectorScreen(List<AgentSession.ForkMessage> messages,
                                     String initialSelectedId,
                                     IntSupplier terminalCols,
                                     Consumer<String> onSelect, Runnable onCancel) {
        this.messages = List.copyOf(messages);
        this.terminalCols = terminalCols;
        this.onSelect = onSelect;
        this.onCancel = onCancel;
        int initialIndex = -1;
        if (initialSelectedId != null) {
            for (int i = 0; i < messages.size(); i++) {
                if (messages.get(i).entryId().equals(initialSelectedId)) {
                    initialIndex = i;
                    break;
                }
            }
        }
        // pi :26-28：有初值用初值，否则默认最后一条。
        this.selectedIndex = initialIndex >= 0 ? initialIndex : Math.max(0, messages.size() - 1);
    }

    /** 当前选中下标（宿主/测试只读探针）。 */
    int selectedIndex() {
        return selectedIndex;
    }

    // ── 键分派（pi handleInput :81-106 分派序逐字）──────────────

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        if (event.isUp()) {
            // 上＝更旧，到顶环绕到底（pi :84-87）。
            if (!messages.isEmpty()) {
                selectedIndex = selectedIndex == 0 ? messages.size() - 1 : selectedIndex - 1;
            }
            return true;
        }
        if (event.isDown()) {
            // 下＝更新，到底环绕到顶（pi :88-91）。
            if (!messages.isEmpty()) {
                selectedIndex = selectedIndex == messages.size() - 1 ? 0 : selectedIndex + 1;
            }
            return true;
        }
        if (event.isConfirm()) {
            if (!messages.isEmpty()) {
                confirmed = true;   // apply 里 onSelect(entryId)
            }
            return true;
        }
        if (event.isCancel()) {
            cancelled = true;
            return true;
        }
        return false;
    }

    // ── 结局 ──────────────────────────────────────────────────

    @Override
    public boolean isDone() {
        return confirmed || cancelled;
    }

    @Override
    public void apply(AgentSession session, Consumer<AgentSession> switcher) {
        // apply 不动会话：entryId 交回调（fork 编排由宿主负责，pi :5514-5528）。
        if (cancelled) {
            onCancel.run();
            return;
        }
        onSelect.accept(messages.get(selectedIndex).entryId());
    }

    // ── 渲染（pi render(width) :36-78＋容器层 :120-144 逐字行形）──

    @Override
    public Element render() {
        var children = new ArrayList<Element>();
        for (var row : layout()) {
            if (row.isEmpty()) {
                children.add(TamboUIAdapter.spacer(1));
            } else {
                children.add(TamboUIAdapter.richText(Text.from(Line.from(row.stream()
                    .map(s -> new dev.tamboui.text.Span(s.text(), s.style()))
                    .toList()))));
            }
        }
        return TamboUIAdapter.column(children);
    }

    /**
     * 全部可视行的 span 模型（测试断言面；空行＝空表）。
     * 容器头（pi :120-130）：空行 ⇒ 粗体标题 ⇒ muted 副标（Text paddingX=1，
     * 按 width−2 词换行）⇒ 空行 ⇒ border ⇒ 空行；容器尾（:143-144）：空行 ⇒ border。
     */
    List<List<Span>> layout() {
        int width = Math.max(1, terminalCols.getAsInt());
        var rows = new ArrayList<List<Span>>();
        rows.add(List.of());
        rows.add(List.of(new Span(" Fork from Message", SemanticColors.PLAIN.bold())));
        for (var line : wrapWords(SUBTITLE, Math.max(1, width - 2))) {
            rows.add(List.of(new Span(" " + line, SemanticColors.MUTED)));
        }
        rows.add(List.of());
        rows.add(borderRow(width));
        rows.add(List.of());
        if (messages.isEmpty()) {
            // pi :39-42（防御臂，宿主预检 "No messages to fork from" 后不可达）。
            rows.add(List.of(new Span("  No user messages found", SemanticColors.MUTED)));
        } else {
            // 可视窗以选中项为中心滑动（pi :44-48 公式逐字）。
            int startIndex = Math.max(0, Math.min(
                selectedIndex - MAX_VISIBLE / 2, messages.size() - MAX_VISIBLE));
            int endIndex = Math.min(startIndex + MAX_VISIBLE, messages.size());
            for (int i = startIndex; i < endIndex; i++) {
                boolean isSelected = i == selectedIndex;
                // 单行化（pi :53：\n→空格，trim）。
                var normalized = messages.get(i).text().replace('\n', ' ').trim();
                // 光标占 2 列（pi :55-57）；选中加粗。
                var truncated = truncateToWidth(normalized, width - 2);
                if (isSelected) {
                    rows.add(List.of(new Span("› ", SemanticColors.ACCENT),
                        new Span(truncated, SemanticColors.PLAIN.bold())));
                } else {
                    rows.add(List.of(new Span("  " + truncated, SemanticColors.PLAIN)));
                }
                // 序位行（pi :61-64）。
                rows.add(List.of(new Span(
                    "  Message " + (i + 1) + " of " + messages.size(), SemanticColors.MUTED)));
                rows.add(List.of());   // 条间空行（pi :65）
            }
            // 滚动指示（pi :68-71 出现条件逐字）。
            if (startIndex > 0 || endIndex < messages.size()) {
                rows.add(List.of(new Span(
                    "  (" + (selectedIndex + 1) + "/" + messages.size() + ")",
                    SemanticColors.MUTED)));
            }
        }
        rows.add(List.of());
        rows.add(borderRow(width));
        return rows;
    }

    /** pi DynamicBorder（dynamic-border.ts:16-18，裁决⑤）：{@code "─" × max(1, width)}，MUTED 近似。 */
    private static List<Span> borderRow(int width) {
        return List.of(new Span("─".repeat(Math.max(1, width)), SemanticColors.MUTED));
    }

    /**
     * pi {@code truncateToWidth}（pi-tui utils.ts:1121，默认省略号 {@code "..."}、
     * 不 padding）的列宽截断：逐 codepoint 累计显示宽（批 9 TreeListRenderer 的
     * 列宽纪律），宽字符不越界。
     */
    static String truncateToWidth(String text, int maxWidth) {
        if (maxWidth <= 0) {
            return "";
        }
        if (TextLayout.displayWidth(text) <= maxWidth) {
            return text;
        }
        if (maxWidth <= 3) {
            // pi 边缘臂：省略号自身按 maxWidth 裁剪。
            return "...".substring(0, maxWidth);
        }
        int target = maxWidth - 3;
        var out = new StringBuilder();
        int used = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int w = TextLayout.displayWidth(new String(Character.toChars(cp)));
            if (used + w > target) {
                break;
            }
            out.appendCodePoint(cp);
            used += w;
            i += Character.charCount(cp);
        }
        return out + "...";
    }

    /** pi Text 组件的词换行（paddingX=1 ⇒ 内容宽 width−2）；纯 ASCII 副标专用。 */
    private static List<String> wrapWords(String text, int contentWidth) {
        var lines = new ArrayList<String>();
        var current = new StringBuilder();
        for (var word : text.split(" ")) {
            if (!current.isEmpty() && current.length() + 1 + word.length() > contentWidth) {
                lines.add(current.toString());
                current.setLength(0);
            }
            if (!current.isEmpty()) {
                current.append(' ');
            }
            current.append(word);
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
    }
}
