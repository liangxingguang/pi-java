package com.pijava.tui.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.core.slash.builtin.ThinkingCommands;
import com.pijava.tui.component.KeybindingHints;
import com.pijava.tui.component.SelectList;
import com.pijava.tui.theme.SemanticColors;
import com.pijava.tui.util.TamboUIAdapter;

import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyEvent;

/**
 * {@code /thinking}（空参）的思考级别选择器（批 15 T8 简版 → 批 34 补完整，
 * pi {@code components/thinking-selector.ts} 154 行对位）。
 *
 * <p>逐字语义：标题 {@code Thinking Level}；cycle hint 行；级别描述列
 * （pi {@code LEVEL_DESCRIPTIONS}）；默认级行追加 {@code  · default}；选中项
 * {@code ✓ } 前缀＋accent、未选两空格；{@code app.thinking.save}（ctrl+s）＝
 * 存为默认（persist=true，会话级＋设置项），Enter ＝只设会话级；底行三键提示。
 * 搜索行是自绘形态（复用 {@link SelectList} 的 type-to-filter，窄差 D196），
 * 匹配面＝{@code label + " " + description}（pi fuzzyFilter 组合串同形）。</p>
 */
public final class ThinkingLevelScreen implements ScreenOverlay {

    /** pi {@code LEVEL_DESCRIPTIONS}（thinking-selector.ts 逐字）。 */
    private static final Map<String, String> DESCRIPTIONS = Map.of(
        "off", "No reasoning",
        "minimal", "Very brief reasoning (~1k tokens)",
        "low", "Light reasoning (~2k tokens)",
        "medium", "Moderate reasoning (~8k tokens)",
        "high", "Deep reasoning (~16k tokens)",
        "xhigh", "Extra-high reasoning (~32k tokens)",
        "max", "Maximum reasoning");

    /** pi {@code DEFAULT_THINKING_LEVEL}（core/defaults.ts）。 */
    private static final String DEFAULT_LEVEL = "medium";

    private final AgentSession session;
    private final KeybindingsManager keys;
    private final IntSupplier terminalCols;
    private final Consumer<String> statusSink;
    private final SelectList<ModelThinkingLevel> list;
    /** ctrl+s 的落项（pi onSelectAsDefault 的选中项快照）。 */
    private ModelThinkingLevel saveTarget;
    private boolean saveAsDefault;

    /**
     * @param session      当前会话（可用集、当前级预选、直设落点）
     * @param keys         键位表（THINKING_SAVE／tui.select.* overlay 臂）
     * @param terminalCols 终端列数源（边框宽）
     * @param statusSink   状态行落点（{@code chatScreen::appendSystemText}）
     */
    public ThinkingLevelScreen(AgentSession session, KeybindingsManager keys,
                               IntSupplier terminalCols, Consumer<String> statusSink) {
        this.session = session;
        this.keys = keys;
        this.terminalCols = terminalCols;
        this.statusSink = statusSink;
        var available = ThinkingCommands.availableLevels(session);
        // 过滤键＝label + " " + description（pi fuzzyFilter 组合串；渲染只画 label）。
        this.list = new SelectList<>(available, lvl -> lvl.label() + " " + description(lvl));
        // 当前级预选（pi 选择器初始选中当前级）。
        var current = session.harness().getThinkingLevel();
        list.selectItem(current::equals);
    }

    /** Handle selector keys; returns true when consumed（ctrl+s 先于导航判定）。 */
    public boolean onKeyEvent(KeyEvent event) {
        var stroke = TamboUIAdapter.toStroke(event);
        if (keys.matches(KeybindingsManager.THINKING_SAVE, stroke)) {
            saveTarget = list.selected().orElse(null);
            saveAsDefault = saveTarget != null;
            return true;
        }
        return list.onKeyEvent(event);
    }

    @Override
    public boolean isDone() {
        return list.confirmed() || list.cancelled() || saveAsDefault;
    }

    @Override
    public void apply(AgentSession target, Consumer<AgentSession> switcher) {
        if (list.cancelled()) {
            return; // Esc：pi 的 onCancel 只关选择器
        }
        if (saveAsDefault) {
            if (saveTarget == null) {
                return;
            }
            // pi selectLevel(level, persist=true)：会话级＋默认设置两份都写。
            this.session.setThinkingLevel(saveTarget);
            this.session.services().settings().accessors()
                .setDefaultThinkingLevel(saveTarget.label());
            statusSink.accept("Default thinking level: " + saveTarget.label());
            return;
        }
        list.selected().ifPresent(level -> {
            this.session.setThinkingLevel(level);
            statusSink.accept("Thinking level: " + level.label());
        });
    }

    // ── 渲染（pi 构造器 :31-66 的子件序逐行落形）────────────────

    @Override
    public Element render() {
        var children = new ArrayList<Element>();
        for (var row : layout()) {
            if (row.isEmpty()) {
                children.add(TamboUIAdapter.spacer(1));
            } else {
                children.add(TamboUIAdapter.richText(Text.from(Line.from(row))));
            }
        }
        return TamboUIAdapter.column(children);
    }

    /** 全部可视行的 span 模型（测试断言面；空行＝空表）。 */
    List<List<Span>> layout() {
        int width = Math.max(1, terminalCols.getAsInt());
        var rows = new ArrayList<List<Span>>();
        rows.add(borderRow(width));
        rows.add(List.of());
        rows.add(List.of(new Span(" Thinking Level", SemanticColors.accent().bold())));
        rows.add(List.of());
        rows.add(cycleHintRow());
        rows.add(List.of());
        rows.add(searchRow());
        rows.add(List.of());
        var items = list.visibleItems();
        int labelWidth = items.stream().mapToInt(lvl -> lvl.label().length()).max().orElse(0);
        int selected = list.selectedIndex();
        for (int i = 0; i < items.size(); i++) {
            rows.add(itemRow(items.get(i), i == selected, labelWidth));
        }
        rows.add(List.of());
        rows.add(hintRow());
        rows.add(borderRow(width));
        return rows;
    }

    /** 级别行：{@code ✓ }／两空格前缀＋label 左列＋两空格＋description 右列（dim）。 */
    private List<Span> itemRow(ModelThinkingLevel level, boolean selected, int labelWidth) {
        var mark = selected ? SemanticColors.accent().bold() : SemanticColors.plain();
        var label = level.label();
        int pad = labelWidth - label.length();
        return List.of(
            new Span(" " + (selected ? "✓ " : "  "), mark),
            new Span(label + " ".repeat(Math.max(0, pad)), mark),
            new Span("  ", SemanticColors.plain()),
            new Span(description(level), SemanticColors.dim()));
    }

    /** pi description：默认级追加 {@code  · default}。 */
    private String description(ModelThinkingLevel level) {
        String label = level.label().toLowerCase(Locale.ROOT);
        String base = DESCRIPTIONS.getOrDefault(label, label);
        return label.equals(defaultLevelLabel()) ? base + " · default" : base;
    }

    /** 设置项 defaultThinkingLevel；缺省（null/空）⇒ {@link #DEFAULT_LEVEL}。 */
    private String defaultLevelLabel() {
        var configured = session.services().settings().accessors().getDefaultThinkingLevel();
        return configured == null || configured.isBlank()
            ? DEFAULT_LEVEL : configured.toLowerCase(Locale.ROOT);
    }

    /** pi {@code "{cycle} cycles thinking levels in-session"}。 */
    private List<Span> cycleHintRow() {
        return List.of(
            new Span(" " + KeybindingHints.displayText(
                keys.strokeFor(KeybindingsManager.THINKING_CYCLE))
                + " cycles thinking levels in-session", SemanticColors.muted()));
    }

    /** 自绘搜索行（D196）：空 query ⇒ 空行；非空 ⇒ {@code " > " + query}。 */
    private List<Span> searchRow() {
        return list.filter().isEmpty()
            ? List.of()
            : List.of(new Span(" > " + list.filter(), SemanticColors.plain()));
    }

    /** 底行逐字：{@code <confirm> to select · <save> to set as default · <cancel> to cancel}。 */
    private List<Span> hintRow() {
        return List.of(
            new Span("   ", SemanticColors.plain()),
            new Span(hintKey(KeybindingsManager.SELECT_CONFIRM), SemanticColors.dim()),
            new Span(" to select · ", SemanticColors.muted()),
            new Span(hintKey(KeybindingsManager.THINKING_SAVE), SemanticColors.dim()),
            new Span(" to set as default · ", SemanticColors.muted()),
            new Span(hintKey(KeybindingsManager.SELECT_CANCEL), SemanticColors.dim()),
            new Span(" to cancel", SemanticColors.muted()));
    }

    private String hintKey(String actionId) {
        return KeybindingHints.keyText(keys.strokeFor(actionId));
    }

    /** pi DynamicBorder：{@code "─".repeat(max(1, width))}，border 色（≈ DIM）。 */
    private static List<Span> borderRow(int width) {
        return List.of(new Span("─".repeat(Math.max(1, width)), SemanticColors.dim()));
    }
}
