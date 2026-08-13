package com.pijava.coding.agent.core.slash;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.pijava.coding.agent.core.slash.builtin.MiscCommands;
import com.pijava.coding.agent.core.slash.builtin.ModelCommands;
import com.pijava.coding.agent.core.slash.builtin.SessionCommands;
import com.pijava.coding.agent.core.slash.builtin.SettingsCommands;

/**
 * Slash command registry (Phase 3 design §14.1).
 *
 * <p>Commands that open TUI selectors return a {@code "@ui:*"} marker string;
 * the TUI layer interprets these markers and opens the corresponding screen.
 * An input that does not start with {@code "/"} is not a command —
 * {@link #dispatch} returns {@code null} so callers treat it as a prompt.</p>
 */
public final class CommandRegistry {

    // UI marker constants returned by selector-opening commands
    public static final String UI_MODEL_SELECTOR = "@ui:model-selector";
    public static final String UI_SESSION_SELECTOR = "@ui:session-selector";
    public static final String UI_TREE_SELECTOR = "@ui:tree-selector";
    public static final String UI_SETTINGS = "@ui:settings";
    public static final String UI_SCOPED_MODELS = "@ui:scoped-models";

    private final ConcurrentMap<String, SlashCommand> commands = new ConcurrentHashMap<>();

    /** Register a command by name (replaces an existing registration). */
    public void register(SlashCommand command) {
        commands.put(command.name(), command);
    }

    /** Remove a command by name. */
    public void unregister(String name) {
        commands.remove(name);
    }

    /** List all registered command names (sorted for stable output). */
    public List<String> names() {
        return commands.keySet().stream().sorted().toList();
    }

    /** Look up a command by name. */
    public SlashCommand get(String name) {
        return commands.get(name);
    }

    /**
     * Match and execute a slash command.
     *
     * @param input   full input line
     * @param context command context
     * @return result future, or {@code null} when the input is not a command
     */
    public CompletionStage<String> dispatch(String input, SlashContext context) {
        if (input == null || !input.startsWith("/")) {
            return null;
        }
        var parts = input.substring(1).split("\\s+", 2);
        var command = commands.get(parts[0]);
        if (command == null) {
            return CompletableFuture.completedFuture(
                "Unknown command: /" + parts[0]
                    + " (try /hotkeys for the list of commands)");
        }
        return command.execute(parts.length > 1 ? parts[1] : "", context);
    }

    /** Registry with the 22 built-in commands (Phase 3 design §14.2). */
    public static CommandRegistry withBuiltins() {
        var registry = new CommandRegistry();
        ModelCommands.registerAll(registry);
        SessionCommands.registerAll(registry);
        SettingsCommands.registerAll(registry);
        MiscCommands.registerAll(registry);
        return registry;
    }
}
