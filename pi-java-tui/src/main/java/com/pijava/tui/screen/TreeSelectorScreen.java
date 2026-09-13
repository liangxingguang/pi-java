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
 */
public final class TreeSelectorScreen implements ScreenOverlay {

    private final SelectList<String> list;

    /**
     * Creates the tree selector listing the session's lanes.
     *
     * @param snapshot the snapshot whose lanes are listed
     */
    public TreeSelectorScreen(SessionSnapshot snapshot) {
        var lanes = snapshot.lanes().stream()
            .map(lane -> lane.name() + (lane.leafId() == null ? "" : " @" + lane.leafId()))
            .sorted()
            .toList();
        this.list = new SelectList<>(lanes, s -> s);
    }

    /** Handle selector keys; returns true when consumed. */
    public boolean onKeyEvent(KeyEvent event) {
        return list.onKeyEvent(event);
    }

    @Override
    public boolean isDone() {
        return list.confirmed() || list.cancelled();
    }

    @Override
    public void apply(AgentSession session, Consumer<AgentSession> switcher) {
        // 选中一行 = 从该分支点**分支**出去 —— 分支是会话层的事（docs/31 §4.3）：
        // 新会话持自己的 harness，日志在 entry 前截断后播种。
        //
        // 此前这里是 createLane(选中的名字)，而列表本来就来自现存分支的名字 ——
        // 那条路对任何真实分支都直接抛 LaneExistsException，实际从未生效过。
        list.selected().ifPresent(selection -> {
            var parts = selection.split(" @", 2);
            if (parts.length < 2) {
                return;                       // 空分支：无 entry 可分支
            }
            switcher.accept(session.forkFromEntry(parts[1]));
        });
    }

    @Override
    public Element render() {
        return list.render();
    }
}
