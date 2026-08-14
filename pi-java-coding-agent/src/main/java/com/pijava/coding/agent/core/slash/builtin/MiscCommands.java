package com.pijava.coding.agent.core.slash.builtin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.ai.auth.FileCredentialStore;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.core.slash.CommandRegistry;
import com.pijava.coding.agent.core.slash.SlashCommand;
import com.pijava.coding.agent.core.slash.SlashContext;
import static com.pijava.coding.agent.core.slash.builtin.CommandUtil.simple;

/**
 * Miscellaneous slash commands (Phase 3 design §14.2
 * #4/#5/#6/#7/#10/#11/#16/#17/#19/#22).
 */
public final class MiscCommands {

    private MiscCommands() {}

    private static final String CHANGELOG = """
        pi-java 0.1.0-SNAPSHOT (Phase 3)
        - CLI: pi-java [options] [@files...] [messages...]
        - Interactive TUI with streaming bubbles, slash commands, settings
        - Print mode: pi-java -p "prompt"
        Phase 4: persistent sessions (SQLite/JSONL). Phase 6: extensions, RPC.
        """;

    /** Register the remaining 10 commands. */
    public static void registerAll(CommandRegistry registry) {
        registry.register(simple("help", "Show slash commands and shortcuts", "",
            (args, ctx) -> ctx.keybindings() == null
                ? registry.helpText()
                : registry.helpText() + "\n\n" + hotkeys(ctx)));
        registry.register(placeholder("export",
            "Export session (HTML/JSONL)", "[path]",
            "HTML export is not implemented yet (Phase 6)."));
        registry.register(placeholder("import",
            "Import session from JSONL", "<file>",
            "JSONL import is not implemented yet (Phase 4)."));
        registry.register(placeholder("share",
            "Share session as GitHub gist", "",
            "Session sharing is not implemented yet (Phase 6)."));
        registry.register(new SlashCommand() {
            @Override public String name() { return "copy"; }
            @Override public String description() {
                return "Copy last agent message";
            }
            @Override public String argumentHint() { return ""; }
            @Override public CompletionStage<String> execute(String args, SlashContext ctx) {
                var lastAssistant = ctx.session().lastAssistantText();
                if (lastAssistant == null) {
                    return CompletableFuture.completedFuture(
                        "No assistant message to copy yet.");
                }
                try {
                    var clipboard = java.awt.Toolkit.getDefaultToolkit()
                        .getSystemClipboard();
                    clipboard.setContents(
                        new java.awt.datatransfer.StringSelection(lastAssistant), null);
                    return CompletableFuture.completedFuture("Copied.");
                } catch (Exception headless) {
                    // Headless environments fall back to printing the text.
                    return CompletableFuture.completedFuture(
                        "Clipboard unavailable; last message:\n" + lastAssistant);
                }
            }
        });
        registry.register(simple("changelog", "Show changelog", "",
            (args, ctx) -> CHANGELOG));
        registry.register(simple("hotkeys", "Show all keyboard shortcuts", "",
            (args, ctx) -> hotkeys(ctx)));
        registry.register(simple("compact", "Compact context manually", "",
            (args, ctx) -> {
                try {
                    ctx.session().compact(CompactionSettings.defaults());
                    return "Compacted.";
                } catch (Exception e) {
                    return "Compaction failed: " + e.getMessage();
                }
            }));
        registry.register(new SlashCommand() {
            @Override public String name() { return "login"; }
            @Override public String description() { return "Configure provider auth"; }
            @Override public String argumentHint() { return "<provider>"; }
            @Override public CompletionStage<String> execute(String args, SlashContext ctx) {
                if (args.isBlank()) {
                    return CompletableFuture.completedFuture(
                        "Usage: /login <provider> (anthropic|openai|google|deepseek|mistral)");
                }
                var console = System.console();
                if (console == null) {
                    return CompletableFuture.completedFuture(
                        "No console available. Set the environment variable "
                            + "or run: pi-java auth " + args.trim());
                }
                var key = new String(console.readPassword(
                    "API key for %s: ", args.trim()));
                if (key.isBlank()) {
                    return CompletableFuture.completedFuture("Aborted.");
                }
                new FileCredentialStore().storeApiKey(args.trim(), key);
                return CompletableFuture.completedFuture(
                    "API key saved for " + args.trim() + ".");
            }
        });
        registry.register(simple("logout", "Remove provider auth", "<provider>",
            (args, ctx) -> {
                if (args.isBlank()) {
                    return "Usage: /logout <provider>";
                }
                new FileCredentialStore().deleteApiKey(args.trim());
                return "API key removed for " + args.trim() + ".";
            }));
        registry.register(new SlashCommand() {
            @Override public String name() { return "quit"; }
            @Override public String description() { return "Exit"; }
            @Override public String argumentHint() { return ""; }
            @Override public CompletionStage<String> execute(String args, SlashContext ctx) {
                ctx.onQuit().run();
                return CompletableFuture.completedFuture("Bye.");
            }
        });
    }

    private static String hotkeys(SlashContext ctx) {
        var keys = ctx.keybindings();
        var builder = new StringBuilder("Keybindings:\n");
        for (var actionId : keys.actionIds()) {
            var stroke = keys.strokeFor(actionId);
            builder.append("  ")
                .append(actionId.replaceFirst("^app\\.", ""))
                .append("  ")
                .append(describe(stroke))
                .append('\n');
        }
        return builder.toString();
    }

    private static String describe(KeybindingsManager.KeyStroke stroke) {
        var builder = new StringBuilder();
        if (stroke.ctrl()) builder.append("Ctrl+");
        if (stroke.alt()) builder.append("Alt+");
        if (stroke.shift()) builder.append("Shift+");
        builder.append(stroke.key().toUpperCase());
        return builder.toString();
    }

    private static SlashCommand placeholder(String name, String description,
                                            String hint, String message) {
        return simple(name, description, hint, (args, ctx) -> message);
    }
}
