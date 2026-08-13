package com.pijava.coding.agent.core.slash;

import java.util.function.Consumer;

import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.KeybindingsManager;

/**
 * Runtime references passed to {@link SlashCommand#execute} (Phase 3
 * design §14.1).
 *
 * @param session           the active session (settings/trust reachable via it)
 * @param keybindings       application keybindings for {@code /hotkeys}
 * @param onQuit            invoked by {@code /quit} to stop the TUI
 * @param onSwitchSession   invoked by {@code /new /resume /fork /clone} to swap
 *                          the interactive session
 */
public record SlashContext(
    AgentSession session,
    KeybindingsManager keybindings,
    Runnable onQuit,
    Consumer<AgentSession> onSwitchSession
) {
    /** Minimal context with no-op callbacks (unit tests). */
    public static SlashContext of(AgentSession session) {
        return new SlashContext(session, new KeybindingsManager(),
            () -> { }, ignored -> { });
    }
}
