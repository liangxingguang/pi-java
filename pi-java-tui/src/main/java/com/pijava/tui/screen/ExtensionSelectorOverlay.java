package com.pijava.tui.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.tui.component.KeybindingHints;
import com.pijava.tui.theme.SemanticColors;
import com.pijava.tui.util.TamboUIAdapter;

import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyEvent;

/**
 * 批 17（T4，docs/28 §1.3）：pi {@code ExtensionSelectorComponent}
 * （extension-selector.ts，117 行）的 java 落形——扩展 {@code ctx.ui.select} 的
 * 选择器对话框。落在 {@link ScreenOverlay} 模态层（裁决④：java 无编辑器换槽；
 * overlay 渲染位在转录与状态栏之间，视觉近似，窄差 D95）。
 *
 * <p>逐字语义：标题 accent <b>加粗</b>；上下 DynamicBorder；选中项 {@code → } 前缀
 * ＋accent、未选两空前缀；无滚动窗（全量绘制）；键位 up/k 上移（夹 0）、down/j 下移
 * （夹 length-1）、confirm/Enter 提交（<b>空表不提交</b>）、cancel（esc/ctrl+c）取消；
 * hint 行 {@code ↑↓ navigate  <confirm> select  <cancel> cancel}。timeout 倒计时
 * 由宿主（TuiExtensionUI 的共享调度器）驱动：{@link #countdownTick} 改标题为
 * {@code "{title} ({Ns})"}、{@link #countdownExpired} ＝取消（pi CountdownTimer 逐字）。
 * ctrl+o（app.tools.expand）不消费（窄差 D97）。</p>
 */
public final class ExtensionSelectorOverlay implements ScreenOverlay, ExtensionDialog {

    /** 一行渲染文本的一个样式段（测试断言面；同 UserMessageSelectorScreen 手法）。 */
    record Span(String text, Style style) {}

    private final String baseTitle;
    private final List<String> options;
    private final KeybindingsManager keys;
    private final IntSupplier terminalCols;
    private final CompletableFuture<String> result;
    // ⚠️ 写点在渲染线程（onKeyEvent），读点在 layout()——但倒计时线程会经
    // countdownTick/cancel 触达本对象（volatile 族）⇒ spotbugs 按共享变量审：
    // 读-改-写用 AtomicInteger（批 12 ChatScreen.runToolCalls 先例同旨）。
    private final java.util.concurrent.atomic.AtomicInteger selectedIndex =
        new java.util.concurrent.atomic.AtomicInteger();
    /** 倒计时秒数（-1 ⇒ 无倒计时；倒计时线程写、渲染线程读 ⇒ volatile）。 */
    private volatile int countdownSeconds = -1;
    private volatile boolean done;
    /** cancel 可从倒计时/abort 线程进入（⇒ volatile；pi 单线程无此形态）。 */
    private volatile boolean cancelled;

    /**
     * @param title        对话框标题（confirm 特化时宿主已拼 {@code title\nmessage}）
     * @param options      选项表（空表合法——渲染空列表、Enter 不提交）
     * @param keys         键位表（tui.select.* overlay 臂）
     * @param terminalCols 终端列数源（DynamicBorder 宽）
     * @param result       结局 future（apply 时完成：选项或 null）
     */
    public ExtensionSelectorOverlay(String title, List<String> options,
                                    KeybindingsManager keys, IntSupplier terminalCols,
                                    CompletableFuture<String> result) {
        this.baseTitle = title;
        this.options = List.copyOf(options);
        this.keys = keys;
        this.terminalCols = terminalCols;
        this.result = result;
    }

    // ── 键分派（pi handleInput :85-105 分派序逐字）──────────────

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        var stroke = TamboUIAdapter.toStroke(event);
        if (keys.matches(KeybindingsManager.SELECT_UP, stroke)
                || ("k".equals(stroke.key()) && !stroke.ctrl() && !stroke.alt() && !stroke.shift())) {
            selectedIndex.getAndUpdate(i -> Math.max(0, i - 1));
        } else if (keys.matches(KeybindingsManager.SELECT_DOWN, stroke)
                || ("j".equals(stroke.key()) && !stroke.ctrl() && !stroke.alt() && !stroke.shift())) {
            selectedIndex.getAndUpdate(i -> Math.min(options.size() - 1, i + 1));
        } else if (keys.matches(KeybindingsManager.SELECT_CONFIRM, stroke)
                || TamboUIAdapter.isSendEnter(event)) {
            // pi :97-99：空表不提交。
            if (!options.isEmpty()) {
                done = true;
            }
        } else if (keys.matches(KeybindingsManager.SELECT_CANCEL, stroke)) {
            cancel();
        }
        // ctrl+o（app.tools.expand）不消费——窄差 D97（pi 转发 toggleToolOutputExpansion）。
        return true;
    }

    /** 取消（Esc/ctrl+c、timeout 到期、宿主复位同走此口）。 */
    @Override
    public void cancel() {
        cancelled = true;
        done = true;
    }

    /** pi CountdownTimer 的 onTick：标题变 {@code "{title} ({Ns})"}。 */
    @Override
    public void countdownTick(int remainingSeconds) {
        countdownSeconds = remainingSeconds;
    }

    @Override
    public boolean isDone() {
        return done;
    }

    @Override
    public void apply(AgentSession session, Consumer<AgentSession> switcher) {
        result.complete(cancelled ? null : options.get(selectedIndex.get()));
    }

    /** 结局 future（宿主复位时补完成——幂等）。 */
    @Override
    public CompletableFuture<String> resultFuture() {
        return result;
    }

    // ── 渲染（pi 构造器 :31-66 的子件序＋updateList :69-79 逐字行形）──

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
     * 全部可视行的 span 模型（测试断言面；空行＝空表）。pi 序：border ⇒ 空行 ⇒
     * 标题（accent 加粗，倒计时后缀）⇒ 空行 ⇒ 列表 ⇒ 空行 ⇒ hint ⇒ 空行 ⇒ border。
     * pi 的 Text 组件 paddingX=1 ⇒ 行前导一格。
     */
    List<List<Span>> layout() {
        int width = Math.max(1, terminalCols.getAsInt());
        var rows = new ArrayList<List<Span>>();
        rows.add(borderRow(width));
        rows.add(List.of());
        // confirm 特化拼 "title\nmessage"（pi :2750）⇒ 标题多行逐行渲；
        // 倒计时后缀拼在末行（pi 单 Text 多行渲染的等价）。
        var titleLines = baseTitle.split("\n", -1);
        if (countdownSeconds >= 0) {
            titleLines[titleLines.length - 1] += " (" + countdownSeconds + "s)";
        }
        for (var line : titleLines) {
            rows.add(List.of(new Span(" " + line, SemanticColors.ACCENT.bold())));
        }
        rows.add(List.of());
        // pi updateList：选中项 "→ " 前缀＋accent；未选 "  " 前缀＋正文色。
        int selected = selectedIndex.get();
        for (int i = 0; i < options.size(); i++) {
            if (i == selected) {
                rows.add(List.of(new Span(" → " + options.get(i), SemanticColors.ACCENT)));
            } else {
                rows.add(List.of(new Span("   " + options.get(i), SemanticColors.PLAIN)));
            }
        }
        rows.add(List.of());
        rows.add(hintRow());
        rows.add(List.of());
        rows.add(borderRow(width));
        return rows;
    }

    /** pi hint 行逐字：{@code rawKeyHint("↑↓","navigate") + keyHint(confirm,"select") + keyHint(cancel,"cancel")}。 */
    private List<Span> hintRow() {
        return List.of(
            new Span(" ↑↓", SemanticColors.DIM),
            new Span(" navigate", SemanticColors.MUTED),
            new Span("  ", SemanticColors.PLAIN),
            new Span(hintKey(KeybindingsManager.SELECT_CONFIRM), SemanticColors.DIM),
            new Span(" select", SemanticColors.MUTED),
            new Span("  ", SemanticColors.PLAIN),
            new Span(hintKey(KeybindingsManager.SELECT_CANCEL), SemanticColors.DIM),
            new Span(" cancel", SemanticColors.MUTED));
    }

    private String hintKey(String actionId) {
        return KeybindingHints.keyText(keys.strokeFor(actionId));
    }

    /** pi DynamicBorder：{@code "─".repeat(max(1, width))}，border 色（≈ DIM）。 */
    static List<Span> borderRow(int width) {
        return List.of(new Span("─".repeat(Math.max(1, width)), SemanticColors.DIM));
    }
}
