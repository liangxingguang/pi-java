package com.pijava.coding.agent.core.slash.builtin;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.pijava.coding.agent.core.slash.CommandRegistry;
import com.pijava.coding.agent.core.slash.SlashCommand;
import com.pijava.coding.agent.core.slash.SlashContext;

/**
 * Settings and trust slash commands (Phase 3 design §14.2
 * #1/#15/#21).
 */
public final class SettingsCommands {

    private SettingsCommands() {}

    /** Register {@code /settings /trust /reload}. */
    public static void registerAll(CommandRegistry registry) {
        registry.register(new SlashCommand() {
            @Override public String name() { return "settings"; }
            @Override public String description() { return "Open settings"; }
            @Override public String argumentHint() { return ""; }
            @Override public CompletionStage<String> execute(String args, SlashContext ctx) {
                return CompletableFuture.completedFuture(CommandRegistry.UI_SETTINGS);
            }
        });
        registry.register(new SlashCommand() {
            @Override public String name() { return "trust"; }
            @Override public String description() { return "Save project trust decision"; }
            @Override public String argumentHint() { return "always|never|ask"; }
            @Override public CompletionStage<String> execute(String args, SlashContext ctx) {
                var value = args.trim().toLowerCase();
                if (!value.equals("always") && !value.equals("never")
                        && !value.equals("ask")) {
                    return CompletableFuture.completedFuture(
                        "Usage: /trust always|never|ask");
                }
                var trust = ctx.session().services().trust();
                trust.setDefaultTrust(value);
                trust.trust(Path.of(System.getProperty("user.dir")),
                    value.equals("always"));
                var accessors = ctx.session().services().settings().accessors();
                accessors.setDefaultProjectTrust(value);
                ctx.session().services().settings().flush();
                return CompletableFuture.completedFuture(
                    "Project trust set to \"" + value
                        + "\" (persistence in Phase 4)");
            }
        });
        registry.register(new SlashCommand() {
            @Override public String name() { return "reload"; }
            @Override public String description() {
                return "Reload settings and keybindings";
            }
            @Override public String argumentHint() { return ""; }
            @Override public CompletionStage<String> execute(String args, SlashContext ctx) {
                ctx.session().services().settings().reload();
                return CompletableFuture.completedFuture(
                    "Reloaded settings (extensions/skills/prompts in Phase 6)");
            }
        });
    }
}
