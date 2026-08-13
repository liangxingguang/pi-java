package com.pijava.tui.screen;

import java.util.function.Consumer;

import com.pijava.coding.agent.core.AgentSession;

import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyEvent;

/**
 * Common shape of modal overlay screens (model/session/tree/settings)
 * driven by {@code PiTuiApp} (Phase 3 design §8).
 */
public interface ScreenOverlay {

    /** Handle a key event; returns true when consumed. */
    boolean onKeyEvent(KeyEvent event);

    /** Whether the overlay finished (confirmed or cancelled). */
    boolean isDone();

    /** Apply the selection before the overlay closes. */
    void apply(AgentSession session, Consumer<AgentSession> switcher);

    /** Render the overlay content. */
    Element render();
}
