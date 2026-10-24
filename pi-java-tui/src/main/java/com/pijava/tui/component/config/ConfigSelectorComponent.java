package com.pijava.tui.component.config;

import java.util.ArrayList;
import java.util.List;

import com.pijava.coding.agent.core.ConfigResourceWriteBack;
import com.pijava.coding.agent.core.ConfigResourceWriteBack.ConfigItem;
import com.pijava.coding.agent.core.ConfigResourceWriteBack.ProjectOverrideState;
import com.pijava.coding.agent.pkg.SourceScope;
import com.pijava.coding.agent.spi.ConfigSelectorProvider.WriteScope;
import com.pijava.tui.component.EditorComponent;
import com.pijava.tui.component.config.ConfigSelectorModel.FlatEntry;
import com.pijava.tui.component.config.ConfigSelectorModel.ResourceItem;
import com.pijava.tui.theme.SemanticColors;
import com.pijava.tui.util.TamboUIAdapter;

import dev.tamboui.text.Line;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;

/**
 * pi {@code ConfigSelectorComponent}＋{@code ResourceList}（config-selector.ts:
 * 193-951）的 java 落形（批 19 T6，docs/30 §3）——header 两行（:208-225）、键位表
 * （:460-520）、复选框三态渲染（:645-653）＋后缀（:655-661）、滚动指示（:450-455）、
 * toggle 写回经 {@link ConfigResourceWriteBack}。
 *
 * <p>色/粗体走 {@link SemanticColors}（pi theme.fg/bold 对位——D116 同族，终端
 * 真色由主题层翻译）。搜索输入复用 {@link EditorComponent} 单行（"> " 提示符
 * 约定，批 9/14 同手法）。</p>
 */
public final class ConfigSelectorComponent {

    /** pi 的 8 行 chrome（:270-272）。 */
    private static final int CHROME_LINES = 8;

    private final ConfigSelectorModel model;
    private final ConfigResourceWriteBack writeBack;
    private final EditorComponent searchEditor = new EditorComponent();
    private final boolean projectModeAvailable;
    private int maxVisible;

    private Runnable onClose = () -> {};
    private Runnable onExit = () -> {};
    private Runnable onToggle = () -> {};
    private boolean done;

    public ConfigSelectorComponent(ConfigSelectorModel model,
            ConfigResourceWriteBack writeBack, int terminalHeight,
            boolean projectModeAvailable) {
        this.model = model;
        this.writeBack = writeBack;
        this.projectModeAvailable = projectModeAvailable;
        setTerminalHeight(terminalHeight);
    }

    /** pi {@code maxVisible = max(5, rows − 8)}（:270-272）；resize 时更新。 */
    public void setTerminalHeight(int terminalHeight) {
        this.maxVisible = Math.max(5, terminalHeight - CHROME_LINES);
    }

    /** pi 构造注入回调（:889-900）。 */
    public void setCallbacks(Runnable close, Runnable exit, Runnable toggle) {
        this.onClose = close;
        this.onExit = exit;
        this.onToggle = toggle;
    }

    public boolean isDone() {
        return done;
    }

    public ConfigSelectorModel model() {
        return model;
    }

    String searchText() {
        return searchEditor.getText();
    }

    // ── 键位表（pi :460-520 分派序逐字）────────────────────────

    /** 分派一个键事件（选择器全吞）。 */
    public boolean onKeyEvent(KeyEvent event) {
        if (event.isUp()) {
            model.moveSelection(-1);
            return true;
        }
        if (event.isDown()) {
            model.moveSelection(1);
            return true;
        }
        if (event.isPageUp()) {
            model.pageUp(maxVisible);
            return true;
        }
        if (event.isPageDown()) {
            model.pageDown(maxVisible);
            return true;
        }
        if (event.isCancel()) {
            onClose.run();
            done = true;
            return true;
        }
        if (event.isCtrlC()) {
            onExit.run();
            done = true;
            return true;
        }
        if (event.isKey(KeyCode.TAB) && !event.hasShift()) {
            if (projectModeAvailable) {
                model.setWriteScope(model.writeScope() == WriteScope.GLOBAL
                    ? WriteScope.PROJECT : WriteScope.GLOBAL);
            }
            return true;
        }
        if (isSpace(event) || event.isConfirm()) {
            toggleSelected();
            return true;
        }
        // 其余 ⇒ 转发搜索输入框并重过滤（pi :517-519）。
        searchEditor.onKeyEvent(event);
        model.filterWith(searchEditor.getText());
        return true;
    }

    private static boolean isSpace(KeyEvent event) {
        return event.isKey(KeyCode.CHAR) && " ".equals(event.string());
    }

    /** pi toggle 门（:505-513）＋ toggleResource（:522-536）。 */
    private void toggleSelected() {
        var item = model.selectedItem();
        if (item == null) {
            return;
        }
        var projectMode = model.writeScope() == WriteScope.PROJECT;
        if (!projectMode && item.metadata().scope() == SourceScope.PROJECT) {
            return;   // global 模式只能动 user 域条目（pi :507）
        }
        final Boolean newEnabled;
        if (projectMode) {
            newEnabled = writeBack.toggleProject(toConfigItem(item),
                getInheritedEnabled(item), isInheritedGlobalItem(item));
        } else {
            newEnabled = writeBack.toggleGlobal(toConfigItem(item));
        }
        if (newEnabled != null) {
            model.updateItem(item, newEnabled);
            onToggle.run();
        }
    }

    // ── 继承态（pi :782-791）────────────────────────────────────

    private boolean getInheritedEnabled(ResourceItem item) {
        var inherited = model.inheritedEnabledByKey().get(item.itemKey());
        return inherited != null ? inherited
            : item.metadata().scope() != SourceScope.PROJECT ? item.enabled() : true;
    }

    private boolean isInheritedGlobalItem(ResourceItem item) {
        return item.metadata().scope() != SourceScope.PROJECT
            || model.inheritedEnabledByKey().containsKey(item.itemKey());
    }

    private ProjectOverrideState overrideStateOf(ResourceItem item) {
        if (model.writeScope() != WriteScope.PROJECT) {
            return ProjectOverrideState.INHERIT;
        }
        return writeBack.getProjectOverrideState(toConfigItem(item));
    }

    private static ConfigItem toConfigItem(ResourceItem item) {
        return new ConfigItem(item.path(), item.enabled(), item.resourceType(),
            item.metadata());
    }

    // ── 渲染（pi :208-225 header＋:398-458 list）────────────────

    /** 全部可视行的 span 模型（测试断言面）。 */
    public List<List<RowSpan>> layout(int width) {
        var rows = new ArrayList<List<RowSpan>>();
        rows.add(List.of());
        rows.add(borderRow(width));
        rows.add(List.of());
        rows.addAll(headerRows(width));
        rows.add(List.of());
        // 搜索行＋其下空行（pi :402-403；"> " 提示符约定同批 14）
        rows.add(List.of(
            new RowSpan("> ", SemanticColors.ACCENT),
            new RowSpan(searchEditor.getText(), SemanticColors.PLAIN)));
        rows.add(List.of());
        rows.addAll(listRows(width));
        rows.add(List.of());
        rows.add(borderRow(width));
        return rows;
    }

    /** pi {@code ConfigSelectorHeader.render}（:208-225）两行逐字（色对位）。 */
    private List<List<RowSpan>> headerRows(int width) {
        var projectMode = model.writeScope() == WriteScope.PROJECT;
        var title = projectMode ? "Project Local Resources" : "Global Resources";
        var hints = new StringBuilder();
        if (projectModeAvailable) {
            hints.append("tab switch mode · ");
        }
        hints.append(projectMode ? "space cycle inherit/+/- · " : "space toggle · ");
        hints.append("esc close");
        var scopeHint = projectMode
            ? ".pi-java/settings.json · inherited global resources are dimmed"
            : "~/.pi-java/agent/settings.json";
        return List.of(
            twoPartRow(title, hints.toString(), width),
            List.of(new RowSpan(scopeHint, SemanticColors.MUTED)));
    }

    /** pi :215-222：标题左齐＋提示右齐（spacing 夹紧），超宽截断（色裁 D116 同族）。 */
    private static List<RowSpan> twoPartRow(String title, String hint, int width) {
        var spacing = Math.max(1, width - title.length() - hint.length());
        var line = title + " ".repeat(spacing) + hint;
        if (line.length() > width) {
            line = line.substring(0, width);
        }
        return List.of(new RowSpan(line, SemanticColors.PLAIN));
    }

    /** pi ResourceList.render（:398-458）。 */
    private List<List<RowSpan>> listRows(int width) {
        var rows = new ArrayList<List<RowSpan>>();
        var entries = model.filteredItems();
        if (entries.isEmpty()) {
            rows.add(List.of(new RowSpan("  No resources found", SemanticColors.MUTED)));
            return rows;
        }
        var selectedIndex = model.selectedIndex();
        var startIndex = Math.max(0, Math.min(
            selectedIndex - maxVisible / 2, entries.size() - maxVisible));
        var endIndex = Math.min(startIndex + maxVisible, entries.size());
        var projectMode = model.writeScope() == WriteScope.PROJECT;

        for (int i = startIndex; i < endIndex; i++) {
            var entry = entries.get(i);
            var isSelected = i == selectedIndex;
            switch (entry) {
                case FlatEntry.GroupRow row -> {
                    // pi :421-426：project 模式下 user 组 ⇒ dim＋" · inherited global"
                    var inherited = projectMode && row.group().scope() == SourceScope.USER;
                    var label = row.group().label() + (inherited ? " · inherited global" : "");
                    rows.add(List.of(new RowSpan("  " + label,
                        inherited ? SemanticColors.DIM : SemanticColors.ACCENT)));
                }
                case FlatEntry.SubgroupRow row -> {
                    var dim = projectMode && row.group().scope() == SourceScope.USER;
                    rows.add(List.of(new RowSpan("    " + row.subgroup().label(),
                        dim ? SemanticColors.DIM : SemanticColors.MUTED)));
                }
                case FlatEntry.ItemRow row -> rows.add(itemRow(row.item(), isSelected,
                    projectMode, width));
            }
        }
        // pi :450-455：滚动指示（条目序位/总数）。
        if (startIndex > 0 || endIndex < entries.size()) {
            var itemCount = entries.stream().filter(e -> e instanceof FlatEntry.ItemRow).count();
            var currentItem = entries.subList(0, selectedIndex).stream()
                .filter(e -> e instanceof FlatEntry.ItemRow).count() + 1;
            rows.add(List.of(new RowSpan("  (" + currentItem + "/" + itemCount + ")",
                SemanticColors.DIM)));
        }
        return rows;
    }

    /** pi item 行（:432-446）：cursor＋checkbox＋名＋后缀，超宽 … 截断。 */
    private List<RowSpan> itemRow(ResourceItem item, boolean isSelected, boolean projectMode,
            int width) {
        var cursor = isSelected ? "> " : "  ";
        var dimmed = projectMode && isInheritedGlobalItem(item)
            && overrideStateOf(item) == ProjectOverrideState.INHERIT;
        var line = cursor + "    " + checkbox(item, projectMode) + " " + item.displayName()
            + suffix(item, projectMode);
        if (line.length() > width) {
            line = line.substring(0, Math.max(0, width - 3)) + "...";
        }
        var style = dimmed ? SemanticColors.DIM
            : isSelected ? SemanticColors.PLAIN.bold() : SemanticColors.PLAIN;
        return List.of(new RowSpan(line, style));
    }

    /** pi {@code renderCheckbox}（:645-653）逐字。 */
    private String checkbox(ResourceItem item, boolean projectMode) {
        if (projectMode) {
            return switch (overrideStateOf(item)) {
                case LOAD -> "[+]";
                case UNLOAD -> "[-]";
                case INHERIT -> item.enabled() ? "[x]" : "[ ]";
            };
        }
        return item.enabled() ? "[x]" : "[ ]";
    }

    /** pi {@code getItemSuffix}（:655-661）逐字。 */
    private String suffix(ResourceItem item, boolean projectMode) {
        if (!projectMode) {
            return "";
        }
        return switch (overrideStateOf(item)) {
            case LOAD -> "  project load";
            case UNLOAD -> "  project unload";
            case INHERIT -> isInheritedGlobalItem(item) ? "  inherited global" : "";
        };
    }

    /** pi DynamicBorder 对位（批 10/14 同形）：accent 色全宽横线。 */
    private static List<RowSpan> borderRow(int width) {
        return List.of(new RowSpan("─".repeat(Math.max(1, width)), SemanticColors.ACCENT));
    }

    /** span 模型 → TamboUI Element（render 只翻译，不另建行形——批 14 同手法）。 */
    public Element render(int width) {
        var children = new ArrayList<Element>();
        for (var row : layout(width)) {
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

    /** 行内 span（文本＋样式）——测试断言面。 */
    public record RowSpan(String text, dev.tamboui.style.Style style) {}
}
