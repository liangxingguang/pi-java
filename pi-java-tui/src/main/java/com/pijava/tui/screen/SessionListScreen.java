package com.pijava.tui.screen;

import java.util.Optional;
import java.util.function.Consumer;

import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.session.SessionInfo;
import com.pijava.tui.component.SelectList;

import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyEvent;

/**
 * Session list for {@code /resume} and {@code /session}
 * (Phase 3 design §8.2; in-memory only, persistence in Phase 4).
 */
public final class SessionListScreen implements ScreenOverlay {

    private final SelectList<SessionInfo> list;

    public SessionListScreen(java.util.List<SessionInfo> sessions) {
        this.list = new SelectList<>(sessions,
            info -> info.id().substring(0, 8) + "  " + info.name());
    }

    public boolean onKeyEvent(KeyEvent event) {
        return list.onKeyEvent(event);
    }

    @Override
    public boolean isDone() {
        return list.confirmed() || list.cancelled();
    }

    @Override
    public void apply(AgentSession session, Consumer<AgentSession> switcher) {
        list.selected().ifPresent(chosen ->
            session.findSession(chosen.id()).ifPresent(switcher));
    }

    @Override
    public Element render() {
        return list.render();
    }
}
