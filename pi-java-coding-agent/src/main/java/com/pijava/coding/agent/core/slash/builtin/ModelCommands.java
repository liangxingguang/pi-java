package com.pijava.coding.agent.core.slash.builtin;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.pijava.coding.agent.core.slash.CommandRegistry;
import com.pijava.coding.agent.core.slash.SlashCommand;
import com.pijava.coding.agent.core.slash.SlashContext;

/**
 * Model-related slash commands (Phase 3 design §14.2 #2/#3).
 */
public final class ModelCommands {

    private ModelCommands() {}

    /** Register {@code /model} and {@code /scoped-models}. */
    public static void registerAll(CommandRegistry registry) {
        registry.register(new SlashCommand() {
            @Override public String name() { return "model"; }
            @Override public String description() { return "Select model"; }
            @Override public String argumentHint() { return ""; }
            @Override public CompletionStage<String> execute(String args, SlashContext context) {
                return CompletableFuture.completedFuture(CommandRegistry.UI_MODEL_SELECTOR);
            }
        });
        registry.register(new SlashCommand() {
            @Override public String name() { return "scoped-models"; }
            @Override public String description() {
                return "Enable/disable Ctrl+P cycling models";
            }
            @Override public String argumentHint() { return "[+|-]<model>"; }
            @Override public CompletionStage<String> execute(String args, SlashContext context) {
                var accessors = context.session().services().settings().accessors();
                if (args.isBlank()) {
                    return CompletableFuture.completedFuture(CommandRegistry.UI_SCOPED_MODELS);
                }
                var current = new ArrayList<>(accessors.getEnabledModels());
                if (args.startsWith("+")) {
                    current.add(args.substring(1).trim());
                } else if (args.startsWith("-")) {
                    current.remove(args.substring(1).trim());
                } else {
                    current.add(args.trim());
                }
                accessors.setEnabledModels(current);
                context.session().services().settings().flush();
                return CompletableFuture.completedFuture(
                    "Enabled models: " + current);
            }
        });
    }
}
