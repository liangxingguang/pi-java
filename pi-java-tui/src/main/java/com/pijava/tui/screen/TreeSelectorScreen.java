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
        // Phase 3: lane navigation is minimal — the selection is reported as a
        // system bubble; rich tree navigation arrives Phase 6.
        list.selected().ifPresent(selection ->
            session.harness().createLane(com.pijava.agent.harness.LaneConfig.of(
                selection.split(" @", 2)[0])));
    }

    @Override
    public Element render() {
        return list.render();
    }
}
