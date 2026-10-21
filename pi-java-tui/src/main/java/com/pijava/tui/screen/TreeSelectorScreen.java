package com.pijava.tui.screen;

import java.util.function.Consumer;

import com.pijava.agent.harness.SessionSnapshot;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.tui.component.SelectList;

import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyEvent;

/**
 * Session-tree selector for {@code /tree} and {@code /fork}
 * (Phase 3 design §8.3; rich tree filtering lands Phase 6).
 *
 * <p>docs/18（§3.10）双模式：<b>fork</b>（{@code /fork} 空参，旧行为——选中＝
 * {@code forkFromEntry}）与 <b>navigate</b>（{@code /tree}——不动会话，把选中
 * entryId 交给 {@code TreeNavigationFlow} 对位的回调）。列表形态仍是 lane 名
 * ＋leafId（pi 的 entry 树选择器本体是 map/05 S12 存疑项，窄差 D37）。</p>
 */
public final class TreeSelectorScreen implements ScreenOverlay {

    private final SelectList<String> list;
    private final Consumer<String> onNavigateSelected;
    private final Runnable onNavigateCancelled;

    /**
     * Creates the tree selector listing the session's lanes (fork mode).
     *
     * @param snapshot the snapshot whose lanes are listed
     */
    public TreeSelectorScreen(SessionSnapshot snapshot) {
        this(snapshot, null, null, null);
    }

    /**
     * Creates the selector in navigate mode（{@code /tree}）。
     *
     * @param snapshot           the snapshot whose lanes are listed
     * @param initialSelectedId 重开时恢复选中的 entryId（pi {@code initialSelectedId}
     *                          对位：lane 行按 leafId 相等定位，缺省 0，窄差 D40）
     * @param onSelected         选中行的 entryId（lane 叶）
     * @param onCancelled        Esc 取消
     */
    public TreeSelectorScreen(SessionSnapshot snapshot, String initialSelectedId,
                              Consumer<String> onSelected, Runnable onCancelled) {
        this.onNavigateSelected = onSelected;
        this.onNavigateCancelled = onCancelled;
        var lanes = snapshot.lanes().stream()
            .map(lane -> lane.name() + (lane.leafId() == null ? "" : " @" + lane.leafId()))
            .sorted()
            .toList();
        this.list = new SelectList<>(lanes, s -> s);
        if (initialSelectedId != null) {
            list.selectItem(row -> row.endsWith(" @" + initialSelectedId));
        }
    }

    /** Handle selector keys; returns true when consumed. */
    public boolean onKeyEvent(KeyEvent event) {
        return list.onKeyEvent(event);
    }

    @Override
    public boolean isDone() {
        return list.confirmed() || list.cancelled();
    }

    /** 当前选中行的 entryId（navigate 模式与测试探针用；无选中/无叶 ⇒ null）。 */
    public String selectedEntryId() {
        return list.selected()
            .map(row -> {
                var parts = row.split(" @", 2);
                return parts.length < 2 ? null : parts[1];
            })
            .orElse(null);
    }

    @Override
    public void apply(AgentSession session, Consumer<AgentSession> switcher) {
        if (onNavigateSelected != null) {
            // navigate 模式：不动会话，把结局交给编排者。
            if (list.cancelled()) {
                onNavigateCancelled.run();
                return;
            }
            var entryId = selectedEntryId();
            if (entryId != null) {
                onNavigateSelected.accept(entryId);
            }
            return;
        }
        // 选中一行 = 从该分支点**分支**出去 —— 分支是会话层的事（原 docs/31 §4.3）：
        // 新会话持自己的 harness，日志在 entry 前截断后播种。
        //
        // 此前这里是 createLane(选中的名字)，而列表本来就来自现存分支的名字 ——
        // 那条路对任何真实分支都直接抛 LaneExistsException，实际从未生效过。
        list.selected().ifPresent(selection -> {
            var parts = selection.split(" @", 2);
            if (parts.length < 2) {
                return;                       // 空分支：无 entry 可分支
            }
            // docs/15：session_before_fork cancel ⇒ empty，不切换。
            session.forkFromEntry(parts[1]).ifPresent(switcher);
        });
    }

    @Override
    public Element render() {
        return list.render();
    }
}
