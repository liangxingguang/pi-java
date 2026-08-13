package com.pijava.coding.agent.core.slash;

import java.util.concurrent.CompletionStage;

/**
 * A slash command (aligned with pi's {@code BuiltinSlashCommand}; Phase 3
 * design §14.1).
 *
 * <p>{@code name}/{@code description}/{@code argumentHint} provide the
 * metadata shown by {@code /hotkeys} and help; {@link #execute} is the
 * pi-java runtime entry point (pi inlines dispatch in interactive-mode).</p>
 */
public interface SlashCommand {

    /** Command name without the leading slash (e.g. {@code "model"}). */
    String name();

    /** One-line description for {@code /hotkeys}. */
    String description();

    /** Optional argument hint (e.g. {@code "<model-id>"}); empty if none. */
    String argumentHint();

    /**
     * Execute the command.
     *
     * @param args    text after the command name (may be empty)
     * @param context session/settings/trust/exit references
     * @return result text, or a UI marker constant from {@link CommandRegistry}
     *         when the command opens a selector
     */
    CompletionStage<String> execute(String args, SlashContext context);
}
