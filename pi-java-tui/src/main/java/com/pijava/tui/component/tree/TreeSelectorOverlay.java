package com.pijava.tui.component.tree;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.core.SessionTreeNode;
import com.pijava.tui.component.EditorComponent;
import com.pijava.tui.screen.ScreenOverlay;
import com.pijava.tui.theme.SemanticColors;
import com.pijava.tui.util.TamboUIAdapter;

import dev.tamboui.text.Line;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;

/**
 * pi {@code TreeSelectorComponent}（tree-selector.ts:1336-1435）的 java 落形
 * （docs/19 §T4）：entry 树选择器 overlay（navigate 模式；{@code /fork} 的
 * lane 平表留在旧 {@code TreeSelectorScreen}，裁决⑥/B254）。
 *
 * <p>组合：粗体标题 {@code "  Session Tree"}／帮助行／搜索行／树区（或
 * label 编辑器）。键分派严格按 pi handleInput（:1004-1110）的分派序：
 * app.tree.*／app.message.copy 经 {@link KeybindingsManager#matches} 查表，
 * tui.select.* 层沿 SelectList 纪律直配 {@link KeyEvent} helper。结局：
 * Enter ⇒ confirmed＋entryId（{@code apply} 交回调，不动会话）；Esc/ctrl+c
 * ⇒ 先清搜索（pi 第一级），再 cancelled。</p>
 */
public final class TreeSelectorOverlay implements ScreenOverlay {

    private final TreeListModel model;
    private final TreeListRenderer renderer = new TreeListRenderer();
    private final IntSupplier terminalCols;
    private final Consumer<String> onSelect;
    private final Runnable onCancel;
    private final BiConsumer<String, String> onLabelChange;
    private final Consumer<String> onCopy;
    private final KeybindingsManager keybindings;

    private boolean confirmed;
    private boolean cancelled;
    private String selectedId;

    // label 编辑臂（pi LabelInput :1279-1331）
    private EditorComponent labelEditor;
    private String labelEntryId;

    /**
     * @param tree              entry 树（{@code AgentSession.getTree()}；空树不
     *                          开——宿主先查，pi :5563-5566）
     * @param currentLeafId     当前叶（活动路径锚点）
     * @param terminalRows      终端行数（maxVisibleLines = max(5, rows/2)，pi :1370）
     * @param initialSelectedId 恢复选中（pi showTreeSelector(entryId)）
     * @param initialFilterMode settings 读入的初值过滤（选择器内切换不回写）
     * @param onSelect          定案回调（entryId；apply 时触发）
     * @param onCancel          取消回调（apply 时触发）
     * @param onLabelChange     label 持久化回调（(entryId, label|null)，编辑 submit 即触发）
     * @param onCopy            复制回调（文本或 null——宿主给状态反馈，pi :5595-5607）
     * @param keybindings       键位表（app.tree.*／app.message.copy）
     * @param terminalCols      终端列数（水平视口宽度源）
     */
    public TreeSelectorOverlay(List<SessionTreeNode> tree, String currentLeafId,
                               int terminalRows, String initialSelectedId,
                               FilterMode initialFilterMode,
                               Consumer<String> onSelect, Runnable onCancel,
                               BiConsumer<String, String> onLabelChange,
                               Consumer<String> onCopy,
                               KeybindingsManager keybindings,
                               IntSupplier terminalCols) {
        this.model = new TreeListModel(tree, currentLeafId,
            Math.max(5, terminalRows / 2), initialSelectedId, initialFilterMode);
        this.terminalCols = terminalCols;
        this.onSelect = onSelect;
        this.onCancel = onCancel;
        this.onLabelChange = onLabelChange;
        this.onCopy = onCopy;
        this.keybindings = keybindings;
    }

    /** 模型探针（测试/宿主只读）。 */
    TreeListModel model() {
        return model;
    }

    /** label 编辑臂是否激活。 */
    boolean isEditingLabel() {
        return labelEditor != null;
    }

    // ── 键分派（pi handleInput :1004-1110 分派序逐字）─────────

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        if (isEditingLabel()) {
            return onLabelEditorKey(event);
        }
        var stroke = TamboUIAdapter.toStroke(event);
        if (event.isUp()) {
            model.moveUp();
        } else if (event.isDown()) {
            model.moveDown();
        } else if (keybindings.matches(KeybindingsManager.TREE_FOLD_OR_UP, stroke)) {
            model.foldOrUp();
        } else if (keybindings.matches(KeybindingsManager.TREE_UNFOLD_OR_DOWN, stroke)) {
            model.unfoldOrDown();
        } else if (event.isLeft() || event.isPageUp()) {
            model.pageUp();
        } else if (event.isRight() || event.isPageDown()) {
            model.pageDown();
        } else if (event.isConfirm()) {
            String id = model.selectedEntryId();
            if (id != null) {
                selectedId = id;
                confirmed = true;
            }
        } else if (keybindings.matches(KeybindingsManager.MESSAGE_COPY, stroke)) {
            onCopy.accept(model.selectedCopyText());
        } else if (event.isCancel() || event.isCtrlC()) {
            // pi :1039-1046：有搜索串先清搜索＋清折叠，否则 onCancel。
            if (!model.clearSearchIfAny()) {
                cancelled = true;
            }
        } else if (keybindings.matches(KeybindingsManager.TREE_FILTER_DEFAULT, stroke)) {
            model.setFilter(FilterMode.DEFAULT);
        } else if (keybindings.matches(KeybindingsManager.TREE_FILTER_NO_TOOLS, stroke)) {
            toggleFilter(FilterMode.NO_TOOLS);
        } else if (keybindings.matches(KeybindingsManager.TREE_FILTER_USER_ONLY, stroke)) {
            toggleFilter(FilterMode.USER_ONLY);
        } else if (keybindings.matches(KeybindingsManager.TREE_FILTER_LABELED, stroke)) {
            toggleFilter(FilterMode.LABELED_ONLY);
        } else if (keybindings.matches(KeybindingsManager.TREE_FILTER_ALL, stroke)) {
            toggleFilter(FilterMode.ALL);
        } else if (keybindings.matches(KeybindingsManager.TREE_FILTER_CYCLE_BACKWARD, stroke)) {
            model.cycleFilter(false);
        } else if (keybindings.matches(KeybindingsManager.TREE_FILTER_CYCLE_FORWARD, stroke)) {
            model.cycleFilter(true);
        } else if (event.isKey(KeyCode.BACKSPACE)) {
            model.backspaceSearch();
        } else if (keybindings.matches(KeybindingsManager.TREE_EDIT_LABEL, stroke)) {
            startLabelEdit();
        } else if (keybindings.matches(KeybindingsManager.TREE_TOGGLE_LABEL_TIMESTAMP, stroke)) {
            model.toggleLabelTimestamps();
        } else if (event.code() == KeyCode.CHAR && !event.hasCtrl() && !event.hasAlt()
                && !Character.isISOControl(event.codePoint())) {
            // pi :1099-1108：其余可打印字符 ⇒ 追加搜索串。
            model.appendSearch(event.string());
        }
        return true;
    }

    /** pi 互切臂（:1052-1071）：非 default 键再按回 default。 */
    private void toggleFilter(FilterMode mode) {
        model.setFilter(model.filterMode() == mode ? FilterMode.DEFAULT : mode);
    }

    // ── label 编辑臂（pi LabelInput :1295-1331）───────────────

    private void startLabelEdit() {
        var node = model.selectedNode();
        if (node == null) {
            return;
        }
        labelEntryId = node.entry().id();
        labelEditor = new EditorComponent();
        if (node.label() != null) {
            labelEditor.setText(node.label());
        }
    }

    private boolean onLabelEditorKey(KeyEvent event) {
        if (event.isConfirm()) {
            // pi :1322-1324：trim 后空串 ⇒ undefined（清除）。
            String value = labelEditor.getText().trim();
            String label = value.isEmpty() ? null : value;
            // pi :1402-1406：本地即时反映＋宿主持久化回调。
            model.updateNodeLabel(labelEntryId, label, Instant.now());
            onLabelChange.accept(labelEntryId, label);
            labelEditor = null;
            labelEntryId = null;
            return true;
        }
        if (event.isCancel() || event.isCtrlC()) {
            labelEditor = null;   // pi :1325-1326：取消只换回，不动 label
            labelEntryId = null;
            return true;
        }
        labelEditor.onKeyEvent(event);
        return true;
    }

    // ── 结局（批 8 §3.10 navigate 合同原样）───────────────────

    @Override
    public boolean isDone() {
        return confirmed || cancelled;
    }

    @Override
    public void apply(AgentSession session, Consumer<AgentSession> switcher) {
        // apply 不动会话：entryId 交回调（导航编排由宿主流负责）。
        if (confirmed) {
            onSelect.accept(selectedId);
        } else {
            onCancel.run();
        }
    }

    // ── 渲染（pi 容器层 :1383-1393 的列堆叠落形）──────────────

    @Override
    public Element render() {
        int width = Math.max(20, terminalCols.getAsInt());
        var children = new ArrayList<Element>();
        children.add(richLine(List.of(
            new TreeListRenderer.Span("  Session Tree", SemanticColors.PLAIN.bold()))));
        for (var helpLine : TreeHelpLine.render(width)) {
            children.add(richLine(List.of(new TreeListRenderer.Span(helpLine, SemanticColors.MUTED))));
        }
        // pi SearchLine（:1174-1180）：查询串 accent。
        var searchSpans = new ArrayList<TreeListRenderer.Span>();
        searchSpans.add(new TreeListRenderer.Span("  Type to search:", SemanticColors.MUTED));
        if (!model.searchQuery().isEmpty()) {
            searchSpans.add(new TreeListRenderer.Span(" " + model.searchQuery(), SemanticColors.ACCENT));
        }
        children.add(richLine(searchSpans));

        if (isEditingLabel()) {
            // pi LabelInput.render（:1305-1317）逐字。
            children.add(richLine(List.of(
                new TreeListRenderer.Span("  Label (empty to remove):", SemanticColors.MUTED))));
            children.add(labelEditor.render());
            children.add(richLine(List.of(
                new TreeListRenderer.Span("  enter save  esc cancel", SemanticColors.MUTED))));
        } else {
            var rows = renderer.buildRows(model);
            if (rows.isEmpty()) {
                children.add(richLine(List.of(
                    new TreeListRenderer.Span(TreeListRenderer.emptyLine(), SemanticColors.MUTED))));
            } else {
                for (var lineSpans : renderer.viewport(rows, width)) {
                    children.add(richLine(lineSpans));
                }
            }
            children.add(richLine(List.of(
                new TreeListRenderer.Span(renderer.statusLine(model), SemanticColors.MUTED))));
        }
        return TamboUIAdapter.column(children);
    }

    /** span 行 → TamboUI Element（renderer span → tamboui span 直映射）。 */
    private static Element richLine(List<? extends TreeListRenderer.Span> spans) {
        return TamboUIAdapter.richText(Text.from(Line.from(spans.stream()
            .map(s -> new dev.tamboui.text.Span(s.text(), s.style()))
            .toList())));
    }

}
